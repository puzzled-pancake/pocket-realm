#!/usr/bin/env python3
"""DB-scope engine benchmark driver.

Builds the SAME two server APKs as the differential lane (A = default
MariaDB, B = -PdifferentialTestLane + -PsqliteProvider, x86_64), drives the
IDENTICAL EngineBenchmarkRunner on both via `am instrument`, pulls the
evidence bundles, and emits a paired comparison under
build/engine-benchmark/<run-id>/. Reuses the differential lane's
build/boot machinery (tools/run_differential_parity.py).

Everything measured here is COMPARATIVE on this host (x86_64 emulator,
WHPX): the absolute product contracts stay device-gated, and the
world-driven metrics (tick p99, saveall under bots, probe delay under
load) stay gated to on-device runs — see the runner's KDoc for the
representation caveats per engine.
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
BENCH_CLASS = "com.pocketrealm.database.EngineBenchmarkRunner"


def drive(apk: bytes, tests: bytes, tag: str, out_dir: Path) -> dict:
    LANE.adb("uninstall", "com.pocketrealm", check=False)
    apk_path = out_dir / f"server-{tag}.apk"
    apk_path.write_bytes(apk)
    LANE.adb("install", "-r", str(apk_path), timeout=600)
    tests_path = out_dir / f"tests-{tag}.apk"
    tests_path.write_bytes(tests)
    LANE.adb("install", "-r", str(tests_path), timeout=300)
    LANE.adb("shell", "run-as", "com.pocketrealm", "rm", "-f",
             "files/engine-benchmark.json", check=False)
    result = LANE.run([LANE.ADB, "shell", "am", "instrument", "-w", "-e",
                       "class", BENCH_CLASS, INSTRUMENT],
                      timeout=3600, check=False)
    (out_dir / f"instrument-{tag}.log").write_text(result, encoding="utf-8")
    raw = LANE.adb("shell", "run-as", "com.pocketrealm", "cat",
                   "files/engine-benchmark.json", timeout=120)
    (out_dir / f"evidence-{tag}.json").write_text(raw, encoding="utf-8")
    if "OK (1 test)" not in result:
        print(f"WARNING: server {tag} instrument did not report OK; log at {out_dir / f'instrument-{tag}.log'}")
    return json.loads(raw)


def compare(a: dict, b: dict) -> dict:
    def cyc(bundle):
        rows = bundle.get("lifecycleCycles") or []
        def med(key):
            vals = sorted(r.get(key, 0) for r in rows)
            return vals[len(vals) // 2] if vals else None
        return {"startMs": med("startMs"), "stopMs": med("stopMs"),
                "healthMs": med("healthMs"),
                "rssRunningKb": max((r.get("rssRunningKb", 0) for r in rows), default=None)}

    def lookups(bundle):
        node = ((bundle.get("battery") or {}).get("pointLookups1k")) or {}
        out = {}
        for table, record in node.items():
            if isinstance(record, dict) and "p50Ms" in record:
                out[table] = {"p50Ms": record.get("p50Ms"), "p99Ms": record.get("p99Ms")}
            elif isinstance(record, dict):
                out[table] = {"perStatementMs": record.get("perStatementMs"),
                              "batchWallMs": record.get("batchWallMs")}
        return out

    def ratio(x, y):
        try:
            if x is None or y is None or x == 0:
                return None
            return round(y / x, 2)
        except (TypeError, ZeroDivisionError):
            return None

    a_cyc, b_cyc = cyc(a), cyc(b)
    a_lk, b_lk = lookups(a), lookups(b)
    comparison = {
        "bootAndLifecycle": {
            "startMs": {"A(mariadb)": a_cyc["startMs"], "B(sqlite)": b_cyc["startMs"],
                        "B/A": ratio(a_cyc["startMs"], b_cyc["startMs"])},
            "stopMs": {"A(mariadb)": a_cyc["stopMs"], "B(sqlite)": b_cyc["stopMs"],
                       "B/A": ratio(a_cyc["stopMs"], b_cyc["stopMs"])},
            "initializeMs": {"A(mariadb)": a.get("initializeMs"), "B(sqlite)": b.get("initializeMs")},
            "idempotentMigrationsMs": {"A(mariadb)": a.get("idempotentMigrationsMs"),
                                       "B(sqlite)": b.get("idempotentMigrationsMs")},
        },
        "pointLookups": {
            table: {"A(mariadb) perStmtMs": a_lk.get(table, {}).get("perStatementMs"),
                    "B(sqlite) p50Ms": b_lk.get(table, {}).get("p50Ms")}
            for table in sorted(set(a_lk) | set(b_lk))
        },
        "writes": {
            "A(mariadb) burstNetMs": ((a.get("battery") or {}).get("writeBurst") or {}).get("netMs"),
            "B(sqlite) insert500TxnMs": ((b.get("battery") or {}).get("writeBurst") or {}).get("insert500TxnMs"),
        },
        "footprint": {
            "datadirBytes": {"A(mariadb)": a.get("datadirBytes"), "B(sqlite)": b.get("datadirBytes")},
            "rssRunningKb": {"A(mariadb)": a_cyc["rssRunningKb"], "B(sqlite)": b_cyc["rssRunningKb"]},
        },
        "caveats": [
            "x86_64 emulator, WHPX — comparative only; absolute numbers stay device-gated",
            "SQLite numbers are in-process on a datadir copy (what CMaNGOS DO_SQLITE does in :world)",
            "MariaDB numbers are client-batch server-side execution via the engine's own client "
            "launcher — NOT the C-connector round trip; the client spawn cost is measured separately",
            "world-driven metrics (tick p99, saveall under bots, probe under load) stay device-gated",
        ],
    }
    return comparison


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true",
                        help="reuse the newest prior differential run's captured APKs")
    args = parser.parse_args()

    run_id = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir = ROOT / "build" / "engine-benchmark" / run_id
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
    try:
        LANE.wait_for_device(600)
        evidence_a = drive(apks["server_a"], apks["tests_a"], "a", out_dir)
        evidence_b = drive(apks["server_b"], apks["tests_b"], "b", out_dir)
    finally:
        try:
            LANE.adb("emu", "kill", timeout=60, check=False)
        except Exception:
            pass
        try:
            emulator.wait(timeout=60)
        except Exception:
            pass

    report = {
        "runId": run_id,
        "providers": {"a": evidence_a.get("providerMode"), "b": evidence_b.get("providerMode")},
        "evidenceA": evidence_a,
        "evidenceB": evidence_b,
        "comparison": compare(evidence_a, evidence_b),
    }
    (out_dir / "benchmark-report.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps(report["comparison"], indent=2))
    ok = report["providers"]["a"] == "MARIADB" and report["providers"]["b"] == "SQLITE"
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
