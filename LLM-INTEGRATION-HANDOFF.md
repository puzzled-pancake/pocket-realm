# LLM INTEGRATION — Agent Handoff (2026-08-31, rewritten after S10 close)

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
| S8 memory-use + believability beats (A13/A16-A19) | PASSED (2 rounds + post-gate folds) — pre-existing |
| S9 player surface (E1-E5 + §4.4 + T4 + S8-(n)) | PASSED (2 rounds + post-gate folds) — pre-existing |
| S10 E6 world chatter (§4.6b) | PASSED (4 rounds + post-gate folds) — pre-existing |
| **S11 v2.3 + v2.4 (long-form/depth) data + retrain** | **IN PROGRESS — rounds 0-5 landed: scaffolding, couplings, G0 harness conversion, and the BANKS (P45-P54 authored, gated, composed — 16,953 examples); retrain arms + G0-G5 pending — ← NEXT** |

All verification green at this checkpoint: **157 LLM host gates** (129
in the nine rounds-0-3 LLM test files: 19 player-surface + 29
act_tools + 33 recall + 9 truth + 10 banter + 8 a0 + 5 json + 5
prompt-format + 11 chatter; + 17 prtools-harness pins in
tests/test_llm_prtools_harness.py (rounds 4-4c); + 9 sqlite-dialect + 2
lockfile pins); mutation 22/22 (tmp/mutation_s11_final.log) + 18/18 on
the round-4 pins (tmp/mutation_s11_r4.log); all four native lanes
rebuilt and all four lockfiles hash-fresh (the sqlite x86_64 lane
verified manually — 27 entries, zero mismatches); Kotlin LLM suites
green (the 4-5 AndroidPort/AddonCatalog failures + tree-wide red
detekt remain the parallel stream's, untouched). Nothing is committed
— everything lives in the working tree of branch
`feature/universal-client-installer` alongside unrelated parallel
streams (installer/database/Vulkan/AndroidPort); do NOT touch or
revert those.

## 2. What the shipped code now does (S11 rounds 0-4 — read the S6-S9
## record summaries in the stages file for the earlier layers; the S10
## summary follows below)

**The §5.1 scaffolding is landed and the wording lock is now
MECHANICAL in both directions + STRUCTURAL in the bank pipeline.**
Tier max_tokens raised (T1 230 / T2 210 / T3 210 / T4 300 unchanged)
with the arithmetic pinned in tests and docs. The beat-cargo builders
speak persona-flavored VARIANT SETS (banklib BEAT_CARGO_VARIANTS, 3
flavors × 8 kinds, flavor 0 = the S8 measured wording byte-preserved;
selection `flavor = bot_guid % 3`, one flavor per bot for life;
static_asserts pin set uniformity). The ONE frozen long-form cue
(LONGFORM_CUE → kLongFormCue) rides exactly three bridge rungs
(storytelling ask / tier-5 bonded open-confidence / news deep-dive),
each gated on LongFormLicensed(maxNewTokens >= 225) AND anchored to a
real DB fact; each marks its note longFormCued, and the reply budget
widens to 4×255 ONLY for a cued turn on a licensed tier (the flag
threads Note → ToolLicense → NoteLongFormCued, resolved on the world
thread as a by-value std::async argument — a superseding note can
neither widen nor narrow an in-flight generation; uncued turns keep
2×160 on every tier; ambient stays 1×80; the RPG site passes false
explicitly because defaults do not bind through the async function
pointer — a lane-compile lesson). The fieldless `<<perform_emote
laugh>>` form now folds into emote=laugh at parse (pure form only;
executor field authority unchanged). On the G: side: guard/longform/
murmur bank shapes + renderers (prompt text NEVER lives in a bank
file), validator bands L3b 80-150 (130 tooled) / L3c 8-20 + content-
field laws + the P45 no-replacement-invention check + a frozen-literal
scan, the composer's branches + arc longform plumbing + fail-loud on
uncomposed banks + the frozen strings in the anti-echo forbidden set,
draft_dedup.py (13-gram gate), and extract_bridge_wording.py (compiles
the C++ cores on the host; renders the frozen A10/murmur wording into
the authoring tree; --check wired as a pytest leg — the C++ → banklib
direction, complementing the emitter's banklib → C++ --check leg).
The s8 harness draws frames from banklib with flavor cycling + a
LONGFORM measurement section (the honest pre-P50 baseline: cued 32-61
words, 0/9 >90w; uncued 0/9 >60w — artifact s8_beats_...194021.json).

**S11 remaining scope (the stage is OPEN; round 5 LANDED the banks —
2026-09-01, zero P0/P1 through its 6-reviewer panel, record in
LLM-INTEGRATION-STAGES.md)**: the bank-authoring work is DONE —
P45-P54 all validate CLEAN and are COMPOSED into
`finetune-data/merged/v2_all.jsonl` (**16,953 examples**; protocol
share 16.54% vs the 14.83% floor; long-row share 15.13% within the
10-20% budget; 157-battery + all three wording locks green at close).
P45 492 / P46 300 / P47 500 / P48 300 / P50 600 / P51 300 arcs
(3,886 turns; 285 in corpus) / P52 192 murmurs / P53 60 sessions (56
in corpus) / P54 590 rows. P49 is a REPORT BLOCKER at zero rows — no
banklib shape can express the enum-classifier sub-call and the
"already queued" claim was false; the owner owes a decision (coordinated
banklib+bridge change, or strike P49). Evidence:
`finetune-data/reports/S11_round5_{baseline,final,rejects}.json` +
PXX_report.json + phrase_ledger.json (91 actionable; the round-5
delta is proper nouns + the session-doc threshold artifact, reworked
per B3). PROVENANCE: ALL round-5 rows are machine-authored by SPAWNED
AGENTS (owner directive 2026-09-01 — no llama-server/GGUF/external
API) and are HUMAN-UNREAD — the next owner review must spot-review
~20 sampled rows (candidates named in the round-5 record). THE NEXT
SESSION'S JOB is the retrain + gates: run **arm2′ (E2B)**,
**arm1b′ (0.8B)**, and the open **2B arm** from the composed corpus
(recipe per fix-plan §3 + rev-3 mechanics; LoRA preferred; re-screen
LR; masking ablation at the arm2′ first checkpoint), then **G0-G5** —
G0 tools/format + example-bleed (harness conversion converged rounds
4/4b/4c; re-derive the G1 .861 threshold on the first post-conversion
baseline run), G2 hard-creative, G3 live RP 100-turn @8192, G4 device
matrix (KV-RAM reading at 8192), G5 full battery incl. the held-out
nonce hedge gate (P45 pool: 304 train / 76 held-out, held-out NEVER
trained — verified), per-tool-family S1 rates, era 0/8, from-card
lore, the rev-3 length histograms (cued/uncued), multi-turn eval at
deployment length (P51/P53 carry the multi-turn mass: 285 arcs + 56
sessions), the question-rate counter vs the recorded per-bank
baselines (reports/S11_round5_final.json), the 0.8B cadence-tic
check, and the worst-case token-budget gate. S9-(m) first-line device
leg still needs UNCUED scoping. See the S11 round-5 record's
deviations list (compose ran three times — disclosed; companion-arc
tool counts vs the "1-3" line; P49) and ledger (a)-(l) for the full
binding list.

## 2b. S10 summary (pre-existing, kept for context)

**The §4.6b doctrine (SILENCE IS DEFAULT) is now load-bearing code.**
Pure core `PlayerbotLlmChatterCore.h` (host-pinned, 412-check battery):
the rung policy table, the world-level Jaccard ring (24 entries, 0.5 —
cross-bot echo), the fatigue/legend ledger (5-telling retirement,
per-(template×speaker×listener) spacing, heard→never-retell credence,
deterministic DistortGossipHop drift frozen at 3 content hops over the
immutable row), the murmur register tolerance (5-24 words/120 B — the
documented pre-P52 band; S11's P52 bank trains 8-20 and the runtime
tolerance re-gates at S11's checkpoint), 10 event-grounded floor
templates (every line carries a real {E}), the FROZEN murmur/party/
global/composer prompt wording (the P52 wording lock, byte-pinned by
the battery — S11 must train these exact strings), ParseComposerScript
(speaker-validated, line-safety law inline), and ChatterLineSafe (the
tightened LineIsValid law: printable ASCII, no <>{}|, no emote leads —
applied at enqueue AND on both floor paths).

**Scheduler** `PlayerbotLlmChatter.{h,cpp}` (world thread, riding the
mgr's 10s telemetry gate): the pre-generated queue (cap 12, ≤2
delivered/tick, interruption deferral), murmur refill (batch window +
low-water + a 30s QUIET-CHANNEL admission gate), party banter
(windows stamped on the ROLL; the duel note fires once ≥60s then is
consumed, re-armed while held), the rare global set piece, and
delivery with ring+fatigue re-vet + the full ledger + shared-channel
history. Device batches pay the shared governor (GovernorAdmit — the
S3 block extracted) and yield the interactive lane; workers are
copy-only detached threads under catch-all wrappers; composer turns
deliver IN ORDER (monotone stagger).

**THE FLAG LAW BIT AGAIN**: the plan's §2.2 "--parallel 2 with lane
pinning" is UNIMPLEMENTABLE on the vendored binary — string-extract
shows 0 hits for --parallel but "n_parallel is set to auto, using
n_parallel = 4 and kv_unified = true" (the desktop build agrees), so
the server auto-runs 4 sequences with a unified KV cache and the
whisper lane needed no flag — the 30s quiet-window admission gate
shipped instead. Reconcile the plan text at the next revision.

**Power ladder + master toggle (Kotlin)**: ChatterPowerMonitor computes
the rung (getThermalHeadroom + battery + charging + connectivity;
the table pinned by ChatterPowerMonitorTest mirroring the native enum)
and writes the power file every 60s (epoch-keyed refresher, armed at
every world start, both modes). The conf arms the subsystem whenever
the LLM runs; the FILE's enabled flag carries the live ambience
switch — mid-session both directions. Missing/disabled/garbage = OFF +
queue clear; stale (>10min, incl. zero/future stamps) = EMERGENCY +
non-floor flush.

**Instrument discipline (the round-3 lesson, binding)**:
tmp/mutation_s10.py now asserts a GREEN BASELINE before mutating,
restores + byte-verifies after EVERY mutant, and sweeps for leftovers
— the pre-fix harness accumulated mutants (every kill after the first
was unattributable) and its 12 survivors exposed vacuous pins (a
missing " in source", name-only/signature-only/comment-robust pins).
Any new pin suite must survive per-mutant isolation.

Key files: `PlayerbotLlmChatterCore.h` + `PlayerbotLlmChatter.{h,cpp}`
(NEW), `PlayerbotLlmMemory.cpp` (the OnDuelCompleted hook),
`tools/build_o09_realm_runtime.py` (manifest + copies; the conf keys
with the GUARDED composer-URL parse; the mgr Tick + include; the
SayAction gate stamp; GovernorAdmit/PostChatHttp/
InteractiveGenerationInFlight + the GenerateHttp endpoint/key
overrides + the two new endpoint anchors); Kotlin:
ChatterPowerMonitor.kt (NEW), LlmRuntimePolicy/ServerRuntimeFiles
(emission + staging), Settings (llmAmbience), LlmScreen; tests:
tools/test_llm_chatter.cpp + tests/test_llm_chatter.py (NEW),
ChatterPowerMonitorTest (NEW), tmp/mutation_s10.py (67 mutants under
per-mutant isolation); harness: tools/llm_lab/s10_chatter_gates.py
(NEW — the voice gates; report-only, exits 0).

## 3. S10 verification artifacts of record (C:\llm-lab\results\)

- `s10_chatter_e2b-tuned_20260831-120101.json` — the first voice-gate
  run: all sections green pre-fix.
- `s10_chatter_e2b-tuned_20260831-124211.json` — the honest pre-fix
  FAIL kept: composer turns [3,1,3] + one ring collision at 0.636
  (before the enqueue-veto model + the turn-ordering fix).
- `s10_chatter_e2b-tuned_20260831-124321.json` — green under the
  honest floors (murmur/party/global 0.8-1.0; every failing draw a
  uniform register overshoot, dropped in-tree by the register gate).
- `n3_trained_e2b-tuned_s10-close_20260831-120155.json` +
  `n3_legacy-composite_e2b-tuned_s10-close_20260831-120210.json` — the
  no-see-saw checkpoint (trained 5/6, adjust_sentiment 0.33 the
  ledgered family; legacy 4/6; S2 all-clean both).
- tmp/mutation_s10_rerun.log (55/67, 12 survivors) →
  tmp/mutation_s10_rerun2.log (67/67 after pin repair) — the honest
  mutation chain.
- All S6-S9 artifacts of record unchanged (see the stage records).

**E1 pacing**: timeDiff is a RUNNING credit across all reply lines;
MsPerChar 200→35 (all chat classes; ambient is separately budgeted);
the busy placeholder is instant; whispers get an instant ack BEFORE
the memory reads + generation (SetFacingToObject + one PlayTextEmote
nod/wave, 4 s per-pairing cap, whisper + non-event only);
`pocketllm::ApplyReplyBudget` clamps conversational 2×160 B / ambient
1×80 B BEFORE the history recorder, threaded as replyClass (0 chat /
1 RPG) through GenerateResponsePackets; the journal keeps 4 ms/char.

**T4**: `AiPlayerbot.LLMConnectTimeout` (10, clamped 1-60) bounds the
TCP connect (non-blocking + select + SO_ERROR); SO_RCVTIMEO/
SO_SNDTIMEO = the generation budget bound the TLS handshake + write
legs (a stalled peer can no longer leak generation slots).

**E2**: the onboarding sys line at login (once per character per
world process — the anchor site also fires on cross-map teleports;
the OnboardedPlayers dedupe handles it); the player's first-EVER bot
contact gets the scripted welcome + a native first-meeting LogFact;
tutorial step 5 points at the submenu with honest distribution copy.

**E3**: addon `Talk.lua` (target → last whisperer → last say speaker →
pre-filled `/w Name ` composer); radial Talk replaces SOCIAL (the
parallel stream pinned Move UI's slot by test — do not displace it);
the Hud chat grows to 220 px during whisper conversations and reveals
scroll chrome on 5-lines/2.5 s bursts; journaled rects never resize.

**E4**: tier-shift sys line at the ceremony consume site ("X seems
warmer/colder toward you." — survives governor-dropped ceremonies);
the automatic standing one-liner on a pairing's first whisper of the
session + the "standing"/"gossip" whisper keywords (NO relationship
points — reads, not conversations); the whisper keyword set is
documented in docs/llm-runtime-submenu.md; an atomic conversations
counter read on the debug-llm surface (app-side transport deferred
with §4.3's Workstream-A dependency).

**E5 + S8-(n)**: the LlmScreen model picker (small-first, trade-off
copy, restart note, hand-staging notes on localOnly entries); the
banter copy now describes the full authored layer; the debug
llmOverrides block emits LLMBanterEnabled.

Key files: `PlayerbotLlmToolsCore.h` (ApplyReplyBudget),
`PlayerbotLlmRecallCore.h` (TierShiftSysLine), `PlayerbotLlmMemory.*`
(ack/login/welcome/standing/gossip/counter + the two dedupe sets),
`PlayerbotLlmBridge.cpp` (the ceremony sys line); driver anchors
PB_SAY_{TIMEDIFF_HEAD,TIMEDIFF_TAIL,PACE_CALL} (NEW), PB_SAY_RECORDER
(budget + counter), PB_SAY_CONTEXT (ack + standing + welcome +
keywords), PB_IFACE_{SOCKINCLUDE,CONNECT} (NEW), PB_LLM_TIMEOUT/
CONFIG_HEADER/CONF (llmConnectTimeout), CORE_LOGIN_ONBOARDING (NEW,
apply + restore); Kotlin: LlmRuntime.kt (chatTemplateFile +
serialVersionUID; contextSize now 8192 per the S10 rev-3c fix),
LlmRuntimeService (probe/restart/revert), LlmRuntimePolicy
(stageChatTemplate), LlmModelRegistry (connectTimeoutSec), LlmScreen
(picker), ServerRuntimeFiles (debug banter), FirstRunTutorial (step
5); addon: Talk.lua (NEW) + Radial/Core/Hud/toc; tests:
tests/test_llm_player_surface.py (NEW), tools/test_llm_{act_tools,
recall}.cpp legs/rows, Kotlin LlmRuntimeConfigTest +
LlmRuntimeServiceWarmUpTest (NEW), tmp/mutation_s9.py (26 mutants).

## 3b. S9 verification artifacts of record (C:\llm-lab\results\)

- `s44_probe_default-template_20260831.json` — the base gemma under
  its own template: thinks=true (reasoning 20 chars, content empty,
  finish=length) — the §1.4 failure shape reproduced live.
- `s44_probe_nonthinking-override_20260831.json` — the file-flag
  override on desktop b10520: content "Ready.", finish=stop.
- `s44_probe_nonthinking-inline_20260831.json` — the INLINE
  `--chat-template` content form (what the service actually sends):
  identical clean result — the shipped mechanism's live proof.
- All S6/S7/S8 artifacts of record unchanged (see the stage records).

## 4. Remaining stages — scope + binding conditions

Rev-3 renumber (2026-08-31): world chatter was **S10** (now PASSED);
the v2.3/v2.4 retrain is **S11** — "S10" in the frozen S1-S8 records
and ledger items means the retrain.

- **S11 is the only remaining stage** (§5 v2.3 banks P45-P49 +
  P50/P51/P52 + retrain arm2′/arm1b′/2B + G0-G5; was "S10" pre-rev-3):
  **BINDING (S5 C2): the "tools ≥ .93" gate converts to PER-FAMILY
  acceptance (per-trap pass), explicitly gating adjust_sentiment
  majority-fire on arm2′** (S8's event-leg measured the family again:
  2/3 strict / 3/3 tolerant of the single-'>' closer). Also binding:
  the keyed-emote harness conversion (prtools2/3/4+ambition — DONE in
  S11 round 4, pinned by tests/test_llm_prtools_harness.py), the
  scorer rebuild, persona-locked sessions, q08 family gaps, the
  fieldless `<<perform_emote laugh>>` acceptance (S6-ledger (j)), and
  NOW the S8 additions: **nickname-adoption bank rows** (ledger (b) —
  the procedure measured 1/6 pooled; weights-side is the fix), the A16
  meetup-initiation + A17 dusk appointments deferrals (ledger (a)),
  and the A10 class-2/3 + denial-mood P45 items (S7/S8 ledger (m)).
  Banklib is the source of truth (`G:\NPU LLM\scripts\finetune\
  banklib.py`). Device legs owed: A17's ≥3/30-min idle-adjacency gate,
  A18's in-world pacing, S8 ledger (c), and now the S9-(m) legs: ack
  <1 s, first line 6-8 s, the Talk flow in-game, tier lines visible,
  the on-device template restart, the vendored flag surface executed
  on-device.
- **S11 rev-3 addition (2026-08-31, plan §5 + §5.1 — long-form + depth
  generation, four-agent literature review):** NEW banks P50 (long-form,
  ~600-800 cue-bearing rows, 90-150 words: storytelling / news-recall
  deep-dive / gossip / tier-5 Bonded) + P51 (depth arcs, ~300-400 rows ×
  12-24 turns, growing multi-turn mass ~127 → ~450-500), authored per the
  §5.1 API-drafter protocol: ADD, never rewrite (frozen corpus = human
  anchor; only P21(h) + phrase-ledger offenders are reworked); multi-source
  drafters, NON-Qwen providers for the Qwen arms; provider ToS
  (training-on-outputs) checked BEFORE drafting, or open-weight local
  drafters used; draft-vs-corpus dedup gate (minhash/13-gram, explicit);
  per-provider validator-reject log; phrase_ledger pass on every batch.
  BINDING conditions: cue-string wording lock GENERALIZED (frozen before
  authoring, byte-identical to what the bridge injects — conditional
  length is proven only with explicit in-prompt signals, unproven at
  0.8B/2B, so cued/uncued behavior is measured per checkpoint); long rows
  ≤10-20% of assistant TOKEN mass (not row count — a 120-word reply is ~3x
  the gradient of the 37-word mean); 150-word hard cap; validator L3b added
  (80-150 words ONLY in cue-bearing P50/P51 rows — never a global L3
  raise); arcs carry ≥3 forced callbacks + terse turns inside + protocol
  turns mid-arc + loss on ALL assistant turns; masking ablated at the
  arm2′ first checkpoint. COUPLED S9 CHANGE: tier max_tokens must rise to
  ~200-230 on the long-licensed tiers or the bank truncates (90-150 words
  ≈ 130-225 tokens at ~1.4-1.5 tok/word; today's 120/100/120 caps already
  truncate the current 102-word max). G5 gains: the two length histograms
  (P(>60w | uncued) ≈ 0 inflation alarm; P(>90w | cued) delivery metric —
  thresholds set at the first checkpoint), the multi-turn eval (persona
  fidelity + callback recall + distinct-n at turns 8/16/24,
  deployment-length histories), and the per-epoch protocol-compliance
  curve. Retrain mechanics: LoRA preferred, EOS/terminator verify on
  composed rows, LR re-screen for the larger corpus. Standing caveat: the
  token-mass budget and both length thresholds are literature-grounded
  HEURISTICS, not measured artifacts — the first checkpoint confirms or
  revises them. Rev-3b ledger adds (same-day post-review): (1)
  persona-flavored VARIANT SETS for the shared beat-cargo strings
  (DebtCargo/NewsCargo/MemoryCargo/Grudge/Gossip) + the two ceremony
  shapes — GUID-stable selection per bot, wording lock applies (banks
  train the same variant set the bridge injects), and the RecallCore
  byte-pins + mutation tests move in the SAME change; (2) G5
  question-ending-RATE counter-metric per persona/beat-class (deny+pivot
  + ask-afters + reminders can stack into a question-machine tic;
  baseline at the first checkpoint, gate the delta vs the pinned
  baseline). Rev-3c (same day, MEASURED + FIXED): the server context was
  the real constraint — llama-server ran `-c 4096` (LlmRuntimeConfig
  default, never overridden by runtimeConfig()) vs the 8192-CHAR native
  waterfall (LLMContextLength is chars; ~4.07 chars/token measured on
  Qwen3.5-2B via llama-tokenize). FIXED: contextSize default 4096 → 8192
  (LlmRuntime.kt data class + Builder; LlmRuntimePolicyTest pin updated;
  :app + :pocketrealm-llm test classes green). Measured: worst-case
  runtime request 2,047 tokens (largest session row, 8-pair shape) →
  ~2.8k with template + bias + full asset card + a 300-token reply —
  ~3x headroom at 8192. G4 records KV-RAM at 8192 (q8_0 KV cache is the
  fallback lever); the in-process debug path's LLMCtxSize=4096 is
  deliberately UNCHANGED (its n_ctx = ctxSize × 4 slots). P47 condition
  added: card spans sample the shipped asset's real length spread
  (trained [RESULT] max 468 chars vs runtime max ~1,166 — measured
  distribution gap); optional TruthCore render-trim rides S10's native
  lane discipline. G5 gains the worst-case token-budget gate (real
  tokenizer, assert ≤ n_ctx − max_tokens − margin).
- **NOTE for S11 from S9**: the rev-3 "coupled S9 change" (tier
  max_tokens ~200-230) touches LlmModelRegistry profiles +
  LlmRuntimePolicyTest's max_tokens pins and the docs sample — do it as
  its own reviewed change with the emission tests updated together.
  **NOW ALSO BINDING FROM S10**: (1) P52 trains the FROZEN S10 wording
  byte-exactly (MurmurSystemMessage/MurmurNote/PartyNote/GlobalNote/
  ComposerSystemPrompt/UserPrompt — pinned by the battery's
  TestFrozenWording; the banks and the bridge move together); (2) after
  whatever checkpoint S11 ships, RE-RUN the S10 voice gates
  (tools/llm_lab/s10_chatter_gates.py) and tighten the runtime murmur
  tolerance (today 5-24 words/120 B, deliberately pre-P52) toward the
  trained 8-20 register — the S10 device legs (battery %, whisper-ack
  under murmur load, live power ladder, a real cloud-composer run)
  ride the same checkpoint per S10-ledger (a); (3) the murmur composer
  script currently binds one factKey per batch (5-turn cap) — multi-
  fact scripts need the eventRows plumbing (S10-ledger (m)). The S9
  deferred-P2 ledger is in the S9 record, items (a)-(o); load-bearing
  residuals: (l) the template asset is gemma-dialect — a future
  registry family that trips the probe needs its own dialect asset;
  (k) the diagnostics transport stays a Workstream-A dependency
  (WorldNative's ABI is parallel-owned).

Deferred-P2 ledgers of earlier stages live in their records (S6
(a)-(n), S7 (a)-(l), S8 (a)-(n), S9 (a)-(o)) — consult before "fixing"
anything already accepted.

## 5. Operational mechanics (hard rules, learned the hard way)

- **Native lane builds**: `python tools/build_o09_realm_runtime.py
  --abi {arm64-v8a|x86_64} --backend {mysql|sqlite}` (~3-5 min each).
  NEVER run two concurrently (shared cmangos submodule), and do NOT
  run tests/test_sqlite_dialect.py or the mutation suite while a build
  is in flight (both read/mutate the live submodule + patch files). At
  every stage close rebuild ALL FOUR and re-run the lockfile-freshness
  test (pins 3 of 4; the sqlite x86_64 lane is verified manually per
  close — hash-compare `patches_content` against `native/patches/`).
  Recovery on a dirty submodule: `git -C native/cmangos checkout -- . &&
  git -C native/cmangos clean -fd src/game/Chat/PocketRealmInteraction.cpp`.
  CORE-tree anchors need BOTH apply and restore entries (the S6 rule;
  S9's CORE_LOGIN_ONBOARDING is the example).
- **Edit mechanics**: SayAction/PlayerbotLLMInterface/PlayerbotAIConfig/
  PlayerbotAI/RpgSubActions/DebugAction/Player.cpp/Unit.cpp edits =
  driver-anchor patches (`*_UPSTREAM` must match the pristine submodule
  byte-exactly). New files = overlays in `native/patches/playerbots/`
  (already-manifested files just edit in place). **DO NOT edit repo
  files via bash heredocs** — heredocs mangled the S8 record and two
  S9 scratch scripts mid-session (backslash eating), and struck TWICE
  MORE in S11 (a doc replace and a test append both landed broken
  escapes; both caught by tests) — use the Edit/Write tools exclusively.
  **Emitter splice law (S11)**: emit_prompt_constants.py REFUSES a
  marker-less target — its first emission once replaced
  PlayerbotLlmRecallCore.h wholesale (recovered byte-exact from the
  submodule mirror; add the marker pair by hand, then run the emitter).
  **std::async law (S11)**: default arguments do not bind through the
  std::async function pointer — every added parameter needs an explicit
  value at BOTH call sites or the lane build fails.
- **Vendored-binary law (S9)**: verify server flags against the actual
  `android/pocketrealm-llm/prebuilt/` artifacts (string extraction works
  on the ARM .so), never against the desktop llama-server build.
- **Host batteries**: `g++ -std=c++11 -O2 -Wall -I
  native/patches/playerbots -o tmp/x.exe tools/x.cpp`; pytest wrappers
  follow the existing pattern — when adding a battery LEG, wire the
  pytest wrapper leg in the SAME change (S9's P0 was exactly an
  unwired leg). Full gate = 124. RecallCore/ToolsCore use `uint32_t`
  (NOT `uint32`) in pure-header signatures, and bionic's getsockopt
  takes a real `socklen_t*` (an `int` "portability" fold broke all
  four lanes and had to be reverted).
- **Kotlin tests**: `cd android && ./gradlew.bat :app:testDebugUnitTest
  --tests "..." -PpocketAbi=arm64-v8a -PsqliteProvider` (the
  :pocketrealm-llm module takes the same flags). Known pre-existing
  failures: 4 AndroidPort addon-asset tests (the parallel stream's
  stale pins) and a tree-wide red detekt (their in-flight baseline) —
  do not fix or touch their files; record around them.
- **Desktop LLM work**: server `G:\NPU LLM\tools\llama-cuda-b10520\
  llama-server.exe`; models on `G:\NPU LLM\models\`; results in
  `C:\llm-lab\results\` (versioned filenames — never fixed names).
  Harnesses: sanity_battery, run_n3_checkpoint, s6_act_chain,
  s7_truth_gates, s8_beats_gates (unchanged by S9/S10 — no
  conversational prompt text changed, so no see-saw checkpoint was
  owed; the S10-close n3 both-composites run is recorded anyway), and
  s10_chatter_gates (the S10 voice gates — REPORT-ONLY, exits 0; read
  its JSON summary, do not gate CI on it).
- **Review protocol**: 6 roles R1 native / R2 Kotlin / R3 tests / R4
  laws / R5 systems / R6 red-team. Background agents are UNSUPPORTED —
  dispatch as foreground Agent calls in ONE message. Rounds until ZERO
  P0/P1 (S9: 2 rounds; S10: 6 → 3 → 2 → 2, converged at round 4 —
  long tails are real, budget for them). **Write
  the stage record only AFTER the closing gate returns.**
  Evidence-hygiene law: comments/docs/records claim only what an
  artifact records; mutation-test string-find pins (S9: 26/26 via
  tmp/mutation_s9.py; S10: 67/67 via tmp/mutation_s10.py — rerunnable
  but ONLY on a quiet tree, and NOTE the S10 round-3 law: the harness
  asserts a green baseline, restores + byte-verifies after EVERY
  mutant (accumulating mutants made kills unattributable and shipped
  12 vacuous pins), and sweeps for leftovers; any new pin suite must
  survive per-mutant isolation — a pin on a comment, a bare name, a
  signature, or a substring a comment retains is NOT a pin).
- **Injection-hygiene law** (binding): StripAstral → ScrubControlTokens
  → NeuterMarkersCopy → TruncUtf8 → EscapeSql at every persistence
  write, neuter LAST. Reserved prefixes ("secret told:", "(tone ±)")
  are forgeable through model-filled text — constrain by CATEGORY, not
  text.
- **See-saw discipline** (§3.0): ONE prompt-text change per checkpoint;
  n=3 scored per family; BOTH composites when either changes. S9/S10
  changed no conversational prompt text (S10's new wording lives on
  chatter-only paths, byte-pinned for P52) — the law bites at S11's
  bank work.
