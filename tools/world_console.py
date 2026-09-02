#!/usr/bin/env python3
"""Interactive world console - "a script to play with the server".

Boots (or attaches to) the benchmark emulator, starts the long-lived
WorldConsoleRelay instrumentation, and gives a REPL over the REAL
product Binder surface: boot the stack with bots, raise/lower the bot
population, watch tick telemetry, create GM accounts, save, pause, kill
the world, and bring everything back up.

Commands (see WorldConsoleRelay for the full surface):
  stack-up-bot [profile]   db+realm+world on the bot profile (default
                           mobile-balanced-b100-v1)
  world-bots <n>           set the live bot target
  world-status             tick percentiles, bots online, db probe delay
  world-bot-status | world-save | world-pause <0|1> | world-kill |
  world-account <u> <p> | world-verify <u> <p> | world-gm <u> <lvl> |
  world-account-status <u> | world-persistence <u> <char>
  realm-status | db-status | db-health
  world-stop | realm-stop | db-stop | quit

Usage:
  python3 tools/world_console.py                 # attach or boot+install
  python3 tools/world_console.py --fresh         # boot a fresh emulator
"""

import argparse
import importlib.util
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
_SPEC = importlib.util.spec_from_file_location(
    "run_differential_parity", ROOT / "tools" / "run_differential_parity.py")
LANE = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(LANE)

INSTRUMENT = "com.pocketrealm.test/androidx.test.runner.AndroidJUnitRunner"
RELAY = "com.pocketrealm.database.WorldConsoleRelay"
PKG_FILES = "/data/data/com.pocketrealm/files"
IN_PATH = f"{PKG_FILES}/console-in.json"
OUT_PATH = f"{PKG_FILES}/console-out.json"

SLOW_OPS = {"stack-up-bot", "world-start-bot", "world-start", "world-kill"}


def adb_root() -> None:
    LANE.run([LANE.ADB, "root"], check=False, timeout=60)
    time.sleep(4)
    LANE.adb("wait-for-device", timeout=120)


def device_online() -> bool:
    try:
        output = LANE.adb("devices", timeout=30)
    except Exception:
        return False
    return any("\temulator-" in line and "\toffline" not in line
               for line in output.splitlines())


def ensure_device(fresh: bool) -> None:
    if not fresh and device_online():
        print("attaching to the running emulator")
        return
    print("booting a fresh benchmark emulator (PocketDiff_x86_64)...")
    subprocess.Popen(
        [str(LANE.EMULATOR), "-avd", LANE.AVD, "-no-window", "-no-audio",
         "-no-boot-anim", "-no-snapshot", "-accel", "on", "-memory", "6144"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    LANE.wait_for_device(600)


def start_relay() -> None:
    adb_root()
    for path in (IN_PATH, OUT_PATH):
        LANE.run([LANE.ADB, "shell", "rm", "-f", path], check=False, timeout=30)
    # non-blocking: the instrumentation stays resident as the console's
    # service host (a -w invocation would block and hold the terminal)
    LANE.run([LANE.ADB, "shell", "am", "instrument",
              "-e", "class", RELAY, INSTRUMENT], timeout=120)
    print("relay starting; waiting for first response...")


def uid() -> str:
    return LANE.run([LANE.ADB, "shell", "stat", "-c", "%u:%g",
                     "/data/data/com.pocketrealm"], check=True,
                    timeout=60).strip()


def send(op: str, arg1: str = "", arg2: str = "", timeout_s: int = 120) -> dict:
    payload = json.dumps({"op": op, "arg1": arg1, "arg2": arg2})
    local = ROOT / "build" / "world-console-in.json"
    local.write_text(payload, encoding="utf-8")
    app_uid = uid()
    LANE.run([LANE.ADB, "shell", "rm", "-f", OUT_PATH], check=False, timeout=30)
    LANE.run([LANE.ADB, "push", str(local), IN_PATH], check=True, timeout=60)
    LANE.run([LANE.ADB, "shell", "chown", app_uid, IN_PATH],
             check=True, timeout=30)
    LANE.run([LANE.ADB, "shell", "chmod", "644", IN_PATH],
             check=True, timeout=30)
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        raw = LANE.run([LANE.ADB, "shell", "cat", OUT_PATH],
                       check=False, timeout=30)
        if raw.strip().startswith("{"):
            return json.loads(raw)
        time.sleep(1)
    return {"ok": False, "error": f"relay did not answer '{op}' "
                                   f"within {timeout_s}s"}


def pretty(response: dict) -> str:
    trimmed = {k: v for k, v in response.items() if k != "atMs"}
    return json.dumps(trimmed, indent=2, sort_keys=True)


HELP = """commands:
  stack-up-bot [profile]   boot db+realm+world with bots (default balanced-100)
  world-bots <n> | world-status | world-bot-status | world-save
  world-pause <0|1> | world-kill | world-stop
  world-account <u> <p> | world-verify <u> <p> | world-gm <u> <lvl>
  world-account-status <u> | world-persistence <u> <char>
  world-start [profile] | realm-start | realm-status | realm-stop
  db-status | db-health | db-stop | ping | help | quit"""


def repl() -> None:
    print(HELP)
    while True:
        try:
            line = input("world> ").strip()
        except (EOFError, KeyboardInterrupt):
            line = "quit"
        if not line:
            continue
        parts = line.split()
        op, args = parts[0].lower(), parts[1:]
        if op in ("help", "?"):
            print(HELP)
            continue
        if op == "exit":
            op = "quit"
        profile_default = "mobile-balanced-b100-v1"
        if op == "stack-up-bot" and not args:
            args = [profile_default]
        timeout_s = 900 if op in SLOW_OPS else 120
        response = send(op, args[0] if args else "", args[1] if len(args) > 1 else "",
                        timeout_s)
        print(pretty(response))
        if op == "quit":
            return


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fresh", action="store_true",
                        help="boot a fresh emulator even if one is online")
    parser.add_argument("--install", metavar="RUN_DIR",
                        help="install APKs from a benchmark run directory "
                             "(build/bot-pressure/<id>) before starting")
    args = parser.parse_args()
    ensure_device(args.fresh)
    if args.install:
        run_dir = Path(args.install)
        LANE.adb("uninstall", "com.pocketrealm", check=False)
        LANE.adb("install", "-r", str(run_dir / "server-b.apk"), timeout=600)
        LANE.adb("install", "-r", str(run_dir / "tests-b.apk"), timeout=300)
        # re-stage the o11 generation the benchmark used
        store = ROOT / "build" / "o11-bot-data" / "o11-server"
        base = "/data/data/com.pocketrealm/files/content/o11-server"
        LANE.run([LANE.ADB, "shell", "rm", "-rf", base], check=False, timeout=60)
        LANE.run([LANE.ADB, "shell", "mkdir", "-p", base], check=True, timeout=60)
        LANE.adb("push", f"{store}{Path('.')}/.", base, timeout=1800)
        app_uid = uid()
        LANE.run([LANE.ADB, "shell", "chown", "-R", app_uid, base],
                 check=True, timeout=600)
        LANE.run([LANE.ADB, "shell", "restorecon", "-R", base],
                 check=False, timeout=600)
    start_relay()
    print(pretty(send("ping", timeout_s=180)))
    repl()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
