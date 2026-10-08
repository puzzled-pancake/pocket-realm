"""Flavor parameterization pins for tools/stage_o07_client.py (F12, S5 item 8
host fixture; lanes item 13).

The staging lane's client identity (client id, version tuple, exe hashes,
MPQ exact set, root-DLL allowlist, locale label) must resolve from
schemas/flavor.json through tools/flavor_layout — never a per-flavor literal
in the tool. These tests pin:

- load_profile resolves every registered flavor from the REAL manifest and
  agrees with it field-for-field (the S5.1 registration is the data the lane
  consumes), and refuses an unknown flavor id through flavor_layout.resolve;
- parse_wow_exe accepts a PE carrying the flavor's own version tuple and
  rejects the same blob under another flavor's profile (VAL-03 names the
  expected version resource);
- scan_source validates the locale-segmented MPQ layout (Data\\enUS\\...) a
  later-flavor client carries, reports the flavor's clientId/locale, fails
  VAL-04 naming a missing locale MPQ, and fails VAL-04 on a bad MPQ magic
  inside the locale directory — while vanilla's flat Data/ layout keeps its
  historical behavior (F12 is an additive parameterization: vanilla rows
  must not change).

All expected identities are DERIVED from the manifest (no build literals in
this file), so a future qualification flip or re-registration updates these
pins through data alone.

The VAL-08 plausibility bounds are monkeypatched down for the synthetic
sub-gigabyte trees; the production bounds themselves are pinned separately.
"""
from __future__ import annotations

import struct
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from tools import flavor_layout  # noqa: E402
from tools import stage_o07_client as o07  # noqa: E402


def _manifest_block(flavor_id: str) -> dict:
    manifest = flavor_layout.load_flavor_manifest()
    return next(f for f in manifest["flavors"] if f["id"] == flavor_id)


def _version_tuple(block: dict) -> tuple[int, int, int, int]:
    return tuple(int(p) for p in block["version_resource"].split("."))


def _synthetic_exe(version_tuple: tuple[int, int, int, int]) -> bytes:
    """Minimal PE32 carrying a VS_VERSIONINFO-shaped version tuple at a
    0xFEEF04BD signature — exactly the bytes parse_wow_exe decodes."""
    size = 0x400
    blob = bytearray(size)
    blob[0:2] = b"MZ"
    pe_offset = 0x80
    struct.pack_into("<I", blob, 0x3C, pe_offset)
    blob[pe_offset:pe_offset + 4] = b"PE\0\0"
    struct.pack_into("<H", blob, pe_offset + 4, 0x14C)   # IMAGE_FILE_MACHINE_I386
    struct.pack_into("<H", blob, pe_offset + 24, 0x10B)  # PE32 optional magic
    pos = 0x200
    struct.pack_into("<I", blob, pos, 0xFEEF04BD)
    ms = (version_tuple[0] << 16) | version_tuple[1]
    ls = (version_tuple[2] << 16) | version_tuple[3]
    struct.pack_into("<II", blob, pos + 8, ms, ls)
    return bytes(blob)


@pytest.fixture()
def sub_gigabyte_bounds(monkeypatch: pytest.MonkeyPatch) -> None:
    """Let synthetic trees pass VAL-08 without weakening the real bounds
    (pinned by test_val08_production_bounds_stay_put below)."""
    monkeypatch.setattr(o07, "MIN_FILE_COUNT", 1)
    monkeypatch.setattr(o07, "MIN_TOTAL_BYTES", 0)


def _client_tree(root: Path, flavor_id: str) -> Path:
    """A synthetic client for the flavor: WoW.exe carrying its version tuple
    plus every required MPQ (valid magic) under Data/ — locale segments
    included, per the manifest's mpq_set.required convention."""
    profile = o07.load_profile(flavor_id)
    block = _manifest_block(flavor_id)
    root.mkdir(parents=True)
    (root / "WoW.exe").write_bytes(_synthetic_exe(_version_tuple(block)))
    data = root / "Data"
    for relative in sorted(profile.required_mpqs):
        target = data.joinpath(*relative.split("/"))
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"MPQ\x1a" + b"\x00" * 16)
    (root / "realmlist.wtf").write_text("set realmlist 127.0.0.1\n")
    return root


# ---------------------------------------------------------------------------
# load_profile: the manifest is the data source
# ---------------------------------------------------------------------------

def test_load_profile_resolves_every_registered_flavor_from_the_manifest() -> None:
    for flavor_id in flavor_layout.FLAVOR_IDS:
        profile = o07.load_profile(flavor_id)
        block = _manifest_block(flavor_id)
        assert profile.flavor_id == flavor_id
        assert profile.client_id == block["client_id"]
        assert profile.version_resource == block["version_resource"]
        assert profile.version_tuple == _version_tuple(block)
        assert profile.expected_exe_sha256 == block["wine"]["known_exe_sha256"]
        assert profile.expected_exe_size == block["client_binary"]["known_exe_size_bytes"]
        assert profile.required_mpqs == frozenset(
            name.lower() for name in block["mpq_set"]["required"]
        )


def test_load_profile_carries_the_locale_segmented_sets_and_dll_allowlists() -> None:
    """The later flavors' MPQ sets carry the Data\\<locale>\\ segment the scan
    must traverse, and wotlk's staged root carries its measured extra DLLs."""
    tbc = o07.load_profile("tbc")
    assert "enus/locale-enus.mpq" in tbc.required_mpqs
    assert tbc.locale_label == "enUS-locale-dir"
    wotlk = o07.load_profile("wotlk")
    assert "enus/lichking-locale-enus.mpq" in wotlk.required_mpqs
    assert {"battle.net.dll", "msvcr80.dll"} <= wotlk.root_dlls
    vanilla = o07.load_profile("vanilla")
    assert all("/" not in name for name in vanilla.required_mpqs)
    assert vanilla.locale_label == "enUS-flat-classic"
    # The vanilla base DLL set is shared; later flavors only extend it.
    assert vanilla.root_dlls <= tbc.root_dlls


def test_load_profile_refuses_unknown_flavor_ids() -> None:
    with pytest.raises(ValueError, match="valid flavors"):
        o07.load_profile("cataclysm")


def test_load_profile_refuses_an_unregistered_identity(monkeypatch: pytest.MonkeyPatch) -> None:
    """Fail-closed: a flavor block without hashes/MPQ set must refuse to
    stage even if its id resolves (the pre-S5.1 tbc/wotlk shape)."""
    import json

    manifest = flavor_layout.load_flavor_manifest()
    block = next(f for f in manifest["flavors"] if f["id"] == "tbc")
    block["wine"]["known_exe_sha256"] = None
    monkeypatch.setattr(
        flavor_layout, "load_flavor_manifest", lambda: json.loads(json.dumps(manifest))
    )
    with pytest.raises(o07.ImportFailure, match="registered exe identity"):
        o07.load_profile("tbc")


# ---------------------------------------------------------------------------
# parse_wow_exe: the version tuple is flavor data
# ---------------------------------------------------------------------------

def test_parse_wow_exe_accepts_the_flavors_own_version_tuple(tmp_path: Path) -> None:
    for flavor_id in ("tbc", "wotlk"):
        profile = o07.load_profile(flavor_id)
        block = _manifest_block(flavor_id)
        exe = tmp_path / f"{flavor_id}-wow.exe"
        exe.write_bytes(_synthetic_exe(_version_tuple(block)))
        identity = o07.parse_wow_exe(exe, profile)
        assert identity["version"] == block["version_resource"]
        assert identity["build"] == block["build"]


def test_parse_wow_exe_rejects_a_foreign_version_tuple(tmp_path: Path) -> None:
    """The vanilla profile must refuse a later-flavor exe AND name the
    expected version resource (VAL-03), never a generic parse failure."""
    vanilla = o07.load_profile("vanilla")
    tbc_block = _manifest_block("tbc")
    exe = tmp_path / "foreign.exe"
    exe.write_bytes(_synthetic_exe(_version_tuple(tbc_block)))
    with pytest.raises(o07.ImportFailure, match="VAL-03"):
        o07.parse_wow_exe(exe, vanilla)


# ---------------------------------------------------------------------------
# scan_source: the locale-segmented exact set
# ---------------------------------------------------------------------------

def test_scan_source_validates_a_locale_segmented_client(
    tmp_path: Path, sub_gigabyte_bounds: None
) -> None:
    profile = o07.load_profile("tbc")
    root = _client_tree(tmp_path / "src", "tbc")
    files, identity = o07.scan_source(root, profile)
    block = _manifest_block("tbc")
    assert identity["clientId"] == block["client_id"]
    assert identity["locale"] == "enUS-locale-dir"
    warnings = identity["warnings"]
    assert any("LOCALE_ENUS_INFERRED" in w for w in warnings)
    # The synthetic exe is not the registered pristine hash: warn, not reject.
    assert any("not on the local evidence allowlist" in w for w in warnings)
    # Every staged file is accounted for, locale directories included.
    assert any(f.relative == "Data/enus/locale-enus.mpq" for f in files)


def test_scan_source_missing_locale_mpq_fails_val04(
    tmp_path: Path, sub_gigabyte_bounds: None
) -> None:
    profile = o07.load_profile("tbc")
    root = _client_tree(tmp_path / "src", "tbc")
    target = root / "Data" / "enus" / "patch-enus-2.mpq"
    target.unlink()
    with pytest.raises(o07.ImportFailure, match=r"VAL-04: missing base MPQ set: enus/patch-enus-2\.mpq"):
        o07.scan_source(root, profile)


def test_scan_source_bad_locale_mpq_magic_fails_val04(
    tmp_path: Path, sub_gigabyte_bounds: None
) -> None:
    profile = o07.load_profile("wotlk")
    root = _client_tree(tmp_path / "src", "wotlk")
    (root / "Data" / "enus" / "speech-enus.mpq").write_bytes(b"NOTMPQ!")
    with pytest.raises(o07.ImportFailure, match=r"VAL-04: invalid MPQ header: Data/enus/speech-enus\.mpq"):
        o07.scan_source(root, profile)


def test_scan_source_vanilla_flat_layout_keeps_its_shape(
    tmp_path: Path, sub_gigabyte_bounds: None
) -> None:
    """F12 is additive: vanilla's flat Data/ scan behaves exactly as before
    (flat locale warning, flat required set, vanilla clientId)."""
    profile = o07.load_profile("vanilla")
    root = _client_tree(tmp_path / "src", "vanilla")
    _files, identity = o07.scan_source(root, profile)
    block = _manifest_block("vanilla")
    assert identity["clientId"] == block["client_id"]
    assert identity["locale"] == "enUS-flat-classic"
    assert any("FLAT_ENGLISH_LOCALE_INFERRED" in w for w in identity["warnings"])


def test_val08_production_bounds_stay_put() -> None:
    """The monkeypatched test bounds must not leak into production: the
    module-level constants keep their historical values (1..20 GiB,
    20..100k files)."""
    assert o07.MIN_FILE_COUNT == 20
    assert o07.MAX_FILE_COUNT == 100_000
    assert o07.MIN_TOTAL_BYTES == 1 * 1024**3
    assert o07.MAX_TOTAL_BYTES == 20 * 1024**3
