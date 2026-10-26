package dev.kernelis.core.telemetry

/**
 * Parsers for the procfs/sysfs formats KERNELIS consumes.
 *
 * These are pure functions with no Android dependencies so the tricky parts (counter
 * wraparound, kernel version differences, truncated files) are unit tested against
 * captured real-world samples rather than guessed at.
 */
public object ProcParsers {

    /**
     * Parses `/proc/stat` into per-CPU and aggregate time counters.
     *
     * Returns a map keyed by CPU index plus [AGGREGATE_KEY] for the `cpu` line.
     * Kernel 4.14+ adds guest/guest_nice which are *already counted* in user/nice;
     * we ignore them, which matches Android's own accounting.
     */
    public const val AGGREGATE_KEY: Int = -1

    public fun parseStat(raw: String): Map<Int, CpuTimes> {
        val out = LinkedHashMap<Int, CpuTimes>()
        for (line in raw.lineSequence()) {
            if (!line.startsWith("cpu")) continue
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5) continue
            val key = when (val label = parts[0]) {
                "cpu" -> AGGREGATE_KEY
                else -> label.removePrefix("cpu").toIntOrNull() ?: continue
            }
            val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size < 4) continue
            fun at(i: Int): Long = values.getOrElse(i) { 0L }
            out[key] = CpuTimes(
                user = at(0),
                nice = at(1),
                system = at(2),
                idle = at(3),
                iowait = at(4),
                irq = at(5),
                softirq = at(6),
                steal = at(7),
            )
        }
        return out
    }

    /**
     * Parses `/proc/meminfo`. Values are reported in kB by the kernel regardless of
     * architecture, so the conversion to bytes is a fixed ×1024.
     */
    public fun parseMemInfo(raw: String): MemoryFrame? {
        val values = LinkedHashMap<String, Long>()
        for (line in raw.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val numberText = line.substring(idx + 1).trim().substringBefore(' ').trim()
            val number = numberText.toLongOrNull() ?: continue
            values[key] = number * 1024L
        }
        val total = values["MemTotal"] ?: return null
        if (total <= 0L) return null
        return MemoryFrame(
            totalBytes = total,
            availableBytes = values["MemAvailable"] ?: values["MemFree"] ?: 0L,
            freeBytes = values["MemFree"] ?: 0L,
            cachedBytes = values["Cached"] ?: 0L,
            swapTotalBytes = values["SwapTotal"] ?: 0L,
            swapFreeBytes = values["SwapFree"] ?: 0L,
        )
    }

    /**
     * Parses a sysfs thermal `temp` value into °C.
     *
     * Kernels disagree on units: most report milli-degrees (45_000), some report whole
     * degrees (45), and a few vendor drivers report deci-degrees (450). Magnitude is the
     * only discriminator available. The ranges are chosen so the common cases are right:
     *   10_000+  -> milli-degrees
     *   100..9_999 -> deci-degrees   (450 -> 45.0 °C)
     *   10..99   -> whole degrees    (45 -> 45.0 °C)
     * A genuine 100 °C whole-degree reading would be misread as 10 °C; such readings are
     * rare (throttling usually caps below this) and the ambiguity is inherent to the
     * interface, so we prefer the common case and document the limitation.
     */
    public fun parseThermalMilliC(raw: String?): Double? {
        val value = raw?.trim()?.toLongOrNull() ?: return null
        if (value <= INVALID_THERMAL) return null // 0 / negative mean "no reading"
        return when {
            value >= 10_000L -> value / 1000.0   // milli-degrees:  45_000 -> 45.0
            value >= 100L -> value / 10.0        // deci-degrees:     450 -> 45.0
            value >= 10L -> value.toDouble()     // whole degrees:     45 -> 45.0
            else -> null                         // implausible
        }
    }

    private const val INVALID_THERMAL = 0L

    /**
     * Parses one line of `/proc/pressure/`:
     * `some avg10=0.00 avg60=0.00 avg300=0.00 total=12345`
     */
    public fun parsePressure(raw: String, resource: String): PressureFrame? {
        val line = raw.lineSequence().firstOrNull { it.startsWith("some") } ?: return null
        val fields = line.trim().split(Regex("\\s+"))
            .mapNotNull { token ->
                val idx = token.indexOf('=')
                if (idx <= 0) null else token.substring(0, idx) to token.substring(idx + 1).toDoubleOrNull()
            }
            .toMap()
        val fullLine = raw.lineSequence().firstOrNull { it.startsWith("full") }
        val fullFields = fullLine?.trim()?.split(Regex("\\s+"))
            ?.mapNotNull { token ->
                val idx = token.indexOf('=')
                if (idx <= 0) null else token.substring(0, idx) to token.substring(idx + 1).toDoubleOrNull()
            }?.toMap()
            ?: emptyMap()
        return PressureFrame(
            resource = resource,
            someAvg10 = fields["avg10"],
            fullAvg10 = fullFields["avg10"],
        )
    }

    /**
     * Battery current is reported in micro-amps by BatteryManager on most devices but
     * in milliamps on some vendor builds. Normalise by magnitude: no phone exceeds
     * ~20 A, and no meaningful current reading is below 1 mA except during deep idle.
     */
    public fun normaliseCurrentMicroAmps(raw: Long?): Long? {
        val value = raw ?: return null
        if (value == 0L || value == Long.MIN_VALUE) return null
        return if (value in -20_000..20_000) value * 1000 else value
    }

    /** Voltage reported by BatteryManager is already in mV; sanity-clamp to plausible range. */
    public fun normaliseVoltageMv(raw: Int?): Int? {
        val value = raw ?: return null
        return if (value in 2_000..6_000) value else null
    }
}
