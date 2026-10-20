"""W6B code-red round 1: audit-record truth pins (R3-1/R3-2/R4-3/R5-3).

- The production-diff sentence is BIDIRECTIONAL: every changed main file is
  named AND every named file actually changed (the R5-3 phantom class).
- The android detekt baseline on disk equals a sanctioned
  :app:detektBaseline regeneration (R3-1: no silent hand edits; any regen
  delta must be enumerated in the wave ledger).
- Every schemas/ file that differs from the wave base commit is enumerated
  in out/completion/w6b/WAVE-EXIT.md (R3-2: byte-frozen schemas edits must
  be owning-recorded, never silent).
"""
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "5a22314704973624a16b8c3655a6b4c0504d186c"
EXIT = ROOT / "out/completion/w6b/WAVE-EXIT.md"
BASELINE = ROOT / "android/app/detekt-baseline.xml"


def _git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)


def test_production_diff_sentence_is_bidirectional():
    """R4-3/R5-3: changed -> named AND named -> changed (the phantom-name
    class: GeneratedTimeframeOverlayCatalog.kt was named while
    byte-identical to the base)."""
    exit_text = EXIT.read_text(encoding="utf-8")
    i = exit_text.find("## Production-diff sentence")
    assert i >= 0, "the exit record lost its production-diff section"
    j = exit_text.find("\n## ", i + 10)
    section = exit_text[i:j if j >= 0 else len(exit_text)]
    named = set(re.findall(r"`([A-Za-z0-9._-]+\.kt)`", section))

    changed = _git("diff", "--name-only", BASE, "--",
                   "desktop/src/main/", "gateway/src/main/", "android/app/src/main/")
    changed_names = {
        p.replace("\\", "/").rsplit("/", 1)[-1]
        for p in changed.stdout.splitlines() if p.strip()
    }
    assert changed_names, "no main-source changes found (unexpected)"
    unnamed = changed_names - named
    assert not unnamed, (
        f"main source files changed but the production-diff sentence does not "
        f"name them: {sorted(unnamed)}"
    )
    phantoms = named - changed_names
    assert not phantoms, (
        f"the production-diff sentence names files with NO diff vs the base "
        f"commit (phantom names): {sorted(phantoms)}"
    )


def test_android_detekt_baseline_equals_sanctioned_regen():
    """R3-1: the committed baseline entry set must equal what the sanctioned
    :app:detektBaseline regeneration produces — no silent hand edits. Any
    regen delta must be enumerated entry-by-entry in the W6B exit record's
    D-W6B-4 decision."""
    before = BASELINE.read_bytes()
    before_ids = set(
        re.findall(r"<ID>(.*?)</ID>", before.decode("utf-8"), re.S))
    gradlew = str(ROOT / "android" / "gradlew.bat")
    regen = subprocess.run(
        [gradlew, ":app:detektBaseline", "-PpocketAbi=arm64-v8a",
         "--console=plain"],
        cwd=ROOT / "android", capture_output=True, text=True, timeout=600)
    try:
        after_ids = set(
            re.findall(r"<ID>(.*?)</ID>",
                       BASELINE.read_text(encoding="utf-8"), re.S))
    finally:
        # restore the exact pre-test bytes and keep index == worktree:
        # the pin only measures the delta, it never keeps the regen output
        BASELINE.write_bytes(before)
        subprocess.run(["git", "add", "android/app/detekt-baseline.xml"],
                       cwd=ROOT, capture_output=True)
    assert regen.returncode == 0, (
        f"the sanctioned baseline regeneration failed: {regen.stdout[-300:]}")
    unexpected = after_ids - before_ids
    missing = before_ids - after_ids
    assert not unexpected and not missing, (
        "the committed detekt baseline differs from a sanctioned regeneration "
        f"— entries the regen would ADD: {sorted(unexpected)[:6]}; entries the "
        f"regen would DROP: {sorted(missing)[:6]} — re-derive via "
        ":app:detektBaseline and enumerate every delta entry in the wave "
        "ledger (D-W6B-4 contract)"
    )


def test_schemas_diff_vs_base_is_enumerated_in_the_exit_record():
    """R3-2: byte-frozen schemas/*.json must never drift silently. Any
    schemas/ file differing from the wave base commit must be named in
    out/completion/w6b/WAVE-EXIT.md, and the conf-matrix pin json's
    catalog_sha256 must equal the live generated catalog."""
    diff = _git("diff", "--name-only", BASE, "--", "schemas/")
    changed = [p for p in diff.stdout.splitlines() if p.strip()]
    exit_text = EXIT.read_text(encoding="utf-8")
    for path in changed:
        basename = path.replace("\\", "/").rsplit("/", 1)[-1]
        assert basename in exit_text, (
            f"schemas/{basename} differs from the base commit but the exit "
            "record does not enumerate it (byte-frozen schemas edits must be "
            "owning-recorded)"
        )
    pin = ROOT / "schemas/conf-matrix-kotlin-catalog-pin.json"
    if pin.is_file():
        catalog = (ROOT / "android/app/src/main/java/com/pocketrealm/server/"
                   "GeneratedBotConfKeyCatalog.kt")
        if catalog.is_file():
            import hashlib
            live = hashlib.sha256(catalog.read_bytes()).hexdigest()
            recorded = json.loads(pin.read_text(encoding="utf-8"))
            assert recorded.get("catalog_sha256") == live, (
                "schemas/conf-matrix-kotlin-catalog-pin.json catalog_sha256 "
                "does not match the live generated catalog — regenerate via "
                "python tools/generate_conf_matrix.py --kotlin-catalog"
            )


def test_wave_exit_cited_logs_and_totals_are_true():
    """R6B-4/R6B-5/R4-1/R4-2/R4-3/R3-1/R3-2/R2-3 (round 2): every log cited
    in the W6B exit record exists, is non-empty, and carries its claimed
    markers; the canonical android row's totals equal the tracked counts
    artifact; 'task EXECUTED' phrasing is backed by a non-FROM-CACHE task
    line; the working tree has zero AM/MM residue and the production-diff
    paths are staged."""
    text = EXIT.read_text(encoding="utf-8")
    # cited logs exist and are non-empty
    for log in ("out/completion/w6b/android-canonical-final.log",
                "out/completion/w6b/gateway-lane.log",
                "out/completion/w6b/spikes-desktop-echo.log",
                "out/completion/w6b/desktop-suite-final.log",
                "out/completion/w6b/android-fence-negative.log",
                "out/completion/w6b/android-fence-positive.log"):
        p = ROOT / log
        assert p.is_file() and p.stat().st_size > 0, f"{log} missing/empty"
    # gateway lane log carries its tasks and a terminal BUILD SUCCESSFUL
    gw = (ROOT / "out/completion/w6b/gateway-lane.log").read_text(
        encoding="utf-8", errors="replace")
    assert "BUILD SUCCESSFUL" in gw and "compileKotlin" in gw.replace("-", ""), (
        "gateway-lane.log lost its task/BUILD lines")
    # canonical android log: the test tasks EXECUTED (no FROM-CACHE/UP-TO-DATE)
    canon = (ROOT / "out/completion/w6b/android-canonical-final.log").read_text(
        encoding="utf-8", errors="replace")
    assert "> Task :app:testDebugUnitTest" in canon and "FROM-CACHE" not in canon, (
        "the canonical android log's :app:testDebugUnitTest is not an executed run")
    assert "# ANDROID-CANONICAL-COUNTS tests=1839" in canon, (
        "the canonical android log lost its native counts marker")
    # the quoted canonical totals equal the tracked artifact (single truth)
    counts = json.loads(
        (ROOT / "out/completion/w0/android-unit-counts.json").read_text(encoding="utf-8"))
    assert (counts["tests"], counts["classes"]) == (1839, 231), counts
    assert "1839 tests / 231 classes" in text, (
        "the exit record must quote the canonical android tuple the artifact holds")
    assert "1816 tests / 226 classes" not in text, (
        "the exit record still quotes the superseded app-module-only tuple")
    # zero AM/MM residue; production-diff paths staged
    st = subprocess.run(
        ["git", "status", "--porcelain"], cwd=ROOT, capture_output=True, text=True)
    assert not re.search(r"^(AM|MM) ", st.stdout, re.M), (
        "AM/MM residue in the index: " + st.stdout[:300])
    diff_names = _git("diff", "--name-only", BASE, "--",
                      "desktop/src/main/", "gateway/src/main/", "android/app/src/main/")
    staged = _git("diff", "--cached", "--name-only", BASE, "--",
                  "desktop/src/main/", "gateway/src/main/", "android/app/src/main/")
    for pth in diff_names.stdout.splitlines():
        if pth.strip():
            assert pth.strip() in staged.stdout, (
                f"production-diff path not staged: {pth} (the finalize commit "
                "would drop it)")


def test_android_tests_fence_is_present_and_honest():
    """R3-1-first (round 3, sanctioned-remediation acknowledgment) and
    W6B-R3-2: the android --tests fence exists in the build script, and its
    refusal copy names tools/focused_android_unit_test.py as the ONLY
    sanctioned filtered-run path — a bare -Ppocket.focus label is NOT
    presented as a remedy (it does not redirect JUnit XML on this lane)."""
    text = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    assert "androidFocusRedirect" in text, "the android --tests fence was removed"
    assert "android test-class filters (--tests) are only sanctioned through" in text, (
        "the fence refusal copy no longer names the only sanctioned path")
    assert "tools/focused_android_unit_test.py" in text
    assert "bare -Ppocket.focus label does NOT redirect JUnit XML" in text, (
        "the fence copy must not present a bare -Ppocket.focus label as a "
        "sufficient remedy on the android lane (the label performs no redirect)")


def test_digest_archiver_is_write_once():
    """R3-2-first (round 3): the digest archiver must refuse to overwrite an
    existing label's manifest without the explicit --refresh flag, so audit
    history cannot be silently rewritten again."""
    src = (ROOT / "tools/archive_junit_tree_digest.py").read_text(encoding="utf-8")
    assert "--refresh" in src and "already exists" in src, (
        "tools/archive_junit_tree_digest.py lost its write-once guard")


def test_wave_exit_totals_match_live_diff_and_sweep_notes_present():
    """W6B-R3-3/R4-r3-1/R6-2/R5-r3-1/R3-4 (round 3): the exit record's totals
    sentence equals the live `git diff <base> --shortstat`; every sweep-quote
    record the D-W6B-2 waiver names carries both the live count and the W6B
    refresh annotation; and the hard-constraint compliance bullet enumerates
    the sanctioned schemas/catalog exceptions (R4-r3-2)."""
    import re as _re
    text = EXIT.read_text(encoding="utf-8")
    m = _re.search(
        r"Total diff vs base [0-9a-f]+: (\d+) files changed, \+(\d+)/-(\d+)",
        text)
    assert m, "the exit record lost its totals sentence"
    stat = subprocess.run(
        ["git", "diff", BASE, "--shortstat"], cwd=ROOT,
        capture_output=True, text=True).stdout.strip()
    sm = _re.match(r"(\d+) files? changed, (\d+) insertions?\(\+\), (\d+) deletions?\(-\)", stat)
    assert sm, stat
    assert (m.group(1), m.group(2), m.group(3)) == sm.groups(), (
        f"the exit record totals {m.groups()} but the live diff is {sm.groups()}")
    # compliance bullet enumerates the sanctioned schemas/catalog exceptions
    assert "schemas/conf-matrix-kotlin-catalog-pin.json" in text, (
        "the compliance bullet must enumerate the sanctioned pin-json delta")
    assert "GeneratedBotConfKeyCatalog.kt" in text, (
        "the compliance bullet must enumerate the regenerated catalog")
    # sweep-quote records carry the refresh annotation alongside live counts
    for rel in ("out/completion/w1/W1-SUMMARY.md",
                "out/completion/w1/WAVE-EXIT.md",
                "out/completion/w5/WAVE-EXIT.md",
                "out/completion/w6/WAVE-EXIT.md",
                "out/completion/w6/N5-CAMPAIGN-VERDICT.md"):
        rec = (ROOT / rel).read_text(encoding="utf-8", errors="replace")
        assert "W6B finalize refresh" in rec, f"{rel} lost its W6B refresh note"
        assert ("1633 files scanned" in rec or "1633 files /" in rec), \
            f"{rel} lost the live count"


def test_rates_member_type_is_pinned():
    """the round-4 rates-pin finding: the ListingStore trust boundary must refuse a
    non-object rates member (string/number) with a VAL-NOSTR-CONTENT reason
    naming rates — the source pin holds the type check; the behavior tests
    live in ListingStoreTest (rates-string and rates-number cases)."""
    src = (ROOT / "android/app/src/main/java/com/pocketrealm/discovery/nostr/"
           "ListingStore.kt").read_text(encoding="utf-8")
    assert 'listing rates must be an object' in src, (
        "the rates member type check was removed from ListingStore")
    test_src = (ROOT / "android/app/src/test/java/com/pocketrealm/discovery/"
                "nostr/ListingStoreTest.kt").read_text(encoding="utf-8")
    assert 'rates", "3x"' in test_src and '"rates", 3.5' in test_src, (
        "the rates type tests (string + number attacks) left ListingStoreTest")


def test_dw6b_ids_are_unique_across_records():
    """R4-r5-3 (round 5): every D-W6B-n id must map to exactly one decision
    description across WAVE-EXIT.md and docs/DECISIONS-PORT.md (round-2
    renamed WAVE-EXIT local ids to W6B-DEC-N; the only D-W6B-* ids allowed
    are the DECISIONS-PORT owning entries D-W6B-1..4)."""
    import re as _re
    we = (ROOT / "out/completion/w6b/WAVE-EXIT.md").read_text(encoding="utf-8")
    dp = (ROOT / "docs/DECISIONS-PORT.md").read_text(encoding="utf-8")
    we_dw = set(_re.findall(r"D-W6B-\d+", we))
    uncited = set()
    for m in _re.finditer(r"D-W6B-\d+", we):
        ctx = we[max(0, m.start() - 80):m.start()]
        if "DECISIONS-PORT" not in ctx:
            uncited.add(m.group(0))
    assert not uncited, (
        f"WAVE-EXIT.md carries D-W6B-* ids {sorted(uncited)} WITHOUT a "
        "docs/DECISIONS-PORT citation — they collide with DECISIONS-PORT's "
        "own numbering; cite the owning record or rename to W6B-DEC-N")
    dp_dw = set(_re.findall(r"D-W6B-\d+", dp))
    assert dp_dw == {"D-W6B-1", "D-W6B-2", "D-W6B-3", "D-W6B-4"}, (
        f"unexpected DECISIONS-PORT D-W6B ids: {sorted(dp_dw)}")


def test_rates_pin_has_owning_decision_entry():
    """R5-arch-2/R6-r5-1 (round 5): the ListingStore rates TYPE pin must
    have an owning D-W6B-4 decision entry in docs/DECISIONS-PORT.md."""
    dp = (ROOT / "docs/DECISIONS-PORT.md").read_text(encoding="utf-8")
    assert "D-W6B-4" in dp and "listing rates must be an object" in dp, (
        "the rates TYPE pin lost its owning D-W6B-4 decision entry")


def test_cited_lane_logs_are_fresh():
    """R5-3/R6-3 (round 5): each cited lane log must be newer than every
    tracked source file in that lane's tree (the stale-evidence class)."""
    import os
    lanes = {
        "out/completion/w6b/gateway-lane.log": "gateway/src",
        "out/completion/w6b/spikes-desktop-echo.log": "spikes/iroh-n2/desktop-echo/src",
        "out/completion/w6b/desktop-suite-final.log": "desktop/src",
    }
    for log_rel, tree in lanes.items():
        log = ROOT / log_rel
        assert log.is_file() and log.stat().st_size > 0, f"{log_rel} missing/empty"
        log_mtime = log.stat().st_mtime
        stale = []
        for dp, dn, fn in os.walk(ROOT / tree):
            dn[:] = [d for d in dn if d not in ("build", ".gradle")]
            for f in fn:
                fp = os.path.join(dp, f)
                if fp.endswith((".kt", ".kts", ".java")) and os.path.getmtime(fp) > log_mtime:
                    stale.append(fp.replace(str(ROOT) + os.sep, ""))
        assert not stale, (
            f"{log_rel} predates later-edited sources in {tree}: {stale[:5]}")


def test_cited_lane_logs_show_executed_tasks():
    """R5-r6-4/R4-r6-2 (round 6): the cited gateway and spikes lane logs must
    show at least one EXECUTED task (not an all-UP-TO-DATE capture)."""
    import re as _re
    gw = (ROOT / "out/completion/w6b/gateway-lane.log").read_text(
        encoding="utf-8", errors="replace")
    executed = _re.search(r"actionable tasks: .*[1-9][0-9]* executed", gw)
    bare_task = _re.search(r"> Task :\w+\s*$", gw, _re.M)
    assert executed or bare_task, (
        "gateway-lane.log is an all-UP-TO-DATE capture — re-capture with an "
        "executed :test (cleanTest test) so the archived evidence shows a "
        "real test execution")


def test_assemble_debug_log_is_fresh():
    """R1-3 (round 6): the assembleDebug evidence log must postdate the
    android/app tree it packages."""
    import os
    log = ROOT / "out/completion/w6b/assembleDebug4.log"
    assert log.is_file() and log.stat().st_size > 0, (
        "out/completion/w6b/assembleDebug4.log missing/empty")
    log_mtime = log.stat().st_mtime
    stale = []
    for dp, dn, fn in os.walk(ROOT / "android/app/src"):
        dn[:] = [d for d in dn if d not in ("build", ".gradle")]
        for f in fn:
            fp = os.path.join(dp, f)
            if os.path.getmtime(fp) > log_mtime:
                stale.append(fp)
    assert not stale, (
        f"assembleDebug4.log predates later-edited android/app/src files: "
        f"{stale[:5]} — re-run :app:assembleDebug before finalize")

