"""R6-R4-1 (W4 code-red round 6): the tbc/wotlk storage notes' derived
multiplier claims must agree with their own quoted bytes — the round-6
close caught '~3.7x'/'~7.3x' against 12 GiB/3,217,410,914 B and
24 GiB/3,309,122,582 B (actual 4.0x/7.8x). The pin recomputes each stated
multiplier from the note's own figures and asserts agreement within
rounding."""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

EXPECTED = {"tbc": 4.0, "wotlk": 7.8}


def test_storage_note_multipliers_match_their_own_quoted_bytes():
    flavor = json.loads(
        (ROOT / "schemas/flavor.json").read_text(encoding="utf-8"))
    prepared = {"tbc": 3_217_410_914, "wotlk": 3_309_122_582}
    for f in flavor["flavors"]:
        fid = f["id"]
        if fid not in EXPECTED:
            continue
        note = f["storage"]["note"]
        m = re.search(
            r"extracted_estimate_bytes = (\d+) GiB .*? (\d+\.\d)x the measured "
            r"prepared generation", note)
        assert m, (
            f"{fid}: the storage note lost its multiplier sentence — the "
            "derived provenance the flip quotes must stay pinned")
        gib = int(m.group(1))
        stated = float(m.group(2))
        actual = (gib * 1024 ** 3) / prepared[fid]
        assert abs(actual - stated) < 0.05, (
            f"{fid}: the note claims ~{stated}x but {gib} GiB over the "
            f"measured {prepared[fid]} B is {actual:.2f}x — fix the "
            "multiplier arithmetic (R6-R4-1)")
        assert abs(stated - EXPECTED[fid]) < 0.05, (
            f"{fid}: the note's multiplier drifted from the pinned "
            f"{EXPECTED[fid]}x")
