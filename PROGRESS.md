# Current project state

Last verified commit: `O01 bootstrap (initial commit pending)`
Active feature: `none` (O01 marked done)
Current milestone: `A1 — reproducible source and Android shell`

## Last successful checks
- `python3 scripts/bootstrap.py` → BOOTSTRAP OK
- `python3 tools/check_sources.py` → All pinned sources verified
- NDK arm64-v8a native build smoke (libhello.so, API 26) → ok
- Android Compose debug APK build (smoke, not committed) → 8.7 MB valid APK
- `.gitignore` proprietary-data leak test → clean

## Current state
- Repository initialized on `main` with monorepo layout from PLAN.md §2.
- Upstream sources pinned as git submodules at exact commits:
  - `native/cmangos` → cmangos/mangos-classic@de8f7299 (GPL-2.0)
  - `native/classic-db` → cmangos/classic-db@be1a5206 (GPL-3.0; content = Blizzard proprietary)
  - `native/playerbots` → cmangos/playerbots@01c621f1 (GPL-2.0)
- Source pinning, licenses, and redistribution status recorded in `schemas/sources.json`.
- Offline Vanilla 1.12 flavor contract recorded in `schemas/flavor.json`.
- Proprietary client/data/game-data paths excluded from source control (`.gitignore`, tested).
- Environment fixed for this machine: `python3` resolves to real Python 3.13 (Store stub bypassed); `ANDROID_SDK_ROOT` set.

## Blockers
- None.

## Next action
- Run `python3 scripts/next_feature.py --activate` to select **O02** (Android Compose shell, supervisor state model, storage roots, foreground service).

## Session note
Replace this file with the current state; do not append a permanent diary. Git history is the durable record.
