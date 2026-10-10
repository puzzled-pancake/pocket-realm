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

    # R2-r5-3: exclusive lock — a concurrent seat refuses loud instead of
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
                 f"-PpocketAbi={abi}", *passthrough, "--console=plain"],
                cwd=ROOT / "android", env=env,
            )
            return run.returncode
        finally:
            # the focused run's own results are disposable: the CANONICAL
            # tree is what every gate reconciles against, so it is restored
            # byte-for-byte
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
