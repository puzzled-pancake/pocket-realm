#!/usr/bin/env python3
"""Stage the closed ARM DXVK and Vulkan-driver catalogs from pinned assets."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess


ROOT = Path(__file__).resolve().parents[1]
SOURCE = (
    ROOT / "native" / ".providers-extracted" / "winlator-app-ca3d735" /
    "app" / "src" / "main" / "assets"
)
RENDERER_STAGE = (
    ROOT / "native" / ".build-arm64" / "wine-staging" / "assets" /
    "arm-translated" / "renderer-packages"
)
DRIVER_STAGE = (
    ROOT / "native" / ".build-arm64" / "wine-staging" / "assets" /
    "arm-translated" / "vulkan-drivers"
)
SOURCE_COMMIT = "ca3d735a60d653a787daf16d14fafef28d9c2c23"
TURNIP_ARCHIVE_SHA256 = "9b4a10975456197e403c2b6a8a9781a8fd42ccf5048262a8cdea6538bb68d288"
VORTEK_ARCHIVE_SHA256 = "f2cce15552bc6ff195823bf066ad1a421cb1f453bf80afa3ccd925ff0b1a5713"
OLD_ROOT = b"/data/data/com.winlator/files/rootfs"
NEW_ROOT = b"/data/data/com.pocketrealm/files/rfs"

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


def elf_machine(path: Path) -> int:
    data = path.read_bytes()[:20]
    if len(data) != 20 or data[:4] != b"\x7fELF" or data[5] != 1:
        raise RuntimeError(f"not a little-endian ELF file: {path}")
    return int.from_bytes(data[18:20], "little")


def write_atomic(target: Path, data: bytes) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(f".{target.name}.{os.getpid()}.tmp")
    with temporary.open("wb") as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, target)


def stage_vulkan_drivers() -> dict[str, object]:
    if len(OLD_ROOT) != len(NEW_ROOT):
        raise RuntimeError("Vortek package-root adaptation is not length preserving")
    if DRIVER_STAGE.exists():
        shutil.rmtree(DRIVER_STAGE)

    records: list[dict[str, object]] = []

    turnip_archive = SOURCE / "graphics_driver" / "turnip-26.1.0.tzst"
    if not turnip_archive.is_file() or sha256(turnip_archive) != TURNIP_ARCHIVE_SHA256:
        raise RuntimeError("pinned Turnip 26.1.0 archive is missing or changed")
    turnip_members = validate_archive_members(turnip_archive)
    turnip_id = "turnip-26.1.0"
    turnip_dir = DRIVER_STAGE / turnip_id
    turnip_lib = turnip_dir / "libvulkan_freedreno.so"
    turnip_member = "./usr/lib/libvulkan_freedreno.so"
    if turnip_member not in turnip_members:
        raise RuntimeError("pinned Turnip archive lacks its Vulkan library")
    extract_member(turnip_archive, turnip_member, turnip_lib)
    turnip_icd = turnip_dir / "freedreno_icd.aarch64.json"
    # The upstream JSON embeds Winlator's package root. Publish a small,
    # deterministic manifest instead of accepting a mutable path at runtime.
    turnip_icd_data = (
        "{\n"
        "  \"ICD\": {\n"
        "    \"api_version\": \"1.4.318\",\n"
        "    \"library_arch\": \"64\",\n"
        "    \"library_path\": \"/data/data/com.pocketrealm/files/rfs/lib/libvulkan_freedreno.so\"\n"
        "  },\n"
        "  \"file_format_version\": \"1.0.1\"\n"
        "}\n"
    ).encode("utf-8")
    write_atomic(turnip_icd, turnip_icd_data)
    if elf_machine(turnip_lib) != 0xB7:
        raise RuntimeError("Turnip guest driver is not AArch64 ELF")
    records.append({
        "id": turnip_id,
        "kind": "turnip",
        "version": "26.1.0",
        "qualification": "explicit-rp6-adreno-740",
        "release": {
            "enabled": True,
            "default": False,
            "qualified_device_models": ["Retroid Pocket 6"],
        },
        "source": {
            "upstream": "https://github.com/brunodev85/winlator-app",
            "commit": SOURCE_COMMIT,
            "archive": "graphics_driver/turnip-26.1.0.tzst",
            "archive_sha256": TURNIP_ARCHIVE_SHA256,
            "license": "Mesa MIT",
        },
        "files": [
            {
                "role": "guest-vulkan-icd-library",
                "asset": f"arm-translated/vulkan-drivers/{turnip_id}/{turnip_lib.name}",
                "size": turnip_lib.stat().st_size,
                "sha256": sha256(turnip_lib),
                "elf_machine": elf_machine(turnip_lib),
            },
            {
                "role": "guest-vulkan-icd-manifest",
                "asset": f"arm-translated/vulkan-drivers/{turnip_id}/{turnip_icd.name}",
                "size": turnip_icd.stat().st_size,
                "sha256": sha256(turnip_icd),
            },
        ],
    })

    vortek_archive = SOURCE / "graphics_driver" / "vortek-2.1.tzst"
    if not vortek_archive.is_file() or sha256(vortek_archive) != VORTEK_ARCHIVE_SHA256:
        raise RuntimeError("pinned Vortek 2.1 archive is missing or changed")
    vortek_members = validate_archive_members(vortek_archive)
    vortek_id = "system-vulkan-vortek-2.1"
    vortek_dir = DRIVER_STAGE / vortek_id
    vortek_library_member = "./usr/lib/libvulkan_vortek.so"
    vortek_manifest_member = "./usr/share/vulkan/icd.d/vortek_icd.aarch64.json"
    if vortek_library_member not in vortek_members or vortek_manifest_member not in vortek_members:
        raise RuntimeError("pinned Vortek archive lacks its library or ICD manifest")
    vortek_lib = vortek_dir / "libvulkan_vortek.so"
    vortek_icd = vortek_dir / "vortek_icd.aarch64.json"
    extract_member(vortek_archive, vortek_library_member, vortek_lib)
    library_data = vortek_lib.read_bytes()
    if library_data.count(OLD_ROOT) != 2:
        raise RuntimeError("unexpected Vortek package-root count in guest library")
    write_atomic(vortek_lib, library_data.replace(OLD_ROOT, NEW_ROOT))
    vortek_icd_data = (
        "{\n"
        "  \"ICD\": {\n"
        "    \"api_version\": \"1.3.128\",\n"
        "    \"library_arch\": \"64\",\n"
        "    \"library_path\": \"/data/data/com.pocketrealm/files/rfs/lib/libvulkan_vortek.so\"\n"
        "  },\n"
        "  \"file_format_version\": \"1.0.1\"\n"
        "}\n"
    ).encode("utf-8")
    write_atomic(vortek_icd, vortek_icd_data)
    if elf_machine(vortek_lib) != 0xB7:
        raise RuntimeError("Vortek guest driver is not AArch64 ELF")
    if (vortek_lib.stat().st_size, sha256(vortek_lib)) != (
        422_624,
        "894665b2df007b3dafcf987a56ddd0e67475ab6d7ef91224c395fffda3301c25",
    ):
        raise RuntimeError("adapted Vortek guest library differs from its pinned identity")
    if (vortek_icd.stat().st_size, sha256(vortek_icd)) != (
        192,
        "9e80133ca51ef57dac0cdc29ff8614d1fdffc5335fcf4e8ce38066da43f3c262",
    ):
        raise RuntimeError("adapted Vortek ICD differs from its pinned identity")
    records.insert(0, {
        "id": vortek_id,
        "kind": "system-vortek",
        "version": "2.1",
        "qualification": "hardened-capability-gated-system-vulkan",
        "release": {
            "enabled": True,
            "default": True,
            "request_handle_authority_complete": True,
            "minimum_vulkan_by_renderer": {
                "box64-dxvk-2.4.1": "1.3",
                "box64-dxvk-1.10.3": "1.1",
            },
            "required_device_extensions": [
                "VK_KHR_swapchain",
                "VK_ANDROID_external_memory_android_hardware_buffer",
                "VK_KHR_external_memory",
                "VK_KHR_external_memory_fd",
                "VK_KHR_external_semaphore",
                "VK_KHR_external_semaphore_fd",
                "VK_KHR_external_fence",
                "VK_KHR_external_fence_fd",
            ],
            "requires_native_texture_compression_bc": True,
        },
        "source": {
            "upstream": "https://github.com/brunodev85/winlator-app",
            "commit": SOURCE_COMMIT,
            "archive": "graphics_driver/vortek-2.1.tzst",
            "archive_sha256": VORTEK_ARCHIVE_SHA256,
            "license": "LGPL-2.1",
        },
        "files": [
            {
                "role": "guest-vulkan-bridge-library",
                "asset": f"arm-translated/vulkan-drivers/{vortek_id}/{vortek_lib.name}",
                "size": vortek_lib.stat().st_size,
                "sha256": sha256(vortek_lib),
                "elf_machine": elf_machine(vortek_lib),
            },
            {
                "role": "guest-vulkan-icd-manifest",
                "asset": f"arm-translated/vulkan-drivers/{vortek_id}/{vortek_icd.name}",
                "size": vortek_icd.stat().st_size,
                "sha256": sha256(vortek_icd),
            },
        ],
    })
    manifest: dict[str, object] = {
        "schema": 2,
        "default": vortek_id,
        "selection_policy": "exact-request-fail-closed",
        "drivers": records,
    }
    target = DRIVER_STAGE / "catalog.json"
    write_atomic(target, (json.dumps(manifest, indent=2) + "\n").encode("utf-8"))
    return manifest


def main() -> int:
    for retired in (RENDERER_STAGE.parent / "turnip", RENDERER_STAGE.parent / "dxvk"):
        if retired.exists():
            shutil.rmtree(retired)
    if RENDERER_STAGE.exists():
        shutil.rmtree(RENDERER_STAGE)
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
            target = RENDERER_STAGE / str(package["id"]) / directory / "d3d9.dll"
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
            "qualification": package["qualification"],
            "source": {
                "upstream": "https://github.com/brunodev85/winlator-app",
                "commit": SOURCE_COMMIT,
                "archive": package["archive"],
                "archive_sha256": package["archive_sha256"],
                "license": "DXVK zlib",
            },
            "files": files,
        })

    manifest = {
        "schema": 1,
        "default_by_translator": {
            "box64": "box64-dxvk-2.4.1",
        },
        "packages": records,
    }
    RENDERER_STAGE.mkdir(parents=True, exist_ok=True)
    target = RENDERER_STAGE / "catalog.json"
    write_atomic(target, (json.dumps(manifest, indent=2) + "\n").encode("utf-8"))
    driver_manifest = stage_vulkan_drivers()
    print(json.dumps({
        "renderer_catalog": str(target),
        "packages": [p["id"] for p in manifest["packages"]],
        "driver_catalog": str(DRIVER_STAGE / "catalog.json"),
        "drivers": [d["id"] for d in driver_manifest["drivers"]],
    }))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
