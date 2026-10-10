"""Crash-trace root contract pins (R3-2/R5-1, native-server round 1).

The native crash tracer (win_exception_shim.cpp pocket_crash_file) must
resolve the SAME data root the whole product resolves — POCKET_REALM_DATA_ROOT
(the D50 override) IS the PocketRealm root when set; only when it is unset
does the shipped %LOCALAPPDATA%\\PocketRealm default apply. The two owning
resolvers are DesktopStorageRoots.defaultRoot (Kotlin) and
tools/common.product_data_root (Python); the tracer is the native twin of
their contract, and tools/symbolize_crash_trace.py reads the trace from
product_data_root()/crash-trace.txt — writer and reader must share the root
or a relocated run's trace symbolizes nothing.

These are source-contract pins: they assert the resolution ORDER and SHAPE
in each resolver's source, so a resolver that drifts (env order swapped,
override nested under PocketRealm, reader repointed) fails here.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHIM_CPP = ROOT / "native/realm-runtime/src/win_exception_shim.cpp"
COMMON_PY = ROOT / "tools/common.py"
STORAGE_KT = ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopStorageRoots.kt"
SYMBOLIZE_PY = ROOT / "tools/symbolize_crash_trace.py"


def _pocket_crash_file_body() -> str:
    source = SHIM_CPP.read_text(encoding="utf-8")
    start = source.index("HANDLE pocket_crash_file()")
    body = source[start:]
    end = body.index("\nvoid pocket_write_trace(")
    return body[:end]


def test_tracer_resolves_the_override_before_the_localappdata_fallback():
    body = _pocket_crash_file_body()
    override_at = body.find('std::getenv("POCKET_REALM_DATA_ROOT")')
    fallback_at = body.find('std::getenv("LOCALAPPDATA")')
    assert override_at != -1, "pocket_crash_file lost the POCKET_REALM_DATA_ROOT read"
    assert fallback_at != -1, "pocket_crash_file lost the LOCALAPPDATA fallback"
    assert override_at < fallback_at, (
        "the crash tracer must resolve POCKET_REALM_DATA_ROOT before the "
        "LOCALAPPDATA fallback (the owning resolvers' order, R3-2/R5-1)"
    )


def test_tracer_treats_the_override_as_the_root_itself():
    body = _pocket_crash_file_body()
    # the override branch appends ONLY the file name: the override IS the
    # PocketRealm root (DesktopStorageRoots.defaultRoot's File(configured))
    assert re.search(r'have_override[^\\n]*\\n[^"]*"%s\\\\crash-trace\.txt"', body) or \
        '"%s\\\\crash-trace.txt"' in body, (
        "the override branch must use <root>\\\\crash-trace.txt — the override "
        "IS the root, never <root>\\\\PocketRealm\\\\crash-trace.txt"
    )
    assert '"%s\\\\PocketRealm\\\\crash-trace.txt"' in body, (
        "the LOCALAPPDATA fallback branch lost the PocketRealm subtree"
    )


def test_tracer_creates_the_trace_parent_directory():
    body = _pocket_crash_file_body()
    assert "CreateDirectoryA" in body, (
        "OPEN_ALWAYS never creates parent directories — a relocated root's "
        "first crash must still land (R3-2/R5-1)"
    )


def test_python_owner_resolves_the_same_root_and_order():
    source = COMMON_PY.read_text(encoding="utf-8")
    body = source[source.index("def product_data_root"):]
    body = body[:body.index("\ndef ", 1)]
    override_at = body.find('os.environ.get("POCKET_REALM_DATA_ROOT"')
    fallback_at = body.find('os.environ.get("LOCALAPPDATA")')
    assert override_at != -1 and fallback_at != -1
    assert override_at < fallback_at, "product_data_root flipped the resolution order"
    assert "Path(override)" in body, (
        "the override must be the root itself (never nested under PocketRealm)"
    )


def test_kotlin_owner_resolves_the_same_root_and_order():
    source = STORAGE_KT.read_text(encoding="utf-8")
    body = source[source.index("fun defaultRoot():"):]
    prop_at = body.find('System.getProperty(ENV_DATA_ROOT)')
    env_at = body.find('System.getenv(ENV_DATA_ROOT)')
    fallback_at = body.find('System.getenv("LOCALAPPDATA")')
    assert -1 not in (prop_at, env_at, fallback_at)
    assert prop_at < env_at < fallback_at, (
        "DesktopStorageRoots.defaultRoot must keep property -> env -> "
        "LOCALAPPDATA order (the test seam precedes the production env read)"
    )
    assert '"PocketRealm",' in body, (
        "the LOCALAPPDATA fallback lost the PocketRealm subtree"
    )


def test_crash_trace_reader_shares_the_product_root():
    source = SYMBOLIZE_PY.read_text(encoding="utf-8")
    assert re.search(
        r"DEFAULT_TRACE\s*=\s*common\.product_data_root\(\)\s*/\s*\"crash-trace\.txt\"",
        source,
    ), "the reader must resolve crash-trace.txt under common.product_data_root()"
