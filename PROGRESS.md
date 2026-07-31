# Current project state

Last verified commit: `O03 self-test harness (pending commit)`
Active feature: `none` (O03 marked done; x86_64 emulator self-test added)
Current milestone: `A2 — native ARM64 realm runtime (O04 next)`

## Last successful checks
- `scripts/build_native.py --abi arm64-v8a` and `--abi x86_64` both build all native artifacts
- `mangosd` + `realmd`: link cleanly for both ABIs (arm64-v8a product; x86_64 emulator test)
- `scripts/smoke_native.py --abi arm64-v8a` -> SMOKE OK (ELF64/AArch64, 16KB align, expected deps only)
- `scripts/smoke_native.py --abi x86_64 --device` -> SMOKE OK on emulator-5554 (API35):
  mangosd --version runs (exit 0), realmd executes (reaches config-load)
- `:app:connectedDebugAndroidTest` -> 12 tests, 0 failures, 0 errors on emulator-5554
- ELF LOAD segments aligned 0x4000 (16 KB page-size compatible) for both ABIs
- Dynamic deps: only libdl/libm/libc++_shared/libc (Boost/OpenSSL/SQLite statically linked)

## Current state
- Native build driver supports two ABIs: arm64-v8a (product) and x86_64
  (emulator-only test target). The product ABI stays arm64-v8a per
  .claude/rules/native.md; x86_64 lets the realm actually EXECUTE on the
  emulator (the arm64 build can only be objdump'd on an x86 host).
- External deps cross-compiled for both ABIs under native/.deps/prefix-<triple>:
  OpenSSL 3.4.3, Boost 1.86.0 (6 libs), SQLite 3.46.1.
- CMaNGOS Classic + Playerbots build against the pinned NDK; playerbots is now
  pinned to the audited commit (FetchContent GIT_REPOSITORY removed so it can no
  longer re-clone upstream master over the patched mirror).
- Four documented patches (docs/patches/native-source-patches.md): gsoap
  sys/timeb.h (Android Bionic), Playerbots TestAction.cpp format-security,
  cmangos FetchContent pinning, and the FetchContent source-mirror build step.
- Self-test harness in place: smoke_native.py (ELF + on-device exec) and the
  first instrumented tests (RealmSupervisorTest x10, RealmServiceLifecycleTest
  x2) covering the supervisor state machine and the H1/M2/M3 service fixes.
- Startup sequence in RealmService is still simulated; O04-O05 swap in the
  embeddable lifecycle facade and real native bring-up against these binaries.
- RealmService uses START_NOT_STICKY + unconditional startForeground; FGS type
  is specialUse (persistent local realm; dataSync's 6h cap is unsuitable).

## Build-host fixes this pass (x86_64 enablement)
- run_msys: switched -lc -> -c (login shell reset PATH and dropped the NDK
  toolchain) and pass ANDROID_NDK_ROOT in MSYS-mount form so OpenSSL's clang
  regex matches `which clang`.
- ABI-switch stale-object guards: openssl/boost clean when the target/triple
  changes (avoids mixed-arch archives).
- Prefix paths passed to b2/openssl in MSYS-mount form (/g/...) so the non-login
  shell does not mangle drive-letter paths into a malformed <G:> tree.
- ensure_playerbots: force-remove handler clears read-only git pack files during
  a mirror refresh.

## Blockers
- None. (mangosd is ~496 MB unstripped for arm64; smoke_native.py strips before
  pushing to the device.)

## Next action
- Run `python3 scripts/next_feature.py --activate` to select **O04** (embeddable realm lifecycle facade).

## Session note
Replace this file with the current state; do not append a permanent diary. Git history is the durable record.
