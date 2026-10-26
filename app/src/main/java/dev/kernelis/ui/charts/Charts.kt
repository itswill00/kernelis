package dev.kernelis.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kernelis.ui.theme.LocalKernelisColors
import dev.kernelis.ui.theme.Spacing

/**
 * Chart primitives drawn directly with Canvas.
 *
 * No chart library is used on purpose: KERNELIS needs three very specific visuals —
 * per-core bars, a time-series line and a capacity gauge — and each is small enough to
 * own, which keeps the APK lean and the visuals consistent with the palette.
 *
 * All charts are pure functions of their data: no animation state, no sampling of their
 * own, no side effects. The sampler owns cadence; charts just draw.
 */

/** Per-core CPU bars. Null load renders as an empty track, never as 0%. */
@Composable
public fun CoreLoadBars(
    loads: List<Double?>,
    frequenciesKhz: List<Long?>,
    modifier: Modifier = Modifier,
    barColor: Color = LocalKernelisColors.current.accent,
) {
    val colors = LocalKernelisColors.current
    Canvas(modifier = modifier.fillMaxWidth().height(height = 96.dp)) {
        if (loads.isEmpty()) return@Canvas
        val gap = 6.dp.toPx()
        val barWidth = (size.width - gap * (loads.size - 1)) / loads.size
        val trackHeight = size.height - 20.dp.toPx()

        loads.forEachIndexed { index, load ->
            val x = index * (barWidth + gap)
            // Track
            drawRoundRect(
                color = colors.surfaceHigh,
                topLeft = Offset(x, 0f),
                size = Size(barWidth, trackHeight),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            // Fill from bottom: a null load is an empty track.
            if (load != null) {
                val fraction = load.coerceIn(0.0, 1.0).toFloat()
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, trackHeight * (1f - fraction)),
                    size = Size(barWidth, trackHeight * fraction),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
            // Frequency label rendered below the track as a tick, drawn as a 2dp line
            // whose length scales with frequency so labels never jitter the layout.
            val freq = frequenciesKhz.getOrNull(index)
            if (freq != null && loads.getOrNull(index) != null) {
                val maxFreq = frequenciesKhz.filterNotNull().maxOrNull() ?: freq
                val tickFraction = (freq.toDouble() / maxFreq.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
                drawLine(
                    color = colors.textTertiary,
                    start = Offset(x, trackHeight + 8.dp.toPx()),
                    end = Offset(x + barWidth * tickFraction, trackHeight + 8.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
    }
}

/** Time-series line with a soft area fill. Null points create gaps, not zero dips. */
@Composable
public fun TimeSeriesChart(
    series: List<Double?>,
    modifier: Modifier = Modifier,
    lineColor: Color = LocalKernelisColors.current.accent,
    minY: Double = 0.0,
    maxY: Double = 1.0,
    fill: Boolean = true,
) {
    val colors = LocalKernelisColors.current
    Canvas(modifier = modifier.fillMaxWidth().height(120.dp)) {
        if (series.size < 2) return@Canvas
        val range = (maxY - minY).coerceAtLeast(0.0001)
        val stepX = size.width / (series.size - 1)

        val line = Path()
        var drawing = false
        for ((index, value) in series.withIndex()) {
            if (value == null) {
                // Null creates a gap: the line resumes at the next non-null point.
                drawing = false
                continue
            }
            val x = index * stepX
            val y = size.height - (value.coerceIn(minY, maxY) - minY).toFloat() / range.toFloat() * size.height
            if (drawing) {
                line.lineTo(x, y)
            } else {
                line.moveTo(x, y)
                drawing = true
            }
        }

        drawPath(line, color = lineColor, style = Stroke(width = 2.dp.toPx()))

        if (fill) {
            val area = Path().apply {
                addPath(line)
                lineTo((series.size - 1) * stepX, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(area, color = lineColor.copy(alpha = 0.12f))
        }

        // Baseline for orientation.
        drawLine(
            color = colors.surfaceHigh,
            start = Offset(0f, size.height - 1),
            end = Offset(size.width, size.height - 1),
            strokeWidth = 1f,
        )
    }
}

/** Horizontal capacity gauge used for RAM, swap, storage and battery. */
@Composable
public fun CapacityGauge(
    fraction: Double?,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    val colors = LocalKernelisColors.current
    androidx.compose.foundation.layout.Column(modifier = modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            androidx.compose.material3.Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
            androidx.compose.material3.Text(
                valueText,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = colors.textTertiary,
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.xs))
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            drawRoundRect(
                color = colors.surfaceHigh,
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
            if (fraction != null) {
                val f = fraction.coerceIn(0.0, 1.0).toFloat()
                drawRoundRect(
                    color = color ?: colors.accent,
                    size = Size(size.width * f, size.height),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
    }
}

/** Legend dot + label row for multi-series charts. */
@Composable
public fun SeriesLegend(
    entries: List<Pair<String, Color>>,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.padding(top = Spacing.xs)) {
        entries.forEachIndexed { index, (label, color) ->
            if (index > 0) androidx.compose.foundation.layout.Spacer(Modifier.padding(start = Spacing.md))
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Canvas(Modifier.height(8.dp).width(8.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(4f)) }
                androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                androidx.compose.material3.Text(
                    label,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = LocalKernelisColors.current.textTertiary,
                )
            }
        }
    }
}
