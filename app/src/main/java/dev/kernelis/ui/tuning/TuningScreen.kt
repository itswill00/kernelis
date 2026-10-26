package dev.kernelis.ui.tuning

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import dev.kernelis.core.tuning.PlannedWrite
import dev.kernelis.ui.components.Pill
import dev.kernelis.ui.components.SectionCard
import dev.kernelis.ui.theme.LocalKernelisColors
import dev.kernelis.ui.theme.Spacing

/**
 * Tuning screen.
 *
 * Flow: pick a domain → stage values → review the diff (what changes, what is skipped
 * and why) → confirm. HIGH-risk plans require an explicit acknowledgement checkbox;
 * the dialog states what the app will snapshot first, and what happens if a write is
 * rejected. The screen never writes anything itself — it only stages and plans.
 */
@Composable
public fun TuningScreen(
    viewModel: TuningViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalKernelisColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // Domain selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            CapabilityDomain.entries.forEach { domain ->
                FilterChip(
                    selected = state.domain == domain,
                    onClick = { viewModel.selectDomain(domain) },
                    label = { Text(domain.label) },
                )
            }
        }

        if (state.rows.isEmpty()) {
            SectionCard(title = state.domain.label) {
                Text(
                    "No controls discovered in this domain on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textTertiary,
                )
            }
        } else {
            state.rows.forEach { row ->
                ControlCard(
                    row = row,
                    onStage = { viewModel.stageValue(row, it) },
                    onUnstage = { viewModel.unstage(row) },
                )
            }
        }

        // Sticky action bar when something is staged
        if (state.stagedCount > 0 || state.plan != null) {
            SectionCard(
                title = "${state.planPreview.size} change(s) planned",
                subtitle = if (state.planUnmatched > 0) "${state.planUnmatched} entry skipped" else null,
            ) {
                Button(
                    onClick = viewModel::openPlanDialog,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.planPreview.isNotEmpty(),
                ) { Text("Review & apply") }
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(onClick = viewModel::clearStaged, modifier = Modifier.fillMaxWidth()) {
                    Text("Discard staged changes")
                }
            }
        }

        if (state.resultMessage != null) {
            SectionCard(title = if (state.resultIsError) "Result" else "Applied") {
                Text(
                    state.resultMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.resultIsError) colors.danger else colors.success,
                )
            }
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
private fun ControlCard(
    row: TuningViewModel.ControlRow,
    onStage: (String) -> Unit,
    onUnstage: () -> Unit,
) {
    val colors = LocalKernelisColors.current
    var textValue by remember(row.path) { mutableStateOf(row.stagedValue ?: row.currentValue ?: "") }

    SectionCard(
        title = row.label + (row.scope?.let { " · $it" } ?: ""),
        subtitle = row.path,
        trailing = {
            Pill(
                text = when {
                    !row.isEditable -> "unsupported"
                    row.isStaged -> "staged"
                    row.risk == Risk.HIGH -> "high risk"
                    row.risk == Risk.MEDIUM -> "medium"
                    else -> "low"
                },
                container = when {
                    !row.isEditable -> null
                    row.isStaged -> colors.accentSoft
                    row.risk == Risk.HIGH -> colors.dangerSoft
                    else -> null
                },
                content = when {
                    !row.isEditable -> colors.textTertiary
                    row.isStaged -> colors.accent
                    row.risk == Risk.HIGH -> colors.danger
                    else -> null
                },
            )
        },
    ) {
        if (!row.isEditable) {
            Text(
                row.availability.reasonText,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
            )
            return@SectionCard
        }

        Text(
            "Current: ${row.currentValue ?: "N/A"}${row.units?.let { " $it" } ?: ""}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary,
        )
        Spacer(Modifier.height(Spacing.md))

        when (row.valueKind) {
            dev.kernelis.core.capability.ValueKind.ENUM -> {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    row.samples.forEach { sample ->
                        val selected = (row.stagedValue ?: row.currentValue) == sample
                        OutlinedButton(
                            onClick = { onStage(sample) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = true,
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) colors.accentSoft
                                else MaterialTheme.colorScheme.surface,
                                contentColor = if (selected) colors.accent
                                else MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Text(
                                sample,
                                style = dev.kernelis.ui.theme.NumericTextStyle,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            dev.kernelis.core.capability.ValueKind.NUMBER -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { textValue = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = dev.kernelis.ui.theme.NumericTextStyle,
                        supportingText = row.numericRange?.let {
                            { Text("Range: ${it.min} – ${it.max}") }
                        },
                    )
                    Button(
                        onClick = { onStage(textValue) },
                        enabled = textValue.isNotBlank() && textValue != row.currentValue,
                    ) { Text("Stage") }
                }
                if (row.samples.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.sm))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        row.samples.forEach { notch ->
                            FilterChip(
                                selected = textValue == notch,
                                onClick = {
                                    textValue = notch
                                    onStage(notch)
                                },
                                label = { Text(notch, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }
            dev.kernelis.core.capability.ValueKind.BOOLEAN -> {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = { onStage("1") }, modifier = Modifier.weight(1f)) { Text("Enable") }
                    OutlinedButton(onClick = { onStage("0") }, modifier = Modifier.weight(1f)) { Text("Disable") }
                }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { textValue = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = dev.kernelis.ui.theme.NumericTextStyle,
                    )
                    Button(onClick = { onStage(textValue) }, enabled = textValue.isNotBlank()) { Text("Stage") }
                }
            }
        }

        if (row.isStaged) {
            Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Staged: ${row.stagedValue}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.accent,
                    fontWeight = FontWeight.Medium,
                )
                TextButton(onClick = onUnstage) { Text("Unstage") }
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply ${state.planPreview.size} change(s)?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (state.planRequiresSnapshot) {
                    Text(
                        "A snapshot will be saved first so this operation can be rolled back.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.info,
                    )
                }
                state.planPreview.forEach { write ->
                    Column {
                        Text(
                            "${write.capability.label}${write.scope?.let { " · $it" } ?: ""}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "${write.current ?: "N/A"} → ${write.requested}",
                            style = dev.kernelis.ui.theme.NumericTextStyle,
                            color = colors.accent,
                        )
                    }
                }
                if (state.planRequiresAck) {
                    Text(
                        "This includes HIGH-risk changes (e.g. core online state, thermal limits) that can make the device unstable. If the device becomes unresponsive, a reboot reverts everything: these are runtime settings, not boot configuration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.danger,
                    )
                    KaiCheckbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                        label = "I understand the risk",
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !state.applying && (!state.planRequiresAck || acknowledged),
            ) {
                Text(if (state.applying) "Applying…" else "Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun KaiCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.padding(top = Spacing.xs),
    ) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
