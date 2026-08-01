# PKG experiment evidence — AVD-16K-x86_64-v1 (API 35, 16 KB page)

Captured 2026-08-01 via `tools/run_pkg_experiments.py`. Guest: API 35,
system-image `system-images;android-35;google_apis_ps16k;x86_64`, abilist
`x86_64,arm64-v8a`, **page_size=16384**.

Per-lane evidence files in `evidence/` (timestamped): same structure as the
modern lane (`debug-all-*.log`, `pkgExperiment-t2_*.log`,
`capability-report-debug-*.json`, `debug-t4_pkg06_*.log`).

## PKG-01 (experiment variant)
- `variant=pkgExperiment`, `extractNativeLibs=true`, `exitCode=0`,
  `realmDladdrPath` resolved (`.../lib/x86_64/libpocketrealm.so`).
- Result: `ok=true code=OK`.

## PKG-01 (production variant)
- `variant=debug`, `extractNativeLibs=false`; no executable fs path.
- Result: `ok=true code=NO_EXECUTABLE_FS_PATH`.

## PKG-02 (production variant)
- `ok=true code=CONTAINMENT_PROVEN`.

## PKG-06 (genuine 30-minute smoke, production variant)
Enumerates all 6 APK libs (`packagedLibCount=6`), excludes the launcher, probes
the 5 loadable libs by SONAME (`perLibProbeCount=5`), all `OK` from
`base.apk!/lib/x86_64/...` on 16 KB pages.
- `pageSize=16384`; every tick logged; `tickCount=30` over ~30 min; stable PID.

## Capability (X0)
`tests/avd/AVD-16K-x86_64-v1.json` — adb + app + comparison. Compared fields:
sdkInt, pageSizeBytes, abilist, abilist32, abilist64, totalRamBytes (all OK).
App GL: `glVendor=Google (NVIDIA Corporation)`.
