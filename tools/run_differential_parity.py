#!/usr/bin/env python3
"""P6.5/DEC-09: the differential parity lane orchestrator.

Builds both server APKs (Server A = the default MariaDB provider,
Server B = -PdifferentialTestLane + -PsqliteProvider, x86_64), manages
the dedicated AVD (enforced cold boots), drives the identical
DifferentialBarrageRunner on both via `am instrument`, pulls the
evidence bundles, runs the parity oracle against the append-only
KNOWN-DIFFERENCE LEDGER, and emits build/differential/<run-id>/
parity-report.json (always — failure paths too).

Verdicts: PASS (exit 0) | PASS-GATED (exit 2 — every executed check
passed but the world legs were skipped for lack of prepared game data,
DEC-10; a gated run can never satisfy the standard exit criteria) |
FAIL (exit 1).

The comparison today is ROW-COUNT-ONLY over Server A's P5 export slice.
The content-level comparator (typed field comparators over the staged
TSVs vs a canonical SQLite emission, modulo the ledger classes) is
REGISTERED-NOT-IMPLEMENTED — the W9 TSV leg; until it lands, the three
content ledger classes are recorded but not exercisable.

Profiles: quick (smoke) | standard (the evidence run) | massive (soak).
"""

import argparse
import datetime as _dt
import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AVD = "PocketDiff_x86_64"
SDK = Path(os.environ.get("ANDROID_SDK_ROOT")
           or os.environ.get("ANDROID_HOME")
           or Path.home() / "AppData" / "Local" / "Android" / "Sdk")
ADB = SDK / "platform-tools" / "adb.exe"
EMULATOR = SDK / "emulator" / "emulator.exe"
INSTRUMENT = "com.pocketrealm.test/androidx.test.runner.AndroidJUnitRunner"
BUILD_CMD_PREFIX = [str(ROOT / "android" / "gradlew.bat")]     if (ROOT / "android" / "gradlew.bat").exists() else ["./gradlew"]
# The instrument timeout must exceed the runner's own worst-case internal
# budget sum (world boots 600 s + bot ramps 900 s per step + soaks), or a
# slow-but-legal run is killed by the harness (P6.5 R1 A).
INSTRUMENT_TIMEOUT_S = {"quick": 3600, "standard": 7200, "massive": 10800}

# The append-only KNOWN-DIFFERENCE LEDGER (Part 2 P6.5 spec): every
# engine-expected divergence class, seeded from the recorded residuals.
KNOWN_DIFFERENCE_LEDGER = [
    {"id": "NOCASE-ASCII", "origin": "I-60",
     "rule": "text differing only by ASCII case folds equal (NOCASE folds ASCII only, not full utf8_general_ci)"},
    {"id": "DECIMAL-REAL", "origin": "I-65",
     "rule": "decimal/numeric REAL columns compare within 1e-9"},
    {"id": "DATETIME-WALLCLOCK", "origin": "I-84",
     "rule": "DATETIME audit columns carry each engine's wall-clock provenance (no product consumer)"},
    {"id": "AUTOINCREMENT-SEQ", "origin": "spec",
     "rule": "auto-increment sequence values (sqlite_sequence / MariaDB AUTO_INCREMENT counters) are engine-mechanical"},
    {"id": "RUN-TIMESTAMPS", "origin": "spec",
     "rule": "run timestamps (created_at/started_at of THIS barrage) differ between servers"},
]

# Server A's export slice shape (DatabaseUserStateBridge.REALMD_USER_
# STATE_TABLES + classiccharacters minus ai_playerbot*): pinned so an
# exporter/schema regression cannot shrink the compared set silently
# (P6.5 R1 C). The characters count is as deterministic as the realmd
# list (sha-pinned bootstrap + the 412 pinned migrations) — exact pin,
# update-with-manifest discipline (P6.5 R2 C).
PINNED_REALMD_SLICE = {"account", "account_banned", "ip_banned",
                       "realmcharacters", "system_fingerprint_usage"}
PINNED_CHARACTER_TABLES = 64


def run(cmd, cwd=None, check=True, timeout=None, capture=True):
    print(f"+ {' '.join(str(c) for c in cmd)}", flush=True)
    out = subprocess.run(
        [str(c) for c in cmd], cwd=str(cwd) if cwd else None, check=check,
        timeout=timeout, capture_output=capture, text=True,
    )
    return out.stdout + (out.stderr or "")


def adb(*args, timeout=120, check=True):
    return run([ADB, *args], timeout=timeout, check=check)


def wait_for_device(boot_timeout_s: int) -> None:
    # Deadline-bounded polling (no blocking `adb wait-for-device`: a stuck
    # offline emulator made that stage raise an unhandled TimeoutExpired
    # before the poll loop could even run).
    deadline = time.time() + boot_timeout_s
    while time.time() < deadline:
        try:
            boot = adb("shell", "getprop", "sys.boot_completed", timeout=30,
                       check=False).strip()
            if boot == "1":
                return
        except subprocess.SubprocessError:
            pass
        time.sleep(5)
    raise RuntimeError(f"emulator did not boot within {boot_timeout_s}s")


def build_apks() -> dict:
    gradle = ROOT / "android"
    common = ["-PpocketAbi=x86_64", "-PpocketLane=full"]
    # Server A (the DEFAULT MariaDB provider) builds FIRST and is captured
    # EAGERLY: the sqlite build below overwrites app-debug.apk at the same
    # path. The first quick run read server A's bytes lazily (a Path kept
    # until the return statement) and therefore captured the SQLITE APK
    # for BOTH servers - an invalid sqlite-vs-sqlite comparison that
    # PASSED (recorded in the plan's P6.5 first-run CORRECTION).
    command_a = [*BUILD_CMD_PREFIX, ":app:assembleDebug", ":app:assembleDebugAndroidTest",
                 *common]
    run(command_a, cwd=gradle, timeout=1800)
    debug_dir = ROOT / "android" / "app" / "build" / "outputs" / "apk" / "debug"
    server_a = (debug_dir / "app-debug.apk").read_bytes()
    tests_a = (ROOT / "android" / "app" / "build" / "outputs" / "apk" /
               "androidTest" / "debug" / "app-debug-androidTest.apk").read_bytes()
    command_b = [*BUILD_CMD_PREFIX, ":app:assembleDebug", ":app:assembleDebugAndroidTest",
                 *common, "-PsqliteProvider", "-PdifferentialTestLane"]
    run(command_b, cwd=gradle, timeout=1800)
    server_b = (debug_dir / "app-debug.apk").read_bytes()
    # An identical-APK refusal lives in main() so the report records the
    # executed commands and hashes BEFORE refusing (R4 F).
    # The SAME instrumentation APK drives both servers by design (identical
    # barrage; the sqlite-lane rebuild of the androidTest APK is ignored).
    tests_b = tests_a
    return {"server_a": server_a, "tests_a": tests_a,
            "server_b": server_b, "tests_b": tests_b,
            "gradle_commands": [" ".join(str(c) for c in command_a),
                                " ".join(str(c) for c in command_b)]}


def reuse_prior_apks() -> dict:
    """--skip-build: reuse the newest prior run's captured server APKs (the
    run dir is timestamped; the captured bytes are the pinned inputs).
    Only COMPLETE captures (server + tests APKs) qualify — a run that died
    mid-capture is skipped, not half-reused (P6.5 R2 D/F)."""
    base = ROOT / "build" / "differential"
    candidates = sorted(d for d in base.iterdir() if d.is_dir()) if base.is_dir() else []
    for run_dir in reversed(candidates):
        parts = {name: run_dir / f"{name}.apk" for name in
                 ("server-a", "server-b", "tests-a", "tests-b")}
        if all(p.is_file() and p.stat().st_size > 0 for p in parts.values()):
            return {"server_a": parts["server-a"].read_bytes(),
                    "tests_a": parts["tests-a"].read_bytes(),
                    "server_b": parts["server-b"].read_bytes(),
                    "tests_b": parts["tests-b"].read_bytes(),
                    "reused_from": run_dir.name}
    raise RuntimeError("--skip-build: no prior run with a complete APK capture found under build/differential/")


def drive_server(apk_bytes: bytes, tests_bytes: bytes, tag: str, out_dir: Path,
                 profile: str = "quick") -> dict:
    # Fresh process state per server: uninstall anything present, install,
    # run the barrage, pull the evidence bundle, uninstall again.
    adb("uninstall", "com.pocketrealm", check=False)
    apk_path = out_dir / f"server-{tag}.apk"
    apk_path.write_bytes(apk_bytes)
    adb("install", "-r", str(apk_path), timeout=600)
    # The barrage runner rides the separate androidTest APK.
    tests_path = out_dir / f"tests-{tag}.apk"
    tests_path.write_bytes(tests_bytes)
    adb("install", "-r", str(tests_path), timeout=300)
    # Stale-evidence guard (P6.5 R2 E): a mid-run crash leaves the prior
    # bundle in place — delete it so this run can only adjudicate its own
    # evidence (a missing bundle fails the pull, loudly).
    adb("shell", "run-as", "com.pocketrealm", "rm", "-f",
        "files/differential-barrage.json", check=False)
    result = run([ADB, "shell", "am", "instrument", "-w", "-e",
                  "class", "com.pocketrealm.database.DifferentialBarrageRunner",
                  "-e", "differentialProfile", profile,
                  INSTRUMENT], timeout=INSTRUMENT_TIMEOUT_S[profile], check=False)
    (out_dir / f"instrument-{tag}.log").write_text(result, encoding="utf-8")
    try:
        evidence_raw = adb("shell", "run-as", "com.pocketrealm", "cat",
                           "files/differential-barrage.json", timeout=120)
    except subprocess.CalledProcessError:
        raise RuntimeError("server %s: evidence bundle pull failed; instrument log at %s; tail: %s" % (
            tag, out_dir / ("instrument-%s.log" % tag), result[-2000:]))
    (out_dir / f"evidence-{tag}.json").write_text(evidence_raw, encoding="utf-8")
    try:
        return json.loads(evidence_raw)
    except json.JSONDecodeError:
        raise RuntimeError("server %s: evidence bundle is not valid JSON; instrument log at %s; tail: %s" % (
            tag, out_dir / ("instrument-%s.log" % tag), result[-2000:]))


def verdict_to_exit(verdict) -> int:
    """PASS exits 0; PASS-GATED (the world legs skipped for lack of game
    data, DEC-10) exits 2 — every executed check passed, but the standard
    exit criteria are NOT satisfied by a gated run; everything else is 1."""
    if verdict == "PASS":
        return 0
    return 2 if verdict == "PASS-GATED" else 1


def mariadb_export_counts(bundle: dict) -> dict:
    """Server A's dump shape: the P5 exporter's per-table records
    ({rows, sha256, ...}) under dump.tables[db][table]. Only the
    user-state slice is exported BY DESIGN (classicrealmd's 5 tables +
    the classiccharacters non-bot tables); tables outside that slice —
    world content, regenerable bot state, and seed content present on
    both engines (antispam_*, realmlist) — are not comparable through
    this leg and are skipped deliberately, never silently."""
    tables = (bundle.get("dump") or {}).get("tables") or {}
    return {db: {table: record.get("rows") for table, record in per.items()}
            for db, per in tables.items()}


def parity_oracle(a: dict, b: dict, profile: str) -> dict:
    """Compare the two evidence bundles; any diff outside the ledger fails."""
    findings = []
    verdict = "PASS"

    def check(name: str, ok: bool, detail: str):
        nonlocal verdict
        findings.append({"check": name, "ok": ok, "detail": detail})
        if not ok:
            verdict = "FAIL"

    # W0 provider gates: the comparison is ONLY valid as A=MARIADB vs
    # B=SQLITE. The first quick run PASSED a sqlite-vs-sqlite comparison
    # because nothing asserted the providers - that evidence class must
    # FAIL mechanically, not pass vacuously.
    check("W0-A-provider", a.get("providerMode") == "MARIADB",
          f"providerMode={a.get('providerMode')} (must be MARIADB)")
    check("W0-B-provider", b.get("providerMode") == "SQLITE",
          f"providerMode={b.get('providerMode')} (must be SQLITE)")
    check("W0-A-dump-source", (a.get("dump") or {}).get("source") == "mariadb-outfile-staging",
          f"source={(a.get('dump') or {}).get('source')}")
    check("W0-B-dump-source", (b.get("dump") or {}).get("source") == "sqlite-rowcount-emitter",
          f"source={(b.get('dump') or {}).get('source')}")
    # W0 corpus identity (R1 E): both servers must have driven the
    # REQUESTED corpus — a standard-flagged run whose evidence is
    # quick-shaped (stale tests APK, arg-plumbing regression) must FAIL,
    # not exit 0 as a manufactured "standard PASS".
    for tag, bundle in (("A", a), ("B", b)):
        check(f"W0-{tag}-corpus", bundle.get("profile") == profile,
              f"profile={bundle.get('profile')} (requested {profile})")
    if profile != "quick":
        for tag, bundle in (("A", a), ("B", b)):
            check(f"W0-{tag}-worldleg-present", "worldLeg" in bundle,
                  "standard/massive corpora must record the world leg (gated or not)")

    # W1 boot parity: both providers completed the full lifecycle.
    for tag, bundle in (("A", a), ("B", b)):
        check(f"W1-{tag}-boot", bool(bundle.get("revisionsVerified")),
              f"provider={bundle.get('providerMode')} bootWallMs={bundle.get('bootWallMs')}")

    # W8 cross-engine revision agreement (R1 C/E): both engines must
    # report the same manifest/seal counts and currency.
    ra, rb = a.get("revisionState") or {}, b.get("revisionState") or {}
    for key in ("migrationManifestCount", "migrationSealedCount", "migrationsCurrent"):
        check(f"W8-{key}", ra.get(key) == rb.get(key) and ra.get(key) is not None,
              f"A={ra.get(key)} B={rb.get(key)} (must agree)")
    check("W8-manifest-is-412", ra.get("migrationManifestCount") == 412,
          f"A={ra.get('migrationManifestCount')} B={rb.get('migrationManifestCount')}")
    check("W8-sealed-is-412",
          ra.get("migrationSealedCount") == 412 and rb.get("migrationSealedCount") == 412,
          f"A={ra.get('migrationSealedCount')} B={rb.get('migrationSealedCount')}")
    check("W8-migrations-current",
          ra.get("migrationsCurrent") is True and rb.get("migrationsCurrent") is True,
          f"A={ra.get('migrationsCurrent')} B={rb.get('migrationsCurrent')}")

    # W7 db-level dirty-kill (recover-acked on both engines; the SQLite
    # side's RUNNING-state kill is a marker drill by design — the engine
    # is in-process with no write in flight at this point; the
    # load-bearing in-flight B-side kill is the gated world-kill pair).
    check("W7-dirty-kill", bool(a.get("dirtyKillRecovered")) and bool(b.get("dirtyKillRecovered")),
          "db kill+recover acked on both engines (B-side running-state marker drill; in-flight pair is the world leg)")
    # The quick leg's recover payload (R5 A/B/D): present on both, no
    # silent rebuild — the only recovery payload that executes on a gated
    # host, so it must be mechanically inspected too.
    for bundle, tag in ((a, "A"), (b, "B")):
        quick_payload = bundle.get("quickRecoverResult")
        check(f"W7-{tag}-quick-recover-payload", isinstance(quick_payload, dict)
              and "rebuilt" not in json.dumps(quick_payload),
              f"quickRecoverResult={type(quick_payload).__name__}")

    # Per-table row counts over SERVER A'S EXPORT SLICE (the P5 user-state
    # tables): every exported table must exist on B with the same count.
    # Row-count-only today: the content comparator is the W9 TSV leg
    # (REGISTERED-NOT-IMPLEMENTED — see the module docstring).
    counts_a = mariadb_export_counts(a)
    counts_b = (b.get("dump") or {}).get("rowCounts", {})
    row_check_summary = {"total": 0, "nonZero": 0}
    for database in sorted(counts_a):
        tb = counts_b.get(database, {})
        for table in sorted(counts_a[database]):
            ra_count = counts_a[database][table]
            rb_count = tb.get(table)
            # A record with no rows key is an exporter regression, not a
            # 0==0 match (R2 E: None == None passed vacuously before).
            check(f"rows:{database}.{table}",
                  ra_count is not None and ra_count == rb_count,
                  f"A={ra_count} B={rb_count}")
            row_check_summary["total"] += 1
            if (ra_count or 0) != 0 or (rb_count or 0) != 0:
                row_check_summary["nonZero"] += 1
    if not counts_a:
        check("W9-export-slice-nonempty", False, "Server A exported no tables")
    else:
        # Slice shape pin (R1 C): an exporter/schema regression must not
        # shrink the compared set silently.
        realmd = set(counts_a.get("classicrealmd", {}))
        check("W0-export-slice-realmd", realmd == PINNED_REALMD_SLICE,
              f"realmd slice={sorted(realmd)}")
        characters = counts_a.get("classiccharacters", {})
        check("W0-export-slice-characters", len(characters) == PINNED_CHARACTER_TABLES,
              f"characters tables={len(characters)} (pin {PINNED_CHARACTER_TABLES})")

    # W10 telemetry: comparative bands (B within 1.5x A on boot wall as
    # the quick leg; the standard profile adds saveall/tick bands below).
    try:
        wa = float(a.get("bootWallMs", 0))
        wb = float(b.get("bootWallMs", 0))
        if wa > 0 and wb > 0:
            check("W10-boot-band", wb <= wa * 1.5, f"A={wa}ms B={wb}ms (<=1.5xA)")
        else:
            check("W10-boot-band", False, f"bootWallMs missing/zero (A={wa} B={wb})")
    except (TypeError, ValueError):
        check("W10-boot-band", False, "bootWallMs missing/malformed")

    # Standard/massive profile legs.
    accounts_a, accounts_b = a.get("accounts"), b.get("accounts")
    gated_a = bool((a.get("worldLeg") or {}).get("gated"))
    gated_b = bool((b.get("worldLeg") or {}).get("gated"))
    gated = gated_a or gated_b
    if gated_a != gated_b:
        # Gate SYMMETRY (R2 A/D): one server gating while the other
        # executed the world corpus means the servers ran DIFFERENT
        # corpora — a partial-failure provisioning stage must FAIL, never
        # cap at PASS-GATED with the executed side unchecked.
        check("W0-gate-symmetry", False,
              f"corpus divergence: A.worldLeg.gated={gated_a} B.worldLeg.gated={gated_b}")
    if gated and gated_a == gated_b:
        # GAME-DATA GATE (DEC-10): this host has no imported game client,
        # so the world cannot boot and the world-driven legs (W2/W3/W5,
        # the world saves, the world-kill sentinel matrix) are
        # SKIPPED-GATED - loudly, never silently. The verdict is capped at
        # PASS-GATED (exit 2): a gated run can never satisfy the
        # standard-profile exit criteria.
        for name in ("W2-account-barrage", "W3-character-persistence",
                     "W5-bot-soak", "W6-world-saves",
                     "W7-world-kill-sentinels", "W7-kill-mid-save",
                     "W10-saveall-band", "W10-worldtick-band",
                     "W10-probe-band", "W10-drain-band"):
            findings.append({"check": name, "ok": None,
                             "detail": "SKIPPED-GATED: no prepared game data on "
                                       "this host (import a client to enable)"})
        # W4 is stretch-gated, not game-data-gated: loud on gated runs too
        # (R2 E — I-145's wording promised every non-quick profile).
        findings.append({"check": "W4-gameplay-probes", "ok": None,
                         "detail": "SKIPPED-STRETCH: the managed-addon scriptable "
                                   "surface (spec-marked stretch)"})
        verdict = "PASS-GATED" if verdict == "PASS" else verdict
    elif profile != "quick":
        # A non-gated standard/massive run MUST carry the full-corpus
        # evidence — anything else is a corpus regression, not a skip.
        check("W2-corpus-shape", isinstance(accounts_a, dict) and isinstance(accounts_b, dict),
              f"accounts A={type(accounts_a).__name__} B={type(accounts_b).__name__}")
        if isinstance(accounts_a, dict) and isinstance(accounts_b, dict):
            # W2 parity: the identical account barrage through the REAL
            # :world console writer (the F30 cross-process LoginDatabase
            # path).
            for key, expected in (("created", 100), ("verified", 100),
                                  ("wrongPasswordRejected", 100), ("gmLeveled", 10),
                                  ("statusProbed", 100), ("persistenceProbed", 100),
                                  ("persistenceMissing", 100)):
                ra2 = accounts_a.get(key)
                rb2 = accounts_b.get(key)
                check(f"W2-{key}", ra2 == expected and rb2 == expected,
                      f"A={ra2} B={rb2} (expected {expected})")
            # W7/DEC-02 sentinel survival through the concurrent-save
            # world-kill + db-kill/recover matrix, on BOTH engines.
            sa = a.get("sentinelsAliveAfterRecovery")
            sb = b.get("sentinelsAliveAfterRecovery")
            check("W7-sentinels", sa == 100 and sb == 100, f"A={sa} B={sb} (expected 100)")
            # The concurrent-save premise must hold on BOTH engines: the
            # kernel-level kill fired while the save was in flight (R2
            # A/B/E — a post-ack kill never enters the torn-write window
            # and must not be booked as DEC-02 evidence).
            ka = a.get("killFiredWhileSaveInFlight")
            kb = b.get("killFiredWhileSaveInFlight")
            check("W7-kill-mid-save", ka is True and kb is True,
                  f"A={ka} B={kb} (the kill must fire mid-save on both)")
            # R3 B: the save call must have died UNACKED (the kill preceded
            # the reply) and the recovery must have healed via ordinary
            # WAL/InnoDB recovery — a silent VACUUM-INTO rebuild on B or a
            # recovery without observed output on A is a divergence to
            # adjudicate, never a quiet pass.
            for bundle, tag in ((a, "A"), (b, "B")):
                died = bundle.get("saveDiedUnacked")
                check(f"W7-{tag}-save-died-unacked", died is True, f"saveDiedUnacked={died}")
                recover_payload = bundle.get("dbRecoverResult")
                start_payload = bundle.get("dbStartAfterRecover")
                check(f"W7-{tag}-recovery-payloads-present",
                      isinstance(recover_payload, dict) and isinstance(start_payload, dict),
                      "dbRecoverResult/dbStartAfterRecover must ride the evidence (R4 D)")
                payloads = json.dumps(recover_payload) + json.dumps(start_payload)
                check(f"W7-{tag}-no-silent-rebuild", "rebuilt" not in payloads,
                      "recovery payload mentions a rebuild (VACUUM INTO) - adjudicate, do not pass")
        # W5 soak floors: every step reached its bot target on both
        # servers (telemetry-compared, never row-diffed - bot generation
        # carries its own RNG state). A missing/empty soak or a zero
        # target is a corpus failure, never a silent skip (R1 E, R2 E).
        for bundle, tag in ((a, "A"), (b, "B")):
            steps = bundle.get("botSoak")
            if not isinstance(steps, list) or not steps:
                check(f"W5-{tag}-soak-present", False,
                      f"botSoak missing/empty ({type(steps).__name__})")
                continue
            for step in steps:
                samples = step.get("samples") or []
                peak = max((s.get("botsOnline") or 0) for s in samples) if samples else 0
                check(f"W5-{tag}-{step.get('profileId')}-ramp",
                      (step.get("target") or 0) > 0 and peak > 0
                      and peak >= step.get("target") - 2,
                      f"peak={peak} target={step.get('target')}")
        # W10 comparative bands: saveall-ack (<=1.5xA), world tick p99
        # (<=1.25xA), db probe delay (<=1.5xA), stop-drain (<=2xA — the
        # spec's fourth band, R2 A) - worst observed per server. Missing
        # or all-zero inputs FAIL (never silently skip); anchors floor at
        # 1 ms so a legitimately-sub-ms A sample cannot force B<=0.
        def worst_of(bundle, key):
            values = list(bundle.get(key) or [])
            return max(values) if values else None
        def worst_tick(bundle, sample_key):
            worst = 0
            for step in bundle.get("botSoak") or []:
                if isinstance(step, str):
                    continue
                for sample in step.get("samples") or []:
                    worst = max(worst, sample.get(sample_key) or 0)
            return worst or None
        # The saveall band uses ONLY real save acks; the concurrent-kill
        # elapsed is a different measurement and rides separately (R2 A).
        sa_ack, sb_ack = worst_of(a, "worldSaveAckMs"), worst_of(b, "worldSaveAckMs")
        check("W10-saveall-band",
              sa_ack is not None and sb_ack is not None
              and sb_ack <= max(sa_ack, 1) * 1.5,
              f"A={sa_ack}ms B={sb_ack}ms (<=1.5xA; kill-elapsed excluded)")
        ta, tb = worst_tick(a, "p99Ms"), worst_tick(b, "p99Ms")
        check("W10-worldtick-band",
              ta is not None and tb is not None and tb <= max(ta, 1) * 1.25,
              f"A={ta}ms B={tb}ms (<=1.25xA)")
        pa, pb = worst_tick(a, "dbProbeDelayMs"), worst_tick(b, "dbProbeDelayMs")
        check("W10-probe-band",
              pa is not None and pb is not None and pb <= max(pa, 1) * 1.5,
              f"A={pa}ms B={pb}ms (<=1.5xA)")
        def worst_drain(bundle):
            telemetry = bundle.get("telemetry") or {}
            values = list(telemetry.get("worldDrainMs") or [])
            return max(values) if values else None
        da, db = worst_drain(a), worst_drain(b)
        check("W10-drain-band",
              da is not None and db is not None and db <= max(da, 1) * 2,
              f"A={da}ms B={db}ms (<=2xA)")
        # W4 gameplay probes: the spec's stretch item — recorded skipped
        # by the runner, echoed loudly here.
        findings.append({"check": "W4-gameplay-probes", "ok": None,
                         "detail": "SKIPPED-STRETCH: the managed-addon scriptable "
                                   "surface (spec-marked stretch)"})

    return {"verdict": verdict, "findings": findings,
            "knownDifferenceLedger": KNOWN_DIFFERENCE_LEDGER,
            "rowCountCheckSummary": row_check_summary,
            "contentComparator": "REGISTERED-NOT-IMPLEMENTED (the W9 TSV leg)"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=("quick", "standard", "massive"), default="standard")
    parser.add_argument("--skip-build", action="store_true",
                        help="reuse the newest prior run's captured APKs (iteration)")
    args = parser.parse_args()

    run_id = _dt.datetime.now().strftime("%Y%m%d-%H%M%S") + f"-{args.profile}"
    out_dir = ROOT / "build" / "differential" / run_id
    out_dir.mkdir(parents=True, exist_ok=True)
    # The report exists BEFORE any stage can fail (P6.5 R2 E/F): build,
    # reuse, and emulator-spawn failures archive a FAIL report too.
    report = {"runId": run_id, "profile": args.profile, "verdict": "FAIL",
              "failingStage": None}
    try:
        for binary in (ADB, EMULATOR):
            if not Path(binary).is_file():
                raise RuntimeError(f"SDK binary missing: {binary} (set ANDROID_SDK_ROOT)")
        report["failingStage"] = "build"
        if args.skip_build:
            apks = reuse_prior_apks()
            report["buildMode"] = "reused"
            report["reusedFromRunId"] = apks.pop("reused_from", None)
        else:
            apks = build_apks()
            report["buildMode"] = "fresh"
            report["gradleCommands"] = apks.pop("gradle_commands")
        report["apkSha256"] = {tag: hashlib.sha256(apks[f"server_{tag}"]).hexdigest()
                               for tag in ("a", "b")}
        report["testsApkSha256"] = hashlib.sha256(apks["tests_a"]).hexdigest()
        # Code-era marker (R4 E): the orchestrator + runner source digests —
        # "current code" claims are mechanically checkable per report.
        report["codeEra"] = {
            name: hashlib.sha256((ROOT / path).read_bytes()).hexdigest()
            for name, path in (("orchestrator", "tools/run_differential_parity.py"),
                               ("runner", "android/app/src/androidTest/java/com/pocketrealm/database/DifferentialBarrageRunner.kt"))
        }
        if report["apkSha256"]["a"] == report["apkSha256"]["b"]:
            raise RuntimeError("identical server APKs (see build_apks)")

        # AVD snapshot discipline: cold boot the dedicated AVD (the spec's
        # sequential-runs default; snapshot restore is the optional
        # speedup). -no-snapshot ENFORCES the cold boot both ways: a stale
        # quickboot image left the device offline for a whole 600 s budget
        # once.
        report["failingStage"] = "emulator"
        emulator = subprocess.Popen(
            [str(EMULATOR), "-avd", AVD, "-no-window", "-no-audio", "-no-boot-anim",
             "-no-snapshot", "-accel", "on", "-memory", "4096"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        report["failingStage"] = "boot"
        wait_for_device(600)
        report["failingStage"] = "server-a"
        evidence_a = drive_server(apks["server_a"], apks["tests_a"], "a", out_dir, args.profile)
        report["failingStage"] = "server-b"
        evidence_b = drive_server(apks["server_b"], apks["tests_b"], "b", out_dir, args.profile)
        report["failingStage"] = None
        report.update({"providerModes": {"a": evidence_a.get("providerMode"),
                                         "b": evidence_b.get("providerMode")},
                       **parity_oracle(evidence_a, evidence_b, args.profile)})
    except Exception as failure:  # failure-path evidence discipline (R1 E)
        report["verdict"] = "FAIL"
        report["error"] = f"{type(failure).__name__}: {failure}"[:2000]
    finally:
        # Best-effort teardown on EVERY exception class (R3 D: a missing
        # adb path raises OSError, which is not a SubprocessError) — the
        # report write must always be reached.
        if report.get("failingStage") not in ("build", "emulator"):
            try:
                adb("emu", "kill", timeout=60, check=False)
            except Exception:
                pass
            try:
                emulator.wait(timeout=60)
            except Exception:
                pass

    (out_dir / "parity-report.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({k: report[k] for k in ("runId", "profile", "verdict")}, indent=2))
    return verdict_to_exit(report.get("verdict"))


if __name__ == "__main__":
    raise SystemExit(main())
