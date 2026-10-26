package dev.kernelis.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import dev.kernelis.ui.components.Pill
import dev.kernelis.ui.components.SectionCard
import dev.kernelis.ui.theme.LocalKernelisColors
import dev.kernelis.ui.theme.NumericTextStyle
import dev.kernelis.ui.theme.Spacing

/**
 * Diagnostics screen: root probe, capability summary, expert read-only console.
 */
@Composable
public fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalKernelisColors.current
    var command by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // Root probe ---------------------------------------------------------------
        SectionCard(
            title = "Root",
            subtitle = state.probe?.let { if (it.available) "granted" else "not available" },
            trailing = {
                Pill(
                    text = when {
                        state.probing -> "probing…"
                        state.probe?.available == true -> "uid ${state.probe?.uid}"
                        else -> "no root"
                    },
                    container = when {
                        state.probe?.available == true -> colors.accentSoft
                        else -> colors.dangerSoft
                    },
                    content = when {
                        state.probe?.available == true -> colors.accent
                        else -> colors.danger
                    },
                )
            },
        ) {
            val probe = state.probe
            if (probe == null) {
                Text("Probing…", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
            } else {
                Text(
                    "Provider: ${probe.provider.family.name.lowercase()}" +
                        (probe.provider.version?.let { " $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                probe.reason?.let {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.danger)
                }
            }
            Spacer(Modifier.height(Spacing.md))
            OutlinedButton(onClick = viewModel::runProbe, enabled = !state.probing) { Text("Re-probe") }
        }

        // Capability summary -------------------------------------------------------
        state.snapshot?.let { snapshot ->
            SectionCard(
                title = "Capabilities",
                subtitle = "${snapshot.supportedCount} of ${snapshot.totalCount} controls available",
            ) {
                Text("Kernel: ${snapshot.deviceFingerprint}", style = NumericTextStyle)
                snapshot.notes.forEach { note ->
                    Spacer(Modifier.height(Spacing.xs))
                    Text("• $note", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                }
                Spacer(Modifier.height(Spacing.md))
                Text(
                    "Unsupported on this kernel",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val unsupported = snapshot.unsupported()
                if (unsupported.isEmpty()) {
                    Text("Nothing — every probed control is available.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        unsupported.forEach { (id, availability) ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text("• ${id.label}", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(0.dp))
                                Text(
                                    " — ${availability.reasonText}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textTertiary,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Expert console -----------------------------------------------------------
        SectionCard(
            title = "Expert console",
            subtitle = "read-only · every command is logged",
        ) {
            Text(
                "Allowed: ${DiagnosticsViewModel.ALLOWED_BINARIES.joinToString(", ")}. " +
                    "Mutating commands are refused by design — writes belong in Tuning, where they are validated, verified and reversible.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
            )
            Spacer(Modifier.height(Spacing.md))

            state.consoleHistory.forEach { entry ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(Spacing.md),
                ) {
                    Column {
                        Text(
                            "$ ${entry.command}",
                            style = NumericTextStyle,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        val output = if (entry.result.isSuccess) {
                            entry.result.stdout.ifBlank { "(no output)" }
                        } else {
                            entry.result.combinedOutput.ifBlank { "exit ${entry.result.exitCode}" }
                        }
                        Text(
                            output.take(2000),
                            style = NumericTextStyle,
                            color = if (entry.result.isSuccess) MaterialTheme.colorScheme.onSurface else colors.danger,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
            }

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("cat /proc/version") },
                    textStyle = NumericTextStyle,
                )
                Button(
                    onClick = {
                        viewModel.runCommand(command)
                        command = ""
                    },
                    enabled = command.isNotBlank() && !state.consoleBusy,
                ) { Text("Run") }
            }
        }

        // Report -------------------------------------------------------------------
        SectionCard(title = "Bug report", subtitle = "copy everything below into an issue") {
            Text(viewModel.reportText(), style = MaterialTheme.typography.bodySmall)
        }
    }
}
