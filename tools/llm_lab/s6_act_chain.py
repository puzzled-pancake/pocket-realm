#!/usr/bin/env python3
"""The ACT-chain battery: the duelworld-10
chain plus the bridge-elicited follow/party_invite legs and the
event+note untrained-combo measurement.

Every prompt is built with the EXACT shipped renderer contract
(banklib.sysm_for_card / compose) and the EXACT note shapes
PlayerbotLlmBridge::BuildNote now emits for these beats - ready-made
lines with the speaker's name filled, no invented wording. The harness
is therefore sensitive to what the bridge licenses, not just to what
the model can do when handed any note.

Chain legs per scenario (the shipped flow, end to end):
  1. challenge  a duel-request turn (bridge duel trigger phrasing) with
                the ready <<duel_challenge name="Brannoc">> note ->
                expect duel_challenge fire + consent voice + hygiene.
  2. outcome    the [EVENT] reaction turn with the housekeeping
                log_fact nudge (the UNTRAINED compose combination:
                event head + note lines + speak-first) -> log_fact fire
                AND voice measured; event-only control runs alongside so
                the combo's cost (if any) is visible.
  3. news       "any news" after the duel's verified-event window ->
                share_gossip fire.

Gate (stage row): duelworld-10 >= 9/10 scenarios, majority across n=3
draws per scenario (see-saw law: majority scoring, not aggregates).
Zero executions from unlicensed turns is the NATIVE gate (license
cross-check) - pinned host-side by tests/test_llm_act_tools.py.

Usage:  python tools/llm_lab/s6_act_chain.py [--model e2b-tuned] [--n 3]
Output: RESULTS_DIR/s6_duelworld_<model>_<ts>.json (+ table).
"""
from __future__ import annotations

import argparse
import datetime
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sanity_battery as SB  # noqa: E402
sys.path.insert(0, SB.BANKLIB_DIR)
import banklib as BK  # noqa: E402

RESULTS_DIR = os.environ.get("LLM_LAB_RESULTS_DIR")
if not RESULTS_DIR:
    sys.stderr.write("LLM_LAB_RESULTS_DIR is not set - point it at the "
                     "local results directory\n")
    raise SystemExit(2)

# ten duel-request phrasings, every one carrying a bridge duel trigger
# (duel me / care for a duel / i challenge you / want to duel / let's
# duel) so the chain measures the BRIDGE-elicited path; "fight me" is
# deliberately NOT a trigger (third-person narration uses it)
DUEL_TURNS = [
    "fine, you win the argument. duel me, right now, goldshire",
    "you keep talking. duel me and prove it",
    "care for a duel, smith? right here in the yard",
    "i challenge you, grumph. axes now, no potions",
    "want to duel? i could use the practice swings",
    "let's duel. first blood, and the loser buys the ale",
    "duel me, unless you are scared of a farmer",
    "you want proof? duel me, before the guard changes shift",
    "i challenge you to one clean duel, no tricks",
    "duel me then, before the ale wears off",
]

# A14 outcome texts in the shipped wording family (alternating won/lost
# from the bot's perspective)
OUTCOME_EVENTS = [
    "You beat Brannoc in the duel, fair and square.",
    "Brannoc beat you in the duel, fair and square.",
]

FOLLOW_TURN = "follow me to the quarry, keep close"
INVITE_TURN = "invite me along, will you? the road is dull"
NEWS_TURN = "any news from the militia?"

KNOWN_TOOLS = ("log_fact", "adjust_sentiment", "share_gossip", "perform_emote",
               "duel_challenge", "give_item", "follow", "party_invite",
               "move_to", "loot_roll")

FILLS_NEWS = "Write in place of ...: the news - one line about what just happened."
FILLS_EVENT_FACT = ("Write in place of ...: the news - one line about what "
                    "just happened.")


def score(reply_text, expect, fill_field=None):
    tools = SB.parse_tools(reply_text)
    spoken = SB.strip_tools(reply_text)
    names = [t["name"] for t in tools]
    row = dict(fired=expect in names,
               fill_ok=True, voice_ok=bool(spoken) and len(spoken.split()) >= 3,
               hygiene=("[BRIDGE" not in reply_text
                        and all(n in KNOWN_TOOLS for n in names)),
               tools=names, reply=reply_text)
    if fill_field:
        for t in tools:
            if t["name"] == expect:
                v = t["fields"].get(fill_field, "")
                if "..." in v or not v.strip():
                    row["fill_ok"] = False
    return row


def run(model_name: str, n: int) -> dict:
    cfg = SB.MODELS[model_name]
    proc = SB.start_server(cfg)
    report = {"model": model_name, "n": n, "scenarios": [], "extras": {}}
    try:
        sysm = BK.sysm_for_card(SB.CARD, player=SB.PLAYER)
        sampling, qwen = cfg["sampling"], cfg["qwen"]

        def chat(user):
            return SB.chat([{"role": "system", "content": sysm},
                            {"role": "user", "content": user}], sampling, qwen)

        for idx, turn in enumerate(DUEL_TURNS):
            event = OUTCOME_EVENTS[idx % len(OUTCOME_EVENTS)]
            scenario = {"turn": turn, "event": event, "runs": []}
            for _ in range(n):
                # leg 1: the bridge's duel note (ready line, no fills)
                user = BK.compose(turn, lines=['<<duel_challenge name="Brannoc">>'],
                                  state=BK.card_state(SB.CARD, idx, SB.PLAYER))
                r1 = chat(user)
                challenge = score(r1["content"], "duel_challenge")

                # leg 2: the A14 event + the S6 housekeeping log_fact nudge
                user2 = BK.compose(None, events=[event],
                                   lines=['<<log_fact text="..." category="shared-event">>'],
                                   fills=FILLS_EVENT_FACT, extra=BK.SPEAK_FIRST,
                                   state=BK.card_state(SB.CARD, idx, SB.PLAYER))
                r2 = chat(user2)
                outcome = score(r2["content"], "log_fact", "text")

                # leg 2 control: event-only (no note) - the S5 shape
                user2c = BK.compose(None, events=[event], extra=BK.SPEAK_FIRST,
                                    state=BK.card_state(SB.CARD, idx, SB.PLAYER))
                r2c = chat(user2c)
                outcome_control = score(r2c["content"], "__none__")

                # leg 3: news with the verified-event window open
                user3 = BK.compose(NEWS_TURN,
                                   lines=['<<share_gossip text="...">>'],
                                   fills=FILLS_NEWS,
                                   state=BK.card_state(SB.CARD, idx, SB.PLAYER))
                r3 = chat(user3)
                news = score(r3["content"], "share_gossip", "text")

                scenario["runs"].append({
                    "challenge": challenge, "outcome": outcome,
                    "outcome_control_voice": outcome_control["voice_ok"],
                    "outcome_control_clean": outcome_control["hygiene"],
                    "news": news})
            f = lambda leg, field: sum(1 for r in scenario["runs"] if r[leg][field])  # noqa: E731
            scenario["summary"] = {
                "duel_fire_majority": f("challenge", "fired") * 2 > n,
                "duel_fires": f("challenge", "fired"),
                "duel_voice": f("challenge", "voice_ok"),
                "duel_hygiene": f("challenge", "hygiene"),
                "event_logfact_majority": f("outcome", "fired") * 2 > n,
                "event_logfact_fires": f("outcome", "fired"),
                "event_fill": f("outcome", "fill_ok"),
                "event_voice": f("outcome", "voice_ok"),
                "event_control_voice": sum(
                    1 for r in scenario["runs"] if r["outcome_control_voice"]),
                "event_control_clean": sum(
                    1 for r in scenario["runs"] if r["outcome_control_clean"]),
                "news_majority": f("news", "fired") * 2 > n,
                "news_fires": f("news", "fired"),
            }
            report["scenarios"].append(scenario)

        # extras: the other bridge-elicited ACT beats + restraint controls
        extras = defaultdict(list)
        legs = [
            ("follow", FOLLOW_TURN, ['<<follow name="Brannoc">>'], "follow", None),
            ("party_invite", INVITE_TURN, ['<<party_invite name="Brannoc">>'],
             "party_invite", None),
            ("news_restraint", NEWS_TURN, [], None, None),
            ("duel_restraint", "tell me about the duel in goldshire yesterday",
             [], None, None),
        ]
        for leg, turn, lines, expect, _fills in legs:
            for _ in range(n):
                user = BK.compose(turn, lines=lines,
                                  state=BK.card_state(SB.CARD, 0, SB.PLAYER))
                r = chat(user)
                tools = SB.parse_tools(r["content"])
                if expect is None:  # restraint: no note, no tools wanted
                    extras[leg].append(dict(clean=not tools, reply=r["content"]))
                else:
                    extras[leg].append(score(r["content"], expect))
        report["extras"] = {k: v for k, v in extras.items()}
    finally:
        SB.stop_server(proc)

    duels_won = sum(1 for s in report["scenarios"] if s["summary"]["duel_fire_majority"])
    report["duelworld10_pass"] = duels_won
    report["duelworld10_gate"] = duels_won >= 9
    ev = [s["summary"] for s in report["scenarios"]]
    report["event_combo"] = {
        "logfact_majority_scenarios": sum(1 for s in ev if s["event_logfact_majority"]),
        "mean_voice": round(sum(s["event_voice"] for s in ev) / len(ev), 2),
        "mean_control_voice": round(sum(s["event_control_voice"] for s in ev) / len(ev), 2),
        "mean_control_clean": round(sum(s["event_control_clean"] for s in ev) / len(ev), 2),
    }
    report["news_after_duel"] = {
        "majority_scenarios": sum(1 for s in ev if s["news_majority"])}

    os.makedirs(RESULTS_DIR, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    out = os.path.join(RESULTS_DIR, f"s6_duelworld_{model_name}_{ts}.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=1, ensure_ascii=False)

    print(f"\n===== S6 duelworld-10 ({model_name}, n={n} per leg) =====")
    print(f"duel fire: {duels_won}/10 scenarios majority  "
          f"{'PASS (>=9)' if report['duelworld10_gate'] else 'FAIL (<9)'}")
    for s in report["scenarios"]:
        sm = s["summary"]
        print(f"  [{sm['duel_fires']}/{n}] {s['turn'][:52]}"
              f"  event-logfact {sm['event_logfact_fires']}/{n}"
              f" voice {sm['event_voice']}/{n} (control {sm['event_control_voice']}/{n})"
              f"  news {sm['news_fires']}/{n}")
    print(f"event+note combo: {report['event_combo']}")
    for k, v in report["extras"].items():
        if k.endswith("restraint"):
            clean = sum(1 for r in v if r["clean"])
            print(f"extra {k}: clean {clean}/{len(v)}")
        else:
            fired = sum(1 for r in v if r["fired"])
            voice = sum(1 for r in v if r["voice_ok"])
            print(f"extra {k}: fire {fired}/{len(v)} voice {voice}/{len(v)}")
    print(f"artifact: {out}")
    return report


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="e2b-tuned")
    ap.add_argument("--n", type=int, default=3)
    args = ap.parse_args()
    run(args.model, args.n)
