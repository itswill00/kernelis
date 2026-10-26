package dev.kernelis.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kernelis.core.telemetry.TelemetryCollector
import dev.kernelis.core.telemetry.TelemetryFrame
import dev.kernelis.domain.CapabilityService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the dashboard: capability state plus a rolling telemetry window.
 *
 * Sampling is lifecycle-aware: the poller starts only in [resume] and is cancelled in
 * [pause]. The cadence is deliberately conservative (1.5 s) — a kernel monitor that
 * drains the battery to display its own dashboard would be self-defeating. Storage is
 * re-read only every 8th frame; CPU, memory and thermal come from each frame's single
 * batched read.
 *
 * A failed frame is skipped, not fabricated: charts show a gap rather than zero.
 */
@HiltViewModel
public class DashboardViewModel @Inject constructor(
    private val capabilityService: CapabilityService,
    private val collector: TelemetryCollector,
) : ViewModel() {

    public data class UiState(
        val capabilityState: CapabilityService.State = CapabilityService.State.Idle,
        val frame: TelemetryFrame? = null,
        /** Rolling windows for the charts (newest last). Nulls render as gaps. */
        val loadHistory: List<Double?> = emptyList(),
        val freqHistory: List<Double?> = emptyList(),
        val ramHistory: List<Double?> = emptyList(),
        val tempHistory: List<Double?> = emptyList(),
        val rootProviderLabel: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    public val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null
    private var frameCount = 0

    public companion object {
        /** Interval between frames. Slower than "live" on purpose, for battery kindness. */
        public const val POLL_INTERVAL_MS: Long = 1_500
        public const val WINDOW_SIZE: Int = 60

        /** Storage stats change slowly; they are refreshed less often. */
        public const val STORAGE_EVERY_N_FRAMES: Int = 8
    }

    init {
        viewModelScope.launch {
            capabilityService.state.collect { state ->
                _uiState.value = _uiState.value.copy(
                    capabilityState = state,
                    rootProviderLabel = (state as? CapabilityService.State.Ready)?.snapshot?.rootProvider
                        ?: _uiState.value.rootProviderLabel,
                )
            }
        }
    }

    public fun startScan() {
        viewModelScope.launch { capabilityService.refresh(force = false) }
    }

    public fun rescan() {
        viewModelScope.launch { capabilityService.refresh(force = true) }
    }

    /** Call from ON_RESUME. Idempotent. */
    public fun resume() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                sample()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Call from ON_PAUSE. */
    public fun pause() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun sample() {
        val snapshot = capabilityService.current ?: return
        val includeStorage = frameCount % STORAGE_EVERY_N_FRAMES == 0
        val frame = try {
            collector.collect(snapshot, includeStorage = includeStorage)
        } catch (_: Throwable) {
            null
        } ?: return

        frameCount++
        if (frame.storage != null) collector.rememberStorage(frame.storage)

        _uiState.value = _uiState.value.copy(
            frame = frame,
            loadHistory = append(_uiState.value.loadHistory, frame.cpu.totalLoad),
            freqHistory = append(
                _uiState.value.freqHistory,
                frame.cpu.coreFreqKhz.values.filterNotNull().maxOrNull()?.div(1_000_000.0),
            ),
            ramHistory = append(_uiState.value.ramHistory, frame.memory?.usedFraction),
            tempHistory = append(
                _uiState.value.tempHistory,
                frame.thermal.mapNotNull { it.celsius }.maxOrNull(),
            ),
        )
    }

    private fun <T> append(list: List<T?>, value: T?): List<T?> =
        (list + value).takeLast(WINDOW_SIZE)

    /** Clears the chart windows (used after a re-scan). */
    public fun clearHistory() {
        _uiState.value = _uiState.value.copy(
            loadHistory = emptyList(),
            freqHistory = emptyList(),
            ramHistory = emptyList(),
            tempHistory = emptyList(),
        )
    }
}
