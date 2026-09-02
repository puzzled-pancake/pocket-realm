#!/usr/bin/env python3
"""Generate the A4/A5 byte-diff vectors from banklib (the training source
of truth) for the host battery.

Each row pairs plain renderer inputs (card/player/tier/absence/facts and
compose inputs) with the EXACT banklib renderings (sysm_for_card /
compose / card_state). The committed vectors let the host battery
(tests/test_llm_prompt_format.py + tools/test_llm_prompt_format.cpp)
verify the SHIPPED C++ renderer without depending on G: being mounted.

Regenerate deliberately (the training text is versioned with the corpus):
    python tools/llm_lab/gen_prompt_vectors.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

BANKLIB_DIR = r"G:\NPU LLM\scripts\finetune"
sys.path.insert(0, BANKLIB_DIR)
import banklib as B  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "tests" / "llm_prompt_vectors.json"

PLAYERS = [
    dict(name="Brannoc", sex="male", race="human", class_="paladin", level=22),
    dict(name="Kelda", sex="female", race="night elf", class_="priest", level=31),
    dict(name="Torvall", sex="male", race="human", class_="warrior", level=34),
    dict(name="Ymma", sex="female", race="gnome", class_="mage", level=18),
]

ZONES = ["Elwynn Forest", "Westfall", "Loch Modan", "Darkshore"]
ROLES = ["smith", "guard", "herbalist", "adventurer"]


def make_cards():
    cards = []
    # one card per production bible + variants exercising optional fields
    for i, (bible_name, bible) in enumerate(B.PRODUCTION_BIBLES.items()):
        cards.append(dict(
            id=f"vec{i:02d}{bible_name.lower()}",
            name=["Grumph", "Gromnak", "Maera", "Thren"][i],
            race=["dwarf", "orc", "human", "undead"][i],
            class_=["warrior", "hunter", "mage", "rogue"][i],
            role=ROLES[i],
            zone=ZONES[i],
            faction="Alliance" if i % 2 == 0 else "Horde",
            bible=bible,
            quirks="counts everything twice; hates wet coal" if i == 0 else "",
            never="never discusses the Greymane wall" if i == 0 else "",
            tier=3,
        ))
    # companion card with explicit backstory + tier note + state flavors
    cards.append(dict(
        id="veccomp01", name="Bygdok", race="dwarf", class_="hunter",
        role="adventurer", zone="Redridge Mountains", faction="Alliance",
        bible=B.PRODUCTION_BIBLES["Gromnak"],
        backstory=("Bygdok is a dwarf hunter - adventurer - who owes every "
                   "tavernkeep between Goldshire and Menethil at least one coin."),
        quirks="", never="", tier=3, companion=True,
        tier_note="You have fought beside each other often.",
        state_flavors=["on the road with {player}",
                       "at camp with {player}",
                       "in a hard fight beside {player}"],
    ))
    # NPC card with fixed spot and explicit absence
    cards.append(dict(
        id="vecnpc07", name="Serailla", race="night elf", class_="druid",
        role="herbalist", zone="Darnassus", faction="Alliance",
        bible=B.PRODUCTION_BIBLES["Maera"],
        quirks="presses leaves into every book she owns", never="",
        tier=2,
        absence="{player} was last seen many days ago.",
    ))
    return cards


def main() -> int:
    cards = make_cards()
    rows = []
    case = 0
    for tier in (1, 2, 3, 4, 5):
        c = cards[tier % len(cards)]
        p = PLAYERS[case % len(PLAYERS)]
        facts_pool = [
            "owes {player} five silver from the ale",
            "hates spiders since the basement job",
            "has a cousin Dagna in Ironforge",
            "lost a militia crate in Westfall",
            "keeps a map of the tram tunnels",
            "cannot swim",
            "prefers ale over wine",
        ]
        facts_variant = case % 4
        facts = [f.replace("{player}", p["name"])
                 for f in facts_pool[:facts_variant * 2 + (1 if case % 2 else 0)]]
        absence = c.get("absence", "").replace("{player}", p["name"]) or None

        # banklib renders from the CARD: inject the row's facts/tier so the
        # expectation matches what the C++ renderer receives
        render_card = dict(c, facts=facts)
        sysm = B.sysm_for_card(render_card, tier_override=tier, player=p)
        # user-turn variants across the compose surface
        state = B.card_state(c, idx=case, player=p)
        variants = [
            dict(player_line="what do you charge for a shield repair",
                 says=(), events=(), results=(), mems=(), lines=(), fills="", extra=""),
            dict(player_line="i finally saved enough for that stormwind forge permit",
                 says=(), events=(), results=(),
                 mems=facts[-6:], lines=(), fills="", extra=""),
            dict(player_line="",
                 says=("Kelda says: the boat is late again",
                       "Torvall says: it is always late"),
                 events=("MURLOC RAID on the east docks - witnessed by the watch",),
                 results=(), mems=(), lines=(), fills="", extra=""),
            dict(player_line="so do we have a deal or not",
                 says=(), events=(), results=(),
                 mems=("owes you five silver from the ale",) * 8,
                 lines=('<<log_fact text="...">>',),
                 fills="Write in place of ...: what he just agreed to.",
                 extra="He drove a fair bargain - say so."),
            dict(player_line="",
                 says=(), events=(), results=("No falconer badge is issued in Stormwind.",),
                 mems=(), lines=(), fills="",
                 extra="Something DID happen: you LOST a duel to him yesterday."),
        ]
        v = variants[case % len(variants)]
        user = B.compose(
            v["player_line"] or None,
            says=list(v["says"]), events=list(v["events"]),
            results=list(v["results"]), state=state,
            mems=list(v["mems"]), lines=list(v["lines"]), fills=v["fills"],
            extra=v["extra"] or None,
        )
        rows.append(dict(
            idx=case,
            card=dict(c), player=dict(p), tier=tier,
            absence=absence if absence else "",
            facts=facts,
            user=dict(player_line=v["player_line"] or "",
                      says=list(v["says"]), events=list(v["events"]),
                      results=list(v["results"]), state=state,
                      mems=list(v["mems"]), lines=list(v["lines"]),
                      fills=v["fills"], extra=v["extra"]),
            expected_sysm=sysm,
            expected_user=user,
        ))
        case += 1

    # 20-row minimum for the gate: add plain-turn coverage across the rest
    while len(rows) < 24:
        c = cards[case % len(cards)]
        p = PLAYERS[case % len(PLAYERS)]
        tier = 1 + (case * 7) % 5
        state = B.card_state(c, idx=case, player=p)
        line = ["any news from the militia",
                "the rain is going to rust my armor",
                "you dwarf folk really can drink",
                "i think i will head to westfall tomorrow"][case % 4]
        absence = (p["name"] + " was last seen many days ago."
                   if case % 3 == 0 else "")
        if not absence and c.get("absence"):
            absence = c["absence"].replace("{player}", p["name"])
        render_card = dict(c, facts=[] if case % 2 else ["owes {player} a favor"])
        if absence:
            render_card["absence"] = absence
        rows.append(dict(
            idx=case, card=dict(c), player=dict(p), tier=tier,
            absence=absence,
            facts=[] if case % 2 else ["owes %s a favor" % p["name"]],
            user=dict(player_line=line, says=[], events=[], results=[],
                      state=state, mems=[], lines=[], fills="", extra=""),
            expected_sysm=B.sysm_for_card(render_card,
                                           tier_override=tier, player=p),
            expected_user=B.compose(line, state=state),
        ))
        case += 1

    OUT.write_text(json.dumps(rows, indent=1, ensure_ascii=False),
                   encoding="utf-8", newline="\n")
    print(f"wrote {OUT} ({len(rows)} rows)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
