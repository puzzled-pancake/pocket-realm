#!/usr/bin/env python3
"""Arm D: bridge-side entity-guard directive vs invented-name confabulation.

Simulates the production bridge guard: when the player's message names an
entity the world DB does not know, the bridge injects a directive note
(compose(extra=...)) telling the character they have never heard of it.
Scored against the same 6 invented probes as S3.

Also re-runs e2b-base with its MAKER sampling (t1.0/p0.95/k64) because the
main battery tested it at the tuned profile only.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sanity_battery import (B, CARD, PLAYER, MODELS, RESULTS_DIR,
                             HEDGE_SIGNS, contains_any, chat, start_server,
                             stop_server, parse_tools)

PROBES = [
    "what do you know about marshal redwyn",
    "have you seen the emerald chalice of lakeshire anywhere",
    "what did guildmaster torbin say about the kobold tunnels",
    "is the night watch at northvale tower doubled yet",
    "where can i get a stormwind falconer badge",
    "does fionna in goldshire sell rune bread",
]

GUARDS = {
    "what do you know about marshal redwyn":
        "There is no Marshal Redwyn in Stormwind or the Militia. You have never heard the name. Say so plainly and ask who he means.",
    "have you seen the emerald chalice of lakeshire anywhere":
        "You have never heard of any Emerald Chalice. Nobody in Lakeshire sells or keeps such a thing. Say you do not know it.",
    "what did guildmaster torbin say about the kobold tunnels":
        "There is no Guildmaster Torbin. You have never met anyone of that name. Say so and ask who he means.",
    "is the night watch at northvale tower doubled yet":
        "There is no Northvale Tower in Elwynn Forest. You have never heard of it. Say so plainly.",
    "where can i get a stormwind falconer badge":
        "Stormwind issues no falconer badge and has no falconer guild. You have never heard of such a badge. Say so.",
    "does fionna in goldshire sell rune bread":
        "There is no Fionna in Goldshire. You know every trader on that road. Say you do not know the name.",
}

MAKER_SAMPLING = dict(temperature=1.0, top_p=0.95, top_k=64, repeat_penalty=1.0)


def run(name, sampling_override=None, arms=("guard",)):
    cfg = dict(MODELS[name])
    if sampling_override:
        cfg["sampling"] = sampling_override
    proc = start_server(cfg)
    out = []
    try:
        sysm = B.sysm_for_card(CARD, player=PLAYER)
        for p in PROBES:
            # guarded arm
            user = B.compose(p, state="You are at your forge in Elwynn Forest.",
                             extra=GUARDS[p])
            r = chat([{"role": "system", "content": sysm},
                      {"role": "user", "content": user}], cfg["sampling"],
                     cfg["qwen"])
            low = r["content"].lower()
            denied = contains_any(low, HEDGE_SIGNS + [
                r"no (such|marshal|guildmaster|fionna)", r"never heard",
                r"no one by that name", r"don'?t know (any|the|a) (redwyn|torbin|fionna)",
                r"no falconer", r"no northvale", r"no emerald"])
            out.append(dict(probe=p, arm="guard", denied=bool(denied),
                            tools=parse_tools(r["content"]) and True or False,
                            reply=r["content"], speed=r))
    finally:
        stop_server(proc)
    return out


def main():
    targets = []
    # tuned models at their profiles; e2b-base also re-run at maker sampling
    targets.append(("e2b-tuned", None))
    targets.append(("q08-tuned", None))
    targets.append(("q4b-base", None))
    results = {}
    for name, so in targets:
        print(f"### arm D on {name}", flush=True)
        results[name] = run(name, sampling_override=so)
        for row in results[name]:
            print(f"  denied={row['denied']} tools={row['tools']} [{row['probe']}]")
            print("   >", row["reply"][:160].replace("\n", " | "))

    # e2b-base maker-sampling re-run of the S1 contract (6 tool cases)
    name = "e2b-base"
    print(f"### e2b-base @ maker sampling S1", flush=True)
    cfg = dict(MODELS[name])
    cfg["sampling"] = MAKER_SAMPLING
    proc = start_server(cfg)
    s1 = []
    try:
        sysm = B.sysm_for_card(CARD, player=PLAYER)
        cases = [
            ("i finally saved enough for that stormwind forge permit",
             ['<<log_fact text="..." category="shared-event">>'],
             "Write in place of ...: what he just told you about his savings."),
            ("haha, you actually out-forged the guildmaster",
             ['<<perform_emote emote="laugh">>'], ""),
            ("your work is pig iron trash and you know it",
             ['<<adjust_sentiment direction="-1" reason="...">>'],
             "Write in place of ...: how he just treated you - the reason."),
            ("fine, you win the argument. duel me, right now, goldshire",
             ['<<duel_challenge name="Brannoc">>'], ""),
            ("here, take this hammer for the militia order, no charge",
             ['<<give_item player="Brannoc" item="hammer">>'], ""),
        ]
        for player, lines, fills in cases:
            user = B.compose(player, lines=lines, fills=fills)
            r = chat([{"role": "system", "content": sysm},
                      {"role": "user", "content": user}], MAKER_SAMPLING,
                     cfg["qwen"])
            tools = [t["name"] for t in parse_tools(r["content"])]
            s1.append(dict(player=player, fired=bool(tools), tools=tools,
                           reply=r["content"], speed=r))
            print(f"  fired={bool(tools)} {tools} [{player[:40]}]")
            print("   >", r["content"][:160].replace("\n", " | "))
    finally:
        stop_server(proc)
    results["e2b-base-maker"] = s1

    os.makedirs(RESULTS_DIR, exist_ok=True)
    with open(os.path.join(RESULTS_DIR, "arm_d.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, indent=1, ensure_ascii=False)
    print("saved", os.path.join(RESULTS_DIR, "arm_d.json"))


if __name__ == "__main__":
    main()
