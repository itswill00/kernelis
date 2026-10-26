package dev.kernelis.ui.history

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kernelis.data.TuningRepository
import dev.kernelis.data.db.ChangeLogEntity
import dev.kernelis.ui.components.Pill
import dev.kernelis.ui.components.SectionCard
import dev.kernelis.ui.theme.LocalKernelisColors
import dev.kernelis.ui.theme.NumericTextStyle
import dev.kernelis.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * History & snapshots screen.
 *
 * The audit trail is the product promise "log every change, roll back where feasible",
 * so this screen shows the raw truth: what was requested, what the kernel actually
 * stored, and whether a rollback is possible.
 */
@Composable
public fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snapshots by viewModel.snapshots.collectAsStateWithLifecycle()
    val colors = LocalKernelisColors.current
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        if (state.message != null) {
            SectionCard(title = if (state.messageIsError) "Notice" else "Done") {
                Text(
                    state.message!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.messageIsError) colors.danger else colors.success,
                )
            }
        }

        // --- Snapshots ------------------------------------------------------------
        SectionCard(
            title = "Snapshots",
            subtitle = "${snapshots.size} saved · full capture of writable state",
        ) {
            if (snapshots.isEmpty()) {
                Text(
                    "Snapshots are created automatically before medium/high-risk changes, or manually from Tuning.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    snapshots.take(10).forEach { snapshot ->
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(snapshot.label, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${SimpleFormatter.format(snapshot.createdAtMs)} · ${snapshot.entryCount} entries" +
                                            if (snapshot.automatic) " · auto" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textTertiary,
                                    )
                                }
                                Column {
                                    TextButton(onClick = { viewModel.restoreSnapshot(snapshot.id) }) {
                                        Text("Restore")
                                    }
                                    TextButton(onClick = { viewModel.deleteSnapshot(snapshot.id) }) {
                                        Text("Delete", color = colors.danger)
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }

        // --- Change history -------------------------------------------------------
        SectionCard(
            title = "Change history",
            subtitle = "${state.totalChanges} recorded events · grouped by operation",
            trailing = {
                if (state.groups.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) { Text("Clear") }
                }
            },
        ) {
            if (state.groups.isEmpty()) {
                Text(
                    "No changes recorded yet. Every write attempt — applied, rejected or failed — will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    state.groups.forEach { group ->
                        OperationCard(group = group, viewModel = viewModel)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear change history?") },
            text = {
                Text(
                    "The audit log will be permanently deleted. Snapshots are kept. " +
                        "If any runtime change is still active, clearing history removes the ability to roll it back from this app.",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.clearHistory()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OperationCard(
    group: HistoryViewModel.OperationGroup,
    viewModel: HistoryViewModel,
) {
    val colors = LocalKernelisColors.current
    var expanded by remember { mutableStateOf(false) }

    SectionCard(
        title = group.label,
        subtitle = SimpleFormatter.format(group.timestampMs),
        trailing = {
            Pill(
                text = when {
                    group.anyFailed -> "partial"
                    group.allApplied -> "applied"
                    else -> "mixed"
                },
                container = when {
                    group.anyFailed -> colors.dangerSoft
                    else -> colors.accentSoft
                },
                content = when {
                    group.anyFailed -> colors.danger
                    else -> colors.accent
                },
            )
        },
    ) {
        group.entries.take(if (expanded) Int.MAX_VALUE else 3).forEach { entry ->
            ChangeRow(entry)
            Spacer(Modifier.height(Spacing.sm))
        }
        if (group.entries.size > 3) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show less" else "… ${group.entries.size - 3} more")
            }
        }
        if (group.reversibleCount > 0) {
            OutlinedButton(
                onClick = { viewModel.rollback(group.operationId) },
                enabled = state0(group.operationId, viewModel),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state0(group.operationId, viewModel)) "Rolling back…" else "Roll back ${group.reversibleCount} change(s)")
            }
        }
    }
}

private fun state0(operationId: String, viewModel: HistoryViewModel): Boolean = false

@Composable
private fun ChangeRow(entry: ChangeLogEntity) {
    val colors = LocalKernelisColors.current
    val statusColor = when (entry.status) {
        TuningRepository.STATUS_APPLIED -> colors.success
        TuningRepository.STATUS_REVERTED -> colors.warning
        TuningRepository.STATUS_SNAPSHOT_RESTORED -> colors.info
        TuningRepository.STATUS_FAILED, TuningRepository.STATUS_REJECTED -> colors.danger
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                entry.capability + (entry.scope?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(entry.status, style = MaterialTheme.typography.labelSmall, color = statusColor)
        }
        Text(
            "${entry.previousValue ?: "N/A"} → ${entry.requestedValue}" +
                (entry.observedValue?.takeIf { it != entry.requestedValue }?.let { " (observed: $it)" } ?: ""),
            style = NumericTextStyle,
        )
        if (entry.message.isNotBlank() &&
            entry.status != TuningRepository.STATUS_APPLIED &&
            entry.message != "Verified at ${entry.observedValue}"
        ) {
            Text(
                entry.message,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        }
        Text(
            entry.path,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
        )
    }
}

private object SimpleFormatter {
    private val format = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
    fun format(ms: Long): String = format.format(Date(ms))
}
