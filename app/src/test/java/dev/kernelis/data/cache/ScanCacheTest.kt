package dev.kernelis.data.cache

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.kernelis.core.capability.Availability
import dev.kernelis.core.capability.Binding
import dev.kernelis.core.capability.CapabilityId
import dev.kernelis.core.capability.CapabilitySnapshot
import dev.kernelis.core.capability.NumericRange
import dev.kernelis.core.capability.ValueKind
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The disk scan cache exists to make a cold start instant. Its one real risk is
 * trusting a snapshot from a different boot or a different kernel, which would make
 * KERNELIS claim controls this boot does not have. These tests pin that behaviour:
 * the cache is only ever reused when boot identity AND kernel release both match.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanCacheTest {

    private fun snapshot(value: String = "1000") = CapabilitySnapshot(
        bindings = mapOf(
            CapabilityId.CPU_MAX_FREQ to listOf(
                Binding(
                    capability = CapabilityId.CPU_MAX_FREQ,
                    path = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq",
                    availability = Availability.Supported("writable"),
                    valueKind = ValueKind.NUMBER,
                    current = value,
                    samples = listOf("300000", "1000000", "2000000"),
                    numericRange = NumericRange(300_000, 2_000_000, 100_000),
                    units = "kHz",
                    scope = "cpu0",
                    looksWritable = true,
                ),
            ),
        ),
        scannedAtEpochMs = 1L,
        rootAvailable = true,
        rootProvider = "Magisk 28.1",
        deviceFingerprint = "6.1.0-kernelis",
        notes = listOf("note"),
    )

    private fun newCache(): ScanCache =
        ScanCache(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun saveThenLoad_sameBootAndKernel_returnsSnapshot() = runTest {
        val cache = newCache()
        cache.save(bootId = "boot-aaa", kernelRelease = "6.1.0", snapshot = snapshot("1500"))

        val loaded = cache.load(bootId = "boot-aaa", kernelRelease = "6.1.0")

        assertThat(loaded).isNotNull()
        val binding = loaded!!.bindingsFor(CapabilityId.CPU_MAX_FREQ).single()
        assertThat(binding.current).isEqualTo("1500")
        assertThat(binding.path).isEqualTo("/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq")
        assertThat(binding.numericRange).isEqualTo(NumericRange(300_000, 2_000_000, 100_000))
        assertThat(binding.samples).containsExactly("300000", "1000000", "2000000")
        assertThat(binding.scope).isEqualTo("cpu0")
        assertThat(loaded.rootProvider).isEqualTo("Magisk 28.1")
    }

    @Test
    fun differentBoot_discardsSnapshot() = runTest {
        val cache = newCache()
        cache.save(bootId = "boot-aaa", kernelRelease = "6.1.0", snapshot = snapshot())

        // Runtime tuning does not survive a reboot, so neither may the snapshot.
        assertThat(cache.load(bootId = "boot-bbb", kernelRelease = "6.1.0")).isNull()
    }

    @Test
    fun differentKernel_discardsSnapshot() = runTest {
        val cache = newCache()
        cache.save(bootId = "boot-aaa", kernelRelease = "6.1.0", snapshot = snapshot())

        // A kernel flash/OTA can add or remove nodes: discovery must run again.
        assertThat(cache.load(bootId = "boot-aaa", kernelRelease = "6.6.1")).isNull()
    }

    @Test
    fun unknownBootId_discardsSnapshot() = runTest {
        val cache = newCache()
        cache.save(bootId = "boot-aaa", kernelRelease = "6.1.0", snapshot = snapshot())

        // If boot_id could not be read we cannot prove the snapshot is current.
        assertThat(cache.load(bootId = null, kernelRelease = "6.1.0")).isNull()
        assertThat(cache.load(bootId = "", kernelRelease = "6.1.0")).isNull()
    }

    @Test
    fun emptyCache_returnsNull() = runTest {
        // DataStore keeps one file per process, and Robolectric reuses the process
        // across tests, so this test must clear first rather than assume emptiness.
        val cache = newCache()
        cache.clear()

        assertThat(cache.load(bootId = "boot-aaa", kernelRelease = "6.1.0")).isNull()
    }

    @Test
    fun clear_removesSnapshot() = runTest {
        val cache = newCache()
        cache.save(bootId = "boot-aaa", kernelRelease = "6.1.0", snapshot = snapshot())
        assertThat(cache.load(bootId = "boot-aaa", kernelRelease = "6.1.0")).isNotNull()

        cache.clear()

        assertThat(cache.load(bootId = "boot-aaa", kernelRelease = "6.1.0")).isNull()
    }
}
