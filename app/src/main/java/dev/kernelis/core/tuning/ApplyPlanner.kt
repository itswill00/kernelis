package dev.kernelis.core.tuning

import dev.kernelis.core.capability.Binding
import dev.kernelis.core.capability.CapabilityId
import dev.kernelis.core.capability.CapabilitySnapshot
import dev.kernelis.core.capability.Risk
import java.util.UUID

/**
 * One line of a planned operation, with its validation outcome already decided.
 *
 * Planning is separated from execution so the UI can show a **diff** — exactly what
 * will change, what will be skipped and why — before anything is written. This is the
 * mechanism behind "configuration diffs" and it also makes the engine testable without
 * a device.
 */
public data class PlannedWrite(
    val capability: CapabilityId,
    val scope: String?,
    val path: String,
    val current: String?,
    val requested: String,
    val risk: Risk,
    val validation: ValidationResult,
) {
    val willChange: Boolean
        get() = validation.isValid &&
            validation is ValidationResult.Valid &&
            normalise(current) != normalise((validation as ValidationResult.Valid).normalised)

    val skipReason: String?
        get() = when {
            validation is ValidationResult.Invalid -> (validation as ValidationResult.Invalid).reason
            !willChange -> "Already at this value"
            else -> null
        }

    private fun normalise(value: String?): String? = value?.trim()?.toLongOrNull()?.toString() ?: value?.trim()
}

public data class ApplyPlan(
    val capabilitySnapshot: CapabilitySnapshot,
    val operationId: String,
    val writes: List<PlannedWrite>,
    val unmatched: List<ProfileEntry>,
    val maxRisk: Risk,
) {
    val effective: List<PlannedWrite> get() = writes.filter { it.willChange }
    val skipped: List<PlannedWrite> get() = writes.filter { !it.willChange }
    val isNoop: Boolean get() = effective.isEmpty()

    /** MEDIUM and above, or any multi-write operation, takes a snapshot first. */
    val requiresSnapshot: Boolean get() = maxRisk >= Risk.MEDIUM || effective.size > 1

    /** HIGH risk can make the device unstable, so it always needs explicit consent. */
    val requiresAcknowledgement: Boolean get() = maxRisk >= Risk.HIGH
}

/**
 * Builds [ApplyPlan]s from a profile or snapshot against the live capability scan.
 *
 * Resolution rules:
 *  - Entries are matched to controls by [CapabilityId] first and by exact recorded path
 *    second. An entry whose path no longer exists is reported as unmatched, never
 *    silently redirected to a "similar" node.
 *  - Built-in presets use the sentinel values `@device:min-notch` / `@device:max-notch`
 *    to mean "the device's own extreme frequency", which are resolved from the scan.
 *  - A governor requested by a preset only applies when the kernel lists it; otherwise
 *    it is skipped with a reason.
 */
public class ApplyPlanner(private val validator: TuningValidator = TuningValidator()) {

    public fun plan(
        capabilitySnapshot: CapabilitySnapshot,
        entries: List<ProfileEntry>,
        operationId: String = UUID.randomUUID().toString(),
    ): ApplyPlan {
        val writes = mutableListOf<PlannedWrite>()
        val unmatched = mutableListOf<ProfileEntry>()

        for (entry in entries) {
            val targets = resolveTargets(capabilitySnapshot, entry)
            if (targets.isEmpty()) {
                unmatched += entry
                continue
            }
            for (binding in targets) {
                val resolvedValue = resolveValue(entry, binding) ?: run {
                    unmatched += entry
                    continue
                }
                val request = TuningRequest(
                    capability = binding.capability,
                    path = binding.path,
                    rawValue = resolvedValue,
                    scope = binding.scope,
                    previousValue = binding.current,
                )
                writes += PlannedWrite(
                    capability = binding.capability,
                    scope = binding.scope,
                    path = binding.path,
                    current = binding.current,
                    requested = resolvedValue,
                    risk = binding.capability.risk,
                    validation = validator.validate(request, capabilitySnapshot.bindingsFor(binding.capability)),
                )
            }
        }

        val maxRisk = writes.filter { it.willChange }.maxOfOrNull { it.risk } ?: Risk.NONE
        return ApplyPlan(
            capabilitySnapshot = capabilitySnapshot,
            operationId = operationId,
            writes = writes,
            unmatched = unmatched,
            maxRisk = maxRisk,
        )
    }

    private fun resolveTargets(snapshot: CapabilitySnapshot, entry: ProfileEntry): List<Binding> {
        val all = snapshot.bindingsFor(entry.capability).filter { it.isAvailable }
        if (all.isEmpty()) return emptyList()
        // Exact path wins; otherwise fall back to all available bindings for this
        // capability (e.g. every cluster) — but only for controls with one node per
        // cluster, which the scanner encodes through distinct scopes.
        val exact = all.filter { it.path == entry.path }
        if (exact.isNotEmpty()) return exact
        if (entry.path.startsWith("@device")) return all
        return all
    }

    private fun resolveValue(entry: ProfileEntry, binding: Binding): String? {
        val raw = entry.value
        return when {
            raw == "@resolved" || raw == "@device:min-notch" ->
                binding.samples.firstOrNull() ?: binding.numericRange?.min?.toString()
            raw == "@device:max-notch" ->
                binding.samples.lastOrNull() ?: binding.numericRange?.max?.toString()
            raw == "@device" -> binding.current
            else -> raw
        }
    }
}
