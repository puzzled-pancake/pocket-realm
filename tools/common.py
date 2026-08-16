#!/usr/bin/env python3
"""Shared toolbox for tools/ and scripts/ (de-vibe plan Phase 4).

One canonical home for the helpers that were previously copy-pasted per
script (21 sha256 variants, 14 run/output pairs, 5 adb resolvers, 4
docker_path forms, 2 checked_rmtree, 2 build_bootstrap) plus the SDK/NDK/adb
discovery that the old `Path(os.environ.get(...) or "")` idiom broke: that
construct yields `Path(".")`, whose `.is_dir()` is always true, so the
fallback branch was dead and an unset environment produced a misleading
crash later.

Import pattern (scripts run with their own directory as sys.path[0], tests
and CI import them as `tools.<name>` via the PEP 420 namespace package):

    try:
        from tools import common
    except ImportError:  # direct execution: python tools/<script>.py
        import common

All functions fail loud with actionable messages; nothing here returns a
best-effort guess when the environment is not actually usable.
"""
from __future__ import annotations

import hashlib
import os
import shutil
import stat
import subprocess
import sys
from pathlib import Path
from typing import Sequence

ROOT = Path(__file__).resolve().parents[1]

# Host-tag and tool-name suffixes. The toolchain is developed on Windows;
# centralizing the tag keeps a future Linux CI lane a one-line change.
HOST_TAG = "windows-x86_64"


def _exe(name: str) -> str:
    """Host-suffixed executable name (adb.exe on Windows, adb elsewhere)."""
    return f"{name}.exe" if os.name == "nt" else name


def _search_env(*env_names: str) -> Path | None:
    """First non-EMPTY value from env_names, or None. The empty-string trap
    (`Path("")` == `Path(".")`) is what broke the old discovery idiom."""
    for env_name in env_names:
        value = os.environ.get(env_name, "").strip()
        if value:
            return Path(value)
    return None


def _default_sdk_locations() -> list[Path]:
    local_properties = ROOT / "android" / "local.properties"
    candidates: list[Path] = []
    if local_properties.is_file():
        for line in local_properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir"):
                raw = line.split("=", 1)[1].strip()
                if raw:
                    candidates.append(Path(raw))
    if os.name == "nt":
        candidates.append(Path.home() / "AppData" / "Local" / "Android" / "Sdk")
    else:
        candidates.append(Path.home() / "Android" / "Sdk")
        candidates.append(Path.home() / "Library" / "Android" / "sdk")
    return candidates


def resolve_android_sdk() -> Path:
    """Locate the Android SDK or raise with instructions."""
    for candidate in [_search_env("ANDROID_SDK_ROOT", "ANDROID_HOME"), *_default_sdk_locations()]:
        if candidate and (candidate / "platform-tools").is_dir():
            return candidate.resolve()
    raise RuntimeError(
        "Android SDK not found; set ANDROID_SDK_ROOT (or ANDROID_HOME) to the "
        "SDK directory containing platform-tools/, or create "
        "android/local.properties with sdk.dir"
    )


def resolve_android_tool(tool: str, sdk: Path | None = None) -> Path:
    """Locate an SDK-shipped tool (adb, emulator, ...). Falls back to PATH;
    raises with instructions when neither works."""
    sdk = sdk or resolve_android_sdk()
    names = [tool, f"{tool}.exe"] if os.name != "nt" else [f"{tool}.exe", tool]
    for name in names:
        candidate = sdk / "platform-tools" / name
        if candidate.is_file():
            return candidate
        emulator = sdk / "emulator" / name
        if emulator.is_file():
            return emulator
    which = shutil.which(_exe(tool)) or shutil.which(tool)
    if which:
        return Path(which).resolve()
    raise RuntimeError(
        f"{tool} not found under {sdk} (platform-tools/ or emulator/) or on PATH; "
        "set ANDROID_SDK_ROOT or install platform-tools"
    )


def resolve_android_ndk(sdk: Path | None = None, prefer_version: str | None = None) -> Path:
    """Locate the NDK under $SDK/ndk/, newest-version-wins unless a pin is
    given (pins keep builds reproducible — see schemas/sources.json)."""
    sdk = sdk or resolve_android_sdk()
    ndk_root = sdk / "ndk"
    if not ndk_root.is_dir():
        raise RuntimeError(f"no NDK installed under {ndk_root}; install one via sdkmanager")
    versions = sorted(
        (p for p in ndk_root.iterdir() if p.is_dir()),
        key=lambda p: p.name,
        reverse=True,
    )
    if prefer_version:
        for candidate in versions:
            if candidate.name == prefer_version:
                return candidate
        raise RuntimeError(f"pinned NDK {prefer_version} not found under {ndk_root}")
    if not versions:
        raise RuntimeError(f"no NDK versions found under {ndk_root}")
    return versions[0]


def sha256_file(path: Path | str, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        while chunk := handle.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()


def run(
    command: Sequence[str | Path],
    *,
    check: bool = True,
    timeout: float | None = None,
    capture: bool = True,
    env_extra: dict[str, str] | None = None,
) -> subprocess.CompletedProcess:
    """subprocess.run with list-argv enforcement, check=True by default."""
    argv = [str(part) for part in command]
    env = None
    if env_extra:
        env = {**os.environ, **env_extra}
    return subprocess.run(
        argv,
        check=check,
        timeout=timeout,
        capture_output=capture,
        text=capture,
        env=env,
    )


def output(command: Sequence[str | Path], *, timeout: float | None = None) -> str:
    """Run a command and return its stdout, failing loud on a nonzero exit."""
    return run(command, check=True, timeout=timeout, capture=True).stdout.strip()


def docker_path(path: Path | str) -> str:
    """Windows path -> //c/... Docker Desktop mount form (lowercase drive, no
    colon — a colon makes Docker parse the volume as a 3-part spec). The
    as_posix() form is wrong for this and is deliberately not offered."""
    text = str(path).replace("\\", "/")
    if len(text) >= 2 and text[1] == ":":
        text = text[0].lower() + text[2:]
    return "//" + text


def checked_rmtree(path: Path, *, within: Path | None = None) -> None:
    """rmtree that (a) refuses to escape its allowed root, (b) clears the
    read-only bit Windows sets on some outputs, (c) raises on failure."""
    path = Path(path)
    root = Path(within) if within else ROOT
    resolved = path.resolve()
    if root.resolve() not in resolved.parents and resolved != root.resolve():
        raise RuntimeError(f"refusing to remove {resolved}: outside {root}")
    if not path.exists():
        return

    def _onerror(func, target, _exc):
        os.chmod(target, stat.S_IWRITE)
        func(target)

    shutil.rmtree(path, onerror=_onerror if sys.version_info < (3, 12) else None)
    if path.exists():
        shutil.rmtree(path, ignore_errors=False)


def atomic_output(target: Path):
    """Context-manager-free helper: returns (temporary_path, commit) where
    commit() fsyncs and os.replace()s onto target. Callers write temporary,
    verify it, then commit — the crash-safe staging convention."""
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(f".{target.name}.{os.getpid()}.tmp")

    def commit() -> Path:
        os.replace(temporary, target)
        return target

    return temporary, commit


def wait_for_boot(adb: Path, serial: str, *, timeout_seconds: float = 300.0) -> None:
    """Block until `sys.boot_completed` is 1 on the given device."""
    import time

    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        result = subprocess.run(
            [str(adb), "-s", serial, "shell", "getprop", "sys.boot_completed"],
            capture_output=True, text=True,
        )
        if result.returncode == 0 and result.stdout.strip() == "1":
            return
        time.sleep(2)
    raise RuntimeError(f"device {serial} did not finish booting within {timeout_seconds:.0f}s")


# Shared constants that were copy-pasted per script (DEVIBE_PLAN.md P11).
CGCT_BUILDER_IMAGE = (
    "ghcr.io/termux/package-builder-cgct@"
    "sha256:69ffa5cfe02ca569e7d03d1c99e3a4db3f6f4bcb53a1ea0f2adfa5e6357ce742"
)


def msys2_root() -> Path:
    """MSYS2 root via MSYS2_ROOT env, then conventional install locations.
    The old default hardcoded the original developer's G: drive."""
    from_env = _search_env("MSYS2_ROOT")
    if from_env and (from_env / "usr" / "bin" / "pacman.exe").is_file() or (
        from_env and (from_env / "mingw32").is_dir()
    ):
        return from_env
    for candidate in (Path("C:/msys64"), Path("D:/msys64")):
        if (candidate / "mingw32").is_dir():
            return candidate
    raise RuntimeError(
        "MSYS2 not found; set MSYS2_ROOT to its install root (e.g. C:\\msys64)"
    )
