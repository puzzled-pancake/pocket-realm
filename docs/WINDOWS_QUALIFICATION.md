# Pocket Realm for Windows — Qualification

The Windows sibling of the Android app (same repo, shared sources; see
`desktop/shared-sources.json` and the Windows-port PLAN-LOG entries).
This document is the test campaign record: what is machine-gated, what
is human-in-the-loop, and what is deliberately deferred.

## 1. Automated gates (all green on the dev box, 2026-09-10)

| Gate | How to run | Evidence |
| --- | --- | --- |
| Desktop JVM suite + detekt | `cd desktop && gradlew test detekt` | 300 tests, 0 failures, 0 skipped (with DLLs staged); detekt maxIssues=0, tests linted too |
| Shared-manifest pins | `pytest tests/test_desktop_shared_manifest.py` | android-free (broadened package regex), sorted, twins, build consumes manifest |
| SQLite seam pins | `pytest tests/test_win_sqlite_seam.py` | production define set pinned WITH values (single-sourced), UTF-16 boundary, UTF-8 file encoding, shim-derived JNI gate, win32 driver run |
| MSVC pure-core smoke | `pytest tests/test_win_msvc_smoke.py` | 8 batteries under cl.exe; banter golden FNV-1a64 `de4bd8227a3ab0d1` |
| Native build lanes | `python scripts/build_win_deps.py` then `python tools/build_win_realm_runtime.py [--extractors]` then `python tools/build_win_sqlite_seam.py` | both realm DLLs + the seam DLL + four extractor exes; JNI exports derived from the Kotlin shims; deps smoke asserts the pinned sqlite 3.46.1; submodule byte-pristine after every run |
| Co-load gate | part of `gradlew test` (NativeCoLoadTest) | both realm DLLs loaded in ONE JVM; status ABI width/version/STOPPED pinned, not just "did not throw" |
| Seed bring-up | `cd desktop && gradlew seedRealmData` | four databases into `%LOCALAPPDATA%\PocketRealm\database\sqlite-datadir`; statement counts match the C pinned-amalgamation harness exactly (43 / 1,110,192 / 12 / 27,886) |
| realmd listener gate | `cd desktop && gradlew bootRealmd` | in-process realmd; 127.0.0.1:3724 accepts; clean stop; no WAL sidecars |
| Protocol-auth gate | `cd desktop && gradlew authGate` | full 1.12 SRP6 logon: M1 accepted, M2 verified, session key persisted server-side; platform/os parse back to `x86`/`Win` (NUL-last wire form); full 26-byte proof frame drained with LoginFlags checked |
| World boot gate | `cd desktop && gradlew bootWorld` | world READY with vmaps+mmaps; 8085 accepts; save rc=0; clean stops incl. WAL seal |
| World one-lifetime restart gate | `cd desktop && gradlew bootWorld -DbootCycles=2` (or `JAVA_TOOL_OPTIONS=-DbootCycles=2`) | cycle 1 boots/saves/stops fully; cycle 2's second in-process start is REFUSED fast with WRONG_STATE + the restart explanation (the pinned v1 contract — see §3) |
| Whisper bridge gate | `cd desktop && gradlew whisperGate` | chat-injection surface: honest sender-not-online / unknown-channel; onlinePlayers honest; memory state JSON |
| Conf golden pins | part of `gradlew test` (DesktopServerRuntimeFilesTest) | full mangosd.conf body + bots-disabled block pinned line-by-line against the Android twin; debug-toggle LogFileLevel=3 variant; secureWrite leaves no temp debris across rewrites |
| Ownership contract | part of `gradlew test` (DesktopRuntimeBackendTest) | start claims + every observation echoes the owner; foreign-owner stops withheld; adopt refuses non-orphans; claim released on clean stop |
| Packaged native smoke | `cd desktop && gradlew packageApp`, then from any directory: `JAVA_TOOL_OPTIONS=-Dpocketrealm.nativeSmoke=1` run `build/package/PocketRealm/PocketRealm.exe` | the packaged exe loads its bundled `pocket_sqlite.dll` through the launcher-expanded `$APPDIR` library path (the old `.` path could never find them) and exits 0; packageApp re-runs idempotently |
| Packaged app launch | `cd desktop && gradlew packageApp`, then run `build/package/PocketRealm/PocketRealm.exe` | jpackage app image with DLLs + seeds + provenance; windowed app launches and runs on its bundled JVM |

CI: `hygiene` (ubuntu, check_repo + pytest), `android-unit` and
`desktop-unit` (windows-latest) on every push, plus the dispatch/weekly
`desktop-native` lane (MSVC deps + DLLs + `gradlew test detekt
-PrequireNatives`, so every DLL-guarded test executes instead of
skipping).

## 2. Human-in-the-loop campaign (needs the user at the keyboard)

1. One-time data preparation (already done on the dev box):
   `python tools/win_prepare_data.py` — extracts dbc/maps/vmaps/mmaps
   from the client (`C:\Vanilla wow 1.12.1`), assembles the verified
   PreparedDataStore generation (10,673 files).
2. `cd desktop && gradlew launchClient` — boots database + realm +
   world, then launches WoW.exe with the realmlist projected at
   127.0.0.1.
3. Log in with the gate's account (`AUTHGATE` / `AuthGate-Password-1`)
   or create one in-game; enter the world.
4. Whisper a bot. Expected (mirrors the Android whisper-lane fix,
   commit 864e94a): the whisper dispatches to the bot's LLM lane, the
   relationship is minted, and the N4 counters move. Without a
   configured cloud LLM endpoint the dispatch runs and the reply fails
   gracefully — configure the endpoint (Phase-5 CloudLaneConf UI is
   the remaining port) for live replies.
5. Press Enter in the console to save + stop the realm cleanly.

## 3. Deliberately deferred (tracked, not forgotten)

- In-process world RESTART (second world lifetime in one process): after a
  successful boot+stop, the embedded cmangos lane cannot re-initialize in
  place. Three real second-boot defects were found and fixed along the way
  (unjoined LFG/BG queue threads → `std::terminate` on re-assign; the asio
  `io_context` never `restart()`ed → a zombie listener; AhBot running on
  uninitialized config state from a recycled heap → `ForceUpdate` on
  garbage). Past those, the second boot deterministically FREEZES inside
  its first `World::Update` ticks (tick 0 completes, tick 1 never returns;
  verified by tick-level logging and a minidump of the hung process — no
  thread remains inside the world DLL), and the 30 s stop timeout then
  frees the DB pools under the hung thread and takes the JVM down with
  0xC0000005. The v1 contract is therefore ONE world lifetime per process:
  the facade refuses a second start after READY with WRONG_STATE and the
  restart explanation (`gradlew bootWorld -DbootCycles=2` pins it), and
  the app process restarts for another world lifetime. A true re-init
  audit of the cmangos singleton surface (script system, map manager,
  accessors) is the fast-follow; everything learned lives in the
  windows-port review-fix PLAN-LOG entry.
- Kill matrix (taskkill /F mid-world-run / mid-db-init) and the
  long-session soak: the supervisor journal + WAL seals are in place;
  the scripted matrix runner lands with the engine-twin recovery flow.
- The Bots + LLM settings screens (population profiles, admission,
  CloudLaneConf endpoint UI) and the HomeScreen port — the conf lane
  currently ships the reviewed bots-disabled block.
- The Windows native lockfile (`schemas/realm-runtime-lockfile-sqlite-win.json`,
  PE import tables) — `BuildConfig` stays loudly `unpinned` until then.
- Auto-login (Win32 SendInput) — v1 is manual password per launch.
- Code signing / SmartScreen posture — unsigned for development.
