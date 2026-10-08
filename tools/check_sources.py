#!/usr/bin/env python3
"""Verify pinned upstream sources match schemas/sources.json.

Checks every entry by kind:
  - git-submodule       : working-tree commit must equal the recorded commit.
  - prebuilt-archive    : the cached artifact must exist and its SHA-256 match.
  - source-built-archive: the recorded upstream commit is present in the entry
                          (provenance only; the built closure is hashed into the
                          per-package lockfile, checked separately).
  - source-built-static-library: a full upstream commit and an existing local
                          build recipe identify the statically linked source.
  - vendored-source     : the recorded upstream commit is present; the vendored
                          copy is checked separately by the build that uses it.
  - host-toolchain      : the recorded version + source-tarball name are present
                          (the host toolchain's availability is checked by the
                          build script that consumes it).
  - verified-package-converted: an immutable package/index hash, build script,
                                and generated lockfile are recorded.

Per-flavor git-submodule entries (S1) carry a `status` field with a
deliberately asymmetric contract — loud about drift, patient about absence:

  - status "unresolved"  : no commit pin yet (the tbc/wotlk cores and DBs
                           until their forks/pins land). A missing checkout is
                           PENDING (expected-loud, non-fatal); a tree PRESENT
                           at the path is a failure — an unpinned tree is a
                           provenance lie.
  - status "registered"  : commit pinned (the tbc/wotlk bots pins per
                           correction C4) but the checkout may still land with
                           the fork-registration wave. A missing checkout is
                           PENDING; a present checkout is checked strictly
                           (wrong commit = DRIFT, fatal).
  - no status (or "active", the vanilla lane) : the original strict behavior —
                           missing checkout and drift are both fatal.

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

try:
    from tools import common
except ImportError:  # direct execution: python tools/<script>.py
    import common
SOURCES = ROOT / "schemas" / "sources.json"
# Cache root for prebuilt archives. Populated by tools/fetch_provider.py.
PROVIDER_CACHE = ROOT / "native" / ".providers"


def submodule_commit(path: Path) -> str:
    """Return the exact commit a submodule is checked out at.

    Round-3 R3-6: the +/- prefix of `git submodule status` is the DRIFT
    SIGNAL (+ checkout != recorded gitlink, - gitlink not initialized).
    The old lstrip("+-") discarded it before the == pin comparison, so a
    checkout ADVANCED past the recorded gitlink passed as the pin. The
    marker now fails loud; only a clean checkout (leading space) returns
    its sha."""
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
    token = line.split()[0]
    if token.startswith("+"):
        raise RuntimeError(
            f"{path}: checkout {token.lstrip('+')[:12]} is NOT the recorded "
            "gitlink — the superproject's committed provenance has drifted "
            "from the working tree; commit the new gitlink or check out the "
            "recorded commit before verifying sources"
        )
    if token.startswith("-"):
        raise RuntimeError(
            f"{path}: the recorded gitlink is not initialized (git submodule "
            "update --init) — an uninitialized pin cannot attest anything"
        )
    return token


sha256_of = common.sha256_file
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
    """Validate either a Git source pin or a release-tarball source pin.

    Most entries use a full upstream commit. Some projects (notably talloc)
    publish a release tarball instead; for those, URL + complete SHA-256 is the
    immutable source identity. Built outputs are verified by their consuming
    recipe/lockfile.
    """
    commit = entry.get("commit")
    if commit:
        if len(commit) < 40:
            return False, f"commit appears abbreviated ({commit})"
        return True, commit[:16]
    source_url = entry.get("source_tarball_url")
    source_hash = entry.get("source_tarball_sha256")
    if not source_url or not source_hash:
        return False, "missing commit or source_tarball_url/source_tarball_sha256"
    if len(source_hash) != 64 or any(ch not in "0123456789abcdefABCDEF" for ch in source_hash):
        return False, "source_tarball_sha256 is not a complete SHA-256"
    return True, f"tarball {source_hash[:16]}"


def check_vendored_source(entry: dict) -> tuple[bool, str]:
    """A vendored-source pin is its upstream commit (full). The vendored copy
    is checked by the build that consumes it; here we confirm the pin fields."""
    commit = entry.get("commit")
    if not commit:
        return False, "missing commit"
    if len(commit) < 40:
        return False, f"commit appears abbreviated ({commit})"
    return True, commit[:16]


def check_source_built_static_library(entry: dict) -> tuple[bool, str]:
    """Validate the immutable source pin and the checked-in build recipe.

    The consuming build records the resulting archive and final ELF hashes in
    its lockfile. This gate ensures that the source correspondence needed to
    reproduce that static library cannot silently lose either half.
    """
    commit = entry.get("commit")
    build_script = entry.get("build_script")
    if not commit or len(commit) != 40 or any(
        ch not in "0123456789abcdefABCDEF" for ch in commit
    ):
        return False, "commit is not a complete 40-character Git object ID"
    if not build_script:
        return False, "missing build_script"
    recipe = ROOT / build_script
    if not recipe.is_file():
        return False, f"build_script not found: {build_script}"
    return True, f"{commit[:16]} via {build_script}"


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


def check_verified_package(entry: dict) -> tuple[bool, str]:
    """Validate a package conversion without requiring network/cache state."""
    required = (
        "package", "package_url", "package_sha256", "package_index_url",
        "package_index_sha256", "build_script", "runtime_lockfile", "version",
    )
    missing = [field for field in required if not entry.get(field)]
    if missing:
        return False, f"missing fields: {', '.join(missing)}"
    for field in ("package_sha256", "package_index_sha256"):
        value = entry[field]
        if len(value) != 64 or any(ch not in "0123456789abcdefABCDEF" for ch in value):
            return False, f"{field} is not a complete SHA-256"
    script = ROOT / entry["build_script"]
    lockfile = ROOT / entry["runtime_lockfile"]
    if not script.is_file():
        return False, f"build_script not found: {entry['build_script']}"
    if not lockfile.is_file():
        return False, f"runtime_lockfile not found: {entry['runtime_lockfile']}"
    return True, f"{entry['version']} {entry['package_sha256'][:16]}"


KIND_CHECKERS = {
    "git-submodule": None,  # handled by the original submodule path
    "prebuilt-archive": check_prebuilt_archive,
    "source-built-archive": check_source_built_archive,
    "source-built-static-library": check_source_built_static_library,
    "vendored-source": check_vendored_source,
    # Prebuilt distributed from a source-pinned upstream: the commit pin is the
    # identity; built outputs are verified by the consuming recipe/lockfile.
    "vendored-prebuilt-from-source": check_source_built_archive,
    "host-toolchain": check_host_toolchain,
    "verified-package-converted": check_verified_package,
}


def main() -> int:
    data = json.loads(SOURCES.read_text(encoding="utf-8"))
    entries = data["sources"]
    if not entries:
        print("No sources to check.", file=sys.stderr)
        return 0

    drift = False
    unknown_kinds = False
    pending = 0
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
            status = entry.get("status")
            if status == "unresolved":
                # No pin yet (per-flavor core/db entries pre-registration).
                if path.exists():
                    print(
                        f"FAIL {eid}: tree present at {path} but the entry is "
                        "status:unresolved — record its commit pin or remove the tree "
                        "(an unpinned tree is a provenance lie)"
                    )
                    drift = True
                else:
                    pending += 1
                    print(f"PEND {eid}: pin unregistered ({path} absent) — expected until "
                          "its wave registers the fork/pin")
                continue
            if status == "registered":
                # Pin recorded, checkout lands with the registration wave.
                if not path.exists():
                    pending += 1
                    print(f"PEND {eid}: pin {str(expected)[:12]} registered, checkout "
                          f"pending ({path} absent)")
                    continue
                actual = submodule_commit(path)
                if actual == expected:
                    print(f"OK   {eid:<36} {actual[:12]}")
                else:
                    print(f"DRIFT {eid}: expected {expected[:12]} got {actual[:12]}")
                    drift = True
                continue
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
    if pending:
        print(f"\nAll active pins verified ({pending} per-flavor pin(s) pending registration — expected-loud).")
        return 0
    print("\nAll pinned sources verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
