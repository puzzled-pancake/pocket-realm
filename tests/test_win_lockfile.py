"""Windows realm-runtime lockfile pins.

schemas/realm-runtime-lockfile-sqlite-win.json is the Windows lane's twin
of the android realm-runtime lockfiles: same source-side pins (the same
engine-fix overlay set must apply on both platforms), PE import tables
where the android lockfiles pin ELF DT_NEEDED, and the BuildConfig runtime
telltale pinned to the derivable-current value.

CI posture mirrors the android lanes' pins-only mode: the artifact sha
pins describe the dev box's untracked build outputs and are NOT compared
against staged bytes here (a fresh CI machine rebuilds its own DLLs);
everything derivable from the committed tree IS compared.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

WIN_LOCKFILE = ROOT / "schemas" / "realm-runtime-lockfile-sqlite-win.json"
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


def test_patches_content_pins_the_working_tree() -> None:
    import build_o09_realm_runtime as driver
    record = _record()
    pinned = record.get("patches_content")
    assert isinstance(pinned, dict) and pinned, "win lockfile predates patches pinning"
    assert pinned == driver.patches_content_digests(), \
        "win lockfile patches_content is stale vs the working tree"


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
        imports = artifact["pe_imports"]
        assert isinstance(imports, list) and imports, \
            f"{artifact['path']}: empty import table pin"
        assert imports == sorted(imports), f"{artifact['path']}: imports unsorted"
        forbidden = [dll for dll in imports
                     if dll.lower().startswith(FORBIDDEN_IMPORT_PREFIXES)]
        assert not forbidden, (
            f"{artifact['path']} imports unpackaged DLLs {forbidden}: the "
            "static-OpenSSL overlay (win-static-openssl-only) regressed")


def test_build_config_telltale_matches_lockfile() -> None:
    """Desktop BuildConfig is hand-maintained; this recomputes the id with
    the SAME formula the Android build derives from ITS lockfile and fails
    on drift, so the shared staleness telltale can never go stale silently."""
    record = _record()
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
        "--write-lockfile and sync BuildConfig.kt")
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
