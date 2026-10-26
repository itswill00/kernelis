package dev.kernelis.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kernelis.core.telemetry.TelemetryFrame
import dev.kernelis.core.telemetry.ThermalFrame
import dev.kernelis.domain.CapabilityService
import dev.kernelis.ui.charts.CapacityGauge
import dev.kernelis.ui.charts.CoreLoadBars
import dev.kernelis.ui.charts.TimeSeriesChart
import dev.kernelis.ui.components.DetailRow
import dev.kernelis.ui.components.MetricRow
import dev.kernelis.ui.components.MetricSpec
import dev.kernelis.ui.components.Pill
import dev.kernelis.ui.components.SectionCard
import dev.kernelis.ui.components.StatusDot
import dev.kernelis.ui.components.ThinDivider
import dev.kernelis.ui.theme.LocalKernelisColors
import dev.kernelis.ui.theme.Spacing

/**
 * Dashboard.
 *
 * Information hierarchy, top to bottom:
 *   1. **Live summary** — the four numbers you glance at (CPU, RAM, temp, battery).
 *   2. **CPU** — per-core bars, then load and frequency over time.
 *   3. **Memory** — RAM, swap, zram compression.
 *   4. **Thermal** — hottest zones first, colour-coded.
 *   5. **Battery / storage / pressure** — lower-priority detail.
 *
 * Uses LazyColumn rather than a scrolling Column so thermal-zone lists and history do
 * not compose everything at once — this is a live-updating screen and recomposition cost
 * matters.
 */
@Composable
public fun DashboardScreen(
    onOpenTuning: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        viewModel.resume()
        onDispose { viewModel.pause() }
    }

    when (val capability = state.capabilityState) {
        is CapabilityService.State.Idle -> {
            LaunchedEffect(Unit) { viewModel.startScan() }
            CenteredMessage("Scanning kernel capabilities…", showSpinner = true)
        }
        is CapabilityService.State.Scanning -> CenteredMessage("Scanning kernel capabilities…", showSpinner = true)
        is CapabilityService.State.Failed -> RootRequired(
            reason = capability.reason,
            onRetry = viewModel::startScan,
        )
        is CapabilityService.State.Ready -> {
            val frame = state.frame
            if (frame == null) {
                CenteredMessage("Waiting for the first telemetry frame…", showSpinner = true)
            } else {
                DashboardContent(state, frame, onOpenTuning, onOpenDiagnostics, viewModel)
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
    viewModel: DashboardViewModel,
) {
    val colors = LocalKernelisColors.current
    val hottest = frame.thermal.mapNotNull { it.celsius }.maxOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // ---------------------------------------------------------------- summary
        item("summary") {
            SectionCard(
                title = "Now",
                subtitle = state.rootProviderLabel?.let { "root · $it" },
                trailing = {
                    StatusDot(color = colors.success, label = "live")
                },
            ) {
                MetricRow(
                    listOf(
                        MetricSpec("CPU", formatPercent(frame.cpu.totalLoad), accent = colors.accent),
                        MetricSpec("RAM", frame.memory?.let { "${(it.usedFraction * 100).toInt()}" }, unit = "%"),
                        MetricSpec("Temp", hottest?.let { "%.0f".format(it) }, unit = "°C", accent = tempColor(hottest)),
                        MetricSpec("Battery", frame.battery?.percent?.toString(), unit = "%"),
                    ),
                )
                if (frame.thermal.isEmpty() && frame.memory == null) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "This kernel exposes limited telemetry; unavailable values show as N/A.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }
            }
        }

        // -------------------------------------------------------------------- CPU
        item("cpu-header") {
            SectionCard(
                title = "CPU",
                subtitle = "${frame.cpu.coreLoad.size} cores reporting",
            ) {
                val cores = frame.cpu.coreLoad.keys.sorted()
                CoreLoadBars(
                    loads = cores.map { frame.cpu.coreLoad[it] },
                    frequenciesKhz = cores.map { frame.cpu.coreFreqKhz[it] },
                )
                Spacer(Modifier.height(Spacing.lg))
                ChartBlock(
                    label = "Utilisation",
                    series = state.loadHistory,
                    minY = 0.0,
                    maxY = 1.0,
                    valueText = formatPercent(frame.cpu.totalLoad),
                )
                Spacer(Modifier.height(Spacing.md))
                val maxGhz = state.freqHistory.filterNotNull().maxOrNull()
                ChartBlock(
                    label = "Peak clock",
                    series = state.freqHistory,
                    minY = 0.0,
                    maxY = (maxGhz ?: 1.0).coerceAtLeast(0.5),
                    valueText = maxGhz?.let { "%.2f GHz".format(it) } ?: "N/A",
                )
            }
        }

        // ----------------------------------------------------------------- memory
        item("memory") {
            SectionCard(
                title = "Memory",
                subtitle = frame.zram?.algorithm?.let { "zram · $it" },
            ) {
                val memory = frame.memory
                if (memory == null) {
                    Text("No /proc/meminfo readable.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                } else {
                    CapacityGauge(
                        fraction = memory.usedFraction,
                        label = "RAM used",
                        valueText = "${formatBytes(memory.usedBytes)} / ${formatBytes(memory.totalBytes)}",
                    )
                    if (memory.swapTotalBytes > 0) {
                        Spacer(Modifier.height(Spacing.md))
                        CapacityGauge(
                            fraction = memory.swapUsedBytes.toDouble() / memory.swapTotalBytes,
                            label = "Swap used",
                            valueText = "${formatBytes(memory.swapUsedBytes)} / ${formatBytes(memory.swapTotalBytes)}",
                        )
                    }
                    frame.zram?.compressionRatio?.let { ratio ->
                        Spacer(Modifier.height(Spacing.md))
                        DetailRow("zram compression", "%.2f×".format(ratio))
                    }
                    Spacer(Modifier.height(Spacing.lg))
                    ChartBlock(
                        label = "RAM pressure",
                        series = state.ramHistory,
                        minY = 0.0,
                        maxY = 1.0,
                        valueText = "${(memory.usedFraction * 100).toInt()}%",
                    )
                }
            }
        }

        // ---------------------------------------------------------------- thermal
        if (frame.thermal.isNotEmpty()) {
            item("thermal-header") {
                SectionCard(
                    title = "Thermal",
                    subtitle = "${frame.thermal.size} zones · hottest first",
                ) {
                    ChartBlock(
                        label = "Hottest zone",
                        series = state.tempHistory,
                        minY = 20.0,
                        maxY = (state.tempHistory.filterNotNull().maxOrNull() ?: 90.0).coerceAtLeast(45.0),
                        valueText = hottest?.let { "%.1f°C".format(it) } ?: "N/A",
                    )
                }
            }
            items(frame.thermal.sortedByDescending { it.celsius ?: -1.0 }, key = { it.name }) { zone ->
                ThermalRow(zone)
            }
        }

        // ---------------------------------------------------------------- battery
        item("battery") {
            SectionCard(
                title = "Battery",
                subtitle = listOfNotNull(frame.battery?.status, frame.battery?.health).joinToString(" · ").ifBlank { null },
            ) {
                val battery = frame.battery
                if (battery == null) {
                    Text("BatteryManager unavailable.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
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
                    MetricRow(
                        listOf(
                            MetricSpec("Temp", battery.temperatureCelsius?.let { "%.1f".format(it) }, "°C"),
                            MetricSpec("Current", battery.currentMicroAmps?.let { "${it / 1000}" }, "mA"),
                            MetricSpec("Voltage", battery.voltageMv?.let { "%.2f".format(it / 1000.0) }, "V"),
                        ),
                    )
                }
            }
        }

        // ---------------------------------------------------------------- storage
        frame.storage?.dataTotalBytes?.takeIf { it > 0 }?.let { total ->
            item("storage") {
                SectionCard(title = "Storage", subtitle = "/data") {
                    val free = frame.storage.dataFreeBytes ?: 0L
                    CapacityGauge(
                        fraction = 1.0 - free.toDouble() / total,
                        label = "Used",
                        valueText = "${formatBytes(total - free)} / ${formatBytes(total)}",
                    )
                }
            }
        }

        // --------------------------------------------------------------- pressure
        if (frame.pressure.isNotEmpty()) {
            item("pressure") {
                SectionCard(title = "Pressure stall", subtitle = "kernel PSI · 10s average") {
                    frame.pressure.forEach { psi ->
                        DetailRow(
                            label = psi.resource.uppercase(),
                            value = psi.someAvg10?.let { "%.2f".format(it) } ?: "N/A",
                            mono = true,
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------- actions
        item("actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(onClick = onOpenTuning, modifier = Modifier.weight(1f)) { Text("Tuning") }
                OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.weight(1f)) {
                    Text("Diagnostics")
                }
            }
        }
    }
}

@Composable
private fun ChartBlock(
    label: String,
    series: List<Double?>,
    minY: Double,
    maxY: Double,
    valueText: String?,
) {
    val colors = LocalKernelisColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
        Text(
            valueText ?: "N/A",
            style = dev.kernelis.ui.theme.NumericTextStyle,
            color = if (valueText == null) colors.textTertiary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
    Spacer(Modifier.height(Spacing.xs))
    TimeSeriesChart(series = series, minY = minY, maxY = maxY)
}

@Composable
private fun ThermalRow(zone: ThermalFrame) {
    val colors = LocalKernelisColors.current
    val color = tempColor(zone.celsius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            zone.name,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Spacer(Modifier.padding(start = Spacing.sm))
        Pill(
            text = zone.celsius?.let { "%.1f°C".format(it) } ?: "N/A",
            container = color.copy(alpha = 0.12f),
            content = color,
        )
    }
}

@Composable
private fun CenteredMessage(text: String, showSpinner: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showSpinner) {
                CircularProgressIndicator()
                Spacer(Modifier.height(Spacing.lg))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RootRequired(reason: String, onRetry: () -> Unit) {
    val colors = LocalKernelisColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.lg),
        verticalArrangement = Arrangement.Center,
    ) {
        SectionCard(title = "Root required") {
            Text(reason, style = MaterialTheme.typography.bodyMedium, color = colors.danger)
            Spacer(Modifier.height(Spacing.md))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "KERNELIS needs a working su binary (Magisk, KernelSU, APatch or equivalent). " +
                    "If you already granted root, check the root manager's app list and DenyList.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        }
    }
}

@Composable
private fun tempColor(celsius: Double?): androidx.compose.ui.graphics.Color {
    val colors = LocalKernelisColors.current
    return when {
        celsius == null -> colors.textTertiary
        celsius >= 75.0 -> colors.danger
        celsius >= 55.0 -> colors.warning
        else -> colors.success
    }
}

internal fun formatPercent(fraction: Double?): String? =
    fraction?.let { "${(it.coerceIn(0.0, 1.0) * 100).toInt()}" }

internal fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "N/A"
    val gib = bytes / (1024.0 * 1024 * 1024)
    val mib = bytes / (1024.0 * 1024)
    return when {
        gib >= 1.0 -> "%.1f GB".format(gib)
        else -> "%.0f MB".format(mib)
    }
}
