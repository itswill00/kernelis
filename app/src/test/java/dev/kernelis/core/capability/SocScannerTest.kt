package dev.kernelis.core.capability

import com.google.common.truth.Truth.assertThat
import dev.kernelis.testing.FakeSysFs
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Cross-SoC discovery.
 *
 * These tests encode the layouts that actually differ between vendors, so a change to
 * the scanner cannot silently break one family while the others still pass.
 */
class SocScannerTest {

    // ---------------------------------------------------------------- MediaTek

    @Test
    fun `mediatek ppm interface is detected`() = runTest {
        val fs = FakeSysFs()
            .put("/proc/ppm/enabled", "1")
            .put("/proc/ppm/policy_status", "0 1\n1 1\n2 0\n")
            .put("/proc/ppm/policy/ut_fix_freq", "0 0 0 0")
            .put("/proc/mtktscpu/mtktscpu_temperature", "45000")

        val result = MediaTekScanner(fs).scan()

        assertThat(result.isMediaTek).isTrue()
        assertThat(result.notes.any { it.contains("MediaTek") }).isTrue()
        assertThat(result.bindings.any { it.capability == CapabilityId.CPU_MAX_FREQ }).isTrue()
        assertThat(result.thermalSensors).hasSize(1)
        assertThat(result.thermalSensors.first().celsius).isWithin(0.01).of(45.0)
    }

    @Test
    fun `non-mediatek device yields no mediatek results`() = runTest {
        val fs = FakeSysFs().put("/proc/sys/vm/swappiness", "60")
        val result = MediaTekScanner(fs).scan()
        assertThat(result.isMediaTek).isFalse()
        assertThat(result.bindings).isEmpty()
    }

    @Test
    fun `mtk temperature parser handles all observed formats`() {
        val scanner = MediaTekScanner(FakeSysFs())
        assertThat(scanner.parseMtkTemp("45000")).isWithin(0.01).of(45.0)
        assertThat(scanner.parseMtkTemp("450")).isWithin(0.01).of(45.0)
        assertThat(scanner.parseMtkTemp("45")).isWithin(0.01).of(45.0)
        assertThat(scanner.parseMtkTemp("0")).isNull()
        assertThat(scanner.parseMtkTemp("garbage")).isNull()
        // Multi-line table: first numeric token wins.
        assertThat(scanner.parseMtkTemp("42000\n43000")).isWithin(0.01).of(42.0)
    }

    // ------------------------------------------------------------------ Exynos

    @Test
    fun `exynos dvfs limit interface is detected`() = runTest {
        val fs = FakeSysFs()
            .put("/sys/power/cpufreq_max_limit", "1804000")
            .put("/sys/power/cpufreq_min_limit", "300000")

        val result = ExynosScanner(fs).scan()

        assertThat(result.isExynos).isTrue()
        assertThat(result.bindings.map { it.capability })
            .containsExactly(CapabilityId.CPU_MAX_FREQ, CapabilityId.CPU_MIN_FREQ)
        assertThat(result.notes.any { it.contains("Exynos") }).isTrue()
    }

    @Test
    fun `non-exynos device yields no exynos results`() = runTest {
        val result = ExynosScanner(FakeSysFs().put("/proc/stat", "")).scan()
        assertThat(result.isExynos).isFalse()
    }

    // --------------------------------------------------------------------- GPU

    @Test
    fun `mali gpu is discovered via devfreq name hint`() = runTest {
        val fs = FakeSysFs()
            .put("/sys/class/devfreq/1c400000.mali/name", "1c400000.mali")
            .put("/sys/class/devfreq/1c400000.mali/governor", "simple_ondemand")
            .put("/sys/class/devfreq/1c400000.mali/available_governors", "simple_ondemand performance")
            .put("/sys/class/devfreq/1c400000.mali/available_frequencies", "177000000 533000000 800000000")
            .put("/sys/class/devfreq/1c400000.mali/min_freq", "177000000")
            .put("/sys/class/devfreq/1c400000.mali/max_freq", "800000000")

        val surface = GpuScanner(fs).scan()

        assertThat(surface).isNotNull()
        assertThat(surface!!.vendor).isEqualTo(GpuScanner.Vendor.MALI)
        assertThat(surface.availableGovernors).contains("simple_ondemand")
        assertThat(surface.availableFreqs).containsExactly(177000000L, 533000000L, 800000000L)
        assertThat(surface.minFreqPath).isEqualTo("/sys/class/devfreq/1c400000.mali/min_freq")
    }

    @Test
    fun `adreno gpu reads load from the parent of devfreq`() = runTest {
        val fs = FakeSysFs()
            .put("/sys/class/kgsl/kgsl-3d0/devfreq/governor", "msm-adreno-tz")
            .put("/sys/class/kgsl/kgsl-3d0/devfreq/available_governors", "msm-adreno-tz performance")
            .put("/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies", "257000000 587000000")
            .put("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "37 %")

        val surface = GpuScanner(fs).scan()!!

        assertThat(surface.vendor).isEqualTo(GpuScanner.Vendor.ADRENO)
        assertThat(surface.loadPath).isEqualTo("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
    }

    @Test
    fun `powervr sgxfreq layout is discovered`() = runTest {
        val fs = FakeSysFs()
            .put("/sys/devices/platform/omap/pvrsrvkm.0/sgxfreq/governor", "ondemand")
            .put("/sys/devices/platform/omap/pvrsrvkm.0/sgxfreq/governor_list", "ondemand performance")
            .put("/sys/devices/platform/omap/pvrsrvkm.0/sgxfreq/frequency_list", "100000000 200000000")
            .put("/sys/devices/platform/omap/pvrsrvkm.0/sgxfreq/frequency_limit", "100000000")

        val surface = GpuScanner(fs).scan()!!

        assertThat(surface.vendor).isEqualTo(GpuScanner.Vendor.POWERVR)
        assertThat(surface.availableGovernors).containsExactly("ondemand", "performance")
        assertThat(surface.minFreqPath).endsWith("frequency_limit")
    }

    @Test
    fun `device with no gpu nodes returns null rather than guessing`() = runTest {
        val surface = GpuScanner(FakeSysFs().put("/proc/stat", "")).scan()
        assertThat(surface).isNull()
    }

    // ------------------------------------------------------------ extended scan

    @Test
    fun `low memory killer is detected on legacy kernels`() = runTest {
        val fs = FakeSysFs()
            .put("/sys/module/lowmemorykiller/parameters/minfree", "18432,23040,27648,32256,36864,46080")
            .put("/sys/module/lowmemorykiller/parameters/adj", "0,100,200,300,900,906")
            .put("/sys/module/lowmemorykiller/parameters/enable_adaptive_lmk", "1")

        val result = ExtendedScanner(fs).scan()

        val lmk = result.bindings.filter { it.capability == CapabilityId.LMK_MIN_FREE }
        assertThat(lmk).hasSize(1)
        assertThat(lmk.first().isAvailable).isTrue()
        assertThat(lmk.first().current).contains("18432")
    }

    @Test
    fun `ksm is detected when present`() = runTest {
        val fs = FakeSysFs()
            .put("/sys/kernel/mm/ksm/run", "1")
            .put("/sys/kernel/mm/ksm/pages_to_scan", "100")

        val result = ExtendedScanner(fs).scan()

        val ksm = result.bindings.first { it.capability == CapabilityId.KSM_ENABLED }
        assertThat(ksm.isAvailable).isTrue()
        assertThat(ksm.current).isEqualTo("1")
    }

    @Test
    fun `vm tunables are discovered with their ranges`() = runTest {
        val fs = FakeSysFs()
            .put("/proc/sys/vm/min_free_kbytes", "8192")
            .put("/proc/sys/vm/watermark_scale_factor", "10")
            .put("/proc/sys/vm/swappiness", "60")

        val result = ExtendedScanner(fs).scan()

        val minFree = result.bindings.first { it.capability == CapabilityId.VM_MIN_FREE }
        assertThat(minFree.isAvailable).isTrue()
        assertThat(minFree.current).isEqualTo("8192")
        assertThat(minFree.numericRange).isNotNull()
    }

    @Test
    fun `missing extended surfaces are reported not invented`() = runTest {
        val result = ExtendedScanner(FakeSysFs().put("/proc/stat", "")).scan()

        // Every extended capability must still appear, marked unavailable.
        val ids = result.bindings.map { it.capability }.toSet()
        assertThat(ids).containsAtLeast(
            CapabilityId.HOTPLUG_ENABLED,
            CapabilityId.LMK_MIN_FREE,
            CapabilityId.KSM_ENABLED,
            CapabilityId.SCREEN_KCAL,
            CapabilityId.CHARGE_FAST,
            CapabilityId.MISC_FSYNC,
        )
        assertThat(result.bindings.none { it.isAvailable }).isTrue()
    }
}
