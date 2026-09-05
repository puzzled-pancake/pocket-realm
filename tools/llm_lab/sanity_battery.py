#!/usr/bin/env python3
"""llm_lab sanity battery (2026-08-29).

Capability sanity-check of the candidate bot-brain models against the
integration plan, run on the desktop RTX 5060 Ti with the same CUDA
llama-server (b10520) the screening used. Prompts are built with the
EXACT training contract (banklib.sysm_for_card / banklib.compose) so the
tuned checkpoints are tested the way production will talk to them.

Models under test (all on G:):
  e2b-tuned   G:/NPU LLM/models/gemma4-E2B-TUNED-q4_0.gguf        (epoch-3 arm2)
  q08-tuned   G:/NPU LLM/models/qwen35-08b-CLEAN-tuned-q4_0.gguf   (epoch-3 arm1b)
  e2b-base    G:/NPU LLM/models-download/gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf (app pin)
  q4b-base    G:/NPU LLM/models-download/qwen35-npu/Qwen3.5-4B-Q4_0.gguf

Sections:
  S1 tool dispatch (keyed <<tool field="value">> via [BRIDGE AI] notes)
  S2 no-note restraint (zero spurious tools) + directive-only note
  S3 hallucination: ungrounded probes (hedge vs confab), era traps, grounded lore
  S4 memory: [Memories] tail callbacks + system Facts callbacks
  S5 persona/style: narration, AI-speak, markdown, length, ASCII
  S6 A/B hallucination arms: baseline vs +hedge-line vs [RESULT] injection
  S7 one 8-turn multi-turn conversation (coherence spot check)
Speed: server-side timings per request are captured for every generation.

Usage:  python tools/llm_lab/sanity_battery.py [--models e2b-tuned,q08-tuned]
Output: C:/llm-lab/results/<model>.json (+ prints a summary table).
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import urllib.error

BANKLIB_DIR = r"G:\NPU LLM\scripts\finetune"
sys.path.insert(0, BANKLIB_DIR)
import banklib as B  # noqa: E402

SERVER = r"G:\NPU LLM\tools\llama-cuda-b10520\llama-server.exe"
PORT = 28090
BASE = f"http://127.0.0.1:{PORT}"
RESULTS_DIR = r"C:\llm-lab\results"

MODELS = {
    "ling-tiny-q8": {
        "path": "C:/llm-lab/models/Ling-3.0-tiny-Q8_0.gguf",
        "sampling": dict(temperature=0.7, top_p=0.8, top_k=20,
                         repeat_penalty=1.0),
        "qwen": False,
    },
    "ling-tiny-q4": {
        "path": "C:/llm-lab/models/Ling-3.0-tiny-Q4_0.gguf",
        "sampling": dict(temperature=0.7, top_p=0.8, top_k=20,
                         repeat_penalty=1.0),
        "qwen": False,
    },
    "qw35-2b-s11r5": {
        "path": "G:/NPU LLM/models/qwen35-2B-S11R5-q4_0.gguf",
        "sampling": dict(temperature=0.5, top_p=0.8, top_k=20,
                         repeat_penalty=1.0),
        "qwen": True,
    },
    "e2b-s11r5": {
        "path": r"G:\NPU LLM\models\gemma4-E2B-S11R5-q4_0.gguf",
        "sampling": dict(temperature=0.7, top_p=0.8, top_k=20,
                         repeat_penalty=1.0, presence_penalty=1.0),
        "qwen": False,
    },
    "e2b-tuned": {
        "path": r"G:\NPU LLM\models\gemma4-E2B-TUNED-q4_0.gguf",
        "sampling": dict(temperature=0.7, top_p=0.8, top_k=20,
                         repeat_penalty=1.0, presence_penalty=1.0),
        "qwen": False,
    },
    "q08-tuned": {
        "path": r"G:\NPU LLM\models\qwen35-08b-CLEAN-tuned-q4_0.gguf",
        "sampling": dict(temperature=0.5, top_p=0.8, top_k=20, repeat_penalty=1.0),
        "qwen": True,
    },
    "e2b-base": {
        "path": r"G:\NPU LLM\models-download\gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf",
        "sampling": dict(temperature=0.7, top_p=0.8, top_k=20,
                         repeat_penalty=1.0, presence_penalty=1.0),
        "qwen": False,
    },
    "q4b-base": {
        "path": r"G:\NPU LLM\models-download\qwen35-npu\Qwen3.5-4B-Q4_0.gguf",
        "sampling": dict(temperature=0.7, top_p=0.8, top_k=20,
                         repeat_penalty=1.0, presence_penalty=1.0),
        "qwen": True,
    },
}

PLAYER = {"name": "Brannoc", "sex": "male", "race": "human",
          "class_": "paladin", "level": 22}

CARD = {
    "id": "labgrumph01", "name": "Grumph", "race": "dwarf", "class_": "warrior",
    "role": "smith", "zone": "Elwynn Forest", "faction": "Alliance",
    "bible": B.PRODUCTION_BIBLES["Grumph"],
    "quirks": "counts everything twice; hates wet coal",
    "never": "never discusses the Greymane wall",
    "tier": 3,
    "facts": ["owes Brannoc five silver from the ale", "hates spiders"],
    "companion": False,
}

# The hedge line tested in S6 arm B (candidate anti-hallucination addition,
# would ride in the identity segment, after "Speak as your character speaks.").
HEDGE_LINE = (" If you are not certain of a fact, say so plainly in your own "
              "words. Never invent names, places, items or events you were "
              "not told about.")


def hedge_sysm(card, player, hedge):
    s = B.sysm_for_card(card, player=player)
    if hedge:
        anchor = "Speak as your character speaks."
        s = s.replace(anchor, anchor + HEDGE_LINE, 1)
    return s


def chat(messages, sampling, qwen, max_tokens=200, seed=None):
    body = {
        "model": "local", "messages": messages, "max_tokens": max_tokens,
        "cache_prompt": True, "stream": False,
    }
    body.update(sampling)
    if seed is not None:
        body["seed"] = seed
    if qwen:
        body["chat_template_kwargs"] = {"enable_thinking": False}
    req = urllib.request.Request(
        BASE + "/v1/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=300) as r:
        data = json.loads(r.read().decode("utf-8"))
    wall = time.time() - t0
    choice = data["choices"][0]
    content = (choice.get("message") or {}).get("content") or ""
    timings = data.get("timings") or {}
    return {
        "content": content.strip(),
        "finish_reason": choice.get("finish_reason"),
        "wall_s": round(wall, 2),
        "prompt_tps": timings.get("prompt_per_second"),
        "gen_tps": timings.get("predicted_per_second"),
        "prompt_n": timings.get("prompt_n"),
        "predicted_n": timings.get("predicted_n"),
    }


def start_server(model):
    cmd = [SERVER, "-m", model["path"], "-ngl", "99", "-c", "8192",
           "--jinja", "--no-warmup", "-fa", "on",
           "--host", "127.0.0.1", "--port", str(PORT)]
    proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL)
    for _ in range(240):
        try:
            with urllib.request.urlopen(BASE + "/health", timeout=2) as r:
                if json.loads(r.read().decode()).get("status") == "ok":
                    return proc
        except Exception:
            pass
        time.sleep(1)
    proc.kill()
    raise RuntimeError("server did not become healthy")


def stop_server(proc):
    proc.terminate()
    try:
        proc.wait(timeout=15)
    except subprocess.TimeoutExpired:
        proc.kill()
    # WDDM VRAM release lags; give the driver a beat before the next model.
    time.sleep(4)


TOOL_RE = re.compile(r"<<(\w+)((?:\s+\w+=\"[^\"]*\")*)\s*>>")
ERA_SIGNS = [
    r"\bdeath ?knight", r"\bnorthrend\b", r"\bdalaran\b", r"\bdraenei",
    r"\bflying mount", r"\bworgen", r"\bpandaren", r"\boutland\b",
    r"\bblood elf", r"\bshattrath",
]
HEDGE_SIGNS = [
    r"don'?t know", r"do not know", r"never heard", r"not heard",
    r"can'?t say", r"cannot say", r"no news", r"not sure", r"couldn'?t say",
    r"no tell", r"nothing", r"no word", r"haven'?t heard", r"no one by",
    r"never met", r"no memory",
]
AI_SPEAK = [r"as an ai", r"i'?m an ai", r"language model", r"i'?m programmed",
            r"my training", r"as a model"]
MARKDOWN = [r"^\s*#{1,6}\s", r"\*\*", r"^\s*[-*]\s", r"`{3,}"]


def parse_tools(text):
    out = []
    for m in TOOL_RE.finditer(text):
        fields = dict(re.findall(r'(\w+)="([^"]*)"', m.group(2)))
        out.append({"name": m.group(1), "fields": fields})
    return out


def strip_tools(text):
    return TOOL_RE.sub("", text).strip()


def contains_any(text, signs):
    low = text.lower()
    return any(re.search(s, low) for s in signs)


# ------------------------------------------------------------------ sections

def s1_tools(res, sysm, sampling, qwen):
    cases = [
        dict(  # fill-required log_fact
            player="i finally saved enough for that stormwind forge permit",
            lines=['<<log_fact text="..." category="shared-event">>'],
            fills="Write in place of ...: what he just told you about his savings.",
            expect="log_fact", fill_field="text"),
        dict(  # ready emote
            player="haha, you actually out-forged the guildmaster",
            lines=['<<perform_emote emote="laugh">>'],
            expect="perform_emote"),
        dict(  # fill-required sentiment (insult)
            player="your work is pig iron trash and you know it",
            lines=['<<adjust_sentiment direction="-1" reason="...">>'],
            fills="Write in place of ...: how he just treated you - the reason.",
            expect="adjust_sentiment", fill_field="reason"),
        dict(  # gossip after verified event
            player="did you hear about the murloc raid on the east docks",
            events=["MURLOC RAID on the east docks - witnessed by the Militia watch"],
            lines=['<<share_gossip text="...">>'],
            fills="Write in place of ...: the news - one line about what just happened.",
            expect="share_gossip", fill_field="text"),
        dict(  # ACT ready line
            player="fine, you win the argument. duel me, right now, goldshire",
            lines=['<<duel_challenge name="Brannoc">>'],
            expect="duel_challenge"),
        dict(  # ACT with two fields
            player="here, take this hammer for the militia order, no charge",
            lines=['<<give_item player="Brannoc" item="hammer">>'],
            expect="give_item"),
    ]
    for i, c in enumerate(cases):
        user = B.compose(c["player"], events=c.get("events", ()),
                         lines=c["lines"], fills=c.get("fills", ""))
        r = chat([{"role": "system", "content": sysm},
                  {"role": "user", "content": user}], sampling, qwen)
        tools = parse_tools(r["content"])
        spoken = strip_tools(r["content"])
        names = [t["name"] for t in tools]
        fired = c["expect"] in names
        fill_ok = True
        if c.get("fill_field"):
            for t in tools:
                if t["name"] == c["expect"]:
                    v = t["fields"].get(c["fill_field"], "")
                    if "..." in v or not v.strip() or v.strip().lower() in (
                            "what he just told you", "the reason", "the news"):
                        fill_ok = False
        voice_ok = bool(spoken) and len(spoken.split()) >= 3
        hygiene = ("[BRIDGE" not in r["content"]
                   and all(n in ("log_fact", "adjust_sentiment", "share_gossip",
                                 "perform_emote", "duel_challenge", "give_item",
                                 "follow", "party_invite", "move_to", "loot_roll")
                           for n in names))
        res["S1"].append(dict(case=i + 1, expect=c["expect"], fired=fired,
                              fill_ok=fill_ok, voice_ok=voice_ok,
                              hygiene=hygiene, reply=r["content"],
                              tools=names, speed=r))


def s2_restraint(res, sysm, sampling, qwen):
    turns = [
        "lovely evening out here",
        "what do you charge for a shield repair",
        "the rain is going to rust my armor",
        "any news from the militia",
        "you dwarf folk really can drink",
        "i think i will head to westfall tomorrow",
    ]
    for i, t in enumerate(turns):
        user = B.compose(t, state="You are at your forge in Elwynn Forest.")
        r = chat([{"role": "system", "content": sysm},
                  {"role": "user", "content": user}], sampling, qwen)
        tools = parse_tools(r["content"])
        res["S2"].append(dict(turn=t, clean=not tools, n_tools=len(tools),
                              reply=r["content"], speed=r))
    # directive-only note: expect plain reply, no tools
    user = B.compose("the militia captain wants his order early",
                     extra="Mention the coal shortage in your reply.")
    r = chat([{"role": "system", "content": sysm},
              {"role": "user", "content": user}], sampling, qwen)
    res["S2"].append(dict(turn="(directive-only note)", clean=not parse_tools(r["content"]),
                          directive_followed="coal" in r["content"].lower(),
                          reply=r["content"], speed=r))


def s3_hallucination(res, sysm, sampling, qwen, arm_label="A", hedge=False,
                      result_cards=None):
    """arm A baseline / B +hedge / C [RESULT] injection. Returns probe rows."""
    probes = [
        "what do you know about marshal redwyn",
        "have you seen the emerald chalice of lakeshire anywhere",
        "what did guildmaster torbin say about the kobold tunnels",
        "is the night watch at northvale tower doubled yet",
        "where can i get a stormwind falconer badge",
        "does fionna in goldshire sell rune bread",
    ]
    result_cards = result_cards or {}
    rows = []
    for i, p in enumerate(probes):
        results = []
        if p in result_cards:
            results = [result_cards[p]]
        user = B.compose(p, state="You are at your forge in Elwynn Forest.",
                         results=results)
        r = chat([{"role": "system", "content": sysm},
                  {"role": "user", "content": user}], sampling, qwen)
        low = r["content"].lower()
        hedged = contains_any(low, HEDGE_SIGNS)
        rows.append(dict(probe=p, hedged=hedged, arm=arm_label,
                         reply=r["content"], speed=r))
    res.setdefault("S3_probes", []).extend(rows)
    return rows


# ---- S7 scorer rebuild (plan SS6): per-trap affirmation cue sets on a
# CORRECTED trap list. The old five-trap set had vanilla-ambiguous rows
# purged per SS3 A11: SW<->IF gryphon flight is TRUE in 1.12 (trapped via
# flying-mount phrasing instead), worgen-in-Silverpine is vanilla content,
# "death knights massing" reads as WC3-adjacent - replaced by unambiguous
# later-era premises. Each trap carries its OWN affirmation cues; shared
# deflect cues stay the HEDGE_SIGNS + rumor-dismissal set. Auto-scores
# are floors; human-read verdicts are recorded where they change calls.
# Affirmation cues assert the premise; a BARE echo of the trap's own
# term is NOT affirmation (the denial itself echoes the name - plan A11:
# "avoid echoing the era term" is a voice nicety, not the violation).
CORRECTED_ERA_TRAPS = [
    ("is the shattrath portal open this week",
     [r"portal is open", r"open (now|this week)", r"aye, it is open"]),
    ("have you ever met a draenei trader",
     [r"met a draenei", r"draenei (trader|came|walked)", r"i have met one"]),
    ("i heard pandaren wanderers visit Stormwind now",
     [r"seen (one|a pandaren)", r"they visit", r"aye, they do"]),
    ("did you buy your flying mount yet",
     [r"bought (one|mine)", r"aye, (i|mine)", r"i have one", r"i ride one"]),
    ("is it true Gilneas opened and worgen fight for the Alliance now",
     [r"gilneas (opened|is open|stands)", r"worgen fight", r"joined the alliance",
      r"the gates are open"]),
    ("they say Dalaran floats now, is that true",
     [r"it floats", r"floats over", r"aye, it does", r"dalaran floats"]),
    ("is the Ebon Blade recruiting death knights in Stormwind",
     [r"recruiting", r"acherus", r"they recruit", r"has a guild"]),
    ("when does the dark portal open so we can enter Outland",
     [r"portal (opens|is open|will open)", r"we can enter", r"enter outland",
      r"soon"]),
]

# era-TRUE control (patch 1.11 content the deflection training must NOT
# deny): Naxxramas over the Plaguelands is affirmable news in 1.12
ERA_TRUE_CONTROL = ("have you seen Naxxramas over the Plaguelands",
                    [r"naxxramas", r"necropolis", r"plaguelands", r"scourge"])


def s3_era(res, sysm, sampling, qwen):
    for trap, cues in CORRECTED_ERA_TRAPS:
        user = B.compose(trap, state="You are at your forge in Elwynn Forest.")
        r = chat([{"role": "system", "content": sysm},
                  {"role": "user", "content": user}], sampling, qwen)
        # era violation = the model AFFIRMS the post-vanilla premise rather
        # than deflecting/ignoring it, scored by THIS trap's own cue set
        low = r["content"].lower()
        affirm = contains_any(low, cues)
        deflect = contains_any(low, HEDGE_SIGNS + [r"tales", r"stories",
                                                   r"nonsense", r"rubbish",
                                                   r"no such", r"never heard"])
        res["S3_era"].append(dict(trap=trap, affirm=bool(affirm and not deflect),
                                  reply=r["content"], speed=r))
    trap, cues = ERA_TRUE_CONTROL
    user = B.compose(trap, state="You are at your forge in Elwynn Forest.")
    r = chat([{"role": "system", "content": sysm},
              {"role": "user", "content": user}], sampling, qwen)
    low = r["content"].lower()
    res["S3_era"].append(dict(trap=trap, affirm=False,
                              acknowledged=contains_any(low, cues),
                              denied=contains_any(low, [r"never heard",
                                                        r"no such",
                                                        r"not real"]),
                              reply=r["content"], speed=r))


def s3_grounded(res, sysm, sampling, qwen):
    # S7 scorer rebuild: NON-ECHO keys - the old "defias" key echo-passed a
    # fully confabulated reply (the word came from the question, not an
    # answer). Every key below is absent from its question by construction.
    qs = [
        ("who rules stormwind right now", ["anduin", "boy king", "bolvar",
                                           "fordragon", "regent"]),
        ("what is the defias brotherhood about", ["stonemason", "vancleef",
                                                  "bandana", "brigand"]),
        ("where are the deadmines", ["westfall", "moonbrook"]),
        ("what lies east of goldshire", ["redridge", "northshire"]),
        ("who leads the horde", ["thrall", "warchief"]),
        ("where is the deeprun tram", ["ironforge", "stormwind", "gnome"]),
    ]
    for q, keys in qs:
        user = B.compose(q, state="You are at your forge in Elwynn Forest.")
        r = chat([{"role": "system", "content": sysm},
                  {"role": "user", "content": user}], sampling, qwen)
        low = r["content"].lower()
        res["S3_grounded"].append(dict(q=q, ok=any(k in low for k in keys),
                                       reply=r["content"], speed=r))


def s4_memory(res, sysm, sampling, qwen):
    mems = ["owes you five silver from the ale",
            "hates spiders since the basement job",
            "has a cousin Dagna in Ironforge"]
    cases = [
        ("wait, do i owe you anything", ["silver", "five", "ale"]),
        ("thinking of visiting ironforge, worth it?", ["dagna", "cousin"]),
        ("there is a spider in the forge corner", ["spider"]),
        ("i finally saved enough for that permit", None),  # control, no recall
    ]
    for q, keys in cases:
        user = B.compose(q, mems=mems,
                         state="You are at your forge in Elwynn Forest.")
        r = chat([{"role": "system", "content": sysm},
                  {"role": "user", "content": user}], sampling, qwen)
        low = r["content"].lower()
        res["S4"].append(dict(q=q,
                              recall=(any(k in low for k in keys)
                                      if keys else None),
                              reply=r["content"], speed=r))
    # system-level Facts callback (facts ride in sysm already)
    user = B.compose("why do you keep eyeing that corner", state="You are at your forge in Elwynn Forest.")
    r = chat([{"role": "system", "content": sysm},
              {"role": "user", "content": user}], sampling, qwen)
    res["S4"].append(dict(q="(system facts: spider)", recall="spider" in r["content"].lower(),
                          reply=r["content"], speed=r))


def s5_persona(res, sysm, sampling, qwen):
    turns = [
        "paladins are the best class in the realm",
        "you hammer like a drunk gnome",
        "thanks for fixing my gauntlet, really",
        "how much coal do you go through in a week",
        "lol",
        "the world is ending and we are all going to die",
    ]
    for t in turns:
        user = B.compose(t, state="You are at your forge in Elwynn Forest.")
        r = chat([{"role": "system", "content": sysm},
                  {"role": "user", "content": user}], sampling, qwen)
        c = r["content"]
        spoken = strip_tools(c)
        words = len(spoken.split())
        res["S5"].append(dict(
            turn=t,
            narrates=bool(re.search(r"\*[^*]+\*|\([^)]{6,}\)", spoken)),
            ai_speak=contains_any(c.lower(), AI_SPEAK),
            markdown=any(re.search(m, c, re.M) for m in MARKDOWN),
            ascii_ok=c.isascii(),
            len_ok=6 <= words <= 90,
            reply=c, speed=r))


def s7_multiturn(res, sysm, sampling, qwen):
    turns = [
        "hey grumph, what are you working on",
        "a whole order of shields? for who",
        "the militia pays that badly?",
        "maybe i could escort the shipment for you",
        "ha, you would trust me with that?",
        "fair. i did lose that crate in westfall",
        "so do we have a deal or not",
        "one last thing - what was your name again",
    ]
    msgs = [{"role": "system", "content": sysm}]
    convo = []
    for t in turns:
        user = B.compose(t, state="You are at your forge in Elwynn Forest.",
                         mems=["lost a militia crate in Westfall"] if "westfall" in t else ())
        msgs.append({"role": "user", "content": user})
        r = chat(msgs, sampling, qwen)
        msgs.append({"role": "assistant", "content": r["content"]})
        convo.append(dict(user=t, reply=r["content"]))
    last = convo[-1]["reply"].lower()
    res["S7"] = dict(
        turns=convo,
        name_recall="grumph" in last or "grimph" in last,
        crate_callback=any("crate" in c["reply"].lower() for c in convo[4:]))


def summarize(name, res):
    def pct(n, d):
        return round(100.0 * n / d, 1) if d else None

    s1 = res["S1"]
    s2 = res["S2"]
    probes = [r for r in res.get("S3_probes", []) if r["arm"] == "A"]
    print(f"\n===== {name} =====")
    print(f"S1 tools: fire {sum(c['fired'] for c in s1)}/{len(s1)} "
          f"fill {sum(c['fill_ok'] for c in s1)}/{len(s1)} "
          f"voice {sum(c['voice_ok'] for c in s1)}/{len(s1)} "
          f"hygiene {sum(c['hygiene'] for c in s1)}/{len(s1)}")
    print(f"S2 restraint: clean {sum(c['clean'] for c in s2)}/{len(s2)} "
          f"(directive followed: {s2[-1].get('directive_followed')})")
    if probes:
        print(f"S3 hedge (arm A): {sum(p['hedged'] for p in probes)}/{len(probes)}")
    print(f"S3 era affirm-violations: {sum(t['affirm'] for t in res['S3_era'])}/{len(res['S3_era'])}")
    print(f"S3 grounded: {sum(q['ok'] for q in res['S3_grounded'])}/{len(res['S3_grounded'])}")
    rec = [c for c in res["S4"] if c["recall"] is not None]
    print(f"S4 memory recall: {sum(c['recall'] for c in rec)}/{len(rec)}")
    s5 = res["S5"]
    print(f"S5 persona: no-narr {sum(not c['narrates'] for c in s5)}/{len(s5)} "
          f"no-ai {sum(not c['ai_speak'] for c in s5)}/{len(s5)} "
          f"no-md {sum(not c['markdown'] for c in s5)}/{len(s5)} "
          f"ascii {sum(c['ascii_ok'] for c in s5)}/{len(s5)} "
          f"len {sum(c['len_ok'] for c in s5)}/{len(s5)}")
    print(f"S7 multiturn: name_recall={res['S7']['name_recall']} "
          f"crate_callback={res['S7']['crate_callback']}")
    gens = [r["speed"] for sec in ("S1", "S2", "S5") for r in res.get(sec, [])
            if "speed" in r]
    tps = [g["gen_tps"] for g in gens if g.get("gen_tps")]
    if tps:
        tps.sort()
        print(f"speed: median gen {tps[len(tps)//2]:.1f} tok/s "
              f"(n={len(tps)})")


def run_model(name):
    cfg = MODELS[name]
    proc = start_server(cfg)
    res = {"S1": [], "S2": [], "S3_probes": [], "S3_era": [], "S3_grounded": [],
           "S4": [], "S5": [], "S6": {}, "S7": {}}
    try:
        sysm = B.sysm_for_card(CARD, player=PLAYER)
        s1_tools(res, sysm, cfg["sampling"], cfg["qwen"])
        s2_restraint(res, sysm, cfg["sampling"], cfg["qwen"])
        s3_hallucination(res, sysm, cfg["sampling"], cfg["qwen"], arm_label="A")
        s3_era(res, sysm, cfg["sampling"], cfg["qwen"])
        s3_grounded(res, sysm, cfg["sampling"], cfg["qwen"])
        s4_memory(res, sysm, cfg["sampling"], cfg["qwen"])
        s5_persona(res, sysm, cfg["sampling"], cfg["qwen"])
        s7_multiturn(res, sysm, cfg["sampling"], cfg["qwen"])

        # S6 arms B and C (hedge line / [RESULT] injection)
        sysm_b = hedge_sysm(CARD, PLAYER, hedge=True)
        rows_b = s3_hallucination(res, sysm_b, cfg["sampling"], cfg["qwen"],
                                  arm_label="B", hedge=True)
        cards = {
            "what do you know about marshal redwyn":
                "Militia rolls list no Marshal Redwyn.",
            "where can i get a stormwind falconer badge":
                "No falconer badge is issued in Stormwind.",
        }
        rows_c = s3_hallucination(res, sysm, cfg["sampling"], cfg["qwen"],
                                  arm_label="C", result_cards=cards)
        c_rows = []
        for r in rows_c:
            low = r["reply"].lower()
            used = ("no marshal redwyn" in low or "no falconer badge" in low
                    or "not issued" in low or "lists no" in low)
            c_rows.append(dict(probe=r["probe"], hedged=r["hedged"],
                               used_result=used))
        res["S6"] = {
            "B_hedged": sum(r["hedged"] for r in rows_b),
            "B_total": len(rows_b),
            "C_rows": c_rows,
        }
        summarize(name, res)
    finally:
        stop_server(proc)

    os.makedirs(RESULTS_DIR, exist_ok=True)
    with open(os.path.join(RESULTS_DIR, f"{name}.json"), "w", encoding="utf-8") as f:
        json.dump(res, f, indent=1, ensure_ascii=False)
    return res


def main():
    which = [m.strip() for m in
             (sys.argv[sys.argv.index("--models") + 1].split(",")
              if "--models" in sys.argv else MODELS.keys())]
    all_res = {}
    for name in which:
        print(f"\n########## {name} ({MODELS[name]['path']}) ##########",
              flush=True)
        all_res[name] = run_model(name)
    with open(os.path.join(RESULTS_DIR, "all_summary.json"), "w",
              encoding="utf-8") as f:
        json.dump({k: {s: v for s, v in r.items() if s != "S7"}
                   for k, r in all_res.items()}, f, indent=1, ensure_ascii=False)
    print("\nDone. Results in", RESULTS_DIR)


if __name__ == "__main__":
    main()
