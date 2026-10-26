package dev.kernelis.core.telemetry

/**
 * One frame of telemetry.
 *
 * Every field is nullable on purpose: a device that does not expose GPU load must
 * produce `null`, not `0`. The UI renders null as "N/A" so a metric is never invented.
 */
public data class TelemetryFrame(
    val timestampMs: Long,
    val cpu: CpuFrame,
    val memory: MemoryFrame?,
    val zram: ZramFrame?,
    val thermal: List<ThermalFrame>,
    val battery: BatteryFrame?,
    val storage: StorageFrame?,
    val pressure: List<PressureFrame>,
)

public data class CpuFrame(
    /** Per-core utilisation 0..1, derived from deltas of /proc/stat. */
    val coreLoad: Map<Int, Double>,
    /** Per-core current frequency in kHz. */
    val coreFreqKhz: Map<Int, Long?>,
    val totalLoad: Double?,
)

public data class MemoryFrame(
    val totalBytes: Long,
    val availableBytes: Long,
    val freeBytes: Long,
    val cachedBytes: Long,
    val swapTotalBytes: Long,
    val swapFreeBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0)

    val usedFraction: Double
        get() = if (totalBytes <= 0) 0.0 else usedBytes.toDouble() / totalBytes

    val swapUsedBytes: Long get() = (swapTotalBytes - swapFreeBytes).coerceAtLeast(0)
}

public data class ZramFrame(
    val disksizeBytes: Long?,
    val origDataBytes: Long?,
    val usedBytes: Long?,
    val algorithm: String?,
) {
    /** Compression ratio > 1 means the pool is saving memory. */
    val compressionRatio: Double?
        get() {
            val orig = origDataBytes ?: return null
            val used = usedBytes ?: return null
            if (used <= 0L) return null
            return orig.toDouble() / used
        }
}

public data class ThermalFrame(
    val name: String,
    val celsius: Double?,
) {
    /**
     * Coarse classification only, used to colour the UI. Thresholds are informational
     * and intentionally not device-specific: they describe "cool/warm/hot", not trip
     * points, which the kernel owns.
     */
    val level: Level
        get() {
            val c = celsius ?: return Level.UNKNOWN
            return when {
                c >= 75.0 -> Level.HOT
                c >= 55.0 -> Level.WARM
                else -> Level.COOL
            }
        }

    enum class Level { COOL, WARM, HOT, UNKNOWN }
}

public data class BatteryFrame(
    val percent: Int?,
    val temperatureCelsius: Double?,
    val voltageMv: Int?,
    val currentMicroAmps: Long?,
    val chargeCounterMicroAmpHours: Long?,
    val status: String?,
    val health: String?,
    val plugged: String?,
    /** Approximate energy in mWh derived from charge counter × voltage, when both exist. */
    val energyMilliWattHours: Long?,
)

public data class StorageFrame(
    val dataTotalBytes: Long?,
    val dataFreeBytes: Long?,
)

public data class PressureFrame(
    val resource: String,
    val someAvg10: Double?,
    val fullAvg10: Double?,
)

/** Parsed /proc/stat counters for one CPU (or the aggregate). */
public data class CpuTimes(
    val user: Long,
    val nice: Long,
    val system: Long,
    val idle: Long,
    val iowait: Long,
    val irq: Long,
    val softirq: Long,
    val steal: Long,
) {
    val idleTotal: Long get() = idle + iowait

    val total: Long
        get() = user + nice + system + idle + iowait + irq + softirq + steal

    /**
     * Utilisation between two samples. Uses idle+iowait as the idle bucket, which is
     * what tools like `top` and Android's own `procstat` do.
     */
    public companion object {
        public fun utilisation(previous: CpuTimes, current: CpuTimes): Double? {
            val totalDelta = current.total - previous.total
            if (totalDelta <= 0L) return null
            val idleDelta = current.idleTotal - previous.idleTotal
            val busy = (totalDelta - idleDelta).coerceIn(0L, totalDelta)
            return busy.toDouble() / totalDelta
        }
    }
}
