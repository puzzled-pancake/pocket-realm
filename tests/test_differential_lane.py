"""P6.5 lane tripwires.

1. The cross-ABI seed-pin equality tripwire (Part 2 P6.5 build
   prerequisite step 2): the seed .sqlz assets are ABI-INDEPENDENT bytes
   (host-generated transcripts, digest-bound to the same append-only
   baseline) - the x86_64 sibling's seed pins MUST equal the arm64
   sibling's byte-for-byte. Skips LOUDLY until the x86_64 sibling exists
   (it is created by P6.5 step 2's build), so the first differential
   assembly is born gated rather than gated-after.
2. The driver<->Gradle sibling-lockfile naming parity (R1 F1's class,
   made mechanical): build.gradle.kts's sqlite branch must mirror the
   driver's `-sqlite.json` naming for BOTH ABIs.
3. The differentialTestLane Gradle allowance stays debug-only and
   property-paired (static pins).
"""

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SIBLINGS = {
    "arm64-v8a": ROOT / "schemas" / "realm-runtime-lockfile-arm64-v8a-sqlite.json",
    "x86_64": ROOT / "schemas" / "realm-runtime-lockfile-sqlite.json",
}


def test_cross_abi_seed_pins_are_byte_identical() -> None:
    arm = json.loads(SIBLINGS["arm64-v8a"].read_text(encoding="utf-8"))
    x86 = SIBLINGS["x86_64"]
    if not x86.is_file():
        # LOUD skip: the x86_64 sibling is created by the P6.5 build step.
        import pytest
        pytest.skip("x86_64 sqlite sibling lockfile not built yet (P6.5 step 2)")
    pins_a = arm.get("seed_transcripts")
    pins_b = json.loads(x86.read_text(encoding="utf-8")).get("seed_transcripts")
    assert pins_a == pins_b, "cross-ABI seed pins diverged - the transcripts are ABI-independent bytes"


def test_sibling_lockfile_naming_parity_between_driver_and_gradle() -> None:
    driver = (ROOT / "tools" / "build_o09_realm_runtime.py").read_text(encoding="utf-8")
    gradle = (ROOT / "android" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    # Driver: the sibling is the base lockfile name with -sqlite inserted.
    assert 'LOCKFILE.name.replace(".json", "-sqlite.json")' in driver
    # Gradle: BOTH ABI spellings must appear (the x86_64 no-suffix special
    # case + the arm64 suffixed form).
    assert "realm-runtime-lockfile-sqlite.json" in gradle
    assert "realm-runtime-lockfile-$selectedAbi-sqlite.json" in gradle
    assert gradle.count("realm-runtime-lockfile-sqlite.json") >= 1


def test_differential_lane_allowance_is_debug_only_and_paired() -> None:
    gradle = (ROOT / "android" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    assert 'providers.gradleProperty("differentialTestLane").isPresent' in gradle
    # The shipping refusal survives: sqliteProvider+x86_64 still refuses
    # WITHOUT the lane property (the I-114 family stays intact).
    refusal = re.search(
        r'if \(sqliteProvider && pocketAbi != "arm64-v8a" && !differentialTestLane\)', gradle)
    assert refusal, "the arm64-first shipping refusal must survive the lane escape"
    assert "-PdifferentialTestLane permits debug-type assemblies only" in gradle
    assert "pass both properties together" in gradle
    # The debug-only invariant must not depend on how the task was spelled:
    # aggregate requests (build/assemble/bundle/...) can resolve release
    # work and are refused too (P6.5 R1 F + R2 C/F: qualified names keep
    # their last path segment; Needed/Dependents aggregates included).
    assert "it.substringAfterLast(':')" in gradle, \
        "the aggregate refusal must match the LAST path segment (qualified spellings)"
    assert 'name.equals("build", ignoreCase = true)' in gradle, \
        "the aggregate-task refusal must stay in the lane allowance"
    assert 'name.equals("buildDependents", ignoreCase = true)' in gradle
    assert 'name.equals("test", ignoreCase = true)' in gradle
    assert 'name.equals("lint", ignoreCase = true)' in gradle
    assert 'name.equals("assembleAndroidTest", ignoreCase = true)' in gradle
    assert "aggregate " in gradle and "release-type work" in gradle


def test_known_difference_ledger_is_append_only_shaped() -> None:
    sys_path = ROOT / "tools" / "run_differential_parity.py"
    text = sys_path.read_text(encoding="utf-8")
    seeded = re.findall(r'"id": "([A-Z0-9-]+)", "origin": "([^"]+)"', text)
    ids = [i for i, _ in seeded]
    assert len(ids) == len(set(ids)), "ledger ids must be unique"
    # The spec's five seeded classes, all present:
    for expected in ("NOCASE-ASCII", "DECIMAL-REAL", "DATETIME-WALLCLOCK",
                     "AUTOINCREMENT-SEQ", "RUN-TIMESTAMPS"):
        assert expected in ids, f"the known-difference ledger lost {expected}"


# ---- the parity oracle's verdict machinery (P6.5 R2 E: the honesty
# discipline gets its own mechanical tests — no emulator required) ----

import importlib.util

_ORACLE_SPEC = importlib.util.spec_from_file_location(
    "run_differential_parity", ROOT / "tools" / "run_differential_parity.py")
_ORACLE = importlib.util.module_from_spec(_ORACLE_SPEC)
_ORACLE_SPEC.loader.exec_module(_ORACLE)


def _bundle(profile="quick", gated=False, provider="SQLITE", **overrides):
    """A minimal well-formed evidence bundle; the quick shape by default.
    The export slice matches PINNED_REALMD_SLICE + 64 character tables so
    the shape gates pass for well-formed pairs."""
    realmd = {name: {"rows": 0} for name in _ORACLE.PINNED_REALMD_SLICE}
    characters = {f"t{i:02d}": {"rows": 0} for i in range(_ORACLE.PINNED_CHARACTER_TABLES)}
    if provider == "SQLITE":
        dump = {"source": "sqlite-rowcount-emitter",
                "rowCounts": {"classicrealmd": {k: v["rows"] for k, v in realmd.items()},
                              "classiccharacters": {k: v["rows"] for k, v in characters.items()}}}
    else:
        dump = {"source": "mariadb-outfile-staging",
                "tables": {"classicrealmd": realmd, "classiccharacters": characters}}
    bundle = {
        "profile": profile, "providerMode": provider, "sqliteCapable": provider == "SQLITE",
        "revisionsVerified": True, "bootWallMs": 30000,
        "dump": dump,
        "revisionState": {"migrationManifestCount": 412, "migrationSealedCount": 412,
                          "migrationsCurrent": True},
        "dirtyKillRecovered": True,
        "quickRecoverResult": {"ok": True, "recovered": True},
        "telemetry": {"bootWallMs": 30000, "saveWaves": [], "lastStatus": {}},
    }
    if profile != "quick":
        bundle["worldLeg"] = {"gated": gated}
        if gated:
            bundle["accounts"] = "SKIPPED:game-data-gate"
            bundle["botSoak"] = "SKIPPED:game-data-gate"
    bundle.update(overrides)
    return bundle


def _pair(profile="quick", gated=False):
    """A well-formed Server A (MariaDB) / Server B (SQLite) pair."""
    return _bundle(profile, gated, "MARIADB"), _bundle(profile, gated, "SQLITE")


def _failed(report):
    return [f for f in report["findings"] if f["ok"] is False]


def test_oracle_fails_standard_flagged_quick_corpus() -> None:
    """I-142's regression pin: quick-shaped evidence on a standard request
    FAILs on the corpus gates — never exit 0 as a manufactured PASS."""
    report = _ORACLE.parity_oracle(_bundle("quick"), _bundle("quick"), "standard")
    failed = {f["check"] for f in _failed(report)}
    assert {"W0-A-corpus", "W0-B-corpus", "W0-A-worldleg-present", "W0-B-worldleg-present"} <= failed
    assert report["verdict"] == "FAIL"


def test_oracle_gated_run_caps_only_a_passing_verdict() -> None:
    """PASS-GATED never masks a FAIL (DEC-10 honesty): a gated bundle with
    a failing check stays FAIL; a clean gated pair becomes PASS-GATED with
    every world leg SKIPPED-GATED and W4 SKIPPED-STRETCH."""
    clean = _ORACLE.parity_oracle(*_pair("standard", gated=True), "standard")
    assert clean["verdict"] == "PASS-GATED"
    checks = {f["check"]: f["ok"] for f in clean["findings"]}
    assert checks["W2-account-barrage"] is None and checks["W4-gameplay-probes"] is None
    dirty_a, dirty_b = _pair("standard", gated=True)
    dirty_b = dict(dirty_b, providerMode="MARIADB")  # a provider regression under gating
    dirty = _ORACLE.parity_oracle(dirty_a, dirty_b, "standard")
    assert dirty["verdict"] == "FAIL"  # the W0 provider gate fired under gating


def test_oracle_fails_asymmetric_gate_as_corpus_divergence() -> None:
    """R2 A/D: one server gating while the other executed the world corpus
    is corpus divergence — FAIL, never PASS-GATED."""
    report = _ORACLE.parity_oracle(_bundle("standard", gated=True, provider="MARIADB"),
                                    _bundle("standard", gated=False), "standard")
    assert "W0-gate-symmetry" in {f["check"] for f in _failed(report)}
    assert report["verdict"] == "FAIL"


def test_oracle_requires_none_free_row_counts() -> None:
    """R2 E: an exporter record with no rows key is a FAIL, not None==None."""
    a, b = _pair("quick")
    a["dump"]["tables"]["classicrealmd"]["account"] = {"sha256": "x"}  # rows key dropped
    b["dump"]["rowCounts"]["classicrealmd"]["account"] = None
    report = _ORACLE.parity_oracle(a, b, "quick")
    assert any(f["check"] == "rows:classicrealmd.account" for f in _failed(report))


def test_verdict_to_exit_mapping_is_pinned() -> None:
    """R3 C/E: the DEC-10 exit contract is mechanical — PASS exits 0,
    PASS-GATED exits 2 (never satisfiable as a standard PASS), FAIL 1."""
    assert _ORACLE.verdict_to_exit("PASS") == 0
    assert _ORACLE.verdict_to_exit("PASS-GATED") == 2
    assert _ORACLE.verdict_to_exit("FAIL") == 1
    assert _ORACLE.verdict_to_exit(None) == 1


def test_oracle_gated_skip_surface_is_exactly_pinned() -> None:
    """R3 E: the loud-skip surface cannot erode quietly — a clean gated
    standard pair emits EXACTLY the 10 SKIPPED-GATED checks plus the one
    W4 SKIPPED-STRETCH, and nothing else is skipped."""
    report = _ORACLE.parity_oracle(*_pair("standard", gated=True), "standard")
    skipped = {f["check"] for f in report["findings"] if f["ok"] is None}
    assert skipped == {
        "W2-account-barrage", "W3-character-persistence", "W5-bot-soak",
        "W6-world-saves", "W7-world-kill-sentinels", "W7-kill-mid-save",
        "W10-saveall-band", "W10-worldtick-band", "W10-probe-band",
        "W10-drain-band", "W4-gameplay-probes",
    }
    assert report["verdict"] == "PASS-GATED"


def test_row_count_summary_self_quantifies() -> None:
    """R3 C: the rowCountCheckSummary key cannot silently report {0, 0}."""
    a, b = _pair("quick")
    report = _ORACLE.parity_oracle(a, b, "quick")
    assert report["rowCountCheckSummary"] == {"total": 69, "nonZero": 0}
    a["dump"]["tables"]["classicrealmd"]["account"] = {"rows": 4}
    b["dump"]["rowCounts"]["classicrealmd"]["account"] = 4
    report = _ORACLE.parity_oracle(a, b, "quick")
    assert report["rowCountCheckSummary"] == {"total": 69, "nonZero": 1}


def test_oracle_inspects_quick_recover_payload() -> None:
    """R5 A/B/D: the quick leg's recover payload is presence-pinned and a
    silent rebuild fails — the only recovery payload a gated host executes."""
    a, b = _pair("quick")
    a.pop("quickRecoverResult"); b.pop("quickRecoverResult")
    report = _ORACLE.parity_oracle(a, b, "quick")
    assert "W7-A-quick-recover-payload" in {f["check"] for f in _failed(report)}
    a["quickRecoverResult"] = {"ok": True, "recovered": True}
    b["quickRecoverResult"] = {"ok": True, "recovered": True}
    report = _ORACLE.parity_oracle(a, b, "quick")
    assert report["verdict"] == "PASS"
    b["quickRecoverResult"] = {"ok": True, "integrityGate": {"classicmangos": "rebuilt"}}
    report = _ORACLE.parity_oracle(a, b, "quick")
    assert report["verdict"] == "FAIL"
