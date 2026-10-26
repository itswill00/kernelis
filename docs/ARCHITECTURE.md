# Architecture

One Gradle module (`:app`), layered so that device-specific behaviour never leaks into
the UI and the whole discovery pipeline is testable without a device.

```
ui/          Compose screens, ViewModels (Hilt)
  ├─ dashboard, tuning, profiles, history, diagnostics, update
  ├─ charts/      Canvas primitives (no chart library)
  └─ theme/       palette, typography, CompositionLocal
domain/      CapabilityService — owns the scan, caches it, primes telemetry
core/
  ├─ root/       RootShell boundary + SuProcessRootShell
  ├─ sysfs/      SysFs projection + ShellSysFs (batched reads)
  ├─ capability/ CapabilityId, Scanner, CpuTopologyScanner
  ├─ telemetry/  TelemetryFrame, ProcParsers, TelemetryCollector
  ├─ tuning/     Validator, KernelWriter, ApplyPlanner, profiles/snapshots
  └─ update/     GitHub releases client + DownloadManager installer
data/        Room (change log, snapshots, profiles) + TuningRepository
di/          Hilt modules
```

## The two seams

Everything device-specific funnels through two interfaces, which is what makes the rest
testable:

```kotlin
interface RootShell {            // how privileged commands run
    suspend fun exec(command: ShellCommand): ShellResult
}
interface SysFs {                // what the kernel exposes
    suspend fun readMany(paths: Collection<String>): Map<String, String>
}
```

Tests substitute `FakeRootShell` and `FakeSysFs` (see `src/test/.../testing/Fakes.kt`)
and describe a synthetic device node-by-node. The real scanner runs unmodified against
them.

## Data flow: a tuning change

```
UI stages value
   └─ TuningValidator.validate(request, bindings)      ← rejects bad values
        └─ ApplyPlanner.plan(snapshot, entries)        ← builds diff + skip reasons
             └─ user confirms (HIGH risk needs checkbox)
                  └─ TuningRepository.applyPlan
                       ├─ snapshot first (if risk ≥ MEDIUM or multi-write)
                       ├─ KernelWriter.write(request)
                       │    ├─ read previous
                       │    ├─ printf value > path
                       │    ├─ read back, compare
                       │    └─ revert on mismatch
                       └─ append ChangeLogEntity (applied/rejected/failed/reverted)
```

Nothing writes without passing through all of it.

## Why reads are batched

Each `su` invocation costs tens of milliseconds on Android. A naive scan of ~60 sysfs
nodes would be ~2 seconds of shell churn. `ShellSysFs.readMany` builds a single script
that `cat`s every path with delimiter framing, so a full scan is a handful of round-trips
and a telemetry frame is one.

## Capability model

`CapabilityId` is a *semantic* control (e.g. `CPU_MAX_FREQ`), never a path. The scanner
binds ids to whatever paths this kernel exposes and records one of:

- `Supported(detail)`
- `NotPresent` — node absent; normal, not an error
- `Unreadable(reason)` — exists but the scan could not read it
- `Unrecognised(sample)` — found but in an unknown format
- `NoRoot`

The UI renders `NotPresent` as "unsupported on this kernel" with the reason. There is no
fallback that pretends a control exists.

## Telemetry

`TelemetryFrame` fields are nullable by design. `null` means "not exposed", rendered as
N/A. Parsers are pure functions (`ProcParsers`) tested against captured real formats,
including the unit ambiguities that actually exist (thermal milli/deci/whole degrees,
battery mA vs µA).

Sampling is owned by `DashboardViewModel` and runs only between `resume()` and `pause()`.

## Persistence

Room, three tables:

| Table | Nature |
| --- | --- |
| `change_log` | append-only audit trail; never updated in place |
| `snapshots` | full captures of writable state, JSON payload |
| `profiles` | built-in presets + user captures |

Migrations are declared explicitly; there is **no** `fallbackToDestructiveMigration` —
losing the audit log on a schema change would destroy recovery evidence.

## Deliberate non-goals in this release

- Boot persistence (see `docs/RECOVERY.md` for why it is not here yet)
- Kernel flashing / backup / restore of boot images
- Per-app profile automation
- Multi-device sync

Each is a large surface with its own failure modes; shipping them half-done would
contradict the "no non-functional toggles" rule.
