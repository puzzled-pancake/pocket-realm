"""Round-3 R3-2: UTF-16/32 text is TEXT to both literal gates.

The NUL-byte binary sniff used to silently vanish the whole class —
including UTF-16LE .ps1 files, which PowerShell 5.1's Out-File default
produces — so a C: literal or a banned flavor literal inside one passed
both gates with a clean exit. Both gates now decode via BOM; these tests
pin that behavior (and would fail loudly if the decoding regressed).
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import check_e_only  # noqa: E402
import leak_sweep  # noqa: E402

BS = chr(92)
NL = chr(10)


def _git_repo(tmp_path: Path) -> Path:
    root = tmp_path / "repo"
    root.mkdir()
    subprocess.run(["git", "init", "-q"], cwd=root, check=True)
    subprocess.run(["git", "config", "user.email", "t@t"], cwd=root, check=True)
    subprocess.run(["git", "config", "user.name", "t"], cwd=root, check=True)
    return root


def _commit_all(root: Path) -> None:
    subprocess.run(["git", "add", "-A"], cwd=root, check=True)
    subprocess.run(
        ["git", "commit", "-q", "-m", "synthetic", "--no-gpg-sign", "--no-verify"],
        cwd=root,
        check=True,
    )


def _write_utf16(root: Path, rel: str, text: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(bytes([0xFF, 0xFE]) + text.encode("utf-16-le"))


def test_utf16_ps1_with_c_literal_is_caught(tmp_path):
    root = _git_repo(tmp_path)
    evil = "Copy-Item -Recurse E:" + BS + "staged" + BS + "tbc C:" + BS + "Wow clients" + BS + "tbc"
    _write_utf16(root, "tools/evil-lane.ps1", evil + NL)
    _commit_all(root)
    findings, scanned = check_e_only.literal_findings(root)
    assert scanned == 1, findings
    assert any(
        f["code"] == "E-LITERAL" and f["path"] == "tools/evil-lane.ps1"
        for f in findings
    ), findings


def test_utf16_kotlin_with_banned_literal_is_swept(tmp_path):
    root = _git_repo(tmp_path)
    leak_rel = "android/app/src/main/java/com/pocketrealm/client/Evil.kt"
    _write_utf16(root, leak_rel, 'val id = "wow-2.4.3-8606"' + NL)
    _commit_all(root)
    findings, _allowlisted, scanned = leak_sweep.sweep(root)
    assert scanned == 1, findings
    assert any(f["path"] == leak_rel for f in findings), findings


def test_clean_utf16_files_still_pass(tmp_path):
    root = _git_repo(tmp_path)
    _write_utf16(root, "tools/clean.ps1", "Write-Host 'hello'" + NL)
    _commit_all(root)
    findings, scanned = check_e_only.literal_findings(root)
    assert scanned == 1 and findings == [], findings
    leaks, _allow, _scanned = leak_sweep.sweep(root)
    assert leaks == [], leaks
