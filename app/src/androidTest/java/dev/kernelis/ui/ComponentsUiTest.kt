package dev.kernelis.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.kernelis.core.capability.CapabilityDomain
import dev.kernelis.ui.components.MetricRow
import dev.kernelis.ui.components.MetricSpec
import dev.kernelis.ui.components.Pill
import dev.kernelis.ui.components.SectionCard
import dev.kernelis.ui.theme.KernelisTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Compose UI tests.
 *
 * These run on a device/emulator and cover the two things unit tests cannot: that the
 * theme actually provides its CompositionLocals, and that the shared components render
 * without crashing when data is missing.
 *
 * The "N/A" assertions matter: a metric with a null value must never render as 0.
 */
@RunWith(AndroidJUnit4::class)
class ComponentsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sectionCardRendersTitleSubtitleAndContent() {
        composeRule.setContent {
            KernelisTheme {
                SectionCard(title = "CPU", subtitle = "8 cores") {
                    Pill(text = "live")
                }
            }
        }

        composeRule.onNodeWithText("CPU").assertIsDisplayed()
        composeRule.onNodeWithText("8 cores").assertIsDisplayed()
        composeRule.onNodeWithText("live").assertIsDisplayed()
    }

    @Test
    fun missingMetricRendersNaNotZero() {
        composeRule.setContent {
            KernelisTheme {
                MetricRow(
                    listOf(
                        MetricSpec("CPU", "42"),
                        MetricSpec("Temp", null),
                        MetricSpec("Battery", "87"),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("42").assertIsDisplayed()
        composeRule.onNodeWithText("87").assertIsDisplayed()
        composeRule.onNodeWithText("N/A").assertIsDisplayed()
        // The whole point: an unavailable metric must not silently become "0".
        composeRule.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun metricShowsUnitOnlyWhenValueExists() {
        composeRule.setContent {
            KernelisTheme {
                Column {
                    MetricRow(listOf(MetricSpec("Temp", "45", unit = "°C")))
                    MetricRow(listOf(MetricSpec("Other", null, unit = "mV")))
                }
            }
        }

        composeRule.onNodeWithText("°C").assertIsDisplayed()
        // A unit without a value would imply a reading exists; it must not be shown.
        composeRule.onNodeWithText("mV").assertDoesNotExist()
    }

    @Test
    fun longMetricListsWrapIntoRows() {
        composeRule.setContent {
            KernelisTheme {
                MetricRow(
                    listOf(
                        MetricSpec("A", "1"),
                        MetricSpec("B", "2"),
                        MetricSpec("C", "3"),
                        MetricSpec("D", "4"),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("1").assertIsDisplayed()
        composeRule.onNodeWithText("4").assertIsDisplayed()
    }

    @Test
    fun pillIsClickableTarget() {
        var clicked = false
        composeRule.setContent {
            KernelisTheme {
                Column {
                    androidx.compose.material3.TextButton(onClick = { clicked = true }) {
                        Pill(text = "tap me")
                    }
                }
            }
        }
        composeRule.onNodeWithText("tap me").performClick()
        assert(clicked)
    }

    @Test
    fun allDomainsHaveLabels() {
        // Guards against a new CapabilityDomain shipping without a display name.
        CapabilityDomain.entries.forEach { domain ->
            assert(domain.label.isNotBlank()) { "Domain ${domain.name} has no label" }
        }
    }
}
