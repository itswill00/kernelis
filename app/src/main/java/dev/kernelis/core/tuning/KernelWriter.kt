package dev.kernelis.core.tuning

import dev.kernelis.core.root.RootShell
import dev.kernelis.core.root.ShellCommand
import dev.kernelis.core.root.ShellResult
import dev.kernelis.core.sysfs.SysFs
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only place in KERNELIS allowed to write to the kernel.
 *
 * Safety properties, each of which is verified after the write rather than assumed:
 *  1. **Pre-flight read.** The current value is captured so rollback needs no re-read.
 *  2. **Write.** The value is written with a single `printf > file` redirection; the
 *     value has already been validated to be a single safe token.
 *  3. **Read-back verification.** The node is re-read and compared. Kernels may clamp,
 *     reorder, or reject a value; reporting success without checking would be a lie.
 *  4. **Automatic revert on mismatch.** If verification shows a value other than what
 *     was requested and the previous value is known, it is restored immediately.
 *
 * This is *runtime* rollback — it undoes a write that did not take effect. It is not a
 * bootloop recovery mechanism; see docs/RECOVERY.md for that distinction.
 */
@Singleton
public class KernelWriter @Inject constructor(
    private val shell: RootShell,
    private val sysFs: SysFs,
) {
    private val writeInProgress = AtomicBoolean(false)

    public suspend fun write(request: TuningRequest): WriteOutcome {
        if (!writeInProgress.compareAndSet(false, true)) {
            return WriteOutcome.Busy
        }
        try {
            val before = sysFs.read(request.path)
            if (before == null) {
                return WriteOutcome.Failed(
                    reason = "Could not read ${request.path} before writing.",
                    before = null,
                    after = null,
                )
            }
            val previous = request.previousValue ?: before

            val command = ShellCommand(
                binary = "sh",
                args = listOf("-c", "printf %s ${ShellCommand.quote(request.rawValue)} > ${request.path}"),
                timeoutMs = 5_000,
            )
            val result: ShellResult = shell.exec(command)
            if (!result.isSuccess) {
                return WriteOutcome.Failed(
                    reason = writeFailureReason(result),
                    before = previous,
                    after = sysFs.read(request.path),
                    exitCode = result.exitCode,
                )
            }

            // Verification: sysfs reads can be cached by the kernel, but a re-read after
            // a successful write reflects the value the driver actually stored.
            val after = sysFs.read(request.path)
            val verified = after != null && valuesEquivalent(after, request.rawValue)
            return if (verified) {
                WriteOutcome.Applied(
                    requested = request.rawValue,
                    verified = after,
                    previousValue = previous,
                )
            } else {
                val reverted = attemptRevert(request.path, previous)
                WriteOutcome.NotApplied(
                    reason = "Kernel did not accept '${request.rawValue}' (now reports '${after ?: "unreadable"}')." +
                        if (reverted) " Previous value restored." else "",
                    previousValue = previous,
                    observed = after,
                    reverted = reverted,
                )
            }
        } finally {
            writeInProgress.set(false)
        }
    }

    /**
     * Best-effort restore used by both verification failure and explicit rollback.
     * Returns true only when the node reads back the restored value.
     */
    public suspend fun attemptRevert(path: String, previous: String?): Boolean {
        if (previous == null) return false
        val command = ShellCommand(
            binary = "sh",
            args = listOf("-c", "printf %s ${ShellCommand.quote(previous)} > $path"),
            timeoutMs = 5_000,
        )
        val result = shell.exec(command)
        if (!result.isSuccess) return false
        val after = sysFs.read(path)
        return after != null && valuesEquivalent(after, previous)
    }

    /**
     * Compares a sysfs read-back with the requested value.
     *
     * Multi-valued nodes (e.g. `input_boost_freq` lists several frequencies) report the
     * full list, so containment is accepted. Numeric comparison is done numerically so
     * that "0800" and "800" are equivalent.
     */
    internal fun valuesEquivalent(observed: String, requested: String): Boolean {
        val o = observed.trim()
        val r = requested.trim()
        if (o == r) return true
        val on = o.toLongOrNull()
        val rn = r.toLongOrNull()
        if (on != null && rn != null) return on == rn
        if (o.split(Regex("\\s+")).contains(r)) return true
        // Some drivers echo with surrounding brackets or units.
        return o.replace("[", "").replace("]", "").trim() == r
    }

    private fun writeFailureReason(result: ShellResult): String = when {
        result.stderr.contains("Read-only file system", ignoreCase = true) ->
            "The node is read-only on this kernel (props or SELinux may enforce this)."
        result.stderr.contains("Permission denied", ignoreCase = true) || result.stderr.contains("denied", ignoreCase = true) ->
            "Permission denied by SELinux or file mode."
        result.stderr.contains("No such file", ignoreCase = true) ->
            "The node disappeared between scan and write."
        result.stderr.contains("Invalid argument", ignoreCase = true) ->
            "The kernel rejected the value as invalid for this control."
        else -> (result.stderr.ifBlank { result.combinedOutput }).trim().take(300).ifBlank {
            "Write failed with exit code ${result.exitCode}."
        }
    }

    public companion object {
        public val WRITABLE_PREFIXES: List<String> = listOf(
            "/sys/devices/system/cpu/",
            "/sys/class/thermal/",
            "/sys/class/kgsl/",
            "/sys/class/devfreq/",
            "/sys/block/",
            "/sys/module/",
            "/dev/stune/",
            "/dev/cpuctl/",
            "/proc/sys/vm/",
            "/proc/sys/kernel/",
            "/sys/kernel/",
        )

        /** Defence in depth: even a validated path must live under a known kernel tree. */
        public fun isWritablePath(path: String): Boolean =
            path.startsWith('/') && WRITABLE_PREFIXES.any { path.startsWith(it) }
    }
}

/** Outcome of a write attempt. Every branch states what is now true on the device. */
public sealed interface WriteOutcome {
    /** The value was written and the read-back matched. [previousValue] enables rollback. */
    public data class Applied(
        val requested: String,
        val verified: String,
        val previousValue: String?,
    ) : WriteOutcome

    /** The write was rejected or clamped, and the previous value was restored if possible. */
    public data class NotApplied(
        val reason: String,
        val previousValue: String?,
        val observed: String?,
        val reverted: Boolean,
    ) : WriteOutcome

    /** The write could not even be attempted. */
    public data class Failed(
        val reason: String,
        val before: String?,
        val after: String?,
        val exitCode: Int? = null,
    ) : WriteOutcome

    /** Another write is in flight; the caller must retry rather than race. */
    public data object Busy : WriteOutcome

    val isSuccess: Boolean get() = this is Applied

    val description: String
        get() = when (this) {
            is Applied -> "Applied $requested (verified: $verified)"
            is NotApplied -> reason
            is Failed -> reason
            Busy -> "Another change is currently being applied."
        }
}
