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
    unqualified-off rule belong on the page."""
    wiki = WIKI_PAGE.read_text(encoding="utf-8")
    source = HOME_SCREEN.read_text(encoding="utf-8")
    assert '"Game flavor"' in source
    for phrase in ("Game flavor", "Unqualified flavors stay off until their client is qualified."):
        assert phrase in wiki, f"the wiki page lost the flavor-card phrase: {phrase!r}"


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
