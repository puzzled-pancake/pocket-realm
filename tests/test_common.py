"""Unit tests for tools/common.py."""
from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import common  # noqa: E402


def test_sha256_file_streams_and_matches():
    import hashlib

    scratch = ROOT / ".tmp" / "scratch" / "test_common"
    scratch.mkdir(parents=True, exist_ok=True)
    blob = scratch / "blob.bin"
    payload = os.urandom(3 * 1024 * 1024)  # forces multiple chunks
    blob.write_bytes(payload)
    assert common.sha256_file(blob) == hashlib.sha256(payload).hexdigest()
    blob.unlink()


def test_docker_path_lowercases_drive_and_strips_colon():
    assert common.docker_path(Path(r"C:\work\repo")) == "//c/work/repo"
    assert common.docker_path("/srv/data") == "///srv/data" or common.docker_path("/srv/data").endswith("srv/data")


def test_docker_path_rejects_nothing_but_normalizes(tmp_path):
    assert common.docker_path(tmp_path).startswith("//")


def test_exe_suffix():
    if os.name == "nt":
        assert common._exe("adb") == "adb.exe"
    else:
        assert common._exe("adb") == "adb"


def test_search_env_ignores_empty_string():
    os.environ["PR_TEST_EMPTY"] = ""
    os.environ["PR_TEST_SET"] = str(ROOT)
    try:
        assert common._search_env("PR_TEST_EMPTY") is None
        assert common._search_env("PR_TEST_EMPTY", "PR_TEST_SET") == ROOT
    finally:
        del os.environ["PR_TEST_EMPTY"]
        del os.environ["PR_TEST_SET"]


def test_resolve_android_sdk_env(monkeypatch, tmp_path):
    fake_sdk = tmp_path / "sdk"
    (fake_sdk / "platform-tools").mkdir(parents=True)
    monkeypatch.setenv("ANDROID_SDK_ROOT", str(fake_sdk))
    monkeypatch.delenv("ANDROID_HOME", raising=False)
    assert common.resolve_android_sdk() == fake_sdk.resolve()


def test_resolve_android_sdk_missing_raises(monkeypatch, tmp_path):
    monkeypatch.setenv("ANDROID_SDK_ROOT", str(tmp_path / "nothing"))
    monkeypatch.delenv("ANDROID_HOME", raising=False)
    monkeypatch.setattr(common, "_default_sdk_locations", lambda: [])
    with pytest.raises(RuntimeError, match="ANDROID_SDK_ROOT"):
        common.resolve_android_sdk()


def test_run_check_default(tmp_path):
    import subprocess as sp

    with pytest.raises(sp.CalledProcessError):
        common.run([sys.executable, "-c", "import sys; sys.exit(3)"])
    ok = common.run([sys.executable, "-c", "print('hi')"])
    assert ok.stdout.strip() == "hi"


def test_checked_rmtree_refuses_outside_root(tmp_path):
    target = tmp_path / "child" / "inner"
    target.mkdir(parents=True)
    with pytest.raises(RuntimeError, match="outside"):
        common.checked_rmtree(target, within=tmp_path / "child" / "other-root")


def test_checked_rmtree_removes(tmp_path):
    target = tmp_path / "dir"
    (target / "sub").mkdir(parents=True)
    (target / "sub" / "file.txt").write_text("x")
    common.checked_rmtree(target, within=tmp_path)
    assert not target.exists()


def test_atomic_output_commits(tmp_path):
    target = tmp_path / "out.json"
    temporary, commit = common.atomic_output(target)
    temporary.write_text("{}", encoding="utf-8")
    assert commit() == target
    assert target.read_text(encoding="utf-8") == "{}"
    assert not temporary.exists()


def test_msys2_root_env(monkeypatch, tmp_path):
    fake = tmp_path / "msys64"
    (fake / "mingw32").mkdir(parents=True)
    monkeypatch.setenv("MSYS2_ROOT", str(fake))
    assert common.msys2_root() == fake


def test_msys2_root_missing_raises(monkeypatch):
    monkeypatch.setenv("MSYS2_ROOT", r"Q:\definitely\not\here")
    monkeypatch.delenv("MSYS2_ROOT", raising=False)
    with pytest.raises(RuntimeError, match="MSYS2_ROOT"):
        common.msys2_root()


def test_cgct_image_pinned_by_digest():
    assert common.CGCT_BUILDER_IMAGE.startswith("ghcr.io/termux/package-builder-cgct@sha256:")
