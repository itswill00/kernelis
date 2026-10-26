# Supported features

This table reflects what the scanner actually probes. "Probed" means KERNELIS looks for
the node; whether it is *available* depends entirely on your kernel and is reported at
runtime in the app (Diagnostics → Capabilities).

## Monitoring

| Feature | Source | Notes |
| --- | --- | --- |
| Per-core CPU load | `/proc/stat` deltas | Requires two samples; first frame has no load. |
| Per-core frequency | `cpufreq/scaling_cur_freq` | Falls back to `cpuinfo_cur_freq` per policy. |
| CPU topology | `cpufreq/policyN` or per-CPU dirs | Clusters inferred by frequency table when policy dirs are absent. |
| RAM / swap | `/proc/meminfo` | Uses `MemAvailable` when present. |
| ZRAM usage + ratio | `/sys/block/zram0/*` | Ratio derived from `orig_data_size` / `mem_used_total`. |
| Thermal zones | `/sys/class/thermal/thermal_zone*/` | Unit auto-detected (milli/deci/whole degrees). |
| Battery | `BatteryManager` | Percent, temp, voltage, current, charge counter, health. |
| Storage | `StatFs(/data)` | Refreshed every 8th frame. |
| Pressure stall | `/proc/pressure/{cpu,memory,io}` | Kernel 4.20+ only. |
| GPU load | `kgsl-3d0/gpu_busy_percentage` or devfreq `load` | Adreno-centric; absent elsewhere. |

## Tuning (write) — all validated and verified

| Control | Path(s) probed | Risk |
| --- | --- | --- |
| CPU min/max frequency | `policyN/scaling_{min,max}_freq` | LOW |
| CPU governor | `policyN/scaling_governor` | MEDIUM |
| Governor tunables | `policyN/*` + `/sys/devices/system/cpu/cpufreq/<gov>/*` | MEDIUM |
| Core online state | `cpuN/online` | **HIGH** |
| GPU governor / min / max | devfreq dir discovered at runtime | LOW–MEDIUM |
| I/O scheduler | `/sys/block/<dev>/queue/scheduler` | MEDIUM |
| Read-ahead | `queue/read_ahead_kb` | LOW |
| Merge policy / rotational | `queue/nomerges`, `queue/rotational` | LOW |
| Dirty ratio / swappiness / vfs cache pressure | `/proc/sys/vm/*` | LOW–MEDIUM |
| stune boost | `/dev/stune/*/schedtune.boost` | MEDIUM |
| uClamp | `/proc/sys/kernel/sched_util_clamp_*` | MEDIUM |

Governor tunables are limited to a conservative allowlist (`up_threshold`,
`hispeed_freq`, `rate_limit_us`, …). Unknown files in a governor directory are ignored
rather than exposed as unlabelled knobs.

## Deliberately not implemented

| Feature | Why not |
| --- | --- |
| Boot persistence | Requires root-manager integration + out-of-app recovery design. See `RECOVERY.md`. |
| Kernel flashing / boot image backup | High blast radius; needs partition-level tooling. |
| Per-app automation | Needs a foreground service + app-usage access; not built yet. |
| Sound / LED / KCAL controls | Vendor-specific drivers; no reliable discovery path. |
| Voltage / overclock | Unsafe and unsupported on most mobile SoCs. |
| SELinux mode switching | KERNELIS never runs `setenforce`. |
| ZRAM resize | Probed but gated as HIGH; not offered in the UI yet because changing `disksize` under active swap can OOM. |

## Reporting "unsupported" honestly

If a control shows as unsupported, KERNELIS could not find a node it understands. That is
not a bug in your device; it means your kernel does not expose that interface. The exact
reason is shown in Diagnostics:

- **Not present on this kernel** — no matching node.
- **Unreadable: …** — node exists, read failed (SELinux, permission, root denied).
- **Unrecognised format: …** — node exists but contents did not parse; the raw sample is shown.
- **Root access required** — no working `su`.

Please include that Diagnostics report in bug reports — it is the fastest way to add
support for a new kernel layout.
