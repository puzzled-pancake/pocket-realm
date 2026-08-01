#!/usr/bin/env python3
"""Capture a checked-in AVD/device capability record (report X0 / §20.1 / §C.2).

Runs the report's getprop/getconf/meminfo/df commands via adb against a given
serial, writes/refreshes tests/avd/<avdId>.json (format mirrors schemas/*.json),
and (with --compare <app-report.json>) compares ONLY genuinely equivalent fields
between the adb capture and the in-app CapabilityReport.

Fields recorded SEPARATELY (never forced equal):
  - allocatable_bytes_df      : adb `df` free on /data (host-side)
  - allocatable_bytes_storage : StorageManager.getAllocatableBytes (app-side)
  - gl_*_host                 : host/emulator graphics configuration
  - gl_*_app                  : app in-process GLES strings

Usage:
  python3 tools/capture_avd.py --serial emulator-5554 --avd-id AVD-Modern-x86_64-v1
  python3 tools/capture_avd.py --serial ... --avd-id ... --compare app-report.json
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "")
ADB = SDK / "platform-tools" / "adb.exe" if SDK.is_dir() else shutil.which("adb")
REFERENCE = "docs/SPP_Classics_WoW_1.12.1_Android_Port_Report.docx"


def adb(serial: str, shell_cmd: str) -> str:
    assert ADB, "adb not found; set ANDROID_SDK_ROOT"
    r = subprocess.run([str(ADB), "-s", serial, "shell", shell_cmd],
                       capture_output=True, text=True, timeout=30)
    if r.returncode != 0:
        print(f"adb shell failed: {r.stderr.strip()}", file=sys.stderr)
    return r.stdout.strip()


def first_line(s: str) -> str:
    return (s or "").splitlines()[0].strip() if s else ""


def capture(serial: str, avd_id: str, system_image: str | None) -> dict:
    sh = lambda c: adb(serial, c)
    sdk = first_line(sh("getprop ro.build.version.sdk"))
    mem_total = ""
    for line in sh("cat /proc/meminfo").splitlines():
        if line.startswith("MemTotal:"):
            mem_total = line.split()[1]
            break
    df_line = ""
    for line in sh("df /data").splitlines():
        if "/data" in line:
            df_line = line
            break
    # Page size: prefer getconf; older images (API 28) lack it, so fall back to
    # the syscall via a tiny getconf replacement reading /proc. The app-side
    # probe uses Os.sysconf(_SC_PAGE_SIZE), which is the authoritative source.
    page_str = first_line(sh("getconf PAGE_SIZE"))
    if not page_str.isdigit():
        # API 28 has no getconf binary; derive from the Bionic page-size via
        # `cat /proc/self/maps` alignment, or accept the app probe's value.
        page_str = "0"
    # Best-effort GL strings from the guest (dumpsys SurfaceFlinger / getprop).
    gl_host = {
        "egl": sh("getprop ro.hardware.egl"),
        "vulkan": sh("getprop ro.hardware.vulkan"),
        "gralloc": sh("getprop ro.hardware.gralloc"),
    }
    record = {
        "schema": 1,
        "reference_document": REFERENCE,
        "avd_id": avd_id,
        "system_image": system_image or sh("getprop ro.bootimage.build.fingerprint") or "unknown",
        "captured_at_utc": datetime.now(timezone.utc).isoformat(),
        "host": {
            "emulator_version": _host_emulator_version(),
        },
        "guest": {
            "api_level": int(sdk) if sdk.isdigit() else sdk,
            "abilist": sh("getprop ro.product.cpu.abilist"),
            "abilist32": sh("getprop ro.product.cpu.abilist32"),
            "abilist64": sh("getprop ro.product.cpu.abilist64"),
            "page_size_bytes": int(first_line(sh("getconf PAGE_SIZE")) or 0),
            "mem_total_kb": int(mem_total) if mem_total.isdigit() else mem_total,
            "df_data_line": df_line,
            # host-side GL config (recorded separately from app GLES strings)
            "gl_host": gl_host,
            "build_id": sh("getprop ro.build.id"),
            "avd_name": sh("getprop ro.kernel.qemu.avd_name"),
        },
    }
    return record


def _host_emulator_version() -> str:
    emu = SDK / "emulator" / "emulator.exe" if SDK.is_dir() else shutil.which("emulator")
    if not emu:
        return ""
    try:
        r = subprocess.run([str(emu), "-version"], capture_output=True, text=True, timeout=10)
        return first_line(r.stdout) or first_line(r.stderr)
    except Exception:
        return ""


# Fields compared between adb capture and the in-app CapabilityReport. Only
# genuinely-equivalent fields are compared; allocatable storage and GL strings
# are recorded separately on each side (see module docstring).
COMPARABLE = {
    # app_field : adb_extractor
    "sdkInt": lambda r: int(r["guest"]["api_level"]) if str(r["guest"]["api_level"]).isdigit() else None,
    "pageSizeBytes": lambda r: r["guest"]["page_size_bytes"],
    # abilist is a list on the app side, a comma-joined string on adb.
    "abilist64": lambda r: r["guest"]["abilist64"],
}


def compare(adb_record: dict, app_report: dict) -> list[tuple[str, str, str, bool]]:
    mismatches: list[tuple[str, str, str, bool]] = []
    for app_field, extract in COMPARABLE.items():
        adb_val = extract(adb_record)
        app_val = app_report.get(app_field)
        # abilist64 special-case: app is a list, adb is comma-joined.
        if app_field == "abilist64":
            app_val_str = ",".join(app_val) if isinstance(app_val, list) else str(app_val or "")
        else:
            app_val_str = str(app_val)
        ok = str(adb_val) == app_val_str
        mismatches.append((app_field, str(adb_val), app_val_str, ok))
    return mismatches


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--avd-id", required=True, help="e.g. AVD-Modern-x86_64-v1")
    ap.add_argument("--system-image", default=None)
    ap.add_argument("--compare", default=None, help="app CapabilityReport JSON to diff")
    ap.add_argument("--checkin", action="store_true",
                    help="write to tests/avd/<avd-id>.json (default: print)")
    args = ap.parse_args()

    record = capture(args.serial, args.avd_id, args.system_image)
    out = json.dumps(record, indent=2, ensure_ascii=False)

    if args.checkin:
        path = ROOT / "tests" / "avd" / f"{args.avd_id}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(out + "\n", encoding="utf-8")
        print(f"wrote {path}")
    else:
        print(out)

    if args.compare:
        app = json.loads(Path(args.compare).read_text(encoding="utf-8"))
        rows = compare(record, app)
        all_ok = True
        print("\n--- capability comparison (adb vs app) ---")
        for field, adb_v, app_v, ok in rows:
            tag = "OK " if ok else "MISMATCH"
            if not ok:
                all_ok = False
            print(f"  [{tag}] {field}: adb={adb_v!r} app={app_v!r}")
        print("ALLOCATABLE (recorded separately, NOT compared): "
              f"df_data={record['guest']['df_data_line']!r} "
              f"app_allocatable={app.get('allocatableBytes')!r}")
        print("GL (recorded separately, NOT compared): "
              f"host={record['guest']['gl_host']} app_vendor={app.get('glVendor')!r}")
        return 0 if all_ok else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
