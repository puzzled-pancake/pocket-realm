"""Pin the desktop SQLite execution seam contract (Windows port Phase 3).

pocket_sqlite.dll is the desktop twin of android.database.sqlite for the
DatabaseSqliteControlPlane legs. The pins that must never drift:

- it compiles the repo-pinned amalgamation with EXACTLY the production
  define set every other fixture uses (single-sourced from
  tests/test_sqlite_seeding.py PRODUCTION_DEFINES);
- all text crosses the JNI boundary as UTF-16 (non-ASCII Windows
  usernames in %LOCALAPPDATA% paths, CESU-8 corruption avoidance), and
  the file encoding is forced back to UTF-8 so desktop database files
  are byte-identical to the Android lane's;
- the JNI export gate derives from the Kotlin shim (same derivation the
  realm lane uses);
- on Windows, the driver actually builds the DLL.
"""

import importlib.util
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
DRIVER = ROOT / "tools" / "build_win_sqlite_seam.py"
SHIM = ROOT / "desktop/src/main/kotlin/com/pocketrealm/database/DesktopSqlite.kt"
JNI_SRC = ROOT / "native/desktop-sqlite/src/desktop_sqlite_jni.c"
CMAKELISTS = ROOT / "native/desktop-sqlite/CMakeLists.txt"
SEEDING_TESTS = ROOT / "tests" / "test_sqlite_seeding.py"


def _production_defines() -> list[str]:
    spec = importlib.util.spec_from_file_location("pocket_sqlite_seeding", SEEDING_TESTS)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return list(module.PRODUCTION_DEFINES)


def test_lane_files_exist():
    for path in (DRIVER, SHIM, JNI_SRC, CMAKELISTS):
        assert path.is_file(), f"missing seam lane file: {path}"


def test_compiles_the_production_define_set():
    defines = _production_defines()
    assert defines, "production define set is empty — the pin is broken"
    cmake = CMAKELISTS.read_text(encoding="utf-8")
    for define in defines:
        token = define[2:].split("=")[0]
        assert token in cmake, (
            f"native/desktop-sqlite/CMakeLists.txt must compile with the production "
            f"define set; missing {token}"
        )


def test_all_text_crosses_the_boundary_as_utf16():
    source = JNI_SRC.read_text(encoding="utf-8")
    for entry in ("sqlite3_open16", "sqlite3_prepare16_v2",
                  "sqlite3_column_text16", "sqlite3_errmsg16"):
        assert entry in source, (
            f"the seam must use the UTF-16 entry point {entry} "
            "(non-ASCII Windows usernames; JNI modified-UTF-8 would be CESU-8)"
        )
    # ...and new database FILES stay UTF-8, matching the Android lane.
    assert "PRAGMA encoding = 'UTF-8'" in source


def test_export_gate_derives_from_the_kotlin_shim():
    driver = DRIVER.read_text(encoding="utf-8")
    assert "jni_symbols_from_shim" in driver, (
        "the export gate must derive its symbol list from the Kotlin shim "
        "(same derivation as the realm lane)"
    )
    assert str(SHIM).replace("\\", "/").endswith("DesktopSqlite.kt")


def test_msvc_lane_builds() -> None:
    if sys.platform != "win32":
        pytest.skip("windows-only compiler lane")
    amalgamation = ROOT / "native/.deps/src/sqlite/sqlite-amalgamation-3460100"
    if not (amalgamation / "sqlite3.c").is_file():
        pytest.skip("amalgamation not staged (run tools/stage_sqlite_amalgamation.py)")
    result = subprocess.run(
        [sys.executable, str(DRIVER)],
        capture_output=True, text=True, timeout=600, cwd=str(ROOT))
    assert result.returncode == 0, result.stdout + result.stderr[-2000:]
    assert "JNI exports verified" in result.stdout
