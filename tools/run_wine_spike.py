#!/usr/bin/env python3
"""O06 Phase-1 Wine feasibility spike host driver.

Builds the pkgExperiment APK (with the Wine + glibc + PE closure), installs it
on an AVD, runs the WineSpikeTest instrumentation, drains logcat for the
WINE_SPIKE_* markers, and writes evidence to tests/avd/<lane>/evidence/.

Mirrors tools/run_pkg_experiments.py's adb/build/install/logcat/evidence patterns.

Usage:
  python3 tools/run_wine_spike.py --lane modern --serial emulator-5554
  python3 tools/run_wine_spike.py --lane modern --serial ... --only t1_s1_effective_loader
  python3 tools/run_wine_spike.py --lane modern --serial ... --no-build   # skip gradle

Prerequisites:
  - AVD booted (the driver waits for sys.boot_completed)
  - python tools/stage_wine_runtime.py  (generate the Wine/glibc/PE staging)
  - python tools/build_wine_spike.py --abi x86_64  (build libwine_spike.so)
  - python scripts/build_native.py --abi x86_64 all  (realm facade etc.)
  - python tools/build_packaging.py --abi x86_64  (PKG JNI shim)
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
ADB = SDK / "platform-tools" / "adb.exe" if SDK.is_dir() else Path("adb")

PKG = "com.pocketrealm"
TEST_PKG = "com.pocketrealm.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "com.pocketrealm.wine.WineSpikeTest"

LANES = {
    "modern": {"avd": "AVD-Modern-x86_64", "page": 4096},
    "16k":    {"avd": "AVD-16K-x86_64",   "page": 16384},
}


def adb(serial: str, *args: str, capture=True, timeout=120) -> str:
    cmd = [str(ADB), "-s", serial] + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if r.returncode != 0 and capture:
        print(f"  adb warn: {' '.join(args[:3])}... rc={r.returncode}", file=sys.stderr)
    return r.stdout if capture else ""


def wait_for_boot(serial: str, timeout_s=300) -> bool:
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        r = adb(serial, "shell", "getprop", "sys.boot_completed", timeout=10)
        if r.strip() == "1":
            return True
        time.sleep(3)
    return False


def assert_page_size(serial: str, expected: int) -> bool:
    r = adb(serial, "shell", "getconf", "PAGE_SIZE", timeout=15)
    actual = r.strip()
    if actual.isdigit() and int(actual) == expected:
        print(f"  page size: {actual} OK")
        return True
    print(f"  page size MISMATCH: expected {expected}, got '{actual}'", file=sys.stderr)
    return False


def probe_device(serial: str) -> dict[str, str]:
    """Fetch API level, ABI list, page size for evidence provenance."""
    api = adb(serial, "shell", "getprop", "ro.build.version.sdk", timeout=15).strip()
    abi = adb(serial, "shell", "getprop", "ro.product.cpu.abi", timeout=15).strip()
    abi_list = adb(serial, "shell", "getprop", "ro.product.cpu.abilist", timeout=15).strip()
    page = adb(serial, "shell", "getconf", "PAGE_SIZE", timeout=15).strip()
    return {"api": api or "?", "abi": abi or "?",
            "abilist": abi_list or "?", "kernel_page_size": page or "?"}


def gradle_build():
    gw = str(ANDROID / "gradlew.bat")
    print("== building pkgExperiment APK ==")
    subprocess.run([gw, ":app:assemblePkgExperiment",
                    "-p", str(ANDROID),
                    "-Pandroid.suppressUnsupportedCompileSdk=35"], check=True)
    print("== building androidTest APK ==")
    subprocess.run([gw, ":app:assembleDebugAndroidTest",
                    "-p", str(ANDROID),
                    "-Pandroid.suppressUnsupportedCompileSdk=35"], check=True)


def install_apks(serial: str):
    app_apk = ANDROID / "app" / "build" / "outputs" / "apk" / "pkgExperiment" / "app-pkgExperiment.apk"
    test_apk = ANDROID / "app" / "build" / "outputs" / "apk" / "androidTest" / "debug" / "app-debug-androidTest.apk"
    for apk in (app_apk, test_apk):
        if not apk.is_file():
            print(f"ERROR: APK not found: {apk}", file=sys.stderr)
            sys.exit(2)
    print("== installing app APK ==")
    adb(serial, "install", "-r", "-t", str(app_apk), timeout=120)
    print("== installing test APK ==")
    adb(serial, "install", "-r", "-t", str(test_apk), timeout=120)


def run_instrumentation(serial: str, lane: str, only: str | None, smoke_seconds: int) -> tuple[str, str]:
    """Run the WineSpikeTest instrumentation. Returns (instrument_stdout, logcat)."""
    adb(serial, "logcat", "-c")  # clear before running
    classes = f"{TEST_CLASS}#{only}" if only else TEST_CLASS
    cmd = [str(ADB), "-s", serial, "shell", "am", "instrument", "-w", "-r",
           "-e", "class", classes,
           "-e", "lane", lane,
           "-e", "variant", "pkgExperiment",
           "-e", "smokeSeconds", str(smoke_seconds),
           f"{TEST_PKG}/{RUNNER}"]
    print(f"  $ {' '.join(cmd)}")
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
        instr_out = r.stdout + r.stderr
    except subprocess.TimeoutExpired:
        instr_out = "(instrumentation timed out after 600s)"

    # Drain logcat.
    try:
        log = adb(serial, "logcat", "-d", timeout=120)
    except Exception:
        log = ""
    return instr_out, log


def parse_outcome(instr_out: str) -> tuple[int, int, int]:
    """Parse INSTRUMENTATION_STATUS lines. Returns (ran, passed, failed)."""
    ran = instr_out.count("INSTRUMENTATION_STATUS_CODE: 1")  # STARTED
    passed = instr_out.count("INSTRUMENTATION_STATUS_CODE: 0")  # OK
    failed = 0
    if "FAILURES!!!" in instr_out or "There were" in instr_out:
        # Count failures from the summary.
        import re
        m = re.search(r"Tests run:\s*(\d+),\s*Failures:\s*(\d+)", instr_out)
        if m:
            failed = int(m.group(2))
    return ran, passed, failed


def parse_wine_spike_results(wine_lines: list[str]) -> dict[str, dict[str, str]]:
    """Parse WINE_SPIKE_*_RESULT ok=... lines into {exp: {ok, code, ...}}.

    Each result line looks like:
        ... WINE_SPIKE_S1_RESULT  ok=true  code=LOADER_PROVEN ...
    We split on whitespace, find tok=VALUE pairs, and key by the experiment
    (S1/S2/S3) parsed from the tag.
    """
    import re
    results: dict[str, dict[str, str]] = {}
    for ln in wine_lines:
        m = re.search(r"WINE_SPIKE_(S\d)_RESULT\b(.*)", ln)
        if not m:
            continue
        exp = m.group(1)
        tail = m.group(2)
        fields: dict[str, str] = {}
        for tok in tail.split():
            if "=" in tok:
                k, _, v = tok.partition("=")
                fields[k] = v
        results[exp] = fields
    return results


def write_evidence(lane: str, name: str, content: str, passed: bool):
    """Write exactly ONE evidence artifact (PASS or FAIL), after the outcome is
    determined. The suffix encodes the outcome; we do NOT write a second
    byte-identical .FAIL.log copy (that recreated the duplicate pairs we
    removed)."""
    avd = LANES[lane]["avd"]
    d = ROOT / "tests" / "avd" / f"{avd}-v1" / "evidence"
    d.mkdir(parents=True, exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    suffix = "PASS" if passed else "FAIL"
    path = d / f"pkgExperiment-wine_spike-{name}-{ts}.{suffix}.log"
    path.write_text(content, encoding="utf-8")
    print(f"  evidence -> {path.relative_to(ROOT)}")
    return path


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--lane", required=True, choices=list(LANES))
    ap.add_argument("--serial", required=True, help="adb device serial (e.g. emulator-5554)")
    ap.add_argument("--only", help="run only one test method (e.g. t1_s1_effective_loader)")
    ap.add_argument("--smoke-seconds", type=int, default=30)
    ap.add_argument("--no-build", action="store_true", help="skip gradle build")
    ap.add_argument("--no-install", action="store_true", help="skip APK install")
    args = ap.parse_args()

    print(f"=== O06 Phase-1 Wine spike ===")
    print(f"  lane   : {args.lane} (page={LANES[args.lane]['page']})")
    print(f"  serial : {args.serial}")

    # 1. Wait for boot.
    print("== waiting for boot ==")
    if not wait_for_boot(args.serial):
        print("ERROR: device did not boot within 300s", file=sys.stderr)
        return 1

    # 2. Assert page size.
    if not assert_page_size(args.serial, LANES[args.lane]["page"]):
        return 1

    # 3. Build + install.
    if not args.no_build:
        gradle_build()
    if not args.no_install:
        install_apks(args.serial)

    # 4. Run instrumentation.
    print("== running WineSpikeTest ==")
    suffix = args.only or "all"
    instr_out, log = run_instrumentation(args.serial, args.lane, args.only, args.smoke_seconds)

    # 5. Filter logcat for WINE_SPIKE lines.
    wine_lines = [ln for ln in log.splitlines()
                  if "WINE_SPIKE" in ln or "wine_spike" in ln]

    # 6. Parse the per-experiment results BEFORE writing evidence, so we write
    #    exactly ONE artifact (PASS or FAIL), not a normal copy + a byte-
    #    identical .FAIL.log copy.
    results = parse_wine_spike_results(wine_lines)
    ran, passed_n, failed_n = parse_outcome(instr_out)

    # Determine the overall verdict:
    #   - instrumentation must not have failed (failed_n == 0, ran > 0)
    #   - AND every emitted WINE_SPIKE_*_RESULT must report ok=true
    #   - if --only was passed, that one test's result must be ok=true
    all_results_ok = all(r.get("ok") == "true" for r in results.values())
    expected_exps = ([args.only.split("_", 1)[0].upper().replace("T1", "S1")
                      .replace("T2", "S2").replace("T3", "S3")] if args.only
                     else ["S1", "S2", "S3"])
    # We only require the results for experiments that actually ran. If a test
    # produced no RESULT line at all, treat as not-ok.
    saw_expected = any(exp in results for exp in expected_exps)
    overall_ok = (ran > 0 and failed_n == 0 and all_results_ok and saw_expected)

    print(f"\n=== outcome ===")
    print(f"  ran={ran} passed={passed_n} failed={failed_n}")
    print(f"  WINE_SPIKE lines captured: {len(wine_lines)}")
    print(f"  results: {results}")

    # Print the key result lines.
    for ln in wine_lines:
        if "RESULT" in ln:
            print(f"  {ln}")

    # 7. Build the evidence text WITH provenance metadata, then write one file.
    dev = probe_device(args.serial)
    meta = {
        "serial": args.serial,
        "avd": LANES[args.lane]["avd"],
        "lane": args.lane,
        "api": dev["api"],
        "abi": dev["abi"],
        "abilist": dev["abilist"],
        "kernel_page_size": dev["kernel_page_size"],
        "expected_page_size": LANES[args.lane]["page"],
        "variant": "pkgExperiment",
        "test": suffix,
        "result_ok": overall_ok,
        "results": ";".join(f"{k}={v.get('ok')}/{v.get('code')}" for k, v in sorted(results.items())),
    }
    meta_block = "\n".join(f"# {k}: {v}" for k, v in meta.items())
    evidence_text = (
        f"=== wine_spike evidence ===\n{meta_block}\n"
        f"=== WINE_SPIKE logcat ===\n" + "\n".join(wine_lines) +
        f"\n--- instrument stdout ---\n{instr_out}"
    )
    write_evidence(args.lane, suffix, evidence_text, passed=overall_ok)

    return 0 if overall_ok else 1


if __name__ == "__main__":
    sys.exit(main())
