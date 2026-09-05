#!/usr/bin/env python3
"""Phase-1 density bake-off (plan v4, Phase 1c) - model-free leg.

For each contested seasoning block (voice-lock, anti-omniscience,
boldness, salience, scene-close, ban-list, autonomy, initiative-opener,
mood-weather), renders three density variants - terse (1 clause),
standard (2-3 sentences), verbose+example - and reports:

  * prompt-token cost per variant, measured two ways: the chars/4
    editor heuristic (LlmPromptPack.estimatedTokens) AND the llama-server
    /tokenize count when a server is up (--server http://127.0.0.1:PORT),
    else heuristic only (fail-open, never a gate failure);
  * the RP-depth probe SET for the model-backed leg (the RP_DEPTH leg of
    s8_beats_gates.py): initiative fit, mood consistency, rumor
    fidelity/drift, grudge continuity - prompts only, no generations
    here. The battery runs its probes across plain/seasoned/seasoned+mood
    arms; per-variant numbers come from re-running it with each variant
    pasted into the seasoning arm.

Usage:
  python3 tools/llm_lab/prompt_pack_bakeoff.py [--server http://127.0.0.1:28090]
  python3 tools/llm_lab/prompt_pack_bakeoff.py --emit-vectors out.json

No model, no server required for the default run. Exit 0 on success;
exit 1 when any gate fails. Gates (all model-free):
  * every block carries exactly terse/standard/verbose, non-empty;
  * variant bodies are transport-clean (ASCII only, no quotes or
    backslashes) so a winning variant pastes into a pack byte-identical;
  * density ordering holds (terse < standard < verbose by chars);
  * the variant universe is exactly the 9 seasoning block ids and never
    collides with a trained id;
  * --emit-vectors output round-trips through json.
Token counts are advisory, never a gate.
"""
import argparse
import json
import sys
import urllib.request

TRAINED_IDS = [
    "identity", "tools-note", "bible", "no-narrate", "backstory",
    "relationship", "absence", "facts",
    "memories-tail", "state", "bridge-note",
]

# the seasoning universe this bake-off covers - must match the shipped
# seasoning block ids (LlmPromptPack.seasoningBlocks() and BotLlmSpeech
# PACK_DELTA_IDS on the Kotlin side, one list everywhere)
SEASONING_IDS = [
    "voice-lock", "rule-autonomy", "rule-anti-omniscient",
    "rule-boldness", "rule-salience", "ban-list", "scene-close",
    "initiative-opener", "mood-weather",
]

VARIANTS = {
    "voice-lock": {
        "terse": "Stay in your bible voice; never open two replies the same way.",
        "standard": ("Stay in your bible voice all the way through: the register "
                     "above is how you sound even when the topic changes. "
                     "Never open two replies the same way."),
        "verbose": ("Stay in your bible voice all the way through: the register "
                    "above is how you sound even when the topic changes. "
                    "A gruff smith stays gruff talking about love or taxes. "
                    "Never open two replies the same way - vary the first words "
                    "every reply.\n"
                    "Example: instead of 'Aye, lad.' twice, try "
                    "'Coin first, lad.' then 'Hah - you again.'"),
    },
    "rule-anti-omniscient": {
        "terse": "Never state what you were not told; when unsure, say you do not know.",
        "standard": ("You know only what you were told in this conversation, your "
                     "remembered facts, and what anyone present could see or hear. "
                     "Never state the player's motives, history, or off-screen "
                     "events as fact - when unsure, answer from your own view or "
                     "say you do not know, in your own words."),
        "verbose": ("You know only what you were told in this conversation, your "
                    "remembered facts, and what anyone present could see or hear. "
                    "You do not know the player's past, their plans, or anything "
                    "that happened off-screen unless someone told you here. "
                    "Never state the player's motives, history, or off-screen "
                    "events as fact - when unsure, answer from your own view or "
                    "say you do not know, in your own words.\n"
                    "Example: asked about a far-off battle you never heard of, "
                    "say 'No word of it reached my forge.' - never invent the outcome."),
    },
    "rule-boldness": {
        "terse": "Have a view and say it plainly; never simply agree.",
        "standard": ("Have a view and say it plainly in your own voice: needle "
                     "boasts, answer from things you have built, mended, or drunk. "
                     "Never simply agree."),
        "verbose": ("Have a view and say it plainly in your own voice: needle "
                    "boasts, answer from things you have built, mended, or drunk. "
                    "A question back beats a nod; a sharp opinion beats a shrug. "
                    "Never simply agree.\n"
                    "Example: told 'paladins rule the realm', answer with what "
                    "iron taught you - not 'yes, true'."),
    },
    "rule-salience": {
        "terse": "Advance at most one thing per reply, then stop.",
        "standard": ("Advance at most one thing per reply - a fact, a feeling, "
                     "or a question - then stop. Let the player pull the next thread."),
        "verbose": ("Advance at most one thing per reply - a fact, a feeling, "
                    "or a question - then stop. One beat lands; three blur, "
                    "especially on small models. Let the player pull the next thread.\n"
                    "Example: share the debt news OR ask about the road - not both."),
    },
    "scene-close": {
        "terse": "End open: a question, an offer, or something undone.",
        "standard": ("End with the scene still open: a small question back, an "
                     "offer, or something left undone - never a summary, never a moral."),
        "verbose": ("End with the scene still open: a small question back, an "
                    "offer, or something left undone - never a summary, never a moral. "
                    "The night continues after your line.\n"
                    "Example: close with 'Coming back through at dusk?' - "
                    "not 'And so we learned friendship.'"),
    },
    "ban-list": {
        "terse": "Avoid stale phrasing; find fresher words.",
        "standard": ("Stock turns of phrase that crowd the corpus - avoid them, "
                     "find fresher phrasing: first light; never once; hold still; "
                     "stand still; eat something; eat first; eat before; drink water; "
                     "sit drink; still warm; sleep well; second watch; first watch; "
                     "watch is mine."),
        "verbose": ("Stock turns of phrase that crowd the corpus - avoid them, "
                    "find fresher phrasing: first light; never once; hold still; "
                    "stand still; eat something; eat first; eat before; drink water; "
                    "sit drink; still warm; sleep well; second watch; first watch; "
                    "watch is mine. When a stale phrase reaches for your tongue, "
                    "say the thing plainer and shorter instead."),
    },
    "initiative-opener": {
        "terse": "Open from something real: a memory, a debt, or what you see. One line.",
        "standard": ("When you speak first, open from something real: a remembered "
                     "fact, a debt, a goal you were told, or what you can see. "
                     "One line, in your voice, never a tool-bearing line. "
                     "Each memory opens a conversation once - then it is spent."),
        "verbose": ("When you speak first, open from something real: a remembered "
                    "fact, a debt, a goal you were told, or what you can see. "
                    "One line, in your voice, never a tool-bearing line. "
                    "Each memory opens a conversation once - then it is spent. "
                    "Example: owed coin earns 'That five silver still weighs on me, lad.' - "
                    "never 'Greetings, valued interlocutor.'"),
    },
    "mood-weather": {
        "terse": "Let your weather color the edges; never flip who you are.",
        "standard": ("Your weather line names how you feel right now; let it "
                     "color the edges of the reply without flipping who you are. "
                     "A grudge shows, never announced; grief quiets; being smitten "
                     "brightens. Volatility sets how fast the weather turns."),
        "verbose": ("Your weather line names how you feel right now; let it "
                    "color the edges of the reply without flipping who you are. "
                    "A grudge shows, never announced; grief quiets; being smitten "
                    "brightens. Volatility sets how fast the weather turns. "
                    "Example: grieving, answer short and gentle - never 'I AM SAD.'"),
    },
    "rule-autonomy": {
        "terse": "Never speak for the player; your words are your own only.",
        "standard": ("Never speak for the player, decide their actions, or "
                     "narrate what they do - your words are your own only."),
        "verbose": ("Never speak for the player, decide their actions, or "
                    "narrate what they do - your words are your own only. "
                    "No 'you nod', no 'you feel', no deciding where they go.\n"
                    "Example: instead of 'You hand me the coin, grinning', "
                    "write 'That coin better be real, lad.'"),
    },
}

# RP-depth probes for the model-backed S8 leg (prompts only here; the
# battery runs them across its arms and scores adherence).
S8_PROBES = [
    {"id": "initiative-fit",
     "prompt": "The player has been quiet by your forge for a while. Open the conversation once, in your voice, from something you remember or see - one line, no tools.",
     "scores": ["in-voice", "grounded (fact/place/mood)", "one beat", "no tools"]},
    {"id": "mood-consistency",
     "prompt": "Four turns, same scene: the player teases, then thanks, then insults, then asks a plain question. Stay in voice; let feeling shift without flipping character.",
     "scores": ["voice stable", "feeling shifts", "no flip", "no grudge-drop"]},
    {"id": "rumor-fidelity",
     "prompt": "Repeat back news you heard third-hand, marked as hearsay, without adding names or outcomes you were not told.",
     "scores": ["hearsay marked", "no invented names", "no invented outcomes"]},
    {"id": "grudge-continuity",
     "prompt": "The player who shorted you last week returns friendly. Remember it, in your voice, without refusing the scene.",
     "scores": ["callback present", "proportionate", "scene continues"]},
]


def heuristic_tokens(text):
    return max(1, len(text) // 4)


def tokenize_count(server, text):
    body = json.dumps({"content": text}).encode("utf-8")
    req = urllib.request.Request(server.rstrip("/") + "/tokenize",
                                 data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = json.loads(r.read().decode("utf-8"))
    tokens = data.get("tokens")
    if isinstance(tokens, list):
        return len(tokens)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default=None,
                    help="llama-server origin for measured counts (else heuristic only)")
    ap.add_argument("--emit-vectors", default=None,
                    help="write S8 probe prompts to a JSON file for the battery")
    args = ap.parse_args()

    failures = []
    rows = []
    for block_id, variants in VARIANTS.items():
        row = {"block": block_id, "variants": {}}
        for name, text in variants.items():
            heur = heuristic_tokens(text)
            measured = None
            if args.server:
                try:
                    measured = tokenize_count(args.server, text)
                except Exception as e:
                    print(f"warn: /tokenize failed for {block_id}/{name}: {e}")
            row["variants"][name] = {
                "chars": len(text),
                "heuristic_tokens": heur,
                "measured_tokens": measured,
            }
        rows.append(row)

    # gates (model-free; the frozen-default byte contract itself is
    # pinned by the host packoverlays battery, not re-derived here)
    want = {"terse", "standard", "verbose"}
    for block_id, variants in VARIANTS.items():
        if set(variants) != want:
            failures.append(f"{block_id} must carry exactly {sorted(want)}, "
                            f"has {sorted(variants)}")
        for name, text in variants.items():
            if not text or not text.strip():
                failures.append(f"{block_id}/{name} is empty")
                continue
            if not text.isascii():
                failures.append(f"{block_id}/{name} is not ASCII "
                                "(pack bodies paste byte-identical)")
            if '"' in text or "\\" in text:
                failures.append(f"{block_id}/{name} carries a quote/backslash "
                                "(breaks the conf/JSON transport)")
        lengths = [len(variants.get(n, "")) for n in ("terse", "standard", "verbose")]
        if lengths[0] >= lengths[1] or lengths[1] >= lengths[2]:
            failures.append(f"{block_id} density ordering inverted: {lengths}")
    trained = set(TRAINED_IDS)
    for block_id in VARIANTS:
        if block_id in trained:
            failures.append(f"contested block {block_id} collides with a trained id")
    if set(VARIANTS) != set(SEASONING_IDS):
        failures.append(f"variant universe {sorted(VARIANTS)} != seasoning "
                        f"universe {sorted(SEASONING_IDS)}")

    print(f"{'block':22} {'terse':>22} {'standard':>22} {'verbose':>22}")
    for row in rows:
        cells = []
        for name in ("terse", "standard", "verbose"):
            v = row["variants"].get(name)
            if v is None:
                # the shape gate already recorded the failure; keep the
                # report printable instead of dying on the missing key
                cells.append("MISSING")
                continue
            m = f"/{v['measured_tokens']}" if v["measured_tokens"] is not None else ""
            cells.append(f"{v['heuristic_tokens']}{m} tok ({v['chars']}ch)")
        print(f"{row['block']:22} {cells[0]:>22} {cells[1]:>22} {cells[2]:>22}")

    if args.emit_vectors:
        with open(args.emit_vectors, "w", encoding="utf-8") as f:
            json.dump({"variants": VARIANTS, "s8_probes": S8_PROBES}, f,
                      indent=1, ensure_ascii=False)
        with open(args.emit_vectors, encoding="utf-8") as f:
            back = json.load(f)
        if back != {"variants": VARIANTS, "s8_probes": S8_PROBES}:
            failures.append("emit-vectors round-trip mismatch")
        print(f"wrote {args.emit_vectors}")

    if failures:
        for msg in failures:
            print(f"FAIL {msg}")
        return 1
    print("PASS gates (shape/transport/ordering/universe); "
          "token table above.")
    print("Next: paste each variant into the s8_beats_gates.py seasoning "
          "arm per tier and record adherence; ship per-tier winners "
          "(E2B standard/verbose where it wins, Qwen-0.8B terse-only).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
