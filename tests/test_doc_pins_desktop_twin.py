"""Doc-drift pins for the desktop-twin slice (R1-2/R2-1/R3-1, round 1).

README's "Pocket Realm for Windows" section named a SQLite provider and
schemas/realm-runtime-lockfile-sqlite-win.json as the Windows lane, while
the SHIPPED desktop lane is the MariaDB one per D19: Settings defaults to
DatabaseProvider.MARIADB, the packaging fence reads
schemas/realm-runtime-lockfile-mariadb-win.json, BuildConfig pins that
lane ("the MARIADB lane the desktop image actually ships"), and
packageApp stages the .build-win-x86_64-mariadb runtime + the mariadb
provider closure. A maintainer following the old README verified the
wrong lane's pins. These pins hold the prose to the owning constants.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
README = ROOT / "README.md"


def _windows_section() -> str:
    """The README's 'Pocket Realm for Windows' section (through the next
    same-level heading)."""
    text = README.read_text(encoding="utf-8")
    start = text.index("## Pocket Realm for Windows")
    tail = text[start:]
    ends = [m.start() for m in re.finditer(r"^## ", tail[1:], re.MULTILINE)]
    return tail[: ends[0] + 1] if ends else tail


def test_windows_section_names_the_shipped_mariadb_lane():
    section = _windows_section()
    assert re.search(r"MariaDB provider", section), (
        "the README Windows section must name the MariaDB provider as the "
        "shipped lane (the D19 flip; BuildConfig + NativeRuntimeFence + "
        "packageApp all stage mariadb-win)")
    assert "realm-runtime-lockfile-mariadb-win.json" in section, (
        "the Windows provenance lockfile the README cites must be the "
        "mariadb-win lane the image actually ships")


def test_windows_section_no_longer_claims_the_sqlite_provider_ships():
    section = _windows_section()
    assert "SQLite provider" not in section, (
        "the README must not claim the desktop realm ships a SQLite "
        "provider — Settings.DatabaseProvider ships MARIADB and the "
        "sqlite lane is dev/compat only")
    assert "realm-runtime-lockfile-sqlite-win.json" not in section, (
        "the README must not cite the sqlite-win lockfile as the Windows "
        "lane's provenance lockfile")


def test_the_cited_lane_is_the_one_buildconfig_and_the_fence_own():
    """The doc must agree with the owning constants: BuildConfig's pinned
    lockfile and NativeRuntimeFence's LOCKFILE_RELATIVE are the shipped
    lane."""
    build_config = (
        ROOT / "desktop/src/main/kotlin/com/pocketrealm/BuildConfig.kt"
    ).read_text(encoding="utf-8")
    fence = (
        ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/NativeRuntimeFence.kt"
    ).read_text(encoding="utf-8")
    assert "realm-runtime-lockfile-mariadb-win.json" in build_config
    assert "realm-runtime-lockfile-mariadb-win.json" in fence
    # the doc names exactly that lane
    assert "realm-runtime-lockfile-mariadb-win.json" in _windows_section()
