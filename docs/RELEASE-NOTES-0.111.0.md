# Pocket Realm 0.111.0 — SD-card content storage

Post-W7 follow-up (owner-directed during the RP6 device session, 2026-10-22):
the Android app can now keep the big immutable data on a portable SD card
while the realm database and runtime stay on internal UFS. `versionCode` 10.

## What changed

- New **Client storage** setting (Settings → Client storage): imported client
  generations, staged import archives, and prepared server data live on the
  SD card when enabled. Off by default; existing installs are untouched.
- Two-volume storage preflight: the import planner now checks the content
  rows (source, staged/scratch archive, extracted, prepared) against the SD
  card's free space and the database/snapshot/Wine/margin rows against
  internal storage, each with its own 20% working margin. The refusal
  message names both volumes (`STORAGE_PREFLIGHT: internal(…) sdcard(…)`).
- The internal-only invariant is preserved where it matters: realm data and
  the database remain on internal storage (`StorageRoots.verify()` still
  asserts it); only the immutable content class is redirectable.
- Honest limits, stated in the UI: switching volumes applies to new imports
  only — clients imported under the other volume are not visible until you
  switch back, and a running realm must be stopped before switching. If the
  card is removed, the app falls back to internal roots and diagnostics
  report the card as requested-but-absent.

## Why

The Retroid Pocket 6 carries 128 GB internal (≈18 GB free in practice) while
the three qualified flavors need ~50 GB of client/prepared data and ~88 GB of
peak working space for the WotLK import alone — the all-flavors-resident
posture physically cannot fit internal-only. With content on the card, all
three flavors fit with the database staying on fast internal storage.

The 0.110.0 qualification build (SHA-256 `49986dac51c6331b…`, archived at
`E:\pocket_realm_toolchain\tmp\w7-rp6\app-qualification-0.110.0-arm64.apk`)
remains the W7 evidence artifact; this build extends it.
