#!/usr/bin/env python3
"""MariaDB-vs-SQLite bot pressure benchmark driver (the DEC-10 bot legs).

Stages the host-prepared o11 generation (dbc+maps+vmaps+mmaps from the
user's own client; tools/prepare_o11_bot_data.py) into the app's
content/o11-server, then drives BotPressureBenchmarkRunner on BOTH
servers (B = SQLite first, then A = MariaDB): the REAL stack boots on
the product's own bot-profile entry (startBotProfile), steps REAL
playerbot populations through setBotTarget waves, samples the product
telemetry (tick percentiles, hard stalls, db probe delay, bots online,
per-process RSS, Binder health RTT), and times a client-acked saveall
per wave.

Usage:
    python3 tools/run_bot_pressure_benchmark.py [--only b|a] [--skip-build]
        [--profile mobile-balanced-b100-v1] [--waves 25:120,50:180,100:240]
        [--sample-ms 5000]

Leave one console attached afterwards with tools/world_console.py.
"""

import argparse
import datetime as _dt
import importlib.util
import json
import statistics
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
_SPEC = importlib.util.spec_from_file_location(
    "run_differential_parity", ROOT / "tools" / "run_differential_parity.py")
LANE = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(LANE)

INSTRUMENT = "com.pocketrealm.test/androidx.test.runner.AndroidJUnitRunner"
RUNNER = "com.pocketrealm.database.BotPressureBenchmarkRunner"
O11_STORE = ROOT / "build" / "o11-bot-data" / "o11-server"


def preflight_device_mode(apks: dict, out_dir: Path) -> None:
    """PC-side readiness gate before ANY contact with a production
    device (the 2026-08-25 device window burned hours on seed SQL the
    API-35 emulator masked): the sqlite APK's embedded seed is replayed
    through the pinned 3.18/3.32 shells and digest-checked against the
    baseline+lockfile, and the o11 staging source is proven present -
    all host-side, before the first adb command."""
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "verify_seed_sqlite_floor",
        ROOT / "tools" / "verify_seed_sqlite_floor.py")
    verify = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verify)
    staged = out_dir / "preflight-server-b.apk"
    staged.write_bytes(apks["server_b"])
    verify.verify_apk(staged, verify.DEFAULT_FLOORS)
    if not (O11_STORE / "active.json").is_file():
        raise RuntimeError(
            f"prepared o11 store missing: {O11_STORE} - run "
            f"tools/prepare_o11_bot_data.py first")
    print("device preflight PASS (seed floor replayed host-side)")


def stage_o11_run_as(base: str) -> None:
    """Production-device staging (no adb root): one gzip tar pushed to
    /data/local/tmp (chmod 644 - the dir is world-traversable), then
    extracted by the app uid through run-as."""
    import subprocess
    import tarfile
    import tempfile
    LANE.run([LANE.ADB, "shell", "rm", "-rf", base], check=False, timeout=60)
    # A prebuilt cache tar next to the store saves ~5 minutes of host
    # gzip per staged engine inside a time-boxed device window. The
    # o11 store is static content; if the cache is absent or empty the
    # original per-run temp tar path is used unchanged.
    cache = O11_STORE.parent / "o11-server.tar.gz"
    if cache.is_file() and cache.stat().st_size > 0:
        archive = cache
    else:
        with tempfile.NamedTemporaryFile(suffix=".tar.gz", delete=False) as handle:
            archive = Path(handle.name)
        with tarfile.open(archive, "w:gz") as tar:
            for item in sorted(O11_STORE.iterdir()):
                tar.add(item, arcname=item.name)
    print(f"staging archive: {archive.stat().st_size / 1e6:.0f} MB")
    LANE.run([LANE.ADB, "push", str(archive), "/data/local/tmp/o11.tar.gz"],
             check=True, timeout=1800)
    if archive != cache:
        archive.unlink()
    LANE.run([LANE.ADB, "shell", "chmod", "644", "/data/local/tmp/o11.tar.gz"],
             check=True, timeout=60)
    # adb shell CONCATENATES argv and the device shell re-parses it: an
    # unquoted "sh -c <script>" reaches the device as separate words and
    # -c sees only the first (the o09 quoting lesson, device edition) -
    # the whole remote command must travel as ONE quoted argv string.
    LANE.run([LANE.ADB, "shell",
              "run-as com.pocketrealm mkdir -p files/content/o11-server"],
             check=True, timeout=60)
    LANE.run([LANE.ADB, "shell",
              "run-as com.pocketrealm sh -c 'tar xzf /data/local/tmp/o11.tar.gz"
              " -C files/content/o11-server'"],
             check=True, timeout=1800)
    LANE.run([LANE.ADB, "shell", "rm", "-f", "/data/local/tmp/o11.tar.gz"],
             check=False, timeout=60)


def stage_o11_data() -> None:
    """Stage the prepared generation into files/content/o11-server via
    `adb root` push+chown+restorecon (the o09 staging precedent: run-as
    cannot cross the API-35 FUSE boundary)."""
    if not (O11_STORE / "active.json").is_file():
        raise RuntimeError(
            f"prepared o11 store missing: {O11_STORE} - run "
            f"tools/prepare_o11_bot_data.py first")
    import os
    import time
    base = "/data/data/com.pocketrealm/files/content/o11-server"
    serial = os.environ.get("ANDROID_SERIAL") or ""
    if serial and not serial.startswith("emulator-"):
        stage_o11_run_as(base)
        return
    LANE.run([LANE.ADB, "root"], check=False, timeout=60)
    time.sleep(4)
    LANE.adb("wait-for-device", timeout=120)
    LANE.run([LANE.ADB, "shell", "rm", "-rf", base], check=False, timeout=60)
    LANE.run([LANE.ADB, "shell", "mkdir", "-p", base], check=True, timeout=60)
    LANE.adb("push", f"{O11_STORE}{Path('.')}/.", base, timeout=1800)
    uid = LANE.run([LANE.ADB, "shell", "stat", "-c", "%u:%g",
                    "/data/data/com.pocketrealm"], check=True, timeout=60).strip()
    LANE.run([LANE.ADB, "shell", "chown", "-R", uid, base],
             check=True, timeout=600)
    LANE.run([LANE.ADB, "shell", "restorecon", "-R", base],
             check=False, timeout=600)
    LANE.run([LANE.ADB, "unroot"], check=False, timeout=60)
    time.sleep(4)
    LANE.adb("wait-for-device", timeout=120)
    probe = LANE.adb("shell", "run-as", "com.pocketrealm", "ls",
                     "files/content/o11-server", timeout=60)
    for needed in ("active.json", "generations"):
        if needed not in probe:
            raise RuntimeError(f"o11 staging verification missed {needed}: {probe}")


def summarize_wave(wave: dict) -> dict:
    samples = wave.get("samples") or []
    health = wave.get("dbHealth") or []

    def series(key, source=samples):
        return [s.get(key) for s in source
                if isinstance(s.get(key), (int, float)) and s.get(key, -1) >= 0]

    def median(key, source=samples):
        values = series(key, source)
        return round(statistics.median(values), 1) if values else None

    stalls = series("worldHardStalls")
    return {
        "profileId": wave.get("profileId"),
        "target": wave.get("target"),
        "worldBootMs": wave.get("worldBootMs"),
        "rampMs": wave.get("rampMs"),
        "botsOnlineAtRampEnd": wave.get("botsOnlineAtRampEnd"),
        "saveallAckMs": wave.get("saveallAckMs"),
        "botsOnlineAfterSave": wave.get("botsOnlineAfterSave"),
        "tickP50MedianMs": median("worldTickP50Ms"),
        "tickP95MedianMs": median("worldTickP95Ms"),
        "tickP99MaxMs": max(series("worldTickP99Ms"), default=None),
        "tickWindowMaxMs": max(series("worldTickWindowMaxMs"), default=None),
        "hardStallDelta": (max(stalls) - min(stalls)) if len(stalls) >= 2 else 0,
        "dbProbeMedianMs": median("dbProbeDelayMs"),
        "dbProbeMaxMs": max(series("dbProbeDelayMs"), default=None),
        "dbHealthRttMedianMs": median("dbHealthBinderRttMs", health),
        "dbHealthRttMaxMs": max(series("dbHealthBinderRttMs", health), default=None),
        "worldRssLastKb": series("worldRssKb")[-1] if series("worldRssKb") else None,
        "dbRssLastKb": series("dbRssKb")[-1] if series("dbRssKb") else None,
        "sampleCount": wave.get("sampleCount"),
    }


def drive(apk: bytes, tests: bytes, tag: str, out_dir: Path,
          waves: str, sample_ms: int, ready_timeout_ms: int,
          ramp_timeout_ms: int, keep_install: bool = False,
          skip_install: bool = False) -> dict:
    """keep_install: skip the uninstall and the o11 staging when the data
    is already in place (install -r preserves app data) - the
    steady-state test shape: same install, world restarted, no
    provisioning noise, and nothing to re-push over wireless adb."""
    if not keep_install:
        LANE.adb("uninstall", "com.pocketrealm", check=False)
    if skip_install:
        print("skip-install: APKs assumed staged on device")
    apk_path = out_dir / f"server-{tag}.apk"
    apk_path.write_bytes(apk)
    # wireless installs of the ~340MB APK are flaky on a freshly
    # re-registered link - retry instead of dying
    import time as _time
    for attempt in range(0 if skip_install else 3):
        try:
            LANE.adb("install", "-r", str(apk_path), timeout=600)
            break
        except Exception:
            if attempt == 2:
                raise
            print(f"server install attempt {attempt + 1} failed; retrying")
            _time.sleep(5)
    tests_path = out_dir / f"tests-{tag}.apk"
    tests_path.write_bytes(tests)
    for attempt in range(0 if skip_install else 3):
        try:
            LANE.adb("install", "-r", str(tests_path), timeout=300)
            break
        except Exception:
            if attempt == 2:
                raise
            _time.sleep(5)
    if keep_install:
        probe = LANE.adb("shell", "run-as", "com.pocketrealm", "ls",
                         "files/content/o11-server", check=False)
        if "active.json" not in probe:
            print("o11 data missing under --keep-install; staging anyway")
            stage_o11_data()
    else:
        stage_o11_data()
    LANE.adb("shell", "run-as", "com.pocketrealm", "rm", "-f",
             "files/bot-pressure-benchmark.json", check=False)
    result = LANE.run(
        [LANE.ADB, "shell", "am", "instrument", "-w",
         "-e", "class", RUNNER,
         "-e", "waves", waves,
         "-e", "sampleMs", str(sample_ms),
         "-e", "worldReadyTimeoutMs", str(ready_timeout_ms),
         "-e", "rampTimeoutMs", str(ramp_timeout_ms),
         INSTRUMENT],
        timeout=7200, check=False)
    (out_dir / f"instrument-{tag}.log").write_text(result, encoding="utf-8")
    for name in ("world.log", "database-errors.log", "realmd.log"):
        logged = LANE.run([LANE.ADB, "shell", "run-as", "com.pocketrealm", "cat",
                           f"no_backup/server/logs/{name}"], check=False, timeout=120)
        (out_dir / f"{name}.{tag}").write_text(logged, encoding="utf-8")
    # A passing population verdict is NOT engine health: the 2026-08-27
    # device window had 324 rejected queries in a 600/600-green run (the
    # MySQL unary-'!' portability defect), and the bot manager's
    # criteria-free fallback masked it. Surface rejected queries as a
    # first-class finding on every run, pass or fail.
    errors = (out_dir / f"database-errors.log.{tag}").read_text(
        encoding="utf-8", errors="replace") if (out_dir / f"database-errors.log.{tag}").is_file() else ""
    error_kinds: dict[str, int] = {}
    for line in errors.splitlines():
        if "query ERROR" in line:
            tail = line.split("query ERROR:", 1)[-1].strip()
            error_kinds[tail] = error_kinds.get(tail, 0) + 1
    if error_kinds:
        summary = ", ".join(f"{kind} x{count}" for kind, count in
                            sorted(error_kinds.items(), key=lambda kv: -kv[1]))
        print(f"FINDING: server {tag} rejected {sum(error_kinds.values())} "
              f"queries during the run ({summary})")
    crash = LANE.run([LANE.ADB, "shell", "logcat", "-d", "-b", "crash", "-t", "200"],
                     check=False, timeout=60)
    (out_dir / f"logcat-crash.{tag}").write_text(crash, encoding="utf-8")
    raw = LANE.run([LANE.ADB, "shell", "run-as", "com.pocketrealm", "cat",
                    "files/bot-pressure-benchmark.json"],
                   check=False, timeout=120)
    if not raw.strip().startswith("{"):
        raise RuntimeError(
            f"server {tag}: no benchmark evidence (instrument verdict: "
            f"{'OK' if 'OK (1 test)' in result else 'FAILED'}; see "
            f"{out_dir / f'instrument-{tag}.log'})")
    (out_dir / f"evidence-{tag}.json").write_text(raw, encoding="utf-8")
    evidence = json.loads(raw)
    evidence["_wavesSummary"] = [summarize_wave(w) for w in evidence.get("waves", [])]
    if "OK (1 test)" not in result:
        print(f"WARNING: server {tag} instrument did not report OK — see "
              f"{out_dir / f'instrument-{tag}.log'}")
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--only", choices=("b", "a"),
                        help="drive a single server (default: b then a)")
    parser.add_argument("--waves",
                        default="bench-autologin-b50-v1:120,"
                                "bench-autologin-b100-v1:180,"
                                "bench-autologin-b160-v1:240",
                        help="profileId:soakSeconds comma list (one "
                             "measured bot profile per wave)")
    parser.add_argument("--sample-ms", type=int, default=5000)
    parser.add_argument("--world-ready-timeout-ms", type=int, default=3_600_000,
                        help="per-wave world readiness budget (first boot "
                             "per install builds the 246k-cell playerbot "
                             "equipment cache)")
    parser.add_argument("--ramp-timeout-ms", type=int, default=1_800_000,
                        help="bot ramp budget (600 bots need a long ramp)")
    parser.add_argument("--serial",
                        help="drive an attached ADB device (no emulator is "
                             "booted; production devices stage via run-as)")
    parser.add_argument("--apks",
                        help="directory with prebuilt server-{a,b}.apk and "
                             "tests-{a,b}.apk (e.g. arm64 device builds) - "
                             "skips the lane build entirely")
    parser.add_argument("--avd",
                        help="emulator AVD override (default: the "
                             "differential lane's API-35 AVD). Use an "
                             "older-framework AVD (e.g. PocketRealm_API28, "
                             "SQLite 3.22) to prove the seed floor "
                             "end-to-end below the device's 3.32.")
    parser.add_argument("--skip-install", action="store_true",
                        help="APKs already installed on the device (staged "
                             "manually) - skip both installs entirely")
    parser.add_argument("--skip-preflight", action="store_true",
                        help="skip the host seed-floor preflight (only for "
                             "artifacts already verified this session - "
                             "the gate exists to protect production "
                             "devices from unverified seeds)")
    parser.add_argument("--keep-install", action="store_true",
                        help="do not uninstall or re-stage: install -r over "
                             "the existing app data (steady-state re-test "
                             "shape; o11 is staged only if missing)")
    parser.add_argument("--emulator-mem-mb", type=int, default=6144,
                        help="emulator guest RAM. The admission monitor's "
                             "minFreeMemoryMiB safety floor caps population "
                             "when guest RAM is small: a 6 GiB guest "
                             "plateaus ~440 bots regardless of engine. Use "
                             "12288 to match the 12 GiB handheld for "
                             "forced-ceiling waves.")
    args = parser.parse_args()

    run_id = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir = ROOT / "build" / "bot-pressure" / run_id
    out_dir.mkdir(parents=True, exist_ok=True)

    if args.apks:
        apk_dir = Path(args.apks)
        apks = {name: (apk_dir / f"{name.replace('_', '-')}.apk").read_bytes()
                for name in ("server_a", "tests_a", "server_b", "tests_b")}
    elif args.skip_build:
        apks = LANE.reuse_prior_apks()
    else:
        apks = LANE.build_apks()
    apks.pop("gradle_commands", None)

    if args.serial:
        # A serial means a REAL attached device: no emulator may be booted
        # and every adb call must stay scoped to it (--skip-preflight only
        # skips the host seed-floor replay, never the device scoping).
        if not args.skip_preflight:
            preflight_device_mode(apks, out_dir)
        import os
        os.environ["ANDROID_SERIAL"] = args.serial
        state = LANE.adb("get-state", timeout=60).strip()
        if state != "device":
            raise SystemExit(f"device {args.serial} not available: {state}")
    else:
        # Fixed port + ANDROID_SERIAL: with a production device also
        # attached (the normal shape of a device-resume session), bare
        # adb commands fail with "more than one device" - scope every
        # adb call this driver makes to ITS emulator only, never the
        # maintainer's hardware.
        emu_port = 5574
        emulator = subprocess.Popen(
            [str(LANE.EMULATOR), "-avd", args.avd or LANE.AVD, "-no-window",
             "-no-audio", "-no-boot-anim", "-no-snapshot", "-accel", "on",
             "-memory", str(args.emulator_mem_mb), "-port", str(emu_port)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        import os
        os.environ["ANDROID_SERIAL"] = f"emulator-{emu_port}"
    order = ("b", "a") if not args.only else (args.only,)
    report = {"runId": run_id, "waves": args.waves, "device": args.serial,
              "servers": {}}
    try:
        if not args.serial:
            LANE.wait_for_device(600)
        for tag in order:
            evidence = drive(apks[f"server_{tag}"], apks[f"tests_{tag}"], tag,
                             out_dir, args.waves, args.sample_ms,
                             args.world_ready_timeout_ms,
                             args.ramp_timeout_ms, args.keep_install, args.skip_install)
            report["servers"][tag] = {
                "providerMode": evidence.get("providerMode"),
                "evidence": evidence,
            }
    finally:
        if not args.serial:
            try:
                LANE.adb("emu", "kill", timeout=60, check=False)
            except Exception:
                pass
            try:
                emulator.wait(timeout=60)
            except Exception:
                pass

    (out_dir / "benchmark-report.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n")

    print(f"\n===== bot pressure benchmark (waves {args.waves}) =====")
    for tag, entry in report["servers"].items():
        ev = entry["evidence"]
        print(f"\n[{tag}] provider={entry['providerMode']} "
              f"dbBootMs={ev.get('dbBootMs')} worldBootMs={ev.get('worldBootMs')} "
              f"finalSaveallAckMs={ev.get('finalSaveallAckMs')}")
        for wave in ev.get("_wavesSummary", []):
            print(f"  target={wave['target']:>3}  rampMs={wave['rampMs']} "
                  f"online={wave['botsOnlineAtRampEnd']} "
                  f"saveAckMs={wave['saveallAckMs']} "
                  f"tickP50={wave['tickP50MedianMs']} tickP95={wave['tickP95MedianMs']} "
                  f"tickP99max={wave['tickP99MaxMs']} windowMax={wave['tickWindowMaxMs']} "
                  f"stalls+={wave['hardStallDelta']}")
            print(f"           dbProbeMed={wave['dbProbeMedianMs']} "
                  f"dbProbeMax={wave['dbProbeMaxMs']} "
                  f"dbHealthRttMed={wave['dbHealthRttMedianMs']} "
                  f"worldRss={wave['worldRssLastKb']}Kb dbRss={wave['dbRssLastKb']}Kb "
                  f"({wave['sampleCount']} samples)")
    providers = {entry["providerMode"] for entry in report["servers"].values()}
    return 0 if providers and providers <= {"MARIADB", "SQLITE"} else 1


if __name__ == "__main__":
    raise SystemExit(main())
