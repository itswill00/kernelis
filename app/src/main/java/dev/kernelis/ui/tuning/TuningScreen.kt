package dev.kernelis.ui.tuning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kernelis.core.capability.CapabilityDomain
import dev.kernelis.core.capability.Risk
import dev.kernelis.core.capability.ValueKind
import dev.kernelis.ui.components.Pill
import dev.kernelis.ui.components.SectionCard
import dev.kernelis.ui.components.SettingRow
import dev.kernelis.ui.components.ThinDivider
import dev.kernelis.ui.theme.LocalKernelisColors
import dev.kernelis.ui.theme.NumericTextStyle
import dev.kernelis.ui.theme.Spacing

/**
 * Tuning screen.
 *
 * Design, after studying FK Manager / RvKernel Manager / Minimal Kernel Manager and the
 * M3 lists spec: **one list item per control inside a single card**, not a card per
 * control. Cards-per-control produced a screen where everything shouted and nothing
 * was scannable.
 *
 * Row anatomy (top to bottom, importance order):
 *   1. label + current value (the row headline, both visible without interaction)
 *   2. the control itself: slider for numbers, chips for enums, two buttons for booleans
 *   3. metadata (risk) only when it is not LOW
 *
 * Unsupported controls stay in the list with their reason — same as RvKernel Manager —
 * because "why is this missing" is exactly what the user came to learn.
 */
@Composable
public fun TuningScreen(
    viewModel: TuningViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalKernelisColors.current

    Column(Modifier.fillMaxSize()) {
        // Domain strip stays at the top so switching sections never requires scrolling up.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            CapabilityDomain.entries.forEach { domain ->
                val selected = state.domain == domain
                Pill(
                    text = domain.label,
                    container = if (selected) colors.accentSoft else colors.surfaceHigh,
                    content = if (selected) colors.accent else colors.textTertiary,
                    modifier = Modifier.clickable { viewModel.selectDomain(domain) },
                )
            }
        }
        ThinDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (state.resultMessage != null) {
                item("result") {
                    SectionCard(
                        title = if (state.resultIsError) "Result" else "Applied",
                    ) {
                        Text(
                            state.resultMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.resultIsError) colors.danger else colors.success,
                        )
                    }
                }
            }

            if (state.rows.isEmpty()) {
                item("empty") {
                    SectionCard(title = state.domain.label) {
                        Text(
                            "No ${state.domain.label.lowercase()} controls were discovered on this kernel.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textTertiary,
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "Diagnostics lists every probed control and why it is unavailable.",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textTertiary,
                        )
                    }
                }
            } else {
                val (available, unavailable) = state.rows.partition { it.isEditable }
                item("available-card") {
                    SectionCard(
                        title = "${available.size} control(s)",
                        subtitle = "changes are staged, reviewed, then applied together",
                    ) {
                        available.forEachIndexed { index, row ->
                            if (index > 0) ThinDivider()
                            ControlRow(row, viewModel)
                        }
                    }
                }
                if (unavailable.isNotEmpty()) {
                    item("unavailable-card") {
                        SectionCard(
                            title = "Not available",
                            subtitle = "${unavailable.size} probed but unsupported",
                        ) {
                            unavailable.forEachIndexed { index, row ->
                                if (index > 0) ThinDivider()
                                SettingRow(
                                    title = row.label + (row.scope?.let { " · $it" } ?: ""),
                                    value = null,
                                    enabled = false,
                                    disabledReason = row.availability.reasonText,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.plan != null) {
            StagedBar(
                state = state,
                onReview = viewModel::openPlanDialog,
                onDiscard = viewModel::clearStaged,
            )
        }
    }

    if (state.showPlanDialog && state.plan != null) {
        PlanDialog(
            state = state,
            onConfirm = { viewModel.applyStaged(acknowledgeHighRisk = true) },
            onDismiss = viewModel::closePlanDialog,
        )
    }
}

@Composable
private fun StagedBar(
    state: TuningViewModel.UiState,
    onReview: () -> Unit,
    onDiscard: () -> Unit,
) {
    val colors = LocalKernelisColors.current
    SectionCard(
        title = "${state.planPreview.size} change(s) staged",
        subtitle = state.planUnmatched.takeIf { it > 0 }?.let { "$it entry skipped (not on this kernel)" },
        trailing = { Pill(text = "not applied", container = colors.accentSoft, content = colors.accent) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(onClick = onReview, modifier = Modifier.weight(1f)) { Text("Review & apply") }
            TextButton(onClick = onDiscard, modifier = Modifier.weight(1f)) { Text("Discard") }
        }
    }
}

@Composable
private fun ControlRow(
    row: TuningViewModel.ControlRow,
    viewModel: TuningViewModel,
) {
    val colors = LocalKernelisColors.current
    // Slider state is derived from the current value; a staged value overrides it. A
    // remembered text buffer only exists for free-form numeric entry.
    var textValue by remember(row.path, row.scope, row.currentValue) {
        mutableStateOf(row.stagedValue ?: row.currentValue ?: "")
    }
    val displayValue = row.stagedValue ?: row.currentValue
    val unitSuffix = row.units?.let { " $it" } ?: ""

    SettingRow(
        title = row.label + (row.scope?.let { " · $it" } ?: ""),
        value = displayValue?.let { "$it$unitSuffix" },
        subtitle = row.path.substringAfterLast('/'),
        supporting = {
            when (row.valueKind) {
                ValueKind.ENUM -> EnumControl(row, viewModel)
                ValueKind.BOOLEAN -> BooleanControl(row, viewModel)
                else -> NumericControl(row, viewModel, textValue) { textValue = it }
            }
            if (row.isStaged) {
                Spacer(Modifier.height(Spacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(text = "staged", container = colors.accentSoft, content = colors.accent)
                    TextButton(onClick = { viewModel.unstage(row) }) { Text("Undo") }
                }
            }
            if (row.risk == Risk.HIGH) {
                Spacer(Modifier.height(Spacing.xs))
                Pill(text = "high risk", container = colors.dangerSoft, content = colors.danger)
            }
        },
    )
}

@Composable
private fun EnumControl(row: TuningViewModel.ControlRow, viewModel: TuningViewModel) {
    val colors = LocalKernelisColors.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        row.samples.forEach { sample ->
            val selected = (row.stagedValue ?: row.currentValue) == sample
            Pill(
                text = sample,
                container = if (selected) colors.accentSoft else colors.surfaceHigh,
                content = if (selected) colors.accent else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable {
                    viewModel.stageValue(row, sample)
                },
            )
        }
    }
}

@Composable
private fun BooleanControl(row: TuningViewModel.ControlRow, viewModel: TuningViewModel) {
    val current = (row.stagedValue ?: row.currentValue)?.trim()
    val colors = LocalKernelisColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Pill(
            text = "On",
            container = if (current == "1") colors.accentSoft else colors.surfaceHigh,
            content = if (current == "1") colors.accent else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clickable { viewModel.stageValue(row, "1") },
        )
        Pill(
            text = "Off",
            container = if (current == "0") colors.accentSoft else colors.surfaceHigh,
            content = if (current == "0") colors.accent else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clickable { viewModel.stageValue(row, "0") },
        )
    }
}

@Composable
private fun NumericControl(
    row: TuningViewModel.ControlRow,
    viewModel: TuningViewModel,
    textValue: String,
    onTextChange: (String) -> Unit,
) {
    val range = row.numericRange
    val current = row.stagedValue ?: row.currentValue
    val currentValueLong = current?.trim()?.toLongOrNull()

    Column {
        // Slider whenever a range exists and the current value is numeric — the primary
        // interaction, matching every kernel manager studied. Free text stays available.
        if (range != null && currentValueLong != null) {
            Slider(
                value = currentValueLong.coerceIn(range.min, range.max).toFloat(),
                onValueChange = { raw ->
                    val stepped = range.snap(raw.toLong())
                    onTextChange(stepped.toString())
                },
                onValueChangeFinished = {
                    if (textValue != row.currentValue) viewModel.stageValue(row, textValue)
                },
                valueRange = range.min.toFloat()..range.max.toFloat(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${range.min}", style = MaterialTheme.typography.labelSmall, color = LocalKernelisColors.current.textTertiary)
                Text("${range.max}${row.units?.let { " $it" } ?: ""}", style = MaterialTheme.typography.labelSmall, color = LocalKernelisColors.current.textTertiary)
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = NumericTextStyle,
                )
                TextButton(
                    onClick = { viewModel.stageValue(row, textValue) },
                    enabled = textValue.isNotBlank() && textValue != row.currentValue,
                ) { Text("Stage") }
            }
        }
        // Frequency tables stay as chips (a notch list, not a continuum).
        if (row.samples.isNotEmpty() && row.samples.size <= 24) {
            Spacer(Modifier.height(Spacing.xs))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                row.samples.forEach { notch ->
                    val selected = textValue == notch
                    Pill(
                        text = notch,
                        container = if (selected) LocalKernelisColors.current.accentSoft else LocalKernelisColors.current.surfaceHigh,
                        content = if (selected) LocalKernelisColors.current.accent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clickable {
                            onTextChange(notch)
                            viewModel.stageValue(row, notch)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanDialog(
    state: TuningViewModel.UiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalKernelisColors.current
    var acknowledged by remember { mutableStateOf(false) }
    val plan = state.plan ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply ${state.planPreview.size} change(s)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (state.planRequiresSnapshot) {
                    Text(
                        "A snapshot is saved first, so this operation can be rolled back from History.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.info,
                    )
                }
                state.planPreview.forEach { write ->
                    Column {
                        Text(
                            write.capability.label + (write.scope?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "${write.current ?: "N/A"} → ${write.requested}",
                            style = NumericTextStyle,
                            color = colors.accent,
                        )
                    }
                }
                val skipped = plan.skipped
                if (skipped.isNotEmpty()) {
                    ThinDivider()
                    Text(
                        "${skipped.size} unchanged or invalid entr${if (skipped.size == 1) "y" else "ies"} skipped",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }
                if (state.planRequiresAck) {
                    ThinDivider()
                    Text(
                        "HIGH risk. This can make the device unstable. Runtime setting: " +
                            "a reboot restores the previous value, so a reboot is always a safe exit.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.danger,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                        Text("I understand", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !state.applying && (!state.planRequiresAck || acknowledged),
            ) { Text(if (state.applying) "Applying…" else "Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
