# Device Test Checklist — Dual-Provider Database + LLM Integration (0.103.0-alpha)

Written 2026-09-02 against the uncommitted `feature/universal-client-installer`
working tree (dual-provider window P0–P6.5 + LLM integration + user Vulkan
driver registry + bot profile catalog). Owner decisions encoded here:

- **MariaDB/MySQL remains the stable, default database provider.** The
  MariaDB lane is never deleted or degraded.
- **SQLite ships as a separate, EXPERIMENTAL APK** (`-PsqliteProvider`
  assembly); both providers stay in the codebase and both APKs are built.
- Every phase gate that normally ran on the bench (Retroid Pocket 6 /
  Adreno 740) is listed below. Work top to bottom.

Build the two artifacts first (on the build host, not the device):

```bash
# Stable APK (MariaDB provider, default):
./gradlew -PpocketLane=full -PpocketAbi=arm64-v8a assembleRelease
# Experimental APK (SQLite provider; MariaDB closure still packaged for export/rollback):
./gradlew -PpocketLane=full -PpocketAbi=arm64-v8a -PsqliteProvider assembleRelease
```

## A. Two-APK identity and install

- [ ] Both APKs build clean for arm64-v8a; the SQLite APK is visibly
      labeled experimental (versionName suffix / About text), the stable
      APK is not.
- [ ] Stable APK installs and launches standalone on a clean profile.
- [ ] SQLite APK installs over the stable install (same applicationId,
      upgrade path preserved) and launches; its data dir is separate from
      the MariaDB datadir (`sqlite-*` markers, `provider/sqlite` root).
- [ ] Version/code identity is sane for both artifacts
      (0.103.0-alpha / versionCode 8; SQLite artifact distinguishable).

## B. MariaDB provider regression (the rollback anchor — must not regress)

- [ ] Fresh boot on MariaDB provider: datadir initialize → migrations
      marker written → world reaches RUNNING → clean stop
      (`clean-stop.json` written, no `recovery.json` on next boot).
- [ ] Existing (pre-upgrade) MariaDB datadir boots unchanged — no forced
      reseed, generation marker honored.
- [ ] Import client → play → stop → reboot device → realm boots again
      (durable-state round trip).
- [ ] Snapshot/restore lane still works (create snapshot, restore, boot).
- [ ] The SQLite APK can still BOOT the MariaDB provider (F13/F44: the
      old provider must come up inside the SQLite APK to export the
      sealed datadir).

## C. SQLite provider (experimental APK)

- [ ] Fresh SQLite boot: seeded datadir (1,461,781 rows / 28,020
      statements, 0 errors on the host lane) boots to world RUNNING.
- [ ] Seals verified: `sqlite-initialized.json`,
      `.pocketrealm-migrations.json`, `.pocketrealm-generation.json`
      written under the SQLite datadir; tamper → boot refuses loudly.
- [ ] Player account creation, character creation, world save, stop,
      reboot — user state survives (user-state bridge round trip).
- [ ] SQLite boot with an existing MariaDB datadir does NOT touch or
      migrate it in place (state stays distinct; provider marker honors
      the selection).
- [ ] A DB error path surfaces as a readable failure, not a silent
      fallback to MariaDB (no manufactured convergence).
- [ ] Kill -9 mid-write → next boot: `verifyOrRebuildSqlite` integrity
      gate fires; quick_check/integrity_check verdict and the
      VACUUM INTO rebuild path both behave.
- [ ] Seed replay on device: the compressed transcript replays through
      the chunk scanner with byte-exact digest verification; WAL size
      during replay stays under the 768 MiB reseed gate on slow storage.
- [ ] Crash windows in `provisionSqliteProvider` (kill during seed /
      import / carry / seal-commit): durable file interleavings
      (quarantine/retire/restore) leave the provider bootable.

## D. Export bridge / provider switch

- [ ] MariaDB → SQLite export on device: sealed datadir export runs,
      parity record written, SQLite boots from the exported state.
- [ ] Switch back SQLite → MariaDB: the MariaDB datadir still boots and
      pre-switch data is intact (rollback anchor holds).
- [ ] Interrupted export (kill app mid-export) leaves both providers
      bootable and a recovery record, not a half-migrated datadir.

## E. LLM integration on-device

- [ ] Vendored llama closure loads in the world runtime
      (`libllama.so` + 4 siblings staged from the correct
      `realm-staging[-sqlite]` root for the APK in hand).
- [ ] LLM screen: model download service completes over loopback/Wi-Fi,
      progress UI matches `LlmModelCoordinator` states, cancel works.
- [ ] Chatter: bots produce LLM banter in-world at plausible cadence;
      `ChatterPowerMonitor` reports sane numbers; governor caps hold
      (window / per-bot / global maxima from the emitted conf).
- [ ] Truth/beat gates behave on-device (no regression from the host
      gate suites `s7_truth_gates` / `s8_beats_gates` fixtures).
- [ ] Kill the world process mid-generation → no orphaned state, next
      boot clean.
- [ ] LLM disabled (default off) → zero llama work at boot (cold-start
      parity with pre-LLM builds).
- [ ] Companion-mode reload: `SetCompanionMode` mid-session → in-place
      free+load completes; prewarm requests during the reload window are
      dropped (not stalled); generation continues after reload.
- [ ] Model download (~2.8 GB): cancel mid-stream → resume via
      `.part`/`.etag` sidecars (If-Range always sent; a 206 without an
      ETag must not blank the validator); POST_NOTIFICATIONS grant;
      foreground-service tap-through on Android 12+.
- [ ] NPU (RP6): Hexagon probe passes → DSP skels extracted → `--device
      HTP0` healthy; 2 consecutive pre-healthy NPU deaths → CPU fallback
      with `npuBlocked`; Reset NPU clears it; PSI watchdog SIGTERMs the
      child only under sustained pressure.
- [ ] `:llm` process kill → sticky restart with persisted config;
      service kill → child SIGTERMed via PDEATHSIG (DSP session
      released); child pinned to cores 3-5 at nice 10.
- [ ] Template override: a thinking model triggers exactly one
      `--chat-template` restart; unhealthy override child reverts.
- [ ] Chatter power-file staleness (future `at=` stamp / clock jump)
      degrades to the EMERGENCY floor-only rung, never a chatter storm.

## F. User Vulkan driver registry (regression from 0.101/0.102 lanes)

- [ ] Packaged-driver launch unchanged (Turnip 26.1.0 / DXVK 2.4.1 proof
      in `WoW_d3d9.log`).
- [ ] Import → validate → select user driver still works end to end;
      registry upgrades cleanly to the new schema (old registry rows
      preserved or migrated, never dropped).
- [ ] Invalid driver file refused with the new validator wording.
- [ ] Device-only registry paths: import → early-crash ×2 → quarantine →
      byte-identical re-import rejected (org.json null-coercion paths the
      desktop JVM tests cannot see).
- [ ] Network security config change: model/driver downloads work over
      the intended endpoints and still fail closed elsewhere.

## G. Bot profiles + benchmark runners (instrumented, on device)

- [ ] `BotProfilesCatalogTest` catalog matches the on-device profile
      list shown in UI (no phantom/missing profiles).
- [ ] Run the instrumented lanes (arm64 device):
      `BaselineWorldSanityRunner`, `EngineBenchmarkRunner`,
      `BotPressureBenchmarkRunner`, `DifferentialBarrageRunner`
      (`connectedDebugAndroidTest` or the lane's runner invocation).
- [ ] `WorldConsoleRelay` passes console commands through and returns
      output during a live world.
- [ ] Differential barrage: MariaDB vs SQLite same-code comparison
      produces a VALID verdict (A=MARIADB vs B=SQLITE enforced), no
      invalid sqlite-vs-sqlite class passes.

## H. UI / tutorial / Lua surface

- [ ] First-run tutorial plays through with the new content; skip works.
- [ ] Settings screen: every new control round-trips through
      `Settings.kt` write-set (change → relaunch → persists; no
      unrelated key churned).
- [ ] AndroidPort Lua: HUD/radial/frame-mover changes behave in-game;
      new `Talk.lua` registers and routes chat without lua errors in
      the client log; radial Talk is the SEVENTH slot (help text fixed
      to match).
- [ ] FrameMover regression check (covers the tidy-up fix): with the
      Bags module off, journal a container scale via Move UI, log out
      and back in, open bags → journaled scale is reasserted after the
      stock sweep (live-frame set rebuilds at bag open).
- [ ] UI-scale managed/unmanaged transition: set a scale → `SET uiScale`
      in Config.wtf; switch to Default → the `uiScale`/`useUiScale` pair
      deleted exactly once (second launch must not re-delete); an
      in-game scale change during the final managed session survives.
- [ ] Import stall watchdog: SAF import from a cloud-backed provider
      that stalls mid-stream surfaces "The import stalled…" within ~1
      minute; a null-mime `.rar` picker case still greys correctly and
      the archive sniffer remains the validation gate.
- [ ] LAN screen and client display host changes: connect, play, rotate,
      background/foreground without input-contract regressions.

## I. Soak, thermal, battery

- [ ] 60-minute play session with LLM chatter enabled: thermal state,
      battery drain vs LLM-disabled session (`tools/battery_sampler.py`
      / `battery_window_report.py` on the pulled logs).
- [ ] 8-hour idle-with-realm soak: no wake-lock leak, no unbounded log
      growth, clean-stop honored after process death.

## Sign-off

Record device, build fingerprint, APK sha256 for both artifacts, and the
session log paths. A failed item in B or D blocks the stable APK; failed
items in C/E block only the experimental APK from being labeled non-experimental.

## Already executed: x86_64 emulator smoke (PocketDiff_x86_64 AVD, 2026-09-02)

- [x] `-PsqliteProvider -PdifferentialTestLane` x86_64 debug APK assembles
      with all lockfile/ELF closure gates green (471 MB APK).
- [x] Install + launch on the AVD: main + `:supervisor` processes come up,
      Home screen renders (Alive Realm 320 default, "Realm stopped"),
      no FATAL/crash lines in logcat; only the EXPECTED legacy-targetSdk
      dialog (targetSdk 27 is the deliberate Wine/SELinux design).
- [x] `versionName=0.103.0-alpha-sqlite-experimental` verified via
      dumpsys — the two-APK experimental labeling works on a live system.
- [ ] Realm + world boot on the emulator (needs a staged server-data /
      client import; left for the differential lane run, DEC-10).
