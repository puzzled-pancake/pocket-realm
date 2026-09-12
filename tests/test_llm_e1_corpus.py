"""E1 pool pins (plan RP workstream E1, v2.3 s6): the authored corpus
targets.

E1 grows the seven speech families to the reconciled ~1,100-line corpus:
greet tiers 40 -> 330, busy 12 -> 132, silence 12 -> 104, idle 18 -> 190,
kill 14 -> 142 (the kill change re-pins the FNV golden in
test_llm_banter.py in the SAME commit), floor 10 -> 120 (the murmur
template bank), cheer 0 -> 24 (the level-up authored leg), plus the
additive archetype seasoning bank (4x12 = 48, composed onto the greet
tier draw with RACE in the phrase-draw lane key). This battery pins the
exact pool shapes, the register lint over every line (banned tokens,
mechanic words, digits, uppercase acronyms, ASCII, word-count bounds, no
verbatim duplicates within or across pools), the seasoning/wiring
contracts, and the floor template authoring rule (renders >= 5 words
with a two-word {E}).

The target vector is the v2.2 table read through v2.3's corrections (the
table itself is superseded and not in the tree; the sum is the law).
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
PATCHES = ROOT / "native" / "patches" / "playerbots"
CORE = PATCHES / "llm_banter_core.h"
CHATTER_CORE = PATCHES / "PlayerbotLlmChatterCore.h"
PERSONA_CPP = PATCHES / "PlayerbotLlmPersona.cpp"
PERSONA_H = PATCHES / "PlayerbotLlmPersona.h"
MEMORY_CPP = PATCHES / "PlayerbotLlmMemory.cpp"
BANTER_TEST = ROOT / "tools" / "test_llm_banter_core.cpp"

LINE_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')

# The E0 register-lint law, shared (E1 pools MAY use {P}/{B} placeholders,
# which the core's LineIsValid admits).
BANNED_TOKENS = ("|cff", "gearscore", "http", "www", "|")
MECHANIC_WORDS = ("level", "quest", "dungeon", "raid", "buff", "gear",
                  "cooldown", "xp", "aggro")
# E1 pools keep the authored house style: ALL-CAPS EMPHASIS is legal
# (pre-existing lines use it - hold THIS one, a WEEK); the ban is on
# web/chat acronyms as lowercase words.
CHAT_ACRONYMS = ("lol", "brb", "omg", "wtf", "lmao", "afk", "idk", "btw",
                 "gg", "thx", "pls", "u", "ur")
DIGIT_RE = re.compile(r"\d")

# The E1 target vector (the corpus law this battery enforces).
TARGETS = {
    "kGreetStranger": 83,
    "kGreetAcq": 81,
    "kGreetAlly": 83,
    "kGreetTrusted": 83,
    "kBusy": 132,
    "kSilence": 104,
    "kIdle": 190,
    "kKill": 142,
    "kCheer": 24,
    "kArchetypePhrase": 48,
}
WORD_BOUNDS = {
    "kGreetStranger": (4, 18),
    "kGreetAcq": (4, 20),
    "kGreetAlly": (5, 22),
    "kGreetTrusted": (5, 26),
    "kBusy": (3, 18),
    "kSilence": (4, 24),
    "kIdle": (5, 28),
    "kKill": (1, 12),
    "kCheer": (3, 16),
    "kArchetypePhrase": (2, 8),
}


def _table_lines(text: str, definition: str) -> list[str]:
    start = text.index(definition)
    brace = text.index("{", start)
    depth, end = 0, None
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                end = i
                break
    assert end, f"unbalanced braces after {definition}"
    return [m.replace('\\"', '"') for m in LINE_RE.findall(text[brace:end])]


def _pool(name: str) -> list[str]:
    if name == "kArchetypePhrase":
        return _table_lines(CORE.read_text(encoding="utf-8"), f"{name}[4][12] = ")
    return _table_lines(CORE.read_text(encoding="utf-8"), f"{name}[] = ")


@pytest.fixture(scope="module", params=sorted(TARGETS))
def pool(request):
    return request.param, _pool(request.param)


def _word_count(line: str) -> int:
    stripped = line.replace("{P}", " name ").replace("{B}", " name ")
    return len([w for w in re.split(r"[^A-Za-z0-9']+", stripped) if w])


def test_pool_shapes_hit_the_e1_target_vector():
    for name, want in TARGETS.items():
        got = len(_pool(name))
        assert got == want, f"{name}: {got} != target {want}"


def test_register_lint_on_every_e1_line(pool):
    name, lines = pool
    lo, hi = WORD_BOUNDS[name]
    for line in lines:
        low = line.lower()
        for token in BANNED_TOKENS:
            assert token not in low, f"{name} leaks {token!r}: {line!r}"
        for word in MECHANIC_WORDS:
            assert not re.search(rf"\b{word}\b", low), \
                f"{name} names a mechanic ({word}): {line!r}"
        assert line.isascii(), f"{name} is not ASCII: {line!r}"
        for acronym in CHAT_ACRONYMS:
            assert not re.search(rf"\b{acronym}\b", low), \
                f"{name} has a chat acronym ({acronym}): {line!r}"
        assert not DIGIT_RE.search(line), f"{name} has a digit: {line!r}"
        assert "{" not in line.replace("{P}", "").replace("{B}", ""), \
            f"{name} uses braces outside the placeholders: {line!r}"
        n = _word_count(line)
        assert lo <= n <= hi, f"{name} word count {n} outside [{lo},{hi}]: {line!r}"
        assert line[0] not in "*[ ", f"{name} bad lead char: {line!r}"


def test_no_duplicates_within_or_across_e1_pools():
    seen: dict[str, str] = {}
    for name in TARGETS:
        for line in _pool(name):
            assert line not in seen, \
                f"verbatim duplicate of {seen[line]!r} in {name}: {line!r}"
            seen[line] = name


def test_no_verbatim_collision_with_the_e0_banks():
    e0 = (
        _table_lines(CORE.read_text(encoding="utf-8"), "kStreetShort[4][12] = ")
        + _table_lines(CORE.read_text(encoding="utf-8"), "kSecurityRefuse[4][12] = ")
    )
    e1 = [l for name in TARGETS for l in _pool(name)]
    assert not set(e0) & set(e1), "E1 reused an E0 bank line verbatim"


def test_the_seeded_pool_family_is_untouched_where_e0_promised():
    # E1 grows pools but must not renumber them: POOL_CHEER is appended
    # AFTER POOL_SECURITY_REFUSE, before POOL_COUNT
    text = CORE.read_text(encoding="utf-8")
    enum_block = text.split("enum PoolId")[1].split("};")[0]
    assert enum_block.index("POOL_SECURITY_REFUSE") < enum_block.index("POOL_CHEER")
    assert enum_block.index("POOL_CHEER") < enum_block.index("POOL_COUNT")
    assert "case POOL_CHEER: count = sizeof(kCheer)/sizeof(void*); return kCheer;" in text


def test_seasoning_bank_rows_follow_the_archetype_order():
    text = CORE.read_text(encoding="utf-8")
    block = _table_lines_block(text, "kArchetypePhrase[4][12] = ")
    for tag in ("// ARCHETYPE_GRUFF", "// ARCHETYPE_SHY",
                "// ARCHETYPE_NOBLE", "// ARCHETYPE_ROGUEISH"):
        assert tag in block, f"missing row tag {tag}"
    assert block.index("// ARCHETYPE_GRUFF") < block.index("// ARCHETYPE_SHY") < \
        block.index("// ARCHETYPE_NOBLE") < block.index("// ARCHETYPE_ROGUEISH")


def _table_lines_block(text: str, definition: str) -> str:
    start = text.index(definition)
    brace = text.index("{", start)
    depth, end = 0, None
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                end = i
                break
    return text[start:end]


def test_greeting_seasoning_is_additive_with_race_in_the_lane():
    text = PERSONA_CPP.read_text(encoding="utf-8")
    helper = re.search(r"static std::string SeasonGreeting\(.*?\n\}", text, re.S)
    assert helper, "SeasonGreeting helper missing"
    body = helper.group(0)
    # additive: the tier line is the backbone; the phrase composes after it
    assert "ArchetypePhraseRow" in body
    assert 'tierLine + " " + phrase' in body
    assert "seasoned.size() > 195" in body, "the chat byte budget gates the compose"
    # RACE mixed into the phrase-draw lane key (the plan's fix for the
    # class-only ArchetypeFor)
    assert "bot->getRace() & 0xF" in body
    # the season draws BEFORE RandomTeleport... (no - it draws through the
    # same ring machinery as every pool): the 0x400 lane bit keeps the
    # phrase state clear of every pool lane
    assert "0x400" in body
    greet = re.search(r"std::string PlayerbotLlmPersona::GreetingLine\(.*?\n\}", text, re.S).group(0)
    # the lock-scope restructure seasons through named draws
    # (drawn / redrawn) - still exactly TWO season points: the draw and
    # the C7 redraw-past-persisted
    assert greet.count("SeasonGreeting(bot, player, ") == 2, \
        "both the draw and the C7 redraw season the line"
    # and the state lock must NOT be held across a season call
    # (SeasonGreeting re-enters StateFor: EDEADLK on the non-recursive
    # state mutex - the arrival-storm crash)
    assert greet.count("StateRef stateRef = StateFor(") == 3, \
        "draw, redraw and ApplyTic each take their own tight lock scope"


def test_cheer_pool_and_the_event_drain_delivery():
    # the persona export + the ring-deduped draw
    header = PERSONA_H.read_text(encoding="utf-8")
    assert "static std::string CheerLine(Player* bot, Player* forPlayer);" in header
    persona = PERSONA_CPP.read_text(encoding="utf-8")
    cheer = re.search(r"std::string PlayerbotLlmPersona::CheerLine\(.*?\n\}", persona, re.S)
    assert cheer and "POOL_CHEER" in cheer.group(0)
    # the delivery decision at the event drain: ONE grouped bot voices an
    # authored cheer 2-5 s after the level-up note (the condolence pattern)
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    lvl = re.search(r"void PlayerbotLlmMemory::OnPlayerLevelUp\(.*?\n\}", memory, re.S)
    assert lvl, "OnPlayerLevelUp missing"
    body = lvl.group(0)
    assert "QueueForPartyBots(player, out.str(), 2, PlayerbotLlmBridge::EVENT_LEVEL_UP);" in body
    assert "PlayerbotLlmPersona::CheerLine(speaker, player)" in body
    assert "cheerReaction.authored = true;" in body
    assert "cheerReaction.notBefore = time(nullptr) + urand(2, 5);" in body
    assert "QueueAuthoredReaction(speaker, cheerReaction);" in body
    # the authored leg rides the same kill-switches as every authored beat
    assert "llmBanterEnabled" in body and "llmEventReactionsEnabled" in body


def test_the_floor_bank_hits_its_target_and_the_authoring_rule():
    text = CHATTER_CORE.read_text(encoding="utf-8")
    templates = _table_lines(text, "templates[] = ")
    assert len(templates) == 120, \
        f"the murmur floor bank must hold 120 templates (got {len(templates)})"
    for t in templates:
        assert t.count("{E}") == 1 and t.count("{L}") == 1, \
            f"floor template must carry exactly one {{E}} and one {{L}}: {t!r}"
        # the authoring rule: renders >= 5 words with a two-word {E}
        # ({L} renders as one word, {E} as two)
        own = _word_count(t.replace("{L}", "").replace("{E}", ""))
        assert own >= 3, f"floor template too thin ({own} own words): {t!r}"
        assert own + 3 <= 24, f"floor template too long ({own} own words): {t!r}"


def test_the_kill_pool_change_re_pinned_the_golden_in_the_same_commit():
    # the FNV golden rides any kill-pool change (E1 touched kKill) - this
    # asserts the harness side still exists so the golden in
    # test_llm_banter.py is the only fingerprint authority
    harness = BANTER_TEST.read_text(encoding="utf-8")
    assert "Pool(POOL_KILL, n)" in harness
    assert "golden_fnv1a64" in harness
