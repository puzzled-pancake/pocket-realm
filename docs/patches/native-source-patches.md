# Pocket Realm native-source patches

These patches isolate the minimal changes required to cross-compile the pinned
upstream sources for Android arm64-v8a with NDK r30. Each documents *why*
upstream behavior is insufficient. They are recorded here so a re-pin or
upstream update re-applies them deliberately rather than silently.

## Environment

- NDK r30 (30.0.15729638), CMake 4.1.2, Ninja 1.12 (all SDK-bundled)
- MSYS2 at `G:\msys64` provides the complete Unix-style perl (OpenSSL's android
  target requires a Unix-host perl; Git-for-Windows perl is missing core modules
  and Strawberry perl is native-Windows). MSYS2 gcc bootstraps Boost's b2.
- NDK junction at `SDK/ndk-link` avoids Windows 8.3 short-name path mismatches
  in OpenSSL's toolchain detection.

## Patches

### playerbots — `playerbot/strategy/tests/TestAction.cpp:401`
`HAVE_SYS_TIMEB_H` is set by gSOAP's platform detection for a generic Linux
target; Android's Bionic libc removed the obsolete `<sys/timeb.h>` header. The
include is guarded by `#ifdef HAVE_SYS_TIMEB_H`; skip it on `__ANDROID__`.
gSOAP does not call `ftime()` in code paths used by CMaNGOS, so behavior is
unchanged.

**Change:** `sLog.outString(msg.c_str())` → `sLog.outString("%s", msg.c_str())`

NDK r30 enables `-Werror=format-security` by default, which rejects non-literal
format strings. The test code passed a runtime string directly as a format
string. Passing it as an explicit `"%s"` argument is the standard secure-coding
fix and does not change behavior.

**Location in build:** `native/cmangos/src/modules/PlayerBots/...` (CMaNGOS's
FetchContent populates the playerbots source there from the submodule).

### cmangos — `dep/include/gsoap/stdsoap2.h:815`
**Change:** wrap `#include <sys/timeb.h>` in `#ifndef __ANDROID__`.

`HAVE_SYS_TIMEB_H` is set by gSOAP's platform detection for a generic Linux
target; Android's Bionic libc removed the obsolete `<sys/timeb.h>` header. The
include is guarded by `#ifdef HAVE_SYS_TIMEB_H`; skip it on `__ANDROID__`.
gSOAP does not call `ftime()` in code paths used by CMaNGOS, so behavior is
unchanged.

## Reproduction

See `scripts/build_native.py` for the full reproducible build (stages:
openssl, boost, sqlite, cmangos). The patches live in the upstream working
trees (submodules) at the paths above; a clean re-pin requires re-applying them.
