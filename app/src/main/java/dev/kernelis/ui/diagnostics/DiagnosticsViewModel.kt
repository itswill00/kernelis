package dev.kernelis.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kernelis.core.capability.CapabilityId
import dev.kernelis.core.capability.CapabilitySnapshot
import dev.kernelis.core.root.RootDetector
import dev.kernelis.core.root.RootProvider
import dev.kernelis.core.root.RootShell
import dev.kernelis.core.root.ShellCommand
import dev.kernelis.core.root.ShellResult
import dev.kernelis.domain.CapabilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Diagnostics.
 *
 * Two jobs, both honest:
 *  1. **System report** — what root provider was found, what the kernel exposes, what
 *     the scanner could not read. This is the data you attach to a bug report.
 *  2. **Expert console** — a raw read-only shell for people who know what they are
 *     doing. It is READ-ONLY by design: writes go through the validated tuning flow,
 *     never through a free-text box.
 */
@HiltViewModel
public class DiagnosticsViewModel @Inject constructor(
    private val rootShell: RootShell,
    private val capabilityService: CapabilityService,
) : ViewModel() {

    public data class ProbeInfo(
        val available: Boolean,
        val reason: String?,
        val provider: RootProvider,
        val uid: Int?,
    )

    public data class ConsoleEntry(
        val command: String,
        val result: ShellResult,
    )

    public data class UiState(
        val probe: ProbeInfo? = null,
        val snapshot: CapabilitySnapshot? = null,
        val probing: Boolean = false,
        val consoleHistory: List<ConsoleEntry> = emptyList(),
        val consoleBusy: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    public val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        runProbe()
        viewModelScope.launch {
            capabilityService.state.collect { state ->
                if (state is CapabilityService.State.Ready) {
                    _uiState.value = _uiState.value.copy(snapshot = state.snapshot)
                }
            }
        }
    }

    public fun runProbe() {
        _uiState.value = _uiState.value.copy(probing = true)
        viewModelScope.launch {
            val probe = RootDetector.probe(rootShell)
            val provider = if (probe.available) rootShell.provider() else RootProvider.NONE
            _uiState.value = _uiState.value.copy(
                probing = false,
                probe = ProbeInfo(
                    available = probe.available,
                    reason = probe.reason,
                    provider = provider,
                    uid = probe.uid,
                ),
            )
        }
    }

    /**
     * Runs a read-only diagnostic command.
     *
     * The allowlist is small on purpose: `cat`, `ls`, `getprop`, `id`, `uname`,
     * `dumpsys` and a few probes. Anything that could mutate device state (`setprop`,
     * `mount`, `echo` redirection, `dd`, `rm`) is rejected before it reaches the shell —
     * writes belong in the validated tuning flow where they are logged and reversible.
     */
    public fun runCommand(raw: String) {
        val command = raw.trim()
        if (command.isEmpty() || _uiState.value.consoleBusy) return

        val binary = command.substringBefore(' ').substringAfter('/')
        if (binary !in ALLOWED_BINARIES) {
            appendConsole(
                command,
                ShellResult(
                    command = command,
                    stdout = "",
                    stderr = "Refused: '$binary' is not a read-only diagnostic command. " +
                        "Allowed: ${ALLOWED_BINARIES.joinToString(", ")}. " +
                        "Writes must go through Tuning, where they are validated and logged.",
                    exitCode = ShellResult.EXIT_NOT_EXECUTED,
                    durationMs = 0,
                    failure = dev.kernelis.core.root.ShellFailure.PermissionDenied("read-only console"),
                ),
            )
            return
        }

        _uiState.value = _uiState.value.copy(consoleBusy = true)
        viewModelScope.launch {
            val result = rootShell.exec(ShellCommand(binary = binary, args = listOf(command.removePrefix(binary).trim()).filter { it.isNotBlank() }))
            appendConsole(command, result)
            _uiState.value = _uiState.value.copy(consoleBusy = false)
        }
    }

    private fun appendConsole(command: String, result: ShellResult) {
        _uiState.value = _uiState.value.copy(
            consoleHistory = (_uiState.value.consoleHistory + ConsoleEntry(command, result)).takeLast(30),
        )
    }

    /** Human-readable summary suitable for pasting into a bug report. */
    public fun reportText(): String {
        val probe = _uiState.value.probe
        val snapshot = _uiState.value.snapshot
        return buildString {
            appendLine("KERNELIS diagnostics report")
            appendLine("===========================")
            appendLine("Root: ${if (probe?.available == true) "yes" else "no"}" +
                (probe?.uid?.let { " (uid $it)" } ?: ""))
            appendLine("Provider: ${probe?.provider?.family?.name ?: "?"} ${probe?.provider?.version ?: ""}")
            probe?.reason?.let { appendLine("Root reason: $it") }
            appendLine()
            if (snapshot != null) {
                appendLine("Kernel: ${snapshot.deviceFingerprint}")
                appendLine("Provider label: ${snapshot.rootProvider}")
                appendLine("Capabilities: ${snapshot.supportedCount}/${snapshot.totalCount} available")
                appendLine()
                appendLine("Unsupported controls:")
                snapshot.unsupported().forEach { (id, availability) ->
                    appendLine("  - ${id.label}: ${availability.reasonText}")
                }
                appendLine()
                appendLine("Notes:")
                snapshot.notes.forEach { appendLine("  - $it") }
            } else {
                appendLine("No capability scan available.")
            }
        }
    }

    public companion object {
        public val ALLOWED_BINARIES: Set<String> = setOf(
            "cat", "ls", "getprop", "id", "uname", "uptime", "stat", "readlink",
            "dumpsys", "dmesg", "logcat", "whoami", "groups", "df", "free",
            "test", "head", "wc",
        )
    }
}
