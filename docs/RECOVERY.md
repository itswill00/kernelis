# Recovery

Read this before tuning anything. KERNELIS distinguishes three very different kinds of
"undo", and conflating them is how people brick devices.

## 1. Runtime rollback (what KERNELIS does automatically)

Every write is followed by a read-back verification. If the kernel stores something
other than what was requested, KERNELIS immediately writes the previous value back and
records the attempt as `REVERTED` or `REJECTED`.

- Scope: a single node, a single write.
- Trigger: automatic, on verification mismatch.
- Limit: it can only undo a value that was readable *before* the write.

## 2. Operation rollback (user-initiated, in History)

Each tuning action gets an `operationId`. History groups rows by it and offers
**Roll back**, which restores the captured previous value for every applied row, newest
first, and logs the rollback as a new operation.

- Scope: one operation (one profile apply, one staged batch).
- Trigger: you tap it in History.
- Limit: it restores values, not side effects. If a governor change caused a thermal
  event, rollback does not undo the event — only the setting.

## 3. Bootloop recovery (KERNELIS does **not** do this)

A bootloop happens when something persists across reboots and prevents boot. KERNELIS
**does not persist tuning by default**: every change is a runtime sysfs write that the
kernel discards on reboot. That is a deliberate safety property, not an omission.

If you enable boot persistence later (not implemented in this release), a bad value could
survive a reboot. In that case KERNELIS alone cannot save you — recovery happens outside
the app:

| Situation | Recovery |
| --- | --- |
| Device boots but is unstable | Reboot. Runtime changes are gone. |
| Device bootloops after enabling persistence | Boot to recovery, mount `/data`, delete the persistence file (see below). |
| Root manager itself broken | Reflash the stock `boot.img`, then re-root. |
| Magisk bootloop | `adb shell` may still work in early boot: `magisk --remove-modules` |

### Where KERNELIS stores state (for manual recovery)

```
/data/data/dev.kernelis.app/databases/kernelis.db      # audit log, snapshots, profiles
/data/data/dev.kernelis.app/files/                     # app-internal files
```

Deleting `kernelis.db` resets history and snapshots. It does **not** change kernel state.

Snapshots live inside that database. If you are locked out of the UI but have root via
`adb shell`, you can read the last snapshot:

```bash
adb shell su -c "sqlite3 /data/data/dev.kernelis.app/databases/kernelis.db \
  'select payload from snapshots order by createdAtMs desc limit 1;'"
```

The payload is JSON: `[{"capability":"CPU_MAX_FREQ","path":"/sys/...","value":"1804000"}]`.
Writing those values back manually is possible but bypasses all validation — prefer
restoring from a working boot and using the UI.

## What KERNELIS will refuse to do

- Write to a path the scanner did not discover.
- Write a value that fails range/enumeration validation.
- Write when it cannot read the current value first.
- Apply a MEDIUM+ risk plan when a safety snapshot could not be created.
- Apply a HIGH risk plan without an explicit acknowledgement checkbox.

## Known unsafe combinations

These are refused or heavily gated because they are the common brick causes:

- **Offlining the last online core in a cluster** — the scheduler can stall.
- **`disksize` on zram while swap is active** — can OOM-kill the system.
- **Thermal trip points above the hardware shutdown threshold** — thermal runaway.
- **`scaling_max_freq` below `scaling_min_freq`** — the kernel clamps or errors; KERNELIS
  validates against the discovered notch table, but vendor kernels vary.

## If you are unsure

Do nothing. Reboot. Runtime tuning is not worth a brick, and KERNELIS is designed so that
rebooting is always a full reset.
