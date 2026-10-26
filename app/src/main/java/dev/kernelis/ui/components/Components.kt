package dev.kernelis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.kernelis.ui.theme.LocalKernelisColors
import dev.kernelis.ui.theme.NumericTextStyle
import dev.kernelis.ui.theme.Spacing

/**
 * Shared layout primitives.
 *
 * Cards are flat (no elevation) with a 1px hairline outline: on an OLED dashboard,
 * elevation shadows create banding and compete with data for attention.
 */
public val CardShape: RoundedCornerShape = RoundedCornerShape(16.dp)

@Composable
public fun SectionCard(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = LocalKernelisColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            if (title != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textTertiary,
                            )
                        }
                    }
                    trailing?.invoke()
                }
                Spacer(Modifier.height(Spacing.md))
            }
            content()
        }
    }
}

/** Big numeric readout used across the dashboard. Monospace keeps digits from jittering. */
@Composable
public fun StatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    val colors = LocalKernelisColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
        )
        Text(
            value,
            style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
            fontWeight = FontWeight.Medium,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Row of two to four [StatChip]s, evenly weighted. */
@Composable
public fun StatRow(
    stats: List<Triple<String, String, Color?>>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        stats.forEach { (label, value, color) ->
            Column(Modifier.weight(1f)) { StatChip(label, value, accent = color) }
        }
    }
}

/** Renders an N/A placeholder when a metric is unavailable — never a fabricated 0. */
@Composable
public fun metricText(value: String?): String = value ?: "N/A"

/** Small pill used for risk badges and statuses. */
@Composable
public fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color? = null,
    content: Color? = null,
) {
    val colors = LocalKernelisColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = content ?: colors.textSecondary(),
        modifier = modifier
            .background(
                color = container ?: colors.surfaceHigh,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private fun LocalKernelisColors.textSecondary() = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
