package dev.kernelis.core.root

/**
 * Root discovery.
 *
 * KERNELIS must work with any root manager that can run `su -c`. This object probes
 * for known providers and records *what it actually observed*. It never assumes a
 * provider from the presence of a package name alone: `su -c id` must return uid 0
 * before root is considered usable.
 *
 * Documented limitations (see docs/ROOT_PROVIDERS.md):
 *  - KernelSU's `ksud` hides paths from non-root uids; we only look for it via su.
 *  - Magisk DenyList can unmount su for the app; in that case root is unavailable
 *    until the app is removed from DenyList. We report that state explicitly.
 *  - Provider version strings change format between releases; they are diagnostic only.
 */
public object RootDetector {

    /** Shell probe used to confirm that `su` really yields uid 0. */
    private val PROBE = ShellCommand(binary = "su", args = listOf("-c", "id -u"))

    /**
     * Probe the device. [shell] is the candidate root implementation; the result is a
     * [ProbeOutcome] describing what happened so diagnostics can show *why* root failed.
     */
    public suspend fun probe(shell: RootShell): ProbeOutcome {
        val result = shell.exec(PROBE.copy(timeoutMs = 15_000))
        if (!result.isSuccess) {
            return ProbeOutcome(
                available = false,
                reason = failureReason(result),
                rawResult = result,
            )
        }
        val uid = result.stdout.trim().lineSequence().lastOrNull()?.trim()?.toIntOrNull()
        val isRoot = uid == 0
        return ProbeOutcome(
            available = isRoot,
            reason = if (isRoot) null else "su returned uid $uid instead of 0 (root grant required)",
            rawResult = result,
            uid = uid,
        )
    }

    private fun failureReason(result: ShellResult): String = when (val f = result.failure) {
        ShellFailure.NoRoot -> "No working su binary. Device is not rooted, or KERNELIS was not granted root."
        is ShellFailure.PermissionDenied -> "Root request was denied: ${f.detail}"
        is ShellFailure.Timeout -> "Root request timed out after ${f.timeoutMs} ms. Check the root manager prompt."
        is ShellFailure.Transport -> "Could not reach root manager: ${f.detail}"
        null -> "su exited with code ${result.exitCode}: ${result.combinedOutput.trim().take(200)}"
    }

    public data class ProbeOutcome(
        val available: Boolean,
        val reason: String?,
        val rawResult: ShellResult,
        val uid: Int? = null,
    )
}
