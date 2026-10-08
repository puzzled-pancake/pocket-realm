"""Repo-wide pytest configuration.

Round-4 R3-4 (revised round-6 R6-r6-3/R3-r6-2): test scratch lives on E:
(hard rule 1) — but via PYTEST_DEBUG_TEMPROOT, NOT a fixed --basetemp.
pytest names fixture dirs uniquely per session under the temproot
(pytest-of-<user>/pytest-N), so parallel code-red reviewer sessions never
prune each other's mktemp directories the way the fixed
E:/pocket_realm_toolchain/tmp/pytest basetemp did.
"""
from __future__ import annotations

import os

import pytest

_TEMPROOT = "E:/pocket_realm_toolchain/tmp"


def pytest_configure(config):
    if not os.environ.get("PYTEST_DEBUG_TEMPROOT"):
        os.environ["PYTEST_DEBUG_TEMPROOT"] = _TEMPROOT


# W0 close-out: the pre-commit hook runs under git's own environment —
# GIT_DIR / GIT_WORK_TREE / GIT_INDEX_FILE point at the SUPERPROJECT for
# every child process, whatever cwd a test uses. Any test that subprocesses
# `git` for a SUBMODULE (or any per-cwd repo resolution, e.g. the byte-
# pristine pin, require_flavor_pins' dirty scan, the per-backend build
# roots) must resolve like a plain shell, so strip the inherited git
# context for the duration of every test. The git GATES themselves run
# outside pytest and keep their (correct, staged-index) environment.
_INHERITED_GIT_VARS = (
    "GIT_DIR",
    "GIT_WORK_TREE",
    "GIT_INDEX_FILE",
    "GIT_OBJECT_DIRECTORY",
    "GIT_ALTERNATE_OBJECT_DIRECTORIES",
    "GIT_COMMON_DIR",
    "GIT_PREFIX",
)


@pytest.fixture(autouse=True)
def _strip_inherited_git_context(monkeypatch):
    for var in _INHERITED_GIT_VARS:
        monkeypatch.delenv(var, raising=False)
