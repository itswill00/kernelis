package dev.kernelis.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches every unhandled exception, writes the full stack trace to a local file, and
 * offers the user a "share crash log" action on the next launch.
 *
 * Rationale: a root tool crashes in the field where we cannot attach a debugger, and
 * users rarely run `adb logcat`. Without this, a crash report is "the diagnostics menu
 * crashed" — with it, the report is the actual stack trace. The log stays inside app
 * storage; nothing leaves the device unless the user taps Share.
 */
public class CrashHandler(
    private val context: Context,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching { writeCrashFile(thread, throwable) }
        // Give the chain a chance to do its own teardown (process kill etc.).
        previous?.uncaughtException(thread, throwable)
    }

    private fun writeCrashFile(thread: Thread, throwable: Throwable) {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("KERNELIS crash · $timestamp · thread=${thread.name}")
            appendLine(trace)
            appendLine()
            appendLine("Recent logcat (if readable):")
            appendLine(recentLogcat())
        }
        File(crashDirectory(context), "crash-${System.currentTimeMillis()}.txt")
            .writeText(text.take(MAX_FILE_CHARS))
    }

    /** Last ~100 log lines; best-effort and may be empty on some SELinux policies. */
    private fun recentLogcat(): String = runCatching {
        ProcessBuilder("logcat", "-d", "-t", "100")
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().use { it.readText() }
    }.getOrDefault("(logcat not readable from this uid)")

    public companion object {
        private const val MAX_FILE_CHARS = 200_000

        /** Installed from [KernelisApplication.onCreate]; idempotent per process. */
        @Volatile
        public var installed: Boolean = false
            private set

        public fun install(context: Context) {
            if (installed) return
            val current = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext, current))
            installed = true
        }

        public fun crashDirectory(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "crashes").apply { mkdirs() }

        /** Newest crash log, or null when the app has not crashed. */
        public fun latestCrash(context: Context): File? =
            crashDirectory(context).listFiles { f -> f.name.startsWith("crash-") }
                ?.maxByOrNull { it.lastModified() }
    }
}

/** Context helper: opens the system share sheet for a crash log. */
public fun Context.shareCrashLog(file: File) {
    val uri = runCatching {
        FileProvider.getUriForFile(this, "$packageName.updates", file)
    }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "KERNELIS crash log")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(intent, "Share crash log").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
