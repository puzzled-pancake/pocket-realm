#!/usr/bin/env python3
"""Build the desktop embedded-LLM sidecar: llama-server.exe (Windows).

The DESK-9/28 lane's producer: compiles the PINNED llama.cpp source
(schemas/sources.json `llama-cpp-6d05498`, staged by tools/stage_llama_cpp.py)
with the platform CMake toolchain into a single self-contained
llama-server.exe, stages it at native/.build-win-x86_64/llama-staging/
(the path packageApp copies into the jpackage image and
DesktopLlmModelManager.resolveSidecarBinary resolves on dev machines),
and pins the built binary's SHA-256 in native/llm/lockfile-win-x86_64.json
(the lockfile discipline the arm64-v8a lane established).

Build contract (the DESK-9 treatment: CPU-only on Windows):
  - target: llama-server ONLY (no tests/examples/other tools)
  - CPU backends only: no CUDA/Vulkan/HIP/SYCL (all default OFF; asserted)
  - LLAMA_CURL=OFF: the sidecar needs no network fetch dependency
  - static: BUILD_SHARED_LIBS=OFF + LLAMA_STATIC=ON so the exe carries
    ggml/llama/common statically (the image stages ONE exe, like the
    realm DLLs' app-local deployment; the VC/MinGW runtime DLLs follow
    the packageApp vcRuntimeDlls() lane)

Toolchain, in priority order (the sqlite-seam lane's reality on dev
machines): MSVC (vswhere -> vcvars64 + Ninja) when a Visual Studio
installation exists, else the WinLibs MinGW-w64 gcc on PATH (the
toolchain that builds the desktop sqlite seam). Every outcome is
verified fail-loud: the pinned staged source, the exe's existence, and
its recorded hash.

Output: native/.build-win-x86_64/llama-staging/llama-server.exe (+ lockfile)
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import stage_llama_cpp  # noqa: E402

BUILD = ROOT / "native" / ".build-win-x86_64" / "llama-server-build"
STAGING = ROOT / "native" / ".build-win-x86_64" / "llama-staging"
LOCKFILE = ROOT / "native" / "llm" / "lockfile-win-x86_64.json"
COMMIT = stage_llama_cpp.entry()["commit"]
SOURCE = ROOT / "native" / ".deps" / "src" / "llama" / COMMIT

# The CPU-only build contract (DESK-9): every accelerator path stays off.
CMAKE_FLAGS = [
    "-DLLAMA_BUILD_TESTS=OFF",
    "-DLLAMA_BUILD_EXAMPLES=OFF",
    "-DLLAMA_BUILD_TOOLS=ON",
    "-DLLAMA_CURL=OFF",
    # The sidecar runs --no-webui (the lane's argv); skip the Svelte UI
    # assembly (npm build + llama-ui-embed helper) entirely — one less
    # node/host-tool dependency in the native lane.
    "-DLLAMA_BUILD_UI=OFF",
    "-DLLAMA_STATIC=ON",
    "-DBUILD_SHARED_LIBS=OFF",
    "-DGGML_CUDA=OFF",
    "-DGGML_VULKAN=OFF",
    "-DGGML_HIP=OFF",
    "-DGGML_SYCL=OFF",
    "-DGGML_OPENMP=OFF",
    # MinGW defaults _WIN32_WINNT below Windows 10; cpp-httplib (the
    # server's HTTP layer) refuses to compile there. MSVC ignores the
    # explicit floor. The desktop lane's floor IS Windows 10 (the
    # jpackage launcher's own baseline).
    "-DCMAKE_CXX_FLAGS=-D_WIN32_WINNT=0x0A00",
    "-DCMAKE_C_FLAGS=-D_WIN32_WINNT=0x0A00",
]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_msvc_vcvars() -> Path | None:
    """vswhere -> vcvars64.bat, the build_win_realm_runtime discovery."""
    vswhere = Path(
        os.environ.get("ProgramFiles(x86)", "C:/Program Files (x86)"),
    ) / "Microsoft Visual Studio" / "Installer" / "vswhere.exe"
    if not vswhere.is_file():
        return None
    try:
        out = subprocess.run(
            [str(vswhere), "-latest", "-products", "*",
             "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
             "-property", "installationPath"],
            capture_output=True, text=True, timeout=60, check=True,
        ).stdout.strip().splitlines()
    except (subprocess.SubprocessError, OSError):
        return None
    if not out:
        return None
    vcvars = Path(out[0]) / "VC" / "Auxiliary" / "Build" / "vcvars64.bat"
    return vcvars if vcvars.is_file() else None


def find_mingw_gcc() -> Path | None:
    """The WinLibs MinGW-w64 gcc on PATH (the sqlite-seam dev toolchain)."""
    gcc = shutil.which("gcc")
    return Path(gcc) if gcc else None


def run_logged(
    cmd: list[str],
    workdir: Path,
    log: Path,
    env: dict | None = None,
    vcvars: Path | None = None,
) -> None:
    """Run one build step, logging output; fail loud on a nonzero exit.

    MSVC steps run through a vcvars64 .bat wrapper (the
    build_win_realm_runtime.in_msvc_env discipline) so cl/ninja resolve
    from the VS environment instead of the ambient PATH."""
    log.parent.mkdir(parents=True, exist_ok=True)
    if vcvars is not None:
        body = " ".join(f'"{part}"' if " " in part else part for part in cmd)
        script = log.with_suffix(".bat")
        script.write_bytes(
            (
                "@echo off\r\n"
                "set CCACHE_DISABLE=1\r\n"
                f'call "{vcvars}" >nul 2>&1\r\n'
                f'cd /d "{workdir}"\r\n'
                f"{body}\r\n"
                "exit /b %ERRORLEVEL%\r\n"
            ).encode("ascii"),
        )
        cmd = ["cmd", "/c", str(script)]
        workdir = Path.cwd()
    result = subprocess.run(
        cmd, cwd=str(workdir), env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    log.write_text(result.stdout[-20000:], encoding="utf-8", errors="replace")
    if result.returncode != 0:
        raise RuntimeError(
            f"{' '.join(cmd[:3])}... failed (exit {result.returncode}); "
            f"see {log}: {result.stdout[-2000:]}",
        )
    print(f"OK   {' '.join(cmd[:2])} ...")


def cmake_toolchain() -> tuple[list[str], Path | None]:
    """MSVC first (the documented Windows native lane), MinGW-w64 second
    (the dev-machine sqlite-seam reality)."""
    vcvars = find_msvc_vcvars()
    if vcvars is not None:
        print(f"OK   toolchain: MSVC via {vcvars}")
        # cl resolves inside the vcvars environment; Ninja ships with both
        # CMake and the VS install. Static CRT: the sidecar exe carries its
        # runtime, one less DLL the image must stage (the realm DLLs keep
        # /MD + vcRuntimeDlls(); a lone exe is simpler self-contained).
        return [
            "-G", "Ninja",
            "-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded",
        ], vcvars
    gcc = find_mingw_gcc()
    if gcc is None:
        raise SystemExit(
            "no C++ toolchain found: install Visual Studio C++ tools (vswhere-"
            "discoverable) or put a MinGW-w64 gcc on PATH; the sqlite-seam lane "
            "documents the WinLibs MinGW-w64 build this repo builds with",
        )
    print(f"OK   toolchain: MinGW-w64 gcc at {gcc}")
    # MinGW needs the gcc bin dir first in PATH so CMake picks the matching
    # toolset and the runtime DLLs come from the same tree.
    env = os.environ.copy()
    env["PATH"] = str(gcc.parent) + os.pathsep + env["PATH"]
    return [
        "-G", "Ninja",
        f"-DCMAKE_C_COMPILER={gcc}",
        f"-DCMAKE_CXX_COMPILER={str(gcc)[:-3]}++.exe" if str(gcc).endswith(".exe")
        else f"-DCMAKE_CXX_COMPILER={gcc}",
        "-DCMAKE_MAKE_PROGRAM=" + (shutil.which("ninja") or "ninja"),
        # Static libgcc/libstdc++/winpthread: build-host helper exes (the
        # server's ui-embed) and the sidecar itself must not resolve
        # libstdc++-6.dll from whichever foreign MinGW sits on PATH first
        # (0xC0000139 entry-point-not-found) — the sqlite-seam lane's
        # self-contained-DLL posture, applied to the exe lane.
        "-DCMAKE_EXE_LINKER_FLAGS=-static -static-libgcc -static-libstdc++",
    ], None


def main() -> None:
    if not (SOURCE / "CMakeLists.txt").is_file():
        raise SystemExit(
            f"{SOURCE} does not contain CMakeLists.txt — stage the pinned "
            "source first: python tools/stage_llama_cpp.py",
        )
    BUILD.mkdir(parents=True, exist_ok=True)
    cache_cxx = BUILD / "CMakeCache.txt"
    if cache_cxx.is_file():
        # A cached generator mismatch (toolchain switched between runs)
        # poisons reconfigure; start clean, the staged source is immutable.
        generator_switch = "llama-server-build: toolchain cache cleared"
        shutil.rmtree(BUILD)
        BUILD.mkdir(parents=True, exist_ok=True)
        print(f"OK   {generator_switch}")
    flags, vcvars = cmake_toolchain()
    configure_cmd = ["cmake", str(SOURCE), *flags, *CMAKE_FLAGS]
    run_logged(configure_cmd, BUILD, BUILD / "configure.log", vcvars=vcvars)
    build_cmd = ["cmake", "--build", ".", "--target", "llama-server", "--config", "Release"]
    run_logged(build_cmd, BUILD, BUILD / "build.log", vcvars=vcvars)

    # Locate the exe: llama.cpp sets RUNTIME_OUTPUT_DIRECTORY=bin for
    # single-config generators; MSVC multi-config drops it under Release/.
    candidates = [
        BUILD / "bin" / "llama-server.exe",
        BUILD / "llama-server.exe",
        BUILD / "Release" / "llama-server.exe",
    ]
    exe = next((c for c in candidates if c.is_file()), None)
    if exe is None:
        raise RuntimeError(
            "llama-server target reported success but no llama-server.exe "
            f"landed in {BUILD}; see {BUILD / 'build.log'}",
        )
    digest = sha256_file(exe)

    # Stage for the packaging + dev-resolution consumers.
    STAGING.mkdir(parents=True, exist_ok=True)
    staged = STAGING / "llama-server.exe"
    shutil.copy2(exe, staged)
    if sha256_file(staged) != digest:
        raise RuntimeError("staged copy hash diverged from the build output")

    # The lockfile pin (the arm64-v8a lane's discipline, Windows edition).
    LOCKFILE.parent.mkdir(parents=True, exist_ok=True)
    LOCKFILE.write_text(
        json.dumps({
            "schema": 1,
            "target": "llama-server",
            "abi": "x86_64",
            "os": "windows",
            "source": {
                "id": stage_llama_cpp.ENTRY_ID,
                "commit": COMMIT,
                "tarball_sha256": stage_llama_cpp.entry()["artifact_sha256"],
            },
            "build": {
                "script": "tools/build_win_llama_server.py",
                "contract": "CPU-only (no CUDA/Vulkan/HIP/SYCL/OpenMP), static, LLAMA_CURL off",
            },
            "artifact": {
                "name": "llama-server.exe",
                "path": "native/.build-win-x86_64/llama-staging/llama-server.exe",
                "bytes": staged.stat().st_size,
                "sha256": digest,
            },
        }, indent=1) + "\n",
        encoding="utf-8",
    )
    print(f"OK   llama-server.exe staged: {staged}")
    print(f"     bytes: {staged.stat().st_size}  sha256: {digest}")
    print(f"     pin:   {LOCKFILE.relative_to(ROOT)}")


if __name__ == "__main__":
    sys.exit(main())
