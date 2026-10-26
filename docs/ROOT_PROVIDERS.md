# Root providers

KERNELIS talks to root through one boundary (`dev.kernelis.core.root.RootShell`) and one
implementation (`SuProcessRootShell`). It does not link any vendor SDK, which is what
makes it work across managers — and also what limits it.

## What is actually used

Every provider ultimately exposes a `su` binary that runs a command as uid 0. KERNELIS
probes with:

```
su -c "id -u"
```

Root is considered available **only** if that returns `0`. A present `su` binary that is
denied or hangs is reported as unavailable with a reason, never assumed to work.

## Provider detection

Detection is diagnostic only — it never changes behaviour. KERNELIS tries, in order:

| Provider | Probe | Notes |
| --- | --- | --- |
| Magisk | `magisk -V` / `magisk -v` | Version code and version string. |
| KernelSU | `cat /data/adb/ksu/version`, `test -d /data/adb/ksu` | Requires root to read. |
| APatch | `cat /data/adb/ap/version`, `test -d /data/adb/ap` | Requires root to read. |
| Other | `id -u` returns 0 | Reported as `OTHER`. |

If detection fails but `su` works, KERNELIS still runs — it just labels the provider
`other` and says so in Diagnostics.

## Documented limitations

### Magisk

- **DenyList hides `su` from this app.** If KERNELIS is on the DenyList, root requests
  fail. Remove KERNELIS from DenyList in the Magisk app. KERNELIS reports "no working su
  binary", which is accurate but not the root cause — check DenyList first.
- **Superuser access set to "ADB only"** blocks app requests. Set it to "Apps and ADB".
- Magisk hides itself by randomising its package name; KERNELIS does not depend on the
  package name, only on `su`.
- `magisk -V` output format has changed across releases; the version string is shown as
  diagnostics and is never parsed for capability decisions.

### KernelSU

- KernelSU grants root per-app through its own manager UI. If the request is denied there,
  KERNELIS sees a failed `su` and reports permission denied.
- **App Profile features** (uid/gid/capability restriction) can silently break privileged
  reads. If scans return "Unreadable" for many nodes, check the app's KernelSU profile.
- `ksud` is not used directly. KERNELIS only reads `/data/adb/ksu/version` for reporting.
- KernelSU's metamodule mounting does not affect sysfs reads; no special handling needed.

### APatch

- Same `su` semantics as above; detection via `/data/adb/ap`.
- APatch's "superkey" model means the first root request needs the key entered in the
  APatch app. A timeout during the first scan is normal; re-probe from Diagnostics.

### SuperSU / legacy

- Not tested. Should work if `su -c` behaves like standard `su`, but the SELinux context
  handling on old ROMs often blocks sysfs writes. Treated as `OTHER`.

## Things KERNELIS cannot do through `su`

- **No kernel module loading.** Writing to `/sys/module/...` parameters works only for
  modules already loaded.
- **No SELinux policy changes.** If a write is blocked by SELinux, KERNELIS reports
  "Permission denied by SELinux or file mode" and stops. It does not run `setenforce 0`.
- **No system partition writes.** Everything is sysfs/procfs runtime state.
- **No `init` service changes.** Persistence (not implemented) would need a root-manager
  module, not an app.

## Security posture

- KERNELIS never runs `setenforce`, `mount -o remount`, or writes to `/system`.
- The expert console is **read-only** with an allowlist; mutating binaries are refused
  before they reach the shell.
- All values are single-token validated and shell-quoted before use, so a hostile profile
  file cannot inject commands.
