#!/usr/bin/env python3
"""Verify pinned upstream sources match schemas/sources.json.

Checks every git-submodule entry: the working-tree commit must equal the
recorded commit. Exits non-zero on any drift, so this is safe to run in CI
or as a pre-commit gate. Run from the repository root.

    python3 tools/check_sources.py
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "schemas" / "sources.json"


def submodule_commit(path: Path) -> str:
    """Return the exact commit a submodule is checked out at."""
    result = subprocess.run(
        ["git", "-C", str(ROOT), "submodule", "status", str(path)],
        capture_output=True,
        text=True,
        check=True,
    )
    # Output line: " <sha> <path> (<desc>)" — leading char is space or +/-.
    line = result.stdout.strip()
    if not line:
        raise RuntimeError(f"no submodule recorded at {path}")
    return line.split()[0].lstrip("+-")


def main() -> int:
    data = json.loads(SOURCES.read_text(encoding="utf-8"))
    entries = [s for s in data["sources"] if s["kind"] == "git-submodule"]
    if not entries:
        print("No git-submodule sources to check.", file=sys.stderr)
        return 0

    drift = False
    for entry in entries:
        path = ROOT / entry["path"]
        expected = entry["commit"]
        if not path.exists():
            print(f"MISSING {entry['id']}: {path} not checked out")
            drift = True
            continue
        actual = submodule_commit(path)
        if actual == expected:
            print(f"OK   {entry['id']:<14} {actual[:12]}")
        else:
            print(f"DRIFT {entry['id']}: expected {expected[:12]} got {actual[:12]}")
            drift = True

    if drift:
        print("\nSource pin check FAILED. Update schemas/sources.json after a review.", file=sys.stderr)
        return 1
    print("\nAll pinned sources verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
