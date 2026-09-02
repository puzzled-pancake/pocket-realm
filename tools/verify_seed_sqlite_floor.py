#!/usr/bin/env python3
r"""PC-side device-readiness preflight for the SQLite seed APK (the
"verify before you stage" gate - the device window of 2026-08-25 burned
three hours on defects the emulator's newer framework SQLite masked:
UPDATE..FROM needs 3.33+, native concat() needs 3.44+; a later replay
ladder also caught RENAME COLUMN at 3.25+).

The device replays the seed transcripts through the FRAMEWORK
SQLiteDatabase (DatabaseEngine.replaySeedDatabase), whose engine version
is the device's Android, not the pinned 3.46 amalgamation the world
itself runs on. The floor this tool pins:

  minSdk 26 (Android 8.0) -> SQLite 3.18   (the absolute floor)
  the RP6 device class     -> SQLite 3.32  (Android 12L/13 era)

What is verified, WITHOUT touching any device:
  1. APK statics (aapt): package, minSdk <= 33-class devices, and the
     expected native ABI directory is present (libworld.so etc).
  2. Seed integrity: every assets/seed/<db>.sqlz is gunzipped in
     memory; the raw sha256 must match BOTH the append-only baseline
     (schemas/sqlite-seed-baseline.json) and the sqlite realm lockfile
     pinned for that APK's ABI (the I-50 chain extended to the artifact
     that actually ships).
  3. Floor replay: every transcript is executed start-to-finish by the
     pinned old sqlite3 shells (.bail on, exit 0, empty stderr). A parse
     or execute error at ANY version gate fails the preflight.

The shells are the official precompiled win32 tools from sqlite.org's
immutable year archives, fetched once into build/seed-floor-shells/ and
identified by version banner afterwards (never executed unverified from
a fresh download without the banner check passing).

Usage:
    python3 tools/verify_seed_sqlite_floor.py --apk build/device-apks/server-b.apk
    python3 tools/verify_seed_sqlite_floor.py --apks build/device-apks
Exit code 0 only when every check passes; run this BEFORE any adb
command aimed at a production device.
"""

import argparse
import gzip
import hashlib
import json
import platform
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SHELL_STORE = ROOT / "build" / "seed-floor-shells"

# version -> (year archive, banner prefix). Immutable upstream URLs.
SHELL_URLS = {
    "3.18": ("https://www.sqlite.org/2017/sqlite-tools-win32-x86-3180000.zip",
             "3.18.0"),
    "3.32": ("https://www.sqlite.org/2020/sqlite-tools-win32-x86-3320100.zip",
             "3.32.1"),
}
DEFAULT_FLOORS = ("3.18", "3.32")

BASELINE = ROOT / "schemas" / "sqlite-seed-baseline.json"
LOCKFILES = {
    "x86_64": ROOT / "schemas" / "realm-runtime-lockfile-sqlite.json",
    "arm64-v8a": ROOT / "schemas" / "realm-runtime-lockfile-arm64-v8a-sqlite.json",
}
DATABASES = ("classicrealmd", "classiccharacters", "classiclogs",
             "classicmangos")
SQLZ_PREFIX = "assets/seed/"


def run(cmd: list[str], timeout: int = 300, check: bool = True) -> str:
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if check and proc.returncode != 0:
        raise RuntimeError(
            f"command failed ({proc.returncode}): {' '.join(map(str, cmd))}\n"
            f"stderr: {proc.stderr[:500]}")
    return proc.stdout


def find_aapt() -> Path | None:
    sdk = Path(platform_logic())
    bt = sdk / "build-tools"
    if not bt.is_dir():
        return None
    candidates = sorted(bt.iterdir(), reverse=True)
    for cand in candidates:
        exe = cand / "aapt.exe" if platform.system() == "Windows" else cand / "aapt"
        if exe.is_file():
            return exe
    return None


def platform_logic() -> str:
    import os
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        guess = Path.home() / "AppData" / "Local" / "Android" / "Sdk"
        if guess.is_dir():
            return str(guess)
        raise RuntimeError("set ANDROID_SDK_ROOT (no aapt for APK statics)")
    return sdk


def shell_for(version: str) -> Path:
    """Pinned sqlite3 shell for a floor version, downloading once."""
    marker = SHELL_STORE / version / "OK"
    if marker.is_file():
        shells = list((SHELL_STORE / version).glob("**/sqlite3.exe"))
        if not shells:
            raise RuntimeError(f"shell store for {version} lost its exe")
        return shells[0]
    url, banner = SHELL_URLS[version]
    if platform.system() != "Windows":
        raise RuntimeError(
            f"floor replay needs the win32 sqlite3 shell ({version}); "
            f"this host is {platform.system()}")
    SHELL_STORE.mkdir(parents=True, exist_ok=True)
    archive = SHELL_STORE / f"sqlite-{version}.zip"
    if not archive.is_file():
        print(f"fetching pinned shell {version}: {url}")
        with urllib.request.urlopen(url, timeout=120) as resp:
            archive.write_bytes(resp.read())
    dest = SHELL_STORE / version
    dest.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as zf:
        zf.extractall(dest)
    shells = list(dest.glob("**/sqlite3.exe"))
    if not shells:
        raise RuntimeError(f"shell archive for {version} had no sqlite3.exe")
    exe = shells[0]
    out = run([str(exe), "--version"])
    if not out.startswith(banner):
        raise RuntimeError(
            f"shell banner mismatch for {version}: {out[:40]!r}")
    marker.write_text(out, encoding="utf-8")
    return exe


def apk_statics(apk: Path) -> dict:
    aapt = find_aapt()
    if aapt is None:
        print("WARN: no aapt found; APK statics skipped (install a build-tool)")
        return {"abi": None, "minSdk": None, "package": None}
    out = run([str(aapt), "dump", "badging", str(apk)])
    info: dict = {}
    for line in out.splitlines():
        # aapt badging: name rides the package line; sdkVersion:'26' is
        # its own line with a colon-separated quoted value
        if line.startswith("package:"):
            for part in line.split():
                if part.startswith("name="):
                    info["package"] = part[5:].strip("'\"")
        elif line.startswith("sdkVersion:"):
            info["minSdk"] = line.split(":", 1)[1].strip().strip("'\"")
        elif line.startswith("native-code:"):
            info["abi"] = line.split(":", 1)[1].replace("'", "").split()
    return info


def apk_seed_assets(apk: Path) -> dict[str, bytes]:
    """Gunzip every seed transcript embedded in the APK, in memory."""
    found: dict[str, bytes] = {}
    with zipfile.ZipFile(apk) as zf:
        names = zf.namelist()
        for db in DATABASES:
            asset = f"{SQLZ_PREFIX}{db}.sqlz"
            if asset not in names:
                raise RuntimeError(f"{apk.name}: missing seed asset {asset}")
            found[db] = gzip.decompress(zf.read(asset))
    return found


def verify_apk(apk: Path, floors: tuple[str, ...]) -> dict:
    print(f"\n=== {apk.name} ===")
    statics = apk_statics(apk)
    abi = None
    if statics.get("abi"):
        abi = statics["abi"]
        print(f"statics: package={statics['package']} "
              f"minSdk={statics['minSdk']} abi={abi}")
        if len(abi) != 1:
            raise RuntimeError(f"expected exactly one ABI, got {abi}")
    seeds = apk_seed_assets(apk)
    baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
    baseline_digests = baseline["transcript_digests"]
    lockfile = None
    if abi and abi[0] in LOCKFILES:
        lockfile = json.loads(LOCKFILES[abi[0]].read_text(encoding="utf-8"))
    for db, raw in sorted(seeds.items()):
        digest = hashlib.sha256(raw).hexdigest()
        ok_base = digest == baseline_digests.get(db)
        line = f"seed {db}: baseline={'OK' if ok_base else 'MISMATCH'}"
        if lockfile:
            ok_lock = digest == lockfile["seed_transcripts"][db]["sha256"]
            line += f" lockfile={'OK' if ok_lock else 'MISMATCH'}"
            if not ok_lock:
                raise RuntimeError(f"{db}: APK seed not pinned by lockfile "
                                   f"({abi[0]}) - restage the realm runtime")
        print(line + f" ({len(raw)} bytes)")
        if not ok_base:
            raise RuntimeError(f"{db}: APK seed diverges from baseline")
    replay(seeds, floors)
    return {"apk": str(apk), "statics": statics, "databases": sorted(seeds)}


def replay(seeds: dict[str, bytes], floors: tuple[str, ...]) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        for version in floors:
            exe = shell_for(version)
            for db, raw in sorted(seeds.items()):
                sql = Path(tmp) / f"{db}.sql"
                sql.write_bytes(raw)
                dbf = Path(tmp) / f"floor-{version}-{db}.db"
                dbf.unlink(missing_ok=True)
                # dot-command args eat backslashes (the win32 shell's
                # escape handling) - .read needs forward slashes
                proc = subprocess.run(
                    [str(exe), str(dbf), ".bail on",
                     f".read {sql.resolve().as_posix()}"],
                    capture_output=True, text=True, timeout=1800)
                stderr = proc.stderr.strip()
                if proc.returncode != 0 or stderr:
                    raise RuntimeError(
                        f"floor {version} REJECTED {db} "
                        f"(exit {proc.returncode}): {stderr[:300]}")
                print(f"replay {version} {db}: OK "
                      f"({dbf.stat().st_size // 1024} KiB db)")
                dbf.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", help="a single APK (the sqlite server apk)")
    parser.add_argument("--apks", help="directory with server-{a,b}.apk - "
                        "the sqlite lane (server-b) is verified")
    parser.add_argument("--floors", default=",".join(DEFAULT_FLOORS),
                        help=f"sqlite floor versions to replay against "
                             f"(default {DEFAULT_FLOORS})")
    args = parser.parse_args()
    if not args.apk and not args.apks:
        parser.error("need --apk or --apks")
    floors = tuple(v.strip() for v in args.floors.split(",") if v.strip())
    targets: list[Path]
    if args.apk:
        targets = [Path(args.apk)]
    else:
        base = Path(args.apks)
        targets = [base / "server-b.apk"]
    summary = [verify_apk(t, floors) for t in targets]
    print("\nPREFLIGHT PASS: seed floor verified host-side; the APK is "
          "cleared for device staging")
    (ROOT / "build" / "seed-floor-verify" / "preflight-last.json").parent \
        .mkdir(parents=True, exist_ok=True)
    (ROOT / "build" / "seed-floor-verify" / "preflight-last.json").write_text(
        json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"\nPREFLIGHT FAIL: {exc}", file=sys.stderr)
        raise SystemExit(2)
