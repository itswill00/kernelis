package dev.kernelis.core.capability

import com.google.common.truth.Truth.assertThat
import dev.kernelis.core.root.RootProvider
import dev.kernelis.testing.FakeRootShell
import dev.kernelis.testing.FakeSysFs
import dev.kernelis.testing.minimalDevice
import dev.kernelis.testing.modernDevice
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The capability scanner is the product promise: "we detect, we do not assume".
 *
 * These tests run the real scanner against synthetic devices and assert both what is
 * found and — just as important — that missing features are reported as unsupported with
 * a reason, rather than appearing as a working control.
 */
class CapabilityScannerTest {

    private fun scannerFor(device: FakeSysFs): CapabilityScanner {
        val shell = FakeRootShell(device)
        return CapabilityScanner(
            sysFs = device,
            rootShell = shell,
            topologyScanner = CpuTopologyScanner(device),
        )
    }

    @Test
    fun `modern device discovers two cpu clusters and their frequency tables`() = runTest {
        val snapshot = scannerFor(modernDevice()).scan(true, RootProvider(RootProvider.Family.MAGISK, "28.1"))

        val minFreq = snapshot.bindingsFor(CapabilityId.CPU_MIN_FREQ)
        assertThat(minFreq).hasSize(2)
        assertThat(minFreq.map { it.scope }).containsExactly("Cluster A", "Cluster B")
        assertThat(minFreq[0].current).isEqualTo("300000")
        assertThat(minFreq[1].current).isEqualTo("710400")
        assertThat(minFreq.all { it.isAvailable }).isTrue()

        // Values are the device's, not a hard-coded table.
        val clusterB = minFreq.first { it.scope == "Cluster B" }
        assertThat(clusterB.samples).containsExactly("710400", "1209600", "2419200", "2841600")
        assertThat(clusterB.numericRange).isEqualTo(NumericRange(710400, 2841600))
    }

    @Test
    fun `modern device discovers governors actually offered by the kernel`() = runTest {
        val snapshot = scannerFor(modernDevice()).scan(true, RootProvider(RootProvider.Family.MAGISK, "28.1"))

        val governor = snapshot.bindingsFor(CapabilityId.CPU_GOVERNOR)
        assertThat(governor).hasSize(2)
        assertThat(governor.first().samples).containsExactly("performance", "powersave", "schedutil")
        assertThat(governor.first().current).isEqualTo("schedutil")
        assertThat(governor.first().valueKind).isEqualTo(ValueKind.ENUM)
    }

    @Test
    fun `gpu is discovered through the kgsl devfreq node`() = runTest {
        val snapshot = scannerFor(modernDevice()).scan(true, RootProvider(RootProvider.Family.MAGISK, "28.1"))

        val gpuGovernor = snapshot.first(CapabilityId.GPU_GOVERNOR)
        assertThat(gpuGovernor).isNotNull()
        assertThat(gpuGovernor!!.isAvailable).isTrue()
        assertThat(gpuGovernor.path).contains("kgsl-3d0/devfreq")
        assertThat(gpuGovernor.samples).contains("msm-adreno-tz")
        assertThat(snapshot.supports(CapabilityId.GPU_LOAD)).isTrue()
    }

    @Test
    fun `io scheduler parses the bracketed active value`() = runTest {
        val snapshot = scannerFor(modernDevice()).scan(true, RootProvider(RootProvider.Family.MAGISK, "28.1"))

        val scheduler = snapshot.first(CapabilityId.IO_SCHEDULER)!!
        assertThat(scheduler.isAvailable).isTrue()
        assertThat(scheduler.current).isEqualTo("mq-deadline")
        assertThat(scheduler.samples).containsExactly("mq-deadline", "kyber", "bfq")
    }

    @Test
    fun `thermal zones are enumerated with their real type names`() = runTest {
        val snapshot = scannerFor(modernDevice()).scan(true, RootProvider(RootProvider.Family.MAGISK, "28.1"))

        val zones = snapshot.bindingsFor(CapabilityId.THERMAL_ZONES).filter { it.isAvailable }
        assertThat(zones.map { it.scope }).containsExactly("cpu-0-0-usr", "gpuss-0-usr", "battery")
    }

    @Test
    fun `zram is detected with algorithm options`() = runTest {
        val snapshot = scannerFor(modernDevice()).scan(true, RootProvider(RootProvider.Family.MAGISK, "28.1"))

        val algorithm = snapshot.first(CapabilityId.ZRAM_ALGORITHM)!!
        assertThat(algorithm.isAvailable).isTrue()
        assertThat(algorithm.current).isEqualTo("lz4")
        assertThat(algorithm.samples).containsExactly("lz4", "lz4hc", "zstd")
    }

    @Test
    fun `minimal device reports missing features as unsupported rather than faking them`() = runTest {
        val snapshot = scannerFor(minimalDevice()).scan(true, RootProvider(RootProvider.Family.OTHER, null))

        // Present:
        assertThat(snapshot.supports(CapabilityId.CPU_MIN_FREQ)).isTrue()
        assertThat(snapshot.supports(CapabilityId.CPU_GOVERNOR)).isTrue()

        // Absent: must be explicitly NotPresent, not silently zero.
        assertThat(snapshot.supports(CapabilityId.GPU_GOVERNOR)).isFalse()
        assertThat(snapshot.first(CapabilityId.GPU_GOVERNOR)!!.availability)
            .isEqualTo(Availability.NotPresent)
        assertThat(snapshot.supports(CapabilityId.ZRAM_SIZE)).isFalse()
        assertThat(snapshot.supports(CapabilityId.THERMAL_ZONES)).isFalse()
        assertThat(snapshot.supports(CapabilityId.IO_SCHEDULER)).isFalse()
        assertThat(snapshot.first(CapabilityId.IO_SCHEDULER)!!.availability)
            .isEqualTo(Availability.NotPresent)
    }

    @Test
    fun `unsupported list explains every missing capability`() = runTest {
        val snapshot = scannerFor(minimalDevice()).scan(true, RootProvider(RootProvider.Family.OTHER, null))

        val unsupported = snapshot.unsupported()
        assertThat(unsupported).isNotEmpty()
        // Each has an id and a human-readable reason, never a bare boolean.
        for ((id, availability) in unsupported) {
            assertThat(id).isNotNull()
            assertThat(availability.reasonText).isNotEmpty()
        }
        assertThat(unsupported.size).isEqualTo(snapshot.totalCount - snapshot.supportedCount)
    }

    @Test
    fun `per-cpu fallback reconstructs clusters from identical frequency tables`() = runTest {
        val device = FakeSysFs()
            .put("/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies", "300000 600000")
            .put("/sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq", "300000")
            .put("/sys/devices/system/cpu/cpu1/cpufreq/scaling_available_frequencies", "300000 600000")
            .put("/sys/devices/system/cpu/cpu1/cpufreq/scaling_min_freq", "300000")
            .put("/sys/devices/system/cpu/cpu2/cpufreq/scaling_available_frequencies", "600000 1200000")
            .put("/sys/devices/system/cpu/cpu2/cpufreq/scaling_min_freq", "600000")

        val topology = CpuTopologyScanner(device).scan()

        assertThat(topology.usesPolicyDirs).isFalse()
        assertThat(topology.clusters).hasSize(2)
        assertThat(topology.clusters[0].cores.map { it.index }).containsExactly(0, 1)
        assertThat(topology.clusters[1].cores.map { it.index }).containsExactly(2)
    }

    @Test
    fun `unreadable node is reported as unreadable with a reason`() = runTest {
        val device = modernDevice()
        device.readFailure = setOf("/sys/devices/system/cpu/policy0/scaling_min_freq")
        val snapshot = scannerFor(device).scan(true, RootProvider(RootProvider.Family.MAGISK, "28.1"))

        val binding = snapshot.first(CapabilityId.CPU_MIN_FREQ)!!
        // The node exists but the scan could not read it: the UI must show why, not a fake value.
        assertThat(binding.isAvailable).isFalse()
        assertThat(binding.availability).isInstanceOf(Availability.Unreadable::class.java)
        assertThat(binding.availability.reasonText).contains("Unreadable")
    }

    @Test
    fun `no root yields an explicit no-root reason for every capability`() = runTest {
        val snapshot = scannerFor(modernDevice()).scan(false, RootProvider.NONE)

        assertThat(snapshot.rootAvailable).isFalse()
        assertThat(snapshot.supportedCount).isEqualTo(0)
        val allBindings = snapshot.bindings.values.flatten()
        assertThat(allBindings).hasSize(CapabilityId.entries.size)
        assertThat(allBindings.all { it.availability == Availability.NoRoot }).isTrue()
        assertThat(snapshot.notes).isNotEmpty()
        assertThat(snapshot.notes).isNotEmpty()
    }

    @Test
    fun `scanner never trusts a SoC name - it uses presence only`() = runTest {
        // A device with an exotic path layout still works if the nodes exist.
        val device = FakeSysFs()
            .put("/sys/devices/system/cpu/policy0/affected_cpus", "0")
            .put("/sys/devices/system/cpu/policy0/scaling_available_frequencies", "100000 200000")
            .put("/sys/devices/system/cpu/policy0/scaling_min_freq", "100000")
            .put("/sys/devices/system/cpu/policy0/scaling_max_freq", "200000")
            .put("/sys/devices/system/cpu/policy0/scaling_governor", "custom_gov")
            .put("/sys/devices/system/cpu/policy0/scaling_available_governors", "custom_gov")
            .put("/proc/sys/kernel/osrelease", "9.9.9-exotic")

        val snapshot = scannerFor(device).scan(true, RootProvider(RootProvider.Family.OTHER, null))

        assertThat(snapshot.supports(CapabilityId.CPU_MIN_FREQ)).isTrue()
        assertThat(snapshot.first(CapabilityId.CPU_GOVERNOR)!!.samples).containsExactly("custom_gov")
    }
}
