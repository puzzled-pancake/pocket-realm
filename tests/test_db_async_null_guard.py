"""Tripwire: every async DB enqueue site must route through the null guard.

HaltDelayThread() nulls m_threadBody while m_allowAsyncTransactions stays
sticky-true across POCKET_EMBEDDED restart cycles: the
production restart path re-enters session 2 with CharacterDatabase async-on
against freshly-halted thread state, so an unguarded enqueue after a halt is
a null dereference. The build driver overlays route all twelve sites
(Database.cpp x3, DatabaseImpl.h x7 Delay + x2 holder->Execute) through
SafeDelayOperation/SafeDelayQueryHolder; this test proves the overlay pair
applies cleanly to the pinned pristine sources, produces a fully guarded
tree, and restores byte-identically.
"""
from __future__ import annotations

import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import build_o09_realm_runtime as driver  # noqa: E402

PRISTINE = ROOT / "native" / "cmangos" / "src" / "shared" / "Database"
SOURCES = ("Database.h", "Database.cpp", "DatabaseImpl.h")


def _submodule_available() -> bool:
    return (ROOT / "native" / "cmangos" / ".git").exists()


def _submodule_clean() -> bool:
    """The overlay round-trip proof must run against pristine bytes.

    Mid-build (or after a crashed run) the submodule working tree carries the
    overlays; replace_anchor's already-patched path would then make apply a
    no-op and the restore test would compare patched-vs-patched.
    """
    import subprocess

    cmangos = ROOT / "native" / "cmangos"
    diff = subprocess.run(["git", "diff", "--quiet"], cwd=cmangos)
    cached = subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=cmangos)
    untracked = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard"], cwd=cmangos,
        capture_output=True, text=True)
    return diff.returncode == 0 and cached.returncode == 0 and not untracked.stdout.strip()


def test_driver_declares_the_guard_overlays() -> None:
    assert "SafeDelayOperation" in driver.DB_GUARD_HELPERS_ANDROID
    assert "SafeDelayQueryHolder" in driver.DB_GUARD_HELPERS_ANDROID
    assert driver.DB_GUARD_ASYNC_QUERY_UPSTREAM == "m_threadBody->Delay(new SqlQuery("
    assert driver.DB_GUARD_HOLDER_UPSTREAM.startswith("holder->Execute(")
    # The fail-loud backend overlay must refuse the no-op DO_* cache defines.
    assert "DEFINED CACHE{DO_MYSQL}" in driver.BACKEND_SELECT_ANDROID
    assert "message(FATAL_ERROR" in driver.BACKEND_SELECT_ANDROID


def test_driver_registers_the_overlays_in_provenance() -> None:
    ids = {entry["id"] for entry in driver.CMANGOS_OVERLAYS}
    assert "db-async-null-guard" in ids
    assert "fail-loud-backend-selection" in ids


def test_overlay_applies_and_guards_every_site() -> None:
    if not _submodule_available():
        import pytest

        pytest.skip("cmangos submodule not initialized")
    if not _submodule_clean():
        import pytest

        pytest.skip("cmangos submodule working tree is not pristine "
                    "(overlays applied mid-build or a crashed run)")
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        shared = root / "src" / "shared" / "Database"
        shared.mkdir(parents=True)
        for name in SOURCES:
            shutil.copy2(PRISTINE / name, shared / name)

        driver.apply_db_null_guard_overlays(shared)

        database_cpp = (shared / "Database.cpp").read_text(encoding="utf-8")
        database_impl = (shared / "DatabaseImpl.h").read_text(encoding="utf-8")
        database_h = (shared / "Database.h").read_text(encoding="utf-8")

        # The single tolerated enqueue lives inside SafeDelayOperation.
        assert database_cpp.count("m_threadBody->Delay(") == 1
        assert "SafeDelayOperation(SqlOperation* op)" in database_cpp
        assert "SafeDelayQueryHolder(SqlQueryHolder* holder" in database_cpp
        assert "m_threadBody->Delay(" not in database_impl
        assert "holder->Execute(" not in database_impl
        # 7 template Delay sites + 2 holder sites route through the helpers.
        assert database_impl.count("SafeDelayOperation(") == 7
        assert database_impl.count("SafeDelayQueryHolder(") == 2
        assert "bool SafeDelayOperation(SqlOperation* op);" in database_h
        # Ownership: the worker queue deletes ops after Execute, so the inline
        # fallback must too (mirrors CommitTransactionDirect). A fallback that
        # leaks every op it handles would rot the restart window.
        helpers = database_cpp.split("bool Database::SafeDelayOperation", 1)[1]
        fallback = helpers.split("bool Database::SafeDelayQueryHolder", 1)[0]
        assert "op->Execute(m_pAsyncConn);" in fallback
        assert "delete op;" in fallback.split("op->Execute(m_pAsyncConn);", 1)[1]

        driver.verify_db_async_null_guards(root)  # must not raise


def test_tripwire_fires_on_an_unguarded_site() -> None:
    # The raise branches must stay real. The tripwire cannot tell guarded
    # from unguarded beyond its invariants (exactly one Delay, and it lives
    # with the helper), so the failing cases are: a second Delay site, a
    # Delay without the helper, and any DatabaseImpl.h enqueue.
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        shared = root / "src" / "shared" / "Database"
        shared.mkdir(parents=True)
        (shared / "DatabaseImpl.h").write_text("", encoding="utf-8")

        guarded = (
            "bool Database::SafeDelayOperation(SqlOperation* op)\n{\n"
            "    if (m_threadBody)\n    {\n        m_threadBody->Delay(op);\n"
            "        return true;\n    }\n    return false;\n}\n")
        (shared / "Database.cpp").write_text(guarded, encoding="utf-8")
        driver.verify_db_async_null_guards(root)  # the one tolerated enqueue

        (shared / "Database.cpp").write_text(
            guarded + "void f() { m_threadBody->Delay(nullptr); }\n", encoding="utf-8")
        try:
            driver.verify_db_async_null_guards(root)
        except RuntimeError as error:
            assert "exactly one" in str(error)
        else:
            raise AssertionError("tripwire accepted a second Delay site")

        (shared / "Database.cpp").write_text(
            "void f() { m_threadBody->Delay(nullptr); }\n", encoding="utf-8")
        try:
            driver.verify_db_async_null_guards(root)
        except RuntimeError as error:
            assert "SafeDelayOperation" in str(error)
        else:
            raise AssertionError("tripwire accepted a Delay without the helper")

        (shared / "Database.cpp").write_text(guarded, encoding="utf-8")
        (shared / "DatabaseImpl.h").write_text(
            "    return m_threadBody->Delay(new SqlQuery(sql));\n", encoding="utf-8")
        try:
            driver.verify_db_async_null_guards(root)
        except RuntimeError as error:
            assert "DatabaseImpl.h" in str(error)
        else:
            raise AssertionError("tripwire accepted an unguarded DatabaseImpl.h site")


def test_overlay_restores_pristine_sources_byte_for_byte() -> None:
    if not _submodule_available():
        import pytest

        pytest.skip("cmangos submodule not initialized")
    if not _submodule_clean():
        import pytest

        pytest.skip("cmangos submodule working tree is not pristine "
                    "(overlays applied mid-build or a crashed run)")
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        shared = root / "src" / "shared" / "Database"
        shared.mkdir(parents=True)
        for name in SOURCES:
            shutil.copy2(PRISTINE / name, shared / name)

        driver.apply_db_null_guard_overlays(shared)
        driver.restore_db_null_guard_overlays(shared)

        for name in SOURCES:
            assert (shared / name).read_bytes() == (PRISTINE / name).read_bytes(), name


def test_backend_selection_uses_the_real_cmake_switch() -> None:
    # The historical no-op pair must be gone from the driver's configure
    # invocation; the SQLITE cache variable replaced it.
    source = (ROOT / "tools" / "build_o09_realm_runtime.py").read_text(encoding="utf-8")
    assert '"-DDO_MYSQL=ON", "-DDO_SQLITE=OFF"' not in source
    assert '"-DSQLITE=ON"] if backend == "sqlite" else ["-DSQLITE=OFF"]' in source


def test_committed_lockfiles_never_record_the_sqlite_backend() -> None:
    # The committed MariaDB-lane lockfiles are the shipped-provider pin
    # verified by Gradle. A sqlite build must never move them - fail
    # loud if one ever records database_backend "sqlite" (a rename or
    # copy-paste accident during the dual-provider window). The field is
    # REQUIRED (not defaulted): an old-schema lockfile means that ABI's
    # lane was never rebuilt since the overlay pinning landed - exactly
    # the stale staged-artifact hazard the pin exists to prevent - and
    # the overlay id set must be current for the same reason.
    import json

    # The exact-set expectation below uses the same .get() default as the
    # driver - so a registry entry MISSING its "backends" key would
    # silently enter both provenances and still match. Require the
    # declaration explicitly; the silent default becomes a loud error.
    assert all("backends" in entry for entry in driver.CMANGOS_OVERLAYS), (
        "every CMANGOS_OVERLAYS entry must declare backends explicitly "
        "(a missing key silently defaults into both lanes' provenance)")

    names = ("realm-runtime-lockfile.json", "realm-runtime-lockfile-arm64-v8a.json")
    required_overlays = {"db-async-null-guard", "fail-loud-backend-selection"}
    seen = 0
    for name in names:
        path = ROOT / "schemas" / name
        if not path.is_file():
            continue
        seen += 1
        record = json.loads(path.read_text(encoding="utf-8"))
        backend = record.get("database_backend")
        assert backend == "mysql", (
            f"{name} records database_backend={backend!r}; committed lockfiles "
            "are MariaDB-lane pins until the P8 cutover")
        overlays = {entry["id"] for entry in record.get("cmangos_source_overlays", [])}
        missing = required_overlays - overlays
        assert not missing, (
            f"{name} predates the P0 overlay work (missing overlay ids "
            f"{sorted(missing)}); rebuild that ABI lane with the current driver")
        # The exact mysql overlay set, mechanically derived from
        # the driver's registry + backend filter. A future registry entry
        # without a "backends" key defaults into BOTH provenances - this
        # catches that (and a mis-filtered regeneration) instead of trusting
        # the lockfile to have been written correctly.
        expected = {entry["id"] for entry in driver.CMANGOS_OVERLAYS
                    if "mysql" in entry.get("backends", ("mysql", "sqlite"))}
        assert overlays == expected, (
            f"{name} overlay ids differ from the driver registry filtered "
            f"for mysql: missing={sorted(expected - overlays)}, "
            f"unexpected={sorted(overlays - expected)} (a sqlite-only overlay "
            "in mysql provenance or a registry/lockfile drift)")
    assert seen > 0, "no committed realm-runtime lockfile found at all"
    # The sqlite lane stages into a sibling root with a sibling lockfile.
    # The arm64 sibling is the dual-provider window's sqlite pin:
    # required, backend=sqlite, carrying the exact sqlite overlay set
    # and the patches content pin.
    sibling = ROOT / "schemas" / "realm-runtime-lockfile-arm64-v8a-sqlite.json"
    assert sibling.is_file(), (
        "the arm64 sqlite sibling lockfile is missing - run the arm64 "
        "sqlite staging build: python3 tools/build_o09_realm_runtime.py "
        "--abi arm64-v8a --backend sqlite")
    srec = json.loads(sibling.read_text(encoding="utf-8"))
    assert srec.get("database_backend") == "sqlite", (
        "the sibling lockfile must record database_backend=sqlite")
    sqlite_overlays = {
        entry["id"] for entry in srec.get("cmangos_source_overlays", [])}
    expected_sqlite = {
        entry["id"] for entry in driver.CMANGOS_OVERLAYS
        if "sqlite" in entry.get("backends", ("mysql", "sqlite"))}
    assert sqlite_overlays == expected_sqlite, (
        f"sibling sqlite overlay drift: missing="
        f"{sorted(expected_sqlite - sqlite_overlays)}, unexpected="
        f"{sorted(sqlite_overlays - expected_sqlite)}")


def test_lockfiles_pin_patches_content() -> None:
    """Every committed lockfile (mysql lanes AND the sqlite sibling)
    pins the sha256 of every native/patches/ file compiled into its
    build. Any patches edit makes the committed lockfile mechanically
    stale - this pin is what catches that drift."""
    import json
    expected = driver.patches_content_digests()
    assert expected, "no patch files found under native/patches/"
    names = ("realm-runtime-lockfile.json",
             "realm-runtime-lockfile-arm64-v8a.json",
             "realm-runtime-lockfile-arm64-v8a-sqlite.json",
             "realm-runtime-lockfile-sqlite-win.json")
    for name in names:
        path = ROOT / "schemas" / name
        assert path.is_file(), f"{name} missing (rebuild that lane)"
        record = json.loads(path.read_text(encoding="utf-8"))
        pinned = record.get("patches_content")
        assert isinstance(pinned, dict) and pinned, (
            f"{name} predates patches-content pinning - rebuild that "
            "lane with the current driver")
        assert pinned == expected, (
            f"{name} patches content is stale vs the working tree: "
            f"changed={sorted(set(expected) ^ set(pinned))[:6]}... - "
            "rebuild that lane (the lockfile pins what IS staged)")


def test_write_lockfiles_mode_is_idempotent_and_content_only() -> None:
    """T0.3: the --write-lockfiles warm-dir regen refreshes ONLY the
    source-side pins and rewrites nothing when the tree is current -
    so the pin tripwire can be re-greened after an overlay edit without
    a lane rebuild, while a no-op run never perturbs the committed bytes
    (a gratuitous rewrite would show up as git noise on every CI run)."""
    import json
    names = ("realm-runtime-lockfile.json",
             "realm-runtime-lockfile-sqlite.json",
             "realm-runtime-lockfile-arm64-v8a.json",
             "realm-runtime-lockfile-arm64-v8a-sqlite.json",
             "realm-runtime-lockfile-sqlite-win.json")
    before = {n: (ROOT / "schemas" / n).read_bytes() for n in names
              if (ROOT / "schemas" / n).is_file()}
    updated = driver.write_lockfiles()
    assert updated == [], "regen rewrote lockfiles on an unchanged tree"
    for name, payload in before.items():
        assert (ROOT / "schemas" / name).read_bytes() == payload
        # the artifact pins must survive the regen path untouched: they
        # still describe the last FULL lane build
        record = json.loads(payload.decode("utf-8"))
        assert record["artifacts"], f"{name} lost its artifact pins"
