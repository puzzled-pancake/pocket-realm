#!/usr/bin/env python3
"""Cross-build the O06 G1 self-test PE with the pinned MinGW toolchain.

Produces pocket_selftest.exe — a 32-bit Win32 PE (the same bitness WoW.exe is,
so it exercises Wine's new-WoW64 thunk path) from runtime/wine-x86_64-wow64/
selftest/pocket_selftest.c. The PE is project-owned, LGPL-clean code.

Toolchain: mingw-w64-i686-gcc 16.1.0-5 (pinned in sources.json). MSYS2 publishes
only a PGP .sig for its source archive, so the source-archive SHA-256 is
computed and recorded here on first build (the package SHA-256 is the pin).

The output PE ships as an authorized guest PE (NOT a Wine-owned runtime module);
it is materialized into filesDir by the spike harness as guest-code.

Usage:
  python3 tools/build_selftest_pe.py                 # build if toolchain present
  python3 tools/build_selftest_pe.py --check-toolchain  # verify MinGW only
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "runtime" / "wine-x86_64-wow64" / "selftest" / "pocket_selftest.c"
OUT_DIR = ROOT / "runtime" / "wine-x86_64-wow64" / "selftest"
OUT_PE = OUT_DIR / "pocket_selftest.exe"
SOURCES = ROOT / "schemas" / "sources.json"

# winmm is needed for the audio probe (waveOutOpen/Close).
LIBS = ["gdi32", "user32", "winmm"]


def find_mingw_gcc() -> tuple[str | None, str | None]:
    """Locate i686-w64-mingw32-gcc + its bin dir (which must be on PATH for the
    gcc subprocess to find its runtime DLLs — libgmp/libmpfr/libzstd etc.).
    Without the bin dir on PATH, gcc spawns, fails to load a DLL, and exits 1
    with NO output (silent spawn death). Returns (gcc_path, bin_dir)."""
    candidates = [
        r"G:\msys64\mingw32\bin\i686-w64-mingw32-gcc.exe",
        r"C:\msys64\mingw32\bin\i686-w64-mingw32-gcc.exe",
    ]
    found = shutil.which("i686-w64-mingw32-gcc")
    if found:
        candidates.insert(0, found)
    for c in candidates:
        if c and Path(c).is_file():
            return c, str(Path(c).parent)
    return None, None


def gcc_version(gcc: str) -> str:
    r = subprocess.run([gcc, "--version"], capture_output=True, text=True)
    return r.stdout.strip().splitlines()[0] if r.returncode == 0 else ""


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--check-toolchain", action="store_true",
                    help="verify the MinGW toolchain presence/version, don't build")
    args = ap.parse_args()

    gcc, bin_dir = find_mingw_gcc()
    if not gcc:
        print("ERROR: i686-w64-mingw32-gcc not found.", file=sys.stderr)
        print("Install via MSYS2:  pacman -S mingw-w64-i686-gcc", file=sys.stderr)
        print("(pinned: version 16.1.0-5, package SHA-256 in schemas/sources.json)", file=sys.stderr)
        return 2
    ver = gcc_version(gcc)
    print(f"MinGW: {gcc}")
    print(f"  {ver}")

    if args.check_toolchain:
        return 0

    if not SRC.is_file():
        print(f"ERROR: source not found: {SRC}", file=sys.stderr)
        return 2

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    cmd = [gcc, "-O2", "-Wall", "-mwindows",
           str(SRC), "-o", str(OUT_PE)]
    for lib in LIBS:
        cmd += ["-l" + lib]
    # The mingw32 bin dir MUST be on PATH for the gcc subprocess to find its
    # runtime DLLs (libgmp/libmpfr/libzstd). Without it gcc spawns, fails to
    # load a DLL, and exits 1 with NO output (silent spawn death).
    import os
    env = dict(os.environ)
    env["PATH"] = bin_dir + os.pathsep + env.get("PATH", "")
    print(f"  $ {' '.join(cmd)}")
    r = subprocess.run(cmd, env=env)
    if r.returncode != 0:
        print(f"FAIL: MinGW build returned {r.returncode}", file=sys.stderr)
        return 1

    # Hash the produced PE and record it (the PE is the artifact, not the source).
    h = hashlib.sha256()
    with OUT_PE.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    pe_sha = h.hexdigest()
    print(f"\nBuilt: {OUT_PE.relative_to(ROOT)}")
    print(f"  size: {OUT_PE.stat().st_size} bytes")
    print(f"  sha256: {pe_sha}")
    print(f"  (32-bit Win32 PE; authorized guest PE for the O06 spike)")

    # Record a small provenance sidecar next to the PE.
    sidecar = OUT_DIR / "pocket_selftest.exe.sha256"
    sidecar.write_text(f"{pe_sha}  pocket_selftest.exe\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
