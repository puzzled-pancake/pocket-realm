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
    # Page size: prefer getconf (single call, cached in page_str). Older images
    # (API 28) lack the getconf binary, so fall back to the smallest alignment
    # seen in /proc/self/maps (a reliable proxy for the kernel page size). The
    # app-side probe uses Os.sysconf(_SC_PAGE_SIZE) and is merged separately.
    page_str = first_line(sh("getconf PAGE_SIZE"))
    page_source = "getconf"
    if not page_str.isdigit():
        # API 28 has no getconf; derive from /proc/self/maps entry alignment.
        maps_align = None
        for line in sh("cat /proc/self/maps").splitlines():
            # lines look like: 7f0c1a2b3000-7f0c1a2b4000 r--p 00000000 ...
            parts = line.split()
            if not parts:
                continue
            addrs = parts[0].split("-")
            if len(addrs) == 2:
                try:
                    span = int(addrs[1], 16) - int(addrs[0], 16)
                    if span > 0 and (span & (span - 1)) == 0:  # power of two
                        if maps_align is None or span < maps_align:
                            maps_align = span
                except ValueError:
                    continue
        page_str = str(maps_align) if maps_align else "0"
        page_source = "proc_self_maps"
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
            "page_size_bytes": int(page_str) if page_str.isdigit() else 0,
            "page_size_source": page_source,
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


def pull_app_report(serial: str) -> dict | None:
    """Pull the in-app CapabilityReport JSON the test wrote to app-private
    storage and parse it. Returns None if unavailable."""
    assert ADB, "adb not found; set ANDROID_SDK_ROOT"
    # The report is written by CapabilityReport.writeToFile to
    # /data/data/<pkg>/app_capability/capability-report.json (debug build only;
    # run-as is required to read app-private storage).
    base = [str(ADB), "-s", serial]
    pkg = "com.pocketrealm"
    path = "/data/data/%s/app_capability/capability-report.json" % pkg
    r = subprocess.run(base + ["shell", "run-as", pkg, "cat", path],
                       capture_output=True, text=True, timeout=30)
    if r.returncode != 0 or not r.stdout.strip():
        return None
    try:
        return json.loads(r.stdout)
    except json.JSONDecodeError:
        return None


def merge_app_record(adb_record: dict, app_report: dict | None) -> dict:
    """Merge app-side-only fields (allocatable via StorageManager, app GL
    strings) into the adb record under a separate "app" key, and run the
    equivalent-field comparison."""
    record = json.loads(json.dumps(adb_record))  # deep copy
    if app_report is None:
        record["app"] = None
        record["comparison"] = {"status": "NO_APP_REPORT",
                                "note": "Run the instrumented test first so the app writes capability-report.json."}
        return record
    record["app"] = {
        "testRunId": app_report.get("testRunId"),
        "allocatableBytes_storageManager": app_report.get("allocatableBytes"),
        "allocatableVolumeLabel": app_report.get("allocatableVolumeLabel"),
        "glVendor_app": app_report.get("glVendor"),
        "glRenderer_app": app_report.get("glRenderer"),
        "glVersion_app": app_report.get("glVersion"),
        "nativeLibraryDirObserved": app_report.get("nativeLibraryDirObserved"),
    }
    rows = compare(adb_record, app_report)
    record["comparison"] = {
        "status": "PASS" if all(ok for _, _, _, ok in rows) else "MISMATCH",
        "fields": [
            {"field": f, "adb": a, "app": ap, "ok": ok}
            for f, a, ap, ok in rows
        ],
        "note": ("Allocatable storage (StorageManager vs df) and GL strings "
                 "(app EGL vs host/emulator) are recorded separately and not compared."),
    }
    return record


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--avd-id", required=True, help="e.g. AVD-Modern-x86_64-v1")
    ap.add_argument("--system-image", default=None)
    ap.add_argument("--compare", default=None, help="app CapabilityReport JSON file to diff")
    ap.add_argument("--pull-app-report", action="store_true",
                    help="pull the in-app capability-report.json from the device and merge+compare")
    ap.add_argument("--checkin", action="store_true",
                    help="write to tests/avd/<avd-id>.json (default: print)")
    args = ap.parse_args()

    record = capture(args.serial, args.avd_id, args.system_image)

    app_report = None
    if args.pull_app_report:
        app_report = pull_app_report(args.serial)
        if app_report is None:
            print("WARNING: could not pull app capability-report.json "
                  "(is the instrumented test run complete?)", file=sys.stderr)
    elif args.compare:
        app_report = json.loads(Path(args.compare).read_text(encoding="utf-8"))

    record = merge_app_record(record, app_report)
    out = json.dumps(record, indent=2, ensure_ascii=False)

    if args.checkin:
        path = ROOT / "tests" / "avd" / f"{args.avd_id}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(out + "\n", encoding="utf-8")
        print(f"wrote {path}")
    else:
        print(out)

    # Print a human-readable comparison summary to stdout for the driver log.
    if app_report is not None:
        rows = compare(record, app_report)
        print("\n--- capability comparison (adb vs app) ---")
        for field, adb_v, app_v, ok in rows:
            print(f"  [{'OK ' if ok else 'MISMATCH'}] {field}: adb={adb_v!r} app={app_v!r}")
        print("ALLOCATABLE (separate): df=%r storageManager=%r"
              % (record['guest']['df_data_line'], app_report.get('allocatableBytes')))
        print("GL (separate): host=%s app_vendor=%r"
              % (record['guest']['gl_host'], app_report.get('glVendor')))
        return 0 if all(ok for _, _, _, ok in rows) else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
