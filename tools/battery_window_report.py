#!/usr/bin/env python3
"""Align battery-sampler CSVs to benchmark soak windows and print the
engine comparison. Each window: the evidence JSON's wave sample atMs
range defines the soak; CSV rows inside that span average to mW."""

import csv
import datetime as _dt
import json
import statistics as st
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def window(csv_path: Path, evidence_path: Path, label: str):
    if not csv_path.is_file() or not evidence_path.is_file():
        print(f"{label}: MISSING ({csv_path.name} / {evidence_path.name})")
        return None
    ev = json.loads(evidence_path.read_text(encoding="utf-8"))
    wave = (ev.get("waves") or [{}])[0]
    samples = wave.get("samples") or []
    if not samples:
        print(f"{label}: no soak samples in evidence")
        return None
    t0, t1 = samples[0]["atMs"], samples[-1]["atMs"]
    rows = []
    with csv_path.open(encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            try:
                t = _dt.datetime.fromisoformat(row["t"]).timestamp() * 1000
                mw = float(row["mW"])
            except Exception:
                continue
            if t0 <= t <= t1 and mw > 0:
                rows.append(mw)
    bots = [s["botsOnline"] for s in samples]
    p95 = [s["worldTickP95Ms"] for s in samples]
    out = {
        "label": label,
        "provider": ev.get("providerMode"),
        "soakS": round((t1 - t0) / 1000),
        "botsStart": bots[0], "botsEnd": bots[-1], "botsPeak": max(bots),
        "samples": len(rows),
        "mW_avg": round(st.mean(rows), 1) if rows else None,
        "mW_med": round(st.median(rows), 1) if rows else None,
        "mW_max": round(max(rows), 1) if rows else None,
        "tickP95_med": st.median(p95),
        "windowMax": max(s["worldTickWindowMaxMs"] for s in samples),
        "stalls": samples[-1].get("worldHardStalls"),
        "saveallAckMs": wave.get("saveallAckMs"),
        "worldRssMiB": round(samples[-1]["worldRssKb"] / 1024),
    }
    if rows and out["botsPeak"]:
        out["mW_per_100bots"] = round(out["mW_avg"] / out["botsPeak"] * 100, 1)
    return out


def main() -> int:
    pairs = [
        ("build/battery/sqlite-b2r-window.csv",
         "build/bot-pressure/20260827-122018/evidence-b.json",
         "SQLite battery soak (b600 profile, on-battery)"),
        ("build/battery/mariadb-battery-final.csv", None, None),  # resolved below
    ]
    # resolve the newest 'a' evidence for the final battery window
    runs = sorted(Path(ROOT, "build/bot-pressure").glob("*/evidence-a.json"))
    pairs[1] = ("build/battery/mariadb-battery-final.csv",
                str(runs[-1]) if runs else "", "MariaDB battery soak (b600)")
    pairs += [
        ("build/battery/sqlite-forced1000.csv", "", "SQLite forced-1000"),
        ("build/battery/mariadb-forced1000.csv", "", "MariaDB forced-1000"),
    ]
    for csvp, evp, label in pairs:
        if not evp:
            print(f"{label}: no evidence yet")
            continue
        out = window(Path(ROOT, csvp), Path(evp), label)
        if out:
            print(json.dumps(out, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
