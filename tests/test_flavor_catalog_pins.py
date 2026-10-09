"""Integrity pins for the four generated tbc/wotlk catalogs.

R5-3-2 (review round 3): the committed catalogs carry in-file fidelity
claims (PUBLIC_ID_ORDER_SHA256 / SUPPORTED_ID_ORDER_SHA256 /
ID_ORDER_SHA256 / SETTING_COUNT) that NOTHING verified - unlike the
vanilla precedent (VanillaCatalogPinsTest + WowVanillaBindingCatalogTest).
This file pins them capture-free (parse the committed Kotlin, recompute
the hashes from the row source order) so the claims cannot rot silently.

R5-3-1 (review round 3): the generator's own "do not hand-edit;
regenerate" contract must hold - regenerating per the capture must
produce exactly the committed bytes. The producer and the committed
D44(2)-repaired catalogs drifted once (the emitted fixedReason rows
referenced identifiers that resolve to nothing); this pin catches that
class, and skips - loudly - only when the gitignored capture is absent.
"""
from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import generate_flavor_catalogs as gfc  # noqa: E402

CATALOGS = {
    "tbc": (
        ROOT / "android/app/src/main/java/com/pocketrealm/client/WowTbcBindingCatalog.kt",
        ROOT / "android/app/src/main/java/com/pocketrealm/ingame/WowTbcSettingsCatalog.kt",
    ),
    "wotlk": (
        ROOT / "android/app/src/main/java/com/pocketrealm/client/WowWotlkBindingCatalog.kt",
        ROOT / "android/app/src/main/java/com/pocketrealm/ingame/WowWotlkSettingsCatalog.kt",
    ),
}


def sha256_lines(lines: list) -> str:
    """The generator's own hash shape (tools/generate_flavor_catalogs.py)."""
    return hashlib.sha256(
        ("\n".join(lines) + "\n").encode("utf-8")).hexdigest()


def _capture_present(flavor: str) -> bool:
    return (gfc.CAPTURE_ROOT / flavor / "manifest.json").is_file()


# ------------------------------------------------------------- R5-3-1

@pytest.mark.parametrize("flavor", ("tbc", "wotlk"))
def test_regenerated_catalogs_match_the_committed_bytes(flavor):
    """The generator and the committed catalogs must agree byte-for-byte
    (trailing newline normalized): the catalogs declare 'do not
    hand-edit; regenerate', so a producer drift overwrites compile-clean
    catalogs with broken ones the next time someone follows the
    contract."""
    if not _capture_present(flavor):
        pytest.skip(
            f"LOUD NOTE: the gitignored FrameXML capture for {flavor} is "
            f"absent (out/flavor-capture/{flavor}/manifest.json) - run "
            "tools/capture_flavor_framexml.py against the staged client to "
            "re-verify the generator/committed-catalog contract")
    cfg = gfc.FLAVORS[flavor]
    binding = gfc.generate_bindings(flavor, cfg)
    settings = gfc.generate_settings(flavor, cfg)
    committed_binding = CATALOGS[flavor][0].read_text(encoding="utf-8")
    committed_settings = CATALOGS[flavor][1].read_text(encoding="utf-8")
    assert binding.strip() == committed_binding.strip(), (
        f"{flavor}: the BINDING catalog drifted from its generator - the "
        "catalog declares 'do not hand-edit; regenerate', so the producer "
        "and the committed bytes must agree (fix the generator, never the "
        "committed file alone)")
    assert settings.strip() == committed_settings.strip(), (
        f"{flavor}: the SETTINGS catalog drifted from its generator - the "
        "catalog declares 'do not hand-edit; regenerate', so the producer "
        "and the committed bytes must agree (fix the generator, never the "
        "committed file alone)")


# ------------------------------------------------------------- R5-3-2

@pytest.mark.parametrize("flavor", ("tbc", "wotlk"))
def test_binding_catalog_integrity_pins(flavor):
    """Recompute PUBLIC_ID_ORDER_SHA256 (the userFacing row order) and
    SUPPORTED_ID_ORDER_SHA256 (userFacing + internalPointer) from the
    committed Kotlin source and compare to the in-file consts."""
    text = CATALOGS[flavor][0].read_text(encoding="utf-8")
    user_block, pointer_block = text.split("val internalPointer", 1)
    user_ids = re.findall(r'key\("([A-Z0-9_]+)"', user_block)
    # the internalPointer rows are full WowBindingDefinition blocks
    pointer_ids = re.findall(r'id = "([A-Z0-9_]+)"', pointer_block)
    assert user_ids and pointer_ids, flavor
    public_const = re.search(
        r'PUBLIC_ID_ORDER_SHA256: String =\s*"([0-9a-f]{64})"', text)
    supported_const = re.search(
        r'SUPPORTED_ID_ORDER_SHA256: String =\s*"([0-9a-f]{64})"', text)
    assert public_const and supported_const, flavor
    assert public_const.group(1) == sha256_lines(user_ids), (
        f"{flavor}: PUBLIC_ID_ORDER_SHA256 no longer matches the "
        "userFacing row order - a hand edit or a stale regeneration")
    assert supported_const.group(1) == sha256_lines(
        user_ids + pointer_ids), (
        f"{flavor}: SUPPORTED_ID_ORDER_SHA256 no longer matches the "
        "userFacing+internalPointer row order")


@pytest.mark.parametrize("flavor", ("tbc", "wotlk"))
def test_settings_catalog_integrity_pins(flavor):
    """Recompute ID_ORDER_SHA256 and SETTING_COUNT from the committed
    WowSettingDefinition id order and compare to the in-file consts."""
    text = CATALOGS[flavor][1].read_text(encoding="utf-8")
    ids = re.findall(r'id = "([^"]+)"', text)
    count_const = re.search(r"SETTING_COUNT: Int = (\d+)", text)
    id_const = re.search(
        r'ID_ORDER_SHA256: String = "([0-9a-f]{64})"', text)
    assert ids and count_const and id_const, flavor
    assert int(count_const.group(1)) == len(ids), (
        f"{flavor}: SETTING_COUNT != the committed definition count")
    assert id_const.group(1) == sha256_lines(ids), (
        f"{flavor}: ID_ORDER_SHA256 no longer matches the committed "
        "definition id order")


@pytest.mark.parametrize("flavor", ("tbc", "wotlk"))
def test_fixed_reason_rows_reference_declared_consts(flavor):
    """The fixedReason-identifier pin: every `fixedReason = X` in both
    catalogs must name a `const val X` declared in the SAME file - the
    pre-D44 raw-identifier forms (fixedReason = MANAGED_DISPLAY) resolve
    to nothing and stop the catalog compiling (exactly the R5-3-1
    producer/repair drift)."""
    for catalog in CATALOGS[flavor]:
        text = catalog.read_text(encoding="utf-8")
        declared = set(re.findall(r"const val (FIXED_REASON_[A-Z_]+)", text))
        referenced = set(re.findall(r"fixedReason = ([A-Z_][A-Z0-9_]*)", text))
        unresolved = sorted(referenced - declared)
        assert not unresolved, (
            f"{catalog.name}: fixedReason rows reference undeclared "
            f"identifier(s) {unresolved} - the emitted object would not "
            "compile (regenerate or repair BOTH sides in one change)"
        )
