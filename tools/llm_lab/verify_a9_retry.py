#!/usr/bin/env python3
"""A9 empty-content retry verification against the pinned base model (S2).

The production bug (plan §1.4, measured 2026-08-29): the app-pinned
gemma-4-E2B-it-qat-UD-Q4_K_XL under llama-server --jinja +
/v1/chat/completions with max_tokens <= 200 returns an EMPTY content with
the budget burned into reasoning_content ("Thinking Process:" preamble,
finish_reason "length") - the shipped default model cannot produce bot
chat at all.

This script replays the EXACT production request the C++ client sends (the
LLMApiJson template LlmRuntimePolicy.apiJsonTemplate emits for the BASE_E2B
profile, with realistic SayAction fill content), verifies the failure
shape, then sends the retry body the C++ client builds in that case
(" Answer directly." spliced onto the last user message - the same pure
insertion AppendInstructionToLastUserMessage performs) and reports whether
the retry produces usable content.

Also exercises a Qwen-family model when available: the tuned GGUFs exclude
the bug with enable_thinking sent harness-side; the production body sends
NO such kwarg, so leg 2 records what the default template does.

Usage:  python tools/llm_lab/verify_a9_retry.py [--model e2b-base|q08-tuned]
Output: C:/llm-lab/results/a9_retry_<model>.json (+ printed verdict).
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request

SERVER = r"G:\NPU LLM\tools\llama-cuda-b10520\llama-server.exe"
PORT = 28091
BASE = f"http://127.0.0.1:{PORT}"
RESULTS_DIR = r"C:\llm-lab\results"

MODELS = {
    "e2b-base": {
        "path": r"G:\NPU LLM\models-download\gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf",
        "qwen": False,
    },
    "q08-tuned": {
        "path": r"G:\NPU LLM\models\qwen35-08b-CLEAN-tuned-q4_0.gguf",
        "qwen": True,
    },
}

# The EXACT template LlmRuntimePolicy.apiJsonTemplate(model="local",
# profile=LlmModelRegistry.BASE_E2B.profile, providerSafe=False) emits -
# byte-for-byte (docs/llm-runtime-submenu.md pins the same sample).
TEMPLATE = ("{\"model\":\"local\",\"messages\":[{\"role\":\"system\","
            "\"content\":\"<pre prompt> <context>\"},{\"role\":\"user\","
            "\"content\":\"<prompt> <post prompt>\"}],\"max_tokens\":120,"
            "\"temperature\":1,\"top_p\":0.95,\"top_k\":64,"
            "\"repeat_penalty\":1,\"cache_prompt\":true,\"stream\":false}")

FILLS = {
    "<pre prompt>": ("You are Grumph, a dwarf warrior smith in Goldshire. "
                     "You speak in short, gruff sentences."),
    "<context>": ("Relationship with Brannoc: Civil (tier 2 of 5). "
                  "Facts you remember about Brannoc: owes you five silver "
                  "from the ale; hates spiders."),
    "<prompt>": "Brannoc says: what do you charge for a shield repair",
    "<post prompt>": ("Reply as Grumph would speak aloud. One or two "
                      "sentences, no narration."),
}


def fill_template():
    body = TEMPLATE
    for key, value in FILLS.items():
        body = body.replace(key, value)
    return body


def append_instruction_to_last_user_message(body, instruction):
    """The python twin of pocketllm::AppendInstructionToLastUserMessage:
    a pure insertion before the closing quote of the last user content."""
    import re
    spans = [(m.start(), m.end()) for m in re.finditer(r'"role":"user"', body)]
    if not spans:
        return None
    user_role_start = spans[-1][0]
    content_match = re.search(r'"content":"((?:[^"\\]|\\.)*)"', body[user_role_start:])
    if not content_match:
        return None
    insert_at = user_role_start + content_match.end(1)
    return body[:insert_at] + instruction + body[insert_at:]


def chat(body):
    req = urllib.request.Request(BASE + "/v1/chat/completions",
                                 data=body.encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=300) as r:
        data = json.loads(r.read().decode("utf-8"))
    choice = data["choices"][0]
    message = choice.get("message") or {}
    return {
        "content": message.get("content") or "",
        "reasoning_content": message.get("reasoning_content") or "",
        "finish_reason": choice.get("finish_reason"),
        "wall_s": round(time.time() - t0, 2),
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="e2b-base", choices=list(MODELS))
    args = parser.parse_args()
    cfg = MODELS[args.model]
    proc = start_server(cfg)
    report = {"model": args.model, "path": cfg["path"], "legs": []}
    try:
        for turn in ("what do you charge for a shield repair",
                     "any news from the militia"):
            FILLS["<prompt>"] = f"Brannoc says: {turn}"
            first_body = fill_template()
            first = chat(first_body)
            leg = {"turn": turn, "first": first, "retry": None}
            # the exact condition the C++ client checks: envelope parsed,
            # content unusable, reasoning present and the budget NOT already
            # exhausted mid-thinking (finish_reason "length" skips the
            # retry - the budget-elastic thinking toll always precedes
            # content, so an identical-budget resend cannot pay it; every
            # empty draw at the production budget was length-class in the
            # preserved artifact)
            usable = bool(first["content"].strip())
            if not usable and first["reasoning_content"] and first["finish_reason"] != "length":
                retry_body = append_instruction_to_last_user_message(
                    first_body, " Answer directly.")
                if retry_body:
                    leg["retry"] = chat(retry_body)
            elif not usable and first["finish_reason"] == "length":
                leg["retry_skipped_budget_exhausted"] = True
            report["legs"].append(leg)

        first_ok = sum(1 for leg in report["legs"] if leg["first"]["content"].strip())
        retry_ok = sum(1 for leg in report["legs"]
                       if leg["retry"] and leg["retry"]["content"].strip())
        report["first_leg_usable"] = first_ok
        report["retry_leg_usable"] = retry_ok
        report["verdict"] = (
            f"first-attempt usable {first_ok}/{len(report['legs'])}; "
            f"retry usable {retry_ok}/{len(report['legs'])}")

        # Budget diagnosis: the §1.4 failure is the gemma-4 "it" template
        # routing a thinking preamble into reasoning_content. The thinking
        # is budget-elastic (reasoning_chars grow as max_tokens grows) and
        # ALWAYS precedes any content: under the app's production budget
        # the reply is ALWAYS empty (this run: empty at 120/200/300, first
        # usable at 500 with plain-fill prompts; §1.4 measured 51/51 empty
        # at <=200 under the trained prompt shapes). The tier table's
        # 120-token BASE budget can never pay the toll - the base tier
        # stays gated on the §4.4 template override by design, and no
        # budget raise may be read out of this probe as a "fix".
        report["budget_probe"] = []
        FILLS["<prompt>"] = "Brannoc says: what do you charge for a shield repair"
        probe_body = fill_template()
        for max_tokens in (120, 200, 300, 500):
            r = chat(probe_body.replace('"max_tokens":120',
                                        f'"max_tokens":{max_tokens}'))
            report["budget_probe"].append({
                "max_tokens": max_tokens,
                "usable": bool(r["content"].strip()),
                "finish_reason": r["finish_reason"],
                "reasoning_chars": len(r["reasoning_content"]),
            })
        print(json.dumps(report, indent=1, ensure_ascii=False))
        print("VERDICT:", report["verdict"])
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()

    os.makedirs(RESULTS_DIR, exist_ok=True)
    with open(os.path.join(RESULTS_DIR, f"a9_retry_{args.model}.json"), "w",
              encoding="utf-8") as f:
        json.dump(report, f, indent=1, ensure_ascii=False)
    print("saved:", os.path.join(RESULTS_DIR, f"a9_retry_{args.model}.json"))


if __name__ == "__main__":
    main()
