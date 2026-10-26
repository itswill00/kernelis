package dev.kernelis.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LifecycleResumeEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kernelis.core.capability.CapabilityDomain
import dev.kernelis.core.telemetry.TelemetryFrame
import dev.kernelis.ui.charts.CapacityGauge
import dev.kernelis.ui.charts.CoreLoadBars
import dev.kernelis.ui.charts.TimeSeriesChart
import dev.kernelis.ui.components.SectionCard
import dev.kernelis.ui.components.StatRow
import dev.kernelis.ui.theme.LocalKernelisColors
import dev.kernelis.ui.theme.Spacing

/**
 * The dashboard: one screen answering "what is this device doing right now".
 *
 * Every metric is real, sourced from the last collected telemetry frame. When the device
 * does not expose a metric, the widget shows N/A — a blank gauge, not a fake zero.
 */
@Composable
public fun DashboardScreen(
    onOpenTuning: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.resume()
        onPauseOrDispose { viewModel.pause() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        when (val capability = state.capabilityState) {
            is dev.kernelis.domain.CapabilityService.State.Scanning -> {
                ScanningCard()
            }
            is dev.kernelis.domain.CapabilityService.State.Failed -> RootRequiredCard(
                reason = capability.reason,
                onRetry = viewModel::startScan,
            )
            is dev.kernelis.domain.CapabilityService.State.Idle -> {
                // Kick off initial scan once.
                androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.startScan() }
                ScanningCard()
            }
            is dev.kernelis.domain.CapabilityService.State.Ready -> {
                val frame = state.frame
                if (frame == null) {
                    ScanningCard()
                } else {
                    DashboardContent(
                        state = state,
                        frame = frame,
                        onOpenTuning = onOpenTuning,
                        onOpenDiagnostics = onOpenDiagnostics,
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardContent(
    state: DashboardViewModel.UiState,
    frame: TelemetryFrame,
    onOpenTuning: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val colors = LocalKernelisColors.current

    // --- Header: provider + live status -----------------------------------------
    SectionCard(title = "Runtime", subtitle = state.rootProviderLabel ?: "unknown provider") {
        StatRow(
            listOf(
                Triple("CPU", "${formatLoad(frame.cpu.totalLoad)}", colors.accent),
                Triple("Cores", "${frame.cpu.coreLoad.size}", null),
                Triple("RAM", formatMemory(frame.memory?.usedBytes, frame.memory?.totalBytes), null),
                Triple("Battery", frame.battery?.percent?.let { "$it%" } ?: "N/A", null),
            ),
        )
    }

    // --- CPU ---------------------------------------------------------------------
    SectionCard(title = "CPU", subtitle = "per-core load · kHz frequency ticks") {
        val sortedCores = frame.cpu.coreLoad.keys.sorted()
        CoreLoadBars(
            loads = sortedCores.map { frame.cpu.coreLoad[it] },
            frequenciesKhz = sortedCores.map { frame.cpu.coreFreqKhz[it] },
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            "Utilisation (%)",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
        )
        TimeSeriesChart(
            series = state.loadHistory,
            minY = 0.0,
            maxY = 1.0,
        )
    }

    // --- Frequency ---------------------------------------------------------------
    SectionCard(title = "Frequency", subtitle = "max core clock (GHz)") {
        TimeSeriesChart(
            series = state.freqHistory,
            minY = 0.0,
            maxY = (state.freqHistory.filterNotNull().maxOrNull() ?: 1.0).coerceAtLeast(0.5),
        )
    }

    // --- Memory ------------------------------------------------------------------
    SectionCard(title = "Memory", subtitle = frame.zram?.algorithm?.let { "zram · $it" } ?: "RAM + swap") {
        val memory = frame.memory
        CapacityGauge(
            fraction = memory?.usedFraction,
            label = "RAM",
            valueText = formatMemory(memory?.usedBytes, memory?.totalBytes),
        )
        Spacer(Modifier.height(Spacing.sm))
        CapacityGauge(
            fraction = memory?.takeIf { it.swapTotalBytes > 0 }?.let { it.swapUsedBytes.toDouble() / it.swapTotalBytes },
            label = "Swap",
            valueText = formatMemory(memory?.swapUsedBytes, memory?.swapTotalBytes),
        )
        frame.zram?.let { zram ->
            Spacer(Modifier.height(Spacing.sm))
            CapacityGauge(
                fraction = zram.compressionRatio?.let { 1.0 / it },
                label = "zram compression",
                valueText = zram.compressionRatio?.let { "${"%.2f".format(it)}×" } ?: "N/A",
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Text(
            "RAM usage (%)",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
        )
        TimeSeriesChart(series = state.ramHistory, minY = 0.0, maxY = 1.0)
    }

    // --- Thermal -----------------------------------------------------------------
    SectionCard(title = "Thermal", subtitle = "kernel thermal zones") {
        if (frame.thermal.isEmpty()) {
            Text(
                "No thermal zones exposed by this kernel.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                frame.thermal.forEach { zone ->
                    val levelColor = when (zone.level) {
                        dev.kernelis.core.telemetry.ThermalFrame.Level.HOT -> colors.danger
                        dev.kernelis.core.telemetry.ThermalFrame.Level.WARM -> colors.warning
                        else -> colors.success
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(zone.name, style = MaterialTheme.typography.bodySmall)
                        Text(
                            zone.celsius?.let { "%.1f°C".format(it) } ?: "N/A",
                            style = MaterialTheme.typography.bodySmall,
                            color = levelColor,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.md))
            Text(
                "Hottest zone (°C)",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
            val maxTemp = state.tempHistory.filterNotNull().maxOrNull() ?: 90.0
            TimeSeriesChart(series = state.tempHistory, minY = 20.0, maxY = maxTemp.coerceAtLeast(40.0))
        }
    }

    // --- Battery -----------------------------------------------------------------
    SectionCard(title = "Battery", subtitle = frame.battery?.status ?: "framework readout") {
        val battery = frame.battery
        if (battery == null) {
            Text("Not available.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
        } else {
            CapacityGauge(
                fraction = battery.percent?.div(100.0),
                label = "Charge",
                valueText = battery.percent?.let { "$it%" } ?: "N/A",
                color = when {
                    (battery.percent ?: 100) <= 15 -> colors.danger
                    (battery.percent ?: 100) <= 30 -> colors.warning
                    else -> colors.success
                },
            )
            Spacer(Modifier.height(Spacing.md))
            StatRow(
                listOf(
                    Triple("Temp", battery.temperatureCelsius?.let { "%.1f°C".format(it) } ?: "N/A", null),
                    Triple("Current", battery.currentMicroAmps?.let { "${it / 1000} mA" } ?: "N/A", null),
                    Triple("Voltage", battery.voltageMv?.let { "%.2f V".format(it / 1000.0) } ?: "N/A", null),
                    Triple("Health", battery.health ?: "N/A", null),
                ),
            )
        }
    }

    // --- Pressure ----------------------------------------------------------------
    if (frame.pressure.isNotEmpty()) {
        SectionCard(title = "Pressure stall information", subtitle = "kernel PSI") {
            frame.pressure.forEach { psi ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(psi.resource.uppercase(), style = MaterialTheme.typography.labelSmall)
                    Text(
                        psi.someAvg10?.let { "%.2f".format(it) } ?: "N/A",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }
            }
        }
    }

    // --- Storage -----------------------------------------------------------------
    frame.storage?.dataTotalBytes?.takeIf { it > 0 }?.let { total ->
        SectionCard(title = "Storage", subtitle = "/data") {
            CapacityGauge(
                fraction = 1.0 - (frame.storage.dataFreeBytes?.toDouble() ?: 0.0) / total,
                label = "Used",
                valueText = formatMemory(frame.storage.dataFreeBytes?.let { total - it }, total),
            )
        }
    }

    // --- Footer actions ----------------------------------------------------------
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Button(onClick = onOpenTuning, modifier = Modifier.weight(1f)) { Text("Tuning") }
        androidx.compose.material3.OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.weight(1f)) {
            Text("Diagnostics")
        }
    }
}

@Composable
private fun ScanningCard() {
    Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(Spacing.lg))
            Text("Scanning kernel capabilities…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RootRequiredCard(reason: String, onRetry: () -> Unit) {
    val colors = LocalKernelisColors.current
    SectionCard(title = "Root required", subtitle = null) {
        Text(
            reason,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.danger,
        )
        Spacer(Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

internal fun formatLoad(load: Double?): String =
    load?.let { "${(it * 100).toInt()}%" } ?: "N/A"

internal fun formatMemory(used: Long?, total: Long?): String {
    if (used == null || total == null || total <= 0) return "N/A"
    val usedGiB = used / (1024.0 * 1024 * 1024)
    val totalGiB = total / (1024.0 * 1024 * 1024)
    return if (totalGiB >= 1.0) "%.1f / %.1f GB".format(usedGiB, totalGiB) else "%.0f / %.0f MB".format(used / 1e6, total / 1e6)
}
