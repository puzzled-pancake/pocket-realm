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
    assert match is not None and run.returncode != 0, (
        f"detekt in {cwd} did not fail with a weighted-issues count — the "
        "lane's detekt posture changed; refresh the pack and this pin "
        f"(rc={run.returncode})")
    return int(match.group(1))


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
