package dev.kernelis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kernelis.ui.theme.LocalKernelisColors
import dev.kernelis.ui.theme.NumericTextStyle
import dev.kernelis.ui.theme.Spacing

/**
 * Layout primitives.
 *
 * Rules the whole UI follows, so screens stay consistent:
 *  - One idea per card. A card never mixes monitoring and editing.
 *  - Section headers are small-caps labels; values are large and monospaced so digits
 *    do not shift as they update.
 *  - Colour carries meaning only (status, risk, heat). Never decoration.
 *  - Hairline borders, no elevation: shadows band badly on OLED and compete with data.
 */
public val CardShape: RoundedCornerShape = RoundedCornerShape(16.dp)

@Composable
public fun SectionCard(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(contentPadding)) {
            if (title != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (subtitle != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalKernelisColors.current.textTertiary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (trailing != null) {
                        Spacer(Modifier.width(Spacing.sm))
                        trailing()
                    }
                }
                Spacer(Modifier.height(Spacing.md))
            }
            content()
        }
    }
}

/**
 * A metric readout: small label above, large monospaced value below.
 *
 * [value] is nullable on purpose. When a device does not expose a metric the widget
 * renders "N/A" — it never shows 0, which would be a lie the user cannot detect.
 */
@Composable
public fun Metric(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    unit: String? = null,
    accent: Color? = null,
    emphasis: Boolean = false,
) {
    val colors = LocalKernelisColors.current
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value ?: "N/A",
                style = if (emphasis) {
                    MaterialTheme.typography.headlineSmall.copy(fontFamily = NumericTextStyle.fontFamily)
                } else {
                    NumericTextStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize)
                },
                fontWeight = FontWeight.Medium,
                color = if (value == null) colors.textTertiary else (accent ?: MaterialTheme.colorScheme.onSurface),
                maxLines = 1,
            )
            if (unit != null && value != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

/** Evenly weighted row of metrics. Splits into two rows above three items. */
@Composable
public fun MetricRow(
    metrics: List<MetricSpec>,
    modifier: Modifier = Modifier,
) {
    if (metrics.size <= 3) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            metrics.forEach { spec ->
                Metric(
                    label = spec.label,
                    value = spec.value,
                    unit = spec.unit,
                    accent = spec.accent,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            metrics.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    row.forEach { spec ->
                        Metric(
                            label = spec.label,
                            value = spec.value,
                            unit = spec.unit,
                            accent = spec.accent,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keep alignment when the last row is short.
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

public data class MetricSpec(
    val label: String,
    val value: String?,
    val unit: String? = null,
    val accent: Color? = null,
)

/** Compact status pill. */
@Composable
public fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color? = null,
    content: Color? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = content ?: MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier
            .background(
                color = container ?: LocalKernelisColors.current.surfaceHigh,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** A dot + label, used for live status and legend entries. */
@Composable
public fun StatusDot(
    color: Color,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = LocalKernelisColors.current.textTertiary,
        )
    }
}

/** Key/value line for dense detail lists (diagnostics, paths, capabilities). */
@Composable
public fun DetailRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    mono: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalKernelisColors.current.textTertiary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            value ?: "N/A",
            style = if (mono) NumericTextStyle.copy(fontSize = 12.sp) else MaterialTheme.typography.bodySmall,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.2f),
        )
    }
}

/** Thin divider matching the card outline colour. */
@Composable
public fun ThinDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
}

/**
 * A single tunable rendered as a Material 3 list item rather than a card.
 *
 * Rationale (from studying FK Manager, RvKernel Manager, Minimal Kernel Manager and the
 * M3 lists spec): one row per control is scannable; a card per control is not. The row
 * shows, in order of importance: the value being tuned (headline), the control itself
 * (slider or value), and only then metadata (path, risk) in the supporting line.
 *
 * `enabled = false` renders the reason instead of hiding the row — an unsupported
 * control stays visible with its reason, per the "no silent absence" rule.
 */
private val VALUE_COLUMN_MIN_WIDTH = 64.dp

@Composable
public fun SettingRow(
    title: String,
    value: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    supporting: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    disabledReason: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalKernelisColors.current
    val rowModifier = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    } else {
        modifier.fillMaxWidth()
    }
    Column(
        modifier = rowModifier
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else colors.textTertiary,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (value != null) {
                Spacer(Modifier.width(Spacing.md))
                Text(
                    value,
                    style = NumericTextStyle.copy(
                        fontSize = MaterialTheme.typography.titleSmall.fontSize,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) colors.accent else colors.textTertiary,
                    // Fixed minimum width + end alignment gives every row the same
                    // right-hand value column, so a list of numbers can be scanned
                    // vertically. Taken from Scene's CPU cluster grid, which pins the
                    // value column at 150dp and aligns it to the end for this reason.
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = VALUE_COLUMN_MIN_WIDTH),
                )
            }
        }
        if (!enabled && disabledReason != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                disabledReason,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        } else if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (supporting != null) {
            Spacer(Modifier.height(Spacing.sm))
            supporting()
        }
        if (trailing != null) {
            Spacer(Modifier.height(Spacing.xs))
            trailing()
        }
    }
}
