package dev.kernelis.core.root

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [RootShell] backed by a real `su` process, which is how Magisk, KernelSU and APatch
 * all expose root to apps. There is no vendor SDK dependency:
 *  - Magisk:   `su -c "<command>"`, provider identified through `magisk -V`.
 *  - KernelSU: `su -c "<command>"`, provider identified through `/data/adb/ksu`.
 *  - APatch:   `su -c "<command>"`, provider identified through `/data/adb/ap`.
 *
 * Each invocation is isolated: a fresh process, an explicit timeout, and a recorded
 * outcome. A timeout kills the process and reports [ShellFailure.Timeout] so callers
 * can avoid double-applying writes.
 */
public class SuProcessRootShell(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val suBinary: String = "su",
) : RootShell {

    @Volatile
    private var cachedProvider: RootProvider? = null

    override suspend fun isAvailable(): Boolean = withContext(dispatcher) {
        val result = runSu("id -u", PROBE_TIMEOUT_MS)
        result.isSuccess && result.stdout.trim().lineSequence().lastOrNull()?.trim() == "0"
    }

    override suspend fun provider(): RootProvider {
        cachedProvider?.let { return it }
        val detected = withContext(dispatcher) { detectProvider() }
        cachedProvider = detected
        return detected
    }

    override suspend fun exec(command: ShellCommand): ShellResult = withContext(dispatcher) {
        runSu(command.render(), command.timeoutMs)
    }

    override suspend fun readText(path: String): String? = withContext(dispatcher) {
        val safe = runCatching { requireShellSafePath(path) }.getOrNull() ?: return@withContext null
        val result = runSu("cat $safe", READ_TIMEOUT_MS)
        if (result.isSuccess) result.stdout.trim().ifEmpty { null } else null
    }

    /**
     * Identifies the root provider in **one** shell round-trip.
     *
     * The previous version issued up to ten separate `su` calls (two per candidate plus
     * directory tests), which alone cost hundreds of milliseconds on every cold start.
     * Everything is now printed with markers and parsed locally.
     */
    private fun detectProvider(): RootProvider {
        val script = buildString {
            append("printf 'magisk_v='; magisk -V 2>/dev/null || echo; ")
            append("printf 'magisk_n='; magisk -v 2>/dev/null || echo; ")
            append("printf 'ksu='; cat /data/adb/ksu/version 2>/dev/null || echo; ")
            append("printf 'ksu_dir='; [ -d /data/adb/ksu ] && echo 1 || echo 0; ")
            append("printf 'ap='; cat /data/adb/ap/version 2>/dev/null || echo; ")
            append("printf 'ap_dir='; [ -d /data/adb/ap ] && echo 1 || echo 0; ")
            append("printf 'uid='; id -u 2>/dev/null || echo")
        }
        val result = runSu(script, DETECT_TIMEOUT_MS)
        if (!result.isSuccess) return RootProvider.NONE

        val fields = result.stdout.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()

        val magiskCode = fields["magisk_v"].orEmpty()
        val magiskVersion = fields["magisk_n"].orEmpty()
        if (magiskCode.any { it.isDigit() }) {
            return RootProvider(
                family = RootProvider.Family.MAGISK,
                version = magiskVersion.ifBlank { magiskCode }.ifBlank { null },
                detail = mapOf("versionCode" to magiskCode),
            )
        }
        if (fields["ksu_dir"] == "1") {
            return RootProvider(
                family = RootProvider.Family.KERNELSU,
                version = fields["ksu"].orEmpty().ifBlank { null },
            )
        }
        if (fields["ap_dir"] == "1") {
            return RootProvider(
                family = RootProvider.Family.APATCH,
                version = fields["ap"].orEmpty().ifBlank { null },
            )
        }
        return if (fields["uid"] == "0") {
            RootProvider(RootProvider.Family.OTHER, null, mapOf("note" to "su works; provider not identified"))
        } else {
            RootProvider.NONE
        }
    }

    private fun runSu(script: String, timeoutMs: Long): ShellResult {
        val started = System.nanoTime()
        var process: Process? = null
        return try {
            val resolved = resolveBinary(suBinary)
                ?: return failureResult(script, started, ShellFailure.NoRoot)

            process = ProcessBuilder(resolved, "-c", script).start()
            // Close stdin immediately: su must not block waiting on an interactive prompt
            // beyond what the root manager itself provides.
            runCatching { process.outputStream.close() }

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = pump(process.inputStream, stdout)
            val errThread = pump(process.errorStream, stderr)

            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                outThread.join(100)
                errThread.join(100)
                return failureResult(script, started, ShellFailure.Timeout(timeoutMs), stdout.toString(), stderr.toString())
            }
            outThread.join(500)
            errThread.join(500)

            val code = process.exitValue()
            val stderrText = stderr.toString()
            val failure = when {
                code == 0 -> null
                stderrText.contains("permission denied", ignoreCase = true) -> ShellFailure.PermissionDenied(stderrText.trim())
                code == 255 && stderrText.isBlank() -> ShellFailure.NoRoot
                else -> null
            }
            ShellResult(
                command = script,
                stdout = stdout.toString(),
                stderr = stderrText,
                exitCode = code,
                durationMs = elapsedMs(started),
                failure = failure,
            )
        } catch (e: IOException) {
            failureResult(script, started, ShellFailure.Transport(e.message ?: e.javaClass.simpleName))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            failureResult(script, started, ShellFailure.Transport("interrupted"))
        } finally {
            process?.let { if (it.isAlive) runCatching { it.destroyForcibly() } }
        }
    }

    private fun pump(source: java.io.InputStream, sink: StringBuilder): Thread =
        Thread {
            runCatching {
                source.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    var read: Int
                    while (reader.read(buffer).also { read = it } != -1) {
                        synchronized(sink) { sink.append(buffer, 0, read) }
                        if (sink.length > MAX_CAPTURED_CHARS) break
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "kernelis-su-pipe"
            start()
        }

    private fun failureResult(
        command: String,
        startedNs: Long,
        failure: ShellFailure,
        stdout: String = "",
        stderr: String = "",
    ): ShellResult = ShellResult(
        command = command,
        stdout = stdout,
        stderr = stderr,
        exitCode = ShellResult.EXIT_NOT_EXECUTED,
        durationMs = elapsedMs(startedNs),
        failure = failure,
    )

    private fun elapsedMs(startedNs: Long): Long = (System.nanoTime() - startedNs) / 1_000_000

    private fun resolveBinary(binary: String): String? {
        if (binary.contains('/')) return binary.takeIf { File(it).canExecute() }
        val candidates = System.getenv("PATH").orEmpty()
            .split(':')
            .filter { it.isNotBlank() }
            .plus(listOf("/system/bin", "/system/xbin", "/sbin", "/debug_ramdisk", "/data/adb/ksu/bin"))
        return candidates.map { File(it, binary) }.firstOrNull { it.canExecute() }?.absolutePath
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 15_000L
        const val READ_TIMEOUT_MS = 5_000L
        const val DETECT_TIMEOUT_MS = 6_000L
        const val MAX_CAPTURED_CHARS = 1 shl 20
    }
}
