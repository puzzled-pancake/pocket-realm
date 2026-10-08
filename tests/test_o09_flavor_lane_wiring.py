"""Round-1 review pins: the o09 android driver's --flavor must be wired
end-to-end — a non-vanilla lane configures, builds into, records, and
hygiene-gates ITS OWN flavor tree and names, never the vanilla ones the
pre-fix driver hardcoded — and the --write-lockfiles warm-dir regen must
cover EVERY committed realm-runtime lockfile with each record's own
flavor/backend deciding the pins (a tbc/wotlk lockfile must never be
re-pinned with vanilla constants).

These tests never build: the lane entry points run against monkey-patched
toolchain/run functions and synthetic NATIVE trees, keeping the pins-only
CI posture the other lockfile tests document. select_flavor('tbc') runs
for real — the same require_flavor_pins verification
test_s1_flavor_parameterization.py already performs in CI.
"""
from __future__ import annotations

import inspect
import json
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from tools import build_o09_realm_runtime as o09  # noqa: E402
from tools import flavor_layout  # noqa: E402

TBC_CORE_PIN = "ffbf97675dc31f845587778f5bb993b232f1b6a2"
WOTLK_CORE_PIN = "c3a8d86fe4e61c3373eff424eb12cd7e7e8282b5"

# Realm-runtime lockfiles the warm-dir regen must cover when present. The
# historical android lanes and both previously-SKIPPED win siblings
# (mariadb-win, sqlite-win-tbc) pin the coverage contract; the wotlk win
# sibling is listed last because its presence rides the in-flight wotlk
# wave — the glob-driven assertion below covers it whenever it is present.
COMMITTED_LOCKFILES = (
    "realm-runtime-lockfile.json",
    "realm-runtime-lockfile-sqlite.json",
    "realm-runtime-lockfile-arm64-v8a.json",
    "realm-runtime-lockfile-arm64-v8a-sqlite.json",
    "realm-runtime-lockfile-mariadb-win.json",
    "realm-runtime-lockfile-sqlite-win.json",
    "realm-runtime-lockfile-sqlite-win-tbc.json",
    "realm-runtime-lockfile-sqlite-win-wotlk.json",
)
OPTIONAL_LOCKFILES = ("realm-runtime-lockfile-sqlite-win-wotlk.json",)


def _active_state() -> tuple:
    return (o09.ACTIVE_FLAVOR, o09.ACTIVE_CMANGOS_COMMIT,
            o09.ACTIVE_PLAYERBOTS_COMMIT, o09.ACTIVE_CORE_SUBMODULE,
            o09.ACTIVE_BOTS_SUBMODULE)


def _restore_active_state(saved: tuple) -> None:
    (o09.ACTIVE_FLAVOR, o09.ACTIVE_CMANGOS_COMMIT, o09.ACTIVE_PLAYERBOTS_COMMIT,
     o09.ACTIVE_CORE_SUBMODULE, o09.ACTIVE_BOTS_SUBMODULE) = saved


# ---------------------------------------------------------------------------
# F1: select_abi routes the build root and lockfile through flavor_layout
# ---------------------------------------------------------------------------

def test_select_abi_routes_build_root_and_lockfile_per_flavor() -> None:
    """Vanilla keeps the historical literals byte-for-byte (the naming
    schema's invariant 1); a flavor-resolved lane gets the -{flavor}-suffixed
    build root and lockfile — never the shared vanilla names."""
    saved = _active_state()
    try:
        o09.ACTIVE_FLAVOR = "vanilla"
        o09.select_abi("x86_64")
        assert o09.BUILD == ROOT / "native/.build-o09-x86_64"
        assert o09.LOCKFILE == ROOT / "schemas/realm-runtime-lockfile.json"
        o09.select_abi("arm64-v8a")
        assert o09.BUILD == ROOT / "native/.build-o09-arm64-v8a"
        assert o09.LOCKFILE == ROOT / "schemas/realm-runtime-lockfile-arm64-v8a.json"
        o09.ACTIVE_FLAVOR = "tbc"
        o09.select_abi("x86_64")
        assert o09.BUILD == ROOT / flavor_layout.resolve("tbc").o09_build_root("x86_64")
        assert o09.BUILD == ROOT / "native/.build-o09-x86_64-tbc"
        assert o09.LOCKFILE == ROOT / "schemas/realm-runtime-lockfile-tbc.json"
        o09.select_abi("arm64-v8a")
        assert o09.BUILD == ROOT / "native/.build-o09-arm64-v8a-tbc"
        assert o09.LOCKFILE == ROOT / "schemas/realm-runtime-lockfile-tbc-arm64-v8a.json"
    finally:
        _restore_active_state(saved)
        o09.select_abi("x86_64")


# ---------------------------------------------------------------------------
# F1: configure_and_build configures the ACTIVE flavor's tree and stamps
# the ACTIVE pin in the POCKET_BACKEND_VERIFY marker
# ---------------------------------------------------------------------------

def _fake_android_deps(native: Path) -> None:
    deps = native / ".deps" / "prefix-x86_64"
    (deps / "include" / "openssl").mkdir(parents=True)
    (deps / "include" / "openssl" / "ssl.h").write_bytes(b"")
    (deps / "lib" / "cmake" / "Boost-1.86.0").mkdir(parents=True)
    for name in ("libssl.a", "libcrypto.a", "libsqlite3.a"):
        (deps / "lib" / name).write_bytes(b"x")


def test_configure_and_build_uses_the_active_flavor_tree_and_pin(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}
    monkeypatch.setattr(o09, "tools", lambda: (
        tmp_path / "ndk", tmp_path / "cmake.exe", tmp_path / "ninja.exe",
        tmp_path / "llvm"))

    def fake_run(args, cwd=None):
        captured["build_args"] = args

    def fake_capture(args, cwd=None):
        captured["configure_args"] = args
        return "Pocket Realm database backend: SQLITE\n"

    monkeypatch.setattr(o09, "run", fake_run)
    monkeypatch.setattr(o09, "run_capture", fake_capture)
    monkeypatch.setattr(o09, "verify_backend_selection", lambda *a, **k: None)
    native = tmp_path / "native"
    _fake_android_deps(native)
    saved = _active_state()
    saved_globals = (o09.NATIVE, o09.CMANGOS_BUILD, o09.BUILD)
    try:
        o09.select_flavor("tbc")
        o09.select_abi("x86_64")
        o09.NATIVE = native
        o09.CMANGOS_BUILD = tmp_path / "cmangos-build"
        o09.configure_and_build(False, backend="sqlite", configure_only=True)
        args = [str(arg) for arg in captured["configure_args"]]
        source = Path(args[args.index("-S") + 1])
        assert source == native / "cmangos-tbc", (
            f"-S must point at the ACTIVE flavor tree, got {source}")
        marker = o09.CMANGOS_BUILD / "POCKET_BACKEND_VERIFY.sqlite.json"
        record = json.loads(marker.read_text(encoding="utf-8"))
        assert record["cmangos_commit"] == o09.ACTIVE_CMANGOS_COMMIT == TBC_CORE_PIN
    finally:
        _restore_active_state(saved)
        (o09.NATIVE, o09.CMANGOS_BUILD, o09.BUILD) = saved_globals
        o09.select_abi("x86_64")


# ---------------------------------------------------------------------------
# F1: stage() records the ACTIVE flavor's pins in provenance + lockfile
# ---------------------------------------------------------------------------

def test_stage_records_the_active_flavor_pins(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(o09, "run", lambda args, cwd=None: None)

    def fake_output(args, cwd=None):
        if "-dW" in args:
            return "  (NEEDED) [libc.so]\n"
        return "  LOAD 0x0 0x4000\n"

    monkeypatch.setattr(o09, "output", fake_output)
    build = tmp_path / "build"
    runtime = build / "cmangos" / "pocket-runtime-build"
    runtime.mkdir(parents=True)
    for name in ("libpocket_realmd_runtime.so", "libpocket_world_runtime.so"):
        (runtime / name).write_bytes(b"fake-so")
    (tmp_path / "native" / "patches").mkdir(parents=True)
    (tmp_path / "schemas").mkdir()
    saved = _active_state()
    saved_globals = (o09.ROOT, o09.NATIVE, o09.BUILD, o09.CMANGOS_BUILD,
                     o09.LOCKFILE, o09.BACKEND, o09.TARGET_ABI)
    try:
        o09.select_flavor("tbc")
        o09.select_abi("x86_64")
        o09.ROOT = tmp_path
        o09.NATIVE = tmp_path / "native"
        o09.BUILD = build
        o09.CMANGOS_BUILD = build / "cmangos"
        o09.LOCKFILE = tmp_path / "schemas" / "realm-runtime-lockfile-tbc.json"
        o09.BACKEND = "mysql"
        record = o09.stage(tmp_path / "llvm")
        assert record["cmangos_commit"] == o09.ACTIVE_CMANGOS_COMMIT == TBC_CORE_PIN
        assert record["playerbots_commit"] == o09.ACTIVE_PLAYERBOTS_COMMIT
        lock_record = json.loads(o09.LOCKFILE.read_text(encoding="utf-8"))
        assert lock_record["cmangos_commit"] == TBC_CORE_PIN
        assert lock_record["playerbots_commit"] == o09.PLAYERBOTS_COMMIT
        assert lock_record["cmangos_commit"] != o09.CMANGOS_COMMIT, (
            "a tbc lane must never record the vanilla pin")
    finally:
        _restore_active_state(saved)
        (o09.ROOT, o09.NATIVE, o09.BUILD, o09.CMANGOS_BUILD,
         o09.LOCKFILE, o09.BACKEND, o09.TARGET_ABI) = saved_globals
        o09.select_abi("x86_64")


# ---------------------------------------------------------------------------
# F1: main()'s hygiene gates inspect the ACTIVE flavor's submodule
# ---------------------------------------------------------------------------

def _git(path: Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=path, check=True, capture_output=True)


def _repo(path: Path, dirty: bool = False) -> None:
    path.mkdir(parents=True)
    _git(path, "init", "-q")
    _git(path, "config", "user.email", "t@t")
    _git(path, "config", "user.name", "t")
    (path / "README").write_text("x\n", encoding="utf-8")
    _git(path, "add", ".")
    _git(path, "commit", "-q", "-m", "init")
    if dirty:
        (path / "untracked-dirt.txt").write_text("dirt\n", encoding="utf-8")


def test_main_hygiene_gates_inspect_the_active_flavor_submodule(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
        capsys: pytest.CaptureFixture[str]) -> None:
    """A --flavor tbc run gates on native/cmangos-tbc: a DIRTY vanilla
    checkout (the tree the pre-fix main() inspected) must not fail the run,
    a clean flavor checkout must pass both the dirty-tree refusal and the
    post-build drift invariant, and a dirty FLAVOR checkout must be refused
    naming the flavor submodule."""
    native = tmp_path / "native"
    _repo(native / "cmangos", dirty=True)      # vanilla tree: not this lane's
    _repo(native / "cmangos-tbc")              # the tree this run overlays
    monkeypatch.setattr(o09, "NATIVE", native)
    monkeypatch.setattr(o09, "prepare_cmangos_source", lambda: None)
    monkeypatch.setattr(o09, "restore_cmangos_source", lambda: None)
    monkeypatch.setattr(o09, "configure_and_build", lambda *a, **k: (Path("llvm"), Path("cmake")))
    monkeypatch.setattr(sys, "argv", [
        "build_o09_realm_runtime.py", "--flavor", "tbc", "--abi", "x86_64",
        "--backend", "sqlite", "--configure-only"])
    saved = _active_state()
    saved_globals = (o09.NATIVE, o09.BUILD, o09.BACKEND)
    try:
        assert o09.main() == 0
        # The flavor tree's own dirt is what the gates refuse — loudly,
        # with the flavor submodule named.
        (native / "cmangos-tbc" / "flavor-dirt.txt").write_text("dirt\n", encoding="utf-8")
        with pytest.raises(RuntimeError, match="cmangos-tbc"):
            o09.main()
    finally:
        _restore_active_state(saved)
        (o09.NATIVE, o09.BUILD, o09.BACKEND) = saved_globals
        o09.select_abi("x86_64")


# ---------------------------------------------------------------------------
# F2: write_lockfiles covers every committed lockfile, pins per record
# ---------------------------------------------------------------------------

def test_write_lockfiles_refreshes_every_committed_lockfile_per_flavor(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """The warm-dir regen must reach EVERY committed realm-runtime lockfile
    (the pre-fix lanes list + win block skipped the mariadb-win and the
    -tbc/-wotlk win siblings) and derive each record's pins from its own
    flavor: an overlay edit no longer leaves those files stale, and a
    tbc/wotlk lockfile is never re-pinned with the vanilla constants."""
    committed = sorted(
        path.name for path in (ROOT / "schemas").glob("realm-runtime-lockfile*.json"))
    for name in COMMITTED_LOCKFILES:
        if name in OPTIONAL_LOCKFILES:
            continue
        assert name in committed, f"{name} is no longer committed; update the pin"
    schemas = tmp_path / "schemas"
    schemas.mkdir()
    originals: dict[str, dict] = {}
    for name in committed:
        payload = (ROOT / "schemas" / name).read_bytes()
        (schemas / name).write_bytes(payload)
        originals[name] = json.loads(payload)
    # An "edited" patches tree: only patches_content should force a rewrite.
    patches = tmp_path / "native" / "patches"
    patches.mkdir(parents=True)
    (patches / "edited.cpp").write_text("// edited\n", encoding="utf-8")
    monkeypatch.setattr(o09, "ROOT", tmp_path)
    monkeypatch.setattr(o09, "NATIVE", tmp_path / "native")
    updated = o09.write_lockfiles()
    assert sorted(updated) == sorted(f"schemas/{name}" for name in committed), (
        "every committed realm-runtime lockfile must be refreshed")
    expected_digests = o09.patches_content_digests()
    for name in committed:
        record = json.loads((schemas / name).read_text(encoding="utf-8"))
        flavor = record.get("flavor") or next(
            (candidate for candidate in ("tbc", "wotlk")
             if f"-{candidate}" in name), "vanilla")
        expected_core = {"vanilla": o09.CMANGOS_COMMIT,
                         "tbc": TBC_CORE_PIN,
                         "wotlk": WOTLK_CORE_PIN}[flavor]
        assert record["cmangos_commit"] == expected_core, name
        assert record["playerbots_commit"] == o09.PLAYERBOTS_COMMIT, name
        assert record["patches_content"] == expected_digests, name
        # Round-2 twin-drift pin: each record's overlay registry is the
        # flavor-resolved, registration-checked set its own lane's writer
        # records — overlays_for(flavor, backend family), with the win
        # mariadb lane's backend mapped onto the table's mysql family key —
        # never the vanilla shared table backend-filtered (the pre-fix win
        # twin, whose 'mariadb' filter emptied that record's registry).
        backend = record.get("database_backend", "mysql")
        family = "mysql" if backend == "mariadb" else backend
        assert record["cmangos_source_overlays"] == [
            dict(entry) for entry in o09.overlays_for(flavor, family)], name
        # Only the source-side pins move; the artifact pins still describe
        # the last full build of that lane.
        assert record["artifacts"] == originals[name]["artifacts"], name
        if "seed_transcripts" in originals[name]:
            assert record["seed_transcripts"] == originals[name]["seed_transcripts"], name
    # The previously-skipped lanes are pinned by name (whichever are on
    # disk — the historical skips were the mariadb-win and -tbc/-wotlk
    # win siblings).
    for name in ("realm-runtime-lockfile-mariadb-win.json",
                 "realm-runtime-lockfile-sqlite-win-tbc.json",
                 "realm-runtime-lockfile-sqlite-win-wotlk.json"):
        if name in committed:
            assert f"schemas/{name}" in updated


def test_write_lockfiles_win_overlays_follow_the_flavor_registration(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Round-2 twin-drift root-cause pin: the win branch of the warm-dir
    regen used to record the vanilla CMANGOS_OVERLAYS table backend-filtered
    while the win writer (build_win_realm_runtime.write_lockfile) records
    overlays_for(ACTIVE_FLAVOR, backend) — derivations that agreed only by
    coincidence, while every registered flavor set was the full
    flavor-scoped id set. Under a simulated per-flavor re-derivation (tbc's
    registered set narrowed by one overlay) the refreshed tbc win record
    must follow ITS flavor's registered set, not the vanilla table; and the
    mariadb record must carry the mysql-family set — the overlay table keys
    backends by family, so the raw 'mariadb' id filtered the pre-fix twin
    to nothing (the committed record's empty registry)."""
    schemas = tmp_path / "schemas"
    schemas.mkdir()
    win_names = ("realm-runtime-lockfile-mariadb-win.json",
                 "realm-runtime-lockfile-sqlite-win.json",
                 "realm-runtime-lockfile-sqlite-win-tbc.json")
    for name in win_names:
        (schemas / name).write_bytes((ROOT / "schemas" / name).read_bytes())
    (tmp_path / "native" / "patches").mkdir(parents=True)
    (tmp_path / "native" / "patches" / "x.cpp").write_text("// x\n", encoding="utf-8")
    monkeypatch.setattr(o09, "ROOT", tmp_path)
    monkeypatch.setattr(o09, "NATIVE", tmp_path / "native")
    narrowed = sorted(set(o09.PER_FLAVOR_OVERLAY_REGISTRATION["tbc"])
                      - {"llm-companion-event-hooks"})
    assert len(narrowed) < len(o09.PER_FLAVOR_OVERLAY_REGISTRATION["tbc"]), (
        "the narrowed registration must actually differ (vacuous pin)")
    monkeypatch.setitem(o09.PER_FLAVOR_OVERLAY_REGISTRATION, "tbc", narrowed)
    o09.write_lockfiles()
    tbc_ids = [entry["id"] for entry in json.loads(
        (schemas / "realm-runtime-lockfile-sqlite-win-tbc.json")
        .read_text(encoding="utf-8"))["cmangos_source_overlays"]]
    assert tbc_ids == [entry["id"] for entry in o09.overlays_for("tbc", "sqlite")]
    assert "llm-companion-event-hooks" not in tbc_ids
    vanilla_table_sqlite = [
        entry["id"] for entry in o09.CMANGOS_OVERLAYS
        if "sqlite" in entry.get("backends", ("mysql", "sqlite"))]
    assert tbc_ids != vanilla_table_sqlite, (
        "a flavor win lockfile must never be refreshed with the vanilla "
        "overlay table backend-filtered — the pre-fix twin drift")
    mariadb_ids = [entry["id"] for entry in json.loads(
        (schemas / "realm-runtime-lockfile-mariadb-win.json")
        .read_text(encoding="utf-8"))["cmangos_source_overlays"]]
    assert mariadb_ids == [entry["id"]
                           for entry in o09.overlays_for("vanilla", "mysql")], (
        "the mariadb win record must carry the mysql-family overlay set its "
        "lane builds, never the raw-'mariadb' empty filter")


def test_write_lockfiles_is_idempotent_in_the_sandbox(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """The per-record derivation must reproduce each lockfile's bytes
    exactly once refreshed: a second run over a current sandbox rewrites
    nothing and reports no updates (the same no-op contract
    test_db_async_null_guard's idempotence test enforces on a settled
    tree; run in a sandbox here because the shared tree's lockfiles ride
    the in-flight overlay edits until the next deliberate regen)."""
    schemas = tmp_path / "schemas"
    schemas.mkdir()
    for path in (ROOT / "schemas").glob("realm-runtime-lockfile*.json"):
        (schemas / path.name).write_bytes(path.read_bytes())
    (tmp_path / "native" / "patches").mkdir(parents=True)
    (tmp_path / "native" / "patches" / "x.cpp").write_text("// x\n", encoding="utf-8")
    monkeypatch.setattr(o09, "ROOT", tmp_path)
    monkeypatch.setattr(o09, "NATIVE", tmp_path / "native")
    o09.write_lockfiles()
    names = sorted(p.name for p in schemas.glob("realm-runtime-lockfile*.json"))
    before = {name: (schemas / name).read_bytes() for name in names}
    assert o09.write_lockfiles() == []
    for name, payload in before.items():
        assert (schemas / name).read_bytes() == payload, name


# ---------------------------------------------------------------------------
# F3/F4: configure locals deduplicated; no replace_anchor twin in the
# restore path
# ---------------------------------------------------------------------------

def test_configure_locals_are_computed_once() -> None:
    """The shared configure locals (toolchain/ndk_triple/zlib/common) must
    exist exactly once in configure_and_build — the pre-fix duplicate
    recomputation is how a future edit feeds one cmake call stale flags."""
    source = inspect.getsource(o09.configure_and_build)
    assert source.count("android.toolchain.cmake") == 1
    assert source.count('"-G", "Ninja"') == 1
    assert source.count("-DANDROID_PLATFORM=android-26") == 1


def test_restore_cmangos_source_has_no_replace_anchor_twin() -> None:
    """Every restore in restore_cmangos_source must go through restore_anchor
    (or a dedicated restore helper) — the pre-fix RESULT_QUEUE restore used
    replace_anchor, a drifted twin in a restore path."""
    source = inspect.getsource(o09.restore_cmangos_source)
    assert "replace_anchor(" not in source
    # non-vacuous: the RESULT_QUEUE site is still restored, via restore_anchor
    assert "restore_anchor(\n        cmangos / \"src\" / \"shared\" / \"Database\" / \"SqlOperations.cpp\",\n        RESULT_QUEUE_ANDROID,\n        RESULT_QUEUE_UPSTREAM,\n    )" in source
