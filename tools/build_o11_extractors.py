#!/usr/bin/env python3
"""Cross-build pinned CMaNGOS DBC/map/vmap/mmap tools for Android x86_64."""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import tarfile
from datetime import datetime, timezone
from pathlib import Path

import build_o09_realm_runtime as o09


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "native" / "cmangos"
BUILD = ROOT / "native" / ".build-o11-x86_64" / "extractors"
PATCHED_SOURCE = ROOT / "native" / ".build-o11-x86_64" / "cmangos-patched"
STAGE = ROOT / "native" / ".build-o11-x86_64" / "extractor-staging" / "jniLibs" / "x86_64"
PROVENANCE = STAGE.parents[1] / "BUILD_PROVENANCE.json"
LOCKFILE = ROOT / "schemas" / "o11-extractor-lockfile.json"
MAX_PAGE = 0x4000
PATCHES = [ROOT / "native" / "patches" / "o11-cmangos-safe-mpq-listfile.patch"]


def run(args: list[object], cwd: Path | None = None) -> None:
    command = [str(value) for value in args]
    print("+", " ".join(command), flush=True)
    subprocess.run(command, check=True, cwd=cwd)


def output(args: list[object]) -> str:
    return subprocess.check_output([str(value) for value in args], text=True)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(8 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def prepare_source(commit: str, force: bool) -> Path:
    """Materialize and patch the pinned source without dirtying the submodule."""
    recipe = hashlib.sha256(
        (commit + "\n" + "\n".join(sha256(path) for path in PATCHES)).encode("ascii")
    ).hexdigest()
    marker = PATCHED_SOURCE / ".pocket-recipe"
    if force or not marker.is_file() or marker.read_text(encoding="ascii").strip() != recipe:
        shutil.rmtree(PATCHED_SOURCE, ignore_errors=True)
        PATCHED_SOURCE.mkdir(parents=True)
        archive = PATCHED_SOURCE.parent / "cmangos-pinned.tar"
        run(["git", "archive", "--format=tar", "-o", archive, commit], cwd=SOURCE)
        with tarfile.open(archive) as stream:
            stream.extractall(PATCHED_SOURCE, filter="data")
        archive.unlink()
        for patch in PATCHES:
            prefix = PATCHED_SOURCE.relative_to(ROOT).as_posix()
            run(["git", "apply", "--unsafe-paths", f"--directory={prefix}", patch], cwd=ROOT)
        marker.write_text(recipe + "\n", encoding="ascii")
    return PATCHED_SOURCE


def configure(force: bool = False) -> tuple[Path, Path]:
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=SOURCE, text=True).strip()
    if actual != o09.CMANGOS_COMMIT:
        raise RuntimeError(f"CMaNGOS pin mismatch: {actual}")
    if subprocess.run(["git", "diff", "--quiet"], cwd=SOURCE).returncode != 0:
        raise RuntimeError("CMaNGOS submodule is dirty; O11 patches must live in native/patches")
    source = prepare_source(actual, force)
    ndk, cmake, ninja, llvm = o09.tools()
    deps = ROOT / "native" / ".deps" / "prefix-x86_64"
    connector_root = ROOT / "native" / ".build-o09-x86_64"
    connector_source = connector_root / "sources" / "mariadb-connector-c"
    connector_build = connector_root / "mariadb-connector"
    connector = connector_build / "libmariadb" / "libmariadbclient.a"
    if not connector.is_file():
        raise RuntimeError("O09 Connector/C build is missing; run tools/build_o09_realm_runtime.py")
    if force:
        shutil.rmtree(BUILD, ignore_errors=True)
    BUILD.mkdir(parents=True, exist_ok=True)
    toolchain = ndk / "build" / "cmake" / "android.toolchain.cmake"
    boost_cmake = deps / "lib" / "cmake"
    boost_components = (
        "headers", "atomic", "filesystem", "program_options", "regex",
        "serialization", "system", "thread", "wserialization",
    )
    boost_component_dirs = [
        f"-Dboost_{name}_DIR={boost_cmake / f'boost_{name}-1.86.0'}"
        for name in boost_components
    ]
    run([
        cmake, "-S", source, "-B", BUILD, "-G", "Ninja", f"-DCMAKE_MAKE_PROGRAM={ninja}",
        f"-DCMAKE_TOOLCHAIN_FILE={toolchain}", "-DANDROID_ABI=x86_64",
        "-DANDROID_PLATFORM=android-26", "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_POLICY_VERSION_MINIMUM=3.5", "-DBUILD_GAME_SERVER=OFF",
        "-DBUILD_LOGIN_SERVER=OFF", "-DBUILD_SCRIPTDEV=OFF", "-DBUILD_EXTRACTORS=ON",
        "-DBUILD_PLAYERBOTS=OFF", "-DBUILD_AHBOT=OFF", "-DBUILD_DEPRECATED_PLAYERBOT=OFF",
        "-DDO_MYSQL=ON", "-DDO_SQLITE=OFF", f"-DBOOST_ROOT={deps}",
        f"-DBoost_DIR={deps / 'lib' / 'cmake' / 'Boost-1.86.0'}",
        f"-DCMAKE_PREFIX_PATH={deps}", *boost_component_dirs, "-DBoost_USE_STATIC_LIBS=ON",
        "-DBoost_USE_STATIC_RUNTIME=ON", f"-DOPENSSL_ROOT_DIR={deps}",
        f"-DOPENSSL_INCLUDE_DIR={deps / 'include'}", f"-DOPENSSL_SSL_LIBRARY={deps / 'lib' / 'libssl.a'}",
        f"-DOPENSSL_CRYPTO_LIBRARY={deps / 'lib' / 'libcrypto.a'}",
        f"-DMYSQL_INCLUDE_DIR={connector_source / 'include'}", f"-DMYSQL_LIBRARY={connector}",
        f"-DMYSQL_EXTRA_LIBRARIES={ndk / 'toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/x86_64-linux-android/26/libz.so'}",
        f"-DCMAKE_CXX_FLAGS=-I{connector_build / 'include'}", "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
        "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
    ])
    run([cmake, "--build", BUILD, "--target", "ad", "vmap_extractor", "vmap_assembler", "MoveMapGen",
         "-j", str(os.cpu_count() or 4)])
    return llvm, cmake


def stage(llvm: Path) -> dict:
    STAGE.mkdir(parents=True, exist_ok=True)
    artifacts = {
        "libpocket_ad.so": BUILD / "contrib" / "extractor" / "ad",
        "libpocket_vmap_extractor.so": BUILD / "contrib" / "vmap_extractor" / "vmapextract" / "vmap_extractor",
        "libpocket_vmap_assembler.so": BUILD / "contrib" / "vmap_assembler" / "vmap_assembler",
        "libpocket_movemapgen.so": BUILD / "contrib" / "mmap" / "MoveMapGen",
    }
    missing = [str(path) for path in artifacts.values() if not path.is_file()]
    if missing:
        raise RuntimeError(f"extractor outputs missing: {missing}")
    strip, readelf = llvm / "llvm-strip.exe", llvm / "llvm-readelf.exe"
    allowed = {"libc++_shared.so", "libc.so", "libdl.so", "libm.so", "libz.so"}
    records = []
    for target_name, source in artifacts.items():
        target = STAGE / target_name
        shutil.copy2(source, target)
        run([strip, "--strip-unneeded", target])
        dynamic = output([readelf, "-dW", target])
        needed = sorted(line.split("[")[1].split("]")[0] for line in dynamic.splitlines() if "(NEEDED)" in line)
        unexpected = set(needed) - allowed
        if unexpected:
            raise RuntimeError(f"unexpected DT_NEEDED for {target_name}: {sorted(unexpected)}")
        program = output([readelf, "-lW", target])
        aligns = [int(line.split()[-1], 16) for line in program.splitlines() if line.lstrip().startswith("LOAD ")]
        if not aligns or any(value < MAX_PAGE for value in aligns):
            raise RuntimeError(f"{target_name} is not 16 KB compatible: {aligns}")
        records.append({"path": target.relative_to(ROOT).as_posix(), "size": target.stat().st_size,
                        "sha256": sha256(target), "needed": needed, "load_alignments": aligns})
    record = {
        "schema": 1, "built_at_utc": datetime.now(timezone.utc).isoformat(), "abi": "x86_64",
        "min_api": 26, "elf_max_page_size": "0x4000", "cmangos_commit": o09.CMANGOS_COMMIT,
        "purpose": "O11 finite on-device DBC/map/vmap/mmap preparation",
        "source_patches": [{"path": path.relative_to(ROOT).as_posix(), "sha256": sha256(path)} for path in PATCHES],
        "artifacts": records,
    }
    PROVENANCE.parent.mkdir(parents=True, exist_ok=True)
    PROVENANCE.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    LOCKFILE.write_text(json.dumps({k: v for k, v in record.items() if k != "built_at_utc"}, indent=2) + "\n",
                        encoding="utf-8")
    return record


def main() -> int:
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    llvm, _ = configure(args.force)
    print(json.dumps(stage(llvm), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
