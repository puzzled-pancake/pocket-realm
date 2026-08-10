#!/usr/bin/env python3
"""Stage a verified ARM64/Bionic MariaDB provider for the live-device lane.

The provider is intentionally a package conversion rather than a glibc
runtime: the official Termux aarch64 MariaDB package and its pinned private
library closure are copied into APK-native artifacts, while Android's libc,
libm, and libdl remain platform dependencies.  No Termux prefix is required
at runtime; DatabaseEngine supplies the private SONAME directory via
LD_LIBRARY_PATH and creates the provider data tree in app storage.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
from collections import deque
from io import BytesIO
from pathlib import Path
from urllib.request import urlopen, Request

ROOT = Path(__file__).resolve().parents[1]
ABI = "arm64-v8a"
BUILD = ROOT / "native" / ".build-arm64" / "mariadb-arm"
STAGE = ROOT / "native" / ".build-arm64" / "mariadb-staging"
JNI = STAGE / "jniLibs" / ABI
ASSETS = STAGE / "assets" / "database" / "provider"
LOCKFILE = ROOT / "schemas" / "mariadb-runtime-lockfile-arm64-v8a.json"
INDEX_URL = "https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz"
BASE_URL = "https://packages.termux.dev/apt/termux-main/"
MARIADB_VERSION = "2:12.3.2"
MARIADB_PACKAGE = "pool/main/m/mariadb/mariadb_2:12.3.2_aarch64.deb"
MARIADB_SHA256 = "ffda12fad7d55748e4f8d7e40fe37fd2de67a79e9cb00a5fd7adf3a4948a3fdc"

# This is the complete dependency closure declared by the pinned MariaDB
# package at the recorded index.  Keeping it explicit makes a mirror or
# dependency drift fail closed rather than silently broadening the APK.
PACKAGE_PINS: dict[str, tuple[str, str, str]] = {
    "ca-certificates": (
        "pool/main/c/ca-certificates/ca-certificates_1:2026.07.16_all.deb",
        "93dc49a8009012c29510081b8f07f30c57af9b10b1dae4f541231d8ee785b37a",
        "1:2026.07.16",
    ),
    "libandroid-glob": (
        "pool/main/liba/libandroid-glob/libandroid-glob_0.6-3_aarch64.deb",
        "2276ae8adedf0db76c2f4ffc94cc4cceb2f4f5d78e021b54e2e046d1233e7826",
        "0.6-3",
    ),
    "libandroid-support": (
        "pool/main/liba/libandroid-support/libandroid-support_29-1_aarch64.deb",
        "f2f145d6135ad4843ac9670153be3e3944dc1e6f1736d46d2306c28f2b86f517",
        "29-1",
    ),
    "libbz2": (
        "pool/main/libb/libbz2/libbz2_1.0.8-8_aarch64.deb",
        "4335d7f060650b0aabef545d1334c2f9f280223d5962e13c24a00ec934b794ba",
        "1.0.8-8",
    ),
    "libc++": (
        "pool/main/libc/libc++/libc++_29_aarch64.deb",
        "bb9f12113c137aa0e8513bb51cc49fe77a5ce3ca39ab9e92c57d228ecdf00222",
        "29",
    ),
    "libcrypt": (
        "pool/main/libc/libcrypt/libcrypt_0.2-6_aarch64.deb",
        "6c283eed576b98cc3568f99638156f8588f77d979579d03bef8683d6eb8601e1",
        "0.2-6",
    ),
    "libedit": (
        "pool/main/libe/libedit/libedit_20260512-3.1-0_aarch64.deb",
        "728da8f45e9c0027f0c5844346dd8cea153a43394bceee36a257972acf0ed440",
        "20260512-3.1-0",
    ),
    "libiconv": (
        "pool/main/libi/libiconv/libiconv_1.18-1_aarch64.deb",
        "b19e6f348034bb48d2a5590b5cb242769f682c476717374d134d004cc663dc84",
        "1.18-1",
    ),
    "libicu": (
        "pool/main/libi/libicu/libicu_78.3_aarch64.deb",
        "f536403f65a08fe0df6e7304184e902d54def77d5c3bd5edfd9109d57601d276",
        "78.3",
    ),
    "liblz4": (
        "pool/main/libl/liblz4/liblz4_1.10.0-1_aarch64.deb",
        "09b9449418d5c2dc4f5c1c140ba8138d56be3e9ae5fd3be3318825ec9f8a0499",
        "1.10.0-1",
    ),
    "liblzma": (
        "pool/main/libl/liblzma/liblzma_5.8.3_aarch64.deb",
        "594925a313879f590fbd24050305551a78eadd9a9319f6e612389b1a521113c6",
        "5.8.3",
    ),
    "libxml2": (
        "pool/main/libx/libxml2/libxml2_2.15.3-2_aarch64.deb",
        "59fbced0c60a7df9ff84faf20d248f563da5ae45a6783ed683faf33e9010fb24",
        "2.15.3-2",
    ),
    "ncurses": (
        "pool/main/n/ncurses/ncurses_6.6.20260307+really6.5.20250830_aarch64.deb",
        "f44bbfdc3d42ec0217bffa978309390e59cea5a48a9a83226d4a496c42ad0b99",
        "6.6.20260307+really6.5.20250830",
    ),
    "openssl": (
        "pool/main/o/openssl/openssl_1:3.6.3_aarch64.deb",
        "86760e9ce736f463236f2c15b1eb3a3fdcfc5778d0fd7077a917448dcc90f3aa",
        "1:3.6.3",
    ),
    "pcre2": (
        "pool/main/p/pcre2/pcre2_10.47_aarch64.deb",
        "51f915d22de639bfca6ec029ae613987bbe3bc73626eede13319fd2e95f50b63",
        "10.47",
    ),
    "zlib": (
        "pool/main/z/zlib/zlib_1.3.2_aarch64.deb",
        "75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9",
        "1.3.2",
    ),
    "zstd": (
        "pool/main/z/zstd/zstd_1.5.7-1_aarch64.deb",
        "e1b4a5113648da8de189620ba1fce74c48b2d0833d9043391b9a1c91fb606fd3",
        "1.5.7-1",
    ),
}

SYSTEM_SONAMES = {
    "libc.so", "libc.so.6", "libm.so", "libm.so.6", "libdl.so", "libdl.so.2",
    "liblog.so", "libandroid.so", "libjnigraphics.so", "libstdc++.so", "libgcc_s.so.1",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def readelf(name: str) -> Path:
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "")
    candidates = sorted(sdk.glob("ndk*/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"))
    candidates += sorted((sdk / "ndk-link/toolchains/llvm/prebuilt/windows-x86_64/bin").glob("llvm-readelf.exe"))
    if not candidates:
        raise RuntimeError("Android NDK llvm-readelf unavailable")
    return candidates[-1]


READELF = readelf("llvm-readelf")


def download(url: str, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_file():
        return
    request = Request(url, headers={"User-Agent": "PocketRealm ARM provider builder"})
    with urlopen(request, timeout=120) as source, target.open("wb") as destination:
        shutil.copyfileobj(source, destination)


def safe_extract_deb(deb: Path, destination: Path) -> None:
    raw = deb.read_bytes()
    if not raw.startswith(b"!<arch>\n"):
        raise RuntimeError(f"not an ar archive: {deb}")
    offset = 8
    while offset < len(raw):
        header = raw[offset:offset + 60]
        if len(header) != 60 or header[58:60] != b"`\n":
            raise RuntimeError(f"invalid ar member in {deb}")
        offset += 60
        name = header[:16].decode("ascii", errors="strict").strip().rstrip("/")
        size = int(header[48:58].decode("ascii", errors="strict").strip())
        payload = raw[offset:offset + size]
        offset += size + size % 2
        if name.startswith("data.tar"):
            destination.mkdir(parents=True, exist_ok=True)
            with tarfile.open(fileobj=BytesIO(payload), mode="r:*") as archive:
                archive.extractall(destination, filter="data")
            return
    raise RuntimeError(f"data member missing in {deb}")


def needed(path: Path) -> list[str]:
    output = subprocess.check_output([str(READELF), "-dW", str(path)], text=True, errors="replace")
    return re.findall(r"Shared library: \[([^]]+)]", output)


def sonames(path: Path) -> set[str]:
    output = subprocess.check_output([str(READELF), "-dW", str(path)], text=True, errors="replace")
    names = {path.name}
    names.update(re.findall(r"Library soname: \[([^]]+)]", output))
    return names


def max_load_align(path: Path) -> int:
    output = subprocess.check_output([str(READELF), "-lW", str(path)], text=True, errors="replace")
    values = [int(line.split()[-1], 16) for line in output.splitlines() if line.lstrip().startswith("LOAD ")]
    return max(values, default=0)


def is_elf(path: Path) -> bool:
    try:
        return path.is_file() and path.open("rb").read(4) == b"\x7fELF"
    except OSError:
        return False


def find_usr(root: Path) -> Path:
    candidates = list(root.glob("data/data/com.termux/files/usr"))
    if len(candidates) != 1:
        raise RuntimeError(f"Termux prefix missing in {root}")
    return candidates[0]


def build_bootstrap(share: Path) -> tuple[str, list[str]]:
    names = [
        "mariadb_system_tables.sql", "mariadb_performance_tables.sql",
        "mariadb_system_tables_data.sql", "fill_help_tables.sql",
        "maria_add_gis_sp_bootstrap.sql", "mariadb_sys_schema.sql",
    ]
    parts = []
    for name in names:
        path = share / name
        if not path.is_file():
            raise RuntimeError(f"MariaDB bootstrap input missing: {name}")
        parts.append(path.read_text(encoding="utf-8", errors="strict"))
    body = "create database if not exists mysql;\nuse mysql;\nSET @auth_root_socket=NULL;\n" + "\n".join(parts)
    tokens = set(re.findall(r"@([A-Za-z0-9_]+)@", body))
    unresolved = sorted(
        f"@{token}@" for token in tokens
        if "_" in token or (any(ch.isalpha() for ch in token) and token.upper() == token)
        or token in {"prefix", "bindir", "libdir", "sysconfdir"}
    )
    if unresolved:
        raise RuntimeError(f"unresolved bootstrap placeholders: {unresolved}")
    return body.rstrip() + "\n", names


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="use only existing package/index cache")
    args = parser.parse_args()
    BUILD.mkdir(parents=True, exist_ok=True)
    package_cache = BUILD / "packages"
    index_path = BUILD / "Packages.gz"
    if not index_path.is_file() and not args.offline:
        download(INDEX_URL, index_path)
    if not index_path.is_file():
        raise RuntimeError(f"missing package index: {index_path}")
    index_sha = sha256(index_path)

    mariadb_archive = BUILD / "mariadb_2-12.3.2_aarch64.deb"
    if not mariadb_archive.is_file() and not args.offline:
        download(BASE_URL + MARIADB_PACKAGE, mariadb_archive)
    if not mariadb_archive.is_file() or sha256(mariadb_archive) != MARIADB_SHA256:
        raise RuntimeError("MariaDB package missing or hash mismatch")
    archives: dict[str, Path] = {"mariadb": mariadb_archive}
    for package, (relative, expected, _version) in PACKAGE_PINS.items():
        target = package_cache / Path(relative).name.replace(":", "-")
        if not target.is_file() and not args.offline:
            download(BASE_URL + relative, target)
        if not target.is_file() or sha256(target) != expected:
            raise RuntimeError(f"package missing or hash mismatch: {package}")
        archives[package] = target

    with tempfile.TemporaryDirectory(prefix="pocket-mariadb-arm-") as temporary:
        root = Path(temporary)
        package_roots: dict[str, Path] = {}
        for package, archive in archives.items():
            destination = root / package
            safe_extract_deb(archive, destination)
            package_roots[package] = destination
        mariadb_usr = find_usr(package_roots["mariadb"])

        candidates: dict[str, list[tuple[Path, str]]] = {}
        for package, package_root in package_roots.items():
            for path in package_root.rglob("*"):
                if is_elf(path):
                    for name in sonames(path):
                        candidates.setdefault(name, []).append((path, package))

        server = mariadb_usr / "bin/mariadbd"
        client = mariadb_usr / "bin/mariadb"
        if not is_elf(server) or not is_elf(client):
            raise RuntimeError("Termux MariaDB executables missing")
        queue = deque([server, client])
        closure: dict[Path, str] = {server: "mariadb", client: "mariadb"}
        links: dict[str, tuple[Path, str]] = {}
        while queue:
            binary = queue.popleft().resolve()
            for soname in needed(binary):
                if soname in SYSTEM_SONAMES:
                    continue
                matches = candidates.get(soname, [])
                if not matches:
                    raise RuntimeError(f"unresolved ARM Bionic DT_NEEDED {soname} from {binary}")
                hashes = {sha256(path.resolve()) for path, _ in matches}
                if len(hashes) != 1:
                    raise RuntimeError(f"ambiguous ARM Bionic dependency {soname}: {matches}")
                dependency, package = matches[0][0].resolve(), matches[0][1]
                links[soname] = (dependency, package)
                if dependency not in closure:
                    closure[dependency] = package
                    queue.append(dependency)

        if JNI.exists():
            shutil.rmtree(JNI)
        if ASSETS.exists():
            shutil.rmtree(ASSETS)
        STAGE.mkdir(parents=True, exist_ok=True)
        JNI.mkdir(parents=True)
        ASSETS.mkdir(parents=True)
        (ASSETS / "lib").mkdir()
        (ASSETS / "plugin").mkdir()

        def stage_elf(source: Path, apk_name: str) -> dict[str, object]:
            if max_load_align(source) > 0x4000:
                raise RuntimeError(f"ELF exceeds 16K alignment: {source}")
            target = JNI / apk_name
            shutil.copy2(source, target)
            target.chmod(0o755)
            return {"apk_name": apk_name, "sha256": sha256(target), "size": target.stat().st_size}

        executable_records = {
            "mariadbd": stage_elf(server, "libpocket_mariadbd.so"),
            "mariadb": stage_elf(client, "libpocket_mariadb_client.so"),
        }
        used_packages = {"mariadb"}
        link_records = []
        for logical, (source, package) in sorted(links.items()):
            safe = re.sub(r"[^A-Za-z0-9]+", "_", logical).strip("_")
            target_hash = sha256(source)
            apk_name = f"libdb_{safe}_{target_hash[:10]}.so"
            record = stage_elf(source, apk_name)
            record["logical"] = logical
            record["package"] = package
            link_records.append(record)
            used_packages.add(package)

        # Termux installs the server data under share/mariadb; the APK
        # provider deliberately normalizes that distro path to share/mysql so
        # DatabaseEngine has one immutable contract across ABI lanes.
        share = next((candidate for candidate in (mariadb_usr / "share/mysql", mariadb_usr / "share/mariadb") if candidate.is_dir()), None)
        if share is None:
            raise RuntimeError("MariaDB share data directory missing")
        shutil.copytree(share, ASSETS / "share/mysql", symlinks=False)
        bootstrap, bootstrap_inputs = build_bootstrap(ASSETS / "share/mysql")
        (ASSETS / "bootstrap.sql").write_text(bootstrap, encoding="utf-8", newline="\n")

        runtime_manifest = {
            "schema": 1,
            "provider": "mariadb-12.3.2-termux-bionic-arm64",
            "abi": ABI,
            "executables": executable_records,
            "links": link_records,
            "plugins": [],
            "bootstrap_inputs": bootstrap_inputs,
            "bootstrap_sha256": sha256(ASSETS / "bootstrap.sql"),
        }
        (ASSETS / "runtime-manifest.json").write_text(json.dumps(runtime_manifest, indent=2) + "\n", encoding="utf-8")

        packages = {}
        for package, archive in archives.items():
            if package == "mariadb":
                version = MARIADB_VERSION
                expected = MARIADB_SHA256
            else:
                _relative, expected, version = PACKAGE_PINS[package]
            packages[package] = {
                "filename": archive.name, "sha256": expected, "version": version,
                "used_by_elf_closure": package in used_packages,
            }
        provenance = {
            "schema": 1,
            "provider": runtime_manifest["provider"],
            "abi": ABI,
            "architecture": "AArch64",
            "source": {
                "package": "mariadb",
                "version": MARIADB_VERSION,
                "index_url": INDEX_URL,
                "index_sha256": index_sha,
                "package_url": BASE_URL + MARIADB_PACKAGE,
                "package_sha256": MARIADB_SHA256,
                "license": "GPL-2.0-only",
            },
            "packages": packages,
            "runtime": runtime_manifest,
        }
        (STAGE / "BUILD_PROVENANCE.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
        lock = {
            "schema": 1,
            "provider": runtime_manifest["provider"],
            "abi": ABI,
            "package_index": {"url": INDEX_URL, "sha256": index_sha},
            "packages": packages,
            "runtime": runtime_manifest,
        }
        LOCKFILE.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"provider": runtime_manifest["provider"], "abi": ABI,
                          "executables": executable_records, "private_libs": len(link_records),
                          "index_sha256": index_sha}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
