package dev.kernelis.ui.dashboard

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.kernelis.core.telemetry.BatteryFrame
import dev.kernelis.core.telemetry.CpuFrame
import dev.kernelis.core.telemetry.MemoryFrame
import dev.kernelis.core.telemetry.TelemetryFrame
import dev.kernelis.core.telemetry.ThermalFrame

/**
 * A realistic Dashboard for screenshots.
 *
 * Same reason as the Tuning preview: an empty or zeroed screen hides the density
 * problems. This uses plausible values for an 8-core phone so the layout can be judged
 * as it will actually appear.
 */
@Composable
internal fun DashboardPreviewForScreenshot() {
    val frame = remember { previewFrame() }
    val state = remember {
        DashboardViewModel.UiState(
            frame = frame,
            loadHistory = List(60) { i -> 0.15 + 0.6 * kotlin.math.abs(kotlin.math.sin(i / 7.0)) },
            // freqHistory is stored in GHz (see DashboardViewModel), not kHz.
            freqHistory = List(60) { i -> 1.2 + 0.5 * kotlin.math.cos(i / 9.0) },
            ramHistory = List(60) { i -> 0.55 + 0.08 * kotlin.math.sin(i / 11.0) },
            tempHistory = List(60) { i -> 41.0 + 6 * kotlin.math.sin(i / 13.0) },
            rootProviderLabel = "Magisk 28.1",
        )
    }
    DashboardContentForTest(state, frame)
}

private fun previewFrame(): TelemetryFrame {
    val cores = (0..7).toList()
    return TelemetryFrame(
        timestampMs = 1_700_000_000_000,
        cpu = CpuFrame(
            coreLoad = cores.associateWith { c ->
                listOf(0.42, 0.31, 0.55, 0.28, 0.19, 0.36, 0.61, 0.24)[c]
            },
            coreFreqKhz = cores.associateWith { c ->
                if (c < 4) 1_804_800L else 2_400_000L
            },
            totalLoad = 0.37,
        ),
        memory = MemoryFrame(
            totalBytes = 7_800_000_000,
            availableBytes = 3_100_000_000,
            freeBytes = 1_200_000_000,
            cachedBytes = 1_900_000_000,
            swapTotalBytes = 4_000_000_000,
            swapFreeBytes = 3_400_000_000,
        ),
        zram = null,
        thermal = listOf(
            ThermalFrame("cpu-0-0", 44.2),
            ThermalFrame("cpu-0-1", 46.8),
            ThermalFrame("gpu", 51.3),
            ThermalFrame("battery", 33.0),
        ),
        battery = BatteryFrame(
            percent = 71,
            temperatureCelsius = 33.0,
            voltageMv = 4_082,
            currentMicroAmps = -612_000,
            chargeCounterMicroAmpHours = 2_900_000,
            status = "Discharging",
            health = "Good",
            plugged = "Unplugged",
            energyMilliWattHours = 11_837L,
        ),
        storage = null,
        pressure = emptyList(),
    )
}
