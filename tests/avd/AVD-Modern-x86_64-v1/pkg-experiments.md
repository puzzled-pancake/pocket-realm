# PKG experiment evidence — AVD-Modern-x86_64-v1 (API 35, 4 KB page)

Captured 2026-08-01. Host: Android emulator 36.6.11.0, WHPX, Windows.
Guest: API 35, x86_64, page_size=4096, MemTotal 2532436 kB.

## Variant summary
- **Production variant (debug, useLegacyPackaging=false):** native `.so` stored
  uncompressed/page-aligned in the APK. `applicationInfo.nativeLibraryDir`
  resolves into the APK path; no extracted executable filesystem path for a
  standalone launcher. This is the documented production behavior.
- **Experiment variant (pkgExperiment, useLegacyPackaging=true):** native `.so`
  extracted into `/data/app/.../lib/x86_64/` with the executable bit
  (`-rwxr-xr-x`), so a PIE launcher has an executable filesystem path.

## PKG-01 (launcher exec from nativeLibraryDir)
Experiment variant. Launcher executed from its nativeLibraryDir path; its
`/proc/self/exe` resolved to that path; page size reported 4096.

- Without `LD_LIBRARY_PATH`: launcher exec succeeds (hello marker, self path,
  page size), but `dlopen("libpocketrealm.so")` returns "library not found" —
  the standalone exec's linker namespace does not include the app library dir
  by default. Exit 3 (structured `PKG_LAUNCHER_ERROR	dlopen_failed`).
- With `LD_LIBRARY_PATH=<nativeLibraryDir>`: full success.
  - `self_exe_path = /data/app/~~.../com.pocketrealm-.../lib/x86_64/libpocket_pkg_launcher.so`
  - `realm_soname = libpocketrealm.so`, `realm_symbol = realm_err_str`
  - `realm_path = /data/app/~~.../com.pocketrealm-.../lib/x86_64/libpocketrealm.so`
  - `realm_base = 0x787a51a88000`
  - `PKG_LAUNCHER_OK`, exit 0.

Production variant: `NO_EXECUTABLE_FS_PATH` (documented; the launcher has no
executable fs path when stored uncompressed in the APK). This honest result is
part of what justifies selecting Lane A (library-backed, not standalone exec).

## PKG-02 (isolated library-backed crash containment) — production variant
Single `:pkg` child process bound via AIDL.
- mainPid=3765, childPidBeforeCrash=3786
- Child `dlopen("libpocketrealm.so", RTLD_NOW)` by SONAME: `realmLoaded=1`
- `realmDladdrPath = /data/app/~~.../com.pocketrealm-.../base.apk!/lib/x86_64/libpocketrealm.so`
  (loaded from the APK, not extracted — production packaging confirmed)
- `helloBeforeCrash = pocket-realm-pkg-ok`
- Deterministic `abort()` (kind 0): `binderDeathObserved=true`,
  `mainPidStillAlive=true`, child process gone
- Restart → `childPidAfterCrash=3808` (NEW pid), `helloAfterRestart=pocket-realm-pkg-ok`
- Result: `CONTAINMENT_PROVEN`

## PKG-06 (startup + genuine 30-minute smoke, production variant)
- `pageSize=4096`, `realmLoaded=1`, realm loaded from APK
- ELF LOAD alignment 0x4000 (16 KB compatible); `zipalign -c -P 16 -v 4` passes.

**Genuine 30-minute acceptance run** (`am instrument -e smokeSeconds 1800`):
- `RESULT ok=true code=SMOKE_OK`, `tickCount=30`
- firstTick `t=1785548079057` (tick 1) → lastTick `t=1785549819340` (tick 30)
  = 1,740,283 ms ≈ 29 min of continuous heartbeat
- same `:pkg` PID (4564) stable across all 30 ticks; hello answered every tick
- realm `.so` resident throughout (`realmDladdrPath` = base.apk path)

Full per-tick logcat in `evidence/pkg-experiments.logcat.txt`; instrument stdout
in `evidence/pkg06-30min-instrument.log`.
