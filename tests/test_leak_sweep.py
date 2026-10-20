"""Contract for tools/leak_sweep.py: the §4.6 cross-flavor leak sweep exits 1
on banned flavor-identity literals outside the allowlist, suppresses exactly
the allowlisted paths (and exactly the covered literals on scoped entries),
decodes hex/binary/octal numeric forms before the banned-number match, and
reports machine-readable findings under --json. A scoped allowlist entry whose
target file no longer contains any of its literals is itself a (stale-entry)
config error."""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "tools" / "leak_sweep.py"
sys.path.insert(0, str(ROOT / "tools"))

import leak_sweep  # noqa: E402

KOTLIN_SRC = Path("android") / "app" / "src" / "main" / "java" / "com" / "pocketrealm" / "client"


def run_sweep(root: Path, *flags: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(TOOL), "--root", str(root), *flags],
        capture_output=True,
        text=True,
    )


def make_tree(tmp_path: Path, entries: dict[str, str]) -> Path:
    for rel, text in entries.items():
        path = tmp_path / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    return tmp_path


def test_banned_literal_outside_allowlist_fails(tmp_path):
    make_tree(tmp_path, {
        KOTLIN_SRC / "Thing.kt": 'const val MANAGED_ID = "wow-2.4.3-8606"\n',
    })
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 1
    report = json.loads(result.stdout)
    assert report["ok"] is False
    assert report["files_scanned"] == 1
    assert len(report["findings"]) == 1
    finding = report["findings"][0]
    assert finding["path"] == str(KOTLIN_SRC / "Thing.kt").replace("\\", "/")
    assert finding["line"] == 1
    assert finding["literals"] == ["wow-2.4.3-8606"]
    assert "wow-2.4.3-8606" in finding["text"]


def test_standalone_build_number_trips_but_prefix_segment_does_not(tmp_path):
    make_tree(tmp_path, {
        KOTLIN_SRC / "Builds.kt": "val build = 12340\nval segment = \"wow12340\"\n",
    })
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 1
    finding = json.loads(result.stdout)["findings"][0]
    assert finding["line"] == 1
    assert finding["literals"] == ["12340"]


def test_whole_file_allowlist_suppresses_the_registry(tmp_path):
    make_tree(tmp_path, {
        KOTLIN_SRC / "ClientFlavor.kt": 'val vanilla = "wow-1.12.1-5875"\n',
    })
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 0
    report = json.loads(result.stdout)
    assert report["ok"] is True
    assert report["findings"] == []
    assert report["allowlisted_findings"] == 1


def test_scoped_allowlist_covers_only_its_literals(tmp_path):
    # tools/checkpoint_dbs.py is exempt for the vanilla DB names only: the
    # same file tripping a build number must still fail.
    make_tree(tmp_path, {
        "tools/checkpoint_dbs.py": 'DATABASES = ("classicrealmd", "classiclogs")\n',
        "tools/other_seed_tool.py": 'DATABASES = ("classicmangos",)\n',
    })
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 1
    report = json.loads(result.stdout)
    assert [f["path"] for f in report["findings"]] == ["tools/other_seed_tool.py"]


def test_scripts_directory_is_scanned(tmp_path):
    make_tree(tmp_path, {
        "scripts/helper.py": 'CLIENT_ID = "wow-3.3.5a-12340"\n',
    })
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 1
    assert json.loads(result.stdout)["findings"][0]["path"] == "scripts/helper.py"


def test_json_shape_carries_the_allowlist(tmp_path):
    # a zero-file scan is a degraded-scan FAILURE now, so the shape
    # fixture needs one clean scannable file to be a legitimate OK case
    make_tree(tmp_path, {str(Path("tools") / "clean.py"): "x = 1\n"})
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 0
    report = json.loads(result.stdout)
    assert set(report) == {
        "ok", "files_scanned", "scanned_roots", "binary_skipped",
        "allowlisted_findings", "findings", "allowlist",
    }
    assert isinstance(report["scanned_roots"], list)
    assert report["scanned_roots"] == sorted(report["scanned_roots"])
    assert "tools" in report["scanned_roots"]
    for entry in report["allowlist"]:
        expected_keys = {"path", "literals", "reason", "retires"}
        if entry.get("path_prefix"):
            expected_keys.add("path_prefix")
        assert set(entry) == expected_keys
        assert entry["reason"] and entry["retires"]


def test_human_output_summarizes_ok_and_failed(tmp_path):
    clean = make_tree(tmp_path / "clean", {
        KOTLIN_SRC / "Fine.kt": 'val segment = "wow5875"\n',
    })
    result = run_sweep(clean)
    assert result.returncode == 0
    assert "leak_sweep: OK (1 files scanned" in result.stdout

    leaking = make_tree(tmp_path / "leaking", {
        KOTLIN_SRC / "Leak.kt": 'val id = "wow-2.4.3-8606"\n',
    })
    result = run_sweep(leaking)
    assert result.returncode == 1
    assert "LEAK" in result.stdout
    assert "leak_sweep: FAILED (1 leaks in 1 files" in result.stdout


def test_repo_allowlist_paths_exist():
    """Every allowlist entry must name a real file in this repository."""
    spec = json.loads(run_sweep(ROOT, "--json").stdout or "{}")
    assert spec, "sweep on the real repo must emit JSON"
    assert spec["allowlist"], "the sweep must ship a justified allowlist"
    for entry in spec["allowlist"]:
        if entry.get("path_prefix"):
            assert (ROOT / entry["path_prefix"].rstrip("/")).is_dir(), entry["path_prefix"]
        else:
            assert (ROOT / entry["path"]).is_file(), entry["path"]


def test_real_repo_sweeps_clean():
    """The repository itself must pass its own gate: allowlisted findings are
    suppressed before they ever reach `findings`, so a clean repo is exactly
    ok=True with an empty findings list."""
    spec = json.loads(run_sweep(ROOT, "--json").stdout or "{}")
    assert spec, "sweep on the real repo must emit JSON"
    assert spec["ok"] is True, json.dumps(spec["findings"], indent=2)
    assert spec["findings"] == []


def test_normalized_integer_literals_trip(tmp_path):
    # Digit separators and hex decode to their value before the banned-number
    # match: 5_875 and 0x16F3 are both 5875.
    make_tree(tmp_path, {
        KOTLIN_SRC / "Sep.kt": "val build = 5_875\n",
        KOTLIN_SRC / "Hex.kt": "val build = 0x16F3\n",
    })
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 1
    findings = json.loads(result.stdout)["findings"]
    assert {f["path"] for f in findings} == {
        str(KOTLIN_SRC / "Sep.kt").replace("\\", "/"),
        str(KOTLIN_SRC / "Hex.kt").replace("\\", "/"),
    }
    for finding in findings:
        # The finding reports the canonical value, so scoped allowlist
        # entries written for the plain decimal keep covering these forms.
        assert finding["literals"] == ["5875"]


def test_unrelated_numeric_forms_still_pass(tmp_path):
    make_tree(tmp_path, {
        KOTLIN_SRC / "Fine.kt": (
            "val glued = wow8606\n"
            "val wider = 15875\n"
            "val hex = 0x8664\n"
            "val float = 1.12340\n"
        ),
    })
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 0
    assert json.loads(result.stdout)["findings"] == []


def test_scope_covers_debug_androidtest_test_gradle_and_res(tmp_path):
    entries = {
        "android/app/src/debug/java/com/pocketrealm/Fixture.java":
            'int BUILD = 5875;\n',
        "android/app/src/androidTest/java/com/pocketrealm/LaneTest.kt":
            'assertEquals(8606, manifest.getInt("build"))\n',
        "android/app/src/test/java/com/pocketrealm/UnitTest.kt":
            'assertEquals(12340, flavor.build)\n',
        "android/app/build.gradle.kts":
            'def vanillaBuild = 5875\n',
        "android/build.gradle.kts":
            'def other = 12340\n',
        "android/app/src/main/res/values/strings.xml":
            '<string name="client_id">wow-1.12.1-5875</string>\n',
    }
    make_tree(tmp_path, entries)
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 1
    report = json.loads(result.stdout)
    assert {f["path"] for f in report["findings"]} == set(entries)


def test_scope_covers_desktop_module(tmp_path):
    paths = {
        "desktop/src/main/kotlin/com/pocketrealm/desktop/Unlisted.kt":
            "const val BUILD_5875 = 5875\n",
        "desktop/src/main/kotlin/com/pocketrealm/server/UnlistedDb.kt":
            'val name = "classicmangos"\n',
        "desktop/src/test/kotlin/com/pocketrealm/desktop/LaneTest.kt":
            "assertEquals(12340, build)\n",
    }
    make_tree(tmp_path, paths)
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 1
    report = json.loads(result.stdout)
    assert {f["path"] for f in report["findings"]} == set(paths)


def _git_init_commit(tree: Path) -> None:
    def git(*args: str) -> None:
        subprocess.run(["git", "-C", str(tree), *args], check=True, capture_output=True)
    git("init", "-q")
    git("add", "-A")
    git("-c", "user.email=gate@example.invalid", "-c", "user.name=gate",
        "commit", "-q", "-m", "scope fixture")


def test_git_tracked_mode_sweeps_outside_legacy_roots(tmp_path):
    """D18's core property: inside a git checkout the sweep covers every
    tracked file — including paths no legacy SCAN_ROOT would touch."""
    make_tree(tmp_path, {
        "misc/notes.kt": "val leaked = 5875\n",
        # A legacy-root file as control: swept in either mode.
        "scripts/control.py": 'DB = "classicrealmd"\n',
    })
    _git_init_commit(tmp_path)
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 1
    report = json.loads(result.stdout)
    assert report["scanned_roots"] == "git-tracked"
    found = {f["path"] for f in report["findings"]}
    assert "misc/notes.kt" in found, found
    assert "scripts/control.py" in found, found


def test_git_mode_skips_binary_files_but_counts_them(tmp_path):
    nul = bytes([0])
    make_tree(tmp_path, {})
    (tmp_path / "blob.bin").write_bytes(nul + b"\x01\x02" + b"5875")
    (tmp_path / "misc").mkdir()
    (tmp_path / "misc" / "text.kt").write_text("val x = 8606\n", encoding="utf-8")
    _git_init_commit(tmp_path)
    result = run_sweep(tmp_path, "--json")
    report = json.loads(result.stdout)
    assert report["scanned_roots"] == "git-tracked"
    assert report["binary_skipped"] >= 1
    # The NUL-prefixed blob is skipped; the text file next to it is not.
    assert [f["path"] for f in report["findings"]] == ["misc/text.kt"]


def test_docs_prefix_suppresses_prose_tree_only(tmp_path):
    make_tree(tmp_path, {
        "docs/prose.md": "Vanilla ships as build 5875.\n",
        KOTLIN_SRC / "Real.kt": 'val db = "classicmangos"\n',
    })
    _git_init_commit(tmp_path)
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 1
    report = json.loads(result.stdout)
    found = {f["path"] for f in report["findings"]}
    assert "docs/prose.md" not in found
    assert str(KOTLIN_SRC / "Real.kt").replace("\\", "/") in found


def test_tools_and_scripts_globs_are_recursive(tmp_path):
    paths = {
        "tools/llm_lab/helper.py": 'VANILLA_ID = "wow-1.12.1-5875"\n',
        "tools/rp_harness/suites/probe.py": 'DB = "classicrealmd"\n',
        "scripts/sub/dir/deep.py": 'BUILD = 12340\n',
        # Top-level files must keep scanning too.
        "tools/toplevel.py": 'OTHER = "classicmangos"\n',
    }
    make_tree(tmp_path, paths)
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 1
    report = json.loads(result.stdout)
    assert {f["path"] for f in report["findings"]} == set(paths)


@pytest.mark.parametrize("literal", ["5875", "8606", "12340"])
def test_banned_numbers_are_matched_standalone(tmp_path, literal):
    make_tree(tmp_path, {
        KOTLIN_SRC / "Num.kt": f"val build = {literal}\n",
    })
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 1
    assert json.loads(result.stdout)["findings"][0]["literals"] == [literal]


@pytest.mark.parametrize("form", ["5875u", "5875U", "5875uL", "5875UL", "12340uL", "0x16F3UL"])
def test_kotlin_unsigned_suffixes_decode(tmp_path, form):
    make_tree(tmp_path, {
        KOTLIN_SRC / "Unsigned.kt": "val build = " + form + "\n",
    })
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 1
    literals = json.loads(result.stdout)["findings"][0]["literals"]
    assert literals in (["5875"], ["12340"])


def test_db_names_match_case_insensitively(tmp_path):
    make_tree(tmp_path, {
        KOTLIN_SRC / "Casey.kt": 'val db = "ClassicRealmd"\n',
    })
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 1
    assert json.loads(result.stdout)["findings"][0]["literals"] == ["classicrealmd"]


def test_binary_and_octal_forms_trip(tmp_path):
    # 5875 == 0b1011011110011 == 0o13363: the binary and leading-zero octal
    # forms decode to the banned value exactly like the hex form does.
    paths = {
        KOTLIN_SRC / "Bin.kt": "val build = 0b1011011110011\n",
        KOTLIN_SRC / "Oct.kt": "val build = 013363\n",
        KOTLIN_SRC / "OctPrefix.kt": "val build = 0o13363\n",
    }
    make_tree(tmp_path, paths)
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 1
    findings = json.loads(result.stdout)["findings"]
    assert {f["path"] for f in findings} == {str(p).replace("\\", "/") for p in paths}
    for finding in findings:
        # The finding reports the canonical value, so scoped allowlist
        # entries written for the plain decimal keep covering these forms.
        assert finding["literals"] == ["5875"]


def test_plain_zero_and_small_octals_do_not_trip(tmp_path):
    # Octal is only the leading-zero form (a zero followed by octal digits):
    # plain decimal lists like the bot maps "0, 1" and permission-style
    # octals that decode to unbanned values stay clean.
    make_tree(tmp_path, {
        KOTLIN_SRC / "Maps.kt": (
            "val maps = listOf(0, 1)\n"
            "val pad = 007\n"
            "val mode = 0o755\n"
        ),
    })
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 0
    assert json.loads(result.stdout)["findings"] == []


def test_scope_covers_main_java_and_non_res_xml(tmp_path):
    # src/main is swept wholesale: .java sources and every non-res XML (the
    # AndroidManifest, addon assets) are in scope, not just the res tree.
    paths = {
        "android/app/src/main/java/com/pocketrealm/legacy/Legacy.java":
            'int BUILD = 5875;\n',
        "android/app/src/main/AndroidManifest.xml":
            '<provider android:authorities="classiclogs" android:exported="false" />\n',
        "android/app/src/main/assets/addons/android-port/AndroidPort/Bindings.xml":
            '<Binding id="wow-2.4.3-8606" />\n',
    }
    make_tree(tmp_path, paths)
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 1
    report = json.loads(result.stdout)
    assert {f["path"] for f in report["findings"]} == {str(p).replace("\\", "/") for p in paths}


def test_scope_covers_ps1_values_night_and_llm_module(tmp_path):
    paths = {
        "tools/helper.ps1": '$expected = "1.12.1.5875"\n',
        "android/app/src/main/res/values-night/strings.xml":
            '<string name="client_id">wow-1.12.1-5875</string>\n',
        "android/pocketrealm-llm/src/main/java/com/pocketrealm/llm/Lane.kt":
            "const val BUILD = 5875\n",
    }
    make_tree(tmp_path, paths)
    result = run_sweep(tmp_path, "--json")
    assert result.returncode == 1
    report = json.loads(result.stdout)
    assert {f["path"] for f in report["findings"]} == {str(p).replace("\\", "/") for p in paths}


def test_stale_scoped_allowlist_entry_is_a_config_error(tmp_path):
    # A scoped entry whose target file no longer contains ANY of its covered
    # literals is dead weight: the sweep reports it (exit 1) so entries
    # retire with their literals instead of silently accumulating.
    clean = KOTLIN_SRC / "Clean.kt"
    entry_path = str(clean).replace("\\", "/")
    make_tree(tmp_path, {clean: "val plain = 1\n"})
    allowlist = (
        {
            "path": entry_path,
            "literals": ("5875",),
            "reason": "fixture entry",
            "retires": "S5 retires: fixture",
        },
    )
    findings, allowlisted, _scanned = leak_sweep.sweep(tmp_path, allowlist=allowlist)
    assert len(findings) == 1
    assert findings[0]["path"] == entry_path
    assert findings[0]["line"] == 0
    assert findings[0]["literals"] == ["5875"]
    assert findings[0]["text"] == (
        f"stale allowlist entry: {entry_path} (literal '5875' not found)"
    )
    assert allowlisted == 0


def test_live_scoped_allowlist_entry_is_not_stale(tmp_path):
    target = KOTLIN_SRC / "Leak.kt"
    make_tree(tmp_path, {target: "val build = 5875\n"})
    allowlist = (
        {
            "path": str(target).replace("\\", "/"),
            "literals": ("5875",),
            "reason": "fixture entry",
            "retires": "S5 retires: fixture",
        },
    )
    findings, allowlisted, _scanned = leak_sweep.sweep(tmp_path, allowlist=allowlist)
    assert findings == []
    assert allowlisted == 1


def test_whole_file_allowlist_entry_is_never_stale(tmp_path):
    # Whole-file exemptions (owning tables) carry no literal list to go stale.
    target = KOTLIN_SRC / "Registry.kt"
    make_tree(tmp_path, {target: "val plain = 1\n"})
    allowlist = (
        {
            "path": str(target).replace("\\", "/"),
            "literals": None,
            "reason": "owning table",
            "retires": "never",
        },
    )
    findings, _allowlisted, _scanned = leak_sweep.sweep(tmp_path, allowlist=allowlist)
    assert findings == []


def test_repo_allowlist_has_no_stale_entries():
    """The real repository must not carry a scoped allowlist entry whose
    literals have all been fixed elsewhere: the sweep would exit 1 on it."""
    spec = json.loads(run_sweep(ROOT, "--json").stdout or "{}")
    assert spec, "sweep on the real repo must emit JSON"
    stale = [f for f in spec["findings"] if "stale allowlist entry" in str(f.get("text", ""))]
    assert stale == [], json.dumps(stale, indent=2)


def test_journal_schema_bump_is_a_finding(tmp_path):
    """G3-4: the supervisor journal schema stays 3 — the sweep greps for any
    `JOURNAL_SCHEMA = <n>` other than 3 in the supervisor package."""
    supervisor = tmp_path / "android" / "app" / "src" / "main" / "java" / "com" / "pocketrealm" / "supervisor"
    supervisor.mkdir(parents=True)
    (supervisor / "RuntimeModel.kt").write_text(
        "object RuntimeSnapshot { const val JOURNAL_SCHEMA = 4 }\n", encoding="utf-8"
    )
    (supervisor / "Clean.kt").write_text(
        "const val JOURNAL_SCHEMA = 3\n", encoding="utf-8"
    )
    findings, _allowlisted, _scanned = leak_sweep.sweep(tmp_path)
    bumps = [f for f in findings if f["literals"] == ["<journal-schema-bump>"]]
    assert len(bumps) == 1
    assert bumps[0]["path"].endswith("RuntimeModel.kt")
    assert bumps[0]["line"] == 1


def test_journal_schema_three_is_quiet(tmp_path):
    supervisor = tmp_path / "android" / "app" / "src" / "main" / "java" / "com" / "pocketrealm" / "supervisor"
    supervisor.mkdir(parents=True)
    (supervisor / "RuntimeModel.kt").write_text(
        "const val JOURNAL_SCHEMA = 3\nval reference = RuntimeSnapshot.JOURNAL_SCHEMA\n",
        encoding="utf-8",
    )
    findings, _allowlisted, _scanned = leak_sweep.sweep(tmp_path)
    assert not [f for f in findings if f["literals"] == ["<journal-schema-bump>"]]


def test_real_repo_journal_schema_stays_three():
    spec = json.loads(run_sweep(ROOT, "--json").stdout or "{}")
    assert not [
        f for f in spec.get("findings", []) if f.get("literals") == ["<journal-schema-bump>"]
    ]

# ---- the sweep covers UNTRACKED
# commit-candidate files and fails loudly when it cannot scan.


def _git_repo_with(tmp_path: Path, committed: dict, untracked: dict) -> Path:
    import subprocess

    root = tmp_path / "repo"
    root.mkdir()
    subprocess.run(["git", "init", "-q"], cwd=root, check=True)
    subprocess.run(["git", "config", "user.email", "t@t"], cwd=root, check=True)
    subprocess.run(["git", "config", "user.name", "t"], cwd=root, check=True)
    for rel, content in {**committed, **untracked}.items():
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    if committed:
        subprocess.run(["git", "add", "-A"], cwd=root, check=True)
        subprocess.run(["git", "commit", "-q", "-m",
                        "synthetic", "--no-gpg-sign", "--no-verify"],
                       cwd=root, check=True)
    return root


def test_untracked_files_are_swept(tmp_path):
    """R1-1/R3-1: an untracked-but-committable file with a banned
    literal fails the sweep - the finalize commit can never flip the gate
    red after the fact."""
    leak_rel = Path("android/app/src/main/java/com/pocketrealm/client/Leaky.kt").as_posix()
    root = _git_repo_with(
        tmp_path,
        committed={str(Path("tools/clean.py")): "x = 1"},
        untracked={leak_rel: 'val db = "classicrealmd"'},
    )
    result = run_sweep(root, "--json")
    report = json.loads(result.stdout)
    assert not report["ok"], report["findings"]
    assert any(f["path"] == leak_rel for f in report["findings"])


def test_zero_file_scan_is_a_loud_degraded_scan(tmp_path):
    """R2-2: a root with neither git nor any legacy SCAN_ROOTS
    directory is a degraded-scan finding - zero coverage is never OK."""
    empty_root = tmp_path / "nowhere"
    empty_root.mkdir()
    result = run_sweep(empty_root, "--json")
    report = json.loads(result.stdout)
    assert not report["ok"]
    assert any("<degraded-scan>" in f["literals"]
               for f in report["findings"])

# ---------------------------------------------------------------------------
# the lane tokens joined the
# banned set — the per-flavor addon interface numbers (11200/20400/30300),
# the realmbuilds-adjacent builds (6005/6141), and the per-flavor expansion
# MPQ names. A planted token in a NON-owning Kotlin file must fail the
# sweep; the owning surfaces stay green.
# ---------------------------------------------------------------------------


def test_planted_interface_number_in_a_non_owning_kotlin_file_fails_the_sweep(tmp_path):
    import subprocess
    (tmp_path / "ui").mkdir()
    (tmp_path / "ui" / "SomeScreen.kt").write_text(
        'val metadata = "requires Interface 30300 metadata"\n', encoding="utf-8")
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    subprocess.run(["git", "add", "-A"], cwd=tmp_path, check=True)
    findings, _allowlisted, _files = leak_sweep.sweep(tmp_path)
    assert any(
        f.get("path", "").endswith("SomeScreen.kt") and "30300" in str(f.get("literals", ""))
        for f in findings
    ), findings


def test_planted_bare_client_line_is_deliberately_not_banned(tmp_path):
    # SCOPE NOTE (deviation from the proposed test) the bare
    # client-line tokens ("1.12", "2.4.3", "3.3.5") are the repo's client-
    # LINE families, named by design in catalog-v1.json, the era research
    # and the per-flavor projection on 100+ owning files — a substring ban
    # would exempt the whole repo and defeat the sweep. The mechanically
    # banned tokens are the interface numbers (11200/20400/30300), the
    # realmbuilds-adjacent builds (6005/6141) and the expansion MPQ names;
    # the client-line research surfaces are gated by the AddonCatalog parse
    # guard and the AddonFlavorCatalog projection pins instead.
    (tmp_path / "ui").mkdir()
    (tmp_path / "ui" / "Chip.kt").write_text('Text("· Vanilla client 1.12")\n', encoding="utf-8")
    import subprocess
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    subprocess.run(["git", "add", "-A"], cwd=tmp_path, check=True)
    findings, _allowlisted, _files = leak_sweep.sweep(tmp_path)
    assert not [
        f for f in findings if "1.12" in str(f.get("literals", ""))
    ], "the bare client line must stay unbanned (see the scope note)"




def test_owning_surfaces_stay_green_with_the_widened_token_set():
    # the ClientFlavor registry / AddonFlavorCatalog projection / realmbuilds
    # data are the OWNING surfaces: their tokens are allowlisted.
    findings, _allowlisted, _files = leak_sweep.sweep(ROOT)
    owning = [
        f for f in findings
        if str(f.get("path", "")).startswith(("schemas/", "tools/leak_sweep"))
        or "AddonFlavorCatalog" in str(f.get("path", ""))
    ]
    assert not owning, owning


# ---------------------------------------------------------------------------
# multi-flavor UX copy must DERIVE its client
# identity from the flavor registry, not hard-code a lane's version line —
# a hard-coded "WoW 1.12.1 client" on a TBC/WotLK lane was factually wrong
# copy AND slipped past the sweep (the bare version line is deliberately
# not a banned token — see the scope note above). The sweep stays
# token-scoped; this pin is the UX-copy CLASS gate for the screen class.
# ---------------------------------------------------------------------------


def test_archive_sniffer_val_copy_is_flavor_neutral():
    """R6-r6-1 (W4 code-red round 6): VAL-11/VAL-12 rejection copy must not
    hard-code the vanilla identity ("1.12.1", "5875") — the flip qualified
    tbc/wotlk imports, and the copy derives the accepted set from
    ClientPeIdentity.expectedVersions (the same registry source VAL-03
    uses), so it cannot drift from the registry again."""
    source = (
        ROOT / "android/app/src/main/java/com/pocketrealm/importer/"
        "ArchiveFormatSniffer.kt"
    ).read_text(encoding="utf-8")
    stripped = re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL)
    stripped = re.sub(r"//[^\n]*", "", stripped)
    for token in ("1.12.1", "5875"):
        hits = [
            l for l in stripped.splitlines()
            if token in l and ("VAL11_ISO" in l or "VAL12_NOT_INNO" in l
                               or "installers" in l or "extracted" in l)
        ]
        assert not hits, (
            f"ArchiveFormatSniffer's VAL-11/12 copy hard-codes the vanilla "
            f"identity {token!r} ({hits[:1]}) - derive the accepted set "
            "from ClientPeIdentity.expectedVersions (R6-r6-1)"
        )
    assert "expectedVersions" in stripped, (
        "the VAL-11/12 copy must derive the accepted set from the registry "
        "(ClientPeIdentity.expectedVersions)"
    )


def test_settings_setup_card_copy_is_flavor_neutral():
    """R4-4 (W4 code-red round 4): the Settings maintenance Setup card
    must not hard-code "the WoW 1.12.1 client" — the desktop twin
    already reads flavor-neutrally, and the flip made tbc/wotlk
    selectable, so the card names the user's own flavor, never one
    lane's version token."""
    source = (
        ROOT / "android/app/src/main/java/com/pocketrealm/ui/SettingsScreen.kt"
    ).read_text(encoding="utf-8")
    stripped = re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL)
    stripped = re.sub("//[^\n]*", "", stripped)
    hard_coded = re.findall(
        r"WoW \d+\.\d+ client", stripped,
    )
    assert not hard_coded, (
        f"SettingsScreen's Setup card hard-codes a lane's client "
        f"identity ({hard_coded[:2]}) - the desktop twin reads "
        "flavor-neutrally; name the user's own flavor instead (R4-4)"
    )
    assert "Setup covers the game-files import for your flavor" in stripped, (
        "the Setup card lost the flavor-neutral requirement copy"
    )


def test_multi_flavor_screen_copy_derives_the_client_identity():
    """ClientScreen's copy names the ACTIVE lane's client, resolved through
    the fail-loud flavor machine (the TunnelInvite.clientRequirementLine
    precedent) — never a hard-coded "WoW <version> client" literal."""
    source = (ROOT / "android/app/src/main/java/com/pocketrealm/ui/ClientScreen.kt").read_text(
        encoding="utf-8")
    # comment-stripped: the CONTRACT marker names the old literal; only
    # copy in string position counts
    stripped = re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL)
    stripped = re.sub("//[^\n]*", "", stripped)
    hard_coded = re.findall(r"WoW \d+\.\d+", stripped)
    assert not hard_coded, (
        f"ClientScreen hard-codes a lane's client identity in user copy "
        f"({hard_coded[:2]}) — derive it from ActiveFlavor (R2-1)"
    )
 # Flip: the render lane resolves through the machine's canonical
    # displayedId (selection, else sole qualified, else null -> the
    # first-pick copy) - the import OPERATION still fails loud at the
    # resolver; either form is the ActiveFlavor machine, never a literal.
    assert ("ActiveFlavor.resolve(" in source
            or "ActiveFlavor.displayedId" in source), (
        "ClientScreen lost the active-lane resolution its copy derives from"
    )


# ---------------------------------------------------------------------------
# multiplayer privacy rules
# addr= correction) the sweep grows the
# campaign's structural privacy rules over the published-payload /
# discovery-record code paths. No IP-shaped token in a payload builder (the
# fence files' own KDoc examples stay legal prose — comments are masked);
# no addr= anywhere in the payload surfaces or the descriptor lanes; a
# listed surface that vanishes is a loud finding, never a silent pass.
# ---------------------------------------------------------------------------

_DISCOVERY = Path("android") / "app" / "src" / "main" / "java" / "com" / "pocketrealm" / "discovery"


def make_privacy_tree(tmp_path: Path, surfaces: dict[str, str]) -> Path:
    """A tree carrying the real discovery-module marker (so the privacy
    rules arm) plus the given surface files."""
    (tmp_path / _DISCOVERY / "nostr").mkdir(parents=True, exist_ok=True)
    return make_tree(tmp_path, surfaces)


def privacy_findings_of(tmp_path: Path, surfaces: dict[str, str]) -> list[dict]:
    """Sweep a privacy-armed tree; returns ALL findings. Tests that scope to
    leak rows filter the <privacy-surface-missing> rows themselves (a
    partial tree is the normal test shape)."""
    make_privacy_tree(tmp_path, surfaces)
    result = run_sweep(tmp_path, "--json")
    return json.loads(result.stdout)["findings"]


def surfaced(findings: list[dict]) -> list[dict]:
    """The findings minus the surface-missing rows (a partial tree is the
    normal test shape; the missing-surface rule has its own test)."""
    return [
        f for f in findings
        if f["literals"] != ["<privacy-surface-missing>"]
    ]


@pytest.mark.parametrize("spelling", [
    "https://10.0.0.1/relay",            # canonical v4 in a published URL
    "192.168.000.001",                   # leading-zero octets (loose quad)
    "2130706433",                        # the dotless inet_aton integer
    "0x7f000001",                        # the dotless hex spelling
    "0x7f.0x0.0.1",                      # the dotted-hex spelling
    "2001:db8::1",                       # an IPv6 literal
    "[::1]:8085",                        # bracketed v6 with a port
    "join me at 127.0.0.1:8085",         # loopback is NOT exempt in payloads
    # the fence's own adjudicated IPv4 semantics — one
    # sentence-final period, 4-digit leading-zero octets. (The bare
    # "037777777777" octal row moved to the negative list at:
    # measured it UNRESOLVED on the pinned JDK — 0xFFFFFFFF
    # exceeds the signed-int parse — so the value-aware leg passes it.)
    "play at 10.0.0.1.",
    "0177.0.0.1",
    "1.1.1.0100",
    # all-hex-letter IPv6 literals — JVM-connectable with ZERO
    # decimal digits (InetAddress.getByName("dead:beef::cafe") resolves):
    "dead:beef::cafe",
    "DEAD:BEEF::CAFE",
    "[dead:beef::cafe]:8085",
    "cafe:beef:baa::",
    # the prose-period class extends to the dotless/octal/hex
    # legs — "2130706433." is the same pointer as "10.0.0.1.".
    "moved to 2130706433.",
    "dial 0x7f000001.",
    "dial 0x7f.0x0.0.1.",
    # re-adjudicated by VALUE:
    # the JVM's single-chunk parser is PLAIN DECIMAL with leading zeros
    # IGNORED — every digit-only token whose decimal value fits the
    # unsigned 32-bit range is connectable and sails char-count bounds:
    "join me at 0002130706433",
    "dial 000000275600001",
    "fallback 0123456789",
    "fallback 0256985321",
    # digit SEPARATORS and the BINARY literal
    # form are the same integer 2130706433 = 127.0.0.1 — caught by the
    # separator-normalized, 0b-decoding dotless judge:
    "2_130_706_433",
    "0b01111111000000000000000000000001",
    "2_130_706_433L",
    # Kotlin's integer suffixes are syntax — the hex run
    # is the integer 127.0.0.1 and must be caught with the suffix aboard:
    "0x7F000001L",
    "0x7F000001u",
    # an IPv4 followed by a hostname label (wildcard-DNS
    # "nip.io"-style) — the trailing-dot alternative already consumes the
    # dot; pinned so the shape stays banned:
    "10.0.0.1.nip.io",
    # 5/6-digit zero-padded OCTETS in
    # dotted quads are JVM-resolvable decimal — any digit width:
    "00177.0.0.1",
    "00254.0.0.1",
    "000177.0.0.1",
    "00010.0.0.1",
    "10.0.0.00177",
    "00010.00000.0.1",
])
def test_privacy_ip_spellings_fail_in_a_payload_surface(tmp_path, spelling):
    surfaces = {
        _DISCOVERY / "nostr" / "RealmListingEvent.kt":
            f'val motd = "{spelling}"\n',
    }
    findings = surfaced(privacy_findings_of(tmp_path, surfaces))
    hits = [f for f in findings if f["literals"] == ["<privacy-ip-literal>"]]
    assert len(hits) == 1, findings
    assert hits[0]["path"].endswith("RealmListingEvent.kt")
    assert hits[0]["line"] == 1
    assert spelling in hits[0]["text"]


@pytest.mark.parametrize("line", [
    'private const val BYTE_MASK = 0xFF',          # a 2-digit hex mask
    'private val DIGITS = "0123456789abcdef"',     # a glued charset, not a token
    'val rate = 1.0',                              # a float literal
    'val version = "3.3.5"',                       # a version token (the free-text fence owns shorthand)
    'val k = Foo::class',                          # the ::-reference
    'val p = Phase2::class',                       # a DIGIT-bearing receiver
    'val q = Page2::class.java',                   # digit-bearing, .java suffix
    'val fallback = "037777777777"',               # decimal 37,777,777,777 EXCEEDS the unsigned-32 parse — UnknownHost on the pinned JDKs, deliberately not banned
    'val url = "wss://relay.example.com"',         # hostname relays are the design
])
def test_privacy_legitimate_code_shapes_stay_clean(tmp_path, line):
    surfaces = {
        _DISCOVERY / "nostr" / "RelayPool.kt": line + "\n",
    }
    findings = surfaced(privacy_findings_of(tmp_path, surfaces))
    assert findings == [], findings


def test_privacy_comments_are_masked_but_strings_are_code(tmp_path):
    surfaces = {
        _DISCOVERY / "nostr" / "RealmListingEvent.kt": (
            '/** the fence refuses "2130706433" and "10.0.0.1" spellings. */\n'
            '// a line comment citing 192.168.1.10 too\n'
            'val target = "https://example.com" // trailing prose: 10.0.0.1\n'
            'val leaked = "https://10.0.0.1/relay"\n'
        ),
    }
    findings = surfaced(privacy_findings_of(tmp_path, surfaces))
    hits = [f for f in findings if f["literals"] == ["<privacy-ip-literal>"]]
    assert len(hits) == 1, findings
    assert hits[0]["line"] == 4
    assert "ipv4:10.0.0.1" in hits[0]["text"]


def test_privacy_char_literal_quote_does_not_unmask_a_line_comment(tmp_path):
    """R5-3: a char-literal containing a quote (`'"'`) used to flip the
    comment-masker into string state, surfacing a trailing line comment's
    address as a payload finding. Char literals are tracked; the comment
    stays masked."""
    surfaces = {
        _DISCOVERY / "nostr" / "ListingStore.kt": (
            'val q = \'"\' // logged 10.0.0.1 here\n'
        ),
    }
    findings = surfaced(privacy_findings_of(tmp_path, surfaces))
    assert findings == [], findings


def test_privacy_ip_exempt_file_is_judged_line_scoped(tmp_path):
    """R1-2: the single carve-out file is not unjudged — every IP-shaped
    token in it must sit on the documented JOINER_WORLD_TARGET line; a
    second literal anywhere else in the file is a finding."""
    rewrite = (
        "android/app/src/main/java/com/pocketrealm/discovery/iroh/"
        "RealmListRewrite.kt"
    )
    surfaces = {
        rewrite: (
            'const val JOINER_WORLD_TARGET: String = "127.0.0.1:8085"\n'
            'const val ADVERTISED = "10.0.0.1"\n'
        ),
    }
    findings = surfaced(privacy_findings_of(tmp_path, surfaces))
    hits = [f for f in findings if f["literals"] == ["<privacy-ip-literal>"]]
    assert len(hits) == 1, findings
    assert hits[0]["line"] == 2, findings
    assert "ipv4:10.0.0.1" in hits[0]["text"]


def test_privacy_addr_hit_in_both_table_passes_is_reported_once(tmp_path):
    """R1-4 (finalize): RealmListRewrite sits in both privacy tables — an
    addr= hit there is deduplicated to ONE finding, not one per pass."""
    rewrite = (
        "android/app/src/main/java/com/pocketrealm/discovery/iroh/"
        "RealmListRewrite.kt"
    )
    surfaces = {
        rewrite: 'const val TAG = "addr="',
    }
    findings = surfaced(privacy_findings_of(tmp_path, surfaces))
    addr_hits = [f for f in findings
                 if f["literals"] == ["<privacy-addr-marker>"]]
    assert len(addr_hits) == 1, findings


def test_privacy_ip_exempt_reference_lines_cannot_smuggle_a_literal(tmp_path):
    """R3-1 (round 2): the exemption is bound to the constant's DECLARATION
    shape — a line that merely references JOINER_WORLD_TARGET while carrying
    a fresh address literal is a finding, not an exemption."""
    rewrite = (
        "android/app/src/main/java/com/pocketrealm/discovery/iroh/"
        "RealmListRewrite.kt"
    )
    surfaces = {
        rewrite: (
            'const val JOINER_WORLD_TARGET: String = "127.0.0.1:8085"\n'
            'val dial = joinDial(JOINER_WORLD_TARGET, "10.0.0.1")\n'
        ),
    }
    findings = surfaced(privacy_findings_of(tmp_path, surfaces))
    hits = [f for f in findings if f["literals"] == ["<privacy-ip-literal>"]]
    assert len(hits) == 1, findings
    assert hits[0]["line"] == 2, findings
    assert "ipv4:10.0.0.1" in hits[0]["text"]


def test_privacy_addr_marker_fails_in_payload_surfaces_and_record_lanes(tmp_path):
    surfaces = {
        _DISCOVERY / "nostr" / "ListingStore.kt": 'val row = "node id addr=deadbeef"\n',
        Path("gateway") / "src" / "main" / "kotlin" / "com" / "pocketrealm" / "gateway" / "GatewayIo.kt":
            'val echo = "iroh addr=yellow"\n',
        # a record lane's loopback constant is the documented design — no IP
        # ban on the lane, only the direct-address vocabulary:
        Path("gateway") / "src" / "main" / "kotlin" / "com" / "pocketrealm" / "gateway" / "JoinGateway.kt":
            'const val JOINER_TARGET = "127.0.0.1:8085"\n',
    }
    findings = surfaced(privacy_findings_of(tmp_path, surfaces))
    hits = [f for f in findings if f["literals"] == ["<privacy-addr-marker>"]]
    assert sorted(f["path"] for f in hits) == [
        str(_DISCOVERY / "nostr" / "ListingStore.kt").replace("\\", "/"),
        "gateway/src/main/kotlin/com/pocketrealm/gateway/GatewayIo.kt",
    ], findings
    assert all("JoinGateway" not in f["path"] for f in findings), findings


def test_privacy_missing_surface_is_a_loud_finding(tmp_path):
    findings = privacy_findings_of(tmp_path, {})
    missing = [f for f in findings if f["literals"] == ["<privacy-surface-missing>"]]
    # RealmListRewrite sits in BOTH tables — the dedupe
    # collapses its two missing rows to one, so the expected count is the
    # UNIQUE surface count.
    expected = len(set(leak_sweep.PRIVACY_PAYLOAD_SURFACES)
                   | set(leak_sweep.PRIVACY_RECORD_LANES))
    assert len(missing) == expected, (
        f"expected {expected} surface-missing findings, got {len(missing)}"
    )
    assert all("fail-closed" in f["text"] for f in missing)


def test_privacy_rules_skip_trees_without_the_discovery_module(tmp_path):
    make_tree(tmp_path, {KOTLIN_SRC / "Thing.kt": "val x = 1\n"})
    result = run_sweep(tmp_path, "--json")
    findings = json.loads(result.stdout)["findings"]
    assert all(f["literals"] != ["<privacy-surface-missing>"] for f in findings), findings


def test_privacy_table_has_no_exemption_mechanism_beyond_the_one_constant():
    """The payload table's ONLY IP-leg exception is the documented joiner
    loopback constant — the exemption set must stay exactly that file, and
    the constant must still exist there (the exemption retires with its
    reason, the allowlist staleness rule's spirit)."""
    assert set(leak_sweep.PRIVACY_PAYLOAD_IP_EXEMPT) == {
        "android/app/src/main/java/com/pocketrealm/discovery/iroh/RealmListRewrite.kt",
    }
    source = (ROOT / leak_sweep.PRIVACY_PAYLOAD_IP_EXEMPT.__iter__().__next__()).read_text(
        encoding="utf-8"
    )
    assert 'JOINER_WORLD_TARGET: String = "127.0.0.1' in source


def test_real_repo_payload_surfaces_are_privacy_clean():
    """The live tree's published-payload code paths carry no address-shaped
    token and no addr= marker (the sweep ran clean on the whole repo — this
    pin holds the specific claim)."""
    findings, _allowlisted, _files = leak_sweep.sweep(ROOT)
    privacy = [f for f in findings if "privacy" in str(f.get("literals", ""))]
    assert not privacy, privacy


def test_every_on_disk_payload_tree_file_is_a_swept_surface_or_carveout():
    """R7-1: the payload tables are closed enumerations whose only fail-open
    was a NEW file under a swept tree growing unswept. Every .kt under the
    discovery module, the gateway main source, and the desktop
    nostr/realmstatus/iroh payload directories must be a member of a privacy
    table or the reviewed PRIVACY_NON_PAYLOAD_CARVEOUTS — a 15th discovery
    file (or a new publisher/realmstatus source) fails the suite until the
    table (or the carve-out, by review) grows with it."""
    swept = set(leak_sweep.PRIVACY_PAYLOAD_SURFACES) | set(leak_sweep.PRIVACY_RECORD_LANES)
    carveouts = set(leak_sweep.PRIVACY_NON_PAYLOAD_CARVEOUTS)
    trees = (
        ROOT / "android/app/src/main/java/com/pocketrealm/discovery",
        ROOT / "gateway/src/main/kotlin/com/pocketrealm/gateway",
        ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/nostr",
        ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/realmstatus",
        ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/iroh",
    )
    on_disk: set[str] = set()
    for tree in trees:
        assert tree.is_dir(), f"privacy swept tree vanished: {tree}"
        for path in tree.rglob("*.kt"):
            on_disk.add(path.relative_to(ROOT).as_posix())
    unaccounted = sorted(on_disk - swept - carveouts)
    assert not unaccounted, (
        f"file(s) under the privacy-swept payload trees are in NO privacy "
        f"table and not in PRIVACY_NON_PAYLOAD_CARVEOUTS — join them to "
        f"PRIVACY_PAYLOAD_SURFACES / PRIVACY_RECORD_LANES (payload builders) "
        f"or review them into the carve-out: {unaccounted}"
    )
    phantom = sorted((swept | carveouts) - on_disk)
    assert not phantom, (
        f"privacy table/carve-out entries pointing at no on-disk file: "
        f"{phantom}"
    )


def test_import_screen_empty_state_copy_is_flavor_neutral():
    """R6-2: the import empty state must not tell every user to import one
    lane's client — the screen's own folder-picking copy says "(any
    qualified flavor)" and the flip qualified tbc/wotlk imports."""
    source = (
        ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/ImportScreen.kt"
    ).read_text(encoding="utf-8")
    # mask comments STRING-AWARE (the sweep's own
    # masker) — a plain '//' strip would hide a lane build inside a string
    # that follows a comment marker.
    stripped = "\n".join(
        code for _lineno, code in leak_sweep._kotlin_code_lines(source)
    )
    hard_coded = [l for l in stripped.splitlines() if "1.12.1" in l]
    assert not hard_coded, (
        f"ImportScreen's user-facing copy hard-codes a lane's client build "
        f"({hard_coded[:1]}) — the import accepts any qualified flavor; "
        f"name the user's own client, never '1.12.1' (R6-2)"
    )
    assert "genuine game client folder" in stripped, (
        "ImportScreen lost the flavor-neutral empty-state copy (R6-2)"
    )


def test_ingame_settings_screen_copy_is_flavor_neutral():
    """R6-2: the in-game settings header and key-capture copy must not claim
    one lane's build — the screen resolves per-flavor catalogs
    (DesktopInGameSettingsEditor.activeLaneForCatalog), so '1.12.1' copy is
    wrong on the tbc/wotlk lanes."""
    source = (
        ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/"
        "InGameSettingsScreen.kt"
    ).read_text(encoding="utf-8")
    # mask comments STRING-AWARE — a plain '/' strip
    # would hide a lane build inside a string that follows a marker.
    stripped = "\n".join(
        code for _lineno, code in leak_sweep._kotlin_code_lines(source)
    )
    hard_coded = [l for l in stripped.splitlines() if "1.12.1" in l]
    assert not hard_coded, (
        f"InGameSettingsScreen's user-facing copy hard-codes a lane's "
        f"client build ({hard_coded[:1]}) — the catalogs resolve per "
        f"flavor; name 'your flavor', never '1.12.1' (R6-2)"
    )
    assert "stock settings panels for your flavor" in stripped
    assert "no binding in the stock panels" in stripped


def test_readme_quick_start_is_flavor_aware():
    """R6-N5-r2-2: the README's first-boot tutorial description and
    quick-start import step must name all three qualified flavors (or use
    the flavor-neutral 'the flavor you play' wording) — every user reads
    them, and tbc/wotlk have been qualified since W4."""
    source = (ROOT / "README.md").read_text(encoding="utf-8")
    first_boot = source.split("## First boot", 1)[-1]
    assert "the flavor you play" in first_boot, (
        "the README first-boot copy must be flavor-neutral or name the "
        "flavors (R6-N5-r2-2)"
    )
    assert "Vanilla 1.12.1, TBC 2.4.3, or WotLK 3.3.5a" in first_boot, (
        "the README tutorial description must enumerate the qualified "
        "flavors"
    )
    assert "Vanilla 1.12.1 build 5875, TBC 2.4.3 build\n   8606, or WotLK 3.3.5a build 12340" in first_boot, (
        "the README quick-start step must enumerate the qualified builds"
    )
    stale = [l for l in first_boot.splitlines() if "pick your WoW 1.12.1" in l]
    assert not stale, (
        f"the README quick-start still hard-codes the vanilla client "
        f"({stale[:1]}) (R6-N5-r2-2)"
    )
    # the WHOLE README is flavor-aware — a bare "WoW 1.12.1
    # client" mention outside the three-flavor enumeration is the exact
    # staleness class (found at lines 8 and 111 in round 3).
    bare = [
        l for l in source.splitlines()
        if "WoW 1.12.1 client" in l and "TBC 2.4.3" not in l
    ]
    assert not bare, (
        f"the README still presents the vanilla client as THE client "
        f"({bare[:1]}) — name the flavor set or use flavor-neutral wording "
        f"(R6-N5-r3-2)"
    )


def test_reserved_name_root_debris_is_a_finding(monkeypatch):
    """R5-N5-r6-1 (round 6): a DOS reserved-name file at the repo root
    ("nul" — created by a POSIX shell's `>nul` redirect) is invisible to
    plain del/dir yet shifts the sweep's scanned-file tuple. The sweep
    flags it as root debris; Windows-only (the reserved-name class is a
    DOS/Windows concept). Modern Windows builds refuse to CREATE such
    files even via the extended-length prefix (CreateFileW returns
    INVALID_HANDLE_VALUE — measured), so the test drives the debris
    leg's own visibility probe through a monkeypatched os.path.isfile
    that answers for the reserved name only."""
    import os
    import tempfile
    root = Path(tempfile.mkdtemp(prefix="leak-sweep-nul-probe-"))
    target = root / "nul"
    real_isfile = os.path.isfile

    def sees_reserved(path):
        # answer True for ANY path whose basename is a reserved name —
        # the debris leg probes the extended-length form whose prefix
        # defeats plain prefix matching.
        lowered = str(path).lower()
        return lowered.rsplit("\\", 1)[-1].rsplit("/", 1)[-1] == "nul"

    monkeypatch.setattr(os.path, "isfile", sees_reserved)
    findings, _allow, _files = leak_sweep.sweep(root)
    debris = [f for f in findings
              if f.get("literals") == ["<reserved-name-root-debris>"]]
    assert debris, "the sweep missed the reserved-name root debris"
    assert debris[0]["path"].endswith("nul")


def test_dotless_judge_doc_truth_matches_the_pinned_negative():
    """R7-N5-r4-1 (round 4): the gate's normative prose must agree with
    its own judge — the round-3 docstring claimed the non-resolving octal
    form 'stays banned by the floor' while the code, the pinned negative
    row, and the decision record all say it does NOT resolve and is
    deliberately NOT banned. The false claim must never return."""
    source = (ROOT / "tools" / "leak_sweep.py").read_text(encoding="utf-8")
    assert "stays banned by the floor" not in source, (
        "the dotless docstring again claims a token the judge passes is "
        "banned (R7-N5-r4-1)"
    )
    # the docstring's rationale must match the pinned negative row's
    # (decimal-value, unsigned-bound reasoning):
    assert "exceeds the bound and does NOT resolve" in source, (
        "the dotless docstring lost the measured not-resolving rationale"
    )
    # and the judge itself still passes the non-resolving form (the pinned
    # negative's truth, held at the unit level):
    assert leak_sweep._privacy_dotless_int_is_address("037777777777") is False
    assert leak_sweep._privacy_dotless_int_is_address("0123456789") is True
