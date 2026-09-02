# LLM-BANK-AUTHORING-BRIEF-R2.md — S11 bank authoring, SPAWNED-AGENT drafters

Written 2026-09-01 (R2). This file supersedes **§5 only** of
`LLM-BANK-AUTHORING-BRIEF.md` (drafter sourcing); every other section of
that brief still binds (read-first list, §3 bank conditions, §4 protocol,
§6 verification loop, §7 hard constraints, §8 records, §9 checklist).
You are the authoring agent: banks P45-P52 + persona sessions + the
mandated reworks, so the retrain/gates agent can run arm2′/arm1b′/2B and
G0-G5. Not the trainer; not the reviewer of prior rounds.

Machine: Windows, Git Bash, repo `C:\pocket_realm_complete` (branch
`feature/universal-client-installer`; NOTHING is committed — the owner
commits). Authoring tree `G:\NPU LLM`.

---

## 0. THE R2 CHANGE — owner directive (2026-09-01)

Drafters are **agents you spawn** (general-purpose subagents, foreground,
dispatched in one message per batch). Do NOT use llama-server, local GGUF
models (gemma/Qwen/LFM etc.), or any external LLM API. Precedent: B13
(owner mid-campaign engine switch). Consequences:

- **Multi-source (§5.1a)** = >=2 independent drafter agents per bank, with
  disjoint few-shot seeds (real corpus rows from different landed cards)
  and different voice targets. All drafters are non-Qwen by construction.
- **ToS (§5.1b)**: sidestepped entirely — house agents, no provider terms.
- **Per-provider reject log (§5.1d)** = per-DRAFTER-AGENT reject rate
  (validator drops ÷ rows submitted, tracked by part-file origin).
- Record this directive in the round-5 record + package report.
- Drafters get the no-heredoc law (Write/Edit tools only) and must return
  SHORT summaries (counts, opener plans, uncertainties — never the prose).

## 1. STATE ALREADY ON DISK (this session; do NOT redo)

- **Read-first docs**: read; all binding conditions extracted (they live in
  the original brief + INTEGRATION §5/§5.1 + the S11 record — re-read only
  those three if you need the wording).
- **Locks verified GREEN 2026-09-01** (re-run before AND after your work):
  `python tools/llm_lab/emit_prompt_constants.py --check` → "fresh";
  `python tools/llm_lab/extract_bridge_wording.py --check` → "fresh";
  `python -m pytest tests/test_llm_prtools_harness.py -q` → 17 passed.
- **Verified absent**: no P45-P52 bank files exist; **P49 rows exist
  nowhere** (no `claim=`/`verdict=`/enum rows in any bank). No banklib
  shape can express a grammar-enum classifier sub-call and banklib/shared
  tooling is off-limits → P49 is a REPORT BLOCKER (see §2), zero rows.
- **Lore asset measured** (`android/app/src/main/assets/lore/lore_cards_v112.jsonl`):
  585 cards; text-length min/p25/median/p75/max = **289/858/897/939/1102
  chars**. P47 card spans must sample THIS spread (the plan's "~1,166"
  figure is the runtime ceiling; the measured shipped max is 1,102).
- **q08 tool gap pinned**: `adjust_sentiment` is the failing family —
  artifacts `C:\llm-lab\results\n3_trained_e2b-tuned_s11-round0_20260831-175616.json`
  (fire 0.67, majority TRUE) and `n3_legacy-composite_...-175702.json`
  (fire 0.33, majority FALSE). The top-up targets adjust_sentiment rows.
- **P45 nonce pool DONE**: `G:\NPU LLM\finetune-data\reports\P45_nonce_pool.json`
  — 304 train / 76 held-out (person 136/34, place 88/22, thing 80/20),
  syllable-pool generated (seed 20260901, generator
  `tmp/nonce_gen_p45.py`), collision-validated against the shipped asset
  (titles+keys+text), the whole lore corpus, every landed bank source, the
  banklib player pools, and the production bible names. **Held-out names
  never enter any bank** (G5 reserve).
- **Shared drafter law sheet DONE**: `G:\NPU LLM\finetune-data\drafts\SPEC_common.md`
  (era lock, L1-L18, frozen strings never to quote, B2 player law, tool
  syntax, opener/address/not-but caps, output contract). Every drafter
  reads it before writing. Extend it only for bank-specific sections.
- **P45 skeleton DONE**: `G:\NPU LLM\finetune-data\banks\P45_entity_hedge.py`
  — 8 cards, empty `guard`/`free` banks: bertha_millwell (t5 human
  innkeeper, Elwynn), dain_coalbeard (t2 dwarf smith, Dun Morogh),
  thalirra (t1 night elf watcher, Teldrassil), grizka (t2 orc trader,
  Durotar), fenwick_cogglespan (t3 gnome tinker, Dun Morogh), velmaar
  (t1 undead apothecary, Tirisfal), tam_hallow (t2 human night watch,
  Duskwood), ketra_stonehorn (t4 tauren companion, Barrens; companion=True
  + state_flavors). Tiers 1-5 all present.
- **Lore allowlist for guard-reply pivots** (the validator L45 check loads
  1061 title-derived names): verified IN: Stormwind, Goldshire, Elwynn,
  Ironforge, Dun Morogh, Teldrassil, Darnassus, Durotar, Orgrimmar,
  Tirisfal, Barrens, Duskwood, Westfall, Lakeshire, Darkshire, Kharanos,
  Thunder Bluff, Crossroads, Brill, Undercity, Silverpine. **"Lion's
  Pride" is NOT allowlisted** — never mid-sentence in a guard reply.
- **P21(h) rows located (sweep not exhaustive yet)**:
  `P21B_lore_npcs_af.py` ~line 339-344 ("...I will hazard a guess!") and
  ~line 900 ("Don't know the name's full weight..."). The rework must grep
  ALL P21* for hedge-then-guess shapes ("hazard a guess", "Don't know the
  name, but", "Sounds like one of those") and rework each in place (B3):
  keep the honest hedge, DELETE the confabulation, land on deny+pivot.
- Repo regression battery to keep green (157 at handoff):
  `python -m pytest tests/test_llm_player_surface.py tests/test_llm_act_tools.py tests/test_llm_recall.py tests/test_llm_truth.py tests/test_llm_banter.py tests/test_llm_a0_unification.py tests/test_llm_json_client.py tests/test_llm_prompt_format.py tests/test_llm_chatter.py tests/test_llm_prtools_harness.py tests/test_sqlite_dialect.py tests/test_mariadb_lockfile_pins.py -q`

## 2. BANK MANIFEST (numbering decisions recorded here)

| file (G:\NPU LLM\finetune-data\banks\) | cards | rows | drafters | notes |
|---|---|---|---|---|
| P45_entity_hedge.py (skeleton done) | 8 | guard ~400 + free ~100 | 2 (cards 1-4 / 5-8) | see §4 craft notes; P21(h) rework in the SAME change; held-out nonces excluded; 5 denial moods (suspicion/dismissal/deflect-to-authority/humor/worry) rotated, primary ~40-50% per card; ~55% of guard replies end in a question; negatives = confident known-entity answers + ~30% no-proper-noun vague questions |
| P46_era_deflection.py | 6 | free ~300 | 2 | corrected 1.12 traps (flying-MOUNT phrasing, never "can you fly"; NO worgen-as-vanilla trap); never affirm a false era, never echo the era term; ~60 era-TRUE affirmations (Naxx 1.11, Scourge invasion — A11's context-allow list governs) |
| P47_card_grounded_lore.py | 6 | poi ~500 | 2-3 | question + `[RESULT]` card span -> answer FROM card in voice; spans sampled from the SHIPPED asset's real spread (289-1102 chars, quartile buckets); off-card pairs hedge; ultra-common trivia capped ~100; poi row = (card, player, result_card_text, reply[, tools]) |
| P48_associative_recall.py | 6 | memory ~300 | 2 | planted-fact rows whose answer reaches for an ADJACENT memory; + register-variety rows (calm beats, plain-answer directives; not every row echo-and-twist) |
| P49 | — | 0 | — | BLOCKER: no representable bank shape (enum-classifier sub-call is a separate grammar-locked generation path); banklib/validator/composer are off-limits. Report it; do not force rows |
| P50_longform.py | 8 | longform ~600-800 | 4-6 | 6-tuple (card, player, reply, kind, fact, guid); kind story/news/gossip/bonded; 80-150w; TOOL-FREE; guids cycle 0/1/2 so all 3 frame flavors train; the cue NEVER appears in the bank (composed at merge) |
| P51_depth_arcs.py | ~12 | ~300 arcs x 12-24 turns | ~14-18 (10-20 arcs each) | arc dict {"card", "tier", "guid", "turns", "longform": [i], "longform_facts": {i: fact}}; >=3 FORCED callbacks per arc (name things in turn 2-4 the reply must use again turns 8+); 1-3 TOOL turns MID-arc (the dilution compensation); ~1 cued longform turn per arc (80-150w, or 80-130 if tooled — flag it in "longform"); terse turns inside; scenario mix: betrayal->restitution, rescue, long-lost-friend, mourning, rumor investigation, bar-story contest, travel/camp with ACT tools |
| P52_ambient_barks.py | 8 | murmur ~150-250 | 2 | cards need murmur_demeanor/murmur_quirk/murmur_gripe fields (see smoke fixture); row = (card, event, listener, reply); 8-20 words; event-grounded; the frozen murmur wording never appears in the bank |
| P53_persona_sessions.py (NEW NUMBER — the §5 table row had no id) | ~10 | 60 sessions x 12-16 turns | 4-6 | sessions LOCKED to one card each (persona-dip fix .778->.5); session = {"card", "seed", "turns": [(player, reply[, tools][, state][, mem]), ...]}; states may vary but the PERSONA never drifts; ~10 cards x 6 sessions |
| P54_topups.py (NEW NUMBER) | 4 companion | ~190 | 1-2 | q08 adjust_sentiment beat rows (~60 — the measured gap); nickname-adoption rows (~30, S8-ledger (b), tier-5 ceremony surface); A16 meetup-initiation (~20, tier-5 bonded); A17 dusk-appointment reminders (~20, tier-3+); protocol token-mass top-up (~60) — placed inside P51 arcs AND here per the standing rule |

Mandated reworks (the ONLY edits to landed content): P21(h) rows (above) +
current phrase-ledger offenders (run `phrase_ledger.py`, rework
editable-flagged offenders in place per B3; authored rows are skipped).
Everything else is ADD, never rewrite.

## 3. DRAFTER PROTOCOL (how to spawn)

1. You (orchestrator) author each bank's CARDS first (skeleton file with
   empty BANKS) — cards are the consistency layer; bibles >=200 chars with
   2 example exchanges ("Example exchanges:" must appear); ASCII; avoid
   era terms and frozen strings in bibles. Card ids unique dataset-wide.
2. Build a self-contained dispatch prompt per drafter: mission; files to
   read (SPEC_common.md, the bank skeleton, TEMPLATE_bank.py); the exact
   row tuple shapes WITH one example each; its card + row-count
   assignment; its nonce slice (P45: read the JSON, explicit index
   ranges); mood/kind/tier distribution targets; question-rate target;
   opener-menu discipline; its part-file path. Drafters write
   `G:\NPU LLM\finetune-data\drafts\PXX_dN.py` containing
   `PART = {"<bank>": [rows...], ...}` — rows only, no PACKAGE/cards.
3. Dispatch >=2 drafters per bank in ONE message (foreground, parallel).
   P51 needs the most (~14-18 total, batched 4-6 per message).
4. Merge parts deterministically (small script in tmp/, Write-tool
   created): final bank = skeleton cards + concatenated part rows.
   Track part origin for reject-rate logs.
5. Edit pass (you): fix validator violations, opener collisions, 6-gram
   dups; rewrite (don't debate) any row that quotes a frozen string.
6. Gates per bank (§6 of the original brief): validate CLEAN ->
   phrase_ledger pass -> draft_dedup 13-gram gate. Then compose_banks
   merge (fail-loud) + the 157-battery.

## 4. VALIDATOR CRAFT NOTES (the reject reasons — brief the drafters)

- Opener law is per CARD across ALL banks in the file: a first word max
  TWICE per card. With ~60 rows/card plan 30+ openers per card.
- L5 in-file 6-gram dup set is GLOBAL across the file's banks (guard +
  free together) — prose diversity is the lever, and merge re-checks
  cross-bank.
- Guard replies (L45): NO new capitalized proper nouns mid-sentence
  except the nonce itself + the §1 allowlist; sentence-START capitals are
  exempt but the doctrine is still "no invented names at all". Deny +
  pivot to the player or a real known name; ZERO speculation; NEVER a
  replacement entity ("the gnome who ran the smithy is dead" is the
  failure shape).
- Negatives must be FREE-shape, not guard-shape: guard_turn ALWAYS injects
  the A10 directive at merge — a known-entity row in the guard bank would
  train "deny a name you know". Free rows carry no directive.
- check_reply bands: default 6-110w; guard/free negatives 18-60w target;
  P50 80-150; arc longform 80-150 (80-130 if the turn carries tools);
  P52 8-20. Address words <=1/6 rows (law is 1/4 — margin). "not X, but
  Y" <=2/card. No "<<" in prose. ASCII, straight quotes, " - " for dash.
- Player lines: human register (lowercase, typos, slang, RP emote-speak,
  jargon); era words allowed in PLAYER lines ONLY for P46 trap rows.
- Question-rate: record per-bank share of replies ending in "?" (the G5
  baseline; guard ~55%, others ~33%).

## 5. BUDGETS + EVIDENCE (per bank, in finetune-data/reports/)

- Token-mass: assistant TOKENS (~1.45 tok/word). Long rows (>=80w) stay
  <=10-20% of assistant token mass corpus-wide — compute the actual share
  from the composed merge and report it (estimate with P50+P51 cued turns
  included: it lands ~14% if P51 terse turns are as specified).
- Protocol-row share: measure the protocol share of assistant token mass
  in the CURRENT v2_all.jsonl (13,673 examples; 109 sessions) BEFORE
  authoring, then after the merge; top up to >= previous proportion
  (mid-arc P51 tool turns + P54). Track per-family fire calibration
  intent (adjust_sentiment emphasis per §1).
- Per-drafter reject logs; question-rate baselines; nonce lists (train
  vs held-out); 150-word cap respected; L3b/L3c scoped to P50/P51/P52.
- Persist artifacts BEFORE the record citing them; claim only what an
  artifact records.

## 6. CLOSE-OUT (unchanged from the original brief §8-9, condensed)

1. All banks validate CLEAN; per-batch phrase_ledger + draft_dedup;
   compose_banks merge succeeds; 157-battery green; three locks green.
2. 6-reviewer panel (spawned agents, ONE message, six roles:
   data-methodology/budget compliance, lore+era correctness,
   validator+hygiene, wording-lock+repo coupling, evidence/process,
   adversarial dedup+bleed) -> fix to ZERO P0/P1.
3. Append the S11 round-5 record to LLM-INTEGRATION-STAGES.md (dated
   round, evidence lines naming artifact files, deferred items; record
   the R2 owner directive + the P49 blocker + the P53/P54 numbering
   decisions); update the S11 status row + the handoff remaining-scope
   paragraph.
4. Provenance flag carried forward: all drafted rows are machine-authored
   (spawned-agent drafters, human-unread) — flag sampled rows for the
   owner's spot-review.
5. Nothing committed; no out-of-scope file touched (native/, android/,
   tests/, bench-harness, banklib, BULLETINS.md, parallel-stream dirty
   files); never edit repo files via bash heredocs.
