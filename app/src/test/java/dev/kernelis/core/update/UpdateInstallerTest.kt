package dev.kernelis.core.update

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the field crash on the play flavour.
 *
 * On real devices PackageManager.canRequestPackageInstalls() throws SecurityException
 * when the app does not declare REQUEST_INSTALL_PACKAGES (the play flavour, by
 * design). Robolectric's shadow does NOT reproduce that throw — verified empirically
 * with a probe test before writing this — so the PackageManager is mocked to mirror
 * the documented platform behaviour.
 *
 * canInstallPackages() must answer "may I install?" with a boolean, never a crash:
 * no declaration simply means no.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateInstallerTest {

    @Test
    fun canInstallPackages_returnsFalse_whenPlatformThrowsSecurityException() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val pm = Mockito.mock(PackageManager::class.java)
        Mockito.`when`(pm.canRequestPackageInstalls())
            .thenThrow(
                SecurityException(
                    "Need to declare android.permission.REQUEST_INSTALL_PACKAGES to call this api",
                ),
            )
        val context = Mockito.mock(android.content.Context::class.java)
        Mockito.`when`(context.packageManager).thenReturn(pm)

        val installer = UpdateInstaller(context)

        // The assertion that matters: this call completes instead of throwing, which
        // is exactly what crashed Diagnostics on device via UpdateViewModel's init.
        assertThat(installer.canInstallPackages()).isFalse()
    }

    @Test
    fun canInstallPackages_returnsTrue_whenPlatformAllows() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val pm = Mockito.mock(PackageManager::class.java)
        Mockito.`when`(pm.canRequestPackageInstalls()).thenReturn(true)
        val context = Mockito.mock(android.content.Context::class.java)
        Mockito.`when`(context.packageManager).thenReturn(pm)

        val installer = UpdateInstaller(context)

        assertThat(installer.canInstallPackages()).isTrue()
    }
}
