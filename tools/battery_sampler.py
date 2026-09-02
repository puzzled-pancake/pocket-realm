#!/usr/bin/env python3
"""Battery drain sampler for on-device engine tests.

Polls the kernel power-supply node (current_now uA, voltage_now uV,
capacity %) over adb and writes a CSV with derived milliwatts. Discharge
is positive mW (charging shows as negative). Run it for the DURATION OF
A MEASURED WINDOW with the device unplugged; compare windows between
engines with identical bot load, screen state and CPU governor.

Usage:
    python3 tools/battery_sampler.py --serial <SERIAL> --duration-s 600 \
        --out build/battery/sqlite-window.csv
"""

import argparse
import csv
import datetime as _dt
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = None


def read_node(name: str) -> str:
    out = subprocess.run(
        [ADB, "shell", "cat", f"/sys/class/power_supply/battery/{name}"],
        capture_output=True, text=True, timeout=30)
    return out.stdout.strip()


def main() -> int:
    global ADB
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--duration-s", type=int, required=True)
    parser.add_argument("--interval-s", type=float, default=5.0)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    import os
    os.environ["ANDROID_SERIAL"] = args.serial
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "run_differential_parity", ROOT / "tools" / "run_differential_parity.py")
    lane = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(lane)
    ADB = str(lane.ADB)

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    deadline = time.time() + args.duration_s
    rows = []
    # sane sensor probe first: refuse to record a plugged-in window
    plugged = read_node("status")
    if plugged == "Charging":
        print(f"WARN: battery status is '{plugged}' - unplug the device or "
              f"this window is not a discharge measurement")
    while time.time() < deadline:
        try:
            ua = int(read_node("current_now"))
            uv = int(read_node("voltage_now"))
        except ValueError:
            ua = uv = 0
        cap = read_node("capacity")
        status = read_node("status")
        # kernels differ on sign; normalize: discharge positive
        mw = abs(ua) * abs(uv) / 1e9
        if status == "Charging":
            mw = -mw
        rows.append({
            "t": _dt.datetime.now().isoformat(timespec="seconds"),
            "uA": ua, "uV": uv, "capacityPct": cap, "status": status,
            "mW": round(mw, 1),
        })
        print(f"{len(rows):4d} {status:10s} {cap:>3s}% {mw:8.1f} mW",
              flush=True)
        time.sleep(args.interval_s)

    with out_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    discharge = [r["mW"] for r in rows if r["mW"] > 0]
    if discharge:
        avg = sum(discharge) / len(discharge)
        print(f"\nwindow: {len(rows)} samples, {len(discharge)} discharging, "
              f"avg {avg:.0f} mW, min {min(discharge):.0f}, "
              f"max {max(discharge):.0f} -> {out_path}")
    else:
        print(f"\nwindow: NO discharging samples (plugged in?) -> {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
