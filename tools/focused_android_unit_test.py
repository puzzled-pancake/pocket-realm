#!/usr/bin/env python3
"""Run FOCUSED android unit tests without touching the canonical JUnit tree.

R6-1/R2-r4-1 (W1 code-red round 4): on the android lane the unit-test task is
AGP 9's AndroidUnitTest — not an org.gradle.api.tasks.testing.Test subtype —
and its reports.junitXml.outputLocation is FINALIZED at task creation, so the
desktop lane's -Ppocket.focus redirect is impossible there. A plain
`--tests`-filtered run would hollow the canonical
android/app/build/test-results/testDebugUnitTest tree that the counts
artifacts and test_canonical_junit_trees_match_the_counts_artifacts reconcile
against.

This wrapper gives §2.4-sanctioned focused runs the desktop redirect's
guarantee by snapshot/restore: the canonical tree is moved aside for the
duration of the run and restored byte-for-byte afterwards — the focused
results (disposable) are all that a failed/hollowed run can touch.

R2-r5-3 (W1 code-red round 5): the snapshot/restore is guarded by an EXCLUSIVE
lock file (O_CREAT|O_EXCL) — a second concurrent invocation refuses loud
("another focused android run is active") instead of proceeding snapshot-less
and destroying the first seat's canonical snapshot or leaking focused results
into the restored tree.

Usage (from anywhere; E: paths only):

    python tools/focused_android_unit_test.py \
        --tests com.pocketrealm.database.MariaDbBootstrapFlavorRoutingTest

Extra args after the known flags are passed to gradlew verbatim
(e.g. --tests may be repeated). The canonical tree is restored even when the
Gradle run fails.
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "android/app/build/test-results/testDebugUnitTest"
SNAPSHOT = ROOT / "android/app/build/test-results/testDebugUnitTest.canonical-snapshot"
LOCK = ROOT / "android/app/build/test-results/.focused-android.lock"

ANOTHER_RUN_ACTIVE = "another focused android run is active"


def aggregate_counts(results: Path) -> str | None:
    """W6-N4 code-red R3-1/R6-N4-r1-3: aggregate the FOCUSED run's JUnit
    XML into one N4-FOCUSED-COUNTS marker line BEFORE the canonical
    snapshot is restored (the focused tree is disposable by design, so
    the counts must be printed into the archived log while they exist).
    Per-class split included so the exit record's per-class numbers are
    citable to this line. Returns None when no XML was produced (the run
    never executed)."""
    import xml.etree.ElementTree as ET

    xmls = sorted(results.glob("TEST-*.xml"))
    if not xmls:
        return None
    total = failures = classes = 0
    per_class: list[str] = []
    for xml_path in xmls:
        try:
            root = ET.parse(xml_path).getroot()
        except ET.ParseError:
            continue
        tests = int(root.get("tests", "0"))
        failed = int(root.get("failures", "0")) + int(root.get("errors", "0"))
        total += tests
        failures += failed
        classes += 1
        per_class.append(f"{root.get('name', '?').rsplit('.', 1)[-1]} {tests}/{failed}")
    marker = f"# N4-FOCUSED-COUNTS tests={total} classes={classes} failures={failures}"
    if per_class:
        marker += " (" + ", ".join(per_class) + ")"
    return marker


def run_focused(
    tests: list[str],
    abi: str,
    passthrough: list[str],
    runner=subprocess.run,
) -> int:
    """Snapshot, run, restore. `runner` is injectable so the concurrency
    contract is testable without a Gradle invocation (R2-r5-3)."""
    env = dict(os.environ)
    env["JAVA_HOME"] = r"E:\pocket_realm_toolchain\jdk\jdk-17.0.20.8-hotspot"
    env["GRADLE_USER_HOME"] = r"E:\pocket_realm_toolchain\gradle-home"

    # exclusive lock — a concurrent seat refuses loud instead of
    # proceeding snapshot-less (its cleanup would destroy this seat's
    # canonical snapshot or leak focused results into the restored tree).
    RESULTS.parent.mkdir(parents=True, exist_ok=True)
    try:
        lock_fd = os.open(str(LOCK), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        print(f"REFUSED: {ANOTHER_RUN_ACTIVE} (lock {LOCK} exists)", file=sys.stderr)
        return 3
    try:
        snapshot_taken = False
        if RESULTS.exists():
            shutil.move(str(RESULTS), str(SNAPSHOT))
            snapshot_taken = True
        try:
            run = runner(
                ["cmd", "/c", "gradlew.bat", ":app:testDebugUnitTest",
                 *[f"--tests={t}" for t in tests],
                 f"-PpocketAbi={abi}",
                 # the android lane now
                 # fences unfenced --tests runs, so the wrapper must carry the
                 # focus label itself (the snapshot/restore below still guards
                 # the canonical tree — the android redirect is impossible).
                 "-Ppocket.focus=focused",
                 *passthrough, "--console=plain"],
                cwd=ROOT / "android", env=env,
            )
            return run.returncode
        finally:
            # aggregate + PRINT the focused counts while the focused tree
            # still exists (the restore below deliberately destroys it):
            # the CANONICAL tree is what every gate reconciles against, so
            # it is restored byte-for-byte
            marker = aggregate_counts(RESULTS)
            if marker:
                print(marker, flush=True)
            if snapshot_taken:
                if RESULTS.exists():
                    shutil.rmtree(RESULTS, ignore_errors=True)
                shutil.move(str(SNAPSHOT), str(RESULTS))
            elif SNAPSHOT.exists():
                # no canonical tree existed before the run (fresh clone): the
                # focused run must not fabricate one either
                shutil.rmtree(RESULTS, ignore_errors=True)
                shutil.rmtree(SNAPSHOT, ignore_errors=True)
    finally:
        os.close(lock_fd)
        LOCK.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tests", action="append", required=True,
                        help="fully-qualified test class to run (repeatable)")
    parser.add_argument("--abi", default="x86_64",
                        help="pocketAbi value (default x86_64)")
    args, passthrough = parser.parse_known_args()
    return run_focused(args.tests, args.abi, passthrough)


if __name__ == "__main__":
    raise SystemExit(main())
