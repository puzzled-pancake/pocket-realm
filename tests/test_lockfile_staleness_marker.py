"""R1-3/R3-3 (code-red round 1): the arm64 lockfiles' provenance honesty.

A warm-dir lockfile refresh (build_o09_realm_runtime.py --write-lockfiles)
moves the SOURCE pins (patches_content) without rebuilding, so the recorded
artifact bytes correspond to the PREVIOUS patch set. The refresh now stamps
each lane with `binaries_stale_for_patches` (derived from the patch-
sensitive world-runtime artifact's mtime vs the newest patch input), so a
consumer reading the lockfile alone CAN tell the .so predates the pinned
sources. Pinned here:

- the two vanilla arm64 lanes carry `binaries_stale_for_patches: true`
  (their .so files predate the W3 patch sources; the rebuild is W5's lane);
- the four win lanes carry false (rebuilt through the real build path this
  wave, world DLLs postdate every patch input — the row17 witness);
- patches_content on every lane matches the working tree (the pins are
  current even where the binaries are stale).
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

ARM64_LANES = (
    "schemas/realm-runtime-lockfile-arm64-v8a.json",
    "schemas/realm-runtime-lockfile-arm64-v8a-sqlite.json",
)
# R2-9 (finalize batch): the ANDROID x86_64 o09 lanes are ALSO rebuilt at
# W5 — their staleness markers must be pinned disclosed-true like the
# arm64 lanes', not just the arm64 pair the round-1 record discussed.
X86_64_O09_LANES = (
    "schemas/realm-runtime-lockfile.json",
    "schemas/realm-runtime-lockfile-sqlite.json",
)
WIN_LANES = (
    "schemas/realm-runtime-lockfile-sqlite-win.json",
    "schemas/realm-runtime-lockfile-sqlite-win-tbc.json",
    "schemas/realm-runtime-lockfile-sqlite-win-wotlk.json",
    "schemas/realm-runtime-lockfile-mariadb-win.json",
)


def _record(rel: str) -> dict:
    return json.loads((ROOT / rel).read_text(encoding="utf-8"))


def test_arm64_lanes_disclose_binaries_stale_of_sources() -> None:
    for rel in ARM64_LANES:
        record = _record(rel)
        assert record.get("binaries_stale_for_patches") is True, (
            f"{rel}: binaries_stale_for_patches must be true — the recorded "
            "artifacts predate the pinned W3 patch sources (the rebuild is "
            "W5's lane); without the marker a consumer reading the lockfile "
            "alone cannot tell the .so predates the pinned sources"
        )
        # The marker must not excuse drift: pins match the working tree.
        import hashlib
        for rel_path, digest in record.get("patches_content", {}).items():
            source = ROOT / rel_path
            assert source.is_file(), f"{rel}: patches_content names a missing file"
            assert hashlib.sha256(source.read_bytes()).hexdigest() == digest, (
                f"{rel}: patches_content.{rel_path} drifted from the working tree"
            )


def test_x86_64_o09_lanes_disclose_binaries_stale_of_sources() -> None:
    """R2-9 (finalize batch): the two ANDROID x86_64 o09 lanes carry the
    same disclosed-stale marker as the arm64 pair (their .so files predate
    the current W3 patch sources; the rebuild is W5's lane) — every stale
    lane's honesty marker is pinned, not just the arm64 pair."""
    for rel in X86_64_O09_LANES:
        record = _record(rel)
        assert record.get("binaries_stale_for_patches") is True, (
            f"{rel}: binaries_stale_for_patches must be true — the recorded "
            "artifacts predate the pinned W3 patch sources (the rebuild is "
            "W5's lane); a missing or false marker hides real staleness"
        )
        import hashlib
        for rel_path, digest in record.get("patches_content", {}).items():
            source = ROOT / rel_path
            assert source.is_file(), f"{rel}: patches_content names a missing file"
            assert hashlib.sha256(source.read_bytes()).hexdigest() == digest, (
                f"{rel}: patches_content.{rel_path} drifted from the working tree"
            )


def test_win_lanes_record_binaries_fresh() -> None:
    """The four win lanes were really rebuilt this wave (world DLLs postdate
    every patch input — the row17 witness); their marker must be false so a
    stale-true blanket would erode the field's meaning."""
    import time
    for rel in WIN_LANES:
        record = _record(rel)
        assert record.get("binaries_stale_for_patches") is False, (
            f"{rel}: binaries_stale_for_patches must be false — the lane was "
            "rebuilt after the last patch input; a stale-true marker would "
            "hide a real staleness regression"
        )
        world = next(a for a in record["artifacts"]
                     if str(a.get("path", "")).endswith("pocket_world_runtime.dll"))
        dll = ROOT / world["path"]
        assert dll.is_file()
        newest_patch = max(
            f.stat().st_mtime for f in (ROOT / "native/patches").rglob("*") if f.is_file()
        )
        assert dll.stat().st_mtime >= newest_patch, (
            f"{rel}: the world DLL went stale again — rebuild the lane "
            "(tools/build_win_realm_runtime.py) before shipping"
        )
    _ = time  # imported for the mtime comparison clarity above
