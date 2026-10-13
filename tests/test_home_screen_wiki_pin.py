"""Doc-drift pin (R6-2, code-red round 1): every user-reachable Home-card
state string the Home screen can render must appear in the user wiki's
Home-Screen page, so a new state cannot ship without its wiki row."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HOME_SCREEN = (
    ROOT / "android/app/src/main/java/com/pocketrealm/ui/HomeScreen.kt"
)
WIKI_PAGE = ROOT / "docs/wiki/Home-Screen.md"


def _home_card_states() -> list[str]:
    """The left-hand state names of realmStatus()'s `X to Y` pairs."""
    source = HOME_SCREEN.read_text(encoding="utf-8")
    body = source.split("private fun realmStatus(")[1]
    body = body.split("\nprivate fun ")[0]
    names = re.findall(r'"([A-Z][^"]{2,48})" to', body)
    seen: list[str] = []
    for name in names:
        if name not in seen:
            seen.append(name)
    return seen


def test_every_home_card_state_has_a_wiki_row():
    wiki = WIKI_PAGE.read_text(encoding="utf-8")
    states = _home_card_states()
    assert states, "the realmStatus parser found nothing - re-pin it"
    missing = [state for state in states if state not in wiki]
    assert not missing, (
        "Home-card states without a wiki row (docs/wiki/Home-Screen.md): "
        + repr(missing)
    )


def test_the_game_flavor_card_is_documented():
    """The Game flavor switcher card shipped in W0's diff; its copy and its
    unqualified-off rule belong on the page. R6-6 (W4 code-red round 3):
    the round-2 revision had tautologized this pin with a permanent
    `if False else` — the registry-state sentence the wave added to the
    wiki was asserted NOWHERE. Both sentences are pinned for real now, and
    the qualified-count claim must agree with schemas/flavor.json."""
    import json
    wiki = WIKI_PAGE.read_text(encoding="utf-8")
    source = HOME_SCREEN.read_text(encoding="utf-8")
    assert '"Game flavor"' in source
    assert "visible but unselectable." in wiki, (
        "the wiki page lost the flavor-card phrase: 'visible but "
        "unselectable.'")
    normalized = " ".join(wiki.split())
    assert (
        "all three registered lanes are qualified since the W4 flip "
        "(decision D86), so every chip is selectable today." in normalized
    ), ("the wiki page lost the registry-state sentence the W4 flip added "
        "(all three lanes qualified, decision D86)")
    # the wiki's qualified-count claim must agree with the registry
    flavor = json.loads((ROOT / "schemas" / "flavor.json").read_text(encoding="utf-8"))
    qualified = [f["id"] for f in flavor["flavors"] if f.get("qualified")]
    assert qualified == ["vanilla", "tbc", "wotlk"], (
        f"the registry's qualified set moved to {qualified} — update the "
        "wiki sentence and this pin together")


def test_the_desktop_image_wiki_names_the_packaged_lane_honestly():
    """R6-4 (W4 code-red round 3): the wiki must not claim the PACKAGED
    desktop image ships all three lanes' runtimes — packageApp stages only
    the vanilla lane's DLLs and the boot gate refuses a non-vanilla boot
    loud (DesktopRuntimeBackend.requireRuntimeLaneMatches). The honest
    wording (vanilla-only image + the dev -PrpFlavor routing + the
    RUNTIME-LANE MISMATCH refusal) is pinned; the false sentence is
    forbidden outright."""
    wiki = (ROOT / "docs/wiki/Home-Screen.md").read_text(encoding="utf-8")
    assert "The desktop image ships all three lanes' runtimes" not in wiki, (
        "the wiki claims the packaged image ships all three lanes' "
        "runtimes — false while packageApp stages only the vanilla lane; "
        "name the refusal + the dev -PrpFlavor routing instead")
    assert "RUNTIME-LANE MISMATCH" in wiki, (
        "the wiki's desktop lane paragraph must name the boot gate's "
        "refusal a user actually sees")
    assert "-PrpFlavor=" in wiki, (
        "the wiki's desktop lane paragraph must name the developer "
        "harness routing that loads a per-lane runtime")


def test_troubleshooting_documents_the_wave_val_codes():
    """R6-r6-2 (W4 code-red round 6): Troubleshooting.md must document the
    VAL codes the wave made user-reachable — the tbc/wotlk exact-set VAL-04
    (including the vanilla-tolerates/expansions-refuse distinction) and the
    VAL-02 oversized fast-scan cap — the page's VAL-01/03/11/12/13 rows set
    the expectation that VAL codes are explained there."""
    page = (ROOT / "docs/wiki/Troubleshooting.md").read_text(encoding="utf-8")
    normalized = " ".join(page.split())
    for code in ("VAL-02", "VAL-04"):
        assert code in page, (
            f"Troubleshooting.md never explains {code} — the wave's "
            "negative-matrix legs made it user-reachable"
        )
    assert "unknown patch MPQ not in the exact set" in normalized, (
        "Troubleshooting.md must quote the exact-set refusal text"
    )
    assert "Vanilla flavor tolerates unknown patch files" in normalized, (
        "Troubleshooting.md must state the vanilla-tolerates/expansions-"
        "refuse distinction"
    )
    assert "exceeds fast-scan limit" in normalized, (
        "Troubleshooting.md must quote the VAL-02 size-cap refusal text"
    )


def test_the_wiki_states_the_per_platform_runtime_honesty():
    """R4-1 (W4 code-red round 4): the wiki must state, per platform, what
    actually boots today — the desktop installed image refuses a TBC/WotLK
    boot (RUNTIME-LANE MISMATCH), AND Android ships the Vanilla lane's
    runtime + ledger only (boot + server-database gate refuse; per-flavor
    Android runtimes/ledgers are W5). A wiki that documents only the
    desktop refusal hides the Android gate the flip made reachable."""
    home = (ROOT / "docs/wiki/Home-Screen.md").read_text(encoding="utf-8")
    assert "On Android, the app ships the Vanilla lane's runtime and migration ledger only" in " ".join(home.split()), (
        "the wiki's flavor section must state the Android vanilla-only "
        "runtime + ledger scope and the boot refusal")
    assert "ANDROID-LANE-SCOPE" in home, (
        "the wiki must name the Android server-database gate's refusal code")
    started = (ROOT / "docs/wiki/Getting-Started.md").read_text(
        encoding="utf-8", errors="replace")
    normalized = " ".join(started.split())
    assert "Vanilla does, on every platform" in normalized, (
        "Getting-Started must answer 'which flavors run the server today' "
        "with the per-platform truth")
    assert "W5" in normalized, (
        "Getting-Started must name the per-lane-runtime milestone")


def test_the_wiki_front_page_names_all_three_qualified_versions():
    """R6-5 (W4 code-red round 3): the wiki front page (README.md) and the
    Overview scope line must not make a vanilla-only product claim — the
    accepted version list is derived from schemas/flavor.json so the wiki
    cannot drift from the registry again."""
    import json
    flavor = json.loads((ROOT / "schemas/flavor.json").read_text(encoding="utf-8"))
    qualified = [f for f in flavor["flavors"] if f.get("qualified")]
    readme = (ROOT / "docs/wiki/README.md").read_text(encoding="utf-8")
    assert "1.12.1 client on a handheld device" not in readme, (
        "the wiki front page still makes a vanilla-only product claim")
    for f in qualified:
        assert f["version_resource"].rsplit(".", 1)[0] in readme, (
            f"the wiki front page must name the qualified {f['id']} "
            f"version {f['version_resource'].rsplit('.', 1)[0]}")
    overview = (ROOT / "docs/wiki/Overview.md").read_text(encoding="utf-8")
    assert "inside a 1.12.1 client" not in overview, (
        "the Overview scope line still claims a vanilla-only client")
    assert "classic-era clients" in overview


def test_wiki_game_flavor_paragraph_matches_the_switcher_semantics():
    """Round-5 R6-1: all registered flavors RENDER as chips (visible), with
    only qualified ones selectable — the wiki must not claim unqualified
    chips are absent, and must carry the visible-but-unselectable wording.
    Parsed from ActiveFlavor.switcherChips()'s own contract."""
    source = (
        ROOT / "android/app/src/main/java/com/pocketrealm/client/ActiveFlavor.kt"
    ).read_text(encoding="utf-8")
    chips_body = source.split("fun switcherChips(): List<FlavorChip> {")[1].split(
        chr(10) + "    }")[0]
    # every declared flavor renders (map over ClientFlavors.ALL), and
    # selectability is the qualified-membership gate
    assert "ClientFlavors.ALL.map" in chips_body
    assert "selectable = flavor.flavorId in qualifiedIds" in chips_body

    wiki = WIKI_PAGE.read_text(encoding="utf-8")
    assert "appear only once their clients are qualified" not in wiki, (
        "the wiki again claims unqualified chips are ABSENT - they render "
        "visible but unselectable (switcherChips maps ALL flavors)"
    )
    assert "visible but unselectable" in wiki, (
        "the wiki lost the visible-but-unselectable wording"
    )
