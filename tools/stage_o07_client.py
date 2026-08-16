#!/usr/bin/env python3
"""Validate and stage a user-owned WoW 1.12.1 build 5875 client.

This is the temporary O07 debug import path allowed by report section 20.3.
It never executes or modifies the source directory. Files are streamed into a
private app staging generation, published per-file through .partial + rename,
verified on-device, then the complete generation is atomically activated.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

try:
    from tools import common
except ImportError:  # direct execution: python tools/<script>.py
    import common


PACKAGE = "com.pocketrealm"
CLIENT_ID = "wow-1.12.1-5875"
EXPECTED_EXE_SHA256 = "b4756d38ef207c02ed651f4952bd89a70b4857b73a33413339e1b285b28d2dc7"
SAFE_REALMLIST = b"set realmlist 127.0.0.1\r\n"
SAFE_CONFIG = b"""SET readTOS "1"\r
SET readEULA "1"\r
SET readScanning "1"\r
SET movie "0"\r
SET gxResolution "1280x720"\r
SET gxWindow "1"\r
SET gxMaximize "0"\r
SET gxVSync "0"\r
SET gxMultisample "1"\r
SET gxMultisampleQuality "0.000000"\r
SET maxFPS "30"\r
SET Sound_EnableAllSound "0"\r
SET Sound_EnableMusic "0"\r
SET Sound_EnableSFX "0"\r
SET Sound_EnableAmbience "0"\r
SET ffxGlow "0"\r
SET ffxDeath "0"\r
SET farclip "177"\r
SET realmName "MaNGOS"\r
"""
REQUIRED_MPQS = {
    "base.mpq", "dbc.mpq", "fonts.mpq", "interface.mpq", "misc.mpq",
    "model.mpq", "sound.mpq", "speech.mpq", "terrain.mpq", "texture.mpq",
    "wmo.mpq",
}
STANDARD_ROOT_DLLS = {
    "dbghelp.dll", "divxdecoder.dll", "fmod.dll", "ijl15.dll", "scan.dll",
    "unicows.dll",
}


class ImportFailure(RuntimeError):
    pass


@dataclass(frozen=True)
class SourceFile:
    relative: str
    path: Path
    size: int


sha256_file = common.sha256_file
def parse_wow_exe(path: Path) -> dict[str, object]:
    data = path.read_bytes()
    if len(data) < 512 or data[:2] != b"MZ":
        raise ImportFailure("VAL-01/02: WoW.exe is not a PE executable")
    pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
    if pe_offset + 26 > len(data) or data[pe_offset:pe_offset + 4] != b"PE\0\0":
        raise ImportFailure("VAL-02: WoW.exe has no valid PE header")
    machine = struct.unpack_from("<H", data, pe_offset + 4)[0]
    optional_magic = struct.unpack_from("<H", data, pe_offset + 24)[0]
    if machine != 0x14C or optional_magic != 0x10B:
        raise ImportFailure(
            f"VAL-02: expected IMAGE_FILE_MACHINE_I386 PE32, got machine=0x{machine:04x} magic=0x{optional_magic:04x}"
        )
    signature = struct.pack("<I", 0xFEEF04BD)
    positions = [m.start() for m in re.finditer(re.escape(signature), data)]
    version = None
    for pos in positions:
        if pos + 16 <= len(data):
            version_ms, version_ls = struct.unpack_from("<II", data, pos + 8)
            candidate = (version_ms >> 16, version_ms & 0xFFFF, version_ls >> 16, version_ls & 0xFFFF)
            if candidate == (1, 12, 1, 5875):
                version = candidate
                break
    if version != (1, 12, 1, 5875):
        raise ImportFailure(f"VAL-03: expected 1.12.1.5875, detected {version or 'no matching version resource'}")
    return {
        "machine": machine,
        "optionalMagic": optional_magic,
        "version": ".".join(map(str, version)),
        "build": version[3],
        "sha256": hashlib.sha256(data).hexdigest(),
        "size": len(data),
    }


def scan_source(root: Path) -> tuple[list[SourceFile], dict[str, object]]:
    root = root.resolve(strict=True)
    if not root.is_dir():
        raise ImportFailure("selected source is not a directory")
    exe = root / "WoW.exe"
    if not exe.is_file():
        raise ImportFailure("VAL-01: WoW.exe is absent or unreadable")
    identity = parse_wow_exe(exe)

    files: list[SourceFile] = []
    folded: dict[str, str] = {}
    warnings: list[str] = []
    for current, dirs, names in os.walk(root, followlinks=False):
        current_path = Path(current)
        for name in [*dirs, *names]:
            item = current_path / name
            relative = item.relative_to(root).as_posix()
            pure = PurePosixPath(relative)
            if pure.is_absolute() or ".." in pure.parts or "\x00" in relative or any("\n" in p or "\r" in p for p in pure.parts):
                raise ImportFailure(f"VAL-07: unsafe path {relative!r}")
            info = item.lstat()
            attrs = getattr(info, "st_file_attributes", 0)
            if stat.S_ISLNK(info.st_mode) or attrs & 0x400:
                raise ImportFailure(f"VAL-07: link/reparse point is not importable: {relative}")
            key = relative.casefold()
            previous = folded.setdefault(key, relative)
            if previous != relative:
                raise ImportFailure(f"VAL-06: case-fold collision: {previous!r} and {relative!r}")
        for name in names:
            item = current_path / name
            info = item.stat()
            if not stat.S_ISREG(info.st_mode):
                raise ImportFailure(f"VAL-07: special file is not importable: {item.relative_to(root)}")
            relative = item.relative_to(root).as_posix()
            files.append(SourceFile(relative, item, info.st_size))

    files.sort(key=lambda f: f.relative.casefold())
    total = sum(f.size for f in files)
    if not (20 <= len(files) <= 100_000) or not (1 * 1024**3 <= total <= 20 * 1024**3):
        raise ImportFailure(f"VAL-08: implausible client size/count: files={len(files)} bytes={total}")

    data_dir = root / "Data"
    if not data_dir.is_dir():
        raise ImportFailure("VAL-04: Data directory is absent")
    mpq_files = [
        path for path in data_dir.iterdir()
        if path.is_file() and path.suffix.casefold() == ".mpq"
    ]
    present_mpqs = {p.name.casefold() for p in mpq_files}
    missing = sorted(REQUIRED_MPQS - present_mpqs)
    if missing:
        raise ImportFailure(f"VAL-04: missing base MPQ set: {', '.join(missing)}")
    for file in mpq_files:
        with file.open("rb") as stream:
            if stream.read(4) != b"MPQ\x1a":
                raise ImportFailure(f"VAL-04: invalid MPQ header: Data/{file.name}")
    # This 2006 client uses the supported flat English layout; GlueStrings.lua
    # was confirmed in interface.MPQ/patch.MPQ during the read-only inspection.
    warnings.append("VAL-05: FLAT_ENGLISH_LOCALE_INFERRED")

    custom = [
        f.relative for f in files
        if "/" not in f.relative and f.path.suffix.casefold() == ".dll"
        and f.path.name.casefold() not in STANDARD_ROOT_DLLS
    ]
    if custom:
        warnings.append("VAL-10: unrecognized root DLLs: " + ", ".join(custom))
    if identity["sha256"] != EXPECTED_EXE_SHA256:
        warnings.append("VAL-03: build confirmed but executable hash is not on the local evidence allowlist")
    else:
        warnings.append("VAL-03: executable hash matches the inspected user-supplied build 5875 copy")
    return files, {
        **identity,
        "clientId": CLIENT_ID,
        "fileCount": len(files),
        "sourceBytes": total,
        "locale": "enUS-flat-classic",
        "warnings": warnings,
    }


class Adb:
    def __init__(self, serial: str, package: str):
        self.serial = serial
        self.package = package
        self.exe = shutil.which("adb")
        if not self.exe:
            sdk = common.resolve_android_sdk()
            candidate = sdk / "platform-tools" / "adb.exe"
            if candidate.is_file():
                self.exe = str(candidate)
        if not self.exe:
            raise ImportFailure("adb was not found")
        safe_serial = re.sub(r"[^A-Za-z0-9_.-]", "_", serial)
        self.transfer = f"/data/local/tmp/pocketrealm-o07-{safe_serial}.blob"

    def command(self, *args: str) -> list[str]:
        return [self.exe, "-s", self.serial, *args]

    def run_as(self, *args: str, input_data: bytes | None = None) -> subprocess.CompletedProcess[bytes]:
        transport_args = ("exec-in",) if input_data is not None else ("exec-out",)
        return subprocess.run(
            self.command(*transport_args, "run-as", self.package, *args),
            input=input_data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
        )

    def install_writer(self) -> None:
        helper = b"""#!/system/bin/sh
mode=$1
target=$(printf '%s' "$2" | base64 -d) || exit 2
case "$target" in
  no_backup/client/.staging-o07/*) ;;
  *) echo O07_BAD_TARGET >&2; exit 3 ;;
esac
if [ "$mode" = stat ]; then stat -c %s "$target"; exit; fi
if [ "$mode" = sha ]; then sha256sum "$target"; exit; fi
if [ "$mode" = copy ]; then
  parent=${target%/*}
  mkdir -p "$parent" || exit 4
  cp "__TRANSFER__" "$target.partial" || exit 9
  chmod 600 "$target.partial" || exit 6
  mv "$target.partial" "$target" || exit 7
  exit 0
fi
[ "$mode" = write ] || exit 8
parent=${target%/*}
mkdir -p "$parent" || exit 4
base64 -d > "$target.partial" || exit 5
chmod 600 "$target.partial" || exit 6
mv "$target.partial" "$target" || exit 7
exit 0
""".replace(b"__TRANSFER__", self.transfer.encode())
        self.run_as("mkdir", "-p", "no_backup/client")
        self.run_as("tee", "no_backup/client/.o07-write.sh", input_data=helper)
        remote = self.run_as("sha256sum", "no_backup/client/.o07-write.sh").stdout.decode().split()
        expected = hashlib.sha256(helper).hexdigest()
        if not remote or remote[0].lower() != expected:
            raise ImportFailure("could not install app-private import writer")

    def write_bytes(self, relative: str, content: bytes) -> None:
        temp_name = ""
        try:
            with tempfile.NamedTemporaryFile(prefix="pocketrealm-o07-", delete=False) as temp:
                temp.write(content)
                temp_name = temp.name
            self._push_to_target(Path(temp_name), relative)
            if self.sha256(relative) != hashlib.sha256(content).hexdigest():
                raise ImportFailure(f"adb write verification failed for {relative}")
        finally:
            if temp_name:
                Path(temp_name).unlink(missing_ok=True)

    def stream_file(self, source: Path, relative: str) -> str:
        self._push_to_target(source, relative)
        return sha256_file(source)

    def _push_to_target(self, source: Path, relative: str) -> None:
        encoded = base64.b64encode(relative.encode()).decode()
        last_error = "transfer was not attempted"
        try:
            for attempt in range(1, 4):
                pushed = subprocess.run(
                    self.command("push", str(source), self.transfer),
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
                )
                if pushed.returncode:
                    last_error = pushed.stderr.decode(errors="replace").strip()
                else:
                    copied = self.run_as(
                        "sh", "no_backup/client/.o07-write.sh", "copy", encoded,
                    )
                    measured = self.stat_size(relative)
                    if copied.returncode == 0 and measured == str(source.stat().st_size):
                        return
                    last_error = (
                        copied.stderr.decode(errors="replace").strip() or
                        copied.stdout.decode(errors="replace").strip() or
                        f"size={measured!r}, expected={source.stat().st_size}"
                    )
                if attempt < 3:
                    time.sleep(attempt)
            raise ImportFailure(
                f"app-private copy failed for {relative} after 3 attempts: {last_error}"
            )
        finally:
            subprocess.run(self.command("shell", "rm", "-f", self.transfer),
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)

    def stat_size(self, relative: str) -> str:
        encoded = base64.b64encode(relative.encode()).decode()
        return self.run_as("sh", "no_backup/client/.o07-write.sh", "stat", encoded).stdout.decode().strip()

    def sha256(self, relative: str) -> str:
        encoded = base64.b64encode(relative.encode()).decode()
        return self.run_as("sh", "no_backup/client/.o07-write.sh", "sha", encoded).stdout.decode().split()[0].lower()


def stage(
    source: Path,
    serial: str,
    package: str,
    replace: bool,
    restore_arm64_after_app_data_loss: bool = False,
) -> dict[str, object]:
    files, identity = scan_source(source)
    adb = Adb(serial, package)
    props = subprocess.run(adb.command("shell", "getprop", "ro.product.cpu.abi"), capture_output=True, text=True, check=True).stdout.strip()
    page = subprocess.run(adb.command("shell", "getconf", "PAGESIZE"), capture_output=True, text=True, check=True).stdout.strip()
    ordinary_lane = props == "x86_64" and page == "4096"
    exact_arm64_restore = (
        restore_arm64_after_app_data_loss and
        props == "arm64-v8a" and page == "4096" and
        identity["sha256"] == EXPECTED_EXE_SHA256
    )
    if not ordinary_lane and not exact_arm64_restore:
        raise ImportFailure(
            "O07 staging requires x86_64/4096, or the explicit exact-hash "
            f"ARM64 data-loss restore lane; got {props}/{page}"
        )
    free_lines = adb.run_as("df", "-Pk", "no_backup").stdout.decode().splitlines()
    free_out = free_lines[-1].split() if free_lines else []
    if len(free_out) < 4:
        raise ImportFailure("could not measure app-private free space")
    free_bytes = int(free_out[3]) * 1024
    reserve = 2 * 1024**3
    if free_bytes < int(identity["sourceBytes"]) + reserve:
        raise ImportFailure(
            f"VAL-08: insufficient free space: need source+2GiB={int(identity['sourceBytes']) + reserve}, have={free_bytes}"
        )

    exists = adb.run_as("ls", "-d", "no_backup/client/active").stdout.strip() == b"no_backup/client/active"
    if exists and not replace:
        raise ImportFailure("managed client already exists; pass --replace to publish a new generation")
    adb.run_as("mkdir", "-p", "no_backup/client/.staging-o07")
    adb.install_writer()

    completed: dict[str, dict[str, object]] = {}
    journal_result = adb.run_as("cat", "no_backup/client/.staging-o07/.import-journal.json")
    if journal_result.stdout:
        try:
            completed = json.loads(journal_result.stdout)["completed"]
        except (KeyError, json.JSONDecodeError):
            completed = {}

    started = time.time()
    stored_entries: list[dict[str, object]] = []
    for index, item in enumerate(files, 1):
        target_rel = f"no_backup/client/.staging-o07/{item.relative}"
        previous = completed.get(item.relative)
        # A resumable generation is reusable only for the same source bytes,
        # not merely for a same-sized file left by an earlier selection.
        source_digest = sha256_file(item.path)
        device_ok = False
        if (previous and previous.get("size") == item.size and
                previous.get("sha256") == source_digest):
            measured = adb.stat_size(target_rel)
            device_ok = (
                measured == str(item.size) and
                adb.sha256(target_rel) == source_digest
            )
        if device_ok:
            digest = source_digest
        else:
            print(f"[{index:03d}/{len(files):03d}] {item.relative} ({item.size:,} bytes)", flush=True)
            digest = adb.stream_file(item.path, target_rel)
            if digest != source_digest:
                raise ImportFailure(f"source changed while copying: {item.relative}")
            completed[item.relative] = {"size": item.size, "sha256": digest}
            journal = json.dumps({"schema": 1, "completed": completed}, sort_keys=True).encode()
            adb.write_bytes("no_backup/client/.staging-o07/.import-journal.json", journal)
        stored_entries.append({"path": item.relative, "size": item.size, "sha256": digest})

    # Only the managed copy receives endpoint and conservative settings.
    adb.write_bytes("no_backup/client/.staging-o07/realmlist.wtf", SAFE_REALMLIST)
    adb.write_bytes("no_backup/client/.staging-o07/WTF/Config.wtf", SAFE_CONFIG)
    for relative, content in (("realmlist.wtf", SAFE_REALMLIST), ("WTF/Config.wtf", SAFE_CONFIG)):
        stored_entries = [entry for entry in stored_entries if entry["path"].casefold() != relative.casefold()]
        stored_entries.append({"path": relative, "size": len(content), "sha256": hashlib.sha256(content).hexdigest()})
    stored_entries.sort(key=lambda entry: str(entry["path"]).casefold())

    manifest = {
        "schema": 1,
        "complete": True,
        "clientId": CLIENT_ID,
        "identity": identity,
        "executable": "WoW.exe",
        "directLaunch": True,
        "sourceRuntimeDependency": False,
        "managedRoot": "no_backup/client/active",
        "safeMode": {
            "renderer": "wined3d", "resolution": "1280x720", "fpsCap": 30,
            "audio": "off", "realmEndpoint": "127.0.0.1", "addons": "off",
        },
        "files": stored_entries,
        "stagedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "durationSeconds": round(time.time() - started, 3),
    }
    encoded = json.dumps(manifest, indent=2, sort_keys=True).encode()
    adb.write_bytes("no_backup/client/.staging-o07/client-manifest.json", encoded)

    # Re-read every staged byte through sha256sum before activation. The two
    # managed config hashes intentionally replace their source counterparts.
    for index, entry in enumerate(stored_entries, 1):
        relative = str(entry["path"])
        actual = adb.sha256("no_backup/client/.staging-o07/" + relative)
        if actual != entry["sha256"]:
            raise ImportFailure(f"verification failed after copy: {relative}")
        if index % 20 == 0 or index == len(stored_entries):
            print(f"verified {index}/{len(stored_entries)}", flush=True)

    if exists:
        adb.run_as("rm", "-rf", "no_backup/client/previous")
        moved = adb.run_as("mv", "no_backup/client/active", "no_backup/client/previous")
        if moved.stderr:
            raise ImportFailure(f"could not preserve active generation: {moved.stderr.decode(errors='replace')}")
    adb.run_as("rm", "-f", "no_backup/client/.o07-write.sh")
    published = adb.run_as("mv", "no_backup/client/.staging-o07", "no_backup/client/active")
    if published.stderr:
        raise ImportFailure(f"could not publish managed generation: {published.stderr.decode(errors='replace')}")
    print(json.dumps(identity, indent=2, sort_keys=True))
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--serial", required=True,
                        help="target device/emulator serial (e.g. emulator-5554); "
                             "no implicit device default (de-vibe P4)")
    parser.add_argument("--package", default=PACKAGE)
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--replace", action="store_true")
    parser.add_argument(
        "--restore-arm64-after-app-data-loss",
        action="store_true",
        help="explicitly restore the inspected build-5875 source to an ARM64 device",
    )
    parser.add_argument("--evidence-out", type=Path)
    args = parser.parse_args()
    try:
        files, identity = scan_source(args.source)
        print(json.dumps(identity, indent=2, sort_keys=True))
        if args.evidence_out:
            evidence = {
                "schema": 1,
                "clientId": CLIENT_ID,
                "sourceMutated": False,
                "sourceRuntimeDependency": False,
                "directLaunch": True,
                "validation": identity,
            }
            args.evidence_out.parent.mkdir(parents=True, exist_ok=True)
            args.evidence_out.write_text(
                json.dumps(evidence, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
        if not args.validate_only:
            stage(
                args.source,
                args.serial,
                args.package,
                args.replace,
                args.restore_arm64_after_app_data_loss,
            )
        return 0
    except (ImportFailure, OSError, subprocess.CalledProcessError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
