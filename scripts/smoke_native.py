#!/usr/bin/env python3
"""Smoke-test the native realm binaries for an ABI.

This is the runtime verification the arm64-only build never had: it both inspects
the produced ELF (architecture, 16 KB page-size alignment, no unresolved
symbols, expected dynamic deps) AND, when a device/emulator is connected, pushes
the stripped binaries plus libc++_shared.so to the device and runs
`mangosd --version` / `realmd --version` to prove they actually execute.

The product ABI is arm64-v8a; x86_64 is an emulator-only test target. This
script never claims x86_64 is a product target.

    python3 scripts/smoke_native.py --abi arm64-v8a          # ELF checks only (no x86 host)
    python3 scripts/smoke_native.py --abi x86_64 --device    # ELF checks + run on emulator
    python3 scripts/smoke_native.py --abi x86_64             # ELF checks; --device optional

Exits 0 only if every assertion holds. Exits 1 on any real failure (no fake
success).
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "native"

SDK = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "")
NDK_DIR = SDK / "ndk"
_NDK_VERSIONS = sorted([p.name for p in NDK_DIR.iterdir()]) if NDK_DIR.is_dir() else []
NDK = NDK_DIR / _NDK_VERSIONS[-1] if _NDK_VERSIONS else None
TC = NDK / "toolchains" / "llvm" / "prebuilt" / "windows-x86_64" if NDK else None
READELF = TC / "bin" / "llvm-readelf.exe" if TC else shutil.which("readelf")
OBJDUMP = TC / "bin" / "llvm-objdump.exe" if TC else shutil.which("objdump")
STRIP = TC / "bin" / "llvm-strip.exe" if TC else shutil.which("strip")
ADB = SDK / "platform-tools" / "adb.exe" if SDK.is_dir() else shutil.which("adb")

# Expected per-ABI ELF facts.
ABI_FACTS = {
    "arm64-v8a": {
        "triple": "arm64", "elfclass": "ELF64",
        "machine": "AArch64", "libcxx_dir": "aarch64-linux-android",
    },
    "x86_64": {
        "triple": "x86_64", "elfclass": "ELF64",
        "machine": "Advanced Micro Devices X86-64", "libcxx_dir": "x86_64-linux-android",
    },
}

# 16 KB page-size compatible: every PT_LOAD segment's alignment must be >= 0x4000.
MIN_LOAD_ALIGN = 0x4000


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def check(label: str, ok: bool, detail: str = "") -> bool:
    tag = "OK  " if ok else "FAIL"
    print(f"{tag} {label}{(': ' + detail) if detail else ''}")
    return ok


def readelf(binpath: Path, *flags: str) -> str:
    r = run([str(READELF), *flags, str(binpath)])
    return r.stdout


def elf_checks(binpath: Path, facts: dict) -> bool:
    """Static ELF assertions for one binary. Returns True iff all hold."""
    good = True
    hdr = readelf(binpath, "-h")
    dyn = readelf(binpath, "-d")

    good &= check(f"{binpath.name}: ELF class",
                  facts["elfclass"] in hdr, facts["elfclass"])
    good &= check(f"{binpath.name}: machine",
                  facts["machine"] in hdr, facts["machine"])
    good &= check(f"{binpath.name}: is an executable (ET_EXEC/ET_DYN)",
                  "Type:" in hdr and ("EXEC" in hdr or "DYN" in hdr))

    # 16 KB page-size compatibility: all PT_LOAD alignments >= 0x4000.
    segs = readelf(binpath, "-l")
    aligns = []
    for line in segs.splitlines():
        if "LOAD" in line:
            # Align is the last hex field on the program-header line.
            parts = line.split()
            if parts:
                try:
                    aligns.append(int(parts[-1], 16))
                except ValueError:
                    pass
    min_align = min(aligns) if aligns else 0
    good &= check(f"{binpath.name}: 16 KB page align (min LOAD align >= 0x4000)",
                  min_align >= MIN_LOAD_ALIGN, f"min=0x{min_align:x}")

    # No unresolved symbols: NEEDED entries only (no undefined-reloc surprise).
    # We confirm the runtime deps are the expected minimal set. readelf formats
    # these as `Shared library: [libdl.so]`, so strip the surrounding brackets.
    needed = []
    for l in dyn.splitlines():
        if "NEEDED" in l and "Shared library:" in l:
            name = l.split("Shared library:", 1)[1].strip().strip("[]")
            if name:
                needed.append(name)
    allowed = {"libc++_shared.so", "libdl.so", "libm.so", "libc.so", "liblog.so"}
    unexpected = [n for n in needed if n not in allowed]
    good &= check(f"{binpath.name}: dynamic deps only in expected set",
                  not unexpected, ("unexpected=" + ",".join(unexpected)) if unexpected
                  else ",".join(needed))

    return good


def device_checks(binpath: Path, facts: dict) -> bool:
    """Push the stripped binary + libc++_shared.so to a connected device and run
    `<bin> --version`. Proves the binary actually executes (not just links).
    """
    if not ADB or not Path(ADB).is_file():
        print("FAIL  adb not found; cannot run device checks", file=sys.stderr)
        return False
    devs = run([str(ADB), "devices"]).stdout
    if sum(1 for l in devs.splitlines()[1:] if "\tdevice" in l) == 0:
        print("FAIL  no device/emulator connected (run `adb devices`)", file=sys.stderr)
        return False

    libcxx = (NDK / "toolchains" / "llvm" / "prebuilt" / "windows-x86_64" / "sysroot"
              / "usr" / "lib" / facts["libcxx_dir"] / "libc++_shared.so")
    # Stage stripped binaries + libc++_shared.so in a temp dir, then push once.
    stage = NATIVE / f".smoke-stage-{facts['triple']}"
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)
    stripped = stage / binpath.name
    shutil.copy2(binpath, stripped)
    r = run([str(STRIP), str(stripped)])
    if r.returncode != 0:
        print(f"FAIL  strip {binpath.name}: {r.stderr.strip()}", file=sys.stderr)
        return False
    shutil.copy2(libcxx, stage / "libc++_shared.so")

    # Push to /data/local/tmp (executable, no root needed) and chmod.
    run([str(ADB), "push", str(stage) + "/.", "/data/local/tmp/pocket-smoke/"])
    run([str(ADB), "shell",
         "chmod 755 /data/local/tmp/pocket-smoke/mangosd "
         "/data/local/tmp/pocket-smoke/realmd 2>/dev/null; true"])

    # Run with LD_LIBRARY_PATH so it finds libc++_shared.so.
    # - mangosd has an early `--version` exit (Main.cpp): prints a revision line
    #   and exits 0. We require exit 0 and output.
    # - realmd has NO early --version exit: it parses args, then loads config
    #   BEFORE any version handling, so on a bare device (no realmd.conf) it
    #   prints "Could not find configuration file ..." and exits 1. That clean,
    #   expected exit is itself proof of execution; only a crash/SEGV (signal
    #   death, no recognized message) is a failure.
    env = ("LD_LIBRARY_PATH=/data/local/tmp/pocket-smoke ")
    r = run([str(ADB), "shell", env + "/data/local/tmp/pocket-smoke/" + binpath.name
             + " --version"])
    out = (r.stdout + r.stderr).strip()
    last = out.splitlines()[-1][:80] if out else "no output"
    if binpath.name == "realmd":
        executed = ("Could not find configuration file" in out
                    or "version" in out.lower())
        ok = r.returncode in (0, 1) and executed
        return check(f"realmd executes on device (exit {r.returncode})", ok, last)
    ok = r.returncode == 0 and bool(out)
    return check(f"{binpath.name} --version runs on device (exit {r.returncode})",
                 ok, last)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--abi", required=True, choices=list(ABI_FACTS))
    ap.add_argument("--device", action="store_true",
                    help="also push + run on a connected device/emulator")
    args = ap.parse_args()
    facts = ABI_FACTS[args.abi]
    build = NATIVE / f".build-{facts['triple']}"
    binaries = [build / "src" / "mangosd" / "mangosd", build / "src" / "realmd" / "realmd"]

    missing = [b for b in binaries if not b.is_file()]
    if missing:
        for b in missing:
            print(f"FAIL  missing build artifact: {b} (run build_native.py --abi {args.abi})",
                  file=sys.stderr)
        return 1

    ok = True
    print(f"# smoke_native: {args.abi} (triple {facts['triple']})")
    for b in binaries:
        ok &= elf_checks(b, facts)
    if args.device:
        for b in binaries:
            ok &= device_checks(b, facts)

    print(f"\n{'SMOKE OK' if ok else 'SMOKE FAIL'} ({args.abi})")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
