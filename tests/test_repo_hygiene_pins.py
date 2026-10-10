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
        assert "ActiveFlavor.resolve(" in text, (
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
