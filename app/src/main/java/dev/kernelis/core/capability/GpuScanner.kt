package dev.kernelis.core.capability

import dev.kernelis.core.sysfs.SysFs
import dev.kernelis.core.sysfs.asSysList

/**
 * Discovers the GPU control surface across vendors.
 *
 * Android ships at least four GPU families with completely different sysfs layouts:
 *
 * | Vendor | GPU | Control root |
 * | --- | --- | --- |
 * | Qualcomm | Adreno | `/sys/class/kgsl/kgsl-3d0` (+ `devfreq` subdir) |
 * | ARM | Mali | `/sys/class/devfreq/<name>.mali` (devfreq) |
 * | Imagination | PowerVR | `/sys/devices/platform/<name>/sgxfreq` (older Exynos/Atom) |
 * | Samsung/AMD | Xclipse | `/sys/class/devfreq/<name>.gpu` (Exynos 2200+) |
 * | NVIDIA | Tegra | `/sys/kernel/tegra_gpu` |
 *
 * Rather than hard-coding one layout, this scanner probes candidate directories, then
 * falls back to scanning `/sys/class/devfreq` and matching the device `name` attribute.
 * A directory only counts as a GPU if it actually exposes governor or frequency nodes —
 * presence of a name alone is not evidence.
 */
public class GpuScanner(private val sysFs: SysFs) {

    public data class GpuSurface(
        val root: String,
        val vendor: Vendor,
        val governorPath: String?,
        val availableGovernors: List<String>,
        val currentGovernor: String?,
        val availableFreqs: List<Long>,
        val minFreqPath: String?,
        val maxFreqPath: String?,
        val currentFreqPath: String?,
        val loadPath: String?,
        val pwrLevelMinPath: String?,
        val pwrLevelMaxPath: String?,
        val adrenoBoostPath: String?,
        val tempPath: String?,
        val throttlingPath: String?,
    ) {
        val hasFrequencyControl: Boolean get() = minFreqPath != null || maxFreqPath != null
        val units: String get() = if (vendor == Vendor.ADRENO) "Hz" else "Hz"
    }

    public enum class Vendor(val label: String) {
        ADRENO("Adreno"),
        MALI("Mali"),
        POWERVR("PowerVR"),
        XCLIPSE("Xclipse"),
        TEGRA("Tegra"),
        GENERIC("GPU"),
    }

    public suspend fun scan(): GpuSurface? {
        val root = discoverRoot() ?: return null
        val vendor = classify(root)

        // Two layouts exist in the wild:
        //  - a device root with a `devfreq/` subdirectory (Adreno, Mali on some kernels)
        //  - a devfreq directory itself, exposing `governor` etc. directly (most Mali)
        // So every logical file is probed under both prefixes and the first hit wins.
        val logicalFiles = listOf(
            "governor",
            "available_governors",
            "available_frequencies",
            "min_freq",
            "max_freq",
            "cur_freq",
            "adrenoboost",
            "governor_list",
            "frequency_list",
            "frequency",
            "frequency_limit",
            "gpu_available_frequencies",
            "gpu_available_rates",
            "gpuclk",
            "max_gpuclk",
            "gpu_busy_percentage",
            "gpubusy",
            "min_pwrlevel",
            "max_pwrlevel",
            "temp",
            "throttling",
        )
        // Adreno exposes load/clock/temp on the *parent* of the devfreq dir
        // (e.g. /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage), so the parent is probed too.
        val parent = root.substringBeforeLast('/').takeIf { it.length > 1 }
        // Absolute candidates only: sysfs is not required to normalise "..", and some
        // kernels resolve it inconsistently, so we never emit relative segments.
        val candidates = logicalFiles.flatMap { file ->
            buildList {
                add("$root/devfreq/$file")
                add("$root/$file")
                if (parent != null) add("$parent/$file")
            }
        }
        val values = sysFs.readMany(candidates)

        /** Resolves a logical file name to the first existing path, across layouts. */
        fun resolve(name: String): String? {
            val devfreqPath = "$root/devfreq/$name"
            if (values.containsKey(devfreqPath)) return devfreqPath
            val directPath = "$root/$name"
            if (values.containsKey(directPath)) return directPath
            if (parent != null) {
                val parentPath = "$parent/$name"
                if (values.containsKey(parentPath)) return parentPath
            }
            return null
        }

        fun valueOf(name: String): String? = resolve(name)?.let { values[it] }

        val governorPath = resolve("governor")
        val availableGovernors = valueOf("available_governors")?.asSysList()
            ?: valueOf("governor_list").asSysList()
        val freqList = valueOf("available_frequencies")
            ?: valueOf("gpu_available_frequencies")
            ?: valueOf("frequency_list")
            ?: valueOf("gpu_available_rates")

        val freqs = freqList.asSysList()
            .mapNotNull { token -> token.toLongOrNull() }
            .sorted()

        return GpuSurface(
            root = root,
            vendor = vendor,
            governorPath = governorPath,
            availableGovernors = availableGovernors,
            currentGovernor = valueOf("governor")?.trim(),
            availableFreqs = freqs,
            minFreqPath = resolve("min_freq") ?: resolve("frequency_limit"),
            maxFreqPath = resolve("max_freq"),
            currentFreqPath = resolve("cur_freq") ?: resolve("frequency") ?: resolve("gpuclk"),
            loadPath = resolve("gpu_busy_percentage") ?: resolve("gpubusy"),
            pwrLevelMinPath = resolve("min_pwrlevel"),
            pwrLevelMaxPath = resolve("max_pwrlevel"),
            adrenoBoostPath = resolve("adrenoboost"),
            tempPath = resolve("temp"),
            throttlingPath = resolve("throttling"),
        )
    }

    /**
     * Finds the GPU control root using **at most four** shell round-trips, regardless of
     * how many candidate directories exist.
     *
     * The old version cost one `su` process per candidate per marker plus three more per
     * devfreq device, which on a MediaTek device with a dozen devfreq devices meant
     * ~50 processes for GPU discovery alone.
     */
    private suspend fun discoverRoot(): String? {
        // 1. Known candidates, verified by an actual control node.
        val candidateProbes = NodeCatalogue.GPU_DEVFREQ_CANDIDATES.flatMap { candidate ->
            GPU_MARKERS.map { marker -> "$candidate/$marker" }
        }
        val present = sysFs.accessMany(candidateProbes).filterValues { it.exists }.keys
        for (candidate in NodeCatalogue.GPU_DEVFREQ_CANDIDATES) {
            if (GPU_MARKERS.any { marker -> "$candidate/$marker" in present }) return candidate
        }

        // 2. Every devfreq device: one listing, one batched name read, one batched probe.
        val dirs = sysFs.list(NodeCatalogue.DEVFREQ_ROOT)
        if (dirs.isEmpty()) return null
        val roots = dirs.map { "${NodeCatalogue.DEVFREQ_ROOT}/$it" }
        val names = sysFs.readMany(roots.map { "$it/name" })

        val access = sysFs.accessMany(
            roots.flatMap { root ->
                GPU_MARKERS.map { "$root/$it" } + listOf(
                    "$root/governor",
                    "$root/available_frequencies",
                    "$root/max_freq",
                )
            },
        )
        fun exposesControlNode(root: String): Boolean =
            GPU_MARKERS.any { marker -> access["$root/$marker"]?.exists == true }

        // 2a. The device name or directory hints at a GPU.
        for (root in roots) {
            val name = names["$root/name"].orEmpty().lowercase()
            val dir = root.substringAfterLast('/').lowercase()
            val hinted = NodeCatalogue.GPU_NAME_HINTS.any { name.contains(it) || dir.contains(it) }
            if (hinted && exposesControlNode(root)) return root
        }

        // 2b. Last resort: governor + frequencies is almost certainly the GPU on mobile
        //     SoCs, because CPUs use cpufreq rather than devfreq.
        for (root in roots) {
            val complete = access["$root/governor"]?.exists == true &&
                access["$root/available_frequencies"]?.exists == true &&
                access["$root/max_freq"]?.exists == true
            if (complete) return root
        }
        return null
    }

    private fun classify(root: String): Vendor = when {
        root.contains("kgsl") -> Vendor.ADRENO
        root.contains("mali", ignoreCase = true) -> Vendor.MALI
        root.contains("pvrsrv", ignoreCase = true) || root.contains("sgxfreq") -> Vendor.POWERVR
        root.contains("dfrgx", ignoreCase = true) -> Vendor.XCLIPSE
        root.contains("tegra", ignoreCase = true) -> Vendor.TEGRA
        else -> Vendor.GENERIC
    }

    private companion object {
        /**
         * Files whose presence proves a directory really is a GPU control surface.
         * A directory name alone is never treated as evidence.
         */
        val GPU_MARKERS: List<String> = listOf(
            "devfreq/governor",
            "governor",
            "gpuclk",
            "gpu_available_frequencies",
            "frequency_list",
            "max_pwrlevel",
        )
    }
}
