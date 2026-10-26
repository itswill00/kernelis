# Threat model

KERNELIS runs as root on a device the user controls. This document states what that
means, what the app does to limit damage, and what it explicitly cannot protect against.

## Assets

1. **Device availability** — the device must keep booting.
2. **Kernel integrity** — runtime tuning must not corrupt kernel state.
3. **Audit truth** — the change log must reflect what actually happened.
4. **User data** — the app holds no user content, but its database sits in app storage.

## Trust boundaries

| Boundary | Trust | Control |
| --- | --- | --- |
| UI → domain | Untrusted input | Validation before any write (`TuningValidator`) |
| Domain → shell | Partially trusted | Single-token values, POSIX quoting, path allowlist |
| Shell → kernel | Untrusted | Read-back verification after every write |
| Network → app | Untrusted | HTTPS only; update APK handed to system installer |
| Root manager → app | Trusted for `su`, not for capabilities | Capabilities are discovered, never inferred from provider |

## Threats and mitigations

### T1 — Command injection through a value or profile file

**Vector:** a profile JSON containing `"value": "1; reboot"`, or a crafted sysfs path.

**Mitigation:** values must match `^[A-Za-z0-9_.+:/\-,]+$` (single token). Commands are
rendered with POSIX single-quote escaping (`ShellCommand.quote`). Paths must be absolute,
contain no newline/NUL, and must match a discovered binding before a write is attempted.
Tested in `ShellCommandTest`.

**Residual risk:** a *legitimate* single-token value can still be harmful
(e.g. `scaling_max_freq=100000`). That is a correctness problem, handled by range
validation, not an injection problem.

### T2 — Writing to a node that does not exist or changed type

**Vector:** stale profile from another kernel; node removed between scan and write.

**Mitigation:** writes require an exact path match against the current capability
snapshot. A write failure is reported with the kernel's own error (ENOENT, EINVAL,
EACCES) rather than swallowed. Snapshots re-resolve entries against a fresh scan.

### T3 — Silent acceptance of a rejected value

**Vector:** kernel clamps or ignores a write; naive app reports success.

**Mitigation:** every write is followed by a read and compared. Mismatch triggers
automatic revert and a `REJECTED`/`REVERTED` log row. This is the single most important
honesty property in the app.

### T4 — Bricking through persistence

**Vector:** a bad value applied at boot.

**Mitigation:** *this release has no boot persistence.* All tuning is runtime and dies on
reboot. If persistence is added, it must be opt-in, validated at boot, and paired with a
root-manager module for out-of-app recovery.

### T5 — Malicious update

**Vector:** tampered APK delivered as an "update".

**Mitigation:** updates are fetched over HTTPS from a fixed GitHub repository URL, and the
APK is passed to the **system package installer**, which enforces signature matching
against the installed app. An APK signed by a different key is rejected by Android.
KERNELIS does not self-install silently.

**Residual risk:** a compromised GitHub account could publish a malicious release *signed
with the same key*. Signature continuity protects against third parties, not against the
publisher. This is inherent to in-app updates.

### T6 — Data exfiltration

**Vector:** the app reading kernel state and sending it somewhere.

**Mitigation:** KERNELIS makes exactly one kind of network request: `GET` on the public
GitHub releases API. No telemetry, no analytics, no crash reporting. The diagnostics
report is only ever written to the screen for the user to copy manually.

### T7 — Root escalation by other apps via KERNELIS

**Vector:** another app invoking KERNELIS components to perform privileged writes.

**Mitigation:** no exported components other than the launcher activity. The FileProvider
is `exported="false"` with a single narrow path. There is no `Service`, `Receiver` or
`ContentProvider` that accepts external intents in this release.

### T8 — Denial of service through excessive sampling

**Vector:** the dashboard draining battery or blocking the shell.

**Mitigation:** sampling runs only while the dashboard is resumed, at 1.5 s intervals,
with one batched read per frame. Writes are serialised behind a single-flight flag;
concurrent writes return `Busy` instead of racing.

## Out of scope

- **Malicious root manager.** If `su` is compromised, nothing an app does matters.
- **Malicious kernel.** A kernel can lie about every value it reports.
- **Physical access.** KERNELIS does not protect a device from someone holding it.
- **Play Integrity / root hiding.** KERNELIS does not attempt to hide root and does not
  interact with DenyList or Shamiko.
- **Multi-user isolation.** The app assumes the primary user; secondary profiles are not
  tested.

## Reporting

Security issues should be reported privately via GitHub Security Advisories on the
repository rather than public issues.
