package dev.kernelis.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real screens to PNG on the JVM so they can be LOOKED at.
 *
 * This exists because every previous UI decision in this project was made by reading
 * code and reasoning about it, and the result was repeatedly judged to look generic.
 * The launcher icon only became good once it was rendered and inspected instead of
 * measured. This is the same correction applied to the screens.
 *
 * Not an assertion suite: it writes build/screenshots as PNG and asserts only that
 * each capture produced a non-blank bitmap, so a crash still fails the build.
 *
 * Run: ./gradlew :app:testGithubDebugUnitTest --tests "dev.kernelis.ui.ScreenshotTest"
 *      --no-daemon
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun capture(name: String, widthDp: Int = 411, heightDp: Int = 891, content: @Composable () -> Unit) {
        composeRule.setContent {
            dev.kernelis.ui.theme.KernelisTheme {
                Box(
                    Modifier
                        .size(widthDp.dp, heightDp.dp)
                        .background(MaterialTheme.colorScheme.background),
                ) { content() }
            }
        }
        composeRule.waitForIdle()
        val bmp: Bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val f = File(outDir, "$name.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // A blank capture means the harness silently broke; fail rather than write junk.
        val distinct = (0 until bmp.width step 7).flatMap { x ->
            (0 until bmp.height step 7).map { y -> bmp.getPixel(x, y) }
        }.toSet().size
        check(distinct > 3) { "capture $name looks blank ($distinct distinct colours)" }
    }

    @Test
    fun tuning_screen() = capture("tuning") {
        dev.kernelis.ui.tuning.TuningPreviewForScreenshot()
    }

    @Test
    fun dashboard_screen() = capture("dashboard") {
        dev.kernelis.ui.dashboard.DashboardPreviewForScreenshot()
    }

    @Test
    fun drawer_screen() = capture("drawer") {
        NavigationDrawerForTest()
    }

    @Test
    fun profiles_screen() = capture("profiles") {
        dev.kernelis.ui.profiles.ProfilesPreviewForScreenshot()
    }

    @Test
    fun small_width_tuning() = capture("tuning_small", widthDp = 360, heightDp = 740) {
        dev.kernelis.ui.tuning.TuningPreviewForScreenshot()
    }
}
