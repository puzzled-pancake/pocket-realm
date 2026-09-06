"""T0.5's banned-token CI grep (plan §10 T0.5, round-5 R8): no
content-policy/safety-boilerplate phrasing anywhere in prompt-adjacent
text - the authored corpus, the prompt/composer surfaces, and the
driver payloads that build bodies.

§0.1's carve-out is honored by scope, not by token: diegetic
in-character refusal lines (E0's pools, E3's wiring) are authored
CONTENT delivered engine-side - the banned tokens below are the
app-side assistant-register phrases a leaked system prompt or an
out-of-place safety preamble would carry; none of them can appear in
an authored fantasy line without failing the E1 register lint anyway.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# prompt-adjacent text surfaces: the authored pools, the persona/
# chatter composer surfaces, the prompt headers (emitter-spliced, so
# ANY hit here also trips the byte-freeze lockfile pin), the compose
# furniture/trained-cue surfaces whose prose rides into prompts
# (round-6 R7: the five files beyond the original six), and the whole
# driver (payload bodies + conf docs)
SURFACES = [
    ROOT / "native" / "patches" / "playerbots" / "llm_banter_core.h",
    ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmPersona.cpp",
    ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmChatterCore.h",
    ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmPrompt.h",
    ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmRecallCore.h",
    ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmMemory.cpp",
    ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmBridge.cpp",
    ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmFilters.cpp",
    ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmTruthCore.h",
    ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmToolsCore.h",
    ROOT / "tools" / "build_o09_realm_runtime.py",
]

_APOS = "'’"  # straight + curly apostrophes (round-8 R3/R7)

BANNED = [
    r"as an ai\b",
    r"i(?:'| a)m (?:just )?an ai\b",
    r"content policy",
    r"safety guidelines",
    # round-6 R7: the contraction forms slip a naive grep - cover both;
    # round-7 R3: the adjacent soft-refusal variants join ("could not",
    # "won't", "would not", "will not", "unable to"); round-8 R3/R7: the
    # "i'm" prefix gets its own row (an "i "+space prefix can never
    # match it) and the apostrophe classes admit the curly U+2019 (the
    # corpus is ASCII-only, so no authored line can false-positive);
    # round-9 R3: the verb tails widen (fulfill/provide/complete) and
    # the negation arms join (spaced "can not", "am not able to"); the
    # sorry-row takes the apostrophe class too (it was the last
    # straight-only row)
    "i (?:can(?:[" + _APOS + "]|no)?t|can not|could not|won[" + _APOS + "]t|would not|will not|am unable to|am not able to)"
    " (?:assist|comply|help with|fulfill|provide|complete)",
    "i[" + _APOS + "]m (?:unable to )?(?:assist|comply|help with|fulfill|provide|complete)",
    "i[" + _APOS + "]m sorry, but i",
    r"harmful or inappropriate",
    r"my instructions",
    r"language model",
]


def test_no_boilerplate_tokens_in_prompt_adjacent_text():
    hits: list[str] = []
    for surface in SURFACES:
        text = surface.read_text(encoding="utf-8", errors="replace").lower()
        for token in BANNED:
            for match in re.finditer(token, text):
                line_no = text.count("\n", 0, match.start()) + 1
                hits.append(f"{surface.name}:{line_no}: /{token}/")
    assert not hits, (
        "content-policy/safety-boilerplate tokens in prompt-adjacent "
        "text (0.1 violation class):\n" + "\n".join(hits))
