# KERNELIS — Android root kernel & system manager

A polished, trustworthy control center for rooted Android devices. Not a bag of
tuning toggles: KERNELIS discovers what your kernel actually supports, validates every
change before it is written, verifies what the kernel did with it, and logs everything
so you can roll back.

```
Status: work in progress · Kotlin · Jetpack Compose · single :app module
```

## Principles

1. **Detect, never assume.** No sysfs node, kernel feature, permission, or root
   provider is assumed to exist. A full capability scan runs on the device and each
   control is bound to a path the kernel actually exposed — or reported as unsupported
   with a reason.
2. **No fake metrics.** A missing sensor produces *N/A*, never zero. A rejected write
   is never reported as applied.
3. **Every change is audited.** Applied, rejected, failed, reverted — all of it lands
   in an append-only change log you can review and roll back.
4. **Dangerous operations are opt-in.** Risk classification drives confirmation,
   snapshotting and (where the kernel allows) automatic revert.
5. **Battery-respectful monitoring.** One batched `su` read per telemetry frame,
   lifecycle-aware sampling, no background work unless you enable it.

## Requirements

- Android 8.0+ (API 26+), a rooted device (Magisk, KernelSU, APatch, or any working
  `su`)
- JDK 17+ and Android SDK 37 to build

## Committing (aturan lokal repo ini)

Setiap commit **wajib** memuat tiga trailer berikut. Hook `commit-msg` menolak
commit yang tidak patuh; `Change-Id` dibuat otomatis bila kosong.

```
Signed-off-by: itswill00 <anstyk00@gmail.com>
Co-authored-by: Claude <noreply@anthropic.com>
Change-Id: I<32-hex>
```

Cara paling mudah:

```bash
git add -A
./.githooks/mkcommit -m "ringkasan perubahan" [-m "badan opsional"]
```

Setup setelah clone (agar hook aktif):

```bash
git config commit.template .gitmessage
```

## Building

```bash
./gradlew :app:assembleDebug --no-daemon
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Tests:

```bash
./gradlew :app:testDebugUnitTest --no-daemon
```

`local.properties` (not committed) must point `sdk.dir` at your SDK.

## Documentation

| Document | Purpose |
| --- | --- |
| `docs/ARCHITECTURE.md` | Layers, module boundaries, data flow |
| `docs/ROOT_PROVIDERS.md` | Magisk / KernelSU / APatch notes and limitations |
| `docs/THREAT_MODEL.md` | What this app can and cannot protect against |
| `docs/RECOVERY.md` | Runtime rollback vs bootloop recovery — read before tuning |
| `docs/SUPPORTED_FEATURES.md` | What is probed, what is not, and why |

## License

GPL-3.0 — see [LICENSE](LICENSE).
