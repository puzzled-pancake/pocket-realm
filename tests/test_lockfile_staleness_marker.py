"""R1-3/R3-3 (code-red round 1; W5 code-red round 3 disposition): the vanilla
android o09 lanes' provenance honesty.

A warm-dir lockfile refresh (build_o09_realm_runtime.py --write-lockfiles)
moves the SOURCE pins (patches_content) without rebuilding, so the recorded
artifact bytes can correspond to a PREVIOUS patch set. The
`binaries_stale_for_patches` field (derived from the patch-sensitive
world-runtime artifact's mtime vs the newest patch input) is the honesty
marker. W3 pinned the four vanilla android o09 lanes stale-true with "the
rebuild is W5's lane"; W5 code-red round 3 (R1-r3-2) paid that obligation:
all four lanes were rebuilt through the real build path
(out/completion/w5/o09-vanilla-*-rebuild.log), so every lane's marker must
now be FALSE with world-runtime artifacts postdating every patch input —
a stale-true blanket would erode the field's meaning, and a future
stale-true state must come with a recorded rebuild-or-deferral decision.
Pinned here:

- the four vanilla android o09 lanes (arm64/x86_64 x mysql/sqlite) carry
  `binaries_stale_for_patches: false` and their world .so postdates the
  newest patch input;
- the four win lanes carry false (rebuilt at W5 round-1, row17 witness);
- patches_content on every lane matches the working tree (pins current);
- R1-r3-2 guard: any vanilla o09 lane that goes stale-true again must be
  accompanied by a DECISIONS-PORT row recording the rebuild-or-deferral
  decision (grep marker "vanilla o09 rebuild").
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

ANDROID_O09_LANES = (
    "schemas/realm-runtime-lockfile-arm64-v8a.json",
    "schemas/realm-runtime-lockfile-arm64-v8a-sqlite.json",
    "schemas/realm-runtime-lockfile.json",
    "schemas/realm-runtime-lockfile-sqlite.json",
)
# R5-r5-1 (W5 code-red round 5): the wave's OWN new per-flavor android lanes
# join the staleness/pins-current coverage — the exact R1-r3-2 class this
# wave fixed for the vanilla lanes, applied to the lanes W5 added.
PER_FLAVOR_O09_LANES = (
    "schemas/realm-runtime-lockfile-tbc-arm64-v8a.json",
    "schemas/realm-runtime-lockfile-wotlk-arm64-v8a.json",
)
WIN_LANES = (
    "schemas/realm-runtime-lockfile-sqlite-win.json",
    "schemas/realm-runtime-lockfile-sqlite-win-tbc.json",
    "schemas/realm-runtime-lockfile-sqlite-win-wotlk.json",
    "schemas/realm-runtime-lockfile-mariadb-win.json",
)


def _record(rel: str) -> dict:
    return json.loads((ROOT / rel).read_text(encoding="utf-8"))


def _assert_pins_current(record: dict, rel: str) -> None:
    import hashlib
    for rel_path, digest in record.get("patches_content", {}).items():
        source = ROOT / rel_path
        assert source.is_file(), f"{rel}: patches_content names a missing file"
        assert hashlib.sha256(source.read_bytes()).hexdigest() == digest, (
            f"{rel}: patches_content.{rel_path} drifted from the working tree"
        )


def test_android_o09_lanes_record_binaries_fresh() -> None:
    """R1-r3-2: all four vanilla android o09 lanes were rebuilt through the
    real build path at W5 code-red round 3 — their marker must be false and
    their world .so must postdate every patch input (the same freshness the
    win-lane pin enforces). A stale-true marker here now requires the
    recorded rebuild-or-deferral decision below."""
    newest_patch = max(
        f.stat().st_mtime
        for f in (ROOT / "native/patches").rglob("*") if f.is_file()
    )
    stale_lanes = []
    for rel in ANDROID_O09_LANES:
        record = _record(rel)
        _assert_pins_current(record, rel)
        if record.get("binaries_stale_for_patches") is True:
            stale_lanes.append(rel)
            continue
        world = next(a for a in record["artifacts"]
                     if str(a.get("path", "")).endswith("pocket_world_runtime.so"))
        world_so = ROOT / world["path"]
        assert world_so.is_file(), f"{rel}: pinned world .so missing on disk"
        assert world_so.stat().st_mtime >= newest_patch, (
            f"{rel}: the world .so went stale again (predates the newest patch "
            "input) — rebuild the lane (tools/build_o09_realm_runtime.py "
            "--flavor vanilla, matching --abi/--backend) before shipping"
        )
    if stale_lanes:
        decisions = (ROOT / "docs/DECISIONS-PORT.md").read_text(
            encoding="utf-8", errors="replace")
        assert "vanilla o09 rebuild" in decisions, (
            f"vanilla android o09 lanes carry binaries_stale_for_patches=true "
            f"({stale_lanes}) with no recorded rebuild-or-deferral decision — "
            "rebuild the lanes or record the waiver in DECISIONS-PORT.md "
            "(grep marker: 'vanilla o09 rebuild'; the R1-r3-2 class)"
        )


def test_win_lanes_record_binaries_fresh() -> None:
    """The four win lanes were really rebuilt this wave (world DLLs postdate
    every patch input — the row17 witness); their marker must be false so a
    stale-true blanket would erode the field's meaning."""
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
    _assert_pins_current(_record(WIN_LANES[0]), WIN_LANES[0])


def test_per_flavor_o09_lanes_record_binaries_fresh() -> None:
    """R5-r5-1: the per-flavor android o09 lanes W5 added must carry the
    same freshness contract as the vanilla lanes — marker false, world .so
    postdating every patch input, patches_content pins current. A stale-true
    marker here requires a per-flavor rebuild-or-deferral decision (grep
    marker 'per-flavor o09 rebuild')."""
    newest_patch = max(
        f.stat().st_mtime
        for f in (ROOT / "native/patches").rglob("*") if f.is_file()
    )
    stale_lanes = []
    for rel in PER_FLAVOR_O09_LANES:
        record = _record(rel)
        _assert_pins_current(record, rel)
        if record.get("binaries_stale_for_patches") is True:
            stale_lanes.append(rel)
            continue
        world = next(a for a in record["artifacts"]
                     if str(a.get("path", "")).endswith("pocket_world_runtime.so"))
        world_so = ROOT / world["path"]
        assert world_so.is_file(), f"{rel}: pinned world .so missing on disk"
        assert world_so.stat().st_mtime >= newest_patch, (
            f"{rel}: the world .so went stale again — rebuild the lane "
            "(tools/build_o09_realm_runtime.py --flavor "
            f"{'tbc' if 'tbc' in rel else 'wotlk'}, matching --abi/--backend) "
            "before shipping"
        )
    if stale_lanes:
        decisions = (ROOT / "docs/DECISIONS-PORT.md").read_text(
            encoding="utf-8", errors="replace")
        assert "per-flavor o09 rebuild" in decisions, (
            f"per-flavor android o09 lanes carry binaries_stale_for_patches="
            f"true ({stale_lanes}) with no recorded rebuild-or-deferral "
            "decision — rebuild the lanes or record the waiver in "
            "DECISIONS-PORT.md (grep marker: 'per-flavor o09 rebuild')"
        )
