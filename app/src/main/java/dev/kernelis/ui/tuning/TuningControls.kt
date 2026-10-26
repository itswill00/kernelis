package dev.kernelis.ui.tuning

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.kernelis.core.capability.ValueKind
import dev.kernelis.ui.components.Pill
import dev.kernelis.ui.theme.LocalKernelisColors
import dev.kernelis.ui.theme.Spacing

/**
 * How a kernel value is shown to a human.
 *
 * The first version printed the raw sysfs string: "1804800 kHz". That is a number the
 * kernel uses, not one a person reads — nobody knows whether 1804800 is fast, and
 * comparing two clusters meant comparing seven-digit strings. Kernel managers that feel
 * professional (FKM, Scene, RvKernel) all show 1804 MHz and keep the raw value out of
 * the way.
 *
 * Frequency is the only kind with a well-known display convention here, so it is the
 * only one that gets scaled. Everything else is shown as-is rather than guessing: a
 * ratio of 0.35 is not "35%" of anything in particular, and inventing a unit would be
 * a lie the user cannot detect.
 */
internal object ValueDisplay {

    /** MHz with no decimals when whole, one decimal otherwise, e.g. "1804 MHz". */
    fun frequency(khz: String?, units: String?): String? {
        val raw = khz?.trim()?.toLongOrNull() ?: return khz?.trim()
        if (!units.equals("kHz", ignoreCase = true)) return "$khz $units".trim()
        val mhz = raw / 1000.0
        val text = if (mhz >= 100.0) {
            if (mhz % 1.0 == 0.0) mhz.toInt().toString() else "%.1f".format(mhz)
        } else {
            "%.2f".format(mhz)
        }
        return "$text MHz"
    }

    /** Short form for a slider endpoint: "300" / "2208" without the unit. */
    fun mhzShort(khz: Long): String {
        val mhz = khz / 1000.0
        return if (mhz % 1.0 == 0.0) mhz.toInt().toString() else "%.1f".format(mhz)
    }

    /** The value as it should appear in a row's right-hand column. */
    fun rowValue(value: String?, units: String?, kind: ValueKind): String? {
        if (value == null) return null
        return when {
            units.equals("kHz", ignoreCase = true) && kind == ValueKind.NUMBER ->
                frequency(value, units)
            units != null -> "$value $units"
            else -> value
        }
    }
}

/**
 * A section header inside a list: a quiet label, not a card title.
 *
 * Tuning previously put everything into one card titled "6 control(s)", which is a
 * number nobody wants and a grouping that means nothing. Kernel controls cluster
 * naturally by *what they act on* — a CPU cluster, the GPU, a scheduler — so the list is
 * grouped that way and each group gets a small header.
 */
@Composable
internal fun GroupHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalKernelisColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Segmented control for a small enum (governor, scheduler, on/off).
 *
 * Replaces a wrapping row of pills. Pills gave every option the same weight as the
 * selected one and reflowed into a ragged block; a segmented control shows the choices
 * as one bounded set with the active segment filled, which is both faster to read and
 * obvious to operate. Falls back to wrapping when there are too many options to fit a
 * single row, because squeezing eight governors into 360dp would truncate them.
 */
@Composable
internal fun SegmentedChoice(
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalKernelisColors.current
    if (options.isEmpty()) return
    // A single row of up to four short options; otherwise wrap into rows of three.
    val perRow = when {
        options.size <= 3 -> options.size
        options.all { it.length <= 8 } && options.size <= 4 -> options.size
        else -> 3
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.chunked(perRow).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { option ->
                    val active = option == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active) colors.accentSoft else colors.surfaceHigh)
                            .clickable { onSelect(option) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            option,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) colors.accent else colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * A frequency control: a slider over the *notch table*, not over a raw range.
 *
 * Two things were wrong before. The slider ran from min to max as a continuum, so
 * dragging it produced values the kernel would silently reject — a frequency must land
 * on one of the available steps. And its endpoints were printed as "300000" and
 * "2208000", which is unreadable.
 *
 * Here the slider indexes the notch list directly, so every position it can reach is a
 * value the kernel actually offers. The endpoints are shown in MHz, and the current
 * selection is shown once, large, instead of also being repeated as the row value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FrequencyControl(
    notches: List<String>,
    selected: String?,
    units: String?,
    onSelect: (String) -> Unit,
) {
    val colors = LocalKernelisColors.current
    val values = notches.mapNotNull { it.trim().toLongOrNull() }
    if (values.size < 2) {
        // No usable table: fall back to the enum-style chooser rather than showing a
        // slider whose range would be invented.
        SegmentedChoice(notches, selected, onSelect)
        return
    }
    val currentIdx = values.indexOfFirst { it.toString() == selected?.trim() }
        .let { if (it >= 0) it else values.indexOfLast { v -> v <= (selected?.trim()?.toLongOrNull() ?: values.first()) } }
        .coerceAtLeast(0)

    Column(Modifier.fillMaxWidth()) {
        // A custom track + thumb, because Material3's default thumb is a 4dp-wide
        // vertical bar. Next to the rounded track it reads as a second, broken thumb —
        // visible in the screenshot as a tall line beside the round handle. A circular
        // thumb matching the track's corner radius makes the control look like one
        // object instead of two.
        Slider(
            value = currentIdx.toFloat(),
            onValueChange = { f -> onSelect(values[f.toInt().coerceIn(values.indices)].toString()) },
            valueRange = 0f..(values.size - 1).toFloat(),
            steps = 0,
            thumb = {
                Box(
                    Modifier
                        .size(20.dp)
                        .background(colors.accent, androidx.compose.foundation.shape.CircleShape),
                )
            },
            track = { sliderState ->
                val fraction = if (values.size > 1) {
                    (sliderState.value / (values.size - 1)).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(colors.surfaceHigh, RoundedCornerShape(3.dp)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .background(colors.accent, RoundedCornerShape(3.dp)),
                    )
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                ValueDisplay.mhzShort(values.first()),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
            Text(
                ValueDisplay.mhzShort(values.last()),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        }
    }
}

/**
 * A toggle rendered as a single track with two labelled stops.
 *
 * The previous version used two pills reading "On" and "Off", which looked like two
 * separate actions rather than two states of one setting. A track makes the exclusivity
 * visible.
 */
@Composable
internal fun ToggleControl(
    value: String?,
    onSelect: (String) -> Unit,
) {
    val colors = LocalKernelisColors.current
    val on = value?.trim() == "1"
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(colors.surfaceHigh)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf("Off" to "0", "On" to "1").forEach { (label, raw) ->
            val active = (raw == "1") == on
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (active) colors.accent else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(raw) }
                    .padding(horizontal = 18.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) colors.onAccent else colors.textSecondary,
                )
            }
        }
    }
}

/** A short inline note under a row, used for staged state and unsupported reasons. */
@Composable
internal fun RowNote(text: String, tone: androidx.compose.ui.graphics.Color, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(5.dp)
                .background(tone, RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = tone,
        )
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.xs))
            trailing()
        }
    }
}

/** "1804 MHz" style value for the right-hand column of a control row. */
@Composable
internal fun ControlValue(value: String?, units: String?, kind: ValueKind) {
    val colors = LocalKernelisColors.current
    Text(
        ValueDisplay.rowValue(value, units, kind) ?: "—",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = colors.accent,
    )
}

/** Undo affordance next to a staged row. */
@Composable
internal fun UndoButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
        Text("Undo", style = MaterialTheme.typography.labelSmall)
    }
}

/** Small pill used for a staged marker. */
@Composable
internal fun StagedPill() {
    val colors = LocalKernelisColors.current
    Pill(text = "staged", container = colors.accentSoft, content = colors.accent)
}
