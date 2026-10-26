package dev.kernelis.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kernelis.core.tuning.ApplyPlanner
import dev.kernelis.core.tuning.KernelProfile
import dev.kernelis.core.tuning.ProfileEntry
import dev.kernelis.core.tuning.TuningValidator
import dev.kernelis.data.TuningRepository
import dev.kernelis.domain.CapabilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Profile management: capture the current device state as a profile, re-apply it, delete.
 *
 * A profile is a list of (capability, path, value). Re-applying resolves those entries
 * against a *fresh* capability scan, so a profile captured on another kernel version
 * reports unmatched entries instead of writing to nodes that no longer exist.
 */
@HiltViewModel
public class ProfilesViewModel @Inject constructor(
    private val repository: TuningRepository,
    private val capabilityService: CapabilityService,
) : ViewModel() {

    public data class UiState(
        val profiles: List<KernelProfile> = emptyList(),
        val busy: Boolean = false,
        val message: String? = null,
        val messageIsError: Boolean = false,
        val pendingPlan: dev.kernelis.core.tuning.ApplyPlan? = null,
        val pendingProfileName: String? = null,
        val showNameDialog: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    public val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val planner = ApplyPlanner(TuningValidator())

    init {
        viewModelScope.launch {
            repository.ensureBuiltInProfiles()
            repository.observeProfiles().collect { entities ->
                _uiState.value = _uiState.value.copy(
                    profiles = entities.map { entity ->
                        dev.kernelis.data.ProfileEntityMapper.toModel(entity)
                    },
                )
            }
        }
        if (capabilityService.current == null) {
            viewModelScope.launch { capabilityService.refresh(false) }
        }
    }

    public fun openNameDialog() { _uiState.value = _uiState.value.copy(showNameDialog = true) }
    public fun closeNameDialog() { _uiState.value = _uiState.value.copy(showNameDialog = false) }

    /** Captures every currently-available writable control into a new profile. */
    public fun captureCurrentState(name: String, description: String = "") {
        val snapshot = capabilityService.current ?: return
        val entries = buildList {
            for (id in TuningRepository.WRITABLE_CAPABILITIES) {
                for (binding in snapshot.bindingsFor(id)) {
                    if (!binding.isAvailable) continue
                    val value = binding.current ?: continue
                    if (binding.path.isBlank()) continue
                    add(ProfileEntry(id, binding.path, value, binding.scope))
                }
            }
        }
        if (entries.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                message = "Nothing to capture: no writable control is available.",
                messageIsError = true,
                showNameDialog = false,
            )
            return
        }
        viewModelScope.launch {
            repository.saveProfile(
                KernelProfile(
                    id = "user." + UUID.randomUUID(),
                    name = name.ifBlank { "Profile ${System.currentTimeMillis() % 10000}" },
                    description = description,
                    entries = entries,
                    deviceFingerprint = snapshot.deviceFingerprint,
                ),
            )
            _uiState.value = _uiState.value.copy(
                message = "Saved ${entries.size} settings as \"$name\".",
                messageIsError = false,
                showNameDialog = false,
            )
        }
    }

    public fun delete(profile: KernelProfile) {
        if (profile.builtIn) {
            _uiState.value = _uiState.value.copy(
                message = "Built-in presets cannot be deleted.",
                messageIsError = true,
            )
            return
        }
        viewModelScope.launch {
            repository.deleteProfile(profile.id)
            _uiState.value = _uiState.value.copy(message = "Deleted \"${profile.name}\".", messageIsError = false)
        }
    }

    /** Plans an apply and asks for confirmation; nothing is written yet. */
    public fun prepareApply(profile: KernelProfile) {
        val snapshot = capabilityService.current ?: return
        val plan = planner.plan(snapshot, profile.entries)
        _uiState.value = _uiState.value.copy(
            pendingPlan = plan,
            pendingProfileName = profile.name,
        )
    }

    public fun cancelApply() {
        _uiState.value = _uiState.value.copy(pendingPlan = null, pendingProfileName = null)
    }

    public fun confirmApply(acknowledgeHighRisk: Boolean) {
        val plan = _uiState.value.pendingPlan ?: return
        val name = _uiState.value.pendingProfileName ?: "profile"
        _uiState.value = _uiState.value.copy(busy = true)
        viewModelScope.launch {
            val report = repository.applyPlan(plan, acknowledgeHighRisk)
            _uiState.value = _uiState.value.copy(
                busy = false,
                pendingPlan = null,
                pendingProfileName = null,
                message = "Applied \"$name\": ${report.summary}",
                messageIsError = !report.isFullSuccess,
            )
            capabilityService.refresh(force = true)
        }
    }

    public fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
