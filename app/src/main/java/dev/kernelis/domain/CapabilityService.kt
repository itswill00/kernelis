package dev.kernelis.domain

import dev.kernelis.core.capability.Binding
import dev.kernelis.core.capability.CapabilityId
import dev.kernelis.core.capability.CapabilityScanner
import dev.kernelis.core.capability.CapabilitySnapshot
import dev.kernelis.core.capability.CpuTopologyScanner
import dev.kernelis.core.root.RootDetector
import dev.kernelis.core.root.RootProvider
import dev.kernelis.core.root.RootShell
import dev.kernelis.core.sysfs.SysFs
import dev.kernelis.core.telemetry.TelemetryCollector
import dev.kernelis.data.TuningRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the "what can this device do" question.
 *
 * A scan is expensive (shell round-trips), so results are cached and shared. Callers
 * observe [state] rather than re-scanning per screen. The cache is invalidated on
 * demand after a change that could alter availability (e.g. a governor switch).
 */
@Singleton
public class CapabilityService @Inject constructor(
    private val sysFs: SysFs,
    private val rootShell: RootShell,
    private val topologyScanner: CpuTopologyScanner,
    private val scanner: CapabilityScanner,
    private val telemetry: TelemetryCollector,
    private val repository: TuningRepository,
) {
    public sealed interface State {
        public data object Idle : State
        public data object Scanning : State
        public data class Ready(val snapshot: CapabilitySnapshot) : State
        public data class Failed(val reason: String, val rootReason: String?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    public val state: StateFlow<State> = _state.asStateFlow()

    private val _topology = MutableStateFlow(CpuTopologyScanner.Topology.EMPTY)
    public val topology: StateFlow<CpuTopologyScanner.Topology> = _topology.asStateFlow()

    private val mutex = Mutex()

    public val current: CapabilitySnapshot? get() = (_state.value as? State.Ready)?.snapshot

    public suspend fun refresh(force: Boolean = false): CapabilitySnapshot? {
        mutex.withLock {
            if (!force) current?.let { return it }
            _state.value = State.Scanning

            val probe = RootDetector.probe(rootShell)
            if (!probe.available) {
                val reason = probe.reason ?: "Root is unavailable."
                _state.value = State.Failed(reason, reason)
                // Still surface the topology-free snapshot so the UI can explain itself.
                return null
            }

            val provider: RootProvider = rootShell.provider()
            return try {
                val topology = topologyScanner.scan()
                val snapshot = scanner.scan(rootAvailable = true, provider = provider)
                _topology.value = topology
                telemetry.primeCores(topology.cores.map { it.index })
                repository.bindCapabilities(snapshot)
                _state.value = State.Ready(snapshot)
                snapshot
            } catch (t: Throwable) {
                _state.value = State.Failed(
                    reason = t.message ?: t.javaClass.simpleName,
                    rootReason = null,
                )
                null
            }
        }
    }

    public suspend fun topologyNow(): CpuTopologyScanner.Topology = topologyScanner.scan()

    /** Convenience used by screens that need one control's bindings. */
    public fun bindings(id: CapabilityId): List<Binding> = current?.bindingsFor(id).orEmpty()
}
