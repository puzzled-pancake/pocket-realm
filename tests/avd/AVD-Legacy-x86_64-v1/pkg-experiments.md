# PKG experiment evidence — AVD-Legacy-x86_64-v1 (API 28, 4 KB page, research lane)

Captured 2026-08-01 via `tools/run_pkg_experiments.py`. Guest: API 28,
system-image `system-images;android-28;default;x86_64`, abilist `x86_64,x86`,
page_size 4096 (`page_size_source=proc_self_maps` — API 28 has no `getconf`;
the fallback derives the page size from `/proc/self/maps`, reproducibly).

This is the report's disposable **research lane** (§5.1); it is not a PKG-06 lane.

Per-lane evidence files in `evidence/` (timestamped): same structure as the
modern lane (`debug-all-*.log`, `pkgExperiment-t2_*.log`,
`capability-report-debug-*.json`).

## PKG-01 (experiment variant)
- `variant=pkgExperiment`, `extractNativeLibs=true`, `exitCode=0`,
  `realmDladdrPath` resolved. Result: `ok=true code=OK`.

## PKG-01 (production variant)
- `variant=debug`, `extractNativeLibs=false`; no executable fs path.
- Result: `ok=true code=NO_EXECUTABLE_FS_PATH`.

## PKG-02 (production variant)
- `ok=true code=CONTAINMENT_PROVEN`.

## Capability (X0)
`tests/avd/AVD-Legacy-x86_64-v1.json` — adb + app + comparison. Compared fields:
sdkInt, pageSizeBytes, abilist, abilist32, abilist64, totalRamBytes (all OK).
On this image `ro.kernel.qemu.gltransport=pipe`, `ro.kernel.qemu.gles=1`
(captured under host GL, separately from the app EGL strings).
App GL: `glVendor=Google (NVIDIA Corporation)`.
