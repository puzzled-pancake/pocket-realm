#!/usr/bin/env python3
"""Generate the per-flavor conf-key matrix from each core's .conf.dist files.

H1-17 work item 1 (design half, landed wave S1): the matrix is GENERATED
data, never hand-maintained. This tool parses every flavor's pinned
`.conf.dist(.in)` surfaces and emits `schemas/conf-matrix-{flavor}.json`:

    {
      "schema": 1,
      "flavor": "vanilla",
      "keys":   {"Key.Name": {"file": <dist basename>, "default": "<value>"}},
      "excluded_keys": [ ... verbatim from schemas/flavor.json conf_matrix ... ]
    }

Parsing rules (H1-17 item 3): dist files are CMake `.in` templates — lines
are `Key = value` or `#Key = value` (commented = documented but unset); any
`@...@` substitution token is stripped from values before recording. Keys are
recorded with their default (unset keys record null). An empty `#` comment or
prose line is ignored.

Correction C9 (VoiceChat): the flavor's `conf_matrix.excluded_keys` families
from schemas/flavor.json are enforced here — the generator NEVER emits an
excluded family's keys into `keys`, and if a parsed dist suddenly gains a key
matching an excluded family the run FAILS LOUD naming the key+file (a phantom
key family must not enter the matrix by surprise; mangos-wotlk's VoiceChat is
a 46-line inert stub with zero conf keys — register §0 C9).

Vanilla golden (H1-17 item 5 / G3-7): the first CI run against the pinned
vanilla dists is the proof that the matrix reproduces today's emitted conf;
the full matrix generation for every flavor lands with its S2/S3 pins.

Usage:
    python tools/generate_conf_matrix.py --flavor vanilla [--out PATH]
    python tools/generate_conf_matrix.py --list-surfaces --flavor vanilla

Exit codes: 0 = matrix written (or listed), 1 = validation failure,
2 = fail-loud (missing pins/dists, excluded-family violation).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

try:
    from tools import flavor_layout
except ImportError:  # direct execution: python tools/<script>.py
    import flavor_layout

ROOT = Path(__file__).resolve().parents[1]
FLAVOR_MANIFEST = ROOT / "schemas" / "flavor.json"

# The .conf.dist surfaces each flavor's pinned trees carry (H1-17 current
# state). Paths are relative to a flavor's core/bots submodule root; a
# missing dist is a fail-loud (the flavor's pins are not checked out).
CORE_SURFACES = (
    "src/mangosd/mangosd.conf.dist.in",
    "src/realmd/realmd.conf.dist.in",
    "src/mangosd/mods.conf.dist.in",
)
CORE_ALT_SURFACES = (  # older layouts name realmd.conf under src/realmd/ or the root
    "realmd.conf.dist.in",
    "src/mangosd/realmd.conf.dist.in",
)
CORE_GAME_SURFACES = (
    "src/game/Anticheat/module/anticheat.conf.dist.in",
    "src/game/AuctionHouseBot/ahbot.conf.dist.in",
    "src/game/PlayerBot/playerbot.conf.dist.in",
)
BOTS_SURFACES = (
    "playerbot/aiplayerbot.conf.dist.in",
    "ahbot/ahbot.conf.dist.in",
)

_KEY_VALUE_RE = re.compile(r"^\s*#?\s*([A-Za-z][A-Za-z0-9_.]*)\s*=\s*(.*?)\s*$")
_TOKEN_RE = re.compile(r"@[^@]+@")


def parse_dist(path: Path) -> dict[str, str | None]:
    """Parse one .conf.dist(.in) file: key -> default (None for commented-out
    keys). @...@ CMake substitution tokens are stripped (H1-17 item 3)."""
    keys: dict[str, str | None] = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.strip() or line.lstrip().startswith("##"):
            continue
        match = _KEY_VALUE_RE.match(line)
        if not match:
            continue
        key, value = match.group(1), match.group(2)
        value = _TOKEN_RE.sub("", value).strip()
        commented = line.lstrip().startswith("#")
        keys[key] = None if commented else (value or "")
    return keys


def _display(path: Path) -> str:
    """Repo-relative display form; absolute paths outside the repo (tests,
    staged fixtures) print as-is instead of crashing relative_to()."""
    try:
        return path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def flavor_surfaces(layout: flavor_layout.FlavorLayout) -> list[Path]:
    """The dist files a flavor's pinned trees carry; missing files are a
    fail-loud (they arrive with the flavor's S2/S3 submodule pins)."""
    surfaces: list[Path] = []
    core = ROOT / layout.core_submodule
    bots = ROOT / layout.bots_submodule
    for rel in (*CORE_SURFACES, *CORE_GAME_SURFACES, *BOTS_SURFACES):
        surfaces.append(core / rel)
    for rel in BOTS_SURFACES:
        surfaces.append(bots / rel)
    # Realmd dist layout differs between branches; accept either location.
    present = [path for path in surfaces if path.is_file()]
    if not any(path.name.startswith("realmd.conf") for path in present):
        for rel in CORE_ALT_SURFACES:
            candidate = core / rel
            if candidate.is_file():
                present.append(candidate)
                break
    return present


def build_record(flavor_id: str) -> dict:
    """Build one flavor's conf-matrix record from the CURRENT manifest +
    pinned conf dists, writing nothing. Raises RuntimeError on the
    fail-loud classes (missing flavor block, missing dists, C9
    excluded-family violation) so the writer and the freshness check
    (R3-1, review round 3) share one code path."""
    layout = flavor_layout.resolve(flavor_id)
    manifest = flavor_layout.load_flavor_manifest()
    block = next(
        (f for f in manifest["flavors"] if f.get("id") == flavor_id), None
    )
    if block is None:
        raise RuntimeError(f"flavor {flavor_id!r} not in {FLAVOR_MANIFEST}")
    excluded_families = [
        entry.get("key_family")
        for entry in (block.get("conf_matrix") or {}).get("excluded_keys", [])
    ]
    surfaces = flavor_surfaces(layout)
    missing = [path for path in surfaces if not path.is_file()]
    if not surfaces or missing:
        detail = "\n  ".join(_display(p) for p in missing)
        raise RuntimeError(
            f"conf dist files missing for flavor {flavor_id!r} (they arrive "
            "with the flavor's S2/S3 submodule pins; H1-17 generation "
            f"executes then):\n  {detail}")
    keys: dict[str, dict] = {}
    for path in surfaces:
        for key, default in parse_dist(path).items():
            family = key.split(".")[0]
            if family in excluded_families:
                raise RuntimeError(
                    f"correction C9 violation — dist {path.name} carries key "
                    f"{key!r} whose family is excluded for flavor {flavor_id!r} "
                    "(schemas/flavor.json conf_matrix.excluded_keys); the generator "
                    "must not emit it and staging must not expect it")
            keys[key] = {"file": path.name, "default": default}
    return {
        "schema": 1,
        "flavor": flavor_id,
        "surfaces": sorted(_display(p) for p in surfaces),
        "keys": dict(sorted(keys.items())),
        "excluded_keys": (block.get("conf_matrix") or {}).get("excluded_keys", []),
    }


def generate(flavor_id: str, out: Path | None = None, list_only: bool = False) -> int:
    layout = flavor_layout.resolve(flavor_id)
    if list_only:
        surfaces = flavor_surfaces(layout)
        missing = [path for path in surfaces if not path.is_file()]
        if not surfaces or missing:
            print(
                "ERROR: conf dist files missing for flavor "
                f"{flavor_id!r} (they arrive with the flavor's S2/S3 submodule "
                "pins; H1-17 generation executes then):",
                file=sys.stderr,
            )
            for path in missing:
                print(f"  - {path}", file=sys.stderr)
            return 2
        for path in surfaces:
            print(_display(path))
        return 0
    try:
        record = build_record(flavor_id)
    except RuntimeError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    encoded = json.dumps(record, indent=2) + "\n"
    if out is None:
        out = ROOT / layout.conf_matrix()
    out.write_bytes(encoded.encode("utf-8"))
    print(f"conf matrix: {out} ({len(record['keys'])} keys, "
          f"{len(record['excluded_keys'])} excluded families)")
    return 0


def check(flavor_ids=("tbc", "wotlk")) -> list[str]:
    """Freshness gate (R3-1, review round 3; decision D61): every committed
    schemas/conf-matrix-<flavor>.json must regenerate BYTE-IDENTICALLY
    from the current manifest + pinned conf dists — the binding used to
    exist only at write time (the D57 'provenance that can rot' class), so
    manifest exclusion edits, submodule re-pins that change dists, or hand
    edits rotted the matrices silently until W3.4 wires the consumers.
    Scoped to the COMMITTED per-flavor matrices (tbc/wotlk — the vanilla
    golden stays the G3-7 CI-run lane and ships no committed matrix;
    extending the tuple covers a future one). A flavor whose dists are not
    checked out is a LOUD NOTE, not an error (the pins arrive with S2/S3;
    check_sources owns that posture)."""
    errors: list[str] = []
    for flavor_id in flavor_ids:
        layout = flavor_layout.resolve(flavor_id)
        committed = ROOT / layout.conf_matrix()
        try:
            record = build_record(flavor_id)
        except RuntimeError as exc:
            print(f"NOTE conf-matrix: {flavor_id}: {exc}", file=sys.stderr)
            continue
        if not committed.is_file():
            errors.append(
                f"conf-matrix: {committed.relative_to(ROOT).as_posix()} is "
                "missing (regenerate: python tools/generate_conf_matrix.py "
                f"--flavor {flavor_id})")
            continue
        fresh_bytes = json.dumps(record, indent=2) + "\n"
        if committed.read_text(encoding="utf-8") != fresh_bytes:
            errors.append(
                "conf-matrix: "
                f"{committed.relative_to(ROOT).as_posix()} is STALE - it no "
                "longer regenerates from the current manifest + pinned conf "
                "dists (regenerate: python tools/generate_conf_matrix.py "
                f"--flavor {flavor_id}; a deliberate exclusion-set change "
                "lands with its parity pin in the same change)")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--flavor", default="vanilla",
                        choices=tuple(flavor_layout.FLAVOR_IDS))
    parser.add_argument("--out", type=Path, default=None,
                        help="write the matrix to PATH instead of the flavor's "
                             "schemas/conf-matrix-{flavor}.json")
    parser.add_argument("--list-surfaces", action="store_true",
                        help="print the dist files the flavor's pins carry and exit")
    parser.add_argument("--check", action="store_true",
                        help="verify every committed conf matrix regenerates "
                             "byte-identically from the current manifest + "
                             "pinned dists (R3-1 freshness gate; 0 = in sync, "
                             "1 = stale/missing)")
    args = parser.parse_args()
    if args.check:
        errors = check()
        for error in errors:
            print(f"ERROR {error}", file=sys.stderr)
        print("conf-matrix check: OK (all committed matrices regenerate "
              "identically)" if not errors
              else f"conf-matrix check: FAILED ({len(errors)} stale/missing)")
        return 1 if errors else 0
    return generate(args.flavor, args.out, args.list_surfaces)


if __name__ == "__main__":
    sys.exit(main())
