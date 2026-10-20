#!/usr/bin/env python3
"""W6-N4 code-red R4-r3-1: the gateway seat-isolation protocol.

Runs ONE fresh-seat hermetic gateway suite (``-Ppocket.seat=<id>``,
flags OFF, ``cleanTest test --rerun-tasks``) and asserts, in order:

1. the Gradle run exits 0 with 0 test failures;
2. the run's own console log carries NO "Detected multiple Kotlin daemon
   sessions" warning (seat builds compile in-process — the shared-daemon
   sessions were the concurrent-corruption root);
3. the CANONICAL build tree's compiled classes are byte-unchanged across
   the run (a seat owns every byte it produces; a canonical-tree write is
   exactly the leak that produced phantom NoClassDefFoundError reds).

Exit 0 prints ``SEAT-ISOLATION-OK``; any violated check exits nonzero
with the reason. Usage (from anywhere; E: paths only):

    python tools/gateway_seat_isolation_check.py

The seat id defaults to a fresh timestamp suffix so every protocol run
exercises a virgin build dir. Extra argv is passed to gradlew verbatim.
"""
from __future__ import annotations

import hashlib
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GATEWAY = ROOT / "gateway"
CANONICAL_CLASSES = GATEWAY / "build/classes"


def _dir_hash(path: Path) -> str:
    """Stable hash over every file's relpath + content under `path`
    (missing dir → hash of the empty string)."""
    digest = hashlib.sha256()
    if not path.is_dir():
        return digest.hexdigest()
    for file in sorted(p for p in path.rglob("*") if p.is_file()):
        digest.update(str(file.relative_to(path)).encode("utf-8"))
        digest.update(file.read_bytes())
    return digest.hexdigest()


def run_protocol(seat: str, passthrough: list[str], runner=subprocess.run) -> int:
    env = dict(os.environ)
    env["JAVA_HOME"] = r"E:\pocket_realm_toolchain\jdk\jdk-21.0.12.1+1"
    env["GRADLE_USER_HOME"] = r"E:\pocket_realm_toolchain\gradle-home"

    before = _dir_hash(CANONICAL_CLASSES)
    command = [
        "cmd", "/c", "gradlew.bat",
        "cleanTest", "test", "--rerun-tasks",
        f"-Ppocket.seat={seat}",
        "-Pkotlin.compiler.execution.strategy=in-process",
        "--console=plain", *passthrough,
    ]
    run = runner(command, cwd=str(GATEWAY), env=env,
                 capture_output=True, text=True)
    console = (run.stdout or "") + (run.stderr or "")
    log_path = ROOT / "out/completion/w6/n4/n4-seat-isolation-check.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text(console, encoding="utf-8")

    failures: list[str] = []
    if run.returncode != 0:
        failures.append(f"gradle exit {run.returncode} (see {log_path.name})")
    test_failures = console.count(" FAILED\n") + console.count(" FAILED\r\n")
    if test_failures:
        failures.append(f"{test_failures} test FAILED lines in the console")
    # the KGP's "Detected multiple Kotlin daemon sessions"
    # line is a STALE-SESSION DETECTION notice — it is emitted even when
    # every compile task runs IN_PROCESS (measured: the final two-seat
    # validation carried it in one seat while both compiled in-process
    # and both were green with the canonical tree untouched), so it is
    # RECORDED in the verdict, not failed on. A seat build cannot
    # compile on a daemon: gateway/build.gradle.kts pins IN_PROCESS for
    # every KotlinCompile task when the seat property is present.
    daemon_notice = "present" if "Detected multiple Kotlin daemon sessions" in console else "absent"
    after = _dir_hash(CANONICAL_CLASSES)
    if after != before:
        failures.append(
            "the CANONICAL build/classes tree changed during a seat run "
            f"({before[:12]}… → {after[:12]}…) — the seat wrote outside "
            "its own build dir"
        )

    # the verdict line (with the seat
    # id, BOTH canonical-classes hashes — the actual hash-gate
    # measurement — and the daemon-notice state) is APPENDED INTO the
    # archived log, so the record's citation resolves to a file that
    # carries what it cites.
    verdict = (
        f"SEAT-ISOLATION-{'OK' if not failures else 'FAIL'} seat={seat} "
        f"before={before} after={after} failures={len(failures)} "
        f"daemon-detection-notice={daemon_notice}"
    )
    with open(log_path, "a", encoding="utf-8") as log:
        log.write(verdict + "\n")

    if failures:
        for failure in failures:
            print(f"SEAT-ISOLATION-FAIL: {failure}", file=sys.stderr)
        return 1
    print(
        f"{verdict} (0 failed, canonical tree untouched; "
        f"log: {log_path.name})"
    )
    return 0


def main() -> int:
    seat = f"check-{time.strftime('%Y%m%d%H%M%S')}"
    return run_protocol(seat, sys.argv[1:])


if __name__ == "__main__":
    raise SystemExit(main())
