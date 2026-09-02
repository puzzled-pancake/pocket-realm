# LLM-BANK-AUTHORING-HANDOFF-R3.md — S11 round-5 PROGRESS handoff (partial session)

Written 2026-09-01. The bank-authoring session ran pre-flight through the
P50 merge+gates CLEAN, then the P51 wave-A drafter dispatch (6 agents, one
message) failed to spawn — "off-peak-ticket-expired" on all six — and the
owner ended the session for handoff to an off-peak continuation.

**The next agent is the SAME author (S11 bank-authoring, R2 brief rules).
Read, in order: this file, then `LLM-BANK-AUTHORING-BRIEF-R2.md` (the
plan; only its §5 was superseded by the spawned-agent directive), then
`LLM-BANK-AUTHORING-BRIEF.md` (binding laws), then §1 below for exactly
where the line stopped.** Everything in R2 §1 "state already on disk"
still stands (nonce pool, SPEC_common, P45 skeleton cards, locks, lore
allowlist, P21(h) locations — the P21(h) rework itself is NOW DONE, see
§1). The owner directive stands: drafters are AGENTS YOU SPAWN, never
llama-server/local GGUF/external APIs; record it in the round-5 record.

Nothing is committed. No out-of-scope file touched (repo writes this
session: `tmp/*` campaign scripts/fix lists + this handoff file only).
`LLM-INTEGRATION-STAGES.md` and `LLM-INTEGRATION-HANDOFF.md` are UNTOUCHED
— no round-5 record exists yet; the round is incomplete and the next
agent writes the record at close-out per R2 §6.

---

## 1. DONE and verified on disk (do NOT redo)

| bank | rows | state |
|---|---|---|
| P45_entity_hedge.py | 492 (396 guard + 96 free) | CLEAN; 13g clean; frozen-6g clean; ledger pass |
| P46_era_deflection.py | 300 free | CLEAN; 13g clean; frozen-6g clean |
| P47_card_grounded_lore.py | 500 poi | CLEAN; 13g clean; frozen-6g clean |
| P48_associative_recall.py | 300 memory | CLEAN; 13g clean; frozen-6g clean |
| P50_longform.py | 600 longform | CLEAN; 13g clean; frozen-6g clean |
| P21B_lore_npcs_af.py | 120 lore | CLEAN — the P21(h) hedge rework LANDED (3 rows, see below) |

- "CLEAN" = `validate_banks.py` exits 0. "13g" = zero 13-gram overlap vs
  frozen corpus (`tmp/gate_new_banks.py`). "frozen-6g" = zero replies
  sharing a 6-gram with the frozen corpus (so the composer will drop none
  of them for dup-vs-old rows).
- **P21(h) rework DONE**: the sweep found THREE hedge-then-guess rows, all
  in P21B — ayamiss (~line 339), sartura (~line 425, found by widened
  sweep "can't say i know the name's full weight, BUT I know the shape"),
  fankriss (~line 899). All three reworked to honest-hedge + deny +
  pivot-to-source (no confabulation); P21B validates CLEAN. P21J:815 was
  checked and is a correct era-trap denial (NOT a rework target). No other
  hedge shapes exist in P21* (sweep terms: hazard / don't know the name /
  sounds like one of those / can't say i know / never heard of).
- **Skeletons authored + CLEAN (cards only, banks empty except P45-P50)**:
  P51_depth_arcs.py (12 cards, incl. 4 companion cards with
  state_flavors), P52_ambient_barks.py (8 cards with murmur_demeanor/
  murmur_quirk/murmur_gripe), P53_persona_sessions.py (10 cards),
  P54_topups.py (4 companion cards). P51/P52/P53/P54 banks are EMPTY —
  their drafters have NOT run.
- **Evidence persisted**: `G:\NPU LLM\finetune-data\reports\S11_round5_baseline.json`
  (locks green; v2_all.jsonl 13,673 examples; protocol share **14.83%** of
  assistant words — the top-up floor; long-row >=80w share 0.65%; ledger
  47 actionable idioms; P21(h) set; lore asset 585 cards / 289-1102 chars)
  and `reports\P47_span_manifest.json` (500 spans, quartile buckets
  289-858/858-897/897-939/939-1102, seed 20260901, per-card class split).
- **Part files on disk** (`finetune-data/drafts/`): P45_d1..d8, P46_d1..d6,
  P47_d1..d6, P48_d1..d6, P50_d01..d16 (+ SPEC_common.md). They are
  already merged into the landed banks — kept for reject-rate accounting.
- Locks were green at session start and nothing repo-side changed since
  (my writes: G: banks, repo tmp/, this file). Re-run all three locks
  before AND after your work regardless (R2 §1).

## 2. The dispatch recipe that worked (use it unchanged)

1. You author/verify the skeleton CARDS (done for all banks).
2. One drafter agent per card (or per half-card for P50) — general-purpose
   subagents, FOREGROUND, ALL dispatched in ONE message per wave. Each
   prompt contained: read-first list (SPEC_common.md, the bank skeleton,
   TEMPLATE_bank.py), the exact row tuple shape WITH one example, its
   card, its row-count target, its nonce slice (P45) or span ids (P47),
   class/kind/mood distribution, question-rate target, an explicit
   OPENER word menu (disjoint slices per drafter sharing a card), the
   no-heredoc law, and "reply SHORT summary only". This produced ZERO
   opener violations and ~20-80 validator flags per ~500-row bank — all
   fixable in the edit pass.
3. Merge deterministically: `python tmp/merge_parts.py <bank_stem> <part...>`
   (concatenates PARTs in order; splices P47 "SPAN:<id>" -> "Title = text";
   drops P45 guard rows whose ENTITY contains a single-word held-out nonce
   as a whole word — the correct contamination rule; multi-word held-out
   names share component syllables BY DESIGN, do not "fix" those).
   Arc-dict parts (P51) are supported and SMOKE-TESTED (dict keys are
   stringified; validated CLEAN on a throwaway bank, then deleted).
4. Edit pass to CLEAN: `validate_banks.py <bank>` -> dump offending rows
   (import the bank, print by index) -> minimal replacements with
   `assert src.count(old)==1` (see tmp/edit_pass1.py / p50_dupfix.py
   patterns) -> revalidate. Reword, never debate, never touch constants.
5. Gates per bank: `validate_banks.py` CLEAN -> `python tmp/gate_new_banks.py
   <stem...>` (13-gram gate vs frozen corpus + 6-gram merge-drop predictor)
   -> fix flagged rows (usually via an edit-pass SPAWNED agent given a
   dumped fix list — the pattern that cleared P45/P46/P47/P48/P50) ->
   `phrase_ledger.py` (actionable count must not grow; baseline 47).
6. Onward to the next bank.

## 3. Machinery (all in C:\pocket_realm_complete\tmp\, Write-tool created)

- `merge_parts.py` — part merger (banks rebuilt as skeleton-head +
  generated BANKS block; keeps cards verbatim). Arc-dict path tested.
- `gate_new_banks.py` — the frozen-corpus dedup gate for LANDED banks.
  IMPORTANT: draft_dedup.py self-loads every banks/*.py (so a landed bank
  matches ITSELF — that is why this variant exists). Equally important:
  gate_new_banks treats merged/v2_*.jsonl as frozen; if you ever run
  compose_banks mid-campaign, the new banks enter v2_all and poison the
  frozen side — so RUN ALL GATES BEFORE ANY compose_banks MERGE, and run
  compose_banks exactly once at the end (fail-loud, all banks in).
- `p47_span_manifest.py` — regenerates the span manifest (only if needed;
  it exists, don't regenerate).
- Fix lists + edit agents' check scripts (kept as accounting evidence):
  gate_p45_p46_fixlist.txt, p47_fixlist.txt, p47_p48_gram_fixlist.txt,
  p50_gram_fixlist.txt, check_p45_p46_grams.py, p47_check.py,
  check_p47_p48_grams.py, check_p50_gramfix.py, edit_pass1.py,
  p50_dupfix.py.

## 4. REMAINING WORK, in order

### 4a. P51 depth arcs (THE STOPPED POINT — wave A was never dispatched)
18 arcs per drafter, 12-14 turns each, per the P51 skeleton docstring
(>=3 forced callbacks naming turns-1-3 tokens again at turns 8+; 2-3 tool
turns MID-arc; exactly ONE longform-flagged turn (80-150w, tool-free,
needs longform_facts[i]); other turns terse; guid cycles 0/1/2; loss is
on all turns by construction). Wave A (6 agents, one message) — the six
prompts were fully composed but never spawned; re-compose from §2 with:

| card id | scenario | arc-key prefix | terse band | callback-token domain |
|---|---|---|---|---|
| sw_clothfactor | betrayal->restitution | arc_merrow_d01_NN | 6-30w | partner, seal, ledger, warehouse, bolt, promise |
| durotar_secondblade | betrayal->restitution | arc_rakgor_d02_NN | 4-25w | the second, the route, a blade, graves, sixth place |
| redridge_militiacaptain | rescue | arc_hart_d03_NN | 6-30w | missing name, trail marker, rope, slopes, lantern count |
| ashenvale_pathfinder | rescue | arc_thalyra_d04_NN | 5-25w | lost party, knot-mark, ford, satyr post, owl call |
| dunmorogh_lostpartner | long-lost-friend | arc_fizwick_d05_NN | 6-30w | partner's name, blueprint vN, tool, hatch, two mugs |
| duskwood_vigilkeeper | mourning | arc_maren_d06_NN | 6-28w | husband's habit, oil, bread-bringer, two cups, hill path |

Each prompt also embeds an opener word menu (first word max TWICE across
ALL the drafter's replies for that card — the generic menu used for wave
A: Aye No Yes Not Never Maybe Perhaps Surely Truly Honestly Plainly
Quietly Slowly Softly Listen Look Hear Hold Keep Mind Wait Stop Come Go
Sit Stand Rest Walk Ask Say Tell Speak Think Suppose Remember Forget Try
Start Stay Leave First Last Once Tonight Today Tomorrow Yesterday Soon
Dawn Dusk Morning Noon Good Fine Wrong True Strange Odd Two Three What
Who When Where Why How There This That Then Now Well Still — plus 8-12
card-specific nouns). Zero address words, zero "not X, but Y", ~1 in 5
replies ends "?", player lines lowercase varied, {player} token allowed.

Wave B (2 agents): sw_inquiryagent (rumor investigation; prefix
arc_vespertine_d07_NN; source/date/corroboration discipline) and
lakeshire_talering_host (bar-story contest; arc_pell_d08_NN; rounds,
the bell, the slate). Wave C (4 agents, the COMPANION cards — travel/camp
arcs WITH ACT tools per B10: <<duel_challenge name="{player}">>,
<<give_item player="{player}" item="...">>, <<follow name="{player}">>,
<<party_invite name="{player}">>, <<move_to place="...">>,
<<loot_roll choice="need|greed|pass">>): elwynn_shieldmate
(arc_bram_d09_NN), barrens_sunrunner (arc_joraga_d10_NN),
hillbrad_fernhealer (arc_marta_d11_NN), ashenvale_nightblade
(arc_sylissa_d12_NN). Camp/agro/loot/watch/duel scenarios; state_flavors
already on the cards. TOTAL = 12 agents x 18 = 216 arcs; if you want the
manifest's ~300, add a wave D topping up cards with +6-8 arcs each.
Then: merge (`python tmp/merge_parts.py P51_depth_arcs P51_d01 ...`),
validate (4-24 turns; longform flags need facts; L5 dup-grams across the
whole file are the risk — the terse-turn fix loop is cheap), gates, then
delete nothing. NOTE: the P51 skeleton BANKS currently holds a
placeholder `"free": []` — the merge regenerates BANKS from parts and the
placeholder disappears (expected).

### 4b. P52 ambient barks (~150-250 murmur rows)
1 drafter per card (8 agents, one message), ~20-30 rows each: shape
(card_id, event, listener, reply); reply 8-20 WORDS (L3c), event-grounded,
one line to the named listener; cards already carry the murmur triple.
No tools. Opener rotation still applies per card (murmur rows are
reply-bearing). Listener names: simple NPC-ish first names are fine (they
render into the frozen murmur note). Then merge/validate/gates.

### 4c. P53 persona sessions (~60 sessions)
10 cards x 6 sessions, 12-16 turns: {"card": id, "seed": tag,
"turns": [(player, reply[, tools][, state][, mem]), ...]}. THE LAW: one
session = one persona start to finish (the persona-dip fix); states may
vary but never consecutive-identical; 1-2 tool turns per session allowed;
cross-session memory used late. L11 REGISTER FLOORS BIND (>=25 player
lines -> messy 40% / abbrev 15% / emote 10% / jargon 5% / confused 5% —
brief the drafters with concrete examples: lowercase, typos, "u/ur/idk/
lol", *emotes*, aggro/rez/wts jargon, new-player confusion). Dispatch
1-2 cards per agent (6-10 agents, one message). Sessions are exempt from
per-card opener/L5 passes but DO count toward L4 address ratio.

### 4d. P54 top-ups (~190 rows, 4 companion cards)
(a) ~60 adjust_sentiment beat rows (the measured q08 gap — reasons
substantive, both directions); (b) ~60 cross-family protocol rows
(log_fact/share_gossip/perform_emote/ACT) for token-mass; (c) ~30
nickname-adoption FREE rows (tier-5 ceremony surface — the earned
nickname used sparingly mid-prose, per the card bibles); (d) ~20 A16
meetup-initiation EVENTS rows (companion INITIATES: place + time);
(e) ~20 A17 dusk-appointment MEMORY rows (planted appointment fact the
reply proactively honors). 2 drafters (2 cards each) is enough.

### 4e. Phrase-ledger offender reworks (mandated, in place per B3)
Run `phrase_ledger.py`; rework editable-flagged (rp_*/crowd/session,
authored=false) occurrences of the stock-phrase tics ONLY — 'first
light', 'hold still', 'take your time', 'sit and let', 'sit take',
'rinse the cut', 'hold steady', 'twice over', 'next pull', etc. Proper
nouns (booty bay, loch modan, sentinel hill, second war...) are content,
not tics — leave them. Authored rows are skipped. Keep banks CLEAN.

### 4f. Final merge + batteries (order matters — gates BEFORE merge)
1. All banks validate CLEAN; per-bank gate_new_banks 13g/6g clean;
   phrase_ledger pass.
2. `python "G:\NPU LLM\scripts\finetune\compose_banks.py"` (fail-loud).
   Inspect reports/merge_report.json + PXX_report.json: dropped rows
   must be ~0 (you pre-cleaned the 6-gram side), no opener >5% global,
   no jaccard >= 0.5 pairs.
3. The 157-battery:
   `python -m pytest tests/test_llm_player_surface.py tests/test_llm_act_tools.py tests/test_llm_recall.py tests/test_llm_truth.py tests/test_llm_banter.py tests/test_llm_a0_unification.py tests/test_llm_json_client.py tests/test_llm_prompt_format.py tests/test_llm_chatter.py tests/test_llm_prtools_harness.py tests/test_sqlite_dialect.py tests/test_mariadb_lockfile_pins.py -q`
4. The three locks (emit_prompt_constants --check, extract_bridge_wording
   --check, prtools harness pytest).

### 4g. Evidence reports (persist BEFORE the record)
Per bank in reports/: rows authored/landed, per-drafter reject rates
(§5 accounting + your own), question-rate share (count "?"-ending
replies per bank — guard target was ~55%, others ~33%), nonce train vs
held-out statement (held-out NEVER trained — assert no single-word
held-out nonce appears as a whole word in any bank), token-mass:
assistant-word totals per bank and CORPUS-WIDE long-row (>=80w) share of
assistant token mass (budget <=10-20%; estimate: P50 ~66k + P51 cued
turns ~24k + P53 sessions long turns vs ~562k existing + ~370k new
short — lands ~14%, compute the real number from the merged file), and
protocol share vs the 14.83% baseline (P51 mid-arc tool turns + P54 +
P53 tool turns; top up via P54 if below).

### 4h. 6-reviewer panel -> zero P0/P1, then the round-5 record
Panel: six spawned agents, ONE message, roles per R2 §6 (data-
methodology/budget, lore+era, validator+hygiene, wording-lock+repo
coupling, evidence/process, adversarial dedup+bleed). Fix to zero P0/P1.
Then append the S11 round-5 record to LLM-INTEGRATION-STAGES.md (dated;
evidence lines naming artifacts; the R2 owner directive verbatim-pointed;
the P49 BLOCKER — zero rows, no banklib shape can express the enum-
classifier sub-call, shared tooling off-limits, the "already queued"
claim false; the P53/P54 numbering decisions; provenance flag: all rows
machine-authored by spawned agents, human-unread — flag samples for the
owner's spot-review; deferred items). Update the S11 status row +
LLM-INTEGRATION-HANDOFF.md remaining-scope paragraph. Walk the original
brief §9 checklist. NOTHING COMMITTED.

## 5. Gotchas that bit (save yourself the relearn)

1. draft_dedup.py self-loads banks/*.py — never run it on a LANDED S11
   bank; use tmp/gate_new_banks.py (frozen corpus excludes P45-P54).
2. Run every gate BEFORE compose_banks — the merge rewrites
   merged/v2_*.jsonl, which is the frozen side of gate_new_banks.
3. Apostrophes: merge_parts writes repr() strings safely, but HAND edits
   (yours or edit-agents') that inject an apostrophe into a single-quoted
   literal break the file (B5) — switch the literal to double quotes.
   One such break happened (P50 "row's"); fixed.
4. L45 (P45 guard replies): NO new capitalized tokens mid-sentence, and
   POSSESSIVES COUNT ("Tirisfal's" flagged). Legal mid-sentence: the
   nonce, {player}, and the verified allowlist (Stormwind...Silverpine;
   "Lion's Pride" is NOT allowlisted). P45 free negatives may use real
   lore names (Thrall, Magni, Tyrande...) — the check is guard-bank only.
5. P47 spans contain post-1.12 wiki material; replies must answer only
   from vanilla-safe facts and PARAPHRASE banned tokens (worgen ->
   wolf-men; draenor -> the homeworld/the orcs' old world; lich king ->
   the lord of the Scourge; frostmourne -> the hungering blade; illidan
   -> the Betrayer). 27 such rows were fixed this session.
6. Every edit pass can introduce NEW 6-gram collisions — after any edit
   round re-run BOTH validate_banks (in-file) and gate_new_banks
   (frozen). Budget 1-3 follow-up singles per pass; that was the
   steady-state.
7. The 6-gram dup check is FILE-GLOBAL across all banks in the file and
   includes player lines? — no: replies only, but across every bank in
   the file. Cross-FILE new-new 6-gram overlaps surface only at
   compose_banks (drops) — that is why gate_new_banks' frozen side plus
   per-bank pre-merge hygiene matters.
8. Canonical-lore phrases ("war of the three hammers", "cult of the
   damned") collide across rows AND vs the old P21 lore corpus — vary
   one side ("Three Hammers war", "the Damned cult").
9. The validator counts openers per CARD across ALL banks in the file —
   disjoint opener menus per drafter (dispatched in the prompt) are what
   kept this at zero violations. Keep doing that for P51-P54.
10. Session rows (P53): state may not repeat on consecutive turns; L11
    floors bind at >=25 rp lines; sessions count toward the L4 address
    ratio per card.
11. P45 nonce pool: single-word held-out names (Halfrey, Gildell, Sargar,
    Selbin...) are the real leak class — merge_parts already drops
    train entities containing them as whole words (4 rows dropped this
    session). Multi-word held-out names share syllables by design.

## 6. Per-drafter accounting so far (for the owed reports)

Rows submitted -> landed; flags fixed IN PLACE by edit passes (the honest
"would-have-been-dropped" reject class; fix lists in tmp/ name the rows):

- P45 (8 drafters x 62): 496 -> 492 (4 merge-dropped: held-out
  contamination). Validator flags fixed: 17; frozen-6g rows reworded: 22.
- P46 (6 x 50): 300 -> 300. Validator: 8; frozen-6g: 16.
- P47 (6 x ~83): 500 -> 500. Validator: 77 (27 era-token + 50 dup-gram);
  frozen-6g: 63.
- P48 (6 x 50): 300 -> 300. Validator: 1; frozen-6g: 13.
- P50 (16 x 37-38): 600 -> 600. Validator: 20; frozen-6g: 39.
- P21B: 3 mandated reworks (not rejects).

## 7. Constants at a glance

- Bands: default 6-110w; guard target 18-60; P50 80-150; arc longform
  80-150 (80-130 if tooled — but keep longform turns tool-free); P52
  8-20; arcs 12-24 turns (validator 4-24); sessions 12-16 turns.
- Question rates: guard ~55% (measured 54-56% across P45 drafters),
  others ~33%.
- Address words <= 3 rows per drafter (law 1/4, margin 1/6); "not X, but
  Y" <= 2/card; zero in P51-P53 prompts was the deployed rule.
- Opener law: first word max TWICE per card per FILE.
- Frozen strings (never quote): long-form cue, beat-frame wording
  ("It is UNPAID. You are NOT square. Name it.", "You DO remember",
  "Something DID happen", "a friend worth keeping" etc.), guard/murmur
  wording, TOOLS_NOTE, "[BRIDGE AI]" — SPEC_common.md lists them all.
- Tools: exact keyed syntax, filled; emotes from the 19-name whitelist;
  ACT forms per B10; P51 mid-arc placement is the point.
