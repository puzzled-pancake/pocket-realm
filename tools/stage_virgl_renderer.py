#!/usr/bin/env python3
"""Stage the exact ca3d735 Mesa virpipe guest used by its VirGL GLES server."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess


ROOT = Path(__file__).resolve().parents[1]

try:
    from tools import common
except ImportError:  # direct execution: python tools/<script>.py
    import common
SOURCE_COMMIT = "ca3d735a60d653a787daf16d14fafef28d9c2c23"
MESA_SOURCE_COMMIT = "71c57a2def7db3eb45cde5ee520f112de0fa6ec0"
PACKAGE_ID = "box64-virgl-23.1.9"
ARCHIVE = (
    ROOT / "native" / ".providers-extracted" / "winlator-app-ca3d735" /
    "app" / "src" / "main" / "assets" / "graphics_driver" / "virgl-23.1.9.tzst"
)
ARCHIVE_SHA256 = "614b1edc8e47c57b2cbb2d96f9c7ab5f5b1a89038de618a58b2faf9c64380e09"
MEMBER = "./usr/lib/libGL.so.1.7.0"
CLIENT_SIZE = 14_379_544
CLIENT_SHA256 = "531e3dc809281feadcc2120abc6d9f88025d92d567ac32eed9c376bd9e4e04f6"
STAGE = (
    ROOT / "native" / ".build-arm64" / "wine-staging" / "assets" /
    "arm-translated" / "renderer-packages" / PACKAGE_ID
)
TARGET = STAGE / "libGL.so.1"
PROVENANCE = STAGE / "BUILD_PROVENANCE.json"


sha256 = common.sha256_file
def members() -> set[str]:
    result = subprocess.run(
        ["tar", "-tf", str(ARCHIVE)], check=True, capture_output=True, text=True,
    )
    normalized: set[str] = set()
    for raw in result.stdout.splitlines():
        value = raw.strip()
        if not value:
            continue
        path = PurePosixPath(value[2:] if value.startswith("./") else value)
        if path.is_absolute() or ".." in path.parts:
            raise RuntimeError(f"unsafe VirGL archive member: {value}")
        normalized.add("./" + str(path))
    return normalized


def main() -> int:
    if not ARCHIVE.is_file() or sha256(ARCHIVE) != ARCHIVE_SHA256:
        raise RuntimeError("pinned ca3d735 VirGL guest archive is absent or changed")
    actual_members = members()
    expected_members = {"./.", "./usr", "./usr/lib", MEMBER}
    if actual_members != expected_members:
        raise RuntimeError(f"VirGL archive member set changed: {sorted(actual_members)}")

    STAGE.mkdir(parents=True, exist_ok=True)
    temporary = TARGET.with_name(f".{TARGET.name}.{os.getpid()}.tmp")
    with temporary.open("wb") as output:
        subprocess.run(["tar", "-xOf", str(ARCHIVE), MEMBER], check=True, stdout=output)
        output.flush()
        os.fsync(output.fileno())
    data = temporary.read_bytes()[:20]
    if len(data) < 20 or data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise RuntimeError("VirGL client is not little-endian ELF64")
    if int.from_bytes(data[18:20], "little") != 0xB7:
        raise RuntimeError("VirGL client is not AArch64")
    if temporary.stat().st_size != CLIENT_SIZE or sha256(temporary) != CLIENT_SHA256:
        raise RuntimeError("VirGL guest client differs from the reviewed artifact")
    os.replace(temporary, TARGET)

    record = {
        "schema": 1,
        "package_id": PACKAGE_ID,
        "source_commit": SOURCE_COMMIT,
        "mesa_source_commit": MESA_SOURCE_COMMIT,
        "source_archive": str(ARCHIVE.relative_to(ROOT)).replace("\\", "/"),
        "source_archive_sha256": ARCHIVE_SHA256,
        "source_member": MEMBER,
        "output": str(TARGET.relative_to(ROOT)).replace("\\", "/"),
        "size": CLIENT_SIZE,
        "sha256": CLIENT_SHA256,
        "elf_machine": 0xB7,
        "soname": "libGL.so.1",
        "guest_driver": "virpipe",
        "server_protocol": "ca3d735-virgl-vtest",
    }
    temporary_record = PROVENANCE.with_name(f".{PROVENANCE.name}.{os.getpid()}.tmp")
    temporary_record.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary_record, PROVENANCE)
    print(f"staged {TARGET} ({CLIENT_SIZE} bytes, {CLIENT_SHA256})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
