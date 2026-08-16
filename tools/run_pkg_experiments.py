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

try:
    from tools import common
except ImportError:
    import common

ADB = str(common.resolve_android_tool("adb"))

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
                         "-PpocketAbi=x86_64",
                         "-Pandroid.suppressUnsupportedCompileSdk=35"], env=env).returncode
    if rc != 0:
        return rc
    # The androidTest APK is variant-agnostic; build it once (debug component).
    return subprocess.run([gw, ":app:assembleDebugAndroidTest", "-p", str(ANDROID),
                           "-PpocketAbi=x86_64",
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
                   only: str | None = None) -> "tuple[int, str, InstrumentOutcome]":
    """Run the experiment(s) via am instrument on this one serial.

    Returns (overall_rc, evidence_text, outcome). overall_rc is the process exit
    to use: 0 ONLY when at least one test ran AND every test passed; non-zero
    otherwise (including 0-tests-run, which `am instrument` happily returns 0 for).
    evidence_text is the logcat PKG lines + the raw instrument stdout. outcome
    carries the parsed counts so the caller can decide whether to check in
    evidence as a PASS artifact or quarantine it as a failure log.
    """
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
    # Drain the PKG evidence lines from logcat (full per-tick history). The
    # logcat buffer after a 30-min run can be large, so allow a generous timeout
    # and never let a logcat-drain failure mask the real test outcome: if the
    # drain fails, fall back to the instrument stdout (which carries the
    # INSTRUMENTATION_CODE summary the parser needs).
    try:
        log = adb(serial, "logcat", "-d", timeout=600)
    except Exception as e:
        print(f"  adb logcat drain failed ({e}); falling back to instrument stdout", file=sys.stderr)
        log = ""
    if log is None:
        log = ""
    pkg_lines = [ln for ln in log.splitlines()
                 if "PKG_EXPERIMENT" in ln or "PKG-06 TICK" in ln or "PKG_CAPABILITY" in ln]
    evidence = "\n".join(pkg_lines) + "\n--- instrument stdout ---\n" + instr_out

    outcome = parse_instrumentation(instr_out)
    # rc from adb is unreliable for pass/fail: `am instrument` returns 0 even when
    # zero tests matched the class filter, and prints INSTRUMENTATION_CODE: -1 on
    # a FAILED assertion (but 0 on a no-op run). Gate solely on parsed outcome.
    overall_rc = 0 if (outcome.ran > 0 and outcome.failed == 0) else 1
    return overall_rc, evidence, outcome


class InstrumentOutcome:
    """Parsed am instrument result: how many tests ran/passed/failed, plus the
    trailing INSTRUMENTATION_CODE (0/1 = OK-ish, -1 = failure)."""

    def __init__(self, ran: int, passed: int, failed: int, code: int | None, note: str):
        self.ran = ran
        self.passed = passed
        self.failed = failed
        self.code = code
        self.note = note

    @property
    def ok(self) -> bool:
        return self.ran > 0 and self.failed == 0

    def __repr__(self) -> str:
        return (f"InstrumentOutcome(ran={self.ran}, passed={self.passed}, "
                f"failed={self.failed}, code={self.code}, note={self.note!r})")


def parse_instrumentation(instr_out: str) -> InstrumentOutcome:
    """Parse `am instrument -r` output into an InstrumentOutcome.

    Per-test status codes appear as INSTRUMENTATION_STATUS_CODE: <n> where
    1 = STARTED and 0 = OK (pass). ran = tests that reached STARTED; passed =
    those that subsequently reached code 0. failed = ran - passed.

    Failure signals (any one):
      - a test STARTED but never reached code 0 (failed = ran - passed > 0);
      - a `FAILURES!!!` / `There were N failures:` summary line;
      - the `OK (0 tests)` summary when the caller asked for a real test.

    IMPORTANT: the terminal INSTRUMENTATION_CODE is -1 on BOTH a clean finish
    and a failure (verified against captured passing runs). It is NOT a failure
    indicator; do not treat it as one. The trailing summary line (`OK (N tests)`
    vs `Tests run: N, Failures: M`) is the reliable text signal.
    """
    ran = 0
    passed = 0
    code: int | None = None
    current_test: str | None = None
    summary_failures = 0          # parsed from `There were N failures:` / FAILURES
    zero_test_summary = False
    for line in instr_out.splitlines():
        s = line.strip()
        if s.startswith("INSTRUMENTATION_STATUS: test="):
            current_test = s.split("=", 1)[1].strip()
        elif s.startswith("INSTRUMENTATION_STATUS_CODE:"):
            try:
                v = int(s.split(":", 1)[1].strip())
            except ValueError:
                continue
            if v == 1 and current_test:  # STARTED
                ran += 1
            elif v == 0 and current_test:  # OK / passed
                passed += 1
                current_test = None
        elif s.startswith("INSTRUMENTATION_CODE:"):
            try:
                code = int(s.split(":", 1)[1].strip())
            except ValueError:
                pass
        elif s.startswith("There were ") and "failure" in s:
            try:
                summary_failures = max(summary_failures, int(s.split()[2]))
            except (ValueError, IndexError):
                summary_failures = max(summary_failures, 1)
        elif "FAILURES!!!" in s:
            summary_failures = max(summary_failures, 1)
        elif s.startswith("OK (0 tests)"):
            zero_test_summary = True
    failed = max(ran - passed, summary_failures)
    if zero_test_summary and ran == 0:
        # Explicit "no tests ran" — not ok regardless of terminal code.
        failed = max(failed, 0)  # ran==0 already makes ok() False; keep note honest
    note = (f"parsed {ran} started, {passed} passed, {failed} failed "
            f"(summary_failures={summary_failures}, zero_test_summary="
            f"{zero_test_summary}, terminal INSTRUMENTATION_CODE={code})")
    return InstrumentOutcome(ran=ran, passed=passed, failed=failed, code=code, note=note)


def write_evidence(lane: str, name: str, content: str) -> Path:
    d = ROOT / "tests" / "avd" / f"{LANES[lane]['avd']}-v1" / "evidence"
    d.mkdir(parents=True, exist_ok=True)
    p = d / name
    p.write_text(content, encoding="utf-8")
    return p


def _selftest() -> int:
    """Offline checks of parse_instrumentation() against captured exemplars,
    including the regression that motivated this fix: `am instrument` returns
    rc 0 and INSTRUMENTATION_CODE 0 even when zero tests matched the filter."""
    failures = []

    def check(name, instr_out, expect_ok, expect_ran=None, expect_failed=None):
        o = parse_instrumentation(instr_out)
        ok = (o.ok == expect_ok
              and (expect_ran is None or o.ran == expect_ran)
              and (expect_failed is None or o.failed == expect_failed))
        if not ok:
            failures.append(f"{name}: expected ok={expect_ok} ran={expect_ran} "
                            f"failed={expect_failed}, got {o!r}")

    # Real passing run (captured from a genuine t4): 1 test, STARTED then OK,
    # the `OK (1 test)` summary, and terminal code -1 (which is the runner's
    # normal "done" signal, NOT a failure). Must classify as a clean PASS.
    check("single_pass",
          "INSTRUMENTATION_STATUS: test=t4_pkg06_smoke_loads_all_libs\n"
          "INSTRUMENTATION_STATUS_CODE: 1\n"
          "INSTRUMENTATION_STATUS_CODE: 0\n"
          "Time: 1800.962\nOK (1 test)\n"
          "INSTRUMENTATION_CODE: -1\n",
          expect_ok=True, expect_ran=1, expect_failed=0)

    # Zero tests matched the class filter: rc 0, the `OK (0 tests)` summary, no
    # per-test status. This is the regression — must be NOT ok.
    check("zero_tests",
          "INSTRUMENTATION_RESULT: stream=\n\nTime: 0.001\nOK (0 tests)\n\n"
          "INSTRUMENTATION_CODE: 0\n",
          expect_ok=False, expect_ran=0, expect_failed=0)

    # A failing assertion: test STARTED but never reached code 0, plus the
    # `There was 1 failure:` / FAILURES summary. Must be NOT ok with failed=1.
    check("assertion_fail",
          "INSTRUMENTATION_STATUS: test=t2_pkg01_launcher_executes_or_documents_no_path\n"
          "INSTRUMENTATION_STATUS_CODE: 1\n"
          "There was 1 failure:\n"
          "1) t2_pkg01_launcher_executes_or_documents_no_path(...)\n"
          "java.lang.AssertionError: ...\n"
          "FAILURES!!!\n"
          "Tests run: 1,  Failures: 1\n"
          "INSTRUMENTATION_CODE: -1\n",
          expect_ok=False, expect_ran=1, expect_failed=1)

    if failures:
        for f in failures:
            print("FAIL:", f)
        return 1
    print("parse_instrumentation self-test: 3/3 passed")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    # --self-test needs no device; relax the required device args when it's set.
    self_test = "--self-test" in sys.argv
    ap.add_argument("--lane", required=not self_test, choices=list(LANES))
    ap.add_argument("--serial", required=not self_test, help="adb serial of the booted AVD")
    ap.add_argument("--variant", required=not self_test, choices=["debug", "pkgExperiment"],
                    help="debug = production (useLegacyPackaging=false); "
                         "pkgExperiment = launcher extraction (useLegacyPackaging=true)")
    ap.add_argument("--smoke-seconds", type=int, default=10,
                    help="PKG-06 duration; 1800 = genuine 30-min acceptance run")
    ap.add_argument("--only", default=None,
                    help="single test method suffix, e.g. t2_pkg01_launcher_executes_or_documents_no_path")
    ap.add_argument("--no-build", action="store_true", help="skip gradle assemble")
    ap.add_argument("--self-test", action="store_true",
                    help="run offline parse_instrumentation checks and exit (no device needed)")
    args = ap.parse_args()

    if args.self_test:
        return _selftest()

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
    rc, pkg_log, outcome = run_instrument(args.serial, args.variant, args.lane,
                                          args.smoke_seconds, args.only)
    # Gate evidence naming on the validated outcome. A green run writes the
    # canonical name; a failure (or zero-tests-run) is quarantined under .FAIL
    # so a subsequent re-run can't be confused into trusting a stale green file,
    # and the driver returns non-zero so CI/sequencing stops here.
    suffix = args.only or "all"
    if outcome.ok:
        name = f"{args.variant}-{suffix}-{ts}.log"
    else:
        name = f"{args.variant}-{suffix}-{ts}.FAIL.log"
    ev = write_evidence(args.lane, name, pkg_log)
    tag = "PASS" if outcome.ok else "FAIL"
    print(f"  [{tag}] {outcome.note}")
    print(f"  wrote evidence: {ev.relative_to(ROOT)}")
    if not outcome.ok:
        # Do not leave a stale capability report implying a green capability run.
        return rc
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
