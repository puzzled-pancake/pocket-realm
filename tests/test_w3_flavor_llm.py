"""W3.2 flavor-LLM binding pins (shared lore corpus + per-flavor prompt
identity).

The owner decision (completion plan §8 table #2, approved default): ONE
shared lore corpus serves every flavor — no per-flavor jsonl is authored or
generated — and the per-flavor era filtering is the native LOAD-TIME lint
(PlayerbotLlmTruthCore EraLintCards against the ACTIVE stage's per-flavor
ban lists, W3.1's generated header). The prompt-pack identity line carries
the ACTIVE flavor's world title.

These pins hold the three surfaces to the manifest:
- schemas/flavor.json `llm.lore_cards` / `llm.prompt_identity` per flavor;
- the Kotlin mirror (ClientFlavor.loreCardsFile / .promptIdentity);
- the frozen vanilla identity wording (trained-output freeze).
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

FLAVOR_MANIFEST = ROOT / "schemas" / "flavor.json"
KOTLIN_REGISTRY = (
    ROOT / "android/app/src/main/java/com/pocketrealm/client/ClientFlavor.kt"
)

EXPECTED_IDENTITY = {
    "vanilla": "World of Warcraft: Classic",
    "tbc": "World of Warcraft: The Burning Crusade",
    "wotlk": "World of Warcraft: Wrath of the Lich King",
}
SHARED_CORPUS_ASSET = "lore_cards_v112.jsonl"


def _manifest() -> dict:
    return json.loads(FLAVOR_MANIFEST.read_text(encoding="utf-8"))


def test_every_flavor_carries_its_prompt_identity() -> None:
    """llm.prompt_identity per flavor — the identity titles the native
    renderer and the editor preview speak."""
    flavors = _manifest()["flavors"]
    for flavor in flavors:
        identity = (flavor.get("llm") or {}).get("prompt_identity")
        assert identity == EXPECTED_IDENTITY[flavor["id"]], (
            f"{flavor['id']}: llm.prompt_identity is {identity!r}, expected "
            f"{EXPECTED_IDENTITY[flavor['id']]!r} — the per-flavor identity "
            "strings are manifest data (W3.2), not code literals"
        )


def test_kotlin_registry_mirrors_graphics_routes_and_tweaks() -> None:
    """W3.6 (D15(4) retirement): the ClientFlavor registry's graphicsRoutes /
    vanillaTweaksEnabled mirror the manifest's wine block verbatim — vanilla
    keeps all three routes + tweaks; tbc/wotlk are DXVK-only + tweaks off
    (decision D3)."""
    text = KOTLIN_REGISTRY.read_text(encoding="utf-8")
    expected = {
        "vanilla": (["dxvk", "opengl", "virgl"], "true"),
        "tbc": (["dxvk"], "false"),
        "wotlk": (["dxvk"], "false"),
    }
    for flavor_id, (routes, tweaks) in expected.items():
        block = re.search(
            r'flavorId = "' + flavor_id + r'",.*?(?=\n    val [A-Z]+: ClientFlavor|\n    val ALL)',
            text,
            re.DOTALL,
        )
        assert block, "kotlin registry: no block for " + flavor_id
        body = block.group(0)
        routes_match = re.search(r"graphicsRoutes = setOf\(([^)]*)\)", body)
        assert routes_match, flavor_id + ": no graphicsRoutes in the registry block"
        parsed = re.findall(r'"([^"]+)"', routes_match.group(1))
        assert parsed == routes, (
            flavor_id + ": kotlin graphicsRoutes " + repr(parsed) +
            " != manifest wine graphics_routes " + repr(routes)
        )
        tweaks_match = re.search(r"vanillaTweaksEnabled = (true|false)", body)
        assert tweaks_match and tweaks_match.group(1) == tweaks, (
            flavor_id + ": kotlin vanillaTweaksEnabled drifted from the "
            "manifest wine.vanilla_tweaks"
        )
    # And the manifest itself still carries the projection's source data.
    flavors = _manifest()["flavors"]
    for flavor in flavors:
        wine = flavor.get("wine") or {}
        routes = wine.get("graphics_routes")
        assert isinstance(routes, list) and routes, (
            flavor["id"] + ": wine.graphics_routes missing from the manifest"
        )
        assert isinstance(wine.get("vanilla_tweaks"), bool), (
            flavor["id"] + ": wine.vanilla_tweaks missing from the manifest"
        )


def test_per_flavor_addon_catalogs_are_well_formed_new_assets() -> None:
    """W3.7 asset pins: the per-flavor addon catalogs are NEW assets (the
    # 2026-10-23 owner direction: the discovery catalogs merged in (TBC 137,
    # WotLK 353 incl. ConsolePortLK); the 49-row honest-short cap retired.
    vanilla catalog-v1.json bytes stay frozen, D29); each carries the same
    schema with self-describing counts, era-researched entries with real
    GitHub evidence, the AndroidPort built-in as a first-class row, and no
    Blizzard assets."""
    import hashlib
    import json as _json

    vanilla = ROOT / "android/app/src/main/assets/addons/catalog-v1.json"
    # The vanilla catalog is untouched by this wave (its sha256 is also
    # pinned in tools/check_repo.py — this leg double-checks the file the
    # parser reads).
    assert hashlib.sha256(vanilla.read_bytes()).hexdigest() == (
        "1741a486e919696be6569c5f28b2ef53841737cd339f40d7e85be2ee569d1dba"
    ), "the byte-frozen vanilla catalog moved (D29)"

    manifest_flavors = {f["id"]: f for f in _manifest()["flavors"]}
    for flavor in ("tbc", "wotlk"):
        interface = manifest_flavors[flavor]["addon_interfaces"][0]
        path = ROOT / f"android/app/src/main/assets/addons/catalog-{flavor}.json"
        assert path.is_file(), f"the {flavor} catalog asset is missing"
        data = _json.loads(path.read_text(encoding="utf-8"))
        assert data["schema"] == 1 and data["flavor"] == flavor
        addons = data["addons"]
        assert 1 <= len(addons) <= 500, (
            f"{flavor}: catalog size out of bounds, got {len(addons)}"
        )
        assert data["expectedAddonCount"] == len(addons)
        assert data["expectedCompatibilityPairCount"] == len(data["compatibilityPairs"])
        ids = [a["matrixId"] for a in addons]
        assert len(ids) == len(set(ids)), f"{flavor}: duplicate matrixIds"
        # Carry-over rows keep their vanilla matrixIds (the cross-flavor
        # identity key); NEW rows use the 2xx per-flavor namespace.
        for addon_id in ids:
            assert len(addon_id) == 3 and addon_id.isdigit()
        # Every entry carries real research fields and the flavor's client
        # line; the built-in row carries no GitHub home by design; no
        # Blizzard download URLs anywhere.
        token = "2.4.3" if flavor == "tbc" else "3.3.5"
        for addon in addons:
            assert token in addon["clientTarget"], addon["name"]
            assert addon["communitySignal"] and addon["maintenance"]
            if addon["installSource"] != "builtin":
                assert addon["githubUrl"] and addon["githubUrl"].startswith("https://github.com/")
                assert "blizzard" not in addon["githubUrl"].lower()
        # The AndroidPort built-in (151) installs by default on every game
        # version: the row must be present, builtin-sourced, and recommended.
        assert "151" in ids, (
            f"{flavor}: the AndroidPort built-in (151) must be present "
            "(the fresh-install seed resolves it per flavor)"
        )
        android_port = next(a for a in addons if a["matrixId"] == "151")
        assert android_port["installSource"] == "builtin"
        assert android_port["builtinId"] == "builtin__androidport"
        assert android_port["assetPath"] == "addons/android-port"
        assert "151" in data["recommendedIds"], (
            f"{flavor}: the AndroidPort built-in must sit in recommendedIds"
        )
        assert data["recommendationReasons"].get("151"), (
            f"{flavor}: the AndroidPort recommendation reason is missing"
        )
        assert data["flavorNote"], f"{flavor}: the catalog intro note is missing"
        # The pairs reference in-catalog rows only.
        for pair_entry in data["compatibilityPairs"]:
            assert pair_entry["addonA"] in ids and pair_entry["addonB"] in ids
