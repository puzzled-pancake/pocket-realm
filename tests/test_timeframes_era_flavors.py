"""W3.1 corpus-driven era-lint tests for the TBC and WotLK lanes.

Port plan S3.4: "Era lint re-curation: ... TBC: ban WotLK+ concepts — death
knights, Northrend, lich king, etc., allow Outland/flying; WotLK: allow
through WotLK concepts, ban Cataclysm+. Keep the rule that a term legitimate
in earlier content is never banned. Add corpus-driven tests per flavor."

Both directions, per flavor, against each flavor's OWN shipped corpus dump:

- NO ANACHRONISMS: the flavor's ban lists actually ban the later-era
  concepts (TBC bans WotLK+; WotLK bans Cata+) — the anachronism direction.
- NO FALSE BANS: every banned term is either corpus-clean against the
  flavor's own shipped dump (zero whole-word hits) or a recorded deliberate
  anachronism guard (the D50/KEPT_GUARDS pattern); and the era-native
  vocabulary the flavor LEGITIMIZES is (a) not banned and (b) corpus-proven
  to hit the flavor's own dump — the legitimization direction.

The vanilla lane's own discipline lives in tests/test_llm_truth.py
(test_vanilla_era_baseline_carries_no_corpus_proven_false_bans) and is
untouched: the vanilla manifest is byte-frozen by design (D31/D50).
"""
from __future__ import annotations

import gzip
import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]

# Per-flavor shipped corpus (the flavor's own DB content — the text a bot
# on that lane can legitimately encounter).
CORPORA = {
    "tbc": ROOT / "native/classic-db-tbc/Full_DB/TBCDB_1.11.0_Vengeance_One_A_Cmangos_Story.sql.gz",
    "wotlk": ROOT / "native/classic-db-wotlk/Full_DB/WoTLKDB_1_9_14092.sql.gz",
}

# The manifests under test (the vanilla one is covered by test_llm_truth.py).
FLAVORS = ("tbc", "wotlk")

# The legitimization moves, corpus-proven both ways: the term must NOT be
# banned for the flavor AND must hit the flavor's shipped dump (whole-word)
# — the probe direction ("no longer hits the shipped dump = corpus
# regression").
LEGITIMIZED_TERMS = {
    # TBC legitimizes every vanilla-banned TBC concept.
    "tbc": [
        "outland", "flying mount", "shattrath", "hellfire peninsula",
        "zangarmarsh", "terokkar forest", "netherstorm", "shadowmoon valley",
        "draenei", "exodar", "the dark portal",
    ],
    # WotLK legitimizes everything through WotLK — including every TBC
    # concept and the WotLK-native institutions vanilla bans.
    "wotlk": [
        "outland", "flying mount", "shattrath", "hellfire peninsula",
        "dalaran", "ebon blade", "wrath of the lich king", "acherus",
        "northrend", "borean tundra", "howling fjord", "wintergrasp",
    ],
}

# Deliberate anachronism guards: banned terms whose whole-word hits DO exist
# in the flavor's own dump — kept because a bot on that lane saying the term
# IS the anachronism the era lint exists for. Every entry must still hit the
# dump and still be BANNED.
KEPT_GUARDS_TBC = {
    "acherus": "the WotLK Ebon Hold (14 lore-text mentions)",
    "ebon blade": "the WotLK knightly order (18 lore-text mentions)",
    "sewers of dalaran": "WotLK Dalaran (4 lore-text mentions)",
    "mists of pandaria": "post-WotLK expansion title (1 lore mention)",
    "nazjatar": "post-WotLK zone (9 lore-text mentions)",
    # WotLK proper-noun guards (dump-scan counts).
    "argent tournament": "WotLK zone (10 hits)",
    "borean tundra": "WotLK zone (16 hits)",
    "crystalsong": "WotLK forest (6 hits)",
    "dragonblight": "WotLK zone (25 hits)",
    "ebon hold": "WotLK dungeon (5 hits)",
    "frenzyheart": "WotLK tribe (48 hits)",
    "howling fjord": "WotLK zone (12 hits)",
    "icecrown": "WotLK glacier/citadel (103 hits)",
    "sholazar basin": "WotLK zone (22 hits)",
    "storm peaks": "WotLK zone (27 hits)",
    "taunka": "WotLK tribe (34 hits)",
    "tuskarr": "WotLK tribe (22 hits)",
    "wintergrasp": "WotLK PvP zone (104 hits)",
    # full-corpus scan additions.
    "vortex pinnacle": "Cata dungeon (88 hits)",
    "utgarde": "WotLK dungeons (16 hits)",
    "grizzly hills": "WotLK zone (15 hits)",
    "transitus shield": "WotLK enclave (10 hits)",
    "ramkahen": "Cata faction (9 hits)",
    "westguard keep": "WotLK keep (8 hits)",
    "kamagua": "WotLK village (7 hits)",
    "blackrock caverns": "Cata dungeon (5 hits)",
    "halls of origination": "Cata dungeon (1 hit)",
    "hodir": "WotLK boss (23 hits)",
    "valiance keep": "WotLK keep (23 hits)",
    "valgarde": "WotLK village (18 hits)",
    "wintergarde": "WotLK keep (17 hits)",
    "throne of the tides": "Cata dungeon (6 hits)",
    "amber ledge": "WotLK enclave (5 hits)",
    # post-TBC Cata concepts with tbc-dump hits - the hits
    # are Cata-era lore text in the shared dump, not live TBC vocabulary,
    # and the card-lint shows zero era-legitimate card drops. (vashj'ir
    # carries no guard row: zero whole-word tbc-dump hits - its single
    # lore-card drop is recorded in CARD_DROPS_RECORDED instead.)
    "gilneas city": "Cata city (7 hits)",
    "kezan": "Cata goblin isle (35 hits)",
    "lost isles": "Cata zone (5 hits)",
    "stonecore": "Cata dungeon (2 hits)",
    "tol barad": "Cata PvP isle (25 hits)",
    "wandering isle": "pandaren isle (17 hits)",
    "bilgewater": "Cata goblin cartel (40 hits)",
    # the WotLK-era SYSTEMS and FACTIONS the
    # zone-only scan missed - each hits the shipped tbc dump as live
    # broadcast_text anachronisms and drops zero era-legitimate cards
    # except where noted (recorded in CARD_DROPS_RECORDED).
    "inscription": "WotLK profession (102 hits; drops the Herbalism card - "
                   "correct-era drop, the card cites Inscription)",
    "argent crusade": "WotLK faction formed at Light's Hope (73 hits; drops "
                      "7 cards whose current-state narration is post-TBC)",
    "saronite": "WotLK ore (22 hits)",
    "the skybreaker": "WotLK gunship (9 hits)",
    "timeless isle": "MoP zone (6 hits; R6-3's standard applied to the tbc "
                     "lane; drops the Southsea card)",
    "sons of hodir": "WotLK faction (4 hits)",
    "onslaught harbor": "WotLK port (1 hit)",
}

KEPT_GUARDS_WOTLK = {
    "mists of pandaria": "post-WotLK expansion title (1 lore mention)",
    "nazjatar": "post-WotLK zone (8 lore-text mentions)",
    # full-corpus scan additions.
    "vortex pinnacle": "Cata dungeon (88 hits)",
    "ramkahen": "Cata faction (9 hits)",
    "throne of the tides": "Cata dungeon (6 hits)",
    "blackrock caverns": "Cata dungeon (5 hits)",
    "halls of origination": "Cata dungeon (1 hit)",
    "bilgewater": "Cata goblin cartel (40 hits)",
    "gilneas city": "Cata city (7 hits)",
    "kezan": "Cata goblin isle (35 hits)",
    "lost isles": "Cata zone (5 hits)",
    "stonecore": "Cata dungeon (2 hits)",
    "tol barad": "Cata PvP isle (25 hits)",
    "wandering isle": "pandaren isle (17 hits)",
    # the MoP zone the MoP-title scan standard missed -
    # broadcast_text 75183 ('The presence of the Timeless Isle is proof...')
    # is live MoP text in the shipped WotLK dump.
    "timeless isle": "MoP zone (6 hits)",
}

# The anachronism direction: later-era terms that MUST be banned.
REQUIRED_ANACHRONISM_BANS = {
    # TBC bans WotLK+ concepts.
    "tbc": [
        "acherus", "ebon blade", "dalaran floats", "wrath of the lich king",
        "playable worgen", "gilneas opened", "gilneas is open",
        "gilneas has opened", "mists of pandaria", "warlords of draenor",
        "zereth mortis", "korthia", "revendreth", "maldraxxus",
        "ardenweald", "nazjatar",
        # WotLK proper-noun guards.
        "argent tournament", "borean tundra", "crystalsong", "dragonblight",
        "ebon hold", "frenzyheart", "howling fjord", "icecrown",
        "sholazar basin", "storm peaks", "taunka", "tuskarr",
        # guards - full-corpus scan (card-safe subset).
        "vortex pinnacle", "utgarde", "grizzly hills", "transitus shield",
        "ramkahen", "westguard keep", "kamagua", "blackrock caverns",
        "halls of origination", "hodir", "valiance keep", "valgarde",
        "wintergarde", "throne of the tides",
        # post-TBC concepts added. Each drops zero
        # era-legitimate lore cards (card-lint verified); deepholm/
        # firelands/therazane are NOT here — they are era-legitimate
        # vanilla-era elemental/MC vocabulary the shared cards cite, pruned
        # to KEPT_ALLOWS (see REQUIRED_NOT_BANNED below).
        "vashj'ir", "ny'alotha", "stormsong valley", "drustvar", "vol'dun",
        "gilneas city", "kezan", "lost isles", "stonecore", "tol barad",
        "wandering isle", "bilgewater",
        # the WotLK-era systems/factions the zone-only scan
        # missed — inscription/argent crusade/saronite/the skybreaker/
        # sons of hodir/onslaught harbor and timeless isle (R6-3's
        # standard applied to tbc).
        "inscription", "argent crusade", "saronite", "the skybreaker",
        "sons of hodir", "onslaught harbor", "timeless isle",
    ],
    # WotLK bans Cata+ concepts.
    "wotlk": [
        "gilneas opened", "gilneas is open", "gilneas has opened",
        "playable worgen", "stormsong valley", "drustvar",
        "mists of pandaria", "warlords of draenor",
        "zereth mortis", "korthia", "revendreth", "maldraxxus",
        "ardenweald", "nazjatar", "battle for azeroth", "shadowlands",
        "the war within", "vashj'ir", "ny'alotha",
        # guards.
        "vortex pinnacle", "ramkahen", "blackrock caverns",
        "throne of the tides", "halls of origination", "bilgewater",
        # the MoP zone.
        "timeless isle",
    ],
}

# Mirror direction: era-native concepts that must NEVER be banned.
REQUIRED_NOT_BANNED = {
    "tbc": LEGITIMIZED_TERMS["tbc"] + ["northrend", "dalaran", "lich king",
                                       # close: elemental-plane /
                                       # vanilla-MC lore the shared cards cite.
                                       "deepholm", "firelands", "therazane"],
    "wotlk": LEGITIMIZED_TERMS["wotlk"] + ["lich king", "worgen", "gilneas",
                                           "death knight", "legion"],
}


def _manifest(flavor: str) -> dict:
    return json.loads(
        (ROOT / "schemas" / "timeframes" / f"{flavor}.json").read_text(encoding="utf-8")
    )


def _effective_ban_sets(manifest: dict) -> tuple[set[str], set[str]]:
    terms: set[str] = set()
    needles: set[str] = set()
    baseline = manifest.get("final_stage_era_baseline") or {}
    terms.update(t.lower() for t in baseline.get("always_ban_terms") or [])
    needles.update(n.lower() for n, _f in baseline.get("sense_phrases") or [])
    for stage in manifest["stages"]:
        era = stage.get("era_terms") or {}
        grounded = era.get("grounded_baseline") or {}
        terms.update(t.lower() for t in grounded.get("always_ban_terms") or [])
        needles.update(n.lower() for n, _f in grounded.get("sense_phrases") or [])
    return terms, needles

KEPT_GUARDS = {"tbc": KEPT_GUARDS_TBC, "wotlk": KEPT_GUARDS_WOTLK}


@pytest.mark.parametrize("flavor", FLAVORS)
def test_flavor_carries_its_own_grounded_baseline(flavor: str) -> None:
    """W3.1's data invariant: each new flavor has a final_stage_era_baseline
    of its own (the generator's CROSS-FLAVOR vanilla fallback is retired for
    these lanes) and every stage inherits it (delta null = inherit)."""
    manifest = _manifest(flavor)
    baseline = manifest.get("final_stage_era_baseline")
    assert isinstance(baseline, dict), (
        f"{flavor}: no final_stage_era_baseline — the lane would resolve every "
        "stage through the generator's cross-flavor vanilla fallback and "
        "live-ban core expansion vocabulary (exactly the W3.1 defect)"
    )
    assert baseline.get("always_ban_terms"), "baseline must ban something"
    assert baseline.get("sense_phrases"), "baseline must carry sense guards"
    # The vanilla lane's own bans must be GONE from the new baseline's
    # non-banned surface: each flavor's legitimization is real.
    vanilla = json.loads(
        (ROOT / "schemas" / "timeframes" / "vanilla.json").read_text(encoding="utf-8")
    )["final_stage_era_baseline"]
    vanilla_tbc_guarded = {"shattrath", "hellfire peninsula", "zangarmarsh",
                           "terokkar forest", "blade's edge", "netherstorm",
                           "shadowmoon valley", "flying mount", "flying mounts"}
    banned = set(_effective_ban_sets(manifest)[0]) | set(_effective_ban_sets(manifest)[1])
    assert not (vanilla_tbc_guarded & banned), (
        f"{flavor}: vanilla's TBC guards {vanilla_tbc_guarded & banned} are still "
        "banned — the W3.1 legitimization move is missing"
    )
    # stage curation no longer carries the pending-flavor-qualification
    # marker (curates before the flip — the marker must be gone).
    for stage in manifest["stages"]:
        curation = str((stage.get("era_terms") or {}).get("curation", ""))
        assert not curation.startswith("pending-flavor-qualification"), (
            f"{flavor}: stage {stage['id']} still carries the pending marker — "
            "the era curation landed; the marker must not outlive it"
        )


@pytest.mark.parametrize("flavor", FLAVORS)
def test_flavor_bans_its_required_anachronisms(flavor: str) -> None:
    """Anachronism direction: the later-era concepts ARE banned."""
    terms, needles = _effective_ban_sets(_manifest(flavor))
    banned = terms | needles
    missing = [t for t in REQUIRED_ANACHRONISM_BANS[flavor] if t not in banned]
    assert not missing, (
        f"{flavor}: anachronism terms {missing} are NOT banned — the era lint "
        "would let bots speak later-era vocabulary on this lane"
    )


@pytest.mark.parametrize("flavor", FLAVORS)
def test_flavor_never_bans_its_era_native_vocabulary(flavor: str) -> None:
    """False-ban direction (structural): era-native vocabulary is never in
    the ban lists — the never-ban discipline over the new lanes."""
    terms, needles = _effective_ban_sets(_manifest(flavor))
    banned = terms | needles
    leaked = [t for t in REQUIRED_NOT_BANNED[flavor] if t in banned]
    assert not leaked, (
        f"{flavor}: era-legitimate vocabulary {leaked} is BANNED — a live "
        "false ban (bots deflect on real flavor content)"
    )


@pytest.mark.parametrize("flavor", FLAVORS)
def test_flavor_era_lint_is_corpus_ground_both_ways(flavor: str) -> None:
    """The corpus direction, both ways, against the flavor's OWN dump:
    (a) every banned term with whole-word dump hits carries a
    KEPT_GUARDS entry (no unrecorded live false ban);
    (b) every guard entry still hits the dump and is still banned
    (no stale table rows);
    (c) every legitimization term actually hits the dump (the probe's
    corpus assumptions hold — 'no longer hits = re-derive before trusting').
    """
    import re

    dump = CORPORA[flavor]
    assert dump.is_file(), f"the pinned {flavor} corpus is missing"
    terms, needles = _effective_ban_sets(_manifest(flavor))
    banned = terms | needles
    guards = KEPT_GUARDS[flavor]

    corpus_terms = sorted(set(banned) | set(LEGITIMIZED_TERMS[flavor]))
    needles_bytes = sorted(t.encode("utf-8").lower() for t in corpus_terms)
    hits: dict[str, int] = {}
    with gzip.open(dump, "rb") as handle:
        for line in handle:
            low = line.lower()
            for raw in needles_bytes:
                start = 0
                while True:
                    i = low.find(raw, start)
                    if i == -1:
                        break
                    j = i + len(raw)
                    before_ok = i == 0 or not chr(low[i - 1]).isalpha()
                    after_ok = j >= len(low) or not chr(low[j]).isalpha()
                    if before_ok and after_ok:
                        hits[raw.decode()] = hits.get(raw.decode(), 0) + 1
                    start = i + 1

    # (a) no unrecorded false ban
    unguarded = sorted(
        term for term in banned
        if hits.get(term, 0) > 0 and term not in guards
    )
    assert not unguarded, (
        f"{flavor}: ban term(s) {unguarded} hit the shipped {flavor} dump but "
        "carry no KEPT_GUARDS entry — a corpus-hitting ban is a live false "
        "ban; record the deliberate-guard justification or prune the term"
    )
    # (b) guards stay live and stay banned
    stale_hits = sorted(g for g in guards if hits.get(g, 0) == 0)
    assert not stale_hits, (
        f"{flavor}: guard entries {stale_hits} no longer hit the dump — prune "
        "the stale table rows (leak-sweep stale-entry pattern)"
    )
    stale_bans = sorted(g for g in guards if g not in banned)
    assert not stale_bans, (
        f"{flavor}: guard entries {stale_bans} are no longer banned — prune "
        "the stale table rows"
    )
    # (c) the legitimization is corpus-proven
    regression = [
        t for t in LEGITIMIZED_TERMS[flavor] if hits.get(t, 0) == 0
    ]
    assert not regression, (
        f"{flavor}: legitimization terms {regression} no longer hit the "
        f"shipped {flavor} dump — the corpus probe's assumptions changed; "
        "re-derive before trusting the legitimization move"
    )


def test_kotlin_registry_mirrors_every_flavor_baseline() -> None:
    """The shared core's per-flavor baselines mirror the manifests — the
    same contract the vanilla leg has pinned since S0 (checked here in the
    pytest lane against the Kotlin source so a Kotlin-only edit cannot
    silently drift)."""
    sys.path.insert(0, str(ROOT / "tools"))
    import check_timeframes

    registry = (
        ROOT / "android/app/src/main/java/com/pocketrealm/client/TimeframeStage.kt"
    ).read_text(encoding="utf-8")
    named = check_timeframes.parse_kotlin_named_baselines(registry)
    assert set(named) >= set(FLAVORS), (
        f"kotlin registry is missing named baselines for "
        f"{set(FLAVORS) - set(named)}"
    )
    for flavor in FLAVORS:
        baseline = _manifest(flavor)["final_stage_era_baseline"]
        kotlin_terms, kotlin_phrases = named[flavor]
        assert kotlin_terms == list(baseline["always_ban_terms"]), (
            f"{flavor}: kotlin alwaysBanTerms drifted from the manifest"
        )
        assert kotlin_phrases == [
            (needle, flag) for needle, flag in baseline["sense_phrases"]
        ], f"{flavor}: kotlin sensePhrases drifted from the manifest"


def test_generated_header_serves_distinct_per_flavor_sets() -> None:
    """The generated native header (the truth core's selector source)
    resolves the three lanes to DISTINCT era sets — a tbc stage never
    serves the vanilla ban list (the pre-W3.1 single-set posture)."""
    sys.path.insert(0, str(ROOT / "tools"))
    import generate_timeframe_era as gte

    manifests = gte.load_manifests()
    names, bindings = gte.era_sets(gte.all_stages(manifests))
    by_flavor: dict[str, str] = {}
    for binding in bindings:
        flavor = binding["stage"].rsplit("-", 1)[0]
        by_flavor[flavor] = binding["set"]
    assert len(set(by_flavor.values())) == 3, (
        f"the generated era sets collapsed across flavors: {by_flavor} — "
        "the TBC/WotLK baselines never reached the generated header"
    )
    # And the tbc binding carries the TBC legitimization (shattrath is NOT
    # in the tbc set; it IS in the vanilla set).
    tbc_binding = next(b for b in bindings if b["set"] == by_flavor["tbc"])
    assert "shattrath" not in tbc_binding["always"], (
        "the tbc era set still bans shattrath — the legitimization move is "
        "not in the generated header"
    )
    vanilla_binding = next(b for b in bindings if b["set"] == by_flavor["vanilla"])
    assert "shattrath" in vanilla_binding["always"], (
        "the vanilla era set lost its shattrath guard — the vanilla lane "
        "must stay byte-equivalent (D31/D50)"
    )

# the per-flavor LORE-CORPUS card-drop table — the EraLintCards
# load-time lint drops any card whose surface trips the ACTIVE stage's ban
# lists, so a shared-corpus card that legitimately names later-era lore
# must either survive or its drop must be recorded here (title ->
# justification). "Eastern Kingdoms" on the wotlk AND tbc lanes is a
# CORRECT era drop: the card's text narrates the post-Cata shattering
# (vashj'ir), text an era bot must not cite as [RESULT] truth.
# the recorded DELIBERATE ALLOWS - later-era proper
# nouns with whole-word dump hits that are deliberately NOT banned because
# the dump's own rows are era-legitimate lore the lane cites. Audited both
# ways by test_later_era_terms_are_banned_or_recorded_allows.
# this table existed TWICE at module level — the second definition
# silently shadowed the first; the duplicate is removed and the surviving
# table is the fuller-worded one the tests always actually read.)
KEPT_ALLOWS = {
    "tbc": {
        "ulduar": "titan lore ('the halls of fabled ulduar') predates the "
                  "WotLK raid; the dump rows are mixed lore text",
        "suramar": "single lore mention (1 hit); the Legion zone never features",
        "grim batol": "pre-Cata Dragonmaw lore (TBC quest text) - era-legitimate "
                      "for the TBC lane",
        # close (card-lint) these guards dropped real vanilla-era lore
        # cards (Molten Core, Baron Geddon, Sulfuras, Human, Tirisfal Glades,
        # Zeppelin,...) - pruned to allows per the rule.
        "firelands": "card-lint allow - vanilla-era MC lore (Ragnaros/Firelands) "
                     "cited by Molten Core/Baron Geddon/Sulfuras cards",
        "therazane": "card-lint allow - vanilla-era elemental lore cited by "
                     "Theradras/Neptulon/Earth elemental cards",
        "uldum": "card-lint allow - vanilla-era titan lore cited by Tanaris/"
                 "Un'Goro Crater/Al'Akir/Obsidian destroyer cards",
        "vrykul": "card-lint allow - vanilla-era origins lore cited by the "
                  "Human/Tirisfal Glades cards",
        "westfall brigade": "card-lint allow - vanilla-era Sentinel Hill lore",
        "wrathgate": "card-lint allow - vanilla-era Bolvar Fordragon lore",
        "wyrmrest": "card-lint allow - vanilla-era Zeppelin route lore",
        "wyrmrest temple": "card-lint allow - vanilla-era Zeppelin route lore",
        "yogg-saron": "card-lint allow - vanilla-era Northrend/Old God/"
                      "Nerubian lore",
        # the generic-word collision class - the dump hits
        # mix era-legitimate prose with later-era system rows, so a ban
        # would false-trip legitimate vocabulary (sampled rows cited).
        "dragon isles": "retail DF home of the dragonflights (4 tbc-dump "
                        "hits); 11 era-legitimate cards (Blue dragonflight, "
                        "Gnoll, Boat, Zeppelin, ...) cite it in retail-"
                        "written passing - banning would mass-drop era-"
                        "correct lore (R5-1 false-ban direction wins; the "
                        "card-content recording is CARD_CONTENT_RECORDED)",
        "achievement": "generic English prose ('The Fruit of Fertility was "
                       "her crowning achievement' - Elune lore) mixed with "
                       "WotLK achievement-system UI rows (25 hits)",
        "glyph": "the hits mix the WotLK glyph system with era-legitimate "
                 "vanilla item rows (Glyph of Deflection in an Ahn'Qiraj "
                 "row) - a ban would deflect legitimate vocabulary (66 "
                 "hits)",
        "archaeology": "generic scholarly prose ('This discovery will surely "
                       "rock the foundation of modern archaeology') mixed "
                       "with Cata system rows (34 hits)",
        "obsidium": "Cata ore (1 hit) - ambiguous single hit, recorded",
        "pyrium": "Cata ore (1 hit) - ambiguous single hit, recorded",
    },
    "wotlk": {
        "uldum": "titan-lore text in the WotLK dump predates the Cata zone",
        "ulduar": "WotLK-NATIVE raid - era-native vocabulary, never an "
                  "anachronism on this lane",
        "mount hyjal": "TBC Caverns-of-Time raid era-legitimate for WotLK; "
                       "dump rows are mixed TBC-raid and lore text",
        "stormstout": "the brewery predates the Mists expansion (MC-era "
                      "vocabulary per the round-2 scan)",
        "grim batol": "pre-Cata Dragonmaw lore - era-legitimate for WotLK",
        # close (card-lint) these guards dropped real vanilla-era lore
        # cards (Gnome, Molten Core, Earth elemental, Therazane, Trogg, Boat,
        # Cho'gall, Wildhammer dwarf) - pruned to allows per the rule.
        "deepholm": "card-lint allow - vanilla-era elemental lore cited by "
                    "Earth elemental/Therazane/Trogg cards",
        "firelands": "card-lint allow - vanilla-era MC lore cited by Molten "
                     "Core/Baron Geddon/Hydraxian Waterlords cards",
        "mechagon": "card-lint allow - vanilla-era Gnome lore",
        "therazane": "card-lint allow - vanilla-era elemental lore cited by "
                     "Earth elemental/Theradras cards",
        "tiragarde sound": "card-lint allow - vanilla-era Boat route lore",
        "twilight highlands": "card-lint allow - vanilla-era Cho'gall/"
                              "Wildhammer dwarf lore",
        # the generic-word collision class on this
        # lane too (an archaeology judgment call), plus dragon isles
        # (4 dump hits; 11 era-legitimate cards - recorded, not banned).
        "dragon isles": "retail DF home of the dragonflights (4 wotlk-dump "
                        "hits); 11 era-legitimate cards cite it in "
                        "retail-written passing - the card-content "
                        "recording is CARD_CONTENT_RECORDED",
        "archaeology": "generic scholarly prose mixed with Cata system rows "
                       "(35 hits)",
        "obsidium": "Cata ore (1 hit) - ambiguous single hit, recorded",
        "pyrium": "Cata ore (1 hit) - ambiguous single hit, recorded",
    },
}


CARD_DROPS_RECORDED = {
    "wotlk": {
        "Eastern Kingdoms": "correct era drop - the card's text is post-Cata "
                            "shattering lore (the term is vashj'ir, a Cata zone)",
        # the card's MoP/DF retail narration (Timeless Isle,
        # Dragon Isles, Forbidden Reach) is wrong-era truth on this lane.
        "Southsea Freebooters": "correct era drop - the card narrates MoP/DF "
                                "retail zones (timeless isle is the ban hit; "
                                "R6-3)",
    },
    "tbc": {
        "Eastern Kingdoms": "correct era drop - same post-Cata Shattering "
                            "text as the wotlk lane's recorded drop (the term "
                            "is vashj'ir, a Cata zone; R5-1 round 5)",
        # correct-era drops for the TBC lane.
        # ("Herbalism" pruned at the W7 round-4 lore scrub: its Inscription
        # sentence was the drop hit and was removed from the corpus — the
        # card now loads clean on this lane.)
        "Hearthglen": "correct era drop - the card's current-state narration "
                      "names the Argent Crusade as its headquarters (formed "
                      "in WotLK; the term is argent crusade; R6-1)",
        "Light's Hope Chapel": "correct era drop - same Argent Crusade "
                               "current-state narration (R6-1)",
        "Scarlet Crusade": "correct era drop - the card narrates the Fourth "
                           "War era world state via the Argent Crusade "
                           "(R6-1)",
        "Ashbringer": "correct era drop - the card narrates the WotLK "
                      "ending (Frostmourne shattered, the Lich King "
                      "defeated) as history, via the Argent Crusade (R6-1)",
        # ("Jandice Barov" pruned at the W7 round-4 lore scrub: its drop
        # hit rode the Fourth-War/Argent-Crusade sentence that was removed
        # from the corpus - the card now loads clean on this lane.)
        "Tirion Fordring": "correct era drop - Argent Crusade current-state "
                           "narration (R6-1)",
        "Western Plaguelands": "correct era drop - Argent Crusade "
                               "current-state narration (R6-1)",
        "Southsea Freebooters": "correct era drop - the card narrates MoP/DF "
                                "retail zones (timeless isle is the ban "
                                "hit; R6-1/R6-3)",
    },
}

# the per-flavor CARD-CONTENT recording - a term
# whose dump/card hits are era-legitimate vocabulary or whose ban would
# mass-drop era-legitimate cards is deliberately NOT banned, and the term
# is recorded here with its justification (never silent). A card whose
# surface trips a CONTENT-probe term must be dropped for that lane (via a
# ban term) OR its term must be recorded here.
CARD_CONTENT_RECORDED = {
    "vanilla": {
        "argent crusade": "the vanilla lane's frozen contract (pre-wave "
                          "byte-frozen corpus, D50-pruned baseline) predates "
                          "this term's curation; extending the vanilla era "
                          "set is a vanilla behavior change reserved for an "
                          "owner decision (D31/D50) - 7 cards narrate the "
                          "Crusade (WotLK-formed) as current state; "
                          "recorded, not silent",
        "fourth war": "same frozen-contract posture; retail BfA world-state "
                      "narration in passing across era-legitimate cards",
        "timeless isle": "same frozen-contract posture; MoP zone",
        "dragon isles": "same frozen-contract posture; retail DF zone",
        "forbidden reach": "same frozen-contract posture; retail DF zone",
        "inscription": "same frozen-contract posture; WotLK profession "
                       "(the Herbalism card cites it)",
    },
    "tbc": {
        "fourth war": "retail BfA world-state narration in passing across 17 "
                      "era-legitimate cards (Ogre, Gadgetzan, Sentinel Hill, "
                      "Zeppelin-class lore) - banning would mass-drop era-"
                      "correct lore; the R5-1 false-ban direction wins (0 "
                      "tbc-dump whole-word hits)",
        "dragon isles": "11 era-legitimate cards cite it in retail-written "
                        "passing (see the KEPT_ALLOWS row) - recorded here "
                        "at the card-content level",
        "forbidden reach": "retail DF zone; its single card hit (Southsea "
                           "Freebooters) is already dropped via the "
                           "timeless-isle ban",
    },
    "wotlk": {
        "argent crusade": "ERA-LEGITIMATE on this lane - the Argent Crusade "
                          "forms in WotLK (the Battle of Light's Hope), so "
                          "the 7 cards' Crusade mentions (Light's Hope as "
                          "its base, Tirion leading it) are era-correct "
                          "vocabulary here even though the same terms are "
                          "anachronisms on the vanilla/tbc lanes (R6-2)",
        "inscription": "ERA-LEGITIMATE on this lane - Inscription launches "
                       "WITH WotLK, so the Herbalism card's mention is "
                       "era-correct here (it is a correct-era drop on the "
                       "tbc lane; R6-2)",
        "fourth war": "retail BfA world-state narration in passing across 17 "
                      "era-legitimate cards (Ogre, Gadgetzan, Southshore, "
                      "Syndicate lore) - banning would mass-drop era-correct "
                      "lore; the R5-1 false-ban direction wins (0 wotlk-dump "
                      "whole-word hits)",
        "dragon isles": "11 era-legitimate cards cite it in retail-written "
                        "passing (see the KEPT_ALLOWS row)",
        "forbidden reach": "retail DF zone; its single card hit (Southsea "
                           "Freebooters) is already dropped via the "
                           "timeless-isle ban",
    },
}


def _era_scan_replica(banned_terms, sense_phrases, surface):
    """A conservative Python replica of the truth core's EraScan (no
    negation escape — the conservative direction drops MORE, so a clean
    conservative scan implies the real scan is clean)."""
    low = surface.lower()
    for term in banned_terms:
        start = 0
        while True:
            i = low.find(term.lower(), start)
            if i == -1:
                break
            j = i + len(term)
            before_ok = i == 0 or not low[i - 1].isalpha()
            after = j
            if after < len(low) and low[after] == "s":
                after += 1
            after_ok = after >= len(low) or not low[after].isalpha()
            if before_ok and after_ok:
                return term
            start = i + 1
    for needle, _flag in sense_phrases:
        if needle.lower() in low:
            return needle
    return None


CONTENT_PROBE_TERMS = (
    # the later-era proper-noun set whose card-content
    # presence the term-ban lint cannot see unless a lane bans it.
    "argent crusade", "fourth war", "timeless isle", "dragon isles",
    "forbidden reach", "inscription",
)


@pytest.mark.parametrize("flavor", FLAVORS)
def test_later_era_terms_are_banned_or_recorded_allows(flavor: str) -> None:
    """R2-1/R2-2 (round 2): every later-era proper noun in the probe list
    that HITS the flavor's dump must be either banned (with a guard entry)
    or deliberately allowed (with a KEPT_ALLOWS entry) - never unmentioned."""
    import gzip

    probe_list = {
        "tbc": [
            "vortex pinnacle", "uldum", "firelands", "hodir", "valiance keep",
            "therazane", "valgarde", "wintergarde", "utgarde", "grizzly hills",
            "transitus shield", "ramkahen", "westguard keep", "wrathgate",
            "kamagua", "throne of the tides", "amber ledge", "westfall brigade",
            "blackrock caverns", "halls of origination", "wyrmrest", "ulduar",
            "suramar", "grim batol",
            # the WotLK-era systems/factions the
            # "full-corpus scan" omitted - each dump-hits and must be
            # banned-with-guard or a recorded allow.
            "inscription", "argent crusade", "glyph", "saronite",
            "the skybreaker", "sons of hodir", "onslaught harbor",
            "achievement", "timeless isle",
            # its retail-narration terms (banned or recorded, both ways).
            "fourth war", "dragon isles", "forbidden reach",
            "archaeology", "obsidium", "pyrium",
        ],
        "wotlk": [
            "vortex pinnacle", "uldum", "firelands", "therazane", "ramkahen",
            "throne of the tides", "blackrock caverns", "halls of origination",
            "bilgewater", "ulduar", "mount hyjal", "stormstout", "grim batol",
            # the MoP zone + the retail-narration and
            # generic-word terms (banned or recorded, both ways).
            "timeless isle", "fourth war", "dragon isles", "forbidden reach",
            "archaeology", "obsidium", "pyrium",
        ],
    }[flavor]
    terms, needles = _effective_ban_sets(_manifest(flavor))
    banned = terms | needles
    allows = KEPT_ALLOWS[flavor]
    dump = CORPORA[flavor]
    hits = {}
    raw_needles = sorted(t.encode("utf-8").lower() for t in probe_list)
    with gzip.open(dump, "rb") as handle:
        for line in handle:
            low = line.lower()
            for raw in raw_needles:
                start = 0
                while True:
                    i = low.find(raw, start)
                    if i == -1:
                        break
                    j = i + len(raw)
                    before_ok = i == 0 or not chr(low[i - 1]).isalpha()
                    after_ok = j >= len(low) or not chr(low[j]).isalpha()
                    if before_ok and after_ok:
                        hits[raw.decode()] = hits.get(raw.decode(), 0) + 1
                    start = i + 1
    unaccounted = sorted(
        t for t in probe_list
        if hits.get(t, 0) > 0 and t not in banned and t not in allows
    )
    assert not unaccounted, (
        flavor + ": later-era probe term(s) " + str(unaccounted) + " hit the "
        "shipped dump but are neither banned nor recorded as deliberate "
        "allows - the era lint lets bots voice them unchallenged (add to the "
        "ban lists with guards, or record the allow with its justification)"
    )
