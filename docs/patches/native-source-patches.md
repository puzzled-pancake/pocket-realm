# Pocket Realm native-source patches

These patches isolate the minimal changes required to cross-compile the pinned
upstream sources for Android arm64-v8a with NDK r30. Each documents *why*
upstream behavior is insufficient. They are recorded here so a re-pin or
upstream update re-applies them deliberately rather than silently.

## Environment

- NDK r30 (30.0.15729638), CMake 4.1.2, Ninja 1.12 (all SDK-bundled)
- MSYS2 (located via `MSYS2_ROOT` env, then conventional install paths) provides the
  complete Unix-style perl (OpenSSL's android target requires a Unix-host perl;
  Git-for-Windows perl is missing core modules and Strawberry perl is
  native-Windows). MSYS2 gcc bootstraps Boost's b2.
- NDK junction at `SDK/ndk-link` avoids Windows 8.3 short-name path mismatches
  in OpenSSL's toolchain detection. `build_native.py ensure_ndk_link()` recreates
  it if it ever points at a stale NDK version.

## Patches

### cmangos — `dep/include/gsoap/stdsoap2.h:815`

**Change:** wrap `#include <sys/timeb.h>` in `#ifndef __ANDROID__`.

`HAVE_SYS_TIMEB_H` is set by gSOAP's platform detection for a generic Linux
target; Android's Bionic libc removed the obsolete `<sys/timeb.h>` header (and
`ftime()`). The include is guarded by `#ifdef HAVE_SYS_TIMEB_H`; skip it on
`__ANDROID__`. gSOAP does not call `ftime()` in code paths used by CMaNGOS, so
behavior is unchanged.

### playerbots — `playerbot/strategy/tests/TestAction.cpp:401`

**Change:** `sLog.outString(msg.c_str())` → `sLog.outString("%s", msg.c_str())`

NDK r30 enables `-Werror=format-security` by default, which rejects non-literal
format strings. The test code passed a runtime string directly as a format
string. Passing it as an explicit `"%s"` argument is the standard secure-coding
fix and does not change behavior.

This patch must be applied in two places because of how the playerbots source
enters the build (see next item): once in the submodule at
`native/playerbots/.../TestAction.cpp`, and once in the in-tree mirror at
`native/cmangos/src/modules/PlayerBots/.../TestAction.cpp`.

### cmangos — `src/CMakeLists.txt:28` FetchContent pinning (reproducibility)

**Change:** remove the `GIT_REPOSITORY`/`GIT_TAG` lines from the PlayerBots
`FetchContent_Declare`, leaving it SOURCE_DIR-only.

CMaNGOS declares PlayerBots with both `SOURCE_DIR=.../modules/PlayerBots` and
`GIT_REPOSITORY=github.com/cmangos/playerbots.git` (`GIT_TAG=master`). When
FetchContent_Populate runs it honors the GIT_REPOSITORY and **re-clones upstream
`master` at whatever the latest commit is**, overwriting the pinned submodule
mirror with unpinned, unpatched upstream code. The build then links an
unaudited commit and (because the local patches are absent) hits the format-
security `-Werror`.

With GIT_REPOSITORY removed, FetchContent uses the SOURCE_DIR we mirror from
the pinned submodule verbatim. This is the change that actually makes the
playerbots build reproducible and pinned to the audited commit.

### playerbots — FetchContent `modules/PlayerBots` source location (build-time integration)

**Not a code patch; a reproducibility step encoded in `build_native.py`.**

CMaNGOS's `src/CMakeLists.txt` declares the PlayerBots dependency via
FetchContent in **SOURCE_DIR** form (`SOURCE_DIR=.../src/modules/PlayerBots`),
so CMake expects the playerbots source to already exist at that path rather than
cloning it. On a clean checkout that directory is absent (gitignored by the
cmangos submodule under `src/modules/`).

`build_native.py ensure_playerbots()` mirrors the pinned `native/playerbots`
submodule into `native/cmangos/src/modules/PlayerBots` (idempotent via a
`.pocket-realm-source` marker; `.git` is excluded so it is a plain source tree,
and a force-remove handler clears the read-only bit on git pack files during a
refresh) and `cmangos()` passes `-DBUILD_PLAYERBOTS=ON`. Combined with the
FetchContent pinning patch above, this makes the playerbots build reproducible
from a clean checkout at the audited commit. Because the mirror is refreshed
from the submodule, the source patch above must land in the submodule copy
(re-running `ensure_playerbots()` propagates it into the mirror).

## Embeddable realm lifecycle facade (POCKET_EMBEDDED)

The library lane makes the CMaNGOS/Playerbots realm controllable in-process via a versioned
C ABI (`schemas/abi/pocket_realm.h`), driven by the Android supervisor without
process exit, console-only control, or signal-only shutdown.
The patches below isolate every change to the pinned upstream trees so a re-pin
re-applies them deliberately.

### cmangos — `src/shared/Util/Errors.h` POCKET_FATAL macro

**Change:** add a `POCKET_FATAL(msg)` macro. When `POCKET_EMBEDDED` is defined
(only the `libpocketrealm.so` target defines it), it calls
`pocket_realm::embed::throw_fatal(msg)` (defined in
`native/pocket-runtime/src/embed.cpp`), which throws a `fatal_error` the facade
catches at the ABI boundary and converts to a `realm_err`. When
`POCKET_EMBEDDED` is **not** defined (the standalone `mangosd`/`realmd`
executables), it expands to `::exit(1)` — bit-for-bit identical to the prior
behavior.

**Why:** CMaNGOS terminates the process on unrecoverable startup errors. That
is correct for a standalone server whose process IS the failure domain, but a
bad DB must not kill the host Android app. The embedded build routes those
fatal paths into a catchable exception instead.

### cmangos — 18 `exit(1)` startup sites → `POCKET_FATAL`

**Change:** replace every `exit(1)`/`exit(-1)` in the startup path with
`POCKET_FATAL("...")`. The 18 sites are in `game/Server/DBCStores.cpp` (×4:
DBC directory/files/version gates), `game/Globals/ObjectMgr.cpp` (×7: pet
stats, playercreateinfo, class/race level stats, xp tables),
`game/World/World.cpp` (×3: map files, mangos_string, DBC locale),
`shared/Database/SQLStorageImpl.h` (×2: missing/broken table),
`shared/Database/DatabaseMysql.cpp` (×1: thread-unsafe lib — not compiled under
DO_SQLITE but patched for consistency), and `mangosd/MaNGOSsoap.cpp` (×1: bind
failure on the SOAP worker thread).

**Why:** these are exactly the calls that would kill the host app process on bad
data. Under `POCKET_EMBEDDED` they throw instead, classified by the facade as
`REALM_E_FATAL_STARTUP` (or `REALM_E_BLOCKED_ON_CLIENT_DATA` for the `.map`/
`.dbc` gates that indicate the client-data import hasn't run).

### cmangos — `src/mangosd/Master.{h,cpp}` embeddable lifecycle hooks

**Change:** add `Master::StartDatabasesEmbedded()`, `InitWorldEmbedded(bool*)`,
`StartNetworkEmbedded(uint32)`, `StopEmbedded()` public methods (and the
`m_worldThread`/`m_worldListener`/`m_netThreads` members they own). Gate the
standalone `Master::Run()` and the signal handlers (`_OnSignal`/`_HookSignals`/
`_UnhookSignals`) under `#ifndef POCKET_EMBEDDED`.

**Why:** `Run()` is a blocking monolith (PID file, DB, world init, signals, CLI
thread, blocking wait, teardown) designed for a standalone process. The facade
needs the same reusable phases but on its own worker thread, without signals,
the console thread, the blocking wait, or process exit. The standalone path is
unchanged (the gates exclude nothing when `POCKET_EMBEDDED` is off).

### cmangos — `src/game/World/World.h` ResetForReinit

**Change:** add a `static void World::ResetForReinit()` (under
`#ifdef POCKET_EMBEDDED`) that clears the static `m_stopEvent`/`m_ExitCode`
flags so a second realm generation can start in the same process.

**Why:** the world stop gate is static; after a cooperative stop it stays set
and a second `WorldRunnable` loop would exit immediately. Resetting it (called
at the end of `StopEmbedded`) enables the in-process re-entrancy the
library-lane acceptance criterion requires ("twice in one process").

### cmangos — `src/mangosd/Main.cpp` gate `main()`

**Change:** wrap `int main(int argc, char* argv[])` in
`#ifndef POCKET_EMBEDDED`. The file-scope globals above `main`
(`WorldDatabase`, `CharacterDatabase`, `LoginDatabase`, `LogsDatabase`,
`realmID`, `m_ServiceStatus`) are **kept** so the embedded build has exactly one
definition of each; the facade drives the lifecycle hooks instead of `main`.

**Why:** `main()` is the standalone entry point (arg parsing, service dispatch,
config load, OpenSSL providers, `sMaster.Run()`). The embedded runtime is
driven by `realm_create`/`realm_start`; compiling `main()` into the shared
library would collide with the host's `main` and pull in the standalone flow.

### cmangos — `src/realmd/Main.cpp` gate globals + main + helpers

**Change:** wrap realmd's file-scope globals (`stopEvent`, `restart`,
`LoginDatabase`, the io_context), `int main(...)`, and the signal/DB helpers
(`OnSignal`, `StartDB`, `HookSignals`, `UnhookSignals`) in
`#ifndef POCKET_EMBEDDED`.

**Why:** realmd's `LoginDatabase` would collide with mangosd's (both are
`DatabaseType LoginDatabase;` in different TUs — a duplicate symbol once both
compile into one shared library). The embedded runtime uses the single shared
`LoginDatabase` from mangosd/Main.cpp; `lifecycle_realmd.cpp` drives the auth
listener with a facade-owned io_context and stop flag instead of signals.

### cmangos — `src/mangosd/CliRunnable.cpp` gate `run()`

**Change:** wrap `CliRunnable::run()` (the console stdin loop) in
`#ifndef POCKET_EMBEDDED`. The `ChatHandler::Handle*` command implementations
above `run()` in the same file are **kept**.

**Why:** the embedded path has no console; commands go through
`realm_command` → `sWorld.QueueCliCommand` directly. But `libgame`'s command
table references the command implementations (`HandleServerExitCommand`,
`HandleAccountCreateCommand`, etc.) that live in this TU, so the file must
still compile — only the stdin-reading `run()` is excluded.

### cmangos — `CMakeLists.txt` + `src/CMakeLists.txt` build hooks

**Change:** in the root `CMakeLists.txt`, set
`CMAKE_POSITION_INDEPENDENT_CODE ON` when `BUILD_POCKET_RUNTIME` is on (so
libgame/libshared/libframework/libplayerbots compile PIC and can link into a
shared library). In `src/CMakeLists.txt`, add the pocket-runtime subdir
conditionally on `BUILD_POCKET_RUNTIME` + `POCKET_RUNTIME_DIR`. PIC objects
link cleanly into executables too, so the standalone mangosd/realmd are
unaffected.

### build_native.py — PIC Boost + `--runtime` flag

**Change:** Boost b2 now builds with `cxxflags=-fPIC cflags=-fPIC` (required to
link the static Boost archives into libpocketrealm.so; the standalone
executables accept PIC too). The stale-target guard key includes `+pic` so a
re-run after this change cleans bin.v2. Added `--runtime`/`--runtime-tests`
flags that pass `-DBUILD_POCKET_RUNTIME=ON -DPOCKET_RUNTIME_DIR=...`.

### Client-import extractors — bounded MPQ `(listfile)` parsing

**Change:** `native/patches/o11-cmangos-safe-mpq-listfile.patch` replaces the
two extractor copies of `MPQArchive::GetFileListTo` with a transferred-length-
bounded parser. It allocates an explicit trailing NUL, uses `memchr` within the
reported byte count, trims CR safely, and ignores empty records. The pinned
source submodule stays clean: `tools/build_o11_extractors.py` materializes the
exact CMaNGOS commit into an ignored build tree, applies the hash-pinned patch,
then builds the four Android x86_64 extractor PIEs.

**Why:** upstream allocated exactly the listfile's unpacked size and passed the
non-NUL-terminated bytes to `strtok`. The real build-5875 VMAP run reached an
Android page boundary and crashed at `mpq_libmpq04.h:67` with SIGSEGV. The
bounded parser completed the same read-only client extraction and also removes
the corresponding latent defect from the DBC/map extractor.

### Realm runtime — graceful MMap miss guards (`MoveMap.cpp`)

**Change:** two `tools/build_o09_realm_runtime.py` overlays
(`mmap-loadmap-graceful-miss`, `mmap-loadallmaptiles-graceful-miss`) replace
the `MANGOS_ASSERT(itr != loadedMMaps.end())` preconditions in
`MMapManager::loadMap` and `MMapManager::loadAllMapTiles` with an
`sLog.outError` plus early return (`false` for `loadMap`, plain return for the
void `loadAllMapTiles`). A map whose navmesh was never registered — a missing
`mmaps/NNN.mmap` — now disables pathfinding for that map instead of aborting
the world process; the distinct error strings keep overlay cleanup symmetric.

**Why:** the fatal precondition is safe only when every enterable map ships a
navmesh header. A bot entering Uldaman (map 070, WMO-only, no ADT terrain)
after the import mmap stage skipped it killed `com.pocketrealm:world` with SIGABRT
at `MoveMap.cpp:202` (tombstone 2026-08-16 15:10). The companion import fix
derives the generation map list from `maps/*.map` union `vmaps/*.vmtree` so
WMO-only dungeons generate real navmeshes; these guards make any residual gap
(corrupted or deleted content) degrade instead of kill. The `loadMap` anchor
must keep its full trailing comment: `loadAllMapTiles` carries a byte-identical
bare assert earlier in the file and `replace_anchor` patches the first match.

## Reproduction

See `scripts/build_native.py` for the full reproducible build (stages:
openssl, boost, sqlite, cmangos). Most code patches live in the upstream working
trees (submodules) at the paths above; a clean re-pin requires re-applying them.
The client import is the exception: its external patch, patch hash, clean source
materialization, and extractor hashes are automated by
`tools/build_o11_extractors.py` and `schemas/o11-extractor-lockfile.json`. The
FetchContent integration is automated and needs no manual step.
