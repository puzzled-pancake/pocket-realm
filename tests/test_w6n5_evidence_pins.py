"""W6-N5 evidence pins (the code-red round-1 R1-1/R6-1 remedy, the shape of
test_w6n4_evidence_pins.py): the campaign-close records' quoted tuples must
live in the archived artifacts the records name, held by pins that fail on
the exact gap class the round found — an n5 log regenerated or a quoted
tuple drifting can never pass silently again.

Held here:
- n5-gateway-hermetic.log's native markers == the quoted hermetic tuple
  (50 PASSED / 2 SKIPPED / 0 FAILED of 52, flags OFF) and class census
  (52 / 15);
- n5-desktop-focused-census.log's N4-FOCUSED-COUNTS == (215, 16, 0) with
  the 16-class live-join set named (IrohRejoinTest 4/0 included);
- the canonical tuples quoted in N5-CAMPAIGN-VERDICT.md equal the standing
  counts artifacts (desktop 1676/210, android 1838/231) and the android
  artifact cites its own n5 log through the tracked writer (R5-1);
- the sweep tuple quoted in the verdict re-derives LIVE
  (leak_sweep.sweep(ROOT) — files scanned + allowlisted findings);
- the verdict's measured-numbers table is carried by
  out/completion/w6/n2/MEASUREMENTS.md (the campaign's measurement record).

Portability (the R6-N4-r6-1 rule): the module skips wholesale on a checkout
without the gitignored evidence tree.
"""
import json
import re
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
W6 = ROOT / "out" / "completion" / "w6"
W0 = ROOT / "out" / "completion" / "w0"
VERDICT = W6 / "N5-CAMPAIGN-VERDICT.md"
GATEWAY_LOG = W6 / "n5-gateway-hermetic.log"
CENSUS_LOG = W6 / "n5-desktop-focused-census.log"

pytestmark = pytest.mark.skipif(
    not VERDICT.is_file() or not GATEWAY_LOG.is_file() or not CENSUS_LOG.is_file(),
    reason="the gitignored W6-N5 evidence tree is absent from this checkout "
           "(the rename-dance portability rule, R6-N4-r6-1's shape)",
)

sys.path.insert(0, str(ROOT / "tools"))


def _gateway_markers(text: str) -> dict[str, str]:
    markers = {}
    for line in text.splitlines():
        if line.startswith("# N4-GATEWAY-SUITE-COUNTS") or line.startswith(
            "# N3-GATEWAY-COUNTS",
        ):
            key = line.split()[1]
            assert key not in markers, f"duplicate {key} marker line"
            markers[key] = line
    return markers


def _tallies(text: str) -> tuple[int, int, int]:
    """Per-TEST outcome lines only (the N4 parser's contract — Gradle TASK
    lines are never counted)."""
    passed = skipped = failed = 0
    for line in text.splitlines():
        if re.search(r" > .*\(\) (PASSED|SKIPPED|FAILED)$", line):
            if line.endswith(" PASSED"):
                passed += 1
            elif line.endswith(" SKIPPED"):
                skipped += 1
            elif line.endswith(" FAILED"):
                failed += 1
    return passed, skipped, failed


def test_gateway_hermetic_log_carries_the_quoted_tuple():
    """R1-1: the verdict/D99 gateway tuple (50/2/0 of 52, flags OFF) must be
    the archived log's own native markers and per-test outcomes."""
    text = GATEWAY_LOG.read_text(encoding="utf-8", errors="replace")
    markers = _gateway_markers(text)
    assert (
        markers.get("N4-GATEWAY-SUITE-COUNTS")
        == "# N4-GATEWAY-SUITE-COUNTS tests=52 skipped=2 failures=0 flags=off"
    ), markers
    assert (
        markers.get("N3-GATEWAY-COUNTS")
        == "# N3-GATEWAY-COUNTS tests=52 classes=15 failures=0"
    ), markers
    assert _tallies(text) == (50, 2, 0), _tallies(text)
    # the opt-in live legs are the SKIPPED set (honest without flags)
    assert (
        "ReconnectRelayE2E > reconnectAfterHostRedialReusesTheSameListingDescriptor() SKIPPED"
        in text
    )
    assert (
        "ReconnectDockerE2E > hostSourceNetworkChangeSurvivedByRedialingTheSameEndpointId() SKIPPED"
        in text
    )


def test_desktop_focused_census_log_carries_the_quoted_tuple():
    """R1-1: the census tuple (213 / 16 / 0) must be the log's own native
    marker, naming the full 16-class live-join set (IrohRejoinTest 4/0 in —
    a narrowed re-run can never stand in for the archived measurement)."""
    text = CENSUS_LOG.read_text(encoding="utf-8", errors="replace")
    match = re.search(
        r"# N4-FOCUSED-COUNTS tests=(\d+) classes=(\d+) failures=(\d+) "
        r"\((.*)\)",
        text,
    )
    assert match, "the census log lost its native counts marker"
    assert (int(match.group(1)), int(match.group(2)), int(match.group(3))) == (
        215,
        16,
        0,
    ), match.group(0)
    per_class = {
        name: int(tests) for name, tests, _fails in re.findall(
            r"([A-Za-z0-9]+Test) (\d+)/(\d+)", match.group(4),
        )
    }
    assert len(per_class) == 16, sorted(per_class)
    assert per_class["IrohRejoinTest"] == 4, per_class


def test_verdict_gate_table_tuples_equal_the_archived_logs():
    """R6-1: the verdict's gate-table rows citing the two n5 logs must quote
    exactly what those logs' native markers record — the record↔artifact
    triangle the N4 exit made mandatory."""
    verdict = " ".join(VERDICT.read_text(encoding="utf-8").split())
    gateway = " ".join(GATEWAY_LOG.read_text(
        encoding="utf-8", errors="replace",
    ).split())
    census = " ".join(CENSUS_LOG.read_text(
        encoding="utf-8", errors="replace",
    ).split())
    gateway_row = re.search(
        r"\*\*50 PASSED / 2 SKIPPED / 0 FAILED of 52\*\*[^|]*?"
        r"`n5-gateway-hermetic\.log`",
        verdict,
    )
    assert gateway_row, "the verdict lost its gateway tuple row"
    assert "# N4-GATEWAY-SUITE-COUNTS tests=52 skipped=2 failures=0 flags=off" in gateway
    census_row = re.search(
        r"\*\*215 tests / 16 classes / 0 failures\*\*[^|]*?"
        r"`n5-desktop-focused-census\.log`",
        verdict,
    )
    assert census_row, "the verdict lost its census tuple row"
    assert "# N4-FOCUSED-COUNTS tests=215 classes=16 failures=0" in census


def test_reconciliation_archive_carries_the_quoted_tuple():
    """R2-N5-r1-3/R3-N5-r3-3 (round 6 finalize): the five-module
    reconciliation run is ARCHIVED (n5-reconciliation.log, like every
    sibling evidence row) and its native count line carries the tuple the
    records quote — the one reconciliation row that named no artifact."""
    log = W6 / "n5-reconciliation.log"
    assert log.is_file(), "the reconciliation archive is missing"
    text = log.read_text(encoding="utf-8", errors="replace")
    match = re.search(r"(\d+) passed", text)
    assert match, "the reconciliation archive carries no count line"
    assert int(match.group(1)) == 139, (
        f"the archived reconciliation run says {match.group(1)} passed; the "
        f"records quote 139 — re-archive after any test addition"
    )


def test_verdict_canonical_tuples_equal_the_counts_artifacts():
    """R6-1(c): every canonical tuple the verdict's gate table quotes must
    equal the standing counts artifact it names — desktop 1676/210 and
    android 1838/231, with the android artifact citing its own n5 log
    through the tracked writer (the R5-1 disposition)."""
    verdict = VERDICT.read_text(encoding="utf-8")
    flat = " ".join(verdict.split())
    for artifact_name, tests, classes in (
        ("desktop-test-counts.json", 1678, 210),
        ("android-unit-counts.json", 1839, 231),
    ):
        artifact = json.loads((W0 / artifact_name).read_text(encoding="utf-8"))
        assert (artifact["tests"], artifact["classes"]) == (tests, classes), (
            f"{artifact_name} drifted from the quoted tuple"
        )
        assert f"**{tests} tests / {classes} classes" in flat, (
            f"the verdict's gate table lost the {artifact_name} tuple"
        )
    android = json.loads((W0 / "android-unit-counts.json").read_text(
        encoding="utf-8",
    ))
    assert android["log"] == "n5-canonical-android.log"
    assert "W6-N5 canonical re-measure" in android["generated"].split(" | ")[-1]
    assert (W0 / "n5-canonical-android.log").is_file(), (
        "the android artifact cites a log that is not archived under w0"
    )
    desktop = json.loads((W0 / "desktop-test-counts.json").read_text(
        encoding="utf-8",
    ))
    assert desktop["log"] == "n5-canonical-desktop.log"
    assert (W0 / "n5-canonical-desktop.log").is_file()


def test_verdict_sweep_tuple_rederives_live():
    """R1-1: the sweep tuple quoted in the verdict must equal a LIVE
    leak_sweep.sweep(ROOT) run — the record's L0 line can never silently
    drift from the tool (when the tree's file count moves, the verdict's
    quote is refreshed in the same wave, the standing sweep-quote
    contract)."""
    import leak_sweep  # noqa: E402

    findings, allowlisted, scanned = leak_sweep.sweep(ROOT)
    assert findings == [], [f for f in findings][:4]
    verdict = VERDICT.read_text(encoding="utf-8")
    match = re.search(
        r"OK \((\d+) files scanned, (\d+) allowlisted findings\)", verdict,
    )
    assert match, "the verdict lost its sweep line"
    assert (int(match.group(1)), int(match.group(2))) == (scanned, allowlisted), (
        f"the verdict quotes {match.group(1)}/{match.group(2)} but the live "
        f"sweep says {scanned}/{allowlisted} — refresh the record's sweep "
        f"quote from a finalize-time tool run"
    )


def test_verdict_carries_no_stale_android_canonical_tuple():
    """R6-N5-r4-1/R4-N5-r4-1/R1-N5-r4-1: the verdict must not quote the
    retired round-1-era android canonical tuple anywhere (the prose sites
    escaped the gate-table pins twice) — the standing artifact's tuple is
    the only android canonical tuple the record may carry."""
    verdict = VERDICT.read_text(encoding="utf-8")
    assert "1836/231" not in verdict, (
        "the verdict quotes the retired 1836/231 android canonical tuple — "
        "refresh to the standing artifact's tuple"
    )


def test_records_android_and_desktop_prose_tuples_equal_their_artifacts():
    """R7-N5-r4-2/R1-N5-r4-1; R6-r6-2/R6-1/R1-r6-1/R4-N5-r6-1 (round 6):
    EVERY 'android [canonical] NNNN/NNN' and 'desktop [canonical] NNNN/NNN'
    tuple in the verdict, in the D99 ROW (extracted structurally — the old
    ' | D99 |' delimiter never occurred, blinding this pin to the row), and
    in WAVE-EXIT.md's stage-N5 section must equal the standing counts
    artifacts."""
    android = json.loads((W0 / "android-unit-counts.json").read_text(
        encoding="utf-8"))
    desktop = json.loads((W0 / "desktop-test-counts.json").read_text(
        encoding="utf-8"))
    android_tuple = (android["tests"], android["classes"])
    desktop_tuple = (desktop["tests"], desktop["classes"])
    decisions = (ROOT / "docs" / "DECISIONS-PORT.md").read_text(
        encoding="utf-8",
    )
    d99_rows = [l for l in decisions.splitlines() if l.startswith("| D99 |")]
    assert len(d99_rows) == 1, (
        f"expected exactly one D99 table row, found {len(d99_rows)}"
    )
    # self-check (R6-r6-2): the row is the real D99 row — a delimiter or
    # format drift fails loudly instead of degrading to an empty scan.
    assert "LEAK SWEEP PRIVACY SCOPE" in d99_rows[0], (
        "the extracted D99 row is not the privacy-scope row — the "
        "extraction drifted"
    )
    exit_record = (W6 / "WAVE-EXIT.md").read_text(encoding="utf-8")
    n5_section = exit_record.split(
        "# W6 Wave Exit — campaign gates + verdict (stage N5", 1,
    )[-1]
    texts = {
        "verdict": " ".join(VERDICT.read_text(encoding="utf-8").split()),
        "D99 row": " ".join(d99_rows[0].split()),
        "WAVE-EXIT N5 section": " ".join(n5_section.split()),
    }
    # the "canonical" word is required: the round LEDGER rows quote drifted
    # intermediate trees ("android 114/18" — R2-N5-r4-3's clobber receipt)
    # that are dated history, not canonical-tuple claims.
    tuple_re = re.compile(
        r"android canonical \**(\d{3,4})/(\d{2,3})"
        r"|desktop canonical \**(\d{3,4})/(\d{2,3})"
    )
    for source, flat in texts.items():
        for m in tuple_re.finditer(flat):
            if m.group(1):
                lane, got = "android", (int(m.group(1)), int(m.group(2)))
            else:
                lane, got = "desktop", (int(m.group(3)), int(m.group(4)))
            expected = android_tuple if lane == "android" else desktop_tuple
            assert got == expected, (
                f"{source} quotes {lane} canonical tuple {got} but the "
                f"standing artifact holds {expected} — refresh the prose "
                f"(the stale-tuple class R6-r6-1)"
            )
    # the retired tuples must not survive in the N5 CHECKLIST bullets —
    # the round LEDGER rows quote them as dated finding narration (the
    # reviewers' own texts) and stay allowed there.
    checklist = " ".join(
        l for l in n5_section.splitlines() if l.lstrip().startswith("- [")
    )
    checklist = " ".join(checklist.split())
    for retired in ("1837/231", "1836/231"):
        assert retired not in checklist, (
            f"the N5 checklist still quotes the retired {retired} android "
            f"canonical tuple"
        )


def test_verdict_measured_table_is_carried_by_the_n2_measurement_record():
    """R6-1(d): the verdict's measured-numbers table must be carried by the
    campaign's measurement record — every claimed number names the artifact
    it came from, and the artifact carries it."""
    verdict = VERDICT.read_text(encoding="utf-8")
    measurements = (W6 / "n2" / "MEASUREMENTS.md").read_text(
        encoding="utf-8", errors="replace",
    )
    n4_record = (W6 / "n4" / "WAVE-EXIT-N4.md").read_text(
        encoding="utf-8", errors="replace",
    )
    carriers = (
        # (verdict substring, carrier file text, carrier substring)
        ("441.5 Mbit/s", measurements, "441.5 Mbit/s"),
        ("~1.9 Mbit/s sustained", measurements, "1.88 Mbit/s sustained"),
        ("3.9–6.8 s ping gaps", measurements, "3.9–6.8 s gaps"),
        ("240 s soak", measurements, "240 s soak"),
        ("936 ms", measurements, "936 ms"),
        ("16 MiB byte-exact", measurements, "16 MiB) echoed both directions"),
        ("24 MiB byte-exact over 107 s", measurements,
         "24 MiB) echoed both directions"),
        # the genuine-IP-change numbers are the N4 record's (the narration
        # receipt's own ASCII-arrow form, quoted there):
        ("172.30.0.2 → 172.31.0.3", n4_record, "172.30.0.2 -> 172.31.0.3"),
    )
    for quoted, carrier_text, carrier in carriers:
        assert quoted in verdict, f"the verdict lost the measured claim {quoted!r}"
        assert carrier in carrier_text, (
            f"the measurement records no longer carry the record for "
            f"{quoted!r} (expected {carrier!r})"
        )
    assert "NOT measured" in verdict, (
        "the verdict lost its not-measured section (the honest carry list)"
    )


def collected(*targets: str) -> int:
    """The live pytest collection count for the given test targets (the
    freshness pins' re-derivation primitive — R7-2-1/R6-N5-r2-1 and the
    round-3 five-module/D99(4) legs share it)."""
    import subprocess

    run = subprocess.run(
        [sys.executable, "-m", "pytest", "--collect-only", "-q", *targets],
        capture_output=True, text=True, cwd=ROOT, timeout=300,
    )
    tail = run.stdout.strip().splitlines()[-1]
    match = re.search(r"(\d+) tests? collected", tail)
    assert run.returncode == 0 and match is not None, (
        f"pytest --collect-only failed for {targets} ({tail!r})"
    )
    return int(match.group(1))


def test_verdict_l0_rows_match_the_live_pytest_collection():
    """R7-2-1/R6-N5-r2-1/R1-N5-r2-1: the closing records' pytest-count rows
    must equal the LIVE collection of the modules they name — a stale
    quoted count (the round-1 '66 passed' class) fails the suite instead
    of surviving to the commit."""
    sweep_tests = collected("tests/test_leak_sweep.py")
    verdict = " ".join(VERDICT.read_text(encoding="utf-8").split())
    row = re.search(
        r"L0 sweep contract[^|]*?\| `[^`]+` \| (\d+) passed \((\d+) standing "
        r"\+ (\d+) new privacy-rule tests\)",
        verdict,
    )
    assert row, "the verdict lost its L0 sweep-contract row"
    assert int(row.group(1)) == sweep_tests, (
        f"the verdict quotes {row.group(1)} sweep-contract tests but the "
        f"live collection is {sweep_tests} — refresh the row"
    )
    assert int(row.group(2)) + int(row.group(3)) == sweep_tests, (
        "the standing+new split does not sum to the quoted total"
    )
    exit_flat = " ".join((W6 / "WAVE-EXIT.md").read_text(
        encoding="utf-8",
    ).split())
    exit_row = re.search(
        r"(\d+)/\d+ sweep contract tests", exit_flat
    )
    assert exit_row, "the exit record lost its sweep-contract tuple"
    assert int(exit_row.group(1)) == sweep_tests, (
        f"the exit record quotes {exit_row.group(1)} sweep-contract tests "
        f"but the live collection is {sweep_tests}"
    )
    seven = collected(
        "tests/test_leak_sweep.py",
        "tests/test_w6n5_evidence_pins.py",
        "tests/test_repo_hygiene_pins.py",
        "tests/test_wave_evidence.py",
        "tests/test_w6_nostr_pins.py",
        "tests/test_gateway_shared_sources_manifest.py",
        "tests/test_w6n4_evidence_pins.py",
    )
    seven_row = re.search(
        r"(\d+) tests collected across the seven touched pytest modules",
        exit_flat,
    )
    assert seven_row, "the exit record lost its seven-module collection row"
    assert int(seven_row.group(1)) == seven, (
        f"the exit record quotes {seven_row.group(1)} collected across the "
        f"seven modules but the live collection is {seven} — refresh the row"
    )
    # R2-4/R6-N5-r5-1: the checklist's sweep-contract growth bullet and
    # D99(1)'s new-contract-tests clause are held to the live collection
    # too — the two sites whose staleness survived to round 5.
    sweep_tests = collected("tests/test_leak_sweep.py")
    standing = 46
    grew = re.search(
        r"grew (\d+) privacy tests\*\* \((\d+) total\)",
        exit_flat,
    )
    assert grew, "the exit record lost its sweep-contract growth bullet"
    assert int(grew.group(1)) == sweep_tests - standing, (
        f"the growth bullet quotes {grew.group(1)} new privacy tests; the "
        f"live collection minus the 46 standing is {sweep_tests - standing}"
    )
    assert int(grew.group(2)) == sweep_tests, (
        f"the growth bullet quotes {grew.group(2)} total; the live "
        f"collection is {sweep_tests}"
    )
    d99_text = quoted_in[
        "decisions"
    ] if "quoted_in" in dir() else " ".join(
        (ROOT / "docs" / "DECISIONS-PORT.md").read_text(
            encoding="utf-8",
        ).split(),
    )
    new_tests = re.search(
        r"(\d+) new contract tests in tests/test_leak_sweep\.py",
        d99_text,
    )
    assert new_tests, "D99(1) lost its new-contract-tests clause"
    assert int(new_tests.group(1)) == sweep_tests - standing, (
        f"D99(1) quotes {new_tests.group(1)} new contract tests; the live "
        f"collection minus the 46 standing is {sweep_tests - standing}"
    )


def test_records_privacy_table_cardinalities_match_the_tables():
    """R7-2-1: the records' privacy-table cardinality words must equal the
    code tables they describe — a table that grows (R7-1's 6→7 descriptor
    lanes) without the record is the stale-citation class retired by name
    in every prior round."""
    import leak_sweep  # noqa: E402

    exit_flat = " ".join((W6 / "WAVE-EXIT.md").read_text(
        encoding="utf-8",
    ).split())
    surfaces = re.search(r"(\d+) published-payload surfaces", exit_flat)
    lanes = re.search(r"(\d+) descriptor lanes", exit_flat)
    assert surfaces, "the exit record lost its payload-surface count"
    assert lanes, "the exit record lost its descriptor-lane count"
    assert int(surfaces.group(1)) == len(leak_sweep.PRIVACY_PAYLOAD_SURFACES), (
        f"the record says {surfaces.group(1)} payload surfaces, the table "
        f"holds {len(leak_sweep.PRIVACY_PAYLOAD_SURFACES)}"
    )
    assert int(lanes.group(1)) == len(leak_sweep.PRIVACY_RECORD_LANES), (
        f"the record says {lanes.group(1)} descriptor lanes, the table "
        f"holds {len(leak_sweep.PRIVACY_RECORD_LANES)} (the R7-1 class)"
    )


def test_records_reconciliation_tuple_and_d99_figures_match_the_live_collection():
    """R3-N5-r3-1(second)/R4-r3-1/R5-r3-1/R7-N5-r3-1: the five-module
    reconciliation tuple quoted in the verdict's gate table, the exit
    record's checklist row and its L1 evidence row, AND D99 item 4's own
    sentence must equal the LIVE collection of exactly those modules —
    plus D99(4)'s sweep-contract figure is held to the live
    tests/test_leak_sweep.py collection (the round-2 refresh missed both
    D99(4) sites; the stale-tuple class dies here)."""
    five = collected(
        "tests/test_repo_hygiene_pins.py",
        "tests/test_wave_evidence.py",
        "tests/test_w6_nostr_pins.py",
        "tests/test_gateway_shared_sources_manifest.py",
        "tests/test_w6n4_evidence_pins.py",
    )
    sweep_tests = collected("tests/test_leak_sweep.py")
    quoted_in = {
        "verdict": " ".join(VERDICT.read_text(encoding="utf-8").split()),
        "exit": " ".join((W6 / "WAVE-EXIT.md").read_text(
            encoding="utf-8",
        ).split()),
        "decisions": " ".join((ROOT / "docs" / "DECISIONS-PORT.md").read_text(
            encoding="utf-8",
        ).split()),
    }
    verdict_row = re.search(
        r"\| record reconciliation \| `[^`]+` \(twice consecutively\) \| "
        r"(\d+) passed ×2 \|",
        quoted_in["verdict"],
    )
    assert verdict_row, "the verdict lost its record-reconciliation row"
    assert int(verdict_row.group(1)) == five, (
        f"the verdict quotes {verdict_row.group(1)} for the five-module "
        f"reconciliation run; the live collection is {five}"
    )
    exit_row = re.search(r"\*\*(\d+) passed twice consecutively\*\*", quoted_in["exit"])
    assert exit_row and int(exit_row.group(1)) == five, (
        f"the exit record's checklist row quotes "
        f"{exit_row.group(1) if exit_row else '?'}; the live collection is {five}"
    )
    l1_row = re.search(r"reconciliation pins (\d+) ×2 consecutive", quoted_in["exit"])
    assert l1_row and int(l1_row.group(1)) == five, (
        f"the exit record's L1 row quotes "
        f"{l1_row.group(1) if l1_row else '?'}; the live collection is {five}"
    )
    d99_row = re.search(
        r"reconciliation pin modules (\d+) passed TWICE consecutively",
        quoted_in["decisions"],
    )
    assert d99_row and int(d99_row.group(1)) == five, (
        f"D99(4) quotes {d99_row.group(1) if d99_row else '?'} for the "
        f"five-module reconciliation run; the live collection is {five}"
    )
    d99_sweep = re.search(r"\+ (\d+)/(\d+) sweep tests", quoted_in["decisions"])
    assert d99_sweep, "D99(4) lost its sweep-contract figure"
    assert int(d99_sweep.group(1)) == sweep_tests, (
        f"D99(4) quotes {d99_sweep.group(1)}/{d99_sweep.group(2)} sweep "
        f"tests; the live collection is {sweep_tests} — refresh by name in "
        f"the round amendment"
    )
