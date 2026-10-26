package dev.kernelis.core.sysfs

import dev.kernelis.core.root.RootShell
import dev.kernelis.core.root.ShellCommand
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [SysFs] backed by the root shell, optimised for the fact that **every `su` invocation
 * costs tens of milliseconds** on Android.
 *
 * Design rule: never issue one shell process per file. The naive implementation (one
 * `su` per `exists`, one per `access`, one per `list`) produced ~100 processes per scan
 * and took seconds. Everything here is batched into a handful of round-trips:
 *
 *  - [readMany]      one script for all `cat`s
 *  - [accessMany]    one script for all `stat`/`test` probes
 *  - [listMany]      one script for all `ls` calls
 *
 * Results are cached for the lifetime of the instance (invalidated on write), because
 * sysfs topology does not change between two scans a second apart.
 */
public class ShellSysFs(
    private val shell: RootShell,
) : SysFs {

    private val mutex = Mutex()
    private val readCache = HashMap<String, String?>()
    private val accessCache = HashMap<String, PathInfo>()
    private val listCache = HashMap<String, List<String>>()
    private var cacheValid = false

    override suspend fun read(path: String): String? = readMany(listOf(path))[path]

    override suspend fun readFresh(path: String): String? = readMany(listOf(path), fresh = true)[path]

    override suspend fun exists(path: String): Boolean = accessMany(listOf(path))[path]?.exists == true

    override suspend fun list(path: String): List<String> = listMany(listOf(path))[path].orEmpty()

    // ------------------------------------------------------------------ reads

    override suspend fun readMany(paths: Collection<String>, fresh: Boolean): Map<String, String> {
        val targets = paths.mapNotNull(::safePath).distinct()
        if (targets.isEmpty()) return emptyMap()

        return mutex.withLock {
            val out = LinkedHashMap<String, String>(targets.size)
            val missing = ArrayList<String>(targets.size)
            for (path in targets) {
                if (!fresh && cacheValid && readCache.containsKey(path)) {
                    readCache[path]?.let { out[path] = it }
                } else {
                    missing += path
                }
            }
            if (missing.isEmpty()) return@withLock out

            // One process. Each file is framed by a marker line so a missing or
            // unreadable file is unambiguous rather than silently empty.
            val script = buildString {
                for (path in missing) {
                    append("echo ").append(READ_MARK).append(path).append('\n')
                    append("cat ").append(path).append(" 2>/dev/null || echo ").append(UNREADABLE).append('\n')
                }
            }
            val result = shell.exec(ShellCommand("sh", listOf("-c", script), timeoutMs = BATCH_TIMEOUT_MS))
            if (!result.isSuccess) return@withLock out

            var currentPath: String? = null
            val buffer = StringBuilder()
            fun flush() {
                val path = currentPath ?: return
                val text = buffer.toString().trim()
                if (text.isNotEmpty() && text != UNREADABLE) {
                    out[path] = text
                    // A fresh read must NOT be cached: the caller asked precisely
                    // because the value is volatile (/proc/stat, cpufreq, thermal).
                    if (!fresh) readCache[path] = text
                } else if (!fresh) {
                    readCache[path] = null
                }
                buffer.clear()
            }
            for (line in result.stdout.lineSequence()) {
                val trimmed = line.trimEnd()
                if (trimmed.startsWith(READ_MARK) && trimmed.length > READ_MARK.length) {
                    flush()
                    currentPath = trimmed.removePrefix(READ_MARK).trim()
                } else {
                    if (buffer.isNotEmpty()) buffer.append('\n')
                    buffer.append(trimmed)
                }
            }
            flush()
            if (!fresh) cacheValid = true
            out
        }
    }

    // ------------------------------------------------------------------ access

    /**
     * Batched stat. One script emits, for every path, a single machine-readable line:
     * `path|exists|readable|writable|symlink`.
     *
     * This replaced one-process-per-path and is the single biggest scan-time win.
     */
    override suspend fun accessMany(paths: Collection<String>): Map<String, PathInfo> {
        val targets = paths.mapNotNull(::safePath).distinct()
        if (targets.isEmpty()) return emptyMap()

        return mutex.withLock {
            val out = LinkedHashMap<String, PathInfo>(targets.size)
            val missing = ArrayList<String>(targets.size)
            for (path in targets) {
                val cached = accessCache[path]
                if (cached != null) out[path] = cached else missing += path
            }
            if (missing.isEmpty()) return@withLock out

            val script = buildString {
                for (path in missing) {
                    // `printf` keeps output parseable and avoids `echo` portability quirks.
                    append("p=").append(path).append('\n')
                    append("if [ -e \"\$p\" ]; then printf '%s|1|' \"\$p\"; ")
                    append("[ -r \"\$p\" ] && printf '1|' || printf '0|'; ")
                    append("[ -w \"\$p\" ] && printf '1|' || printf '0|'; ")
                    append("[ -L \"\$p\" ] && printf '1\\n' || printf '0\\n'; ")
                    append("else printf '%s|0|0|0|0\\n' \"\$p\"; fi\n")
                }
            }
            val result = shell.exec(ShellCommand("sh", listOf("-c", script), timeoutMs = BATCH_TIMEOUT_MS))

            if (!result.isSuccess) {
                // Fall back to "unknown but present" rather than claiming absence: a
                // failed stat must not make the scanner report a control as missing.
                for (path in missing) {
                    val info = PathInfo(path = path, exists = true, canRead = true, canWrite = false)
                    out[path] = info
                    accessCache[path] = info
                }
                return@withLock out
            }

            for (line in result.stdout.lineSequence()) {
                val parts = line.trim().split('|')
                if (parts.size < 5) continue
                val path = parts[0]
                val info = PathInfo(
                    path = path,
                    exists = parts[1] == "1",
                    canRead = parts[2] == "1",
                    canWrite = parts[3] == "1",
                    isSymlink = parts[4] == "1",
                )
                out[path] = info
                accessCache[path] = info
            }
            out
        }
    }

    override suspend fun access(path: String): PathInfo =
        accessMany(listOf(path))[path] ?: PathInfo.MISSING

    // -------------------------------------------------------------------- list

    /** Batched directory listing: one script, markers per directory. */
    override suspend fun listMany(paths: Collection<String>): Map<String, List<String>> {
        val targets = paths.mapNotNull(::safePath).distinct()
        if (targets.isEmpty()) return emptyMap()

        return mutex.withLock {
            val out = LinkedHashMap<String, List<String>>(targets.size)
            val missing = ArrayList<String>(targets.size)
            for (path in targets) {
                val cached = listCache[path]
                if (cached != null) out[path] = cached else missing += path
            }
            if (missing.isEmpty()) return@withLock out

            val script = buildString {
                for (path in missing) {
                    append("echo ").append(LIST_MARK).append(path).append('\n')
                    append("ls -1 ").append(path).append(" 2>/dev/null\n")
                }
            }
            val result = shell.exec(ShellCommand("sh", listOf("-c", script), timeoutMs = BATCH_TIMEOUT_MS))
            if (!result.isSuccess) return@withLock out

            var current: String? = null
            val buffer = mutableListOf<String>()
            fun flush() {
                val path = current ?: return
                val entries = buffer.toList()
                out[path] = entries
                listCache[path] = entries
                buffer.clear()
            }
            for (line in result.stdout.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.startsWith(LIST_MARK)) {
                    flush()
                    current = trimmed.removePrefix(LIST_MARK)
                } else if (trimmed.isNotEmpty()) {
                    buffer += trimmed
                }
            }
            flush()
            out
        }
    }

    /** Invalidate all caches; called after any write so the next scan is truthful. */
    public fun invalidate() {
        synchronized(this) {
            readCache.clear()
            accessCache.clear()
            listCache.clear()
            cacheValid = false
        }
    }

    private fun safePath(path: String): String? {
        val trimmed = path.trim()
        if (!trimmed.startsWith('/')) return null
        if (trimmed.any { it == '\n' || it == '\u0000' || it == '\'' }) return null
        return trimmed
    }

    private companion object {
        const val READ_MARK = "@@K@R@@"
        const val LIST_MARK = "@@K@L@@"
        const val UNREADABLE = "__KERNELIS_UNREADABLE__"
        const val BATCH_TIMEOUT_MS = 15_000L
    }
}
