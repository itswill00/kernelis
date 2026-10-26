package dev.kernelis

import android.app.Activity
import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the class of bugs that unit tests and compilation cannot catch: a manifest
 * component pointing at a class that does not exist.
 *
 * A `ClassNotFoundException` on launch is invisible to `assembleDebug` and to every
 * JVM test, because the manifest is only resolved at install/launch time on a device.
 * This test reads the merged manifest and asserts every declared component resolves on
 * the *unit test* classpath (which contains the app's own classes).
 */
class ManifestComponentTest {

    private val manifest: String by lazy {
        val candidates = listOf(
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
        )
        val file = candidates.map { java.io.File(it) }.firstOrNull { it.exists() }
            ?: error("AndroidManifest.xml not found from ${java.io.File(".").absolutePath}")
        file.readText()
    }

    @Test
    fun `application class is declared with a resolvable class`() {
        val name = Regex("""android:name="([^"]*KernelisApplication)"""").find(manifest)
            ?.groupValues?.get(1)
        assertThat(name).isNotNull()
        assertThat(manifest).contains("android:name=\".KernelisApplication\"")
        // The application class lives in the namespace package.
        assertThat(Class.forName("dev.kernelis.app.KernelisApplication")).isNotNull()
    }

    @Test
    fun `launcher activity points at an existing Activity class`() {
        val activityName = Regex("""<activity[^>]*android:name="([^"]+)"""")
            .find(manifest)
            ?.groupValues?.get(1)

        assertThat(activityName).isNotNull()

        // Resolve the manifest name the same way the platform does: a leading dot means
        // "relative to the namespace", otherwise it is already fully qualified.
        val resolved = when {
            activityName!!.startsWith(".") -> "dev.kernelis.app$activityName"
            else -> activityName
        }

        val clazz = Class.forName(resolved)
        assertThat(Activity::class.java.isAssignableFrom(clazz)).isTrue()
    }

    @Test
    fun `every fully qualified class referenced in the manifest exists`() {
        val fqcnPattern = Regex("""android:name="((?:[a-z][a-z0-9_]*\.)+[A-Z][A-Za-z0-9_$]*)\"""")
        val referenced = fqcnPattern.findAll(manifest)
            .map { it.groupValues[1] }
            // Framework/internal providers are not on the app classpath; only assert on
            // the app's own classes.
            .filter { it.startsWith("dev.kernelis.") }
            .toList()

        assertThat(referenced).isNotEmpty()
        for (name in referenced) {
            val loaded = runCatching { Class.forName(name) }
            assertThat(loaded.isSuccess).isTrue()
        }
    }

    @Test
    fun `application class in manifest is an Application`() {
        assertThat(Application::class.java.isAssignableFrom(Class.forName("dev.kernelis.app.KernelisApplication")))
            .isTrue()
    }
}
