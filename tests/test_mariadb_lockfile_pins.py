"""Tripwire: the mariadb runtime lockfile must only move deliberately.

The termux gpkg index is live upstream metadata. Twice during the 2026-08
Phase 4 window a full staging run silently moved package versions (bash,
libicu, liblzma, readline) by trusting it. stage_mariadb_runtime now refuses
an unpinned index without --refresh; this test pins the four reviewed
package versions so an accidental refresh cannot ride a commit unnoticed.
Update these pins only alongside a reviewed --refresh staging change.
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCKFILE = ROOT / "schemas" / "mariadb-runtime-lockfile.json"

REVIEWED_PACKAGES = {
    "bash-glibc-5.2.37-0-x86_64.pkg.tar.xz",
    "libicu-glibc-76.1-0-x86_64.pkg.tar.xz",
    "liblzma-glibc-5.6.4-0-x86_64.pkg.tar.xz",
    "readline-glibc-8.2.13-0-x86_64.pkg.tar.xz",
}


def test_lockfile_pins_the_reviewed_package_versions() -> None:
    lock = json.loads(LOCKFILE.read_text(encoding="utf-8"))
    filenames = {entry["filename"] for entry in lock["packages"].values()}
    drifted = REVIEWED_PACKAGES - filenames
    assert not drifted, (
        "mariadb-runtime lockfile no longer pins the reviewed package versions "
        f"(missing: {sorted(drifted)}). If the index refresh was reviewed and "
        "deliberate, update REVIEWED_PACKAGES in this test in the same change."
    )


def test_lockfile_metadata_hash_is_pinned() -> None:
    lock = json.loads(LOCKFILE.read_text(encoding="utf-8"))
    metadata_sha256 = lock.get("metadata_sha256")
    assert isinstance(metadata_sha256, str) and len(metadata_sha256) == 64, (
        "metadata_sha256 must stay a full sha-256: stage_mariadb_runtime's "
        "index pin depends on it"
    )
