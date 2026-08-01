# PKG experiment evidence — AVD-Modern-x86_64-v1 (API 35, 4 KB page)

Captured 2026-08-01 via the reproducible serial+variant-specific host driver
`tools/run_pkg_experiments.py`. Guest: API 35, x86_64, page_size=4096.

Per-lane evidence files in `evidence/` (timestamped):
- `pkgExperiment-t2_*.log` — PKG-01 on the **experiment variant**
  (useLegacyPackaging=true; launcher extracted to nativeLibraryDir).
- `debug-t2_*.log` — PKG-01 on the **production variant** (useLegacyPackaging=false;
  `NO_EXECUTABLE_FS_PATH`, documented).
- `debug-t1_*.log` — capability report (debug/production variant).
- `debug-t3_*.log` — PKG-02 (debug/production variant).
- `capability-report-debug-*.json` — pulled in-app CapabilityReport (app-side
  allocatable + GL strings; merged into the AVD record by capture_avd.py).
- `debug-t4_*.log` — genuine 30-min PKG-06 run (full per-tick `PKG-06 TICK` log).

## PKG-01 (launcher exec from nativeLibraryDir) — experiment variant
Launcher extracted with `rwxr-xr-x` and executed from nativeLibraryDir.
`LD_LIBRARY_PATH=nativeLibraryDir` is set (a standalone exec does not inherit
the app linker namespace, so the launcher's `dlopen("libpocketrealm.so")` could
not otherwise resolve the app libraries).
- `extractNativeLibs=true`, `launcherCanExecute=true`, `exitCode=0`
- `realmDladdrPath = .../lib/x86_64/libpocketrealm.so` (dlopen + dlsym + dladdr OK)
- Result: `variant=pkgExperiment ok=true code=OK`

## PKG-01 (no executable filesystem path) — production variant
The launcher `.so` is stored uncompressed/page-aligned in the APK and is **not**
extracted to nativeLibraryDir, so it has no executable filesystem path.
- `extractNativeLibs=false`, `nativeLibraryDir` present but launcher absent there
- Result: `variant=debug ok=true code=NO_EXECUTABLE_FS_PATH` (see `debug-t2_*.log`).
  This honest observed behavior is central evidence for selecting Lane A over Lane B.

## PKG-02 (isolated library-backed crash containment) — production variant
`dlopen` of the real `libpocketrealm.so` by SONAME inside `:pkg`, then
deterministic `abort()`; `:main` survives, Binder death fires, restart → new PID.
- Result: `ok=true code=CONTAINMENT_PROVEN` (see `debug-t3_*.log`).

## PKG-06 (startup + genuine 30-minute smoke) — production variant
Under the production variant the libs are not extracted to nativeLibraryDir
(`packagedLibCount=0`), so the smoke proves the closure by **directly** loading
the realm facade by SONAME and **transitively** exercising its dependencies:
`libpocketrealm.so` links `libc++_shared.so` (a `DT_NEEDED`), so a successful
`dlopen("libpocketrealm.so")` proves both load and stay resident. The JNI shim
`libpocketpkgtest.so` is exercised directly by PKG-02 in the same `:pkg` process.
- `pageSize=4096`; every tick logged (`PKG-06 TICK tick=N ...`); `tickCount=30`
  over ~30 min (1800.962s); stable `:pkg` PID throughout.
- `realmDladdrPath = base.apk!/lib/x86_64/libpocketrealm.so` (APK-backed).
- Full per-tick log in `evidence/debug-t4_*.log`.

## Capability (X0) — merged app + host comparison
`tests/avd/AVD-Modern-x86_64-v1.json` carries both the adb capture and the
app-side fields under `app` plus a `comparison` block. Equivalent fields
(sdkInt, pageSizeBytes, abilist64) match; allocatable storage (StorageManager vs
`df`) and GL strings (app EGL vs host/emulator) are recorded separately.
App GL (real EGL context): `glVendor=Google (NVIDIA Corporation)`,
`glRenderer=Android Emulator OpenGL ES Translator (NVIDIA GeForce RTX 5060 Ti...)`.
