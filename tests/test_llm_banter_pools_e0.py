"""E0 pool pins: the street short-reaction bank and the gate security
refusal bank.

Both banks live in native/patches/playerbots/llm_banter_core.h as 4x12
archetype tables (48 authored lines each). This battery pins: pool
presence and shape (4 groups x 12 lines), the register lint over every
line (banned tokens, mechanic words, digits, uppercase acronyms, ASCII,
word-count bounds, no verbatim duplicates), the no-collision law against
the persona refuseLine cells, the recency-ring non-repetition contract
(12 distinct draws per cell, driven on the host against the SHIPPED
header), the guid<<24 state-key layout (the guid<<8 persona lane is
full), and the SecurityRefusalLine export with its configured-string
fallback. The seeded-path fingerprint (the FNV golden over the KILL
pool) stays pinned by tests/test_llm_banter.py - E0 appends its pools
after POOL_WILDCARD and never touches InitBanterState, and that battery
proves it in the same run.
"""
from __future__ import annotations

import re
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
PATCHES = ROOT / "native" / "patches" / "playerbots"
CORE = PATCHES / "llm_banter_core.h"
PERSONA_H = PATCHES / "PlayerbotLlmPersona.h"
PERSONA_CPP = PATCHES / "PlayerbotLlmPersona.cpp"

STREET_DEF = "kStreetShort[4][12]"
SECURITY_DEF = "kSecurityRefuse[4][12]"

# Banned register tokens: UI color codes, meta talk, web leakage, and any
# pipe (the 1.12 chat-packet control byte).
BANNED_TOKENS = ("|cff", "gearscore", "http", "www", "|")
# Game mechanics never get named in the authored voice.
MECHANIC_WORDS = ("level", "quest", "dungeon", "raid", "buff", "gear",
                  "cooldown", "xp", "aggro")
ACRONYM_RE = re.compile(r"\b[A-Z]{2,}\b")
DIGIT_RE = re.compile(r"\d")
LINE_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')

# Word-count laws: street short-reactions run 5-24 words, gate refusals
# 3-14 (the corpus bounds for the two banks).
STREET_WORDS = (5, 24)
SECURITY_WORDS = (3, 14)

PERSONA_ARCHETYPES = ("// ARCHETYPE_GRUFF", "// ARCHETYPE_SHY",
                      "// ARCHETYPE_NOBLE", "// ARCHETYPE_ROGUEISH")


# ------------------------------------------------------- source parsing ----
def _array_block(text: str, definition: str) -> str:
    """The brace-balanced initializer of `name[4][12] = { ... };`."""
    brace = text.index("{", text.index(definition))
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[brace:i + 1]
    raise AssertionError(f"unbalanced braces after {definition}")


def _groups(block: str) -> list[str]:
    """The inner { ... } row spans of a [4][12] initializer (the pool
    literals carry no braces - the core's own content contract bans them
    outside {P}/{B}, and both E0 banks are placeholder-free)."""
    groups: list[str] = []
    depth, start = 0, None
    for i, ch in enumerate(block):
        if ch == "{":
            depth += 1
            if depth == 2:
                start = i
        elif ch == "}":
            depth -= 1
            if depth == 1 and start is not None:
                groups.append(block[start:i + 1])
                start = None
    return groups


def _lines(group_text: str) -> list[str]:
    return [m.replace('\\"', '"').replace("\\\\", "\\")
            for m in LINE_RE.findall(group_text)]


def _bank(definition: str) -> list[list[str]]:
    return [_lines(g) for g in _groups(_array_block(CORE.read_text(encoding="utf-8"), definition))]


@pytest.fixture(scope="session")
def street_bank():
    return _bank(STREET_DEF)


@pytest.fixture(scope="session")
def security_bank():
    return _bank(SECURITY_DEF)


@pytest.fixture(scope="session")
def refuse_bank():
    text = PERSONA_CPP.read_text(encoding="utf-8")
    return [_lines(g) for g in _groups(_array_block(text, "refuseLine[4][12]"))]


# ------------------------------------------------------------ shape pins ----
def test_street_pool_shape(street_bank):
    # 4 speaker-archetype groups x 12 short reactions = 48 authored lines
    assert len(street_bank) == 4, "the street bank has four speaker groups"
    assert [len(g) for g in street_bank] == [12, 12, 12, 12]
    assert sum(len(g) for g in street_bank) == 48


def test_security_pool_shape(security_bank):
    # 4 persona-archetype rows x 12 gate refusals = 48 authored lines
    assert len(security_bank) == 4, "the security bank has four archetype rows"
    assert [len(g) for g in security_bank] == [12, 12, 12, 12]
    assert sum(len(g) for g in security_bank) == 48


def test_security_rows_follow_the_persona_archetype_order():
    # the rows are indexed by PlayerbotLlmPersona::Archetype; the row
    # comments must mirror the persona cells' tags, in enum order
    block = _array_block(CORE.read_text(encoding="utf-8"), SECURITY_DEF)
    at = [block.find(tag) for tag in PERSONA_ARCHETYPES]
    assert all(a >= 0 for a in at), "every persona archetype tag present"
    assert at == sorted(at), "rows are tagged in persona Archetype order"


def test_street_speaker_groups_are_documented():
    block = _array_block(CORE.read_text(encoding="utf-8"), STREET_DEF)
    assert block.count("// speaker") == 4, (
        "each street speaker archetype is documented with a comment")


# ---------------------------------------------------------- register lint ----
def _assert_register(lines, bounds, pool):
    lo, hi = bounds
    for line in lines:
        low = line.lower()
        for token in BANNED_TOKENS:
            assert token not in low, f"{pool} line leaks {token!r}: {line!r}"
        for word in MECHANIC_WORDS:
            assert not re.search(rf"\b{word}\b", low), (
                f"{pool} line names the mechanic {word!r}: {line!r}")
        assert line.isascii(), f"{pool} line is not ASCII: {line!r}"
        assert not ACRONYM_RE.search(line), f"{pool} line has an acronym: {line!r}"
        assert not DIGIT_RE.search(line), f"{pool} line has digits: {line!r}"
        assert "{" not in line and "}" not in line, (
            f"{pool} line carries a placeholder/brace: {line!r}")
        nwords = len(line.split())
        assert lo <= nwords <= hi, (
            f"{pool} line has {nwords} words (law {lo}..{hi}): {line!r}")


def test_street_register_lint(street_bank):
    for group in street_bank:
        _assert_register(group, STREET_WORDS, "street")


def test_security_register_lint(security_bank):
    for group in security_bank:
        _assert_register(group, SECURITY_WORDS, "security")


def test_no_duplicates_within_or_across_e0_banks(street_bank, security_bank):
    street = [line for group in street_bank for line in group]
    security = [line for group in security_bank for line in group]
    assert len(set(street)) == 48, "street bank has a verbatim duplicate"
    assert len(set(security)) == 48, "security bank has a verbatim duplicate"
    assert not set(street) & set(security), (
        "a line is shared verbatim between the two E0 banks")


def test_no_verbatim_collision_with_the_refuse_pool(street_bank, security_bank, refuse_bank):
    # the beg-refusal ground (refuseLine) and the E0 banks must stay
    # disjoint - a repeated line would read as one refusal system
    refuse = {line for group in refuse_bank for line in group}
    assert len(refuse) == 48, "the persona refuseLine pool still has 48 lines"
    assert not {line for group in security_bank for line in group} & refuse, (
        "a security line collides verbatim with a refuseLine line")
    assert not {line for group in street_bank for line in group} & refuse, (
        "a street line collides verbatim with a refuseLine line")


def test_e0_lines_are_new_to_the_core(street_bank, security_bank):
    core = CORE.read_text(encoding="utf-8")
    detail = core.split("namespace detail")[1].split("} // namespace detail")[0]
    others = (detail
              .replace(_array_block(core, STREET_DEF), "")
              .replace(_array_block(core, SECURITY_DEF), ""))
    new_lines = {line for group in street_bank + security_bank for line in group}
    reused = [line for line in new_lines if f'"{line}"' in others]
    assert not reused, f"E0 reused existing core lines verbatim: {reused}"


# ----------------------------------------------------- host-driven pins ----
PROBE = r'''
#include "llm_banter_core.h"
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

using namespace pocketllm;

static int g_failures = 0;
#define CHECK(cond, msg) do { \
    if (!(cond)) { std::printf("FAIL %s (line %d)\n", msg, __LINE__); ++g_failures; } \
} while (0)

static void CheckBank(char const* name, PoolId flatId,
                      char const* const* (*cellFn)(size_t, size_t&))
{
    size_t n = 0;
    char const* const* flat = Pool(flatId, n);
    CHECK(flat && n == 48, "bank flattens to 48 lines");
    for (size_t i = 0; i < n; ++i)
        CHECK(LineIsValid(flat[i]), "bank line passes the content contract");

    for (size_t g = 0; g < 4; ++g)
    {
        size_t cn = 0;
        char const* const* cell = cellFn(g, cn);
        CHECK(cell && cn == 12, "bank cell holds 12 lines");
        for (size_t i = 0; i < cn; ++i)
            CHECK(LineIsValid(cell[i]), "cell line passes the content contract");
        CHECK(cell[0] == flat[g * 12], "cell aliases the flattened bank (row-major)");

        BanterState s;
        InitBanterState(s, (uint32_t)(g * 131 + 7), (uint32_t)g);
        std::vector<std::string> drawn;
        for (int d = 0; d < 12; ++d)
        {
            BanterResult r = SelectLine(s, cell, cn, 0, 0, 0, 0);
            CHECK(r.line && !r.suppressed, "ring draw answered");
            drawn.push_back(r.line ? r.line : "");
        }
        for (size_t a = 0; a < drawn.size(); ++a)
            for (size_t b = a + 1; b < drawn.size(); ++b)
                CHECK(drawn[a] != drawn[b], "12 ring draws are 12 distinct lines");

        size_t foldN = 0, zeroN = 0;
        char const* const* fold = cellFn(99, foldN);
        char const* const* zero = cellFn(0, zeroN);
        CHECK(fold == zero && foldN == 12 && zeroN == 12, "group folds to row 0");
    }
    (void)name;
}

int main()
{
    CheckBank("street", POOL_STREET_SHORT, &StreetShortCell);
    CheckBank("security", POOL_SECURITY_REFUSE, &SecurityRefuseCell);
    if (g_failures) { std::printf("%d e0 pool failure(s)\n", g_failures); return 1; }
    std::printf("e0 pools ok\n");
    return 0;
}
'''


@pytest.fixture(scope="session")
def e0_probe_binary(tmp_path_factory):
    gxx = shutil.which("g++") or shutil.which("clang++")
    if gxx is None:
        pytest.skip("no host C++ compiler available")
    if not CORE.is_file():
        pytest.skip("banter core not staged")
    tmp = tmp_path_factory.mktemp("e0pools")
    probe = tmp / "e0_probe.cpp"
    probe.write_text(PROBE, encoding="ascii")
    exe = tmp / "e0_probe.exe"
    build = subprocess.run(
        [gxx, "-std=c++11", "-O1", "-Wall", "-I", str(PATCHES),
         "-o", str(exe), str(probe)],
        capture_output=True, text=True, timeout=300)
    if build.returncode != 0:
        pytest.fail(f"e0 probe compile failed:\n{build.stderr[:4000]}")
    return exe


def test_host_probe_e0_pool_contracts(e0_probe_binary):
    # against the SHIPPED header: both banks flatten to 48 valid lines,
    # every cell is 12 aliasing the flat bank, and the recency ring
    # yields 12 distinct draws per 12-line cell (a line cannot return
    # until the ring evicts it).
    result = subprocess.run([str(e0_probe_binary)], capture_output=True,
                            text=True, timeout=300)
    assert result.returncode == 0, result.stdout + result.stderr[:2000]
    assert "e0 pools ok" in result.stdout


# ------------------------------------------------------ wiring/source pins ----
def test_security_refusal_export_exists_with_fallback():
    header = PERSONA_H.read_text(encoding="utf-8")
    assert "static std::string SecurityRefusalLine(Player* bot);" in header
    text = PERSONA_CPP.read_text(encoding="utf-8")
    assert "PlayerbotLlmPersona::SecurityRefusalLine(Player* bot)" in text
    body = text.split("PlayerbotLlmPersona::SecurityRefusalLine(Player* bot)")[1]
    body = body.split("\n}")[0]
    # draws the new bank's archetype cell through the shared ring path
    assert "POOL_SECURITY_REFUSE" in body
    assert "SecurityRefuseCell" in body
    assert "SelectLine" in body
    # draw-failure fallback: the configured legacy string (BusyReply
    # precedent), never an empty shrug
    assert "return sPlayerbotAIConfig.llmBusyReply;" in body


def test_new_pool_state_keys_use_the_guid24_lane():
    # the guid<<8 persona lane is FULL (FallbackLine owns bits 0-4,
    # ReactionLine bits 5/6) - new pool state must key on the
    # guid<<24 | (pool+1) lane like the newest existing pools
    text = PERSONA_CPP.read_text(encoding="utf-8")
    assert "((uint64_t)bot->GetGUIDLow() << 24) | (uint64_t)(pocketllm::POOL_SECURITY_REFUSE + 1)" in text
    body = text.split("PlayerbotLlmPersona::SecurityRefusalLine(Player* bot)")[1]
    body = body.split("\n}")[0]
    assert "<< 8" not in body, "the security pool must not key into the full guid<<8 lane"


def test_new_pools_stay_off_the_seeded_path():
    # appended AFTER POOL_WILDCARD: every pre-existing pool keeps its
    # number, InitBanterState is untouched, and the golden pin over the
    # seeded path (tests/test_llm_banter.py, same run) proves the
    # fingerprint end to end
    core = CORE.read_text(encoding="utf-8")
    enum = core.split("enum PoolId")[1].split("};")[0]
    assert enum.index("POOL_WILDCARD") < enum.index("POOL_STREET_SHORT")
    assert enum.index("POOL_STREET_SHORT") < enum.index("POOL_SECURITY_REFUSE")
    assert enum.index("POOL_SECURITY_REFUSE") < enum.index("POOL_COUNT")
    init = core.split("InitBanterState(BanterState& s")[1].split("\n}")[0]
    for name in ("STREET", "SECURITY"):
        assert name not in init, "InitBanterState must not know the E0 pools"
