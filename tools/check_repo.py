#!/usr/bin/env python3
"""Repository hygiene gate (de-vibe plan Phase 3).

Fails (exit 1) when tracked content regresses into the problems the cleanup
removed: agent-debris paths, committed build output, oversized blobs outside
the allowlist, or scrubbed personal identifiers reappearing in tracked text.

Safe in CI or as a pre-commit gate (same contract as tools/check_sources.py).
`--strict` also fails on WARN-level findings (MSYS2 / user-home path literals
that Phase 4 removes; flip the default once those are gone).
"""
from __future__ import annotations

import fnmatch
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SUBMODULE_PREFIXES = ("native/cmangos", "native/playerbots", "native/classic-db")

FORBIDDEN_PATTERNS = [
    ".codex-*",
    "build-authority/*",
    "native/.tmp-*",
    "GL calls testing/*",
    "CMakeFiles/*",
    "*/CMakeCache.txt",
    "CMakeCache.txt",
    "*/CMakeConfigureLog.yaml",
    "CMakeConfigureLog.yaml",
    "*.tlog",
    "*.pdb",
    "*.ilk",
    "*.lastbuildstate",
    "*.recipe",
]

# Agent debris never belongs at the repo root, whatever the extension case.
ROOT_FORBIDDEN_SUFFIXES = (".png", ".jpg", ".jpeg", ".exe")

# Tracked blobs larger than this are errors outside the allowlist prefixes.
MAX_BLOB_BYTES = 1024 * 1024
BLOB_ALLOWLIST_PREFIXES = (
    "docs/",
    "native/xserver-winlator/",
    "runtime/xserver-winlator/",
    "tests/avd/",
    "android/app/src/main/assets/",
    "schemas/",
)

# Scrubbed personal identifiers (device serials incl. the second device found in
# Phase 1 verification, LAN IP, old character name). ERROR on sight.
PII_ERROR_MARKERS = (
    "4a8069ae",
    "2B031JEGR",
    "CaRp5n",
    "192.168.1.241",
    "Lolpp",
)

# Known-hardcoded personal toolchain paths. WARN until Phase 4 removes them,
# then promote to PII_ERROR_MARKERS and make --strict the default.
PII_WARN_MARKERS = (
    r"Users\David",
    "G:/msys64",
    r"G:\msys64",
)

BINARY_SUFFIXES = {
    ".png", ".jpg", ".jpeg", ".gif", ".ico", ".pdf", ".docx", ".zip", ".7z",
    ".exe", ".dll", ".so", ".obj", ".o", ".a", ".bin", ".apk", ".mpq", ".tzst",
    ".tar", ".gz", ".xz", ".zst", ".jar", ".class", ".keystore", ".p12",
}


def tracked_files() -> list[str]:
    out = subprocess.run(
        ["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, check=True
    ).stdout.decode("utf-8", "replace")
    return [f for f in out.split("\0") if f and not f.startswith(SUBMODULE_PREFIXES)]


def is_binary(path: Path) -> bool:
    if path.suffix.lower() in BINARY_SUFFIXES:
        return True
    try:
        with path.open("rb") as handle:
            return b"\x00" in handle.read(8192)
    except OSError:
        return True


def check_forbidden_paths(files: list[str], errors: list[str]) -> None:
    for path in files:
        norm = path.replace("\\", "/")
        lowered = norm.lower()
        if "/" not in norm and lowered.endswith(ROOT_FORBIDDEN_SUFFIXES):
            errors.append(f"forbidden root-level artifact {norm!r} (root images/exe ban)")
            continue
        for pattern in FORBIDDEN_PATTERNS:
            if fnmatch.fnmatch(lowered, pattern.lower()) or fnmatch.fnmatch(
                lowered, ("**/" + pattern).lower()
            ):
                errors.append(f"forbidden tracked path {norm!r} (pattern {pattern})")
                break


def check_blob_sizes(files: list[str], errors: list[str]) -> None:
    for path in files:
        norm = path.replace("\\", "/")
        if norm.startswith(BLOB_ALLOWLIST_PREFIXES):
            continue
        try:
            size = os.lstat(ROOT / path).st_size
        except OSError:
            continue
        if size > MAX_BLOB_BYTES:
            errors.append(
                f"tracked blob {norm} is {size} bytes (> {MAX_BLOB_BYTES}); "
                f"vendored/report artifacts belong under the allowlist roots"
            )


SELF = "tools/check_repo.py"  # hosts the marker lists; must not flag itself


def check_pii(files: list[str], errors: list[str], warnings: list[str]) -> None:
    for path in files:
        norm = path.replace("\\", "/")
        if norm == SELF:
            continue
        full = ROOT / path
        if is_binary(full):
            continue
        try:
            text = full.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        lowered_text = text.lower()
        for marker in PII_ERROR_MARKERS:
            if marker.lower() in lowered_text:
                errors.append(f"scrubbed identifier {marker!r} present in {norm}")
        # docs/devibe/** quotes the pre-cleanup findings as history; its WARN
        # mentions are records, not live hardcoding.
        if norm.startswith("docs/devibe/"):
            continue
        for marker in PII_WARN_MARKERS:
            if marker.lower() in lowered_text:
                warnings.append(f"personal-path literal {marker!r} present in {norm}")


def check_features(errors: list[str]) -> None:
    features = ROOT / "FEATURES.json"
    try:
        data = json.loads(features.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        errors.append(f"FEATURES.json unreadable: {exc}")
        return
    status_values = set(data.get("status_values", []))
    if not status_values:
        errors.append("FEATURES.json: missing status_values")
        return
    active = 0
    for feature in data.get("features", []):
        fid = feature.get("id", "<missing>")
        status = feature.get("status")
        if status not in status_values:
            errors.append(f"FEATURES.json: {fid} status {status!r} not in enum")
        if status == "active":
            active += 1
    if active > 1:
        errors.append(f"FEATURES.json: {active} active features (expected at most 1)")


def main() -> int:
    strict = "--strict" in sys.argv
    errors: list[str] = []
    warnings: list[str] = []

    files = tracked_files()
    check_forbidden_paths(files, errors)
    check_blob_sizes(files, errors)
    check_pii(files, errors, warnings)
    check_features(errors)

    for warning in sorted(set(warnings)):
        print(f"WARN {warning}")
    if errors:
        for error in sorted(set(errors)):
            print(f"ERROR {error}")
        print(f"check_repo: FAILED ({len(set(errors))} errors, {len(set(warnings))} warnings)")
        return 1
    if strict and warnings:
        print(f"check_repo: FAILED in strict mode ({len(set(warnings))} warnings)")
        return 1
    print(
        f"check_repo: OK ({len(files)} tracked files, "
        f"0 errors, {len(set(warnings))} warnings)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
