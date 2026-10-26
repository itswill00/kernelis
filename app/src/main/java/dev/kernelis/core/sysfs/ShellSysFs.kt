package dev.kernelis.core.sysfs

import dev.kernelis.core.root.RootShell
import dev.kernelis.core.root.ShellCommand
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [SysFs] backed by the root shell.
 *
 * Reads are batched into a single `cat` per request using a delimiter that cannot
 * appear in sysfs contents, because each `su` invocation on Android costs tens of
 * milliseconds. A scan that would otherwise take hundreds of round-trips takes one.
 *
 * Only [SysFs] itself is allowed to build shell strings; the paths it interpolates are
 * validated by [dev.kernelis.core.root.requireShellSafePath] so a hostile or malformed
 * path cannot inject commands.
 */
public class ShellSysFs(
    private val shell: RootShell,
) : SysFs {

    private val mutex = Mutex()
    private val cache = HashMap<String, String?>()
    private var cacheValid = false

    override suspend fun read(path: String): String? = readMany(listOf(path))[path]

    override suspend fun exists(path: String): Boolean = read(path) != null

    override suspend fun list(path: String): List<String> {
        val safe = safePath(path) ?: return emptyList()
        // `ls -1` avoids the columns that `ls -l` would introduce and keeps parsing trivial.
        val result = shell.exec(ShellCommand("ls", listOf("-1", safe), timeoutMs = 5_000))
        if (!result.isSuccess) return emptyList()
        return result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    override suspend fun readMany(paths: Collection<String>): Map<String, String> {
        val targets = paths.mapNotNull(::safePath).distinct()
        if (targets.isEmpty()) return emptyMap()

        return mutex.withLock {
            val out = LinkedHashMap<String, String>(targets.size)
            for (path in targets) {
                val cached = cache[path]
                if (cacheValid && cached != null) {
                    out[path] = cached
                }
            }
            val missing = targets - out.keys
            if (missing.isEmpty()) return@withLock out

            // One shell command per batch. Each file is printed after a marker line so a
            // missing/unreadable file is unambiguous instead of silently empty.
            val script = buildString {
                for (path in missing) {
                    append("echo ").append(DELIMITER).append(path).append('\n')
                    append("cat ").append(path).append(" 2>/dev/null || echo ").append(UNREADABLE).append('\n')
                }
            }
            val result = shell.exec(ShellCommand("sh", listOf("-c", script), timeoutMs = 15_000))
            if (!result.isSuccess) return@withLock out

            var currentPath: String? = null
            val buffer = StringBuilder()
            fun flush() {
                val path = currentPath ?: return
                val text = buffer.toString().trim()
                if (text.isNotEmpty() && text != UNREADABLE) {
                    out[path] = text
                    cache[path] = text
                }
                buffer.clear()
            }
            for (line in result.stdout.lineSequence()) {
                val trimmed = line.trimEnd()
                if (trimmed.startsWith(DELIMITER) && trimmed.length > DELIMITER.length + 1) {
                    flush()
                    currentPath = trimmed.removePrefix(DELIMITER).trim()
                } else {
                    if (buffer.isNotEmpty()) buffer.append('\n')
                    buffer.append(trimmed)
                }
            }
            flush()
            cacheValid = true
            out
        }
    }

    override suspend fun access(path: String): PathInfo {
        val safe = safePath(path) ?: return PathInfo.MISSING
        // `test -r` / `test -w` reflect the *root* perspective; sysfs nodes are usually
        // writable by root even when the node is logically read-only, so [canWrite] is
        // treated as a hint only and re-verified by the writer after each write.
        val result = shell.exec(
            ShellCommand(
                "sh",
                listOf(
                    "-c",
                    "if [ -e $safe ]; then echo exists=1; [ -r $safe ] && echo r=1; [ -w $safe ] && echo w=1; " +
                        "[ -L $safe ] && echo l=1; fi",
                ),
                timeoutMs = 5_000,
            ),
        )
        val text = result.stdout
        val exists = text.contains("exists=1")
        return PathInfo(
            path = path,
            exists = exists,
            canRead = exists && (text.contains("r=1") || text.isNotEmpty()),
            canWrite = text.contains("w=1"),
            isSymlink = text.contains("l=1"),
        )
    }

    /** Invalidate the read cache; called after any write so the next scan is truthful. */
    public fun invalidate() {
        synchronized(this) {
            cache.clear()
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
        const val DELIMITER = "@@KERNELIS@@"
        const val UNREADABLE = "__KERNELIS_UNREADABLE__"
    }
}
