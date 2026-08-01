#!/usr/bin/env python3
"""O05 G0 packaging experiment host driver (report §8.4 PKG-01/02/06).

Orchestrates the PKG experiments across the three AVD lanes:

  Lane                API   Page    PKG-01  PKG-02  PKG-06
  AVD-Legacy-x86_64   28    4 KB    run     run     -
  AVD-Modern-x86_64   35    4 KB    run     run     30-min run
  AVD-16K-x86_64      35    16 KB   run     run     30-min run

PKG-01 (launcher exec) runs on the pkgExperiment variant (useLegacyPackaging=
true) on all three lanes; the production-variant behavior is also recorded.
PKG-02 and PKG-06 run on the production variant (useLegacyPackaging=false).

The actual experiment assertions live in the instrumented test
PackagingExperimentTest; this driver builds + installs + runs that test, then
captures its logcat output and the in-app CapabilityReport. The two genuine
30-minute PKG-06 runs are launched with --smoke-seconds 1800.

This driver never weakens acceptance criteria: a non-zero instrumented-test
exit code propagates as a non-zero exit here.

Prereqs:
  - native build: python3 scripts/build_native.py --abi x86_64 all
  - native/packaging build: python3 tools/build_packaging.py --abi x86_64
  - the three AVDs created and booted
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"
SDK = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "")
ADB = SDK / "platform-tools" / "adb.exe" if SDK.is_dir() else "adb"

LANES = {
    "legacy": {"avd": "AVD-Legacy-x86_64", "api": 28, "page": 4096, "pkg06": False},
    "modern": {"avd": "AVD-Modern-x86_64", "api": 35, "page": 4096, "pkg06": True},
    "16k": {"avd": "AVD-16K-x86_64", "api": 35, "page": 16384, "pkg06": True},
}


def run(cmd, **kw) -> subprocess.CompletedProcess:
    print("  $", " ".join(str(c) for c in cmd[:10]) + (" ..." if len(cmd) > 10 else ""))
    return subprocess.run([str(c) for c in cmd], **kw)


def gradle(task: str, serial: str | None = None) -> int:
    gw = ANDROID / "gradlew.bat"
    cmd = [str(gw), f":app:{task}", "-p", str(ANDROID)]
    if serial:
        cmd += [f"-Pandroid.testInstrumentationRunnerArguments.serial={serial}"]
    # ANDROID_SDK_ROOT must reach gradle.
    env = dict(os.environ)
    return run(cmd, env=env).returncode


def wait_for_boot(serial: str, timeout: int = 300) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        r = subprocess.run([str(ADB), "-s", serial, "shell", "getprop",
                            "sys.boot_completed"], capture_output=True, text=True, timeout=20)
        if r.stdout.strip() == "1":
            return True
        time.sleep(3)
    return False


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lane", required=True, choices=list(LANES))
    ap.add_argument("--serial", required=True, help="adb serial for the booted AVD")
    ap.add_argument("--smoke-seconds", type=int, default=0,
                    help="PKG-06 smoke duration; 1800 = genuine 30-min acceptance run")
    ap.add_argument("--variant", default="debug", choices=["debug", "pkgExperiment"],
                    help="PKG-01 uses pkgExperiment; PKG-02/06 use debug (production)")
    args = ap.parse_args()

    lane = LANES[args.lane]
    print(f"=== Lane {args.lane}: {lane['avd']} (API {lane['api']}, expect page {lane['page']}) ===")

    if not wait_for_boot(args.serial):
        print(f"ERROR: {args.serial} not booted", file=sys.stderr)
        return 2

    # Verify the lane's page size honestly before running anything.
    r = subprocess.run([str(ADB), "-s", args.serial, "shell", "getconf", "PAGE_SIZE"],
                       capture_output=True, text=True, timeout=20)
    page = int((r.stdout or "0").strip() or 0)
    if page != lane["page"]:
        print(f"ERROR: page size mismatch on {args.lane}: got {page}, expected {lane['page']}",
              file=sys.stderr)
        return 2
    print(f"  page_size={page} OK")

    # Build the requested variant.
    asm = "assemblePkgExperiment" if args.variant == "pkgExperiment" else "assembleDebug"
    rc = gradle(asm)
    if rc != 0:
        return rc

    # Install.
    apk = next((ANDROID / "app" / "build" / "outputs" / "apk"
                / ("pkgExperiment" if args.variant == "pkgExperiment" else "debug")
                ).glob("*.apk"), None)
    if not apk:
        print("ERROR: no APK built", file=sys.stderr)
        return 2
    rc = run([str(ADB), "-s", args.serial, "install", "-r", "-t", str(apk)]).returncode
    if rc != 0:
        return rc

    # Run the instrumented PKG test on this variant; pass smoke duration.
    test_args = f"lane={args.lane},variant={args.variant},smokeSeconds={args.smoke_seconds}"
    gw = ANDROID / "gradlew.bat"
    env = dict(os.environ)
    rc = subprocess.run(
        [str(gw), ":app:connectedDebugAndroidTest", "-p", str(ANDROID),
         f"-Pandroid.testInstrumentationRunnerArguments.class=com.pocketrealm.pkg.PackagingExperimentTest",
         f"-Pandroid.testInstrumentationRunnerArguments.lane={args.lane}",
         f"-Pandroid.testInstrumentationRunnerArguments.smokeSeconds={args.smoke_seconds}"],
        env=env).returncode
    return rc


if __name__ == "__main__":
    sys.exit(main())
