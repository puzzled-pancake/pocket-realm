#!/usr/bin/env python3
"""Extract, verify, and optionally stage O09 DBC/maps from a user-owned 5875 client.

The client directory is mounted read-only. No client-derived output is tracked
or bundled in an APK; it is installed only into the app's private content root.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tarfile
import time
from datetime import datetime, timezone
from pathlib import Path

from stage_o07_client import CLIENT_ID, scan_source


ROOT = Path(__file__).resolve().parents[1]
CMANGOS = ROOT / "native" / "cmangos"
BUILD = ROOT / "native" / ".build-o09-host-extractor"
DATA = ROOT / "native" / ".build-o09-server-data"
IMAGE = "ghcr.io/termux/package-builder-cgct@sha256:69ffa5cfe02ca569e7d03d1c99e3c9a0f79390ad6bf11a3629d048c29c6ccb61"
CMANGOS_COMMIT = "c096bada9e4ed23ad4ca706c67160a26d7121337"
PACKAGE = "com.pocketrealm"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(8 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def docker_path(path: Path) -> str:
    return path.resolve().as_posix()


def run(args: list[str], **kwargs) -> subprocess.CompletedProcess:
    print("+", " ".join(args))
    return subprocess.run(args, check=True, **kwargs)


def build_extractor() -> Path:
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=CMANGOS, text=True).strip()
    if actual != CMANGOS_COMMIT:
        raise RuntimeError(f"CMaNGOS pin mismatch: {actual}")
    BUILD.mkdir(parents=True, exist_ok=True)
    script = r'''set -eu
rm -rf /out/obj
mkdir -p /out/obj
for f in /src/dep/libmpq/libmpq/*.c; do
  b=$(basename "$f" .c)
  gcc -O2 -fPIC -I/src/dep/libmpq -I/src/dep/libmpq/libmpq -c "$f" -o "/out/obj/mpq_$b.o"
done
for f in /src/contrib/extractor/*.cpp /src/contrib/extractor/loadlib/*.cpp; do
  b=$(basename "$f" .cpp)
  g++ -std=c++17 -O2 -I/src/contrib/extractor -I/src/dep/libmpq -I/src/dep/libmpq/libmpq \
    -I/src/src/game -I/src/src/shared -c "$f" -o "/out/obj/ext_$b.o"
done
g++ /out/obj/*.o -lz -lbz2 -o /out/ad
strip --strip-unneeded /out/ad
'''
    run(["docker", "run", "--rm", "-v", f"{docker_path(CMANGOS)}:/src:ro",
         "-v", f"{docker_path(BUILD)}:/out", IMAGE, "bash", "-lc", script])
    extractor = BUILD / "ad"
    if not extractor.is_file():
        raise RuntimeError("map/DBC extractor was not produced")
    return extractor


def extract(client: Path, extractor: Path, force: bool) -> None:
    if force:
        shutil.rmtree(DATA, ignore_errors=True)
    dbc, maps = DATA / "dbc", DATA / "maps"
    if dbc.is_dir() and maps.is_dir():
        return
    DATA.mkdir(parents=True, exist_ok=True)
    run(["docker", "run", "--rm", "-v", f"{docker_path(client)}:/client:ro",
         "-v", f"{docker_path(DATA)}:/data", "-v", f"{docker_path(BUILD)}:/tools:ro",
         IMAGE, "/tools/ad", "-i", "/client", "-o", "/data", "-e", "3"])


def write_manifest(identity: dict, extractor: Path) -> dict:
    data_files = sorted(
        [path for folder in (DATA / "dbc", DATA / "maps") for path in folder.glob("*") if path.is_file()],
        key=lambda path: path.relative_to(DATA).as_posix().casefold(),
    )
    dbc_count = sum(path.parent.name == "dbc" for path in data_files)
    map_count = sum(path.parent.name == "maps" for path in data_files)
    if dbc_count < 150 or map_count < 2400:
        raise RuntimeError(f"implausible extractor result: dbc={dbc_count} maps={map_count}")
    required = [DATA / "dbc" / name for name in ("Map.dbc", "AreaTable.dbc", "Spell.dbc")]
    if not all(path.is_file() for path in required):
        raise RuntimeError("required server DBC set is incomplete")
    lines = []
    outputs = []
    for path in data_files:
        relative = path.relative_to(DATA).as_posix()
        digest = sha256(path)
        lines.append(f"{digest}  {relative}")
        outputs.append({"path": relative, "size": path.stat().st_size, "sha256": digest})
    checksum_file = DATA / "BUILD_MANIFEST.sha256"
    checksum_file.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    record = {
        "schema": 1, "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "client_id": CLIENT_ID,
        "client_identity": {key: identity[key] for key in ("version", "build", "sha256", "fileCount", "sourceBytes")},
        "source_policy": "user-owned input mounted read-only; extracted output is app-private and not bundled",
        "cmangos_commit": CMANGOS_COMMIT,
        "extractor": {"sha256": sha256(extractor), "builder_image": IMAGE, "mode": "DBC+MAP"},
        "output": {"dbc_count": dbc_count, "map_count": map_count,
                   "total_bytes": sum(item["size"] for item in outputs),
                   "manifest_sha256": sha256(checksum_file), "files": outputs},
    }
    (DATA / "BUILD_PROVENANCE.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    return record


def adb_executable() -> str:
    found = shutil.which("adb")
    if found:
        return found
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "")
    candidate = sdk / "platform-tools" / "adb.exe"
    if candidate.is_file():
        return str(candidate)
    properties = ROOT / "android" / "local.properties"
    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith("sdk.dir="):
            candidate = Path(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\")) / "platform-tools/adb.exe"
            if candidate.is_file():
                return str(candidate)
    raise RuntimeError("adb not found")


def stage(serial: str, record: dict) -> None:
    adb = adb_executable()
    generation = record["output"]["manifest_sha256"][:16]
    archive = BUILD / f"o09-server-data-{generation}.tar"
    with tarfile.open(archive, "w") as bundle:
        for name in ("dbc", "maps", "BUILD_MANIFEST.sha256", "BUILD_PROVENANCE.json"):
            bundle.add(DATA / name, arcname=name, recursive=True)
    archive_hash = sha256(archive)
    command = [adb, "-s", serial]
    base = f"/data/user/0/{PACKAGE}/files/content/o09-server"
    target = f"{base}/.staging-{generation}"
    run(command + ["shell", "run-as", PACKAGE, "rm", "-rf", target])
    run(command + ["shell", "run-as", PACKAGE, "mkdir", "-p", target])
    # Stream directly into the app domain. A /data/local/tmp intermediate is
    # readable by shell but is not reliably readable from untrusted_app under
    # every API-35 SELinux policy.
    with archive.open("rb") as source:
        unpack = subprocess.run(command + ["exec-in", "run-as", PACKAGE, "toybox", "tar",
                                 "-xf", "-", "-C", target], stdin=source,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if unpack.returncode:
        raise RuntimeError(f"app-private tar extraction failed: {unpack.stderr.decode(errors='replace')}")
    manifest_remote = f"{target}/BUILD_MANIFEST.sha256"
    for _ in range(100):
        if subprocess.run(command + ["shell", "run-as", PACKAGE, "test", "-f", manifest_remote]).returncode == 0:
            break
        time.sleep(0.1)
    else:
        raise RuntimeError("app-private tar extraction did not publish its manifest")
    verify = subprocess.run(command + ["shell",
        f"run-as {PACKAGE} sh -c 'cd {target} && sha256sum -c BUILD_MANIFEST.sha256 >/dev/null && echo O09_VERIFIED'"],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if verify.returncode or "O09_VERIFIED" not in verify.stdout:
        raise RuntimeError(f"app-private data verification failed: {verify.stderr.strip()}")
    previous, active = f"{base}/previous", f"{base}/active"
    run(command + ["shell", "run-as", PACKAGE, "rm", "-rf", previous])
    active_exists = subprocess.run(command + ["shell", "run-as", PACKAGE, "test", "-d", active]).returncode == 0
    if active_exists:
        run(command + ["shell", "run-as", PACKAGE, "mv", active, previous])
    run(command + ["shell", "run-as", PACKAGE, "mv", target, active])
    print(json.dumps({"ok": True, "serial": serial, "generation": generation,
                      "archive_sha256": archive_hash, "dbc": record["output"]["dbc_count"],
                      "maps": record["output"]["map_count"]}, indent=2))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client", type=Path, default=Path(r"C:\Vanilla wow 1.12.1"))
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--serial", help="stage into an already-installed debuggable APK")
    args = parser.parse_args()
    _, identity = scan_source(args.client)
    extractor = build_extractor()
    extract(args.client, extractor, args.force)
    record = write_manifest(identity, extractor)
    print(json.dumps({key: record[key] for key in ("schema", "client_id", "client_identity", "extractor")}, indent=2))
    print(json.dumps({key: record["output"][key] for key in ("dbc_count", "map_count", "total_bytes", "manifest_sha256")}, indent=2))
    if args.serial:
        stage(args.serial, record)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
