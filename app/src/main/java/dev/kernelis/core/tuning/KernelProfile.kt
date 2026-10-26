package dev.kernelis.core.tuning

import dev.kernelis.core.capability.CapabilityId
import kotlinx.serialization.Serializable

/**
 * A single intended setting inside a profile or snapshot.
 *
 * [path] is recorded so a snapshot can be restored even if the scanner would now bind a
 * different node, but restore re-validates the path against the live scan before use.
 */
@Serializable
public data class ProfileEntry(
    val capability: CapabilityId,
    val path: String,
    val value: String,
    val scope: String? = null,
)

@Serializable
public data class KernelProfile(
    val id: String,
    val name: String,
    val description: String,
    val entries: List<ProfileEntry>,
    val builtIn: Boolean = false,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    /** Device fingerprint at capture time; informational only, never used to block. */
    val deviceFingerprint: String? = null,
) {
    public companion object {
        /**
         * Built-in presets are *intent* expressed as capability levels, not fixed
         * numbers: they are resolved against the device's discovered range at apply
         * time. A device without a min-frequency node simply gets that entry skipped
         * and reported, rather than having a made-up value written.
         */
        public val BUILT_IN: List<KernelProfile>
            get() = listOf(
                KernelProfile(
                    id = "preset.balanced",
                    name = "Balanced",
                    description = "Restores the device's own limits. Safe baseline for any kernel.",
                    entries = emptyList(),
                    builtIn = true,
                ),
                KernelProfile(
                    id = "preset.power-saver",
                    name = "Power saver",
                    description = "Caps maximum frequency at the lowest meaningful step and prefers " +
                        "battery-oriented governors where the kernel offers them.",
                    entries = listOf(
                        ProfileEntry(CapabilityId.CPU_MAX_FREQ, "@device:min-notch", "@resolved"),
                        ProfileEntry(CapabilityId.CPU_GOVERNOR, "@device", "powersave"),
                        ProfileEntry(CapabilityId.VM_SWAPPINESS, "/proc/sys/vm/swappiness", "100"),
                    ),
                    builtIn = true,
                ),
                KernelProfile(
                    id = "preset.performance",
                    name = "Performance",
                    description = "Raises limits to the device maximum and prefers performance governors. " +
                        "Increases power draw and heat; thermal throttling still applies.",
                    entries = listOf(
                        ProfileEntry(CapabilityId.CPU_MAX_FREQ, "@device:max-notch", "@resolved"),
                        ProfileEntry(CapabilityId.CPU_GOVERNOR, "@device", "performance"),
                        ProfileEntry(CapabilityId.VM_SWAPPINESS, "/proc/sys/vm/swappiness", "60"),
                    ),
                    builtIn = true,
                ),
            )
    }
}

/**
 * An immutable capture of the values KERNELIS manages at one point in time.
 *
 * A snapshot is the unit of recovery: it can be restored as a whole, and doing so is a
 * user-visible, logged operation. It deliberately covers only controls KERNELIS itself
 * can write, so restoring cannot touch unrelated kernel state.
 */
@Serializable
public data class ConfigSnapshot(
    val id: String,
    val label: String,
    val createdAtMs: Long,
    val entries: List<ProfileEntry>,
    val deviceFingerprint: String,
    val automatic: Boolean,
)
