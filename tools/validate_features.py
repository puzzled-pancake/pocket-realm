#!/usr/bin/env python3
"""Validate FEATURES.json: status enum, ids, dependency references, ordering.

Exits non-zero with one line per violation. Safe in CI or as a pre-commit gate
(same contract as tools/check_sources.py). Added by the de-vibe plan Phase 2
after O08 carried an out-of-enum status ("complete") unnoticed.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FEATURES = ROOT / "FEATURES.json"


def main() -> int:
    data = json.loads(FEATURES.read_text(encoding="utf-8"))
    status_values = set(data.get("status_values", []))
    if not status_values:
        print("FEATURES.json: missing status_values enum")
        return 1

    features = data.get("features", [])
    errors: list[str] = []
    ids: list[str] = []
    active = 0

    for index, feature in enumerate(features):
        fid = feature.get("id", "<missing id>")
        if not feature.get("id"):
            errors.append(f"[{index}] missing id")
            continue
        if fid in ids:
            errors.append(f"{fid}: duplicate id")
        ids.append(fid)

        status = feature.get("status")
        if status not in status_values:
            errors.append(f"{fid}: status {status!r} not in status_values {sorted(status_values)}")
        if status == "active":
            active += 1

        for dep in feature.get("depends", []):
            if dep == fid:
                errors.append(f"{fid}: depends on itself")
                continue
            if dep not in ids:
                if dep in [f.get("id") for f in features]:
                    errors.append(f"{fid}: dependency {dep} appears later in the list (ordering)")
                else:
                    errors.append(f"{fid}: dependency {dep} does not exist")

    if active > 1:
        errors.append(f"{active} features are simultaneously active (expected at most 1)")

    if errors:
        for error in errors:
            print(f"FEATURES.json: {error}")
        return 1
    print(f"FEATURES.json OK: {len(features)} features, {active} active")
    return 0


if __name__ == "__main__":
    sys.exit(main())
