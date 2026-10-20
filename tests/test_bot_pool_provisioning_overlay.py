"""LVF-bot-actors-003+008 pin: the random-bot pool provisioning repair.

The recorded failure: on the shared multi-pool datadir the random-bot login
wave dead-ends two ways. (003) Accumulated ai_playerbot_random_bots 'add'
rows from other profiles' pools fill the pick set, hold the world "full" so
AddRandomBots never provisions this profile's own bots, and burn the login
budget on accounts the CharacterHandler allowlist guard refuses ("Attempt to
add not allowed bot ... please try to reset all random bots"). (008) With
every bot account saturated at the per-account character cap, provisioning
skips the saturated accounts without admitting them to the allowlist, the
world's pool view collapses, and the documented reset remedy (event-table
DELETE) does not restore logins.

The fix ships as tracked full-file overlays under native/patches/playerbots/
(the submodule stays byte-pristine); the build driver copies them over its
CMake mirror BEFORE the anchor overlays run. These tests pin that whole
composition mechanically:
  - the overlay files exist and are the upstream bytes + only the fix hunks;
  - the pinned submodule references are byte-pristine;
  - every anchor overlay the driver applies to these two files still finds
    its upstream text in the overlay copies;
  - applying the copies + the driver's anchor sequence to a pristine copy
    yields code carrying every fix marker;
  - the driver actually wires the copies (before the anchors) and records
    the overlay in PLAYERBOTS_OVERLAYS.
"""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import build_o09_realm_runtime as driver  # noqa: E402

UPSTREAM_MGR = ROOT / "native" / "playerbots" / "playerbot" / "RandomPlayerbotMgr.cpp"
UPSTREAM_CFG = ROOT / "native" / "playerbots" / "playerbot" / "PlayerbotAIConfig.cpp"
OVERLAY_MGR = ROOT / "native" / "patches" / "playerbots" / "RandomPlayerbotMgr.cpp"
OVERLAY_CFG = ROOT / "native" / "patches" / "playerbots" / "PlayerbotAIConfig.cpp"

# Fix markers that must survive the driver's anchor sequence: the observable
# content of each recorded defect's remedy.
MGR_MARKERS = (
    # 003: pool view scoped to the allowlist (foreign 'add' rows can no
    # longer crowd the pool / suppress AddRandomBots / burn the login budget)
    "SELECT r.bot FROM ai_playerbot_random_bots AS r",
    "JOIN characters AS c ON c.guid = r.bot",
    "AND c.account IN (",
    # reset op clears the refusal state end-to-end (event table happens in
    # the upstream body; the pick set, allowlist refill and fresh
    # provisioning are the repair)
    "currentBots.clear();",
    "PocketRefillRandomBotAccounts();",
    "uint32 scheduled = AddRandomBots();",
    "reset applied end-to-end",
    # allowlist refill scans exact prefix names up to the highest observed
    # prefix number, bounded
    "maxAccountNr FROM account WHERE replace(lower(username), lower('%s'), '') != 0",
    "allowlist refilled:",
    # 008: saturated accounts stay admitted; headroom rotation before mint
    "LVF-bot-actors-008: admit every provisioned account to the",
    "account silently. The absent-name mint below stays the",
)
CFG_MARKERS = (
    # boot self-heal: coverage re-derived from the login DB after the factory
    "RandomPlayerbotFactory::CreateRandomBots();",
    "random bot account allowlist covers %zu accounts",
    "recovered",
)


def _overlay_text(path: Path) -> str:
    assert path.is_file(), f"{path} missing - the LVF-bot-actors-003+008 overlay must ship"
    return path.read_bytes().decode("utf-8")


def test_submodule_references_are_byte_pristine() -> None:
    """The check_repo submodule must not carry the fix in its working tree -
    the overlay under native/patches/playerbots/ is the only carrier."""
    for submodule in ("native/playerbots",):
        dirty = subprocess.run(["git", "diff", "--quiet"], cwd=ROOT / submodule)
        assert dirty.returncode == 0, f"{submodule} working tree drifted from the pinned commit"
        staged = subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=ROOT / submodule)
        assert staged.returncode == 0, f"{submodule} index drifted from the pinned commit"


def test_overlays_are_upstream_bytes_plus_only_the_fix_hunks() -> None:
    for upstream, overlay in ((UPSTREAM_MGR, OVERLAY_MGR), (UPSTREAM_CFG, OVERLAY_CFG)):
        up_lines = upstream.read_bytes().decode("utf-8").splitlines()
        ov_lines = overlay.read_bytes().decode("utf-8").splitlines()
        # Every upstream line survives in the overlay except the two hunks
        # the fix intentionally rewrites: the GetBots query construction
        # (003: rewritten into the scoped JOIN) and the GetOrCreateAccount
        # reuse branch (008: the allowlist admission is hoisted out of the
        # headroom branch, re-indenting its two guard lines).
        removed = [line.strip() for line in up_lines if line not in ov_lines]
        sanctioned = {
            "auto results = CharacterDatabase.Query(",
            '"SELECT bot FROM ai_playerbot_random_bots WHERE owner = 0 AND event = \'add\'");',
            "if (!sPlayerbotAIConfig.IsInRandomAccountList(accountId))",
            "sPlayerbotAIConfig.randomBotAccounts.push_back(accountId);",
        }
        extra = [line for line in removed if line not in sanctioned]
        assert not extra, f"{overlay.name} dropped non-fix upstream lines: {extra[:5]}"
        assert len(ov_lines) > len(up_lines), f"{overlay.name} carries no additions"


def _anchor_pairs_for(file_name: str) -> list[tuple[str, str, str]]:
    """The (upstream, android) anchor pairs the driver applies to one file,
    in driver order, parsed straight out of the apply function."""
    src = driver_src = (ROOT / "tools" / "build_o09_realm_runtime.py").read_text(encoding="utf-8")
    pairs = re.findall(
        r'replace_anchor\(\s*bot_root / "' + re.escape(file_name) + r'",\s*(PB_\w+_UPSTREAM),\s*(PB_\w+_ANDROID)\)',
        src)
    assert pairs, f"no anchors found for {file_name}"
    return [(getattr(driver, up), getattr(driver, android), up) for up, android in pairs]


def test_driver_anchors_find_their_upstream_text_in_the_overlays() -> None:
    """replace_anchor() raises on anchor drift - so every anchor applied to
    these two files must find its UPSTREAM text in the overlay copy (the
    driver's edits compose on top; none may be pre-clobbered)."""
    for overlay, file_name in ((OVERLAY_MGR, "RandomPlayerbotMgr.cpp"),
                               (OVERLAY_CFG, "PlayerbotAIConfig.cpp")):
        text = _overlay_text(overlay)
        for upstream, _, label in _anchor_pairs_for(file_name):
            assert upstream in text or upstream.replace("\n", "\r\n") in text, \
                f"{overlay.name} lost anchor {label}'s upstream text"


def test_overlay_composition_yields_the_fixed_sources(tmp_path: Path) -> None:
    """Rehearse the driver's per-file overlay application on a pristine copy:
    full-file overlay copies first, then the driver's anchor sequence in
    order - the result must carry every fix marker and every android-side
    anchor (proving both the fix and the existing engine overlays apply)."""
    for overlay, upstream, file_name, markers in (
            (OVERLAY_MGR, UPSTREAM_MGR, "RandomPlayerbotMgr.cpp", MGR_MARKERS),
            (OVERLAY_CFG, UPSTREAM_CFG, "PlayerbotAIConfig.cpp", CFG_MARKERS)):
        work = tmp_path / file_name
        shutil.copyfile(upstream, work)  # the driver mirrors the pristine submodule first
        # the wiring under test: copy the overlay BEFORE the anchors
        work.write_bytes(overlay.read_bytes())
        for upstream_text, android_text, _ in _anchor_pairs_for(file_name):
            for old, new in ((upstream_text, android_text),
                             (upstream_text.replace("\n", "\r\n"), android_text.replace("\n", "\r\n"))):
                if old in work.read_bytes().decode("utf-8"):
                    work.write_bytes(work.read_bytes().decode("utf-8")
                                     .replace(old, new, 1).encode("utf-8"))
                    break
            else:
                if android_text in work.read_bytes().decode("utf-8") or \
                        android_text.replace("\n", "\r\n") in work.read_bytes().decode("utf-8"):
                    continue  # idempotent re-apply
                pytest.fail(f"anchor drift while applying overlays to {file_name}")
        final = work.read_bytes().decode("utf-8")
        for marker in markers:
            assert marker in final, f"{file_name}: fix marker missing after overlay composition: {marker!r}"


def test_driver_wires_the_overlay_files_before_the_anchors() -> None:
    src = (ROOT / "tools" / "build_o09_realm_runtime.py").read_text(encoding="utf-8")
    wiring = (
        'bot_root = mirror / "playerbot"\n'
        '    # the random-bot provisioning/allowlist repair\n'
    )
    assert wiring in src, "driver must copy the overlay files right after the mirror root"
    copy_pos = src.find('patches" / "playerbots" / "RandomPlayerbotMgr.cpp')
    first_anchor = src.find('replace_anchor(bot_root / "PlayerbotAIConfig.h"')
    assert 0 < copy_pos < first_anchor, "overlay copies must precede every anchor on these files"


def test_playerbots_overlays_registry_records_the_repair() -> None:
    entries = [e for e in driver.PLAYERBOTS_OVERLAYS if e["id"] == "random-bot-pool-provisioning-repair"]
    assert len(entries) == 1, "the repair must be registered exactly once in PLAYERBOTS_OVERLAYS"
    assert sorted(entries[0]["paths"]) == [
        "playerbot/PlayerbotAIConfig.cpp",
        "playerbot/RandomPlayerbotMgr.cpp",
    ]
    assert entries[0]["reason"].strip()
