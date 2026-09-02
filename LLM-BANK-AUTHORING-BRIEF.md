# LLM-BANK-AUTHORING-BRIEF.md — execution order for the S11 bank-authoring agent

Written 2026-09-01, after S11 rounds 0-4 (the scaffolding, couplings, and
the G0 harness conversion are DONE and verified). **Your single mission is
bank authoring: write banks P45-P52 (+ persona-locked sessions + the
mandated reworks) so the retrain/gates agent can run arm2′/arm1b′/2B and
G0-G5.** You are the authoring agent, not the reviewer of prior work and
not the trainer. Do not re-litigate rounds 0-4; do not run gates; do not
touch training. Everything you need is below or pointed at.

Machine: Windows, Git Bash shell, repo at `C:\pocket_realm_complete`
(git branch `feature/universal-client-installer`, NOTHING is committed —
committing is the owner's call). Authoring tree on `G:\NPU LLM`.

## 0. Mission framing (read once, take literally)

IN SCOPE — authoring only:
- banks P45-P52 as `G:\NPU LLM\finetune-data\banks\PXX_<name>.py` files
  (copy `G:\NPU LLM\finetune-data\TEMPLATE_bank.py`; P01-P44 exist —
  numbering, conventions, and the reports/ layout are established)
- persona-locked session rows (~60; see §5 table row "persona-locked
  sessions")
- the ONLY reworks of landed content: P21(h) hedge-then-guess rows and
  phrase-ledger offenders ("ADD, never rewrite" — §5.1's ruling)
- targeted top-ups: q08 per-family tool gaps, nickname-adoption rows
  (S8 ledger (b)), the A16 meetup-initiation + A17 dusk-appointment rows
  (S8 ledger (a) deferrals), tool-row dilution top-up in token mass
- per-batch evidence: validator/ledger/dedup logs + per-provider reject
  rates in `finetune-data/reports/`
- a 6-reviewer panel on the authored banks, then the S11 round-5 record

OUT OF SCOPE — hands off:
- native/ (C++ bridge/cores), android/ Kotlin, tools/llm_lab, tests/,
  and ALL of `G:\NPU LLM\scripts\bench-harness\` (the harness conversion
  converged at zero P0/P1 through 3 panel rounds; it is pinned by
  tests/test_llm_prtools_harness.py and an 18/18 mutation pass)
- training runs (train_arm*.py / train_e2b.py) and the G0-G5 gates
- the parallel streams' dirty files (installer/database/Vulkan/
  AndroidPort) — never touch or revert them
- `finetune-data/BULLETINS.md` — governance: read it, never edit it
  (B7; authoring agents put disputes in their package report and REPORT
  any bulletin edit they did not make)

## 1. Read-first list (in this order)

1. `C:\pocket_realm_complete\LLM-INTEGRATION-HANDOFF.md` — §1 state,
   §2 what's shipped, §4 binding conditions, operational laws.
2. `C:\pocket_realm_complete\LLM-INTEGRATION.md` §5 + §5.1 — the bank
   table (P45-P52 rows, ~lines 770-800) and the full API-drafter
   protocol (~lines 842-950). These two sections ARE your spec.
3. `C:\pocket_realm_complete\LLM-INTEGRATION-STAGES.md` — the S11 record
   (rounds 0-4 + ledger (a)-(l)); ledger (c) is the provenance flag you
   must carry forward, and the round-4 series explains what the harness
   now scores (copy-fidelity, first-shot reporting).
4. `G:\NPU LLM\finetune-data\BULLETINS.md` — standing directives (B1-B11
   active); the validator reprints them on every run.
5. `G:\NPU LLM\scripts\finetune\banklib.py` — THE source of truth for
   row shapes, bands, and frozen strings. The S11 additions you will
   use: `guard_turn` / `longform_turn` / `murmur_turn` renderers,
   `arc_turn(..., longform_fact=, bot_guid=)`, `beat_frame(kind,
   bot_guid)` + `BEAT_CARGO_VARIANTS` (3 flavors x 8 kinds),
   `LONGFORM_CUE` + `longform_extra(cargo)`, `check_reply(..., band=)`
   (L3b 80-150 cue-bearing only, L3c 8-20 murmur only), `TOOLS_NOTE`
   A-D variants. Where the TEMPLATE's prose and banklib disagree
   (e.g. the template's L8 emote list is stale prose), banklib wins.
6. `C:\pocket_realm_complete\tmp\p45_synthetic_smoke.py` — 11-row
   synthetic fixture proving the validator/composer branches (guard/
   longform/murmur/arc). Copy its row shapes; do not copy its
   deliberately-failing rows into real banks.
7. `G:\NPU LLM\finetune-data\docs\dataset-coverage.md` +
   `docs/dataset-master-plan.md` SECTION 2 — coverage conventions and
   the authoring laws the validator enforces.

## 2. Before writing row one: verify the locks, then treat frozen text as INPUT

Run all three and record the output (they must be green before AND after
your work — you are not allowed to break them, which is why you cannot
"improve" any frozen string):

```
python tools/llm_lab/emit_prompt_constants.py --check     # banklib -> C++
python tools/llm_lab/extract_bridge_wording.py --check    # C++ -> bridge_wording.py
python -m pytest tests/test_llm_prtools_harness.py -q     # incl. prtools3.TOOLS_NOTE == banklib.TOOLS_NOTE
```

FROZEN (byte-locked, banks must never restate them in replies —
`_frozen_literal_scan` in validate_banks.py rejects quoted cue/frame/
guard/murmur tokens): the long-form cue, the beat-cargo variant frames,
ceremony phrases, TOOLS_NOTE A-D, the guard directive, murmur texts,
no-narrate. If you believe a frozen string must change, STOP and write
the objection in your package report — that is a coordinated
banks+bridge+C++ change with re-emission and pin updates, not an
authoring edit, and this session it is additionally flagged for human
review (S11 ledger (c): agent-authored, human-unread).

## 3. The banks (counts and conditions from the §5 table; the table wins on any conflict)

- **P45 entity-hedge** ~400 positive + ~100 negative controls. All eight
  conditions (a)-(h) in the table row bind: guard-bank row shape via
  banklib's guard_turn (the shape choice (a) is effectively made —
  record it); A10 directive text frozen; invented names from a fixed
  syllable pool validated absent from the lore corpus, the 158 card
  names, player pools; deny + pivot, ZERO speculation, never invent a
  replacement entity; denial-mood classes rotated (suspicion/dismissal/
  deflect-to-authority/humor/worry, persona- and ledger-varied);
  negatives = known-entity questions answered confidently + no-proper-
  noun vague questions answered normally; **held-out nonce names are
  EXCLUDED from training and persisted (a list file under reports/) for
  the G5 hedge gate**; P21(h) hedge-then-guess rows reworked in the
  same change or the banks fight in weights. Includes the A10 class-2/3
  + denial-mood items (S7/S8 ledger (m)).
- **P46 era-deflection** ~300. Corrected 1.12-aware traps (flying-mount
  phrasing, not "can you fly"; no worgen-as-vanilla). NEVER affirm a
  false era, never echo the era term; era-TRUE affirmations included
  (Naxx 1.11, Scourge invasion) — deflection must not deny events the
  NPC lived through.
- **P47 card-grounded lore** ~500 on the poi_turn shape. Answers
  FROM-card in voice; off-card pairs hedge; weights-trivia capped ~100
  ultra-common facts; **card spans must sample the SHIPPED asset's real
  length spread up to its ~1,166-char max (rev 3c — the old 468-char
  max is a measured distribution gap; close it)**.
- **P48 associative recall** ~300. Planted-fact turns whose ANSWER
  reaches for an adjacent memory + register-variety rows (calm beats
  without the echo-and-twist device; plain-answer directive rows).
- **P49 classifier sub-call** ~150 — table says "already queued (Qwen
  intent gap)": verify whether rows exist; author only the gap.
- **P50 long-form** ~600-800, 90-150 words, cue-bearing ONLY
  (storytelling / news-recall deep-dive / gossip / tier-5 Bonded).
  All §5.1 budgets bind (token-mass share, 150-word hard cap, L3b).
  The honest baseline to beat is recorded: cued 32-61 words, 0/9 >90w;
  uncued 0/9 >60w (artifact s8_beats_...194021.json) — P50 exists to
  move the cued distribution UP without moving the uncued default.
- **P51 depth arcs** ~300-400 arc rows x 12-24 turns (multi-turn mass
  ~127 -> ~450-500). >=3 FORCED callbacks per arc (the quality lever);
  terse turns INSIDE arcs; **protocol (tool) turns placed MID-arc**;
  loss on ALL assistant turns. Use arc_turn(..., longform_fact=,
  bot_guid=) for the cue-bearing variants.
- **P52 ambient barks** ~150-250. The MURMUR register: event row -> ONE
  8-20-word in-voice line, tool-free, no opener convention (L3c).
- **persona-locked sessions** ~60 — sessions locked to single persona
  bibles (the persona-dip fix, .778 -> .5).
- **Targeted top-ups** (small, evidence-driven): q08 per-family tool
  gaps — read C:\llm-lab\results\n3_*_s11-round0_*.json + the S5-C2/
  S8 records to see which families the q08 arm fails (adjust_sentiment
  majority-fire is the known one) and add rows for exactly those;
  nickname-adoption rows (S8 ledger (b), measured 1/6 pooled);
  A16 meetup-initiation + A17 dusk-appointment rows (S8 ledger (a));
  tool-row dilution top-up — ~1,710 v2.3 rows + P50/P51 dilute the
  protocol rows, and the compensation is counted in TOKEN MASS, not
  row count (LLM-INTEGRATION.md ~lines 797-805: place protocol turns
  INSIDE P51 arcs, track per-family fire calibration).

## 4. The authoring protocol (§5.1 — condensed; the doc section governs)

- **ADD, never rewrite.** The 13.7k short rows are the terse-default
  calibration AND the human anchor. Only P21(h) + phrase-ledger
  offenders are reworked. The ">=10% human data" rule is RETIRED.
- **Multi-source drafting**: >=2 providers per bank; NON-Qwen providers
  for the Qwen arms. Few-shot seed each draft from real corpus rows in
  the target card's voice -> edit pass -> validate CLEAN -> phrase
  ledger -> dedup -> compose. See §5 below for sourcing on THIS machine.
- **ToS before scale**: check training-on-outputs terms BEFORE drafting
  at scale, or use open-weight local drafters (sidesteps it entirely).
- **Draft-dedup gate**: every draft batch vs the frozen corpus via
  `python "G:\NPU LLM\scripts\finetune\draft_dedup.py" "G:\NPU LLM\
  finetune-data\banks\PXX_<name>.py"` (13-gram; corpus side self-loads,
  ~310k distinct 13-grams / 228 sources). Explicit named gate per batch.
- **Per-provider reject log**: validator reject rates per provider in
  reports/ (style fingerprinting; catches silent model swaps).
- **Wording lock generalized**: cue strings frozen before authoring;
  banks train the byte strings the bridge injects.
- **Budgets**: long rows (>=80 words) <=10-20% of assistant TOKEN mass;
  150-word HARD cap; L3b only for cue-bearing P50/P51 rows; L3c only
  for P52; never a global cap raise. Report the token-mass share per
  bank in your evidence.
- **Question-rate counter-metric**: record the share of replies ending
  in a question per persona/beat for your banks (the G5 baseline; the
  guard beat must not raise it).
- **Identity + hygiene laws**: never write a player name — use the
  {player} token (B2); L1-L10 per the validator; keyed tool syntax
  exactly (`<<perform_emote emote="laugh">>` — the banks train the
  keyed form; the fieldless form is a runtime tolerance, NOT a target).

## 5. Drafter sourcing on this machine (decide first, record the decision)

Default path: **local open-weight drafters via llama-server**
(`G:\NPU LLM\tools\llama-cuda-b10520\llama-server.exe`) — ToS-clean per
§5.1(b). Inventory the models available under `G:\NPU LLM` (models dir
+ `scripts/bench-harness/screen-models.json`) and pick >=2 DISTINCT
families (multi-source), keeping Qwen-family models OFF the Qwen-arm
drafting. Record the chosen drafters + rationale in your package
report. If only one family is available locally, that is a genuine
blocker: stop and ask the owner for either a second local model or API
access — do NOT silently single-source, and do NOT invent API keys.
Measurement/probe artifacts, if any, go to `C:\llm-lab\results\` with
versioned filenames.

## 6. Verification loop

Per bank, iterate to CLEAN:
```
python "G:\NPU LLM\scripts\finetune\validate_banks.py" "G:\NPU LLM\finetune-data\banks\PXX_<name>.py"
python "G:\NPU LLM\scripts\finetune\phrase_ledger.py"   # per-batch pass
python "G:\NPU LLM\scripts\finetune\draft_dedup.py" "G:\NPU LLM\finetune-data\banks\PXX_<name>.py"
```
Then the merge build:
```
python "G:\NPU LLM\scripts\finetune\compose_banks.py"   # fail-loud; forbidden-set extended with the S11 frozen strings
```
And the repo regression net (must stay green; it contains the wording
locks that pin your inputs):
```
python -m pytest tests/test_llm_player_surface.py tests/test_llm_act_tools.py tests/test_llm_recall.py tests/test_llm_truth.py tests/test_llm_banter.py tests/test_llm_a0_unification.py tests/test_llm_json_client.py tests/test_llm_prompt_format.py tests/test_llm_chatter.py tests/test_llm_prtools_harness.py tests/test_sqlite_dialect.py tests/test_mariadb_lockfile_pins.py -q
# 157 passed at handoff; any drift is yours to explain
```

## 7. Hard constraints (the ones that bite)

- NEVER edit repo files via bash heredocs — Edit/Write tools only (two
  prior incidents; it is a written law).
- Evidence hygiene: persist artifacts BEFORE writing the record that
  cites them; claim only what an artifact records.
- Nothing is committed. Parallel-stream files stay untouched.
- banklib's frozen strings, the bench-harness files, native/, android/,
  and tests/ are all off-limits to edits.
- The banks may not quote frozen strings in replies; the validator
  enforces it, but do not fight the validator — rewrite the row.

## 8. Records, review, and done

- Keep a per-bank report in `finetune-data/reports/`: rows authored,
  word/token-mass stats, providers used + per-provider reject rates,
  dedup stats, question-rate baseline, nonce-pool/held-out lists.
- When the banks validate/compose CLEAN and the evidence is persisted:
  run a **6-reviewer panel** (the house convention — six roles, one
  message, foreground agents; suggested roles: data-methodology/
  budget compliance, lore+era correctness, validator+hygiene, wording-
  lock+repo coupling, evidence/process, adversarial dedup+bleed).
  Fix to zero P0/P1, then append the **S11 round-5 record** to
  LLM-INTEGRATION-STAGES.md (house style: dated round entry, evidence
  line naming artifact files, deferred items) and update the S11 status
  row + LLM-INTEGRATION-HANDOFF.md remaining-scope paragraph.
- Carry the provenance flag forward: drafted rows are machine-authored;
  flag sampled rows for the owner's human spot-review in your record.

## 9. Done checklist

[ ] P45-P52 + persona sessions + mandated reworks + top-ups authored
[ ] Every bank validate CLEAN; phrase_ledger pass per batch; draft_dedup
    gate per batch; compose_banks merge succeeds
[ ] Token-mass budgets reported; 150-word cap respected; L3b/L3c scoped
[ ] Held-out nonce list persisted and NOT trained
[ ] Per-provider reject logs + question-rate baselines in reports/
[ ] The three wording locks + the 157-gate battery green after all work
[ ] 6-reviewer panel to zero P0/P1
[ ] S11 round-5 record + handoff updated; provenance flag carried
[ ] Nothing committed; no out-of-scope file touched
