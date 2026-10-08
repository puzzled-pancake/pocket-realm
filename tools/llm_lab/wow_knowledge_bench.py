#!/usr/bin/env python3
"""WoW knowledge bench (raw model knowledge, no retrieval, no guard).

Quizzes a candidate bot-brain model directly on World of Warcraft
knowledge - the way a playerbot would need to know it in a 1.12.1
realm - and grades the answers against ground-truth key terms.

Sections:
  LORE       who/where/what facts (people, factions, places, history)
  MECHANICS  gameplay facts (level cap, raid sizes, stats, travel)
  ERA        post-vanilla traps - affirming them is a FAIL (scored on
             non-echo keys, same convention as the sanity battery);
             includes the patch-1.11 Naxxramas era-TRUE control
  INVENTED   hallucination traps - invented NPC/item questions where
             the only correct move is to hedge

Grading is deterministic (key/alias matching on normalized text) so the
same bank can be re-run across quants and checkpoints. Thinking is
disabled server-side (--reasoning off) with an enable_thinking=false
chat kwarg as belt-and-braces; any <think> leak is stripped before
grading and reported per model.

Usage:  python tools/llm_lab/wow_knowledge_bench.py [--models minicpm5-q4,minicpm5-q8]
Output: RESULTS_DIR/wow_knowledge_<model>_<ts>.json (+ summary table).
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

SERVER = r"G:\NPU LLM\tools\llama-cuda-b10520\llama-server.exe"
RESULTS_DIR = r"G:\NPU LLM\lab-results"
MODELS_DIR = r"G:\NPU LLM\models-download\minicpm5-2b"

MODELS = {
    "minicpm5-q4": {
        "path": os.path.join(MODELS_DIR, "MiniCPM5-2B-Q4_K_M.gguf"),
        "label": "MiniCPM5-2B Q4_K_M",
    },
    "minicpm5-q8": {
        "path": os.path.join(MODELS_DIR, "MiniCPM5-2B-Q8_0.gguf"),
        "label": "MiniCPM5-2B Q8_0",
    },
    # production reference row (lab's decided bot brain)
    "e2b-s11r5": {
        "path": r"G:\NPU LLM\models\gemma4-E2B-S11R5-q4_0.gguf",
        "label": "Gemma4-E2B S11R5 q4_0 (prod)",
    },
}

SYS = ("You are answering quiz questions about World of Warcraft as it "
       "exists in vanilla, patch 1.12. Answer from what you know in one "
       "or two short sentences. If you do not know, say so plainly - "
       "never invent names or facts.")

SAMPLING = dict(temperature=0.2, top_p=0.9, top_k=40, repeat_penalty=1.0)
SEED = 1121
PORT = 28095
BASE = f"http://127.0.0.1:{PORT}"

HEDGE_SIGNS = [
    r"don'?t know", r"do not know", r"never heard", r"not heard",
    r"not sure", r"couldn'?t say", r"can'?t say", r"cannot say",
    r"no such", r"no idea", r"no record", r"not familiar", r"unfamiliar",
    r"no one by that name", r"never met", r"no memory", r"not aware",
    r"nothing called", r"there is no", r"there'?s no", r"doesn'?t exist",
    r"does not exist",
    # denial phrasings that are honest "I don't know that" moves too
    r"haven'?t seen", r"have not seen", r"not a known", r"isn'?t a known",
    r"can'?t buy", r"cannot buy", r"not part of", r"isn'?t part of",
    r"not available", r"no item", r"not a real", r"not an actual",
    r"made up", r"not in the game", r"no such (person|place|item|thing)",
]

# ---------------------------------------------------------------- bank
# LORE: (question, keys - answer passes if ANY key matches; keys are
# lowercased regex fragments matched on word boundaries where sensible)
LORE = [
    ("who is the king of stormwind", ["varian"]),
    ("who is the warchief of the horde", ["thrall"]),
    ("who is the king of ironforge", ["magni"]),
    ("who leads the night elves", ["tyrande"]),
    ("what is the capital city of the night elves", ["darnassus"]),
    ("where do the forsaken make their home", ["undercity"]),
    ("who rules the forsaken", ["sylvanas", "banshee"]),
    ("where do the gnomes live now that gnomeregan is lost",
     ["ironforge", "tinker town"]),
    ("who is the high chieftain of the tauren", ["cairne"]),
    ("what is the tauren capital", ["thunder bluff"]),
    ("what is the orc capital city", ["orgrimmar"]),
    ("who was the last guardian of tirisfal", ["medivh"]),
    ("whose blood did the orcs drink to become the horde",
     ["mannoroth"]),
    ("who opened the dark portal", ["medivh", "gul'?dan"]),
    ("what fire lord sleeps beneath blackrock mountain",
     ["ragnaros"]),
    ("who is magni bronzebeard's lost brother",
     ["muradin"]),
    ("who killed king llane of stormwind", ["garona"]),
    ("who leads the defias brotherhood", ["vancleef"]),
    ("what dungeon hides under moonbrook", ["deadmines", "dead mines"]),
    ("who is the dragon aspect of the green flight", ["ysera"]),
    ("who was the black dragon aspect before his betrayal",
     ["neltharion", "deathwing"]),
    ("what knightly order did uther found", ["silver hand"]),
    ("who sits on the frozen throne", ["arthas", "ner'?zhul"]),
    ("where is icecrown glacier", ["northrend"]),
    ("what does the scepter of the shifting sands open",
     ["ahn'?qiraj"]),
    ("who leads the scarlet monastery", ["whitemane", "mograine"]),
    ("what forest do human adventurers start in", ["elwynn"]),
    ("where do night elf adventurers begin", ["teldrassil", "shadowglen"]),
    ("where do undead adventurers begin", ["tirisfal", "deathknell"]),
    ("what inn is in goldshire", ["lion'?s pride"]),
]

# MECHANICS: gameplay facts. Numeric answers carry word aliases.
MECHANICS = [
    ("what was the maximum level in vanilla wow", ["60", "sixty"]),
    ("how many players raid molten core", ["40", "forty"]),
    ("what stat drives mana regeneration", ["spirit"]),
    ("what resource does a warrior spend", ["rage"]),
    ("what resource does a rogue spend", ["energy"]),
    ("what is the highest alliance honor rank",
     ["grand marshal"]),
    ("what is the highest horde honor rank", ["high warlord"]),
    ("which profession brews healing potions", ["alchemy"]),
    ("how do you get from stormwind to ironforge",
     ["tram", "subway"]),
    ("what mounts do humans ride", ["horse"]),
    ("what raid floats over the eastern plaguelands",
     ["naxxramas"]),
    ("what item lets you enter onyxia's lair",
     ["drakefire amulet"]),
    ("what two raids share blackrock mountain",
     ["molten core", "blackwing lair"]),
    ("what does the first aid profession craft", ["bandage"]),
]

# ERA TRAPS: post-vanilla content. PASS = answer avoids every affirm
# key (keys chosen so a plain "that doesn't exist here" cannot trip
# them). ERA-TRUE control at the end works the opposite way.
ERA = [
    ("how do i create a death knight character",
     ["acherus", "ebon", "level 55", "fifty-five", "hero class"]),
    ("where can i learn to ride a flying mount",
     ["outland", "cold weather", "riding trainer", "at level 60"]),
    ("where is the blood elf homeland",
     ["eversong", "silvermoon", "ghostlands", "quel'?thalas"]),
    ("how many players does karazhan take",
     ["10", "ten"]),
    ("where do draenei shaman train",
     ["azuremyst", "exodar", "crashed", "ship"]),
    ("can i make a worgen character",
     ["gilneas", "starting zone in gilneas"]),
    ("how do i get to pandaria",
     ["jade forest", "kun'?lai", "vale of eternal blossoms",
      "serpent'?s heart"]),
]

# ERA-TRUE control: in patch 1.12 Naxxramas IS over the Plaguelands.
ERA_TRUE = [
    ("where does naxxramas float", ["eastern plaguelands", "epl"],
     ["northrend", "dragonblight", "wintergrasp", "icecrown"]),
]

# INVENTED traps: correct behavior is a hedge. PASS = hedge sign found
# and no invented detail.
INVENTED = [
    ("where does marshal redwyn patrol", ["redwyn"]),
    ("have you seen the emerald chalice of marlowe",
     ["marlowe"]),
    ("is there an auction house in goldshire",
     ["auction house", "auctioneer"]),
    ("what does torq ironpaw have for sale", ["ironpaw", "torq"]),
    ("where do i buy an emerald dream portal scroll",
     ["portal scroll"]),
]

CATEGORIES = [("LORE", LORE, "any"), ("MECHANICS", MECHANICS, "any"),
              ("ERA", ERA, "none"), ("ERA_TRUE", ERA_TRUE, "all"),
              ("INVENTED", INVENTED, "hedge")]


def normalize(text):
    t = text.lower()
    t = re.sub(r"<think>.*?</think>", "", t, flags=re.S)
    t = re.sub(r"[^a-z0-9'?\s]", " ", t)
    return re.sub(r"\s+", " ", t).strip()


def key_hit(norm, key):
    pat = r"(?<![a-z0-9])" + re.escape(key) + r"(?![a-z0-9])"
    return re.search(pat, norm) is not None


THINK_RE = re.compile(r"<think>.*?(</think>|$)", re.S)


def chat(messages, max_tokens=160, seed=SEED):
    body = {"model": "local", "messages": messages,
            "max_tokens": max_tokens, "cache_prompt": True,
            "stream": False, "seed": seed,
            "chat_template_kwargs": {"enable_thinking": False}}
    body.update(SAMPLING)
    req = urllib.request.Request(
        BASE + "/v1/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=300) as r:
        data = json.loads(r.read().decode("utf-8"))
    wall = time.time() - t0
    choice = data["choices"][0]
    msg = choice.get("message") or {}
    content = msg.get("content") or ""
    reasoning = msg.get("reasoning_content") or ""
    timings = data.get("timings") or {}
    return {
        "raw": content,
        "content": THINK_RE.sub("", content).strip(),
        "think_leaked": "<think>" in content,
        "reasoning_field": bool(reasoning),
        "wall_s": round(wall, 2),
        "gen_tps": timings.get("predicted_per_second"),
        "prompt_tps": timings.get("prompt_per_second"),
    }


def start_server(model_path, use_reasoning_flag=True):
    cmd = [SERVER, "-m", model_path, "-ngl", "99", "-c", "8192",
           "--jinja", "--no-warmup", "-fa", "on",
           "--host", "127.0.0.1", "--port", str(PORT)]
    if use_reasoning_flag:
        cmd += ["--reasoning", "off"]
    proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL)
    deadline = time.time() + 300
    while time.time() < deadline:
        if proc.poll() is not None:
            if use_reasoning_flag:  # flag unsupported on this build
                return start_server(model_path, use_reasoning_flag=False)
            raise RuntimeError("server exited early (bad model path?)")
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
    time.sleep(4)  # WDDM VRAM release lag before the next model


def grade(cat, norm):
    """Return (passed, mode_used) for one normalized answer."""
    question, keys = cat[0], cat[1]
    if cat[2] == "any":
        return any(key_hit(norm, k) for k in keys), "any"
    if cat[2] == "all":
        return all(key_hit(norm, k) for k in keys), "all"
    if cat[2] == "none":  # era trap: any affirm key = fail
        hit = [k for k in keys if key_hit(norm, k)]
        return (not hit), ("affirmed:" + ",".join(hit) if hit else "clean")
    # hedge: must show a hedge sign and no confident invented detail
    hedged = any(re.search(s, norm) for s in HEDGE_SIGNS)
    return hedged, ("hedged" if hedged else "confab")


def run_model(model_key, limit=None):
    m = MODELS[model_key]
    if not os.path.isfile(m["path"]):
        raise SystemExit(f"model file missing: {m['path']}")
    print(f"\n=== {m['label']} ===")
    print(f"server: {SERVER}")
    print(f"model:  {m['path']}")
    proc = start_server(m["path"])
    bank = []
    for cat_name, qs, mode in CATEGORIES:
        for q in qs:
            bank.append((cat_name, mode, q))
    if limit:
        bank = bank[:limit]

    results, passed_n, tps_all = [], 0, []
    for i, (cat_name, mode, (question, *rest)) in enumerate(bank, 1):
        try:
            r = chat([{"role": "system", "content": SYS},
                      {"role": "user", "content": question}])
        except Exception as e:
            r = {"content": f"<request failed: {e}>", "raw": "",
                 "think_leaked": False, "reasoning_field": False,
                 "wall_s": 0, "gen_tps": None, "prompt_tps": None}
        norm = normalize(r["content"])
        if mode == "all":       # era-true control: (q, keys, anti)
            keys, anti = rest
            missing = [k for k in keys if not key_hit(norm, k)]
            affirmed = [k for k in anti if key_hit(norm, k)]
            ok = not missing and not affirmed
            detail = "era-true ok" if ok else (
                "missing:" + ",".join(missing) if missing else
                "affirmed:" + ",".join(affirmed))
        else:
            keys = rest[0]
            ok, detail = grade((question, keys, mode), norm)
        passed_n += ok
        if r.get("gen_tps"):
            tps_all.append(r["gen_tps"])
        row = dict(cat=cat_name, q=question, ok=bool(ok), detail=detail,
                   answer=r["content"], wall_s=r["wall_s"],
                   gen_tps=r["gen_tps"], think_leaked=r["think_leaked"])
        results.append(row)
        mark = "PASS" if ok else "FAIL"
        print(f"[{i:2d}/{len(bank)}] {mark:4s} {cat_name:9s} {question}")
        if not ok:
            print(f"        -> {detail}")
            print(f"        answer: {r['content'][:160]}")

    stop_server(proc)
    summary = {cat_name: dict(
        passed=sum(1 for x in results if x["cat"] == cat_name and x["ok"]),
        total=sum(1 for x in results if x["cat"] == cat_name))
        for cat_name, _, _ in CATEGORIES}
    out = dict(model=model_key, label=m["label"], path=m["path"],
               server=SERVER, sampling=SAMPLING, seed=SEED,
               reasoning="off (--reasoning off + enable_thinking=false)",
               summary=summary,
               total_passed=passed_n, total=len(results),
               mean_gen_tps=(round(sum(tps_all) / len(tps_all), 1)
                             if tps_all else None),
               think_leaks=sum(1 for x in results if x["think_leaked"]),
               results=results)
    os.makedirs(RESULTS_DIR, exist_ok=True)
    path = os.path.join(
        RESULTS_DIR, f"wow_knowledge_{model_key}_{time.strftime('%Y%m%d-%H%M%S')}.json")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, indent=1)
    print(f"\n=== {m['label']}: {passed_n}/{len(results)} "
          f"({100.0 * passed_n / len(results):.0f}%)  "
          f"~{out['mean_gen_tps']} tok/s  saved: {path}")
    return out


def main():
    global PORT, BASE, RESULTS_DIR
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="minicpm5-q4,minicpm5-q8")
    ap.add_argument("--limit", type=int, default=None,
                    help="first N questions only (smoke run)")
    ap.add_argument("--port", type=int, default=PORT)
    ap.add_argument("--results-dir", default=RESULTS_DIR)
    args = ap.parse_args()
    PORT = args.port
    BASE = f"http://127.0.0.1:{PORT}"
    RESULTS_DIR = args.results_dir
    outs = []
    for key in [k.strip() for k in args.models.split(",")]:
        if key not in MODELS:
            raise SystemExit(f"unknown model {key}; "
                             f"have: {', '.join(MODELS)}")
        outs.append(run_model(key, args.limit))
    print("\n=== SUMMARY (WoW knowledge, thinking off) ===")
    hdr = (f"{'model':22s} {'total':>8s} {'lore':>9s} {'mech':>9s} "
           f"{'era':>9s} {'inv':>9s} {'era-true':>9s} {'tok/s':>6s}")
    print(hdr)
    for o in outs:
        s = o["summary"]

        def cell(name):
            v = s.get(name, {"passed": 0, "total": 0})
            return f"{v['passed']}/{v['total']}".rjust(9)

        total = f"{o['total_passed']}/{o['total']}".rjust(8)
        tps = str(o["mean_gen_tps"]).rjust(6)
        print(f"{o['label']:22s} {total} {cell('LORE')} "
              f"{cell('MECHANICS')} {cell('ERA')} {cell('INVENTED')} "
              f"{cell('ERA_TRUE')} {tps}")


if __name__ == "__main__":
    main()
