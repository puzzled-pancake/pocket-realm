# PKG experiment evidence — AVD-16K-x86_64-v1 (API 35, 16 KB page)

Captured 2026-08-01. Host: Android emulator 36.6.11.0, WHPX, Windows.
Guest: API 35, system-image `system-images;android-35;google_apis_ps16k;x86_64`,
abilist `x86_64,arm64-v8a`, **page_size=16384**, MemTotal 8136552 kB.

## PKG-01 (launcher exec from nativeLibraryDir) — experiment variant
Launcher extracted with `-rwxr-xr-x` into nativeLibraryDir and executed there.
- `self_exe_path = /data/app/~~.../com.pocketrealm-.../lib/x86_64/libpocket_pkg_launcher.so`
- `page_size = 16384` (16 KB lane confirmed by the running binary itself)
- With `LD_LIBRARY_PATH=<nativeLibraryDir>`:
  `realm_path = .../lib/x86_64/libpocketrealm.so`, `realm_base = 0x791213480000`,
  `PKG_LAUNCHER_OK`, exit 0.
- Production variant (debug, useLegacyPackaging=false): `NO_EXECUTABLE_FS_PATH`
  (documented; same behavior as the 4 KB lane).

## PKG-02 (isolated library-backed crash containment) — production variant
- mainPid=5057, childPidBeforeCrash=5098
- `dlopen("libpocketrealm.so")` by SONAME: `realmLoaded=1`
- `realmDladdrPath = /data/app/~~.../com.pocketrealm-.../base.apk!/lib/x86_64/libpocketrealm.so`
  (loaded from the APK on the 16 KB image)
- `helloBeforeCrash = pocket-realm-pkg-ok`; deterministic `abort()`
- `binderDeathObserved=true`, `mainPidStillAlive=true`, child gone
- Restart → `childPidAfterCrash=5143` (NEW pid), `helloAfterRestart=pocket-realm-pkg-ok`
- Result: `CONTAINMENT_PROVEN`

## PKG-06 (startup + genuine 30-minute smoke, production variant)
- `pageSize=16384`, `realmLoaded=1`, realm loaded from APK
- ELF LOAD alignment 0x4000; `zipalign -c -P 16 -v 4` passes.

Every `.so` (libpocketrealm.so, libpocketpkgtest.so, libpocket_pkg_launcher.so,
libc++_shared.so) loads and runs on the 16 KB page-size image.

**Genuine 30-minute acceptance run** (`am instrument -e smokeSeconds 1800`):
- `RESULT ok=true code=SMOKE_OK`, `tickCount=30`
- firstTick `t=1785548086983` (tick 1) → lastTick `t=1785549827222` (tick 30)
  = 1,740,239 ms ≈ 29 min of continuous heartbeat
- same `:pkg` PID (6729) stable across all 30 ticks; hello answered every tick
- realm `.so` resident throughout (`realmDladdrPath` = base.apk path)

Full per-tick logcat in `evidence/pkg-experiments.logcat.txt`; instrument stdout
in `evidence/pkg06-30min-instrument.log`.
