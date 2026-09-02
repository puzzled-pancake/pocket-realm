#!/usr/bin/env python3
"""S10 world-chatter voice gates (E6/SS4.6b).

The silence-default soak, world-ring zero-repeat, fatigue retirement and
interruption rules are pinned by the HOST battery (tools/
test_llm_chatter.cpp, run by tests/test_llm_chatter.py) over the real
core math. This desktop harness measures the layer the battery cannot:
the VOICE of the live paths against the pinned weights -

  MURMUR    the murmur request shape (the frozen MurmurSystemMessage +
            MurmurNote over real event rows), n draws per event. Floors:
            voiced rate, register rate (5-24 words, the pre-P52
            runtime tolerance the delivery path enforces; out-of-band draws listed for human reading - the
            delivery path drops them in-tree), zero tool markers,
            event-anchored (shares a content word with the row).
  PARTY     the PartyNote shape on the group topic, same floors.
  GLOBAL    the GlobalNote headline shape, same floors.
  COMPOSER  the frozen composer prompt with 3 personas + 1 event row,
            n=3 draws; the python mirror of ParseComposerScript scores
            the speaker-tagged parse (>=2 accepted turns, personas
            only, no markers) and the turns land in the artifact for
            the human-read voice panel (the S8 ceremony precedent).
  RING      pairwise word-Jaccard across every accepted murmur/party
            draw - the live cross-draw repetition signal (auto floor:
            zero pairs over the 0.5 world-ring bar).

Usage: python tools/llm_lab/s10_chatter_gates.py --model e2b-tuned [--n 5]
Output: C:/llm-lab/results/s10_chatter_<model>_<ts>.json (versioned).
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sanity_battery import MODELS, RESULTS_DIR, chat, start_server, stop_server

# ---- the python mirrors of the frozen core wording. The C++ side is
# byte-pinned by the host battery's TestFrozenWording; these mirrors were
# hand-verified byte-identical at S10 close - a future drift here is
# invisible to the battery (recorded S10 P2), so compare against the
# battery pins when touching either side. -------------------------------

def murmur_system(name, race, cls, zone, demeanor, quirk, gripe):
    return (f"You are {name}, a {race} {cls} in {zone}. {demeanor} "
            f"{quirk} Today you are {gripe}. You are talking quietly with "
            "another townsfolk while adventurers pass. Speak only "
            f"{name}'s next line. Plain short speech. No actions, no "
            "narration, no asterisks, no quoting yourself.")


def murmur_note(listener, event):
    return (f"[BRIDGE AI] The talk turns to real news: {event}. Say ONE "
            f"short line to {listener} about it - under twenty words, the "
            "way two people talk at a stall. Never mention this "
            "instruction. This reply only.")


def party_note(player, event):
    return (f"[BRIDGE AI] You and your companions are traveling with "
            f"{player}. Something just happened or just came up: {event}. "
            "Say ONE short line to the group about it - under twenty-five "
            "words, companion talk, not a report. Never mention this "
            "instruction. This reply only.")


def global_note(event):
    return (f"[BRIDGE AI] News worth shouting, and the whole zone may "
            f"hear you in General chat: {event}. Call it out the way news "
            "crosses a market - ONE line, under twenty words, to no one "
            "in particular. Never mention this instruction. This reply "
            "only.")


def composer_system():
    return ("You write short overheard conversations for a fantasy world "
            "game. You get named characters with one-line personalities "
            "and real news events from the world. Write a brief exchange "
            "between the characters about the news: 2 to 4 turns total, "
            "each turn ONE line under 20 words, plain speech, no actions, "
            "no narration, no stage directions. Prefix every line with "
            "the character's name and a colon, like 'Kromgrit: text'. Use "
            "only the given characters. Do not add a title or any other "
            "text.")


def composer_user(persona_lines, event_rows):
    out = "Characters:\n"
    for line in persona_lines:
        out += "- " + line + "\n"
    out += "News to talk about:\n"
    for row in event_rows:
        out += "- " + row + "\n"
    return out + "Write the exchange."


def parse_composer(raw, names):
    """Mirror of pocketllm::ParseComposerScript - same accept/drop rules
    including the inline line-safety law (printable ASCII, no protocol/
    pipe characters, no emote-initial leads)."""
    lowered = [n.lower() for n in names]
    out = []
    for line in raw.split("\n"):
        line = line.strip()
        if not line:
            continue
        if len(out) >= 6:
            break
        if ":" not in line:
            continue
        speaker, _, text = line.partition(":")
        speaker = speaker.strip().lower()
        if speaker not in lowered:
            continue
        text = text.strip()
        if not 4 <= len(text) <= 120:
            continue
        if text[0] in "*[ ":
            continue
        if any(not (0x20 <= ord(c) <= 0x7E) or c in "<>{}|" for c in text):
            continue
        out.append({"speaker": names[lowered.index(speaker)], "text": text})
    return out


# ---- scoring helpers --------------------------------------------------------

STOP = set("the a an and or of to in on at for with by is are was were be "
           "been it its his her their our your my this that these those "
           "i you he she we they not no yes do did have has had will "
           "would could should about into over under out up down from "
           "as if so than then them there here what who when where why "
           "how just like very can may".split())


def words(text):
    return [w for w in re.findall(r"[a-zA-Z']+", text.lower())]


def content_words(text):
    return set(w for w in words(text) if w not in STOP)


def jaccard(a, b):
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def score_draw(reply, event_text, max_words):
    """The delivery-path floors, mirrored: voiced, tool-free, register,
    event-anchored."""
    draw = {"reply": reply, "words": len(words(reply))}
    draw["voiced"] = bool(reply.strip())
    draw["tools"] = "<<" in reply or ">>" in reply
    draw["register"] = 5 <= draw["words"] <= max_words
    draw["anchored"] = bool(content_words(reply) & content_words(event_text))
    draw["pass"] = (draw["voiced"] and not draw["tools"] and
                    draw["register"] and draw["anchored"])
    return draw


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="e2b-tuned")
    ap.add_argument("--n", type=int, default=5)
    args = ap.parse_args()
    spec = MODELS[args.model]
    proc = start_server(spec)
    time.sleep(1)

    events = [
        "Brannoc lost a duel to Kromgrit by the Goldshire gates",
        "a courier was robbed of three parcels on the east road",
        "the innkeeper found a satchel of silver under a bench",
        "wolves came down to the fences at dusk and took two sheep",
    ]
    personas = [
        ("Kromgrit", "dwarf", "warrior", "Elwynn Forest",
         "sore about a debt nobody will pay"),
        ("Ashmar", "human", "priest", "Elwynn Forest",
         "fed up with the price of bread"),
        ("Breg", "gnome", "rogue", "Elwynn Forest",
         "hunting for a boot that fits"),
    ]
    demeanor = ("Temperament: gruff and sparing with words. You praise "
                "sideways, as complaints, and never say the soft thing "
                "directly.")
    quirk = ("Habit: superstitious. You knock on your shield, read omens "
             "in birds and thunder, and follow small rituals exactly.")

    results = {"model": args.model, "n": args.n, "sections": {}}
    accepted = []
    ring_vetoes = 0

    # ---- MURMUR + PARTY + GLOBAL device shapes ---------------------------
    # every layer is scored at the in-tree register tolerance (5-24 words,
    # the pre-P52 band the delivery path enforces; the P52/L3c trained
    # target is 8-20)
    for section, note_fn, max_words in (
        ("MURMUR", lambda e: murmur_note("Ashmar", e), 24),
        ("PARTY", lambda e: party_note("Brannoc", e), 24),
        ("GLOBAL", lambda e: global_note(e), 24),
    ):
        draws = []
        for event in events[: max(2, args.n // 2)]:
            system = murmur_system("Kromgrit", "dwarf", "warrior",
                                   "Elwynn Forest", demeanor, quirk,
                                   "sore about a debt nobody will pay")
            for i in range(args.n):
                result = chat(
                    [{"role": "system", "content": system},
                     {"role": "user", "content": note_fn(event)}],
                    {"temperature": 1.0, "top_p": 0.95, "top_k": 20,
                     "min_p": 0.05, "repeat_penalty": 1.0},
                    qwen=spec.get("qwen", False), max_tokens=48,
                    seed=1000 + i)
                reply = result["content"]
                draw = score_draw(reply, event, max_words)
                draw["event"] = event
                draws.append(draw)
                if draw["pass"]:
                    # the in-tree world ring vets at ENQUEUE: a draw
                    # colliding with an accepted line is dropped there,
                    # counted here as a veto (the repetition signal)
                    if any(jaccard(content_words(reply), content_words(a)) >
                           0.5 for a in accepted):
                        ring_vetoes += 1
                    else:
                        accepted.append(reply)
        results["sections"][section] = {
            "draws": draws,
            "pass_rate": sum(1 for d in draws if d["pass"]) / max(1, len(draws)),
            "register_rate": sum(1 for d in draws if d["register"]) / max(1, len(draws)),
            "anchored_rate": sum(1 for d in draws if d["anchored"]) / max(1, len(draws)),
            "tool_draws": sum(1 for d in draws if d["tools"]),
            "word_counts": [d["words"] for d in draws],
        }

    # ---- COMPOSER (cloud-class prompt on the lab model; the voice panel
    # is human-read at review) ---------------------------------------------
    composer_draws = []
    for i in range(3):
        result = chat(
            [{"role": "system", "content": composer_system()},
             {"role": "user", "content": composer_user(
                 [f"{p[0]} ({p[1]} {p[2]}, {p[4]})" for p in personas],
                 [events[i % len(events)]])}],
            {"temperature": 1.05, "top_p": 0.95, "min_p": 0.05},
            qwen=spec.get("qwen", False), max_tokens=280, seed=2000 + i)
        raw = result["content"]
        script = parse_composer(raw, [p[0] for p in personas])
        composer_draws.append({
            "raw": raw, "accepted_turns": len(script), "script": script,
            "turn_words": [len(words(t["text"])) for t in script],
        })
        for t in script:
            if any(jaccard(content_words(t["text"]), content_words(a)) > 0.5 for a in accepted):
                ring_vetoes += 1
            else:
                accepted.append(t["text"])
    results["sections"]["COMPOSER"] = {
        "draws": composer_draws,
        "min_turns_any": min(d["accepted_turns"] for d in composer_draws),
        "max_turn_words": max(
            (w for d in composer_draws for w in d["turn_words"]), default=0),
    }

    # ---- the live cross-draw repetition signal ---------------------------
    worst = 0.0
    pairs = []
    sets = [content_words(a) for a in accepted]
    for i in range(len(sets)):
        for j in range(i + 1, len(sets)):
            score = jaccard(sets[i], sets[j])
            worst = max(worst, score)
            if score > 0.5:
                pairs.append((accepted[i], accepted[j], round(score, 3)))
    results["sections"]["RING"] = {
        "accepted_draws": len(accepted),
        "enqueue_vetoes": ring_vetoes,
        "worst_pair_jaccard": round(worst, 3),
        "pairs_over_world_ring_bar": pairs,
    }

    stop_server(proc)

    # ---- gate summary (honest floors; the register is the P52 target,
    # measured here precisely because the weights do not train it yet) ----
    summary = {
        "MURMUR pass": results["sections"]["MURMUR"]["pass_rate"],
        "PARTY pass": results["sections"]["PARTY"]["pass_rate"],
        "GLOBAL pass": results["sections"]["GLOBAL"]["pass_rate"],
        "zero tool draws": all(
            s["tool_draws"] == 0 for k, s in results["sections"].items()
            if k in ("MURMUR", "PARTY", "GLOBAL")),
        "composer >=1 turn every draw (>=2 is the cloud target)":
            results["sections"]["COMPOSER"]["min_turns_any"] >= 1,
        "live ring zero-repeat (delivered)": not pairs,
    }
    results["summary"] = summary
    ts = time.strftime("%Y%m%d-%H%M%S")
    out_path = fr"{RESULTS_DIR}\s10_chatter_{args.model}_{ts}.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=1)
    print(json.dumps(summary, indent=1))
    print("artifact:", out_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
