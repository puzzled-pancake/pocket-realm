# PKG experiment evidence — AVD-Legacy-x86_64-v1 (API 28, 4 KB page, research lane)

Captured 2026-08-01. Host: Android emulator 36.6.11.0, WHPX, Windows.
Guest: API 28, system-image `system-images;android-28;default;x86_64`,
abilist `x86_64,x86`, page_size 4096 (getconf absent on API 28; confirmed by the
running launcher and the app probe), MemTotal 6100156 kB.

This is the report's disposable **research lane** (§5.1): fast feasibility work
for command discovery, isolated before production. It is **not** a PKG-06 lane.

## PKG-01 (launcher exec from nativeLibraryDir) — experiment variant
Launcher extracted with `-rwxr-xr-x` and executed from nativeLibraryDir.
- `self_exe_path = /data/app/com.pocketrealm-.../lib/x86_64/libpocket_pkg_launcher.so`
- `page_size = 4096`
- With `LD_LIBRARY_PATH`: `realm_path = .../lib/x86_64/libpocketrealm.so`,
  `realm_base = 0x709dfe5cb000`, `PKG_LAUNCHER_OK`, exit 0.
- Production variant: `NO_EXECUTABLE_FS_PATH` (documented, as on the other lanes).

## PKG-02 (isolated library-backed crash containment) — production variant
- mainPid=3068, childPidBeforeCrash=3091
- `dlopen("libpocketrealm.so")` by SONAME: `realmLoaded=1`
- `realmDladdrPath = /data/app/com.pocketrealm-.../base.apk!/lib/x86_64/libpocketrealm.so`
- `helloBeforeCrash = pocket-realm-pkg-ok`; deterministic `abort()`
- `binderDeathObserved=true`, `mainPidStillAlive=true`, child gone
- Restart → `childPidAfterCrash=3118` (NEW pid), `helloAfterRestart=pocket-realm-pkg-ok`
- Result: `CONTAINMENT_PROVEN`

The library-backed, process-isolated component model is viable even on the
legacy API 28 research lane, reinforcing Lane A as the production choice.
