#!/usr/bin/env python3
"""Verify pinned upstream sources match schemas/sources.json.

Checks every entry by kind:
  - git-submodule       : working-tree commit must equal the recorded commit.
  - prebuilt-archive    : the cached artifact must exist and its SHA-256 match.
  - source-built-archive: the recorded upstream commit is present in the entry
                          (provenance only; the built closure is hashed into the
                          per-package lockfile, checked separately).
  - vendored-source     : the recorded upstream commit is present; the vendored
                          copy is checked separately by the build that uses it.
  - host-toolchain      : the recorded version + source-tarball name are present
                          (the host toolchain's availability is checked by the
                          build script that consumes it).

No network access in CI. prebuilt-archive artifacts are expected to be cached
locally (populated by tools/fetch_provider.py); a missing cache entry is
reported but does not require network here.

Exits non-zero on any drift, so this is safe to run in CI or as a pre-commit
gate. Run from the repository root.

    python3 tools/check_sources.py
"""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "schemas" / "sources.json"
# Cache root for prebuilt archives. Populated by tools/fetch_provider.py.
PROVIDER_CACHE = ROOT / "native" / ".providers"


def submodule_commit(path: Path) -> str:
    """Return the exact commit a submodule is checked out at."""
    result = subprocess.run(
        ["git", "-C", str(ROOT), "submodule", "status", str(path)],
        capture_output=True,
        text=True,
        check=True,
    )
    # Output line: " <sha> <path> (<desc>)" — leading char is space or +/-.
    line = result.stdout.strip()
    if not line:
        raise RuntimeError(f"no submodule recorded at {path}")
    return line.split()[0].lstrip("+-")


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def check_prebuilt_archive(entry: dict) -> tuple[bool, str]:
    """Verify a cached prebuilt archive exists and its SHA-256 matches.

    Returns (ok, detail). Missing cache is reported distinctly from a hash
    mismatch; neither fails closed (no network here).
    """
    artifact = entry.get("artifact")
    expected_hash = entry.get("artifact_sha256")
    if not artifact or not expected_hash:
        return False, "missing artifact/artifact_sha256 fields"
    # Cache layout: native/.providers/<id>/<artifact>
    cached = PROVIDER_CACHE / entry["id"] / artifact
    if not cached.is_file():
        return False, f"not cached (run tools/fetch_provider.py {entry['id']})"
    actual = sha256_of(cached)
    if actual != expected_hash:
        return False, f"hash drift: expected {expected_hash[:16]} got {actual[:16]}"
    return True, f"{actual[:16]}"


def check_source_built_archive(entry: dict) -> tuple[bool, str]:
    """A source-built archive's pin is its upstream commit; the built outputs
    are hashed into the per-package lockfile (schemas/wine-runtime-lockfile.json),
    verified separately. Here we only confirm the commit field is present and
    complete (no abbreviation)."""
    commit = entry.get("commit")
    if not commit:
        return False, "missing commit"
    if len(commit) < 40:
        return False, f"commit appears abbreviated ({commit})"
    return True, commit[:16]


def check_vendored_source(entry: dict) -> tuple[bool, str]:
    """A vendored-source pin is its upstream commit (full). The vendored copy
    is checked by the build that consumes it; here we confirm the pin fields."""
    commit = entry.get("commit")
    if not commit:
        return False, "missing commit"
    if len(commit) < 40:
        return False, f"commit appears abbreviated ({commit})"
    return True, commit[:16]


def check_host_toolchain(entry: dict) -> tuple[bool, str]:
    """A host-toolchain pin is its package + version + source-tarball name +
    package SHA-256. Host availability is checked by the consuming build."""
    pkg = entry.get("package")
    ver = entry.get("version")
    tarball = entry.get("source_tarball")
    pkg_hash = entry.get("package_sha256")
    missing = [n for n, v in (("package", pkg), ("version", ver),
                              ("source_tarball", tarball), ("package_sha256", pkg_hash)) if not v]
    if missing:
        return False, f"missing fields: {', '.join(missing)}"
    return True, f"{pkg} {ver}"


KIND_CHECKERS = {
    "git-submodule": None,  # handled by the original submodule path
    "prebuilt-archive": check_prebuilt_archive,
    "source-built-archive": check_source_built_archive,
    "vendored-source": check_vendored_source,
    "host-toolchain": check_host_toolchain,
}


def main() -> int:
    data = json.loads(SOURCES.read_text(encoding="utf-8"))
    entries = data["sources"]
    if not entries:
        print("No sources to check.", file=sys.stderr)
        return 0

    drift = False
    unknown_kinds = False
    for entry in entries:
        kind = entry.get("kind")
        eid = entry["id"]
        if kind not in KIND_CHECKERS:
            print(f"UNKNOWN_KIND {eid}: kind={kind!r} (no checker)")
            drift = True
            unknown_kinds = True
            continue

        if kind == "git-submodule":
            path = ROOT / entry["path"]
            expected = entry["commit"]
            if not path.exists():
                print(f"MISSING {eid}: {path} not checked out")
                drift = True
                continue
            actual = submodule_commit(path)
            if actual == expected:
                print(f"OK   {eid:<36} {actual[:12]}")
            else:
                print(f"DRIFT {eid}: expected {expected[:12]} got {actual[:12]}")
                drift = True
            continue

        # Non-submodule kinds use a checker returning (ok, detail).
        checker = KIND_CHECKERS[kind]
        ok, detail = checker(entry)
        status = "OK  " if ok else "FAIL"
        print(f"{status} {eid:<36} {kind:<22} {detail}")
        if not ok:
            drift = True

    if drift:
        if unknown_kinds:
            print("\nUnknown source kind(s) — add a checker to tools/check_sources.py.", file=sys.stderr)
        print("\nSource pin check FAILED. Update schemas/sources.json after a review.", file=sys.stderr)
        return 1
    print("\nAll pinned sources verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
