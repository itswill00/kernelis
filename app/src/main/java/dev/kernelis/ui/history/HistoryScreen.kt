package dev.kernelis.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kernelis.data.TuningRepository
import dev.kernelis.data.db.ChangeLogEntity
import dev.kernelis.data.db.SnapshotEntity
import dev.kernelis.ui.components.Pill
import dev.kernelis.ui.components.SectionCard
import dev.kernelis.ui.components.SettingRow
import dev.kernelis.ui.components.ThinDivider
import dev.kernelis.ui.theme.LocalKernelisColors
import dev.kernelis.ui.theme.NumericTextStyle
import dev.kernelis.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * History and snapshots — the audit trail.
 *
 * Deliberately dense and literal: what was requested, what the kernel stored, and
 * whether rollback is still possible. Rows use [SettingRow] so history matches the
 * visual language of Tuning; nothing is summarised away.
 */
@Composable
public fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snapshots by viewModel.snapshots.collectAsStateWithLifecycle()
    val colors = LocalKernelisColors.current
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        state.message?.let { message ->
            item("message") {
                SectionCard(title = if (state.messageIsError) "Notice" else "Done") {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.messageIsError) colors.danger else colors.success,
                    )
                }
            }
        }

        // ------------------------------------------------------------- snapshots
        item("snapshots") {
            SectionCard(
                title = "Snapshots",
                subtitle = "${snapshots.size} saved · full capture of writable state",
            ) {
                if (snapshots.isEmpty()) {
                    Text(
                        "Created automatically before medium/high-risk changes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                    )
                } else {
                    snapshots.take(5).forEachIndexed { index, snapshot ->
                        if (index > 0) ThinDivider()
                        SettingRow(
                            title = snapshot.label,
                            value = "${snapshot.entryCount}",
                            subtitle = buildString {
                                append(Formatter.format(snapshot.createdAtMs))
                                if (snapshot.automatic) append(" · auto")
                            },
                            onClick = null,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            TextButton(onClick = { viewModel.restoreSnapshot(snapshot.id) }) { Text("Restore") }
                            TextButton(onClick = { viewModel.deleteSnapshot(snapshot.id) }) {
                                Text("Delete", color = colors.danger)
                            }
                        }
                    }
                    if (snapshots.size > 5) {
                        Text(
                            "+${snapshots.size - 5} older snapshot(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textTertiary,
                        )
                    }
                }
            }
        }

        // --------------------------------------------------------------- history
        item("history-header") {
            SectionCard(
                title = "Change history",
                subtitle = "${state.totalChanges} events · grouped by operation",
                trailing = {
                    if (state.groups.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text("Clear") }
                    }
                },
            ) {
                if (state.groups.isEmpty()) {
                    Text(
                        "No changes recorded. Every write attempt — applied, rejected or failed — appears here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                    )
                } else {
                    Text(
                        "Each operation can be rolled back independently. Rollback writes the " +
                            "captured previous values and is itself logged as a new operation.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }
            }
        }

        items(state.groups, key = { it.operationId }) { group ->
            OperationCard(
                group = group,
                busy = state.busyOperationId == group.operationId,
                onRollback = { viewModel.rollback(group.operationId) },
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear change history?") },
            text = {
                Text(
                    "The audit log is deleted permanently. Snapshots are kept. Any runtime change " +
                        "still active can no longer be rolled back from this app — a reboot restores it.",
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
    busy: Boolean,
    onRollback: () -> Unit,
) {
    val colors = LocalKernelisColors.current
    var expanded by remember { mutableStateOf(false) }
    val statusColor = when {
        group.anyFailed -> colors.danger
        group.allApplied -> colors.success
        else -> colors.warning
    }
    val statusText = when {
        group.anyFailed -> "partial"
        group.allApplied -> "applied"
        else -> "mixed"
    }

    SectionCard(
        title = group.label,
        subtitle = Formatter.format(group.timestampMs),
        trailing = {
            Pill(text = statusText, container = statusColor.copy(alpha = 0.14f), content = statusColor)
        },
    ) {
        val visible = if (expanded) group.entries else group.entries.take(4)
        visible.forEachIndexed { index, entry ->
            if (index > 0) ThinDivider()
            ChangeRow(entry)
        }
        if (group.entries.size > 4) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show less" else "+${group.entries.size - 4} more")
            }
        }
        if (group.reversibleCount > 0) {
            Spacer(Modifier.height(Spacing.xs))
            OutlinedButton(
                onClick = onRollback,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "Rolling back…" else "Roll back ${group.reversibleCount} change(s)")
            }
        }
    }
}

@Composable
private fun ChangeRow(entry: ChangeLogEntity) {
    val colors = LocalKernelisColors.current
    val statusColor = when (entry.status) {
        TuningRepository.STATUS_APPLIED -> colors.success
        TuningRepository.STATUS_REVERTED -> colors.warning
        TuningRepository.STATUS_SNAPSHOT_RESTORED -> colors.info
        TuningRepository.STATUS_FAILED, TuningRepository.STATUS_REJECTED -> colors.danger
        else -> colors.textTertiary
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
                modifier = Modifier.weight(1f),
            )
            Text(entry.status, style = MaterialTheme.typography.labelSmall, color = statusColor)
        }
        Text(
            "${entry.previousValue ?: "N/A"} → ${entry.requestedValue}",
            style = NumericTextStyle,
        )
        // Only surface the kernel's own words when it disagreed with the request.
        if (entry.observedValue != null && entry.observedValue != entry.requestedValue) {
            Text(
                "kernel reported ${entry.observedValue}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.warning,
            )
        }
        if (entry.message.isNotBlank() && entry.status != TuningRepository.STATUS_APPLIED) {
            Text(entry.message, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
        }
    }
}

private object Formatter {
    private val format = SimpleDateFormat("MMM d · HH:mm:ss", Locale.getDefault())
    fun format(ms: Long): String = format.format(Date(ms))
}
