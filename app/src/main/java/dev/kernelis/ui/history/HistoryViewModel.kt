package dev.kernelis.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kernelis.core.tuning.ApplyPlanner
import dev.kernelis.core.tuning.TuningValidator
import dev.kernelis.data.TuningRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Change history and snapshots.
 *
 * The history is the app's audit trail: every attempted change, successful or not,
 * grouped by operation so a multi-write profile apply can be rolled back whole.
 * Snapshots are full captures of writable state and can be restored on demand.
 */
@HiltViewModel
public class HistoryViewModel @Inject constructor(
    private val repository: TuningRepository,
) : ViewModel() {

    public data class OperationGroup(
        val operationId: String,
        val entries: List<dev.kernelis.data.db.ChangeLogEntity>,
        val timestampMs: Long,
    ) {
        val label: String
            get() = entries.firstOrNull()?.capability ?: operationId.take(8)

        val allApplied: Boolean get() = entries.all { it.status == TuningRepository.STATUS_APPLIED }
        val anyFailed: Boolean get() = entries.any { it.status == TuningRepository.STATUS_FAILED }
        val reversibleCount: Int get() = entries.count { it.reversible }
    }

    public data class UiState(
        val groups: List<OperationGroup> = emptyList(),
        val totalChanges: Int = 0,
        val busyOperationId: String? = null,
        val message: String? = null,
        val messageIsError: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    public val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    public val snapshots: StateFlow<List<dev.kernelis.data.db.SnapshotEntity>> =
        repository.observeSnapshots()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val planner = ApplyPlanner(TuningValidator())

    init {
        viewModelScope.launch {
            repository.observeHistory(limit = 500).collect { entries ->
                _uiState.value = _uiState.value.copy(
                    groups = groupByOperation(entries),
                    totalChanges = entries.size,
                )
            }
        }
    }

    private fun groupByOperation(entries: List<dev.kernelis.data.db.ChangeLogEntity>): List<OperationGroup> {
        // Preserve ordering: newest operation first, but chronological within an op.
        val grouped = entries.groupBy { it.operationId }
        return grouped.entries
            .sortedByDescending { (_, list) -> list.maxOf { it.timestampMs } }
            .map { (opId, list) ->
                OperationGroup(
                    operationId = opId,
                    entries = list.sortedBy { it.id },
                    timestampMs = list.maxOf { it.timestampMs },
                )
            }
    }

    /** Rolls back every applied change in one operation, newest write first. */
    public fun rollback(operationId: String) {
        _uiState.value = _uiState.value.copy(busyOperationId = operationId, message = null)
        viewModelScope.launch {
            val report = repository.rollbackOperation(operationId)
            _uiState.value = _uiState.value.copy(
                busyOperationId = null,
                message = "Rollback: ${report.summary}" +
                    (report.failures.take(2).takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = " — ") ?: ""),
                messageIsError = report.failures.isNotEmpty(),
            )
        }
    }

    public fun restoreSnapshot(id: String) {
        _uiState.value = _uiState.value.copy(message = null)
        viewModelScope.launch {
            val report = repository.restoreSnapshot(id, planner)
            _uiState.value = _uiState.value.copy(
                message = "Snapshot restore: ${report.summary}",
                messageIsError = report.failures.isNotEmpty(),
            )
        }
    }

    public fun deleteSnapshot(id: String) {
        viewModelScope.launch { repository.deleteSnapshot(id) }
    }

    public fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            _uiState.value = _uiState.value.copy(message = "History cleared", messageIsError = false)
        }
    }

    public fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
