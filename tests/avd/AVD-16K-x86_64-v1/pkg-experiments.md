# PKG experiment evidence — AVD-16K-x86_64-v1 (API 35, 16 KB page)

Captured 2026-08-01 via `tools/run_pkg_experiments.py`. Guest: API 35,
system-image `system-images;android-35;google_apis_ps16k;x86_64`, abilist
`x86_64,arm64-v8a`, **page_size=16384**.

Per-lane evidence files in `evidence/` (timestamped): same structure as the
modern lane (`pkgExperiment-t2_*.log`, `debug-t2_*.log`, `debug-t1/t3/t4_*.log`,
`capability-report-debug-*.json`).

## PKG-01 (experiment variant)
- `extractNativeLibs=true`, `exitCode=0`, `realmDladdrPath` resolved
  (`.../lib/x86_64/libpocketrealm.so`).
  Result: `variant=pkgExperiment ok=true code=OK` (see `evidence/pkgExperiment-t2_*.log`).

## PKG-01 (production variant)
- `extractNativeLibs=false`; launcher has no executable filesystem path.
  Result: `variant=debug ok=true code=NO_EXECUTABLE_FS_PATH` (see `evidence/debug-t2_*.log`).

## PKG-02 (production variant)
- `ok=true code=CONTAINMENT_PROVEN` (see `debug-t3_*.log`).

## PKG-06 (genuine 30-minute smoke, production variant)
Closure proven by direct SONAME load of `libpocketrealm.so` (which transitively
loads `libc++_shared.so` as a `DT_NEEDED`); the JNI shim is exercised by PKG-02.
- `pageSize=16384`; every tick logged; `tickCount=30` over ~30 min; stable
  `:pkg` PID. `realmDladdrPath = base.apk!/lib/x86_64/libpocketrealm.so`.
  Full per-tick log in `evidence/debug-t4_*.log`.

## Capability (X0)
`tests/avd/AVD-16K-x86_64-v1.json` carries adb + app + comparison.
App GL: `glVendor=Google (NVIDIA Corporation)`.
