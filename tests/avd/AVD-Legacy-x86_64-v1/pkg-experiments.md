# PKG experiment evidence — AVD-Legacy-x86_64-v1 (API 28, 4 KB page, research lane)

Captured 2026-08-01 via `tools/run_pkg_experiments.py`. Guest: API 28,
system-image `system-images;android-28;default;x86_64`, abilist `x86_64,x86`,
page_size 4096 (`page_size_source=proc_self_maps` — API 28 has no `getconf`;
the fallback derives the page size from `/proc/self/maps`, reproducibly).

This is the report's disposable **research lane** (§5.1); it is not a PKG-06 lane.

## PKG-01 (experiment variant)
- `extractNativeLibs=true`, `exitCode=0`, `realmDladdrPath` resolved.
  Result: `ok=true code=OK`.
- Production variant: `NO_EXECUTABLE_FS_PATH` (documented).

## PKG-02 (production variant)
- `ok=true code=CONTAINMENT_PROVEN` (see `evidence/debug-t3_*.log`).

## Capability (X0)
`tests/avd/AVD-Legacy-x86_64-v1.json` carries adb + app + comparison.
The host GL props are empty on this image; app GL (EGL context):
`glVendor=Google (NVIDIA Corporation)`.
