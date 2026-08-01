# PKG experiment evidence — AVD-Modern-x86_64-v1 (API 35, 4 KB page)

Captured 2026-08-01 via the reproducible serial+variant-specific host driver
`tools/run_pkg_experiments.py`. Guest: API 35, x86_64, page_size=4096.

Per-lane evidence files in `evidence/` (timestamped):
- `debug-all-*.log` — the deterministic suite (t1 capability + t2 production
  PKG-01 + t3 PKG-02 + t4 PKG-06 short) on the production variant.
- `pkgExperiment-t2_*.log` — PKG-01 on the **experiment variant**
  (useLegacyPackaging=true; launcher extracted to nativeLibraryDir).
- `capability-report-debug-*.json` — pulled in-app CapabilityReport (app-side
  allocatable + GL strings; merged into the AVD record by capture_avd.py).
- `debug-t4_pkg06_*.log` — genuine 30-min PKG-06 run (full per-tick history).

## PKG-01 (launcher exec from nativeLibraryDir) — experiment variant
Launcher extracted with `rwxr-xr-x` and executed from nativeLibraryDir.
`LD_LIBRARY_PATH=nativeLibraryDir` is set (a standalone exec does not inherit
the app linker namespace, so the launcher's `dlopen("libpocketrealm.so")` could
not otherwise resolve the app libraries).
- `variant=pkgExperiment`, `extractNativeLibs=true`, `exitCode=0`
- `realmDladdrPath = .../lib/x86_64/libpocketrealm.so` (dlopen + dlsym + dladdr OK)
- Result: `ok=true code=OK`

## PKG-01 (no executable filesystem path) — production variant
The launcher `.so` is stored uncompressed/page-aligned in the APK and is **not**
extracted to nativeLibraryDir, so it has no executable filesystem path.
- `variant=debug`, `extractNativeLibs=false`
- Result: `ok=true code=NO_EXECUTABLE_FS_PATH` (in `debug-all-*.log`).

## PKG-02 (isolated library-backed crash containment) — production variant
`dlopen` of the real `libpocketrealm.so` by SONAME inside `:pkg`, then
deterministic `abort()`; `:main` survives, Binder death fires, restart → new PID.
- Result: `ok=true code=CONTAINMENT_PROVEN` (in `debug-all-*.log`).

## PKG-06 (startup + genuine 30-minute smoke) — production variant
Enumerates ALL SIX native libraries packaged in the APK directly from `base.apk`
(`packagedLibCount=6`), excludes the launcher as a PIE executable
(`libpocket_pkg_launcher.so=EXCLUDED_EXECUTABLE`), then probes each of the 5
loadable libraries by SONAME (`perLibProbeCount=5`), all loading from the APK:
`libandroidx.graphics.path.so`, `libc++_shared.so`, `libdatastore_shared_counter.so`,
`libpocketpkgtest.so`, `libpocketrealm.so` — every one `OK path=base.apk!/lib/x86_64/...`.
- `pageSize=4096`; every tick logged; `tickCount=30` over ~30 min; stable PID.
- Full per-tick log in `evidence/debug-t4_pkg06_*.log`.

## Capability (X0) — merged app + host comparison
`tests/avd/AVD-Modern-x86_64-v1.json` carries both the adb capture and the
app-side fields under `app` plus a `comparison` block. Equivalent fields compared:
sdkInt, pageSizeBytes, abilist, abilist32, abilist64, totalRamBytes (all OK).
Allocatable storage (StorageManager vs `df`), GL strings (app EGL vs host), and
host platform (CPU/virt-backend/GPU-mode) are recorded separately, not compared.
App GL (real EGL context): `glVendor=Google (NVIDIA Corporation)`.
