#!/usr/bin/env python3
"""Print the active feature, or the next dependency-eligible feature.

Pass --activate to atomically mark the selected pending feature active.
"""
from pathlib import Path
import argparse
import json
import sys

parser = argparse.ArgumentParser()
parser.add_argument("--activate", action="store_true")
args = parser.parse_args()

path = Path(__file__).resolve().parents[1] / "FEATURES.json"
data = json.loads(path.read_text(encoding="utf-8"))
features = data["features"]
by_id = {f["id"]: f for f in features}

if len(by_id) != len(features):
    print("ERROR: duplicate feature ID", file=sys.stderr)
    sys.exit(2)

active = [f for f in features if f["status"] == "active"]
if len(active) > 1:
    print("ERROR: more than one active feature", file=sys.stderr)
    sys.exit(2)
if active:
    print(json.dumps(active[0], indent=2))
    sys.exit(0)

eligible = [
    f for f in features
    if f["status"] == "pending"
    and all(by_id[d]["status"] == "done" for d in f.get("depends", []))
]
if not eligible:
    remaining = [f for f in features if f["status"] not in {"done", "cancelled"}]
    if remaining:
        print("No eligible feature. Resolve blocked dependencies:", file=sys.stderr)
        for f in remaining:
            print(f"- {f['id']} {f['status']}: {f['title']}", file=sys.stderr)
        sys.exit(1)
    print("All features are done.")
    sys.exit(0)

eligible.sort(key=lambda f: (f["priority"], f["id"]))
selected = eligible[0]
if args.activate:
    selected["status"] = "active"
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(json.dumps(selected, indent=2))
