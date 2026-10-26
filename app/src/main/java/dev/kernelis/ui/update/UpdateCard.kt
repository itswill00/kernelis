package dev.kernelis.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kernelis.ui.components.Pill
import dev.kernelis.ui.components.SectionCard
import dev.kernelis.ui.theme.LocalKernelisColors
import dev.kernelis.ui.theme.Spacing

/**
 * Update card shown inside Diagnostics.
 *
 * The card never auto-downloads. It states the current version, what the newer release
 * contains (verbatim release notes from GitHub), and requires an explicit tap to
 * download and a second tap to hand the APK to the system installer.
 */
@Composable
public fun UpdateCard(viewModel: UpdateViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalKernelisColors.current

    SectionCard(
        title = "Updates",
        subtitle = "installed ${state.currentVersion}",
        trailing = {
            when {
                state.checking -> Pill(text = "checking…")
                state.updateAvailable -> Pill(
                    text = state.latest?.tag_name ?: "available",
                    container = colors.accentSoft,
                    content = colors.accent,
                )
                else -> Pill(text = "no update")
            }
        },
    ) {
        if (state.message != null && !state.updateAvailable) {
            Text(
                state.message!!,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.messageIsError) colors.danger else colors.textTertiary,
            )
            Spacer(Modifier.height(Spacing.md))
        }

        val release = state.latest
        if (state.updateAvailable && release != null) {
            Text(
                release.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (release.prerelease) "pre-release" else "stable",
                style = MaterialTheme.typography.labelSmall,
                color = if (release.prerelease) colors.warning else colors.success,
            )
            release.body?.takeIf { it.isNotBlank() }?.let { body ->
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    body.take(1200),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
            }
            Spacer(Modifier.height(Spacing.md))

            if (state.downloading) {
                LinearProgressIndicator(
                    progress = { state.downloadProgress ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    state.downloadProgress?.let { "${(it * 100).toInt()}%" } ?: "starting…",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            } else if (state.downloadedFile != null) {
                Button(onClick = { viewModel.install() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Install update")
                }
                if (!state.canInstall) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "KERNELIS needs permission to install unknown apps.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.warning,
                    )
                    TextButton(onClick = viewModel::openInstallPermissionSettings) {
                        Text("Open permission settings")
                    }
                }
            } else {
                Button(onClick = viewModel::download, modifier = Modifier.fillMaxWidth()) {
                    Text("Download update")
                }
            }
        }

        if (state.message != null && state.updateAvailable) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                state.message!!,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.messageIsError) colors.danger else colors.success,
            )
        }

        Spacer(Modifier.height(Spacing.md))
        OutlinedButton(
            onClick = { viewModel.check() },
            enabled = !state.checking && !state.downloading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Check for updates") }
    }
}

/** First-launch prompt, shown only when a newer release actually exists. */
@Composable
public fun UpdateAvailableDialog(
    viewModel: UpdateViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val release = state.latest ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("KERNELIS ${release.tag_name} available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "Installed: ${state.currentVersion}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (release.prerelease) "This is a pre-release build." else "Stable release.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalKernelisColors.current.textTertiary,
                )
                release.body?.takeIf { it.isNotBlank() }?.let {
                    Text(it.take(800), style = MaterialTheme.typography.bodySmall)
                }
                state.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.messageIsError) LocalKernelisColors.current.danger
                        else LocalKernelisColors.current.success,
                    )
                }
            }
        },
        confirmButton = {
            if (state.downloadedFile != null) {
                Button(onClick = { viewModel.install(); onDismiss() }) { Text("Install") }
            } else {
                Button(onClick = { viewModel.download() }, enabled = !state.downloading) {
                    Text(if (state.downloading) "Downloading…" else "Download")
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(release.html_url),
                            ),
                        )
                    }
                }) { Text("Release page") }
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        },
    )
}
