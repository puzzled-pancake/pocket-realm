"""Windows realm-runtime lockfile pins.

schemas/realm-runtime-lockfile-sqlite-win.json is the Windows lane's twin
of the android realm-runtime lockfiles: same source-side pins (the same
engine-fix overlay set must apply on both platforms) and PE import tables
where the android lockfiles pin ELF DT_NEEDED. The BuildConfig runtime
telltale is NOT derived from this dev/compat lane: the desktop image ships
the MARIADB lane (NativeRuntimeFence.LOCKFILE_RELATIVE pins
realm-runtime-lockfile-mariadb-win.json over the staged DLLs at package
time), so the telltale below derives from that lockfile instead.

CI posture mirrors the android lanes' pins-only mode: the artifact sha
pins describe the dev box's untracked build outputs and are NOT compared
against staged bytes here (a fresh CI machine rebuilds its own DLLs);
everything derivable from the committed tree IS compared.
"""
from __future__ import annotations

import json
import os
import re
import sys
import types
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

WIN_LOCKFILE = ROOT / "schemas" / "realm-runtime-lockfile-sqlite-win.json"
MARIADB_WIN_LOCKFILE = ROOT / "schemas" / "realm-runtime-lockfile-mariadb-win.json"
SIBLING_SQLITE = ROOT / "schemas" / "realm-runtime-lockfile-sqlite.json"
BASELINE = ROOT / "schemas" / "sqlite-seed-baseline.json"
BUILD_CONFIG = ROOT / "desktop" / "src" / "main" / "kotlin" / "com" / "pocketrealm" / "BuildConfig.kt"

# DLLs that must never appear in a lane artifact's import table: the app
# image does not carry them. This is the exact regression the import-table
# pin exists for — the shipped dep/lib prebuilt import libs silently added
# a dynamic libssl-3-x64.dll dependency that only loaded because a
# Git-for-Windows OpenSSL happened to be on PATH.
FORBIDDEN_IMPORT_PREFIXES = ("libssl", "libcrypto", "libmysql")


def _record() -> dict:
    return json.loads(WIN_LOCKFILE.read_text(encoding="utf-8"))


# --- P2-6: per-flavor win SQLite-seam lockfiles -----------------------------
# Naming: realm-runtime-lockfile-sqlite-win-{flavor}.json; the unqualified
# file above is the vanilla alias. Only vanilla's lockfile exists today — the
# tbc/wotlk pins are re-derived per branch at (the seam DLL itself is
# flavor-agnostic, decision), so the per-flavor tests assert the
# expected-loud state: lockfile absent, and the seam build refuses before any
# MSVC work.

def test_per_flavor_lockfile_naming_is_registered() -> None:
    from tools import flavor_layout
    assert flavor_layout.resolve("vanilla").sqlite_win_lockfile() == \
        "schemas/realm-runtime-lockfile-sqlite-win.json"
    # wotlk: the work landed (core pocket-realm @ c3a8d86f; overlay set
    # re-derived at the 2026-10-06 DLL build and registered 2026-10-08) —
    # the lockfile must exist and pin the wotlk lane with the same parity
    # the tbc template below asserts.
    assert flavor_layout.resolve("wotlk").sqlite_win_lockfile() == \
        "schemas/realm-runtime-lockfile-sqlite-win-wotlk.json"
    wotlk_lockfile = ROOT / "schemas" / "realm-runtime-lockfile-sqlite-win-wotlk.json"
    assert wotlk_lockfile.is_file(), (
        "wotlk: S2 landed but the lane lockfile is missing — run "
        "python tools/build_win_realm_runtime.py --flavor wotlk")
    record = json.loads(wotlk_lockfile.read_text(encoding="utf-8"))
    assert record["flavor"] == "wotlk"
    assert record["database_backend"] == "sqlite"
    assert record["abi"] == "windows-x86_64"
    assert record["cmangos_commit"] == "c3a8d86fe4e61c3373eff424eb12cd7e7e8282b5"
    assert record["playerbots_commit"] == "7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d"
    artifact_names = [a["path"].rsplit("/", 1)[-1] for a in record["artifacts"]]
    assert artifact_names == ["pocket_realmd_runtime.dll",
                              "pocket_world_runtime.dll", "pocket_sqlite.dll"]
    for artifact in record["artifacts"]:
        assert artifact["pe_machine"] == "0x8664"
    # tbc: the work landed (core pocket-realm @ ffbf97675 = upstream base +
    # the four Pocket Realm adaptation commits; overlay set re-derived and
    # registration) — the lockfile must exist and pin the tbc lane.
    tbc_lockfile = ROOT / "schemas" / "realm-runtime-lockfile-sqlite-win-tbc.json"
    assert tbc_lockfile.is_file(), (
        "tbc: S2 landed but the lane lockfile is missing — run "
        "python tools/build_win_realm_runtime.py --flavor tbc")
    record = json.loads(tbc_lockfile.read_text(encoding="utf-8"))
    assert record["flavor"] == "tbc"
    assert record["database_backend"] == "sqlite"
    assert record["abi"] == "windows-x86_64"
    assert record["cmangos_commit"] == "ffbf97675dc31f845587778f5bb993b232f1b6a2"
    assert record["playerbots_commit"] == "7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d"
    artifact_names = [a["path"].rsplit("/", 1)[-1] for a in record["artifacts"]]
    assert artifact_names == ["pocket_realmd_runtime.dll",
                              "pocket_world_runtime.dll", "pocket_sqlite.dll"]
    for artifact in record["artifacts"]:
        assert artifact["pe_machine"] == "0x8664"


def test_win_seam_resolves_every_registered_flavor_before_msvc() -> None:
    """Updated at the wotlk S2 wave: all three flavors' overlay sets are now
    registered, so the seam lane resolves each of them (the DLL is
    flavor-agnostic, decision D37 — only the per-flavor lockfile pins
    differ). The refusal contract itself stays pinned: a flavor whose
    registration is REMOVED from the table still dies before any MSVC
    environment is set up (the pre-S2 posture, exercised against the
    registry rather than the calendar)."""
    import build_o09_realm_runtime as o09
    import build_win_sqlite_seam as seam
    assert seam.resolve_flavor_or_die("wotlk").flavor_id == "wotlk"
    assert seam.resolve_flavor_or_die("tbc").flavor_id == "tbc"
    assert seam.resolve_flavor_or_die("vanilla").flavor_id == "vanilla"
    unregistered = {
        flavor: ids for flavor, ids in o09.PER_FLAVOR_OVERLAY_REGISTRATION.items()
        if flavor != "wotlk"}
    monkey = pytest.MonkeyPatch()
    monkey.setattr(o09, "PER_FLAVOR_OVERLAY_REGISTRATION", unregistered)
    try:
        with pytest.raises(SystemExit, match="S2"):
            seam.resolve_flavor_or_die("wotlk")
    finally:
        monkey.undo()


def test_write_lockfile_refuses_unregistered_flavor_overlay_sets(monkeypatch) -> None:
    """Root-cause pin for the premature wotlk lockfile: write_lockfile used
    to record the RAW o09.CMANGOS_OVERLAYS table, so a --flavor wotlk
    --write-lockfile run recorded realm-runtime-lockfile-sqlite-win-wotlk.
    json even though wotlk's per-flavor overlay set is unregistered (pre-S2)
    and overlays_for refuses it. The writer must resolve the flavor's
    REGISTERED set — the same record the android lane's writer keeps — and
    refuse before any artifact bytes are read. wotlk is registered now
    (2026-10-08), so the refusal is pinned against a registry with its
    entry removed — the mechanism, not the calendar."""
    import build_win_realm_runtime as tool
    monkeypatch.setattr(tool.o09, "ACTIVE_FLAVOR", "wotlk")
    monkeypatch.setattr(
        tool.o09, "PER_FLAVOR_OVERLAY_REGISTRATION",
        {flavor: ids for flavor, ids in tool.o09.PER_FLAVOR_OVERLAY_REGISTRATION.items()
         if flavor != "wotlk"})
    with pytest.raises(RuntimeError, match="S2"):
        tool.write_lockfile("sqlite")


@pytest.mark.skipif(os.name != "nt",
                    reason="the lane driver gates on os.name inside main()")
def test_win_lane_refuses_unregistered_flavors_before_any_work(monkeypatch, tmp_path) -> None:
    """The select-time twin of the seam's resolve_flavor_or_die: with wotlk's
    registration REMOVED from the table, the runtime lane must refuse it at
    flavor selection, BEFORE the --write-lockfile short-circuit runs — the
    exact invocation that produced the premature wotlk lockfile — and must
    leave no lockfile behind in the lane's schemas/. wotlk is registered
    now, so the refusal is pinned against the registry-minus-wotlk table
    (the mechanism, not the calendar)."""
    import build_o09_realm_runtime as o09
    monkeypatch.setattr(
        o09, "PER_FLAVOR_OVERLAY_REGISTRATION",
        {flavor: ids for flavor, ids in o09.PER_FLAVOR_OVERLAY_REGISTRATION.items()
         if flavor != "wotlk"})
    with pytest.raises(RuntimeError, match="S2"):
        _drive_lane_main(monkeypatch, tmp_path,
                         ["--flavor", "wotlk", "--write-lockfile"])
    assert not (tmp_path / "lane" / "schemas"
                / "realm-runtime-lockfile-sqlite-win-wotlk.json").is_file()


@pytest.mark.skipif(os.name != "nt",
                    reason="the lane driver gates on os.name inside main()")
def test_win_lane_writes_wotlk_lockfile_in_lockfile_only_refresh(monkeypatch, tmp_path):
    """The wotlk parity twin of the refusal drive above: with wotlk's
    registration live, the same --flavor wotlk --write-lockfile invocation
    is the documented lockfile-only refresh and must succeed, recording the
    flavor's own lockfile (never the vanilla alias) with the flavor's
    source pins."""
    def plant_wotlk_runtime_dlls(build_dir: Path) -> None:
        # select_win_flavor re-routes BUILD to the wotlk-suffixed root; the
        # runtime DLLs are prior lane outputs THERE (the seam DLL stays in
        # the shared root the harness already planted).
        wotlk_root = build_dir.with_name(".build-win-x86_64-wotlk")
        runtime = wotlk_root / "pocket-runtime-build"
        runtime.mkdir(parents=True, exist_ok=True)
        for dll in ("pocket_realmd_runtime.dll",
                    "pocket_world_runtime.dll"):
            (runtime / dll).write_bytes(b"MZ-fake-dll")

    rc, events, lockfile_path, _ = _drive_lane_main(
        monkeypatch, tmp_path, ["--flavor", "wotlk", "--write-lockfile"],
        before_main=plant_wotlk_runtime_dlls)

    assert rc == 0
    assert events == ["lockfile"], events
    # The refresh writes the FLAVOR'S OWN lockfile (flavor_layout naming),
    # never the vanilla alias the harness's unsuffixed path names.
    wotlk_lockfile = lockfile_path.with_name(
        "realm-runtime-lockfile-sqlite-win-wotlk.json")
    assert wotlk_lockfile.is_file(), (
        "the wotlk refresh did not write the flavor-routed lockfile")
    assert not lockfile_path.exists(), (
        "the wotlk refresh wrote the vanilla alias lockfile")
    record = json.loads(wotlk_lockfile.read_text(encoding="utf-8"))
    assert record["flavor"] == "wotlk"
    assert record["database_backend"] == "sqlite"
    assert [a["path"].rsplit("/", 1)[-1] for a in record["artifacts"]] == [
        "pocket_realmd_runtime.dll", "pocket_world_runtime.dll",
        "pocket_sqlite.dll"]


def test_source_pins_mirror_the_android_sqlite_lane() -> None:
    record = _record()
    sibling = json.loads(SIBLING_SQLITE.read_text(encoding="utf-8"))
    assert record["schema"] == 1
    assert record["abi"] == "windows-x86_64"
    assert record["database_backend"] == "sqlite"
    # Same engine-fix set on both platforms: an overlay added for one lane
    # without the other is a platform divergence this lockfile must catch.
    assert record["cmangos_commit"] == sibling["cmangos_commit"]
    assert record["playerbots_commit"] == sibling["playerbots_commit"]
    assert [e["id"] for e in record["cmangos_source_overlays"]] == \
        [e["id"] for e in sibling["cmangos_source_overlays"]], \
        "win lane cmangos overlays diverged from the android sqlite lane"
    assert [e["id"] for e in record["playerbots_source_overlays"]] == \
        [e["id"] for e in sibling["playerbots_source_overlays"]], \
        "win lane playerbots overlays diverged from the android sqlite lane"


def test_mariadb_source_pins_record_the_mysql_family_overlay_set() -> None:
    """The default lane's twin of the sqlite mirror test above: the desktop
    fence pins THIS lockfile over the shipped DLLs (NativeRuntimeFence.
    LOCKFILE_RELATIVE), so its overlay provenance must record the set the
    mariadb build actually applies — overlays_for maps the desktop backend
    name "mariadb" onto the overlay table's mysql family. The pre-fix
    writer filtered overlays with backend in ("mysql", "sqlite"), which
    matched nothing for "mariadb", so the reviewed document claimed the
    shipped default lane applies no source overlays (R2 finding N2)."""
    import build_o09_realm_runtime as driver
    record = json.loads(MARIADB_WIN_LOCKFILE.read_text(encoding="utf-8"))
    sibling = json.loads(SIBLING_SQLITE.read_text(encoding="utf-8"))
    assert record["schema"] == 1
    assert record["abi"] == "windows-x86_64"
    assert record["database_backend"] == "mariadb"
    assert record["flavor"] == "vanilla"
    # Same engine-fix commits as every vanilla lane...
    assert record["cmangos_commit"] == sibling["cmangos_commit"]
    assert record["playerbots_commit"] == sibling["playerbots_commit"]
    # ...and the overlay registry its build applies: the family alias
    # resolves through overlays_for and equals the mysql-family set —
    # never the raw-'mariadb' empty filter.
    overlay_ids = [e["id"] for e in record["cmangos_source_overlays"]]
    expected = [e["id"] for e in driver.overlays_for("vanilla", "mariadb")]
    assert overlay_ids == expected == \
        [e["id"] for e in driver.overlays_for("vanilla", "mysql")], (
            "mariadb win lockfile overlays diverged from the mysql-family "
            "set the lane's build applies")
    assert overlay_ids, \
        "mariadb win lockfile overlays hollow again — the raw-'mariadb' " \
        "filter regression (N2)"
    # The family divergence that makes this a real pin: the mysql-only
    # named-pipe overlay applies to this lane's build; the sqlite-only
    # hardening set does not.
    assert "win-named-pipe-socket-field" in overlay_ids
    assert "db-sqlite-connection-hardening" not in overlay_ids
    assert [e["id"] for e in record["playerbots_source_overlays"]] == \
        [e["id"] for e in sibling["playerbots_source_overlays"]], \
        "mariadb win lane playerbots overlays diverged from the sqlite lane"
    assert record["patches_content"] == driver.patches_content_digests(), \
        "mariadb win lockfile patches_content is stale vs the working tree " \
        "- a native/patches edit landed without its lockfile refresh; run " \
        "python tools/build_o09_realm_runtime.py --write-lockfiles and " \
        "commit the regenerated pins in the same change"
    # The provider cross-reference the desktop engine verifies the staged
    # distribution against rides in full, not just as a filename.
    assert record["provider_lockfile"] == \
        "schemas/database-provider-lockfile-win.json"
    assert record["provider"]["provider"]["id"] == \
        "mariadb-11.5.2-windows-x86_64"
    # The Connector/C link: the mariadb lane's runtime DLLs import the
    # packaged libmysql.dll (the sqlite lane's forbidden-import pin above
    # is the mirror image — there it must NOT appear).
    by_name = {a["path"].rsplit("/", 1)[-1]: a for a in record["artifacts"]}
    for dll in ("pocket_realmd_runtime.dll", "pocket_world_runtime.dll"):
        assert by_name[dll]["pe_machine"] == "0x8664", dll
        assert "libmysql.dll" in by_name[dll]["pe_imports"], (
            f"{dll} lost the MariaDB Connector/C import — this record no "
            "longer describes the mariadb lane build")


def test_patches_content_pins_the_working_tree() -> None:
    import build_o09_realm_runtime as driver
    record = _record()
    pinned = record.get("patches_content")
    assert isinstance(pinned, dict) and pinned, "win lockfile predates patches pinning"
    assert pinned == driver.patches_content_digests(), \
        "win lockfile patches_content is stale vs the working tree - " \
        "a native/patches edit landed without its lockfile refresh; run " \
        "python tools/build_o09_realm_runtime.py --write-lockfiles and " \
        "commit the regenerated pins in the same change"


def test_patches_content_tripwire_names_the_refresh_remedy(
        tmp_path, monkeypatch) -> None:
    """Round-6 process pin (the win-lane twin of the db-async tripwire's
    pin): the failure a patches-editing wave actually sees must name the
    documented one-command remedy, so the refresh lands in the same change
    instead of the gate staying red until review applies it."""
    import build_o09_realm_runtime as driver

    lockfile = tmp_path / "stale-win-lockfile.json"
    lockfile.write_text(json.dumps(
        {"patches_content": {"native/patches/stale.cpp": "0" * 64}}),
        encoding="utf-8")
    patches = tmp_path / "native" / "patches"
    patches.mkdir(parents=True)
    (patches / "edited.cpp").write_text("// edited\n", encoding="utf-8")
    # Hermetic lockfile + digest-walk roots; restored at teardown.
    monkeypatch.setattr(sys.modules[__name__], "WIN_LOCKFILE", lockfile)
    monkeypatch.setattr(driver, "ROOT", tmp_path)
    monkeypatch.setattr(driver, "NATIVE", tmp_path / "native")
    with pytest.raises(AssertionError, match=r"--write-lockfiles"):
        test_patches_content_pins_the_working_tree()


def test_o09_warm_regen_refresh_set_covers_the_win_lanes(
        tmp_path, monkeypatch) -> None:
    """The refresh remedy both patches_content assertions above name is the
    o09 warm-dir regen (--write-lockfiles), not a win-driver lane run:
    write_lockfiles() walks schemas/realm-runtime-lockfile*.json — a prefix
    glob that matches the win sqlite/mariadb lanes — and re-pins
    patches_content in every record it visits. This drives the real
    regen hermetically to pin that the documented remedy still covers BOTH
    win lanes this gate checks (a suffix filter added to that glob would
    strand the remedy and leave the gate red with no working command)."""
    import build_o09_realm_runtime as driver

    schemas = tmp_path / "schemas"
    schemas.mkdir()
    patches = tmp_path / "native" / "patches"
    patches.mkdir(parents=True)
    (patches / "edited.cpp").write_text("// edited\n", encoding="utf-8")
    win_lanes = ("schemas/realm-runtime-lockfile-sqlite-win.json",
                 "schemas/realm-runtime-lockfile-mariadb-win.json")
    for lane in win_lanes:
        (tmp_path / lane).write_text(json.dumps(
            {"patches_content": {"native/patches/stale.cpp": "0" * 64}}),
            encoding="utf-8")
    # Hermetic digest-walk roots (same pattern as the tripwire test above).
    monkeypatch.setattr(driver, "ROOT", tmp_path)
    monkeypatch.setattr(driver, "NATIVE", tmp_path / "native")

    updated = driver.write_lockfiles()

    for lane in win_lanes:
        assert lane in updated, \
            f"{lane} dropped out of the o09 warm regen's refresh set — " \
            "the remedy this file's patches_content messages name no " \
            "longer clears the win gate"
        record = json.loads((tmp_path / lane).read_text(encoding="utf-8"))
        assert record["patches_content"] == driver.patches_content_digests(), \
            f"{lane}: the warm regen ran but did not re-pin patches_content"


def test_seed_pins_equal_sibling_and_baseline() -> None:
    record = _record()
    sibling = json.loads(SIBLING_SQLITE.read_text(encoding="utf-8"))
    assert record["seed_transcripts"] == sibling["seed_transcripts"], \
        "win lockfile seed pins diverged from the android sqlite lane"
    baseline = json.loads(BASELINE.read_text(encoding="utf-8"))["transcript_digests"]
    for name, pin in record["seed_transcripts"].items():
        assert pin["sha256"] == baseline[name], \
            f"win lockfile seed pin for {name} != append-only baseline"


def test_artifacts_pin_pe_import_tables() -> None:
    record = _record()
    artifacts = record["artifacts"]
    names = {a["path"].rsplit("/", 1)[-1] for a in artifacts}
    assert names == {
        "pocket_realmd_runtime.dll",
        "pocket_world_runtime.dll",
        "pocket_sqlite.dll",
    }, "the runtime-loaded PE natives must all be pinned"
    for artifact in artifacts:
        assert re.fullmatch(r"[0-9a-f]{64}", artifact["sha256"]), artifact["path"]
        assert artifact["size"] > 0, artifact["path"]
        # The wrong-arch guard is pinned, not just enforced at write time:
        # a substituted DLL cannot pass this gate.
        assert artifact["pe_machine"] == "0x8664", artifact["path"]
        imports = artifact["pe_imports"]
        assert isinstance(imports, list) and imports, \
            f"{artifact['path']}: empty import table pin"
        assert imports == sorted(imports), f"{artifact['path']}: imports unsorted"
        forbidden = [dll for dll in imports
                     if dll.lower().startswith(FORBIDDEN_IMPORT_PREFIXES)]
        assert not forbidden, (
            f"{artifact['path']} imports unpackaged DLLs {forbidden}: the "
            "static-OpenSSL overlay (win-static-openssl-only) regressed")


def test_mariadb_win_lockfile_pins_the_shipped_libmysql_dll() -> None:
    """The mariadb-win lane ships FOUR PE natives into the app image: the
    two realm runtimes, the sqlite seam, and the MariaDB Connector/C client
    (pocket-runtime-build/libmysql.dll) packageApp stages beside them. The
    desktop fence (NativeRuntimeFence) rehashes artifact ROWS, so the
    connector DLL must carry its own size/sha256/pe_imports row — the
    runtimes' pe_imports entries pin only that they import its NAME, not
    its bytes (the DESK-115 stale-DLL-ships-silently hole, reopened by the
    r1 packaging fix shipping a fourth unpinned DLL). Unlike the sqlite
    lane (FORBIDDEN_IMPORT_PREFIXES above, whose image never carries
    libmysql.dll), the mariadb lane's realm runtimes deliberately import
    it."""
    record = json.loads(MARIADB_WIN_LOCKFILE.read_text(encoding="utf-8"))
    names = [a["path"].rsplit("/", 1)[-1] for a in record["artifacts"]]
    assert names == ["pocket_realmd_runtime.dll", "pocket_world_runtime.dll",
                     "pocket_sqlite.dll", "libmysql.dll"], (
        "every DLL the mariadb-win image ships must be byte-pinned")
    connector = record["artifacts"][-1]
    assert connector["path"] == \
        "native/.build-win-x86_64-mariadb/pocket-runtime-build/libmysql.dll"
    assert re.fullmatch(r"[0-9a-f]{64}", connector["sha256"])
    assert connector["size"] > 0
    assert connector["pe_machine"] == "0x8664"
    imports = connector["pe_imports"]
    assert isinstance(imports, list) and imports, "empty import-table pin"
    assert imports == sorted(imports), "imports unsorted"


def test_shipped_libmysql_dll_is_the_pinned_provider_client_library() -> None:
    """Provenance pin: the mariadb lane's fourth shipped DLL must BE the
    qualified provider distribution's Connector/C client library. Before the
    lane staged its own connector DLL (stage_connector_client_dll, run by
    write_lane_lockfiles on every mariadb lockfile path),
    pocket-runtime-build/libmysql.dll was an untracked manual copy with no
    repo-owned producer — a fresh clone or --force finished the hours-long
    DLL build and then died at the lockfile write with a remedy that could
    not produce the file. This pins-only coherence check ties the two
    COMMITTED documents together: the runtime lockfile's libmysql.dll row
    must equal the provider lockfile's client_library pin (size AND
    sha256), so a hand-copied foreign DLL can never re-enter the image as
    reviewed state."""
    provider = json.loads(
        (ROOT / "schemas" / "database-provider-lockfile-win.json")
        .read_text(encoding="utf-8"))
    client = provider.get("client_library") or {}
    assert client.get("name") == "lib/libmariadb.dll", (
        "the provider lockfile lost its Connector/C client_library pin — "
        "rerun tools/build_win_realm_runtime.py --database-backend mariadb "
        "--write-lockfile")
    assert re.fullmatch(r"[0-9a-f]{64}", client.get("sha256", ""))
    record = json.loads(MARIADB_WIN_LOCKFILE.read_text(encoding="utf-8"))
    connector = next(a for a in record["artifacts"]
                     if a["path"].endswith("/libmysql.dll"))
    assert connector["sha256"] == client["sha256"] and \
        connector["size"] == client["size"], (
            "the shipped libmysql.dll is not the provider distribution's "
            "lib/libmariadb.dll — the manual-copy provenance hole is back")


def test_stage_connector_client_dll_stages_verified_bytes(tmp_path, monkeypatch) -> None:
    """The lane's own producer for its fourth pinned artifact: given the
    verified provider staging, the staging step copies lib/libmariadb.dll
    into BUILD/pocket-runtime-build/libmysql.dll under the runtimes' import
    name, hash-checked against the manifest's client_library pin. A foreign
    copy already sitting in the lane's output dir is REPLACED (the
    manual-copy hole cannot persist through a lockfile refresh) and an
    already-correct copy is left byte-stable."""
    import hashlib

    import build_win_realm_runtime as tool

    staging = tmp_path / "native" / ".build-win-x86_64" / "mariadb-provider"
    (staging / "lib").mkdir(parents=True)
    source = staging / "lib" / "libmariadb.dll"
    source.write_bytes(b"MZ-connector-client-bytes")
    (staging / tool.PROVIDER_RUNTIME_MANIFEST).write_text(json.dumps({
        "client_library": {
            "name": "lib/libmariadb.dll",
            "size": source.stat().st_size,
            "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        },
    }), encoding="utf-8")
    build = tmp_path / "native" / ".build-win-x86_64-mariadb"
    monkeypatch.setattr(tool, "ROOT", tmp_path)
    monkeypatch.setattr(tool, "PROVIDER_STAGING", staging)
    monkeypatch.setattr(tool, "BUILD", build)

    staged = tool.stage_connector_client_dll()
    assert staged == build / "pocket-runtime-build" / "libmysql.dll"
    assert staged.read_bytes() == source.read_bytes()

    staged.write_bytes(b"foreign-hand-copied-dll")
    assert tool.stage_connector_client_dll().read_bytes() == source.read_bytes()
    stable = staged.read_bytes()
    tool.stage_connector_client_dll()
    assert staged.read_bytes() == stable


def test_stage_connector_client_dll_fails_loud_on_unpinned_or_absent_source(
        tmp_path, monkeypatch) -> None:
    """The staging step never trusts the directory: bytes that disagree
    with the runtime manifest's client_library pin, or a missing source
    DLL, fail loud with the restage remedy instead of shipping (or even
    copying) unreviewed bytes."""
    import build_win_realm_runtime as tool

    staging = tmp_path / "native" / ".build-win-x86_64" / "mariadb-provider"
    (staging / "lib").mkdir(parents=True)
    source = staging / "lib" / "libmariadb.dll"
    source.write_bytes(b"MZ-connector-client-bytes")
    (staging / tool.PROVIDER_RUNTIME_MANIFEST).write_text(json.dumps({
        "client_library": {
            "name": "lib/libmariadb.dll",
            "size": source.stat().st_size,
            "sha256": "f" * 64,  # a pin the staged bytes do not match
        },
    }), encoding="utf-8")
    build = tmp_path / "native" / ".build-win-x86_64-mariadb"
    monkeypatch.setattr(tool, "ROOT", tmp_path)
    monkeypatch.setattr(tool, "PROVIDER_STAGING", staging)
    monkeypatch.setattr(tool, "BUILD", build)

    with pytest.raises(RuntimeError, match="client_library pin"):
        tool.stage_connector_client_dll()
    assert not (build / "pocket-runtime-build" / "libmysql.dll").exists(), \
        "bytes that fail the pin check must never be staged"

    source.unlink()
    with pytest.raises(RuntimeError, match="no connector"):
        tool.stage_connector_client_dll()


def test_each_backend_builds_in_its_own_root(monkeypatch) -> None:
    """Root-cause pin (the R3 row-17 bytes leg): select_win_flavor used to
    suffix the win build root by FLAVOR only while lockfile_artifacts()
    returned the SAME BUILD/pocket-runtime-build paths for BOTH backends —
    but the two lanes' DLLs are different bytes under the same names, so
    whichever lane rebuilt last overwrote the other's artifacts and exactly
    one backend's win lockfile could agree with the directory at a time
    (the sqlite pin said a7dc8763 while the dir held the mariadb rebuild's
    b4537bd9). The mariadb backend must carry its own -mariadb build root,
    composed with the flavor suffix it mirrors; only the sqlite lanes keep
    the historical roots."""
    import build_win_realm_runtime as tool

    # Snapshot the routed globals: select_win_flavor reassigns them
    # wholesale, and the drive below must not leak lane state into the
    # other tests (monkeypatch restores the originals at teardown).
    for attr in ("BUILD", "SEAM_BUILD", "EXTRACTORS_BUILD", "LOCKFILE_ARTIFACTS",
                 "WIN_LOCKFILE", "WIN_MARIADB_LOCKFILE"):
        monkeypatch.setattr(tool, attr, getattr(tool, attr))
    for attr in ("ACTIVE_FLAVOR", "ACTIVE_CMANGOS_COMMIT",
                 "ACTIVE_PLAYERBOTS_COMMIT", "ACTIVE_CORE_SUBMODULE",
                 "ACTIVE_BOTS_SUBMODULE"):
        monkeypatch.setattr(tool.o09, attr, getattr(tool.o09, attr))

    def lane_artifacts(backend: str) -> list[str]:
        tool.select_win_flavor("vanilla", backend)
        return [str(p.relative_to(tool.ROOT)).replace("\\", "/")
                for p in tool.lockfile_artifacts(backend)]

    sqlite_lane = lane_artifacts("sqlite")
    mariadb_lane = lane_artifacts("mariadb")
    # The sqlite lane keeps the historical unsuffixed root byte-for-byte ...
    assert ("native/.build-win-x86_64/pocket-runtime-build/"
            "pocket_world_runtime.dll") in sqlite_lane
    assert not any(p.endswith("/libmysql.dll") for p in sqlite_lane)
    # ... while every mariadb row (the connector DLL included) lives under
    # the mariadb lane's OWN root — never the shared one.
    assert mariadb_lane == [
        "native/.build-win-x86_64-mariadb/pocket-runtime-build/"
        "pocket_realmd_runtime.dll",
        "native/.build-win-x86_64-mariadb/pocket-runtime-build/"
        "pocket_world_runtime.dll",
        "native/.build-win-x86_64/sqlite-seam-build/pocket_sqlite.dll",
        "native/.build-win-x86_64-mariadb/pocket-runtime-build/libmysql.dll",
    ], "the mariadb lane still writes the sqlite lane's shared build root"
    # The seam DLL stays the ONE shared artifact: the
    # backend suffix must never reach it.
    assert tool.SEAM_BUILD == \
        tool.ROOT / "native" / ".build-win-x86_64" / "sqlite-seam-build"
    # The extractors' home is the OTHER backend-suffix exception (the
    # cross-tool gap): the four tools are backend-agnostic, and BOTH
    # documented consumers resolve a fixed backend-unsuffixed path —
    # tools/win_prepare_data.py (EXTRACTORS = native/.build-win-x86_64/
    # bin/x64_Release/Extractors) and the desktop DataPreparationStore dev
    # walk — so a --database-backend mariadb extractors build must never
    # strand them inside the -mariadb root the consumers never scan.
    tool.select_win_flavor("vanilla", "mariadb")
    assert tool.EXTRACTORS_BUILD == \
        tool.ROOT / "native" / ".build-win-x86_64"
    assert tool.BUILD == tool.ROOT / "native" / ".build-win-x86_64-mariadb"
    # Flavor composes with the backend suffix exactly like the flavor
    # mechanism it mirrors (no tbc mariadb lane exists yet; the root naming
    # is pinned so a future one cannot silently reuse another lane's root).
    tool.select_win_flavor("tbc", "mariadb")
    assert tool.BUILD == tool.ROOT / "native" / ".build-win-x86_64-tbc-mariadb"
    # The flavor suffix DOES reach the extractors' home (extractor sources
    # are per-flavor client trees); only the backend suffix is dropped.
    assert tool.EXTRACTORS_BUILD == \
        tool.ROOT / "native" / ".build-win-x86_64-tbc"
    tool.select_win_flavor("tbc", "sqlite")
    assert tool.BUILD == tool.ROOT / "native" / ".build-win-x86_64-tbc"
    assert tool.EXTRACTORS_BUILD == tool.BUILD


def test_build_config_telltale_matches_lockfile() -> None:
    """Desktop BuildConfig is hand-maintained; this recomputes the id with
    the SAME formula the Android build derives from ITS lockfile and fails
    on drift, so the shared staleness telltale can never go stale silently.

    The lockfile here is the MARIADB-win lane — the lane the desktop image
    ships (NativeRuntimeFence.LOCKFILE_RELATIVE pins the same file over the
    staged pocket_*.dll at package time) — not the SQLITE dev/compat lane:
    a telltale derived from the sqlite lockfile would misreport the shipped
    lane in every diagnostic surface that reads the constant."""
    record = json.loads(MARIADB_WIN_LOCKFILE.read_text(encoding="utf-8"))
    assert record["database_backend"] == "mariadb", (
        "BuildConfig telltale lane: realm-runtime-lockfile-mariadb-win.json "
        "stopped pinning the mariadb backend")
    world = next(a for a in record["artifacts"]
                 if a["path"].endswith("pocket_world_runtime.dll"))
    expected_id = (
        f"win-x86_64-{record['database_backend']}"
        f"-cmangos-{record['cmangos_commit'][:8]}"
        f"-playerbots-{record['playerbots_commit'][:8]}"
        f"-{world['sha256'][:12]}"
    )
    text = BUILD_CONFIG.read_text(encoding="utf-8")

    def constant(name: str) -> str:
        match = re.search(rf'{name}[^=]*=\s*"([^"]+)"', text)
        assert match, f"BuildConfig.kt lost the {name} constant"
        return match.group(1)

    build_id = constant("NATIVE_RUNTIME_BUILD_ID")
    assert "unpinned" not in build_id, "BuildConfig id reverted to the unpinned sentinel"
    assert build_id == expected_id, (
        f"BuildConfig NATIVE_RUNTIME_BUILD_ID {build_id!r} != lockfile-derived "
        f"{expected_id!r} — rerun tools/build_win_realm_runtime.py "
        "--database-backend mariadb --write-lockfile and sync BuildConfig.kt")
    assert constant("NATIVE_CMANGOS_COMMIT") == record["cmangos_commit"]
    assert constant("NATIVE_PLAYERBOTS_COMMIT") == record["playerbots_commit"]


def test_sqlite_amalgamation_pin_matches_sources() -> None:
    record = _record()
    pin = record.get("sqlite_amalgamation") or {}
    assert pin.get("version") == "3.46.1"
    sources = json.loads((ROOT / "schemas" / "sources.json").read_text(encoding="utf-8"))
    entry = next(s for s in sources["sources"]
                 if "sqlite-amalgamation" in s.get("id", ""))
    assert pin["sqlite3_c_sha256"] == entry["content_sha256"]["sqlite3.c"], \
        "win lockfile sqlite amalgamation pin drifted from sources.json"


def test_vanilla_tweaks_host_lockfile_pins_pe() -> None:
    """The --host lane of tools/build_vanilla_tweaks.py records the Windows
    exe with its PE facts where the android lockfiles pin ELF fields."""
    record = json.loads(
        (ROOT / "schemas" / "vanilla-tweaks-lockfile-win.json").read_text(encoding="utf-8"))
    assert record["host"] == "windows-x86_64"
    assert record["component"] == "vanilla-tweaks"
    (artifact,) = record["artifacts"]
    assert artifact["path"].endswith("vanilla-tweaks.exe")
    assert artifact["pe_machine"] == "0x8664"
    assert artifact["pe_subsystem"] == 3, "the patcher is a console tool"
    assert re.fullmatch(r"[0-9a-f]{64}", artifact["sha256"])
    assert artifact["pe_imports"], "empty import-table pin"
    forbidden = [dll for dll in artifact["pe_imports"]
                 if dll.lower().startswith(FORBIDDEN_IMPORT_PREFIXES)]
    assert not forbidden, f"unexpected unpackaged DLL imports: {forbidden}"


def test_provider_lockfile_artifacts_carry_both_path_forms(tmp_path, monkeypatch) -> None:
    """The Windows MariaDB provider lockfile (written by
    tools/build_win_realm_runtime.py --database-backend mariadb) must pin
    each executable under BOTH path forms: `name` = staging-relative (the
    staged runtime-manifest's executables records' shape — the desktop
    DesktopMariaDbProvider.verify pin lookup's primary key) and `path` =
    repo-relative (provenance). Matching only the repo-relative form would
    refuse a REAL staged distribution, because the staged manifest's short
    name can never equal a repo-relative path. The staging contract also
    covers the Connector/C client DLL (lib/libmariadb.dll): the manifest's
    client_library record pins its bytes and the verification returns it,
    because the mariadb lane ships that DLL as libmysql.dll."""
    import hashlib

    import build_win_realm_runtime as tool

    def sha(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()

    staging = tmp_path / "native" / ".build-win-x86_64" / "mariadb-provider"
    (staging / "bin").mkdir(parents=True)
    (staging / "lib").mkdir(parents=True)
    executables = {
        "mariadbd": staging / "bin" / "mariadbd.exe",
        "mariadb": staging / "bin" / "mariadb.exe",
    }
    for which, path in executables.items():
        path.write_bytes(f"{which}-staged-bytes".encode())
    client_dll = staging / "lib" / "libmariadb.dll"
    client_dll.write_bytes(b"MZ-connector-client-staged-bytes")
    (staging / tool.PROVIDER_BOOTSTRAP).write_text("CREATE TABLE t (id INT);\n", encoding="utf-8")
    (staging / tool.PROVIDER_MIGRATION_MANIFEST).write_text(json.dumps({
        "entries": [],
        "source_commits": {"cmangos": "c" + "0" * 39},
        "app_build_id": "test-build",
        "expected_revisions": {
            "realm": "realmd_db_version", "characters": "character_db_version",
            "logs": "logs_db_version", "world": "db_version",
        },
    }), encoding="utf-8")
    manifest = {
        "schema": 1,
        "provider": tool.WIN_PROVIDER_ID,
        "abi": "windows-x86_64",
        "bootstrap_sha256": sha(staging / tool.PROVIDER_BOOTSTRAP),
        "executables": {
            which: {
                "name": path.relative_to(staging).as_posix(),
                "size": path.stat().st_size,
                "sha256": sha(path),
            }
            for which, path in executables.items()
        },
        "client_library": {
            "name": "lib/libmariadb.dll",
            "size": client_dll.stat().st_size,
            "sha256": sha(client_dll),
        },
    }
    (staging / tool.PROVIDER_RUNTIME_MANIFEST).write_text(json.dumps(manifest), encoding="utf-8")

    monkeypatch.setattr(tool, "ROOT", tmp_path)
    monkeypatch.setattr(tool, "PROVIDER_STAGING", staging)
    monkeypatch.setattr(tool, "PROVIDER_EXECUTABLES", executables)
    record = tool.verify_mariadb_provider_staging()

    by_name = {artifact["name"]: artifact for artifact in record["artifacts"]}
    assert set(by_name) == {"bin/mariadbd.exe", "bin/mariadb.exe"}
    for which, path in executables.items():
        name = path.relative_to(staging).as_posix()
        artifact = by_name[name]
        # The manifest's short name IS the lockfile `name` (the desktop
        # matcher's primary key) ...
        assert manifest["executables"][which]["name"] == name
        # ... and the repo-relative provenance form rides alongside, so the
        # desktop's `endsWith("/" + name)` fallback also matches.
        assert artifact["path"] == f"native/.build-win-x86_64/mariadb-provider/{name}"
        assert artifact["size"] == path.stat().st_size
        assert re.fullmatch(r"[0-9a-f]{64}", artifact["sha256"])
    # The client DLL rides the same verified record (both path forms, same
    # digests as the staged bytes the manifest pins).
    client = record["client_library"]
    assert client["name"] == "lib/libmariadb.dll"
    assert client["path"] == "native/.build-win-x86_64/mariadb-provider/lib/libmariadb.dll"
    assert client["size"] == client_dll.stat().st_size
    assert client["sha256"] == sha(client_dll)


# --- --write-lockfile composes with --extractors -----
# The driver used to short-circuit on --write-lockfile before the extractors
# leg, silently dropping the requested build. The pins here drive main() with
# the MSVC/git/submodule legs stubbed and assert the composed invocation
# builds the extractors FIRST and writes the lockfiles AFTER (the same order
# as the runtime lane), that --write-lockfile alone still short-circuits (the
# documented refresh), and that a composed run on a fresh tree fails loud
# with the lane-build remedy instead of recording a hollow lockfile.

def _drive_lane_main(monkeypatch, tmp_path, argv, with_runtime_dlls=True,
                     before_main=None, backend="sqlite"):
    """Run build_win_realm_runtime.main() against a hermetic tmp tree with
    every side-effecting leg stubbed; returns (rc, events, lockfile_path,
    msvc_bodies). events records the leg order: preflight, prepare,
    configure, build-extractors, lockfile, restore (a mariadb drive prepends
    the provider-staging gate's verify event); msvc_bodies lists each
    generated cmd batch body in the same order. build_dir is ALWAYS the
    backend-unsuffixed .build-win-x86_64 root — for the extractors leg that
    is EXTRACTORS_BUILD (where the tools must land); the mariadb lane's own
    -mariadb root is never touched by the stubbed legs."""
    import build_win_realm_runtime as tool

    events = []
    msvc_bodies = []
    lane_root = tmp_path / "lane"
    build_dir = lane_root / "native" / ".build-win-x86_64"
    build_dir.mkdir(parents=True)
    # The backend-selection evidence verify_backend_selection reads from the
    # build graph after the (stubbed) configure leg.
    (build_dir / "build.ninja").write_text(
        "build realmd: ninja -DDO_SQLITE\n", encoding="utf-8")
    # The documents write_lockfile reads, hermetic under the tmp ROOT.
    schemas = lane_root / "schemas"
    schemas.mkdir()
    seed = {"sha256": "a" * 64}
    (schemas / "realm-runtime-lockfile-sqlite.json").write_text(
        json.dumps({"seed_transcripts": {"seed_floor.sql": seed}}), encoding="utf-8")
    # The baseline's transcript_digests maps name -> digest string (the shape
    # write_lockfile cross-checks the sibling's sha256 pins against).
    (schemas / "sqlite-seed-baseline.json").write_text(
        json.dumps({"transcript_digests": {"seed_floor.sql": seed["sha256"]}}),
        encoding="utf-8")
    (schemas / "sources.json").write_text(json.dumps({"sources": [{
        "id": "sqlite-amalgamation-3.46.1",
        "content_sha256": {"sqlite3.c": "b" * 64},
    }]}), encoding="utf-8")
    scripts = lane_root / "scripts"
    scripts.mkdir()
    (scripts / "build_win_deps.py").write_text(
        'SQLITE_VERSION = "3.46.1"\n', encoding="utf-8")
    lockfile_path = schemas / "realm-runtime-lockfile-sqlite-win.json"

    artifacts = [
        build_dir / "pocket-runtime-build" / "pocket_realmd_runtime.dll",
        build_dir / "pocket-runtime-build" / "pocket_world_runtime.dll",
        build_dir / "sqlite-seam-build" / "pocket_sqlite.dll",
    ]
    if with_runtime_dlls:
        # The runtime DLLs are PRIOR lane outputs on disk (the extractors
        # leg never produces them) — present for the lockfile refresh,
        # absent for the fresh-tree fail-loud pin.
        for artifact in artifacts:
            artifact.parent.mkdir(parents=True, exist_ok=True)
            artifact.write_bytes(b"MZ-fake-dll")

    def fake_preflight(backend="sqlite"):
        events.append("preflight")

    def fake_prepare():
        events.append("prepare")

    def fake_restore():
        events.append("restore")

    def fake_vcvars():
        return build_dir / "vcvars64.bat"

    def fake_env(vcvars, body, log, cwd=None):
        msvc_bodies.append(body)
        if "--build" in body:
            events.append("build-extractors")
            extractors = build_dir / "bin" / "x64_Release" / "Extractors"
            extractors.mkdir(parents=True, exist_ok=True)
            for target in tool.EXTRACTOR_TARGETS:
                (extractors / f"{target}.exe").write_bytes(b"MZ")
        else:
            events.append("configure")
            status, define = (("MYSQL", "-DDO_MYSQL") if backend == "mariadb"
                              else ("SQLITE", "-DDO_SQLITE"))
            log.write_text(
                f"-- Pocket Realm database backend: {status}\n", encoding="utf-8")
            # The real cmake configure regenerates the Ninja graph the
            # backend-selection verify reads (a --force run wipes it first).
            (build_dir / "build.ninja").write_text(
                f"build realmd: ninja {define}\n", encoding="utf-8")

    def fake_subprocess_run(cmd, **kwargs):
        # git plumbing only on this path: pristine diffs, empty listings.
        return types.SimpleNamespace(returncode=0, stdout="", stderr="")

    real_lane_lockfiles = tool.write_lane_lockfiles

    def recording_lane_lockfiles(backend="sqlite"):
        events.append("lockfile")
        real_lane_lockfiles(backend)

    monkeypatch.setattr(sys, "argv", ["build_win_realm_runtime.py", *argv])
    monkeypatch.setattr(tool, "ROOT", lane_root)
    monkeypatch.setattr(tool, "BUILD", build_dir)
    monkeypatch.setattr(tool, "WIN_LOCKFILE", lockfile_path)
    monkeypatch.setattr(
        tool, "SIBLING_SQLITE_LOCKFILE",
        schemas / "realm-runtime-lockfile-sqlite.json")
    monkeypatch.setattr(tool, "WIN_PREFIX", lane_root / "prefix")
    monkeypatch.setattr(tool, "LOCKFILE_ARTIFACTS", artifacts)
    monkeypatch.setattr(tool, "pe_info",
                        lambda path: {"machine": 0x8664,
                                      "dependents": ["KERNEL32.dll"]})
    monkeypatch.setattr(tool, "sha256_file", lambda path: "c" * 64)
    monkeypatch.setattr(tool, "dependency_preflight", fake_preflight)
    if backend == "mariadb":
        # The provider-staging gate runs FIRST on the mariadb lane; the
        # hermetic tree stages no distribution, so the gate is stubbed (the
        # real gate's bytes are pinned by the provider lockfile tests).
        def fake_provider_verify():
            events.append("provider-verify")
        monkeypatch.setattr(tool, "verify_mariadb_provider_staging",
                            fake_provider_verify)
    monkeypatch.setattr(tool, "find_vcvars", fake_vcvars)
    monkeypatch.setattr(tool, "in_msvc_env", fake_env)
    monkeypatch.setattr(tool, "subprocess",
                        types.SimpleNamespace(run=fake_subprocess_run))
    monkeypatch.setattr(tool.o09, "prepare_cmangos_source", fake_prepare)
    monkeypatch.setattr(tool.o09, "restore_cmangos_source", fake_restore)

    def fake_select_flavor(flavor_id):
        # Hermetic: the real gate walks the real checkouts via flavor_layout;
        # the lane harness never touches the real tree. Vanilla resolves to
        # the driver constants, which is all the lockfile record reads.
        tool.o09.ACTIVE_FLAVOR = flavor_id
        tool.o09.ACTIVE_CMANGOS_COMMIT = tool.o09.CMANGOS_COMMIT
        tool.o09.ACTIVE_PLAYERBOTS_COMMIT = tool.o09.PLAYERBOTS_COMMIT
        tool.o09.ACTIVE_CORE_SUBMODULE = "native/cmangos"
        tool.o09.ACTIVE_BOTS_SUBMODULE = "native/playerbots"

    monkeypatch.setattr(tool.o09, "select_flavor", fake_select_flavor)
    monkeypatch.setattr(tool, "write_lane_lockfiles", recording_lane_lockfiles)

    if before_main:
        before_main(build_dir)
    rc = tool.main()
    return rc, events, lockfile_path, msvc_bodies


@pytest.mark.skipif(os.name != "nt",
                    reason="the lane driver gates on os.name inside main()")
def test_write_lockfile_composes_with_extractors(monkeypatch, tmp_path, capsys):
    """The composed invocation must BUILD the extractors and only then
    refresh the lockfiles — never short-circuit into a lockfile-only run
    that silently drops the requested build (LVF-flavor-import-014)."""
    rc, events, lockfile_path, _ = _drive_lane_main(
        monkeypatch, tmp_path, ["--write-lockfile", "--extractors"])

    assert rc == 0
    assert events == ["preflight", "prepare", "configure",
                      "build-extractors", "lockfile", "restore"], events
    record = json.loads(lockfile_path.read_text(encoding="utf-8"))
    assert record["database_backend"] == "sqlite"
    assert [a["path"].rsplit("/", 1)[-1] for a in record["artifacts"]] == [
        "pocket_realmd_runtime.dll", "pocket_world_runtime.dll",
        "pocket_sqlite.dll"]
    out = capsys.readouterr().out
    assert "windows extractors built:" in out
    # relative_to prints the platform separator (backslash on Windows).
    assert re.search(r"wrote schemas[/\\]realm-runtime-lockfile-sqlite-win\.json", out)
    # Output order is the proof the lockfile leg ran after the build leg.
    assert out.index("windows extractors built:") < out.index("wrote schemas")


def test_composed_invocation_configures_the_extractors_graph(monkeypatch, tmp_path):
    """The composed run must actually request the extractor targets from the
    build graph (BUILD_EXTRACTORS=ON + the four targets), not just skip the
    lockfile short-circuit."""
    import build_win_realm_runtime as tool

    rc, events, _, bodies = _drive_lane_main(
        monkeypatch, tmp_path, ["--write-lockfile", "--extractors"])

    assert rc == 0
    configure_body, build_body = bodies
    assert "-DBUILD_EXTRACTORS=ON" in configure_body
    for target in tool.EXTRACTOR_TARGETS:
        assert f"--target {target}" in build_body, target


@pytest.mark.skipif(os.name != "nt",
                    reason="the lane driver gates on os.name inside main()")
def test_extractors_lane_stages_tools_where_win_prepare_data_looks(
        monkeypatch, tmp_path, capsys):
    """Root-cause pin (the R4 cross-tool contract gap): the per-backend
    build-root split used to route --extractors --database-backend mariadb
    into native/.build-win-x86_64-mariadb/bin/x64_Release/Extractors, while
    BOTH documented consumers resolve the fixed UNSUFFIXED path
    (tools/win_prepare_data.py EXTRACTORS; the desktop DataPreparationStore
    dev walk) — the run succeeded, win_prepare_data failed with an extractor
    remedy that reproduced the gap, and the mariadb lane could never stage
    the backend-agnostic tools its own data prep needs. The extractors leg
    must configure and build in the backend-unsuffixed home; only the
    mariadb lane's DLL/lockfile legs keep the -mariadb root."""
    import build_win_realm_runtime as tool
    import win_prepare_data as wpd

    shared_root = tmp_path / "lane" / "native" / ".build-win-x86_64"
    rc, events, _, bodies = _drive_lane_main(
        monkeypatch, tmp_path,
        ["--extractors", "--database-backend", "mariadb"],
        backend="mariadb")

    assert rc == 0
    assert events == ["provider-verify", "preflight", "prepare", "configure",
                      "build-extractors", "restore"], events
    configure_body, build_body = bodies
    # Configure AND build target the backend-unsuffixed home — never the
    # mariadb lane's own root.
    assert f"-B {shared_root}" in configure_body
    assert f'--build "{shared_root}"' in build_body
    assert ".build-win-x86_64-mariadb" not in configure_body + build_body
    # The stale-cache hardening: the shared root's CMake cache holds
    # SQLITE=ON from the default lane's runs, and the SQLITE cache variable
    # is the fail-loud overlay's real switch — an implicit default would
    # silently inherit the sqlite selection.
    assert "-DSQLITE=OFF" in configure_body
    # The cross-tool contract itself: the staged home IS the path
    # win_prepare_data resolves, and the four tools are there.
    staged = shared_root / "bin" / "x64_Release" / "Extractors"
    assert (staged.relative_to(tmp_path / "lane")
            == wpd.EXTRACTORS.relative_to(wpd.ROOT))
    for target in tool.EXTRACTOR_TARGETS:
        assert (staged / f"{target}.exe").is_file(), target
    out = capsys.readouterr().out
    assert "windows extractors built:" in out
    # The coupling note names the shared home and the consumer contract.
    assert "backend-agnostic" in out
    assert "win_prepare_data" in out


@pytest.mark.skipif(os.name != "nt",
                    reason="the lane driver gates on os.name inside main()")
def test_write_lockfile_alone_still_short_circuits(monkeypatch, tmp_path):
    """The plain documented refresh (--write-lockfile, no --extractors) must
    stay lockfile-only: no preflight, no submodule staging, no MSVC leg."""
    rc, events, lockfile_path, _ = _drive_lane_main(
        monkeypatch, tmp_path, ["--write-lockfile"])

    assert rc == 0
    assert events == ["lockfile"], events
    assert lockfile_path.is_file()


@pytest.mark.skipif(os.name != "nt",
                    reason="the lane driver gates on os.name inside main()")
def test_composed_write_lockfile_fails_loud_on_fresh_tree(monkeypatch, tmp_path):
    """A composed run where the runtime DLLs were never built must fail loud
    with the lane-build remedy AFTER building the extractors — composition
    can never silently record a hollow lockfile."""
    with pytest.raises(RuntimeError, match="run the lane build"):
        _drive_lane_main(monkeypatch, tmp_path,
                         ["--write-lockfile", "--extractors"],
                         with_runtime_dlls=False)


@pytest.mark.skipif(os.name != "nt",
                    reason="the lane driver gates on os.name inside main()")
def test_force_wipe_clears_lane_outputs_but_spares_seam(monkeypatch, tmp_path):
    """--force must wipe THIS lane's own outputs — directories AND plain
    files — while sparing sqlite-seam-build/, whose pocket_sqlite.dll comes
    from the separate hours-long seam build (a forced runtime rebuild must
    not strand write_lockfile() without it), and mariadb-provider/, the
    OTHER backend's staged engine distribution (PROVIDER_STAGING) — a
    forced rebuild of one lane must never destroy the sibling lane's
    staged inputs. The split-roots leg: a --force --extractors
    --database-backend mariadb run must wipe the shared backend-unsuffixed
    root THAT invocation configures (stale CMakeCache and extractor tree
    included) and must NEVER wipe the -mariadb lane root, whose
    pocket-runtime-build/ DLLs and staged libmysql.dll this invocation
    never rebuilds (realm-runtime-lockfile-mariadb-win.json pins them)."""
    import build_win_realm_runtime as tool

    planted = {}

    # The wipe reads the module's PROVIDER_STAGING at wipe time; point it at
    # the hermetic lane's own provider staging so the sparing is exercised
    # against THIS tree, not the real checkout's.
    provider_staging = tmp_path / "lane" / "native" / ".build-win-x86_64" / \
        "mariadb-provider"
    monkeypatch.setattr(tool, "PROVIDER_STAGING", provider_staging)

    def plant_stale_outputs(build_dir):
        planted["dir"] = build_dir / "pocket-runtime-build"
        planted["dir"].mkdir(exist_ok=True)
        (planted["dir"] / "pocket_realmd_runtime.dll").write_bytes(b"MZ-stale")
        planted["file"] = build_dir / "CMakeCache.txt"
        planted["file"].write_text("stale", encoding="utf-8")
        planted["seam_dll"] = build_dir / "sqlite-seam-build" / "pocket_sqlite.dll"
        planted["seam_dll"].parent.mkdir(exist_ok=True)
        planted["seam_dll"].write_bytes(b"MZ-seam")
        planted["provider_exe"] = provider_staging / "bin" / "mariadbd.exe"
        planted["provider_exe"].parent.mkdir(parents=True, exist_ok=True)
        planted["provider_exe"].write_bytes(b"MZ-provider")

    rc, events, _, _ = _drive_lane_main(
        monkeypatch, tmp_path, ["--force", "--configure-only"],
        before_main=plant_stale_outputs)

    assert rc == 0
    assert events == ["preflight", "prepare", "configure", "restore"], events
    assert not planted["dir"].exists(), "--force left a stale lane directory"
    assert not planted["file"].exists(), "--force left a stale lane file"
    assert planted["seam_dll"].is_file(), \
        "--force wiped sqlite-seam-build/ (the seam DLL is not this lane's output)"
    assert planted["provider_exe"].is_file(), \
        "--force wiped mariadb-provider/ (the staged engine distribution " \
        "is the sibling backend's input, not this lane's output)"

    # The extractors+mariadb combination: --force must wipe the root THIS
    # run configures (the shared backend-unsuffixed home) and leave the
    # -mariadb lane root byte-intact. A second hermetic lane tree: the
    # harness builds one fresh lane root per drive.
    split = {}
    split_root = tmp_path / "extractors-lane"
    shared_root = split_root / "lane" / "native" / ".build-win-x86_64"
    mariadb_lane_root = split_root / "lane" / "native" / ".build-win-x86_64-mariadb"
    provider_staging = shared_root / "mariadb-provider"
    monkeypatch.setattr(tool, "PROVIDER_STAGING", provider_staging)

    def plant_split_roots(build_dir):
        # Stale extractor tree in the SHARED root: a forced run must clear
        # it, or a prior run's CMakeCache survives --force.
        split["stale_cache"] = build_dir / "CMakeCache.txt"
        split["stale_cache"].write_text("stale", encoding="utf-8")
        split["stale_tools"] = build_dir / "bin" / "x64_Release" / \
            "Extractors" / "ExtractOne.exe"
        split["stale_tools"].parent.mkdir(parents=True, exist_ok=True)
        split["stale_tools"].write_bytes(b"MZ-stale")
        # Foreign artifacts inside the shared root: spared by the wipe.
        split["seam_dll"] = build_dir / "sqlite-seam-build" / "pocket_sqlite.dll"
        split["seam_dll"].parent.mkdir(exist_ok=True)
        split["seam_dll"].write_bytes(b"MZ-seam")
        split["provider_exe"] = provider_staging / "bin" / "mariadbd.exe"
        split["provider_exe"].parent.mkdir(parents=True, exist_ok=True)
        split["provider_exe"].write_bytes(b"MZ-provider")
        # The mariadb lane's PRIOR outputs — the runtime DLLs plus the
        # staged connector DLL the lane's win lockfile pins: an
        # extractors-only run never rebuilds any of them.
        split["lane_dll"] = mariadb_lane_root / "pocket-runtime-build" / \
            "pocket_world_runtime.dll"
        split["lane_dll"].parent.mkdir(parents=True, exist_ok=True)
        split["lane_dll"].write_bytes(b"MZ-mariadb-lane")
        split["lane_libmysql"] = mariadb_lane_root / "pocket-runtime-build" / \
            "libmysql.dll"
        split["lane_libmysql"].write_bytes(b"MZ-connector")

    rc, events, _, _ = _drive_lane_main(
        monkeypatch, split_root,
        ["--force", "--configure-only", "--extractors",
         "--database-backend", "mariadb"],
        backend="mariadb", with_runtime_dlls=False,
        before_main=plant_split_roots)

    assert rc == 0
    assert events == ["provider-verify", "preflight", "prepare", "configure",
                      "restore"], events
    assert not split["stale_cache"].exists(), \
        "--force left a stale CMakeCache in the shared root it configures"
    assert not split["stale_tools"].exists(), \
        "--force left a stale extractor tree in the shared root"
    assert split["seam_dll"].is_file(), \
        "--force wiped sqlite-seam-build/ out of the shared root"
    assert split["provider_exe"].is_file(), \
        "--force wiped mariadb-provider/ out of the shared root"
    assert split["lane_dll"].is_file(), \
        "--force --extractors wiped the mariadb lane root (runtime DLLs " \
        "this invocation never rebuilds; the win lockfile pins them)"
    assert split["lane_libmysql"].is_file(), \
        "--force --extractors wiped the staged libmysql.dll (a lane input " \
        "the extractors leg never rebuilds)"


@pytest.mark.skipif(os.name != "nt",
                    reason="the generated batch runs under cmd.exe")
def test_generated_msvc_batch_cds_into_space_bearing_build(
        monkeypatch, tmp_path):
    """in_msvc_env's generated batch must quote the cd into BUILD like it
    already quotes vcvars and every cmake/log path: an unquoted cd relies on
    cmd.exe's rest-of-the-line parsing and leaves the batch one path
    metacharacter away from configuring in the wrong cwd."""
    import build_win_realm_runtime as tool

    build = tmp_path / "space bearing checkout" / "native" / ".build-win-x86_64"
    build.mkdir(parents=True)
    monkeypatch.setattr(tool, "BUILD", build)
    # A no-op stand-in for vcvars64.bat: the pin is the batch's own cd/cwd
    # mechanics, not the MSVC environment.
    vcvars = tmp_path / "empty vcvars.bat"
    vcvars.write_bytes(b"@echo off\r\n")
    cwd_evidence = tmp_path / "cwd evidence.txt"

    tool.in_msvc_env(vcvars, f'cd > "{cwd_evidence}"', build / "configure.log")

    bat = (build / "configure.log").with_suffix(".bat").read_text(encoding="ascii")
    assert f'cd /d "{build}"' in bat
    assert cwd_evidence.is_file(), "batch body never ran"
    assert Path(cwd_evidence.read_text(encoding="ascii").strip()).samefile(build)


def test_non_vanilla_win_lockfiles_bind_their_own_seed_transcripts() -> None:
    """Round-2 R2-1a: a per-flavor win sqlite lockfile must never present
    vanilla transcripts as its own pinned seed state silently. Every
    seed_transcripts pin either matches that flavor's OWN baseline digests
    (schemas/sqlite-seed-baseline-<flavor>.json) or the lockfile carries
    the explicit vanilla-transcripts placeholder provenance naming the
    retiring wave (W2/W5)."""
    for flavor in ("tbc", "wotlk"):
        path = ROOT / "schemas" / f"realm-runtime-lockfile-sqlite-win-{flavor}.json"
        record = json.loads(path.read_text(encoding="utf-8"))
        assert record.get("flavor") == flavor
        pins = record.get("seed_transcripts") or {}
        assert pins, f"{flavor}: seed_transcripts vanished - re-pin this test"
        baseline_path = ROOT / "schemas" / f"sqlite-seed-baseline-{flavor}.json"
        baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
        own_digests = baseline.get("transcript_digests") or {}
        provenance = record.get("seed_transcripts_provenance") or {}
        if own_digests:
            for name, pin in pins.items():
                assert pin.get("sha256") == own_digests.get(name), (
                    f"{flavor}: seed pin {name} is not its own baseline's "
                    "digest - a per-flavor transcript mismatch"
                )
        else:
            assert provenance.get(
                "retires", ""
            ).startswith("W2/W5"), (
                f"{flavor}: carries vanilla transcripts with no own baseline "
                "and no placeholder provenance naming the retiring wave"
            )
            assert "VANILLA classic* transcripts" in provenance.get("note", ""), (
                f"{flavor}: the placeholder note must say the bytes ARE the "
                "vanilla transcripts"
            )


def test_vanilla_win_lockfile_carries_no_placeholder_note() -> None:
    """The vanilla lane's transcripts ARE its own - the placeholder
    provenance would be a lie there."""
    record = json.loads(
        (ROOT / "schemas" / "realm-runtime-lockfile-sqlite-win.json")
        .read_text(encoding="utf-8")
    )
    assert record.get("flavor") == "vanilla"
    assert "seed_transcripts_provenance" not in record


# ---------------------------------------------------------------------------
# the Windows MariaDB provider contract is
# scoped VANILLA-lane — the shared PROVIDER_STAGING root's
# staged migration manifest IS the vanilla ledger golden, so a non-vanilla
# write would either be refused by the desktop verifier against the vanilla
# pin or silently re-key the committed vanilla provenance. The lockfile
# records the scope machine-readably (lane_scope) and the writer refuses
# non-vanilla lanes fail loud until W1.3 defines per-flavor provider staging.
# ---------------------------------------------------------------------------

def test_provider_lockfile_is_scoped_to_the_vanilla_lane() -> None:
    import hashlib

    record = json.loads(
        (ROOT / "schemas" / "database-provider-lockfile-win.json")
        .read_text(encoding="utf-8"))
    scope = record.get("lane_scope") or {}
    assert scope.get("flavors") == ["vanilla"], scope
    assert scope.get("decision") == "D60"
    # the migration pin IS the vanilla ledger golden (the bytes —
    # alias and canonical byte-copies hash identically), never a
    # tbc/wotlk ledger
    vanilla_alias = hashlib.sha256(
        (ROOT / "schemas" / "database-migrations.json")
        .read_bytes()).hexdigest()
    vanilla_canonical = hashlib.sha256(
        (ROOT / "schemas" / "database-migrations-vanilla.json")
        .read_bytes()).hexdigest()
    assert vanilla_alias == vanilla_canonical, (
        "the G3-3 alias/canonical byte-copy invariant broke - fix that "
        "first, this pin consumes it")
    assert record["migration_manifest_sha256"] == vanilla_alias
    assert record["migration_count"] == 414
    for flavor in ("tbc", "wotlk"):
        flavor_ledger = hashlib.sha256(
            (ROOT / "schemas" / f"database-migrations-{flavor}.json")
            .read_bytes()).hexdigest()
        assert record["migration_manifest_sha256"] != flavor_ledger, (
            f"the shared provider pin was re-keyed to the {flavor} ledger "
            "- the D60 scope was clobbered")


def test_write_provider_lockfile_scopes_non_vanilla_lanes_to_their_own_document(
        tmp_path, monkeypatch) -> None:
    """W4 flip (D72 retirement, superseding the W1.3-era refusal this test
    used to pin): a tbc --write-lockfile run writes the LANE-SCOPED document
    (database-provider-lockfile-win-tbc.json) pinning the tbc sibling
    migration manifest, and NEVER rewrites the shared vanilla document (D60's
    scope holds; both targets are redirected to tmp_path so even a broken
    guard cannot touch the real documents)."""
    import hashlib
    import build_win_realm_runtime as w

    # BOTH write targets are redirected to
    # tmp_path — a unit test must never rewrite a committed provenance
    # document from live staged bytes (the lane target previously wrote
    # the REAL file, silently healing committed-vs-staged drift instead
    # of failing; the never-rekey pin then compared against the same
    # staged bytes and could never fire). The staged sibling manifest is
    # still read in place (read-only verification).
    redirected_shared = tmp_path / "database-provider-lockfile-win.json"
    redirected_lane = tmp_path / "database-provider-lockfile-win-tbc.json"
    monkeypatch.setattr(w, "PROVIDER_LOCKFILE", redirected_shared)
    monkeypatch.setattr(
        w, "lane_provider_lockfile_path", lambda flavor_id: redirected_lane)
    monkeypatch.setattr(w.o09, "ACTIVE_FLAVOR", "tbc")
    # the REAL staged sibling manifest (tools/stage_mariadb_flavor_migrations.py
    # output) — the writer verifies it in place, only the WRITE targets move.
    sibling = (ROOT / "native" / ".build-win-x86_64" / "mariadb-provider"
               / "migration-manifest-tbc.json")
    record = w.write_provider_lockfile()
    assert redirected_lane.is_file(), "the lane document was not written"
    assert not redirected_shared.exists(), (
        "the shared vanilla document must never be rewritten by a lane run")
    assert record["lane_scope"]["flavors"] == ["tbc"]
    assert record["lane_migrations"]["flavor"] == "tbc"
    assert record["lane_migrations"]["manifest"] == "migration-manifest-tbc.json"
    assert record["lane_migrations"]["manifest_sha256"] == hashlib.sha256(
        sibling.read_bytes()).hexdigest()


def test_write_provider_lockfile_records_the_scope_on_vanilla(
        tmp_path, monkeypatch) -> None:
    """Control: the vanilla lane writes the document and stamps the
    machine-readable lane_scope (the guard passes through to the verifier,
    which is stubbed here — the real verifier ran in the committed lockfile
    regeneration this wave performed)."""
    import build_win_realm_runtime as w

    redirected = tmp_path / "database-provider-lockfile-win.json"
    monkeypatch.setattr(w, "PROVIDER_LOCKFILE", redirected)
    monkeypatch.setattr(w, "ROOT", tmp_path)  # the write's print uses relative_to
    monkeypatch.setattr(w.o09, "ACTIVE_FLAVOR", "vanilla")
    monkeypatch.setattr(
        w, "verify_mariadb_provider_staging",
        lambda: {
            "provider": {"id": "mariadb-11.5.2-windows-x86_64"},
            "manifest_sha256": "0" * 64,
            "bootstrap_sha256": "0" * 64,
            "migration_manifest_sha256": "3" * 64,
            "migration_count": 414,
            "staging_root": "native/.build-win-x86_64/mariadb-provider",
            "artifacts": [],
            "client_library": {},
        })
    record = w.write_provider_lockfile()
    assert record["lane_scope"]["flavors"] == ["vanilla"]
    persisted = json.loads(redirected.read_text(encoding="utf-8"))
    assert persisted["lane_scope"]["decision"] == "D60"

def _world_digest_id(lockfile: Path) -> str:
    """The lane's BuildConfig id, recomputed with the same formula the
    telltale test pins for the vanilla lane (prefix + backend + commit
    prefixes + the world DLL digest prefix)."""
    record = json.loads(lockfile.read_text(encoding="utf-8"))
    world = next(a for a in record["artifacts"]
                 if a["path"].endswith("pocket_world_runtime.dll"))
    return (
        f"win-x86_64-{record['database_backend']}"
        f"-cmangos-{record['cmangos_commit'][:8]}"
        f"-playerbots-{record['playerbots_commit'][:8]}"
        f"-{world['sha256'][:12]}"
    )


def test_tbc_mariadb_win_lane_lockfile_and_telltale_are_pinned() -> None:
    """W4 flip (D72 retirement): the tbc MariaDB win lane's runtime lockfile
    exists and its BuildConfig id is pinned — a lane nobody can rebuild or
    verify is a lane the flip cannot boot honestly."""
    lockfile = ROOT / "schemas" / "realm-runtime-lockfile-mariadb-win-tbc.json"
    assert lockfile.is_file(), (
        "the tbc mariadb win lane lockfile is missing; build the lane with "
        "python tools/build_win_realm_runtime.py --flavor tbc "
        "--database-backend mariadb")
    record = json.loads(lockfile.read_text(encoding="utf-8"))
    assert record["database_backend"] == "mariadb"
    assert record["flavor"] == "tbc"
    # the runtime lockfile must cross-
    # reference its OWN lane-scoped provider document — the shared
    # document is the vanilla golden and its lane_scope says
    # non-vanilla lanes must not use it; the embedded provider record
    # must be the lane's own (its lane_scope + lane_migrations pin name
    # THIS lane), so a future consumer following the reference lands in
    # the lane's own pins, never the vanilla 414-entry manifest.
    assert record["provider_lockfile"] == (
        "schemas/database-provider-lockfile-win-tbc.json")
    assert record["provider"]["lane_scope"]["flavors"] == ["tbc"]
    assert record["provider"]["lane_migrations"]["flavor"] == "tbc"
    assert record["provider"]["lane_migrations"]["manifest_sha256"] == (
        json.loads((ROOT / "schemas" / "database-provider-lockfile-win-tbc.json")
                   .read_text(encoding="utf-8"))["lane_migrations"]["manifest_sha256"])
    text = BUILD_CONFIG.read_text(encoding="utf-8")
    match = re.search(r'const val NATIVE_RUNTIME_BUILD_ID_TBC[^=]*=\s*"([^"]+)"', text)
    assert match, "BuildConfig.kt lost the NATIVE_RUNTIME_BUILD_ID_TBC constant"
    expected = _world_digest_id(lockfile)
    assert match.group(1) == expected, (
        f"BuildConfig NATIVE_RUNTIME_BUILD_ID_TBC {match.group(1)!r} != "
        f"lockfile-derived {expected!r}")


def test_wotlk_mariadb_win_lane_lockfile_and_telltale_are_pinned() -> None:
    """W4 flip (D72 retirement): the wotlk MariaDB win lane's runtime
    lockfile + BuildConfig id (same contract as the tbc lane row)."""
    lockfile = ROOT / "schemas" / "realm-runtime-lockfile-mariadb-win-wotlk.json"
    assert lockfile.is_file(), (
        "the wotlk mariadb win lane lockfile is missing; build the lane with "
        "python tools/build_win_realm_runtime.py --flavor wotlk "
        "--database-backend mariadb")
    record = json.loads(lockfile.read_text(encoding="utf-8"))
    assert record["database_backend"] == "mariadb"
    assert record["flavor"] == "wotlk"
    # the runtime lockfile must cross-
    # reference its OWN lane-scoped provider document — the shared
    # document is the vanilla golden and its lane_scope says
    # non-vanilla lanes must not use it; the embedded provider record
    # must be the lane's own (its lane_scope + lane_migrations pin name
    # THIS lane), so a future consumer following the reference lands in
    # the lane's own pins, never the vanilla 414-entry manifest.
    assert record["provider_lockfile"] == (
        "schemas/database-provider-lockfile-win-wotlk.json")
    assert record["provider"]["lane_scope"]["flavors"] == ["wotlk"]
    assert record["provider"]["lane_migrations"]["flavor"] == "wotlk"
    assert record["provider"]["lane_migrations"]["manifest_sha256"] == (
        json.loads((ROOT / "schemas" / "database-provider-lockfile-win-wotlk.json")
                   .read_text(encoding="utf-8"))["lane_migrations"]["manifest_sha256"])
    text = BUILD_CONFIG.read_text(encoding="utf-8")
    match = re.search(r'const val NATIVE_RUNTIME_BUILD_ID_WOTLK[^=]*=\s*"([^"]+)"', text)
    assert match, "BuildConfig.kt lost the NATIVE_RUNTIME_BUILD_ID_WOTLK constant"
    expected = _world_digest_id(lockfile)
    assert match.group(1) == expected, (
        f"BuildConfig NATIVE_RUNTIME_BUILD_ID_WOTLK {match.group(1)!r} != "
        f"lockfile-derived {expected!r}")


def test_flavor_provider_lockfiles_never_rekey_the_vanilla_golden() -> None:
    """D60 holds at the W4 D72 retirement: the SHARED provider document's
    migration manifest sha is still the VANILLA ledger golden, and the
    per-flavor documents scope their own sibling manifest + ledger."""
    import hashlib
    shared = json.loads(
        (ROOT / "schemas" / "database-provider-lockfile-win.json").read_text(
            encoding="utf-8"))
    shared_manifest = (ROOT / "native" / ".build-win-x86_64" / "mariadb-provider"
                       / "migration-manifest.json").read_bytes()
    assert shared["migration_manifest_sha256"] == hashlib.sha256(
        shared_manifest).hexdigest(), (
        "the shared provider document no longer pins the vanilla ledger "
        "golden (D60 breach)")
    for flavor in ("tbc", "wotlk"):
        lane = json.loads(
            (ROOT / f"schemas/database-provider-lockfile-win-{flavor}.json")
            .read_text(encoding="utf-8"))
        lane_manifest = (ROOT / "native" / ".build-win-x86_64" /
                         "mariadb-provider" /
                         f"migration-manifest-{flavor}.json").read_bytes()
        assert lane["lane_migrations"]["manifest_sha256"] == hashlib.sha256(
            lane_manifest).hexdigest(), (
            f"the {flavor} lane provider document does not pin its staged "
            "sibling migration manifest")
        assert lane["lane_migrations"]["flavor"] == flavor
        assert lane["lane_migrations"]["ledger"] == (
            f"schemas/database-migrations-{flavor}.json")
