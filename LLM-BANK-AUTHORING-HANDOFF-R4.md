# LLM-BANK-AUTHORING-HANDOFF-R4.md — S11 round-5 second-half PROGRESS handoff (partial)

Written 2026-09-01 (evening). The round-5 second-half session read the
R3 handoff, verified the three wording locks GREEN, then dispatched P51
wave A (6 drafter agents, one message). THREE landed, THREE died
(2 x "off-peak-ticket-expired" at spawn; 1 x turn-budget death after
reading but before writing its file). The owner ended the session for an
off-peak continuation.

**The next agent is the SAME author (S11 bank-authoring, R2 brief rules).
Read, in order: this file, then `LLM-BANK-AUTHORING-HANDOFF-R3.md` (its
§2 dispatch recipe, §3 machinery, §5 gotchas, §6 accounting, §7 constants
ALL still stand — this file only adds deltas), then
`LLM-BANK-AUTHORING-BRIEF-R2.md`, then `LLM-BANK-AUTHORING-BRIEF.md`,
then INTEGRATION §5/§5.1 + the S11 record.** The owner directive stands:
drafters are AGENTS YOU SPAWN (general-purpose subagents, foreground,
parallel, one message per wave) — never llama-server/local GGUF/external
APIs; record it in the round-5 record.

Nothing is committed. Repo writes this session: this file ONLY (the three
landed part files are on G:). `LLM-INTEGRATION-STAGES.md` and
`LLM-INTEGRATION-HANDOFF.md` remain UNTOUCHED — no round-5 record exists;
the next agent writes it at close-out per R2 §6.

---

## 1. DONE and verified on disk (do NOT redo)

**P51 part files LANDED (drafts/, parse-verified, 18 arcs each):**

| part | card | scenario | turns | notes from the drafter's audit |
|---|---|---|---|---|
| P51_d01.py | sw_clothfactor (Aldous Merrow) | betrayal->restitution | 237 | 211 distinct openers, max 2 uses; varied endings; canon fixed: partner "Osric Vane", theft 240 gold, "counter-seal" = Vane's duplicate stamp |
| P51_d03.py | redridge_militiacaptain (Hart) | rescue | 234 | 215 distinct openers; endings varied (alive/hurt/too-late x2/trade/storm/animal); rope lengths + lantern counts reused as tokens |
| P51_d06.py | duskwood_vigilkeeper (Maren) | mourning | 229 | 213 distinct openers; canon fixed: husband "Bren Coalter", bread-bringer "Berta", chandler "Yost", boat "the Grebe", wick length "thumb-knuckle" |

- Each part = `PART = { "arc_<prefix>_NN": {card, guid, turns, longform, longform_facts}, ... }`, guid cycling 0/1/2, exactly one longform turn per arc, 2-3 mid-arc tool turns. Drafter self-audits reported clean opener caps / 6-gram variety / bands — but the REAL gates (validate_banks, gate_new_banks) have NOT run on any P51 part yet. First merge + edit pass happens when all twelve P51 parts exist.
- **Wave-A failures to re-dispatch (same parameters, §2):**
  - P51_d02 — durotar_secondblade (Rakgor), betrayal->restitution, prefix `arc_rakgor_d02_NN` — agent NEVER SPAWNED (ticket expired).
  - P51_d04 — ashenvale_pathfinder (Thalyra), rescue, prefix `arc_thalyra_d04_NN` — agent NEVER SPAWNED (ticket expired).
  - P51_d05 — dunmorogh_lostpartner (Fizwick), long-lost-friend, prefix `arc_fizwick_d05_NN` — agent spawned, read everything, announced it would write, then DIED at its budget with the file unwritten (5 tool uses total). The §2 incremental-write instruction is the mitigation.
- **Locks verified GREEN this session (2026-09-01)**: emit_prompt_constants --check "fresh"; extract_bridge_wording --check "fresh"; prtools harness 17 passed. Re-run before AND after your work regardless (R2 §1).
- **The P51 skeleton still holds the placeholder `"free": []`** — no bank file was modified this session. P52/P53/P54 banks still empty skeletons. P45-P50 + P21B landed and gated (R3 §1 — unchanged).
- Wave-A prompts were RE-COMPOSED this session from R3 §4a + the §2 template below; the template below is the EXACT shape that produced the three landed parts. d01's flagged uncertainty is resolved: tool lines with escaped inner double quotes (`\"` inside a double-quoted Python literal) are CORRECT canonical form — the keyed syntax itself uses double quotes, and merge_parts repr()s everything safely on merge.

## 2. The drafter dispatch template THAT WORKED (P51; adapt per bank)

Dispatch ONE general-purpose subagent per card, FOREGROUND, all in ONE
message per wave. Verify each wave by FILE EXISTENCE + `ast.parse` + arc
count — never trust the summary alone. Template (all-caps = fill per
card from the table + R3 §4a):

```
You are a DRAFTER agent in the S11 fine-tune bank pipeline for PocketRealm
(World of Warcraft 1.12 roleplay training data). Author 18 multi-turn depth
arcs for ONE card as a Python part file. You touch NO other file. NEVER
write files via bash heredocs/echo/cat - Write/Edit tools only.

READ FIRST, in order:
1. G:\NPU LLM\finetune-data\drafts\SPEC_common.md   (the law sheet - every law binds)
2. G:\NPU LLM\finetune-data\banks\P51_depth_arcs.py (find YOUR card "{CARD_ID}" - absorb its bible voice, quirks, facts, absence; every reply is in {HIS/HER} voice)
3. G:\NPU LLM\finetune-data\TEMPLATE_bank.py        (conventions only; its L8 emote list is stale - SPEC_common's 19 is current)
4. {STRUCTURE_SEED_FILE}                            (read 2-3 arcs for TURN RHYTHM only - NEVER copy phrasing/names/sentence shapes)

YOUR ASSIGNMENT: card "{CARD_ID}", scenario {SCENARIO}, arc keys
"{PREFIX}_01" .. "{PREFIX}_18" (two-digit suffixes).

ARC DICT SHAPE - your part file contains ONE dict per arc:
PART = {
  "{PREFIX}_01": {
    "card": "{CARD_ID}",
    "guid": 0,                # cycles 0,1,2,0,1,2,... across the 18 arcs
    "turns": [
      ("player line lowercase", "reply in voice"),
      ("player line", "reply", ["<<log_fact text=\"...\">>"]),   # tool turn (tools = 3rd element)
      ...                       # 12-14 turns total
    ],
    "longform": [7],           # EXACTLY ONE turn index per arc
    "longform_facts": {7: "one third-person event fact from this arc's story"},
  },
}
[+ the worked shape-model arc from the d01 prompt - one short arc showing
a tool turn, a callback plant, and a longform slot]

CONTENT LAWS:
- 18 arcs x 12-14 turns, ONE continuing conversation per arc (each reply
  builds on prior turns, never resets).
- TERSE: non-longform replies {TERSE_BAND} words. Depth = cross-turn
  dependency, not length.
- >=3 FORCED CALLBACKS per arc: plant specific tokens in turns 1-3 from
  the domain {CALLBACK_DOMAIN} plus arc-specific invented specifics (a
  minor NPC name, an object detail, a count); >=3 replies at turn
  index >= 8 MUST use those exact tokens again, naturally.
- 2-3 TOOL turns per arc, MID-arc (never turn 0, never last, never the
  longform turn). Legal: log_fact (opt. category= preference|shared-event|
  opinion|player-identity), share_gossip, perform_emote (19-name list),
  adjust_sentiment direction="+1"/"-1" reason=...
  [wave C companion cards ADD the ACT forms per B10: duel_challenge
  name="{player}", give_item player="{player}" item="...", follow
  name="{player}", party_invite name="{player}", move_to place="...",
  loot_roll choice="need|greed|pass" - use 1-2 ACT turns per arc]
- EXACTLY ONE longform turn per arc: 80-150 words, NO tools on it, listed
  in "longform", with its fact in "longform_facts" (style: "the barn and
  forty sacks of barley burned at the road farm" - clean nouns/verbs; the
  pipeline flips me/my to you/your). Its player line is a story trigger.
- {SCENARIO_VARIETY_LINE}   (endings must VARY - list 6+ distinct shapes)
- ~1 reply in 5 ends "?" (cap, not floor). ZERO address words (lad, lass,
  friend, pal, paladin, boy, sir, laddy, lassie). ZERO "not X, but Y".
  {player} token where the player's name is needed.
- Player lines: lowercase human register, varied - typos, slang ("u",
  "idk", "lol"), short questions, occasional *emote*; 2-12 words.

OPENER ROTATION (the #1 machine reject): the FIRST word of EVERY reply may
be used at most TWICE across all 18 arcs for this card (~234 replies ->
you need 117+ distinct openers). Plan from this menu (~128 words = 256
slots): {OPENER_MENU = the 116-word generic list below + ~12-16
card-specific nouns}. Never open two consecutive replies (or two replies
in one arc) with the same word. Coin fresh openers from the card's world
when the menu runs dry - the LAW is the max-2 cap.

NO 6-GRAM REPEATS (the #2 machine reject): no 6-word span may repeat
across ANY two of your replies. Never reuse a stock sentence shape.

FROZEN STRINGS - never quote or near-quote: the long-form cue ("This one
is worth telling properly..."), beat-frame wording ("Something DID
happen", "That is your news", "You DO remember", "It is UNPAID", "You are
NOT square", "Name it", "a friend worth keeping", "a true friend", "the
one you would follow anywhere"), "[BRIDGE AI]", any bracketed token.

ERA: vanilla 1.12 only. ASCII, straight quotes, no em-dash char (write
" - "), no ellipsis char (write "..."). Python: double-quoted strings
throughout; never put a double-quote character inside any TEXT value
(escaped \" inside TOOL values is required and fine).

WRITE INCREMENTALLY (a prior agent died with nothing on disk): create the
part file with arcs 01-03 within your FIRST FEW tool calls, then append
03-06 at a time via Edit. Never hold the whole file in your head.

OUTPUT CONTRACT:
- Write EXACTLY one file: G:\NPU LLM\finetune-data\drafts\{PART}.py
  containing ONLY `PART = {...}` (+ a short comment header).
- Verify it parses: python -c "import ast;ast.parse(open(r'G:\NPU LLM\finetune-data\drafts\{PART}.py',encoding='utf-8').read())" - fix any error.
- Reply with a SHORT summary ONLY: arcs, turn counts, openers leaned on,
  callback token names per arc, uncertainties. NEVER paste the arcs back.
```

**The 116-word generic opener list (used verbatim by d01/d03/d06; append
~12-16 card nouns per card):** Aye No Yes Not Never Maybe Perhaps Surely
Truly Honestly Plainly Quietly Slowly Softly Loudly Listen Look Hear Hold
Keep Mind Wait Stop Come Go Sit Stand Rest Walk Ask Say Tell Speak Think
Suppose Remember Forget Try Start Stay Leave First Last Once Tonight Today
Tomorrow Yesterday Soon Dawn Dusk Morning Noon Evening Night Good Bad Fine
Wrong True Strange Odd Two Three Four Five Six Nine Ten Twelve What Who
When Where Why How Which Whose There This That These Those Then Now Well
Still Since Before After Under Over Behind Between Without Against Through
I You We It They The If Because Though While Until.

**P51 dispatch table (waves A-D):**

| part | card | scenario | prefix | terse band | callback domain | status |
|---|---|---|---|---|---|---|
| P51_d01 | sw_clothfactor | betrayal->restitution | arc_merrow_d01_NN | 6-30w | partner, seal, ledger, warehouse, bolt, promise | LANDED |
| P51_d02 | durotar_secondblade | betrayal->restitution | arc_rakgor_d02_NN | 6-25w | the second, the route, a blade, graves, sixth place | RE-DISPATCH |
| P51_d03 | redridge_militiacaptain | rescue | arc_hart_d03_NN | 6-30w | missing name, trail marker, rope, slopes, lantern count | LANDED |
| P51_d04 | ashenvale_pathfinder | rescue | arc_thalyra_d04_NN | 6-25w | lost party, knot-mark, ford, satyr post, owl call | RE-DISPATCH |
| P51_d05 | dunmorogh_lostpartner | long-lost-friend | arc_fizwick_d05_NN | 6-30w | partner's name, blueprint vN, tool, hatch, two mugs | RE-DISPATCH |
| P51_d06 | duskwood_vigilkeeper | mourning | arc_maren_d06_NN | 6-28w | husband's habit, oil, bread-bringer, two cups, hill path | LANDED |
| P51_d07 | sw_inquiryagent | rumor investigation | arc_vespertine_d07_NN | 6-28w | source, date, corroboration, rate, verdict | wave B |
| P51_d08 | lakeshire_talering_host | bar-story contest | arc_pell_d08_NN | 6-30w | rounds, the bell, the slate, the tale, the score | wave B |
| P51_d09 | elwynn_shieldmate | companion travel/camp + ACT | arc_bram_d09_NN | 6-28w | camp meals, watch order, the road, the shield, the split | wave C |
| P51_d10 | barrens_sunrunner | companion travel/camp + ACT | arc_joraga_d10_NN | 6-25w | grass, water, the kodo, the trace, the wind | wave C |
| P51_d11 | hillbrad_fernhealer | companion travel/camp + ACT | arc_marta_d11_NN | 6-28w | stitches, the pot, the ford, the limp, tea | wave C |
| P51_d12 | ashenvale_nightblade | companion travel/camp + ACT | arc_sylissa_d12_NN | 6-28w | the cache, the patrol, the watch, the count, the debt | wave C |

Structure-seed files: betrayal -> P24_arcs_duel_trade.py (d02) /
P26_arcs_betrayal_rescue_friend.py (d01); rescue -> P26 (d03, d04 use
P25_arcs_quest_festival.py for d04); long-lost-friend -> P26; mourning ->
P27_arcs_rumor_barstory_mourning.py; rumor/bar-story -> P27; companion
travel/camp -> any P34-P43 party package (P39_durotar_barrens_party.py
known good for ACT row shapes). Wave D (optional): +6-8 arcs each on
existing prefixes toward ~300 total (216 from waves A-C).

## 3. Machinery (unchanged)

R3 §3 — `tmp/merge_parts.py` (arc-dict path SMOKE-TESTED; run
`python tmp/merge_parts.py P51_depth_arcs P51_d01 P51_d02 ... P51_d12`
with the parts that exist, in numeric order; the skeleton's placeholder
`"free": []` disappears — expected), `tmp/gate_new_banks.py`,
`tmp/p47_span_manifest.py`, the fix lists. Same laws: gates BEFORE
compose_banks; compose ONCE at the end; draft_dedup.py never on landed
S11 banks.

## 4. REMAINING WORK, in order

1. **P51 wave-A remnant**: re-dispatch d02, d04, d05 (3 agents, ONE
   message, §2 template + table). Verify by file existence + parse.
2. **P51 wave B**: d07, d08 (2 agents, one message).
3. **P51 wave C**: d09-d12 (4 companion agents with ACT tools, one
   message).
4. **P51 wave D (optional)**: top-up +6-8 arcs/card toward ~300.
5. **P51 merge + edit pass + gates**: merge_parts -> validate_banks CLEAN
   (bands: arcs 4-24 turns; longform turns need facts; L5 file-global
   6-grams across ~2,800 replies is THE risk) -> tmp/gate_new_banks.py
   P51_depth_arcs (13g + 6g) -> phrase_ledger no-growth (baseline 47).
6. **P52 ambient barks**: 8 drafters (one per card), ~20-30 murmur rows
   each, (card_id, event, listener, reply), reply 8-20 WORDS (L3c hard
   band), event-grounded, tool-free, no opener convention per row BUT the
   validator's generic opener pass still counts murmur replies per card
   (~24 rows/card -> still give each drafter a disjoint ~30-word opener
   slice); listener = simple NPC-ish first names. Frozen murmur wording
   ("The talk turns to real news", "under twenty words", "the way two
   people talk at a stall") never quoted. Then merge/validate/gates.
7. **P53 persona sessions**: 60 sessions (10 cards x 6), 12-16 turns
   (hard validator band), {"card", "seed", "turns"}; ONE persona per
   session start to finish; state may not repeat on consecutive turns
   (a None/absent state breaks the chain legally); 1-2 tool turns per
   session; cross-session memory used late. L11 REGISTER FLOORS bind at
   >=25 rp lines per package (P53 will hold ~840 player lines): messy
   40% / abbrev 15% / emote 10% / jargon 5% / confused 5% — brief
   drafters with CONCRETE examples (lowercase starts, "u/ur/idk/lol",
   *emotes*, aggro/rez/wts, "how do i..." new-player lines). Sessions
   exempt from opener/L5 passes; DO count toward L4 address ratio.
   1-2 cards per agent (6-10 agents, one message).
8. **P54 top-ups (~190 rows, 4 companion cards, 2 drafters)**: (a) ~60
   adjust_sentiment beats (the q08 gap; both directions, substantive
   reasons); (b) ~60 cross-family protocol rows (log_fact/share_gossip/
   perform_emote/ACT); (c) ~30 nickname-adoption FREE rows; (d) ~20 A16
   meetup-initiation EVENTS rows (companion initiates: place + time);
   (e) ~20 A17 dusk-appointment MEMORY rows ((card, player, reply, beat,
   mem) 5-tuples; the planted appointment fact rides the mem). The
   skeleton already carries an OPENER_MENU per card.
9. **P49**: author NOTHING — report blocker (no banklib shape for the
   enum-classifier sub-call; shared tooling off-limits; the "already
   queued" claim is false). Zero rows; state it in the record.
10. **Phrase-ledger offender reworks in place (B3)**: editable-flagged
    stock-phrase tics ONLY ('first light', 'hold still', 'take your
    time', 'sit and let', 'rinse the cut', 'hold steady', 'twice over',
    'next pull'...). Proper nouns (booty bay, loch modan, sentinel
    hill...) are content — leave them. Authored rows skipped.
11. **ALL GATES BEFORE THE MERGE**: per bank validate CLEAN ->
    gate_new_banks (13g + 6g) -> phrase_ledger no-growth. P45-P50 are
    already gated clean and still safely re-gateable (compose has not
    run since). THEN `python "G:\NPU LLM\scripts\finetune\compose_banks.py"`
    (fail-loud, ONCE, all banks in). Inspect merge_report.json + PXX
    reports (drops ~0; no opener >5%; no jaccard >=0.5).
12. **Battery + locks**: the 157-battery (command in R3 §4f) and the
    three wording locks, all green after the merge.
13. **Evidence reports** (R3 §4g): per bank rows/authored/landed,
    per-drafter reject rates (R3 §6 + your passes), question-rate shares,
    nonce train-vs-heldout statement, token-mass shares incl. corpus-wide
    long-row share computed from the merged file (budget <=10-20%),
    protocol share vs the 14.83% baseline. Persist BEFORE the record.
14. **6-reviewer panel** (6 agents, ONE message, roles per R2 §6) ->
    fix to zero P0/P1.
15. **S11 round-5 record** (R3 §4h): the R2 owner directive, the P49
    blocker, the P53/P54 numbering decisions, the three-row P21(h)
    rework, the off-peak interruptions (both sessions — provenance note),
    provenance flag (machine-authored, human-unread; flag samples for
    owner spot-review). Update the S11 status row +
    LLM-INTEGRATION-HANDOFF.md remaining-scope paragraph; walk brief §9.
    NOTHING COMMITTED.

## 5. Gotchas that bit (NEW this session; R3 §5 all still applies)

1. **Off-peak ticket expiry kills INDIVIDUAL agents, not the message**:
   2 of 6 spawned-and-died instantly. Retry only the missing parts in a
   fresh message; never re-dispatch a landed part.
2. **A drafter can die AFTER reading, BEFORE writing** (d05: 5 tool
   uses, no file). The §2 WRITE-INCREMENTALLY instruction is the
   mitigation; verify file existence right after every wave.
3. **The opener law counts arc TURNS** (~234 replies/card at 18 arcs):
   the R3 §4a "generic menu + 8-12 nouns" is TOO SMALL — use the §2
   116-word generic list + card nouns (~128 words total).
4. **Terse band floor is 6 WORDS** (banklib MIN_WORDS=6 hard floor on
   every non-longform reply) — R3 §4a's 4w/5w floors were wrong; the
   table above is corrected.
5. Frozen murmur wording probe string (P52): "The talk turns to real
   news" / "under twenty words" / "the way two people talk at a stall" —
   never in a murmur reply (validator _frozen_literal_scan).
6. Sessions: validator wants EXACTLY 12-16 turns; consecutive turns may
   not carry the same state string; sessions are the ONE bank exempt
   from opener/L5 (but not L4, L1/L2/L7, era, tools, anti-echo).
7. Everything in R3 §5 (draft_dedup self-load, gates-before-compose,
   apostrophes, L45 possessives, P47 paraphrase table, re-run both
   checks after every edit pass, canonical-phrase collisions, per-card
   openers across banks in the file, session state repetition, held-out
   nonce whole-word drops) — read it before your first dispatch.

## 6. Per-drafter accounting (rows submitted; reject accounting happens
at merge/edit)

R3 §6 stands for P45-P48/P50/P21B. This session added: P51_d01 237
turns, P51_d03 234, P51_d06 229 — submitted, NOT yet validated (the
edit-pass/gate reject counts get recorded when the P51 merge runs).

## 7. Constants at a glance (deltas from R3 §7)

- Terse bands per card: see the §2 table (floor 6 words everywhere).
- Arcs: dispatch 12-14 turns (validator allows 4-24); EXACTLY ONE
  longform turn/arc, 80-150w, tool-free; 2-3 tool turns mid-arc; guid
  cycles 0/1/2.
- P52 8-20 words (L3c); P53 sessions exactly 12-16 turns; P54 protocol/
  free/events/memory shapes per the skeleton docstring (memory rows are
  5-tuples: card, player, reply, beat, mem).
- Opener cap: first word max TWICE per card across ALL banks in the
  file — arcs and murmurs included.
- Question rates: guard ~55%, others ~33% (~1 in 5 for arcs is fine).
