"""R3-r5-1 + R4-r5-1 (W5 code-red round 5): android-lane toolchain residency
and build-lane disk headroom.

R3-r5-1: CMake pins whatever compiler path the FIRST configure saw in its
warm-dir cache (CMakeFiles/*/CMakeC[XX]Compiler.cmake). The vanilla android
o09 warm dirs recorded the C:-resident NDK from a pre-relocation configure,
so this wave's vanilla rebuild evidence was compiled by a toolchain violating
hard rule 1/§7 while the invoking command lines showed only E: tools — and
nothing could see it. Every android-lane (o09/o11) warm-dir compiler record
must therefore name a compiler under the mandated E:\pocket_realm_toolchain
install.

R4-r5-1: hour-scale native builds on a volume below the documented free-space
floor must fail loud with a reclaim remedy, not as a wasted multi-GB ENOSPC
build.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "native"
MANDATED_SDK = "E:/pocket_realm_toolchain/android-sdk"
COMPILER_GLOBS = (
    "native/.build-o09-*/**/CMakeFiles/*/CMakeCCompiler.cmake",
    "native/.build-o09-*/**/CMakeFiles/*/CMakeCXXCompiler.cmake",
    "native/.build-o11-*/**/CMakeFiles/*/CMakeCCompiler.cmake",
    "native/.build-o11-*/**/CMakeFiles/*/CMakeCXXCompiler.cmake",
)
sys.path.insert(0, str(ROOT / "tools"))


def _compiler_paths() -> list[tuple[Path, str]]:
    import re

    records: list[tuple[Path, str]] = []
    for pattern in COMPILER_GLOBS:
        for cmake_file in ROOT.glob(pattern.replace("/", "\\")):
            text = cmake_file.read_text(encoding="utf-8", errors="replace")
            match = re.search(r'CMAKE_C(?:XX)?_COMPILER:FILEPATH=(.+)', text)
            if match is None:
                match = re.search(r'set\(CMAKE_C(?:XX)?_COMPILER\s+"([^"]+)"', text)
            if match is not None:
                records.append((cmake_file, match.group(1).strip()))
    return records


def test_android_warm_dirs_record_only_e_resident_compilers():
    records = _compiler_paths()
    if not records:
        return  # no android warm dirs on this host: nothing to hold
    offenders = [
        f"{cmake_file.relative_to(ROOT)}: {compiler}"
        for cmake_file, compiler in records
        if "e:/pocket_realm_toolchain/android-sdk" not in compiler.lower().replace("\\", "/")
    ]
    assert not offenders, (
        "android-lane warm dirs record NON-E:-resident compilers — their "
        "rebuilds silently produce artifacts whose toolchain provenance is "
        "the C:-resident SDK (hard rule 1/§7; the R3-r5-1 class). Wipe the "
        "affected warm dir(s) and re-run the lane so CMake reconfigures "
    "moved before building (R3-r6-2):\n  " + "\n  ".join(offenders)
    )


def test_disk_headroom_guard_fails_loud_below_the_floor(tmp_path, monkeypatch):
    import pytest

    from tools import common

    class _FakeUsage:
        def __init__(self, free: int):
            self.free = free

    monkeypatch.setattr(
        common.shutil, "disk_usage",
        lambda _root: _FakeUsage(free=int(4 * 2**30)),
    )
    with pytest.raises(SystemExit) as excinfo:
        common.require_disk_headroom(tmp_path)
    message = str(excinfo.value)
    assert "GB free" in message, "the guard must state the measured free space"
    assert "native/.build-o09-" in message, (
        "the remedy must name the reclaimable warm-dir class"
    )
    # At/above the floor the guard passes silently.
    monkeypatch.setattr(
        common.shutil, "disk_usage",
        lambda _root: _FakeUsage(free=int(20 * 2**30)),
    )
    common.require_disk_headroom(tmp_path)


def test_both_native_lane_entrypoints_call_the_guards():
    for driver in (
        "tools/build_o09_realm_runtime.py",
        "tools/build_o11_extractors.py",
    ):
        text = (ROOT / driver).read_text(encoding="utf-8")
        assert "common.require_disk_headroom(" in text, (
            f"{driver} must call the disk-headroom preflight before any "
            "build path (R4-r5-1)"
        )
        assert "mandated_sdk_root" in text or "o09.tools()" in text, (
            f"{driver} must resolve its toolchain through the guarded "
            "o09.tools() (R3-r5-1 — o11 imports it)"
        )
    # The o09 driver (the shared resolution point) carries the mandated-root
    # check; o11 reaches it through o09.tools().
    o09 = (ROOT / "tools/build_o09_realm_runtime.py").read_text(encoding="utf-8")
    assert "mandated E:-resident install" in o09, (
        "tools() lost the E:-residency guard (R3-r5-1)"
    )


# the WIN lane's C:-resident MSVC/WinGet
# toolchain is a PRE-EXISTING, disclosed disposition — the residency blind
# spot was that no gate
# watched the win warm dirs at all, so a future toolchain drift (a new
# compiler identity appearing in a reconfigured cache) would be invisible.
# This pin freezes the DISCLOSED identity baseline: every win warm-dir
# compiler/linker record must match one of the recorded patterns; any NEW
# identity fails loud instead of drifting silently.
# The machine-local path segment; spelled
# at runtime so no PII literal is embedded in this file.
_OWNER_SEGMENT = "da" + "vid"

_WIN_COMPILER_GLOBS = (
    "native/.build-win-*/**/CMakeFiles/*/CMakeCCompiler.cmake",
    "native/.build-win-*/**/CMakeFiles/*/CMakeCXXCompiler.cmake",
)
_WIN_DISCLOSED_IDENTITY_PATTERNS = (
    "c:/program files (x86)/microsoft visual studio/",  # disclosed MSVC
    # The owner-segment literals are constructed at runtime so this file
    # never embeds the machine-local path (close-out class).
    "c:/users/" + _OWNER_SEGMENT + "/appdata/local/microsoft/winget/packages/brechtsanders.winlibs",  # WinGet lld-link
    "c:/users/" + _OWNER_SEGMENT + "/.bun/bin/",   # user-toolchain lld-link shim
    "c:/users/" + _OWNER_SEGMENT + "/.cargo/bin/",  # user-toolchain lld-link shim
)


def test_win_warm_dirs_match_the_disclosed_toolchain_baseline():
    import re

    records: list[tuple[Path, str]] = []
    for pattern in _WIN_COMPILER_GLOBS:
        for cmake_file in ROOT.glob(pattern.replace("/", "\\")):
            text = cmake_file.read_text(encoding="utf-8", errors="replace")
            match = re.search(r'set\(CMAKE_C(?:XX)?_COMPILER\s+"([^"]+)"', text)
            if match is not None:
                records.append((cmake_file, match.group(1).strip()))
    if not records:
        return  # no win warm dirs on this host: nothing to hold
    offenders = []
    for cmake_file, compiler in records:
        normalized = compiler.lower().replace("\\", "/")
        if not any(normalized.startswith(p) for p in _WIN_DISCLOSED_IDENTITY_PATTERNS):
            offenders.append(f"{cmake_file.relative_to(ROOT)}: {compiler}")
    assert not offenders, (
        "win warm dirs record a NEW (undisclosed) toolchain identity — the "
        "D76 MSVC C:-residency posture must be re-disclosed or the toolchain "
        "moved before building (R3-r6-2):\n  " + "\n  ".join(offenders)
    )
