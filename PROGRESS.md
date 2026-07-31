# Current project state

Last verified commit: `O02 Android shell (pending commit)`
Active feature: `none` (O02 marked done)
Current milestone: `A2 — native ARM64 realm runtime`

## Last successful checks
- `./gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL (AGP 9.3.1, Kotlin 2.0.21, Compose BOM 2024.12.01)
- APK installed + launched on emulator-5554 (API 35, x86_64) → Home/Settings/Diagnostics render
- Supervisor lifecycle verified on device: Idle → Starting → Running → Saving → Idle
- Foreground notification verified via dumpsys: FOREGROUND_SERVICE flag, title "Pocket Realm", text "Running · tap to play", action "Save & Exit"
- Storage separation verified via adb: mutable `files/realm/{db,generations}` internal-only; only `exports` on external; no realm-data leak

## Current state
- Android Compose shell complete under `android/`.
  - `RealmSupervisor`: typed state machine (Idle/Starting/Running/Saving/Stopping/Recovering/Failed) with a legal-transition table; `Running` reachable only after all `HealthCondition`s hold.
  - `RealmService`: foreground service (dataSync type); notification reflects state + offers Save & Exit; onDestroy intentionally does NOT save (dirty-start recovery is the real boundary).
  - `StorageRoots`: separated roots — internal mutable realm/content/runtime, external exports only.
  - `Settings` (DataStore): bounded advanced presets (provider/renderer/FPS/bots); generation-managed settings stay out of here.
  - `AppLog`: structured, redact-safe logging ring feeding the Diagnostics screen.
  - UI: Home (primary action + live status), Settings (bounded), Diagnostics (storage/provenance/log).
- Offline flavor honored: no gameplay INTERNET permission; only FOREGROUND_SERVICE/POST_NOTIFICATIONS/WAKE_LOCK.

## Blockers
- None. (Native realm bring-up in O03-O05 will replace the simulated health sequence in RealmService.)

## Next action
- Run `python3 scripts/next_feature.py --activate` to select **O03** (portable ARM64 CMaNGOS + Playerbots build).

## Session note
Replace this file with the current state; do not append a permanent diary. Git history is the durable record.
