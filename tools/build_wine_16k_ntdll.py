#!/usr/bin/env python3
"""Build the paired Wine 11.14 x86_64 16 KB dispatcher runtime.

Android's 16 KB kernel cannot give Wine separate 4 KB mappings at 0x7ffe0000
(shared KUSER_SHARED_DATA) and 0x7ffe1000 (a process-local ASLR-relative
syscall dispatcher). The project patch moves the dispatcher to the next 16 KB
boundary, so the Unix ntdll and every x86_64 PE module containing generated
syscall stubs must be rebuilt as one source-matched set.

Outputs:
  native/.build-x86_64/wine-ntdll-16k-multiarch/dlls/ntdll/ntdll.so
  .../dlls/ntdll/x86_64-windows/ntdll.dll
  .../dlls/win32u/x86_64-windows/win32u.dll
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

try:
    from tools import common
except ImportError:  # direct execution: python tools/<script>.py
    import common
PINNED_SOURCE = ROOT / "native" / ".providers-extracted" / "wine-source-1012f3d"
PINNED_COMMIT = "1012f3d99507b80d4869eabf0853567660a7ecbb"
SOURCE_DATE_EPOCH = "1784925752"
PATCH = ROOT / "native" / "wine-spike" / "patches" / "wine-11.14-x86_64-16k.patch"
REVIEWED_PROVENANCE = (
    ROOT / "native" / "wine-spike" / "patches" /
    "wine-11.14-x86_64-16k.provenance.json"
)
BUILD_ROOT = ROOT / "native" / ".build-x86_64"
SOURCE_TREE = BUILD_ROOT / "wine-11.14-source-1012f3d-patched"
BUILD_DIR = BUILD_ROOT / "wine-ntdll-16k-multiarch"
BUILDER_IMAGE = (
    "ghcr.io/termux/package-builder-cgct@"
    "sha256:69ffa5cfe02ca569e7d03d1c99e3c9a0f79390ad6bf11a3629d048c29c6ccb61"
)
OLD_DISPATCH = bytes.fromhex("ff14250010fe7f")
NEW_DISPATCH = bytes.fromhex("ff14250040fe7f")


sha256 = common.sha256_file
def docker_path(path: Path) -> str:
    value = str(path.resolve()).replace("\\", "/")
    if len(value) >= 2 and value[1] == ":":
        value = "//" + value[0].lower() + value[2:]
    return value


def checked_rmtree(path: Path) -> None:
    resolved = path.resolve()
    allowed = BUILD_ROOT.resolve()
    if resolved == allowed or allowed not in resolved.parents:
        raise RuntimeError(f"refusing to remove path outside build root: {resolved}")
    if resolved.exists():
        shutil.rmtree(resolved)


def prepare_source() -> None:
    if not (PINNED_SOURCE / ".git").exists():
        raise FileNotFoundError(
            f"pinned Wine source checkout missing: {PINNED_SOURCE}\n"
            "Run the provider acquisition step before this build."
        )
    commit = subprocess.check_output(
        ["git", "-C", str(PINNED_SOURCE), "rev-parse", "HEAD"], text=True
    ).strip()
    if commit != PINNED_COMMIT:
        raise RuntimeError(f"Wine source pin mismatch: {commit} != {PINNED_COMMIT}")
    if not PATCH.is_file():
        raise FileNotFoundError(PATCH)

    checked_rmtree(SOURCE_TREE)
    SOURCE_TREE.mkdir(parents=True)
    archive = BUILD_ROOT / "wine-11.14-source-1012f3d.tar"
    try:
        subprocess.run(
            ["git", "-c", "core.autocrlf=false", "-C", str(PINNED_SOURCE),
             "archive", "--format=tar",
             "--output", str(archive), PINNED_COMMIT],
            check=True,
        )
        with tarfile.open(archive, "r") as bundle:
            bundle.extractall(SOURCE_TREE, filter="data")
    finally:
        archive.unlink(missing_ok=True)

    # SOURCE_TREE lives under an ignored build directory in the project repo.
    # `git apply` invoked there otherwise finds the parent repository and
    # silently skips every patch target as ignored.  Give the archive its own
    # temporary repository boundary so check/apply operates on the extracted
    # Wine tree itself, then remove that metadata from the build input.
    subprocess.run(["git", "init", "--quiet"], cwd=SOURCE_TREE, check=True)
    try:
        subprocess.run(
            ["git", "apply", "--verbose", "--check", str(PATCH)],
            cwd=SOURCE_TREE,
            check=True,
        )
        subprocess.run(
            ["git", "apply", "--verbose", str(PATCH)],
            cwd=SOURCE_TREE,
            check=True,
        )
    finally:
        checked_rmtree(SOURCE_TREE / ".git")

    if "0x7ffe4000" not in (SOURCE_TREE / "include" / "wine" / "asm.h").read_text(
        encoding="utf-8"
    ):
        raise RuntimeError("Wine 16 KB patch was not materialized in the source tree")


def build() -> None:
    checked_rmtree(BUILD_DIR)
    BUILD_DIR.mkdir(parents=True)
    root_mount = docker_path(ROOT)
    command = " && ".join([
        f"export SOURCE_DATE_EPOCH={SOURCE_DATE_EPOCH}",
        "apt-get update -qq",
        (
            "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "
            "gcc-mingw-w64-i686 gcc-mingw-w64-x86-64"
        ),
        "cd /work/native/.build-x86_64/wine-ntdll-16k-multiarch",
        (
            "LDFLAGS='-Wl,-z,max-page-size=0x4000' "
            "/work/native/.build-x86_64/wine-11.14-source-1012f3d-patched/configure "
            "--prefix=/home/runner/build_wine/wine-11.14-amd64 "
            "--enable-archs=i386,x86_64 --disable-tests --without-x --without-wayland "
            "--without-freetype --without-gstreamer --without-pulse --without-alsa "
            "--without-oss --without-cups --without-dbus --without-gnutls --without-vulkan "
            "--without-opencl --without-usb --without-sane --without-pcap --without-krb5"
        ),
        (
            "make -j8 dlls/ntdll/ntdll.so "
            "dlls/ntdll/x86_64-windows/ntdll.dll "
            "dlls/win32u/x86_64-windows/win32u.dll"
        ),
        (
            "{ gcc --version | head -1; x86_64-w64-mingw32-gcc --version | head -1; "
            "dpkg-query -W gcc-mingw-w64-i686 gcc-mingw-w64-x86-64; } "
            "> BUILD_TOOLCHAIN.txt"
        ),
    ])
    docker = [
        "docker", "run", "--rm", "--user", "0",
        "-v", f"{root_mount}:/work",
        "--workdir", "/work",
        BUILDER_IMAGE, "sh", "-lc", command,
    ]
    print("Building the patched Wine dispatcher pair in the pinned CGCT image...")
    subprocess.run(docker, check=True)


def verify() -> dict[str, object]:
    sys.path.insert(0, str(ROOT / "tools"))
    from stage_wine_runtime import validate_elf_page_compatibility

    ntdll_so = BUILD_DIR / "dlls" / "ntdll" / "ntdll.so"
    pe_modules = {
        "ntdll.dll": BUILD_DIR / "dlls" / "ntdll" / "x86_64-windows" / "ntdll.dll",
        "win32u.dll": BUILD_DIR / "dlls" / "win32u" / "x86_64-windows" / "win32u.dll",
    }
    validate_elf_page_compatibility(ntdll_so.read_bytes(), "patched Wine ntdll.so")

    dispatch_counts: dict[str, int] = {}
    for name, path in pe_modules.items():
        data = path.read_bytes()
        old_count = data.count(OLD_DISPATCH)
        new_count = data.count(NEW_DISPATCH)
        if old_count or not new_count:
            raise RuntimeError(
                f"{name} dispatcher validation failed: old={old_count}, new={new_count}"
            )
        dispatch_counts[name] = new_count

    provider_pe = (
        ROOT / "native" / ".providers-extracted" /
        "wine-kron4ek-11-14-vanilla-wow64" / "wine-11.14-amd64-wow64" /
        "lib" / "wine" / "x86_64-windows"
    )
    old_users = sorted(
        p.name for p in provider_pe.iterdir()
        if p.is_file() and OLD_DISPATCH in p.read_bytes()
    )
    if old_users != ["ntdll.dll", "win32u.dll"]:
        raise RuntimeError(f"unexpected provider modules using old dispatcher: {old_users}")

    outputs = {"ntdll.so": ntdll_so, **pe_modules}
    return {
        "schema": 1,
        "wine_source_commit": PINNED_COMMIT,
        "source_date_epoch": SOURCE_DATE_EPOCH,
        "patch": str(PATCH.relative_to(ROOT)).replace("\\", "/"),
        "patch_sha256": sha256(PATCH),
        "builder_image": BUILDER_IMAGE,
        "dispatcher_address": "0x7ffe4000",
        "dispatcher_stub_counts": dispatch_counts,
        "verified_provider_modules_replaced": old_users,
        "outputs": {
            name: {
                "path": str(path.relative_to(ROOT)).replace("\\", "/"),
                "size": path.stat().st_size,
                "sha256": sha256(path),
            }
            for name, path in outputs.items()
        },
        "toolchain": (BUILD_DIR / "BUILD_TOOLCHAIN.txt").read_text(encoding="utf-8").splitlines(),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    try:
        if not args.verify_only:
            prepare_source()
            build()
        provenance = verify()
        if not REVIEWED_PROVENANCE.is_file():
            raise RuntimeError(f"reviewed Wine build provenance missing: {REVIEWED_PROVENANCE}")
        reviewed = json.loads(REVIEWED_PROVENANCE.read_text(encoding="utf-8"))
        for field in (
            "wine_source_commit", "source_date_epoch", "patch_sha256", "builder_image",
            "dispatcher_address", "dispatcher_stub_counts",
            "verified_provider_modules_replaced",
        ):
            if reviewed.get(field) != provenance.get(field):
                raise RuntimeError(f"reviewed provenance drift for {field}")
        for name, generated in provenance["outputs"].items():
            expected = reviewed.get("outputs", {}).get(name, {})
            for field in ("size", "sha256"):
                if expected.get(field) != generated.get(field):
                    raise RuntimeError(
                        f"reviewed provenance drift for {name}.{field}: "
                        f"{generated.get(field)} != {expected.get(field)}"
                    )
        output = BUILD_DIR / "BUILD_PROVENANCE.json"
        output.write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
        print(f"verified paired 16 KB dispatcher build: {output}")
        return 0
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
