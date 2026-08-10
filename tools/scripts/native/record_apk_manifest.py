#!/usr/bin/env python3
"""Validate a single-ABI APK and record its install-time identity."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile


ELF_MACHINE = {"x86_64": 62, "arm64-v8a": 183}
COMPAT32_MACHINE = {"x86_64": 3, "arm64-v8a": 40}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_apksigner(explicit: str | None) -> Path:
    if explicit:
        candidate = Path(explicit).resolve()
        if candidate.is_file():
            return candidate
        raise FileNotFoundError(f"apksigner does not exist: {candidate}")

    on_path = shutil.which("apksigner") or shutil.which("apksigner.bat")
    if on_path:
        return Path(on_path).resolve()

    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if sdk:
        build_tools = Path(sdk) / "build-tools"
        names = ("apksigner.bat", "apksigner") if os.name == "nt" else ("apksigner",)
        candidates = [path for name in names for path in build_tools.glob(f"*/{name}")]
        if candidates:
            return sorted(candidates, key=lambda path: path.parent.name)[-1].resolve()
    raise FileNotFoundError("apksigner not found; set ANDROID_SDK_ROOT or pass --apksigner")


def signing_identity(apksigner: Path, apk: Path) -> dict[str, str]:
    command = [str(apksigner), "verify", "--print-certs", str(apk)]
    if os.name == "nt" and apksigner.suffix.lower() in {".bat", ".cmd"}:
        command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", *command]
    result = subprocess.run(command, check=False, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f"apksigner rejected {apk}:\n{result.stdout}{result.stderr}")

    fields: dict[str, str] = {}
    labels = {
        "Signer #1 certificate DN": "certificate_dn",
        "Signer #1 certificate SHA-256 digest": "certificate_sha256",
        "Signer #1 certificate SHA-1 digest": "certificate_sha1",
        "Signer #1 certificate MD5 digest": "certificate_md5",
    }
    for line in result.stdout.splitlines():
        for label, key in labels.items():
            if line.startswith(f"{label}:"):
                fields[key] = line.split(":", 1)[1].strip()
    if "certificate_sha256" not in fields:
        raise RuntimeError("apksigner did not report the signer certificate SHA-256")
    return fields


def native_manifest(apk: Path, abi: str) -> list[dict[str, object]]:
    records: list[dict[str, object]] = []
    packaged_abis: set[str] = set()
    with zipfile.ZipFile(apk) as archive:
        for info in archive.infolist():
            parts = info.filename.split("/")
            if len(parts) != 3 or parts[0] != "lib" or not parts[2].endswith(".so"):
                continue
            packaged_abis.add(parts[1])
            data = archive.read(info)
            if len(data) < 20 or data[:4] != b"\x7fELF" or data[5] != 1:
                raise RuntimeError(f"APK native entry is not little-endian ELF: {info.filename}")
            machine = int.from_bytes(data[18:20], "little")
            allowed = {ELF_MACHINE[abi]}
            if parts[2] == "libproot_loader32.so":
                allowed.add(COMPAT32_MACHINE[abi])
            if machine not in allowed:
                raise RuntimeError(
                    f"cross-ABI ELF rejected: {info.filename} has e_machine={machine}, "
                    f"expected one of {sorted(allowed)}"
                )
            records.append(
                {
                    "path": info.filename,
                    "size": info.file_size,
                    "sha256": sha256(data),
                    "elf_machine": machine,
                }
            )
    if packaged_abis != {abi}:
        raise RuntimeError(f"APK ABI set is {sorted(packaged_abis)}, expected only {abi}")
    return sorted(records, key=lambda record: str(record["path"]))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--abi", required=True, choices=sorted(ELF_MACHINE))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--apksigner")
    args = parser.parse_args()

    apk = args.apk.resolve()
    if not apk.is_file():
        parser.error(f"APK does not exist: {apk}")
    output = (args.output or apk.with_suffix(".build-manifest.json")).resolve()
    manifest = {
        "schema": 1,
        "declared_abi": args.abi,
        "apk": {
            "name": apk.name,
            "size": apk.stat().st_size,
            "sha256": sha256_file(apk),
        },
        "signing": signing_identity(find_apksigner(args.apksigner), apk),
        "native_libraries": native_manifest(apk, args.abi),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"validated {args.abi} APK: {apk}")
    print(f"wrote build manifest: {output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (FileNotFoundError, RuntimeError, zipfile.BadZipFile) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
