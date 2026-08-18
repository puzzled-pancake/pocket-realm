#!/usr/bin/env python3
"""Build the glibc-side Android path/syscall shim reproducibly.

The shim must be linked against glibc, so the Windows/NDK CMake build cannot
produce it.  Build it in the same pinned Termux CGCT Linux container used by
the source-built glibc closure, then place it beside libwine_spike.so for APK
staging.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "native" / "wine-spike" / "glibc" / "wine_android_shim.c"
OUTPUT = ROOT / "native" / ".build-x86_64" / "wine-spike-build" / "libwine_android_shim.so"
IMAGE = (
    "ghcr.io/termux/package-builder-cgct@"
    "sha256:69ffa5cfe02ca569e7d03d1c99e3c9a0f79390ad6bf11a3629d048c29c6ccb61"
)


def docker_path(path: Path) -> str:
    value = str(path.resolve()).replace("\\", "/")
    if len(value) >= 2 and value[1] == ":":
        value = "//" + value[0].lower() + value[2:]
    return value


def main() -> int:
    if not SOURCE.is_file():
        print(f"missing source: {SOURCE}", file=sys.stderr)
        return 2
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    mount = docker_path(ROOT)
    command = [
        "docker", "run", "--rm",
        "-v", f"{mount}:/work",
        "--workdir", "/work",
        IMAGE,
        # Wine transitions thread-local state while loading PE/unixlib pairs.
        # A preload interposer may span that transition, so a host glibc stack
        # canary read before the call and checked afterward is not stable.
        # The shim handles only bounded provider paths and uses snprintf for
        # every local buffer; build this one boundary library without SSP.
        "gcc", "-shared", "-fPIC", "-O2", "-fno-stack-protector",
        "-Wall", "-Wextra", "-Werror",
        "-Wl,-z,max-page-size=0x4000",
        "-o", "/work/native/.build-x86_64/wine-spike-build/libwine_android_shim.so",
        "/work/native/wine-spike/glibc/wine_android_shim.c",
    ]
    print(" ".join(command))
    result = subprocess.run(command)
    if result.returncode != 0:
        return result.returncode
    print(f"built {OUTPUT} ({OUTPUT.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
