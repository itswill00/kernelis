package dev.kernelis.core.capability

import dev.kernelis.core.sysfs.SysFs
import dev.kernelis.core.sysfs.asSysInt
import dev.kernelis.core.sysfs.asSysList

/**
 * Reads the CPU topology without assuming cluster count, core count or naming.
 *
 * Discovery order is deliberate: policy directories (`/sys/devices/system/cpu/cpufreq/policyN`)
 * are the modern, kernel-provided grouping and are preferred. When they are absent we
 * fall back to per-CPU `cpufreq` directories and reconstruct clusters by comparing the
 * frequency tables, which is what older Android kernels expose.
 */
public class CpuTopologyScanner(private val sysFs: SysFs) {

    public data class Core(
        val index: Int,
        val policyDir: String,
        val online: Boolean,
        val availableFreqsKhz: List<Long>,
        val minFreqKhz: Long?,
        val maxFreqKhz: Long?,
        val currentFreqKhz: Long?,
        val governors: List<String>,
        val currentGovernor: String?,
    ) {
        val readable: Boolean get() = availableFreqsKhz.isNotEmpty()
    }

    public data class Cluster(
        val index: Int,
        val name: String,
        val policyDir: String,
        val cores: List<Core>,
    ) {
        val onlineCores: List<Core> get() = cores.filter { it.online }
        val representative: Core? get() = onlineCores.firstOrNull() ?: cores.firstOrNull()
        val availableFreqsKhz: List<Long> get() = representative?.availableFreqsKhz.orEmpty()
        /** Human label derived from core count, never from a hard-coded SoC table. */
        val label: String get() = "$name · ${cores.size} core${if (cores.size == 1) "" else "s"}"
    }

    public data class Topology(
        val clusters: List<Cluster>,
        val cores: List<Core>,
        val usesPolicyDirs: Boolean,
        val onlineCoreCount: Int,
        val totalCoreCount: Int,
    ) {
        public val isPresent: Boolean get() = clusters.isNotEmpty()

        public companion object {
            public val EMPTY: Topology = Topology(emptyList(), emptyList(), false, 0, 0)
        }
    }

    public suspend fun scan(): Topology {
        val policyDirNames = sysFs.list(CPU_ROOT)
            .filter { it.matches(POLICY_PATTERN) }
            .sortedBy { it.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }

        if (policyDirNames.isNotEmpty()) {
            return scanViaPolicyDirs(policyDirNames)
        }
        return scanViaPerCpuDirs()
    }

    private suspend fun scanViaPolicyDirs(dirNames: List<String>): Topology {
        val allCores = mutableListOf<Core>()
        val clusters = mutableListOf<Cluster>()

        for ((clusterIndex, dirName) in dirNames.withIndex()) {
            val dir = "$CPU_ROOT/$dirName"
            val related = sysFs.read("$dir/affected_cpus").asSysList()
                .mapNotNull { it.trim().toIntOrNull() }
                .ifEmpty {
                    // Single-core policy (common for the prime core): infer from related_cpus.
                    sysFs.read("$dir/related_cpus").asSysList().mapNotNull { it.trim().toIntOrNull() }
                }

            // Batch all reads for this policy in one round-trip.
            val paths = buildList {
                add("$dir/scaling_available_frequencies")
                add("$dir/scaling_min_freq")
                add("$dir/scaling_max_freq")
                add("$dir/cpuinfo_min_freq")
                add("$dir/cpuinfo_max_freq")
                add("$dir/scaling_governor")
                add("$dir/scaling_available_governors")
                add("$dir/cpuinfo_cur_freq")
                related.forEach { add("$CPU_ROOT/cpu$it/cpufreq/scaling_cur_freq") }
                related.forEach { add("$CPU_ROOT/cpu$it/online") }
            }
            val values = sysFs.readMany(paths)

            val freqs = values["$dir/scaling_available_frequencies"].asSysList().mapNotNull { it.toLongOrNull() }.sorted()
            val governors = values["$dir/scaling_available_governors"].asSysList()
            val governor = values["$dir/scaling_governor"]?.trim()?.ifBlank { null }
            val minFreq = values["$dir/scaling_min_freq"].asSysInt()?.toLong()
                ?: values["$dir/cpuinfo_min_freq"].asSysInt()?.toLong()
            val maxFreq = values["$dir/scaling_max_freq"].asSysInt()?.toLong()
                ?: values["$dir/cpuinfo_max_freq"].asSysInt()?.toLong()

            val coreIndices = related.ifEmpty { listOf(clusterIndex) }
            val cores = coreIndices.map { coreIndex ->
                val onlineRaw = values["$CPU_ROOT/cpu$coreIndex/online"]?.trim()
                Core(
                    index = coreIndex,
                    policyDir = dir,
                    // cpu0 frequently has no `online` node because it cannot be offlined.
                    online = onlineRaw?.let { it == "1" } ?: true,
                    availableFreqsKhz = freqs,
                    minFreqKhz = minFreq,
                    maxFreqKhz = maxFreq,
                    currentFreqKhz = values["$CPU_ROOT/cpu$coreIndex/cpufreq/scaling_cur_freq"]?.trim()?.toLongOrNull()
                        ?: values["$dir/cpuinfo_cur_freq"]?.trim()?.toLongOrNull(),
                    governors = governors,
                    currentGovernor = governor,
                )
            }
            allCores += cores
            clusters += Cluster(
                index = clusterIndex,
                name = clusterName(clusterIndex, dirNames.size),
                policyDir = dir,
                cores = cores,
            )
        }

        return Topology(
            clusters = clusters,
            cores = allCores.sortedBy { it.index },
            usesPolicyDirs = true,
            onlineCoreCount = allCores.count { it.online },
            totalCoreCount = allCores.size,
        )
    }

    private suspend fun scanViaPerCpuDirs(): Topology {
        val cpuDirs = sysFs.list(CPU_ROOT)
            .filter { it.matches(CORE_PATTERN) }
            .mapNotNull { it.removePrefix("cpu").toIntOrNull() }
            .sorted()
        if (cpuDirs.isEmpty()) return Topology.EMPTY

        val paths = buildList {
            for (index in cpuDirs) {
                add("$CPU_ROOT/cpu$index/online")
                add("$CPU_ROOT/cpu$index/cpufreq/scaling_available_frequencies")
                add("$CPU_ROOT/cpu$index/cpufreq/scaling_min_freq")
                add("$CPU_ROOT/cpu$index/cpufreq/scaling_max_freq")
                add("$CPU_ROOT/cpu$index/cpufreq/cpuinfo_min_freq")
                add("$CPU_ROOT/cpu$index/cpufreq/cpuinfo_max_freq")
                add("$CPU_ROOT/cpu$index/cpufreq/scaling_cur_freq")
                add("$CPU_ROOT/cpu$index/cpufreq/scaling_governor")
                add("$CPU_ROOT/cpu$index/cpufreq/scaling_available_governors")
            }
        }
        val values = sysFs.readMany(paths)

        // Reconstruct clusters by grouping cores that advertise identical frequency tables.
        val bySignature = LinkedHashMap<String, MutableList<Int>>()
        for (index in cpuDirs) {
            val freqs = values["$CPU_ROOT/cpu$index/cpufreq/scaling_available_frequencies"].asSysList()
            val signature = freqs.joinToString(",").ifEmpty { "core-$index" }
            bySignature.getOrPut(signature) { mutableListOf() } += index
        }

        val allCores = mutableListOf<Core>()
        val clusters = mutableListOf<Cluster>()
        for ((clusterIndex, entry) in bySignature.entries.withIndex()) {
            val indices = entry.value.sorted()
            val policyDir = "$CPU_ROOT/cpu${indices.first()}/cpufreq"
            val freqs = entry.key.split(',').mapNotNull { it.toLongOrNull() }.sorted()
            val cores = indices.map { index ->
                val dir = "$CPU_ROOT/cpu$index/cpufreq"
                Core(
                    index = index,
                    policyDir = dir,
                    online = values["$CPU_ROOT/cpu$index/online"]?.trim()?.let { it == "1" } ?: true,
                    availableFreqsKhz = freqs,
                    minFreqKhz = values["$dir/scaling_min_freq"]?.trim()?.toLongOrNull()
                        ?: values["$dir/cpuinfo_min_freq"]?.trim()?.toLongOrNull(),
                    maxFreqKhz = values["$dir/scaling_max_freq"]?.trim()?.toLongOrNull()
                        ?: values["$dir/cpuinfo_max_freq"]?.trim()?.toLongOrNull(),
                    currentFreqKhz = values["$dir/scaling_cur_freq"]?.trim()?.toLongOrNull(),
                    governors = values["$dir/scaling_available_governors"].asSysList(),
                    currentGovernor = values["$dir/scaling_governor"]?.trim()?.ifBlank { null },
                )
            }
            allCores += cores
            clusters += Cluster(
                index = clusterIndex,
                name = clusterName(clusterIndex, bySignature.size),
                policyDir = policyDir,
                cores = cores,
            )
        }

        return Topology(
            clusters = clusters,
            cores = allCores.sortedBy { it.index },
            usesPolicyDirs = false,
            onlineCoreCount = allCores.count { it.online },
            totalCoreCount = allCores.size,
        )
    }

    /**
     * Positional naming only. We do not guess "little/big/prime": on unusual topologies
     * (e.g. 2+2+4) any such guess would be wrong, so clusters are named by position and
     * sized by their actual core count.
     */
    private fun clusterName(index: Int, count: Int): String = when (count) {
        1 -> "Cluster 0"
        2 -> if (index == 0) "Cluster A" else "Cluster B"
        3 -> when (index) {
            0 -> "Cluster A"
            1 -> "Cluster B"
            else -> "Cluster C"
        }
        else -> "Cluster ${index + 1}"
    }

    private companion object {
        const val CPU_ROOT = "/sys/devices/system/cpu"
        val POLICY_PATTERN = Regex("^policy\\d+$")
        val CORE_PATTERN = Regex("^cpu\\d+$")
    }
}
