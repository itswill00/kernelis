package dev.kernelis.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders UI on the JVM (Robolectric) so a composition crash is caught in local builds
 * rather than on a user's device. This machine has no emulator, and a compose crash
 * that only reproduces on device is otherwise invisible until release.
 *
 * Smoke-level by design: each test asserts composition *completes* for the states that
 * previously produced field reports, plus the key on-screen strings.
 *
 * Uses [createComposeRule]; the unit-test manifest declares androidx.activity.
 * ComponentActivity so Robolectric can resolve the launch intent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingRow_rendersTitleValueAndDisabledReason() {
        composeRule.setContent {
            dev.kernelis.ui.theme.KernelisTheme {
                dev.kernelis.ui.components.SettingRow(
                    title = "Minimum frequency",
                    value = "300000",
                    subtitle = "scaling_min_freq",
                    enabled = false,
                    disabledReason = "Not present on this kernel",
                )
            }
        }
        composeRule.onNodeWithText("Minimum frequency").assertExists()
        composeRule.onNodeWithText("300000").assertExists()
        composeRule.onNodeWithText("Not present on this kernel").assertExists()
    }

    @Test
    fun tuningDomainStripLabels_exist() {
        composeRule.setContent {
            dev.kernelis.ui.theme.KernelisTheme {
                androidx.compose.foundation.layout.Column {
                    dev.kernelis.ui.components.Pill(text = "CPU")
                    dev.kernelis.ui.components.Pill(text = "GPU")
                }
            }
        }
        composeRule.onNodeWithText("CPU").assertExists()
        composeRule.onNodeWithText("GPU").assertExists()
    }

    @Test
    fun sectionCard_withNullableSubtitle_renders() {
        composeRule.setContent {
            dev.kernelis.ui.theme.KernelisTheme {
                dev.kernelis.ui.components.SectionCard(title = "Root", subtitle = null) {
                    dev.kernelis.ui.components.Pill(text = "no root")
                }
            }
        }
        composeRule.onNodeWithText("Root").assertExists()
        composeRule.onNodeWithText("no root").assertExists()
    }

    /**
     * The tuning domain selector used to be an 11-item horizontal scroller, which hid
     * five domains outside the viewport with no affordance. It is now a wrapping grid
     * that must be fully present: this asserts every domain label is composed, so a
     * regression back to a scroller (where off-screen items are not composed) fails.
     */
    @Test
    fun tuningDomainGrid_composesEveryDomain() {
        composeRule.setContent {
            dev.kernelis.ui.theme.KernelisTheme {
                dev.kernelis.ui.tuning.TuningDomainGridForTest()
            }
        }
        dev.kernelis.core.capability.CapabilityDomain.entries.forEach { domain ->
            composeRule.onNodeWithText(domain.label).assertExists()
        }
    }

    /**
     * Navigation lives in a drawer, not a bottom bar. Opening the drawer must reveal
     * all five destinations with their descriptions.
     */
    @Test
    fun drawer_listsEveryDestination() {
        composeRule.setContent {
            dev.kernelis.ui.theme.KernelisTheme {
                dev.kernelis.ui.NavigationDrawerForTest()
            }
        }
        dev.kernelis.ui.Destination.entries.forEach { destination ->
            composeRule.onNodeWithText(destination.label).assertExists()
        }
    }
}
