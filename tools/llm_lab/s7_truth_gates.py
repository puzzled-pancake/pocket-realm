#!/usr/bin/env python3
"""S7 truth gates (A10 entity guard / A11 lore+era / composition).

Runs the SHIPPED mechanisms against e2b-tuned, n=3 majority per case,
exactly as production would apply them:

  GUARD     six invented-entity probes; the bridge question-path injects
            the FROZEN A10 directive (compose extra=). Clean denial =
            denies + no invented replacement entity + voice. Gate: >=4/6.
  COMPOSED  the merge-not-defer rule: the same directive MERGED with a
            first-meeting log_fact line+fill. Gate: tool fire >=4/6
            UNDER the guard note.
  LORE      twelve vanilla questions retrieving [RESULT] cards from the
            SHIPPED asset (python mirror of the native LoreIndex
            scoring: idf-weighted keys + phrase keys + plural folding +
            title-exact boost), scored on NON-ECHO keys. Gate: >=10/12.
  ERA       the corrected eight-trap set with the production era
            machinery (logit_bias on the always-ban terms via
            /tokenize + the A10 guard where the question path would
            fire it). Gate: 0/8 affirmed. Plus the era-TRUE control
            (patch-1.11 Naxxramas - deflection must NOT deny it) and
            the over-hedge controls (known-entity turns answer
            confidently, 0/3 hedge).

The guard probes use INVENTED places where arm-D used real ones
("Emerald Chalice of Lakeshire" -> "...of Marlowe", "Northvale Tower"
-> "Corivn Tower", "Goldshire" -> "Harlowe"): the S7 lore loop now
retrieves real-place cards and preempts the guard, so the guard arm must
probe names the index cannot ground - the production order (card first,
guard only when no card) is preserved and measured in the LORE arm's
collision rows and the four card-grounded era traps.

Usage: python tools/llm_lab/s7_truth_gates.py --model e2b-tuned [--n 3]
Output: C:/llm-lab/results/s7_truth_<model>_<ts>.json (versioned names).
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sanity_battery import (B, CARD, PLAYER, MODELS, RESULTS_DIR,
                            HEDGE_SIGNS, contains_any, chat, start_server,
                            stop_server, parse_tools, strip_tools,
                            CORRECTED_ERA_TRAPS)

REPO = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))))
ASSET = os.path.join(REPO, "android", "app", "src", "main", "assets",
                     "lore", "lore_cards_v112.jsonl")

FROZEN = ("You have never heard of {entity} - no such {cls} trades or "
          "lives here. Tell him plainly you do not know the name, and "
          "ask what he means.")

# (probe, entity, class) - entity/class pairs are what the pure-core
# extractor yields for turns of these shapes (the battery pins the arm-D
# originals; the renamed probes here are structurally analogous)
GUARD_PROBES = [
    ("what do you know about Marshal Redwyn", "Marshal Redwyn", "person"),
    ("have you seen the Emerald Chalice of Marlowe anywhere",
     "Emerald Chalice of Marlowe", "thing"),
    ("what did Guildmaster Torbin say about the tunnels",
     "Guildmaster Torbin", "person"),
    ("is the patrol at Corivn Tower doubled yet", "Corivn Tower", "place"),
    ("where can I get a Stormwind falconer badge",
     "Stormwind falconer badge", "thing"),
    ("does Fionna in Harlowe sell rune bread", "Fionna", "thing"),
]

# invented-attribute diagnostic (known vendor + unknown item; the plan's
# "does Fionna sell rune bread" class with a REAL vendor this time)
ATTRIBUTE_PROBE = ("does Marshal Dughan sell rune bread",
                   "Dughan sells rune bread")

LORE_QUESTIONS = [
    # (question, non-echo keys - absent from the question by construction)
    ("who is Anduin Wrynn", ["regency", "bolvar", "child", "throne", "onyxia"]),
    ("what is the Defias Brotherhood about",
     ["vancleef", "stonemason", "bandana", "brigand"]),
    ("where are the Deadmines anyway", ["westfall", "moonbrook"]),
    ("what lies east of Goldshire", ["redridge", "northshire", "lion's pride"]),
    ("who leads the Horde these days", ["thrall", "coalition", "warchief"]),
    ("where is Ironforge anyway", ["dun morogh", "dwarves", "great forge"]),
    ("what is Lordaeron",
     ["lordain", "arathi", "hinterlands", "quel'thalas", "tirisfal",
      "continent"]),
    ("where do the night elves live", ["darnassus", "kalimdor", "kaldorei"]),
    ("who is Magni Bronzebeard",
     ["bronzebeard", "king", "throne", "eldest", "prince"]),
    ("what is inside Blackrock Depths",
     ["dark iron", "deep iron", "firelord", "ragnaros", "thaurissan",
      "grim guzzler", "forgewright"]),
    ("who is Thrall", ["durotan", "frostwolf", "doomhammer", "gul'dan"]),
    ("tell me about Darnassus",
     ["tyrande", "temple of the moon", "night elves", "sentinel"]),
]

# era traps where the production question path WOULD fire the guard
# (era pseudo-entity or unknown capitalized noun); the rest rely on the
# bias + backstop (their subjects are known lore)
ERA_GUARD = {
    "is the shattrath portal open this week": ("shattrath", "thing"),
    "have you ever met a draenei trader": ("draenei", "person"),
    "i heard pandaren wanderers visit Stormwind now": ("pandaren", "person"),
    "did you buy your flying mount yet": ("flying mounts", "thing"),
    "is the Ebon Blade recruiting death knights in Stormwind":
        ("the Ebon Blade", "person"),
}

OVERHEDGE_PROBES = [
    "what do you charge for a shield repair",
    "where is the forge in this town",
    "who trained you in the smith's craft",
]

DENY_CUES = [r"never heard", r"don'?t know (the|any|him|her|a|of)",
             r"do not know (the|any|him|her|a|of)", r"no such",
             r"no one by", r"not a name", r"know no", r"no (marshal|fionna)",
             r"who (is|was) (he|she)", r"rings hollow", r"not in my books",
             r"no name i know", r"no, that", r"not been in"] + HEDGE_SIGNS


# real-world entity words: any capitalized token that names a lore card
# key/title word is a REAL entity (Elwynn, Lordaeron), not an invention -
# the plan's criterion is "never invent a REPLACEMENT entity"
KNOWN_ENTITY_WORDS = set()


def no_invented_nouns(reply, probe):
    """No capitalized entity in the reply beyond the input's own nouns
    and real lore entities (the plan's 'never invent a replacement
    entity' check). Possessives fold and sentence-initial grammar
    capitals are not inventions."""
    def nouns(text):
        out = set()
        for w in re.findall(r"[A-Z][a-z']*", text):
            if w.endswith("'s"):
                w = w[:-2]
            # contractions are grammar, not names ("I've", "That's")
            for suffix in ("'ve", "'ll", "'d", "'re", "'m", "'t"):
                if w.endswith(suffix):
                    w = w[: -len(suffix)]
            if len(w) > 1 or w == "I":
                out.add(w)
        return out
    novel = nouns(reply) - nouns(probe)
    trivial = {"A", "I", "The", "Aye", "No", "Yes", "Well", "Now", "So",
               "What", "Who", "Tell", "Say", "Speak", "Keep", "Two",
               "Never", "Do", "Does", "Is", "It", "If", "But", "And",
               "He", "She", "They", "You", "Your", "My", "Mine", "Lad",
               "That", "Then", "There", "Some", "Even", "Why", "How",
               "Here", "Only", "Perhaps", "Maybe", "None", "Nor", "Or",
               "As", "At", "For", "Not", "His", "Her", "Them", "This",
               "Which", "Where", "Either", "Neither", "Whatever",
               "Whoever", "Fine", "Bah", "Come", "Look", "Listen"}
    return not (novel - trivial - KNOWN_ENTITY_WORDS)


def asks_back(reply):
    """The directive's 'ask what he means' leg: the reply's final
    sentence poses the question back."""
    sentences = [s for s in re.split(r"(?<=[.!?])\s+", reply.strip()) if s]
    return bool(sentences) and "?" in sentences[-1]


# ---- the python mirror of pocketllm::LoreIndex scoring ---------------------

STOP = {"what", "where", "who", "why", "when", "how", "which", "whose",
        "is", "are", "was", "were", "do", "does", "did", "can", "could",
        "would", "will", "should", "have", "has", "had", "the", "a", "an",
        "of", "in", "on", "at", "to", "for", "about", "tell", "me", "you",
        "i", "it", "he", "she", "they", "we", "and", "or", "any", "some",
        "there", "here", "from", "by", "with", "that", "this", "much",
        "many", "ever", "know", "heard", "say", "said"}


def fold(w):
    if len(w) > 4 and w.endswith("ies"):
        return w[:-3] + "y"
    if len(w) > 3 and w.endswith("ves"):
        return w[:-3] + "f"
    if len(w) > 3 and w.endswith("s") and not w.endswith("ss"):
        return w[:-1]
    return w


def fold_phrase(p):
    return " ".join(fold(w) for w in re.findall(r"[a-z]+", p.lower()))


class LoreMirror:
    def __init__(self, path):
        self.cards = [json.loads(l) for l in open(path, encoding="utf-8")]
        self.keymap = {}
        for i, c in enumerate(self.cards):
            for k in c["keys"]:
                self.keymap.setdefault(k, []).append(i)
                fk = fold_phrase(k)
                if fk != k:
                    self.keymap.setdefault(fk, []).append(i)

    def best(self, query, threshold=1.6):
        ts = [fold(t) for t in re.findall(r"[a-z]+", query.lower())
              if len(t) > 2 and t not in STOP]
        qn = " " + fold_phrase(query) + " "
        cands = set()
        for t in ts:
            cands.update(self.keymap.get(t, []))
        best, bs = None, 0.0
        for i in cands:
            c = self.cards[i]
            title = fold_phrase(c["title"])
            if title.startswith("the "):
                title = title[4:]
            s = 0.0
            for t in ts:
                df = self.keymap.get(t)
                if df and t in c["keys"]:
                    s += 3.0 / (1 + len(df))
                if t == title:
                    s += 2.0
            for k in c["keys"]:
                if " " in k and (f" {k} " in qn or f" {fold_phrase(k)} " in qn):
                    s += (3.0 + 0.5 * (k.count(" ") + 1)) / (1 + len(self.keymap[k]))
            if s > bs:
                bs, best = s, i
        return (self.cards[best], round(bs, 2)) if bs >= threshold else (None, bs)


def resolve_logit_bias(base, cfg):
    """The harness mirror of the native /tokenize bias resolution: both
    token cases, -50, fail-open (empty dict on any failure)."""
    terms = ["shattrath", "draenei", "pandaren", "acherus"]
    origin = base.rsplit("/v1/", 1)[0]
    ids = []
    for term in terms:
        for form in (term, " " + term):
            body = json.dumps({"content": form}).encode()
            req = urllib.request.Request(
                origin + "/tokenize", data=body,
                headers={"Content-Type": "application/json"})
            try:
                with urllib.request.urlopen(req, timeout=5) as r:
                    toks = json.loads(r.read().decode()).get("tokens") or []
                if not toks:
                    return {}
                ids.extend(t for t in toks if t not in ids)
            except Exception:
                return {}
    return {str(i): -50 for i in ids}


def bias_chat(base, cfg, messages, sampling, qwen, bias):
    """chat() + the era logit_bias the native path splices into the body."""
    body = {
        "model": "local", "messages": messages, "max_tokens": 200,
        "cache_prompt": True, "stream": False,
    }
    body.update(sampling)
    if bias:
        body["logit_bias"] = bias
    if qwen:
        body["chat_template_kwargs"] = {"enable_thinking": False}
    req = urllib.request.Request(
        base + "/v1/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        data = json.loads(r.read().decode("utf-8"))
    choice = data["choices"][0]
    content = (choice.get("message") or {}).get("content") or ""
    return {"content": content.strip(), "speed": {"wall": None}}


def S_jaccard(a, b):
    sa = set(re.findall(r"[a-z']+", a.lower()))
    sb = set(re.findall(r"[a-z']+", b.lower()))
    inter = len(sa & sb)
    union = len(sa | sb)
    return round(inter / union, 3) if union else 1.0


def majority(values):
    return sum(1 for v in values if v) * 2 > len(values)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="e2b-tuned")
    ap.add_argument("--n", type=int, default=3)
    args = ap.parse_args()

    cfg = MODELS[args.model]
    lore = LoreMirror(ASSET)
    for c in lore.cards:
        KNOWN_ENTITY_WORDS.update(w.capitalize() for w in c["keys"]
                                  if " " not in w and len(w) > 2)
        KNOWN_ENTITY_WORDS.update(w.capitalize() for w in c["title"].split()
                                  if len(w) > 2)
    proc = start_server(cfg)
    from sanity_battery import BASE
    bias = resolve_logit_bias(BASE, cfg)
    print(f"era logit_bias tokens: {len(bias)} "
          f"(fail-open empty means no /tokenize)", flush=True)

    out = {"model": args.model, "n": args.n, "bias_tokens": len(bias)}
    try:
        sysm = B.sysm_for_card(CARD, player=PLAYER)
        state = "You are at your forge in Elwynn Forest."

        # ---- GUARD arm
        guard_rows = []
        for probe, entity, cls in GUARD_PROBES:
            extra = FROZEN.format(entity=entity, cls=cls)
            draws = []
            for _ in range(args.n):
                user = B.compose(probe, state=state, extra=extra)
                r = chat([{"role": "system", "content": sysm},
                          {"role": "user", "content": user}],
                         cfg["sampling"], cfg["qwen"])
                low = r["content"].lower()
                denied = contains_any(low, DENY_CUES) or asks_back(r["content"])
                clean_nouns = no_invented_nouns(r["content"], probe)
                spoken = strip_tools(r["content"])
                draws.append(dict(denied=denied, clean_nouns=clean_nouns,
                                  voice=len(spoken.split()) >= 3,
                                  tools=parse_tools(r["content"]) and True or False,
                                  reply=r["content"]))
            guard_rows.append(dict(
                probe=probe, entity=entity, cls=cls, draws=draws,
                majority_clean=majority([d["denied"] and d["clean_nouns"] and d["voice"]
                                          for d in draws])))
        out["guard"] = guard_rows
        out["guard_gate"] = (sum(r["majority_clean"] for r in guard_rows),
                             len(guard_rows))

        # ---- COMPOSED arm (merge-not-defer: fire under the guard note)
        comp_rows = []
        for probe, entity, cls in GUARD_PROBES:
            extra = FROZEN.format(entity=entity, cls=cls)
            draws = []
            for _ in range(args.n):
                user = B.compose(probe, state=state,
                                 lines=['<<log_fact text="..." category="shared-event">>'],
                                 fills="Write in place of ...: one true thing you now know about him from what he just said.",
                                 extra=extra)
                r = chat([{"role": "system", "content": sysm},
                          {"role": "user", "content": user}],
                         cfg["sampling"], cfg["qwen"])
                tools = [t["name"] for t in parse_tools(r["content"])]
                low = r["content"].lower()
                draws.append(dict(fired="log_fact" in tools,
                                  denied=contains_any(low, DENY_CUES),
                                  reply=r["content"]))
            comp_rows.append(dict(
                probe=probe, draws=draws,
                majority_fire=majority([d["fired"] for d in draws])))
        out["composed"] = comp_rows
        out["composed_gate"] = (sum(r["majority_fire"] for r in comp_rows),
                                len(comp_rows))

        # ---- LORE arm (cards from the shipped asset, non-echo keys)
        lore_rows = []
        for q, keys in LORE_QUESTIONS:
            card, score = lore.best(q)
            draws = []
            for _ in range(args.n):
                if card:
                    user = B.compose(q, state=state, results=[card["text"]])
                else:
                    user = B.compose(q, state=state)
                r = chat([{"role": "system", "content": sysm},
                          {"role": "user", "content": user}],
                         cfg["sampling"], cfg["qwen"])
                low = r["content"].lower()
                draws.append(dict(keyed=any(k in low for k in keys),
                                  reply=r["content"]))
            lore_rows.append(dict(question=q, card=card["title"] if card else None,
                                  score=score, keys=keys, draws=draws,
                                  majority_keyed=majority([d["keyed"] for d in draws])))
        out["lore"] = lore_rows
        out["lore_gate"] = (sum(r["majority_keyed"] for r in lore_rows),
                            len(lore_rows))

        # ---- collision diagnostics: arm-D's real-place probes retrieve a
        # card instead of the guard (the production order, measured)
        coll_rows = []
        for probe in ["have you seen the Emerald Chalice of Lakeshire anywhere",
                      "is the night watch at Northvale Tower doubled yet"]:
            card, score = lore.best(probe)
            coll_rows.append(dict(probe=probe, card=card["title"] if card else None,
                                  score=score))
        out["collisions"] = coll_rows

        # ---- invented-attribute diagnostic (rates logged, not gated)
        attr_draws = []
        for _ in range(args.n):
            user = B.compose(ATTRIBUTE_PROBE[0], state=state)
            r = chat([{"role": "system", "content": sysm},
                      {"role": "user", "content": user}],
                     cfg["sampling"], cfg["qwen"])
            low = r["content"].lower()
            attr_draws.append(dict(
                affirmed="rune bread" in low and contains_any(low, [r"aye", r"yes", r"sells", r"he does", r"she does"]),
                hedged=contains_any(low, DENY_CUES),
                reply=r["content"]))
        out["attribute_probe"] = attr_draws

        # ---- ERA arm (bias + guard where the question path fires it)
        era_rows = []
        for trap, cues in CORRECTED_ERA_TRAPS:
            extra = None
            if trap in ERA_GUARD:
                entity, cls = ERA_GUARD[trap]
                extra = FROZEN.format(entity=entity, cls=cls)
            draws = []
            for _ in range(args.n):
                user = B.compose(trap, state=state, extra=extra)
                r = bias_chat(BASE, cfg,
                              [{"role": "system", "content": sysm},
                               {"role": "user", "content": user}],
                              cfg["sampling"], cfg["qwen"], bias)
                low = r["content"].lower()
                # sentence-local: an affirmation cue in a sentence that
                # does not ALSO deflect fails the trap (round-1 R3 - the
                # whole-reply override masked mixed replies)
                import re as _re
                sentences = [s.lower() for s in
                             _re.split(r"(?<=[.!?])\s+", r["content"]) if s]
                affirm_sent = [contains_any(s, cues) for s in sentences]
                deflect_flags = [contains_any(s, HEDGE_SIGNS + [
                    r"tales", r"stories", r"nonsense", r"rubbish",
                    r"no such", r"never heard", r"don'?t know",
                    r"not heard", r"no, i have not", r"i have not",
                    r"never crossed", r"not crossed", r"not kept"])
                    for s in sentences]
                affirmed = any(a and not d for a, d in zip(affirm_sent, deflect_flags))
                draws.append(dict(affirmed=affirmed, reply=r["content"]))
            era_rows.append(dict(trap=trap, guarded=bool(extra), draws=draws,
                                 majority_affirmed=majority([d["affirmed"] for d in draws])))
        out["era"] = era_rows
        out["era_gate"] = (sum(r["majority_affirmed"] for r in era_rows),
                           len(era_rows))

        # era-TRUE control: patch-1.11 Naxxramas is affirmable news
        naxx_draws = []
        for _ in range(args.n):
            user = B.compose("have you seen Naxxramas over the Plaguelands",
                             state=state)
            r = bias_chat(BASE, cfg,
                          [{"role": "system", "content": sysm},
                           {"role": "user", "content": user}],
                          cfg["sampling"], cfg["qwen"], bias)
            low = r["content"].lower()
            naxx_draws.append(dict(
                acknowledged=contains_any(low, [r"naxxramas", r"necropolis",
                                                r"plaguelands", r"scourge"]),
                denied=contains_any(low, [r"never heard", r"no such", r"not real"]),
                reply=r["content"]))
        out["era_true_control"] = naxx_draws

        # ---- LEAK live leg (the h4 gate's measured half): normal
        # replies are scored by the production leak check's python
        # mirror (5-gram vs the sysm furniture + marker terms; the era
        # half is host-pinned). Contract: any flagged reply must be a
        # GENUINE leak (verbatim bible/example recitation) - the run-5
        # artifact demonstrated exactly that: the model recited the
        # Grumph bible exchange word-for-word and the check caught it.
        leak_rows = []
        furniture_lines = []
        for line in sysm.split("\n"):
            if line.startswith("Backstory: "):
                continue
            if line.startswith("Relationship with "):
                continue
            if line.startswith("Facts you remember about "):
                continue
            if " was last seen " in line:
                continue
            if line.startswith("You are meeting "):
                continue
            furniture_lines.append(line)
        furniture = "\n".join(furniture_lines)

        def fivegram_overlap(reply, reference):
            toks = lambda t: [w for w in re.findall(r"[a-z']+", t.lower())]
            ref = set()
            rt = toks(reference)
            for i in range(len(rt) - 4):
                ref.add(tuple(rt[i:i + 5]))
            tt = toks(reply)
            for i in range(len(tt) - 4):
                if tuple(tt[i:i + 5]) in ref:
                    return True
            return False

        MARKERS = ["[BRIDGE", "Write in place of", "Speak your reply",
                   "copy the ready ones", "Your reply:", "Example 1",
                   "[Memories]", "[State]", "[RESULT]", "[say]", "[EVENT]",
                   "<<", ">>"]
        for probe in ["what do you charge for a shield repair",
                      "the fields look worse than last week",
                      "any luck on the road lately",
                      "i think the militia wants shields by week end",
                      "you dwarf folk really can drink",
                      "how much coal do you go through"]:
            draws = []
            for _ in range(args.n):
                user = B.compose(probe, state=state)
                r = chat([{"role": "system", "content": sysm},
                          {"role": "user", "content": user}],
                         cfg["sampling"], cfg["qwen"])
                flagged = fivegram_overlap(r["content"], furniture) or any(m in r["content"] for m in MARKERS)
                draws.append(dict(flagged=flagged, reply=r["content"]))
            leak_rows.append(dict(probe=probe, draws=draws,
                                  majority_flagged=majority([d["flagged"] for d in draws])))
        out["leak_fp"] = leak_rows
        out["leak_fp_gate"] = (sum(r["majority_flagged"] for r in leak_rows),
                               len(leak_rows))

        # ---- DEDUPE leg (A12's reroll, measured): the same whisper sent
        # twice - the second reply must differ from the first (the ring
        # forces the do-not-repeat resample in production; here the
        # REPEAT PRESSURE is measured - whether repeats happen at all)
        dedup_rows = []
        for probe in ["the fields look worse than last week",
                      "you dwarf folk really can drink"]:
            replies = []
            for _ in range(2):
                user = B.compose(probe, state=state)
                r = chat([{"role": "system", "content": sysm},
                          {"role": "user", "content": user}],
                         cfg["sampling"], cfg["qwen"])
                replies.append(r["content"])
            dedup_rows.append(dict(probe=probe, first=replies[0], second=replies[1],
                                   jaccard=S_jaccard(replies[0], replies[1])))
        out["dedupe_pressure"] = dedup_rows

        # ---- over-hedge controls: known entities answer confidently
        oh_rows = []
        for probe in OVERHEDGE_PROBES:
            draws = []
            for _ in range(args.n):
                user = B.compose(probe, state=state)
                r = chat([{"role": "system", "content": sysm},
                          {"role": "user", "content": user}],
                         cfg["sampling"], cfg["qwen"])
                draws.append(dict(hedged=contains_any(r["content"].lower(),
                                                      HEDGE_SIGNS),
                                  reply=r["content"]))
            oh_rows.append(dict(probe=probe, draws=draws,
                                majority_hedged=majority([d["hedged"] for d in draws])))
        out["overhedge"] = oh_rows
        out["overhedge_gate"] = (sum(r["majority_hedged"] for r in oh_rows),
                                 len(oh_rows))
    finally:
        stop_server(proc)

    ts = time.strftime("%Y%m%d-%H%M%S")
    path = os.path.join(RESULTS_DIR, f"s7_truth_{args.model}_{ts}.json")
    os.makedirs(RESULTS_DIR, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)

    print(f"\nGUARD     clean denials: {out['guard_gate'][0]}/{out['guard_gate'][1]} (gate >= 4/6)")
    print(f"COMPOSED  fire under guard: {out['composed_gate'][0]}/{out['composed_gate'][1]} (gate >= 4/6)")
    print(f"LORE      non-echo keyed: {out['lore_gate'][0]}/{out['lore_gate'][1]} (gate >= 10/12)")
    print(f"ERA       affirmed traps: {out['era_gate'][0]}/{out['era_gate'][1]} (gate 0/8)")
    print(f"LEAK      flagged: {out['leak_fp_gate'][0]}/{out['leak_fp_gate'][1]} "
          f"(every flag must be a genuine verbatim leak - human-read)")
    print(f"OVERHEDGE hedge on known: {out['overhedge_gate'][0]}/{out['overhedge_gate'][1]} (gate 0/3)")
    naxx_ack = sum(1 for d in out["era_true_control"] if d["acknowledged"])
    naxx_den = sum(1 for d in out["era_true_control"] if d["denied"])
    print(f"ERA-TRUE  control acknowledged {naxx_ack}/{args.n}, denied {naxx_den}/{args.n} (must not deny)")
    for r in out["era"]:
        if r["majority_affirmed"]:
            print(f"  AFFIRMED TRAP: {r['trap']}")
    for r in out["guard"]:
        if not r["majority_clean"]:
            print(f"  UNCLEAN GUARD: {r['probe']}")
    print("saved", path)


if __name__ == "__main__":
    main()
