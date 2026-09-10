#!/usr/bin/env python3
"""Windows kill matrix + soak runner (qualification §2.3 and §6).

Three kill scenarios, each ending with `taskkill /F` on the victim JVM
(the whole host process dies mid-flight, exactly like a crash):

  1. early-boot     - kill shortly after the victim JVM first appears
                      (warm cache: the world is usually already starting;
                      the DB check is instant, so this is an honest
                      early-boot kill, not a DB-init one)
  2. mid-world-run  - kill while the world ticks, via the stdin-held
                      launchClient victim, with the game client live
  3. mid-save       - kill in the fraction-of-a-second window after the
                      READY marker (the gate saves immediately)

What the follow-up recovery boot actually proves (the honest contract):
  - SQLite's own WAL recovery replays/resigns the sidecars the killed
    host left behind; the next boot completes a full clean cycle
    (world READY, save rc=0, stop with WAL seal, exit 0) - never a
    silent success over corrupt state;
  - no live WAL sidecar is left behind afterwards.

What it deliberately does NOT cover: the supervisor journal's dirty
recovery. Both victims drive DesktopRuntimeBackend directly and never
write the supervisor journal (that is the packaged app's path), so the
journal recovery contract is exercised by the desktop JVM unit suite
(DurableRuntimeSupervisorTest / OrphanSelfHealPolicyTest /
DesktopSupervisorJournalTest), not here. The runner prints the journal
state for visibility only.

HAZARD: this is a dev-box tool. find_victim_pids matches java.exe
command lines containing the victim main class AND this repo's path,
and the WoW.exe cleanup kills only game processes that appeared during
THIS run - but do not run the real game or an unrelated checkout of
this repo while the matrix is running.

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
# BootWorld boots db->realm->world, saves, stops.
VICTIM_TASK = "bootWorld"
JAVA_MAIN_MARKER = "com.pocketrealm.desktop.BootWorldKt"
HOLDING_TASK = "launchClient"
HOLDING_MAIN_MARKER = "com.pocketrealm.desktop.LaunchClientKt"


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    print("+", " ".join(str(c) for c in cmd), flush=True)
    return subprocess.run([str(c) for c in cmd], **kwargs)


def find_victim_pids(marker: str = JAVA_MAIN_MARKER) -> list[int]:
    """Java processes running a victim main (the JavaExec worker, not the
    daemon). The match is scoped to THIS checkout's path so a second
    clone or an IDE run of the same main class elsewhere is not
    collateral. Empty output (no java.exe at all) yields []."""
    query = subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
         "Select-Object ProcessId,CommandLine | ConvertTo-Json -Compress"],
        capture_output=True, text=True)
    text = (query.stdout or "").strip()
    if not text or text == "null":
        return []
    entries = json.loads(text)
    if not isinstance(entries, list):
        entries = [entries]
    return [
        int(entry["ProcessId"]) for entry in entries
        if entry.get("CommandLine")
        and marker in entry["CommandLine"]
        and str(ROOT) in entry["CommandLine"]
    ]


def wow_pids() -> set[int]:
    """Every running WoW.exe pid (the game the victim spawns)."""
    result = subprocess.run(
        ["tasklist", "/FI", "IMAGENAME eq WoW.exe", "/FO", "CSV", "/NH"],
        capture_output=True, text=True)
    pids: set[int] = set()
    for line in (result.stdout or "").splitlines():
        parts = [p.strip('"') for p in line.split('","')]
        if len(parts) >= 2 and parts[1].isdigit():
            pids.add(int(parts[1]))
    return pids


def taskkill_wow(preexisting: set[int]) -> int:
    """A killed host cannot close its WoW.exe child; kill ONLY the game
    processes that appeared during THIS run, never ones that already
    existed (the user's own session). Returns how many were killed."""
    killed = 0
    for pid in sorted(wow_pids() - preexisting):
        run(["taskkill", "/F", "/PID", pid])
        killed += 1
    return killed


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
    """Printed for visibility only - see the module docstring: these legs
    never write the supervisor journal (the victims drive the backend
    directly)."""
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
    """Sidecars that would be replayed by the next SQLite open - the
    backend's liveness semantics: a name whose file cannot be opened
    (delete-pending) is not a live sidecar."""
    datadir = Path.home() / "AppData/Local/PocketRealm/database/sqlite-datadir"
    if not datadir.is_dir():
        return []
    live: list[str] = []
    for path in sorted(datadir.glob("*-wal")):
        try:
            with open(path, "a+b"):
                pass
            live.append(path.name)
        except OSError:
            continue
    return live


def verify_recovery(scenario: str) -> list[str]:
    """Post-kill assertions; returns failure reasons (empty = pass). The
    contract under test is the module docstring's: SQLite's WAL recovery
    plus one full clean re-cycle, no sidecars left. Any nonzero recovery
    exit FAILS the scenario - only the heal outcome is accepted."""
    reasons: list[str] = []
    print(f"[{scenario}] journal after kill (visibility only, not asserted): "
          f"{journal_state()}")
    print(f"[{scenario}] WAL sidecars after kill: {live_wal_sidecars() or 'none'}")
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


def scenario_early_boot(wow_before: set[int]) -> list[str]:
    print("\n=== scenario: early-boot ===")
    victim = Victim()
    try:
        # Kill as soon as the victim JVM exists, bounded; with a warm
        # gradle cache the boot is fast and the kill usually lands during
        # realm/world start (the DB check itself is an instant file
        # existence probe - this leg is an honest early-boot kill).
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline and not find_victim_pids():
            time.sleep(1.0)
        time.sleep(3)
        killed = victim.kill_jvm()
        if killed == 0:
            return ["no victim JVM found to kill (early-boot leg)"]
        taskkill_wow(wow_before)
        return verify_recovery("early-boot")
    finally:
        victim.close()


def scenario_mid_world_run(wow_before: set[int]) -> list[str]:
    print("\n=== scenario: mid-world-run ===")
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
        taskkill_wow(wow_before)
        return verify_recovery("mid-world-run")
    finally:
        victim.close()


def scenario_mid_save(wow_before: set[int]) -> list[str]:
    print("\n=== scenario: mid-save ===")
    # The save window is short: BootWorld saves immediately after the
    # READY + listener checks, so the kill fires a fraction of a second
    # after the marker (0.1 s poll + sleep). A timing miss that lets the
    # victim finish its clean exit FAILS the leg (no JVM left to kill) -
    # the recovery boot is the real assertion, but a scenario that never
    # killed anything mid-flight proves nothing and must be re-run.
    victim = Victim()
    try:
        ready = victim.await_marker("world READY", timeout_s=1500, poll_s=0.1)
        if not ready:
            return ["victim never reached world READY (save leg)"]
        time.sleep(0.1)
        killed = victim.kill_jvm()
        if killed == 0:
            return ["no victim JVM found to kill (save leg)"]
        taskkill_wow(wow_before)
        return verify_recovery("mid-save")
    finally:
        victim.close()


def soak(minutes: int, wow_before: set[int]) -> int:
    print(f"\n=== soak: {minutes} min single world lifetime ===")
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
        try:
            rc = victim.process.wait(timeout=600)
        except subprocess.TimeoutExpired:
            # The drain outran its bound: kill the worker JVM (the gradle
            # wrapper's terminate does not fan out to the forked java
            # child on Windows) and fail honestly.
            for pid in find_victim_pids(HOLDING_MAIN_MARKER):
                run(["taskkill", "/F", "/PID", pid])
            print("SOAK FAILED: post-Enter drain exceeded 600s")
            return 1
        elapsed = int(time.time() - started)
        wal = live_wal_sidecars()
        print(f"SOAK {'PASSED' if rc == 0 and not wal else 'FAILED'} "
              f"(exit={rc}, {elapsed}s, wal={wal or None})")
        return 0 if rc == 0 and not wal else 1
    finally:
        victim.close()
        for pid in find_victim_pids(HOLDING_MAIN_MARKER):
            run(["taskkill", "/F", "/PID", pid])
        taskkill_wow(wow_before)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=("early-boot", "mid-world-run", "mid-save"))
    parser.add_argument("--soak-min", type=int, metavar="N",
                        help="run the long-session soak leg for N minutes instead")
    args = parser.parse_args()

    # Snapshot the game processes ONCE per run: the WoW cleanup only ever
    # kills pids that appear after this point.
    wow_before = wow_pids()
    if args.soak_min is not None:
        return soak(args.soak_min, wow_before)
    scenarios = {
        "early-boot": scenario_early_boot,
        "mid-world-run": scenario_mid_world_run,
        "mid-save": scenario_mid_save,
    }
    selected = [(args.scenario, scenarios[args.scenario])] if args.scenario else list(scenarios.items())
    failures: list[str] = []
    for name, leg in selected:
        failures += [f"{name}: {reason}" for reason in leg(wow_before)]
    if failures:
        print("\nKILL MATRIX FAILED:")
        for reason in failures:
            print("  -", reason)
        return 1
    print("\nKILL MATRIX PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
