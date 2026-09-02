"""The keyed-emote harness conversion, pinned.

The harness gates re-run prtools2/3/4 + ambition (and final.py / rp-web, which
import them) against the v2.3 banks, which train the keyed emote line
(`<<perform_emote emote="laugh">>`). The harnesses used to teach and score
the bare research-era form, and their parsers diverged from the shipped
scanner. These pins hold the conversion in place:

- prtools3's parser mirrors the shipped ExtractToolCalls (keyed primary,
  the lone-word fold with its exact guards, the shipped
  resolve map, leakage/unterminated rules, and NO tolerance beyond it)
- prtools3's TOOLS_NOTE stays byte-identical to banklib's frozen A-variant
  (the harness measures the trained distribution, not a drifted prompt)
- dispatch_of (the trigger-engine self-check) reads keyed skeletons
- prtools4's bridge skeletons, ambition's D spec expansion, and rp-web's
  greet skeleton are keyed; no bare-form teaching remains in any of them
- prtools2's v2 arm uses the mirror; its v1 arm stays the frozen control

Skips (with reason) on a machine without the G: harness tree.
"""
import importlib.util
import re
from pathlib import Path

import pytest

HARNESS = Path(r"G:\NPU LLM\scripts\bench-harness")
BANKLIB_DIR = Path(r"G:\NPU LLM\scripts\finetune")
CONVERTED = ("prtools2.py", "prtools3.py", "prtools4.py", "ambition.py",
             "rp-web.py", "tricks.py", "final.py")


def _load(name):
    if not HARNESS.is_dir():
        pytest.skip("bench harness tree (G:\\NPU LLM) not present")
    spec = importlib.util.spec_from_file_location(
        name, HARNESS / (name + ".py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


@pytest.fixture(scope="module")
def p3():
    return _load("prtools3")


@pytest.fixture(scope="module")
def p2():
    return _load("prtools2")


@pytest.fixture(scope="module")
def p4():
    return _load("prtools4")


@pytest.fixture(scope="module")
def amb():
    return _load("ambition")


@pytest.fixture(scope="module")
def tricks():
    return _load("tricks")


def calls_of(p3, raw):
    return p3.parse(raw)[0]


def test_tools_note_is_frozen_against_banklib(p3):
    if not BANKLIB_DIR.is_dir():
        pytest.skip("authoring tree (G:\\NPU LLM) not present")
    import sys
    sys.path.insert(0, str(BANKLIB_DIR))
    import banklib as B
    assert p3.TOOLS_NOTE == B.TOOLS_NOTE


def test_keyed_forms_parse_and_score(p3):
    calls = calls_of(p3, 'Aye.\n<<perform_emote emote="laugh">>')
    assert len(calls) == 1
    assert calls[0]["name"] == "perform_emote"
    assert calls[0]["fields"]["emote"] == "laugh"
    assert calls[0]["resolves"]
    assert p3.parse('Aye.\n<<perform_emote emote="laugh">>')[2] == "Aye."
    # unquoted values parse (the C++ key scan accepts both spellings)
    assert calls_of(p3, "<<perform_emote emote=laugh>>")[0]["fields"]["emote"] == "laugh"
    # multi-word quoted values and a quoted `>>` survive intact
    assert calls_of(p3, '<<log_fact text="his favorite blade is an axe">>')[
        0]["fields"]["text"] == "his favorite blade is an axe"
    assert calls_of(p3, '<<log_fact text="a <<b>> c">>')[
        0]["fields"]["text"] == "a <<b>> c"


def test_lone_word_fold_matches_the_shipped_scanner(p3):
    """The lone-word fold: perform_emote, no keyed fields, one
    letters-only trailing token - nothing more."""
    c = calls_of(p3, "<<perform_emote laugh>>")[0]
    assert c["fields"]["emote"] == "laugh" and c["resolves"]
    # digits refuse the fold (the C++ whitelist is letters-only)
    c = calls_of(p3, "<<perform_emote laugh1>>")[0]
    assert c["fields"].get("emote") == "" and not c["resolves"]
    # a keyed field present means no fold, whatever trails
    assert calls_of(p3, '<<perform_emote emote="laugh" cheer>>')[
        0]["fields"]["emote"] == "laugh"
    # multiple bare tokens: the key scan's LAST dangling token folds
    # (trace the C++ loop - the first token becomes a key that never
    # reaches its `=` and is overwritten)
    c = calls_of(p3, "<<perform_emote laugh hard>>")[0]
    assert c["fields"].get("emote") == "hard" and not c["resolves"]
    # the fold is perform_emote-only
    assert "emote" not in calls_of(p3, "<<share_gossip laugh>>")[0]["fields"]


def test_resolve_map_mirrors_resolve_text_emote(p3):
    # whitelisted names survive (the old harness maps sent grin->laugh and
    # shrug->nod - grin/shrug/dance are TRAINED emotes now)
    for raw, want in [("grin", "grin"), ("shrug", "shrug"), ("dance", "dance"),
                      ("wave", "wave"),
                      # the shipped alias map, verbatim
                      ("smile", "grin"), ("frown", "no"), ("angry", "glare"),
                      ("mad", "glare"), ("chuckle", "laugh"), ("hello", "wave"),
                      ("applaud", "cheer"), ("yes", "nod"), ("sob", "cry"),
                      # light morphology: plural strip, long-name suffixes
                      ("bows", "bow"), ("waves", "wave"),
                      ("laughing", "laugh"), ("cheered", "cheer"),
                      # unresolvable: no guessing, 0 = play nothing
                      ("sad", None), ("nodding", None), ("smiles", None),
                      ("", None)]:
        assert p3.resolve_emote(raw) == want, raw
    # copy-fidelity: the scored value is the RESOLVED one (what a player
    # would see were this the licensed line; production playback itself
    # always reads the licensed line, not the model's copy)
    c = calls_of(p3, '<<perform_emote emote="smile">>')[0]
    assert c["fields"]["emote"] == "grin" and c["resolves"]


def test_degenerate_blocks_match_production(p3):
    # unterminated marker: prose kept, partial line dropped, scan stops
    calls, _, cleaned = p3.parse('Aye. <<log_fact text="cut short')
    assert not calls and cleaned == "Aye."
    # a single `>` does not close a block (production needs `>>`)
    assert not calls_of(p3, '<<perform_emote emote="laugh">')
    # prose truncates at stray `>>` (protocol leakage)
    cleaned = p3.parse("Sure. >>log_fact text=x tail")[2]
    assert cleaned.startswith("Sure.") and "log_fact" not in cleaned
    # short-name aliasing and mirrored openers never landed in the C++ -
    # the mirror must not resurrect calls production would drop
    c = calls_of(p3, "<<gossip text=\"x\">>")[0]
    assert c["name"] == "gossip" and not c["resolves"]
    assert not calls_of(p3, ">>share_gossip text=\"x\">>")


def test_dispatch_of_reads_keyed_skeletons(p3):
    """Regression: the old norm parsed skeletons as bare and reported
    ENGINE-MISS on every emote case once they went keyed."""
    for cid, turn, _, _, _ in p3.CASES:
        assert p3.dispatch_of(turn) == sorted(p3.EXPECT_DISPATCH[cid]), cid
    skeletons = [ln for _, ln in
                 p3.triggers("DAEVIN REACHED LEVEL 31! *cheers*")[0]]
    assert all(re.search(r'<<perform_emote emote="', ln)
               for ln in skeletons if "perform_emote" in ln)


def test_prtools2_arms_split_control_and_mirror(p2, p3):
    # v2 delegates to the production mirror (keyed + fold)
    c = p2.parse('<<perform_emote emote="laugh">>', True)[0][0]
    assert c["fields"]["emote"] == "laugh" and c["resolves"]
    assert p2.parse("<<perform_emote laugh>>", True)[0][0]["resolves"]
    # v1 is the frozen pre-fix control: keyed quoted parses, no fold
    c = p2.parse('<<perform_emote emote="laugh">>', False)[0][0]
    assert c["fields"]["emote"] == "laugh"
    assert not p2.parse("<<perform_emote laugh>>", False)[0]
    # both arms score through the shipped resolve (grin is not laugh)
    c = p2.parse('<<perform_emote emote="grin">>', False)[0][0]
    assert c["fields"]["emote"] == "grin" and c["resolves"]
    # the v2 instruction teaches the keyed line
    assert '<<perform_emote emote="laugh">>' in p2.V2_INSTR


def test_prtools4_bridge_skeletons_are_keyed(p4):
    _, lines, _ = p4.bridge("duel me. right now.", p4.Sim())
    assert '<<perform_emote emote="salute">>' in [ln for _, ln in lines]
    _, lines, _ = p4.bridge("bad news... the Saldeans lost their whole barn",
                            p4.Sim())
    assert '<<perform_emote emote="cry">>' in [ln for _, ln in lines]
    sim = p4.Sim()
    sim.settle_duel("bot")
    _, lines, _ = p4.bridge("good fight!", sim)
    assert '<<perform_emote emote="cheer">>' in [ln for _, ln in lines]


def test_ambition_d_spec_expands_to_keyed_lines(amb):
    for _, _, skel, _, _ in amb.D_TURNS:
        if skel is None:
            continue
        lines, want = amb.d_skeletons(skel)
        assert all(re.match(
            r'<<(log_fact|adjust_sentiment|share_gossip|perform_emote)[ >]',
            ln) for ln in lines), skel
        assert want == [s.split()[0] for s in skel.split("+")]
    # the fire check's want must be NAMES (the old code compared the whole
    # spec token against parsed names and could never match an emote)
    lines, want = amb.d_skeletons('share_gossip text="..."+perform_emote emote="cheer"')
    assert lines == ['<<share_gossip text="...">>',
                     '<<perform_emote emote="cheer">>']
    assert want == ["share_gossip", "perform_emote"]


def test_ambition_fill_check_is_per_row_not_blob_length(amb):
    # the target call's OWN fill field against the row's OWN regex: a
    # correct fill passes, an off-topic fill of any length fails, and a
    # missing target call fails (the old check passed on json-blob length)
    keg = [{"name": "adjust_sentiment", "fields": {"reason": "a keg of thunderbrew"}}]
    off = [{"name": "adjust_sentiment", "fields":
            {"reason": "he said a thing about the weather and the fields"}}]
    missing = [{"name": "perform_emote", "fields": {"emote": "cheer"}}]
    skel = 'adjust_sentiment reason="..."'
    assert amb.d_fill_ok(keg, skel, r"(keg|brew|thunderbrew|gift)")
    assert not amb.d_fill_ok(off, skel, r"(keg|brew|thunderbrew|gift)")
    assert not amb.d_fill_ok(missing, skel, r"(keg|brew|thunderbrew|gift)")
    combo = [{"name": "share_gossip", "fields": {"text": "daevin reached level 31"}},
             {"name": "perform_emote", "fields": {"emote": "cheer"}}]
    assert amb.d_fill_ok(combo,
                         'share_gossip text="..."+perform_emote emote="cheer"',
                         r"(level|31)")


def test_tricks_tool_turns_are_keyed_and_parse(tricks, p3):
    """tricks.TOOL_TURNS feeds final.py's calibration slice - its bare
    skeletons were model-facing in the gate path."""
    for tname, _, skel, _ in tricks.TOOL_TURNS:
        calls = calls_of(p3, skel)
        assert tname in [c["name"] for c in calls], tname
        for ln in skel.split("\n"):
            if "perform_emote" in ln:
                assert re.search(r'<<perform_emote emote="', ln), ln


def test_ascii_semantics_match_the_c_locale(p3):
    # a NBSP is not isspace in the C locale: the name glues exactly as the
    # shipped scanner does, instead of splitting into name + fields
    c = calls_of(p3, "<<log_fact\xa0text=\"x\">>")[0]
    assert c["fields"] == {} and not c["resolves"]
    # ...and the quoted-value CLOSE check is C-locale too: an inner quote
    # followed by a unicode space does NOT close the value
    c = calls_of(p3, "<<log_fact text=\"a\"\xa0b\" k=v>>")[0]
    assert c["fields"].get("text") == "a\"\xa0b" and c["fields"].get("k") == "v"
    # unicode case folding (KELVIN SIGN) must not mint a whitelisted name
    assert p3.resolve_emote("\u212aiss") is None


def test_cleaned_trims_trailing_only_like_the_cxx(p3):
    assert p3.parse("  Aye.")[2] == "  Aye."      # leading whitespace stays
    assert p3.parse("Aye.\n")[2] == "Aye."        # trailing newline pops
    assert p3.parse("Aye. ")[2] == "Aye."         # trailing space pops
    assert p3.parse("Aye.\t")[2] == "Aye.\t"      # tabs are NOT popped (C++ pops \n and space only)


def test_doubled_line_is_charged_and_repairable(p3):
    """Production queues EVERY licensed call (a doubled emote plays twice);
    the format gate charges duplicates AND offers the repair pass."""
    calls = calls_of(p3, '<<perform_emote emote="laugh">>\n'
                         '<<perform_emote emote="laugh">>')
    r = p3.check_case("f4-joke", ["perform_emote"], {"perform_emote": r"laugh"},
                      lambda t: True, calls, "Ha!")
    assert not r["fire"] and any("dup" in w for w in r["why"])
    assert p3.needs_repair(calls, ["perform_emote"], {"perform_emote": r"laugh"})


def test_bare_tone_extra_sentiment_parity(p3, p4):
    """The F6 mood-tag mirror must render the SAME verdict in both
    scorers: a bare-tone extra adjust_sentiment is dropped by the bridge
    (forgiven); any real reason is charged as an extra."""
    for reason, forgiven in (("gratitude", True), ("he was kind", False)):
        calls = p3.parse(
            '<<perform_emote emote="cheer">>\n'
            f'<<adjust_sentiment reason="{reason}">>')[0]
        r3 = p3.check_case("f4-joke", ["perform_emote"],
                           {"perform_emote": r"cheer"},
                           lambda t: True, calls, "Ha!")
        r4 = p4.check_turn(["perform_emote"], {"perform_emote": r"cheer"},
                           lambda t: True, [], calls, "Ha!")
        c3 = any("extra adjust_sentiment" in w for w in r3["why"])
        c4 = any("extra adjust_sentiment" in w for w in r4["why"])
        assert c3 == c4 == (not forgiven), reason


def test_continuation_grammar_separator_is_not_a_literal_plus(p3):
    """A quoted "\n+" in GBNF is a literal plus after one newline; the
    chain separator must be ("\n" "\n"*)."""
    g = p3.cont_grammar([("won", '<<log_fact text="...">>'),
                        ("w-cheer", '<<perform_emote emote="cheer">>')])
    assert '"\\n"' in g and '"\\n" "\\n"*' in g
    assert '"\\n+"' not in g and '"+"' not in g
    g2 = p3.grammar_for([("joke", '<<perform_emote emote="laugh">>')])
    assert '"\\n+"' not in g2 and '"+"' not in g2


def test_no_bare_form_teaching_remains():
    if not HARNESS.is_dir():
        pytest.skip("bench harness tree (G:\\NPU LLM) not present")
    bare = re.compile(r"<<\s*perform_emote\s+[a-z_]+>>")
    for fn in CONVERTED:
        assert not bare.search(
            (HARNESS / fn).read_text(encoding="utf-8")), fn
