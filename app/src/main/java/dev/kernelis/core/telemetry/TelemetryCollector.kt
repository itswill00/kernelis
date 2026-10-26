package dev.kernelis.core.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import dev.kernelis.core.capability.CapabilityId
import dev.kernelis.core.capability.CapabilitySnapshot
import dev.kernelis.core.sysfs.SysFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gathers one [TelemetryFrame] from the cheapest sources available.
 *
 * Cost discipline: a frame issues **one** batched `su` read plus a few framework calls.
 * The previous `/proc/stat` sample is retained to compute deltas, so no extra syscall is
 * needed per frame.
 *
 * Nothing here is simulated. If a source is unavailable the corresponding field is null
 * and the UI renders "N/A"; we never substitute zero.
 */
@Singleton
public class TelemetryCollector @Inject constructor(
    private val context: Context,
    private val sysFs: SysFs,
) {
    private var previousStat: Map<Int, CpuTimes> = emptyMap()

    @Volatile
    private var coreIndices: List<Int> = emptyList()

    @Volatile
    private var lastStorage: StorageFrame? = null

    /**
     * Informs the collector which CPU indices exist. Called once per capability scan so
     * that per-frame work is a single fixed list of paths.
     */
    public fun primeCores(indices: List<Int>) {
        coreIndices = indices.sorted()
    }

    public suspend fun collect(
        snapshot: CapabilitySnapshot,
        includeStorage: Boolean = false,
    ): TelemetryFrame = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        val thermalBindings = snapshot.bindingsFor(CapabilityId.THERMAL_ZONES)
            .filter { it.path.isNotBlank() }
            .take(MAX_THERMAL_ZONES)
        val zramStats = snapshot.bindingsFor(CapabilityId.ZRAM_STATS).firstOrNull { it.path.isNotBlank() }
        val gpuLoad = snapshot.first(CapabilityId.GPU_LOAD)?.path?.takeIf { it.isNotBlank() }

        val paths = buildList {
            if (snapshot.rootAvailable) {
                add("/proc/stat")
                add("/proc/meminfo")
                for (core in coreIndices) add("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")
                for (binding in thermalBindings) add(binding.path)
                for (resource in PRESSURE_RESOURCES) add("/proc/pressure/$resource")
                gpuLoad?.let { add(it) }
                zramStats?.let { binding ->
                    add(binding.path)
                    val dir = binding.path.substringBeforeLast('/')
                    add("$dir/disksize")
                    add("$dir/orig_data_size")
                    add("$dir/comp_algorithm")
                }
            }
        }

        val values = if (paths.isEmpty()) emptyMap() else sysFs.readMany(paths)

        val statNow = values["/proc/stat"]?.let(ProcParsers::parseStat).orEmpty()
        val loads = computeLoads(previousStat, statNow)
        previousStat = statNow

        val storage = if (includeStorage) readStorage().also { lastStorage = it } else lastStorage

        TelemetryFrame(
            timestampMs = now,
            cpu = CpuFrame(
                coreLoad = loads.perCore,
                coreFreqKhz = coreIndices.associateWith { core ->
                    values["/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq"]?.trim()?.toLongOrNull()
                },
                totalLoad = loads.aggregate,
            ),
            memory = values["/proc/meminfo"]?.let(ProcParsers::parseMemInfo),
            zram = buildZram(values, zramStats),
            thermal = thermalBindings.map { binding ->
                ThermalFrame(
                    name = binding.scope ?: binding.path.substringAfterLast('/').ifBlank { "zone" },
                    celsius = ProcParsers.parseThermalMilliC(values[binding.path]),
                )
            },
            battery = readBattery(),
            storage = storage,
            pressure = PRESSURE_RESOURCES.mapNotNull { resource ->
                values["/proc/pressure/$resource"]?.let { ProcParsers.parsePressure(it, resource) }
            },
        )
    }

    internal data class Loads(val perCore: Map<Int, Double>, val aggregate: Double?)

    /**
     * Pure delta computation, exposed for tests. Returns an empty map on the very first
     * frame because a single `/proc/stat` sample contains no delta information.
     */
    internal fun computeLoads(previous: Map<Int, CpuTimes>, current: Map<Int, CpuTimes>): Loads {
        if (previous.isEmpty()) return Loads(emptyMap(), null)
        val perCore = LinkedHashMap<Int, Double>()
        var sum = 0.0
        for ((index, times) in current) {
            if (index == ProcParsers.AGGREGATE_KEY) continue
            val prev = previous[index] ?: continue
            val utilization = CpuTimes.utilisation(prev, times) ?: continue
            perCore[index] = utilization
            sum += utilization
        }
        val aggregate = current[ProcParsers.AGGREGATE_KEY]?.let { now ->
            previous[ProcParsers.AGGREGATE_KEY]?.let { prev -> CpuTimes.utilisation(prev, now) }
        } ?: perCore.values.takeIf { it.isNotEmpty() }?.average()
        return Loads(perCore, aggregate)
    }

    private fun buildZram(values: Map<String, String>, statsBinding: dev.kernelis.core.capability.Binding?): ZramFrame? {
        if (statsBinding == null) return null
        val dir = statsBinding.path.substringBeforeLast('/')
        val disksize = values["$dir/disksize"]?.trim()?.toLongOrNull()
        val orig = values["$dir/orig_data_size"]?.trim()?.toLongOrNull()
        val used = values[statsBinding.path]?.trim()?.toLongOrNull()
        if (disksize == null && used == null && orig == null) return null
        val algorithm = values["$dir/comp_algorithm"]?.trim()?.let { raw ->
            raw.substringAfter('[', "").substringBefore(']', "").ifBlank { null } ?: raw.substringBefore(' ').ifBlank { null }
        }
        return ZramFrame(
            disksizeBytes = disksize,
            origDataBytes = orig,
            usedBytes = used,
            algorithm = algorithm,
        )
    }

    private fun readBattery(): BatteryFrame? {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        val intent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }

        val percent = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it in 0..100 }
        val temperatureTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperature = temperatureTenths
            ?.takeIf { it != Int.MIN_VALUE && it in -400..1500 }
            ?.let { it / 10.0 }
        val voltage = ProcParsers.normaliseVoltageMv(
            intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE },
        )
        val current = ProcParsers.normaliseCurrentMicroAmps(
            manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                .takeIf { it != Long.MIN_VALUE && it != 0L },
        )
        val chargeCounter = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            .takeIf { it != Long.MIN_VALUE && it > 0L }

        // µAh × mV = µWh ×10^-3... → convert: (µAh * mV) / 1000 = µWh, then /1000 = mWh.
        val energy = if (chargeCounter != null && voltage != null) {
            (chargeCounter * voltage) / 1_000_000L
        } else {
            null
        }

        return BatteryFrame(
            percent = percent,
            temperatureCelsius = temperature,
            voltageMv = voltage,
            currentMicroAmps = current,
            chargeCounterMicroAmpHours = chargeCounter,
            status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let(::statusText),
            health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)?.let(::healthText),
            plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.let(::pluggedText),
            energyMilliWattHours = energy,
        )
    }

    /** Retains the last storage reading so frames that skip storage still show one. */
    public fun rememberStorage(frame: StorageFrame?) {
        lastStorage = frame
    }

    private fun readStorage(): StorageFrame = try {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        StorageFrame(
            dataTotalBytes = stat.blockCountLong * stat.blockSizeLong,
            dataFreeBytes = stat.availableBlocksLong * stat.blockSizeLong,
        )
    } catch (_: IllegalArgumentException) {
        StorageFrame(null, null)
    } catch (_: SecurityException) {
        StorageFrame(null, null)
    }

    private fun statusText(code: Int): String? = when (code) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> null
    }

    private fun healthText(code: Int): String? = when (code) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
        else -> null
    }

    private fun pluggedText(code: Int): String? = when (code) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
        0 -> "Unplugged"
        else -> null
    }

    private companion object {
        const val MAX_THERMAL_ZONES = 16
        val PRESSURE_RESOURCES = listOf("cpu", "memory", "io")
    }
}
