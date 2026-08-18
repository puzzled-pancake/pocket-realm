#!/usr/bin/env python3
"""Stage the pinned MariaDB x86_64 ELF/data subset for the APK variant."""
from __future__ import annotations

import hashlib
import json
import sys
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
from collections import deque
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

try:
    from tools import common
except ImportError:  # direct execution: python tools/<script>.py
    import common
BUILD = ROOT / "native" / ".build-x86_64" / "mariadb"
STAGE = ROOT / "native" / ".build-x86_64" / "mariadb-staging"
JNI = STAGE / "jniLibs" / "x86_64"
PROVIDER = STAGE / "assets" / "database" / "provider"
LOCKFILE = ROOT / "schemas" / "mariadb-runtime-lockfile.json"
REPO_ARCHIVES = ROOT / "native" / ".glibc-build" / "glibc-packages" / "output"
RUNTIME_ARCHIVES = ROOT / "runtime" / "glibc-rootfs-x86_64"
WINE_JNI = ROOT / "native" / ".build-x86_64" / "wine-staging" / "jniLibs"
PACKAGE_CACHE = BUILD / "package-cache"
LIBSTDCXX = BUILD / "libstdc++.so.6.0.35"
METADATA_URL = "https://sync.termux-pacman.dev/gpkg/x86_64/gpkg.json"
PACKAGE_URL = "https://sync.termux-pacman.dev/gpkg/x86_64/{filename}"
INITIAL_PACKAGES = {
    "openssl-glibc", "libxcrypt-glibc", "pcre2-glibc", "zlib-glibc",
    "zstd-glibc", "ncurses-glibc", "libbz2-glibc", "libxml2-glibc",
    "liblz4-glibc", "glibc", "gcc-libs-glibc",
}
# The APK uses MariaDB's built-in InnoDB/Aria/MyISAM engines only. Do not package
# optional audit, PAM, RocksDB, Spider, or replication plugins merely because
# the upstream source recipe compiled them.
REQUIRED_PLUGINS: set[str] = set()


digest = common.sha256_file
def sdk_tool(name: str) -> Path:
    sdk = common.resolve_android_sdk()
    ndks = sorted((sdk / "ndk").glob("*"))
    if not ndks:
        raise RuntimeError("Android NDK unavailable")
    tool = ndks[-1] / "toolchains/llvm/prebuilt/windows-x86_64/bin" / f"{name}.exe"
    if not tool.is_file():
        raise RuntimeError(f"missing NDK tool: {tool}")
    return tool


READELF = sdk_tool("llvm-readelf")


def package_archive(metadata: dict, package: str) -> Path:
    record = metadata[package]
    filename = record["FILENAME"]
    expected = record["SHA256SUM"]
    if package == "gcc-libs-glibc":
        candidates = sorted(RUNTIME_ARCHIVES.glob("gcc-libs-glibc-16.1.0-*-x86_64.pkg.tar.*"))
        if len(candidates) != 1:
            raise RuntimeError(f"expected pinned source-built GCC 16.1 runtime, found {candidates}")
        # Mirror metadata currently advertises GCC 14.2, which is ABI-skewed
        # from the pinned GCC 16.1 compiler. The runtime build already source-built libgcc_s
        # 16.1 from the recorded GNU tarball; pair it with CGCT's hash-pinned
        # 16.1 libstdc++ instead of accepting the live mirror version.
        return candidates[0]
    if package == "glibc":
        candidates = sorted(RUNTIME_ARCHIVES.glob("glibc-2.43-1-x86_64.pkg.tar.*"))
        if len(candidates) != 1:
            raise RuntimeError(f"expected source-built loader/libc archive, found {candidates}")
        return candidates[0]
    for root in (REPO_ARCHIVES, RUNTIME_ARCHIVES, PACKAGE_CACHE):
        candidate = root / filename
        if candidate.is_file() and digest(candidate) == expected:
            return candidate
    PACKAGE_CACHE.mkdir(parents=True, exist_ok=True)
    destination = PACKAGE_CACHE / filename
    print(f"download {package}: {filename}")
    urllib.request.urlretrieve(PACKAGE_URL.format(filename=filename), destination)
    if digest(destination) != expected:
        destination.unlink(missing_ok=True)
        raise RuntimeError(f"package hash mismatch: {filename}")
    return destination


def load_metadata(refresh: bool = False) -> tuple[dict, str]:
    """Fetch the termux gpkg index, PINNED to the committed lockfile.

    The index is live upstream metadata: without a pin, any full staging run
    silently moves package versions (observed twice during the 2026-08
    Phase 4 window). Default behavior: refuse an index whose hash differs
    from schemas/mariadb-runtime-lockfile.json's metadata_sha256; --refresh
    explicitly accepts the new index and rewrites the lockfile for review.
    """
    BUILD.mkdir(parents=True, exist_ok=True)
    path = BUILD / "gpkg.json"
    urllib.request.urlretrieve(METADATA_URL, path)
    metadata_hash = digest(path)
    pinned = None
    if LOCKFILE.is_file():
        try:
            pinned = json.loads(LOCKFILE.read_text(encoding="utf-8")).get("metadata_sha256")
        except json.JSONDecodeError:
            pinned = None
    if pinned and metadata_hash != pinned and not refresh:
        raise RuntimeError(
            "termux gpkg index moved (metadata sha256 "
            f"{metadata_hash[:16]}... != pinned {pinned[:16]}...). Package versions "
            "would silently drift. Review the change and re-run with --refresh "
            "to accept the new index and rewrite the lockfile."
        )
    return json.loads(path.read_text(encoding="utf-8")), metadata_hash


def extract(archive: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as bundle:
        for member in bundle:
            path = Path(member.name)
            if path.is_absolute() or ".." in path.parts:
                raise RuntimeError(f"unsafe package member: {member.name}")
            target = destination / path
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                source = bundle.extractfile(member)
                if source is None:
                    raise RuntimeError(f"cannot read package member: {member.name}")
                with source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
                target.chmod(member.mode & 0o777)
            # Package symlinks encode Termux's absolute installation prefix.
            # The APK runtime creates a separate, hash-verified SONAME tree, so
            # retaining those host-extraction links is unnecessary and unsafe.


def is_elf(path: Path) -> bool:
    return path.is_file() and path.open("rb").read(4) == b"\x7fELF"


def needed(path: Path) -> list[str]:
    output = subprocess.check_output([str(READELF), "-dW", str(path)], text=True, errors="replace")
    return re.findall(r"Shared library: \[([^]]+)]", output)


def elf_names(path: Path) -> set[str]:
    output = subprocess.check_output([str(READELF), "-dW", str(path)], text=True, errors="replace")
    names = {path.name}
    names.update(re.findall(r"Library soname: \[([^]]+)]", output))
    return names


def max_load_align(path: Path) -> int:
    output = subprocess.check_output([str(READELF), "-lW", str(path)], text=True, errors="replace")
    values = []
    for line in output.splitlines():
        if line.lstrip().startswith("LOAD "):
            values.append(int(line.split()[-1], 16))
    return max(values, default=0)


def locate_mariadb_archive() -> Path:
    candidates = sorted(
        path for path in BUILD.glob("mariadb-glibc-*-x86_64.pkg.tar.*")
        if not path.name.startswith("mariadb-glibc-static-")
    )
    if len(candidates) != 1:
        raise RuntimeError(f"run tools/build_mariadb_android.py first; found {candidates}")
    return candidates[0]


def all_runtime_packages(metadata: dict) -> set[str]:
    # Download the declared library graph but intentionally omit perl: the APK
    # performs mariadb-install-db's bootstrap directly and distributes no shell
    # or Perl execution surface.
    selected: set[str] = set()
    queue = deque(sorted(INITIAL_PACKAGES))
    while queue:
        package = queue.popleft()
        if package in selected or package not in metadata:
            continue
        selected.add(package)
        for dependency in metadata[package].get("DEPENDS", []):
            name = re.split(r"[<>=]", dependency, maxsplit=1)[0]
            if name.endswith("-glibc") and name != "perl-glibc":
                queue.append(name)
    return selected


def unique_candidate(candidates: dict[str, list[tuple[Path, str]]], soname: str) -> tuple[Path, str]:
    matches = candidates.get(soname, [])
    if not matches:
        raise RuntimeError(f"unresolved DT_NEEDED: {soname}")
    hashes = {digest(path.resolve()) for path, _ in matches}
    if len(hashes) != 1:
        rendered = [f"{package}:{path}" for path, package in matches]
        raise RuntimeError(f"ambiguous DT_NEEDED {soname}: {rendered}")
    return matches[0][0].resolve(), matches[0][1]


def copy_tree(source: Path, destination: Path) -> None:
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(source, destination, symlinks=False)


def build_bootstrap(share: Path) -> tuple[str, list[str]]:
    names = [
        "mariadb_system_tables.sql",
        "mariadb_performance_tables.sql",
        "mariadb_system_tables_data.sql",
        "fill_help_tables.sql",
        "maria_add_gis_sp_bootstrap.sql",
        "mariadb_sys_schema.sql",
    ]
    parts = []
    for name in names:
        path = share / name
        if not path.is_file():
            raise RuntimeError(f"MariaDB bootstrap input missing: {name}")
        parts.append(path.read_text(encoding="utf-8", errors="strict"))
    # Exact `cat_sql` prefix/order used by MariaDB 11.5.2's
    # mysql_install_db.sh for --auth-root-authentication-method=normal. The
    # disposable test database is intentionally omitted.
    body = "create database if not exists mysql;\nuse mysql;\nSET @auth_root_socket=NULL;\n" + "\n".join(parts)
    tokens = set(re.findall(r"@([A-Za-z0-9_]+)@", body))
    # Help text legitimately documents MariaDB's filename encodings such as
    # @002db@ and @h0@. Build-system placeholders are uppercase/underscore or
    # one of CMake's conventional lowercase installation variables.
    unresolved = sorted(
        f"@{token}@" for token in tokens
        if "_" in token or (any(ch.isalpha() for ch in token) and token.upper() == token)
        or token in {"prefix", "bindir", "libdir", "sysconfdir"}
    )
    if unresolved:
        raise RuntimeError(f"unresolved MariaDB bootstrap placeholders: {unresolved}")
    return body + "\n", names


def main() -> int:
    refresh = "--refresh" in sys.argv
    metadata, metadata_hash = load_metadata(refresh=refresh)
    mariadb_archive = locate_mariadb_archive()
    if not LIBSTDCXX.is_file():
        raise RuntimeError("run tools/build_mariadb_android.py to extract pinned CGCT libstdc++")
    packages = all_runtime_packages(metadata)
    archives = {package: package_archive(metadata, package) for package in sorted(packages)}
    archives["mariadb-glibc"] = mariadb_archive

    with tempfile.TemporaryDirectory(prefix="pocket-mariadb-") as temporary:
        root = Path(temporary)
        extracted = {}
        for package, archive in archives.items():
            destination = root / package
            extract(archive, destination)
            extracted[package] = destination
        toolchain_runtime = root / "cgct-libstdc++"
        toolchain_runtime.mkdir()
        shutil.copy2(LIBSTDCXX, toolchain_runtime / "libstdc++.so.6")
        extracted["cgct-libstdc++"] = toolchain_runtime

        candidates: dict[str, list[tuple[Path, str]]] = {}
        for package, directory in extracted.items():
            for path in directory.rglob("*"):
                if is_elf(path):
                    for name in elf_names(path):
                        candidates.setdefault(name, []).append((path, package))

        mariadb_root = extracted["mariadb-glibc"]
        servers = [p for p in mariadb_root.rglob("mariadbd") if is_elf(p)]
        clients = [p for p in mariadb_root.rglob("mariadb") if is_elf(p)]
        if len(servers) != 1 or len(clients) != 1:
            raise RuntimeError(f"unexpected MariaDB executables: server={servers} client={clients}")
        available_plugins = [
            p for p in mariadb_root.rglob("*.so") if "plugin" in p.parts and is_elf(p)
        ]
        available_names = {path.name for path in available_plugins}
        missing_plugins = REQUIRED_PLUGINS - available_names
        if missing_plugins:
            raise RuntimeError(f"required MariaDB plugins absent: {sorted(missing_plugins)}")
        plugins = [path for path in available_plugins if path.name in REQUIRED_PLUGINS]

        queue = deque(servers + clients + plugins)
        closure: dict[Path, str] = {servers[0]: "mariadb-glibc", clients[0]: "mariadb-glibc"}
        logical_to_path: dict[str, tuple[Path, str]] = {}
        while queue:
            elf = queue.popleft().resolve()
            for soname in needed(elf):
                dependency, package = unique_candidate(candidates, soname)
                logical_to_path[soname] = (dependency, package)
                if dependency not in closure:
                    closure[dependency] = package
                    queue.append(dependency)

        for generated in (JNI, PROVIDER):
            if generated.exists():
                shutil.rmtree(generated)
        (STAGE / "BUILD_PROVENANCE.json").unlink(missing_ok=True)
        JNI.mkdir(parents=True)
        PROVIDER.mkdir(parents=True)
        staged_by_hash: dict[str, str] = {}

        def stage_elf(source: Path, fixed_name: str | None = None) -> tuple[str, str]:
            file_hash = digest(source)
            dynamic = subprocess.check_output(
                [str(READELF), "-dW", str(source)], text=True, errors="replace"
            )
            if "/data/data/com.termux/cgct" in dynamic:
                raise RuntimeError(f"build-tree RPATH leaked into installed ELF: {source}")
            if fixed_name:
                apk_name = fixed_name
            else:
                clean = re.sub(r"[^A-Za-z0-9]+", "_", source.name).strip("_")
                apk_name = staged_by_hash.get(file_hash, f"libdb_{clean}_{file_hash[:10]}.so")
            target = JNI / apk_name
            if not target.exists():
                shutil.copy2(source, target)
            staged_by_hash[file_hash] = apk_name
            alignment = max_load_align(source)
            if alignment > 0x4000:
                raise RuntimeError(f"ELF not 16K compatible ({alignment:#x}): {source}")
            return apk_name, file_hash

        server_name, server_hash = stage_elf(servers[0], "libpocket_mariadbd.so")
        client_name, client_hash = stage_elf(clients[0], "libpocket_mariadb_client.so")
        links = []
        used_packages = {"mariadb-glibc"}
        for logical, (source, package) in sorted(logical_to_path.items()):
            if logical in {"ld-linux-x86-64.so.2", "libc.so.6"}:
                from stage_wine_runtime import (
                    patch_libc_legacy_stat_for_android,
                    patch_rtld_access_for_android,
                )
                qualified = root / f"qualified-{logical.replace('/', '_')}"
                shutil.copy2(source, qualified)
                if logical == "ld-linux-x86-64.so.2":
                    patch_rtld_access_for_android(qualified)
                    paired = WINE_JNI / "libld_linux_x86_64.so"
                else:
                    patch_libc_legacy_stat_for_android(qualified)
                    paired = WINE_JNI / "liblibc.so.6.so"
                if not paired.is_file() or digest(paired) != digest(qualified):
                    raise RuntimeError(f"staged {logical} does not match the qualified APK artifact")
                apk_name, file_hash = paired.name, digest(paired)
            else:
                apk_name, file_hash = stage_elf(source)
            used_packages.add(package)
            links.append({"logical": logical, "apk_name": apk_name, "sha256": file_hash})
        plugin_records = []
        for plugin in sorted(plugins):
            apk_name, file_hash = stage_elf(plugin.resolve())
            plugin_records.append({"logical": plugin.name, "apk_name": apk_name, "sha256": file_hash})

        share_candidates = [p for p in mariadb_root.rglob("share/mysql") if p.is_dir()]
        if len(share_candidates) != 1:
            raise RuntimeError(f"unexpected share/mysql roots: {share_candidates}")
        copy_tree(share_candidates[0], PROVIDER / "share/mysql")
        bootstrap, bootstrap_inputs = build_bootstrap(PROVIDER / "share/mysql")
        (PROVIDER / "bootstrap.sql").write_text(bootstrap, encoding="utf-8", newline="\n")

        runtime_manifest = {
            "schema": 1,
            "provider": "mariadb-11.5.2-termux-glibc",
            "abi": "x86_64",
            "executables": {
                "mariadbd": {"apk_name": server_name, "sha256": server_hash},
                "mariadb": {"apk_name": client_name, "sha256": client_hash},
            },
            "links": links,
            "plugins": plugin_records,
            "bootstrap_inputs": bootstrap_inputs,
            "bootstrap_sha256": digest(PROVIDER / "bootstrap.sql"),
        }
        (PROVIDER / "runtime-manifest.json").write_text(
            json.dumps(runtime_manifest, indent=2) + "\n", encoding="utf-8"
        )
        lock = {
            "schema": 1,
            "metadata_url": METADATA_URL,
            "metadata_sha256": metadata_hash,
            "mariadb_archive": {
                "path": mariadb_archive.relative_to(ROOT).as_posix(),
                "sha256": digest(mariadb_archive),
            },
            "toolchain_runtime": {
                "filename": LIBSTDCXX.name,
                "sha256": digest(LIBSTDCXX),
                "soname": "libstdc++.so.6",
                "source": "pinned CGCT builder image; GCC 16.1.0 source correspondence",
                "license": "GPL-3.0-or-later WITH GCC-exception-3.1",
                "used_by_elf_closure": "cgct-libstdc++" in used_packages,
            },
            "packages": {
                package: {
                    "filename": archives[package].name,
                    "sha256": digest(archives[package]),
                    "license": metadata[package].get("LICENSE", "UNKNOWN"),
                    "used_by_elf_closure": package in used_packages,
                }
                for package in sorted(packages)
            },
            "runtime": runtime_manifest,
        }
        LOCKFILE.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
        (STAGE / "BUILD_PROVENANCE.json").write_text(
            json.dumps(lock, indent=2) + "\n", encoding="utf-8"
        )
        print(f"staged {len(list(JNI.iterdir()))} immutable ELFs")
        print(f"DT_NEEDED links: {len(links)}; plugins: {len(plugin_records)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
