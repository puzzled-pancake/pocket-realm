#!/usr/bin/env python3
"""Repository hygiene gate.

Fails (exit 1) when tracked content regresses into known problem classes:
forbidden build-output paths, committed oversized blobs outside the allowlist,
or personal identifiers (loaded from a gitignored local markers file)
reappearing in tracked text.

Safe in CI or as a pre-commit gate (same contract as tools/check_sources.py).
Personal-identifier scanning reads tools/pii_markers.local.json when present
(copy it from pii_markers.local.json.example and fill in your own values).
When the markers file is absent — CI, fresh clones — that specific scan is
SKIPPED with a loud note; all other checks still run.
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
MARKERS_FILE = ROOT / "tools" / "pii_markers.local.json"

FORBIDDEN_PATTERNS = [
    "native/.tmp-*",
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

# Scratch debris never belongs at the repo root, whatever the extension case.
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


def load_markers() -> tuple[list[str], list[str], bool]:
    """Load personal-identifier markers from the gitignored local file.

    Returns (error_markers, warn_markers, present). Absent file => empty
    lists and present=False, which downgrades ONLY the identifier scan.
    """
    try:
        data = json.loads(MARKERS_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return [], [], False
    errors = [m for m in data.get("error_markers", []) if isinstance(m, str) and m]
    warns = [m for m in data.get("warn_markers", []) if isinstance(m, str) and m]
    return errors, warns, True


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


def check_pii(
    files: list[str],
    errors: list[str],
    warnings: list[str],
    error_markers: list[str],
    warn_markers: list[str],
    markers_present: bool,
) -> None:
    if not markers_present:
        print(
            "note: personal-identifier scan SKIPPED — tools/pii_markers.local.json "
            "not present (copy it from pii_markers.local.json.example to enable)"
        )
        return
    for path in files:
        norm = path.replace("\\", "/")
        full = ROOT / path
        if is_binary(full):
            continue
        try:
            text = full.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        lowered_text = text.lower()
        for marker in error_markers:
            if marker.lower() in lowered_text:
                errors.append(f"personal identifier {marker!r} present in {norm}")
        for marker in warn_markers:
            if marker.lower() in lowered_text:
                warnings.append(f"personal-path literal {marker!r} present in {norm}")


def main() -> int:
    # Strict is the default: WARN-tier findings fail. --no-strict downgrades
    # personal-path literals to warnings for history work.
    strict = "--no-strict" not in sys.argv
    errors: list[str] = []
    warnings: list[str] = []

    error_markers, warn_markers, markers_present = load_markers()

    files = tracked_files()
    check_forbidden_paths(files, errors)
    check_blob_sizes(files, errors)
    check_pii(
        files, errors, warnings, error_markers, warn_markers, markers_present
    )

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
    print("note: --no-strict downgrades personal-path literals to warnings")
    print(
        f"check_repo: OK ({len(files)} tracked files, "
        f"0 errors, {len(set(warnings))} warnings)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
