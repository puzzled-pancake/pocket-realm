"""Doc-drift pin (R3-3, android-ui-tests review round 3): the Add-ons wiki
page must document the FLAVOR-PROJECTED screens — per-lane recommended and
browse sets, and the active flavor's accepted interface number — instead of
the frozen vanilla-only wording the flavor lift superseded."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADDONS_SCREEN = ROOT / "android/app/src/main/java/com/pocketrealm/ui/AddonsScreen.kt"
VALIDATOR = ROOT / "android/app/src/main/java/com/pocketrealm/addons/AddonArchiveValidator.kt"
WIKI_PAGE = ROOT / "docs/wiki/Add-ons.md"


def test_wiki_documents_the_active_flavor_projection():
    wiki = WIKI_PAGE.read_text(encoding="utf-8")
    # the projection copy: the recommended/browse sets are per-lane
    assert (
        "active game version" in wiki or "active flavor" in wiki
    ), "the wiki must document that Recommended/Browse are the active version's researched set"
    # the interface number line covers all three lanes, not vanilla-only
    assert "20400" in wiki and "30300" in wiki, (
        "the wiki's interface-number line must name the tbc/wotlk accepted "
        "interfaces beside vanilla's 11200"
    )
    # the frozen vanilla-only phrasings must be gone
    assert "Vanilla 1.12 add-on catalogue" not in wiki, (
        "the wiki still describes the pre-flavor-lift catalogue"
    )
    assert "declares the Vanilla interface number 11200" not in wiki, (
        "the wiki still pins the interface number to the vanilla lane alone"
    )


def test_wiki_phrases_match_the_screens_copy():
    """The wiki's key phrases must track the screens: the 'add-on set'
    projection phrase and the per-lane interface wording come from the
    AddonsScreen/validator copy this slice ships."""
    screen = ADDONS_SCREEN.read_text(encoding="utf-8")
    validator = VALIDATOR.read_text(encoding="utf-8")
    assert "add-on set" in screen, "the screens' projection phrase moved"
    assert "declares a supported addon interface" in validator or (
        "declaring a supported addon interface" in validator
    ), "the validator's interface-mismatch copy moved"
    # and the wiki carries both families
    wiki = WIKI_PAGE.read_text(encoding="utf-8")
    assert "add-on set" in wiki
    assert "supported addon interface" in wiki or (
        "interface number the active game version accepts" in wiki
    )
