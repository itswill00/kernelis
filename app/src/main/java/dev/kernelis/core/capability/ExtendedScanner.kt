package dev.kernelis.core.capability

import dev.kernelis.core.sysfs.SysFs
import dev.kernelis.core.sysfs.asSysBool
import dev.kernelis.core.sysfs.asSysInt
import dev.kernelis.core.sysfs.asSysList

/**
 * Scans the "extended" kernel surfaces: hotplug, low-memory-killer, KSM, extra VM
 * tunables, screen calibration, sound, charging, wakelocks and misc kernel switches.
 *
 * Kept separate from [CapabilityScanner] so each file stays readable and so a device that
 * has none of these costs almost nothing: every probe is one batched read of a small,
 * fixed path list, and absence produces `NotPresent` entries.
 *
 * All paths come from [NodeCatalogue], which is sourced from real projects rather than
 * guessed. Nothing here assumes a driver exists.
 */
public class ExtendedScanner(
    private val sysFs: SysFs,
) {

    public data class Result(
        val bindings: List<Binding>,
        val notes: List<String> = emptyList(),
    )

    public suspend fun scan(): Result {
        val bindings = mutableListOf<Binding>()
        val notes = mutableListOf<String>()

        bindings += scanHotplug(notes)
        bindings += scanLmk(notes)
        bindings += scanKsm()
        bindings += scanVm(notes)
        bindings += scanScreen()
        bindings += scanSound()
        bindings += scanCharging()
        bindings += scanWakelocks(notes)
        bindings += scanMisc()

        return Result(bindings, notes)
    }

    // ------------------------------------------------------------------ hotplug

    private suspend fun scanHotplug(notes: MutableList<String>): List<Binding> {
        val root = NodeCatalogue.HOTPLUG_ROOTS.firstOrNull { sysFs.exists(it) }
        if (root == null) {
            return listOf(
                Binding(CapabilityId.HOTPLUG_ENABLED, "", Availability.NotPresent),
                Binding(CapabilityId.HOTPLUG_MIN_CORES, "", Availability.NotPresent),
            )
        }
        val entries = sysFs.list(root)
        // Different drivers name the switch differently; look for the common ones.
        val enablePath = entries
            .firstOrNull { it in ENABLE_NAMES }
            ?.let { "$root/$it" }
        val minCoresPath = entries
            .firstOrNull { it in MIN_CORE_NAMES }
            ?.let { "$root/$it" }

        notes += "Hotplug driver found at $root (${entries.size} controls)."

        return listOf(
            Binding(
                capability = CapabilityId.HOTPLUG_ENABLED,
                path = enablePath.orEmpty(),
                availability = if (enablePath != null) Availability.Supported(root) else Availability.NotPresent,
                valueKind = ValueKind.BOOLEAN,
                current = enablePath?.let { sysFs.read(it) },
                scope = root,
            ),
            Binding(
                capability = CapabilityId.HOTPLUG_MIN_CORES,
                path = minCoresPath.orEmpty(),
                availability = if (minCoresPath != null) Availability.Supported(root) else Availability.NotPresent,
                valueKind = ValueKind.NUMBER,
                current = minCoresPath?.let { sysFs.read(it) },
                numericRange = NumericRange(1, 8),
                scope = root,
            ),
        )
    }

    // ---------------------------------------------------------------------- LMK

    private suspend fun scanLmk(notes: MutableList<String>): List<Binding> {
        val values = sysFs.readMany(NodeCatalogue.LMK_PARAMS)
        if (values.isEmpty()) {
            notes += "No in-kernel low-memory-killer (expected on kernels 4.12+; lmkd is userspace)."
            return listOf(
                Binding(CapabilityId.LMK_MIN_FREE, "", Availability.NotPresent),
                Binding(CapabilityId.LMK_ADJ, "", Availability.NotPresent),
                Binding(CapabilityId.LMK_ADAPTIVE, "", Availability.NotPresent),
            )
        }
        fun pathOf(suffix: String): String? =
            NodeCatalogue.LMK_PARAMS.firstOrNull { it.endsWith(suffix) && values[it] != null }

        val minfree = pathOf("minfree")
        val adj = pathOf("adj")
        val adaptive = pathOf("enable_adaptive_lmk")

        return listOf(
            Binding(
                CapabilityId.LMK_MIN_FREE,
                minfree.orEmpty(),
                if (minfree != null) Availability.Supported() else Availability.NotPresent,
                valueKind = ValueKind.STRING,
                current = minfree?.let { values[it] },
            ),
            Binding(
                CapabilityId.LMK_ADJ,
                adj.orEmpty(),
                if (adj != null) Availability.Supported() else Availability.NotPresent,
                valueKind = ValueKind.STRING,
                current = adj?.let { values[it] },
            ),
            Binding(
                CapabilityId.LMK_ADAPTIVE,
                adaptive.orEmpty(),
                if (adaptive != null) Availability.Supported() else Availability.NotPresent,
                valueKind = ValueKind.BOOLEAN,
                current = adaptive?.let { values[it] },
            ),
        )
    }

    // ---------------------------------------------------------------------- KSM

    private suspend fun scanKsm(): List<Binding> {
        // KSM roots are *directories*; `exists` on the root itself is false on most
        // kernels, so presence is decided by a known file inside it.
        val root = NodeCatalogue.KSM_ROOTS.firstOrNull {
            sysFs.exists("$it/run") || sysFs.exists("$it/pages_to_scan")
        } ?: return listOf(
            Binding(CapabilityId.KSM_ENABLED, "", Availability.NotPresent),
            Binding(CapabilityId.KSM_PAGES, "", Availability.NotPresent),
        )
        val paths = listOf("$root/run", "$root/pages_to_scan", "$root/sleep_millisecs")
        val values = sysFs.readMany(paths)
        return listOf(
            Binding(
                CapabilityId.KSM_ENABLED,
                "$root/run",
                if (values["$root/run"] != null) Availability.Supported(root) else Availability.NotPresent,
                valueKind = ValueKind.BOOLEAN,
                current = values["$root/run"],
            ),
            Binding(
                CapabilityId.KSM_PAGES,
                "$root/pages_to_scan",
                if (values["$root/pages_to_scan"] != null) Availability.Supported(root) else Availability.NotPresent,
                valueKind = ValueKind.NUMBER,
                current = values["$root/pages_to_scan"],
                numericRange = NumericRange(0, 100_000),
            ),
        )
    }

    // ------------------------------------------------------------------ extra VM

    private suspend fun scanVm(notes: MutableList<String>): List<Binding> {
        val values = sysFs.readMany(NodeCatalogue.VM_TUNABLES.map { it.path })
        val out = mutableListOf<Binding>()

        fun vmBinding(id: CapabilityId, key: String, kind: ValueKind = ValueKind.NUMBER): Binding {
            val tunable = NodeCatalogue.VM_TUNABLES.first { it.key == key }
            val raw = values[tunable.path]
            return Binding(
                capability = id,
                path = tunable.path,
                availability = if (raw != null) Availability.Supported(tunable.label) else Availability.NotPresent,
                valueKind = kind,
                current = raw,
                numericRange = NumericRange(tunable.min, tunable.max),
                units = tunable.units,
            )
        }

        out += vmBinding(CapabilityId.VM_MIN_FREE, "min_free_kbytes")
        out += vmBinding(CapabilityId.VM_EXTRA_FREE, "extra_free_kbytes")
        out += vmBinding(CapabilityId.VM_WATERMARK, "watermark_scale_factor")
        out += vmBinding(CapabilityId.VM_OVERCOMMIT, "overcommit_memory")
        out += vmBinding(CapabilityId.VM_DIRTY_WRITEBACK, "dirty_writeback_centisecs")

        // The rest of the VM list is reported as diagnostics through the same mechanism
        // so the Tuning screen can show them without a bespoke CapabilityId each.
        val covered = setOf(
            "min_free_kbytes", "extra_free_kbytes", "watermark_scale_factor",
            "overcommit_memory", "dirty_writeback_centisecs",
        )
        val extras = NodeCatalogue.VM_TUNABLES.filterNot { it.key in covered }
            .mapNotNull { tunable ->
                val raw = values[tunable.path] ?: return@mapNotNull null
                Binding(
                    capability = CapabilityId.VM_SWAPPINESS,
                    path = tunable.path,
                    availability = Availability.Supported(tunable.label),
                    valueKind = ValueKind.NUMBER,
                    current = raw,
                    numericRange = NumericRange(tunable.min, tunable.max),
                    units = tunable.units,
                    scope = tunable.label,
                )
            }
        if (extras.isNotEmpty()) notes += "${extras.size} additional VM tunables available."
        out += extras
        return out
    }

    // ------------------------------------------------------------------- screen

    private suspend fun scanScreen(): List<Binding> {
        val root = NodeCatalogue.KCAL_ROOTS.firstOrNull { sysFs.exists(it) }
        if (root == null) {
            return listOf(
                Binding(CapabilityId.SCREEN_KCAL, "", Availability.NotPresent),
                Binding(CapabilityId.SCREEN_HBM, "", Availability.NotPresent),
            )
        }
        val kcalPath = NodeCatalogue.KCAL_FILES.map { "$root/$it" }.firstOrNull { sysFs.exists(it) }
        val satPath = listOf("$root/kcal_sat").firstOrNull { sysFs.exists(it) }
        val hbmPath = listOf("$root/hbm", "$root/SRGB").firstOrNull { sysFs.exists(it) }

        return listOf(
            Binding(
                CapabilityId.SCREEN_KCAL,
                kcalPath.orEmpty(),
                if (kcalPath != null) Availability.Supported(root) else Availability.NotPresent,
                valueKind = ValueKind.STRING,
                current = kcalPath?.let { sysFs.read(it) },
            ),
            Binding(
                CapabilityId.SCREEN_KCAL_SAT,
                satPath.orEmpty(),
                if (satPath != null) Availability.Supported(root) else Availability.NotPresent,
                valueKind = ValueKind.NUMBER,
                current = satPath?.let { sysFs.read(it) },
                numericRange = NumericRange(0, 255),
            ),
            Binding(
                CapabilityId.SCREEN_HBM,
                hbmPath.orEmpty(),
                if (hbmPath != null) Availability.Supported(root) else Availability.NotPresent,
                valueKind = ValueKind.BOOLEAN,
                current = hbmPath?.let { sysFs.read(it) },
            ),
        )
    }

    // -------------------------------------------------------------------- sound

    private suspend fun scanSound(): List<Binding> {
        val root = NodeCatalogue.SOUND_ROOTS.firstOrNull { sysFs.exists(it) }
        if (root == null) return listOf(Binding(CapabilityId.SOUND_GAIN, "", Availability.NotPresent))
        val gainPath = listOf(
            "$root/headphone_gain",
            "$root/speaker_gain",
            "$root/class_AB",
        ).firstOrNull { sysFs.exists(it) }
        return listOf(
            Binding(
                CapabilityId.SOUND_GAIN,
                gainPath.orEmpty(),
                if (gainPath != null) Availability.Supported(root) else Availability.NotPresent,
                valueKind = ValueKind.NUMBER,
                current = gainPath?.let { sysFs.read(it) },
                numericRange = NumericRange(-20, 20),
                units = "dB",
            ),
        )
    }

    // ----------------------------------------------------------------- charging

    private suspend fun scanCharging(): List<Binding> {
        val out = mutableListOf<Binding>()
        val candidates = NodeCatalogue.CHARGE_ROOTS.flatMap { root ->
            NodeCatalogue.CHARGE_FILES.map { "$root/$it" }
        }
        val values = sysFs.readMany(candidates)
        fun first(needle: String): String? = candidates.firstOrNull { it.endsWith("/$needle") && values[it] != null }

        val fast = first("force_fast_charge") ?: first("fast_charge")
        val limit = first("input_current_limit") ?: first("constant_charge_current_max")
        val enabled = first("battery_charging_enabled") ?: first("charge_enabled")

        out += Binding(
            CapabilityId.CHARGE_FAST,
            fast.orEmpty(),
            if (fast != null) Availability.Supported() else Availability.NotPresent,
            valueKind = ValueKind.BOOLEAN,
            current = fast?.let { values[it] },
        )
        out += Binding(
            CapabilityId.CHARGE_LIMIT,
            limit.orEmpty(),
            if (limit != null) Availability.Supported() else Availability.NotPresent,
            valueKind = ValueKind.NUMBER,
            current = limit?.let { values[it] },
            units = "µA",
        )
        out += Binding(
            CapabilityId.CHARGE_ENABLED,
            enabled.orEmpty(),
            if (enabled != null) Availability.Supported() else Availability.NotPresent,
            valueKind = ValueKind.BOOLEAN,
            current = enabled?.let { values[it] },
        )
        return out
    }

    // ---------------------------------------------------------------- wakelocks

    private suspend fun scanWakelocks(notes: MutableList<String>): List<Binding> {
        val root = NodeCatalogue.WAKELOCK_PARAMS.firstOrNull { sysFs.exists(it) }
        if (root == null) return listOf(Binding(CapabilityId.WAKELOCK_BLOCKER, "", Availability.NotPresent))
        val entries = sysFs.list(root).filter { it.startsWith("enable_") }
        notes += "Wakelock blocker at $root with ${entries.size} sources."
        return listOf(
            Binding(
                CapabilityId.WAKELOCK_BLOCKER,
                root,
                if (entries.isNotEmpty()) Availability.Supported("${entries.size} sources") else Availability.NotPresent,
                valueKind = ValueKind.BOOLEAN,
                current = null,
                scope = root,
            ),
        )
    }

    // --------------------------------------------------------------------- misc

    private suspend fun scanMisc(): List<Binding> {
        val values = sysFs.readMany(NodeCatalogue.MISC_FILES)
        fun binding(id: CapabilityId, suffix: String, kind: ValueKind): Binding {
            val path = NodeCatalogue.MISC_FILES.firstOrNull { it.endsWith(suffix) && values[it] != null }
            return Binding(
                capability = id,
                path = path.orEmpty(),
                availability = if (path != null) Availability.Supported() else Availability.NotPresent,
                valueKind = kind,
                current = path?.let { values[it] },
            )
        }
        val tcp = values["/proc/sys/net/ipv4/tcp_congestion_control"]
        val tcpAvailable = values["/proc/sys/net/ipv4/tcp_available_congestion_control"].asSysList()
        return listOf(
            binding(CapabilityId.MISC_FSYNC, "Dyn_fsync_active", ValueKind.BOOLEAN),
            binding(CapabilityId.MISC_LOGGER, "logger_mode", ValueKind.BOOLEAN),
            binding(CapabilityId.MISC_POWER_SUSPEND, "power_suspend_mode", ValueKind.ENUM),
            Binding(
                CapabilityId.MISC_TCP_CONGESTION,
                "/proc/sys/net/ipv4/tcp_congestion_control",
                if (tcp != null) Availability.Supported() else Availability.NotPresent,
                valueKind = ValueKind.ENUM,
                current = tcp,
                samples = tcpAvailable,
            ),
        )
    }

    private companion object {
        val ENABLE_NAMES = setOf("enabled", "enable", "hotplug_enabled", "mpdecision")
        val MIN_CORE_NAMES = setOf("min_cpus", "min_online_cpus", "min_cores", "min_cpus_online")
    }
}
