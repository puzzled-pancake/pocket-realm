#!/usr/bin/env python3
"""O05 G0 packaging experiment host driver (report §8.4 PKG-01/02/06).

Serial-specific AND variant-specific: targets exactly one --serial and one
--variant, installs the matching APK (and the androidTest APK), runs the
requested experiment via `am instrument` against that one device, and captures
the full logcat evidence to tests/avd/<lane>/evidence/.

It does NOT use `connectedDebugAndroidTest` (that task runs on ALL connected
devices and re-targets the debug variant regardless of --variant, which would
mask variant-specific failures). Instead it:

  1. builds the requested variant APK (assemble<Variant>) + the androidTest APK,
  2. installs both on --serial only,
  3. runs the experiment class via `am instrument -e` with the right args,
  4. drains logcat for the PKG_EXPERIMENT/PKG-06 TICK lines,
  5. writes evidence to tests/avd/<lane>/evidence/.

Lanes:
  legacy  AVD-Legacy-x86_64   API 28  4 KB   PKG-01 (pkgExperiment) + PKG-02 (debug)
  modern  AVD-Modern-x86_64   API 35  4 KB   PKG-01 (pkgExperiment) + PKG-02 + PKG-06 (debug)
  16k     AVD-16K-x86_64      API 35  16 KB  PKG-01 (pkgExperiment) + PKG-02 + PKG-06 (debug)

The two genuine 30-minute PKG-06 runs use --smoke-seconds 1800.

Prereqs:
  python3 scripts/build_native.py --abi x86_64 --runtime --runtime-tests cmangos
  python3 tools/build_packaging.py --abi x86_64
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"
SDK = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "")
ADB = str(SDK / "platform-tools" / "adb.exe") if SDK.is_dir() else "adb"

LANES = {
    "legacy": {"avd": "AVD-Legacy-x86_64", "api": 28, "page": 4096, "pkg06": False},
    "modern": {"avd": "AVD-Modern-x86_64", "api": 35, "page": 4096, "pkg06": True},
    "16k": {"avd": "AVD-16K-x86_64", "api": 35, "page": 16384, "pkg06": True},
}
PKG = "com.pocketrealm"
TEST_PKG = "com.pocketrealm.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "com.pocketrealm.pkg.PackagingExperimentTest"


def run(cmd, **kw) -> subprocess.CompletedProcess:
    print("  $", " ".join(str(c) for c in cmd[:10]) + (" ..." if len(cmd) > 10 else ""))
    return subprocess.run([str(c) for c in cmd], **kw)


def adb(serial: str, *args: str, capture=True, timeout=120) -> str:
    cmd = [ADB, "-s", serial] + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if r.returncode != 0 and capture:
        print(f"  adb warning (rc={r.returncode}): {r.stderr.strip()[:200]}", file=sys.stderr)
    return r.stdout if capture else ""


def wait_for_boot(serial: str, timeout: int = 300) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if adb(serial, "shell", "getprop", "sys.boot_completed").strip() == "1":
            return True
        time.sleep(3)
    return False


def variant_apk_name(variant: str) -> str:
    return "app-pkgExperiment.apk" if variant == "pkgExperiment" else "app-debug.apk"


def variant_apk_dir(variant: str) -> str:
    return "pkgExperiment" if variant == "pkgExperiment" else "debug"


def gradle_assemble(variant: str) -> int:
    gw = str(ANDROID / "gradlew.bat")
    task = "assemblePkgExperiment" if variant == "pkgExperiment" else "assembleDebug"
    env = dict(os.environ)
    rc = subprocess.run([gw, f":app:{task}", "-p", str(ANDROID),
                         "-Pandroid.suppressUnsupportedCompileSdk=35"], env=env).returncode
    if rc != 0:
        return rc
    # The androidTest APK is variant-agnostic; build it once (debug component).
    return subprocess.run([gw, ":app:assembleDebugAndroidTest", "-p", str(ANDROID),
                           "-Pandroid.suppressUnsupportedCompileSdk=35"], env=env).returncode


def install(serial: str, variant: str) -> int:
    app_apk = ANDROID / "app" / "build" / "outputs" / "apk" / variant_apk_dir(variant) / variant_apk_name(variant)
    test_apk = ANDROID / "app" / "build" / "outputs" / "apk" / "androidTest" / "debug" / "app-debug-androidTest.apk"
    for apk in (app_apk, test_apk):
        if not apk.is_file():
            print(f"ERROR: missing APK {apk}", file=sys.stderr)
            return 2
        rc = run([ADB, "-s", serial, "install", "-r", "-t", str(apk)]).returncode
        if rc != 0:
            return rc
    return 0


def run_instrument(serial: str, variant: str, lane: str, smoke_seconds: int,
                   only: str | None = None) -> tuple[int, str]:
    """Run the experiment(s) via am instrument on this one serial. Returns
    (instrument_rc, logcat_of_PKG_lines)."""
    adb(serial, "logcat", "-c")
    classes = f"{TEST_CLASS}#{only}" if only else TEST_CLASS
    cmd = [ADB, "-s", serial, "shell", "am", "instrument", "-w", "-r",
           "-e", "class", classes,
           "-e", "lane", lane,
           "-e", "variant", variant,
           "-e", "smokeSeconds", str(smoke_seconds),
           f"{TEST_PKG}/{RUNNER}"]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=60 * 60)
    instr_out = r.stdout + r.stderr
    # Drain the PKG evidence lines from logcat (full per-tick history).
    log = adb(serial, "logcat", "-d")
    pkg_lines = [ln for ln in log.splitlines()
                 if "PKG_EXPERIMENT" in ln or "PKG-06 TICK" in ln or "PKG_CAPABILITY" in ln]
    return r.returncode, "\n".join(pkg_lines) + "\n--- instrument stdout ---\n" + instr_out


def write_evidence(lane: str, name: str, content: str) -> Path:
    d = ROOT / "tests" / "avd" / f"{LANES[lane]['avd']}-v1" / "evidence"
    d.mkdir(parents=True, exist_ok=True)
    p = d / name
    p.write_text(content, encoding="utf-8")
    return p


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lane", required=True, choices=list(LANES))
    ap.add_argument("--serial", required=True, help="adb serial of the booted AVD")
    ap.add_argument("--variant", required=True, choices=["debug", "pkgExperiment"],
                    help="debug = production (useLegacyPackaging=false); "
                         "pkgExperiment = launcher extraction (useLegacyPackaging=true)")
    ap.add_argument("--smoke-seconds", type=int, default=10,
                    help="PKG-06 duration; 1800 = genuine 30-min acceptance run")
    ap.add_argument("--only", default=None,
                    help="single test method suffix, e.g. t2_pkg01_launcher_executes_or_documents_no_path")
    ap.add_argument("--no-build", action="store_true", help="skip gradle assemble")
    args = ap.parse_args()

    lane = LANES[args.lane]
    print(f"=== Lane {args.lane}: {lane['avd']} (API {lane['api']}, expect page {lane['page']}); "
          f"variant={args.variant} serial={args.serial} ===")

    if not wait_for_boot(args.serial):
        print(f"ERROR: {args.serial} not booted", file=sys.stderr)
        return 2

    page_out = adb(args.serial, "shell", "getconf", "PAGE_SIZE").strip()
    page = int(page_out or 0)
    if args.lane != "legacy" and page != lane["page"]:
        # Legacy API 28 has no getconf; skip the page assertion there.
        print(f"ERROR: page mismatch on {args.lane}: got {page}, expected {lane['page']}", file=sys.stderr)
        return 2
    print(f"  page_size={page if page else '(getconf absent on API 28; verified via app probe)'}")

    if not args.no_build:
        rc = gradle_assemble(args.variant)
        if rc != 0:
            return rc

    rc = install(args.serial, args.variant)
    if rc != 0:
        return rc

    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    rc, pkg_log = run_instrument(args.serial, args.variant, args.lane,
                                 args.smoke_seconds, args.only)
    name = f"{args.variant}-{args.only or 'all'}-{ts}.log"
    ev = write_evidence(args.lane, name, pkg_log)
    print(f"  wrote evidence: {ev.relative_to(ROOT)}")
    # Also capture the device-side capability report + check it in if present.
    cap = adb(args.serial, "shell", "run-as", PKG, "cat",
              f"/data/data/{PKG}/app_capability/capability-report.json")
    if cap.strip():
        cp = write_evidence(args.lane, f"capability-report-{args.variant}-{ts}.json",
                            cap.strip() + "\n")
        print(f"  wrote capability report: {cp.relative_to(ROOT)}")
    return rc


if __name__ == "__main__":
    sys.exit(main())
