#!/usr/bin/env python3
"""The see-saw ladder checkpoint battery: n=3 majority scoring,
per tool family, over the model's ACTUAL composite prompt.

The see-saw law: ONE prompt-text change per checkpoint, each with an n=3
battery run scored PER TOOL FAMILY (signals invisible in aggregates are
the reason - emotes once moved 18->0 under an aggregate-only view).
Two composite modes:

* trained (default): the trained contract (banklib sysm + compose) - the
  app's LLMPromptFormat=1 path, byte-identical to the shipped renderer
  (tests/test_llm_prompt_format.py gates that parity).
* legacy-composite: the legacy app shape -
  the legacy pre-prompt identity + the TOOLS_NOTE appended (the exact
  text ToolInstructions() returns, crc32-variant-matched) + the note
  block appended to the user turn (the exact compose rendering). This
  mode makes the instrument SENSITIVE to legacy-path prompt changes
  (the tool-note checkpoint must score under BOTH composites).

Scoring: per-family fire MAJORITY across n draws (the gate metric); fill
and hygiene are reported CONDITIONAL on fire (a never-fired family cannot
pass vacuously); restraint reports the pooled rate and all-clean.

Usage:  python tools/llm_lab/run_n3_checkpoint.py [--model e2b-tuned] [--n 3]
                [--mode trained|legacy-composite] [--tag label]
Output: RESULTS_DIR/n3_<mode>_<model>_<tag>_<ts>.json (+ table).
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

# the legacy pre-prompt identity (the app's pre-trained-format conf
# default shape) -
# the composite the llama/template backends sent before the trained format;
# the legacy composite appends the TOOLS_NOTE after it (exactly as the
# legacy paths do)
LEGACY_PRE_PROMPT = (
    "You are a roleplaying character in World of Warcraft: Classic. "
    "Your name is Grumph. The player Brannoc is speaking to you in say "
    "and is a male human paladin of level 22. You are level 40 and play "
    "as a male dwarf warrior that is currently in Goldshire Elwynn "
    "Forest. Answer as a roleplaying character."
)

S1_CASES = [
    dict(player="i finally saved enough for that stormwind forge permit",
         expect="log_fact", fill_field="text"),
    dict(player="haha, you actually out-forged the guildmaster",
         expect="perform_emote"),
    dict(player="your work is pig iron trash and you know it",
         expect="adjust_sentiment", fill_field="reason"),
    dict(player="did you hear about the murloc raid on the east docks",
         expect="share_gossip", fill_field="text"),
    dict(player="fine, you win the argument. duel me, right now, goldshire",
         expect="duel_challenge"),
    dict(player="here, take this hammer for the militia order, no charge",
         expect="give_item"),
]
S2_TURNS = [
    "lovely evening out here",
    "what do you charge for a shield repair",
    "the rain is going to rust my armor",
    "any news from the militia",
    "you dwarf folk really can drink",
    "i think i will head to westfall tomorrow",
]


def note_block_for(turn: str) -> str:
    """Note blocks for the legacy-composite mode. NOT a byte-mirror of the
    bridge's trigger lists: it scores the elicit-sensitivity of the legacy
    shape by sending each S1 case its trained note (the corpus shape);
    the game bridge would send notes only where its own triggers and
    state gates match (e.g. the news beat also requires a verified
    event; duel/give_item notes await S6's executor whitelist), and its
    log_fact fill hint differs. Conservative direction: more room for
    spurious tools, none observed (S2 all-clean)."""
    low = turn.lower()
    if "trash" in low or "pig iron" in low:
        return BK.compose(None, lines=['<<adjust_sentiment direction="-1" reason="...">>'],
                          fills="Write in place of ...: how he just treated you - the reason.")
    if "haha" in low:
        return BK.compose(None, lines=['<<perform_emote emote="laugh">>'])
    if "saved enough" in low:
        return BK.compose(None, lines=['<<log_fact text="..." category="shared-event">>'],
                          fills="Write in place of ...: what he just told you about his savings.")
    if "murloc raid" in low:
        return BK.compose(None, lines=['<<share_gossip text="...">>'],
                          fills="Write in place of ...: the news - one line about what just happened.")
    if "duel me" in low:
        return BK.compose(None, lines=['<<duel_challenge name="Brannoc">>'])
    if "take this hammer" in low:
        return BK.compose(None, lines=['<<give_item player="Brannoc" item="hammer">>'])
    return ""


def build_legacy_user(turn: str, note_block: str) -> str:
    # the legacy template's user content shape: receiver-tagged message +
    # post prompt; the A1 note block rides the tail (RenderLegacyTurn)
    tail = note_block if note_block else ""
    return "Grumph:" + turn + " Grumph:" + tail


def score_case(case: dict, r: dict) -> dict:
    tools = SB.parse_tools(r["content"])
    spoken = SB.strip_tools(r["content"])
    names = [t["name"] for t in tools]
    fired = case["expect"] in names
    fill_ok = True
    if case.get("fill_field"):
        for t in tools:
            if t["name"] == case["expect"]:
                v = t["fields"].get(case["fill_field"], "")
                if "..." in v or not v.strip():
                    fill_ok = False
    return dict(case=case["player"], expect=case["expect"], fired=fired,
                fill_ok=fill_ok,
                voice_ok=bool(spoken) and len(spoken.split()) >= 3,
                hygiene=("[BRIDGE" not in r["content"]
                         and all(n in ("log_fact", "adjust_sentiment", "share_gossip",
                                       "perform_emote", "duel_challenge", "give_item",
                                       "follow", "party_invite", "move_to", "loot_roll")
                                 for n in names)),
                reply=r["content"], tools=names)


def run_checkpoint(model_name: str, n: int, mode: str, tag: str) -> dict:
    cfg = SB.MODELS[model_name]
    proc = SB.start_server(cfg)
    report = {"model": model_name, "n": n, "mode": mode, "runs": [],
              "families": {}, "s2": {}}
    try:
        tools_note = BK.tools_note_for("labgrumph01")
        trained_sysm = BK.sysm_for_card(SB.CARD, player=SB.PLAYER)
        legacy_sysm = LEGACY_PRE_PROMPT + " " + tools_note
        family_rows = defaultdict(list)
        for run in range(n):
            s1_rows, s2_rows = [], []
            if mode == "legacy-composite":
                for case in S1_CASES:
                    note = note_block_for(case["player"])
                    r = SB.chat([{"role": "system", "content": legacy_sysm},
                                 {"role": "user", "content":
                                  build_legacy_user(case["player"], note)}],
                                cfg["sampling"], cfg["qwen"])
                    row = score_case(case, r)
                    # S6 ledger (k): the note text joins the artifact so
                    # checkpoint-to-checkpoint wording identity is
                    # diffable from the JSON alone
                    row["note"] = note
                    s1_rows.append(row)
                for t in S2_TURNS:
                    r = SB.chat([{"role": "system", "content": legacy_sysm},
                                 {"role": "user", "content": build_legacy_user(t, "")}],
                                cfg["sampling"], cfg["qwen"])
                    s2_rows.append({"turn": t, "clean": not SB.parse_tools(r["content"]),
                                    "reply": r["content"]})
            else:
                SB.s1_tools({"S1": s1_rows}, trained_sysm, cfg["sampling"], cfg["qwen"])
                SB.s2_restraint({"S2": s2_rows}, trained_sysm, cfg["sampling"], cfg["qwen"])
            report["runs"].append({"run": run, "s1": s1_rows, "s2": s2_rows})
            for row in s1_rows:
                family_rows[row["expect"]].append(row)
        for fam, rows in family_rows.items():
            draws = len(rows)
            fired = [r for r in rows if r["fired"]]
            report["families"][fam] = {
                "draws": draws,
                "fire_majority": len(fired) * 2 > draws,
                "fire_rate": round(len(fired) / draws, 2),
                "fill_rate_when_fired": round(
                    sum(1 for r in fired if r["fill_ok"]) / len(fired), 2) if fired else None,
                "voice_rate": round(sum(1 for r in rows if r["voice_ok"]) / draws, 2),
                "hygiene_rate_when_fired": round(
                    sum(1 for r in fired if r["hygiene"]) / len(fired), 2) if fired else None,
            }
        s2_all = [row for run in report["runs"] for row in run["s2"]]
        report["s2"] = {
            "clean_rate": round(sum(1 for r in s2_all if r["clean"]) / len(s2_all), 2),
            "all_clean": all(r["clean"] for r in s2_all),
        }
    finally:
        SB.stop_server(proc)

    os.makedirs(RESULTS_DIR, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    name = f"n3_{mode}_{model_name}_{tag}_{ts}.json" if tag else f"n3_{mode}_{model_name}_{ts}.json"
    out = os.path.join(RESULTS_DIR, name)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=1, ensure_ascii=False)

    print(f"\n===== {model_name} n={n} {mode} checkpoint (per tool family) =====")
    for fam, stats in report["families"].items():
        fill = stats["fill_rate_when_fired"]
        hyg = stats["hygiene_rate_when_fired"]
        print(f"{fam:18s} fire {stats['fire_rate']:.2f} "
              f"fill(when fired) {fill if fill is not None else '-'} "
              f"voice {stats['voice_rate']:.2f} "
              f"hyg(when fired) {hyg if hyg is not None else '-'} "
              f"(majority fire: {stats['fire_majority']})")
    print(f"S2 restraint clean rate: {report['s2']['clean_rate']:.2f} "
          f"(all clean: {report['s2']['all_clean']})")
    print("saved:", out)
    return report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="e2b-tuned", choices=list(SB.MODELS))
    parser.add_argument("--n", type=int, default=3)
    parser.add_argument("--mode", default="trained",
                        choices=("trained", "legacy-composite"))
    parser.add_argument("--tag", default="")
    args = parser.parse_args()
    run_checkpoint(args.model, args.n, args.mode, args.tag)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
