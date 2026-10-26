package dev.kernelis.testing

import dev.kernelis.core.root.RootProvider
import dev.kernelis.core.root.RootShell
import dev.kernelis.core.root.ShellCommand
import dev.kernelis.core.root.ShellResult
import dev.kernelis.core.sysfs.PathInfo
import dev.kernelis.core.sysfs.SysFs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A synthetic device.
 *
 * Tests describe only the sysfs nodes a given kernel would expose; everything above
 * (scanner, planner, validator) runs unmodified against it. This is how KERNELIS can
 * prove "we do not assume a node exists" without a physical phone per test case.
 */
public class FakeSysFs(initial: Map<String, String> = emptyMap()) : SysFs {
    private val files = LinkedHashMap<String, String>(initial)
    private val directories = LinkedHashSet<String>()
    private val writable = LinkedHashSet<String>()
    private val mutex = Mutex()

    @Volatile
    public var readFailure: Set<String> = emptySet()

    /** Records every path written, in order, for assertions. */
    public val writeLog: MutableList<Pair<String, String>> = mutableListOf()

    public fun put(path: String, value: String, canWrite: Boolean = true): FakeSysFs {
        files[path] = value
        if (canWrite) writable += path
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty() && parent != "/") {
            directories += parent
            parent = parent.substringBeforeLast('/', "")
        }
        return this
    }

    public fun writeFile(path: String, value: String) {
        files[path] = value
        writeLog += path to value
    }

    public override suspend fun read(path: String): String? = mutex.withLock {
        if (path in readFailure) null else files[path]
    }

    public override suspend fun exists(path: String): Boolean = read(path) != null

    public override suspend fun list(path: String): List<String> = mutex.withLock {
        val prefix = if (path.endsWith('/')) path else "$path/"
        val children = LinkedHashSet<String>()
        for (key in files.keys) {
            if (key.startsWith(prefix)) {
                val rest = key.removePrefix(prefix)
                if ('/' !in rest && rest.isNotEmpty()) children += rest
            }
        }
        for (dir in directories) {
            if (dir.startsWith(prefix)) {
                val rest = dir.removePrefix(prefix)
                if ('/' !in rest && rest.isNotEmpty()) children += rest
            }
        }
        children.toList()
    }

    public override suspend fun readMany(paths: Collection<String>): Map<String, String> = mutex.withLock {
        val out = LinkedHashMap<String, String>()
        for (path in paths) {
            if (path in readFailure) continue
            files[path]?.let { out[path] = it }
        }
        out
    }

    public override suspend fun access(path: String): PathInfo = mutex.withLock {
        val exists = files.containsKey(path) || directories.contains(path)
        PathInfo(
            path = path,
            exists = exists,
            canRead = exists && path !in readFailure,
            canWrite = path in writable,
        )
    }

    public fun value(path: String): String? = files[path]
}

/**
 * Root shell fake whose behaviour is scripted per test.
 *
 * [deny] simulates the user refusing the root prompt, [failWrites] simulates a
 * read-only node, and [clampWrites] simulates a kernel that silently ignores a value —
 * the three failure modes the writer must distinguish.
 */
public class FakeRootShell(
    private val sysFs: FakeSysFs? = null,
    public var available: Boolean = true,
    public var deny: Boolean = false,
    public var provider: RootProvider = RootProvider(RootProvider.Family.MAGISK, "28.1"),
) : RootShell {

    public val executed: MutableList<String> = mutableListOf()
    public var clampWrites: Boolean = false
    public var rejectWrites: Boolean = false

    override suspend fun isAvailable(): Boolean = available

    override suspend fun provider(): RootProvider = provider

    override suspend fun exec(command: ShellCommand): ShellResult {
        val script = command.args.lastOrNull().orEmpty()
        executed += script
        if (!available || deny) {
            return ShellResult(
                command = script,
                stdout = "",
                stderr = if (deny) "Permission denied" else "",
                exitCode = ShellResult.EXIT_NOT_EXECUTED,
                durationMs = 0,
                failure = if (deny) ShellFailurePermissionDenied else ShellFailureNoRoot,
            )
        }
        if (command.binary == "id" || script == "id -u") {
            return ShellResult(script, "0\n", "", 0, 0)
        }
        if (sysFs != null) {
            applyWriteEffect(script)
        }
        return ShellResult(script, "", "", 0, 0)
    }

    override suspend fun readText(path: String): String? = sysFs?.read(path)

    /** Mimics `printf X > path` for the writer tests. */
    private fun applyWriteEffect(script: String) {
        val match = Regex("""printf %s (.+) > (/\S+)""").find(script) ?: return
        val rawValue = match.groupValues[1].removeSurrounding("'").replace("'\\''", "'")
        val path = match.groupValues[2]
        when {
            rejectWrites -> Unit // kernel refuses to change the node
            clampWrites -> sysFs?.writeFile(path, "0")
            else -> sysFs?.writeFile(path, rawValue)
        }
    }

    public val ShellFailureNoRoot: dev.kernelis.core.root.ShellFailure
        get() = dev.kernelis.core.root.ShellFailure.NoRoot
    public val ShellFailurePermissionDenied: dev.kernelis.core.root.ShellFailure
        get() = dev.kernelis.core.root.ShellFailure.PermissionDenied("denied by policy")
}

/** A representative modern Android device: 4+4 clusters, KGSL GPU, zram, thermal. */
public fun modernDevice(): FakeSysFs = FakeSysFs()
    .put("/sys/devices/system/cpu/policy0/affected_cpus", "0 1 2 3")
    .put("/sys/devices/system/cpu/policy0/related_cpus", "0 1 2 3")
    .put("/sys/devices/system/cpu/policy0/scaling_available_frequencies", "300000 576000 1017000 1804000")
    .put("/sys/devices/system/cpu/policy0/scaling_min_freq", "300000")
    .put("/sys/devices/system/cpu/policy0/scaling_max_freq", "1804000")
    .put("/sys/devices/system/cpu/policy0/scaling_governor", "schedutil")
    .put("/sys/devices/system/cpu/policy0/scaling_available_governors", "performance powersave schedutil")
    .put("/sys/devices/system/cpu/policy0/up_rate_limit_us", "500")
    .put("/sys/devices/system/cpu/policy1/affected_cpus", "4 5 6 7")
    .put("/sys/devices/system/cpu/policy1/related_cpus", "4 5 6 7")
    .put("/sys/devices/system/cpu/policy1/scaling_available_frequencies", "710400 1209600 2419200 2841600")
    .put("/sys/devices/system/cpu/policy1/scaling_min_freq", "710400")
    .put("/sys/devices/system/cpu/policy1/scaling_max_freq", "2841600")
    .put("/sys/devices/system/cpu/policy1/scaling_governor", "schedutil")
    .put("/sys/devices/system/cpu/policy1/scaling_available_governors", "performance powersave schedutil")
    .put("/sys/devices/system/cpu/cpu0/online", "1", canWrite = false)
    .put("/sys/devices/system/cpu/cpu4/online", "1")
    .put("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", "1017000", canWrite = false)
    .put("/sys/devices/system/cpu/cpu1/cpufreq/scaling_cur_freq", "1017000", canWrite = false)
    .put("/sys/devices/system/cpu/cpu2/cpufreq/scaling_cur_freq", "1017000", canWrite = false)
    .put("/sys/devices/system/cpu/cpu3/cpufreq/scaling_cur_freq", "1017000", canWrite = false)
    .put("/sys/devices/system/cpu/cpu4/cpufreq/scaling_cur_freq", "2419200", canWrite = false)
    .put("/sys/devices/system/cpu/cpu5/cpufreq/scaling_cur_freq", "2419200", canWrite = false)
    .put("/sys/devices/system/cpu/cpu6/cpufreq/scaling_cur_freq", "2419200", canWrite = false)
    .put("/sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq", "2419200", canWrite = false)
    .put("/sys/devices/system/cpu/cpufreq/schedutil/up_rate_limit_us", "500")
    .put("/sys/class/kgsl/kgsl-3d0/devfreq/governor", "msm-adreno-tz")
    .put("/sys/class/kgsl/kgsl-3d0/devfreq/available_governors", "msm-adreno-tz performance simple_ondemand")
    .put("/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies", "257000000 430000000 587000000")
    .put("/sys/class/kgsl/kgsl-3d0/devfreq/min_freq", "257000000")
    .put("/sys/class/kgsl/kgsl-3d0/devfreq/max_freq", "587000000")
    .put("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "12 %", canWrite = false)
    .put("/sys/block/sda/queue/scheduler", "[mq-deadline] kyber bfq", canWrite = true)
    .put("/sys/block/sda/queue/read_ahead_kb", "128")
    .put("/sys/block/sda/queue/nomerges", "0")
    .put("/sys/block/sda/queue/rotational", "0")
    .put("/proc/sys/vm/dirty_ratio", "20")
    .put("/proc/sys/vm/swappiness", "60")
    .put("/proc/sys/vm/vfs_cache_pressure", "100")
    .put("/sys/block/zram0/disksize", "2147483648")
    .put("/sys/block/zram0/mem_used_total", "1048576", canWrite = false)
    .put("/sys/block/zram0/orig_data_size", "4194304", canWrite = false)
    .put("/sys/block/zram0/comp_algorithm", "[lz4] lz4hc zstd", canWrite = false)
    .put("/sys/class/thermal/thermal_zone0/type", "cpu-0-0-usr", canWrite = false)
    .put("/sys/class/thermal/thermal_zone0/temp", "45000", canWrite = false)
    .put("/sys/class/thermal/thermal_zone1/type", "gpuss-0-usr", canWrite = false)
    .put("/sys/class/thermal/thermal_zone1/temp", "41000", canWrite = false)
    .put("/sys/class/thermal/thermal_zone2/type", "battery", canWrite = false)
    .put("/sys/class/thermal/thermal_zone2/temp", "31000", canWrite = false)
    .put("/sys/class/thermal/thermal_zone0/mode", "enabled", canWrite = false)
    .put("/proc/meminfo", "MemTotal: 8000000 kB\nMemFree: 1000000 kB\nMemAvailable: 3000000 kB\nCached: 2000000 kB\nSwapTotal: 2147483647 kB\nSwapFree: 2000000 kB")
    .put("/proc/pressure/cpu", "some avg10=1.00 avg60=2.00 avg300=3.00 total=100\nfull avg10=0.50 avg60=1.00 avg300=1.50 total=50")
    .put("/proc/sys/kernel/osrelease", "5.10.198-android13")
    .put("/proc/stat", buildString {
        for (i in 0 until 8) append("cpu$i 1000 0 500 8000 100 0 0 0\n")
        append("cpu 8000 0 4000 64000 800 0 0 0\n")
    })

/** A deliberately minimal device: per-CPU cpufreq only, no GPU, no zram, no thermal. */
public fun minimalDevice(): FakeSysFs = FakeSysFs()
    .put("/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies", "300000 900000 1500000")
    .put("/sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq", "300000")
    .put("/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq", "1500000")
    .put("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor", "ondemand")
    .put("/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors", "ondemand powersave")
    .put("/proc/sys/vm/swappiness", "60")
    .put("/proc/sys/kernel/osrelease", "4.4.23")
    .put("/proc/stat", "cpu0 100 0 50 800 10 0 0 0\ncpu 100 0 50 800 10 0 0 0\n")
