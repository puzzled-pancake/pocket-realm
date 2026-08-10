#!/usr/bin/env python3
"""Stage the closed ARM renderer-package catalog from pinned Winlator assets."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess


ROOT = Path(__file__).resolve().parents[1]
SOURCE = (
    ROOT / "native" / ".providers-extracted" / "winlator-app-ca3d735" /
    "app" / "src" / "main" / "assets"
)
STAGE = (
    ROOT / "native" / ".build-arm64" / "wine-staging" / "assets" /
    "arm-translated" / "renderer-packages"
)
SOURCE_COMMIT = "ca3d735a60d653a787daf16d14fafef28d9c2c23"
TURNIP_ARCHIVE_SHA256 = "9b4a10975456197e403c2b6a8a9781a8fd42ccf5048262a8cdea6538bb68d288"

PACKAGES = (
    {
        "id": "box64-dxvk-2.4.1",
        "version": "2.4.1",
        "archive": "dxwrapper/dxvk-2.4.1.tzst",
        "archive_sha256": "897cc48500241006c15c62f200e9a6e1ea8a674bd285da25df6f68fdcdbfe42e",
        "qualification": "rp6-lab-current",
    },
    {
        "id": "box64-dxvk-1.10.3",
        "version": "1.10.3",
        "archive": "dxwrapper/dxvk-1.10.3.tzst",
        "archive_sha256": "18ed7c263e0d52c4bbd0e7345b4f22908c10966f37d7d6d80c639ec45123075a",
        "qualification": "installed-unqualified-legacy",
    },
)

MEMBERS = (
    ("system32", "./system32/d3d9.dll", 0x8664),
    ("syswow64", "./syswow64/d3d9.dll", 0x014C),
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_archive_members(archive: Path) -> set[str]:
    result = subprocess.run(
        ["tar", "-tf", str(archive)], check=True, capture_output=True, text=True,
    )
    names: set[str] = set()
    for raw in result.stdout.splitlines():
        name = raw.strip()
        if not name:
            continue
        normalized = name[2:] if name.startswith("./") else name
        path = PurePosixPath(normalized)
        if path.is_absolute() or ".." in path.parts:
            raise RuntimeError(f"unsafe renderer archive member: {name}")
        names.add("./" + normalized)
    return names


def extract_member(archive: Path, member: str, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(f".{target.name}.{os.getpid()}.tmp")
    with temporary.open("wb") as output:
        subprocess.run(["tar", "-xOf", str(archive), member], check=True, stdout=output)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, target)


def pe_machine(path: Path) -> int:
    data = path.read_bytes()
    if len(data) < 0x40 or data[:2] != b"MZ":
        raise RuntimeError(f"not a PE file: {path}")
    pe_offset = int.from_bytes(data[0x3C:0x40], "little")
    if pe_offset + 6 > len(data) or data[pe_offset:pe_offset + 4] != b"PE\0\0":
        raise RuntimeError(f"invalid PE header: {path}")
    return int.from_bytes(data[pe_offset + 4:pe_offset + 6], "little")


def main() -> int:
    turnip = SOURCE / "graphics_driver" / "turnip-26.1.0.tzst"
    if not turnip.is_file() or sha256(turnip) != TURNIP_ARCHIVE_SHA256:
        raise RuntimeError("pinned Turnip 26.1.0 archive is missing or changed")

    records = []
    for package in PACKAGES:
        archive = SOURCE / str(package["archive"])
        if not archive.is_file() or sha256(archive) != package["archive_sha256"]:
            raise RuntimeError(f"pinned renderer archive is missing or changed: {archive}")
        names = validate_archive_members(archive)
        files = []
        for directory, member, expected_machine in MEMBERS:
            if member not in names:
                raise RuntimeError(f"renderer archive lacks {member}: {archive}")
            target = STAGE / str(package["id"]) / directory / "d3d9.dll"
            extract_member(archive, member, target)
            machine = pe_machine(target)
            if machine != expected_machine:
                raise RuntimeError(
                    f"wrong PE machine for {package['id']}/{directory}: {machine:#x}"
                )
            files.append({
                "role": f"{directory}/d3d9.dll",
                "asset": (
                    f"arm-translated/renderer-packages/{package['id']}/"
                    f"{directory}/d3d9.dll"
                ),
                "size": target.stat().st_size,
                "sha256": sha256(target),
                "pe_machine": machine,
            })
        records.append({
            "id": package["id"],
            "backend": "dxvk",
            "translator": "box64",
            "dxvk_version": package["version"],
            "turnip_version": "26.1.0",
            "qualification": package["qualification"],
            "source": {
                "upstream": "https://github.com/brunodev85/winlator-app",
                "commit": SOURCE_COMMIT,
                "archive": package["archive"],
                "archive_sha256": package["archive_sha256"],
                "turnip_archive_sha256": TURNIP_ARCHIVE_SHA256,
                "license": "DXVK zlib; Turnip Mesa MIT",
            },
            "files": files,
        })

    manifest = {
        "schema": 1,
        "default_by_translator": {
            "box64": "box64-dxvk-2.4.1",
            "fex": "fex-dxvk-2.3.1-arm64ec",
        },
        "packages": records + [{
            "id": "fex-dxvk-2.3.1-arm64ec",
            "backend": "dxvk",
            "translator": "fex",
            "dxvk_version": "2.3.1 ARM64EC GPLAsync",
            "turnip_version": "26.2.0",
            "qualification": "built-unqualified",
            "source": {
                "catalog": "arm-translated/fexcore/BUILD_PROVENANCE.json",
                "license": "DXVK zlib; Turnip Mesa MIT",
            },
            "files": [],
        }],
    }
    STAGE.mkdir(parents=True, exist_ok=True)
    target = STAGE / "catalog.json"
    temporary = target.with_name(f".{target.name}.{os.getpid()}.tmp")
    temporary.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, target)
    print(json.dumps({"catalog": str(target), "packages": [p["id"] for p in manifest["packages"]]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
