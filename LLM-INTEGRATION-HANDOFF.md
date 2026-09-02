# LLM INTEGRATION — Agent Handoff (2026-08-30, written after S8 close)

For the agent resuming `LLM-INTEGRATION-STAGES.md` execution. Read
`LLM-INTEGRATION.md` (master plan) and `LLM-INTEGRATION-STAGES.md`
(protocol + per-stage records with every review round) first. This file
is the working-state snapshot; the stage records are the authoritative
history.

---

## 1. Where the plan stands

| Stage | Status |
|---|---|
| S1 registry + tier sampling | PASSED (2 rounds) — pre-existing |
| S2 JSON client + empty-content guard (A9) | PASSED (4 rounds) — pre-existing |
| S3 backend unification (A0) | PASSED (3 rounds) — pre-existing |
| S4 trained prompt format + prompt-dump (A4/A5/A6) | PASSED (3 rounds) — pre-existing |
| S5 bridge notes + TOOLS_NOTE (A7 → A1) | PASSED (2 rounds + post-gate fix) — pre-existing |
| S6 ACT tools + emotes + duel hook (A2/A3/A14) | PASSED (4 rounds) — pre-existing |
| S7 truth guards (A10/A11/A12) | PASSED (3 rounds) — pre-existing |
| S8 memory-use + believability beats (A13/A16-A19) | **PASSED (2 rounds + post-gate folds)** |
| **S9 player surface (E1-E5)** | **← NEXT** |
| S10 v2.3 data + retrain | pending |

All verification green at handoff: 104 LLM host gates (30 recall +
28 act_tools + 5 json + 10 banter + 8 a0 + 4 prompt-format + 9 truth +
9 sqlite-dialect + 1 lockfile pin); all four native lanes rebuilt with
the final S8 state and the lockfile-freshness gate passes (the sqlite
x86_64 lane verified manually per close, this session included).
Nothing is committed — everything lives in the working tree of branch
`feature/universal-client-installer` alongside unrelated parallel
streams (installer/database/Vulkan/AndroidPort); do NOT touch or
revert those. tools/llm_lab is now staged in git (S6-ledger (l)
discharged at S7; s8_beats_gates.py staged this session).

## 2. What the shipped code now does (S8 summary — read the S6/S7
## record summaries in the stages file for the earlier layers)

**A13 recall beats** (the recallfix measured table): the bridge ladder
is ACT > insult > gratitude > debt-question > memory-question >
news-recall > greeting-gap (gossip > grudge > weave, once per absence
gap) > first-meeting > gossip-window > laughter. Every recall beat
supplies the fact as note.extra CARGO (DebtCargo/NewsCargo/MemoryCargo/
Grudge/Gossip — pure core `PlayerbotLlmRecallCore.h`, byte-battery-
pinned) and marks the note `mandatesContent`. The A12 dedupe reroll
exempts the generation's OWN stamped note as a per-bot 60s-budget CLAIM
(`NoteMandatesContent` — stamp-checked, rate-capped; the spam residual
at 61s+ cadence was ruled within intent). The MASKS are
`FACT_MASK_*` constants — a raw enum value is NOT its mask (round-1's
P0; pinned + mutation-tested).

**A16**: `GetPreStompState` (ONE query: absence + tier, pre-stomp — the
S5 law generalized); the ceremony fires on OBSERVED transitions (ACT
turns PEEK without consuming; restarts/restarts-swallowed-crossings
recorded); tier-4 releases the one-time secret via the licensed
`secret told:` log_fact line (probe category-constrained to
player-identity — the forged-prefix vector is closed); tier-5 gets the
sysm nickname tierNote + the adoption procedure on the ceremony; the
give_item fill carries the friend's-price procedure at tier>=4.

**A17**: `TickInitiative` (world thread): greet-first arrival packets
(GreetingLine + absence magnitude + town talk), debt reminders
(MoneyPhrase), tier-3 goal ask-afters, the level-up cheer (the event
note licenses `perform_emote cheer`), each fact initiating at most
once; every class shares the ambient slot with the 600s zero-spam cap;
eligibility precedes the claim. EventReaction carries
eventKind/notBefore/emote; the drain waits the stagger window.

**A18**: crowd tier on non-trigger /say (deterministic text emotes via
the shared `PlayTextEmote`, 2-5s notBefore stagger, 12s world window,
1/10 roll); say ANSWERS stagger 2-5s through the new
`QueueChatResponse(..., delaySecs)` param (non-mention says undelayed);
say-history cross-injection capped at 5; rare authored bot2bot
exchange on arrival (paired opener/reply, reply on /say).

**A19**: OnDuelComplete writes the bot's own fact + a player-subject
world_gossip row; `GossipAbout` (newest-8, in-code case-sensitive
word-boundary match) renders on the greeting surfaces with a belief
row logged one `DistortGossipHop` hop deep. The persona-fallback
first-meeting fold landed; the whisper greeting intercept carries
magnitude + town talk; `(tone ±)` prefixes carry the sign and never
render raw.

Key files: `PlayerbotLlmRecallCore.h` (NEW), `PlayerbotLlmBridge.*`,
`PlayerbotLlmMemory.*`, `PlayerbotLlmTools.*` (PlayTextEmote
extracted); driver anchors: PB_SAY_CHATREPLY (eventKind),
PB_SAY_CONTEXT (pre-stomp + folds), PB_SAY_PROMPT_V2, PB_SAY_GATE
(crowd), the voice-filter exemption, PB_UPDATEAI (drain +
TickInitiative), PB_AI_QUEUE_{DECL,DEF,CALL} (NEW — say stagger),
PB_LLM_CONF doc; tests `tests/test_llm_recall.py` +
`tools/test_llm_recall.cpp` (NEW); harness `tools/llm_lab/
s8_beats_gates.py` (staged).

## 3. S8 verification artifacts of record (C:\llm-lab\results\)

- `s8_beats_e2b-tuned_20260830-220238.json` — the FINAL S8 gate run
  (post-round-1 fixes): ASSOCIATIVE 4/4 (lift honestly split 2/4 — the
  debt/memory controls also majority-recall; controls recorded per
  case), CEREMONY floor 5/5 + persisted controls (human-read 4/5,
  judged twice independently), EVENT_KIND cheer 3/3 + duel sentiment
  2/3 strict / 3/3 tolerant (the single-'>' class), JACCARD 0.209
  none-over (the S7-ledger-(d) discharge). Earlier runs 204232/204501/
  204615 document the iteration (204615 = pre-round-1-fix state).
- `n3_trained_e2b-tuned_s8-a13a16_20260830-210414.json` — trained
  composite: 6/6 families majority + S2 all-clean (adjust_sentiment
  1.00; share_gossip 0.67 boundary). No see-saw.
- `n3_legacy-composite_e2b-tuned_s8-a13a16_20260830-210459.json` —
  legacy: 5/6 (share_gossip 0.33, the known family; S7's legacy was
  4/6 — no aggregate regression).
- All S6/S7 artifacts of record unchanged (see the S7 handoff text in
  git history if needed).

## 4. Remaining stages — scope + binding conditions

- **S9** (E1-E5): pacing law (ack <1s — whispers already noDelay; the
  say stagger is 2-5s and must NOT touch whispers), onboarding, Talk
  UI, progression lines, model-picker. **BINDING ENTRY GATE (S4
  record): §4.4's template machinery must land before/with the picker**
  (non-thinking chat_template.jinja asset, warm-up probe,
  `--chat-template-file` verify, LlmRuntimeConfig template-file field
  WITH serialVersionUID). T4 connect timeout (30s gen + 10s connect)
  lands here. **S8-added S9 items (ledger (n))**: the LlmScreen
  banter-toggle copy now under-describes the switch (initiative/crowd/
  greet-first all ride LLMBanterEnabled); the debug-build
  llmOverrides block omits LLMBanterEnabled (the native default 1 runs
  the authored-initiative layer regardless of the toggle). Also S9 owns
  the E-surface pacing interaction with A18's say stagger + MsPerChar.
- **S10** (§5 v2.3 banks P45-P49 + retrain arm2′/arm1b′/2B + G0-G5):
  **BINDING (S5 C2): the "tools ≥ .93" gate converts to PER-FAMILY
  acceptance (per-trap pass), explicitly gating adjust_sentiment
  majority-fire on arm2′** (S8's event-leg measured the family again:
  2/3 strict / 3/3 tolerant of the single-'>' closer). Also binding:
  the keyed-emote harness conversion (prtools2/3/4+ambition), the
  scorer rebuild, persona-locked sessions, q08 family gaps, the
  fieldless `<<perform_emote laugh>>` acceptance (S6-ledger (j)), and
  NOW the S8 additions: **nickname-adoption bank rows** (ledger (b) —
  the procedure measured 1/6 pooled; weights-side is the fix), the A16
  meetup-initiation + A17 dusk appointments deferrals (ledger (a)),
  and the A10 class-2/3 + denial-mood P45 items (S7/S8 ledger (m)).
  Banklib is the source of truth (`G:\NPU LLM\scripts\finetune\
  banklib.py`). Device legs owed: A17's ≥3/30-min idle-adjacency gate,
  A18's in-world pacing, S8 ledger (c).

Deferred-P2 ledger: the S8 record lists (a)-(n) — consult before
"fixing" anything already accepted. Earlier stage ledgers live in
their records (S6 (a)-(n), S7 (a)-(l)).

## 5. Operational mechanics (hard rules, learned the hard way)

- **Native lane builds**: `python tools/build_o09_realm_runtime.py
  --abi {arm64-v8a|x86_64} --backend {mysql|sqlite}` (~3-5 min each).
  NEVER run two concurrently (shared cmangos submodule). At every stage
  close rebuild ALL FOUR and re-run the lockfile-freshness test (pins
  3 of 4; the sqlite x86_64 lane is verified manually per close —
  hash-compare `patches_content` against `native/patches/`). Recovery
  on a dirty submodule: `git -C native/cmangos checkout -- . &&
  git -C native/cmangos clean -fd src/game/Chat/PocketRealmInteraction.cpp`.
  Playerbots-module anchors (SayAction/PlayerbotAI/etc.) need NO
  restore entries (the mirror is wiped per build); CORE-tree anchors
  do (the S6 rule).
- **Edit mechanics**: SayAction/PlayerbotLLMInterface/PlayerbotAIConfig/
  PlayerbotAI/RpgSubActions/DebugAction/Player.cpp/Unit.cpp edits =
  driver-anchor patches (`*_UPSTREAM` must match the pristine submodule
  byte-exactly — trailing whitespace included, the QUEUE_CALL anchor
  carries 5 trailing spaces). New files = overlays in
  `native/patches/playerbots/` + copy line in prepare_cmangos_source +
  PLAYERBOTS_OVERLAYS manifest entry (RecallCore.h is the S8 example).
- **DO NOT edit repo files via bash heredocs** — a heredoc mangled the
  S8 stage record's first append (truncated mid-file) and python
  escapes silently no-op'd twice. Use the Edit/Write tools.
- **Host batteries**: `g++ -std=c++11 -O2 -Wall -I
  native/patches/playerbots -o tmp/x.exe tools/x.cpp`; pytest wrappers
  follow the existing pattern. Full gate = 104. RecallCore's `uint32_t`
  (NOT `uint32`) in pure-header signatures — mangos typedefs don't
  exist on the host.
- **Kotlin tests**: `cd android && ./gradlew.bat :app:testDebugUnitTest
  --tests "..." -PpocketAbi=arm64-v8a -PsqliteProvider`. S8 touched no
  Kotlin. Known pre-existing failures elsewhere: 4-5 AndroidPort
  addon-asset tests (parallel streams' working tree).
- **Desktop LLM work**: server `G:\NPU LLM\tools\llama-cuda-b10520\
  llama-server.exe`; models on `G:\NPU LLM\models\`; results in
  `C:\llm-lab\results\` (versioned filenames — never fixed names).
  Harnesses: sanity_battery, run_n3_checkpoint (both composites at
  every prompt change), s6_act_chain, s7_truth_gates,
  **s8_beats_gates** (ASSOCIATIVE/CEREMONY/EVENT_KIND/JACCARD).
- **Review protocol**: 6 roles R1 native / R2 Kotlin / R3 tests / R4
  laws / R5 systems / R6 red-team. Background agents are UNSUPPORTED —
  dispatch as foreground Agent calls in ONE message. Rounds until ZERO
  P0/P1 (S8: 6 → 3 rotated). **Write the stage record only AFTER the
  closing gate returns.** Evidence-hygiene law: comments/docs/records
  claim only what an artifact records; mutation-test string-find pins
  (S8 round-2 killed 4/4 mutations); harness mirrors measure INTENT —
  pin the plumbing separately (the round-1 mask P0).
- **Injection-hygiene law** (binding): StripAstral → ScrubControlTokens
  → NeuterMarkersCopy → TruncUtf8 → EscapeSql at every persistence
  write, neuter LAST. Reserved prefixes ("secret told:", "(tone ±)")
  are forgeable through model-filled text — constrain by CATEGORY, not
  text alone (the S8 round-1 fix class).
- **See-saw discipline** (§3.0): ONE prompt-text change per checkpoint;
  n=3 scored per family; BOTH composites when either changes; the S8
  record carries the combined A13+A16 attribution caveat — a future
  per-family regression cannot be bisected between them without
  revert, so the prompt-snapshot must cover both.
