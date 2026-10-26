package dev.kernelis.core.capability

import dev.kernelis.core.sysfs.SysFs
import dev.kernelis.core.sysfs.asSysList

/**
 * MediaTek-specific surfaces.
 *
 * MediaTek kernels differ from mainline in ways that break a naive scanner:
 *
 *  - **CPU frequency is often not writable through cpufreq.** On many MTK kernels the
 *    cpufreq nodes exist but writes are ignored; the supported interface is PPM
 *    (`/proc/ppm/…`). KERNELIS probes both and reports which one is actually usable.
 *  - **Thermal sensors are exposed through procfs**, e.g.
 *    `/proc/mtktscpu/mtktscpu_temperature`, not only `thermal_zone*`.
 *  - **EEM / DVFS tables** live in `/proc/eem` and `/proc/cpufreq`.
 *
 * Sources: MediaTek kernel tree (`drivers/misc/mediatek/base/power/ppm_v*`), MTK thermal
 * driver, and community documentation for `/proc/ppm/policy_status`.
 */
public class MediaTekScanner(private val sysFs: SysFs) {

    public data class Result(
        val bindings: List<Binding>,
        val thermalSensors: List<ThermalSensor>,
        val notes: List<String>,
        val isMediaTek: Boolean,
    )

    public data class ThermalSensor(val name: String, val path: String, val celsius: Double?)

    public suspend fun scan(): Result {
        val notes = mutableListOf<String>()
        val bindings = mutableListOf<Binding>()

        val ppmValues = sysFs.readMany(NodeCatalogue.MTK_PPM_PATHS)
        val isMtk = ppmValues.isNotEmpty() || sysFs.exists("/proc/mtktscpu")
        if (!isMtk) {
            return Result(emptyList(), emptyList(), emptyList(), isMediaTek = false)
        }

        notes += "MediaTek platform detected (PPM/procfs interfaces present)."

        val policyStatus = ppmValues["/proc/ppm/policy_status"]
        val fixFreq = ppmValues["/proc/ppm/policy/ut_fix_freq"]
        val fixFreqIdx = ppmValues["/proc/ppm/policy/ut_fix_freq_idx"]
        val ppmEnabled = ppmValues["/proc/ppm/enabled"]

        if (policyStatus != null) {
            notes += "PPM policy_status exposes ${policyStatus.lineSequence().count()} policies."
        }

        bindings += Binding(
            capability = CapabilityId.CPU_MAX_FREQ,
            path = "/proc/ppm/policy/ut_fix_freq",
            availability = when {
                fixFreq != null -> Availability.Supported("MediaTek PPM")
                fixFreqIdx != null -> Availability.Supported("MediaTek PPM (index)")
                else -> Availability.NotPresent
            },
            valueKind = ValueKind.STRING,
            current = fixFreq ?: fixFreqIdx,
            scope = "MediaTek PPM",
        )

        // PPM enable/disable is the closest MTK equivalent to a global governor switch.
        bindings += Binding(
            capability = CapabilityId.CPU_GOVERNOR,
            path = "/proc/ppm/enabled",
            availability = if (ppmEnabled != null) {
                Availability.Supported("PPM ${ppmEnabled.trim()}")
            } else {
                Availability.NotPresent
            },
            valueKind = ValueKind.BOOLEAN,
            current = ppmEnabled,
            samples = listOf("0", "1"),
            scope = "MediaTek PPM",
        )

        // MTK thermal sensors via procfs.
        val thermalPaths = NodeCatalogue.MTK_THERMAL_PATHS
        val thermalValues = sysFs.readMany(thermalPaths)
        val sensors = thermalValues.mapNotNull { (path, raw) ->
            val name = path.substringAfterLast('/')
                .removeSuffix("_temperature")
                .ifBlank { path.substringAfterLast('/') }
            ThermalSensor(name = name, path = path, celsius = parseMtkTemp(raw))
        }
        if (sensors.isNotEmpty()) {
            notes += "${sensors.size} MediaTek thermal sensor(s) read through procfs."
        }

        // EEM / DVFS diagnostics.
        val eem = sysFs.readMany(NodeCatalogue.MTK_EEM_PATHS)
        if (eem.isNotEmpty()) {
            notes += "MediaTek EEM/DVFS interface present (${eem.size} nodes)."
        }

        return Result(bindings, sensors, notes, isMediaTek = true)
    }

    /**
     * MTK procfs temperature formats vary: plain degrees ("45"), milli-degrees
     * ("45000"), or a multi-line table with the first column being the value. We take the
     * first numeric token and reuse the same magnitude heuristic as thermal_zone.
     */
    internal fun parseMtkTemp(raw: String): Double? {
        val token = raw.lineSequence()
            .flatMap { it.split(' ', '\t').asSequence() }
            .firstOrNull { it.trim().toLongOrNull() != null }
            ?.trim()
            ?: return null
        val value = token.toLongOrNull() ?: return null
        return when {
            value <= 0L -> null
            value >= 10_000L -> value / 1000.0
            value >= 100L -> value / 10.0
            value >= 10L -> value.toDouble()
            else -> null
        }
    }
}

/**
 * Samsung Exynos-specific surfaces.
 *
 * Exynos devices often ignore standard cpufreq writes on newer kernels and use
 * `/sys/power/cpufreq_{min,max}_limit` instead. Samsung also has its own `/sys/class/sec`
 * tree for LEDs and haptics.
 */
public class ExynosScanner(private val sysFs: SysFs) {

    public data class Result(
        val bindings: List<Binding>,
        val notes: List<String>,
        val isExynos: Boolean,
    )

    public suspend fun scan(): Result {
        val values = sysFs.readMany(NodeCatalogue.EXYNOS_DVFS_PATHS)
        val maxLimit = values["/sys/power/cpufreq_max_limit"]
        val minLimit = values["/sys/power/cpufreq_min_limit"]
        val secClass = sysFs.exists(NodeCatalogue.SAMSUNG_SEC_CLASS)

        val isExynos = maxLimit != null || minLimit != null || secClass
        if (!isExynos) return Result(emptyList(), emptyList(), isExynos = false)

        val notes = mutableListOf<String>()
        val bindings = mutableListOf<Binding>()

        if (secClass) notes += "Samsung /sys/class/sec tree present (LED/haptics)."

        if (maxLimit != null) {
            notes += "Exynos DVFS limit interface present; standard cpufreq writes may be ignored."
            bindings += Binding(
                capability = CapabilityId.CPU_MAX_FREQ,
                path = "/sys/power/cpufreq_max_limit",
                availability = Availability.Supported("Exynos DVFS"),
                valueKind = ValueKind.NUMBER,
                current = maxLimit,
                units = "kHz",
                scope = "Exynos DVFS",
            )
        }
        if (minLimit != null) {
            bindings += Binding(
                capability = CapabilityId.CPU_MIN_FREQ,
                path = "/sys/power/cpufreq_min_limit",
                availability = Availability.Supported("Exynos DVFS"),
                valueKind = ValueKind.NUMBER,
                current = minLimit,
                units = "kHz",
                scope = "Exynos DVFS",
            )
        }

        return Result(bindings, notes, isExynos = true)
    }
}
