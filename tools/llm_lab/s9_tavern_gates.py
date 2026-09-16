#!/usr/bin/env python3
"""Tavern coherence: 4 bots, one scene, shared event rows.

The multi-voice coherence check the single-bot battery cannot run: four
personas (distinct bible registers — gruff/wry, loud/boastful,
soft/precise, quiet/dry) converse around ONE shared happening (a duel
outcome + a debt reminder), each with its own facts/tier/mood, over 3
rounds. Floors per reply: voiced, in-register (shares no 6-word shingle
with another bot's reply — distinct voices), event-anchored (shares a
content word with the shared happening), zero tool markers, mood-colored
(grudge-bot shows the edge, smitten-bot brightens — human-read, recorded).

Usage: python tools/llm_lab/s9_tavern_gates.py --model e2b-tuned [--n 3]
Output: RESULTS_DIR/s9_tavern_<model>_<ts>.json (versioned names).
"""
import argparse
import copy
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sanity_battery import (B, CARD, PLAYER, MODELS, RESULTS_DIR,
                            chat, start_server, stop_server, parse_tools,
                            strip_tools)
from s8_beats_gates import seasoned_sysm, MOOD_LINES

PERSONAS = [
    ("Grumph", "dwarf", "warrior", "smith", "gruff, wry, alive",
     "grudge", MOOD_LINES[6]),
    ("Gromnak", "orc", "hunter", "tracker", "loud, boastful, warm",
     None, MOOD_LINES[1]),
    ("Maera", "human", "mage", "scholar", "soft, precise, curious",
     "smitten", MOOD_LINES[5]),
    ("Thren", "elf", "rogue", "scout", "quiet, dry, exact",
     None, MOOD_LINES[4]),
]

HAPPENING = ("Brannoc beat Kromgrit in the duel at Goldshire, fair and "
             "square. Brannoc still owes Grumph five silver from the ale.")

# Each bot remembers the happening from its OWN seat: Grumph holds the
# debt, the others hold it as tavern talk (the shared rows stay
# direction-consistent with HAPPENING - a rumor test with the debt
# reversed on half the bots is a different, broken test).
PERSONA_MEMO = {
    "Grumph": (["Brannoc owes you five silver from the ale"],
               ["Brannoc owes you five silver from the ale"]),
    "Gromnak": (["heard Brannoc owes Grumph five silver"],
                ["tavern talk: Brannoc still owes Grumph"]),
    "Maera": (["heard Brannoc owes Grumph five silver"],
              ["tavern talk: Brannoc still owes Grumph"]),
    "Thren": (["heard Brannoc owes Grumph five silver"],
              ["tavern talk: Brannoc still owes Grumph"]),
}

SEASONING = ("Have a view and say it plainly in your own voice. "
             "Never simply agree. Advance at most one thing per reply. "
             "Your weather line names how you feel; let it color the edges.")


def content_words(text):
    stop = {"the", "a", "an", "and", "or", "of", "to", "in", "on", "at",
            "is", "it", "you", "your", "i", "we", "he", "she", "they",
            "this", "that", "with", "for", "as", "was", "were", "be",
            # connective verbs that ride every happening sentence and
            # would anchor any reply trivially (HAPPENING carries "still")
            "still", "from", "his", "her"}
    return {w for w in re.findall(r"[a-z']+", text.lower()) if w not in stop}


def shingles(text, n=6):
    words = re.findall(r"[a-z']+", text.lower())
    return {" ".join(words[i:i + n]) for i in range(len(words) - n + 1)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="e2b-tuned")
    ap.add_argument("--n", type=int, default=3)
    ap.add_argument("--seed", type=int, default=None)
    args = ap.parse_args()

    model = MODELS[args.model]
    proc = start_server(model)
    gate_ok = True
    try:
        res = {"model": args.model, "n": args.n, "rounds": []}
        happen_words = content_words(HAPPENING)
        for round_no in range(args.n):
            replies = []
            for name, race, cls, role, voice, _flag, mood in PERSONAS:
                facts, mems = PERSONA_MEMO[name]
                card = copy.deepcopy(CARD)
                card.update(id=name.lower(), name=name, race=race,
                            class_=cls, role=role,
                            # each voice gets its OWN bible register - the
                            # whole point of the 4-voice coherence gate
                            # (CARD's default is Grumph's)
                            bible=B.PRODUCTION_BIBLES[name],
                            zone="Elwynn Forest",
                            facts=facts,
                            tier=3)
                base = B.sysm_for_card(card, player=PLAYER)
                sysm = seasoned_sysm(base, SEASONING, mood)
                user = B.compose(
                    f"{PLAYER['name']}: {HAPPENING} What do you make of that?",
                    state=f"You are at your usual spot in Elwynn Forest.",
                    mems=mems)
                r = chat([{"role": "system", "content": sysm},
                          {"role": "user", "content": user}],
                         model["sampling"], model["qwen"], seed=args.seed)
                text = strip_tools(r["content"])
                replies.append(dict(bot=name, voice=voice, mood=mood,
                                    reply=r["content"], text=text,
                                    tools=bool(parse_tools(r["content"])),
                                    words=len(text.split()),
                                    anchored=bool(content_words(text) & happen_words)))
            # cross-voice shingle check: no two bots share a 6-word run
            collisions = []
            for i in range(len(replies)):
                for j in range(i + 1, len(replies)):
                    shared = (shingles(replies[i]["text"]) &
                              shingles(replies[j]["text"]))
                    if shared:
                        collisions.append((replies[i]["bot"], replies[j]["bot"],
                                           sorted(shared)[:2]))
            voiced = sum(bool(r["text"].strip()) for r in replies)
            anchored = sum(r["anchored"] for r in replies)
            tools = sum(r["tools"] for r in replies)
            res["rounds"].append(dict(replies=replies, collisions=collisions,
                                      voiced=f"{voiced}/4",
                                      anchored=f"{anchored}/4",
                                      tool_draws=tools))
            round_ok = (voiced == 4 and anchored >= 3 and
                        tools == 0 and not collisions)
            gate_ok = gate_ok and round_ok
            print(f"TAVERN round {round_no + 1}: voiced {voiced}/4, "
                  f"anchored {anchored}/4, tools {tools}, "
                  f"collisions {len(collisions)} "
                  f"(gate: 4/4 voiced, >=3/4 anchored, 0 tools, 0 collisions)"
                  f" -> {'PASS' if round_ok else 'FAIL'}")
        os.makedirs(RESULTS_DIR, exist_ok=True)
        out = os.path.join(
            RESULTS_DIR,
            f"s9_tavern_{args.model}_{time.strftime('%Y%m%d-%H%M%S')}.json")
        res["gate"] = "PASS" if gate_ok else "FAIL"
        with open(out, "w", encoding="utf-8") as f:
            json.dump(res, f, indent=1)
        print("wrote", out)
    finally:
        stop_server(proc)
    if not gate_ok:
        print("S9 GATE FAIL (see rounds above)")
        return 1
    print("S9 GATE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
