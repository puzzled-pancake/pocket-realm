# Current project state

Last verified implementation milestone: `O13 — G4 measured 25-playerbot tier on the large x86_64 lane` (O13 remains open for interruption/admission evidence)

Active feature: `O14 — G4 touch, gamepad, keyboard/mouse, IME, and minimal addon UX` (active)

Current gate: `G4 — bots and mobile input UX`. G0 production packaging, G1 direct-client proof, G2 native realm baseline, and G3 integrated x86 application are complete on their stated lanes; O13's 25-bot soak is qualified on the large lane but its remaining sub-acceptance paths are still open.

Plan/reference alignment: `3 August 2026`

> Note: an earlier O05 commit (27eb1ad) was reopened after a review found the
> evidence pipeline had blocking gaps (variant-mistaken host driver, PKG-01 not
> setting LD_LIBRARY_PATH, null GL strings, non-reproducible API-28 page size,
> overstated PKG-06 coverage). This commit fixes all P1/P2 findings and
> regenerates every evidence artifact through one reproducible serial+variant-
> specific driver (tools/run_pkg_experiments.py).

## Source of truth

- `docs/SPP_Classics_WoW_1.12.1_Android_Port_Report.pdf` is the canonical offline engineering reference; the adjacent DOCX is its editable source.
- `PLAN.md` is the repository execution overlay and connected-realm extension.
- `DECISIONS.md` contains adopted decisions, evidence-backed deltas, and the G0 overlay.
- `FEATURES.json` maps O05-O22 to report gates G0-G6 and named report sections.
- `docs/adr/ADR-013-g0-production-topology.md` records the G0 topology decision (Lane A).

## G0 result (O05, done)

The report's PKG-01/02/06 packaging experiments are complete on three AVD lanes:

| Lane | API | Page | PKG-01 | PKG-02 | PKG-06 |
|---|---|---|---|---|---|
| AVD-Legacy-x86_64 | 28 | 4 KB | executed + documented | CONTAINMENT_PROVEN | (research lane) |
| AVD-Modern-x86_64-v1 | 35 | 4 KB | executed + documented | CONTAINMENT_PROVEN | 30-min run |
| AVD-16K-x86_64-v1 | 35 | 16 KB | executed + documented | CONTAINMENT_PROVEN | 30-min run |

**ADR-013 confirms Production Lane A** (library-backed, supervised, fault-isolated native components in dedicated `android:process` fault domains) from this evidence. target-28 unpack/exec is explicitly not the production path. The O04 `libpocketrealm.so` in-process facade is retained as reusable library/control evidence; it is not the production world-server topology. The signed-code/mutable-data boundary: executable native code ships only via the signed APK; mutable datadir/prefix/cache/journal/database stays app-private and never executes.

## Verified work retained from O01-O05

### O01 - repository and provenance — done
Monorepo layout, pinned CMaNGOS/Playerbots/Classic-DB sources, licenses, flavor manifest, build bootstrap, proprietary-data exclusions.

### O02 - Android shell — done
Kotlin/Compose shell, navigation, settings, separated storage roots, structured logging, foreground service, notification Save & Exit action, supervisor state tests.

### O03 - native server build — done
CMaNGOS Classic and Playerbots build for x86_64 and `arm64-v8a`; 16 KB ELF alignment; x86_64 device smoke + ARM64 artifact checks pass.

### O04 - lifecycle/library experiment — done
Versioned C ABI + `libpocketrealm.so`; create/start/health/save/stop/destroy proven twice in-process; fatal-path containment. Retained as library-lane/control evidence, not the production topology (ADR-013).

### O05 - G0 production packaging — done
- **New native module `native/packaging`**: `libpocket_pkg_launcher.so` (PIE launcher, PKG-01) + `libpocketpkgtest.so` (JNI shim + dlopen-by-SONAME + deterministic abort, PKG-02/06). 16 KB-aligned; links only libc/libdl/liblog + libc++_shared.
- **Gradle packaging**: `stageNativeLibs` stages the full closure into the APK (no platform libs). Production variants `useLegacyPackaging=false` (.so stored uncompressed/page-aligned, loaded from APK); `pkgExperiment` variant `useLegacyPackaging=true` (extracted to nativeLibraryDir with +x). `aidl=true`; `INTERNET` permission added (loopback-only).
- **Kotlin**: `PkgNative` (real JNI glue), `CapabilityReport` (StorageManager.getAllocatableBytes + page size + abilist + real EGL-backed GL strings + host CPU/virt/GPU-mode), `IPkgIsolation` AIDL (cross-process :pkg binder), `PkgIsolationService` (:pkg process), `PackagingExperimentRunner` (PKG-01/02/06), `CapabilityScreen` + nav (4th destination).
- **Host tooling**: `tools/build_packaging.py`, `tools/capture_avd.py` (host-side capture+compare), `tools/run_pkg_experiments.py`.
- **Capability records**: `tests/avd/AVD-{Modern,16K,Legacy}*.json` match adb on equivalent fields (sdkInt, page size, full abilist/abilist32/abilist64, total RAM); allocatable/GL/host-CPU/virt/GPU-mode recorded separately.
- **ADR-013** confirms Lane A; DECISIONS.md G0 overlay; ABI header note updated.

## Last successful implementation checks

- `scripts/build_native.py --abi x86_64 --runtime --runtime-tests cmangos` -> ALL STAGES OK
- `tools/build_packaging.py --abi x86_64` -> libpocketpkgtest.so + libpocket_pkg_launcher.so (16 KB-aligned)
- `scripts/smoke_native.py --abi x86_64 --device --runtime` -> SMOKE OK (mangosd/realmd run, libpocketrealm.so lifecycle 2 cycles)
- `:app:assembleDebug` + `:app:assemblePkgExperiment` -> BUILD SUCCESSFUL (correct lib/x86_64/ closure, INTERNET present, zipalign -c -P 16 -v 4 passes)
- `:app:connectedDebugAndroidTest` on all 3 AVDs -> 16 tests, 0 failures (RealmSupervisor x10 + RealmServiceLifecycle x2 + PackagingExperiment x4)
- PKG-01 executed+documented on all 3 lanes (experiment variant); PKG-02 CONTAINMENT_PROVEN on all 3 lanes; PKG-06 two genuine 30-min runs (4 KB + 16 KB) captured
- Standalone `mangosd`/`realmd` remain unchanged when `POCKET_EMBEDDED` is off.

## Known gaps under the canonical report

- The `pkgExperiment` standalone-exec variant is G0 evidence only; production uses the library-backed Lane A model.
- Integrated client/account/persistence, bots, and mobile UX remain pending at their gates (O12-O22).
- The full report X/FUN/FLT/SOAK gates remain pending.

## Blockers

O06/O07/O08/O09/O10/O11 have no remaining blocker. Named physical-device inputs remain
required at later release gates. The user-owned source client remains unchanged
on `C:` and no proprietary client executables or data archives are added to Git.

## O06 Phase 1 — Wine feasibility spike (complete: Outcome B)

All three spike measurements pass on both required API-35 x86_64 lanes using
the same APK-managed runtime and mutable app-private prefix/cache model:

| Measurement | Modern 4 KB | 16 KB |
|---|---|---|
| S-1 effective loader/process tree | **PASS** `LOADER_PROVEN_VIA_PROOT_FULL_TREE` | **PASS** `LOADER_PROVEN_VIA_PROOT_FULL_TREE` |
| S-2 PE cache, repair, `wineboot --init`, prefix | **PASS** `WINEBOOT_PE_RESOLUTION_PROVEN` | **PASS** `WINEBOOT_PE_RESOLUTION_PROVEN` |
| S-3 in-app X11/GDI window | **PASS** `X11_GDI_WINDOW_PROVEN` | **PASS** `X11_GDI_WINDOW_PROVEN` |

Authoritative full-run evidence:

- `tests/avd/AVD-Modern-x86_64-v1/evidence/pkgExperiment-wine_spike-all-20260802-185902.PASS.log`
- `tests/avd/AVD-16K-x86_64-v1/evidence/pkgExperiment-wine_spike-all-20260802-185645.PASS.log`

### Findings and retained adaptations

- Direct glibc-loader execution and the Bionic trampoline are blocked by the
  Android app-domain seccomp rule for legacy `access(2)` (`SYS_SECCOMP`, syscall
  21). PRoot 5.1.107.89 with its immutable APK-managed helper loader is therefore
  required for S-1 bootstrap/process-tree proof.
- S-2 and S-3 use the glibc adapter/shim after bootstrap. PE cache staging
  materializes and verifies 1,626 modules, detects deliberate corruption of
  `kernel32.dll`, repairs it, and re-matches the canonical SHA-256 before launch.
- The apparent PE/WoW64 exit-1 was traced to Wine's x86_64 syscall stubs calling
  a per-process dispatcher pointer at `0x7ffe1000`. On a 16 KB host that address
  shares the `0x7ffe0000` `MAP_SHARED` page used by `KUSER_SHARED_DATA`, allowing
  different Wine processes to overwrite one another's ASLR-relative pointer.
- The source patch moves the dispatcher to the next private 16 KB page at
  `0x7ffe4000`, makes x86_64 host-page handling dynamic, and rebuilds the paired
  Unix `ntdll.so`, PE `ntdll.dll`, and PE `win32u.dll`. The build script verifies
  that these are the only provider PE modules containing the original stubs and
  rejects mixed old/new output.
- The Winlator-derived X server is deliberately GDI-only; its pinned native
  epoll/SCM_RIGHTS transport is built as 16 KB-compatible `libwinlator.so`.

The spike outcome is **B**: the fixed AVD route is feasible, but a documented
fallback/adaptation path is required. Outcome A is unavailable because direct
loader/trampoline execution is disproven. Outcome C is disproven by the paired
4 KB/16 KB passes.

## O06 full implementation — complete

The qualified fallback is now behind the report's `ClientRuntime` contract and
a non-exported `ClientRuntimeService` in the dedicated `:client` process. The UI
process owns the Winlator-derived X transport, actual `XServerView` surface, and
letterbox-aware input bridge; the service owns the fixed self-test executable,
Wine process group, prefix lifecycle, session tokens, and bounded diagnostics.

- `clientRuntime` is the selected extracted-native O06 product lane. O05's
  `debug`/`release` controls retain non-legacy packaging.
- Runtime code remains APK-managed. Mutable state lives below
  `noBackupFilesDir/wine/`, is versioned, and has declared 768 MiB active-prefix,
  768 MiB single rollback-prefix, 768 MiB cache, and 4 MiB diagnostic quotas.
  Compatible relaunch preserves prefix writes; an incompatible prefix is kept
  as the one bounded rollback generation before replacement.
- The project-owned 32-bit PE visibly paints the app surface and records focus,
  keyboard press/release, transformed mouse press/release, and a true audio-off
  path that skips device initialization.
- Clean close is token-scoped and becomes `WM_CLOSE`. Forced stop cancels the
  active Wine process group; the AIDL service accepts no arbitrary executable,
  prefix path, or environment.

Final paired evidence:

- `tests/avd/AVD-Modern-x86_64-v1/evidence/clientRuntime-o06-lifecycle-20260802-080207Z.PASS.log`
- `tests/avd/AVD-Modern-x86_64-v1/evidence/clientRuntime-o06-surface-20260802-080207Z.png`
- `tests/avd/AVD-16K-x86_64-v1/evidence/clientRuntime-o06-lifecycle-20260802-080252Z.PASS.log`
- `tests/avd/AVD-16K-x86_64-v1/evidence/clientRuntime-o06-surface-20260802-080252Z.png`

## O07 build-5875 result — complete

The user-owned WoW 1.12.1 source tree was inspected read-only and
identified as PE32 i386 `WoW.exe` version `1.12.1.5875` (SHA-256
`b4756d38ef207c02ed651f4952bd89a70b4857b73a33413339e1b285b28d2dc7`),
149 files / 5,389,935,386 bytes, with the required flat English MPQ layout.
The source realmlist was not changed and the source is not a runtime dependency.

- `SafClientScanner` performs the Android read-only fast scan over a selected
  document tree. Four on-device tests cover supported build/layout, wrong
  build, launcher-only selection, and corrupt MPQ rejection. A confirmed build
  with an unknown executable hash remains a warning rather than being silently
  rejected.
- `tools/stage_o07_client.py` provides the report-authorized managed debug-copy
  path. It validates paths/case/PE/MPQ/layout, stages with `.partial` + rename,
  journals resumable copies by source hash (not size alone), re-verifies every
  device file, and atomically publishes an app-private generation. Only that
  generation receives the loopback realmlist and deterministic safe profile.
- `ManagedClientStore` fails closed on manifest, PE/build, executable size/hash,
  base-data, symlink, and endpoint checks. The service accepts the fixed client
  ID only and launches direct `WoW.exe`; arbitrary paths, launchers, DLL
  injection, and caller-supplied environments remain unavailable.
- O07 adds the source-pinned x86_64 Gladio `libGL.so.1` client and paired
  Winlator GLX server. The qualified profile reports OpenGL 3.0 / GLSL 1.30,
  retains WineD3D's internal-format queries, and omits modern draw capabilities
  the GLES 3.1 bridge cannot preserve. Shaders target GLES 3.1 (`#version 310
  es`), transient client arrays use explicit bounded attribute records, and
  the Android compositor uses readback/upload because the fixed AVD does not
  implement `glCopyTexImage2D` for this shared-context path.
- The strict API-35 x86_64 4 KB acceptance test reaches the visible build-5875
  login screen at 800x600 (within the canonical 1280x720-or-lower ceiling), 30
  FPS, audio off, and loopback/no-server configuration. It reads the renderer
  framebuffer on its GLES thread, records 319,606 non-black pixels on first
  launch and 321,732 after a clean stop/relaunch, and rejects a mapped black
  surface.

Authoritative O07 evidence:

- `tests/avd/AVD-Modern-x86_64-v1/evidence/client-build5875-scan-20260802.PASS.json`
- `tests/avd/AVD-Modern-x86_64-v1/evidence/clientRuntime-o07-login-20260802-1218Z.PASS.json`
- `tests/avd/AVD-Modern-x86_64-v1/evidence/o07-login-first.png`
- `tests/avd/AVD-Modern-x86_64-v1/evidence/o07-login-relaunch.png`

## O08 — isolated MariaDB database runtime (complete)

- MariaDB 11.5.2 is source-built for native x86_64 Linux/glibc from the
  hash-pinned official archive and Termux recipe/toolchain. The installed
  server/client closure is 16 KB compatible, has no leaked builder RPATH, uses
  the O06-qualified loader/libc pair, and packages no optional MariaDB plugin.
- The non-exported `:database` process exclusively owns its no-backup datadir.
  Its fixed Binder contract accepts no SQL, path, executable, environment, or
  credential. MariaDB binds only the app-private Unix socket with networking
  disabled in both config and argv.
- Initialization uses the ordered upstream system-table bootstrap, independent
  random admin/core credentials, per-schema least privilege, an authenticated
  query, and a denied account-administration operation. A clean marker is
  written only after authenticated shutdown, zero process exit, and socket
  removal.
- The pinned CMaNGOS, Classic-DB, and Playerbots inputs produce 399 ordered,
  deterministic gzip assets. Every compressed and SQL stream is hash-checked;
  the database ledger records source/target revisions, status, timestamps,
  snapshot/build IDs, and result digests. Classic-DB's own core-sync handoff is
  derived from its pinned SQL before newer CMaNGOS updates are selected.
- The fixed API-35 x86_64 4 KB lane passed the full test twice. The recorded run
  took 139.399 seconds and proved all migrations, exact revision checks plus a
  rejected mismatch, preflight storage-full refusal, stopped-state snapshot
  and restore, clean lifecycle, dirty kill, observed recovery output,
  authenticated post-recovery query, and final clean stop.

Authoritative O08 evidence:

- `tests/avd/AVD-Modern-x86_64-v1/evidence/databaseRuntime-o08-acceptance-20260802-1915Z.PASS.json`
- `schemas/mariadb-runtime-lockfile.json`
- `schemas/database-migrations.json`
- `docs/adr/ADR-015-o08-database-runtime.md`

## O09 — isolated native realmd/world runtime (complete)

- Pinned CMaNGOS `realmd` and world are source-built as Android/Bionic x86_64
  libraries with 16 KB ELF alignment and only Android/NDK runtime dependencies.
  Playerbots, the deprecated playerbot path, and AHBot are compile-time off.
- Non-exported `:realm` and `:world` services expose a fixed versioned
  Binder/JNI/C contract for readiness, account creation, save, stop, heartbeat,
  tick metrics, and bounded error classification. Paths, SQL, bind settings,
  executables, environment, and credentials are not caller-controlled.
- The read-only user-owned build-5875 source produced 158 DBC and 2,429 map
  files (178,365,278 bytes). Every output is hashed and atomically published to
  app-private storage; no client-derived data is tracked or bundled.
- O09 appends the mandatory CMaNGOS DBC/fix/ScriptDev2/ACID layers without
  renumbering O08's 399 existing migration IDs, bringing the deterministic
  ledger to 410. This repairs the full snapshot's older `spell_template` using
  the pinned core's official 159-field DBC SQL.
- Clean world shutdown acknowledges save/teardown, writes the clean journal,
  and retires the dedicated process. A subsequent bind receives a fresh
  CMaNGOS process generation; uncontrolled deaths remain dirty.
- The API-35 x86_64 4 KB lane passed the complete test in 61.009 seconds: 20
  clean realm cycles, 11 rejected control fuzz cases, account/save/clean stop,
  controlled world death and recovery, controlled MariaDB death with recovery
  output, and a final dependency-ordered clean cycle. Concurrent host evidence
  observed only `127.0.0.1:3724` and `127.0.0.1:8085` in LISTEN state.

Authoritative O09 evidence:

- `tests/avd/AVD-Modern-x86_64-v1/evidence/realmRuntime-o09-acceptance-20260803.PASS.json`
- `schemas/realm-runtime-lockfile.json`
- `schemas/database-migrations.json`
- `docs/adr/ADR-016-o09-native-realm-runtime.md`

## O10 — durable RuntimeSupervisor and ownership (complete)

- The foreground, non-exported `:supervisor` process is now the only durable
  lifecycle authority. A pure Kotlin core enforces dependency-gated database ->
  realmd -> world -> client transitions and rejects PID-only readiness.
- Every runtime generation is tied to a UUID session, independent 256-bit
  component token, and live Binder owner lease. Component processes safely
  tear down dirty when that lease dies. Recovery observes first and withholds
  every stop/kill whose live ownership does not match the journal.
- The schema-2 mode-0600 journal uses temp write, file fsync, same-directory
  atomic rename, and directory fsync. Clean is committed only after client stop,
  world save acknowledgement, world stop, realm stop, and database stop.
- Bounded start/stop/recovery sections hold a partial wake lock. Client failure
  is isolated and relaunchable in the state-machine contract; database, realm,
  and world failures are realm-fatal. O12 remains responsible for connecting
  this lifecycle to the qualified O07 client/display session.
- The API-35 x86_64 4 KB device test passed in 33.310 seconds: fresh readiness,
  client-failure isolation, clean stop, deliberate supervisor death with
  owner-loss teardown and fresh-session recovery, owned world death with dirty
  realm-fatal classification, recovery, and final clean journal.

Authoritative O10 evidence:

- `tests/avd/AVD-Modern-x86_64-v1/evidence/runtimeSupervisor-o10-acceptance-20260803.PASS.json`
- `docs/adr/ADR-017-o10-durable-runtime-supervisor.md`

## O11 — resumable managed-client import and prepared data (complete)

- The `:import` foreground process keeps only a persisted SAF read grant. It
  fast-classifies direct x86 PE32 build 5875 before bounded NFKC/case-fold-safe
  traversal and report-formula storage preflight; the source tree is never a
  runtime dependency and receives no write operation.
- The schema-2 SQLite WAL/FULL journal records each file's expected metadata,
  partial, SHA-256, attempt, error, and fsync state. Resume rehashes verified
  files, repairs corruption, and republishes only through a fsynced manifest,
  generation rename, and digest-bearing active pointer. Re-import is a new
  generation; current + previous retention is device-tested and bounded.
- Ten forced `:import` deaths pass on API-35 x86_64 4 KiB and 16 KiB lanes,
  including before-publish and after-rename/before-activation crash windows.
  Wrong builds remain rejected and no incomplete generation becomes active.
- Four fixed-purpose Bionic PIE extractors are source/patch/hash pinned and
  16 KiB aligned. Real-client diagnosis found upstream's non-NUL-terminated MPQ
  listfile parser; the external bounded parser patch removed the SIGSEGV and
  resumed over the existing staging work.
- The real 149-file, 5,389,935,386-byte build-5875 source completed on the
  large-storage API-35 4 KiB lane. Its NORMAL generation has 158 DBCs, 2,429
  maps, 43 VMAP trees, 1,249 VMAP tiles, 22 MMAP maps, 1,815 MMAP tiles, and
  9,204 hash-recorded files totaling 2,280,526,960 bytes. The active pointer
  matches manifest SHA-256
  `be6478859781dd8cda5e85cd47d98ae84b66216a6f392f616a07b89ba482e41c`.
- `PreparedDataStore` verifies the active digest, identity/counts, safe paths,
  sizes, and every file hash before `worldConfigNormal()` enables VMAP/MMAP.
  Missing or damaged data fails closed; O09's no-navigation baseline remains an
  explicit non-normal test path until O12 integration.

Authoritative O11 evidence:

- `tests/avd/AVD-Modern-x86_64-v1/evidence/managedImport-o11-interruptions-20260803.PASS.json`
- `tests/avd/AVD-16K-x86_64-v1/evidence/managedImport-o11-interruptions-20260803.PASS.json`
- `tests/avd/O11-Large-x86_64/evidence/managedImport-o11-real-build5875-20260803.PASS.json`
- `docs/adr/ADR-018-o11-managed-client-import.md`

## O12 — integrated x86 application (complete on the 4 KiB qualification lane)

- The O10 supervisor now owns the qualified Wine client/display session. One
  instrumented action provisions a local account through core control, launches
  the managed build-5875 client, creates a character, and enters the local world
  without placing plaintext credentials in logs.
- The API-35 x86_64 4 KiB lane passed a 1,800-second active zero-bot soak with
  31 one-minute samples. Every sample retained one online player and one active
  session, the world tick count advanced from 2,579 to 37,404, and the rendered
  frame retained at least 128 distinct colors. A harmless jump pulse exercises
  the real Android-to-X11-to-Wine input path and prevents the server's normal
  15-minute AFK logout from invalidating an active-play qualification.
- Clean client exit, database-consistent backup, restore into a fresh datadir,
  20 clean cycles, and forced world-death recovery all preserve durable SHA-256
  `14151106a87a1aa8a994c98e5e32f8cf0de769a78ba8ae9721b1431d2adb7aee`.
  The support bundle passes secret-redaction canaries and retains exact runtime
  identities. Five focused client relaunches also retain renderer readiness and
  non-black WineD3D presentation.
- This closes O12/G3 on the strategic 4 KiB lane. It does not claim an O12
  integrated run on the 16 KiB AVD; that repeat remains part of O20/G6 modern
  Android and page-size release qualification.

Authoritative O12 evidence:

- `tests/avd/AVD-Modern-x86_64-v1/evidence/o12-integrated-runtime-20260803.PASS.json`

## O13 — G4 measured 25-playerbot tier (complete on the large-memory lane)

- The pinned Playerbots module (commit `1abeac64...`) is now compiled into the
  Android x86_64 world runtime (`libpocket_world_runtime.so`, sha256
  `f8005881...`, recorded in `schemas/realm-runtime-lockfile.json`) while
  remaining disabled unless an explicit measured profile is supplied. AHBot is
  excluded at the core build target and its config is omitted. The zero-bot
  O09/O12 baseline stays selectable and its tests now assert
  `compiledPlayerbots=true && playerbotsEnabled=false`.
- A measured `mobile-low-b1-25-v1` profile owns a fixed 25-bot selected / 20-bot
  minimum tier, 3 `PRB13` accounts, bounded login/generation batches, and an
  admission controller with a 768 MiB free-memory floor, a 2 GiB free-storage
  floor, a 250 ms world p99 budget, a 3-minute startup warmup, and bounded
  reduce/increase ramps. Generation is resumable and range-safe: it persists
  each character then yields after a 5-character batch so an interrupted run
  resumes from existing rows rather than duplicating ranges. A first-world-tick
  deadlock traced to a Playerbots-login database lock inversion is fixed by an
  overlaid `SqlOperations.cpp` that executes async result callbacks outside the
  result-queue mutex.
- The formal two-hour SOAK-25 passed on lane `AVD-Large-x86_64-v1` (physical AVD
  `O11-Large-x86_64`, emulator-5556: API 35, x86_64, 4 KiB page, 8 GiB RAM,
  30 GiB data). Run `7304.181 s` with `7200 s` measured across 719 10-s
  samples: `effectiveTarget` stayed 25 with 0 adaptations; `botsOnline` 24..26
  (>=20 throughout; `GetPlayerbotsAmount()` counts bots only, not bots+players;
  the observed 26 was one bot above the configured/effective target and
  `MaxRandomBots` value of 25; the current formal test tolerates it because the
  per-sample assertions are `botsOnline>=20` and `effectiveBotTarget in 20..25`
  and do not assert `botsOnline<=25`; evidence shows the overshoot was transient
  and caused no adaptation, performance failure, population instability, or
  acceptance failure); tickCount advanced to 140011; p50 1..3 ms, p95 3..5 ms,
  p99 4..7 ms with 0 samples >250 ms; 0 p99 violations; 0 hard-stall intervals;
  PSS 938..1253 MiB; freeMemory 5380..5736 MiB (>=768 floor); freeStorage
  7901 MiB (>=2048 floor); thermal `none` throughout. The verified O11
  generation (`8d174e77-...`, manifest `be647885...`) and the completed
  Playerbots equipment/item caches were reused; no regeneration. Shutdown was
  clean and dependency-ordered (world/realm/db clean stop).
- **Lane limitation (explicit):** this qualifies the 25-bot tier only on the
  8-GiB/30-GiB large-memory profile. It does NOT prove the 25-bot tier on the
  existing 2.5-GiB/10-GiB `AVD-Modern-x86_64-v1` profile, which remains
  separately unsupported for O13 until qualified. 50/100-bot and AHBot tiers
  remain unqualified Advanced profiles and were not attempted.

Authoritative O13 evidence:

- `tests/avd/AVD-Large-x86_64-v1/evidence/o13-bot-tier-soak25-20260805.PASS.json`
- `tests/avd/AVD-Large-x86_64-v1/evidence/o13-bot-tier-soak25-20260805-run.log`
- `tests/avd/AVD-Large-x86_64-v1.json` (lane capability record, physical AVD `O11-Large-x86_64`)
- `tests/avd/AVD-Large-x86_64-v1/evidence/o13-bot-tier-smoke-20260805.PASS.json` (bounded smoke)
- `tests/avd/AVD-Modern-x86_64-v1/evidence/o13-bot-tier-smoke-20260805.PROVENANCE.md` (corrects the earlier smoke path's physical lane identity)

### O14 increment 1 — versioned input contract, right/middle/wheel/relative (2026-08-05)

A versioned `InputContract` v1 (`android/app/src/main/java/com/pocketrealm/client/InputContract.kt`)
now owns all pressed state between Android events and the existing in-process
winlator `XServer` injection methods. It stamps every event with the active
display/client `generation` (one per `ClientDisplayHost` instance), rejects
stale-generation input before injection, preserves per-source DOWN-before-UP
ordering, drops unmatched UPs without synthesizing phantom DOWNs, and provides
one deterministic `releaseAll` exit path (right → middle → left buttons, then
held keys in keycode order). A default versioned `InputProfile` (v1, default
only, no on-disk persistence) carries an aspect identity and resets to default
on mismatch. `ClientInputBridge` is now a thin Android→contract adapter that
preserves the verified O06/O12 letterbox transform and left-button/keyboard
forward paths exactly.

Right-button, middle-button, vertical/horizontal wheel pulses, and relative
pointer motion are wired through the contract and the existing XServer
primitives (`injectPointerButtonPress/Release`, `injectPointerMoveDelta`,
`BUTTON_SCROLL_*`). The project-owned Win32 self-test PE
(`runtime/wine-x86_64-wow64/selftest/pocket_selftest.c`) was extended with
bounded `WM_RBUTTONDOWN/UP`, `WM_MBUTTONDOWN/UP`, `WM_MOUSEWHEEL`, and
relative-motion (`WM_MOUSEMOVE` delta) diagnostics.

Verified on `AVD-Large-x86_64-v1` (`O11-Large-x86_64`, emulator-5556): the
instrumentation test drove each new input through Android → contract → X →
Wine → the Win32 probe and observed `rightButtonSeen`, `middleButtonSeen`,
`wheelSeen`, and `relativeMotionSeen` all true. Stale-generation rejection was
confirmed (`rejectedStaleEventCount=1`). Holding right-button + a key and
triggering focus loss released both deterministically
(`lastReleaseReason=FOCUS_LOSS`, 1 button + 1 key). Existing keyboard,
absolute-pointer, left-click, focus, audio-off, and clean-close behavior is
preserved (O06 `ClientRuntimeLifecycleTest` PASS). 19 host-JVM unit tests cover
generation gating, stale rejection, button tracking, wheel atomicity, release
ordering, source isolation, and profile reset. O14's IME increment now has a
production-attached `ClientImeView`/`BaseInputConnection`, a visible Keyboard
affordance, editor-action/delete routing, bounded rejection feedback, and
pointer suppression while the IME is active. The real InputConnection path
still needs a fresh on-device run; the checked-in 2026-08-05 evidence bypassed
that path and is not reused as acceptance evidence. Gamepad, pointer capture,
profile persistence, and the default touch overlay remain open; UX-T01 through
UX-T08 are NOT complete. The tagged O13 boundary (`o13-soak25-qualified` →
`a833c40`) and the qualified runtime hash (`f8005881…`) are unchanged.

Authoritative O14 increment-1 evidence:

- `tests/avd/AVD-Large-x86_64-v1/evidence/o14-input-contract-increment1-20260805.PASS.json`
- `tests/avd/AVD-Large-x86_64-v1/evidence/o14-input-contract-proof-20260805.png`
- `tests/avd/AVD-Large-x86_64-v1/evidence/o14-profile-persistence-20260808.PASS.json`
- `tests/avd/AVD-Large-x86_64-v1/evidence/o14-input-contract-20260808.PASS.json`
- `tests/avd/AVD-Large-x86_64-v1/evidence/o14-relaunch-20260808.PASS.json`
- `tests/avd/AVD-Large-x86_64-v1/evidence/o14-ime-20260808.PASS.json`

### O14 increment 2 — gamepad, pointer capture, persisted profile, and touch overlay (implementation complete; device proof open)

The UI input path now includes a versioned app-private `InputProfileStore` with
aspect-aware reset, gamepad button/axis translation (dead-zone WASD and relative
camera deltas), Android pointer capture for physical mouse camera-look, and a
hideable Compose touch overlay with movement, action-bar, chat, and mouse-mode
controls. All paths share the existing generation-gated `InputContract`, source
release ordering, IME suspension, and lifecycle cleanup. Host tests cover axis
crossing, camera deltas, button mapping/hot-unplug release, and profile JSON
round-trip/migration. A fresh O11-Large-x86_64 run also passes the profile
save/load and aspect-reset instrumentation check; physical gamepad/mouse and
touch-only FUN-008/FUN-009 evidence remain open.

## Next action

O14 implementation increments 1 and 2 are complete in source and host tests.
Fresh clientRuntime evidence now passes the attached IME InputConnection path,
input-contract mapping, generation replacement, and profile save/load/aspect
reset. The remaining device run must cover physical gamepad hot-plug, mouse
pointer capture, overlay hide/show, orientation/resume, and the complete
touch-only UX-T01–T08 sequence. UX-T01 through UX-T08 are NOT complete and O14
remains `pending` until those artifacts exist. Do not start O15 or any later
gate.

## Session note

Replace this file with the current verified state; do not append a permanent diary. Git history is the durable record.
