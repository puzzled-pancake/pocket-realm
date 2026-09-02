#!/usr/bin/env python3
"""S8 beats gates (A13 recall / A16 ceremony / event kinds / jaccard).

Runs the SHIPPED beat mechanisms (python mirrors of the pure core's
cargo builders + the bridge's note shapes) against e2b-tuned, n=3
majority per case:

  ASSOCIATIVE   four memory-USE cases - debt question, memory question,
                news recall, greeting weave - each with the planted fact
                in the system facts + [Memories] tail (the S4 baseline
                shape that scored 0/2 associative) PLUS the bridge's
                recall cargo as the note's extra leg. Gate: >=3/4
                majority recall on NON-ECHO keys. A no-note control row
                per case records the lift.
  CEREMONY      the A16 tier-transition note (plan wording) + the
                Trusted secret, 5 rolls on a plain conversational turn;
                auto-floor = no mechanic tokens + voiced + differs from
                the control. The draws land in the artifact for the
                human-read felt-change panel (gate >=4/5, judged at
                review). A paired tier-3 vs tier-5 (nickname tierNote)
                comparison rides along.
  EVENT_KIND    the S8 event notes: level-up licenses cheer + log_fact
                under the [EVENT] head; a duel loss licenses
                adjust_sentiment +1 + log_fact. Gate: >=2/3 fire each.
  JACCARD       the S7-ledger-(d) re-checkpoint: beat-mandated turns
                drawn twice each, pairwise word-Jaccard across replies -
                the ONE-NOTE-family risk the dedupe exemption could
                incubate. Diagnostics (no gate number); repeats above
                the 0.5 dedupe threshold are listed for human reading.

Usage: python tools/llm_lab/s8_beats_gates.py --model e2b-tuned [--n 3]
Output: C:/llm-lab/results/s8_beats_<model>_<ts>.json (versioned names).
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

# ---- the python mirror of PlayerbotLlmRecallCore's cargo builders ----
# rev-3b: the frames come from banklib.BEAT_CARGO_VARIANTS (the wording
# lock - the C++ core emits the same strings), and each composed cargo
# CYCLES the flavor deterministically so a run measures all three (the
# artifact records the flavor + cargo text per row). secret_cargo and
# nickname_adoption stay single-wording (not variant sets).

_FLAVOR_CYCLE = {"i": 0}
_LAST = {"kind": None, "flavor": None}


def _next_flavor():
    f = _FLAVOR_CYCLE["i"] % 3
    _FLAVOR_CYCLE["i"] += 1
    return f


def _render(kind, player="", fact=None, gossip=None, phrase=None, flavor=None):
    f = (flavor if flavor is not None else _next_flavor()) % 3
    frame = B.BEAT_CARGO_VARIANTS[kind][f]
    _LAST.update(kind=kind, flavor=f)
    out = frame.replace("{P}", player)
    if fact is not None:
        out = out.replace("{F}", fact_direct(fact))
    if gossip is not None:
        out = out.replace("{G}", gossip)
    if phrase is not None:
        out = out.replace("{T}", phrase)
    return out


def fact_direct(fact):
    import re as _re
    out = " " + fact
    for a, b in ((" my", " your"), (" me", " you"), (" mine", " yours")):
        # word-boundary on the right, like the C++ (round-1 R3: a bare
        # replace would rewrite "metal" -> "you tal")
        out = _re.sub(_re.escape(a) + r"(?![a-z])", b, out)
    out = out[1:]
    if out.startswith("he "):
        out = out[3:]
    elif out.startswith("she "):
        out = out[4:]
    return out


def debt_cargo(player, fact, flavor=None):
    return _render("debt", player, fact=fact, flavor=flavor)


def memory_cargo(player, fact, ask_after=False, flavor=None):
    return _render("memory_ask" if ask_after else "memory", player,
                   fact=fact, flavor=flavor)


def news_cargo(player, fact, flavor=None):
    return _render("news", player, fact=fact, flavor=flavor)


def ceremony_up(player, tier, flavor=None):
    return _render("ceremony_up", player,
                   phrase=B.CEREMONY_UP_PHRASES.get(tier, "a true friend"),
                   flavor=flavor)


def secret_cargo(player, secret):
    return (f"You trust {player} enough for the one thing you keep: you "
            f"{secret}. Tell it once, briefly, as your own choice - then "
            "let it be.")


def nickname_adoption(player, nick):
    return (f"You have taken to calling {player} by a private name of "
            f"your own: {nick}. Use it in this reply - and when it "
            "suits you after.")


NICKNAME = "Bran"
SECRET = "keep a private tally of every debt, owed and owing"

# ---- the ASSOCIATIVE cases (fact, turn, cargo builder, non-echo keys) ----
ASSOC_CASES = [
    ("owes Brannoc five silver from the ale",
     "wait, do i owe you anything?",
     lambda p, f: debt_cargo(p, f),
     ["silver", "five", "ale"]),
    ("has a cousin Dagna up in Ironforge",
     "do you even remember me at all?",
     lambda p, f: memory_cargo(p, f),
     ["dagna", "cousin", "ironforge"]),
    ("beat you in a duel last night, fair and square",
     "so... any news for me?",
     lambda p, f: news_cargo(p, f),
     ["duel", "beat", "last night"]),
    ("is saving up for a ram of his own",
     "well met again, been a while",
     lambda p, f: memory_cargo(p, f, ask_after=True),
     ["ram", "saving"]),
]

MECHANIC_TOKENS = ["tier", "relationship", "bonded", "trusted",
                   "warm (", "civil (", "points"]


def majority_recall(draws, keys):
    hits = 0
    for d in draws:
        low = strip_tools(d).lower()
        if any(k in low for k in keys):
            hits += 1
    return hits >= (len(draws) // 2 + 1), hits


def jaccard(a, b):
    wa = set(re.findall(r"[a-z']+", a.lower()))
    wb = set(re.findall(r"[a-z']+", b.lower()))
    if not wa or not wb:
        return 0.0
    return len(wa & wb) / len(wa | wb)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="e2b-tuned")
    ap.add_argument("--n", type=int, default=3)
    ap.add_argument("--seed", type=int, default=None)
    args = ap.parse_args()

    model = MODELS[args.model]
    proc = start_server(model)
    try:
        res = {"model": args.model, "n": args.n, "ASSOCIATIVE": [],
               "CEREMONY": {}, "EVENT_KIND": {}, "JACCARD": {}}

        # ---- ASSOCIATIVE ------------------------------------------------
        for fact, turn, build, keys in ASSOC_CASES:
            card = copy.deepcopy(CARD)
            card["facts"] = [fact, "hates spiders"]
            card["tier"] = 3
            card["absence"] = f"{PLAYER['name']} was last seen a few hours ago."
            sysm = B.sysm_for_card(card, player=PLAYER)
            cargo = build(PLAYER["name"], fact)
            flavor = _LAST["flavor"]
            user = B.compose(turn, mems=[fact],
                             state="You are at your forge in Elwynn Forest.",
                             extra=cargo)
            control_user = B.compose(turn, mems=[fact],
                                     state="You are at your forge in Elwynn Forest.")
            draws, control = [], []
            for i in range(args.n):
                r = chat([{"role": "system", "content": sysm},
                          {"role": "user", "content": user}],
                         model["sampling"], model["qwen"], seed=args.seed)
                draws.append(r["content"])
                c = chat([{"role": "system", "content": sysm},
                          {"role": "user", "content": control_user}],
                         model["sampling"], model["qwen"], seed=args.seed)
                control.append(c["content"])
            ok, hits = majority_recall(draws, keys)
            _, chits = majority_recall(control, keys)
            if chits >= (args.n // 2 + 1) and ok:
                print(f"       NOTE: control also majority-recalls ({chits}/{args.n}) "
                      "- no measured lift for this case")
            res["ASSOCIATIVE"].append(dict(
                turn=turn, fact=fact, keys=keys, cargo=cargo, flavor=flavor,
                draws=draws, control=control, recall=ok, hits=f"{hits}/{args.n}",
                control_hits=f"{chits}/{args.n}"))
            print(f"ASSOC  {'PASS' if ok else 'FAIL'} {turn[:38]:40s} "
                  f"{hits}/{args.n} (control {chits}/{args.n})")

        # ---- CEREMONY ---------------------------------------------------
        card = copy.deepcopy(CARD)
        card["facts"] = ["owes Brannoc five silver from the ale"]
        card["tier"] = 4
        card["absence"] = f"{PLAYER['name']} was last seen a few moments ago."
        sysm4 = B.sysm_for_card(card, player=PLAYER)
        turn = "the militia captain wants his order early"
        cargo = ceremony_up(PLAYER["name"], 4) + "\n" + \
            secret_cargo(PLAYER["name"], SECRET)
        user = B.compose(turn, state="You are at your forge in Elwynn Forest.",
                         extra=cargo)
        control_user = B.compose(turn, state="You are at your forge in Elwynn Forest.")
        draws, floor = [], []
        for i in range(5):
            r = chat([{"role": "system", "content": sysm4},
                      {"role": "user", "content": user}],
                     model["sampling"], model["qwen"], seed=args.seed)
            draws.append(r["content"])
            c = chat([{"role": "system", "content": sysm4},
                      {"role": "user", "content": control_user}],
                     model["sampling"], model["qwen"], seed=args.seed)
            voiced = bool(strip_tools(r["content"]).strip())
            clean = not any(t in r["content"].lower()
                            for t in MECHANIC_TOKENS)
            differs = jaccard(strip_tools(r["content"]),
                              strip_tools(c["content"])) < 0.5
            floor.append(voiced and clean and differs)
        controls = []
        for i in range(2):
            c = chat([{"role": "system", "content": sysm4},
                      {"role": "user", "content": control_user}],
                     model["sampling"], model["qwen"], seed=args.seed)
            controls.append(c["content"])
        res["CEREMONY"]["draws"] = draws
        res["CEREMONY"]["controls"] = controls
        res["CEREMONY"]["floor_pass"] = sum(floor)
        res["CEREMONY"]["floor_detail"] = floor
        print(f"CEREMONY floor (voiced+no-mechanic+differs): {sum(floor)}/5 "
              "- human-read felt-change gate is >=4/5 at review")

        # paired tier-3 vs tier-5 (nickname tierNote) on one turn, plus
        # the tier-5 ceremony's ADOPTION procedure (the Westfall law:
        # the standing disposition line alone measured 0/3 - the
        # procedure form must carry the surface)
        card3 = copy.deepcopy(CARD)
        card3["tier"] = 3
        card3["facts"] = ["owes Brannoc five silver from the ale"]
        card5 = copy.deepcopy(card3)
        card5["tier"] = 5
        card5["tier_note"] = (f'You have a private name for {{player}} - "{NICKNAME}"'
                              " - and it slips out more often than their real name.")
        turn5 = "we should get moving before dark"
        pair = {"tier3": [], "tier5": [], "tier5_ceremony": []}
        adoption = ceremony_up(PLAYER["name"], 5) + "\n" + \
            nickname_adoption(PLAYER["name"], NICKNAME)
        for i in range(args.n):
            for tag, c in (("tier3", card3), ("tier5", card5)):
                u = B.compose(turn5, state="You are on the road with {p}.")
                r = chat([{"role": "system", "content": B.sysm_for_card(c, player=PLAYER)},
                          {"role": "user", "content": u}],
                         model["sampling"], model["qwen"], seed=args.seed)
                pair[tag].append(r["content"])
            u = B.compose(turn5, state="You are on the road with {p}.",
                          extra=adoption)
            r = chat([{"role": "system", "content": B.sysm_for_card(card5, player=PLAYER)},
                      {"role": "user", "content": u}],
                     model["sampling"], model["qwen"], seed=args.seed)
            pair["tier5_ceremony"].append(r["content"])
        res["CEREMONY"]["felt_change_pair"] = pair
        nick5 = sum(NICKNAME.lower() in strip_tools(d).lower() for d in pair["tier5"])
        nickc = sum(NICKNAME.lower() in strip_tools(d).lower()
                    for d in pair["tier5_ceremony"])
        print(f"CEREMONY nickname surface: standing tierNote {nick5}/{args.n}, "
              f"adoption procedure {nickc}/{args.n}")

        # ---- EVENT_KIND ---------------------------------------------------
        ev_card = copy.deepcopy(CARD)
        ev_card["tier"] = 3
        ev_sysm = B.sysm_for_card(ev_card, player=PLAYER)
        ev_user = B.compose(
            None, events=[f"{PLAYER['name']} just reached level 23 while "
                          "fighting at our side."],
            state="You are on the road with {p}.",
            lines=['<<log_fact text="..." category="shared-event">>',
                   '<<perform_emote emote="cheer">>'],
            fills="Write in place of ...: the news - one line about what "
                  "just happened.",
            extra="No player words this turn - the world moved on its own. "
                  "You speak first.")
        cheer = 0
        ev_draws = []
        for i in range(args.n):
            r = chat([{"role": "system", "content": ev_sysm},
                      {"role": "user", "content": ev_user}],
                     model["sampling"], model["qwen"], seed=args.seed)
            ev_draws.append(r["content"])
            tools = parse_tools(r["content"])
            if any(t["name"] == "perform_emote" for t in tools):
                cheer += 1
        res["EVENT_KIND"]["levelup_cheer"] = f"{cheer}/{args.n}"
        res["EVENT_KIND"]["levelup_draws"] = ev_draws

        duel_user = B.compose(
            None, events=[f"{PLAYER['name']} beat you in the duel, "
                          "fair and square."],
            state="You are at your usual spot in Elwynn Forest.",
            lines=['<<log_fact text="..." category="shared-event">>',
                   '<<adjust_sentiment direction="+1" reason="...">>'],
            fills="Write in place of the first ...: the news - one line "
                  "about what just happened. In place of the second ...: "
                  "why the fair win earned your regard.",
            extra="No player words this turn - the world moved on its own. "
                  "You speak first.")
        sent = 0
        duel_draws = []
        for i in range(args.n):
            r = chat([{"role": "system", "content": ev_sysm},
                      {"role": "user", "content": duel_user}],
                     model["sampling"], model["qwen"], seed=args.seed)
            duel_draws.append(r["content"])
            tools = parse_tools(r["content"])
            if any(t["name"] == "adjust_sentiment" for t in tools):
                sent += 1
        # the S5/S7-recorded adjust_sentiment single-'>' closer class:
        # a draw whose text contains the complete line closed with one
        # '>' is a fidelity miss, not an omission - both counts recorded
        tolerant = sum(
            1 for d in duel_draws
            if re.search(r'<<adjust_sentiment [^>]*>', d))
        res["EVENT_KIND"]["duellost_sentiment"] = f"{sent}/{args.n}"
        res["EVENT_KIND"]["duellost_sentiment_tolerant"] = f"{tolerant}/{args.n}"
        res["EVENT_KIND"]["duellost_draws"] = duel_draws
        print(f"EVENT  levelup cheer {res['EVENT_KIND']['levelup_cheer']}, "
              f"duel sentiment {res['EVENT_KIND']['duellost_sentiment']} "
              f"(tolerant {tolerant}/{args.n}; gate >=2/{args.n} each)")

        # ---- LONGFORM (S11 P50 baseline) -----------------------------------
        # The frozen cue's measurement against the CURRENT (pre-P50)
        # weights - the honest expectation is partial compliance at best;
        # this records the baseline G5's P(>90w | cued) delivery metric
        # and the P(>60w | uncued) inflation alarm move against. Drawn at
        # max_tokens 230 (the licensed tier's runtime cap) with the SAME
        # composed note the bridge injects (beat_frame cargo + cue).
        lf_cases = [
            ("story", "come on, tell me the story of the bridge fight",
             "held the border bridge against the night raid"),
            ("news", "so... any news for me?",
             "won the duel at the fair, fair and square"),
            ("bonded", "how have you been, really?",
             "is saving up for a ram of his own"),
        ]
        card5 = copy.deepcopy(CARD)
        card5["tier"] = 5
        card5["facts"] = ["owes Brannoc five silver from the ale"]
        lf_sysm = B.sysm_for_card(card5, player=PLAYER)
        lf_state = "You are at your forge in Elwynn Forest."
        for kind, turn, fact in lf_cases:
            cargo_kind = "memory_ask" if kind == "bonded" else "news"
            frame = B.beat_frame(cargo_kind, 30)
            cargo = frame.replace("{P}", PLAYER["name"]).replace(
                "{F}", fact_direct(fact))
            cued = B.compose(turn, state=lf_state,
                             extra=B.longform_extra(cargo))
            uncued = B.compose(turn, state=lf_state,
                               extra=cargo)
            cued_words, uncued_words, cued_draws = [], [], []
            for i in range(args.n):
                r = chat([{"role": "system", "content": lf_sysm},
                          {"role": "user", "content": cued}],
                         model["sampling"], model["qwen"],
                         max_tokens=230, seed=args.seed)
                cued_draws.append(r["content"])
                cued_words.append(len(strip_tools(r["content"]).split()))
                u = chat([{"role": "system", "content": lf_sysm},
                          {"role": "user", "content": uncued}],
                         model["sampling"], model["qwen"],
                         max_tokens=230, seed=args.seed)
                uncued_words.append(len(strip_tools(u["content"]).split()))
            res.setdefault("LONGFORM", {})[kind] = dict(
                turn=turn, fact=fact, cargo=cargo, draws=cued_draws,
                cued_words=cued_words, uncued_words=uncued_words,
                cued_over90=sum(w > 90 for w in cued_words),
                cued_over60=sum(w > 60 for w in cued_words),
                uncued_over60=sum(w > 60 for w in uncued_words))
            print(f"LONGFORM {kind:<7} cued>90w "
                  f"{res['LONGFORM'][kind]['cued_over90']}/{args.n}, "
                  f"uncued>60w {res['LONGFORM'][kind]['uncued_over60']}/{args.n} "
                  f"(pre-P50 baseline; words cued={cued_words} "
                  f"uncued={uncued_words})")

        # ---- JACCARD re-checkpoint (S7 ledger (d)) ------------------------
        rep_draws = []
        for fact, turn, build, keys in ASSOC_CASES[:3]:
            card = copy.deepcopy(CARD)
            card["facts"] = [fact]
            card["tier"] = 3
            sysm = B.sysm_for_card(card, player=PLAYER)
            user = B.compose(turn, state="You are at your forge in Elwynn Forest.",
                             extra=build(PLAYER["name"], fact))
            pair_draws = []
            for i in range(2):
                r = chat([{"role": "system", "content": sysm},
                          {"role": "user", "content": user}],
                         model["sampling"], model["qwen"], seed=args.seed)
                pair_draws.append(strip_tools(r["content"]))
            rep_draws.append(dict(turn=turn, draws=pair_draws,
                                  j=jaccard(pair_draws[0], pair_draws[1])))
        scores = [r["j"] for r in rep_draws]
        res["JACCARD"]["pairs"] = rep_draws
        res["JACCARD"]["mean"] = round(sum(scores) / len(scores), 3)
        res["JACCARD"]["over_threshold"] = [r["turn"] for r in rep_draws
                                            if r["j"] > 0.5]
        print(f"JACCARD same-turn redraw mean {res['JACCARD']['mean']} "
              f"(>0.5: {res['JACCARD']['over_threshold'] or 'none'})")

        os.makedirs(RESULTS_DIR, exist_ok=True)
        out = os.path.join(
            RESULTS_DIR,
            f"s8_beats_{args.model}_{time.strftime('%Y%m%d-%H%M%S')}.json")
        with open(out, "w", encoding="utf-8") as f:
            json.dump(res, f, indent=1)
        print("wrote", out)
    finally:
        stop_server(proc)


if __name__ == "__main__":
    main()
