# Current project state

Last verified commit: `O03 native ARM64 build (pending commit)`
Active feature: `none` (O03 marked done)
Current milestone: `A2 — native ARM64 realm runtime (O04 next)`

## Last successful checks
- `scripts/build_native.py` produces all native artifacts (openssl/boost/sqlite/cmangos stages)
- `mangosd` (game server) + `realmd` (auth server): elf64-littleaarch64, link cleanly
- `libplayerbots.a` built into the mangosd link (bot subsystem present)
- ELF LOAD segments aligned to 0x4000 (16 KB page-size compatible)
- No -mcpu/-mtune core-type flags (portable across heterogeneous cores)
- Dynamic deps: only libdl/libm/libc++_shared/libc (Boost/OpenSSL/SQLite statically linked)

## Current state
- External dependencies cross-compiled for arm64-v8a into native/.deps/prefix-arm64:
  OpenSSL 3.4.3, Boost 1.86.0 (6 libs), SQLite 3.46.1 — all verified aarch64.
- CMaNGOS Classic + Playerbots build against the pinned NDK with SQLite backend.
- Three documented patches (native/.deps/patches/README.md): gsoap sys/timeb.h
  (Android Bionic), Playerbots TestAction.cpp format-security (NDK -Werror),
  and the FetchContent modules/PlayerBots source location.
- Environment: MSYS2 installed at G:\msys64 (perl/make/gcc); NDK junction at
  SDK/ndk-link. build_native.py encodes the reproducible flow.
- Startup sequence in RealmService is still simulated; O04-O05 swap in the
  embeddable lifecycle facade and real native bring-up against these binaries.

## Blockers
- None. (mangosd is 496 MB unstripped; a strip step is appropriate before packaging.)

## Next action
- Run `python3 scripts/next_feature.py --activate` to select **O04** (embeddable realm lifecycle facade).

## Session note
Replace this file with the current state; do not append a permanent diary. Git history is the durable record.
