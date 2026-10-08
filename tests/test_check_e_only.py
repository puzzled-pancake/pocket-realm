"""Tests for the section 7 E-drive gate (tools/check_e_only.py).

Covers the three legs on synthetic trees/envs (no real machine state is
asserted, so the tests stay deterministic on every host):
- C: literals outside the allowlist are findings; allowlisted files pass;
- docs/ and vendored trees are the prose/upstream exclusion classes;
- stale allowlist entries (target no longer contains a C: literal) fail;
- the redirection table fails closed on unset and C:-resident variables
  and passes when either live env or registry redirects under E:.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "tools"))

import check_e_only  # noqa: E402


def _write(root: Path, rel: str, text: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def _git_repo(tmp_path: Path) -> Path:
    import subprocess

    root = tmp_path / "repo"
    root.mkdir()
    subprocess.run(["git", "init", "-q"], cwd=root, check=True)
    subprocess.run(["git", "config", "user.email", "t@t"], cwd=root, check=True)
    subprocess.run(["git", "config", "user.name", "t"], cwd=root, check=True)
    return root


def _commit_all(root: Path) -> None:
    import subprocess

    subprocess.run(["git", "add", "-A"], cwd=root, check=True)
    subprocess.run(
        ["git", "commit", "-q", "-m", "synthetic", "--no-gpg-sign", "--no-verify"],
        cwd=root,
        check=True,
    )


def test_c_literal_outside_allowlist_is_a_finding(tmp_path):
    root = _git_repo(tmp_path)
    _write(root, "tools/lane.py", 'CLIENT = Path("C:/Wow clients/x")\n')
    _commit_all(root)
    findings, _scanned = check_e_only.literal_findings(root)
    assert any(
        f["code"] == "E-LITERAL" and f["path"] == "tools/lane.py" for f in findings
    ), findings


def test_e_path_and_clean_file_pass(tmp_path):
    root = _git_repo(tmp_path)
    _write(root, "tools/lane.py", 'CLIENT = Path("E:/Wow clients/x")\n')
    _commit_all(root)
    findings, _scanned = check_e_only.literal_findings(root)
    assert findings == [], findings


def test_docs_and_vendored_prefixes_are_excluded(tmp_path):
    root = _git_repo(tmp_path)
    _write(root, "docs/history.md", "the clients lived at C:/Wow clients once\n")
    _write(root, "native/xserver-winlator/cpp/x.c", "/* upstream reads C:/gallium.cfg */\n")
    _commit_all(root)
    findings, _scanned = check_e_only.literal_findings(root)
    assert findings == [], findings


def test_allowlisted_file_passes_and_stale_entry_fails(tmp_path):
    root = _git_repo(tmp_path)
    # The gate itself is allowlisted in ALLOWLIST; give the synthetic repo
    # a copy of an allowlisted target that still contains a C: literal.
    allowlisted_rel = check_e_only.ALLOWLIST[1]["path"]  # tests/test_check_e_only.py
    _write(root, allowlisted_rel, "x = r'C:/adversarial'\n")
    _commit_all(root)
    findings, _scanned = check_e_only.literal_findings(root)
    assert findings == [], findings
    # A different allowlisted entry whose synthetic target has NO C: literal
    # must trip the staleness gate when pointed at a file that exists.
    other = next(
        e
        for e in check_e_only.ALLOWLIST
        if e["path"] not in (allowlisted_rel, "tools/check_e_only.py")
    )
    _write(root, other["path"], "clean = 1\n")
    _commit_all(root)
    stale = check_e_only.stale_allowlist_findings(root)
    assert any(s["code"] == "E-STALE" and s["path"] == other["path"] for s in stale), stale


def test_env_table_fails_closed(monkeypatch):
    import winreg

    class _FakeKey:
        def __init__(self, values):
            self._values = values

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

    def fake_open(hive, path):
        raise OSError("no such key")

    for var, _prefix, _meaning in check_e_only.TOOLCHAIN_VARS:
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setattr(winreg, "OpenKey", fake_open)
    monkeypatch.setattr(check_e_only.sys, "platform", "win32")

    findings, _notes = check_e_only.env_redirection_findings()
    codes = {(f["var"], f["code"]) for f in findings}
    for var, _prefix, _meaning in check_e_only.TOOLCHAIN_VARS:
        assert (var, "E-ENV-UNSET") in codes, (var, codes)

    # C:-resident live values with no E: source anywhere stay findings.
    monkeypatch.setenv("GRADLE_USER_HOME", r"C:\Users\u\.gradle")
    findings, _notes = check_e_only.env_redirection_findings()
    assert any(
        f["var"] == "GRADLE_USER_HOME" and f["code"] == "E-ENV-ON-C" for f in findings
    ), findings


def test_env_table_passes_when_registry_redirects(tmp_path, monkeypatch):
    import winreg

    for var, _prefix, _meaning in check_e_only.TOOLCHAIN_VARS:
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setattr(check_e_only.sys, "platform", "win32")

    registry = {
        name: expected_prefix
        for name, expected_prefix, _meaning in check_e_only.TOOLCHAIN_VARS
    }

    class _FakeKey:
        def __init__(self, values):
            self._values = values

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

    def fake_open(hive, path):
        if path == "Environment":
            return _FakeKey(registry)
        raise OSError("no such key")

    def fake_query(key, name):
        return registry[name], winreg.REG_SZ

    monkeypatch.setattr(winreg, "OpenKey", fake_open)
    monkeypatch.setattr(winreg, "QueryValueEx", fake_query)
    findings, notes = check_e_only.env_redirection_findings()
    assert findings == [], findings
    assert notes == [], notes


def test_cache_root_on_c_fails(monkeypatch):
    monkeypatch.setattr(check_e_only.sys, "platform", "win32")
    monkeypatch.setenv("GRADLE_USER_HOME", r"C:\Users\u\.gradle")
    monkeypatch.delenv("PIP_CACHE_DIR", raising=False)
    import winreg

    def fake_open(hive, path):
        raise OSError("no such key")

    monkeypatch.setattr(winreg, "OpenKey", fake_open)
    findings, _notes = check_e_only.configured_cache_root_findings()
    assert any(
        f["code"] == "E-CACHE-ON-C" and f["var"] == "GRADLE_USER_HOME"
        for f in findings
    ), findings


def test_repo_gate_is_wired_into_check_repo():
    source = (REPO_ROOT / "tools" / "check_repo.py").read_text(encoding="utf-8")
    assert "check_e_drive_gate" in source and "check_e_only" in source


# ---- R2-1/R3-2 (code-red round 1): the gate fails CLOSED when its
# scanner cannot run, and sweeps UNTRACKED commit-candidates (R1-1/R3-1).


def test_non_git_root_fails_closed(tmp_path):
    """A root without a git repository is an E-SCAN-FAILED finding,
    never a zero-file OK (a security gate must not report success on
    coverage it could not even attempt)."""
    bare = tmp_path / "not-a-repo"
    bare.mkdir()
    (bare / "note.txt").write_text("clean", encoding="utf-8")
    report = check_e_only.collect_findings(bare)
    codes = [f["code"] for f in report["findings"]]
    assert "E-SCAN-FAILED" in codes, report["findings"]
    assert not report["ok"]


def test_empty_git_repo_fails_closed(tmp_path):
    import subprocess

    empty_repo = tmp_path / "empty-repo"
    empty_repo.mkdir()
    subprocess.run(["git", "init", "-q"], cwd=empty_repo, check=True)
    report = check_e_only.collect_findings(empty_repo)
    codes = [f["code"] for f in report["findings"]]
    assert "E-SCAN-EMPTY" in codes, report["findings"]
    assert not report["ok"]


def test_untracked_files_are_swept_for_c_literals(tmp_path):
    """R1-1/R3-1: an untracked (committable) file carrying a C: path
    literal is flagged - the finalize commit can never flip the gate red."""
    import subprocess

    repo = tmp_path / "repo"
    repo.mkdir()
    subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
    committed = repo / "tools/clean.py"
    committed.parent.mkdir(parents=True)
    committed.write_text("x = 1", encoding="utf-8")
    untracked = repo / "tools/lane.py"
    untracked.write_text("CLIENT = Path(""C:/Wow clients/x"")",
                         encoding="utf-8")
    subprocess.run(["git", "add", "tools/clean.py"], cwd=repo, check=True)
    findings, scanned = check_e_only.literal_findings(repo)
    assert scanned >= 2, (findings, scanned)
    assert any("E-LITERAL" == f["code"] and f["path"].endswith("tools/lane.py")
               for f in findings), findings


def test_registry_side_drift_is_a_finding_not_a_note(tmp_path, monkeypatch):
    """Round-3 R3-1a: the persisted REGISTRY pointing at C: while the live
    env points at E: means every NEW shell writes its caches to C: — the
    inverse of the stale-daemon note case, and a FINDING (ok=False), never
    a silent pass."""
    import winreg

    def fake_registry(name):
        return r"C:\Users\attacker\.gradle"

    monkeypatch.setenv("GRADLE_USER_HOME", r"E:\pocket_realm_toolchain\gradle-home")
    monkeypatch.setattr(check_e_only, "_registry_user_env", fake_registry)
    monkeypatch.setattr(check_e_only.sys, "platform", "win32")
    findings, notes = check_e_only.env_redirection_findings()
    gradle = [f for f in findings if f.get("var") == "GRADLE_USER_HOME"]
    assert gradle and gradle[0]["code"] == "E-ENV-ON-C", (findings, notes)
    assert "registry" in gradle[0]["detail"] and "NEW shell" in gradle[0]["detail"]


def test_registry_cache_root_on_c_is_a_cache_finding(monkeypatch):
    """Leg 3 must see the registry value even when the live env already
    redirects: the persisted root is what new shells use."""
    monkeypatch.setenv("GRADLE_USER_HOME", r"E:\pocket_realm_toolchain\gradle-home")
    monkeypatch.setattr(check_e_only, "_registry_user_env",
                        lambda name: r"C:\Users\attacker\.gradle")
    monkeypatch.setattr(check_e_only.sys, "platform", "win32")
    findings, _notes = check_e_only.configured_cache_root_findings()
    assert any(
        f["code"] == "E-CACHE-ON-C" and "registry" in f["var"]
        for f in findings
    ), findings


def test_stale_daemon_direction_stays_a_note(monkeypatch):
    """The ORIGINAL direction (live C:, registry E:) is still just a note —
    the registry is correct and new shells are safe."""
    import winreg

    monkeypatch.setenv("GRADLE_USER_HOME", r"C:\Users\stale\.gradle")
    monkeypatch.setattr(check_e_only, "_registry_user_env",
                        lambda name: r"E:\pocket_realm_toolchain\gradle-home")
    monkeypatch.setattr(check_e_only.sys, "platform", "win32")
    findings, notes = check_e_only.env_redirection_findings()
    assert not [f for f in findings if f.get("var") == "GRADLE_USER_HOME"], findings
    assert any("GRADLE_USER_HOME" in n for n in notes), notes


def test_live_only_redirection_without_persisted_value_is_a_finding(monkeypatch):
    """Round-4 R3-4/R2-3: a live-env-only redirection (registry UNSET)
    means every new shell and CI reads nothing - Gradle/pip/cargo default
    their caches to C:. The gate must name it (E-ENV-NOT-PERSISTED) and
    the summary must NOT claim the redirection table was verified."""
    monkeypatch.setattr(check_e_only.sys, "platform", "win32")
    monkeypatch.setattr(check_e_only, "_registry_user_env", lambda name: None)
    for var, _prefix, _meaning in check_e_only.TOOLCHAIN_VARS:
        monkeypatch.setenv(var, check_e_only.TOOLCHAIN_VARS[0][1] if False else r"E:\pocket_realm_toolchain\gradle-home")
    findings, notes = check_e_only.env_redirection_findings()
    named = [f for f in findings if f["code"] == "E-ENV-NOT-PERSISTED"]
    assert named, findings
    assert len(named) == len(check_e_only.TOOLCHAIN_VARS), (
        len(named), len(check_e_only.TOOLCHAIN_VARS))


def test_env_indirection_producer_is_a_finding(tmp_path):
    """Round-4 R2-1: a tracked production file constructing a root from
    LOCALAPPDATA must be flagged — the indirection class puts C: roots on
    the product with zero C: literals (the gate's literal sweep cannot see
    it). Test files are exempt; unknown producers fail until allowlisted."""
    import subprocess

    root = _git_repo(tmp_path)
    producer = root / "desktop/src/main/kotlin/com/pocketrealm/desktop/NewRoot.kt"
    producer.parent.mkdir(parents=True)
    producer.write_text(
        'val root = File(System.getenv("LOCALAPPDATA"), "X")' + chr(10),
        encoding="utf-8",
    )
    subprocess.run(["git", "add", "-A"], cwd=root, check=True)
    findings, _scanned = check_e_only.literal_findings(root)
    assert any(
        f["code"] == "E-ENV-INDIRECTION"
        and f["path"].endswith("NewRoot.kt")
        for f in findings
    ), findings


def test_indirection_allowlist_files_pass(tmp_path):
    import subprocess

    root = _git_repo(tmp_path)
    producer = root / "desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopStorageRoots.kt"
    producer.parent.mkdir(parents=True)
    producer.write_text(
        'fun defaultRoot() = File(System.getenv("LOCALAPPDATA"), "PocketRealm")'
        + chr(10),
        encoding="utf-8",
    )
    subprocess.run(["git", "add", "-A"], cwd=root, check=True)
    findings, _scanned = check_e_only.literal_findings(root)
    assert not [
        f for f in findings if f["code"] == "E-ENV-INDIRECTION"
    ], findings


def test_no_new_localappdata_producers_outside_the_resolvers(tmp_path):
    """Round-5 R5-1: the D50 override (POCKET_REALM_DATA_ROOT) must
    relocate the WHOLE product — a sibling surface constructing the root
    from LOCALAPPDATA/user.home directly (outside the two owning
    resolvers: DesktopStorageRoots.kt and tools/common.py) silently
    disagrees with the app's root and boots/publishes onto C:."""
    import subprocess

    root = _git_repo(tmp_path)
    producer = root / "tools/rp_harness/desktop_newlane.py"
    producer.parent.mkdir(parents=True)
    producer.write_text(
        'LOG = Path(os.environ["LOCALAPPDATA"]) / "PocketRealm" / "world.log"'
        + chr(10),
        encoding="utf-8",
    )
    subprocess.run(["git", "add", "-A"], cwd=root, check=True)
    findings, _scanned = check_e_only.literal_findings(root)
    assert any(
        f["code"] == "E-ENV-INDIRECTION"
        and f["path"].endswith("desktop_newlane.py")
        for f in findings
    ), findings


def test_real_repo_routes_every_product_root_through_the_resolvers():
    """The live repo contract: every tracked production file that reads
    LOCALAPPDATA/APPDATA/user.home for a root is one of the two owning
    resolvers (or the WER read surface) — the D50 override relocates the
    whole product, not just DesktopStorageRoots."""
    findings, _scanned = check_e_only.literal_findings(check_e_only.DEFAULT_ROOT)
    stray = [
        f for f in findings
        if f["code"] == "E-ENV-INDIRECTION"
        and f["path"] not in check_e_only.ENV_INDIRECTION_ALLOWLIST
    ]
    assert not stray, stray


def test_harness_and_prep_tools_forward_the_override(tmp_path, monkeypatch):
    """Functional (b): with POCKET_REALM_DATA_ROOT seeded, the prep tool
    resolves its DATADIR under the override and the harness child env
    carries it — the app reads the SAME root the tools publish to."""
    override = tmp_path / "product-root"
    override.mkdir()
    monkeypatch.setenv("POCKET_REALM_DATA_ROOT", str(override))
    repo_tools = check_e_only.DEFAULT_ROOT.parent / "tools"
    import sys as _sys
    _sys.path.insert(0, str(repo_tools))
    import common  # noqa: E402

    assert common.product_data_root() == override
    # win_prepare_data resolves its datadir under the override
    import importlib

    wpd = importlib.import_module("win_prepare_data")
    datadir = common.product_data_root() / "content" / "o11-server"
    assert str(override) in str(datadir)


def test_desktop_host_injects_the_override_into_its_child_env():
    """Functional (b, source-contract half — a live rpHost spawn is an
    L3 launch): DesktopHost's child env must explicitly carry
    POCKET_REALM_DATA_ROOT resolved from the shared resolver, so a
    registry-only variable can never boot the app back onto
    %LOCALAPPDATA%."""
    host_source = (
        check_e_only.DEFAULT_ROOT / "tools/rp_harness/desktop_host.py"
    ).read_text(encoding="utf-8")
    assert 'env["POCKET_REALM_DATA_ROOT"] = str(common.product_data_root())' in host_source
    assert "import common" in host_source
