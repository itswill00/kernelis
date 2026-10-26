package dev.kernelis.ui.tuning

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
/**
 * Every tuning domain, laid out in a wrapping grid that is always fully visible.
 *
 * Replaces an 11-item horizontal scroller. Two deliberate choices:
 *
 *  - A domain with zero editable controls is dimmed but still tappable: the user
 *    needs to reach it to read *why* it is empty, which is the whole point of the
 *    unsupported-control list inside.
 *  - The count shown is editable controls, not total bindings, because "3" that
 *    turns out to be three greyed-out rows is a broken promise.
 */
@Composable
internal fun DomainGrid(
    selected: CapabilityDomain,
    totals: Map<CapabilityDomain, Int>,
    editable: Map<CapabilityDomain, Int>,
    onSelect: (CapabilityDomain) -> Unit,
) {
    val colors = LocalKernelisColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        CapabilityDomain.entries.chunked(DOMAINS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                row.forEach { domain ->
                    val isSelected = domain == selected
                    val count = editable[domain] ?: 0
                    val total = totals[domain] ?: 0
                    DomainCell(
                        label = domain.label,
                        badge = when {
                            count > 0 -> count.toString()
                            total > 0 -> "—"
                            else -> null
                        },
                        selected = isSelected,
                        hasControls = count > 0,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(domain) },
                    )
                }
                // Keep the last row's cells the same width as a full row's.
                repeat(DOMAINS_PER_ROW - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DomainCell(
    label: String,
    badge: String?,
    selected: Boolean,
    hasControls: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalKernelisColors.current
    val container = when {
        selected -> colors.accentSoft
        else -> colors.surfaceHigh
    }
    val content = when {
        selected -> colors.accent
        hasControls -> MaterialTheme.colorScheme.onSurface
        else -> colors.textTertiary
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Spacing.sm))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            // Two lines rather than auto-shrink: TextAutoSize is not available in the
            // Compose version this project pins, and truncating "Monitoring" to
            // "Monitorin" was a real defect found by the 360dp screenshot. Wrapping to
            // two lines keeps the full label at every width.
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 14.sp,
        )
        if (badge != null) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) colors.accent else colors.textTertiary,
            )
        }
    }
}

/**
 * Three columns, not four.
 *
 * Four was chosen on the assumption that the longest label ("Monitoring") fit. Rendering
 * at 360 dp showed it truncated to "Monitorin", so the grid is three wide and the label
 * auto-sizes down rather than clipping.
 */
private const val DOMAINS_PER_ROW = 3

/**
 * Test-only entry point: composes the domain grid on its own so a JVM smoke test can
 * assert every domain is present without standing up the whole screen and its
 * ViewModel. Internal rather than public so it never becomes app surface.
 */
@Composable
internal fun TuningDomainGridForTest() {
    val state = remember { androidx.compose.runtime.mutableStateOf(dev.kernelis.core.capability.CapabilityDomain.CPU) }
    DomainGrid(
        selected = state.value,
        totals = dev.kernelis.core.capability.CapabilityDomain.entries.associateWith { 3 },
        editable = dev.kernelis.core.capability.CapabilityDomain.entries.associateWith { 2 },
        onSelect = { state.value = it },
    )
}

@Composable
public fun TuningScreen(
    viewModel: TuningViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalKernelisColors.current

    Column(Modifier.fillMaxSize()) {
        // Domain selector: a wrapping grid of every domain, always fully visible.
        //
        // This was a horizontal scroller with 11 pills. That hid domains 8-11 outside
        // the viewport with no affordance, so users never discovered I/O, Scheduler,
        // Screen, Sound or Charging — and a control surface you cannot find is a
        // control surface that does not exist. A wrapping grid costs two rows of
        // vertical space and removes the horizontal hunt entirely.
        DomainGrid(
            selected = state.domain,
            totals = state.domainTotals,
            editable = state.domainEditable,
            onSelect = viewModel::selectDomain,
        )
        ThinDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            tuningItems(state) { row -> ControlRow(row, viewModel) }
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

/**
 * The scrolling body of the tuning screen, with no ViewModel dependency.
 *
 * Extracted so screenshots and UI tests can render a realistic state without standing
 * up Hilt. The list structure lives here rather than inside [TuningScreen] for the same
 * reason: the layout is what needs looking at, and it should be renderable in isolation.
 */
/**
 * The scrolling body of the tuning screen, with no ViewModel dependency.
 *
 * Extracted so screenshots and UI tests can render a realistic state without standing
 * up Hilt. It is NOT a @Composable: LazyColumn's content lambda is a plain
 * LazyListScope receiver, so composition has to happen inside the `item {}` blocks.
 * Colours are therefore read per item rather than once at the top.
 *
 * [rowContent] renders one editable control. The real screen passes a staged-writing
 * row; the screenshot harness passes a display-only one, which is the whole reason this
 * is parameterised instead of duplicated.
 */
internal fun androidx.compose.foundation.lazy.LazyListScope.tuningItems(
    state: TuningViewModel.UiState,
    rowContent: @Composable (TuningViewModel.ControlRow) -> Unit,
) {
    if (state.resultMessage != null) {
        item("result") {
            val colors = LocalKernelisColors.current
            SectionCard(
                title = if (state.resultIsError) "Result" else "Applied",
            ) {
                Text(
                    state.resultMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.resultIsError) colors.danger else colors.success,
                )
            }
        }
    }

    if (state.rows.isEmpty()) {
        item("empty") {
            val colors = LocalKernelisColors.current
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
        return
    }

    val (available, unavailable) = state.rows.partition { it.isEditable }

    // Group by what the control acts on, not by "all controls in one card". The old
    // single card titled "6 control(s)" told the user nothing and gave a CPU cluster's
    // min and max frequency the same visual weight as an unrelated toggle. Grouping by
    // scope is also how FKM and Scene present it: the cluster IS the mental model.
    val groups = available.groupBy { it.groupKey }.toList()
    groups.forEachIndexed { groupIndex, (groupName, rows) ->
        item("group-$groupIndex") {
            val colors = LocalKernelisColors.current
            Column(Modifier.fillMaxWidth()) {
                GroupHeader(
                    title = groupName,
                    subtitle = rows.firstOrNull()?.groupSubtitle,
                )
                SectionCard {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) ThinDivider()
                        rowContent(row)
                    }
                }
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

@Composable
internal fun StagedBar(
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
internal fun ControlRow(
    row: TuningViewModel.ControlRow,
    viewModel: TuningViewModel,
) {
    val colors = LocalKernelisColors.current
    val displayValue = row.stagedValue ?: row.currentValue

    ControlRowScaffold(
        row = row,
        displayValue = displayValue,
        supporting = {
            when {
                // A frequency is a notch table, not a continuum: use the notch slider.
                row.units.equals("kHz", ignoreCase = true) &&
                    row.valueKind == ValueKind.NUMBER &&
                    row.samples.size >= 2 ->
                    FrequencyControl(
                        notches = row.samples,
                        selected = displayValue,
                        units = row.units,
                        onSelect = { viewModel.stageValue(row, it) },
                    )

                row.valueKind == ValueKind.ENUM ->
                    SegmentedChoice(
                        options = row.samples,
                        selected = displayValue,
                        onSelect = { viewModel.stageValue(row, it) },
                    )

                row.valueKind == ValueKind.BOOLEAN ->
                    ToggleControl(
                        value = displayValue,
                        onSelect = { viewModel.stageValue(row, it) },
                    )

                else -> NumericStepper(
                    row = row,
                    displayValue = displayValue,
                    onStage = { viewModel.stageValue(row, it) },
                )
            }
            if (row.isStaged) {
                Spacer(Modifier.height(Spacing.xs))
                RowNote("staged, not applied", colors.accent) {
                    UndoButton { viewModel.unstage(row) }
                }
            }
            if (row.risk == Risk.HIGH) {
                Spacer(Modifier.height(Spacing.xs))
                RowNote("high risk", colors.danger)
            }
        },
    )
}

/**
 * The shared shell of a control row: label, current value, and the control below.
 *
 * Extracted so the screenshot harness renders the identical layout with a no-op
 * handler. The previous harness duplicated this markup, which meant screenshots could
 * look fine while the real row was broken.
 *
 * The sysfs path is deliberately NOT shown here. It was in the first version and made
 * every row look like a debug listing; it now lives in Diagnostics, where someone
 * debugging a kernel actually wants it.
 */
@Composable
internal fun ControlRowScaffold(
    row: TuningViewModel.ControlRow,
    displayValue: String?,
    supporting: @Composable () -> Unit,
) {
    val colors = LocalKernelisColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.md))
            ControlValue(displayValue, row.units, row.valueKind)
        }
        Spacer(Modifier.height(Spacing.sm))
        supporting()
    }
}

/**
 * Free-form numeric entry for values that are neither frequencies nor enums.
 *
 * Kept as a slider when a range exists, but the endpoints are labelled and the value is
 * committed on release rather than on every pixel of the drag — the old version staged
 * a value on every onValueChange frame, which queued a plan rebuild per pixel.
 */
@Composable
private fun NumericStepper(
    row: TuningViewModel.ControlRow,
    displayValue: String?,
    onStage: (String) -> Unit,
) {
    val colors = LocalKernelisColors.current
    val range = row.numericRange
    val current = displayValue?.trim()?.toLongOrNull()
    var pending by remember(row.path, row.scope) { mutableStateOf(current) }

    if (range != null && current != null) {
        Column(Modifier.fillMaxWidth()) {
            Slider(
                value = (pending ?: current).toFloat().coerceIn(range.min.toFloat(), range.max.toFloat()),
                onValueChange = { pending = range.snap(it.toLong()) },
                onValueChangeFinished = {
                    val v = pending
                    if (v != null && v != current) onStage(v.toString())
                },
                valueRange = range.min.toFloat()..range.max.toFloat(),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = colors.accent,
                    activeTrackColor = colors.accent,
                    inactiveTrackColor = colors.surfaceHigh,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    range.min.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
                Text(
                    range.max.toString() + (row.units?.let { " $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }
        }
    } else {
        // No range and no notch table: a value the kernel will validate. Offer the raw
        // entry rather than inventing a range.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            var text by remember(row.path, row.scope) { mutableStateOf(displayValue ?: "") }
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = NumericTextStyle,
            )
            TextButton(
                onClick = { onStage(text) },
                enabled = text.isNotBlank() && text != displayValue,
            ) { Text("Stage") }
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
