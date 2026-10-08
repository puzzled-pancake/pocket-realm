#!/usr/bin/env python3
"""G3-3 golden seal for the vanilla migration ledger (plan §2.7 G3-3).

Every deployed vanilla datadir's migration seal binds the SHA-256 of the
packaged `database/migrations/manifest.json` asset bytes
(`DatabaseMigrationSeal.current()`, fed by `DatabaseEngine`'s
`expectedMigrationManifestSha256`). Any re-key, reorder, whitespace change or
rename that alters those bytes breaks every existing vanilla device's seal at
next boot — forced re-apply or a `DB-REVISION` failure. This gate pins the
bytes:

- the canonical vanilla ledger `schemas/database-migrations-vanilla.json`
  (created additively at wave S1; pre-rename, the single
  `schemas/database-migrations.json` file IS the vanilla ledger) must hash to
  the golden digest;
- while the S1 deprecation window keeps the unqualified alias
  `schemas/database-migrations.json` alive, canonical and alias must be
  byte-identical to each other;
- with `--staged PATH`, the staged
  `assets/database/migrations/manifest.json` input (written by
  `tools/stage_database_migrations.py` from the same `encoded` text) must be
  byte-identical to the pinned ledger — run by the build/release lane against
  the staging it just produced. The default repo gate does NOT glob local
  `native/.build-*` staging trees: they are untracked build state that may
  legitimately predate the current migration set.

A vanilla-ledger change is never silent: rotating the golden sha requires a
Decision Log entry (docs/DECISIONS-PORT.md) plus the
`DatabaseEngine.LEGACY_MANIFEST_SHA256` one-release compatibility window
(G3-3 work item 4).

Usage:
    python tools/check_ledger_golden.py [--staged PATH]

Exit codes: 0 = golden holds, 1 = violation (errors printed as "ERROR ...").
"""
from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Golden digest of the current-release vanilla ledger, measured 2026-09-30:
# 208,495 bytes, 414 entries, schema 1. The datadir seal binds these bytes.
GOLDEN_SHA256 = "3771fc35b4ceddbadd6f786d76b10902db319d07c7d6372156417e4e3d81dc68"

CANONICAL = Path("schemas/database-migrations-vanilla.json")
ALIAS = Path("schemas/database-migrations.json")


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def validate(root: Path = ROOT) -> list[str]:
    """Return a list of violation messages (empty when the golden holds)."""
    errors: list[str] = []
    canonical = root / CANONICAL
    alias = root / ALIAS
    if not canonical.is_file() and not alias.is_file():
        return [
            f"ledger golden: neither {CANONICAL.as_posix()} nor {ALIAS.as_posix()} exists — "
            "the vanilla migration ledger is missing"
        ]
    golden_note = (
        " (the vanilla migration-ledger bytes changed; every deployed vanilla "
        "datadir's migration seal would fail at next boot. Rotation requires a "
        "Decision Log entry plus the DatabaseEngine.LEGACY_MANIFEST_SHA256 "
        "compatibility window — plan §2.7 G3-3 item 4)"
    )
    for path in (canonical, alias):
        if not path.is_file():
            continue
        actual = _sha256(path.read_bytes())
        if actual != GOLDEN_SHA256:
            errors.append(
                f"ledger golden: {path.relative_to(root).as_posix()} sha256 {actual} "
                f"!= golden {GOLDEN_SHA256}{golden_note}"
            )
    if canonical.is_file() and alias.is_file():
        if canonical.read_bytes() != alias.read_bytes():
            errors.append(
                "ledger golden: schemas/database-migrations-vanilla.json and the "
                "schemas/database-migrations.json deprecation-window alias must stay "
                "byte-identical to each other (G3-3 item 2: the rename is additive)"
            )
    return errors


def validate_staged(root: Path, staged: Path) -> list[str]:
    """Byte-equality of a freshly staged manifest input against the pinned ledger."""
    source = root / CANONICAL
    if not source.is_file():
        source = root / ALIAS
    if not staged.is_file():
        return [f"ledger golden: staged manifest {staged} not found"]
    if staged.read_bytes() != source.read_bytes():
        errors = [
            f"ledger golden: staged {staged} differs from the pinned ledger "
            f"{source.relative_to(root).as_posix()} — re-run "
            "tools/stage_database_migrations.py; the packaged asset bytes must stay "
            "identical to the golden-sealed ledger"
        ]
        return errors
    return []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--staged",
        type=Path,
        default=None,
        help="path to a freshly staged assets/database/migrations/manifest.json "
        "to byte-compare against the pinned ledger (build/release lane)",
    )
    args = parser.parse_args()

    errors = validate(ROOT)
    if args.staged is not None:
        errors.extend(validate_staged(ROOT, args.staged))
    if errors:
        for error in errors:
            print(f"ERROR {error}")
        print("check_ledger_golden: FAILED")
        return 1
    staged_note = f"; staged {args.staged} byte-identical" if args.staged else ""
    print(f"check_ledger_golden: OK (golden {GOLDEN_SHA256}{staged_note})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
