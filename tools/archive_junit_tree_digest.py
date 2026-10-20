#!/usr/bin/env python3
"""Archive a per-tree digest manifest of the canonical JUnit XML trees.

R4-r3-1 (W1 code-red round 3): the trees behind the counts artifacts get
clobbered by concurrent seats' focused Gradle runs; the counts artifacts plus
test_canonical_junit_trees_match_the_counts_artifacts detect a post-exit
drift, and THIS manifest is the archived snapshot of what the trees looked
like at the exit that produced the artifacts (per-tree file count + sha256
over the sorted per-class rows), so the exact exit state stays auditable
after any later clobber.

Run at every artifact-generation step (the canonical clean suite re-runs):

    python tools/archive_junit_tree_digest.py --label w1

Writes out/completion/w0/xml-tree-digest-<label>.json. Fails loud if a tree
that exists does not re-aggregate to the counts artifact beside it (the same
contract as the gate pin).
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import json
import sys
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

TREES = (
    ("android-unit-counts.json",
     ("android/app/build/test-results/testDebugUnitTest/*.xml",
      "android/pocketrealm-llm/build/test-results/test/*.xml",
      "android/pocketrealm-llm/build/test-results/testDebugUnitTest/*.xml")),
    ("desktop-test-counts.json",
     ("desktop/build/test-results/test/*.xml",)),
)


def aggregate(files: list[str]) -> tuple[dict, str]:
    rows = []
    totals = dict(tests=0, failures=0, errors=0, skipped=0, classes=0)
    for f in files:
        r = ET.parse(f).getroot()
        row = (
            Path(f).name,
            r.get("tests", "0"), r.get("failures", "0"),
            r.get("errors", "0"), r.get("skipped", "0"),
        )
        rows.append(row)
        totals["tests"] += int(row[1])
        totals["failures"] += int(row[2])
        totals["errors"] += int(row[3])
        totals["skipped"] += int(row[4])
        totals["classes"] += 1
    rows.sort()
    digest = hashlib.sha256(
        "\n".join("|".join(row) for row in rows).encode("utf-8")).hexdigest()
    return totals, digest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", required=True,
                        help="exit label, e.g. w1 — names the manifest file")
    parser.add_argument("--refresh", action="store_true",
                        help="allow overwriting an existing manifest (R3-2 "
                             "write-once guard: without this flag an existing "
                             "label's file is audit history and is never "
                             "silently rewritten — cite a NEW label or pass "
                             "--refresh and own the overwrite in the wave "
                             "ledger/Decision Log)")
    args = parser.parse_args()

    out_path = ROOT / "out/completion/w0" / f"xml-tree-digest-{args.label}.json"
    if out_path.is_file() and not args.refresh:
        existing = json.loads(out_path.read_text(encoding="utf-8"))
        raise SystemExit(
            f"REFUSED: {out_path.name} already exists (generated "
            f"{existing.get('generated', '?')}) and audit artifacts are "
            f"write-once — cite a new label or pass --refresh and record the "
            f"overwrite in the wave ledger/Decision Log (W6B R3-2)")

    manifest = {"generated": datetime.now().astimezone().isoformat(), "trees": {}}
    for artifact_name, xml_globs in TREES:
        artifact_path = ROOT / "out/completion/w0" / artifact_name
        files = [f for pattern in xml_globs for f in glob.glob(str(ROOT / pattern))]
        if not files:
            manifest["trees"][artifact_name] = {"files": 0, "state": "absent"}
            continue
        totals, digest = aggregate(files)
        entry = {**totals, "files": len(files), "digest": digest}
        if artifact_path.is_file():
            counts = json.loads(artifact_path.read_text(encoding="utf-8"))
            entry["matches_artifact"] = (
                totals["tests"] == counts["tests"]
                and totals["failures"] == counts["failures"]
                and totals["errors"] == counts["errors"]
                and totals["skipped"] == counts["skipped"]
                and totals["classes"] == counts["classes"])
            if not entry["matches_artifact"]:
                print(f"ERROR: {artifact_name} tree ({totals}) != artifact "
                      f"({counts['tests']}/{counts['classes']}) — re-run the "
                      "canonical clean suite and regenerate first", file=sys.stderr)
                return 1
        manifest["trees"][artifact_name] = entry

    out = ROOT / "out/completion/w0" / f"xml-tree-digest-{args.label}.json"
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, indent=1)
        f.write("\n")
    print(f"archived {out}")
    for name, entry in manifest["trees"].items():
        print(f"  {name}: {entry.get('files')} files, "
              f"{entry.get('tests')} tests, digest {entry.get('digest', '-')[:16]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
