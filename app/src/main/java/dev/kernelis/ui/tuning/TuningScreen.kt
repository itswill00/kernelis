package dev.kernelis.ui.tuning

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import dev.kernelis.ui.components.DetailRow
import dev.kernelis.ui.components.Pill
import dev.kernelis.ui.components.SectionCard
import dev.kernelis.ui.components.ThinDivider
import dev.kernelis.ui.theme.LocalKernelisColors
import dev.kernelis.ui.theme.NumericTextStyle
import dev.kernelis.ui.theme.Spacing

/**
 * Tuning screen.
 *
 * Layout: a sticky domain strip, then one card per control. Each card answers, in order:
 * what is the current value, what can I set it to, and what happens if I do.
 *
 * Nothing is written here. Staging produces a plan; the plan dialog is the only path to
 * a write, and it states the diff, the snapshot guarantee and the risk level first.
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
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            CapabilityDomain.entries.forEach { domain ->
                FilterChip(
                    selected = state.domain == domain,
                    onClick = { viewModel.selectDomain(domain) },
                    label = { Text(domain.label, style = MaterialTheme.typography.labelMedium) },
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
                        trailing = {
                            Pill(
                                text = if (state.resultIsError) "issue" else "ok",
                                container = if (state.resultIsError) colors.dangerSoft else colors.accentSoft,
                                content = if (state.resultIsError) colors.danger else colors.accent,
                            )
                        },
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
                            "This is not an error: the interface simply is not exposed. " +
                                "Diagnostics lists every probed control and why it is unavailable.",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textTertiary,
                        )
                    }
                }
            } else {
                // Available controls first; unavailable ones stay visible (with the reason)
                // so the user learns what this kernel does not support.
                val (available, unavailable) = state.rows.partition { it.isEditable }
                items(available, key = { it.path + (it.scope ?: "") }) { row ->
                    ControlCard(row, viewModel)
                }
                if (unavailable.isNotEmpty()) {
                    item("unsupported-header") {
                        SectionCard(
                            title = "Not available",
                            subtitle = "${unavailable.size} control(s) probed but unsupported",
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                unavailable.forEach { row ->
                                    DetailRow(
                                        label = row.label + (row.scope?.let { " · $it" } ?: ""),
                                        value = row.availability.reasonText,
                                        valueColor = colors.textTertiary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Staged-changes bar pinned above the nav bar.
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
            OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) { Text("Discard") }
        }
    }
}

@Composable
private fun ControlCard(
    row: TuningViewModel.ControlRow,
    viewModel: TuningViewModel,
) {
    val colors = LocalKernelisColors.current
    var textValue by remember(row.path, row.currentValue) {
        mutableStateOf(row.stagedValue ?: row.currentValue ?: "")
    }

    SectionCard(
        title = row.label,
        subtitle = row.scope ?: row.path.substringAfterLast('/'),
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (row.isStaged) {
                    Pill(text = "staged", container = colors.accentSoft, content = colors.accent)
                }
                if (row.risk == Risk.HIGH) {
                    Pill(text = "high risk", container = colors.dangerSoft, content = colors.danger)
                } else if (row.risk == Risk.MEDIUM) {
                    Pill(text = "medium", container = colors.warning.copy(alpha = 0.14f), content = colors.warning)
                }
            }
        },
    ) {
        // Current value is the anchor; staged value is shown as a transition.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.currentValue?.let { "$it${row.units?.let { u -> " $u" } ?: ""}" } ?: "N/A",
                style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
                fontWeight = FontWeight.Medium,
                color = if (row.currentValue == null) colors.textTertiary else MaterialTheme.colorScheme.onSurface,
            )
            if (row.isStaged) {
                Text(
                    "→ ${row.stagedValue}",
                    style = NumericTextStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
                    color = colors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))

        when (row.valueKind) {
            ValueKind.ENUM -> EnumControl(row, viewModel)
            ValueKind.BOOLEAN -> BooleanControl(row, viewModel)
            else -> NumericControl(row, viewModel, textValue) { textValue = it }
        }

        if (row.isStaged) {
            Spacer(Modifier.height(Spacing.xs))
            TextButton(onClick = { viewModel.unstage(row) }) { Text("Unstage") }
        }
    }
}

@Composable
private fun EnumControl(row: TuningViewModel.ControlRow, viewModel: TuningViewModel) {
    val colors = LocalKernelisColors.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        row.samples.forEach { sample ->
            val selected = (row.stagedValue ?: row.currentValue) == sample
            OutlinedButton(
                onClick = { viewModel.stageValue(row, sample) },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    containerColor = if (selected) colors.accentSoft else MaterialTheme.colorScheme.surface,
                    contentColor = if (selected) colors.accent else MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(
                    sample,
                    style = NumericTextStyle,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BooleanControl(row: TuningViewModel.ControlRow, viewModel: TuningViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedButton(
            onClick = { viewModel.stageValue(row, "1") },
            modifier = Modifier.weight(1f),
        ) { Text("Enable") }
        OutlinedButton(
            onClick = { viewModel.stageValue(row, "0") },
            modifier = Modifier.weight(1f),
        ) { Text("Disable") }
    }
}

@Composable
private fun NumericControl(
    row: TuningViewModel.ControlRow,
    viewModel: TuningViewModel,
    textValue: String,
    onTextChange: (String) -> Unit,
) {
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
            supportingText = row.numericRange?.let { range ->
                { Text("${range.min} – ${range.max}${row.units?.let { " $it" } ?: ""}", style = MaterialTheme.typography.labelSmall) }
            },
        )
        Button(
            onClick = { viewModel.stageValue(row, textValue) },
            enabled = textValue.isNotBlank() && textValue != row.currentValue,
        ) { Text("Stage") }
    }
    // Frequency tables are a notch list; offer them as chips instead of free text.
    if (row.samples.isNotEmpty() && row.samples.size <= 24) {
        Spacer(Modifier.height(Spacing.sm))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            row.samples.forEach { notch ->
                FilterChip(
                    selected = textValue == notch,
                    onClick = {
                        onTextChange(notch)
                        viewModel.stageValue(row, notch)
                    },
                    label = { Text(notch, style = MaterialTheme.typography.labelSmall) },
                )
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
                        "HIGH risk. This can make the device unstable. It is a runtime setting: " +
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
