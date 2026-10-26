package dev.kernelis.ui.profiles

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kernelis.core.tuning.KernelProfile
import dev.kernelis.ui.components.Pill
import dev.kernelis.ui.components.ThinDivider
import dev.kernelis.ui.components.SectionCard
import dev.kernelis.ui.theme.LocalKernelisColors
import dev.kernelis.ui.theme.Spacing

/**
 * Profiles screen.
 *
 * Two kinds of profile live here, and the UI never blurs them:
 *  - **Presets** (built-in): expressed as intent ("max notch", "performance governor")
 *    and resolved against this device's real options at apply time.
 *  - **Captures** (user): an exact list of values recorded from this device.
 *
 * Applying always goes through a plan preview that lists what will change and what will
 * be skipped, because a capture taken on a different kernel may reference dead nodes.
 */
@Composable
public fun ProfilesScreen(
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalKernelisColors.current
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

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

        SectionCard(
            title = "Capture",
            subtitle = "Save the current values of every writable control",
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Profile name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.md))
            Button(
                onClick = {
                    viewModel.captureCurrentState(name, description)
                    name = ""
                    description = ""
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Capture current state") }
        }

        val presets = state.profiles.filter { it.builtIn }
        val captures = state.profiles.filterNot { it.builtIn }

        if (presets.isNotEmpty()) {
            SectionCard(title = "Presets", subtitle = "resolved against this device at apply time") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    presets.forEachIndexed { index, profile ->
                        if (index > 0) ThinDivider()
                        ProfileRow(profile, viewModel)
                    }
                }
            }
        }

        SectionCard(
            title = "Captured profiles",
            subtitle = "${captures.size} saved on this device",
        ) {
            if (captures.isEmpty()) {
                Text(
                    "No captured profiles yet. Use Capture above to snapshot your current tuning.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    captures.forEachIndexed { index, profile ->
                        if (index > 0) ThinDivider()
                        ProfileRow(profile, viewModel)
                    }
                }
            }
        }
    }

    state.pendingPlan?.let { plan ->
        ApplyProfileDialog(
            profileName = state.pendingProfileName ?: "profile",
            state = state,
            onConfirm = viewModel::confirmApply,
            onDismiss = viewModel::cancelApply,
        )
    }
}

@Composable
private fun ProfileRow(profile: KernelProfile, viewModel: ProfilesViewModel) {
    val colors = LocalKernelisColors.current
    dev.kernelis.ui.components.SettingRow(
        title = profile.name,
        value = "${profile.entries.size}",
        subtitle = profile.description.ifBlank {
            if (profile.builtIn) "built-in preset" else "captured from this device"
        },
        supporting = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.prepareApply(profile) },
                    modifier = Modifier.weight(1f),
                    enabled = profile.entries.isNotEmpty() || profile.builtIn,
                ) { Text("Apply") }
                if (!profile.builtIn) {
                    OutlinedButton(onClick = { viewModel.delete(profile) }) { Text("Delete") }
                }
            }
        },
    )
}

@Composable
private fun ApplyProfileDialog(
    profileName: String,
    state: ProfilesViewModel.UiState,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalKernelisColors.current
    val plan = state.pendingPlan ?: return
    var acknowledged by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply \"$profileName\"?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "${plan.effective.size} change(s) will be written, ${plan.skipped.size} skipped.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (plan.unmatched.isNotEmpty()) {
                    Text(
                        "${plan.unmatched.size} entry/entries do not exist on this kernel and will be skipped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.warning,
                    )
                }
                if (plan.requiresSnapshot) {
                    Text(
                        "A snapshot will be saved first so this can be rolled back.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.info,
                    )
                }
                plan.effective.take(6).forEach { write ->
                    Text(
                        "${write.capability.label}${write.scope?.let { " · $it" } ?: ""}: " +
                            "${write.current ?: "N/A"} → ${write.requested}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent,
                    )
                }
                if (plan.effective.size > 6) {
                    Text("… and ${plan.effective.size - 6} more", style = MaterialTheme.typography.labelSmall)
                }
                if (plan.requiresAcknowledgement) {
                    Text(
                        "Includes HIGH-risk changes. These are runtime settings: a reboot restores the previous state.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.danger,
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = acknowledged,
                            onCheckedChange = { acknowledged = it },
                        )
                        Text("I understand the risk", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(true) },
                enabled = !state.busy && (!plan.requiresAcknowledgement || acknowledged) && plan.effective.isNotEmpty(),
            ) { Text(if (state.busy) "Applying…" else "Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
