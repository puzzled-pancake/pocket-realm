#!/usr/bin/env python3
"""Cross-build the vendored vanilla-tweaks patcher (Rust) for Android.

Mirrors tools/build_o11_extractors.py conventions (per-ABI staging to jniLibs,
DT_NEEDED allowlist, 16 KiB LOAD-alignment check, BUILD_PROVENANCE.json +
lockfile) but builds with cargo instead of CMake. Introduces the repo's first
Rust toolchain: requires `rustup target add aarch64-linux-android
x86_64-linux-android` and the NDK clang wrappers as linkers.
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import build_o09_realm_runtime as o09

ROOT = Path(__file__).resolve().parents[1]

try:
    from tools import common
except ImportError:  # direct execution: python tools/<script>.py
    import common
SOURCE = ROOT / "native" / "vanilla-tweaks"
ABI = "x86_64"
RUST_TRIPLE = "x86_64-linux-android"
STAGE: Path = ROOT / "native" / ".build-vanilla-tweaks-x86_64" / "staging" / "jniLibs" / "x86_64"
PROVENANCE: Path = STAGE.parents[1] / "BUILD_PROVENANCE.json"
LOCKFILE: Path = ROOT / "schemas" / "vanilla-tweaks-lockfile.json"
MAX_PAGE = 0x4000
# Rust android binaries link bionic dynamically; rust std is statically linked.
ALLOWED_NEEDED = {"libc.so", "libdl.so", "libm.so"}
OUTPUT_NAME = "libpocket_vanilla_tweaks.so"


def select_abi(abi: str) -> None:
    global ABI, RUST_TRIPLE, STAGE, PROVENANCE, LOCKFILE
    if abi not in {"x86_64", "arm64-v8a"}:
        raise ValueError(f"unsupported vanilla-tweaks ABI: {abi}")
    ABI = abi
    RUST_TRIPLE = "x86_64-linux-android" if abi == "x86_64" else "aarch64-linux-android"
    root = ROOT / "native" / f".build-vanilla-tweaks-{abi}"
    STAGE = root / "staging" / "jniLibs" / abi
    PROVENANCE = STAGE.parents[1] / "BUILD_PROVENANCE.json"
    LOCKFILE = ROOT / (
        "schemas/vanilla-tweaks-lockfile.json" if abi == "x86_64"
        else f"schemas/vanilla-tweaks-lockfile-{abi}.json"
    )


def run(args: list[object], cwd: Path | None = None, env: dict | None = None) -> None:
    command = [str(value) for value in args]
    print("+", " ".join(command), flush=True)
    subprocess.run(command, check=True, cwd=cwd, env=env)


def output(args: list[object]) -> str:
    return subprocess.check_output([str(value) for value in args], text=True)


sha256 = common.sha256_file
def ndk_bin(ndk: Path) -> Path:
    prebuilt = ndk / "toolchains" / "llvm" / "prebuilt"
    host_dirs = [p for p in prebuilt.iterdir() if p.is_dir()] if prebuilt.is_dir() else []
    if not host_dirs:
        raise RuntimeError(f"NDK prebuilt dir not found under {prebuilt}")
    return host_dirs[0] / "bin"


def build(force: bool) -> tuple[Path, Path]:
    if not SOURCE.is_dir():
        raise RuntimeError(f"vendored vanilla-tweaks missing at {SOURCE}")
    ndk, _, _, llvm = o09.tools()
    bindir = ndk_bin(ndk)
    api = 26
    # On Windows the NDK ships .cmd wrappers; the extensionless wrappers are
    # shell scripts that cannot be exec'd by the linker. Prefer .cmd then .exe.
    def pick(name: str) -> Path:
        for candidate in (bindir / f"{name}.cmd", bindir / f"{name}.exe", bindir / name):
            if candidate.is_file():
                return candidate
        raise RuntimeError(f"NDK tool not found: {name}")
    clang = pick(f"{RUST_TRIPLE}{api}-clang")
    clangxx = pick(f"{RUST_TRIPLE}{api}-clang++")
    ar = pick("llvm-ar")
    env = os.environ.copy()
    env.update({
        f"CARGO_TARGET_{RUST_TRIPLE.upper().replace('-', '_')}_LINKER": str(clang),
        f"AR_{RUST_TRIPLE.replace('-', '_')}": str(ar),
        f"CC_{RUST_TRIPLE.replace('-', '_')}": str(clang),
        f"CXX_{RUST_TRIPLE.replace('-', '_')}": str(clangxx),
    })
    target_dir = SOURCE / "target"
    if force:
        shutil.rmtree(target_dir, ignore_errors=True)
    run(["cargo", "build", "--locked", "--release", "--target", RUST_TRIPLE], cwd=SOURCE, env=env)
    built = target_dir / RUST_TRIPLE / "release" / "vanilla-tweaks"
    if not built.is_file():
        raise RuntimeError(f"cargo did not produce the expected binary: {built}")
    return built, llvm


def stage(built: Path, llvm: Path) -> dict:
    STAGE.mkdir(parents=True, exist_ok=True)
    target = STAGE / OUTPUT_NAME
    shutil.copy2(built, target)
    strip = llvm / "llvm-strip.exe"
    if not strip.is_file():
        raise RuntimeError(
            f"llvm-strip not found at {strip}; DT_NEEDED/alignment verification "
            "must not be skipped silently (de-vibe P7)"
        )
    run([strip, "--strip-unneeded", target])
    readelf = llvm / "llvm-readelf.exe"
    needed = []
    aligns: list[int] = []
    if not readelf.is_file():
        raise RuntimeError(
            f"llvm-readelf not found at {readelf}; DT_NEEDED/alignment verification "
            "must not be skipped silently (de-vibe P7)"
        )
    dynamic = output([readelf, "-dW", target])
    needed = sorted(line.split("[")[1].split("]")[0]
                    for line in dynamic.splitlines() if "(NEEDED)" in line)
    unexpected = set(needed) - ALLOWED_NEEDED
    if unexpected:
        raise RuntimeError(f"unexpected DT_NEEDED for {OUTPUT_NAME}: {sorted(unexpected)}")
    program = output([readelf, "-lW", target])
    aligns = [int(line.split()[-1], 16) for line in program.splitlines()
              if line.lstrip().startswith("LOAD ")]
    if not aligns or any(value < MAX_PAGE for value in aligns):
        raise RuntimeError(f"{OUTPUT_NAME} is not 16 KB compatible: {aligns}")
    record = {
        "schema": 1,
        "built_at_utc": datetime.now(timezone.utc).isoformat(),
        "abi": ABI,
        "min_api": 26,
        "elf_max_page_size": "0x4000",
        "component": "vanilla-tweaks",
        "version": "1.6.0",
        "upstream": "https://github.com/brndd/vanilla-tweaks",
        "upstream_commit": "fbbe31add71b23602d981d70f1a58520fc349b47",
        "license": "MIT",
        "purpose": "O23 on-device producer of WoW.exe.patched from a pristine managed WoW.exe",
        "artifacts": [{
            "path": target.relative_to(ROOT).as_posix(),
            "size": target.stat().st_size,
            "sha256": sha256(target),
            "needed": needed,
            "load_alignments": aligns,
        }],
    }
    PROVENANCE.parent.mkdir(parents=True, exist_ok=True)
    PROVENANCE.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    LOCKFILE.write_text(
        json.dumps({k: v for k, v in record.items() if k != "built_at_utc"}, indent=2) + "\n",
        encoding="utf-8",
    )
    return record


def main() -> int:
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=("x86_64", "arm64-v8a"), default="x86_64")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    select_abi(args.abi)
    o09.select_abi(args.abi)
    built, llvm = build(args.force)
    print(json.dumps(stage(built, llvm), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
