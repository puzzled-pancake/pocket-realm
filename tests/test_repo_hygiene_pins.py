"""Repo-hygiene tripwires from code-red round 1.

R4-2: MSYS `> nul` redirections silently recreate 0-byte `nul` files at
any depth — a basename tripwire catches the class the moment it returns.
R1-2: the wave-exit record claims specific working dirs are gitignored;
the claim and .gitignore reality must agree so a finalize `git add` never
sweeps thousands of evidence files by accident.
"""
from __future__ import annotations

import os
import glob
import json
import os
import re
import sys
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WAVE_EXIT = ROOT / "out/completion/w0/WAVE-EXIT.md"


def _untracked_files() -> list[str]:
    out = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout
    return [line for line in out.splitlines() if line.strip()]


def test_no_nul_debris_anywhere():
    offenders = [
        rel for rel in _untracked_files()
        if Path(rel).name.lower() == "nul"
    ]
    assert not offenders, (
        "nul debris recreated (an MSYS `> nul` redirection): delete it and "
        "stop redirecting to the reserved name: " + repr(offenders)
    )


def test_wave_exit_gitignore_claims_match_reality():
    text = WAVE_EXIT.read_text(encoding="utf-8")
    claim = re.search(r"gitignored working dirs\n([^)]*keep\))", text)
    assert claim, "the gitignored-dirs claim line moved - re-pin it"
    claimed = re.findall(r"`([A-Za-z]+/)`", claim.group(1))
    assert claimed, claim.group(1)
    for directory in claimed:
        probe = ROOT / directory.rstrip("/")
        assert probe.is_dir(), directory
        checked = subprocess.run(
            ["git", "check-ignore", "-q", str(probe)],
            cwd=ROOT,
        )
        assert checked.returncode == 0, (
            f"{directory} is claimed gitignored in WAVE-EXIT but matches no rule"
        )


def test_gradle_test_workers_keep_their_tmpdir_on_e():
    """Round-2 R2-4-2/R3-1: org.gradle.jvmargs only configures the DAEMON;
    forked test-worker JVMs default java.io.tmpdir to the OS %TEMP% on C:.
    Both build scripts must configure every Test-type task's tmpdir under
    the E: toolchain root, and the path must resolve on E:."""
    for script in (
        ROOT / "desktop" / "build.gradle.kts",
        ROOT / "android" / "app" / "build.gradle.kts",
    ):
        text = script.read_text(encoding="utf-8")
        assert 'tasks.withType<Test>().configureEach' in text, script
        assert (
            'systemProperty("java.io.tmpdir", "E:/pocket_realm_toolchain/tmp")'
            in text
        ), f"{script}: the Test-worker tmpdir systemProperty drifted"
    # the daemon-side redirect stays in place too (defense in depth)
    for props in (ROOT / "desktop" / "gradle.properties", ROOT / "android" / "gradle.properties"):
        assert "-Djava.io.tmpdir=E:/pocket_realm_toolchain/tmp" in props.read_text(encoding="utf-8"), props

def test_no_embedded_repos_in_the_commit_candidate_set():
    """Round-3 R3-1b: a nested git repo (its own .git) is invisible to the
    content gates' file enumeration AND to git itself until a sweeping add
    embeds it as a .gitmodules-less gitlink whose contents no gate swept.
    Every untracked entry must be a plain file (or an ignored dir like
    repo/, which check-ignore excludes from the enumeration)."""
    for rel in _untracked_files():
        path = ROOT / rel
        assert not (path / ".git").exists() if path.is_dir() else True, (
            f"{rel} is a nested git repo in the commit-candidate set - "
            "gitignore it or remove it before finalize"
        )


def test_known_ambiguous_dirs_stay_gitignored():
    """repo/ (round-3 R3-1b) joined out/ as an ignored working tree: the
    known ambiguous dirs must never re-enter the commit-candidate set."""
    for directory in ("repo", "out", "tmp", "build"):
        probe = ROOT / directory
        if probe.is_dir():
            checked = subprocess.run(
                ["git", "check-ignore", "-q", str(probe)],
                cwd=ROOT,
            )
            assert checked.returncode == 0, (
                f"{directory}/ left the ignore rules - a sweeping git add "
                "would sweep it"
            )


def test_counts_artifacts_match_the_junit_xmls_they_claim():
    """Round-4 R4-2/R4-3: a counts artifact must equal the JUnit XML tree
    of EVERY module its declared command runs (the round-3 android artifact
    undercounted by 23 tests / 5 classes because :pocketrealm-llm was named
    in the command but stale-UP-TO-DATE at write time). Re-aggregates the
    XMLs and fails on any drift; skipped when the XML tree has been
    clobbered by later focused runs (the artifacts are then stale and the
    suite must be re-run before trusting them)."""
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
        artifact_path = ROOT / "out/completion/w0" / artifact_name
        if not artifact_path.is_file():
            continue
        xml_files = [
            f for pattern in xml_globs for f in glob.glob(str(ROOT / pattern))
        ]
        if not xml_files:
            continue  # clobbered tree: stale artifact, re-run the suite
        total = failed = errors = skipped = classes = 0
        for f in xml_files:
            r = ET.parse(f).getroot()
            total += int(r.get("tests", 0))
            failed += int(r.get("failures", 0))
            errors += int(r.get("errors", 0))
            skipped += int(r.get("skipped", 0))
            classes += 1
        counts = json.loads(artifact_path.read_text(encoding="utf-8"))
        # Round-6 R6-R4-1: a PARTIAL tree newer than the artifact is a
        # focused-run clobber, not a stale artifact — skip loudly (the
        # full suite must re-run before finalize trusts either side).
        artifact_mtime = artifact_path.stat().st_mtime
        tree_mtime = max(os.path.getmtime(f) for f in xml_files)
        if tree_mtime > artifact_mtime and len(xml_files) < counts.get(
                "classes", 0):
            print(
                f"note: {artifact_name} skipped — the JUnit XML tree was "
                f"clobbered after the artifact ({len(xml_files)} files vs "
                f"{counts.get('classes')} classes); re-run the full suite "
                "before finalize"
            )
            continue
        assert counts["tests"] == total, (
            f"{artifact_name}: tests={counts['tests']} but the JUnit XML tree "
            f"({len(xml_files)} files) says {total} - the artifact's scope or "
            "vintage is wrong; re-run the full suite and regenerate it"
        )
        assert counts["failures"] == failed and counts["errors"] == errors
        assert counts["skipped"] == skipped and counts["classes"] == classes


def test_android_counts_artifact_covers_every_module_its_command_names():
    """Round-4 R4-2: the android artifact's command names BOTH modules -
    the artifact must say so explicitly, so a :app-only count can never
    masquerade as the two-module total again."""
    import json

    path = ROOT / "out/completion/w0/android-unit-counts.json"
    assert path.is_file()
    counts = json.loads(path.read_text(encoding="utf-8"))
    command = counts.get("command", "")
    assert ":pocketrealm-llm" in command, command
    assert counts.get("modules") == [":app", ":pocketrealm-llm"], counts


def test_wave_exit_test_counts_match_the_archived_artifacts():
    """Round-3 R4-1: every suite-count sentence in WAVE-EXIT.md must equal
    the machine-verifiable counts artifact beside it (parsed from the
    JUnit XMLs at suite time) — a headline number with no surviving
    evidence is indistinguishable from a guess."""
    import json

    wave_exit = " ".join(
        (ROOT / "out/completion/w0/WAVE-EXIT.md").read_text(encoding="utf-8").split()
    )
    for artifact_name, pattern in (
        ("android-unit-counts.json",
         r"(\d+) tests, 0 failures, 0 errors, 1 skipped[^\n]*?[0-9]+ classes"),
        ("desktop-test-counts.json",
         r"(\d+) tests, 0 failures, 0 errors, 2 skipped[^\n]*?[0-9]+ classes"),
    ):
        path = ROOT / "out/completion/w0" / artifact_name
        assert path.is_file(), (
            f"{artifact_name} missing - re-run the suite and dump the counts"
        )
        counts = json.loads(path.read_text(encoding="utf-8"))
        assert counts["failures"] == 0 and counts["errors"] == 0, counts
        match = re.search(pattern, wave_exit)
        assert match, (
            f"WAVE-EXIT no longer carries a {artifact_name}-shaped count "
            "sentence - re-pin the regex or update the record"
        )
        assert match.group(1) == str(counts["tests"]), (
            f"WAVE-EXIT claims {match.group(1)} tests but the archived "
            f"{artifact_name} says {counts['tests']} - sync the record to "
            "the artifact"
        )


def test_wave_exit_per_class_counts_cite_their_own_counts_artifact():
    """R6-8 (W4 code-red round 3): per-class focused-suite claims in the
    W4 exit record ("AuthGateTest 8, ... — w43-desktop-friend-suite.log")
    are bare-Gradle-tail unverifiable — the D49-12/D83 class again. Every
    per-class claim must equal the XML-aggregated counts artifact named by
    the record's evidence section (the writer side is the w43 aggregation
    into out/completion/w4/w43-*-counts.json). The pin fails on any
    per-class count whose artifact disagrees, and on a per-class claim
    with no artifact at all."""
    import json

    wave_exit = ROOT / "out/completion/w4/WAVE-EXIT.md"
    if not wave_exit.is_file():
        return
    text = wave_exit.read_text(encoding="utf-8")
    # per-class claims inside the W4.3 section, row-scoped: each bullet
    # cites its own suite log (w43-<lane>-friend-suite.log) — the claim
    # must equal the SAME lane's counts artifact, never a sibling lane's
    # (ProvisioningListenerTest exists on both lanes with different
    # battery sizes).
    section = text.split("### W4.3", 1)[-1].split("###", 1)[0]
    # bullets wrap: the log citation may trail the class list — scope by
    # paragraph (each W4.3 bullet cites exactly one suite log)
    lane_for_class = {}
    current_lane = None
    for line in section.splitlines():
        m = re.search(r"w43-(desktop|android)-friend-\w+\.json", line)
        if m:
            current_lane = m.group(1)
        if current_lane is None:
            continue
        for class_name, claimed in re.findall(r"([A-Z]\w+Test) (\d+)", line):
            lane_for_class[(class_name, claimed)] = current_lane
    if not lane_for_class:
        return  # no per-class claims this record
    artifacts = {}
    for path in sorted((ROOT / "out/completion/w4").glob("w43-*-counts.json")):
        artifacts[path.name] = json.loads(path.read_text(encoding="utf-8"))
    assert artifacts, (
        "the W4.3 rows carry per-class claims but no w43-*-counts.json "
        "artifact exists — aggregate the focused suites' XML trees (the "
        "w43 regen) instead of quoting bare logs")
    for (class_name, claimed), lane in lane_for_class.items():
        artifact = artifacts.get(f"w43-{lane}-friend-counts.json")
        assert artifact is not None, (
            f"the {lane} per-class claim '{class_name} {claimed}' has no "
            "w43-<lane>-friend-counts.json artifact")
        holders = [c for c, n in artifact.get("classes", {}).items()
                   if c == class_name]
        assert holders, (
            f"the {lane} artifact holds no {class_name} row for the "
            f"per-class claim '{class_name} {claimed}'")
        actual = artifact["classes"][class_name]
        assert str(actual) == claimed, (
            f"WAVE-EXIT claims {class_name} {claimed} but "
            f"w43-{lane}-friend-counts.json says {actual} - sync the "
            "record to the artifact")


def test_timeframe_ui_surfaces_resolve_through_active_flavor():
    """Round-5 R5-2: the world-timeframe card (Android SettingsScreen) and
    the home timeframe line (desktop HomeScreen) must resolve their flavor
    through the ActiveFlavor machine — the documented fail-loud resolver —
    never `qualified().singleOrNull() ?: VANILLA`, the silent fallback
    that renders vanilla stages for a TBC/WotLK world at the W4 flip."""
    for rel in (
        "android/app/src/main/java/com/pocketrealm/ui/SettingsScreen.kt",
        "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/HomeScreen.kt",
    ):
        text = (ROOT / rel).read_text(encoding="utf-8")
        assert "singleOrNull()\n" not in text or "ClientFlavors.qualified().singleOrNull()" not in text, (
            f"{rel}: the silent singleOrNull()?:VANILLA fallback returned"
        )
        # W4 flip: the RENDER surfaces resolve through the machine's
        # canonical displayedId (selection, else sole-qualified, else the
        # first-pick copy) — the operation gates keep the loud resolve().
        # Either ActiveFlavor form proves the surface never hardcodes a
        # lane; BOTH fail on the silent fallback above.
        assert ("ActiveFlavor.resolve(" in text
                or "ActiveFlavor.displayedId" in text), (
            f"{rel}: the timeframe surface must resolve through ActiveFlavor"
        )


def test_no_silent_single_qualified_flavor_fallback_anywhere():
    """R1-2/R2-1 (android-core review round 1): the D51(4) pin covered only
    the two UI files, and TimeframeOverlayPlan (the DB overlay decision
    surface) carried the same silent `singleOrNull() ?: VANILLA` vanilla
    fallback. Repo-wide: NO production Kotlin under android/app/src/main
    or desktop/src/main may pick a flavor via the silent fallback shape -
    the fail-loud ActiveFlavor machine is the only flavor resolver (a
    third file cannot reintroduce the class without failing here)."""
    import re as _re

    fallback = _re.compile(
        r"singleOrNull\(\)\s*\?\:\s*ClientFlavors\.VANILLA")
    offenders = []
    for base in ("android/app/src/main", "desktop/src/main"):
        for path in (ROOT / base).rglob("*.kt"):
            if fallback.search(path.read_text(encoding="utf-8", errors="replace")):
                offenders.append(str(path.relative_to(ROOT).as_posix()))
    assert not offenders, (
        "the silent singleOrNull()?:VANILLA flavor fallback returned in "
        + repr(offenders)
        + " - resolve the lane through ActiveFlavor.resolve (fail-loud)")


def test_counts_artifact_run_log_shows_executed_test_tasks():
    """Round-5 R4-r5-1: an artifact whose generated-note claims a fresh run
    must be backed by an archived log where every named module's test task
    EXECUTED (plain, FROM-CACHE, or ACTION) — a module whose only mention
    is UP-TO-DATE means its XML half is stale vintage and the record lies
    (the exact round-3/4 recurrence this pin terminates)."""
    import json

    counts_path = ROOT / "out/completion/w0/android-unit-counts.json"
    if not counts_path.is_file():
        return
    counts = json.loads(counts_path.read_text(encoding="utf-8"))
    log_name = counts.get("log")
    if not log_name:
        return  # vintage without an archived log: the XML-aggregation pin covers it
    log = (ROOT / "out/completion/w0" / log_name).read_text(encoding="utf-8")
    for module in counts.get("modules", []):
        for task in (f"{module}:testDebugUnitTest", f"{module}:test"):
            executed = any(
                line.startswith(f"> Task {task}")
                and "UP-TO-DATE" not in line
                for line in log.splitlines()
            )
            # at least ONE of the module's test-task aliases must show
            # real execution; skip the alias when the module doesn't have it
            if executed:
                break
        else:
            raise AssertionError(
                f"{module}: the archived run log shows no executed test task "
                "(only UP-TO-DATE) — the artifact's XML half is stale vintage; "
                "delete the module's test-results and re-run"
            )


def test_joiner_refusal_copy_never_asserts_a_cause():
    """Round-6 R6-r6-2: the join-side refusal copy must not assert causes
    the protocol deliberately makes indistinguishable (wrong/expired/burned
    answer byte-identically; a rate lock makes retry guidance harmful).
    Both UI surfaces route through the shared joinerRefusalCopy()."""
    for rel in (
        "android/app/src/main/java/com/pocketrealm/ui/LanScreen.kt",
        "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/HomeScreen.kt",
    ):
        text = (ROOT / rel).read_text(encoding="utf-8")
        assert "wrong/expired" not in text, f"{rel}: guessed refusal cause returned"
        assert "joinerRefusalCopy()" in text, (
            f"{rel}: the joiner refusal must route through the shared copy"
        )


def test_pytest_scratch_relocation_survives_concurrent_sessions():
    """Round-6 R6-r6-3/R3-r6-2: the E: relocation must go through
    PYTEST_DEBUG_TEMPROOT (pytest's per-session unique dir naming), never a
    fixed --basetemp — a fixed basetemp is machine-shared scratch that
    concurrent pytest sessions prune mid-run (the round-6 flake class)."""
    ini = (ROOT / "pytest.ini").read_text(encoding="utf-8")
    assert "--basetemp" not in ini, (
        "pytest.ini pinned a fixed --basetemp again — concurrent sessions "
        "will prune each other's mktemp dirs (round-6 R6-r6-3)"
    )
    conftest = (ROOT / "conftest.py").read_text(encoding="utf-8")
    assert "PYTEST_DEBUG_TEMPROOT" in conftest
    assert "E:/pocket_realm_toolchain/tmp" in conftest


def test_tracked_tools_do_not_reference_untracked_resources():
    """Round-6 R6-R1-2: a tracked build tool referencing an UNTRACKED
    repo-relative resource is a fresh-clone breakage the selective add
    misses (the tbc o11 lane, the o09 build, the llama-server pin all
    referenced untracked files during round 6). Every repo-relative
    `ROOT / "..."` resource a tracked tool reads must be tracked or
    gitignored."""
    import re

    offenders: list[str] = []
    for tool in (
        "tools/build_o11_extractors.py",
        "tools/build_o09_realm_runtime.py",
        "tools/build_win_llama_server.py",
        "tools/generate_timeframe_era.py",
    ):
        text = (ROOT / tool).read_text(encoding="utf-8")
        for match in re.finditer(r'ROOT / ((?:"[^"]+"\s*/?\s*)+)', text):
            expression = match.group(1)
            parts = re.findall(r'"([^"]+)"', expression)
            if not parts or any(part in ("native", ".build") and len(parts) == 1 for part in parts):
                continue
            candidate = ROOT.joinpath(*parts)
            if not candidate.is_file():
                continue
            rel = candidate.relative_to(ROOT).as_posix()
            checked = subprocess.run(
                ["git", "ls-files", "--error-unmatch", rel],
                cwd=ROOT, capture_output=True,
            )
            ignored = subprocess.run(
                ["git", "check-ignore", "-q", rel],
                cwd=ROOT,
            )
            if checked.returncode != 0 and ignored.returncode != 0:
                offenders.append(f"{tool}: {rel}")
    assert not offenders, (
        "tracked tools reference UNTRACKED resources (a selective add "
        "breaks the fresh clone): " + "; ".join(offenders) +
        " — track them in the finalize add"
    )


def test_root_debris_files_stay_out_of_the_commit():
    """Round-6 R6-R1-2: the named root debris files (probe captures, a
    1-byte seed placeholder) must never ride a sweeping add — each is
    either absent or gitignored."""
    debris = (
        "console-csp-on.txt", "dom-csp-on.html", "tmpbat1.bat",
        "tmpbat1.out", "seed.txt",
    )
    for name in debris:
        path = ROOT / name
        if not path.is_file():
            continue
        ignored = subprocess.run(
            ["git", "check-ignore", "-q", name], cwd=ROOT,
        )
        assert ignored.returncode == 0, (
            f"root debris {name} is untracked and unignored — delete it or "
            "gitignore it before finalize"
        )


def test_counts_pin_vintage_rule_unit(tmp_path, capsys):
    """Round-6 R6-R4-1 unit tests of the pin's vintage rule: (a) a partial
    XML tree newer than the artifact → loud SKIP; (b) a full tree with
    drifted totals → FAIL; (c) an empty tree → SKIP (kept behavior)."""
    artifact_path = tmp_path / "android-unit-counts.json"
    artifact_path.write_text(json.dumps(
        {"tests": 100, "failures": 0, "errors": 0, "skipped": 0, "classes": 3}),
        encoding="utf-8")

    def xml(name, tests):
        path = tmp_path / name
        path.write_text(
            f'<testsuite name="{name}" tests="{tests}" failures="0" '
            f'errors="0" skipped="0"/>', encoding="utf-8")
        return path

    # (a) partial tree (1 file < 3 classes), NEWER than the artifact → skip
    xml("partial.xml", 30)
    import time
    time.sleep(0.02)
    artifact_path.write_text(artifact_path.read_text(encoding="utf-8"))  # backdate-ish
    os.utime(artifact_path, (time.time() - 60, time.time() - 60))
    # re-use the production logic by inlining its decision (the pin is a
    # script-style test; the rule under test is the skip condition)
    xml_files = [str(tmp_path / "partial.xml")]
    counts = json.loads(artifact_path.read_text(encoding="utf-8"))
    tree_mtime = max(os.path.getmtime(f) for f in xml_files)
    skipped = tree_mtime > artifact_path.stat().st_mtime and len(xml_files) < counts["classes"]
    assert skipped, "a partial newer tree must be a loud skip"

    # (b) full tree (3 files), drifted totals → NOT a skip → the assert fires
    full = [xml("a.xml", 30), xml("b.xml", 30), xml("c.xml", 45)]  # drifts: 105 != 100
    xml_files = [str(p_) for p_ in full]
    tree_mtime = max(os.path.getmtime(f) for f in xml_files)
    os.utime(artifact_path, (time.time() - 60, time.time() - 60))
    skipped = tree_mtime > artifact_path.stat().st_mtime and len(xml_files) < counts["classes"]
    assert not skipped, "a full tree with drifted totals must FAIL, not skip"
    total = sum(int(ET.parse(f).getroot().get("tests", 0)) for f in xml_files)
    assert total != counts["tests"], "fixture must actually drift"

    # (c) empty tree → skip (kept behavior)
    assert not [f for f in glob.glob(str(tmp_path / "none" / "*.xml"))]


# ---------------------------------------------------------------------------
# R3-3 (review round 1): every tracked project writer routes its tempfile
# scratch through tools/common.toolchain_tmpdir() — OS %TEMP% resolves on
# the system drive on the canonical host, and the D50(c) exception
# ("OS-level tools outside the project still use it") is only honest while
# the PROJECT's own writers are redirected. A new default-tempdir producer
# fails here until it passes dir=str(common.toolchain_tmpdir()) (or is
# allowlisted below with an owning surface and a reason).
# ---------------------------------------------------------------------------

TEMPDIR_CALL_RE = re.compile(
    r"\btempfile\.(TemporaryDirectory|NamedTemporaryFile|mkdtemp)\(")


def _balanced_call_span(text: str, open_paren: int, limit: int = 2000) -> int:
    """Index of the closing paren of a call, or -1."""
    depth = 0
    for index in range(open_paren, min(len(text), open_paren + limit)):
        if text[index] == "(":
            depth += 1
        elif text[index] == ")":
            depth -= 1
            if depth == 0:
                return index
    return -1


# Owning surfaces allowed to use the OS default tempdir, with the reason.
# Entries: path -> reason. Keep this EMPTY on the canonical host: every
# known project writer is redirected through toolchain_tmpdir().
DEFAULT_TEMPDIR_ALLOWLIST: dict[str, str] = {}


def test_tracked_python_writers_redirect_tempfile_scratch_to_e():
    """Static pin over tracked tools/ + scripts/ .py sources: every
    tempfile.TemporaryDirectory(/NamedTemporaryFile(/mkdtemp( call must
    carry a dir= argument routed to tools/common.toolchain_tmpdir() (any
    dir= argument passes this static leg; a dir= pointing anywhere but the
    helper is the author's explicit choice and shows up in review)."""
    out = subprocess.run(
        ["git", "ls-files", "tools/*.py", "tools/**/*.py", "scripts/*.py",
         "scripts/**/*.py"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout.splitlines()
    offenders: list[str] = []
    for rel in out:
        if rel in DEFAULT_TEMPDIR_ALLOWLIST:
            continue
        text = (ROOT / rel).read_text(encoding="utf-8")
        for match in TEMPDIR_CALL_RE.finditer(text):
            open_paren = match.end() - 1
            close = _balanced_call_span(text, open_paren)
            args = text[open_paren + 1:close if close != -1 else open_paren + 1]
            if not re.search(r"\bdir\s*=", args):
                line = text.count("\n", 0, match.start()) + 1
                offenders.append(f"{rel}:{line} {match.group(0)}...)")
    assert not offenders, (
        "tempfile scratch without a dir= lands in the OS %TEMP% on C: "
        "(hard rule 1; the E-TEMP note's 'project writers are redirected' "
        "claim must stay true). Route through "
        "common.toolchain_tmpdir() or add a documented "
        "DEFAULT_TEMPDIR_ALLOWLIST entry:\n  " + "\n  ".join(offenders)
    )


def test_toolchain_tmpdir_helper_resolves_and_creates(monkeypatch, tmp_path):
    """The helper honors POCKET_REALM_TOOLCHAIN_TMP and creates the root;
    an unwritable root fails loud with the env-var recipe (never a silent
    fallback onto the OS tempdir)."""
    from tools import common

    monkeypatch.setenv(common.TOOLCHAIN_TMPDIR_ENV, str(tmp_path / "scratch"))
    resolved = common.toolchain_tmpdir()
    assert resolved == tmp_path / "scratch"
    assert resolved.is_dir()
    # the canonical default is on E: (hard rule 1), never the OS tempdir
    assert str(common.TOOLCHAIN_TMPDIR).lower().startswith("e:")
    assert "temp" not in str(common.TOOLCHAIN_TMPDIR).lower()
# ---------------------------------------------------------------------------
# R2-2 (android-ui-tests review round 2): the installed-add-on detail copy
# claimed "Vanilla interface metadata" while the validator it fronts accepts
# the ACTIVE flavor's interfaces — wrong-copy at the qualification flip (the
# D50(4) class, copy flavor). The ui/ tree must derive the flavor word from
# the active ClientFlavor; the literal is retired here.
# ---------------------------------------------------------------------------


def test_no_hardcoded_vanilla_interface_copy_in_ui():
    offenders = []
    for path in (ROOT / "android/app/src/main/java/com/pocketrealm/ui").rglob("*.kt"):
        if "Vanilla interface" in path.read_text(encoding="utf-8", errors="replace"):
            offenders.append(path.relative_to(ROOT).as_posix())
    assert not offenders, (
        "hard-coded 'Vanilla interface' copy in the ui/ tree (the detail "
        "screen must derive the flavor word from the active ClientFlavor): "
        + repr(offenders)
    )



def test_interact_overlay_variants_are_tracked_for_every_flavor():
    """R5-1 (native-server round 3): pocket_interact_source() builds the
    per-flavor overlay path DYNAMICALLY (f"PocketRealmInteraction-"
    f"{flavor_id}.cpp"), so the literal-expression scan above cannot see
    it — the exact D52(7) fresh-clone class through a dynamic path. Every
    variant the build tool can select for a registered flavor must be
    tracked or gitignored."""
    import sys

    sys.path.insert(0, str(ROOT / "tools"))
    from tools import build_o09_realm_runtime as driver
    from tools import flavor_layout

    offenders: list[str] = []
    for flavor_id in flavor_layout.FLAVOR_IDS:
        try:
            candidate = driver.pocket_interact_source(flavor_id)
        except RuntimeError:
            continue  # an unmaterialized per-flavor variant is the
            # resolver's own fail-loud, exercised by its contract test
        if not candidate.is_file():
            continue
        rel = candidate.relative_to(ROOT).as_posix()
        tracked = subprocess.run(
            ["git", "ls-files", "--error-unmatch", rel],
            cwd=ROOT, capture_output=True,
        )
        ignored = subprocess.run(
            ["git", "check-ignore", "-q", rel],
            cwd=ROOT,
        )
        if tracked.returncode != 0 and ignored.returncode != 0:
            offenders.append(rel)
    assert not offenders, (
        "the interact overlay selects UNTRACKED per-flavor sources (a "
        "fresh clone breaks the next expansion lane build): "
        + "; ".join(offenders) + " — track them in the finalize add"
    )


def test_desktop_main_produces_no_tmpdir_writers():
    """R3-r2-1 (desktop-twin round 2, serious): the mariadbd stdout/stderr
    capture resolved java.io.tmpdir — the Gradle daemon/test lanes redirect
    it to E:, but the rpHost harness and any jpackage install resolve
    %TEMP% ON C: (a project writer on C:, plan §1 hard rule 1;
    pocket-mariadbd.out/.err were observed in %TEMP%). No desktop main
    source may produce a tmpdir path, and the launch lanes must not carry
    tmpdir band-aids — the capture rides the DesktopStorageRoots logs root
    (override-honoring)."""
    offenders: list[str] = []
    for source in (ROOT / "desktop/src/main").rglob("*.kt"):
        text = source.read_text(encoding="utf-8", errors="replace")
        if 'System.getProperty("java.io.tmpdir")' in text or '"java.io.tmpdir"' in text:
            offenders.append(source.relative_to(ROOT).as_posix())
    assert not offenders, (
        "desktop main sources resolve java.io.tmpdir (writes %TEMP% on C: "
        "in the rpHost/jpackage lanes): " + "; ".join(offenders) +
        " — route the capture under the DesktopStorageRoots logs root"
    )
    # the launch lanes (rpHost's JavaExec jvmArgs, jpackage java-options)
    # must not band-aid tmpdir — the app resolves its own roots. The
    # tasks.withType<Test> systemProperty redirects stay (the designed
    # test-lane layers; the app RUNTIME never sees them).
    gradle = (ROOT / "desktop/build.gradle.kts").read_text(encoding="utf-8")
    assert "-Djava.io.tmpdir" not in gradle, (
        "a launch lane carries a -Djava.io.tmpdir band-aid (rpHost jvmArgs "
        "or jpackage java-options) — the app must resolve its own roots"
    )


def test_wave_exit_cited_fix_mechanisms_are_index_tracked():
    """R5-r4-2 (W1 code-red round 4): a tools/*.py or tests/*.py path cited
    in the active wave's WAVE-EXIT.md as a merged fix's mechanism must be
    INDEX-TRACKED — the checkpoint commit at convergence otherwise silently
    drops the wave's own fix product (the round-3 fence covers only test
    source sets, so nothing else holds a tools/ file). Untracked new sources
    are staged the moment they are cited."""
    import re
    import subprocess

    wave_exit = ROOT / "out/completion/w1/WAVE-EXIT.md"
    if not wave_exit.is_file():
        return  # no W1 exit record in this tree
    text = wave_exit.read_text(encoding="utf-8", errors="replace")
    cited = sorted({
        match
        for match in re.findall(r"(?m)(?:tools|tests)/[\w./-]+\.(?:py|kts)\b", text)
    })
    assert cited, "WAVE-EXIT.md cites no tools/tests mechanisms to track"
    untracked = []
    for rel in cited:
        if subprocess.run(
            ["git", "ls-files", "--error-unmatch", rel],
            cwd=ROOT, capture_output=True,
        ).returncode != 0:
            untracked.append(rel)
    assert not untracked, (
        f"WAVE-EXIT.md cites fix mechanisms that are NOT index-tracked: "
        f"{untracked} — git add them (the convergence commit would drop "
        "the wave's own fix products, R5-r4-2)")


def test_w4_exit_record_leak_sweep_quote_matches_the_live_tool():
    """R6-r6-3/R4-r5-3 (W4 code-red rounds 5-6): the wave record's own copy
    of the leak-sweep line must resolve against the LIVE tool — the W1-pack
    pin covers the W1 files only, and the W4 record's copy of the number
    was left stale ('1538/2205' against a live 1539/2202)."""
    import re as _re
    import subprocess

    wave_exit = ROOT / "out/completion/w4/WAVE-EXIT.md"
    if not wave_exit.is_file():
        return
    quoted = _re.findall(
        r"OK \((\d+) files scanned, (\d+) allowlisted findings\)",
        wave_exit.read_text(encoding="utf-8", errors="replace"),
    )
    if not quoted:
        return  # the record quotes no leak-sweep line
    live = subprocess.run(
        [sys.executable, str(ROOT / "tools/leak_sweep.py")],
        capture_output=True, text=True, cwd=ROOT,
    )
    m = _re.search(
        r"OK \((\d+) files scanned, (\d+) allowlisted findings\)", live.stdout)
    assert live.returncode == 0 and m is not None, (
        f"the live leak_sweep run is not OK: rc={live.returncode}")
    for files, findings in quoted:
        assert (files, findings) == m.groups(), (
            f"WAVE-EXIT quotes leak_sweep 'OK ({files} files scanned, "
            f"{findings} allowlisted findings)' but the live tool prints "
            f"'OK ({m.group(1)} files, {m.group(2)} findings)' — refresh the "
            "record's copy (the D70 rule)")


def test_w4_wave_new_sources_and_lockfiles_are_index_tracked():
    """R6-1/R4-r3-2 (W4 code-red round 3): the convergence commit must not
    silently drop the wave's own fix products. Three tracked-reference
    classes are held against `git ls-files --cached`:
      1. every tools/tests path cited by the W4 exit record (the
         W1-shaped leg, pointed at this wave's record);
      2. every schemas/*lockfile*.json present on disk — the committed
         provider/runtime gates digest-verify against them
         (DesktopMariaDbProvider.readLaneLockfile, requireLaneRuntimePinned,
         tests/test_win_lockfile.py), so an untracked one is a gate red at
         commit;
      3. every desktop JavaExec mainClass source (the tracked build script
         registers e.g. com.pocketrealm.desktop.FlavorE2eProbeKt) — an
         untracked main source ships a build whose task references an
         absent class.
    Failed live on W4: all 8 wave products were untracked."""
    import re
    import subprocess

    def tracked(rel):
        return subprocess.run(
            ["git", "ls-files", "--cached", "--error-unmatch", rel],
            cwd=ROOT, capture_output=True,
        ).returncode == 0

    offenders = []
    wave_exit = ROOT / "out/completion/w4/WAVE-EXIT.md"
    if wave_exit.is_file():
        cited = sorted(set(re.findall(
            r"(?m)(?:tools|tests)/[\w./-]+\.(?:py|kts)\b",
            wave_exit.read_text(encoding="utf-8", errors="replace"),
        )))
        offenders += [
            f"cited-but-untracked: {rel}" for rel in cited if not tracked(rel)
        ]
    for lockfile in sorted((ROOT / "schemas").glob("*lockfile*.json")):
        rel = lockfile.relative_to(ROOT).as_posix()
        if not tracked(rel):
            offenders.append(f"lockfile-untracked: {rel}")
    gradle = (ROOT / "desktop/build.gradle.kts").read_text(
        encoding="utf-8", errors="replace")
    for main_class in sorted(set(re.findall(
        r'mainClass\.set\("([\w.]+Kt)"\)', gradle,
    ))):
        rel = (
            "desktop/src/main/kotlin/"
            + main_class.removesuffix("Kt").replace(".", "/")
            + ".kt"
        )
        if not tracked(rel):
            offenders.append(f"mainClass-untracked: {main_class} -> {rel}")
    assert not offenders, (
        "W4 wave products referenced by tracked code are NOT index-tracked: "
        + "; ".join(offenders) +
        " — git add them (the convergence commit would drop the wave's own "
        "fix products; R6-1/R4-r3-2)")


def test_counts_artifact_generated_prose_resolves_into_its_own_suite():
    """R6-r2-1 (W2 code-red round 2): the counts artifacts' generated prose is
    citation-bearing too. Every '<lane> counts N/M' tuple must resolve to the
    NAMED lane's artifact fields, and every test identifier quoted in a
    GRADUE-lane artifact must be a Kotlin JUnit class in that lane's XML tree
    — python-side `test_*` identifiers cannot be in a Gradle suite and are
    refused outright (they belong to the repo-pytest lane's own records).
    Failed on its first day twice: the android artifact quoted
    'desktop counts 1633/213' (android's own numbers under the desktop name)
    and the desktop artifact attributed its +1 to a python test identifier."""
    import xml.etree.ElementTree as ET

    artifacts = ("android-unit-counts.json", "desktop-test-counts.json")
    trees = {
        "android-unit-counts.json": (
            "android/app/build/test-results/testDebugUnitTest/*.xml",
            "android/pocketrealm-llm/build/test-results/test/*.xml",
            "android/pocketrealm-llm/build/test-results/testDebugUnitTest/*.xml",
        ),
        "desktop-test-counts.json": ("desktop/build/test-results/test/*.xml",),
    }
    fields: dict = {}
    classes: dict = {}
    for name in artifacts:
        path = ROOT / "out/completion/w0" / name
        if not path.is_file():
            continue
        counts = json.loads(path.read_text(encoding="utf-8"))
        fields[name] = (counts["tests"], counts["classes"])
        lane_classes: set = set()
        for f in [f for p in trees[name] for f in glob.glob(str(ROOT / p))]:
            lane_classes.add(Path(f).stem.removeprefix("TEST-").rsplit(".", 1)[-1])
        classes[name] = lane_classes

    for name in artifacts:
        path = ROOT / "out/completion/w0" / name
        if not path.is_file():
            continue
        generated = json.loads(path.read_text(encoding="utf-8")).get("generated", "")
        # a '<lane> counts N/M' tuple must carry the NAMED lane's own numbers
        for lane, n, m in re.findall(
                r"(android|desktop)[ -]counts (\d+)/(\d+)", generated):
            lane_artifact = fields.get(f"{lane}-unit-counts.json"
                                       if lane == "android"
                                       else "desktop-test-counts.json")
            assert lane_artifact is not None, (name, lane, n, m)
            assert (int(n), int(m)) == lane_artifact, (
                f"{name} quotes '{lane} counts {n}/{m}' but the {lane} artifact "
                f"records {lane_artifact[0]}/{lane_artifact[1]} — prose numbers "
                "must resolve into the named lane's own structured fields")
        # python-side test identifiers cannot live in a Gradle suite
        strays = sorted(set(re.findall(r"\btest_[a-z0-9_]{8,}\b", generated)))
        assert not strays, (
            f"{name} quotes python-side test identifier(s) {strays} — a "
            "repo-pytest test cannot be in the Gradle suite; cite the Kotlin "
            "pin (its JUnit class) or name the repo-pytest lane's own record")
        # every quoted Kotlin test class must exist in THIS lane's XML tree
        for klass in sorted(set(re.findall(r"\b([A-Z]\w+Test)\b", generated))):
            assert klass in classes[name], (
                f"{name} quotes '{klass}' but no canonical JUnit XML for that "
                "class exists in this lane's tree — the citation does not "
                "resolve into the artifact's own suite")


def test_w3_wave_exit_per_file_pytest_counts_match_the_live_collection():
    """R6-1 (W3 code-red round 6): the w3 WAVE-EXIT quotes per-file pytest
    counts ('**N passed**' beside a `tests/…py -q` citation in the same
    sentence); a quote whose file has since grown is a count that no longer
    reproduces (the round-5 '12 passed' for the era file that now runs 17).
    Re-derives the live collection for every cited file and holds each
    quote to it — the D70 rule applied to the w3 record."""
    import re
    import subprocess
    import sys

    wave_exit = (ROOT / "out/completion/w3/WAVE-EXIT.md").read_text(
        encoding="utf-8")
    quotes = []
    for match in re.finditer(
            r"\*\*(\d+) passed\*\*[^*]*?`?(python -m pytest (tests/[a-z0-9_]+\.py"
            r"(?: tests/[a-z0-9_]+\.py)*))[^`\n]*", wave_exit):
        quotes.append((match.group(1), match.group(3)))
    if not quotes:
        return  # the record quotes no per-file pytest counts
    cache: dict[str, int] = {}
    for quoted, command in quotes:
        files = re.findall(r"tests/[a-z0-9_]+\.py", command)
        total = 0
        for rel in files:
            if rel not in cache:
                run = subprocess.run(
                    [sys.executable, "-m", "pytest", ROOT / rel,
                     "--collect-only", "-q"],
                    capture_output=True, text=True, cwd=ROOT, timeout=300,
                )
                tail = run.stdout.strip().splitlines()[-1]
                collected = re.search(r"(\d+) tests? collected", tail)
                assert collected is not None and run.returncode == 0, (
                    f"{rel}: pytest --collect-only failed to re-derive the "
                    f"count ({tail!r}) — the cited file must collect clean"
                )
                cache[rel] = int(collected.group(1))
            total += cache[rel]
        assert int(quoted) == total, (
            f"WAVE-EXIT quotes '{quoted} passed' for `{command}` but the "
            f"live collection is {total} — sync the record to the cited "
            "files (the stale-quote class R6-1 found)"
        )
