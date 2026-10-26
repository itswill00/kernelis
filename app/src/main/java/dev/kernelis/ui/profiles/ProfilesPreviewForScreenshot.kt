package dev.kernelis.ui.profiles

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.kernelis.core.capability.CapabilityId
import dev.kernelis.core.tuning.KernelProfile
import dev.kernelis.core.tuning.ProfileEntry
import dev.kernelis.ui.components.SectionCard
import dev.kernelis.ui.theme.Spacing

/**
 * Profiles screen for screenshots.
 *
 * This screen was never rendered before the 0.5.0 redesign, and it shows: it is a
 * scroll column of four stacked cards, each of which is a form. Capturing a profile
 * asks for a name and description in two full-width fields before the user has seen
 * what will be captured. Rendering it is the first step to fixing it, and this preview
 * exists so the same mistake is not repeated for the next screen.
 */
@Composable
internal fun ProfilesPreviewForScreenshot() {
    val state = remember {
        ProfilesViewModel.UiState(
            profiles = listOf(
                KernelProfile(
                    id = "builtin-balanced",
                    name = "Balanced",
                    description = "Everyday defaults: schedutil, conservative clocks",
                    builtIn = true,
                    entries = listOf(
                        ProfileEntry(CapabilityId.CPU_GOVERNOR, "", "schedutil", "Cluster A"),
                        ProfileEntry(CapabilityId.CPU_MAX_FREQ, "", "1804800", "Cluster A"),
                    ),
                ),
                KernelProfile(
                    id = "builtin-performance",
                    name = "Performance",
                    description = "Locks clocks high; expect heat and battery drain",
                    builtIn = true,
                    entries = listOf(
                        ProfileEntry(CapabilityId.CPU_GOVERNOR, "", "performance", "Cluster A"),
                    ),
                ),
                KernelProfile(
                    id = "capture-1",
                    name = "My evening setup",
                    description = "Captured 2 days ago",
                    builtIn = false,
                    entries = emptyList(),
                ),
            ),
        )
    }
    val colors = dev.kernelis.ui.theme.LocalKernelisColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        SectionCard(title = "Presets", subtitle = "resolved against this device at apply time") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                state.profiles.filter { it.builtIn }.forEachIndexed { i, p ->
                    if (i > 0) dev.kernelis.ui.components.ThinDivider()
                    Text(p.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        p.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                    )
                }
            }
        }
        SectionCard(
            title = "Captured profiles",
            subtitle = "${state.profiles.count { !it.builtIn }} saved on this device",
        ) {
            state.profiles.filterNot { it.builtIn }.forEach { p ->
                Text(p.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    p.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
            }
        }
        androidx.compose.material3.OutlinedButton(
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Capture a new profile") }
    }
}
