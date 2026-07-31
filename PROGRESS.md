# Current project state

Last verified commit: `O04 embeddable realm lifecycle facade (pending commit)`
Active feature: `none` (O04 marked done)
Current milestone: `A2 — native ARM64 realm runtime (O05 next)`

## Last successful checks
- `scripts/build_native.py --abi x86_64 --runtime --runtime-tests cmangos` -> ALL STAGES OK
- `scripts/build_native.py --abi arm64-v8a --runtime --runtime-tests all` -> ALL STAGES OK
- `scripts/smoke_native.py --abi x86_64 --device --runtime` -> SMOKE OK (standalone
  mangosd/realmd + libpocketrealm.so ELF checks + on-device lifecycle test)
- `tools/run_realm_test.py --abi x86_64` -> NATIVE LIFECYCLE TEST: PASS (exit 0),
  2 full create/start/health/save/stop/destroy cycles in one process
- `:app:testDebugUnitTest RealmNativeTest` -> PASS (JNI shim graceful-degradation)
- Standalone `mangosd`/`realmd` build unchanged (the POCKET_FATAL + lifecycle
  patches are gated under POCKET_EMBEDDED, off for the executables)
- ELF LOAD segments aligned 0x4000 (16 KB page-size compatible) for both ABIs;
  libpocketrealm.so dynamic deps only libdl/libm/libc++_shared/libc

## Current state — O04 done
- **Versioned C ABI** (`schemas/abi/pocket_realm.h`, mirrored to
  `native/pocket-runtime/include/`): `realm_create/start/get_health/command/save/
  checkpoint/request_stop/join/get_state/destroy` with opaque handles, error
  codes, bounded buffers, the 6 PLAN.md A2 health conditions, and a
  `BLOCKED_ON_CLIENT_DATA` status for honest client-data-gate reporting.
- **libpocketrealm.so** (`native/pocket-runtime/`): the facade. Realm owns a
  worker thread that drives CMaNGOS startup without signals/console/blocking/
  exit. The C ABI surface (abi.cpp) catches every native throw at the boundary.
- **POCKET_EMBEDDED patch set** (documented in `docs/patches/`): the 18
  `exit(1)` startup gates route to `POCKET_FATAL` (throw) under the embedded
  build; `Master::Run`/signal handlers/console loop/realmd-main are gated out;
  `Master::StartDatabasesEmbedded/InitWorldEmbedded/StartNetworkEmbedded/
  StopEmbedded` + `World::ResetForReinit` + `Database::StopServerEmbedded` are
  the reusable phases the facade drives.
- **Strategy A re-entrancy proven**: a full create/start/.../destroy cycle runs
  twice in one process. The 4 DatabaseType globals are `StopServer`'d and
  `World::m_stopEvent` reset between cycles so the second `_StartDB`->Initialize
  sees fresh objects.
- **tools/seed_realm_db.py**: MySQL-dump -> SQLite translation. realmd /
  characters / logs seed with 0 errors and pass `CheckRequiredField`. The world
  (mangos) DB seeds (947 stmts, 7 tolerated edge cases) but is at an older
  schema snapshot than the core expects (z2815 vs z2830) — the **O06 migration
  boundary**, surfaced honestly as `_StartDB` FAILED, not faked green.
- **Honest health**: the 3 world-loop conditions (WORLD_LOOP_RUNNING /
  LOCAL_ENDPOINTS_LISTENING / BOT_SUBSYSTEM_INITIALIZED) are blocked on the
  proprietary `.dbc`/`.map` client-data import (O10); they would report
  `BLOCKED_ON_CLIENT_DATA` once the world machinery reaches them.
- **Code review**: a focused review found and fixed 4 HIGH defects (destructor
  not joining `m_worker` -> `std::terminate`; no teardown on the FAILED path;
  `blocker_text` returning a pointer into a worker-mutated string; realmd-state
  type-pun UB) plus 3 MEDIUM (stale `m_terminal`, realmd double-Initialize,
  fake-success `command`). The destructor join was the trickiest: `Realm::join`
  waits on the cv but does not join the `std::thread`; `~Realm` must call
  `m_worker.join()` explicitly or `~thread()` terminates.
- **JNI shim**: `RealmNative.kt` declares the `external fun` surface for O05
  wiring; the host JVM test verifies it reports a missing `.so` loudly
  (UnsatisfiedLinkError), not a stub. The JNI C bridge is O05.

## What is NOT done (deferred)
- **O05**: supervisor↔native wiring, foreground-service native bring-up,
  health-gated Running promotion, loopback-only enforcement audit.
- **O06**: the world (mangos) DB migration chain (z2815 -> z2830+) and full
  SQLite differential parity. Until then the world DB schema check fails and
  `_StartDB` reports a DB error.
- **O10**: client import. The 3 world-loop health conditions stay blocked on
  `.dbc`/`.map` data; no synthetic/fake client data.

## Build-host notes
- Boost now builds with `-fPIC` (required to link the static archives into a
  shared lib; standalone executables accept PIC too). The stale-target guard
  key includes `+pic` so a re-run after this change cleans `bin.v2`.
- `CMAKE_POSITION_INDEPENDENT_CODE ON` is set when `BUILD_POCKET_RUNTIME` is on,
  so libgame/libshared/libframework/libplayerbots compile PIC.
- `--runtime` / `--runtime-tests` flags on `build_native.py` gate the facade
  build (off by default -> O03's exact build is the default behavior).

## Blockers
- None.

## Next action
- Run `python3 scripts/next_feature.py --activate` to select **O05** (loopback
  auth/world endpoints and Android realm supervision) — the facade is ready to
  be wired to the supervisor.

## Session note
Replace this file with the current state; do not append a permanent diary. Git
history is the durable record.
