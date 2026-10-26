package dev.kernelis.core.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Downloads an update APK and hands it to the system installer.
 *
 * Design notes:
 *  - The download runs through [DownloadManager] rather than a hand-rolled HTTP loop, so
 *    it survives the app being backgrounded and reports progress/errors consistently.
 *  - The APK is written to app-external files (`Android/data/<pkg>/files/updates`), which
 *    needs no storage permission on API 29+, and is exposed through a [FileProvider] so
 *    the installer can read it.
 *  - We never install silently: `ACTION_VIEW` with the package-archive MIME type opens
 *    the system confirmation dialog. Auto-install would require REQUEST_INSTALL_PACKAGES
 *    plus a user-granted "install unknown apps" toggle, which is surfaced if missing.
 */
@Singleton
public class UpdateInstaller @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    public data class DownloadState(
        val downloading: Boolean = false,
        val progress: Float? = null,
        val error: String? = null,
        val apkFile: File? = null,
    )

    private var downloadId: Long = -1L
    private var pendingFile: File? = null
    private var pendingMime: String = "application/vnd.android.package-archive"

    /** True when the user must still allow installs from this app. */
    public fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Opens the system screen where "install unknown apps" is granted for this app. */
    public fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    /**
     * Starts the download and suspends until it completes or fails.
     *
     * The APK must be served over HTTPS; the system DownloadManager refuses cleartext
     * URLs, which is the behaviour we want.
     */
    public suspend fun downloadAndInstall(
        url: String,
        fileName: String,
        onProgress: (Float?) -> Unit,
    ): DownloadState = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) {
            return@withContext DownloadState(error = "Update URL must be HTTPS.")
        }
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: return@withContext DownloadState(error = "DownloadManager unavailable.")

        val targetDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates")
            .apply { mkdirs() }
        // Remove stale downloads so storage does not grow with every check.
        targetDir.listFiles()?.forEach { runCatching { it.delete() } }
        val target = File(targetDir, fileName)
        pendingFile = target

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("KERNELIS $fileName")
            setDescription("Downloading update…")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            // Must match [target] exactly: `getExternalFilesDir(DIRECTORY_DOWNLOADS)` +
            // "updates/<name>" — and must match the FileProvider entry in
            // res/xml/file_paths.xml (`external-files-path path="Download/updates/"`),
            // otherwise getUriForFile throws and the install step crashes.
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/$fileName")
            setMimeType("application/vnd.android.package-archive")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }

        val id = runCatching { manager.enqueue(request) }.getOrElse { error ->
            return@withContext DownloadState(error = "Could not start download: ${error.message}")
        }
        downloadId = id

        // Poll progress while waiting for the completion broadcast.
        val result = awaitCompletion(manager, id, onProgress)
        result
    }

    private suspend fun awaitCompletion(
        manager: DownloadManager,
        id: Long,
        onProgress: (Float?) -> Unit,
    ): DownloadState = suspendCancellableCoroutine { continuation ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                runCatching { context.unregisterReceiver(this) }
                val query = DownloadManager.Query().setFilterById(id)
                manager.query(query)?.use { cursor ->
                    if (!cursor.moveToFirst()) {
                        if (continuation.isActive) {
                            continuation.resume(DownloadState(error = "Download record disappeared."))
                        }
                        return
                    }
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val file = pendingFile
                            if (continuation.isActive) {
                                continuation.resume(
                                    if (file != null && file.exists() && file.length() > 0) {
                                        DownloadState(apkFile = file)
                                    } else {
                                        DownloadState(error = "Downloaded file is empty or missing.")
                                    },
                                )
                            }
                        }
                        else -> {
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            if (continuation.isActive) {
                                continuation.resume(
                                    DownloadState(error = "Download failed (reason $reason)."),
                                )
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        // Progress polling on a background thread; cancelled with the coroutine.
        val pollThread = Thread {
            val query = DownloadManager.Query().setFilterById(id)
            while (!continuation.isCompleted) {
                runCatching {
                    manager.query(query)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val downloaded = cursor.getLong(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                            )
                            val total = cursor.getLong(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                            )
                            if (total > 0) onProgress(downloaded.toFloat() / total)
                        }
                    }
                }
                Thread.sleep(300)
            }
        }.apply { isDaemon = true; start() }

        continuation.invokeOnCancellation {
            runCatching { context.unregisterReceiver(receiver) }
            pollThread.interrupt()
            runCatching { manager.remove(id) }
        }
    }

    /**
     * Launches the system package installer for a downloaded APK.
     *
     * Returns an error string when the installer could not be started (for example the
     * user has not granted "install unknown apps"), so the UI can say precisely why.
     */
    public fun install(file: File): String? {
        if (!canInstallPackages()) {
            return "Allow \"install unknown apps\" for KERNELIS, then retry."
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        }.getOrElse { error ->
            return "Could not expose the APK to the installer: ${error.message}"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }
            .exceptionOrNull()
            ?.let { "No package installer available: ${it.message}" }
    }
}
