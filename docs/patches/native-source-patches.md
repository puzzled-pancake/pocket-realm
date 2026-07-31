# Pocket Realm native-source patches

These patches isolate the minimal changes required to cross-compile the pinned
upstream sources for Android arm64-v8a with NDK r30. Each documents *why*
upstream behavior is insufficient. They are recorded here so a re-pin or
upstream update re-applies them deliberately rather than silently.

## Environment

- NDK r30 (30.0.15729638), CMake 4.1.2, Ninja 1.12 (all SDK-bundled)
- MSYS2 (located via `MSYS2_ROOT`/`MSYS_HOME`, default `G:\msys64`) provides the
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

### playerbots — FetchContent `modules/PlayerBots` source location (build-time integration)

**Not a code patch; a reproducibility step encoded in `build_native.py`.**

CMaNGOS's `src/CMakeLists.txt` declares the PlayerBots dependency via
FetchContent in **SOURCE_DIR** form (`SOURCE_DIR=.../src/modules/PlayerBots`),
so CMake expects the playerbots source to already exist at that path rather than
cloning it. On a clean checkout that directory is absent (gitignored by the
cmangos submodule under `src/modules/`).

`build_native.py ensure_playerbots()` mirrors the pinned `native/playerbots`
submodule into `native/cmangos/src/modules/PlayerBots` (idempotent via a
`.pocket-realm-source` marker) and `cmangos()` passes `-DBUILD_PLAYERBOTS=ON`.
This makes the playerbots build reproducible from a clean checkout instead of
relying on a one-time manual copy. Because FetchContent *copies* from the
SOURCE_DIR rather than symlinking it, the source patch above must land in both
trees (the mirror is refreshed from the submodule, so editing the submodule copy
and re-running propagates it).

## Reproduction

See `scripts/build_native.py` for the full reproducible build (stages:
openssl, boost, sqlite, cmangos). The code patches live in the upstream working
trees (submodules) at the paths above; a clean re-pin requires re-applying them.
The FetchContent integration is automated and needs no manual step.
