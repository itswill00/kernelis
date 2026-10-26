package dev.kernelis.core.root

import java.io.File

/**
 * Result of a single rooted (or unrooted) shell invocation.
 *
 * [exitCode] is the real exit status reported by the shell. When the root manager
 * cannot run the command at all (for example the user denied the request) [exitCode]
 * is [EXIT_NOT_EXECUTED] and [failure] explains why. We never fabricate an exit code
 * of 0 in that case: code paths that check `isSuccess` must not treat a denied
 * request as success.
 */
public data class ShellResult(
    val command: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMs: Long,
    val failure: ShellFailure? = null,
) {
    val isSuccess: Boolean get() = exitCode == 0 && failure == null

    /** Combined output, useful for diagnostics; ordering is stdout then stderr. */
    val combinedOutput: String
        get() = when {
            stderr.isBlank() -> stdout
            stdout.isBlank() -> stderr
            else -> "$stdout\n$stderr"
        }

    companion object {
        const val EXIT_NOT_EXECUTED: Int = -1
    }
}

/** Why a shell command could not be executed at all. */
public sealed interface ShellFailure {
    /** No root manager responded, or root access was not granted to KERNELIS. */
    public data object NoRoot : ShellFailure

    /** The root manager exists but refused this specific invocation. */
    public data class PermissionDenied(val detail: String) : ShellFailure

    /** The shell timed out. The command may or may not have partially applied. */
    public data class Timeout(val timeoutMs: Long) : ShellFailure

    /** The root abstraction itself failed (process spawn, IO, ...). */
    public data class Transport(val detail: String) : ShellFailure
}

/**
 * The single root boundary of the application.
 *
 * Everything privileged goes through this interface so that:
 *  - root providers (Magisk / KernelSU / APatch / adb shell) can be swapped,
 *  - tests can substitute a deterministic fake with no device,
 *  - every privileged action can be logged and audited in one place.
 *
 * Implementations must never modify device state on their own; they only execute
 * the [ShellCommand] they are given. Validation and rollback live above this layer.
 */
public interface RootShell {
    /** True when a working root shell is currently available. */
    public suspend fun isAvailable(): Boolean

    /** Best-effort identification of the root provider, for diagnostics only. */
    public suspend fun provider(): RootProvider

    /** Execute a command, capturing its output. */
    public suspend fun exec(command: ShellCommand): ShellResult

    /** Convenience for read-only queries: returns stdout or null on failure. */
    public suspend fun readText(path: String): String?
}

/**
 * A command assembled from explicit, already-validated arguments.
 *
 * Arguments are never interpolated into a single string by callers; [render] performs
 * POSIX single-quote escaping so that a value coming from the UI or a profile file
 * cannot break out of its argument and run arbitrary shell code.
 */
public data class ShellCommand(
    val binary: String,
    val args: List<String>,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    /** True when the command is a pure read (eligible for caching by callers). */
    val isReadOnly: Boolean get() = binary in READ_ONLY_BINARIES

    public fun render(): String = buildString {
        append(quote(binary))
        for (arg in args) {
            append(' ')
            append(quote(arg))
        }
    }

    public companion object {
        public const val DEFAULT_TIMEOUT_MS: Long = 10_000
        private val READ_ONLY_BINARIES = setOf("cat", "ls", "getprop", "dumpsys", "id", "stat", "readlink", "echo")

        /** POSIX-safe single-quote escaping. */
        public fun quote(value: String): String {
            if (value.isEmpty()) return "''"
            if (value.all { it.isLetterOrDigit() || it in SAFE_CHARS }) return value
            return "'" + value.replace("'", "'\\''") + "'"
        }

        private val SAFE_CHARS = "-_./:=@%+,".toSet()
    }
}

/** Identified root provider. Values are informative, never trusted for capability decisions. */
public data class RootProvider(
    val family: Family,
    val version: String?,
    val detail: Map<String, String> = emptyMap(),
) {
    public enum class Family {
        MAGISK,
        KERNELSU,
        APATCH,
        OTHER,
        UNKNOWN,
    }

    public companion object {
        public val NONE: RootProvider = RootProvider(Family.UNKNOWN, null)
    }
}

/** Marker for whether a path exists and whether it is writable by the current uid. */
public data class PathAccess(
    val path: String,
    val exists: Boolean,
    val readable: Boolean,
    val writableByRoot: Boolean,
    val uid: Int?,
    val gid: Int?,
    val mode: String?,
) {
    public val summary: String
        get() = when {
            !exists -> "absent"
            writableByRoot -> "writable"
            readable -> "read-only"
            else -> "unreadable"
        }

    public companion object {
        public fun absent(path: String): PathAccess =
            PathAccess(path, exists = false, readable = false, writableByRoot = false, uid = null, gid = null, mode = null)
    }
}

/** Internal helper shared by root implementations: validates a path shape before use. */
internal fun requireShellSafePath(path: String): String {
    require(path.startsWith('/')) { "sysfs path must be absolute: $path" }
    require(path.none { it == '\n' || it == '\u0000' }) { "path contains control characters" }
    require(File(path).isAbsolute)
    return path
}
