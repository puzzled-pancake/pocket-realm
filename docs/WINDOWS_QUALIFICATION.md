# Pocket Realm for Windows — Qualification

The Windows sibling of the Android app (same repo, shared sources; see
`desktop/shared-sources.json` and the Windows-port PLAN-LOG entries).
This document is the test campaign record: what is machine-gated, what
is human-in-the-loop, and what is deliberately deferred.

## 1. Automated gates (all green on the dev box, 2026-09-10)

| Gate | How to run | Evidence |
| --- | --- | --- |
| Desktop JVM suite + detekt | `cd desktop && gradlew test detekt` | 294 tests, 0 failures, 0 skipped; detekt maxIssues=0 |
| Shared-manifest pins | `pytest tests/test_desktop_shared_manifest.py` | android-free, sorted, twins, build consumes manifest |
| SQLite seam pins | `pytest tests/test_win_sqlite_seam.py` | production define set (single-sourced), UTF-16 boundary, UTF-8 file encoding, shim-derived JNI gate, win32 driver run |
| MSVC pure-core smoke | `pytest tests/test_win_msvc_smoke.py` | 8 batteries under cl.exe; banter golden FNV-1a64 `de4bd8227a3ab0d1` |
| Native build lanes | `python tools/build_win_deps.py` then `python tools/build_win_realm_runtime.py [--extractors]` | both realm DLLs + the seam DLL + four extractor exes; JNI exports derived from the Kotlin shims; submodule byte-pristine after every run |
| Co-load gate | part of `gradlew test` (NativeCoLoadTest) | both realm DLLs loaded in ONE JVM, both shims resolving |
| Seed bring-up | `cd desktop && gradlew seedRealmData` | four databases into `%LOCALAPPDATA%\PocketRealm\database\sqlite-datadir`; statement counts match the C pinned-amalgamation harness exactly (43 / 1,110,192 / 12 / 27,886) |
| realmd listener gate | `cd desktop && gradlew bootRealmd` | in-process realmd; 127.0.0.1:3724 accepts; clean stop; no WAL sidecars |
| Protocol-auth gate | `cd desktop && gradlew authGate` | full 1.12 SRP6 logon: M1 accepted, M2 verified, session key persisted server-side; realmd logs "successfully authenticated" |
| World boot gate | `cd desktop && gradlew bootWorld` | world READY with vmaps+mmaps; 8085 accepts; save rc=0; clean stops incl. WAL seal |
| Whisper bridge gate | `cd desktop && gradlew whisperGate` | chat-injection surface: honest sender-not-online / unknown-channel; onlinePlayers honest; memory state JSON |
| Packaged app launch | `gradlew packageApp`, then run `build/package/PocketRealm/PocketRealm.exe` | jpackage app image with DLLs + seeds + provenance; windowed app launches and runs on its bundled JVM |

CI: `hygiene` (ubuntu, check_repo + pytest) and `android-unit` +
`desktop-unit` (windows-latest) run on every push.

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
