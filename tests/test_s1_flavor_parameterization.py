"""Wave-S1 parameterization tests: the naming resolver, the fail-loud pin
contract, and the C1–C9 data halves.

Covers (port plan S1 end state + register §0 corrections):
- tools/flavor_layout.py resolves every artifact name; vanilla keeps the
  pre-parameterization literals byte-for-byte, tbc/wotlk are -{flavor}-suffixed;
- the vanilla migration-ledger alias stays byte-identical to its canonical
  byte copy (G3-3/D35 alias window);
- build_native.py `--flavor vanilla` emits the pre-change cmake command
  sequence (resolution-level byte-identity proof); after the P2 data wave
  (2026-10-08) `--flavor tbc|wotlk` RESOLVE into their -{flavor} build
  roots — the unregistered/wrong-pin refusal classes stay pinned against
  synthetic trees;
- a registered-but-wrong pin fails the pin-mismatch class BEFORE configure
  (S1 negative-test class 2) — proven against a synthetic wrong-commit repo;
- the o09 overlay split applies exactly the pre-split set for vanilla; tbc
  and wotlk resolve their S2-re-derived sets, id-parity per backend;
- the C5 [ACID] selection rule was corrected in fact at S4: every pinned DB
  repo ships its in-tree ACID/acid_<expansion>.sql file (the installer's
  apply_acid_data layer), so the selection is the classic file shape for all
  three flavors — the commit-history machinery was removed when the pinned
  single-commit-squash checkouts disproved its premise (pinned in
  tests/test_s4_per_flavor_migration_ledger.py);
- the C1/C3/C8/C9 manifest rules enforced by check_flavor (gamebuild ban,
  shared 0x1EC opcode, wotlk-only cache files) and the C9 generator exclusion
  (a VoiceChat key in a wotlk dist is a loud refusal, never an emission);
- tools/build_win_sqlite_seam.py resolves tbc/wotlk into their per-flavor
  sqlite-win lockfiles before any MSVC work (P2-6 item 2).
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from tools import flavor_layout  # noqa: E402

GOLDEN_LEDGER_SHA256 = "3771fc35b4ceddbadd6f786d76b10902db319d07c7d6372156417e4e3d81dc68"


# ---------------------------------------------------------------------------
# Naming resolver
# ---------------------------------------------------------------------------

def test_vanilla_resolves_the_pre_parameterization_literals() -> None:
    layout = flavor_layout.resolve("vanilla")
    assert layout.core_submodule == "native/cmangos"
    assert layout.bots_submodule == "native/playerbots"
    assert layout.db_submodule == "native/classic-db"
    assert layout.build_native_dir("arm64") == "native/.build-arm64"
    assert layout.o11_build_root("x86_64") == "native/.build-o11-x86_64"
    assert layout.realm_runtime_lockfile("x86_64") == "schemas/realm-runtime-lockfile.json"
    assert layout.realm_runtime_lockfile("arm64-v8a") == "schemas/realm-runtime-lockfile-arm64-v8a.json"
    assert layout.o11_lockfile("x86_64") == "schemas/o11-extractor-lockfile.json"
    assert layout.o11_lockfile("arm64-v8a") == "schemas/o11-extractor-lockfile-arm64-v8a.json"
    assert layout.sqlite_win_lockfile() == "schemas/realm-runtime-lockfile-sqlite-win.json"
    assert layout.sqlite_seed_baseline() == "schemas/sqlite-seed-baseline.json"
    canonical, alias = layout.migration_ledger()
    assert canonical == "schemas/database-migrations-vanilla.json"
    assert alias == "schemas/database-migrations.json"


def test_per_flavor_names_are_suffixed_and_unique() -> None:
    seen: set[str] = set()
    for flavor in ("tbc", "wotlk"):
        layout = flavor_layout.resolve(flavor)
        assert layout.core_submodule == f"native/cmangos{'-tbc' if flavor == 'tbc' else '-wotlk'}"
        assert layout.o11_build_root("x86_64") == f"native/.build-o11-x86_64-{flavor}"
        assert layout.o11_lockfile("x86_64") == f"schemas/o11-extractor-lockfile-{flavor}.json"
        assert layout.sqlite_win_lockfile() == f"schemas/realm-runtime-lockfile-sqlite-win-{flavor}.json"
        canonical, alias = layout.migration_ledger()
        assert canonical == f"schemas/database-migrations-{flavor}.json"
        assert alias is None
        for name in (
            layout.o11_lockfile("x86_64"), layout.realm_runtime_lockfile("x86_64"),
            canonical, layout.sqlite_win_lockfile(), layout.conf_matrix(),
        ):
            assert name not in seen
            seen.add(name)


def test_unknown_flavor_is_refused_with_valid_ids() -> None:
    with pytest.raises(ValueError, match="vanilla"):
        flavor_layout.resolve("vanila")


def test_ledger_alias_is_byte_identical_to_canonical() -> None:
    """D35/G3-3: the deprecation-window invariant — the alias
    (database-migrations.json) and the canonical byte copy
    (database-migrations-vanilla.json) must never drift apart while both
    exist; the alias bytes are the seal-critical golden."""
    alias = ROOT / "schemas" / "database-migrations.json"
    canonical = ROOT / "schemas" / "database-migrations-vanilla.json"
    assert alias.is_file() and canonical.is_file()
    alias_bytes = alias.read_bytes()
    assert canonical.read_bytes() == alias_bytes
    import hashlib
    assert hashlib.sha256(alias_bytes).hexdigest() == GOLDEN_LEDGER_SHA256


# ---------------------------------------------------------------------------
# Fail-loud pin contract
# ---------------------------------------------------------------------------

def test_require_flavor_pins_vanilla_resolves() -> None:
    pins = flavor_layout.require_flavor_pins("vanilla")
    assert pins["server_core"] == "ce83805d48f9c98b2af617096be5347acb8a1f17"
    assert pins["db_content"] == "be1a5206bedccc8dc088b7c79dae31fcf3dbbfce"
    assert pins["bots"] == "7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d"


def test_unregistered_pin_fails_loud_naming_stage(tmp_path: Path) -> None:
    """S1 negative-test class 1 (R2): a flavor whose pin is still
    status:unresolved must abort any lane naming the missing pin and the
    owning port-plan stage."""
    manifest = flavor_layout.load_flavor_manifest()
    with pytest.raises(flavor_layout.FlavorPinError, match="S2 item 1") as excinfo:
        flavor_layout.require_flavor_pins("tbc", manifest=manifest, worktree=tmp_path,
                                          groups=("server_core",))
    assert "cmangos-tbc" in str(excinfo.value)


def test_wrong_pin_checkout_fails_before_configure(tmp_path: Path) -> None:
    """S1 negative-test class 2 (R2): a deliberate wrong-pin checkout must
    fail the pin-mismatch error class before any compilation. Proven against
    a synthetic git repo whose HEAD is NOT the pinned commit."""
    import subprocess as sp
    repo = tmp_path / "native" / "cmangos-wotlk"
    repo.mkdir(parents=True)
    def git(*args: str) -> None:
        sp.run(["git", *args], cwd=repo, check=True, capture_output=True)
    git("init", "-q")
    git("config", "user.email", "t@t")
    git("config", "user.name", "t")
    (repo / "file.txt").write_text("wrong commit\n", encoding="utf-8")
    git("add", ".")
    git("commit", "-q", "-m", "not the pin")
    manifest = flavor_layout.load_flavor_manifest()
    # Make the synthetic repo the registered checkout for the wotlk core by
    # pointing the check at tmp_path as the worktree: the flavor's core pin is
    # registered (landed the pin; the checkout is the synthetic one),
    # so the wrong-HEAD checkout trips the pin-mismatch class directly — first
    # against the real registered pin, then deterministically against a
    # known-wrong pin copied into the manifest.
    with pytest.raises(flavor_layout.FlavorPinError, match="pin mismatch"):
        flavor_layout.require_flavor_pins("wotlk", manifest=manifest, worktree=tmp_path,
                                          groups=("server_core",))
    block = next(f for f in manifest["flavors"] if f["id"] == "wotlk")
    wrong_pin = "f" * 40
    block["server_core"]["commit"] = wrong_pin
    block["server_core"]["status"] = "registered"
    with pytest.raises(flavor_layout.FlavorPinError, match="pin mismatch") as excinfo:
        flavor_layout.require_flavor_pins("wotlk", manifest=manifest, worktree=tmp_path,
                                          groups=("server_core",))
    assert wrong_pin[:12] in str(excinfo.value)


def test_registered_pin_with_missing_checkout_names_the_checkout(tmp_path: Path) -> None:
    manifest = flavor_layout.load_flavor_manifest()
    block = next(f for f in manifest["flavors"] if f["id"] == "wotlk")
    block["server_core"]["commit"] = "a" * 40
    block["server_core"]["status"] = "registered"
    with pytest.raises(flavor_layout.FlavorPinError, match="checkout is missing") as excinfo:
        flavor_layout.require_flavor_pins("wotlk", manifest=manifest, worktree=tmp_path,
                                          groups=("server_core",))
    assert "native/cmangos-wotlk" in str(excinfo.value)


# ---------------------------------------------------------------------------
# build_native.py --flavor
# ---------------------------------------------------------------------------

def _fake_msys2_root(tmp_path: Path) -> Path:
    fake = tmp_path / "fake-msys2"
    (fake / "usr" / "bin").mkdir(parents=True, exist_ok=True)
    (fake / "usr" / "bin" / "pacman.exe").write_bytes(b"")
    (fake / "usr" / "bin" / "bash.exe").write_bytes(b"")
    (fake / "mingw32").mkdir(exist_ok=True)
    return fake


def _run_build_native(args: list[str], tmp_path: Path) -> subprocess.CompletedProcess:
    env = dict(os.environ, MSYS2_ROOT=str(_fake_msys2_root(tmp_path)))
    return subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "build_native.py"), *args],
        capture_output=True, text=True, env=env, cwd=str(ROOT),
    )


def test_build_native_vanilla_dry_run_matches_pre_change_resolution(tmp_path: Path) -> None:
    """The --flavor vanilla byte-identity proof (resolution level): the cmake
    configure invocation must carry exactly the pre-parameterization source
    dir, build dir, prefix, and flag sequence."""
    result = _run_build_native(["--flavor", "vanilla", "--dry-run", "cmangos"], tmp_path)
    assert result.returncode == 0, result.stderr
    configure_lines = [
        line for line in result.stdout.splitlines()
        if line.strip().startswith("$ ") and "-S " in line
    ]
    assert configure_lines, result.stdout
    configure = configure_lines[0][2:].split()
    root = str(ROOT)
    assert f"{root}{os.sep}native{os.sep}cmangos" in configure
    build_arg = configure[configure.index("-B") + 1]
    assert build_arg.endswith(os.path.join("native", ".build-arm64"))
    prefix_flags = [token for token in configure if "prefix-arm64" in token]
    assert len(prefix_flags) == 5  # PREFIX_PATH, FIND_ROOT_PATH, OPENSSL_ROOT_DIR, BOOST_ROOT, Boost_DIR
    tail = configure[configure.index("-DANDROID_ABI=arm64-v8a"):]
    assert tail[:3] == ["-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-26",
                        "-DANDROID_STL=c++_shared"]
    for flag in ("-DSQLITE=ON", "-DBUILD_EXTRACTORS=OFF", "-DBUILD_GAME_SERVER=ON",
                 "-DBUILD_LOGIN_SERVER=ON", "-DBUILD_PLAYERBOTS=ON",
                 "-DCMAKE_BUILD_TYPE=Release"):
        assert flag in configure


def test_build_native_registered_flavors_resolve_after_pin_registration(tmp_path: Path) -> None:
    """Fail-loud per flavor, updated at the P2 data wave (2026-10-08, lanes
    item 1): the tbc/wotlk db_content pins are registered in flavor.json and
    the checkouts sit at them, so — after core+bots at their own S2 waves —
    the dry-run lane now RESOLVES for both flavors (rc=0) into the
    -{flavor}-suffixed build root (exactly the unblocking this registration
    exists for; it gates P3/P4/P5). The fail-loud CONTRACT survives at the
    flavor_layout seam: an unregistered or mismatched pin still refuses
    exit 2 naming the owning stage (the synthetic-tree negative tests above
    pin both classes)."""
    for flavor in ("tbc", "wotlk"):
        result = _run_build_native(["--flavor", flavor, "--dry-run", "cmangos"], tmp_path)
        assert result.returncode == 0, (result.stdout, result.stderr)
        combined = result.stdout + result.stderr
        # The flavor-suffixed build root proves flavor_layout resolution, not
        # a silent vanilla fallback.
        assert f".build-arm64-{flavor}" in combined, combined
        assert "ALL STAGES OK" in combined


# ---------------------------------------------------------------------------
# o09 overlay split
# ---------------------------------------------------------------------------

def test_o09_overlay_split_vanilla_parity() -> None:
    """Vanilla's applied set is the whole table backend-filtered, MINUS the
    flavor-scoped ids another flavor owns (W5: tbc's tbc-icu-apple-guard).
    Deriving through PER_FLAVOR_OVERLAY_REGISTRATION keeps the pin
    mechanical: any table entry that slips into vanilla's set without being
    registered for it (or vice versa) diverges here."""
    from tools import build_o09_realm_runtime as o09
    for backend in ("mysql", "sqlite"):
        expected = [
            dict(entry) for entry in o09.CMANGOS_OVERLAYS
            if backend in entry.get("backends", ("mysql", "sqlite"))
            and (o09.OVERLAY_SCOPES[entry["id"]] == "expansion-neutral"
                 or entry["id"] in o09.PER_FLAVOR_OVERLAY_REGISTRATION["vanilla"])
        ]
        assert o09.overlays_for("vanilla", backend) == expected


def test_o09_overlay_split_resolves_registered_flavor_sets() -> None:
    """The overlay registration gate, updated at the wotlk S2 wave and again
    at the P2 data wave (2026-10-08): tbc registered its re-derived set
    (CP-2, five anchors re-derived, none dropped) and wotlk re-derived its
    own variants at its S2 build (2026-10-06) — both now RESOLVE, each set
    scoped to its own flavor's anchors. The ids are the contract: W5 added
    the first DELIBERATE per-flavor delta — tbc's `tbc-icu-apple-guard`
    (its pinned tree is the only branch gating the ICU find on
    `if (UNIX OR APPLE)`, which the NDK toolchain trips at configure) — so
    this pin asserts each delta is EXACTLY one named id: tbc's ICU guard
    and wotlk's `wotlk-bgmap-unlink` (the battleground/map shutdown
    use-after-free). Any further divergence is a registration drift this
    test refuses."""
    from tools import build_o09_realm_runtime as o09
    for backend in ("mysql", "sqlite"):
        vanilla_ids = [e["id"] for e in o09.overlays_for("vanilla", backend)]
        wotlk_ids = [e["id"] for e in o09.overlays_for("wotlk", backend)]
        tbc_ids = [e["id"] for e in o09.overlays_for("tbc", backend)]
        assert sorted(wotlk_ids) == sorted(vanilla_ids + ["wotlk-bgmap-unlink"])
        assert sorted(tbc_ids) == sorted(vanilla_ids + ["tbc-icu-apple-guard"])


def test_o09_select_flavor_vanilla_agrees_with_driver_constants() -> None:
    from tools import build_o09_realm_runtime as o09
    o09.select_flavor("vanilla")
    assert o09.ACTIVE_CMANGOS_COMMIT == o09.CMANGOS_COMMIT
    assert o09.ACTIVE_PLAYERBOTS_COMMIT == o09.PLAYERBOTS_COMMIT
    assert o09.ACTIVE_CORE_SUBMODULE == "native/cmangos"


def test_o09_select_flavor_tbc_resolves_after_s2() -> None:
    """S2 (RA): the tbc fork pins + checkouts landed (mangos-tbc pocket-realm
    @ ffbf97675 = upstream base + the four Pocket Realm adaptation commits;
    playerbots-tbc @ the shared D34 pin), so select_flavor now RESOLVES tbc
    instead of refusing it. The fails-loud contract stays enforced at the
    flavor_layout level (unregistered pin / missing checkout / pin mismatch)
    and for wotlk until its own S2 wave."""
    from tools import build_o09_realm_runtime as o09
    o09.select_flavor("tbc")
    assert o09.ACTIVE_CORE_SUBMODULE == "native/cmangos-tbc"
    assert o09.ACTIVE_BOTS_SUBMODULE == "native/playerbots-tbc"
    assert o09.ACTIVE_CMANGOS_COMMIT == (
        "ffbf97675dc31f845587778f5bb993b232f1b6a2")
    assert o09.ACTIVE_PLAYERBOTS_COMMIT == (
        "7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d")


# ---------------------------------------------------------------------------
# C5 (corrected in fact at S4): ACID ships as the in-tree file for every
# flavor; the [ACID]-commit-history machinery was removed when the pinned DB
# repos — both single-commit squashed imports, tbc's with no [ACID]-tagged
# subject at all — disproved the premise. The per-flavor ACID file
# selection is pinned in tests/test_s4_per_flavor_migration_ledger.py.
# ---------------------------------------------------------------------------


def test_c5_vanilla_selection_keeps_acid_classic_sql() -> None:
    from tools import stage_database_migrations as migrations
    inputs = migrations.select_inputs("vanilla")
    acid = [item for item in inputs if item.path.name == "acid_classic.sql"]
    assert len(acid) == 1, "the vanilla lane must keep its in-tree ACID file"
    assert "ACID" in str(acid[0].path)


def test_c5_flavor_selection_resolves_after_s4_rules_land() -> None:
    """Updated at the S4 wave (2026-10-08): the per-flavor migration-ledger
    selection rules landed (tools/stage_database_migrations.py S4 items 4/5),
    so select_inputs now RESOLVES tbc/wotlk against their own DB trees
    instead of raising S4SelectionNotImplemented. The fail-loud contract
    survives at the pin seam (FlavorPinError) and in the rule refusals
    (missing manifest seed, unreadable revision floors) — pinned in
    tests/test_s4_per_flavor_migration_ledger.py."""
    from tools import stage_database_migrations as migrations
    for flavor in ("tbc", "wotlk"):
        inputs = migrations.select_inputs(flavor)
        paths = [item.path.as_posix() for item in inputs]
        layout = flavor_layout.resolve(flavor)
        assert any(str(layout.db_submodule) in p for p in paths), (
            f"{flavor} selection must draw from its own DB repo")
        other = "classic-db-wotlk" if flavor == "tbc" else "classic-db-tbc"
        assert not any(other in p for p in paths), (
            f"{flavor} selection must never touch the {other} tree")


def test_stage_database_migrations_flavor_run_never_touches_vanilla_alias(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str],
    tmp_path: Path,
) -> None:
    """The clobber guard, updated at the S4 wave (2026-10-08): with the
    per-flavor rules landed, a tbc run no longer refuses — it WRITES
    schemas/database-migrations-tbc.json while the vanilla alias ledger
    (schemas/database-migrations.json) and its canonical byte copy stay
    byte-identical (the structural guard: tbc/wotlk layouts name no alias,
    docs/FLAVOR-ARTIFACT-NAMING.md §2/§3). Proven against a synthetic tree so
    the unit test never writes the repo's real ledgers."""
    from tools import stage_database_migrations as migrations

    # A synthetic repo root with the vanilla alias pair pre-sealed and a
    # minimal tbc input set; every path the stager writes resolves under
    # tmp_path because ROOT is monkeypatched.
    fake_root = tmp_path
    (fake_root / "schemas").mkdir()
    golden_alias = b'{"schema": 1, "sealed": "vanilla"}\n'
    (fake_root / "schemas" / "database-migrations.json").write_bytes(golden_alias)
    (fake_root / "schemas" / "database-migrations-vanilla.json").write_bytes(golden_alias)
    fake_input = fake_root / "native" / "classic-db-tbc" / "Full_DB" / "seed.sql.gz"
    fake_input.parent.mkdir(parents=True)
    import gzip as _gzip
    with fake_input.open("wb") as raw:
        with _gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as z:
            z.write(b"-- required_s2491_01_mangos_spillover_rename\n")

    monkeypatch.setattr(migrations, "ROOT", fake_root)
    monkeypatch.setattr(
        migrations, "select_inputs",
        lambda flavor: [migrations.Input("world", fake_input)],
    )
    monkeypatch.setattr(
        migrations, "expected_revisions",
        lambda flavor="vanilla": dict(migrations.EXPECTED_REVISIONS),
    )
    monkeypatch.setattr(
        migrations, "git_commit", lambda path: "0" * 40,
    )
    monkeypatch.setattr(
        sys, "argv",
        ["stage_database_migrations.py", "--flavor", "tbc", "--abi", "x86_64"],
    )
    rc = migrations.main()
    assert rc == 0, capsys.readouterr().err
    tbc_ledger = fake_root / "schemas" / "database-migrations-tbc.json"
    assert tbc_ledger.is_file(), "the tbc run must write its own canonical ledger"
    assert (fake_root / "schemas" / "database-migrations.json").read_bytes() == golden_alias, \
        "the vanilla alias ledger must not be clobbered"
    assert (fake_root / "schemas" / "database-migrations-vanilla.json").read_bytes() == golden_alias
    staged = (fake_root / "native/.build-x86_64-tbc/mariadb-staging/assets"
              / "database/migrations/manifest.json")
    assert staged.read_bytes() == tbc_ledger.read_bytes()


def test_seed_realm_db_flavor_parameter_fails_loud(
        monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str],
        tmp_path: Path) -> None:
    """S1 item 6, updated at the S4/P3 wave (2026-10-08): the tbc/wotlk
    server_core + db_content pins are registered AND checked out (P2), so
    `--flavor tbc|wotlk` no longer refuses on the "not implemented" branch —
    it seeds the flavor's OWN inputs (tools/seed_realm_db.py
    _flavor_seed_plan; pinned in tests/test_per_flavor_sqlite_seed.py). The
    fail-loud CONTRACT survives at its seam: when the pin check raises, the
    lane still exits rc=2 naming the remedy — never a silent classic-input
    seed under another flavor's name."""
    import seed_realm_db

    def _unresolved(flavor_id, **_kwargs):
        raise seed_realm_db.flavor_layout.FlavorPinError(
            f"flavor {flavor_id!r} db_content pin is unresolved — register "
            "the fork and commit pin (port plan S4 item 1) before building "
            "this lane")

    # Patch the module object seed_realm_db actually calls through (it
    # imports flavor_layout either as tools.flavor_layout or top-level).
    monkeypatch.setattr(seed_realm_db.flavor_layout,
                        "require_flavor_pins", _unresolved)
    monkeypatch.setattr(
        sys, "argv",
        ["seed_realm_db.py", "--out", str(tmp_path / "dbs"),
         "--flavor", "tbc"])
    rc = seed_realm_db.main()
    assert rc == 2
    captured = capsys.readouterr()
    assert "refusing to seed" in captured.err
    assert "S4 item 1" in captured.err  # the owning stage is named
    # Nothing was seeded: not the classic names, not the flavor names.
    assert not (tmp_path / "dbs").exists() or not any(
        (tmp_path / "dbs").iterdir())


def test_migration_staging_root_is_flavor_resolved() -> None:
    """S1 item 7: asset_root resolves the per-flavor staging root; vanilla
    keeps the historical literal the Gradle packaging gate validates."""
    from tools import stage_database_migrations as migrations
    assert migrations.asset_root("x86_64") == \
        ROOT / "native/.build-x86_64/mariadb-staging/assets/database/migrations"
    assert migrations.asset_root("arm64-v8a") == \
        ROOT / "native/.build-arm64/mariadb-staging/assets/database/migrations"
    assert migrations.asset_root("x86_64", "tbc") == \
        ROOT / "native/.build-x86_64-tbc/mariadb-staging/assets/database/migrations"


# ---------------------------------------------------------------------------
# manifest rules (check_flavor) + generator exclusion
# ---------------------------------------------------------------------------

def _manifest_copy() -> dict:
    return json.loads((ROOT / "schemas" / "flavor.json").read_text(encoding="utf-8"))


def _validate(tmp_path: Path, manifest: dict) -> list[str]:
    schemas = tmp_path / "schemas"
    schemas.mkdir(exist_ok=True)
    (schemas / "flavor.json").write_text(json.dumps(manifest), encoding="utf-8")
    from tools import check_flavor
    return check_flavor.validate(schemas / "flavor.json", sources_path=ROOT / "schemas" / "sources.json")


def test_c1_gamebuild_key_is_banned_anywhere(tmp_path: Path) -> None:
    manifest = _manifest_copy()
    manifest["flavors"][1]["realmd"]["gamebuild"] = 1
    errors = _validate(tmp_path, manifest)
    assert any("gamebuild" in error and "realmbuilds" in error for error in errors), errors


def test_c1_realmbuilds_must_include_own_build(tmp_path: Path) -> None:
    manifest = _manifest_copy()
    manifest["flavors"][1]["realmd"]["accepted_builds"] = "12340"
    errors = _validate(tmp_path, manifest)
    assert any("does not include the flavor's own build 8606" in error for error in errors), errors


def test_c3_shared_opcode_pin(tmp_path: Path) -> None:
    manifest = _manifest_copy()
    manifest["auth"]["smsg_auth_challenge_opcode"] = "0x1F0"
    errors = _validate(tmp_path, manifest)
    assert any("0x1EC" in error for error in errors), errors


def test_c8_cache_files_are_wotlk_only(tmp_path: Path) -> None:
    manifest = _manifest_copy()
    manifest["flavors"][1]["wtf"]["character_cache_files"] = ["config-cache.wtf"]
    errors = _validate(tmp_path, manifest)
    assert any("WotLK-era" in error and "tbc" in error for error in errors), errors


def test_c4_pinned_bots_bind_to_per_flavor_sources_entry() -> None:
    """The real manifest+sources pair must bind: tbc/wotlk bots pins are
    pinned (C4) and sources.json carries matching playerbots-tbc/-wotlk
    entries with the same commit."""
    from tools import check_flavor
    errors = check_flavor.validate(ROOT / "schemas" / "flavor.json")
    assert errors == [], errors
    sources = json.loads((ROOT / "schemas" / "sources.json").read_text(encoding="utf-8"))
    by_id = {entry["id"]: entry for entry in sources["sources"]}
    bots_commit = "7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d"
    for flavor in ("tbc", "wotlk"):
        entry = by_id[f"playerbots-{flavor}"]
        assert entry["commit"] == bots_commit
        assert entry["status"] == "registered"


def test_c9_generator_refuses_excluded_family_key(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """C9: a wotlk dist that suddenly carries a VoiceChat key is a loud
    refusal naming the key — never a silent emission into the matrix."""
    from tools import generate_conf_matrix as generator
    dist = tmp_path / "mangosd.conf.dist.in"
    dist.write_text(
        "# Enable voice chat\n"
        "VoiceChat.Enable = 1\n"
        "GameType = 0\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(generator, "flavor_surfaces", lambda layout: [dist])
    rc = generator.generate("wotlk", out=tmp_path / "matrix.json")
    assert rc == 2
    assert not (tmp_path / "matrix.json").exists()


def test_c9_generator_emits_matrix_and_strips_tokens(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    from tools import generate_conf_matrix as generator
    dist = tmp_path / "mangosd.conf.dist.in"
    dist.write_text(
        "# commented = unset\n"
        "#Motd = @MOTD@\n"
        "GameType = 0\n"
        "RealmZone = @REALM_ZONE@\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(generator, "flavor_surfaces", lambda layout: [dist])
    out = tmp_path / "matrix.json"
    assert generator.generate("vanilla", out=out) == 0
    record = json.loads(out.read_text(encoding="utf-8"))
    assert record["keys"]["GameType"] == {"file": "mangosd.conf.dist.in", "default": "0"}
    assert record["keys"]["Motd"] == {"file": "mangosd.conf.dist.in", "default": None}  # commented = unset
    assert record["keys"]["RealmZone"]["default"] == ""


def test_c9_generator_fails_loud_on_missing_dists(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    from tools import generate_conf_matrix as generator
    # wotlk's dists are not checked out earlier: the surfaces helper finds none.
    monkeypatch.setattr(
        generator, "flavor_surfaces",
        lambda layout: [tmp_path / "mangosd.conf.dist.in"])  # absent on purpose
    assert generator.generate("wotlk", out=tmp_path / "unused.json") == 2


# ---------------------------------------------------------------------------
# Win seam and o11 fail-loud lanes
# ---------------------------------------------------------------------------

def test_win_seam_resolves_registered_flavors() -> None:
    """P2-6 item 2, updated at the S2 wave and again at the P2 data wave
    (2026-10-08): tbc's overlay set is registered (the DLL is
    flavor-agnostic, decision D37) and wotlk's landed at its own S2 build —
    both resolve their per-flavor sqlite-win lockfiles before any MSVC
    environment is set up. The refusal contract stays at the
    flavor_layout pin seam (synthetic-tree tests above)."""
    from tools import build_win_sqlite_seam as seam
    for flavor in ("tbc", "wotlk"):
        layout = seam.resolve_flavor_or_die(flavor)
        assert layout.flavor_id == flavor
        assert seam.flavor_lockfile(layout) == (
            ROOT / f"schemas/realm-runtime-lockfile-sqlite-win-{flavor}.json"
        )


def test_win_seam_vanilla_resolves() -> None:
    from tools import build_win_sqlite_seam as seam
    layout = seam.resolve_flavor_or_die("vanilla")
    assert layout.flavor_id == "vanilla"
    assert seam.flavor_lockfile(layout) == ROOT / "schemas/realm-runtime-lockfile-sqlite-win.json"


def test_o11_lockfile_naming_is_flavor_resolved() -> None:
    sys.path.insert(0, str(ROOT / "tools"))
    from tools import build_o11_extractors as o11
    o11.select_flavor("vanilla")
    o11.select_abi("x86_64")
    assert o11.LOCKFILE == ROOT / "schemas/o11-extractor-lockfile.json"
    assert o11.SOURCE == ROOT / "native/cmangos"
    o11.select_flavor("vanilla")
    o11.select_abi("arm64-v8a")
    assert o11.LOCKFILE == ROOT / "schemas/o11-extractor-lockfile-arm64-v8a.json"


def test_o11_lockfile_is_per_flavor_once_a_pin_registers() -> None:
    """Regression guard for the verification-round-2 defect: select_abi must
    derive LOCKFILE from the flavor, not the ABI alone — a tbc/wotlk build
    with a registered pin writes ITS lockfile, never the shared vanilla one
    (the vanilla extractor provenance is pinned by check_repo's divergence
    check). select_flavor('tbc') is still refused today (unregistered pin),
    so drive FLAVOR_ID directly to exercise the resolution that will run
    once wave-0/S2 registers the pins."""
    from tools import build_o11_extractors as o11
    saved = (o11.FLAVOR_ID, o11.LOCKFILE)
    try:
        o11.FLAVOR_ID = "tbc"
        o11.select_abi("x86_64")
        assert o11.LOCKFILE == ROOT / "schemas/o11-extractor-lockfile-tbc.json"
        assert o11.STAGE.as_posix().endswith(".build-o11-x86_64-tbc/extractor-staging/jniLibs/x86_64")
        o11.select_abi("arm64-v8a")
        assert o11.LOCKFILE == ROOT / "schemas/o11-extractor-lockfile-tbc-arm64-v8a.json"
        o11.FLAVOR_ID = "wotlk"
        o11.select_abi("x86_64")
        assert o11.LOCKFILE == ROOT / "schemas/o11-extractor-lockfile-wotlk.json"
    finally:
        o11.FLAVOR_ID, o11.LOCKFILE = saved
        o11.select_abi("x86_64")
    assert o11.LOCKFILE == ROOT / "schemas/o11-extractor-lockfile.json"


def test_o11_unregistered_flavor_fails_loud() -> None:
    """Updated at the wotlk S2 wave (2026-10-06): wotlk's server_core pin +
    checkout landed (native/cmangos-wotlk pocket-realm c3a8d86f), so o11 —
    which verifies exactly that group — resolves it, same as tbc since its
    own S2."""
    from tools import build_o11_extractors as o11
    o11.select_flavor("tbc")
    assert o11.FLAVOR_ID == "tbc"
    o11.select_flavor("wotlk")
    assert o11.FLAVOR_ID == "wotlk"


# ---------------------------------------------------------------------------
# bots pin data + multi-expansion evidence
# ---------------------------------------------------------------------------

def test_c4_bots_pins_resolved_and_multi_expansion_tree_verified() -> None:
    """The C4 flavor.json resolution rests on the pinned tree genuinely being
    multi-expansion — re-verify the evidence here so the data cannot drift
    from its justification."""
    manifest = _manifest_copy()
    for flavor in ("tbc", "wotlk"):
        bots = next(f for f in manifest["flavors"] if f["id"] == flavor)["bots"]
        assert bots["status"] == "pinned"
        assert bots["commit"] == "7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d"
        assert bots["branch"] == "pocket-realm"
    cmake = (ROOT / "native/playerbots/CMakeLists.txt").read_text(encoding="utf-8")
    assert "MANGOSBOT_ZERO" in cmake and "MANGOSBOT_ONE" in cmake and "MANGOSBOT_TWO" in cmake
    bots_dir = ROOT / "native/playerbots/playerbot"
    assert (bots_dir / "aiplayerbot.conf.dist.in.tbc").is_file()
    assert (bots_dir / "aiplayerbot.conf.dist.in.wotlk").is_file()


def test_dirty_checkout_at_the_pinned_head_fails_provenance(tmp_path: Path) -> None:
    """Round-2 R2-3: a tampered working tree AT the pinned HEAD used to pass
    every provenance gate (only HEAD was compared). The pin attests the
    bytes too: a dirty submodule fails loud with the restore recipe."""
    import subprocess as sp
    repo = tmp_path / "native" / "cmangos-wotlk"
    repo.mkdir(parents=True)
    def git(*args: str) -> None:
        sp.run(["git", *args], cwd=repo, check=True, capture_output=True)
    git("init", "-q")
    git("config", "user.email", "t@t")
    git("config", "user.name", "t")
    (repo / "code.cpp").write_text("pristine\n", encoding="utf-8")
    git("add", ".")
    git("commit", "-q", "-m", "the pin")
    head_commit = sp.run(["git", "rev-parse", "HEAD"], cwd=repo,
                         check=True, capture_output=True, text=True).stdout.strip()
    manifest = flavor_layout.load_flavor_manifest()
    block = next(f for f in manifest["flavors"] if f["id"] == "wotlk")
    block["server_core"]["commit"] = head_commit
    block["server_core"]["status"] = "registered"
    # pristine tree at the pinned HEAD: passes
    pins = flavor_layout.require_flavor_pins(
        "wotlk", manifest=manifest, worktree=tmp_path, groups=("server_core",))
    assert pins["server_core"] == head_commit
    # tamper the working tree WITHOUT moving HEAD: must fail the dirty class
    (repo / "code.cpp").write_text("tampered\n", encoding="utf-8")
    with pytest.raises(flavor_layout.FlavorPinError, match="DIRTY") as excinfo:
        flavor_layout.require_flavor_pins(
            "wotlk", manifest=manifest, worktree=tmp_path, groups=("server_core",))
    assert "checkout -- ." in str(excinfo.value)


def test_submodule_gitlink_drift_marker_fails_loud(monkeypatch) -> None:
    """Round-3 R3-6: `git submodule status` prefixes '+' when the checkout
    is NOT the recorded gitlink — the old lstrip('+-') discarded exactly
    that signal before the pin comparison, so a checkout advanced past the
    recorded gitlink passed as the pin. The marker now fails loud."""
    import subprocess

    import check_sources

    class _Fake:
        def __init__(self, stdout: str) -> None:
            self.stdout = stdout

    def fake_run(*_args, **_kwargs):
        # '+<sha>' = checkout != recorded gitlink (the drift case)
        return _Fake("+3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a native/cmangos (heads/master)")

    monkeypatch.setattr(check_sources.subprocess, "run", fake_run)
    try:
        check_sources.submodule_commit(Path("native/cmangos"))
    except RuntimeError as exc:
        assert "NOT the recorded gitlink" in str(exc)
    else:
        raise AssertionError("the drift marker must fail loud, never return the sha")

    def fake_run_uninitialized(*_args, **_kwargs):
        return _Fake("-3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a native/cmangos (not initialized)")

    monkeypatch.setattr(check_sources.subprocess, "run", fake_run_uninitialized)
    try:
        check_sources.submodule_commit(Path("native/cmangos"))
    except RuntimeError as exc:
        assert "not initialized" in str(exc)
    else:
        raise AssertionError("an uninitialized gitlink must fail loud")

    def fake_run_clean(*_args, **_kwargs):
        return _Fake(" 3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a native/cmangos (heads/master)")

    monkeypatch.setattr(check_sources.subprocess, "run", fake_run_clean)
    assert check_sources.submodule_commit(Path("native/cmangos")) == "3a" * 20


# ---------------------------------------------------------------------------
# every per-flavor o11 extractor lockfile binds its
# cmangos_commit to the OWNING flavor's server_core pin — the CI covered
# only the two vanilla lockfiles, so a flavor core re-pin without an
# extractor rebuild silently left a stale provenance lockfile (the exact
# c096bada<->ce83805d split class closed for vanilla). Doctored-lockfile
# rotation on a synthetic tree (the two-file-lie pattern).
# ---------------------------------------------------------------------------

def _synthetic_o11_tree(tmp_path: Path) -> Path:
    """A minimal tree check_extractor_provenance can consume: the three
    flavors' server_core commits, all six o11 lockfiles at their own
    flavor's commit, and the two CMANGOS_COMMIT records (driver + store)."""
    import check_repo

    VANILLA, TBC, WOTLK = ("a1" * 20, "b2" * 20, "c3" * 20)
    root = tmp_path / "repo"
    (root / "schemas").mkdir(parents=True)
    (root / "tools").mkdir()
    store = (root / "android/app/src/main/java/com/pocketrealm/importer")
    store.mkdir(parents=True)
    (root / "schemas" / "flavor.json").write_text(json.dumps({
        "flavors": [
            {"id": "vanilla", "server_core": {"commit": VANILLA}},
            {"id": "tbc", "server_core": {"commit": TBC}},
            {"id": "wotlk", "server_core": {"commit": WOTLK}},
        ]
    }), encoding="utf-8")
    for flavor, commit in (("vanilla", VANILLA), ("tbc", TBC), ("wotlk", WOTLK)):
        suffix = "" if flavor == "vanilla" else f"-{flavor}"
        for name in (f"o11-extractor-lockfile{suffix}.json",
                     f"o11-extractor-lockfile{suffix}-arm64-v8a.json"):
            (root / "schemas" / name).write_text(
                json.dumps({"cmangos_commit": commit}), encoding="utf-8")
    (root / "tools" / "build_o09_realm_runtime.py").write_text(
        f'CMANGOS_COMMIT = "{VANILLA}"\n', encoding="utf-8")
    (store / "DataPreparationStore.kt").write_text(
        f'const val CMANGOS_COMMIT = "{VANILLA}"\n', encoding="utf-8")
    return root


def test_o11_per_flavor_lockfiles_bind_to_their_core_pin(tmp_path: Path) -> None:
    import check_repo

    root = _synthetic_o11_tree(tmp_path)
    errors: list[str] = []
    check_repo.check_extractor_provenance(errors, root)
    assert not errors, errors


def test_o11_doctored_flavor_lockfile_rotation_fails_naming_tbc(
        tmp_path: Path) -> None:
    """Set the tbc lockfile's cmangos_commit to VANILLA's commit (the
    re-pin-without-rebuild shape): the gate must fail NAMING tbc and the
    fix (rebuild + regenerate), not pass silently."""
    import check_repo

    root = _synthetic_o11_tree(tmp_path)
    tbc_lockfile = root / "schemas" / "o11-extractor-lockfile-tbc.json"
    tbc_lockfile.write_text(
        json.dumps({"cmangos_commit": "a1" * 20}), encoding="utf-8")
    errors: list[str] = []
    check_repo.check_extractor_provenance(errors, root)
    assert any("tbc" in error and "server_core commit is b2" in error
               for error in errors), errors
    assert any("build_o11_extractors.py --flavor tbc" in error
               for error in errors), errors
    # and the wotlk lockfile at its own pin stays silent about wotlk
    assert not any("wotlk server_core" in error for error in errors), errors


def test_o11_unknown_flavor_lockfile_name_fails_loud(tmp_path: Path) -> None:
    import check_repo

    root = _synthetic_o11_tree(tmp_path)
    (root / "schemas" / "o11-extractor-lockfile-mist.json").write_text(
        json.dumps({"cmangos_commit": "d4" * 20}), encoding="utf-8")
    errors: list[str] = []
    check_repo.check_extractor_provenance(errors, root)
    assert any("does not name a known flavor" in error for error in errors), errors


def test_o11_missing_flavor_lockfile_fails_naming_it(tmp_path: Path) -> None:
    import check_repo

    root = _synthetic_o11_tree(tmp_path)
    (root / "schemas" / "o11-extractor-lockfile-tbc.json").unlink()
    errors: list[str] = []
    check_repo.check_extractor_provenance(errors, root)
    assert any(
        "o11-extractor-lockfile-tbc.json" in error
        and "is missing" in error
        and "build_o11_extractors.py --flavor tbc" in error
        for error in errors
    ), errors


def test_real_o11_lockfiles_agree_with_their_flavor_pins() -> None:
    """The live-tree form of the same binding: all six committed o11
    lockfiles pin their own flavor's server_core commit today (verified at
    the review; this keeps the agreement from silently drifting before the
    gate above ever sees a synthetic tree)."""
    import check_repo
    from tools import flavor_layout

    errors: list[str] = []
    check_repo.check_extractor_provenance(errors, ROOT)
    assert not errors, errors
    for flavor in flavor_layout.FLAVOR_IDS:
        layout = flavor_layout.resolve(flavor)
        for abi in ("x86_64", "arm64-v8a"):
            path = ROOT / layout.o11_lockfile(abi)
            assert path.is_file(), path
            record = json.loads(path.read_text(encoding="utf-8"))
            manifest = json.loads(
                (ROOT / "schemas" / "flavor.json").read_text(encoding="utf-8"))
            expected = next(
                (f.get("server_core", {}).get("commit")
                 for f in manifest["flavors"] if f.get("id") == flavor), None)
            assert record["cmangos_commit"] == expected, (path, flavor)
