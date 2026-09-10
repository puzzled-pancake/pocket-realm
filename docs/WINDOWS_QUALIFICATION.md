# Pocket Realm for Windows — Qualification

The Windows sibling of the Android app (same repo, shared sources; see
`desktop/shared-sources.json` and the Windows-port PLAN-LOG entries).
This document is the test campaign record: what is machine-gated, what is
human-in-the-loop, and what is deliberately deferred.

## 1. Automated gates (all green on the dev box, 2026-09-10)

| Gate | How to run | Evidence |
| --- | --- | --- |
| Desktop JVM suite + detekt | `cd desktop && gradlew test detekt` | 303 tests, 0 failures, 0 skipped; detekt maxIssues=0 (incl. the bots-conf and external-LLM conf-lane pins) |
| Shared-manifest pins | `pytest tests/test_desktop_shared_manifest.py` | android-free, sorted, twins, build consumes manifest |
| Windows lane lockfile | `pytest tests/test_win_lockfile.py` | source pins mirror the android sqlite lane; PE import tables pinned (no dynamic OpenSSL imports — the regression the table exists for); BuildConfig telltale recomputed from the lockfile; seed pins == baseline; vanilla-tweaks host lane pinned |
| SQLite seam pins | `pytest tests/test_win_sqlite_seam.py` | production define set (single-sourced), UTF-16 boundary, UTF-8 file encoding, shim-derived JNI gate, win32 driver run |
| MSVC pure-core smoke | `pytest tests/test_win_msvc_smoke.py` | 8 batteries under cl.exe; banter golden FNV-1a64 `de4bd8227a3ab0d1` |
| Native build lanes | `python tools/build_win_deps.py` then `python tools/build_win_realm_runtime.py [--extractors]` | both realm DLLs + the seam DLL + four extractor exes; JNI exports derived from the Kotlin shims; `--write-lockfile` refreshes the lane lockfile; submodule byte-pristine after every run |
| Co-load gate | part of `gradlew test` (NativeCoLoadTest) | both realm DLLs loaded in ONE JVM, both shims resolving |
| Seed bring-up | `cd desktop && gradlew seedRealmData` | four databases into `%LOCALAPPDATA%\PocketRealm\database\sqlite-datadir`; statement counts match the C pinned-amalgamation harness exactly (43 / 1,110,192 / 12 / 27,886) |
| realmd listener gate | `cd desktop && gradlew bootRealmd` | in-process realmd; 127.0.0.1:3724 accepts; clean stop; no WAL sidecars |
| Protocol-auth gate | `cd desktop && gradlew authGate` | full 1.12 SRP6 logon: M1 accepted, M2 verified, session key persisted server-side; realmd logs "successfully authenticated" |
| World boot gate | `cd desktop && gradlew bootWorld` | world READY with vmaps+mmaps; 8085 accepts; save rc=0; clean stops incl. WAL seal; cycle-2 refusal pinned (one lifetime per process) |
| Whisper bridge gate | `cd desktop && gradlew whisperGate` | chat-injection surface: honest sender-not-online / unknown-channel; onlinePlayers honest; memory state JSON |
| Supervisor-path gate | `cd desktop && gradlew supervisorStartGate` | the ONLY gate driving the shared DurableRuntimeSupervisor (the app's own start path): db+realm+world through model.startRealm, WORLD_READY, clean save/stop, journal clean — catches regressions the backend-direct gates cannot see |
| Kill matrix + soak | `python tools/win_kill_matrix.py [--soak-min N]` | taskkill /F early-boot / mid-world-run (via the stdin-held launchClient victim, game client live) / mid-save; every kill followed by a full recovery boot proving SQLite's WAL recovery plus one complete clean cycle (READY, save rc=0, stop with WAL seal), no live sidecars left. NOTE: the supervisor journal is never written by these legs (they drive the backend directly); the journal's dirty-recovery contract is covered by the desktop JVM suite |
| Vanilla-tweaks host lane | `python tools/build_vanilla_tweaks.py --host` | vanilla-tweaks.exe (x86_64-pc-windows-msvc) with PE machine/subsystem/import verification + lockfile |
| Packaged app launch | `gradlew packageApp`, then run `build/package/PocketRealm/PocketRealm.exe` | jpackage app image with DLLs + app-local VC runtime + seeds + LLM assets + provenance; longPathAware launcher manifest; `JAVA_TOOL_OPTIONS=-Dpocketrealm.nativeSmoke=1` proves the exe loads its bundled natives |

CI: `hygiene` (ubuntu, check_repo + pytest) and `android-unit` +
`desktop-unit` (windows-latest) run on every push; `desktop-native`
(deps → DLLs → seam → `gradlew test detekt -PrequireNatives`) is
scheduled weekly.

## 2. Human-in-the-loop campaign (needs the user at the keyboard)

1. One-time data preparation (already done on the dev box):
   `python tools/win_prepare_data.py` — extracts dbc/maps/vmaps/mmaps
   from the client (`C:\Vanilla wow 1.12.1`), assembles the verified
   PreparedDataStore generation (10,673 files).
2. `cd desktop && gradlew launchClient` — boots database + realm +
   world (with the bot profile and LLM endpoint selected in the app),
   then launches WoW.exe with the realmlist projected at 127.0.0.1.
   The packaged app's Home screen does the same thing in one click.
3. Log in with the gate's account (`AUTHGATE` / `AuthGate-Password-1`)
   or create one in the app (Home → Local account); auto-login types it
   into the client when enabled.
4. Whisper a bot. Expected (mirrors the Android whisper-lane fix,
   commit 864e94a): the whisper dispatches to the bot's LLM lane, the
   relationship is minted, and the N4 counters move. Configure the
   external endpoint in the LLM destination for live replies (the
   spend disclosure there is the real one).
5. Stop the realm with Save & exit (Home) — clean save, WAL seal, the
   client closed first.

## 3. Deliberately deferred (tracked, not forgotten)

- The cmangos in-process re-init audit (true multi-lifetime world
  restart in one process): the documented decision is one lifetime per
  process with an honest WRONG_STATE refusal; lifting it means auditing
  ~100k lines of singletons.
- Long soak (multi-hour) beyond the scripted soak leg: the runner is in
  place; only the duration is capped by default.
- The LLM prompt-pack editor (advanced block reordering/editing) — the
  desktop LLM screen ships the default pack; `llmPromptPackJson` stays
  empty. Realm-data zip export/import (the Android Settings card) —
  the desktop's datadir lives under `%LOCALAPPDATA%` for file-level
  copies until the archive twin lands.
- Bots editor draft persistence across navigation (the Android screen's
  `rememberSaveable` + configuration savers): switching routes on the
  desktop discards an unsaved editor draft and in-progress LLM edits.
  Known parity gap, deliberate until a state-saver pass.
- Win32 SendInput auto-login is shipped with fixed timing; the Android
  app's tunable timing knobs were not ported (no IME/pointer pacing on
  a desktop).
- Code signing / SmartScreen posture — unsigned for development; the
  update check reads an opt-in feed URL (no distribution infra yet).
