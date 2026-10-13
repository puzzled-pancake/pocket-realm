"""Wave-evidence provenance gate (R4-2, desktop-twin round 1).

The W0 evidence pack drifted from its own narrative: the desktop counts
artifact cited a log whose only content was an ANDROID run summary, the
android artifact claimed a "fresh clean full re-execution" while its own
cited log replayed a module FROM-CACHE, three different log names were
cited for the same android run, and WAVE-EXIT's delta arithmetic was
wrong. The numbers were measured; the provenance narrative was not.

This gate holds the pack to its own claims — whenever a counts artifact
exists in out/completion/w0 (a fresh clone without one skips silently,
the same posture as the counts-matching pin):
  1. the cited log EXISTS under w0/, ends BUILD SUCCESSFUL, and carries
     the SUITE'S OWN task markers — an android summary can never stand in
     for a desktop log again (and vice versa);
  2. when the artifact's note claims fresh/clean/re-executed runs, every
     test task the command names is a REAL EXECUTION line in that log —
     no FROM-CACHE, no UP-TO-DATE, or the wording changes.
"""
from __future__ import annotations

import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
W0 = ROOT / "out/completion/w0"

# artifact name -> (module marker task lines the log must carry, forbidden
# foreign markers) — the (a) leg: a log must be the SUITE'S OWN run.
SUITE_MARKERS = {
    "android-unit-counts.json": (
        ("> Task :app:testDebugUnitTest",
         "> Task :pocketrealm-llm:testDebugUnitTest",
         "> Task :pocketrealm-llm:test"),
        ("> Task :test ", "desktop"),  # an android log must not be the desktop run
    ),
    "desktop-test-counts.json": (
        ("> Task :test",),
        (":app:testDebugUnitTest",),  # a desktop log must not be the android run
    ),
}

# the words that trigger the (b) leg
_RE_EXECUTION_CLAIM = re.compile(r"re-execut|fresh|clean", re.IGNORECASE)
_TEST_TASKS = {
    "android-unit-counts.json": (
        ":app:testDebugUnitTest",
        ":pocketrealm-llm:testDebugUnitTest",
        ":pocketrealm-llm:test",
    ),
    "desktop-test-counts.json": (":test",),
}


def _artifact(name: str) -> dict | None:
    path = W0 / name
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def _task_status(log_text: str, task: str) -> str:
    """The execution status the log records for [task] ('' = executed)."""
    match = re.search(rf"^> Task {re.escape(task)}(?: (\S+))?\s*$", log_text, re.MULTILINE)
    return match.group(1) or "" if match else "<absent>"


def test_each_counts_artifact_cites_its_own_suite_log():
    for name, (required, forbidden) in SUITE_MARKERS.items():
        artifact = _artifact(name)
        if artifact is None:
            continue  # no artifact in this tree: nothing to hold accountable
        log_name = artifact.get("log", "")
        log_path = W0 / log_name
        assert log_path.is_file(), (
            f"{name} cites log {log_name!r} which does not exist under "
            "out/completion/w0 — archive the run the numbers came from")
        text = log_path.read_text(encoding="utf-8", errors="replace")
        assert "BUILD SUCCESSFUL" in text, (
            f"{name} cites {log_name!r} which records no BUILD SUCCESSFUL")
        for marker in required:
            assert marker in text, (
                f"{name} cites {log_name!r} which never runs this suite's "
                f"own task ({marker}) — the cited log is not this suite's run")
        for foreign in forbidden:
            assert foreign not in text, (
                f"{name} cites {log_name!r} which carries another suite's "
                f"marker ({foreign!r}) — an android summary must never stand "
                "in for a desktop log (or vice versa)")


def test_a_re_execution_claim_requires_actually_executed_test_tasks():
    for name, tasks in _TEST_TASKS.items():
        artifact = _artifact(name)
        if artifact is None:
            continue
        note = " ".join(
            str(artifact.get(field, "")) for field in ("generated", "suite")
        )
        if not _RE_EXECUTION_CLAIM.search(note):
            continue  # the artifact makes no re-execution claim
        log_name = artifact.get("log", "")
        log_path = W0 / log_name
        if not log_path.is_file():
            continue  # leg 1 already failed loudly on the missing log
        text = log_path.read_text(encoding="utf-8", errors="replace")
        for task in tasks:
            status = _task_status(text, task)
            assert status == "", (
                f"{name} claims a fresh/clean re-execution but its cited log "
                f"records {task} as {status or 'executed'!r} — re-run the "
                "suite with --no-build-cache (and the clean legs) or word "
                "the claim to match the run")


def test_w4_exit_record_suite_counts_match_the_counts_artifacts():
    """R4-r4-1/R5-W4r4-1 (W4 code-red round 4): every suite-count figure the
    W4 exit record quotes ("N classes / M tests", "M/N") must equal the
    counts artifact whose classes field agrees — a stale mid-wave number
    (desktop 1434 vs artifact 1439) sat in the record for a full round. The
    shorthand leg is SUITE-WORD-scoped ("desktop 1441/193") so non-suite
    numbers (the 222/222 ledger counts) are never held."""
    import json

    wave_exit = " ".join(
        (ROOT / "out/completion/w4/WAVE-EXIT.md").read_text(
            encoding="utf-8").split()
    )
    artifacts = {}
    for name in ("android-unit-counts.json", "desktop-test-counts.json"):
        path = ROOT / "out/completion/w0" / name
        if path.is_file():
            artifacts[name] = json.loads(path.read_text(encoding="utf-8"))
    if not artifacts:
        return  # no counts artifacts in this tree
    quoted = set(re.findall(r"(\d{2,4}) classes / (\d{2,4}) tests", wave_exit))
    quoted |= set(re.findall(
        r"(desktop|android)\s+(\d{2,4})/(\d{2,4})\b", wave_exit,
    ))
    normalized = set()
    for entry in quoted:
        if len(entry) == 3:
            suite, classes, tests = entry
            normalized.add((classes, tests))
        else:
            normalized.add(entry)
    for classes, tests in normalized:
        holders = [
            (name, a) for name, a in artifacts.items()
            if str(a.get("classes")) == classes
        ]
        # only canonical-suite-shaped pairs are held (other quoted numbers
        # - e.g. a 45/45 focused battery - have no counts artifact)
        for name, artifact in holders:
            assert str(artifact["tests"]) == tests, (
                f"the W4 exit record quotes {classes} classes / {tests} "
                f"tests but {name} records {artifact['tests']} - the stale "
                "mid-wave number must sync to the artifact (R4-r4-1)")


def test_xml_tree_digest_matches_the_counts_artifact_it_names():
    """R6-2 (W4 code-red round 3): xml-tree-digest-<label>.json is the
    archived snapshot of what the JUnit trees looked like when the counts
    artifacts were written — its per-tree totals must equal the counts
    artifact it names (the R4-r3-1 regen-bypass left a digest claiming
    desktop 1426/192 matches_artifact:true beside an artifact that said
    1434/193: the pair can never diverge again)."""
    import json

    digest_path = ROOT / "out/completion/w0/xml-tree-digest-w4.json"
    if not digest_path.is_file():
        return  # no digest snapshot in this tree
    digest = json.loads(digest_path.read_text(encoding="utf-8"))
    for artifact_name, tree in digest.get("trees", {}).items():
        artifact_path = ROOT / "out/completion/w0" / artifact_name
        assert artifact_path.is_file(), (
            f"the digest snapshot names {artifact_name} which does not "
            "exist — regenerate the digest and the artifact together")
        counts = json.loads(artifact_path.read_text(encoding="utf-8"))
        for field in ("tests", "failures", "errors", "skipped", "classes"):
            assert tree.get(field) == counts.get(field), (
                f"{artifact_name}: the digest snapshot records "
                f"{field}={tree.get(field)} but the artifact says "
                f"{counts.get(field)} — the snapshot is from a superseded "
                "run; re-run tools/archive_junit_tree_digest.py --label w4 "
                "right after regen_counts_w4.py")
        assert tree.get("matches_artifact") is True, (
            f"{artifact_name}: the digest itself records matches_artifact="
            f"{tree.get('matches_artifact')!r} — the exit snapshot was "
            "taken against a different tree state")


def test_era_stages_staging_implies_ledger_524_fixed_disposition():
    """R6-1 (W1 code-red round 1): ledger bug-hunt finding 5.24 is the exact
    defect the W1 charter item 1 closes — the moment the staging fix is
    present in the shared build driver, the canonical ledger entry a future
    implementer is pointed at must NOT still read 'status: unconfirmed' with
    'the next rebuild fails outright' as its standing text. The 5.25
    precedent is the convention: a disposition marker on the heading AND a
    fixed marker on the round-table row, plus the post-campaign update."""
    from pathlib import Path

    root = Path(__file__).resolve().parents[1]
    driver = (root / "tools/build_o09_realm_runtime.py").read_text(
        encoding="utf-8", errors="replace")
    staging_marker = '(bot_root / "PlayerbotLlmEraStages.generated.h").write_bytes'
    if staging_marker not in driver:
        return  # the fix is absent: the ledger owes nothing (fix removed?)
    ledger = (root / "docs/CROSS-PLATFORM-COMPLETION-LEDGER.md").read_text(
        encoding="utf-8", errors="replace")
    heading = "#### 5.24 "
    row = "| r2 | 5.24 |"
    heading_block = ledger.split(heading, 1)[1].split("\n#### ", 1)[0]
    row_line = next(
        (line for line in ledger.splitlines() if line.startswith(row)), None)
    assert row_line is not None, "ledger round table lost its 5.24 row"
    for label, text in (("5.24 heading block", heading_block), ("5.24 table row", row_line)):
        assert re.search(r"FIXED \d{4}-\d{2}-\d{2}|fixed \d{4}-\d{2}-\d{2}", text), (
            f"the era-stages staging fix is present in the build driver but "
            f"the ledger {label} carries no fixed-disposition marker — add "
            f"the 5.25-style annotation (docs/CROSS-PLATFORM-COMPLETION-LEDGER.md, "
            f"finding 5.24)")


# R4-1/R3-r2-1 (W1 code-red round 2): the wave's evidence pack (index +
# exit record) must never quote a gate output that no longer reproduces on
# the tree — the D70 truth class ("a claim's cited artifact must be the
# claim's own run"). The leak_sweep OK line is cheap to re-derive at gate
# time, so a quote of it is held against the LIVE tool.
_W1_EVIDENCE_PACK = (
    "out/completion/w1/W1-SUMMARY.md",
    "out/completion/w1/WAVE-EXIT.md",
)
_LEAK_SWEEP_OK_LINE = re.compile(
    r"OK \((\d+) files scanned, (\d+) allowlisted findings\)")


def test_w1_evidence_pack_quoted_leak_sweep_output_matches_the_live_tool():
    import subprocess
    import sys

    quoted = []
    for rel in _W1_EVIDENCE_PACK:
        path = ROOT / rel
        if not path.is_file():
            continue  # no such evidence file in this tree: nothing to hold
        text = path.read_text(encoding="utf-8", errors="replace")
        quoted.extend((rel, match) for match in _LEAK_SWEEP_OK_LINE.findall(text))
    if not quoted:
        return  # the pack quotes no leak_sweep output: nothing to re-derive
    live = subprocess.run(
        [sys.executable, str(ROOT / "tools/leak_sweep.py")],
        capture_output=True, text=True, cwd=ROOT,
    )
    live_line = _LEAK_SWEEP_OK_LINE.search(live.stdout)
    assert live.returncode == 0 and live_line is not None, (
        "the live leak_sweep run is not OK while the W1 evidence pack quotes "
        "an OK line — fix the tree or refresh the pack; live rc="
        f"{live.returncode}: {live.stdout[-400:]}")
    for rel, (files, findings) in quoted:
        assert (files, findings) == live_line.groups(), (
            f"{rel} quotes leak_sweep 'OK ({files} files scanned, {findings} "
            f"allowlisted findings)' but the live tool prints 'OK "
            f"({live_line.group(1)} files scanned, {live_line.group(2)} "
            "allowlisted findings)' — a post-quote edit moved a gate result; "
            "refresh the evidence pack (the D70 rule)")


# R2-1/R5-1 (W1 code-red round 3): era-state claims in the W1 evidence pack
# are held against the PINNED artifact bytes, not against memory of an older
# observation — ledger 5.24's original 2026-10-02 "PRE-era" state no longer
# describes the vanilla lane (rebuilt pre-wave), and a pack that repeats it
# misdirects W3 planning.
_ERA_STRINGS = (
    "PocketRealm.TimeframeStage",
    "vanilla-1.12.1",
    "The world is currently in patch",
)


def _pinned_world_dll(lockfile_rel: str) -> Path | None:
    import json

    lock_path = ROOT / lockfile_rel
    if not lock_path.is_file():
        return None
    for artifact in json.loads(lock_path.read_text(encoding="utf-8"))["artifacts"]:
        if artifact["path"].replace("\\", "/").endswith("pocket_world_runtime.dll"):
            return ROOT / artifact["path"]
    return None


def _dll_era_state(path: Path) -> dict[str, bool]:
    data = path.read_bytes()
    return {s: (s.encode() in data) for s in _ERA_STRINGS}


def test_w1_evidence_pack_era_state_claims_match_the_pinned_artifacts():
    pack_files = [
        ROOT / rel for rel in _W1_EVIDENCE_PACK if (ROOT / rel).is_file()
    ]
    if not pack_files:
        return  # no W1 evidence pack in this tree
    texts = {p: p.read_text(encoding="utf-8", errors="replace") for p in pack_files}
    # (a) a PRE-era claim about the vanilla lane requires the pinned vanilla
    #     world DLL to contain NONE of the era strings;
    if any("PRE-era" in text for text in texts.values()):
        vanilla = _pinned_world_dll("schemas/realm-runtime-lockfile-sqlite-win.json")
        assert vanilla is not None and vanilla.is_file(), (
            "the pack claims a PRE-era vanilla runtime but the vanilla "
            "sqlite-win lockfile pins no on-disk world DLL to check against")
        state = _dll_era_state(vanilla)
        assert not any(state.values()), (
            f"the pack claims the vanilla sqlite-win runtime is PRE-era but "
            f"the pinned world DLL ({vanilla}, mtime "
            f"{vanilla.stat().st_mtime}) carries era strings "
            f"{[s for s, present in state.items() if present]} — refresh the "
            "residual: the vanilla lane already carries the era strings")
    # (b) an era-carrier claim (the pack asserts era strings present for the
    #     fresh flavor DLLs) requires those pinned DLLs to carry ALL of them.
    if any("era strings present" in text.lower() for text in texts.values()):
        for lockfile in (
            "schemas/realm-runtime-lockfile-sqlite-win-tbc.json",
            "schemas/realm-runtime-lockfile-sqlite-win-wotlk.json",
        ):
            dll = _pinned_world_dll(lockfile)
            assert dll is not None and dll.is_file(), (
                f"the pack asserts era-carrier DLLs but {lockfile} pins no "
                "on-disk world DLL")
            state = _dll_era_state(dll)
            assert all(state.values()), (
                f"the pack asserts era strings present but the pinned world "
                f"DLL {dll} is missing "
                f"{[s for s, present in state.items() if not present]}")


# R4-r3-1 (W1 code-red round 3): the canonical JUnit XML trees behind the
# counts artifacts get clobbered by concurrent seats' focused Gradle runs
# (observed live in round 3: android tree hollowed to 1-2 classes mid-round).
# Whenever a tree EXISTS it must re-aggregate to exactly its artifact's
# numbers — only a genuinely ABSENT tree (a deliberate clean) skips. A
# partial tree is a failure, not a skip: the archived w0 logs keep the exit
# numbers auditable while the remedy is a canonical re-run.
def test_canonical_junit_trees_match_the_counts_artifacts():
    import glob
    import json
    import xml.etree.ElementTree as ET

    for artifact_name, xml_globs in (
        ("android-unit-counts.json",
         ("android/app/build/test-results/testDebugUnitTest/*.xml",
          "android/pocketrealm-llm/build/test-results/test/*.xml",
          "android/pocketrealm-llm/build/test-results/testDebugUnitTest/*.xml")),
        ("desktop-test-counts.json",
         ("desktop/build/test-results/test/*.xml",)),
    ):
        artifact_path = W0 / artifact_name
        if not artifact_path.is_file():
            continue  # no artifact in this tree: nothing to reconcile
        counts = json.loads(artifact_path.read_text(encoding="utf-8"))
        xml_files = [
            f for pattern in xml_globs for f in glob.glob(str(ROOT / pattern))
        ]
        if not xml_files:
            continue  # tree absent (deliberate clean): nothing to aggregate
        total = failed = errors = skipped = classes = 0
        for f in xml_files:
            r = ET.parse(f).getroot()
            total += int(r.get("tests", 0))
            failed += int(r.get("failures", 0))
            errors += int(r.get("errors", 0))
            skipped += int(r.get("skipped", 0))
            classes += 1
        assert (total, failed, errors, skipped, classes) == (
            counts["tests"], counts["failures"], counts["errors"],
            counts["skipped"], counts["classes"]), (
            f"the canonical JUnit tree for {artifact_name} aggregates "
            f"({total} tests / {classes} classes) but the artifact records "
            f"({counts['tests']} / {counts['classes']}) — the tree was "
            "clobbered or the artifact staled; re-run the canonical clean "
            "suite and regenerate (R4-r3-1)")


# R6-r3-1 (W1 code-red round 3): the wave exit must record the full
# repo-pytest posture — either a full-tree pytest artifact (log whose
# collected count covers the tests/ tree) or the recorded Decision-Log line
# that the full run is the orchestrator script's post-wave gate (the
# workflow charter forbids the implementer duplicating script-gated suites).
def test_w1_exit_records_the_full_repo_pytest_posture():
    wave_exit = ROOT / "out/completion/w1/WAVE-EXIT.md"
    if not wave_exit.is_file():
        return  # no W1 exit record in this tree
    text = wave_exit.read_text(encoding="utf-8", errors="replace")
    if "full repo-pytest" not in text:
        return  # the exit makes no full-suite claim: nothing to hold
    decisions = (ROOT / "docs/DECISIONS-PORT.md").read_text(
        encoding="utf-8", errors="replace")
    assert "R6-r3-1" in decisions and "orchestrator script's post-wave gate" in decisions, (
        "WAVE-EXIT defers the full repo-pytest run but DECISIONS-PORT carries "
        "no recorded line for that posture — add the D-entry (R6-r3-1)")


# R4-r4-1 (W1 code-red round 4): detekt figures quoted in the W1 evidence
# pack are held against the LIVE lanes, the same truth class as the
# leak_sweep quote pin — the round-2/3 pack claimed "zero new issues from
# W1 edits" while the wave's own round-1 edit had introduced a 491st
# desktop LongMethod (found in round 4).
_DETEKT_QUOTE_PATTERN = re.compile(
    r"(desktop|android)\s*(?::app)?\s*(\d+) weighted issues", re.IGNORECASE)
# a bare lane+figure pair ("android :app 153") rides the same sentence as a
# weighted-issues quote and is held to the same live count
_DETEKT_BARE_PATTERN = re.compile(
    r"(desktop|android)(?::app)?[ ,:]+(\d+)(?=,?\s+(?:dominated|weighted))",
    re.IGNORECASE)


def _live_detekt_count(cwd: Path, args: list[str]) -> int:
    import os
    import subprocess

    env = dict(os.environ)
    env["JAVA_HOME"] = r"E:\pocket_realm_toolchain\jdk\jdk-17.0.20.8-hotspot"
    env["GRADLE_USER_HOME"] = r"E:\pocket_realm_toolchain\gradle-home"
    run = subprocess.run(
        ["cmd", "/c", "gradlew.bat", *args, "--console=plain"],
        cwd=cwd, capture_output=True, text=True, env=env, timeout=600,
    )
    combined = (run.stdout or "") + (run.stderr or "")
    match = re.search(r"Analysis failed with (\d+) weighted issues", combined)
    if match is not None:
        # The R5-2 posture change (decision D84) made both lanes' detekt
        # gates GREEN; a weighted-issues failure is a real regression again
        # — refuse with the actionable state, never re-derive silently.
        raise AssertionError(
            f"detekt in {cwd} FAILED with {match.group(1)} weighted issues — "
            "a post-baseline regression; fix the code (or deliberately "
            "re-derive the baseline via `detektBaseline` with a reviewed "
            "delta) and refresh the pack quotes (the D70 rule)")
    assert run.returncode == 0, (
        f"detekt in {cwd} failed rc={run.returncode} without a "
        "weighted-issues line — the lane's build posture changed; refresh "
        "the pack and this pin")
    return 0  # the green posture: the baseline carries the reviewed debt


def test_w1_evidence_pack_quoted_detekt_counts_match_the_live_lanes():
    quotes = []
    for rel in _W1_EVIDENCE_PACK:
        path = ROOT / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        seen = set()
        for pattern in (_DETEKT_QUOTE_PATTERN, _DETEKT_BARE_PATTERN):
            for match in pattern.finditer(text):
                key = (match.group(1).lower(), int(match.group(2)))
                if key not in seen:
                    seen.add(key)
                    quotes.append((rel, *key))
    if not quotes:
        return  # the pack quotes no detekt figures: nothing to re-derive
    live = {
        "desktop": _live_detekt_count(
            ROOT / "desktop", ["detekt", "-PpocketAbi=x86_64"]),
        "android": _live_detekt_count(
            ROOT / "android",
            [":app:detekt", "-PpocketAbi=x86_64", "-PpocketLane=full"]),
    }
    for rel, lane, quoted in quotes:
        assert quoted == live[lane], (
            f"{rel} quotes '{lane} {quoted} weighted issues' but the live "
            f"{lane} detekt measures {live[lane]} — a post-quote edit moved "
            "the lane's detekt state; refresh the pack (the D70 rule)")


# R6-1/R2-r4-1 (W1 code-red round 4): focused reviewer Gradle runs are
# §2.4-sanctioned, so they must write OUTSIDE the canonical JUnit trees —
# both builds implement the `-Dpocket.focus=<label>` redirect and the exit
# record documents the exact command; this pin holds the three pieces
# together so the protocol cannot silently unwind.
def test_focused_gradle_runs_redirect_out_of_the_canonical_trees():
    # desktop: the -Ppocket.focus redirect in tasks.withType<Test>.
    desktop = ROOT / "desktop/build.gradle.kts"
    assert desktop.is_file(), f"missing build file: {desktop}"
    text = desktop.read_text(encoding="utf-8", errors="replace")
    assert 'gradleProperty("pocket.focus")' in text, (
        "desktop/build.gradle.kts no longer reads the pocket.focus property "
        "— focused desktop runs would write into the canonical JUnit trees "
        "again (R6-1/R2-r4-1)")
    assert "focused-test-results-" in text, (
        "desktop/build.gradle.kts no longer redirects focused runs to the "
        "isolated results dir (R6-1/R2-r4-1)")
    # android: AGP 9's AndroidUnitTest finalizes its report location at
    # creation (no redirect possible), so the lane's guarantee is the
    # snapshot/restore wrapper — which must exist and be index-tracked.
    wrapper = ROOT / "tools/focused_android_unit_test.py"
    assert wrapper.is_file(), (
        "the android focused-run lane lost its snapshot/restore wrapper "
        "(tools/focused_android_unit_test.py, R6-1/R2-r4-1)")
    import subprocess
    assert subprocess.run(
        ["git", "ls-files", "--error-unmatch", "tools/focused_android_unit_test.py"],
        cwd=ROOT, capture_output=True,
    ).returncode == 0, (
        "tools/focused_android_unit_test.py is not index-tracked — the "
        "convergence commit would drop the android focused-run guarantee "
        "(R5-r4-2)")
    wave_exit = ROOT / "out/completion/w1/WAVE-EXIT.md"
    if not wave_exit.is_file():
        return  # no exit record in this tree: the wiring pins above still held
    exit_text = wave_exit.read_text(encoding="utf-8", errors="replace")
    assert "pocket.focus" in exit_text and "focused_android_unit_test.py" in exit_text, (
        "the focused-run protocol is wired on both lanes but WAVE-EXIT.md "
        "does not document the exact commands — add them so reviewer seats "
        "stop clobbering the canonical trees (R2-r4-1)")


# R2-r5-3 (W1 code-red round 5): the android focused-run wrapper's
# snapshot/restore is guarded by an exclusive lock — a second concurrent seat
# must REFUSE LOUD instead of proceeding snapshot-less (its cleanup would
# destroy the first seat's canonical snapshot or leak focused results into
# the restored tree). The contract runs hermetically: a fixture tree, a stub
# Gradle runner, barrier-synchronized seats — no Gradle invocation.
def test_focused_android_wrapper_refuses_concurrent_seats(
        tmp_path, monkeypatch):
    import importlib.util
    import threading

    spec = importlib.util.spec_from_file_location(
        "focused_android_unit_test_under_test",
        ROOT / "tools/focused_android_unit_test.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    results = tmp_path / "test-results"
    results.mkdir(parents=True)
    canonical_before = {"TEST-a.xml": "aaa", "TEST-b.xml": "bbb"}
    for name, body in canonical_before.items():
        (results / name).write_text(body, encoding="utf-8")
    monkeypatch.setattr(module, "RESULTS", results)
    monkeypatch.setattr(module, "SNAPSHOT", tmp_path / "canonical-snapshot")
    monkeypatch.setattr(module, "LOCK", tmp_path / ".focused-android.lock")

    import time

    outcomes = {}
    start = threading.Barrier(2)

    def seat(tag: str) -> None:
        def stub_runner(argv, cwd, env):
            time.sleep(1.0)  # hold the lock long enough for the race to form
            # a real Gradle run creates its results dir; the stub must too
            module.RESULTS.mkdir(parents=True, exist_ok=True)
            (module.RESULTS / f"TEST-focused-{tag}.xml").write_text(
                f"focused-{tag}", encoding="utf-8")

            class _Result:
                returncode = 0
            return _Result()

        start.wait()
        outcomes[tag] = module.run_focused(
            ["SomeTest"], "x86_64", [], runner=stub_runner)

    threads = [threading.Thread(target=seat, args=(t,)) for t in ("s1", "s2")]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert sorted(outcomes.values()) == [0, 3], (
        f"exactly one seat must win the lock (rc 0) and the other must "
        f"refuse loud (rc 3): {outcomes}")
    assert module.ANOTHER_RUN_ACTIVE, "the refusal message constant went away"
    assert {p.name for p in results.iterdir()} == set(canonical_before), (
        "the canonical tree must be restored byte-for-byte — no focused "
        "result may leak into it and no snapshot may remain")
    assert not module.SNAPSHOT.exists() and not module.LOCK.exists(), (
        "the wrapper must clean up its snapshot and lock in every path")


# R2-r5-4/R3-r5-1/R4-r5-1 (W1 code-red round 5): a build-file comment that
# claims a residual "is recorded as an owner-visible residual … in
# out/completion/w1/WAVE-EXIT.md" must have its subject ACTUALLY present in
# the cited file — the D70 rule applied to code citations.
def test_build_file_residual_citations_resolve_into_wave_exit():
    build = ROOT / "android/app/build.gradle.kts"
    text = build.read_text(encoding="utf-8", errors="replace")
    if "recorded as an owner-visible residual" not in text:
        return  # no such citation in this tree
    wave_exit = ROOT / "out/completion/w1/WAVE-EXIT.md"
    assert wave_exit.is_file(), (
        "android/app/build.gradle.kts cites out/completion/w1/WAVE-EXIT.md "
        "for a recorded residual but the exit record does not exist")
    exit_text = wave_exit.read_text(encoding="utf-8", errors="replace")
    for marker in ("tmpdir", "%TEMP%"):
        assert marker in exit_text, (
            f"android/app/build.gradle.kts cites a recorded owner-visible "
            f"residual but WAVE-EXIT.md carries no '{marker}' record — add "
            "the residual bullet (R2-r5-4/R3-r5-1/R4-r5-1)")


# R4-r5-2/R6-r5-3 (W1 code-red round 5): a "(N tests)" quote naming a test
# class in the evidence pack must equal the class's canonical JUnit XML
# tests attribute — the same quoted-count truth class as the leak_sweep and
# detekt pins.
def test_w1_evidence_pack_quoted_class_counts_match_the_canonical_xml():
    import glob
    import xml.etree.ElementTree as ET

    quote_pattern = re.compile(r"([A-Za-z]+Test)`? \((\d+) tests\)")
    trees = [
        pattern
        for pattern in (
            "android/app/build/test-results/testDebugUnitTest/TEST-*.xml",
            "android/pocketrealm-llm/build/test-results/test/*.xml",
            "android/pocketrealm-llm/build/test-results/testDebugUnitTest/*.xml",
            "desktop/build/test-results/test/TEST-*.xml",
        )
    ]
    xml_by_class_suffix = {}
    for pattern in trees:
        for f in glob.glob(str(ROOT / pattern)):
            name = Path(f).name  # TEST-<fqcn>.xml
            xml_by_class_suffix[name] = f
    quotes_found = False
    for rel in _W1_EVIDENCE_PACK:
        path = ROOT / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for class_name, quoted in quote_pattern.findall(text):
            quotes_found = True
            candidates = [
                (xml_name, f)
                for xml_name, f in xml_by_class_suffix.items()
                if xml_name.endswith(f"{class_name}.xml")
            ]
            assert candidates, (
                f"{rel} quotes '{class_name} ({quoted} tests)' but no "
                "canonical JUnit XML exists for that class — re-run the "
                "canonical suite before trusting the quote")
            assert len(candidates) == 1, (
                f"{rel} quotes '{class_name}' ambiguously: {candidates}")
            actual = ET.parse(candidates[0][1]).getroot().get("tests")
            assert actual == quoted, (
                f"{rel} quotes '{class_name} ({quoted} tests)' but the "
                f"canonical XML records {actual} — refresh the pack (the "
                "D70 rule)")
    if not quotes_found and not xml_by_class_suffix:
        return  # no quotes and no tree: nothing to reconcile


# R5-R1-1/R2-r5-2/R5-r5-1/R6-r5-1 (W1 code-red round 5): the desktop
# focused-run protocol is verified at EXECUTION level — the textual wiring
# pin cannot catch a doFirst set against Gradle's property finalization
# (exactly how the round-4 desktop half shipped broken). Runs the documented
# command for one cheap class and asserts the focused dir received the XML
# while the canonical tree is untouched.
def test_desktop_focused_run_redirects_live(tmp_path):
    import subprocess
    import os

    label = "gate-probe"
    desktop = ROOT / "desktop"
    focused_dir = desktop / f"build/focused-test-results-{label}/test-results"
    canonical = desktop / "build/test-results/test"
    canonical_before = sorted(p.name for p in canonical.glob("TEST-*.xml"))
    env = dict(os.environ)
    env["JAVA_HOME"] = r"E:\pocket_realm_toolchain\jdk\jdk-17.0.20.8-hotspot"
    env["GRADLE_USER_HOME"] = r"E:\pocket_realm_toolchain\gradle-home"
    if focused_dir.exists():
        shutil.rmtree(focused_dir, ignore_errors=True)
    run = subprocess.run(
        ["cmd", "/c", "gradlew.bat", "test",
         "--tests=com.pocketrealm.database.DatabaseConfigPolicyTest",
         f"-Ppocket.focus={label}", "--console=plain"],
        cwd=desktop, capture_output=True, text=True, env=env, timeout=900,
    )
    assert run.returncode == 0, (
        f"the documented desktop focused command failed (rc={run.returncode}):"
        f" {run.stdout[-600:]} — the -Ppocket.focus redirect is broken "
        "(R5-r5-1/R6-r5-1)")
    focused_xmls = list(focused_dir.glob("TEST-*.xml"))
    assert focused_xmls, (
        "the focused run wrote no JUnit XML under "
        f"build/focused-test-results-{label}/test-results/ — the redirect "
        "did not take (R5-r5-1)")
    canonical_after = sorted(p.name for p in canonical.glob("TEST-*.xml"))
    assert canonical_before == canonical_after, (
        "the focused run touched the canonical desktop tree — the redirect "
        "did not take (R5-r5-1)")
    shutil.rmtree(desktop / f"build/focused-test-results-{label}",
                  ignore_errors=True)


# R6-r6-arch-1 (W1 code-red round 6): the android unit-test worker tmpdir
# residual is MEASURED, not assumed — WorkerTmpdirInstrumentationTest records
# the java.io.tmpdir a real AndroidUnitTest worker resolves on every
# canonical android run, and this gate holds the WAVE-EXIT record to that
# measurement: an E:-rooted measurement means the remedy landed (the record
# must then be retired); anything else means the open-gap record must stay.
def test_android_worker_tmpdir_measurement_matches_the_residual():
    measurement = ROOT / "android/app/build/tmpdir-instrumentation.txt"
    if not measurement.is_file():
        return  # no canonical android run has instrumented this tree yet
    measured = measurement.read_text(encoding="utf-8", errors="replace").strip()
    assert measured, "the tmpdir instrumentation wrote an empty measurement"
    print(f"\n[measured] android unit-test worker java.io.tmpdir = {measured}")
    wave_exit = ROOT / "out/completion/w1/WAVE-EXIT.md"
    exit_text = (
        wave_exit.read_text(encoding="utf-8", errors="replace")
        if wave_exit.is_file() else "")
    on_e_drive = measured.replace("\\", "/").lower().startswith(
        "e:/pocket_realm_toolchain/tmp")
    if on_e_drive:
        assert "retired" in exit_text or "RESOLVED" in exit_text, (
            f"the measured worker tmpdir is on E: ({measured}) — the remedy "
            "landed; retire the open-gap residual bullet in WAVE-EXIT.md "
            "(record the resolution)")
    else:
        for marker in ("tmpdir", "%TEMP%"):
            assert marker in exit_text, (
                f"the measured worker tmpdir is OFF the E: toolchain root "
                f"({measured}) but WAVE-EXIT.md no longer records the open "
                "tmpdir gap — restore the owner-visible residual (R6-r6-arch-1)")


# R3-1/R4-1/R5-1/R6-3 (W5 code-red round 1): a decision-record failure
# narrative must cite evidence that still exists. The W5 implementer overwrote
# both failing-run logs with the green reruns under the same archive names, so
# "died at FindICU ... (out/completion/w5/o09-tbc-arm64-build.log first
# attempt)" pointed at a green log. The convention is now: failing runs are
# archived under DISTINCT ...-first-attempt.log names with a RE-DERIVED /
# original provenance header, and these pins refuse any record whose cited
# artifact lacks the failure it claims.
_DECISIONS = ROOT / "docs" / "DECISIONS-PORT.md"
_W5 = ROOT / "out/completion/w5"
_FAILURE_SIGNATURES = (
    "CMake Error",
    "unrecognized arguments",
    "BUILD FAILED",
)
_FIRST_ATTEMPT_ARCHIVE_RE = re.compile(r"out/completion/w\d/[\w.-]*first-attempt[\w.-]*\.log")
_CITED_LOG_RE = re.compile(r"out/completion/w5/[\w.-]+\.log")
_BARE_FIRST_ATTEMPT_PHRASE = re.compile(r"out/completion/w\d/[\w.-]+\.log first attempt")


def test_decision_rows_never_cite_an_overwritten_log_name():
    """The overwrite convention is banned outright: a citation of the form
    '<archive>.log first attempt' names a file that (by the archive
    discipline) holds the LAST run under that name — the successful rerun.
    Failure evidence goes to ...-first-attempt.log instead."""
    text = _DECISIONS.read_text(encoding="utf-8", errors="replace")
    hits = _BARE_FIRST_ATTEMPT_PHRASE.findall(text)
    assert not hits, (
        "decision rows cite '<archive>.log first attempt' — that file holds "
        f"the latest run under the name, not the failure: {sorted(set(hits))}. "
        "Archive failing runs under a distinct ...-first-attempt.log name and "
        "cite that (the W5 R4-1 class)"
    )


def test_first_attempt_archives_carry_a_real_failure_signature():
    archives = sorted(_W5.glob("*first-attempt*.log")) if _W5.is_dir() else []
    if not archives:
        return  # no first-attempt evidence in this tree: nothing to hold
    for archive in archives:
        text = archive.read_text(encoding="utf-8", errors="replace")
        assert "RE-DERIVED FAILURE EVIDENCE" in text, (
            f"{archive.name} lacks its provenance header — a first-attempt "
            "archive must state what was re-derived, how, and from what "
            "(the original-run-overwritten disclosure)"
        )
        assert any(sig in text for sig in _FAILURE_SIGNATURES), (
            f"{archive.name} contains no failure signature — an archive named "
            "first-attempt must carry the failure it claims "
            f"(one of {list(_FAILURE_SIGNATURES)})"
        )


def test_w5_decision_rows_cited_logs_exist_and_match_their_claims():
    if not _W5.is_dir():
        return  # no W5 evidence in this tree: nothing to hold
    text = _DECISIONS.read_text(encoding="utf-8", errors="replace")
    w5_section_start = text.find("| D88 |")
    w5_section = text[w5_section_start:] if w5_section_start >= 0 else text
    cited = sorted(set(_CITED_LOG_RE.findall(w5_section)))
    assert cited, "the W5 decision rows cite no evidence logs"
    for rel in cited:
        path = ROOT / rel
        assert path.is_file(), (
            f"a W5 decision row cites {rel} but the archive does not contain "
            "it (the R4-1 class: cited evidence must exist)"
        )
        body = path.read_text(encoding="utf-8", errors="replace")
        if rel.endswith("first-attempt-findicu.log"):
            assert "FindICU" in body and "CMake Error" in body, (
                f"{rel} no longer carries the FindICU failure D88 cites"
            )
        if rel.endswith("assembleDebug-arm64-first-attempt.log"):
            assert "unrecognized arguments" in body, (
                f"{rel} no longer carries the buildGladioClientArm failure D89 cites"
            )


# R6-r2-1 (W5 code-red round 2): the source-offer provenance doc still
# claimed the AArch64 Gladio client "is built ... by tools/build_gladio_client.py"
# with a 530400-byte / 1d9663bb... artifact — a lane that errors on use since
# D45(7) retired it (W5 archived the death), and a digest matching nothing
# shipped. The shipped bytes are the vendored 498656 / c02fb727... package.
_WINE_PROVENANCE_DOC = ROOT / "docs/patches/wine-provider-provenance.md"


def test_gladio_provenance_row_cites_the_vendored_reality():
    doc = _WINE_PROVENANCE_DOC
    if not doc.is_file():
        return  # no provenance doc in this tree: nothing to hold
    text = doc.read_text(encoding="utf-8", errors="replace")
    row_start = text.find("**Legacy OpenGL (Gladio):**")
    assert row_start >= 0, "the provenance doc lost its Gladio row"
    row_end = text.find("- **", row_start + 1)
    row = text[row_start: row_end if row_end >= 0 else len(text)]
    assert "native/prebuilt-renderers/box64-gladio-eaa2a8d" in row, (
        "the Gladio provenance row must cite the vendored artifact path the "
        "image actually ships (the retired lane's reviewed bytes)"
    )
    assert "498656" in row and (
        "c02fb7275463bebcc3aa3fcf3e8e6de668bd2e6f39bda57052d3352801636d08" in row
    ), (
        "the Gladio provenance row must carry the shipped size + sha256 pins "
        "(498656 / c02fb727...) — the old 530400 / 1d9663bb row matched "
        "nothing shipped"
    )
    assert "is built" not in row or "build_gladio_client.py" not in row, (
        "the Gladio row still claims the retired ARM lane builds the client — "
        "tools/build_gladio_client.py rejects the ARM invocation outright "
        "(D45(7)/D89; out/completion/w5/assembleDebug-arm64-first-attempt.log)"
    )
    assert "1d9663bb" not in row and "530400" not in row, (
        "the stale 530400/1d9663bb artifact identity must not return"
    )


# R6-r3-1 (W5 code-red round 3): the wiki still named W5 as the pending owner
# of Android per-flavor runtimes after W5 exited — the exact falsifiable-
# pending-owner class R4-r2-1 fixed in the Kotlin copy. The copy must point
# at the recorded follow-up (D90) instead.
_GETTING_STARTED = ROOT / "docs/wiki/Getting-Started.md"


def test_wiki_runtime_lane_copy_points_at_the_recorded_followup():
    """R6-r3-1 + R6-r4-2: NO wiki page may name W5 as the pending owner of
    per-flavor runtime packaging — every page's runtime-lane copy points at
    the recorded follow-up (D90)."""
    wiki_dir = ROOT / "docs/wiki"
    if not wiki_dir.is_dir():
        return  # no wiki in this tree: nothing to hold
    offending = []
    d90_pages = []
    for page in sorted(wiki_dir.glob("*.md")):
        text = page.read_text(encoding="utf-8", errors="replace")
        if "(W5)" in text or "land (W5)" in text or "future work (W5)" in text:
            offending.append(f"{page.name}: pending-W5 phrasing")
        if "D90" in text:
            d90_pages.append(page.name)
    assert not offending, (
        "wiki pages name W5 as the pending owner of per-flavor runtime "
        f"packaging: {offending} — point at the recorded follow-up (D90) "
        "instead (the R4-r2-1 class, wiki edition)"
    )
    assert d90_pages, (
        "no wiki page cites the recorded follow-up (D90) — at least the "
        "runtime-lane pages must lead readers to the owning record"
    )


# R6-r3-2 (W5 code-red round 3): failure narratives in tools comments must
# cite failure archives, never green reruns — the round-1 overwrite class
# survived in tools/build_o09_realm_runtime.py after the decision-row pins
# covered only docs/DECISIONS-PORT.md.
_TOOLS_DIR = ROOT / "tools"


def test_tools_failure_citations_resolve_to_failure_archives():
    if not _TOOLS_DIR.is_dir():
        return
    import re
    citation = re.compile(r"out/completion/w\d/[\w.-]+\.log")
    offenders = []
    for py in sorted(_TOOLS_DIR.glob("*.py")):
        for lineno, line in enumerate(
                py.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            for match in citation.finditer(line):
                rel = match.group(0)
                path = ROOT / rel
                if not path.is_file():
                    offenders.append(f"{py.name}:{lineno}: cited archive missing: {rel}")
                    continue
                failure_claim = ("died" in line or "failed" in line.lower()
                                 or "death" in line)
                if failure_claim and "first-attempt" not in rel:
                    offenders.append(
                        f"{py.name}:{lineno}: a failure narrative cites the "
                        f"green-rerun archive {rel} — cite the "
                        "*-first-attempt.log failure archive instead")
    assert not offenders, (
        "tools/ comments cite W5 archives that are missing or green reruns "
        "for failure claims:\n  " + "\n  ".join(offenders)
    )


# R6-r3-3 (W5 code-red round 3): plan §9 requires every wave archive to carry
# WAVE-EXIT.md; the W5 decision rows exist, so the exit record must too.
def test_w5_exit_record_exists_once_the_wave_records_decisions():
    decisions = (ROOT / "docs/DECISIONS-PORT.md").read_text(
        encoding="utf-8", errors="replace")
    w5_dir = ROOT / "out/completion/w5"
    if "| D88 |" not in decisions or not w5_dir.is_dir():
        return  # no W5 record in this tree: nothing to hold
    exit_record = w5_dir / "WAVE-EXIT.md"
    assert exit_record.is_file(), (
        "out/completion/w5/WAVE-EXIT.md is missing while the W5 decision rows "
        "exist — plan §9 requires the exit record (end-state checklist, rounds "
        "count, findings ledger, waivers) before the wave can lawfully exit"
    )
    body = exit_record.read_text(encoding="utf-8", errors="replace")
    assert "code-red" in body.lower() and "findings" in body.lower(), (
        "the W5 exit record must carry the rounds count and the findings "
        "ledger (including the round-1/2 evidence-overwrite incident)"
    )


# R6-r4-4 / R3-r4-3 / R1-r4-2 (W5 code-red round 4): the WAVE-EXIT record is
# itself a citation surface — every negative-test archive it names must
# exist and carry the CURRENT remedy text (the round-1-era archives printed
# superseded remedies), and its "verified live" claims must resolve to
# archived runs, never journal narration.
_W5_EXIT = ROOT / "out/completion/w5/WAVE-EXIT.md"
_CURRENT_REMEDY_MARKERS = (
    "--flavor tbc --abi arm64-v8a",  # the freshness fence's interpolated remedy
    "build_o11_extractors.py --flavor tbc --abi arm64-v8a",  # the extractor remedy
)


def test_wave_exit_negative_evidence_archives_carry_the_current_remedies():
    if not _W5_EXIT.is_file() or not _W5.is_dir():
        return  # no W5 exit record in this tree: nothing to hold
    text = _W5_EXIT.read_text(encoding="utf-8", errors="replace")
    assert "round-2 live remedy verification" not in text, (
        "WAVE-EXIT cites the round-2 live remedy verification, which is "
        "journal narration, not an archived artifact — cite the re-derived "
        "*-corrupt-r4.log archives (the R6-r4-4 class)"
    )
    named = sorted(set(re.findall(r"negative-[\w.-]+\.log", text)))
    assert named, "WAVE-EXIT names no negative-test archives"
    for name in named:
        archive = _W5 / name
        assert archive.is_file(), (
            f"WAVE-EXIT names {name} but the archive does not exist (the "
            "round-1 cited-evidence class, exit-record edition)"
        )
        body = archive.read_text(encoding="utf-8", errors="replace")
        assert "rc=1" in body, f"{name} does not record the failing exit"
        if "freshness" in name:
            assert any(m in body for m in _CURRENT_REMEDY_MARKERS[:1]), (
                f"{name} prints a superseded remedy — re-derive the "
                "corruption negative under the current code"
            )
        if "extractor" in name:
            assert _CURRENT_REMEDY_MARKERS[1] in body, (
                f"{name} prints a superseded remedy without --abi — "
                "re-derive the corruption negative under the current code"
            )


def test_wave_exit_apk_claims_resolve_to_the_verification_archive():
    """R2-r4-2 / R3-r4-2: ABI-filter and byte-identity claims must cite the
    archived APK content verification, and that archive must carry the
    listing + per-lib MATCH rows against the CURRENT vanilla pins."""
    if not _W5_EXIT.is_file() or not _W5.is_dir():
        return
    text = _W5_EXIT.read_text(encoding="utf-8", errors="replace")
    if "abi filter" not in text.lower() and "abi-filter" not in text.lower():
        return  # the claim is not made in this record
    assert "apk-libs-verify.log" in text, (
        "WAVE-EXIT claims ABI-filter/byte-identity verification without "
        "citing apk-libs-verify.log (the R2-r4-2 class: mutable build "
        "output is not evidence)"
    )
    archive = _W5 / "apk-libs-verify.log"
    assert archive.is_file(), "the cited apk-libs-verify.log does not exist"
    body = archive.read_text(encoding="utf-8", errors="replace")
    assert "ABI filter: arm64-v8a only" in body, (
        "the apk verification archive lacks the ABI-filter listing"
    )
    assert "byte-identical to schemas/realm-runtime-lockfile-arm64-v8a.json" in body, (
        "the apk verification archive lacks the byte-identity verdict"
    )
    assert body.count("-> MATCH") >= 7, (
        "the apk verification archive does not carry per-lib MATCH rows for "
        "the packaged realm libs"
    )


# R6-r6-1 / R4-r6-3 / R4-r6-1 / R4-r6-2 (W5 code-red round 6): the WAVE-EXIT
# record is auditable content, not narrative — its findings ledger must cover
# every reviewer id the wave's tracked files cite (round 4 was silently
# dropped once), its rounds sections must match the LIVE-PROGRESS codered-rN
# sequence, its L0 sweep measurement must equal the live tool, and its
# focused-suite tally must cite an archived invocation whose counts it
# reproduces.
_W5_ID_PATTERN = re.compile(r"R[0-9]-r[0-9]-[0-9]+")
_WAVE_EXIT = ROOT / "out/completion/w5/WAVE-EXIT.md"
_LIVE_PROGRESS = ROOT / "out/completion/LIVE-PROGRESS.log"


def _w5_exit_text() -> str | None:
    if _WAVE_EXIT.is_file() and (ROOT / "docs/DECISIONS-PORT.md").is_file():
        if (ROOT / "docs/DECISIONS-PORT.md").read_text(
                encoding="utf-8", errors="replace").find("| D88 |") >= 0:
            return _WAVE_EXIT.read_text(encoding="utf-8", errors="replace")
    return None


def test_wave_exit_ledger_covers_every_cited_reviewer_id():
    """R6-r6-1: every reviewer id cited on a W5-touched surface must have a
    disposition row in W5's WAVE-EXIT — round 4 was silently dropped once.
    Scope: the files W5 created or edited (its own test pins, the build
    script, the Kotlin/doc surfaces it rewrote). Earlier waves' ids live in
    THEIR exit records and are out of this scan."""
    text = _w5_exit_text()
    if text is None:
        return
    wave_start = "d8a7806b540564f69c3a26d105468ee237fcaa9b"
    w5_surfaces = (
        "tests/test_w5_flavor_packaging_gates.py",
        "tests/test_renderer_packages_restage_contract.py",
        "tests/test_build_toolchain_residency.py",
        "android/app/build.gradle.kts",
        "android/app/src/main/java/com/pocketrealm/database/DatabaseEngine.kt",
        "android/app/src/main/java/com/pocketrealm/server/ServerRuntimeFiles.kt",
        "docs/patches/wine-provider-provenance.md",
        "docs/wiki/Getting-Started.md",
        "docs/wiki/Home-Screen.md",
        "tools/build_o09_realm_runtime.py",
        "tools/stage_renderer_packages.py",
    )
    import subprocess
    introduced = set()
    for rel in w5_surfaces:
        path = ROOT / rel
        if not path.is_file():
            continue
        current_ids = set(_W5_ID_PATTERN.findall(
            path.read_text(encoding="utf-8", errors="replace")))
        baseline = subprocess.run(
            ["git", "show", f"{wave_start}:{rel.replace('/', '/')}"],
            cwd=ROOT, capture_output=True, text=True)
        baseline_ids = set(_W5_ID_PATTERN.findall(
            baseline.stdout)) if baseline.returncode == 0 else set()
        introduced |= current_ids - baseline_ids
    assert introduced, "no W5-introduced reviewer ids found on the surfaces"
    missing = sorted(introduced - set(_W5_ID_PATTERN.findall(text)))
    assert not missing, (
        f"WAVE-EXIT's findings ledger omits W5-introduced reviewer ids "
        f"cited on W5's own surfaces: {missing} — the round that produced "
        "them needs a disposition row (the R6-r6-1 class: round 4 was "
        "dropped once); ids already present at the wave-start commit belong "
        "to earlier waves' records and are out of this scan"
    )


def test_wave_exit_rounds_ledger_matches_the_codered_sequence():
    text = _w5_exit_text()
    if text is None:
        return
    if not _LIVE_PROGRESS.is_file():
        return
    rounds = sorted(set(re.findall(
        r"codered-r(\d+) COMPLETE", _LIVE_PROGRESS.read_text(
            encoding="utf-8", errors="replace"))), key=int)
    assert rounds, "LIVE-PROGRESS records no completed code-red rounds"
    headers = re.findall(r"^### Round (\d+)", text, re.M)
    assert [int(r) for r in headers] == [int(r) for r in rounds], (
        f"WAVE-EXIT's rounds ledger {headers} does not match the executed "
        f"code-red sequence {rounds} — every round needs its section, in "
        "order, each with a findings count (the R4-r6-3 class: round 4 was "
        "missing and round 5 sat after the waivers)"
    )


def test_wave_exit_l0_sweep_line_matches_the_live_tool():
    import subprocess
    import sys
    text = _w5_exit_text()
    if text is None:
        return
    match = re.search(
        r"leak_sweep OK \((\d+) files / (\d+) allowlisted findings", text)
    if match is None:
        return  # the record quotes no sweep measurement
    live = subprocess.run(
        [sys.executable, str(ROOT / "tools/leak_sweep.py")],
        capture_output=True, text=True, cwd=ROOT)
    live_line = re.search(
        r"OK \((\d+) files scanned, (\d+) allowlisted findings\)", live.stdout)
    assert live.returncode == 0 and live_line is not None, (
        "the live leak_sweep run is not OK")
    assert match.groups() == live_line.groups(), (
        f"WAVE-EXIT's L0 sweep line quotes {match.groups()} but the live "
        f"tool prints {live_line.groups()} — refresh the record (the "
        "unsynced-quote class, WAVE-EXIT edition)"
    )


def test_wave_exit_focused_tally_cites_an_archived_invocation():
    text = _w5_exit_text()
    if text is None:
        return
    tally = re.search(
        r"focused pytest (\d+)/(\d+) across the (\d+) touched suites", text)
    if tally is None:
        return  # no focused tally claim in this record
    cited = re.findall(r"focused-r\d+\.txt", text)
    assert cited, (
        "the focused-suite tally names no archived invocation — archive the "
        "per-file results (out/completion/w5/focused-rN.txt) and cite it "
        "(the R4-r6-2 class)"
    )
    archive = _W5 / cited[0]
    assert archive.is_file(), f"the cited {cited[0]} does not exist"
    archive_lines = archive.read_text(encoding="utf-8", errors="replace").splitlines()
    # Self-reference guard: this file's own per-file line is written by the
    # same archive-regeneration run whose tally pin lives HERE — while that
    # final line is absent the archive is mid-regeneration, and comparing
    # against it would record this pin as its own failure.
    if not any("tests/test_wave_evidence.py ->" in l for l in archive_lines):
        return
    passed = failed = files = 0
    for line in archive_lines:
        per_file = re.search(r"tests/[\w_]+\.py -> (?:(\d+) failed, )?(\d+) passed", line)
        entry = re.search(r"tests/[\w_]+\.py -> ", line)
        if per_file is not None:
            files += 1
            failed += int(per_file.group(1) or 0)
            passed += int(per_file.group(2))
        elif entry is not None:
            files += 1
    assert not failed, (
        f"the archived invocation {cited[0]} records {failed} failing "
        "file run(s) — the close archive must be all-green"
    )
    assert (passed, files) == (int(tally.group(1)), int(tally.group(3))), (
        f"the archived invocation {cited[0]} records {passed} passed across "
        f"{files} files, not the {tally.group(1)}/{tally.group(3)} the "
        "record claims — re-run the archive or fix the tally"
    )
