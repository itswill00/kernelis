<div align="center">

# KERNELIS

**A root kernel & system manager for Android that tells you the truth.**

Capability discovery · verified writes · audited changes · runtime rollback

[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Min SDK](https://img.shields.io/badge/minSdk-26-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Tests](https://img.shields.io/badge/unit%20tests-69%20passing-35D0BA)](#testing)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

</div>

---

## Why this exists

Kernel managers tend to be either a wall of toggles that silently do nothing, or a
locked-down app tied to one developer's kernel. Both fail the same way: **you cannot
tell what actually happened.**

KERNELIS is built around four rules:

| Rule | What it means in practice |
| --- | --- |
| **Detect, never assume** | No sysfs node, driver or root provider is presumed. A scan binds each control to a path the kernel really exposes, or reports it unsupported *with a reason*. |
| **No fake data** | A missing sensor renders `N/A`. A rejected write is never reported as applied. A failed update check never says "up to date". |
| **Every change is audited** | Applied, rejected, failed, reverted — all appended to a log you can read and roll back. |
| **Reboot is always a reset** | Tuning is runtime-only. Nothing is persisted that could bootloop your device. |

## What it looks like

```
Monitor    live CPU/RAM/thermal/battery, real charts, N/A where unsupported
Tuning     stage → review diff → apply (risk-gated, snapshot first)
Profiles   capture current state, re-apply presets resolved per device
History    audit log grouped by operation, rollback per operation
Diagnostics root probe · capability report · read-only expert console
```

## Supported hardware

Discovery is layout-driven, so support is not a device list — it is whatever your kernel
exposes. Verified layouts in the test suite:

| SoC family | CPU | GPU | Notes |
| --- | --- | --- | --- |
| **Qualcomm Snapdragon** | cpufreq policies, boost, msm_performance | Adreno via KGSL | Load/clock read from the parent of `devfreq/` |
| **MediaTek** | PPM procfs (`/proc/ppm/…`), EEM | Mali via devfreq | MTK often ignores direct cpufreq writes — the working interface is reported |
| **Samsung Exynos** | `/sys/power/cpufreq_*_limit`, DVFS | Mali / Xclipse | Falls back when standard cpufreq writes are ignored |
| **Google Tensor** | standard cpufreq | Mali | — |
| **Others** | standard cpufreq | any devfreq device whose `name` hints GPU | Last-resort scan of `/sys/class/devfreq` |

Root providers: **Magisk**, **KernelSU**, **APatch**, or any working `su`.
See [docs/ROOT_PROVIDERS.md](docs/ROOT_PROVIDERS.md) for the limitations that actually
bite (DenyList hiding `su`, KernelSU app profiles, APatch superkey).

## Install

Grab an APK from [Releases](https://github.com/itswill00/kernelis/releases):

```bash
adb install -r app-github-debug.apk
```

Two build flavors exist because distribution rules differ:

| Flavor | Self-update | `REQUEST_INSTALL_PACKAGES` | Use |
| --- | --- | --- | --- |
| `github` | yes | declared | Direct APK distribution |
| `play` | **no** | **not declared** | Google Play — policy forbids apps that install their own updates |

## Build

Requirements: **JDK 17+**, **Android SDK 37**, and a rooted device to actually exercise
the privileged paths.

```bash
# Debug APK (self-update flavour)
./gradlew :app:assembleGithubDebug --no-daemon

# Play-compliant build
./gradlew :app:assemblePlayDebug --no-daemon

# Unit tests
./gradlew :app:testGithubDebugUnitTest --no-daemon
```

`local.properties` (not committed) must contain `sdk.dir=/path/to/android-sdk`.

The build is RAM-tuned: 2 GB heap cap, two workers, no daemon by default. It builds on a
7 GB machine without swapping.

## Testing

**69 unit tests**, no device required. The interesting ones run the real scanner against
synthetic devices described node-by-node:

```
FakeSysFs + FakeRootShell  →  describe a device  →  real scanner runs unmodified
```

Covered: shell escaping / injection, `/proc` parsers (including thermal unit ambiguity and
battery mA-vs-µA), capability discovery for a modern Snapdragon and a minimal kernel,
MediaTek PPM, Exynos DVFS, Mali/PowerVR/Adreno GPU layouts, LMK, KSM, VM tunables, and
manifest component resolution (the class of bug that crashes on launch but passes every
JVM test).

```bash
./gradlew :app:testGithubDebugUnitTest --no-daemon
```

## Safety model

Three different kinds of "undo", documented in [docs/RECOVERY.md](docs/RECOVERY.md):

1. **Runtime rollback** — automatic. Every write is read back; a mismatch restores the
   previous value immediately and logs `REJECTED`/`REVERTED`.
2. **Operation rollback** — you tap it. Restores every applied write of one operation.
3. **Bootloop recovery** — **KERNELIS does not do this**, because it does not persist
   anything. Reboot always returns the device to its previous state.

Risk gating: `MEDIUM`+ takes a snapshot first; `HIGH` requires an explicit acknowledgement
checkbox. If a snapshot cannot be created, the change is refused.

## Documentation

| Document | Contents |
| --- | --- |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers, the two test seams, data flow, why reads are batched |
| [RECOVERY.md](docs/RECOVERY.md) | Runtime vs operation rollback vs bootloop recovery |
| [ROOT_PROVIDERS.md](docs/ROOT_PROVIDERS.md) | Magisk / KernelSU / APatch specifics and limits |
| [THREAT_MODEL.md](docs/THREAT_MODEL.md) | T1–T8 with mitigations and residual risk |
| [SUPPORTED_FEATURES.md](docs/SUPPORTED_FEATURES.md) | What is probed, what is not, and why |

## Not implemented (deliberately)

These are listed because pretending otherwise wastes your time:

- **Boot persistence** — needs root-manager integration and an out-of-app recovery story.
- **Kernel flashing / boot image backup** — large blast radius, needs partition tooling.
- **Per-app automation** — needs a foreground service and usage access.
- **Voltage / overclock** — unsafe and unavailable on most mobile SoCs.

## Contributing

Commit rules are enforced by a local hook (`.githooks/commit-msg`). Every commit needs:

```
Signed-off-by: itswill00 <anstykx00@gmail.com>
Co-authored-by: Claude <noreply@anthropic.com>
Change-Id: I<32-hex>        # generated automatically if missing
```

```bash
git config core.hooksPath .githooks
git config commit.template .gitmessage
./.githooks/mkcommit -m "your message"
```

## License

GPL-3.0 — see [LICENSE](LICENSE).

> KERNELIS writes to kernel interfaces. It validates, verifies and logs everything it can,
> but you are still modifying a running kernel. Read [docs/RECOVERY.md](docs/RECOVERY.md)
> first.
