package dev.kernelis.ui.tuning

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.kernelis.core.capability.Availability
import dev.kernelis.core.capability.CapabilityDomain
import dev.kernelis.core.capability.CapabilityId
import dev.kernelis.core.capability.NumericRange
import dev.kernelis.core.capability.Risk
import dev.kernelis.core.capability.ValueKind

/**
 * A realistic Tuning screen for screenshots, with representative data.
 *
 * Screenshotting an empty state would hide exactly the problems that make the screen
 * feel generic: density, whether the value column scans, whether a slider and a chip
 * row fight each other. So this fills in a plausible 8-core phone: two CPU clusters,
 * a governor enum with several samples, a boolean, and two unsupported controls.
 */
@Composable
internal fun TuningPreviewForScreenshot() {
    val rows = remember { previewRows() }
    val state = remember {
        TuningViewModel.UiState(
            domain = CapabilityDomain.CPU,
            rows = rows,
            domainTotals = CapabilityDomain.entries.associateWith { 3 },
            domainEditable = CapabilityDomain.entries.associateWith { 2 },
        )
    }
    val colors = dev.kernelis.ui.theme.LocalKernelisColors.current
    Column(Modifier.fillMaxSize()) {
        DomainGrid(
            selected = state.domain,
            totals = state.domainTotals,
            editable = state.domainEditable,
            onSelect = {},
        )
        dev.kernelis.ui.components.ThinDivider()
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                dev.kernelis.ui.theme.Spacing.lg,
            ),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                dev.kernelis.ui.theme.Spacing.md,
            ),
        ) {
            tuningItems(state) { row -> PreviewControlRow(row) }
        }
    }
}

@Composable
private fun PreviewControlRow(row: TuningViewModel.ControlRow) {
    val display = row.stagedValue ?: row.currentValue
    ControlRowScaffold(
        row = row,
        displayValue = display,
        supporting = {
            when {
                row.units.equals("kHz", ignoreCase = true) &&
                    row.valueKind == ValueKind.NUMBER &&
                    row.samples.size >= 2 ->
                    FrequencyControl(row.samples, display, row.units, onSelect = {})
                row.valueKind == ValueKind.ENUM ->
                    SegmentedChoice(row.samples, display, onSelect = {})
                row.valueKind == ValueKind.BOOLEAN ->
                    ToggleControl(display, onSelect = {})
                else -> {}
            }
            if (row.isStaged) {
                androidx.compose.foundation.layout.Spacer(
                    androidx.compose.ui.Modifier.height(dev.kernelis.ui.theme.Spacing.xs),
                )
                RowNote(
                    "staged, not applied",
                    dev.kernelis.ui.theme.LocalKernelisColors.current.accent,
                )
            }
        },
    )
}

/**
 * Representative rows for an 8-core phone: two CPU clusters, a governor enum, a toggle,
 * and two controls the kernel does not expose. The unsupported ones are included on
 * purpose — a screenshot that only shows working controls hides how the screen handles
 * the common case where half the kernel is missing.
 */
private fun previewRows(): List<TuningViewModel.ControlRow> = listOf(
    row(
        CapabilityId.CPU_MIN_FREQ, "Cluster A", "scaling_min_freq", "Minimum frequency",
        "576000", null,
        listOf("300000", "576000", "768000", "1017600", "1804800", "2208000"),
        300_000L, 2_208_000L, Risk.LOW,
    ),
    row(
        CapabilityId.CPU_MAX_FREQ, "Cluster A", "scaling_max_freq", "Maximum frequency",
        "1804800", "2208000",
        listOf("300000", "576000", "768000", "1017600", "1804800", "2208000"),
        300_000L, 2_208_000L, Risk.LOW,
    ),
    enumRow(
        CapabilityId.CPU_GOVERNOR, "Cluster A", "scaling_governor", "CPU governor",
        "schedutil",
        listOf("schedutil", "performance", "powersave", "ondemand"),
        Risk.MEDIUM,
    ),
    row(
        CapabilityId.CPU_MIN_FREQ, "Cluster B", "scaling_min_freq", "Minimum frequency",
        "1017600", null,
        listOf("1017600", "1305600", "1612800", "1900800", "2400000"),
        1_017_600L, 2_400_000L, Risk.LOW,
    ),
    row(
        CapabilityId.CPU_MAX_FREQ, "Cluster B", "scaling_max_freq", "Maximum frequency",
        "2400000", null,
        listOf("1017600", "1305600", "1612800", "1900800", "2400000"),
        1_017_600L, 2_400_000L, Risk.LOW,
    ),
    boolRow(
        CapabilityId.CPU_INPUT_BOOST, null, "input_boost_enabled", "Input boost", "1",
        Risk.MEDIUM,
    ),
    unsupportedRow(
        CapabilityId.CPU_ONLINE, "cpu7", "online", "Core online state", Risk.HIGH,
    ),
    unsupportedRow(
        CapabilityId.HOTPLUG_ENABLED, null, "cpu_max_freq", "Hotplug driver", Risk.HIGH,
    ),
)

private fun row(
    id: CapabilityId,
    scope: String?,
    file: String,
    label: String,
    current: String,
    staged: String?,
    samples: List<String>,
    min: Long,
    max: Long,
    risk: Risk,
) = TuningViewModel.ControlRow(
    capability = id,
    scope = scope,
    path = "/sys/devices/system/cpu/cpufreq/policy0/$file",
    label = label,
    currentValue = current,
    stagedValue = staged,
    samples = samples,
    numericRange = NumericRange(min, max, 1_000),
    units = "kHz",
    valueKind = ValueKind.NUMBER,
    risk = risk,
    availability = Availability.Supported("writable"),
)

private fun enumRow(
    id: CapabilityId,
    scope: String?,
    file: String,
    label: String,
    current: String,
    samples: List<String>,
    risk: Risk,
) = TuningViewModel.ControlRow(
    capability = id,
    scope = scope,
    path = "/sys/devices/system/cpu/cpufreq/policy0/$file",
    label = label,
    currentValue = current,
    stagedValue = null,
    samples = samples,
    numericRange = null,
    units = null,
    valueKind = ValueKind.ENUM,
    risk = risk,
    availability = Availability.Supported("writable"),
)

private fun boolRow(
    id: CapabilityId,
    scope: String?,
    file: String,
    label: String,
    current: String,
    risk: Risk,
) = TuningViewModel.ControlRow(
    capability = id,
    scope = scope,
    path = "/sys/module/cpu_boost/parameters/$file",
    label = label,
    currentValue = current,
    stagedValue = null,
    samples = emptyList(),
    numericRange = null,
    units = null,
    valueKind = ValueKind.BOOLEAN,
    risk = risk,
    availability = Availability.Supported("writable"),
)

private fun unsupportedRow(
    id: CapabilityId,
    scope: String?,
    file: String,
    label: String,
    risk: Risk,
) = TuningViewModel.ControlRow(
    capability = id,
    scope = scope,
    path = "/sys/devices/system/cpu/cpu7/$file",
    label = label,
    currentValue = null,
    stagedValue = null,
    samples = emptyList(),
    numericRange = null,
    units = null,
    valueKind = ValueKind.BOOLEAN,
    risk = risk,
    availability = Availability.NotPresent,
)
