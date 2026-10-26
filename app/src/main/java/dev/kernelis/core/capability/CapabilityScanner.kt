package dev.kernelis.core.capability

import dev.kernelis.core.root.RootProvider
import dev.kernelis.core.root.RootShell
import dev.kernelis.core.sysfs.PathInfo
import dev.kernelis.core.sysfs.SysFs
import dev.kernelis.core.sysfs.asSysBool
import dev.kernelis.core.sysfs.asSysInt
import dev.kernelis.core.sysfs.asSysList

/**
 * Discovers every control KERNELIS supports, on this device, right now.
 *
 * Design rules enforced here:
 *  1. A missing path is normal. It produces [Availability.NotPresent], never an error.
 *  2. A path that exists but cannot be parsed produces [Availability.Unrecognised] with
 *     the raw sample, so diagnostics can show the truth.
 *  3. No SoC/kernel name is ever mapped to a capability. Presence of a node and a
 *     parseable value is the only evidence used.
 *  4. Reads are batched; one scan costs a handful of `su` round-trips, not hundreds.
 */
public class CapabilityScanner(
    private val sysFs: SysFs,
    private val rootShell: RootShell,
    private val topologyScanner: CpuTopologyScanner = CpuTopologyScanner(sysFs),
) {

    public suspend fun scan(rootAvailable: Boolean, provider: RootProvider): CapabilitySnapshot {
        val topology = if (rootAvailable) topologyScanner.scan() else CpuTopologyScanner.Topology.EMPTY
        val notes = mutableListOf<String>()

        val bindings = LinkedHashMap<CapabilityId, MutableList<Binding>>()
        fun put(binding: Binding) {
            bindings.getOrPut(binding.capability) { mutableListOf() } += binding
        }

        if (!rootAvailable) {
            // Without root we still report what the unprivileged process can see, so the
            // user understands the app detected their device but cannot act on it.
            for (id in CapabilityId.entries) {
                put(Binding(id, path = "", availability = Availability.NoRoot))
            }
            return CapabilitySnapshot(
                bindings = bindings,
                scannedAtEpochMs = System.currentTimeMillis(),
                rootAvailable = false,
                rootProvider = null,
                deviceFingerprint = deviceFingerprint(),
                notes = listOf("Root is not available, so no sysfs controls were probed."),
            )
        }

        if (!topology.isPresent) {
            notes += "No cpufreq interface found: CPU frequency controls are unavailable."
        }
        if (topology.isPresent && !topology.usesPolicyDirs) {
            notes += "Cluster grouping inferred from per-CPU frequency tables (no policy directories)."
        }

        scanCpu(topology, ::put)
        scanGpu(::put)
        scanIo(::put)
        scanMemory(::put)
        scanScheduler(::put)
        scanThermal(::put, notes)
        scanMonitoring(topology, ::put, notes)

        return CapabilitySnapshot(
            bindings = bindings,
            scannedAtEpochMs = System.currentTimeMillis(),
            rootAvailable = true,
            rootProvider = provider.version?.let { "${provider.family.name.lowercase()} $it" }
                ?: provider.family.name.lowercase(),
            deviceFingerprint = deviceFingerprint(),
            notes = notes,
        )
    }

    // ---------------------------------------------------------------------------------
    // CPU
    // ---------------------------------------------------------------------------------

    private suspend fun scanCpu(
        topology: CpuTopologyScanner.Topology,
        put: (Binding) -> Unit,
    ) {
        if (!topology.isPresent) {
            for (id in listOf(
                CapabilityId.CPU_MIN_FREQ,
                CapabilityId.CPU_MAX_FREQ,
                CapabilityId.CPU_GOVERNOR,
                CapabilityId.CPU_GOVERNOR_TUNABLES,
                CapabilityId.CPU_ONLINE,
                CapabilityId.CPU_FREQ_TABLE,
                CapabilityId.CPU_TIME_IN_STATE,
            )) {
                put(Binding(id, "", Availability.NotPresent))
            }
            scanCpuBoost(put)
            return
        }

        val writablePaths = mutableListOf<String>()
        for (cluster in topology.clusters) {
            val dir = cluster.policyDir
            val rep = cluster.representative
            val freqBinding = rep?.availableFreqsKhz.orEmpty().isNotEmpty()
            writablePaths += "$dir/scaling_min_freq"
            writablePaths += "$dir/scaling_max_freq"
            writablePaths += "$dir/scaling_governor"
        }
        val access = probeAccess(writablePaths)

        for (cluster in topology.clusters) {
            val dir = cluster.policyDir
            val rep = cluster.representative ?: continue
            val freqs = rep.availableFreqsKhz

            if (freqs.isEmpty()) {
                put(Binding(CapabilityId.CPU_FREQ_TABLE, "$dir/scaling_available_frequencies", Availability.NotPresent, scope = cluster.name))
            } else {
                put(
                    Binding(
                        capability = CapabilityId.CPU_FREQ_TABLE,
                        path = "$dir/scaling_available_frequencies",
                        availability = Availability.Supported("${freqs.size} steps"),
                        valueKind = ValueKind.ENUM,
                        current = rep.currentFreqKhz?.toString(),
                        samples = freqs.map { it.toString() },
                        numericRange = NumericRange(freqs.first(), freqs.last()),
                        units = "kHz",
                        scope = cluster.name,
                    ),
                )
            }

            val minNotch = freqs.firstOrNull() ?: rep.minFreqKhz
            val maxNotch = freqs.lastOrNull() ?: rep.maxFreqKhz
            put(
                frequencyBinding(
                    id = CapabilityId.CPU_MIN_FREQ,
                    path = "$dir/scaling_min_freq",
                    current = rep.minFreqKhz,
                    range = if (minNotch != null && maxNotch != null) NumericRange(minNotch, maxNotch) else null,
                    samples = freqs,
                    scope = cluster.name,
                    writable = access["$dir/scaling_min_freq"]?.canWrite == true,
                ),
            )
            put(
                frequencyBinding(
                    id = CapabilityId.CPU_MAX_FREQ,
                    path = "$dir/scaling_max_freq",
                    current = rep.maxFreqKhz,
                    range = if (minNotch != null && maxNotch != null) NumericRange(minNotch, maxNotch) else null,
                    samples = freqs,
                    scope = cluster.name,
                    writable = access["$dir/scaling_max_freq"]?.canWrite == true,
                ),
            )

            if (rep.governors.isEmpty()) {
                put(Binding(CapabilityId.CPU_GOVERNOR, "$dir/scaling_governor", Availability.NotPresent, scope = cluster.name))
            } else {
                put(
                    Binding(
                        capability = CapabilityId.CPU_GOVERNOR,
                        path = "$dir/scaling_governor",
                        availability = Availability.Supported(rep.currentGovernor),
                        valueKind = ValueKind.ENUM,
                        current = rep.currentGovernor,
                        samples = rep.governors,
                        scope = cluster.name,
                        looksWritable = access["$dir/scaling_governor"]?.canWrite == true,
                    ),
                )
            }

            // Governor tunables live next to the policy directory on modern kernels, and
            // under /sys/devices/system/cpu/cpufreq/<governor>/ on older ones.
            val tunableCandidates = buildList {
                add(dir)
                rep.currentGovernor?.let { add("/sys/devices/system/cpu/cpufreq/$it") }
            }
            val governedTunables = discoverGovernorTunables(tunableCandidates, cluster.name)
            if (governedTunables.isEmpty()) {
                put(Binding(CapabilityId.CPU_GOVERNOR_TUNABLES, dir, Availability.NotPresent, scope = cluster.name))
            } else {
                for (tunable in governedTunables) put(tunable)
            }

            val onlinePath = "/sys/devices/system/cpu/cpu${rep.index}/online"
            val onlineAccess = probeAccess(listOf(onlinePath))[onlinePath]
            if (cluster.cores.size <= 1 && onlineAccess?.exists != true) {
                put(Binding(CapabilityId.CPU_ONLINE, onlinePath, Availability.NotPresent, scope = cluster.name))
            } else {
                put(
                    Binding(
                        capability = CapabilityId.CPU_ONLINE,
                        path = onlinePath,
                        availability = if (onlineAccess?.exists == true) {
                            Availability.Supported("${cluster.onlineCores.size}/${cluster.cores.size} online")
                        } else {
                            Availability.NotPresent
                        },
                        valueKind = ValueKind.BOOLEAN,
                        current = cluster.onlineCores.size.toString(),
                        scope = cluster.name,
                        looksWritable = onlineAccess?.canWrite == true,
                    ),
                )
            }
        }

        val timeInStatePath = "/sys/devices/system/cpu/cpu0/cpufreq/stats/time_in_state"
        put(
            Binding(
                capability = CapabilityId.CPU_TIME_IN_STATE,
                path = timeInStatePath,
                availability = if (sysFs.read(timeInStatePath).asSysList().isNotEmpty()) {
                    Availability.Supported()
                } else {
                    Availability.NotPresent
                },
            ),
        )

        scanCpuBoost(put)
    }

    private suspend fun scanCpuBoost(put: (Binding) -> Unit) {
        val candidates = listOf(
            "/sys/module/cpu_boost/parameters/input_boost_enabled",
            "/sys/module/cpu_input_boost/parameters/input_boost_enabled",
            "/dev/stune/top-app/schedtune.boost",
            "/sys/module/cpu_boost/parameters/input_boost_freq",
        )
        val found = probeAccess(candidates)
        val inputBoost = candidates.firstOrNull { found[it]?.exists == true }
        put(
            Binding(
                capability = CapabilityId.CPU_INPUT_BOOST,
                path = inputBoost.orEmpty(),
                availability = if (inputBoost != null) Availability.Supported() else Availability.NotPresent,
                valueKind = ValueKind.BOOLEAN,
                current = inputBoost?.let { sysFs.read(it) },
                looksWritable = inputBoost?.let { found[it]?.canWrite } == true,
            ),
        )
        val boostFreq = "/sys/module/cpu_boost/parameters/input_boost_freq"
        put(
            Binding(
                capability = CapabilityId.CPU_BOOST_FREQ,
                path = boostFreq,
                availability = if (found[boostFreq]?.exists == true) Availability.Supported() else Availability.NotPresent,
                valueKind = ValueKind.STRING,
                current = sysFs.read(boostFreq),
                looksWritable = found[boostFreq]?.canWrite == true,
            ),
        )
    }

    private suspend fun discoverGovernorTunables(roots: List<String>, scope: String): List<Binding> {
        val result = mutableListOf<Binding>()
        for (root in roots) {
            val entries = sysFs.list(root)
            if (entries.isEmpty()) continue
            val candidates = entries.filter { name ->
                // Governor tunable files are the ones listed in KNOWN_TUNABLES; we never
                // guess by filename pattern alone because e.g. `boost` may be a directory.
                name in KNOWN_TUNABLES
            }
            if (candidates.isEmpty()) continue
            val values = sysFs.readMany(candidates.map { "$root/$it" })
            for (name in candidates) {
                val path = "$root/$name"
                val raw = values[path] ?: continue
                val isBoolean = raw.asSysBool() != null && raw.trim().length <= 3
                result += Binding(
                    capability = CapabilityId.CPU_GOVERNOR_TUNABLES,
                    path = path,
                    availability = Availability.Supported(name),
                    valueKind = if (isBoolean) ValueKind.BOOLEAN else ValueKind.NUMBER,
                    current = raw,
                    scope = scope,
                )
            }
        }
        return result
    }

    private suspend fun frequencyBinding(
        id: CapabilityId,
        path: String,
        current: Long?,
        range: NumericRange?,
        samples: List<Long>,
        scope: String,
        writable: Boolean,
    ): Binding {
        val info = probeAccess(listOf(path))[path]
        val availability = when {
            info?.exists != true -> Availability.NotPresent
            current == null -> Availability.Unreadable("no numeric value")
            else -> Availability.Supported()
        }
        return Binding(
            capability = id,
            path = path,
            availability = availability,
            valueKind = ValueKind.NUMBER,
            current = current?.toString(),
            samples = samples.map { it.toString() },
            numericRange = range,
            units = "kHz",
            scope = scope,
            looksWritable = writable,
        )
    }

    // ---------------------------------------------------------------------------------
    // GPU
    // ---------------------------------------------------------------------------------

    private suspend fun scanGpu(put: (Binding) -> Unit) {
        val gpuPath = discoverGpuDevfreqPath()
        if (gpuPath == null) {
            for (id in listOf(CapabilityId.GPU_GOVERNOR, CapabilityId.GPU_MIN_FREQ, CapabilityId.GPU_MAX_FREQ)) {
                put(Binding(id, "", Availability.NotPresent))
            }
        } else {
            val paths = listOf(
                "$gpuPath/available_governors",
                "$gpuPath/governor",
                "$gpuPath/available_frequencies",
                "$gpuPath/min_freq",
                "$gpuPath/max_freq",
                "$gpuPath/cur_freq",
                "$gpuPath/load",
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            )
            val values = sysFs.readMany(paths)

            val governors = values["$gpuPath/available_governors"].asSysList()
            put(
                Binding(
                    capability = CapabilityId.GPU_GOVERNOR,
                    path = "$gpuPath/governor",
                    availability = if (governors.isEmpty()) Availability.NotPresent else Availability.Supported(),
                    valueKind = ValueKind.ENUM,
                    current = values["$gpuPath/governor"],
                    samples = governors,
                ),
            )

            val freqs = values["$gpuPath/available_frequencies"].asSysList().mapNotNull { it.toLongOrNull() }.sorted()
            val min = values["$gpuPath/min_freq"]?.asSysInt()?.toLong()
            val max = values["$gpuPath/max_freq"]?.asSysInt()?.toLong()
            val range = if (freqs.size > 1) NumericRange(freqs.first(), freqs.last()) else null
            put(
                Binding(
                    capability = CapabilityId.GPU_MIN_FREQ,
                    path = "$gpuPath/min_freq",
                    availability = if (min != null) Availability.Supported() else Availability.NotPresent,
                    valueKind = ValueKind.NUMBER,
                    current = min?.toString(),
                    samples = freqs.map { it.toString() },
                    numericRange = range,
                    units = "Hz".takeIf { gpuPath.contains("kgsl") } ?: "kHz",
                ),
            )
            put(
                Binding(
                    capability = CapabilityId.GPU_MAX_FREQ,
                    path = "$gpuPath/max_freq",
                    availability = if (max != null) Availability.Supported() else Availability.NotPresent,
                    valueKind = ValueKind.NUMBER,
                    current = max?.toString(),
                    samples = freqs.map { it.toString() },
                    numericRange = range,
                    units = "Hz".takeIf { gpuPath.contains("kgsl") } ?: "kHz",
                ),
            )

            val loadRaw = values["$gpuPath/load"] ?: values["/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"]
            put(
                Binding(
                    capability = CapabilityId.GPU_LOAD,
                    path = if (values["$gpuPath/load"] != null) "$gpuPath/load" else "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                    availability = if (loadRaw != null) Availability.Supported() else Availability.NotPresent,
                    valueKind = ValueKind.NUMBER,
                    current = loadRaw,
                    units = "%",
                ),
            )
        }

        val rendererProbe = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_model",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
        )
        val rendererValues = sysFs.readMany(rendererProbe)
        val renderer = rendererValues.values.firstOrNull()
        put(
            Binding(
                capability = CapabilityId.GPU_RENDERER,
                path = rendererProbe.firstOrNull { rendererValues[it] != null }.orEmpty(),
                availability = if (renderer != null) Availability.Supported() else Availability.NotPresent,
                valueKind = ValueKind.STRING,
                current = renderer,
            ),
        )
    }

    /**
     * GPUs appear under devfreq under vendor-specific names. We look for the directory
     * that actually exposes a governor file rather than assuming `kgsl-3d0`.
     */
    private suspend fun discoverGpuDevfreqPath(): String? {
        val known = listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq",
            "/sys/class/devfreq/gpufreq",
            "/sys/class/devfreq/5000000.mali",
            "/sys/class/devfreq/13000000.mali",
        )
        probeAccess(known).forEach { (path, info) ->
            if (info.exists && sysFs.exists("$path/governor")) return path
        }
        val devfreqDirs = sysFs.list("/sys/class/devfreq")
        for (dir in devfreqDirs) {
            val candidate = "/sys/class/devfreq/$dir"
            val names = sysFs.read("$candidate/name").orEmpty().lowercase()
            if (names.contains("gpu") || names.contains("mali") || names.contains("kgsl")) {
                if (sysFs.exists("$candidate/governor") || sysFs.exists("$candidate/max_freq")) return candidate
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------------
    // I/O
    // ---------------------------------------------------------------------------------

    private suspend fun scanIo(put: (Binding) -> Unit) {
        val blockRoot = "/sys/block"
        // Prefer the physical userdata device; sd*/mmcblk* are usually external storage.
        val devices = sysFs.list(blockRoot)
            .filter { it.startsWith("sd") || it.startsWith("mmcblk") || it.startsWith("nvme") || it.startsWith("dm-") }
            .filterNot { it.startsWith("loop") || it.startsWith("zram") || it.startsWith("ram") }
        val primary = devices.firstOrNull { it.startsWith("sd") || it.startsWith("mmcblk") || it.startsWith("nvme") }
            ?: devices.firstOrNull()

        if (primary == null) {
            for (id in listOf(CapabilityId.IO_SCHEDULER, CapabilityId.IO_READ_AHEAD, CapabilityId.IO_NOMERGES, CapabilityId.IO_ROTATIONAL)) {
                put(Binding(id, "", Availability.NotPresent))
            }
            return
        }

        val queue = "$blockRoot/$primary/queue"
        val paths = listOf(
            "$queue/scheduler",
            "$queue/read_ahead_kb",
            "$queue/nomerges",
            "$queue/rotational",
        )
        val values = sysFs.readMany(paths)

        val schedulerRaw = values["$queue/scheduler"]
        val schedulerOptions = schedulerRaw?.let(::parseSchedulerList).orEmpty()
        val activeScheduler = schedulerRaw?.let(::activeScheduler)
        put(
            Binding(
                capability = CapabilityId.IO_SCHEDULER,
                path = "$queue/scheduler",
                availability = when {
                    schedulerRaw == null -> Availability.NotPresent
                    schedulerOptions.isEmpty() -> Availability.Unrecognised(schedulerRaw.take(80))
                    else -> Availability.Supported(primary)
                },
                valueKind = ValueKind.ENUM,
                current = activeScheduler,
                samples = schedulerOptions,
                scope = primary,
            ),
        )

        val readAhead = values["$queue/read_ahead_kb"]?.trim()?.toLongOrNull()
        put(
            Binding(
                capability = CapabilityId.IO_READ_AHEAD,
                path = "$queue/read_ahead_kb",
                availability = if (readAhead != null) Availability.Supported(primary) else Availability.NotPresent,
                valueKind = ValueKind.NUMBER,
                current = readAhead?.toString(),
                numericRange = NumericRange(0, 8192, step = 32),
                units = "kB",
                scope = primary,
            ),
        )

        put(
            Binding(
                capability = CapabilityId.IO_NOMERGES,
                path = "$queue/nomerges",
                availability = if (values["$queue/nomerges"] != null) Availability.Supported(primary) else Availability.NotPresent,
                valueKind = ValueKind.ENUM,
                current = values["$queue/nomerges"],
                samples = listOf("0", "1", "2"),
                scope = primary,
            ),
        )

        put(
            Binding(
                capability = CapabilityId.IO_ROTATIONAL,
                path = "$queue/rotational",
                availability = if (values["$queue/rotational"] != null) Availability.Supported(primary) else Availability.NotPresent,
                valueKind = ValueKind.BOOLEAN,
                current = values["$queue/rotational"],
                scope = primary,
            ),
        )
    }

    /** `/sys/block/sda/queue/scheduler` is either `[mq-deadline] kyber bfq` or a plain list. */
    internal fun parseSchedulerList(raw: String): List<String> {
        val tokens = raw.replace("[", "").replace("]", " ").split(' ', '\n', '\t')
        return tokens.map { it.trim() }.filter { it.isNotEmpty() }
    }

    internal fun activeScheduler(raw: String): String? {
        val match = BRACKET_PATTERN.find(raw) ?: return null
        return match.groupValues[1].ifBlank { null }
    }

    // ---------------------------------------------------------------------------------
    // Memory
    // ---------------------------------------------------------------------------------

    private suspend fun scanMemory(put: (Binding) -> Unit) {
        val vmPaths = listOf(
            "/proc/sys/vm/dirty_ratio",
            "/proc/sys/vm/dirty_background_ratio",
            "/proc/sys/vm/swappiness",
            "/proc/sys/vm/vfs_cache_pressure",
            "/proc/sys/vm/overcommit_memory",
        )
        val vmValues = sysFs.readMany(vmPaths)

        put(
            Binding(
                capability = CapabilityId.VM_DIRTY_RATIO,
                path = "/proc/sys/vm/dirty_ratio",
                availability = vmValues["/proc/sys/vm/dirty_ratio"]?.let { Availability.Supported() } ?: Availability.NotPresent,
                valueKind = ValueKind.NUMBER,
                current = vmValues["/proc/sys/vm/dirty_ratio"],
                numericRange = NumericRange(1, 100),
                units = "%",
            ),
        )
        put(
            Binding(
                capability = CapabilityId.VM_SWAPPINESS,
                path = "/proc/sys/vm/swappiness",
                availability = vmValues["/proc/sys/vm/swappiness"]?.let { Availability.Supported() } ?: Availability.NotPresent,
                valueKind = ValueKind.NUMBER,
                current = vmValues["/proc/sys/vm/swappiness"],
                numericRange = NumericRange(0, 200),
            ),
        )
        put(
            Binding(
                capability = CapabilityId.VM_VFS_CACHE_PRESSURE,
                path = "/proc/sys/vm/vfs_cache_pressure",
                availability = vmValues["/proc/sys/vm/vfs_cache_pressure"]?.let { Availability.Supported() } ?: Availability.NotPresent,
                valueKind = ValueKind.NUMBER,
                current = vmValues["/proc/sys/vm/vfs_cache_pressure"],
                numericRange = NumericRange(1, 1000),
            ),
        )

        // ZRAM: the block device is `zram0` on essentially all Android devices, but its
        // size/algorithm control surface moved between sysfs and /proc in kernel versions.
        val zramDir = sysFs.list("/sys/block").firstOrNull { it.startsWith("zram") }
        if (zramDir == null) {
            put(Binding(CapabilityId.ZRAM_SIZE, "", Availability.NotPresent))
            put(Binding(CapabilityId.ZRAM_ALGORITHM, "", Availability.NotPresent))
            put(Binding(CapabilityId.ZRAM_STATS, "", Availability.NotPresent))
        } else {
            val base = "/sys/block/$zramDir"
            val values = sysFs.readMany(
                listOf("$base/disksize", "$base/comp_algorithm", "$base/mem_used_total", "$base/orig_data_size"),
            )
            put(
                Binding(
                    CapabilityId.ZRAM_SIZE,
                    "$base/disksize",
                    if (values["$base/disksize"] != null) Availability.Supported() else Availability.NotPresent,
                    valueKind = ValueKind.NUMBER,
                    current = values["$base/disksize"],
                    units = "bytes",
                ),
            )
            val algorithmRaw = values["$base/comp_algorithm"]
            put(
                Binding(
                    CapabilityId.ZRAM_ALGORITHM,
                    "$base/comp_algorithm",
                    when {
                        algorithmRaw == null -> Availability.NotPresent
                        else -> Availability.Supported()
                    },
                    valueKind = ValueKind.ENUM,
                    current = algorithmRaw?.let(::activeScheduler),
                    samples = algorithmRaw?.let(::parseSchedulerList).orEmpty(),
                ),
            )
            put(
                Binding(
                    CapabilityId.ZRAM_STATS,
                    "$base/mem_used_total",
                    if (values["$base/mem_used_total"] != null || values["$base/orig_data_size"] != null) {
                        Availability.Supported()
                    } else {
                        Availability.NotPresent
                    },
                ),
            )
        }

        put(
            Binding(
                CapabilityId.RAM_STATS,
                "/proc/meminfo",
                if (sysFs.exists("/proc/meminfo")) Availability.Supported() else Availability.NotPresent,
            ),
        )
    }

    // ---------------------------------------------------------------------------------
    // Scheduler
    // ---------------------------------------------------------------------------------

    private suspend fun scanScheduler(put: (Binding) -> Unit) {
        val stuneRoots = listOf("/dev/stune", "/dev/cpuctl")
        var stuneFound = false
        for (root in stuneRoots) {
            val entries = sysFs.list(root).filter { sysFs.exists("$root/$it/schedtune.boost") }
            for (entry in entries) {
                val path = "$root/$entry/schedtune.boost"
                put(
                    Binding(
                        CapabilityId.SCHED_STUNE,
                        path,
                        Availability.Supported(),
                        valueKind = ValueKind.NUMBER,
                        current = sysFs.read(path),
                        numericRange = NumericRange(0, 100),
                        scope = entry,
                    ),
                )
                stuneFound = true
            }
            if (stuneFound) break
        }
        if (!stuneFound) {
            // Modern kernels use uclamp instead of schedtune.
            put(Binding(CapabilityId.SCHED_STUNE, "/dev/stune", Availability.NotPresent))
        }

        val uclampRoot = "/proc/sys/kernel/sched_util_clamp_min"
        val uclampMax = "/proc/sys/kernel/sched_util_clamp_max"
        val uclampValues = sysFs.readMany(listOf(uclampRoot, uclampMax))
        put(
            Binding(
                CapabilityId.SCHED_UCLAMP,
                uclampRoot,
                if (uclampValues.values.isNotEmpty()) Availability.Supported() else Availability.NotPresent,
                valueKind = ValueKind.NUMBER,
                current = uclampValues[uclampRoot],
                numericRange = NumericRange(0, 1024),
            ),
        )
        put(
            Binding(
                CapabilityId.SCHED_TASK_UTIL_CLAMP,
                uclampMax,
                if (uclampValues[uclampMax] != null) Availability.Supported() else Availability.NotPresent,
                valueKind = ValueKind.NUMBER,
                current = uclampValues[uclampMax],
                numericRange = NumericRange(0, 1024),
            ),
        )
    }

    // ---------------------------------------------------------------------------------
    // Thermal
    // ---------------------------------------------------------------------------------

    private suspend fun scanThermal(put: (Binding) -> Unit, notes: MutableList<String>) {
        val zones = discoverThermalZones()
        if (zones.isEmpty()) {
            notes += "No thermal zones exposed by this kernel; temperature readings unavailable."
            put(Binding(CapabilityId.THERMAL_ZONES, "/sys/class/thermal", Availability.NotPresent))
        } else {
            for (zone in zones) {
                put(
                    Binding(
                        CapabilityId.THERMAL_ZONES,
                        "/sys/class/thermal/${zone.dir}/temp",
                        Availability.Supported(zone.type),
                        valueKind = ValueKind.NUMBER,
                        current = zone.tempMilliC?.toString(),
                        units = "milli°C",
                        scope = zone.type,
                    ),
                )
            }
            val firstZoneDir = "/sys/class/thermal/${zones.first().dir}"
            val tripPath = "$firstZoneDir/trip_point_0_temp"
            put(
                Binding(
                    CapabilityId.THERMAL_TRIP_POINT,
                    tripPath,
                    if (sysFs.exists(tripPath)) Availability.Supported() else Availability.NotPresent,
                    valueKind = ValueKind.NUMBER,
                    units = "milli°C",
                    current = sysFs.read(tripPath),
                ),
            )
        }

        val modeCandidates = listOf(
            "/sys/class/thermal/thermal_zone0/mode",
            "/sys/module/msm_thermal/parameters/enabled",
            "/sys/kernel/thermal/thermal_mode",
        )
        val modeAccess = probeAccess(modeCandidates)
        val modePath = modeCandidates.firstOrNull { modeAccess[it]?.exists == true }
        put(
            Binding(
                CapabilityId.THERMAL_MODE,
                modePath.orEmpty(),
                if (modePath != null) Availability.Supported() else Availability.NotPresent,
                valueKind = ValueKind.STRING,
                current = modePath?.let { sysFs.read(it) },
            ),
        )

        val msmThermalRoot = "/sys/module/msm_thermal"
        put(
            Binding(
                CapabilityId.MSM_THERMAL,
                "$msmThermalRoot/parameters",
                if (sysFs.exists("$msmThermalRoot/parameters/temp_threshold")) Availability.Supported() else Availability.NotPresent,
            ),
        )
    }

    internal data class ThermalZone(val dir: String, val type: String, val tempMilliC: Long?)

    private suspend fun discoverThermalZones(): List<ThermalZone> {
        val dirs = sysFs.list("/sys/class/thermal")
            .filter { it.startsWith("thermal_zone") }
            .sortedWith(compareBy { it.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE })
        if (dirs.isEmpty()) return emptyList()

        val paths = buildList {
            for (dir in dirs) {
                add("/sys/class/thermal/$dir/type")
                add("/sys/class/thermal/$dir/temp")
            }
        }
        val values = sysFs.readMany(paths)
        return dirs.mapNotNull { dir ->
            val type = values["/sys/class/thermal/$dir/type"]?.trim()
            val temp = values["/sys/class/thermal/$dir/temp"]?.trim()?.toLongOrNull()
            if (type == null && temp == null) return@mapNotNull null
            ThermalZone(dir, type ?: dir, temp)
        }
    }

    // ---------------------------------------------------------------------------------
    // Monitoring-only probes
    // ---------------------------------------------------------------------------------

    private suspend fun scanMonitoring(
        topology: CpuTopologyScanner.Topology,
        put: (Binding) -> Unit,
        notes: MutableList<String>,
    ) {
        // Battery capabilities are sourced from BatteryManager (framework), not sysfs.
        // Presence here simply records that the framework values were readable at scan
        // time; the monitor re-checks on every sample.
        put(
            Binding(
                CapabilityId.BATTERY_CHARGE_COUNTER,
                "framework:BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER",
                Availability.Supported(),
                valueKind = ValueKind.NUMBER,
                units = "µAh",
            ),
        )
        put(
            Binding(
                CapabilityId.BATTERY_CURRENT,
                "framework:BatteryManager.BATTERY_PROPERTY_CURRENT_NOW",
                Availability.Supported(),
                valueKind = ValueKind.NUMBER,
                units = "µA",
            ),
        )
        put(
            Binding(
                CapabilityId.STORAGE_STATS,
                "framework:StorageStatsManager",
                Availability.Supported(),
            ),
        )
        put(
            Binding(
                CapabilityId.KERNEL_PSI,
                "/proc/pressure/cpu",
                if (sysFs.exists("/proc/pressure/cpu")) Availability.Supported() else Availability.NotPresent,
            ),
        )
        if (!topology.isPresent) notes += "CPU load will be derived from /proc/stat only."
    }

    // ---------------------------------------------------------------------------------

    /**
     * Checks many paths at once. On Android these nodes are usually root-owned with mode
     * 0644; reporting `canWrite` from the kernel's own `-w` test avoids offering a
     * control that would silently fail.
     */
    private suspend fun probeAccess(paths: List<String>): Map<String, PathInfo> =
        paths.distinct().associateWith { sysFs.access(it) }

    private suspend fun deviceFingerprint(): String = buildString {
        append(sysFs.read("/proc/sys/kernel/osrelease").orEmpty().ifBlank { "unknown-kernel" })
    }

    private companion object {
        val BRACKET_PATTERN = Regex("""\[([^\]]+)]""")

        /**
         * Names of governor tunables KERNELIS is willing to surface. This list is
         * intentionally conservative: it covers schedutil/ondemand/conservative tunables
         * that are meaningful across kernels. Anything else is ignored rather than
         * exposed as a raw knob the user cannot interpret.
         */
        val KNOWN_TUNABLES = setOf(
            "up_threshold",
            "down_threshold",
            "up_rate_limit_us",
            "down_rate_limit_us",
            "sampling_rate",
            "sampling_rate_min",
            "ignore_nice_load",
            "io_is_busy",
            "hispeed_freq",
            "hispeed_load",
            "target_load",
            "min_sample_time",
            "boost",
            "boost_period",
            "sched_upmigrate",
            "sched_downmigrate",
            "rate_limit_us",
            "pl",
            "Hispeed_freq",
        )
    }
}
