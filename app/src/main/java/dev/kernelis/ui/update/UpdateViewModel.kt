package dev.kernelis.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kernelis.app.BuildConfig
import dev.kernelis.core.update.ReleaseFetcher
import dev.kernelis.core.update.UpdateChecker
import dev.kernelis.core.update.UpdateInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * In-app update flow.
 *
 * Three states the user can be in, and the UI must be explicit about which:
 *  - checking / up to date
 *  - update available (with release notes shown verbatim from GitHub)
 *  - download in progress / downloaded, needs the system installer
 *
 * A failed check never claims "up to date": the error is surfaced, because silently
 * swallowing a network failure is how users end up on stale builds.
 */
@HiltViewModel
public class UpdateViewModel @Inject constructor(
    private val installer: UpdateInstaller,
) : ViewModel() {

    public data class UiState(
        val checking: Boolean = false,
        val latest: UpdateChecker.Release? = null,
        val updateAvailable: Boolean = false,
        val currentVersion: String = BuildConfig.VERSION_NAME,
        val downloadProgress: Float? = null,
        val downloading: Boolean = false,
        val downloadedFile: java.io.File? = null,
        val message: String? = null,
        val messageIsError: Boolean = false,
        val canInstall: Boolean = true,
    )

    private val _uiState = MutableStateFlow(UiState())
    public val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val fetcher = ReleaseFetcher()

    init {
        refreshInstallPermission()
    }

    public fun refreshInstallPermission() {
        _uiState.value = _uiState.value.copy(canInstall = installer.canInstallPackages())
    }

    public fun openInstallPermissionSettings() = installer.openInstallPermissionSettings()

    public fun check(force: Boolean = false) {
        if (_uiState.value.checking) return
        _uiState.value = _uiState.value.copy(checking = true, message = null)
        viewModelScope.launch {
            val result = fetcher.fetch()
            result.fold(
                onSuccess = { releases ->
                    val latest = UpdateChecker.pickLatest(releases)
                    val available = latest != null &&
                        UpdateChecker.isNewer(latest.tag_name, BuildConfig.VERSION_NAME)
                    _uiState.value = _uiState.value.copy(
                        checking = false,
                        latest = latest,
                        updateAvailable = available,
                        message = when {
                            latest == null -> "No releases published yet."
                            !available -> "You are on the latest build (${BuildConfig.VERSION_NAME})."
                            else -> null
                        },
                        messageIsError = false,
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        checking = false,
                        message = "Update check failed: ${error.message ?: "network error"}. " +
                            "This does not mean you are up to date.",
                        messageIsError = true,
                    )
                },
            )
        }
    }

    public fun download() {
        val release = _uiState.value.latest ?: return
        val asset = UpdateChecker.apkAsset(release) ?: run {
            _uiState.value = _uiState.value.copy(
                message = "Release ${release.tag_name} has no APK asset.",
                messageIsError = true,
            )
            return
        }
        if (_uiState.value.downloading) return
        _uiState.value = _uiState.value.copy(downloading = true, downloadProgress = null, message = null)

        viewModelScope.launch {
            val state = installer.downloadAndInstall(
                url = asset.browser_download_url,
                fileName = asset.name,
                onProgress = { progress ->
                    _uiState.value = _uiState.value.copy(downloadProgress = progress)
                },
            )
            _uiState.value = _uiState.value.copy(
                downloading = false,
                downloadProgress = null,
                downloadedFile = state.apkFile,
                message = state.error ?: if (state.apkFile != null) "Download complete. Ready to install." else null,
                messageIsError = state.error != null,
            )
        }
    }

    /** Opens the system installer. Returns false when it could not be launched. */
    public fun install(): Boolean {
        val file = _uiState.value.downloadedFile ?: return false
        val error = installer.install(file)
        if (error != null) {
            _uiState.value = _uiState.value.copy(message = error, messageIsError = true)
            refreshInstallPermission()
            return false
        }
        return true
    }

    public fun openReleasePage() {
        val url = _uiState.value.latest?.html_url ?: return
        // Launching a browser is the caller's job (needs an Activity context), so this is
        // exposed as a plain URL the UI resolves.
        _uiState.value = _uiState.value.copy(message = url)
    }

    public fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
