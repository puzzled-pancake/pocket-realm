# P0 Measurement Baseline Protocol (MariaDB build, "before" side of the A/B)

Status: **PROTOCOL READY — NUMBERS PENDING DEVICE SESSION.**

> **OWNER DECISION (2026-09-02):** P8 (cutover & supply-chain deletion)
> is DESCOPED — MariaDB stays the default provider permanently and the
> SQLite APK ships only as a separate experimental artifact. These
> baseline numbers therefore serve the P7 coverage/soak comparison of
> the experimental SQLite lane against the default MariaDB lane, not a
> provider swap.

The P0 spec requires one device session on the CURRENT MariaDB build at
presets `ALIVE_REALM_320` and `MASSIVE_REALM_600`. No Retroid Pocket 6 is
attached to the executing session, so the numbers cannot be recorded here;
this document is the turn-key procedure. The plan's Part 4 ledger carries
the open item; the A/B at P7 is blocked on it (see DEC-04).

## Preconditions

- Release-track arm64 build of the current `main` (MariaDB lane), installed
  on the Retroid Pocket 6 (capability record: `tools/capture_rp6.py`,
  `tests/devices/retroid-pocket-6/capability.json`).
- Thermal context per report §7: LLM worker (cores 3–5) and the Wine client
  active for the steady-state leg (coexistence is the shipping condition).
- `adb logcat` captured to a file for the whole session; wall-clock
  timestamps retained.

## Scripted session (per preset; run 320 first, then 600)

1. **Fresh first boot** (wipe app data or use a brand-new install):
   - record wall time from DATABASE component start to world-ready
     (supervisor journal `<filesDir>/supervisor/journal.json` lifecycle
     entries: `start:DATABASE` → world ready; the journal is app-private —
     run the session on a debuggable build and pull the files, or read the
     timestamps via `run-as`);
   - record the migration apply count + total (the engine's per-entry
     ledgerStatus/ledgerPending/ledgerFinish CLI invocations are visible as
     `libpocket_mariadb_client` process spawns in logcat; expected ~4 per
     pending entry on a fresh datadir);
   - record the first `POCKET_BOT_GENERATION_CHECKPOINT` line in logcat
     (printed at LogLevel 1) relative to world-ready.
2. **Steady boot** (second start, no migrations pending): wall time
   DATABASE start → world-ready from the journal; ledger-check spawn count
   from logcat.
3. **Login ramp**: poll `botStatusNative` via the service status JSON
   (`botsOnline`) — the per-bot `logged in` lines are `outDetail` and stay
   in the app-private world.log at the shipped LogLevel 1, so the ramp
   curve comes from status polling (the service already samples it), not
   logcat.
4. **Probe RTT**: read `dbProbeDelayMs` from the world status JSON
   (performance status slot 10; added with this plan — the probe delay was
   previously stored in-process and surfaced only by the disabled `diff`
   console command). Sample at the status cadence during steady state;
     `0` = never sampled, `4294967295` (UINT32_MAX) = login gate closed /
     probe expired — exclude both from percentiles. Report p50/p99.
5. **World tick p99**: `worldTickP99Ms`/`worldTickP95Ms` from the same
   status JSON (performance status slots; 2048-sample window); pass/fail
   vs the 250 ms admission contract (F49).
6. **Save wave / saveall**: `saveall-ack` lines in logcat
   (`PocketWorld` tag, `saveall-ack code=... durationMs=...` — added with
   this plan; previously only failures were logged): duration vs the 30 s
   ceiling (F48: 600-bot presets are modeled to exceed it TODAY — this is
   the expected "before" evidence).
7. **Graceful stop**: supervisor journal stop sequence timestamps
   (`save:WORLD` lifecycle record → `stop:DATABASE` clean marker); the
   save-drain window is bounded by the saveall-ack + stop-ack durations.

## Extraction

Probe RTT, world-p99, and login ramp come from the world status JSON
(performance/bot status slots — poll via the service or `dumpsys` on a
debuggable build); saveall-ack and generation checkpoints from logcat;
lifecycle sequencing from the app-private supervisor journal (pull with
`run-as` on a debuggable build). The two telemetry additions this plan
makes (`dbProbeDelayMs` status slot, `saveall-ack` log line) exist
precisely because the probe delay and save duration had NO existing
surface at the shipped log level.

## Recording

Append results to this file as a table: preset × metric × {p50, p99, max,
pass/fail vs contract}, with build id (git describe + APK sha256), date,
thermal state, and the raw log excerpt paths. These numbers are
keep-forever: they are the go/no-go record for the SQLite lane's P7 A/B
(same-or-better on every metric is the swap's exit criterion).
