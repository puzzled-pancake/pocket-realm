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
- the generated native selector (ExpansionTitleFor in
  PlayerbotLlmEraStages.generated.h, regenerated from the manifests);
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
ERA_HEADER = (
    ROOT / "native/patches/playerbots/PlayerbotLlmStages"
)

EXPECTED_IDENTITY = {
    "vanilla": "World of Warcraft: Classic",
    "tbc": "World of Warcraft: The Burning Crusade",
    "wotlk": "World of Warcraft: Wrath of the Lich King",
}
SHARED_CORPUS_ASSET = "lore_cards_v112.jsonl"


def _manifest() -> dict:
    return json.loads(FLAVOR_MANIFEST.read_text(encoding="utf-8"))


def test_every_flavor_binds_the_shared_lore_corpus() -> None:
    """The W3.2 decision is DATA: every flavor's llm.lore_cards names the
    ONE shared corpus asset (no per-flavor jsonl), and the asset exists."""
    flavors = _manifest()["flavors"]
    bound = {
        f["id"]: (f.get("llm") or {}).get("lore_cards") for f in flavors
    }
    assert bound == {fid: SHARED_CORPUS_ASSET for fid in EXPECTED_IDENTITY}, (
        f"llm.lore_cards bindings drifted from the shared-corpus decision: "
        f"{bound} — a per-flavor jsonl would violate the recorded W3.2 "
        "decision (D79); the era filtering is the load-time lint, not a "
        "second corpus"
    )
    asset = ROOT / "android/app/src/main/assets/lore" / SHARED_CORPUS_ASSET
    assert asset.is_file(), "the shared lore corpus asset is missing"


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


def test_kotlin_registry_mirrors_the_llm_bindings() -> None:
    """The ClientFlavor registry mirror (loreCardsFile / promptIdentity)
    carries the manifest values verbatim — the D15(5) retirement contract:
    ServerRuntimeFiles resolves through the registry, so a manifest edit
    must land here in the same change."""
    text = KOTLIN_REGISTRY.read_text(encoding="utf-8")
    for flavor_id, identity in EXPECTED_IDENTITY.items():
        block = re.search(
            rf"flavorId = \"{flavor_id}\",.*?(?=\n    val [A-Z]+: ClientFlavor|\n    val ALL)",
            text,
            re.DOTALL,
        )
        assert block, f"kotlin registry: no block for {flavor_id}"
        body = block.group(0)
        corpus = re.search(r'loreCardsFile = "([^"]+)"', body)
        assert corpus and corpus.group(1) == SHARED_CORPUS_ASSET, (
            f"{flavor_id}: kotlin loreCardsFile drifted from the manifest "
            "binding"
        )
        prompt = re.search(r'promptIdentity = "([^"]+)"', body)
        assert prompt and prompt.group(1) == identity, (
            f"{flavor_id}: kotlin promptIdentity drifted from the manifest"
        )


def test_generated_header_selector_serves_the_manifest_identities() -> None:
    """The generated ExpansionTitleFor selector (PlayerbotLlmEraStages
    .generated.h) resolves every flavor prefix to the manifest's identity —
    the generator is the only writer, the pin keeps the chain honest."""
    header = (
        ROOT / "native/patches/playerbots/PlayerbotLlmEraStages.generated.h"
    ).read_text(encoding="utf-8")
    selector = re.search(
        r"inline std::string ExpansionTitleFor\(std::string const& stage\)\s*\{(.*?)\n\}",
        header,
        re.DOTALL,
    )
    assert selector, "the generated header lost the ExpansionTitleFor selector"
    body = selector.group(1)
    for flavor_id, identity in EXPECTED_IDENTITY.items():
        needle = f'"{identity}"'
        assert needle in body, (
            f"the generated selector lost the {flavor_id} identity {needle!r} "
            "— regenerate: python tools/generate_timeframe_era.py era"
        )
    # every flavor prefix routes before the default
    for flavor_id in EXPECTED_IDENTITY:
        assert f'"{flavor_id}-"' in body, (
            f"the selector has no {flavor_id}- prefix route — a tbc/wotlk "
            "stage would silently render the vanilla title"
        )
    # and the check-timeframes chain is green (the pin JSON covers the header)
    import subprocess

    result = subprocess.run(
        ["python", str(ROOT / "tools" / "generate_timeframe_era.py"), "check"],
        capture_output=True, text=True, timeout=120,
    )
    assert result.returncode == 0, result.stdout + result.stderr


def test_lore_cards_filename_const_is_retired_from_both_twins() -> None:
    """D15(5) retirement: the duplicated LORE_CARDS_FILE_NAME consts are
    GONE from both ServerRuntimeFiles twins — the staged file name resolves
    through ClientFlavor.loreCardsFile (a re-introduced const would silently
    fork the manifest binding again)."""
    for twin in (
        "android/app/src/main/java/com/pocketrealm/server/ServerRuntimeFiles.kt",
        "desktop/src/main/kotlin/com/pocketrealm/server/ServerRuntimeFiles.kt",
    ):
        text = (ROOT / twin).read_text(encoding="utf-8")
        assert "LORE_CARDS_FILE_NAME" not in text, (
            f"{twin}: the LORE_CARDS_FILE_NAME const re-appeared — the "
            "staged lore file name resolves through ClientFlavor."
            "loreCardsFile (the manifest llm.lore_cards binding, W3.2/D79)"
        )
        assert "loreCardsFileName" in text, (
            f"{twin}: the loreCardsFileName resolver is missing"
        )


def test_build_lane_lint_still_consumes_the_vanilla_manifest() -> None:
    """The corpus BUILD lane stays the vanilla scrub (the shared corpus is
    built once with the vanilla filter); its lint consumes the vanilla
    manifest single-source. The per-flavor filtering happens at LOAD time in
    the native core — this pin keeps the build lane's contract stated so a
    future 'per-flavor corpus' idea must revisit the decision, not fork the
    lint silently."""
    builder = (ROOT / "tools/llm_lab/build_lore_cards.py").read_text(encoding="utf-8")
    assert '"vanilla.json"' in builder, (
        "the build-time era lint no longer reads the vanilla manifest — "
        "the shared corpus is the vanilla-scrubbed build (W3.2/D79)"
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
    vanilla catalog-v1.json bytes stay frozen, D29); each carries the same
    schema with self-describing counts, era-researched entries with real
    GitHub evidence, the AndroidPort exclusion note, and no Blizzard
    assets."""
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
        assert 1 <= len(addons) <= 49, (
            f"{flavor}: an honestly short researched catalog, got {len(addons)}"
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
        # line; no Blizzard download URLs anywhere.
        token = "2.4.3" if flavor == "tbc" else "3.3.5"
        for addon in addons:
            assert token in addon["clientTarget"], addon["name"]
            assert addon["githubUrl"] and addon["githubUrl"].startswith("https://github.com/")
            assert addon["communitySignal"] and addon["maintenance"]
            assert "blizzard" not in addon["githubUrl"].lower()
        assert "151" not in ids, (
            f"{flavor}: the AndroidPort built-in (151) must be absent "
            "(owner decision, completion-plan table #8)"
        )
        assert "AndroidPort" in data["flavorNote"], (
            f"{flavor}: the honest AndroidPort exclusion copy is missing"
        )
        # The pairs reference in-catalog rows only.
        for pair_entry in data["compatibilityPairs"]:
            assert pair_entry["addonA"] in ids and pair_entry["addonB"] in ids
