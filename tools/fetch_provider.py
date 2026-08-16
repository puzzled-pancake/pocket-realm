#!/usr/bin/env python3
"""Deterministically fetch + hash-verify a pinned prebuilt provider archive.

Reads the prebuilt-archive entries from schemas/sources.json, downloads the
artifact, recomputes its SHA-256, and refuses to install on hash mismatch.
Caches into native/.providers/<id>/<artifact> (the cache tools/check_sources.py
verifies against, with no network). Offline after the first successful fetch.

For the Wine archive specifically, also extracts it so check_wine_dtneeded.py
can read the ELF/PE closure. Idempotent: a cached + hash-matching archive is
not re-downloaded.

Usage:
  python3 tools/fetch_provider.py                       # fetch all prebuilt archives
  python3 tools/fetch_provider.py wine-kron4ek-11-14-vanilla-wow64
  python3 tools/fetch_provider.py --no-extract <id>      # cache the archive only
  python3 tools/fetch_provider.py --refresh <id>         # re-download even if cached
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

try:
    from tools import common
except ImportError:  # direct execution: python tools/<script>.py
    import common
SOURCES = ROOT / "schemas" / "sources.json"
CACHE = ROOT / "native" / ".providers"
# Extracted trees live next to the cache (gitignored). check_wine_dtneeded.py
# reads the extracted Wine tree to derive the real DT_NEEDED closure.
EXTRACT_ROOT = ROOT / "native" / ".providers-extracted"

CHUNK = 1 << 20  # 1 MiB


sha256_of = common.sha256_file
def download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"  downloading {url}")
    tmp = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(url, timeout=60) as r, tmp.open("wb") as f:  # noqa: S310 - pinned URL
        shutil.copyfileobj(r, f, length=CHUNK)
    tmp.replace(dest)


def fetch_one(entry: dict, *, refresh: bool, extract: bool) -> bool:
    eid = entry["id"]
    artifact = entry["artifact"]
    url = entry["artifact_url"]
    expected_hash = entry["artifact_sha256"]
    cached = CACHE / eid / artifact

    if cached.is_file() and not refresh:
        actual = sha256_of(cached)
        if actual == expected_hash:
            print(f"OK   {eid}: cached + hash matches ({actual[:16]})")
        else:
            print(f"DRIFT {eid}: cached hash {actual[:16]} != expected {expected_hash[:16]}; re-fetching")
            cached.unlink()
            return fetch_one(entry, refresh=refresh, extract=extract)

    if not cached.is_file():
        download(url, cached)
    actual = sha256_of(cached)
    if actual != expected_hash:
        print(f"FAIL {eid}: downloaded artifact hash {actual} != pinned {expected_hash}")
        print("     Refusing to install a hash-mismatched artifact. Update the pin after review.")
        cached.unlink(missing_ok=True)
        return False
    print(f"OK   {eid}: fetched + hash verified ({actual[:16]})")

    if extract:
        extract_archive(entry, cached)
    return True


def extract_archive(entry: dict, archive: Path) -> None:
    """Extract a prebuilt archive into native/.providers-extracted/<id>/.
    Currently only the Wine archive needs extraction (for check_wine_dtneeded)."""
    eid = entry["id"]
    target = EXTRACT_ROOT / eid
    if target.is_dir():
        # Idempotent: skip if already extracted. --refresh callers should delete manually.
        print(f"     (already extracted at {target}; delete to re-extract)")
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    print(f"     extracting -> {target}")
    # tarfile handles .tar.xz / .tar.gz transparently. filter='data' is the
    # Python 3.12+ safe-extraction default (rejects absolute paths, parent-refs,
    # links, device files) — appropriate for a pinned, hash-verified archive.
    with tarfile.open(archive) as tf:
        try:
            tf.extractall(target, filter="data")  # noqa: S202 - pinned provider archive
        except TypeError:
            # Python < 3.12 has no filter argument: apply the same "data"
            # rules by hand instead of extracting unsafely.
            safe = []
            for member in tf.getmembers():
                if member.isdev() or member.issym() or member.islnk():
                    continue  # no devices, no links
                parts = Path(member.name).parts
                if member.name.startswith(("/", *Path(member.name).drive,)) or ".." in parts:
                    raise RuntimeError(f"unsafe archive member: {member.name}")
                safe.append(member)
            tf.extractall(target, members=safe)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("id", nargs="?", help="provider id (default: all prebuilt-archive entries)")
    ap.add_argument("--no-extract", action="store_true", help="cache the archive only; skip extraction")
    ap.add_argument("--refresh", action="store_true", help="re-download even if cached")
    args = ap.parse_args()

    data = json.loads(SOURCES.read_text(encoding="utf-8"))
    entries = [s for s in data["sources"] if s.get("kind") == "prebuilt-archive"]
    if args.id:
        entries = [e for e in entries if e["id"] == args.id]
        if not entries:
            print(f"No prebuilt-archive entry with id={args.id!r}", file=sys.stderr)
            return 2

    if not entries:
        print("No prebuilt-archive entries to fetch.", file=sys.stderr)
        return 0

    extract = not args.no_extract
    ok = True
    for entry in entries:
        if not fetch_one(entry, refresh=args.refresh, extract=extract):
            ok = False
    if not ok:
        print("\nFetch FAILED (hash mismatch). No artifact installed.", file=sys.stderr)
        return 1
    print(f"\nFetched {len(entries)} provider archive(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
