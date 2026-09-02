#!/usr/bin/env python3
"""Baseline-world sanity driver (partial DEC-10 lift).

Stages the in-tree baseline server data pack
(native/.build-o09-server-data: dbc + maps + BUILD_PROVENANCE.json,
extracted from the user's own 1.12.1.5875 client) into the app's
content/o09-server/active, then drives BaselineWorldSanityRunner on BOTH
servers (B = SQLite first — the ask — then A = MariaDB for the same-leg
cross-check): real database + realmd + world boot, real console account
writes, saveall, on-disk proof, dirty-kill recovery with sentinel
survival.

The o11 bot-profile path (vmaps/mmaps from a client) stays gated — this
is the BASELINE world (vmaps/mmaps disabled by its config; playerbots
compiled-in but disabled, exactly the historical o09 nobots lane).

Staging path per server (uninstall wipes app data, so per-install):
  adb push pack -> /sdcard/Android/data/com.pocketrealm/files/o09stage
  run-as com.pocketrealm sh -c 'mkdir -p files/content/o09-server &&
    cp -r .../o09stage files/content/o09-server/active'
"""

import argparse
import datetime as _dt
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
_SPEC = importlib.util.spec_from_file_location(
    "run_differential_parity", ROOT / "tools" / "run_differential_parity.py")
LANE = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(LANE)

INSTRUMENT = "com.pocketrealm.test/androidx.test.runner.AndroidJUnitRunner"
RUNNER = "com.pocketrealm.database.BaselineWorldSanityRunner"
PACK = ROOT / "native" / ".build-o09-server-data"


def stage_baseline_data() -> None:
    """Stage the pack into filesDir via `adb root` (the emulator's adbd is
    rootable): push directly into /data/data/<pkg>/files/content/o09-server/
    active, chown to the app uid, restorecon. The API-35 alternatives all
    fail: `run-as` cannot cross the FUSE boundary from /sdcard, exec-in
    stdin doesn't reach run-as, and the app declares no storage perms."""
    if not (PACK / "BUILD_PROVENANCE.json").is_file():
        raise RuntimeError(f"baseline data pack missing provenance: {PACK}")
    import time
    LANE.run([LANE.ADB, "root"], check=False, timeout=60)
    time.sleep(4)
    LANE.adb("wait-for-device", timeout=120)
    base = "/data/data/com.pocketrealm/files/content/o09-server/active"
    LANE.run([LANE.ADB, "shell", "mkdir", "-p", base], check=True, timeout=60)
    LANE.run([LANE.ADB, "shell", "rm", "-rf", base], check=False, timeout=60)
    LANE.run([LANE.ADB, "shell", "mkdir", "-p", base], check=True, timeout=60)
    LANE.adb("push", f"{PACK}{Path('.')}/.", base, timeout=900)
    uid = LANE.run([LANE.ADB, "shell", "stat", "-c", "%u:%g",
                    "/data/data/com.pocketrealm"], check=True, timeout=60).strip()
    LANE.run([LANE.ADB, "shell", "chown", "-R", uid,
              "/data/data/com.pocketrealm/files/content"], check=True, timeout=600)
    LANE.run([LANE.ADB, "shell", "restorecon", "-R",
              "/data/data/com.pocketrealm/files/content"], check=False, timeout=600)
    LANE.run([LANE.ADB, "unroot"], check=False, timeout=60)
    time.sleep(4)
    LANE.adb("wait-for-device", timeout=120)
    probe = LANE.adb("shell", "run-as", "com.pocketrealm", "ls",
                     "files/content/o09-server/active", timeout=60)
    for needed in ("BUILD_PROVENANCE.json", "dbc", "maps"):
        if needed not in probe:
            raise RuntimeError(f"staging verification missed {needed}: {probe}")


def drive(apk: bytes, tests: bytes, tag: str, out_dir: Path) -> dict:
    LANE.adb("uninstall", "com.pocketrealm", check=False)
    apk_path = out_dir / f"server-{tag}.apk"
    apk_path.write_bytes(apk)
    LANE.adb("install", "-r", str(apk_path), timeout=600)
    tests_path = out_dir / f"tests-{tag}.apk"
    tests_path.write_bytes(tests)
    LANE.adb("install", "-r", str(tests_path), timeout=300)
    stage_baseline_data()
    LANE.adb("shell", "run-as", "com.pocketrealm", "rm", "-f",
             "files/baseline-world-sanity.json", check=False)
    result = LANE.run([LANE.ADB, "shell", "am", "instrument", "-w", "-e",
                       "class", RUNNER, INSTRUMENT],
                      timeout=3600, check=False)
    (out_dir / f"instrument-{tag}.log").write_text(result, encoding="utf-8")
    # Component logs for post-mortem (world crashes surface here).
    for name in ("world.log", "database-errors.log", "realmd.log"):
        logged = LANE.run([LANE.ADB, "shell", "run-as", "com.pocketrealm", "cat",
                           f"no_backup/server/logs/{name}"], check=False, timeout=60)
        (out_dir / f"{name}.{tag}").write_text(logged, encoding="utf-8")
    crash = LANE.run([LANE.ADB, "shell", "logcat", "-d", "-b", "crash", "-t", "200"],
                     check=False, timeout=60)
    (out_dir / f"logcat-crash.{tag}").write_text(crash, encoding="utf-8")
    full = LANE.run([LANE.ADB, "shell", "logcat", "-d", "-t", "800"],
                    check=False, timeout=120)
    (out_dir / f"logcat-full.{tag}").write_text(full, encoding="utf-8")
    tree = LANE.run([LANE.ADB, "shell", "run-as", "com.pocketrealm", "find",
                     "no_backup/server", "-maxdepth", "2"], check=False, timeout=60)
    (out_dir / f"server-tree.{tag}").write_text(tree, encoding="utf-8")
    raw = LANE.adb("shell", "run-as", "com.pocketrealm", "cat",
                   "files/baseline-world-sanity.json", timeout=120)
    (out_dir / f"evidence-{tag}.json").write_text(raw, encoding="utf-8")
    if "OK (1 test)" not in result:
        print(f"WARNING: server {tag} instrument did not report OK — see {out_dir / f'instrument-{tag}.log'}")
    return json.loads(raw)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--only", choices=("b", "a"), help="drive a single server (default: b then a)")
    args = parser.parse_args()

    run_id = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir = ROOT / "build" / "baseline-world-sanity" / run_id
    out_dir.mkdir(parents=True, exist_ok=True)

    if args.skip_build:
        apks = LANE.reuse_prior_apks()
    else:
        apks = LANE.build_apks()
    apks.pop("gradle_commands", None)

    emulator = subprocess.Popen(
        [str(LANE.EMULATOR), "-avd", LANE.AVD, "-no-window", "-no-audio", "-no-boot-anim",
         "-no-snapshot", "-accel", "on", "-memory", "4096"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    order = ("b", "a") if not args.only else (args.only,)
    report = {"runId": run_id, "servers": {}}
    try:
        LANE.wait_for_device(600)
        for tag in order:
            evidence = drive(apks[f"server_{tag}"], apks[f"tests_{tag}"], tag, out_dir)
            report["servers"][tag] = {"providerMode": evidence.get("providerMode"),
                                      "evidence": evidence}
    finally:
        try:
            LANE.adb("emu", "kill", timeout=60, check=False)
        except Exception:
            pass
        try:
            emulator.wait(timeout=60)
        except Exception:
            pass

    (out_dir / "sanity-report.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n")
    for tag, entry in report["servers"].items():
        ev = entry["evidence"]
        print(f"[{tag}] provider={entry['providerMode']} "
              f"dbBootMs={ev.get('dbBootMs')} worldBootMs={ev.get('worldBootMs')} "
              f"saveallAckMs={ev.get('saveallAckMs')} accounts={json.dumps(ev.get('accounts'))} "
              f"dirtyKill={json.dumps(ev.get('dirtyKillRecovery'))} "
              f"onDiskAccounts={ev.get('onDiskAccounts')}")
    providers = {entry["providerMode"] for entry in report["servers"].values()}
    ok = providers <= {"MARIADB", "SQLITE"} and all(
        entry["providerMode"] for entry in report["servers"].values())
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
