"""W6-N4 code-red round-1 evidence pins (R1-1, R1-2, R2-N4-r1, R4-1,
R4-2, R5-1, R6-N4-r1-2, R6-N4-r1-3, R7-N4-1, R7-N4-2).

The round's dominant class: the exit record cited measurements that the
cited artifacts did not carry (test stdout gradle never captures; the
redial joiner's container log destroyed before archiving; focused logs
without counts lines). These pins hold every WAVE-EXIT-N4 citation to
its archived artifact:

- the docker narration file carries the before/after host IPs + OUTCOME;
- every archived container log is REAL container output (a
  "No such container" daemon error is a bug, never evidence);
- the focused logs carry their own N4-FOCUSED-COUNTS lines;
- the android canonical-tree quote equals the FULL counts artifact
  (app + llm sum), never a module slice.
"""
import json
import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
N4 = ROOT / "out/completion/w6/n4"
EXIT = N4 / "WAVE-EXIT-N4.md"

# R6-N4-r6-1: the evidence tree is GITIGNORED (an owner-machine
# artifact) — on a fresh clone or CI the whole module must SKIP, never
# go red (the graceful-skip convention test_w6_nostr_pins.py
# established for the same situation). Every test here reads artifacts
# under out/completion/w6*, so one module-level gate covers them.
pytestmark = pytest.mark.skipif(
    not (ROOT / "out/completion/w6").is_dir(),
    reason="the gitignored out/completion/w6 evidence tree is not "
           "present on this checkout (owner-machine artifact)",
)


def _newest(patterns: list[str]) -> Path | None:
    matches: list[Path] = []
    for pattern in patterns:
        matches.extend(N4.glob(pattern))
    return sorted(matches)[-1] if matches else None


def test_docker_narration_carries_the_cited_measurements():
    """R1-1/R4-1/R5-1/R6-N4-r1-2/R7-N4-1: the docker leg's headline
    tuple (before/after host IPs, OUTCOME=) lives in the ARCHIVED
    narration file — the console log is not evidence."""
    narration = _newest([
        "n4-docker-narration-*.txt",
        "secrets-*/n4-docker-narration-*.txt",
    ])
    assert narration is not None, "no docker narration archived"
    text = narration.read_text(encoding="utf-8")
    changed = re.search(
        r"host network changed (\d+\.\d+\.\d+\.\d+) -> (\d+\.\d+\.\d+\.\d+)",
        text,
    )
    assert changed, f"no host-ip-change line in {narration.name}: {text}"
    before, after = changed.groups()
    assert before != after, "the host source IP must have changed"
    assert "OUTCOME=" in text, "the run outcome line is missing"
    assert "reconnect WORKING" in text, "the reconnect receipt line is missing"


def test_archived_container_logs_are_real_output_not_daemon_errors():
    """R1-2/R2-N4-r1/R7-N4-2: every archived container log is REAL
    container output — non-empty, carrying protocol lines, and NEVER the
    'No such container' daemon error (which means the log was collected
    after removal)."""
    secrets_dirs = sorted(N4.glob("secrets-*"))
    assert secrets_dirs, "no secrets-* archive dirs present"
    checked = 0
    for secrets in secrets_dirs:
        for log in sorted(secrets.glob("pr-n4-*.log")):
            text = log.read_text(encoding="utf-8", errors="replace")
            assert text.strip(), f"{log.name} is empty"
            assert "Error response from daemon" not in text, (
                f"{log.name} holds a daemon error, not container output — "
                "the log was collected after the container was removed"
            )
            assert ("PR_GATEWAY_" in text) or ("FAKESTACK_READY" in text), (
                f"{log.name} carries no protocol line: {text[:120]!r}"
            )
            checked += 1
    assert checked >= 3, f"expected host+join1+join2 logs, found {checked}"


def test_the_redial_joiner_log_carries_its_ready_line():
    """R1-2/R7-N4-2's exact demand: the redialed joiner (join2) — the
    container the tier-2 proof is ABOUT — has its own PR_GATEWAY_READY
    archived."""
    secrets_dirs = sorted(N4.glob("secrets-*"))
    assert secrets_dirs, "no secrets-* archive dirs present"
    matches = sorted(secrets_dirs[-1].glob("pr-n4-*-join2.log"))
    assert matches, "no join2 log archived"
    text = matches[-1].read_text(encoding="utf-8", errors="replace")
    assert "PR_GATEWAY_READY" in text, (
        "the redialed joiner's READY line (the reconnect leg's own "
        f"receipt) is not archived: {text[:200]!r}"
    )


def test_relay_narration_carries_descriptor_stability_and_outcome():
    """The relay leg's narration file carries the descriptor-stability
    receipt and the outcome (the R4-4 durability fix)."""
    narration = _newest(["n4-relay-narration-*.txt"])
    assert narration is not None, "no relay narration archived"
    text = narration.read_text(encoding="utf-8")
    assert "SAME node id + SAME home relay" in text
    assert "OUTCOME=reconnect WORKING" in text
    assert "keep-alive loss lane" in text


def test_relay_payloads_archive_the_loss_lane():
    """R4-4: the archived payload capture carries the joiner's verbatim
    loud-loss line (a red run would carry its own verbatim failure)."""
    payloads = _newest(["n4-relay-payloads-*.txt"])
    assert payloads is not None, "no relay payloads archived"
    text = payloads.read_text(encoding="utf-8")
    assert "GATEWAY-DIAL-FAILED" in text and "connection lost" in text


def test_desktop_focused_log_carries_its_counts_line():
    """R4-2/R6-N4-r1-3: the desktop focused log carries its own counts
    line and it matches the exit record's cited tuple."""
    log = ROOT / "out/completion/w6/n4-desktop-focused.log"
    text = log.read_text(encoding="utf-8", errors="replace")
    counts = re.search(
        r"# N4-FOCUSED-COUNTS tests=(\d+) classes=(\d+) failures=(\d+)", text)
    assert counts, f"{log.name} lost its N4-FOCUSED-COUNTS line"
    assert (int(counts.group(1)), int(counts.group(2)), int(counts.group(3))) == (31, 4, 0)
    exit_text = EXIT.read_text(encoding="utf-8") if EXIT.is_file() else ""
    assert "31 tests / 0 failures" in exit_text or "31/31" in exit_text, (
        "the exit record's desktop focused tuple does not match the "
        "archived counts line"
    )


def test_desktop_census_log_carries_its_counts_line():
    """R4-2 (the census row of the same class): the 16-class census log
    carries its counts line."""
    log = ROOT / "out/completion/w6/n4-desktop-focused-census.log"
    text = log.read_text(encoding="utf-8", errors="replace")
    counts = re.search(
        r"# N4-FOCUSED-COUNTS tests=(\d+) classes=(\d+) failures=(\d+)", text)
    assert counts, f"{log.name} lost its N4-FOCUSED-COUNTS line"
    assert int(counts.group(2)) == 16, "the desktop focused census is 16 classes"
    assert int(counts.group(3)) == 0, "the census archive is red"


def test_android_focused_log_carries_its_counts_line():
    """R3-1/R6-N4-r1-3: the android focused log carries the tool-emitted
    counts line INCLUDING the per-class split the exit record cites."""
    log = ROOT / "out/completion/w6/n4-android-focused.log"
    text = log.read_text(encoding="utf-8", errors="replace")
    counts = re.search(
        r"# N4-FOCUSED-COUNTS tests=(\d+) classes=(\d+) failures=(\d+)"
        r" \(([^)]*)\)",
        text,
    )
    assert counts, f"{log.name} lost its N4-FOCUSED-COUNTS line"
    assert (int(counts.group(1)), int(counts.group(2)), int(counts.group(3))) == (53, 4, 0)
    per_class = counts.group(4)
    for expected in (
        "AuthStreamFilterTest 16/0",
        "IrohJoinDescriptorTest 15/0",
        "PortMuxTest 4/0",
        "RealmListRewriteTest 18/0",
    ):
        assert expected in per_class, f"per-class split missing {expected}"


def test_android_canonical_tuple_quotes_the_full_counts_artifact():
    """R3-1: a 'canonical tree intact (X/Y)' quote in the exit record
    must equal the FULL android counts artifact (app + llm sum), never a
    module slice."""
    exit_text = EXIT.read_text(encoding="utf-8") if EXIT.is_file() else ""
    quoted = re.search(r"canonical tree intact \((\d+)/(\d+)\)", exit_text)
    if quoted is None:
        return  # the record quotes no canonical tuple
    artifact = json.loads(
        (ROOT / "out/completion/w0/android-unit-counts.json").read_text(
            encoding="utf-8"))
    full = (int(artifact["tests"]), int(artifact["classes"]))
    assert (int(quoted.group(1)), int(quoted.group(2))) == full, (
        f"the exit record quotes a canonical tuple {quoted.groups()} but "
        f"the counts artifact holds {full} (app+llm sum) — a module "
        "slice cannot stand for the canonical tree (R3-1)"
    )


def test_aggregate_counts_helper_emits_the_marker():
    """The tool-side fix's own pin: focused_android_unit_test.
    aggregate_counts turns a focused XML tree into the marker line."""
    import sys
    import tempfile

    sys.path.insert(0, str(ROOT / "tools"))
    import focused_android_unit_test as wrapper

    with tempfile.TemporaryDirectory() as tmp:
        results = Path(tmp)
        (results / "TEST-com.example.FakeTest.xml").write_text(
            '<testsuite name="com.example.FakeTest" tests="7" skipped="0" '
            'failures="1" errors="0"></testsuite>', encoding="utf-8")
        (results / "TEST-com.example.OtherTest.xml").write_text(
            '<testsuite name="com.example.OtherTest" tests="3" skipped="0" '
            'failures="0" errors="0"></testsuite>', encoding="utf-8")
        marker = wrapper.aggregate_counts(results)
    assert marker is not None
    assert marker.startswith("# N4-FOCUSED-COUNTS tests=10 classes=2 failures=1")
    assert "FakeTest 7/1" in marker and "OtherTest 3/0" in marker


def test_aggregate_counts_helper_returns_none_without_xml():
    import sys
    import tempfile

    sys.path.insert(0, str(ROOT / "tools"))
    import focused_android_unit_test as wrapper

    with tempfile.TemporaryDirectory() as tmp:
        assert wrapper.aggregate_counts(Path(tmp)) is None


# --------------------------------------------------------------- round 2


def _gateway_tree_test_census() -> int:
    """The tracked gateway tree's @Test count (the inventory the exit
    record itself pins)."""
    total = 0
    for source in sorted((ROOT / "gateway/src/test").rglob("*.kt")):
        total += len(
            re.findall(r"(?m)^\s*@Test\b", source.read_text(encoding="utf-8", errors="replace"))
        )
    return total


def _test_outcome_tallies(log_text: str) -> tuple[int, int, int]:
    """Per-TEST outcome lines only — Gradle TASK lines (':x SKIPPED')
    are excluded (the round-2 phantom-third-skip class)."""
    passed = skipped = failed = 0
    for line in text_lines(log_text):
        if re.search(r" > .*\(\) (PASSED|SKIPPED|FAILED)$", line):
            if line.endswith(" PASSED"):
                passed += 1
            elif line.endswith(" SKIPPED"):
                skipped += 1
            elif line.endswith(" FAILED"):
                failed += 1
    return passed, skipped, failed


def text_lines(text: str) -> list[str]:
    return text.splitlines()


def test_gateway_suite_row_equals_its_archived_log():
    """R7-N4-r2-1/R6-N4-r2-2/R5-r2-1/R2N4R2-1/R2-1/R4-r2-1: the hermetic
    gateway-suite row's tuple must equal the archived log's own per-test
    outcomes (a hermetic run passes at most census−2 — the two opt-in
    legs SKIP), the legs' SKIPPED lines and the new pin classes must be
    present, and the flags-ON 41/0 must cite the counts log."""
    exit_text = EXIT.read_text(encoding="utf-8")
    census = _gateway_tree_test_census()
    hermetic = re.search(
        r"\*\*(\d+) PASSED / (\d+) SKIPPED / (\d+) FAILED of \d+\*\*.*?"
        r"run 1: `(\S+\.log)`, run 2: `(\S+\.log)`",
        exit_text,
        re.DOTALL,
    )
    assert hermetic, "the hermetic gateway-suite row is missing/malformed"
    passed, skipped, failed = (int(x) for x in hermetic.groups()[:3])
    assert passed + skipped + failed == census, (
        f"the hermetic tuple {passed}/{skipped}/{failed} does not sum to "
        f"the tracked tree's {census} tests"
    )
    for log_rel in (hermetic.group(4), hermetic.group(5)):
        log = ROOT / "out/completion/w6" / log_rel
        assert log.is_file(), f"cited hermetic run log {log_rel} is missing"
        tally = _test_outcome_tallies(log.read_text(encoding="utf-8", errors="replace"))
        assert tally == (passed, skipped, failed), (
            f"{log_rel} tallies {tally}, the record claims "
            f"{(passed, skipped, failed)}"
        )
        log_text = log.read_text(encoding="utf-8", errors="replace")
        # the opt-in legs must be the SKIPPED set (flags OFF)
        assert "ReconnectRelayE2E > reconnectAfterHostRedialReusesTheSameListingDescriptor() SKIPPED" in log_text
        assert "ReconnectDockerE2E > hostSourceNetworkChangeSurvivedByRedialingTheSameEndpointId() SKIPPED" in log_text
        # the round-1/round-2 pin classes must have executed
        assert "RelayGateAbortPin > an unreachable relay aborts the opt-in leg as skipped - never a failure() PASSED" in log_text
        assert "GamePortLockPin > releaseGamePortLockNeverDeletesTheLockFile() PASSED" in log_text
    # the flags-ON claim cites the counts log
    flags_on = re.search(
        r"\*\*(\d+) PASSED / (\d+) FAILED\*\* \(both reconnect legs executed LIVE"
        r"[^)]*\)\s*— `(\S+\.log)`",
        exit_text,
    )
    assert flags_on, "the flags-ON gateway-suite row is missing/malformed"
    assert (int(flags_on.group(1)), int(flags_on.group(2))) == (census, 0), (
        "the flags-ON run must pass every tracked test"
    )
    counts_log = ROOT / "out/completion/w6" / flags_on.group(3)
    assert counts_log.is_file(), f"cited counts log {flags_on.group(3)} is missing"
    counts_text = counts_log.read_text(encoding="utf-8", errors="replace")
    tally = _test_outcome_tallies(counts_text)
    assert tally == (census, 0, 0), (
        f"{flags_on.group(3)} tallies {tally}, the flags-ON row claims "
        f"{(census, 0, 0)}"
    )


def test_seat_validation_tuple_equals_the_archived_seat_logs():
    """R6-N4-r2-1/R3-r2-1 (serious)/R5-r2-2/R4-r2-2: the concurrent-seat
    tuple quoted in the exit record must be carried by the archived seat
    logs, be arithmetically possible (PASSED+SKIPPED == the tracked
    census), and record zero failures."""
    exit_text = EXIT.read_text(encoding="utf-8")
    census = _gateway_tree_test_census()
    seat = re.search(
        r"alpha\s+(\d+)\s+PASSED\s*/\s*(\d+)\s+SKIPPED\s*/\s*(\d+)\s+FAILED\s+"
        r"exit\s+\d+(?:\s*\([^)]*\))?,\s*beta\s+(\d+)\s+PASSED\s*/\s*(\d+)\s+"
        r"SKIPPED\s*/\s*(\d+)\s+FAILED\s+exit\s+\d+\*\*\s*—\s*`(\S+\.log)`"
        r"\s*\+\s*`(\S+\.log)`",
        exit_text,
        re.DOTALL,
    )
    assert seat, "the archived seat-validation tuple is missing from the exit record"
    alpha, beta = (
        (int(seat.group(1)), int(seat.group(2)), int(seat.group(3))),
        (int(seat.group(4)), int(seat.group(5)), int(seat.group(6))),
    )
    for passed, skipped, failed in (alpha, beta):
        assert passed + skipped + failed == census, (
            f"seat tuple {passed}/{skipped}/{failed} does not sum to the "
            f"tracked tree's {census} tests (the impossible-tuple class)"
        )
        assert failed == 0, "the seat validation claims a failure-free pair"
    for log_rel in (seat.group(7), seat.group(8)):
        log = N4 / Path(log_rel).name
        assert log.is_file(), f"archived seat log {log_rel} is missing"
        tally = _test_outcome_tallies(log.read_text(encoding="utf-8", errors="replace"))
        expected = alpha if "alpha" in log_rel else beta
        assert tally == expected, (
            f"{log.name} tallies {tally}, the record claims {expected}"
        )


def test_d98_amendment_supersedes_the_impossible_seat_tuple():
    """R4-r2-1/R4-r2-2's D98 leg: the Decision Log's seat tuple (wherever
    quoted) must satisfy the tracked tree's arithmetic — the round-1
    37/3 (=40 on a 39-test tree) class can never stand again."""
    decisions = (ROOT / "docs/DECISIONS-PORT.md").read_text(encoding="utf-8")
    census = _gateway_tree_test_census()
    for m in re.finditer(
        r"alpha (\d+) PASSED / (\d+) SKIPPED[^b]*beta (\d+) PASSED / (\d+) SKIPPED",
        decisions,
    ):
        a_p, a_s, b_p, b_s = (int(x) for x in m.groups())
        assert a_p + a_s <= census and b_p + b_s <= census, (
            f"an impossible seat tuple ({a_p}+{a_s}, {b_p}+{b_s} on a "
            f"{census}-test tree) is quoted in the Decision Log"
        )
    # the round-2 correction must exist and name the archived seat logs
    assert "n4-seat-alpha.log" in decisions and "n4-seat-beta.log" in decisions, (
        "the D98 round-2 amendment must cite the archived seat logs"
    )


def test_every_secrets_archive_dir_is_cited_or_dispositioned():
    """R4-r2-2/R3-r2-3: the exit record's archive-inventory sentence must
    name exactly the secrets-* dirs that exist — an unrecorded live run
    beside the cited one is unverifiable-claim surface."""
    exit_text = EXIT.read_text(encoding="utf-8")
    on_disk = sorted(p.name.removeprefix("secrets-") for p in N4.glob("secrets-*"))
    assert on_disk, "no secrets-* archives present"
    for suffix in on_disk:
        assert suffix in exit_text, (
            f"secrets-{suffix} exists on disk but the exit record neither "
            "cites nor dispositions it"
        )


def test_d98_secret_posture_clause_is_scoped_not_absolute():
    """R3-r2-2: the D98 round-1 amendment's 'never argv' clause is
    over-broad (the relay E2E and the identity pin use runtime-generated
    argv secrets — the documented one-machine E2E affordance); the
    record must carry the round-2 scoped wording."""
    decisions = (ROOT / "docs/DECISIONS-PORT.md").read_text(encoding="utf-8")
    assert "SECRET-POSTURE SCOPE" in decisions, (
        "the round-2 amendment's scoped secret-posture ruling is missing"
    )
    relay_e2e = (ROOT / "gateway/src/test/kotlin/com/pocketrealm/gateway/ReconnectRelayE2E.kt").read_text(
        encoding="utf-8")
    # the relay E2E's argv-secrets are the documented affordance — the
    # Docker leg is the stdin leg
    docker_e2e = (ROOT / "gateway/src/test/kotlin/com/pocketrealm/gateway/ReconnectDockerE2E.kt").read_text(
        encoding="utf-8")
    assert "--secret-from-stdin" in docker_e2e, (
        "the docker leg must keep the stdin secret channel"
    )
    assert '"--secret"' in relay_e2e, (
        "the relay leg's documented argv affordance moved — update the "
        "D98 scope wording with it"
    )


def test_canonical_tuples_in_both_exit_records_equal_their_artifacts():
    """R2-2 (strengthened) + R7-r3-1: THIS wave's record must (a) never
    carry the guarded phrase 'canonical tree intact' WITHOUT a tuple
    (the vacuous-guard class — the phrase pattern the round-1 guard
    regex expected no longer exists, so a regression to it must fail
    here), (b) quote the android canonical tuple in its VERIFICATION row
    (a ledger sentence describing the fix does not count toward
    non-vacuity), and (c) hold every quoted tuple equal to the counts
    artifact (1836/231, app+llm)."""
    android_artifact = json.loads(
        (ROOT / "out/completion/w0/android-unit-counts.json").read_text(encoding="utf-8"))
    android_full = (int(android_artifact["tests"]), int(android_artifact["classes"]))
    text = EXIT.read_text(encoding="utf-8")
    # (a) the guarded phrase must not exist at all — the record quotes
    # the tuple in the verification row's own phrasing instead
    assert "canonical tree intact" not in text, (
        "WAVE-EXIT-N4 carries the round-1-guarded phrase 'canonical tree "
        "intact' — quote the tuple in the verification row instead"
    )
    # (b)+(c) 'android canonical' windows: the ledger self-description
    # ('is quoted as') is a truth-check only; the verification-row hit
    # is what makes the pin non-vacuous.
    truth_checks = 0
    verification_hits = 0
    for m in re.finditer(r"android canonical", text, re.IGNORECASE):
        window = text[m.start():m.start() + 240]
        tuples = list(re.finditer(r"\b(\d{3,4})\s*/\s*(\d{2,3})\b", window))
        assert tuples, (
            f"an 'android canonical' mention carries no tuple: {window[:80]!r}"
        )
        for t in tuples:
            truth_checks += 1
            assert (int(t.group(1)), int(t.group(2))) == android_full, (
                f"WAVE-EXIT-N4.md quotes android canonical tuple "
                f"{t.groups()} but the counts artifact holds {android_full}"
            )
        if "is quoted as" not in window:
            verification_hits += 1
    assert truth_checks > 0, "no android canonical tuple quoted at all"
    assert verification_hits > 0, (
        "the android canonical tuple appears only in ledger self-description "
        "— the verification row must quote it (R7-r3-1)"
    )


# --------------------------------------------------------------- round 3


def _last_n4_amendment() -> str:
    """The LATEST '**W6-N4 code-red round-N amendment' block in the
    Decision Log (append-only history: earlier blocks are superseded
    record, held by their own era pins — R3-R1-1/R6-N4-r3-1)."""
    decisions = (ROOT / "docs/DECISIONS-PORT.md").read_text(encoding="utf-8")
    blocks = re.split(r"(?=\*\*W6-N4 code-red round-\d amendment)", decisions)
    n4_blocks = [b for b in blocks if b.startswith("**W6-N4 code-red round-")]
    assert n4_blocks, "no W6-N4 amendment blocks in the Decision Log"
    return n4_blocks[-1]


def test_d98_archive_inventory_and_tier2_citation_match_the_disk_and_exit_record():
    """R3-R1-1/R3-3/R4-r3-3/R5-r3-1/R6-N4-r3-1: the LATEST D98 amendment
    must (a) name an archive count + suffix set equal to the on-disk
    secrets-* set, (b) cite the SAME docker run suffix for the counts
    run that the exit record's tier-2 row cites, and (c) retire the
    stale tuples it supersedes BY NAME."""
    decisions_last = _last_n4_amendment()
    on_disk = sorted(p.name.removeprefix("secrets-") for p in N4.glob("secrets-*"))
    assert on_disk, "no secrets-* archives present"
    count_sentence = re.search(
        r"complete docker (?:runs|archives) stand", decisions_last)
    assert count_sentence, (
        "the latest D98 amendment carries no archive-inventory sentence"
    )
    named_suffixes = set(re.findall(r"\bmv[a-z0-9]{6}\b", decisions_last))
    # SUBSET semantics (round-6 finalize): later verification runs add
    # archives journaled by their seats — the amendment must not name
    # PHANTOM archives, but the on-disk set may legitimately be larger.
    assert named_suffixes <= set(on_disk), (
        f"the latest D98 amendment names non-existent archives: "
        f"{sorted(named_suffixes - set(on_disk))} — record drift on the "
        "controlling record"
        f"the latest D98 amendment names {sorted(named_suffixes)} but the "
        f"disk holds {on_disk} — record drift on the controlling record"
    )
    # The LAST 'TIER-2 ROW CITES <suffix>' across every W6-N4 amendment
    # must equal the exit record's tier-2 suffix (one counts run, one
    # citation; earlier amendments' citations are superseded history).
    decisions_all = (ROOT / "docs/DECISIONS-PORT.md").read_text(encoding="utf-8")
    d98_tier2 = re.findall(
        r"TIER-2 ROW CITES ([a-z0-9]+)", decisions_all)
    exit_text = EXIT.read_text(encoding="utf-8")
    exit_tier2 = re.search(
        r"run suffix `([a-z0-9]+)`", exit_text)
    assert exit_tier2, "the exit record's tier-2 row lost its run suffix"
    assert d98_tier2, "no TIER-2 ROW CITES sentence in the Decision Log"
    cited = d98_tier2[-1]
    assert cited == exit_tier2.group(1), (
        f"D98's tier-2 citation ({cited}) disagrees with the exit "
        f"record's ({exit_tier2.group(1)}) — one counts run, one citation"
    )
    # the amendment must retire the stale tuples BY NAME (the R3-R1-1
    # list: 29/29, 26/26, mvdyd58i-as-final — the 35/35 pattern)
    # the cumulative retirement roster must name every stale figure the
    # wave's rounds retired (R3-R1-1's list + the later rounds' own)
    for retired in ("29/29", "26/26", "35/35", "mvdyd58i", "mvdz32oo", "mve1zdfk"):
        assert retired in decisions_last, (
            f"the latest D98 amendment's retirement roster must name the "
            f"stale tuple/item it retires: {retired}"
        )


def test_production_diff_sentence_names_every_changed_main_file():
    """R3-R1-2: the wave's production-diff claim must name EVERY main
    sourceSet file the wave changed (git diff vs the start commit)."""
    import subprocess

    diff = subprocess.run(
        ["git", "diff", "--name-only", "6277d0a", "--",
         "desktop/src/main/", "gateway/src/main/", "android/app/src/main/"],
        capture_output=True, text=True, cwd=ROOT,
    )
    changed_main = sorted(
        line for line in diff.stdout.splitlines() if line.strip()
    )
    assert changed_main, "no main-source changes found (unexpected)"
    exit_text = EXIT.read_text(encoding="utf-8")
    for path in changed_main:
        basename = path.replace("\\", "/").rsplit("/", 1)[-1]
        assert basename in exit_text, (
            f"the wave changed main source {path} but the exit record's "
            "production-diff sentence does not name it"
        )


def test_desktop_focused_emitter_is_tracked():
    """R6-N4-r3-2: the desktop focused counts marker must come from a
    TRACKED emitter in the build (never appended text)."""
    build = (ROOT / "desktop/build.gradle.kts").read_text(encoding="utf-8")
    assert "N4-FOCUSED-COUNTS" in build, (
        "desktop/build.gradle.kts lost the focused counts emitter"
    )


def test_d98_counts_run_suffix_resolves_to_an_existing_archive():
    """R3-3(b): every 'docker run <suffix>' attribution in the Decision
    Log must resolve to an existing secrets-<suffix> dir."""
    decisions = (ROOT / "docs/DECISIONS-PORT.md").read_text(encoding="utf-8")
    for suffix in set(re.findall(r"docker run (mvd[a-z0-9]{6})", decisions)):
        assert (N4 / f"secrets-{suffix}").is_dir(), (
            f"D98 cites docker run {suffix} but secrets-{suffix} does not "
            "exist on disk"
        )


# --------------------------------------------------------------- round 4


def test_seat_isolation_log_carries_the_green_verdict():
    """R7-N4-r4-2/R6-N4-r4-1/R3-1/R1-4-1: the cited isolation log must
    carry the verdict line the records quote — SEAT-ISOLATION-OK with
    the seat id, both canonical-classes hashes (equal: the tree was
    untouched), and zero failures."""
    log = N4 / "n4-seat-isolation-check.log"
    assert log.is_file(), "the isolation log is missing"
    text = log.read_text(encoding="utf-8", errors="replace")
    verdict = re.search(
        r"SEAT-ISOLATION-OK seat=(\S+) before=([0-9a-f]{64}) "
        r"after=([0-9a-f]{64}) failures=(\d+)",
        text,
    )
    assert verdict, (
        f"{log.name} carries no green SEAT-ISOLATION verdict line — the "
        "records cite an artifact that cannot hold the cited fact"
    )
    seat, before, after, failures = verdict.groups()
    assert before == after, (
        "a green isolation verdict requires before==after canonical "
        "classes hashes (the canonical tree untouched)"
    )
    assert int(failures) == 0
    # the D98 round-3 amendment's citation resolves to THIS artifact
    assert "SEAT-ISOLATION-OK" in (
        ROOT / "docs/DECISIONS-PORT.md").read_text(encoding="utf-8")


def test_seat_posture_sentence_is_scoped_not_marker_absence():
    """R7-N4-r4-1's reconciliation branch: the exit record's seat bullet
    must NOT claim marker-absence (the KGP stale-session detection notice
    is emitted even under in-process enforcement — measured) and must
    carry the scoped reconciliation (the in-build enforcement + the
    recorded notice state)."""
    exit_text = EXIT.read_text(encoding="utf-8")
    seat_block = re.search(
        r"POSTURE\s+RECONCILIATION \(round 4, R7-N4-r4-1\)(.*?)(?:\n- |\n\n)",
        exit_text,
        re.DOTALL,
    )
    assert seat_block, "the posture reconciliation block is missing"
    block = seat_block.group(1)
    assert "STALE-SESSION DETECTION" in block
    assert "does NOT claim marker-absence" in block
    assert "IN_PROCESS" in block
    assert "daemon-detection-notice" in block


def test_pin_census_matches_quoted_counts():
    """R6-N4-r4-2/R1-4-2: every 'holds N pins' count quoted for the
    evidence-pins file (in the LATEST D98 amendment or the exit record)
    must equal the live `def test_` census of that file."""
    pins_file = ROOT / "tests/test_w6n4_evidence_pins.py"
    live = len(re.findall(r"(?m)^def test_", pins_file.read_text(encoding="utf-8")))
    decisions_last = _last_n4_amendment()
    checked = 0
    for text, name in (
        (decisions_last, "the latest D98 amendment"),
        (EXIT.read_text(encoding="utf-8"), "WAVE-EXIT-N4"),
    ):
        for m in re.finditer(r"(\S+\.py) holds (\d+) pins", text):
            checked += 1
            if "test_w6n4_evidence_pins.py" in m.group(1):
                assert int(m.group(2)) == live, (
                    f"{name} quotes {m.group(2)} pins for "
                    f"test_w6n4_evidence_pins.py but the file holds {live}"
                )
    count_in_amendment = re.search(
        r"test_w6n4_evidence_pins\.py holds (\d+) pins", decisions_last)
    assert count_in_amendment, (
        "the latest D98 amendment quotes no pin census for "
        "test_w6n4_evidence_pins.py (the record-lag class, R6-N4-r4-2)"
    )
    assert int(count_in_amendment.group(1)) == live


def test_relay_payloads_sections_all_nonempty():
    """R5-r4-N4-1: the archived relay payloads must carry EVERY launched
    process's verbatim lines (the transcript fix) — no empty sections."""
    payloads = _newest(["n4-relay-payloads-*.txt"])
    assert payloads is not None, "no relay payloads archived"
    text = payloads.read_text(encoding="utf-8")
    sections = re.split(r"=== (\S+) ===", text)
    named = list(zip(sections[1::2], sections[2::2]))
    assert len(named) >= 4, (
        f"expected 4 sections (host1/join1/host2/join2), got {len(named)}"
    )
    for name, body in named:
        assert body.strip(), f"section {name} is empty (drained-residue class)"
        assert "PR_GATEWAY_" in body, (
            f"section {name} carries no protocol line: {body[:80]!r}"
        )


# --------------------------------------------------------------- round 5


def test_gateway_counts_emitter_is_tracked():
    """R1-N4-r5-2/R5-N4-r5-2: the gateway counts markers must come from a
    TRACKED emitter in the gateway build (the desktop lane's
    R6-N4-r3-2 ruling, gateway twin) — never appended text."""
    build = (ROOT / "gateway/build.gradle.kts").read_text(encoding="utf-8")
    assert "N4-GATEWAY-SUITE-COUNTS" in build, (
        "gateway/build.gradle.kts lost the suite counts emitter"
    )
    assert "N3-GATEWAY-COUNTS" in build, (
        "gateway/build.gradle.kts lost the reader-pin counts emitter"
    )


def test_app_pins_row_equals_the_archived_counts_and_the_class_census():
    """R1-N4-r5-1/R2-r5-N4-1/R4-r5-N4-1/R5-N4-r5-1/R6-1/R7-r5-1: the
    app-pins tier row's tuple ('X/Y green in the Z-test focused run')
    must equal the cited log's N4-FOCUSED-COUNTS line, the per-class
    IrohRejoinTest split, AND the tracked @Test census of
    IrohRejoinTest.kt — the tier table gets the same body-tally
    discipline the verification row already has."""
    exit_text = EXIT.read_text(encoding="utf-8")
    row = re.search(
        r"\| app pins \| `IrohRejoinTest`[^|]*\|[^|]*\|\s*(\d+)/(\d+) green "
        r"in the (\d+)-test focused run \(`([^`]+)`[^)]*\)",
        exit_text,
    )
    assert row, "the app-pins tier row is missing/malformed"
    green, total_green, quoted_tests, log_rel = row.groups()
    assert int(green) == int(total_green), (
        f"the app-pins row quotes {green}/{total_green} — a split with a "
        "failure cannot be quoted green"
    )
    log = ROOT / "out/completion/w6" / log_rel
    assert log.is_file(), f"the cited focused log {log_rel} is missing"
    log_text = log.read_text(encoding="utf-8", errors="replace")
    counts = re.search(
        r"# N4-FOCUSED-COUNTS tests=(\d+) classes=(\d+) failures=(\d+)"
        r" \(([^)]*)\)",
        log_text,
    )
    assert counts, f"{log_rel} lost its N4-FOCUSED-COUNTS line"
    assert int(counts.group(1)) == int(quoted_tests), (
        f"the row quotes a {quoted_tests}-test focused run but the cited "
        f"log records tests={counts.group(1)}"
    )
    split = dict(
        (name, int(n)) for name, n in
        re.findall(r"(\w+) (\d+)/\d+", counts.group(4))
    )
    assert split.get("IrohRejoinTest") == int(green), (
        f"the row quotes IrohRejoinTest {green} green but the cited log's "
        f"split records {split.get('IrohRejoinTest')}"
    )
    # the row's claim list must account for the LIVE class census
    source = (ROOT / "desktop/src/test/kotlin/com/pocketrealm/desktop/iroh/"
              "IrohRejoinTest.kt").read_text(encoding="utf-8")
    live = len(re.findall(r"(?m)^\s*@Test\b", source))
    assert int(green) == live, (
        f"the row quotes IrohRejoinTest {green} green but the class holds "
        f"{live} @Test methods (the record-lag class)"
    )


def test_exit_record_carries_no_stale_era_tuples():
    """R4-r5-N4-1/R6-1: the superseded era shapes ('30-test focused
    run', '3/3 green', the bare '30/4/0' / '212/16/0' parentheticals)
    must be gone from the exit record — the operative numbers live in
    the verification table and the logs' own markers."""
    text = EXIT.read_text(encoding="utf-8")
    for stale in ("30-test focused run", "3/3 green", "30/4/0", "212/16/0"):
        assert stale not in text, (
            f"the superseded era shape {stale!r} is still quoted in the "
            "exit record"
        )


def test_inventory_sentence_names_exactly_the_on_disk_archives():
    """R5-r5-N4-1: WAVE-EXIT-N4's archive-inventory sentence must name
    EXACTLY the secrets-* set on disk (SEVEN naming six was the round-5
    class), and the suffix it labels the tier-2 citation must equal the
    tier-2 row's own run suffix."""
    exit_text = EXIT.read_text(encoding="utf-8")
    on_disk = sorted(p.name.removeprefix("secrets-") for p in N4.glob("secrets-*"))
    assert on_disk, "no secrets-* archives present"
    inventory = re.search(
        r"Archive inventory \(([^*]*?)\)\.\*\*", exit_text, re.DOTALL)
    assert inventory, "the archive-inventory sentence is missing"
    inv_text = inventory.group(1)
    # no count word (the set grows with every legitimate live run)
    assert not re.search(r"(SEVEN|SIX|FIVE|FOUR|EIGHT|NINE) complete docker", inv_text), (
        "the inventory sentence hard-codes an archive count — the set "
        "grows with every legitimate live run (R5-r5-N4-1's root cause)"
    )
    # every NAMED suffix exists on disk (a subset: newer verification
    # archives are journaled by their seats)
    named = set(re.findall(r"(mv[a-z0-9]{6})", inv_text))
    assert named <= set(on_disk), (
        f"the inventory sentence names non-existent archives: "
        f"{sorted(named - set(on_disk))}"
    )
    # the suffix labeled the FINAL counts run must equal the tier-2
    # row's own citation
    tier2 = re.search(r"run suffix `([a-z0-9]+)`", exit_text)
    assert tier2, "the tier-2 row lost its run suffix"
    final_named = re.findall(r"`(mv[a-z0-9]{6})`", inv_text)
    assert final_named and final_named[-1] == tier2.group(1), (
        f"the inventory's final counts run ({final_named[-1]}) disagrees with "
        f"the tier-2 row's citation ({tier2.group(1)})"
    )
    # and the labeled archive must be COMPLETE (narration + join2 READY)
    labeled_dir = N4 / f"secrets-{final_named[-1]}"
    assert (labeled_dir / f"n4-docker-narration-{final_named[-1]}.txt").is_file(), (
        "the labeled archive lacks its narration"
    )


def test_d98_amendments_tier2_and_counts_attribution_coherent():
    """R5-r5-N4-2: inside the LATEST D98 amendment, every 'docker run
    <suffix>' attribution must equal that amendment's own
    'TIER-2 ROW CITES <suffix>' value (one counts run, one citation,
    within a single amendment) — the round-4 block's item (5) vs (1a)
    split must not recur; earlier blocks are superseded history held by
    their retirement rosters."""
    decisions_last = _last_n4_amendment()
    cites = re.findall(r"TIER-2 ROW CITES ([a-z0-9]+)", decisions_last)
    assert cites, "the latest amendment carries no TIER-2 ROW CITES sentence"
    operative = cites[-1]
    # R5-r6-2 (positional, no hardcoded allowance): every
    # 'docker run <suffix>' attribution must EQUAL the operative cite,
    # or lie inside an explicit [CORRECTED …]/[SUPERSEDED …] span.
    # The bracket-span helper is self-tested below against the stale
    # (unbracketed) shape so the justification can never silently
    # drift from reality.
    def inside_bracket_span(text: str, match_start: int) -> bool:
        # a bracket whose body mentions CORRECTED/SUPERSEDED anywhere
        # (e.g. "[ATTRIBUTION CORRECTED round 6: ...]")
        for span in re.finditer(
                r"\[[^\]]*(?:CORRECTED|SUPERSEDED)[^\]]*\]", text):
            if span.start() <= match_start <= span.end():
                return True
        return False

    for attribution in re.finditer(r"docker run ([a-z0-9]{8,})", decisions_last):
        suffix = attribution.group(1)
        if suffix != operative:
            assert inside_bracket_span(decisions_last, attribution.start()), (
                f"an UNCORRECTED docker-run attribution ({suffix}) "
                f"disagrees with the operative tier-2 citation ({operative}) "
                "at offset " + str(attribution.start()) + " — the record of "
                "record contradicts itself (the R5-r6-1 class)"
            )
        assert (N4 / f"secrets-{suffix}").is_dir(), (
            f"the attributed archive secrets-{suffix} does not exist"
        )

    def _self_test_bracket_span_logic():
        stale = "flags-ON 44 PASSED / 0 FAILED (relay payloads; docker run mve0c7yt)"
        stale_match = re.search(r"docker run ([a-z0-9]{8,})", stale)
        assert stale_match.group(1) != operative
        assert not inside_bracket_span(stale, stale_match.start()), (
            "the stale unbracketed shape must be flagged red"
        )
        corrected = ("flags-ON 44 PASSED (relay payloads; [ATTRIBUTION "
                     "CORRECTED round 6: this sentence originally said "
                     "'docker run mve0c7yt' — the tallies belong to docker "
                     "run mve43825])")
        corrected_match = re.search(r"docker run mve0c7yt", corrected)
        assert inside_bracket_span(corrected, corrected_match.start()), (
            "a bracketed superseded attribution must pass the span check"
        )
    _self_test_bracket_span_logic()
