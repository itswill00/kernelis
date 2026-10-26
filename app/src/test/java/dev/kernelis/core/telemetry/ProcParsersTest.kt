package dev.kernelis.core.telemetry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Parsers are tested against captured real-world formats. Where a kernel version
 * disagrees with another (thermal units, battery current units) the test states the
 * ambiguity explicitly rather than pretending there is one answer.
 */
class ProcParsersTest {

    @Test
    fun `parseStat reads aggregate and per-core lines`() {
        val raw = """
            cpu  100 5 50 800 20 0 1 2 0 0
            cpu0 10 0 5 80 2 0 0 0 0 0
            cpu1 20 0 10 160 4 0 0 0 0 0
            intr 12345
            ctxt 99
        """.trimIndent()

        val parsed = ProcParsers.parseStat(raw)

        assertThat(parsed.keys).containsExactly(ProcParsers.AGGREGATE_KEY, 0, 1)
        val aggregate = parsed.getValue(ProcParsers.AGGREGATE_KEY)
        assertThat(aggregate.user).isEqualTo(100)
        assertThat(aggregate.idle).isEqualTo(800)
        assertThat(aggregate.iowait).isEqualTo(20)
        assertThat(parsed.getValue(1).system).isEqualTo(10)
    }

    @Test
    fun `parseStat tolerates truncated lines from some kernels`() {
        val parsed = ProcParsers.parseStat("cpu0 10 0 5 80\ncpu1 1 2")
        assertThat(parsed.keys).containsExactly(0)
        assertThat(parsed.getValue(0).idle).isEqualTo(80)
    }

    @Test
    fun `utilisation computes busy fraction from deltas`() {
        val previous = CpuTimes(user = 100, nice = 0, system = 100, idle = 800, iowait = 0, irq = 0, softirq = 0, steal = 0)
        // 100 total ticks pass, 40 of them busy.
        val current = previous.copy(user = 140, idle = 860)

        val utilisation = CpuTimes.utilisation(previous, current)

        assertThat(utilisation).isNotNull()
        assertThat(utilisation!!).isWithin(0.001).of(0.4)
    }

    @Test
    fun `utilisation returns null when no time has passed`() {
        val times = CpuTimes(1, 0, 1, 1, 0, 0, 0, 0)
        assertThat(CpuTimes.utilisation(times, times)).isNull()
    }

    @Test
    fun `utilisation ignores counters that appear to go backwards`() {
        val previous = CpuTimes(user = 100, nice = 0, system = 100, idle = 100, iowait = 0, irq = 0, softirq = 0, steal = 0)
        val current = CpuTimes(user = 50, nice = 0, system = 50, idle = 50, iowait = 0, irq = 0, softirq = 0, steal = 0)
        assertThat(CpuTimes.utilisation(previous, current)).isNull()
    }

    @Test
    fun `parseMemInfo converts kB to bytes and computes usage`() {
        val raw = """
            MemTotal:       8000000 kB
            MemFree:        1000000 kB
            MemAvailable:   3000000 kB
            Cached:         2000000 kB
            SwapTotal:      2000000 kB
            SwapFree:       500000 kB
        """.trimIndent()

        val memory = ProcParsers.parseMemInfo(raw)!!

        assertThat(memory.totalBytes).isEqualTo(8_000_000L * 1024)
        assertThat(memory.availableBytes).isEqualTo(3_000_000L * 1024)
        assertThat(memory.usedBytes).isEqualTo(5_000_000L * 1024)
        assertThat(memory.swapUsedBytes).isEqualTo(1_500_000L * 1024)
        assertThat(memory.usedFraction).isWithin(0.0001).of(0.625)
    }

    @Test
    fun `parseMemInfo returns null without MemTotal`() {
        assertThat(ProcParsers.parseMemInfo("SomethingElse: 1 kB")).isNull()
    }

    @Test
    fun `thermal milli-degrees`() {
        assertThat(ProcParsers.parseThermalMilliC("45000")).isWithin(0.001).of(45.0)
        assertThat(ProcParsers.parseThermalMilliC("80000")).isWithin(0.001).of(80.0)
    }

    @Test
    fun `thermal whole degrees from older kernels`() {
        assertThat(ProcParsers.parseThermalMilliC("45")).isWithin(0.001).of(45.0)
    }

    @Test
    fun `thermal deci-degrees`() {
        // Some vendor drivers report deci-degrees: 450 -> 45 °C.
        assertThat(ProcParsers.parseThermalMilliC("450")).isWithin(0.001).of(45.0)
    }

    @Test
    fun `thermal sensor with no reading is null not zero`() {
        assertThat(ProcParsers.parseThermalMilliC("0")).isNull()
        assertThat(ProcParsers.parseThermalMilliC("-274000")).isNull()
        assertThat(ProcParsers.parseThermalMilliC(null)).isNull()
        assertThat(ProcParsers.parseThermalMilliC("garbage")).isNull()
    }

    @Test
    fun `parsePressure reads some and full lines`() {
        val raw = "some avg10=1.50 avg60=2.25 avg300=3.00 total=1000\n" +
            "full avg10=0.75 avg60=1.00 avg300=1.25 total=500"

        val pressure = ProcParsers.parsePressure(raw, "cpu")!!

        assertThat(pressure.resource).isEqualTo("cpu")
        assertThat(pressure.someAvg10).isWithin(0.001).of(1.5)
        assertThat(pressure.fullAvg10).isWithin(0.001).of(0.75)
    }

    @Test
    fun `parsePressure returns null without a some line`() {
        assertThat(ProcParsers.parsePressure("full avg10=1", "cpu")).isNull()
    }

    @Test
    fun `current in milliamps is scaled to microamps`() {
        // Some vendor builds report mA: 500 -> 500_000 µA
        assertThat(ProcParsers.normaliseCurrentMicroAmps(500)).isEqualTo(500_000)
        assertThat(ProcParsers.normaliseCurrentMicroAmps(-1200)).isEqualTo(-1_200_000)
    }

    @Test
    fun `current already in microamps is left alone`() {
        assertThat(ProcParsers.normaliseCurrentMicroAmps(1_500_000)).isEqualTo(1_500_000)
    }

    @Test
    fun `unknown current is null`() {
        assertThat(ProcParsers.normaliseCurrentMicroAmps(0)).isNull()
        assertThat(ProcParsers.normaliseCurrentMicroAmps(Long.MIN_VALUE)).isNull()
        assertThat(ProcParsers.normaliseCurrentMicroAmps(null)).isNull()
    }

    @Test
    fun `implausible voltage is rejected rather than displayed`() {
        assertThat(ProcParsers.normaliseVoltageMv(4_200)).isEqualTo(4_200)
        assertThat(ProcParsers.normaliseVoltageMv(0)).isNull()
        assertThat(ProcParsers.normaliseVoltageMv(999_999)).isNull()
    }
}
