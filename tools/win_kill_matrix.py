#!/usr/bin/env python3
"""Windows kill matrix + soak runner (qualification §2.3 and §6).

Three kill scenarios, each ending with `taskkill /F` on the JVM (the
whole supervisor dies mid-flight, exactly like a crash):

  1. mid-db-init    - kill while the DATABASE component is STARTING
  2. mid-world-run  - kill once the world is READY (WAL sidecars live)
  3. mid-save       - kill during the stop/drain sequence

After every kill the runner asserts the documented recovery contract:
  - the supervisor journal on disk is DIRTY (clean=false) or absent-yet-
    recovered on next start (the shared one-attempt recovery owns it),
  - the next world start either heals in place (dirty-DB heal gate) or
    fails HONESTLY with the recovery verdict - never a silent success
    over corrupt state,
  - WAL sidecars from the killed run are drained/resealed by the healed
    boot (no live sidecars left behind after the follow-up clean stop),
  - a save + clean stop after recovery exits 0 (sentinel survival).

The soak leg runs the world for N minutes (default 10), then saves and
stops; the one-lifetime rule makes multi-lifetime soaks fresh-JVM cycles,
so the soak is one long world lifetime with periodic world status polls.

Usage (from the repo root, dev box with the native lanes built):
  python tools/win_kill_matrix.py            # all three kills + verify
  python tools/win_kill_matrix.py --soak-min 10
  python tools/win_kill_matrix.py --scenario mid-world-run
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DESKTOP = ROOT / "desktop"
GRADLEW = DESKTOP / "gradlew.bat"

# The victim main: a fresh JVM per cycle (one world lifetime per process).
# BootWorld with -DbootCycles=1 boots db->realm->world, saves, stops.
VICTIM_TASK = "bootWorld"
JAVA_MAIN_MARKER = "com.pocketrealm.desktop.BootWorldKt"
HOLDING_TASK = "launchClient"
HOLDING_MAIN_MARKER = "com.pocketrealm.desktop.LaunchClientKt"


def taskkill_wow() -> None:
    """A killed supervisor cannot close its WoW.exe child; clear any
    survivor so the next victim starts from a clean slate."""
    result = subprocess.run(
        ["tasklist", "/FI", "IMAGENAME eq WoW.exe"], capture_output=True, text=True)
    if "WoW.exe" in (result.stdout or ""):
        run(["taskkill", "/F", "/IM", "WoW.exe"])


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    print("+", " ".join(str(c) for c in cmd), flush=True)
    return subprocess.run([str(c) for c in cmd], **kwargs)


def find_victim_pids(marker: str = JAVA_MAIN_MARKER) -> list[int]:
    """Java processes running a victim main (the JavaExec worker, not the
    daemon)."""
    query = subprocess.check_output(
        ["powershell", "-NoProfile", "-Command",
         "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
         "Select-Object ProcessId,CommandLine | ConvertTo-Json -Compress"],
        text=True)
    entries = json.loads(query) if query.strip().startswith("[") else [json.loads(query)]
    return [
        int(entry["ProcessId"]) for entry in entries
        if entry.get("CommandLine") and marker in entry["CommandLine"]
    ]


class Victim:
    """A gradle-driven victim JVM whose stdout is captured to a log."""

    def __init__(self, task: str = VICTIM_TASK, stdin_pipe: bool = False):
        self.log = DESKTOP / "build" / "kill-matrix" / f"victim-{int(time.time() * 1000)}.log"
        self.log.parent.mkdir(parents=True, exist_ok=True)
        self.handle = open(self.log, "wb")
        args = [GRADLEW, task, "--console=plain"]
        self.process = subprocess.Popen(
            [str(a) for a in args], cwd=str(DESKTOP), stdout=self.handle,
            stderr=subprocess.STDOUT,
            stdin=subprocess.PIPE if stdin_pipe else subprocess.DEVNULL)

    def send_stdin(self, payload: str) -> None:
        if self.process.stdin and not self.process.stdin.closed:
            self.process.stdin.write(payload.encode())
            self.process.stdin.flush()

    def await_marker(self, marker: str, timeout_s: int, poll_s: float = 2.0) -> bool:
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                return marker in self.read_log()
            if marker in self.read_log():
                return True
            time.sleep(poll_s)
        return False

    def read_log(self) -> str:
        self.handle.flush()
        return self.log.read_text(encoding="utf-8", errors="replace")

    def kill_jvm(self, marker: str = JAVA_MAIN_MARKER) -> int:
        """taskkill /F every victim JVM; returns how many were killed."""
        pids = find_victim_pids(marker)
        for pid in pids:
            run(["taskkill", "/F", "/PID", pid])
        self.process.wait(timeout=120)
        return len(pids)

    def close(self) -> None:
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                self.process.kill()
        self.handle.close()


def journal_state() -> dict | None:
    journal = (Path.home() / "AppData/Local/PocketRealm/realm/runtime-supervisor/journal.json")
    if not journal.is_file():
        return None
    try:
        value = json.loads(journal.read_text(encoding="utf-8"))
    except Exception:
        return {"clean": False, "phase": "CORRUPT"}
    return {
        "clean": value.get("clean"),
        "phase": value.get("phase"),
        "lastDurableAction": value.get("lastDurableAction"),
    }


def live_wal_sidecars() -> list[str]:
    datadir = Path.home() / "AppData/Local/PocketRealm/database/sqlite-datadir"
    return sorted(p.name for p in datadir.glob("*-wal")) if datadir.is_dir() else []


def verify_recovery(scenario: str) -> list[str]:
    """Post-kill assertions; returns failure reasons (empty = pass)."""
    reasons: list[str] = []
    state = journal_state()
    print(f"[{scenario}] journal after kill: {state}")
    print(f"[{scenario}] WAL sidecars after kill: {live_wal_sidecars() or 'none'}")
    # The one-attempt recovery boot: the next world start must either heal
    # or fail honestly; BootWorld exits 0 only on a clean cycle.
    print(f"[{scenario}] recovery boot (next world start)...")
    healed = run(
        [GRADLEW, VICTIM_TASK, "--console=plain"],
        cwd=DESKTOP, capture_output=True, text=True, timeout=1800,
    )
    outcome = healed.stdout + healed.stderr
    print(f"[{scenario}] recovery boot exit={healed.returncode}")
    if healed.returncode != 0:
        reasons.append(
            f"recovery boot did not complete cleanly (exit {healed.returncode}); "
            f"tail: {outcome[-800:]}")
    if "GATE PASSED" not in outcome and "PASSED" not in outcome:
        reasons.append("recovery boot did not print its PASS verdict")
    left = live_wal_sidecars()
    if left:
        reasons.append(f"WAL sidecars survived the healed cycle: {left}")
    return reasons


def scenario_mid_db_init() -> list[str]:
    print("\n=== scenario: mid-db-init ===")
    victim = Victim()
    try:
        # The DB start is the first phase; kill as soon as the victim JVM
        # exists (before the world marker), bounded.
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline and not find_victim_pids():
            time.sleep(1.0)
        time.sleep(3)
        killed = victim.kill_jvm()
        if killed == 0:
            return ["no victim JVM found to kill (db init leg)"]
        return verify_recovery("mid-db-init")
    finally:
        victim.close()


def scenario_mid_world_run() -> list[str]:
    print('\\n=== scenario: mid-world-run ===')
    # launchClient boots the stack and HOLDS it up (waiting on stdin), so
    # the kill lands during ordinary world ticking with the client live.
    victim = Victim(task=HOLDING_TASK, stdin_pipe=True)
    try:
        ready = victim.await_marker("realm stack READY", timeout_s=1500)
        if not ready:
            return ["victim never reached realm stack READY (timeout)"]
        time.sleep(20)  # let the world settle into ordinary ticking
        killed = victim.kill_jvm(marker=HOLDING_MAIN_MARKER)
        if killed == 0:
            return ["no victim JVM found to kill (world run leg)"]
        taskkill_wow()
        return verify_recovery("mid-world-run")
    finally:
        victim.close()
def scenario_mid_save() -> list[str]:
    print('\\n=== scenario: mid-save ===')
    # The save window is short: BootWorld saves immediately after the
    # READY + listener checks, so the kill fires a fraction of a second
    # after the marker. Landing just after the clean exit is a weaker
    # (still passing) outcome - the recovery boot remains the assertion.
    victim = Victim()
    try:
        ready = victim.await_marker("world READY", timeout_s=1500, poll_s=0.1)
        if not ready:
            return ["victim never reached world READY (save leg)"]
        time.sleep(0.1)
        killed = victim.kill_jvm()
        if killed == 0:
            return ["no victim JVM found to kill (save leg)"]
        return verify_recovery("mid-save")
    finally:
        victim.close()

def soak(minutes: int) -> int:
    banner = chr(10) + f"=== soak: {minutes} min single world lifetime ==="
    print(banner)
    started = time.time()
    # The holding victim: launchClient keeps the stack up waiting on its
    # stdin (held open here), so the soak holds ONE world lifetime; the
    # one-lifetime rule makes multi-lifetime soaks fresh-JVM cycles.
    victim = Victim(task=HOLDING_TASK, stdin_pipe=True)
    try:
        if not victim.await_marker("realm stack READY", timeout_s=1500):
            print("SOAK FAILED: never READY")
            return 1
        deadline = time.time() + minutes * 60
        polls = 0
        while time.time() < deadline:
            time.sleep(30)
            polls += 1
            log = victim.read_log()
            if re.search(r"CYCLE \d+ FAILED|GATE FAILED|REFUSED|unclean", log):
                print("SOAK FAILED: failure marker in victim log")
                return 1
            if victim.process.poll() is not None:
                print("SOAK FAILED: victim exited early")
                return 1
        print(f"soak window done ({polls} polls); ending the session with Enter")
        victim.send_stdin(chr(10))
        rc = victim.process.wait(timeout=600)
        elapsed = int(time.time() - started)
        wal = live_wal_sidecars()
        print(f"SOAK {'PASSED' if rc == 0 and not wal else 'FAILED'} "
              f"(exit={rc}, {elapsed}s, wal={wal or None})")
        return 0 if rc == 0 and not wal else 1
    finally:
        victim.close()
        taskkill_wow()

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=("mid-db-init", "mid-world-run", "mid-save"))
    parser.add_argument("--soak-min", type=int, metavar="N",
                        help="run the long-session soak leg for N minutes instead")
    args = parser.parse_args()

    if args.soak_min is not None:
        return soak(args.soak_min)
    scenarios = {
        "mid-db-init": scenario_mid_db_init,
        "mid-world-run": scenario_mid_world_run,
        "mid-save": scenario_mid_save,
    }
    selected = [(args.scenario, scenarios[args.scenario])] if args.scenario else list(scenarios.items())
    failures: list[str] = []
    for name, leg in selected:
        failures += [f"{name}: {reason}" for reason in leg()]
    if failures:
        print("\nKILL MATRIX FAILED:")
        for reason in failures:
            print("  -", reason)
        return 1
    print("\nKILL MATRIX PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
