# De-Vibe-Coding Plan — pocket_realm_complete (v2, verification-passed)

Date: 2026-08-16. This is revision 2: the original plan was re-audited by eight parallel verification agents (four fact-checking every finding against the code, three auditing previously-lightly-covered areas — vendored Java X-server, Lua addons, Gradle/schemas — and one reviewing the plan itself for soundness). All corrections are integrated; a round-2 changelog is in Appendix C. Method: file:line-verified findings against working tree at commit `58f8289`.

Repo facts (verified): 133 commits, 1307 tracked files, 218.3 MB tracked content, no remote configured, **12 local branches**, plus **a linked git worktree at `C:/pocket_realm_winlator_vortek`** on `codex/winlator-vortek-system-port` (matters for the history rewrite). `.git` ≈ 200 MiB loose objects (never packed) + 6 garbage `tmp_obj_*` files. Submodules clean-pinned: `native/cmangos` @ `c096bada`, `native/playerbots` @ `1abeac64`, `native/classic-db` @ `be1a5206`.

---

## 0. Executive summary

**Verdict:** the engineering core is *unusually disciplined for vibe-code* — no leaked credentials (verified across tracked files and all-branch history), loopback-only binds, SecureRandom DB secrets, hash-pinned dependencies, fail-closed Kotlin style, zero TODO markers, real tests. The de-vibe problems concentrate in five areas:

1. **The repo itself is the landfill.** ~180 MB of committed binaries: 51 `.codex-*` agent-debris files (106.4 MB: 43 screenshots, 6 UI dumps, 1 txt, and an 8.9 MB copy of the proprietary WoW 5875 client exe — UTF-16-mangled and non-runnable, but containing the proprietary content, in violation of the repo's own rule #1), a complete committed MSVC build output tree (146 files, 9.3 MB), ~66 MB of vendored dxvk/turnip/gladio tarballs + extracted DLLs (**confirmed orphaned — nothing references them; the real pipeline uses `native/.providers-extracted/`**), and a 366 MB `GL calls testing/` directory that is the historical staging origin of code now canonical in-tree.
2. **No CI, no lint, and a Gradle file with its own vibe-code problems** (1444 lines): a `contains("lint")` task-name hack, a configuration-cache contradiction that can silently disable the connected-test safety gate, four config-identical custom variants, and verifier tasks that re-hash ~100 MB of artifacts on every compile/lint/merge.
3. **Copy-paste as architecture.** 19 files in the Kotlin main source set hand-roll SHA-256 (32 instantiation sites); 21 Python files redefine `sha256`; a 1500-line experiment harness ships in the production source set (and a second 422-line one is reachable from shipped UI); two generations of the embedded-server runtime coexist; the Vortek renderer exists as **two full trees of which the hardened one does not ship** — the APK packages the unbounded upstream decoder.
4. **A handful of real bugs**, now all re-verified: unbounded joins/waits in production paths, a guaranteed use-after-free on every successful realmd start, `waitpid(-1)` child-stealing plus a PID-reuse kill hazard, blind-offset binary patching without original-byte validation, a broken `Path("")` SDK-fallback idiom in 6 Python scripts, and — found in round 2 — an off-by-one in the addon's 0.5.x→0.6.0 journal migration that silently loses users' frame positions.
5. **Personal info in tracked files** (machine fingerprint via CMakeConfigureLog.yaml, wireless-ADB device serial in 4 files, LAN IP, game account/character names in more places than round 1 found) and plaintext WoW-account credentials embedded in export ZIPs.

The plan is ordered so zero-risk mechanical wins come first, the safety net (CI) comes before refactoring, and every code-touching phase has a verification gate.

---

## 1. Findings catalog

Severity: **P0** licensing/security/privacy, **P1** repo-integrity or correctness bugs that can eat data/hang the app, **P2** structural debt, **P3** polish. Round-2 corrections are applied inline; items marked **[R2]** were found or materially revised in the verification pass.

### 1.1 Licensing & privacy (P0)

| # | Finding | Location |
|---|---------|----------|
| L1 | Copy of the proprietary WoW 1.12.1 client committed (build 5875; UTF-16-mangled — first bytes `FF FE`, not runnable — but contains `World of WarCraft (build 5875)` strings and proprietary content; dot-prefix evades `.gitignore`'s `**/WoW*.exe`; violates DECISIONS.md #1) | `.codex-wow-5875.exe` (8.9 MB, tracked) |
| L2 | CMake configure log commits full machine fingerprint: 390 `C:\Users\David` matches plus Ollama/LM Studio/miniconda/.cargo/.codex inventories | `native/xserver-winlator/cpp/vortekrenderer/tests/build-authority/CMakeFiles/CMakeConfigureLog.yaml` |
| L3 | Personal wireless-ADB TLS serial in exactly 4 tracked files | `tools/install_rp6_when_available.ps1:10`, `GL calls testing/CONTEXT_NOTES.md:65`, `GRAPHICS_SELECTION_CONTEXT.md:127`, `docs/L1_NEARBY_USE_INVESTIGATION.md:5` |
| L4 | Home LAN IP `192.168.1.x` | `GL calls testing/CONTEXT_NOTES.md:237` |
| L5 | Game account name and character name — **[R2]** in 4 files, not 1: the investigation doc **plus** `android/app/src/test/java/com/pocketrealm/ui/InGameSettingsContractTest.kt:86,89,90` and `docs/INGAME_SETTINGS_GROUND_TRUTH.md:5` | see listed files |
| L6 | ~60 live-device screenshots / UI dumps committed at root and in `GL calls testing/` | `.codex-*.png/.xml`, `GL calls testing/` |

### 1.2 Committed junk & build artifacts (P1 for repo integrity)

| # | Finding | Location |
|---|---------|----------|
| J1 | 51 tracked `.codex-*` files, 106.4 MB (43 png, 6 xml, 1 txt, 1 exe) | repo root |
| J2 | Complete MSVC build output committed: 146 tracked files, 9.3 MB (69 `.tlog`, 10 `.pdb`, 6 `.exe`, 5 `.ilk`, `.vcxproj`, `CMakeCache.txt`, `CMakeFiles/`, `Debug/`) | `native/xserver-winlator/cpp/vortekrenderer/tests/build-authority/` |
| J3 | Vendored binaries, **confirmed orphaned** (zero references repo-wide; provisioning actually flows through `native/.providers-extracted/` + `tools/stage_renderer_packages.py`, which even synthesizes the ICD JSONs): dxvk tar 24.6 MB + 10 extracted DLLs (~25 MB) + turnip tar 15.4 MB + turnip ICD json **[R2]** + `native/.tmp-winlator-gladio.tar` (0.5 MB) + `native/.tmp-winlator-gladio/usr/lib/libGL.so.1.7.0` | `native/.tmp-*` |
| J4 | `GL calls testing/` — 65 tracked files (~10 MB tracked, 366 MB on disk): research notes worth keeping, plus binaries (`eglprobe`, `installed.apk`, zips), screenshots, logs; **[R2]** its `webagent-attachments/*.c` are byte-identical to in-tree sources and its phase2/3 C sources SHA-match the pinned outputs of `tools/build_gladio_client.py` — i.e., regenerable duplicates | repo root |
| J5 | Tracked root strays: `vc140.pdb`, `after-back.png`, `gladio_chip_zoom.png`, `reason_text*.jpg`, `rp6screen.png`, `verify-home.png` (+ untracked `rail.png`, `test-home.png`, `top-content.png`) | repo root |
| J6 | Accidental dirs: empty `New folder/`, `fixed/` (1 planning doc); **[R2]** empty `runtime/{box64,fex,providers}` `.gitkeep` placeholders while PLAN.md describes them as populated | repo root, `runtime/` |
| J7 | `.tmp/` (2.6 GB incl. a 1.4 GB realm backup) not gitignored; `tmp/` (2.3 GB) ignored by design | repo root |
| J8 | `.git` never gc'd; 6 garbage `tmp_obj_*` files; **[R2]** a linked worktree exists at `C:/pocket_realm_winlator_vortek` — filter-repo cannot run safely with it present | `.git/` |
| J9 | Dead layout: root `addons/` = 3 `.gitkeep`; real addon lives in android assets | `addons/` |

### 1.3 Android app — security & correctness (P1)

| # | Finding | Location |
|---|---------|----------|
| A1 | WoW account password persisted as plaintext JSON (chmod 600 + fsync, but unencrypted) | `supervisor/UserAccountStore.kt:67,84` |
| A2 | Export ZIP embeds `account.json` (plaintext credentials) by design; exclusion is a one-line change at the call site (`SettingsScreen.kt:144` passes the file; `RealmDataArchive.kt:22,56-60` appends it) | `ui/SettingsScreen.kt:143-156`, `ui/RealmDataArchive.kt` |
| A3 | **[R2 — scope widened]** Ungated test-kill/test-mutator Binder APIs in **five** services, not one: `RealmService.forceComponentForTest`/`killSupervisorForTest` (:266-284), `RealmRuntimeService.killForTest` (:68-71), `WorldRuntimeService.killForTest` (:207-213), `DatabaseService.killForTest` (:51→`DatabaseEngine.kt:367`), plus ungated `snapshotAndRestoreTest` (:53) and `storageFullTest` (:60). Only `ImportWorkerService.kt:39` gates with `BuildConfig.DEBUG` | see listed files |
| A4 | Hidden cache-file audio switch — **[R2]** already inside `if (BuildConfig.DEBUG)` at :469; only *removal* is warranted, not gating | `client/ClientDisplayHost.kt:469-474` |
| A5 | Unbounded `while(true)` polling without deadline — **[R2]** six sites, not four: `waitReady` (:392-397) and client-start (:623-639) in AndroidRuntimeBackend, `awaitBackupCompletion` (SettingsScreen:114-122), ClientScreen:89+332, **plus DiagnosticsScreen.kt:65 and InGameBindingsScreen.kt:83** | see listed files |
| A6 | Unbounded lease-acquire retry — **[R2]** spins every **25 ms** (`ACQUIRE_RETRY_MS = 25L` at :257), not 250 ms; reached from the launch Binder path | `client/WineRuntimeStore.kt:257,267-273` |
| A7 | `Thread.sleep(100)` poll while holding `synchronized(lock)` on a Binder thread, up to 45 s | `database/DatabaseEngine.kt:293` |
| A8 | `runBlocking` disk I/O during UI composition | `bots/BotCustomPresets.kt:20` via `ui/BotsScreen.kt:124-129` |
| A9 | `runBlocking` on DataStore in prefix prep; on Binder threads in RealmService (:232,242); **[R2]** third site `bots/BotPresetStore.kt:302` | see listed files |
| A10 | `Thread.sleep` inside `Dispatchers.IO` coroutines (6 sites in WineSpikeRunner: 369,402,429,457,1138,1314 — these should be `delay`); **[R2]** WorldRuntimeService/RealmService sleeps live in raw threads where `delay` doesn't apply — restructure, not sed-replace | see listed files |
| A11 | 35 lines / 37 instances of `!!` in main; worst clusters defeat their own null-safety | `AddonRepository.kt:398,400`, `ClientRuntimeService.kt:73,230,276,333,421,924,993`, `WineRuntimeStore.kt:1150,1319`, `AddonRuntimeProjector.kt` ×4 |
| A12 | Silent settings loss: every persisted-value parse failure falls back to defaults, zero logging | `storage/Settings.kt:522-524,569-603,690-697` |
| A13 | Swallowed catches; `readAsset` failure collapsed to `""` then code proceeds | `wine/WineSpikeRunner.kt:848,1217,1249,1267,1467` |
| A14 | Non-fsync'd temp+rename "atomicWrite" copy-pasted (7 sites across 6 files **[R2]**); fallback rename result dropped | `WineRuntimeStore.kt:2223-2227`, `ClientRuntimeService.kt:996`, `ImportProcessMetrics.kt:82-84`, `PkgRunIds.kt:39-41,60-61`, `CapabilityReport.kt:205-207` |
| A15 | Unchecked `mkdirs()`, ignored `deleteRecursively()`/`renameTo()` | `ClientRuntimeService.kt:993-996`, `WineRuntimeStore.kt:541-543,2087,2194` |
| A16 | Inline device-specific hacks: RP6 controller fingerprints, `Build.MODEL == "Retroid Pocket 6"` | `client/ClientDisplayHost.kt:1417-1423`, `client/ClientRuntimeService.kt:575` |
| A17 | Ports 3724/8085 defined twice; `"bots"` path literal ×5; last-session path ×2 | `server/ServerRuntimeContract.kt:7-8` vs `supervisor/RuntimeTopology.kt:29-30` |
| A18 | `Runtime.getRuntime().exec("kill","-9",...)` — PATH-dependent on Android | `wine/WineSpikeRunner.kt:650-652` |
| A19 | `targetSdk = 27` (documented Wine/execmod trade-off; never Play-distributable as-is); release not minified; inert ProGuard config | `android/app/build.gradle.kts:706-714,740-746` |
| A20 | Only unguarded `settings.update` in BotsScreen (other four are `runCatching`-wrapped) | `ui/BotsScreen.kt:567` |
| A21 | **[R2]** Second experiment harness in production, reachable from shipped UI: `pkg/PackagingExperimentRunner.kt` (422 lines, println at :333, own process launching) is instantiated by `ui/CapabilityScreen.kt:51`, a real nav destination (`PocketRealmApp.kt:226`) | `pkg/`, `ui/CapabilityScreen.kt` |
| A22 | **[R2]** Crash bomb ships in release and is reachable from production UI: `PkgNative.crashNative` (NULL-deref/recursion) is called by `PackagingExperimentRunner:202` via CapabilityScreen; `build.gradle.kts:265` makes `libpocketpkgtest.so` a required closure item on the main sourceSet — all build types package it (crash confined to the isolated `:pkg` process, but the trigger is user-reachable) | `native/packaging/src/crash.c:10-24`, `build.gradle.kts:265` |

### 1.4 Android app — structure & duplication (P2)

| # | Finding | Location |
|---|---------|----------|
| S1 | 1500-line experiment harness in production source set; **[R2]** the Kotlin class has exactly one external reference (`androidTest/wine/WineSpikeTest.kt:34`) so moving it to androidTest is clean — but `WineSpikeNative.kt`/`libwine_spike.so` are production-load-bearing (see Decision 3) | `wine/WineSpikeRunner.kt` |
| S2 | 2228-line god class (path planning + prefix init + 3 renderer installers + binary patcher + attestation + Config.wtf + quota pruning + symlinks) | `client/WineRuntimeStore.kt` |
| S3 | **[R2 — corrected counts]** 19 files in main hand-roll SHA-256 (32 `MessageDigest.getInstance("SHA-256")` sites); 23 files incl. tests. Consolidation target: one streaming implementation in `fs/Digests.kt` | app-wide (full list in round-2 notes) |
| S4 | Duplicated algorithms: prefix-stability poll ×2, symlink manifest walk ×2, asset extract ×2, diagnostics JSON ×2, timing tables ×2, `withBehaviorPreset` ×2 | see round-1 audit §4 |
| S5 | Dead code — **[R2]** refined: `verifyLoaderChain` and `FpsProfile` fully dead (0 refs); `userSelectable()`/`forRequestedTarget()` test-only; `BOT_LIVELY_700_PROFILE` dead but **`BOT_LOW_25_PROFILE` is used by androidTest (O13LiveBootTest, O13BotTierTest) — keep or migrate tests first** | `BotProfiles.kt:1038-1042`, `WineSpikeRunner.kt:172-175`, `Settings.kt:167`, `AndroidRuntimeBackend.kt:878-879` |
| S6 | Bot-profile archaeology: adv1–adv4 codecs confirmed (decode chain `BotProfiles.kt:1143-1147`); **[R2]** InputProfile carries **7** legacy binding maps (v11AndroidPort/Direct/NearbyUse, v11PreRefinement, v11RearButton, v10Classic, v10Overlay), not 4 | `BotProfiles.kt:301-1298`, `InputProfile.kt:393-648` |
| S7 | 1676-line UI god file | `ui/BotsScreen.kt` |
| S8 | Magic numbers incl. **[R2]** the 6-hour session timeout at `ClientRuntimeService.kt:503` (uncited in v1) | `InputContract.kt:855-859`, `ClientDisplayHost.kt:882-884`, `BotsScreen.kt:1087,1507` |
| S9 | Logging — **[R2 — corrected counts]** no level gating in `AppLog.emit()`; **3** printlns (WineSpikeRunner ×2 + `PackagingExperimentRunner.kt:333`); **13** direct `android.util.Log` bypasses (RealmSupervisor ×11, ClientImeView, ManagedClientImporter); full window-topology dump per X11 map/unmap | `log/AppLog.kt:46-51` et al. |
| S10 | Test gaps — **[R2]** one Compose UI test exists (`O14TouchOverlayAcceptanceTest`); otherwise no tests for ClientDisplayHost/WineRuntimeStore pipeline/ClientRuntimeService state machine/DatabaseEngine core/`wine/*`/screens | `android/app/src/test` |

### 1.5 Python tooling (P2, two P1 bugs)

| # | Finding | Location |
|---|---------|----------|
| P1 | Broken SDK fallback — `Path(os.environ.get(...) or "")` → `Path(".")` → `.is_dir()` always true → fallback branch dead; **[R2]** plus a 7th instance: the emulator lookup at `capture_avd.py:143`; and `run_client_runtime.py:16` has *no* fallback at all (silent relative path). The three survivors use three mutually different dead-else forms | `run_pkg_experiments.py:42-43`, `run_wine_spike.py:34-35`, `capture_avd.py:32-33,143`, `smoke_native.py:32,40`, `run_client_runtime.py:15-16`, `build_native.py:53-56` |
| P2 | Silent SQL data loss: translation counts/tolerates errors, prints first 5, returns success if >0 applied; **[R2]** the OK line even prints "(N tolerated errors)", normalizing the loss | `tools/seed_realm_db.py:227-234,269` |
| P3 | Hardcoded personal paths: `G:/msys64` default, `G:\`/`C:\` toolchain candidates, `C:\Vanilla wow 1.12.1` client default | `scripts/build_native.py:50`, `tools/build_selftest_pe.py:44-47`, `tools/prepare_o09_server_data.py:189` |
| P4 | Hardcoded serial defaults (`emulator-5554` ×2; the personal serial is only in `install_rp6_when_available.ps1:10` — **[R2]** `keep_rp6_adb_alive.ps1` matches by model, no serial) | `tools/stage_o07_client.py:442`, ps1 |
| P5 | `run_realm_test.py`: zero `check=` in the file; all adb calls unchecked; raw `os.environ[...]` KeyErrors ×2; strip result ignored (silent 481 MB unstripped push); dead `llvm_strip` var at :152 | `tools/run_realm_test.py:43-45,148-198` |
| P6 | Fetches without timeout; pre-3.12 `extractall` fallback without `filter="data"` | `tools/fetch_provider.py:52,104-107`, `tools/stage_mariadb_runtime.py:89,99` |
| P7 | Safety checks silently skipped when strip/readelf absent (DT_NEEDED allowlist and 16 KB alignment pass vacuously) | `tools/build_vanilla_tweaks.py:113-130` |
| P8 | `assert` for runtime validation (8 sites) | `capture_avd.py:38,196`, `capture_rp6.py` ×6 |
| P9 | Duplication — **[R2 — recounted]**: sha256-family defs **21** (23 incl. orphans) in 21 files; run/output helpers **14** in 13 files; adb helpers ×5; `wait_for_boot` ×2; **`docker_path` ×4** (3 identical `//c/` + 1 divergent `as_posix()` Docker rejects); `checked_rmtree` ×2; `build_bootstrap` ×2; SDK discovery boilerplate ×6 | app-wide |
| P10 | Orphans — **[R2]** `prepare_o09_server_data.py`, `run_client_runtime.py`, `tools/scripts/native/record_apk_manifest.py`: zero refs, safe. **`stage_mariadb_runtime.py` has one reference**: a provenance comment at `DatabaseEngine.kt:750` documenting it as the generator of committed assets — deleting it loses the documented regeneration path; keep it or update the comment | `tools/` |
| P11 | Scattered pins: CGCT digest ×5; CMaNGOS commit ×2 in tools but **9 copies repo-wide incl. schemas**; DB revisions ×2; wine provider id **6 files / ~10 occurrences**; MariaDB version 4 forms in one file | app-wide |
| P12 | Windows-host lock-in: `windows-x86_64` in 8 files; `.exe` literals at **56 sites**; `gradlew.bat`-only ×3 | app-wide |
| P13 | rmtree-then-rebuild staging in **~10 scripts** (v1 said 7); correct tmp+`os.replace` pattern already exists in 2 | see round-1 audit |
| P14 | Silent `except Exception: log = ""` drops logcat evidence | `tools/run_wine_spike.py:133-134` |
| P15 | Brittle substring-contract tests: **9-10 of 11** test files assert on source text (v1 said ~7); only `test_capture_rp6.py` imports real code — generalize that pattern | `tests/` |

### 1.6 Native / runtime C++, Rust, patches (P1/P2)

| # | Finding | Location |
|---|---------|----------|
| N1 | **[R2 — revised]** `start()` holds `m_lifecycle` across an unbounded `join()` (:51,:55), and there are **three** unbounded joins total (also `stop():377` and `stop():392`). The v1 "restart deadlock" scenario is mostly unreachable (state gating returns WRONG_STATE first), but a worker wedged between final state transition and thread exit still deadlocks, and concurrent `stop()` blocks on the mutex | `native/realm-runtime/src/world_runtime.cpp:51-55,377,392` |
| N2 | `stop()` timeout path returns TIMEOUT without joining (thread leak) — **[R2]** and the wait loop also exits on FAILED, so a *clean* failure is reported to Kotlin as TIMEOUT (failure vs wedge indistinguishable); early-fail paths in `run()` (:513-559) skip `cleanup()` entirely, potentially leaving DBs open across restarts | `world_runtime.cpp:385-393,513-559` |
| N3 | **[R2 — revised]** Watchdog records stalls but native never escalates. Kotlin *does* act (BotAdmissionController:164-168 backs off bots on `repeated-hard-stall`); missing is failover/forced teardown of the world itself. Also `:430-431` stores a *timestamp* into `m_last_hard_stall_elapsed_ms` (misnamed; consumed by nobody) | `world_runtime.cpp:427-432` |
| N4 | Queued CLI commands not cancelled on timeout — a retried `account create` can execute twice | `world_runtime.cpp:587-606` |
| N5 | Data race: `m_io` assigned in `run()`/`cleanup()` without `m_lifecycle` while `stop()` uses it under the mutex | `realmd_runtime.cpp:59` vs `:104,144-148` |
| N6 | Listener threads run `m_io->run()` with no try/catch → `std::terminate` — **[R2]** kills the dedicated `:realm`/`:world` child process (not the UI process), still fatal for the realm | `realmd_runtime.cpp:117-118`, `lifecycle_realmd.cpp:103-104` |
| N7 | **[R2 — worse than stated]** `lifecycle_realmd.cpp:104` thread lambda captures `[&]` a *stack-local* `st` that dangles the moment `start_realmd` returns — **guaranteed UB on every successful start**; on the throw path, `delete st` destroys a joinable `std::thread` → `std::terminate` at the delete | `native/pocket-runtime/src/lifecycle_realmd.cpp:66,98-123` |
| N8 | `waitpid(-1, WNOHANG)` reaps any child of the process (steals exit statuses) | `native/wine-spike/src/proot_run.c:429` |
| N9 | **[R2]**: two statics (`visited[512]` :386, `pairs[512]` :366) + single-slot global cancel (:49); single non-blocking reap after timeout-kill leaves zombies (:976); `/proc`-wide SIGKILL sweep of anything mapping `libld_linux_x86_64.so` (:478-548); **PID-reuse kill hazard**: snapshots accumulate and every recorded PID is SIGKILLed without re-validating at kill time — a recycled PID gets killed blindly; dead empty branch (:982-984) | `proot_run.c` |
| N10 | **[R2 — revised]** Blind fixed-offset patching with zero validation — **15 offsets**, not 10; no MZ/PE check, no expected-original-byte verification; wrong-version exe → silently corrupted output. Consequence corrected: it is **exec'd as a subprocess** by `WineRuntimeStore.kt:1720-1730` (not FFI), so a panic kills the patcher child (visible failure), not the app; and the Kotlin caller *does* validate MZ/PE magic (`checkPeX86:1732-1741`). Original-byte validation is still missing and is the fix | `native/vanilla-tweaks/src/main.rs:157-266` |
| N11 | Fake success: `save()` returns OK without saving; `checkpoint()` a documented no-op | `native/pocket-runtime/src/realm.cpp:316-340` |
| N12 | Divergent twins: pocket-runtime realmd does expired-ban cleanup, realm-runtime's does not; OpenSSL provider loading triplicated; DB-teardown sequence duplicated | `lifecycle_realmd.cpp:89-94` vs `realmd_runtime.cpp` |
| N13 | **[R2 — revised]** Overlay system: **41 anchor sites grouped in 10 overlay records** (12 cmangos + 26 playerbots + 2 connector + 1 whole-file) with a hand-maintained inverse-restore list at `:869-932`; **connector overlays ARE idempotent** (`replace_anchor:755-757` no-ops on already-applied — v1 claim withdrawn); no `Origin:`/`Upstream-Status:` headers anywhere; **[R2]** duplicated `PB_LOGIN_DB_SCHEDULE` anchor pair (~:865-866) — harmless only because of the idempotency check; playerbots overlays target the *gitignored mirror* `cmangos/src/modules/PlayerBots`, invisible to `git diff` | `tools/build_o09_realm_runtime.py:51-735,869-932` |
| N14 | Substring failure classification ("DBC" in message mis-flags DB corruption as BLOCKED_ON_CLIENT_DATA) | `lifecycle.cpp:56-63`, `realm.cpp:221-222` |
| N15 | See A22 — crash bomb ships in release, user-reachable trigger | `native/packaging/src/crash.c` |

### 1.7 Docs & process (P2)

| # | Finding | Location |
|---|---------|----------|
| D1 | PLAN.md 4 days stale, contradicts PROGRESS.md ("at G0, next O05" at PLAN.md:74 vs PROGRESS.md:5,9 = O14/G4) — and it is step 2 of the mandated reading order | `PLAN.md` |
| D2 | Two "current handoff" docs (PROGRESS.md vs GRAPHICS_SELECTION_CONTEXT.md:3) | root |
| D3 | FEATURES.json O08 status `"complete"` not in `status_values` enum; **[R2]** `next_feature.py` doesn't validate the enum (won't crash today; O08 just surfaces as unresolvable), so the schema check adds real value | `FEATURES.json` |
| D4 | DECISIONS.md #2 ("may be stored and committed") vs agent.md non-negotiable + `.gitignore` | `DECISIONS.md:9` vs `agent.md:38-39` |
| D5 | Root doc sprawl: **10** root markdown files [R2 count]; SANITY_CHECK/PROMPTING_REDESIGN self-labeled historical; 50 KB unreferenced research report at root | root |
| D6 | No CI, no detekt/ktlint/lint config (verified zero config files) | repo-wide |
| D7 | `.claude/hooks/block_destructive.py` is a Claude-JSON PreToolUse hook, **[R2]** not a git hook and not reusable as one — the Phase 3 hook must be written fresh; also `tools/check_sources.py` already exists as a CI-safe sources/lockfile verifier and is wired into nothing | `.claude/`, `tools/` |

### 1.8 Build system & schemas **[R2 — new section]**

| # | Finding | Location |
|---|---------|----------|
| B1 | Task wiring by name-substring: `it.name.contains("lint", ignoreCase = true)` attaches gates to any task containing "lint"; siblings match `startsWith("merge")&&endsWith("Assets")`, `startsWith("connected")`, compile-task scans, and a pasted ×3 `validateDatabaseRuntime` block (which double-attaches in the full lane) | `android/app/build.gradle.kts:1114-1122,896-906,949-954,1378-1399` |
| B2 | Configuration-cache contradiction: `gradle.startParameter` reads at configuration time (:634-639) mutate `testInstrumentationRunner` (:720-723) and pick the safety gate (:896-906); a cached configuration can be reused for the RP6 hardware run with the **wrong runner and wrong gate baked in** — undermines `CONNECTED_TEST_SAFETY.md` | `build.gradle.kts:634-639,720-723` |
| B3 | Variant explosion: `pkgExperiment` and `clientRuntime` are config-identical to `debug` (:747-756); `realmRuntime` reuses `src/debug` sources cross-set (:815-823); every variant multiplies all AGP tasks (major contributor to the 16 GB `android/app/build`) | `build.gradle.kts:747-768` |
| B4 | Always-rerun verifier tasks: `verifyGeneratedVulkanDriverCatalog` runs a Python exec on **every compile task of every variant** (no outputs, wired at :949-954); `validateSelectedNativeClosure` re-SHA-256s ~100 MB of artifacts on every merge/lint/assemble; `validateDatabaseRuntime`/`validateRealmRuntime`/`removeRetiredArmClientAssets` have no inputs/outputs at all | `build.gradle.kts:933-954,1095-1112,1248-1293,1295-1362,1059-1093` |
| B5 | In-file duplication: `repoRoot` re-derived ×10; two SHA-256 helpers (:241-252 vs :1302-1313); two ELF-header parsers (:193-207 vs :1270-1277,1348-1353) | `build.gradle.kts` |
| B6 | Host hardcoding: NDK `windows-x86_64` prebuilt path (:1208); `commandLine("python", ...)` ×5 (CI images expose `python3`); NDK discovery "newest dir wins" (:922-931) can silently change `libc++_shared.so` while the closure validator expects pinned NDK `30.0.15729638` (:380) | `build.gradle.kts` |
| B7 | Pins scattered: DXVK DLL SHA-256s **only** in build.gradle.kts (:532-545); ARM rootfs hashes in 2 hand-synced places (:386-403 + `ArmRootfsProvisioner.kt:250-254`); gladio/virgl server hashes duplicated Kotlin+gradle; renderer packages have **no schemas/ home**; CMaNGOS commit ×9 consistent copies; `schemas/flavor.json` `target_sdk: 35` contradicts actual 27; vanilla-tweaks pin missing from sources.json. The good pattern to replicate: `schemas/vulkan-driver-catalog.json` → generated `GeneratedVulkanDriverCatalog.kt` | `build.gradle.kts`, `schemas/` |
| B8 | Unused dependencies: `androidx-lifecycle-runtime-ktx`, `-viewmodel-compose`, `-service` (zero `androidx.lifecycle` imports), `ui-tooling-preview` (zero `@Preview`); dead `kotlin-android` toml alias; `zstd-jni`/`org.json:json` coordinates half-cataloged (version in toml, coordinates inline) | `libs.versions.toml`, `build.gradle.kts:1411-1418,1428,1443` |
| B9 | Dead AIDL methods (implemented, zero callers incl. androidTest): `IRuntimeSupervisorControl.startSpec`, `IWorldControl.setBotTarget`/`botStatus`/`accountStatus`, `IImportWorker.statusJson`; `IClientDisplayControl.prepare` takes 13 positional params | `android/app/src/main/aidl/` |
| B10 | `catalog-v1.json` (264 KB) is a raw research workbook (reddit URLs, xlsx provenance, community notes) shipped inside the APK, with counts dual-pinned in Kotlin (`AddonCatalog.kt:165,185` requires 154 addons / 266 pairs) | `assets/addons/catalog-v1.json` |
| B11 | `keepDebugSymbols += "**/*.so"` disables stripping for every .so in every variant incl. release; `android/app/.gitkeep` dead | `build.gradle.kts:782` |
| B12 | tests/avd evidence accumulates FAIL/INVALIDATED/SUPERSEDED artifacts with no retention policy | `tests/avd/` |

### 1.9 Vendored Java X-server & renderer trees **[R2 — new section]**

Both halves are live and required: `runtime/xserver-winlator/` (159 Java files, compiled into the APK via `java.srcDir` at `build.gradle.kts:808`) and `native/xserver-winlator/cpp/` (436 tracked files building `libwinlator.so`/`libgladiorenderer.so`/`libvortekrenderer.so`/`libvirglrenderer.so`). Vendored from winlator-app @ `ca3d735` (LGPL-2.1, source-offer obligation documented).

| # | Finding | Location |
|---|---------|----------|
| X1 | **Dual Vortek renderer trees — the hardened one does not ship.** `cpp/vortekrenderer/` (208 files: bounded serializer, 552 bounds-checks in its request_handler, handle_registry, safe_lane, ASan tests, hardener toolchain) compiles only into host test binaries. The APK ships `cpp/vortekrenderer-winlator-2.1/` — essentially upstream, using the raw unbounded serializer. All the decode-hardening work is currently dead weight | `native/xserver-winlator/cpp/` |
| X2 | `XConnectorEpoll.killAllConnections()` unsynchronized vs every other access (:82,:100,:155); `getClients()` returns a live view that can CME on callers | `runtime/xserver-winlator/com/winlator/xconnector/XConnectorEpoll.java:146-152` |
| X3 | `FileUtils.getSizeAsync` creates a per-call executor, never shuts down, and has zero callers (dead + leaky); `readString` silently returns null; 5 `printStackTrace` sites | `com/winlator/core/FileUtils.java:321-323,70-72,81,93,238`, `xserver/EventListener.java:30`, `xserver/XClient.java:58` |
| X4 | `APP_CACHE_DIR` hardcoded `/data/data/com.pocketrealm/cache` (silently breaks on applicationId change); debug texture-dump writes unbounded | `cpp/include/winlator.h:14`, `debug_utils.h:49-50` |
| X5 | Provenance doc stale: says 143 Java files (now 159), omits `alsaserver` and `xenvironment/components`, still calls sysvshm a 1-file stub (now 4) — the LGPL source-offer inventory is inaccurate | `docs/patches/wine-provider-provenance.md:84-104` |
| X6 | `tools/build_xserver_winlator.py` is wired into no Gradle task (manual step, documented only in a doc); the closure validator merely fails on missing output | `build.gradle.kts` |
| X7 | `GL calls testing/` is the staging origin of in-tree code (webagent attachments byte-identical to `cpp/gladiorenderer/src/*`; phase2/3 sources SHA-match pinned pipeline outputs) — informs the J4 triage | see J4 |

### 1.10 Addon subsystem (Lua/XML + manager) **[R2 — new section]**

Overall quality is high (no debug prints, consistent 1.12 idiom, pcall fail-safes, strong archive validator: size caps, NFKC, case-collision, path traversal, symlink rejection, redirect-policing on every hop).

| # | Finding | Location |
|---|---------|----------|
| AD1 | **Real user-facing bug — off-by-one in the 0.5.x→0.6.0 journal re-key**: `string.sub(name, 18)` keeps the trailing "t" of "Port" (`VanillaConsolePortLeftCluster` → `AndroidPorttLeftCluster`), so migrating devices silently lose journaled frame positions and gain junk keys forever. Should be `sub(name, 19)`. No test pins it | `assets/addons/android-port/AndroidPort/Core.lua:66-67` |
| AD2 | Kotlin duplication: `writeAtomic` ×3 (AddonRepository:920, AndroidPortBindingRepair:220, AndroidPortMigrator:249); `copyBuiltInAssetTree` ×2 with differing strictness; TOC-Interface regex ×2; size/extension constants ×2; bindings-line regex ×3 with drift | `android/.../addons/` |
| AD3 | No `AddonRepositoryTest` (971-line class; OkHttp constructed directly, not injected — network/redirect/rate-limit paths untested); zero Lua *execution* tests (all Lua testing is string-pinning — which is exactly why AD1 survived) | `android/app/src/test` |
| AD4 | Copy-pasted F12/F8/F9/F7 claim blocks with an intentional-but-trappy asymmetry; `AP_ACTION_33..40` defined but unbound (legacy retirement support) with no explanatory comment | `Core.lua:786-818,10-12,764-785`, `Bindings.xml:34-41` |
| AD5 | `Hud`/`FrameMover` hooks have no uninstall path (unlike Bags/Bars); `UISpecialFrames` iterated without table check | `Hud.lua:135-168`, `FrameMover.lua:285-296`, `Core.lua:400`, `Bags.lua:92-95` |
| AD6 | See B10 (research catalog in APK) and J9 (empty root skeleton) | — |

**What is already good (keep and protect; re-verified in round 2):** no leaked secrets anywhere; SecureRandom-generated, socket-only MariaDB; loopback binds with private-IP validation for LAN opt-in; RA/SOAP/console disabled; every download SHA-pinned with provenance lockfiles; fail-closed Kotlin; genuinely strong addon archive validation; real tests with assertions; zero TODO markers; dxvk/turnip committed files confirmed orphaned (deletion-safe) and the cmangos submodule dirt confirmed EOL-only (checkout-safe).

---

## 2. The plan

Work on a branch (`devibe/cleanup`). Every phase ends with its gate green and a commit. Phases 0–2 are mechanical; Phases 4–6 begin only after Phase 3's safety net exists.

### Phase 0 — Baseline & insurance (half a day)

- [ ] Commit this plan itself (`docs/DEVIBE_PLAN.md` is currently untracked).
- [ ] Tag the audit point (`git tag pre-devibe`) — **note: the tag dies in the Phase 1 history rewrite (all refs are rewritten); the bundle below is the real insurance.**
- [ ] **Reconcile the linked worktree first**: `git worktree list` shows `C:/pocket_realm_winlator_vortek` on `codex/winlator-vortek-system-port`. Either preserve its state (commit/merge/discard explicitly) and `git worktree remove` it, or plan to re-create it after the rewrite. filter-repo is unsafe with live linked worktrees and will need `--force` on this non-fresh clone regardless.
- [ ] **Branch triage**: decide which of the 12 local branches survive post-cleanup (several are stale: `o06/sigsys-diagnosis-correction`, five completed `codex/oNN-*`, two `agent/glm-*`). The rewrite rewrites every ref — decide before, not after.
- [ ] Record green baselines into `.tmp/devibe/` (local-only): `./gradlew :app:testDebugUnitTest **-PpocketAbi=x86_64 -PpocketLane=full**` (**[R2] required — `build.gradle.kts:645-652` throws `GradleException` without `-PpocketAbi`**), `python -m pytest tests/`, and one representative native smoke path per PROGRESS.md.
- [ ] Record `git submodule status`; `native/cmangos` currently shows EOL-only dirt on 5 files (verified: `--ignore-cr-at-eol` diff empty) — `git checkout -- .` inside it is safe now.
- [ ] Full backup outside the repo: `git bundle create ../pocket_realm_pre_devibe.bundle --all`.

**Gate 0:** baselines green with the `-P` flags, bundle exists and is ≥ `.git` size, worktree question resolved, submodule status clean.

### Phase 1 — Excise the landfill + history purge (1 day) — *fixes J1–J9, L1–L6*

**Step 1 — forward removal (on branch):**
- [ ] `git rm` all 51 `.codex-*` files, the root strays (J5 list), `vc140.pdb`; delete untracked strays (`rail.png`, `test-home.png`, `top-content.png`, `vortek_decode*.obj`).
- [ ] `git rm -r native/xserver-winlator/cpp/vortekrenderer/tests/build-authority` (also removes L2's fingerprint log).
- [ ] `git rm -r` all vendored `native/.tmp-*`: dxvk dir + tar, turnip tar + ICD json, **and `.tmp-winlator-gladio.tar` + `.tmp-winlator-gladio/`** [R2]. Deletion is **confirmed safe** — zero references; provisioning flows through `native/.providers-extracted/` and `tools/stage_renderer_packages.py`.
- [ ] `GL calls testing/` triage: keep CONTEXT_NOTES.md, research_plan.md, phase2 corrections/engineering/validation/sha256 manifest, the 8 patches, C sources, eglprobe.c → move to `docs/research/gl-calls/` (space-free name). Delete: `eglprobe` binary, `installed.apk`, zips, `webagent-attachments/` (byte-duplicates of in-tree sources), device screenshots, logs, `ui_home.xml`. Remember `GRAPHICS_SELECTION_CONTEXT.md:5` links to `CONTEXT_NOTES.md` — update that path.
- [ ] Delete `New folder/`; move `fixed/PLAN-vanilla-tweaks-autologin.md` → `docs/`; delete `fixed/`; delete or populate `runtime/{box64,fex,providers}` gitkeep skeletons and fix PLAN.md's layout claims (wine-x86 doesn't exist, providers live under native/); resolve `addons/` skeleton (delete — real addon is in android assets).
- [ ] `.gitignore` — exact safe lines **[R2]** (root-anchored patterns only match repo root, so no negations are needed; android has no raster mipmaps to endanger — launcher icons are vector XML):
  ```gitignore
  # Root-level image/exe debris (agent screenshots, drops) — never at repo root
  /.codex-*
  /*.png
  /*.jpg
  /*.jpeg
  /*.exe
  .tmp/
  .pytest_cache/
  native/.tmp-*
  CMakeFiles/
  CMakeCache.txt
  CMakeConfigureLog.yaml
  *.tlog
  *.pdb
  *.ilk
  *.lastbuildstate
  *.recipe
  build-authority/
  ```

**Step 2 — history rewrite (safe: no remote; bundle is insurance; worktree handled in Phase 0):**
- [ ] `git filter-repo` across all refs removing the artifact paths (`.codex-*` glob, `build-authority/`, `native/.tmp-*`, root junk). This is what actually removes the proprietary exe content (L1) from every commit.
- [ ] Post-rewrite: `git reflog expire --expire=now --all && git gc --aggressive --prune=now`; delete the 6 `tmp_obj_*` files; verify the old exe hash is unreachable.

**Step 3 — scrub personal info from tracked text:**
- [ ] Serial in `install_rp6_when_available.ps1:10` → param/env. (v1 also listed `keep_rp6_adb_alive.ps1` — **[R2]** it has no serial; model-matches already.)
- [ ] Redact serial + LAN IP in `GRAPHICS_SELECTION_CONTEXT.md:127` **now** (the doc is only folded in Phase 2, but Gate 1 greps for the serial) and in the moved gl-calls notes.
- [ ] Account/character names in `docs/L1_NEARBY_USE_INVESTIGATION.md` **plus [R2]** `android/app/src/test/.../InGameSettingsContractTest.kt:86,89,90` and `docs/INGAME_SETTINGS_GROUND_TRUTH.md:5` → replace with `player-1`/`char-1`, IP → `192.168.1.x`.

**Gate 1:** fresh clone to temp: no `.codex-*`, proprietary content unreachable, `git grep -l "4a8069ae\|192.168.1.x\|char-1"` (tracked files, excluding submodule dirs) empty, Gate 0 baselines still green, `.git` size recorded.

### Phase 2 — Docs: one truth (half a day) — *fixes D1–D5*

- [ ] PROGRESS.md remains the single current handoff; fold GRAPHICS_SELECTION_CONTEXT.md into it (update its `CONTEXT_NOTES.md` pointer to the new `docs/research/gl-calls/` path).
- [ ] Update PLAN.md gate/feature lines to match PROGRESS.md (G4/O14) or explicitly re-scope it to architecture-only.
- [ ] FEATURES.json: O08 `"complete"` → `"done"`; add `tools/validate_features.py` (enum + dependency order — `next_feature.py` does neither [R2]).
- [ ] Rewrite DECISIONS.md #2 to match agent.md/.gitignore (never commit extracted client data), noted as a dated delta.
- [ ] Move SANITY_CHECK.md + PROMPTING_REDESIGN.md → `docs/history/`; move the 50 KB research report → `docs/research/`.
- [ ] Rewrite README.md file map to match reality.
- [ ] **[R2]** Refresh `docs/patches/wine-provider-provenance.md` (143→159 files; add alsaserver, xenvironment/components; sysvshm is no longer a stub) — X5, it backs the LGPL source offer.

**Gate 2:** validate_features green; one current-handoff doc; reading order contradiction-free; `next_feature.py` still functions.

### Phase 3 — Safety net before code changes (1 day) — *fixes D6, D7, B2-interim*

- [ ] `tools/check_repo.py`: fail on forbidden tracked patterns (`.codex-*`, `build-authority/`, `native/.tmp-*`, root images/exe), tracked blobs > 1 MB outside an explicit allowlist (docs reports, `tests/avd/**/evidence`, `docs/wiki/**`, the LGPL X-server trees — **[R2] these are large and must stay**, catalog-v1.json), FEATURES schema violations, and scrubbed identifiers (`4a8069ae`, `192.168.1.`, `Users\David`, `G:\msys64`) in tracked text.
- [ ] Git hook written fresh (`.githooks/` + `core.hooksPath`; **[R2]** `block_destructive.py` is a Claude-JSON hook, not reusable): runs `check_repo.py` + `pytest tests/` + **`tools/check_sources.py`** (existing verifier, currently wired into nothing).
- [ ] `.github/workflows/ci.yml` ready for the future remote — **[R2] scoped honestly**: pytest + check_repo + check_sources on ubuntu-latest, and gradle unit tests with `-PpocketAbi=x86_64 -PpocketLane=full` on windows-latest; no full builds (runners lack MSYS2/NDK toolchain).
- [ ] Detekt with generated baseline (non-blocking initially).

**Gate 3:** a deliberately added root PNG and a schema-breaking FEATURES edit are both caught; suite green on clean tree with the `-P` flags.

### Phase 4 — Python: one toolbox, fail-loud (2–3 days) — *fixes P1–P15*

**4a. `tools/common.py`** (breaks nothing — verified no module named `common`, no `tools/__init__.py`; current cross-script import graph: build_mariadb_android→build_glibc_closure, build_vanilla_tweaks→build_o09 (uses `o09.tools()/select_abi()/CMANGOS_COMMIT`), build_o11→build_o09, build_wine_16k_ntdll→stage_wine_runtime; tests import `tools.capture_rp6` package-style. **[R2] Pick one canonical import style** and keep `build_o09`'s public names re-exported while migrating its importers):
- [ ] `resolve_android_sdk()/ndk()/adb()/emulator()` with correct unset-env behavior (env → default locations → raise with instructions) — kills all 7 dead-fallback instances including `capture_avd.py:143`.
- [ ] `sha256_file()`, `run()/output()` (list-argv, `check=True` default, timeout), `adb()+wait_for_boot()`, canonical `docker_path()` (`//c/` form), `checked_rmtree()`, `atomic_stage()`, host-suffix helper, shared constants (CGCT digest, host tag).
- [ ] Migrate all scripts; delete the 21 sha256 copies, 14 run/output copies, 5 adb variants, 4 docker_paths, 2 checked_rmtree, 2 build_bootstrap.

**4b. Fail-loud fixes:** run_realm_test.py checked adb/env/strip + dead var (P5); seed_realm_db.py fail-on-error by default + `--tolerate-sql-errors N` with full census in provenance (P2); fetch_provider timeouts + tar filter (P6); build_vanilla_tweaks hard-fail on missing tools (P7); asserts→raises (P8); logcat drop fix (P14).

**4c. De-hardcode & prune:** MSYS2 → env+search+clear error; client path → required arg; serials → required flag/env. Delete verified orphans `prepare_o09_server_data.py`, `run_client_runtime.py`, `tools/scripts/`; **[R2] keep `stage_mariadb_runtime.py`** (it is the documented generator of committed mariadb assets, `DatabaseEngine.kt:750`) — fold it into `common.py` consumers instead. Consolidate pins into `schemas/sources.json` (see also B7). Convert rmtree stagings to `atomic_stage()`. Delete ghost `__pycache__` (includes deleted `stage_fex_*`/`build_alsa_plugin`).

**Gate 4:** pytest green; one `def sha256_file` repo-wide; zero `Path(os.environ.get(... or ""))`; `build_vanilla_tweaks.py` runs end-to-end; check_repo + check_sources green.

### Phase 5 — Kotlin & app: security first, then threading, then structure (4–6 days) — *fixes A1–A22, S1–S10, AD1–AD6*

**5a. Security quick hits (half day):**
- [ ] **[R2]** Gate ALL ungated test APIs behind `BuildConfig.DEBUG`: RealmService ×2, RealmRuntimeService.killForTest, WorldRuntimeService.killForTest, DatabaseService killForTest/snapshotAndRestoreTest/storageFullTest (A3).
- [ ] Export ZIP: exclude `account.json` by default; explicit include-credentials toggle with plaintext warning (A2; one-line call-site change at SettingsScreen.kt:144). Keystore encryption of UserAccountStore stays a separate feature decision.
- [ ] Remove the cache-file audio switch (A4 — already DEBUG-gated, so removal only).
- [ ] **[R2]** Decide PackagingExperimentRunner/CapabilityScreen disposition (A21/A22): minimum = gate the screen or runner behind a debug/pkg lane so release UI can't trigger the crash bomb; better = move `libpocketpkgtest.so` packaging out of the main sourceSet (`build.gradle.kts:265`) into the pkg lane only.

**5b. Threading and waits (1–2 days):** bound all six unbounded loops (A5, incl. DiagnosticsScreen:65 and InGameBindingsScreen:83) with deadlines + failure states; fix the 25 ms lease spin (A6) with backoff + failure propagation; `runBlocking` out of composition (A8) and the third site BotPresetStore:302 (A9); sleep-under-lock restructure (A7); coroutine sleeps → `delay` where actually in coroutines (A10 — thread-based sites need restructuring, not sed).

**5c. Kill the copy-paste (1–2 days):** `fs/Digests.kt` (streaming SHA-256) + `fs/AtomicFiles.kt` (fsync'd) replacing 32 sites/19 files and 7 weak writes (S3, A14); unify ports/paths/stderr-truncation (A17); one home per duplicated algorithm (S4); **[R2] also deduplicate the addon-side copies** (AD2: writeAtomic ×3, copyBuiltInAssetTree ×2, TOC regex, constants, bindings regex).

**5d. Dead code & archaeology (1 day):** move `WineSpikeRunner.kt` to androidTest (verified clean — sole external ref is WineSpikeTest.kt:34); delete `verifyLoaderChain`, `FpsProfile`, `BOT_LIVELY_700_PROFILE`; keep `BOT_LOW_25_PROFILE` (androidTest uses it) or migrate those tests first; collapse legacy codecs to the minimum reachable migration path (S6: 7 InputProfile maps, adv1–4 chain).

**5e. Robustness (half day):** `!!` clusters → explicit state checks; Settings parse failures log-once; guard BotsScreen:567; check mkdirs/renameTo; DeviceQuirks table for RP6 fingerprints (A11–A16, A20).

**5f. Addon fixes (half day):** **fix the AD1 off-by-one (`Core.lua:66-67` `sub(name, 18)` → `19`) and add pin tests** (a Kotlin-side test that `VanillaConsolePortLeftCluster` journals land on `AndroidPortLeftCluster`); comment the `AP_ACTION_33..40` purpose; UISpecialFrames guards (AD4/AD5).

**5g. Gradle/build hygiene (1–2 days) — [R2] new:**
- [ ] Fix the config-cache contradiction (B2): replace `startParameter` scanning with a `-P` property or dedicated task; stop mutating `testInstrumentationRunner` at configuration time. Highest priority in this subsection — it guards the physical-device safety boundary.
- [ ] Replace name-substring task wiring with `androidComponents`/variant APIs (B1), starting with `contains("lint")`.
- [ ] Consolidate variants (B3): migrate `run_pkg_experiments.py`/`run_wine_spike.py`/`run_client_runtime.py` to `debug`, differentiate-or-delete `pkgExperiment`/`clientRuntime`, give `realmRuntime` its own source set.
- [ ] Give verifier tasks declared outputs or narrow their attachment (B4) — stop re-hashing ~100 MB per compile.
- [ ] Deduplicate `repoRoot`/sha256/ELF helpers (B5); de-hardcode host paths and `python` command (B6); move inline pins into schemas/ (B7, mirroring the vulkan-catalog → generated-Kotlin pattern; add missing sources.json entries).
- [ ] Remove unused deps + dead toml alias; catalog zstd-jni/org.json properly (B8); delete dead AIDL methods (B9); decide minify-or-drop-ProGuard (B11); drop `android/app/.gitkeep`.
- [ ] Trim `catalog-v1.json` to runtime-relevant fields (B10); define tests/avd retention policy (B12).

**Gate 5:** full unit suite green (`-PpocketAbi=x86_64 -PpocketLane=full`); budgets: 0 `runBlocking` under `ui/`, 0 `GlobalScope`, `!!` ≤ 10 (from 35), **one SHA-256 implementation in `fs/Digests.kt` (production call sites may still say `MessageDigest` via it)** [R2 reworded], 0 `println` in main after 5d+5a, 6 bounded loops; manual emulator smoke per the current O14 flow; AD1 regression test passes.

### Phase 6 — Native runtime: fix the real bugs, then the patch system (3–5 days) — *fixes N1–N15*

**6a. realm-runtime correctness (production path):**
- [ ] `world_runtime.cpp`: bounded joins (three sites N1), join-or-detach-with-log on the stop-timeout path (N2), distinguish FAILED from TIMEOUT in `stop()` (N2 [R2]), call `cleanup()` on early-fail paths (N2 [R2]), watchdog escalation policy — surface FAILURE to Kotlin + forced teardown after N stalls (N3; the bot backoff already exists on the Kotlin side), cancellable or idempotency-tagged queued commands (N4), fix the misnamed stall telemetry (N3 [R2]).
- [ ] `realmd_runtime.cpp`: assign/reset `m_io` under `m_lifecycle` (N5); try/catch thread procs → error state (N6); add expired-ban cleanup parity (N12).

**6b. pocket-runtime retirement (revised [R2] — verified: realm-runtime/MySQL is production; pocket-runtime has zero production callers but is a required gradle closure item (:264), the PKG-experiment dlopen target (`packaging/src/realm_so.cpp:17`), and the `run_realm_test.py` subject):**
- [ ] Fix the N7 `[&]` capture **now regardless of retirement** — it is UB on every successful realmd start in this runtime (shared_ptr/by-value capture).
- [ ] Retirement sequence, in order: remove the gradle closure entry + stageNativeLibs input; repoint or retire the PKG dlopen probe; delete `realm/RealmNative.kt` + its negative test; retire/replace `run_realm_test.py`; then move `native/pocket-runtime` → `native/attic/` with a README.
- [ ] Make `save()`/`checkpoint()` honest and replace substring failure classification while it lives (N11, N14).

**6c. wine-spike native fixes — mandatory, not conditional ([R2] rewritten):** `native/wine-spike/` builds `libwine_spike.so`, which **is production** (`WineSpikeNative.kt` loads it; WineRuntimeStore/ClientRuntimeService/DatabaseEngine call it; gradle requires it in every lane). The Kotlin *runner* moves out in 5d, but the native lib stays maintained. Minimum fixes: `waitpid(pid)` not `waitpid(-1)` (N8); blocking reap after timeout-kill (N9); de-static `visited`/`pairs` arrays; re-validate `/proc/<pid>/maps` at kill time to kill the PID-reuse hazard (N9 [R2]); remove the dead branch.

**6d. vanilla-tweaks input validation (small, high value):** before any write, check MZ/PE magic (also on the Rust side — the Kotlin caller checks, but the tool is independently exec-able), file size, and expected original bytes at all 15 offsets; error return, never panic (N10). Unit tests with truncated/wrong-version/already-patched inputs.

**6e. Patch-system reform (can trail):** emit the 41 overlay sites as real `.patch` files with `Origin:`/`Upstream-Status:`/`Description` headers. **[R2] Mechanics verified:** the build already refuses a dirty submodule and restores in `finally`, so patch emission is a clean diff capture — but it takes **two diffs**: `git diff` inside `native/cmangos` (12 anchors + the created interaction file) **and** `diff --no-index native/playerbots <mirror>` for the 26 playerbots anchors (they patch a gitignored mirror invisible to git). Restore = checkout + delete the created file + rmtree the mirror (not just `git checkout --`). The lockfiles' `*_source_overlays` metadata regenerates; gradle validates only artifact hashes, so nothing breaks. Fix the duplicated `PB_LOGIN_DB_SCHEDULE` anchor pair during conversion (N13 [R2]).

**6f. Vendored X-server follow-ups ([R2] new):** fix `XConnectorEpoll` synchronization (X2); delete dead leaky `getSizeAsync`, fix `readString`, replace 5 `printStackTrace` (X3); runtime-supply `APP_CACHE_DIR`, gate debug dumps (X4); wire `build_xserver_winlator.py` into Gradle (X6).

**Gate 6:** re-scoped [R2]: realm-runtime lane green on emulator (a realm-runtime-based smoke, since `run_realm_test.py`'s pocket-runtime subject is being retired in 6b); vanilla-tweaks unit tests pass; submodules pristine after build+clean; patches (once 6e lands) apply twice in a row.

### Phase 7 — Test the risky cores (2–3 days initial, then incremental)

- [ ] Extract and test pure logic: close-target scoring (ClientDisplayHost), WineRuntimeStore quota pruning + path planning, ClientRuntimeService teardown sequences.
- [ ] One Compose UI test per critical flow (preset delete w/ confirm; export-without-credentials).
- [ ] **[R2]** `AddonRepositoryTest` with injected fake HTTP (redirect policing, rate-limit mapping, registry transitions); begin a Lua execution harness (tiny 1.12 API stub) so migrations like AD1 are executed, not string-matched.
- [ ] State where connected tests run and how the moved WineSpikeTest keeps its `run_wine_spike.py` driver.
- [ ] Retire brittle substring tests opportunistically (no big-bang).

**Gate 7:** the audit's worst-offender files each have tests or a documented why-not.

### Phase 8 — Sustainment (half day)

- [ ] Update `agent.md` **and the `.claude/` skills/rules** ([R2] — they encode the workflow that produced the root-screenshot habit): evidence → `tests/**/evidence/` or local `tmp/`, never root; no >1 MB binaries without a DECISIONS entry; screenshots only in docs/wiki.
- [ ] Ship the hygiene hook in-repo; monthly cadence: `git gc`, check_repo, check_sources, validate_features.
- [ ] Decide on a private remote (CI activates on first push; history is clean after Phase 1 — the natural moment).

---

## 3. Decision points (owner input)

| # | Decision | Recommendation |
|---|----------|----------------|
| 1 | History rewrite vs forward-only | **Rewrite.** No remote, bundle-insured, worktree reconciled first. |
| 2 | Export-ZIP credentials | **Exclude by default** + opt-in toggle. |
| 3 | wine-spike disposition — **[R2] rewritten** | **Split it:** Kotlin `WineSpikeRunner.kt` → androidTest (verified clean); `native/wine-spike/` **stays maintained** — it builds the production `libwine_spike.so`. v1's "archive both" would have broken Wine launch. |
| 4 | pocket-runtime vs realm-runtime | **Retire pocket-runtime** (zero production callers) via the 6b sequence — gradle, PKG dlopen, tests first. realm-runtime is production (verified wiring). |
| 5 | `targetSdk = 27` | **Keep** (documented constraint), record distribution consequence in DECISIONS; also fix `schemas/flavor.json`'s stale `target_sdk: 35` (B7). |
| 6 | Keystore encryption for `account.json` | **Defer to a feature**; do the export exclusion now. |
| 7 | **[R2] Vortek dual tree (X1)** | **Decide explicitly:** promote the hardened `cpp/vortekrenderer/` tree into the shipped `.so` (it exists, is tested, and the shipped decoder is the unbounded upstream one), or delete the hardened tree + its hardener toolchain + pytests. Shipping the unsafe decoder while maintaining the safe one in-repo is the worst option. Recommend: promote, behind the existing host test suite. |
| 8 | **[R2] Branch survival** | Pick which of the 12 branches live past the rewrite (Phase 0). |

## 4. Effort & sequencing summary

| Phase | Scope | Effort | Risk |
|-------|-------|--------|------|
| 0 | Baseline, worktree, bundle, branch triage | 0.5 d | none |
| 1 | Junk excision + history rewrite + PII scrub | 1 d | low (bundle-insured) |
| 2 | Docs consolidation | 0.5 d | none |
| 3 | Hygiene guard + hooks + CI-ready + detekt | 1 d | none |
| 4 | Python toolbox + fail-loud fixes | 2–3 d | low |
| 5 | Kotlin security/threading/dedup/dead code + addons + gradle | 4–6 d | medium (needs Gate 3 net) |
| 6 | Native bug fixes + patch reform + X-server follow-ups | 3–5 d | medium |
| 7 | Tests for risky cores | 2–3 d + ongoing | low |
| 8 | Sustainment | 0.5 d | none |

Total: roughly **15–20 focused days**. Phases 1–3 (~2.5 days) clear every P0 finding.

## 5. Definition of done

- Fresh clone: zero tracked binaries outside the allowlist; `.git` packed and < 100 MB; proprietary exe content unreachable from any ref.
- One current-handoff doc; PLAN/PROGRESS/FEATURES consistent and schema-validated; no personal identifiers in tracked text (all four L5 locations included).
- Hygiene guard + tests green in hook/CI; detekt active; gradle builds work with documented `-P` properties on a standard runner.
- No unbounded waits in production paths; N1/N2/N5/N6/N7 fixed; wine-spike minimum fixes (N8/N9) landed; vanilla-tweaks validates before patching; **AD1 fixed with regression test**.
- One SHA-256 implementation per language; one atomic-write; one SDK/adb discovery; orphans deleted (stage_mariadb_runtime kept deliberately, documented).
- Vortek dual-tree decision executed (Decision 7); config-cache/lint wiring fixed; verifier tasks incremental.
- Every finding in Section 1 is fixed or re-triaged here with a reason.

## Appendix A — Worst-offender files

| Rank | File | Headline |
|------|------|----------|
| 1 | `native/wine-spike/src/proot_run.c` | waitpid(-1) child stealing, PID-reuse blind kills, static state, zombies, /proc-wide SIGKILL sweep — **stays production (Decision 3)** |
| 2 | `android/app/build.gradle.kts` (1444 ln) | [R2] lint substring hack, config-cache safety-gate hole, variant explosion, always-rerun 100 MB re-hashes, inline pin mirror |
| 3 | `tools/build_o09_realm_runtime.py` | 41-site overlay patch system with hand-kept inverse restore; duplicated anchor pair |
| 4 | `native/realm-runtime/src/world_runtime.cpp` | unbounded joins ×3, stop() conflates FAILED/TIMEOUT, watchdog without escalation, uncancellable commands, cleanup-skipping early fails |
| 5 | `client/WineRuntimeStore.kt` (2228 ln) | god class, 25 ms lease spin, runBlocking, non-atomic writes, hardcoded patch paths |
| 6 | `client/ClientRuntimeService.kt` (1075 ln) | 7 `!!`, duplicated diagnostics, 6-hour magic timeout, RP6 model string |
| 7 | `native/pocket-runtime/src/lifecycle_realmd.cpp` | guaranteed-on-success dangling `[&]` capture (N7) |
| 8 | `wine/WineSpikeRunner.kt` (1500 ln) | experiment harness in production — **moving to androidTest (5d)** |
| 9 | `tools/run_realm_test.py` | every adb call unchecked, env KeyErrors, dead variable |
| 10 | `pkg/PackagingExperimentRunner.kt` + CapabilityScreen route | [R2] second harness reachable from shipped UI; crash-bomb trigger path |

## Appendix B — Immediate quick wins (under an hour each)

1. `git rm` the 51 `.codex-*` files + root strays; add the Phase 1 `.gitignore` block.
2. Gate **all five services'** test-kill/test-mutator Binder APIs behind `BuildConfig.DEBUG` (A3 [R2 list]).
3. **Fix the addon migration off-by-one** — `Core.lua:66-67`, `string.sub(name, 18)` → `19` (AD1; silent user-data loss today).
4. Fix the `Path("")` SDK idiom in the 7 Python locations (P1) — one correct helper pasted 7 times is a win before `common.py` exists.
5. Make `seed_realm_db.py` fail on SQL errors by default (P2).
6. Fix FEATURES.json O08 + PLAN.md's gate line (D1/D3).
7. Add deadlines to the six unbounded UI/supervisor loops (A5).
8. `git bundle` backup + `git gc` (Phase 0 in miniature).

## Appendix C — Round-2 verification changelog

Eight agents re-checked the v1 plan against the code (four fact-checkers, three new-area audits, one soundness review). Outcome:

- **Kotlin (A/S findings): 24 of 30 confirmed verbatim, 6 partial (count/drift), 0 wrong.** Material corrections: A3 widens to 5 services; A4 already DEBUG-gated; A6 spins at 25 ms; S3 recount 19 files/32 sites in main; S6 seven legacy maps; S9 counts 13 Log bypasses / 3 printlns.
- **Python (P findings): all 15 confirmed** (several undercounts fixed: sha256 ×21, docker_path ×4, orphans scoped — `stage_mariadb_runtime.py` has a Kotlin provenance reference). Import graph mapped; `common.py` insertion verified safe.
- **Native (N findings): 8 confirmed, 5 partial, 1 sub-claim withdrawn** (connector overlays *are* idempotent), 0 wholly wrong. N7 is worse than stated (guaranteed UB on success path); N10's consequence corrected (subprocess panic, not app-abort); N1's deadlock scenario narrowed but three unbounded joins confirmed; N13 counts corrected (41 sites / 10 records).
- **Hygiene/security: 21 of 24 claims exact** (branch count 12 not 13; 43 codex PNGs; build-authority 9.3 MB; 10 root md files). The exe is a UTF-16-mangled non-runnable copy but still proprietary content. dxvk/turnip deletion **confirmed safe**; cmangos dirt **confirmed EOL-only**.
- **Soundness review: v1 was NOT safe to execute as written.** Eight blocking issues, all fixed in this revision: (1) wine-spike archiving would have broken Wine launch — `libwine_spike.so` is production; (2) Phase 6 contradicted itself via `run_realm_test.py` (pocket-runtime subject); (3) the linked worktree at `C:/pocket_realm_winlator_vortek` makes filter-repo unsafe until reconciled; (4) Gate 1's serial grep couldn't pass until Phase 2; (5) all gradle commands need `-PpocketAbi`/`-PpocketLane` or they throw; (6) L5 personal names live in two more files; (7) Gate 5's "1 MessageDigest site" was unachievable as worded; (8) 6e's restore is more than `git checkout --` (mirror + created file), and the playerbots overlays need `diff --no-index`.
- **New areas audited (previously uncovered):** build system (§1.8, B1–B12), vendored Java X-server and renderer trees (§1.9, X1–X7 — including the hardened-Vortek-doesn't-ship discovery, Decision 7), addon subsystem (§1.10, AD1–AD6 — including the AD1 migration bug).
