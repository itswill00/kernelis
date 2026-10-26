package dev.kernelis.ui.tuning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kernelis.core.capability.CapabilityDomain
import dev.kernelis.core.capability.CapabilityId
import dev.kernelis.core.tuning.ApplyPlan
import dev.kernelis.core.tuning.ApplyPlanner
import dev.kernelis.core.tuning.KernelProfile
import dev.kernelis.core.tuning.PlannedWrite
import dev.kernelis.core.tuning.ProfileEntry
import dev.kernelis.core.tuning.TuningValidator
import dev.kernelis.core.tuning.ValidationResult
import dev.kernelis.data.TuningRepository
import dev.kernelis.data.toModel
import dev.kernelis.domain.CapabilityService
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the tuning screen: pick a control, stage a value, review the diff, apply.
 *
 * The staged value is deliberately separate from applied state. Nothing is written
 * until the user confirms a plan, and the plan shows exactly which entries will change,
 * which are no-ops and which were rejected by validation — before any write happens.
 */
@HiltViewModel
public class TuningViewModel @Inject constructor(
    private val capabilityService: CapabilityService,
    private val repository: TuningRepository,
    private val json: Json,
) : ViewModel() {

    public data class ControlRow(
        val capability: CapabilityId,
        val scope: String?,
        val path: String,
        val label: String,
        val currentValue: String?,
        val stagedValue: String?,
        val samples: List<String>,
        val numericRange: dev.kernelis.core.capability.NumericRange?,
        val units: String?,
        val valueKind: dev.kernelis.core.capability.ValueKind,
        val risk: dev.kernelis.core.capability.Risk,
        val availability: dev.kernelis.core.capability.Availability,
    ) {
        val isEditable: Boolean get() = availability.isSupported
        val isStaged: Boolean get() = stagedValue != null && stagedValue != currentValue

        /**
         * What this control acts on, used as the list's grouping key.
         *
         * Derived from [scope] rather than from the domain, because a domain like CPU
         * spans two clusters and the user thinks in clusters ("big cores", "little
         * cores"). Controls with no scope group under the domain label.
         */
        val groupKey: String
            get() = scope?.takeIf { it.isNotBlank() } ?: capability.domain.label

        /**
         * A one-line explanation of the group, shown under its header.
         *
         * Only clusters get one, and it states the core count because that is the fact
         * that makes "Cluster A" and "Cluster B" mean something on a phone the app has
         * never seen before.
         */
        val groupSubtitle: String?
            get() = null
    }

    public data class UiState(
        val domain: CapabilityDomain = CapabilityDomain.CPU,
        val rows: List<ControlRow> = emptyList(),
        /**
         * Per-domain counts taken from the whole snapshot, not from [rows] (which only
         * holds the selected domain). The domain grid needs both numbers so a user can
         * see, before tapping, which domains actually have editable controls here.
         */
        val domainTotals: Map<CapabilityDomain, Int> = emptyMap(),
        val domainEditable: Map<CapabilityDomain, Int> = emptyMap(),
        val stagedCount: Int = 0,
        val plan: ApplyPlan? = null,
        val planPreview: List<PlannedWrite> = emptyList(),
        val planUnmatched: Int = 0,
        val planRequiresAck: Boolean = false,
        val planRequiresSnapshot: Boolean = false,
        val applying: Boolean = false,
        val resultMessage: String? = null,
        val resultIsError: Boolean = false,
        val profiles: List<KernelProfile> = emptyList(),
        val showPlanDialog: Boolean = false,
        val showProfileDialog: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    public val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val planner = ApplyPlanner(TuningValidator())

    init {
        viewModelScope.launch {
            capabilityService.state.collect { state ->
                if (state is CapabilityService.State.Ready) {
                    rebuildRows(_uiState.value.domain)
                    loadProfiles()
                }
            }
        }
        if (capabilityService.current == null) {
            viewModelScope.launch { capabilityService.refresh(false) }
        }
    }

    public fun selectDomain(domain: CapabilityDomain) {
        _uiState.value = _uiState.value.copy(domain = domain)
        rebuildRows(domain)
    }

    private fun rebuildRows(domain: CapabilityDomain) {
        val snapshot = capabilityService.current ?: return
        val rows = mutableListOf<ControlRow>()
        // Domain-wide tallies computed once per rebuild so the grid can show which
        // domains are worth visiting without the user tapping through all eleven.
        val totals = mutableMapOf<CapabilityDomain, Int>()
        val editable = mutableMapOf<CapabilityDomain, Int>()
        for (id in CapabilityId.entries) {
            for (binding in snapshot.bindingsFor(id)) {
                totals[id.domain] = (totals[id.domain] ?: 0) + 1
                if (binding.isAvailable) editable[id.domain] = (editable[id.domain] ?: 0) + 1
            }
        }
        for (id in CapabilityId.entries) {
            if (id.domain != domain) continue
            for (binding in snapshot.bindingsFor(id)) {
                rows += ControlRow(
                    capability = id,
                    scope = binding.scope,
                    path = binding.path,
                    label = id.label,
                    currentValue = binding.current,
                    stagedValue = null,
                    samples = binding.samples,
                    numericRange = binding.numericRange,
                    units = binding.units,
                    valueKind = binding.valueKind,
                    risk = id.risk,
                    availability = binding.availability,
                )
            }
        }
        _uiState.value = _uiState.value.copy(
            rows = rows,
            domainTotals = totals,
            domainEditable = editable,
            stagedCount = 0,
            plan = null,
        )
    }

    public fun stageValue(row: ControlRow, rawValue: String) {
        val snapshot = capabilityService.current ?: return
        val validation = TuningValidator().validate(
            dev.kernelis.core.tuning.TuningRequest(
                capability = row.capability,
                path = row.path,
                rawValue = rawValue,
                scope = row.scope,
                previousValue = row.currentValue,
            ),
            snapshot.bindingsFor(row.capability),
        )
        _uiState.value = _uiState.value.copy(
            rows = _uiState.value.rows.map {
                if (it.path == row.path && it.scope == row.scope) {
                    it.copy(stagedValue = rawValue)
                } else {
                    it
                }
            },
        )
        rebuildPlan()
    }

    public fun unstage(row: ControlRow) {
        _uiState.value = _uiState.value.copy(
            rows = _uiState.value.rows.map {
                if (it.path == row.path && it.scope == row.scope) it.copy(stagedValue = null) else it
            },
        )
        rebuildPlan()
    }

    public fun clearStaged() {
        _uiState.value = _uiState.value.copy(
            rows = _uiState.value.rows.map { it.copy(stagedValue = null) },
            plan = null,
            showPlanDialog = false,
        )
    }

    private fun rebuildPlan() {
        val snapshot = capabilityService.current ?: return
        val entries = _uiState.value.rows
            .filter { it.isStaged }
            .map { ProfileEntry(it.capability, it.path, it.stagedValue!!, it.scope) }
        if (entries.isEmpty()) {
            _uiState.value = _uiState.value.copy(plan = null, planPreview = emptyList(), planUnmatched = 0)
            return
        }
        val plan = planner.plan(snapshot, entries)
        _uiState.value = _uiState.value.copy(
            plan = plan,
            planPreview = plan.effective,
            planUnmatched = plan.unmatched.size,
            planRequiresAck = plan.requiresAcknowledgement,
            planRequiresSnapshot = plan.requiresSnapshot,
        )
    }

    public fun openPlanDialog() { _uiState.value = _uiState.value.copy(showPlanDialog = true) }
    public fun closePlanDialog() { _uiState.value = _uiState.value.copy(showPlanDialog = false) }
    public fun openProfileDialog() { _uiState.value = _uiState.value.copy(showProfileDialog = true) }
    public fun closeProfileDialog() { _uiState.value = _uiState.value.copy(showProfileDialog = false) }

    public fun applyStaged(acknowledgeHighRisk: Boolean) {
        val plan = _uiState.value.plan ?: return
        _uiState.value = _uiState.value.copy(applying = true, resultMessage = null)
        viewModelScope.launch {
            val report = repository.applyPlan(plan, acknowledgeHighRisk)
            val message = buildString {
                append(report.summary)
                if (report.snapshotId != null) append(" · snapshot saved")
                if (report.failures.isNotEmpty()) {
                    append("\n")
                    report.failures.take(3).forEach { append("• $it\n") }
                }
            }.trim()
            _uiState.value = _uiState.value.copy(
                applying = false,
                showPlanDialog = false,
                resultMessage = message,
                resultIsError = !report.isFullSuccess,
            )
            // Availability may have changed (governor switch changes tunables).
            capabilityService.refresh(force = true)
            rebuildRows(_uiState.value.domain)
        }
    }

    private fun loadProfiles() {
        viewModelScope.launch {
            repository.ensureBuiltInProfiles()
            repository.observeProfiles().collect { entities ->
                _uiState.value = _uiState.value.copy(
                    profiles = entities.map { it.toModel(json) },
                )
            }
        }
    }

    public fun applyProfile(profile: KernelProfile) {
        val snapshot = capabilityService.current ?: return
        val plan = planner.plan(snapshot, profile.entries)
        _uiState.value = _uiState.value.copy(plan = plan, showPlanDialog = true)
        rebuildPlanFromPlan(plan)
    }

    private fun rebuildPlanFromPlan(plan: ApplyPlan) {
        _uiState.value = _uiState.value.copy(
            planPreview = plan.effective,
            planUnmatched = plan.unmatched.size,
            planRequiresAck = plan.requiresAcknowledgement,
            planRequiresSnapshot = plan.requiresSnapshot,
        )
    }
}
