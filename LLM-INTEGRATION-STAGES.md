# LLM INTEGRATION — Staged Execution Plan & Review Protocol (2026-08-29)

Companion to `LLM-INTEGRATION.md` (rev 2). This file defines the execution
split, the per-stage review gate, and the running stage status. **A stage is
done when its review gate passes with zero issues, not when the code is
written.**

---

## Review protocol (every stage, no exceptions)

1. **Implement** the stage scope exactly (against the REAL code — build
   driver anchors + overlays for native, per `LLM-INTEGRATION.md` §3.0).
2. **Verify locally**: compile + run the stage's named tests/battery.
3. **Dispatch the 6-reviewer panel** (see roster). Each reviewer gets the
   stage diff/file list, the plan sections in scope, and returns issues
   tagged P0 (blocks — wrong vs code/spec/laws, breaks build/tests,
   regression), P1 (should fix this stage), P2 (note for a later stage).
4. **Fix** every P0/P1 (P2s logged into the stage record unless trivial).
5. **Re-dispatch a fresh panel** (round-robin rotation: each round shifts
   reviewer role assignments one slot so no stance dominates; round 2+
   reviewers get the fix delta + previous issues list).
6. Repeat until a round returns **zero P0/P1**. Record rounds + issues in
   the stage record below.

### Reviewer roster (6 roles, rotating)

| slot | role | mandate |
|---|---|---|
| R1 | native C++ correctness | code-vs-claim accuracy, threading, memory, world-thread discipline, build-driver/anchor integrity (§3.0) |
| R2 | Kotlin/app correctness | registry/policy/settings plumbing, serialization, lifecycle, UI contract |
| R3 | contract & tests | every named test updated/passing, new tests adequate, no silent test deletion |
| R4 | research-law conformance | G:\NPU LLM TIMELINE laws + fix-plan A/F items: see-saw, one-note, TAIL layout, tier budgets, prompt-format version |
| R5 | systems/perf | lanes, timeouts, RAM/NPU ceilings, cache/slot invariants, failure paths |
| R6 | adversarial red-team | break it: injection, malformed input, mid-conversation restart, worst-case interleavings, spec contradictions |

---

## Stage split (execution order; dependencies strictly linear unless noted)

| # | stage | scope (plan refs) | files (primary) | acceptance | status |
|---|---|---|---|---|---|
| S1 | Model registry + tier sampling emission | §4.1 registry, §4.3 emission split, G-4 fix, §2.1 tier sampling rows | `LlmModelRegistry.kt` (new), `LlmModelCoordinator.kt`, `Settings.kt`, `LlmRuntimePolicy.kt`, `ServerRuntimeFiles.kt` | `LlmRuntimePolicyTest` + new registry tests green; LLMApiJson carries per-tier top_k/rep1.0/min_p/max_tokens; three model-resolution call sites single-sourced; Settings triple-write verified | **PASSED (2 rounds)** |
| S2 | JSON client + empty-content guard | A9 | `PlayerbotLLMInterface.cpp` (driver anchor), app pattern keys emptied | 100-generation parse test zero failures; empty-content retry verified on e2b-base | **PASSED (4 rounds)** |
| S3 | Backend unification | A0 (gate removal, ExtractAndQueue on HTTP, governor hoist, interlocutor GUID) | SayAction + PlayerbotLLMInterface driver anchors | desktop battery S1 ≥5/6 via HTTP server; facts attributed to whisperer; busy at flood | **PASSED (3 rounds)** |
| S4 | Trained prompt format on HTTP + prompt-dump | A4 (messages builder, thinking kwargs per family), A5 renderer, A6 defaults | SayAction anchors, `PlayerbotLlmMemory.cpp` overlay, prompt-dump hook | 20-row byte-diff vs banklib renderer ≤ whitespace | **PASSED (3 rounds)** |
| S5 | Bridge notes + TOOLS_NOTE (see-saw ladder: A7 alone first, then A1) | A7, A1, licensed-tool plumbing | new `PlayerbotLlmBridge.cpp` overlay + SayAction anchor | battery S1 6/6 + S2 7/7 n=3 per-tool-family, one prompt change per checkpoint | **PASSED (2 rounds)** |
| S6 | ACT tools + emotes + duel hook | A2, A3, A14 | `PlayerbotLlmTools.cpp` overlay, `EmoteAction` routing, `Player::DuelComplete` | duelworld-10 ≥9/10; 19 text-emotes visible in-game | **PASSED (4 rounds)** |
| S7 | Truth guards | A10 (stakes-scoped), A11 (lore loop + corrected era policy + corpus scrub), A12 filters | bridge overlay, lore assets, server launch flags | guard ≥4/6 clean denials n=3; lore ≥10/12 non-echo; era 0/8 corrected traps; leak filter h4 pass | **PASSED (3 rounds)** |
| S8 | Memory USE + believability beats | A13 full table, A16-A19 | bridge + memory overlays | S4 associative ≥3/4 with beats; tier-ceremony felt-change ≥4/5; initiative ≥3/30-min | **PASSED (2 rounds)** |
| S9 | Player surface | E1-E5 (pacing, onboarding, Talk UI, visible progression) | `Hud.lua`/`Core.lua`, LlmScreen, packet pacing code | ack <1s; first line ≤6-8s; Talk flow no name typing; tier lines visible | pending |
| S10 | v2.3 data + retrain | §5 (P45-P49, G5 gate, arms arm2′/arm1b′/2B) | G:\NPU LLM banks + train scripts | G0-G5 gates per §5; held-out hedge ≥5/6; over-hedge 0/6; tools ≥.93 | pending |

Parallel tracks (start after their dependency stage passes): S10 authoring
may start after S4; S9 E1 pacing subset may start after S5.

---

## Stage records (append per review round)

### S1 — Model registry + tier sampling emission
- Round 0 (implementation, 2026-08-29): new `LlmModelRegistry.kt`
  (3 descriptors; tuned pins sha256-verified on disk; DEFAULT = BASE until
  S4 per stage discipline — tuned is localOnly until §4.2 distribution);
  `Settings.llmModelId` triple-write (Snapshot + readLlmSnapshotFields +
  writeSnapshotWrites + key); `LlmRuntimePolicy.confBlock` profile param +
  `apiJsonTemplate` (embedded: top_k/repeat_penalty/min_p/presence_penalty;
  external: provider-safe subset); `ServerRuntimeFiles.llmConfigOverrides`
  resolves the selected model + passes its profile; `ensureLlmRuntime` and
  LlmScreen's three resolution sites single-sourced through
  `modelPathFor(snapshot.llmModelId)`; LlmScreen download button honors
  localOnly (hand-staged note instead of a dead download). Tests:
  LlmRuntimePolicyTest sampling/provider-safe assertions +
  new LlmModelRegistryTest. Scope deliberately deferred: model-picker UI +
  download-service per-descriptor fileName (S9), load-time marker verify
  (S2/S4), default flip to TUNED (S4).
- Round 1 (6 reviewers: R1 cross-layer, R2 Kotlin, R3 tests, R4 laws,
  R5 systems, R6 adversarial): **0 P0, 3 P1** — (1) download service staged
  selection-derived bytes under the BASE fileName (R2+R6; latent wrong-model
  hazard) → fixed by resolving the whole descriptor from the registry id
  (EXTRA_MODEL_ID; retry carries the id; dead expectedModelPath deleted);
  (2) min_p emission untested dead branch (R3) → synthetic-profile test +
  external-strip assertion; (3) profile passthrough at llmOverrides untested
  (R3) → selectedModelsSamplingProfileReachesTheEmbeddedConfBlock. P2s
  folded: dedicated EXTERNAL_PROFILE (t0.7/p0.9/max 300, plan §2.1 T4 —
  external users no longer silently inherit BASE t1.0), tightened
  comma-delimited JSON assertions + q08 max_tokens pin,
  docs/llm-runtime-submenu.md emission sample updated to match
  apiJsonTemplate byte-for-byte.
- Round 2 (3 reviewers covering the six rotated mandates; R1/R5 domains
  unchanged by the fixes and covered by compile+behavior verification):
  **ZERO P0/P1**. min_p pin proven by mutation testing; sha256 pins
  independently re-hashed on disk by the reviewer and matched; external
  envelope field order verified against every assertion; no remaining
  ModelDescriptor constructions outside the registry conversions.
  P2s logged, not blocking: unused BuildConfig import (fixed post-round),
  pre-existing cancel→restart service race, localOnly retry dead-end
  (UI-unreachable), providerSafe strips repeat_penalty for PC llama-server
  external targets, per-tier LLMContextLength deferred, unpinned
  Context-bound hop llmConfigOverrides→profile (pure half pinned),
  full-suite pre-existing addon-asset failures (5, working-tree
  AndroidPort changes, unrelated to this stage).
**VERDICT: S1 PASSED (2 rounds, converged at zero).**

### S2 — JSON client + empty-content guard (A9)
- Round 0 (implementation, 2026-08-29): new header-only
  `PlayerbotLlmJson.h` overlay (vendored parser — the plan's sanctioned
  alternative to boost::property_tree, recorded as a deliberate size
  decision in the header): OpenAI envelope decode (`choices[0].message`
  content with full string semantics incl. \uXXXX + surrogate pairs;
  legacy `choices[0].text` shape too), `reasoning_content` detection,
  finish_reason-length flag, `AppendInstructionToLastUserMessage` (pure
  byte splice at the closing quote — KV prefix stays valid), voicing
  gates (`ContentUsable`: C0/C1/DEL refusal, strict UTF-8 with per-lead
  continuation ranges, 32 KiB cap; `LooksLikeVoicableText` for the
  non-JSON fallback: HTML/BOM/broken-JSON/structural-lead/binary/no-letter-
  run/size refusals). Driver anchors: Generate HTTP leg (envelope →
  content; JSON-but-no-completion → quiet; non-JSON → plausibility-gated
  raw fallback; ONE "Answer directly." retry when reasoning present and
  budget NOT already exhausted, skipped for the length class — documented
  A9-spec deviation, mechanism-argued and artifact-cited); ParseResponse
  truncation trim + decode-aware skipping of the two JSON-era residue
  transforms (escaped-quote rewrite, backslash-eating delete pattern) via
  per-generation thread_local state bits. App: `LLMResponseStart/EndPattern`
  emitted EMPTY (dead keys must still override the native defaults);
  docs/llm-runtime-submenu.md + android/pocketrealm-llm/README-INTEGRATION.md
  refreshed. Tests: tools/test_llm_json_client.cpp + tests/
  test_llm_json_client.py (invariants/battery/fuzz; 100-gen gate with
  byte-exact extraction, escaped-envelope class, voicing-gate checks;
  50k fuzz with pure-insertion + pristine-decoded-content invariants;
  battery fails closed on exit code). Live: tools/llm_lab/verify_a9_retry.py
  replays the exact production body.
- Round 1 (6 reviewers): **0 P0, 5 P1** — delete-pattern/ReplaceAll
  corruption of decoded content (→ decode-aware gates); manifest omission
  (→ PLAYERBOTS_OVERLAYS entry); futile-retry latency (→ length-class
  skip); fail-open raw fallback (→ voicing gate); control-byte content
  (→ ContentUsable validation). P2s folded: splice refuses non-string
  LAST user turn; jsonParsable single-parse; battery escaped variants +
  fail-closed exit; fuzz insert-mutation fix + depth corpus + pristine
  decoded check; KDoc native-defaults citation fix; README-INTEGRATION
  refresh; TrimTruncatedTail doc reconciliation; count-cap + size-decision
  notes; q08-tuned live leg (2/2 first-attempt usable).
- Round 2 (3 reviewers, rotated two-mandate slots): native+systems and
  Kotlin+tests ZERO; red-team slot **2 P1** — ContentUsable admitted
  range-invalid UTF-8 (E0 80 80, ED A0 80, F0 80 80 80, F4 90 80 80 →
  per-lead continuation-range enforcement); no size bound on the voicing
  paths (→ 32 KiB caps + bracket-lead structure + key-fragment rules).
  Evidence hygiene fixed: retry-skip + budget-probe comments now claim
  only artifact-recorded facts.
- Round 3 (2 reviewers): **1 P1** (leftover "measured futile" sibling
  comment → mechanism phrasing) + P2s folded (}]/DEL fallback-gate leads,
  cross-sequence state-machine + exact cap-boundary invariants, non-Latin
  trade documented).
- Round 4 (final gate): **ZERO P0/P1**. Advisory P2 applied post-gate
  (`,`/`:` structural leads refused) with full test rerun + lane rebuilds
  + lockfile refresh. Acceptance evidence, honestly read: 100-generation
  gate 100/100 zero parse failures (regex baseline broke 99/100 — the
  documented motivation); e2b-base live artifact verifies the empty-content
  failure-shape detection, the length-class retry-skip and fail-quiet
  (a9_retry_e2b-base.json: 0/2 usable, both legs finish_reason=length;
  budget-elastic thinking 411→734→1056 chars, first usable at 500; §1.4's
  51/51 empty at ≤200 all length-class); the retry splice itself is
  verified host-side (invariants + 50k fuzz: pure insertion that
  re-parses, refusal leaves the body untouched); no preserved artifact
  shows a live resend on any model (q08-tuned: 2/2 first-attempt usable,
  retry arm untriggered). All four native lanes rebuilt; lockfile
  patches_content pinned (freshness test green).
  Deferred P2s logged: decimal/abbreviation trim cutting (A12 line-splitter
  pass), `reasoning` field variants on external providers (M4 T4 battery),
  pre-existing unbounded recv + debug Authorization leak (A12/debug path),
  3xx no-redirect (documented), desktop-default-conf silence note (done),
  mid-string head-lost fragments accepted by the plausibility gate
  (design limit), unpreserved first-run resend observation (superseded by
  the mechanism argument).
**VERDICT: S2 PASSED (4 rounds, converged at zero).**

### S3 — Backend unification (A0)
- Round 0 (implementation, 2026-08-29): (1) duty-cycle governor HOISTED
  above the backend branch in Generate (HTTP path gains admission control;
  busy semantics unchanged — RPG silent, player-facing POCKETREALM_LLM_BUSY
  → persona BusyReply; rejection is pre-network, immediate); (2)
  ExtractAndQueue on ALL HTTP returns (envelope content, retry content,
  voicable raw fallback) — G-1 closed on the shipped path: tool markers
  never reach chat, calls queue speaker-tagged; (3) interlocutor GUID
  threaded ChatReplyDo → GenerateResponsePackets → Generate →
  ExtractAndQueue → QueuedCall → ExecutePending (FindPlayer(speakerGuid) +
  isRealPlayer replaces GetMaster() — facts/sentiment attribute to the
  SPEAKER; autonomous RPG/debug passes 0 and persistence refuses via the
  double source+player guard); (4) hard-trigger gate backend-independent
  (G-6: whisper | party/raid name-mention | say name-mention+real-player;
  guild/yell/trade/general are non-triggers on both backends); (5) tool
  instructions ride BOTH backends (appended to the pre-prompt fill before
  the split; llama concatenates raw, HTTP JSON-escapes into the system
  message). Memory-block un-gating (A0 item 1) was already landed by the
  M6 work — verified no residual gate. New guard test
  tests/test_llm_a0_unification.py (8 pins: governor-above-branch, 4
  extraction sites, speaker threading, gate independence, instructions
  before the split, no GetMaster call, autonomous guard, write-site
  neutering order). Desktop battery over HTTP (e2b-tuned): S1 fire 5/6,
  fill 6/6, voice 6/6, hygiene 6/6, S2 restraint 7/7 (failed case:
  adjust_sentiment insult case 3 — known weights-side under-fire, S5/S10
  scope).
- Round 1 (6 reviewers): **0 P0, 1 P1** (R6: marker poison persisted
  through tool VALUES into future prompts — LogFact/(tone)/ShareGossip
  wrote model text SQL-escaped but never marker-neutered; S3 ships it on
  the HTTP path) → fixed with NeuterMarkersCopy at all three writes.
  P2s folded: PlayerbotLlmTools.h class comment (both backends);
  docs guillemets/raw-fallback prose-loss note. P2s logged: governor
  tier values + LLMMaxSimultaniousGenerations + per-tier timeout emission
  (S4 §4.3); T4 scanner-only licensing before external ships (S5/S6);
  ToolInstructions outside the LLMContextLength budget (harmless slack
  vs 8192); busy-line pacing 200ms/char (E1/S9); statics across embedded
  restarts (bounded staleness); ExtractAndQueue host battery debt (S5/S6
  refactor); speaker-logout drop (fail-quiet, accepted); see-saw ladder
  ruled NOT binding on S3 (A0 is plumbing, not wording — R4 ruling) with
  the S5 condition that the A7 checkpoint battery must score per-tool-
  family under BOTH backends' composite prompt.
- Round 2: **1 P1** (astral-padding bypass: neuter-then-StripAstral —
  `<X<` survives neuter, strip deletes the emoji, fuses `<<`; proven by
  scratch harness against the real NeuterMarkersCopy) → reordered all
  three chains to StripAstral → NeuterMarkersCopy → TruncUtf8; order pin
  added to the guard test.
- Round 3 (final gate): **ZERO P0/P1**. Re-attack with 13 malformed-lead
  cases + 400k prefix-fuzz: strip-induced fusions are all visible to the
  neuter after the reorder; truncation cannot re-open a pair (prefix of
  pair-free text); no other write path carries model text; the rebuilt
  lane's provenance pins the fixed file's sha256. Desktop legs verified
  (battery S1 5/6 via HTTP; attribution/busy mechanisms code-verified +
  panel-reviewed); on-device legs (facts-attribution at scale, busy under
  flood, M1b exit criteria) recorded DEVICE-PENDING per the plan's own
  Phase 3 user-gating — the desktop world console has no player-chat
  injection. All four lanes rebuilt at close; lockfile pins refreshed.
**VERDICT: S3 PASSED (3 rounds, converged at zero).**

### S4 — Trained prompt format on HTTP + prompt-dump (A4/A5/A6)
- Round 0 (implementation, 2026-08-30): new overlay `PlayerbotLlmPrompt.h`
  (pure, host-compilable): zlib-compatible crc32; TOOLS_NOTE A-D +
  NO_NARRATE + the four production bibles + LEDGER_AVOID_NOTE as
  VERBATIM constants machine-emitted from banklib
  (tools/llm_lab/emit_prompt_constants.py, idempotent splice);
  SysmForCard/ComposeUserTurn/card_state twins (the trained contract:
  identity → TOOLS_NOTE variant → bible+NO_NARRATE → Backstory → tier
  (Wary/Civil/Warm/Trusted/Bonded 1-5) → absence → inline facts; user =
  head/[Memories]/[State]/note legs incl. the [BRIDGE AI] shapes S5 will
  use); BuildChatRequestBody (native JSON body: system + ≤8 history +
  current-last; per-tier sampling; §4.4 chat_template_kwargs;
  providerSafe strip; nan/inf-safe numbers). `BuildTrainedChatRequest`
  in PlayerbotLlmMemory (live persona + GUID-stable bible/quirks;
  GetTrainedTier; facts cap/[Memories] tail per §2.1; current-turn
  exclusion; trained state flavors; prompt-dump hook one-line-per-gen).
  BuildPromptContext's tier/absence/facts segments switched to the
  trained dialect; FORMAT_VERSION 4→5. Driver anchors: conf keys
  (LLMPromptFormat/ApiModel/MinP/PresencePenalty/ThinkingKwargs/
  FactsCap/MemoriesTail/ProviderSafe/PromptDumpFile; LLMMaxNewTokens
  120→200 + LLMGenerationTimeout 600→60 as A6 hand-configured
  fallbacks); SayAction trained branch + json.empty()-gated legacy
  paths; conf doc block. Kotlin: LlmTierProfile per descriptor (§2.1
  values + botToBotChatChance + thinkingKwargs); full §4.3 tier
  emission (18 pinned lines incl. LLMBotToBotChatChance); DEFAULT
  registry flip BASE→TUNED_E2B; docs/llm-runtime-submenu.md sample
  byte-matches the emission. Tests: gen_prompt_vectors.py (24 banklib
  rows committed) + test_llm_prompt_format.{cpp,py}.
  **Gate: 24/24 rows byte-EXACT (zero whitespace-only rows) vs
  banklib; bodydump invariants; battery e2b-tuned S1 5/6, S2 7/7, S7
  multiturn name_recall+crate_callback both true; §4.4 kwargs-omitted
  pass (s44_thinking_kwargs_q08-tuned.json: qwen-tuned template default
  non-thinking, 0 reasoning chars).**
- Round 1 (6 reviewers): **1 P0** (bonded tier wrote a value the schema
  enum/CHECK forbids — relationship writes would permanently fail at 120
  points) → fixed by REVERTING the SQL change and deriving tier 5 on
  READ (GetTrainedTier: points ≥ 120 → Bonded; TierStorageName; no
  migration, no constraint-failing write). P1s: sqlite-dialect ODKU gate
  broken (fixed by the revert); bot2bot chance never emitted (→
  LlmTierProfile.botToBotChatChance 10 on T1/T4, 0 T2/T3 + emission);
  stale apiJsonTemplate KDoc contradicting the dual-path truth (→
  rewritten); governor 12/60s exceeded CPU capacity (→ T1 global 8, the
  CPU-safe §2.1 value, since the shipped steady-state may be CPU);
  invented third state flavor (→ trained 2-flavor rotation + combat
  override). P2s folded: tierNote ApplyPlayer; JsonNumber nan/inf
  guard; dump single-write; conf doc block; emitter idempotence;
  MAX_CONTEXT_LENGTH removal; registry/coordinator/download-service doc
  corrections; 18-line tier emission pin; llama-branch + JSON_DUP
  json.empty() pins. P2s logged: absence/tier read post-stomp (turn-1
  "a few moments" instead of first-meeting — semantically minor);
  event-turn duplication + "(event)" shapes (S5, the [EVENT] compose
  leg); race-window duplicate; [BRIDGE AI] header neutering (S5, bridge
  owns notes); strict-UTF-8 request-side gate (S7/A12); no native
  budget enforcement (worst case fits ctx 4096 with margin); T4 connect
  timeout unimplemented (A6/S9); in-flight-cap silent drops + off-by-one
  (>= fix owed); sysm mutation churn (facts/zone changes re-prefill);
  per-turn redundant DB work; dump unbounded (dev-only); native history
  composition vs banklib session shape partially covered (assistant
  turns are voiced prose without tool lines — deliberate, injection
  law); dual dialects drift risk (S5 prompt-snapshot must capture both).
- Round 2 (2 reviewers, six mandates): **1 P1** (stale bootstrap
  assembler would revert the round-1 fixes on regeneration) → retired
  (emit_prompt_constants.py is the sole regeneration path — it splices
  in place). P2s folded: degenerate state-flavor parity (→ per-key
  persistent rotation counter under the history mutex); docs sample
  re-staled by the governor fix (→ GlobalMax 8 + botToBot line);
  conf-doc MaxNewTokens 120→200 + Temp dedup; dump atomicity claim
  softened. Stage-record conditions recorded below.
- Round 3 (final gate): **ZERO P0/P1**. All four lanes rebuilt; lockfile
  pins refreshed (freshness test green).
- Recorded conditions (binding later stages): A6's literal "120→200"
  conflicts with §2.1's tier values (120/100/120/300) — resolved as
  native-default=200 fallback, app emits per-tier (the plan's §2.1/§4.3
  prose still describes the pre-S4 single-path sampling world and
  should be reconciled at the next plan revision). The in-process
  `llama_chat_apply_template` leg is DEFERRED (M1a's parenthetical
  scopes S4 to the HTTP builder; llama stays a raw-completion debug
  path; owner: S5 if the debug path matters, else accepted). §4.4's
  template-FILE machinery (jinja asset, warm-up probe,
  --chat-template-file verify, LlmRuntimeConfig field+serialVersionUID)
  is an S9 ENTRY GATE (the picker makes the chat-dead base model
  selectable). Default flip consequence: fresh installs ship LLM-off
  with no in-app path to a chat-capable model until §4.2 (adb-push is
  the sanctioned dev channel; no model picker exists — §4.3/S9 scope).
  S5 conditions (from S3's ruling + S4's dialects): A7's checkpoint
  battery must score n=3 per-tool-family under BOTH composite prompts
  (trained HTTP now the app default; llama raw + legacy template carry
  the pre-A7 ToolInstructions); the byte-diff gate re-runs at every
  prompt change. bot2bot trained-format coverage (sysm_for_pair shape)
  is S5+; T4 chain-depth ≤2 has no conf knob (native-side property).
**VERDICT: S4 PASSED (3 rounds, converged at zero).**

### S5 — Bridge notes + TOOLS_NOTE (A7 → A1)
- Round 0 (implementation, 2026-08-30, one prompt change per checkpoint):
  A7 first — `ToolInstructions(botGuid)` returns the trained TOOLS_NOTE
  variant (GUID-keyed, same crc32 selection as the trained sysm; the
  pre-A7 "use them when they fit" license retired — no path licenses
  model-initiated tools anymore). Then A1 — new `PlayerbotLlmBridge`
  overlay: stateless trigger engine (insult > gratitude > first-meeting >
  gossip-after-verified-event > laughter-emote; ONE note; licensed tools
  only — duel/give_item skeletons deferred to S6/A2 whose executor
  whitelists them), `NormalizeTurn`/`IsEventTurn`/`SpeakFirst` (banklib
  verbatim), `RenderLegacyTurn` (compose-shaped rendering for the
  llama/template paths); `BuildTrainedChatRequest` renders event turns
  through the trained [EVENT] head + speak-first and carries the note
  legs through ComposeUserTurn; history exclusion drops the newest
  "(event)" entry (fixes the S4-logged event duplication); legacy paths
  append the rendered turn to `<post prompt>`. New checkpoint harness
  tools/llm_lab/run_n3_checkpoint.py (n=3 per-tool-family MAJORITY; fill/
  hygiene conditional on fire; versioned artifacts; trained + legacy-
  composite modes — the S3 both-composites ruling's instrument).
- Round 1 (3 reviewers, six mandates): **0 P0, 4 P1** — (1) first-meeting
  beat raced the async relationship stomp (double-fire or never-fire) →
  the absence bucket is now captured PRE-STOMP once in ChatReplyDo and
  threaded to the sysm line + both bridge entry points; (2) the A7
  baseline artifact was destroyed by a fixed filename (evidence hygiene)
  → versioned artifacts + fresh both-mode runs; (3) the both-composites
  condition was uninstrumented → the legacy-composite mode added and run;
  (4) three lockfiles stale → all lanes rebuilt. P2s folded: control-
  token scrub ([BRIDGE AI]/[EVENT]/[RESULT]/[say]/[Memories]/[State]) at
  all three persistence writes; the legacy context reserve gains the
  note budget (+256); fill/hygiene now conditional-on-fire (no vacuous
  passes). P2s logged: "(event) " prefix player-forgeable (frame
  laundering only — no tools licensed; drain-flag fix belongs with S6's
  event path); insult trigger precision ("this sword is trash" fires
  -1 with wrong-attribution fill — second-person gating for S6); S2
  turn-4 news coupling (bridge-side restraint case owed); executor
  license cross-check (S6 touches the executor); first-meeting beat lost
  on persona-fallback early exits (A10 merge-not-defer class — record);
  event+note untrained compose combination (unreachable today; before
  S6 adds housekeeping nudges); housekeeping beats absent (event path's
  memory value nil until then); redundant per-turn DB reads; history
  race; LLMToolsEnabled dead under format=1 (hand-conf only); q08-tuned
  family gaps (gossip 0/3, log_fact fill 0/3 — per-model weights-side,
  S10).
- Round 2 (final gate, 3 mandates): **ZERO P0/P1**, two P2s + P3 notes.
  All four round-1 fixes verified (pre-stomp threading sound — the
  builder's fallback re-read is dead code from the live caller; versioned
  artifacts genuine; both-composites mode RUN; all four lockfiles fresh,
  incl. the sqlite x86_64 lane the freshness test does not pin). P2#1:
  the legacy-composite harness mode scores elicit-sensitivity with the
  TRAINED note shapes, not a byte-mirror of the bridge's own triggers
  (conservative direction; docstring corrected to say so). P2#2 —
  **post-gate fix applied**: the round-1 control-token scrub ran AFTER
  the marker neuter, and scrub deletions can FUSE `<[EVENT]<` into a live
  `<<` (scratch-compiled PoC: the neuter-then-scrub chain stored a
  genuine forged `<<log_fact ...>>`) → reordered all three persistence
  writes to strip → SCRUB → NEUTER (the neuter LAST, so deletions can
  never leave a live marker; re-proven clean) and the order pin updated.
  P3s logged: async-commit double-fire window for the first-meeting beat
  (benign, executor-validated); redundant pre-stomp absence read in
  BuildPromptContext on trained turns; the dead fallback at the builder's
  absence parameter is a trap for future callers (assert or remove);
  test_lockfiles_pin_patches_content pins 3 of 4 realm lockfiles (the
  sqlite x86_64 lane is verified manually per close). Post-fix: all four
  lanes rebuilt, pins fresh, LLM host gates 12/12. Checkpoint evidence
  (both composites, n=3 majorities, versioned artifacts
  n3_trained_e2b-tuned_s5-a1_20260830-091919.json /
  n3_legacy-composite_e2b-tuned_s5-a7a1_20260830-091949.json): trained —
  6/6 families majority-fire (adjust_sentiment 2/3, share_gossip 2/3;
  fill/hygiene 1.0 when fired), S2 all clean; legacy-composite — 6/6
  majority-fire, S2 all clean. HONEST aggregate across all runs:
  adjust_sentiment is the brittle family (fires ~4/9 draws across three
  n=3 runs; earlier failures were single-`>` closer fidelity on 2 of 3
  draws, not omissions) — the majority gate passes but the family hovers
  at the boundary; carried to S10 with the C2 condition: the "tools ≥
  .93" gate converts to PER-FAMILY acceptance (per-trap pass), explicitly
  gating adjust_sentiment majority-fire on arm2′.
**VERDICT: S5 PASSED (2 rounds, converged at zero; one post-gate P2 — the
scrub-order fusion hazard — fixed with proof before close;
adjust_sentiment brittleness carried to S10 with the per-family gate
conversion).**

### S6 — ACT tools + emotes + duel hook (A2/A3/A14 + the S5 leftovers)
- Round 0 (implementation, 2026-08-30):
  **A2** — new pure host-compilable core `PlayerbotLlmToolsCore.h`
  (the hardened `<<tool>>` scanner moved out of the .cpp + IsKnownTool =
  banklib VALID_TOOLS exactly + ResolveTextEmote + the bridge's state-free
  beat predicates; discharges the S3-logged ExtractAndQueue host-battery
  debt). ExtractAndQueue whitelists all ten tools and admits a call ONLY
  when licensed. The LICENSE is bridge-recorded at note construction
  (BuildNote -> RecordLicense; stamp + tool set + the licensed line
  itself) and the stamp is THREADED note -> builder out-param ->
  ChatReplyDo -> async GenerateResponsePackets -> Generate ->
  ExtractAndQueue -> queued call -> executor re-check: a generation's
  emissions are vetted against exactly its own note; note-less
  generations (autonomous RPG/debug/bot2bot, stamp 0) queue nothing.
  FIELD AUTHORITY: every bridge-decided field (name/item/direction/
  emote/choice/category) executes from the licensed line, never the
  model's copy — only fill-hint prose (text/reason) is the model's.
  Executor mappings: duel_challenge -> CanCommitDuel (the plan's
  "??-level" strictest-class live guard: both-sides duel state, combat,
  alive, in-world, RpgDuelAction's area legality, sight range) + the
  cast-7266 idiom; give_item -> bot's OWN bags by name (exact/substring/
  plural/es-stem retry; never trusts the model's item=) through the
  RpgTradeUsefulAction trade idiom with a wrong-trader refusal; follow ->
  +follow strategy, master-only; party_invite -> the invite action;
  loot_roll -> the roll action, choice from the licensed line (mapped,
  no beat licenses it yet); move_to refused until S7's POI cards.
  **A3** — perform_emote routed through SMSG_TEXT_EMOTE ->
  HandleTextEmoteOpcode (EmotesText.dbc resolves the animation; the
  authentic "X grins." line broadcasts) with variant counts via
  EmoteActionBase::GetNumberOfEmoteVariants; all 19 trained emotes
  pinned to their 1.12 TEXTEMOTE ids; the measured fuzzy map
  (frown->no + 15 kin) and stem-plural/inflection morphology.
  **A14** — driver anchor in Player::DuelComplete before the deletes
  (all nine outcome call sites; Unit.cpp's damage win calls it on the
  LOSER with DUEL_WON) -> PlayerbotLlmMemory::OnDuelComplete: player-vs-
  bot only, INTERRUPTED skipped, opens the verified-event window and
  queues ONE [EVENT] reaction (whispered to the duel partner — a duel is
  a private exchange; party-channel fallback for the level/loot class).
  **S5 leftovers landed**: the forgeable "(event) " string prefix is
  GONE as a signal (ChatReplyDo gains a defaulted `bool isEventTurn`;
  the drain is the only true-passing caller; event texts carry no
  prefix); insult AND gratitude beats second-person-gated; the absence
  live-read fallback in BuildTrainedChatRequest removed (empty degrades
  to first-meeting); executor license cross-check (above). **Bridge
  beats added** (ready-made lines, banklib field schema, no existing
  wording changed): duel/give_item/follow/party_invite conversational
  beats (ACT requests outrank sentiment; "fight me"/"stay close"/
  contextless "join me" triggers deliberately absent + an abstract-noun
  blocklist + preposition stops — idiom precision per the bridge
  doctrine) and the event housekeeping log_fact nudge (the S5-logged
  "event path memory value nil" gap; fills reuse the trained share_gossip
  wording verbatim).
  **Tests**: host battery tools/test_llm_act_tools.cpp + tests/
  test_llm_act_tools.py (scanner incl. 20k marker-soup fuzz + seam
  de-fuse, whitelist = VALID_TOOLS, 19 emote id pins vs SharedDefines,
  beat predicates incl. the six S2 restraint turns and the idiom
  negatives, + source pins: stamp threading, field authority, gate-
  before-dispatch (executor-sliced, mutation-tested), trader guard,
  nudge event-only, all driver anchors); the A0 GetMaster pin narrowed
  to comparison-only (the follow master check; attribution still
  speakerGuid-resolved). The A0-unification order pins and the
  prompt-format byte-diff gate untouched and green.
  **Gates**: duelworld-10 chain (tools/llm_lab/s6_act_chain.py — banklib
  renderer + the bridge's exact note shapes) **10/10 scenarios
  majority-fire** (29/30 draws; artifact s6_duelworld_e2b-tuned_
  20260830-120849.json — the earlier 110729 artifact is superseded: its
  scenario 8 used the since-dropped "fight me" trigger); event+note (the
  UNTRAINED compose combination): log_fact 10/10 majorities, voice 3.0/3
  IDENTICAL to the event-only control, control clean 3.0 — measured
  clean on e2b-tuned; follow 3/3, party_invite 3/3, news and duel
  restraint turns clean 3/3; news_after_duel 8/10 (not a gate metric;
  the known share_gossip family brittleness, S10-C2). n3 checkpoint
  BOTH composites (instrument untouched for comparability): trained
  6/6 families majority + S2 all-clean (no see-saw vs the S5 baseline;
  adjust_sentiment 0.67 = its known boundary); legacy-composite 5/6 in
  both runs (run1 duel 1/3 adj 2/3; rerun duel 2/3 adj 1/3; pooled
  3/6 each) — no S6 mechanism exists (the harness prompts are banklib-
  authored and unchanged; tools/llm_lab is untracked in git so stability
  is mtime-evidence only) — recorded as family-level boundary variance
  under the legacy composite, carried with S10's per-family conversion.
  All four lanes rebuilt at close; lockfile pins fresh (the sqlite
  x86_64 lane verified manually — the freshness test pins 3 of 4).
  Host gates at close: **65 passed** (28 act_tools + 5 json + 10 banter
  + 8 a0 + 4 prompt-format + 9 sqlite-dialect + 1 lockfile pin).
- Round 1 (6 reviewers: R1 cross-layer, R2 Kotlin/driver, R3 tests,
  R4 laws, R5 systems, R6 red-team): **0 P0, 5 P1** (collapsed: the
  license stamped the bot's LIVE license at extraction time — an
  interleaved newer note or a note-less generation could be vetted
  against a license that never drove it, and share_gossip/perform_emote
  lacked the autonomous-turn seal → fixed by threading the generation's
  own stamp through the whole chain + stamp-0 sealing; give_item could
  hand into an already-open trade with a DIFFERENT player → wrong-trader
  refusal; ACT trigger idioms licensed notes on non-requests ("give me
  a moment", "fight me" third-person, contextless "join me") + the gift
  noun overran ("whetstone off hands") → trigger/stop/abstract-noun
  tightening + es-plural retry; the stamp-equality expression was
  unpinned → pin added). P2s folded: LicensedLineFor single locked
  read, gratitude second-person gate, nudge strip event-turn-only,
  follow/invite name pins. P2s logged below.
- Round 2 (3 rotated reviewers, six mandates): **1 P1** (stale
  ToolLicense contract comment still describing the pre-fix live-stamp
  behavior — S4-precedent evidence-hygiene class) → rewritten. Folds:
  the executor's separate LicenseCovers call dropped (one locked read
  gates + fetches; coverage-equivalence proven from RecordLicense's
  single construction loop), abstract-noun check extended to every
  extracted word with plural stems, alias/trigger battery rows added.
- Round 3 (2 reviewers): **1 P1** (the updated gate pin's bare find()
  anchored on the LicensedField helper's guard — mutation-proven
  ineffective) → executor-body-sliced pin, mutation-tested. Folds: dead
  LicenseCovers API deleted, "category" added to the bridge-decided
  list, the vacuous "second chance" battery row made non-vacuous,
  harness brace nesting.
- Round 4 (final gate): **ZERO P0/P1/P2**. Pin mutation-tests pass;
  LicenseCovers zero references repo-wide; 65 gates + all four lanes +
  mirror byte-identity verified.
- Recorded conditions + deferred P2 ledger (binding later stages):
  (a) "19 text-emotes visible in-game" and the executor-mapping legs are
  DEVICE-PENDING (S3 precedent: code-verified + panel-reviewed; no
  device run in S6). (b) The plan's "??"-level duel guard was
  interpreted as the strictest-class live-state validation (CanCommitDuel)
  — reconcile the wording at the next plan revision, together with A1's
  fuller housekeeping list vs S6's minimal log_fact event nudge
  (use_item is not in banklib's vocabulary at all; loot_roll/party_
  invite shipped as executor-mapped/conversational respectively).
  (c) Idiom residue: negation class ("don't duel me" fires the duel
  beat), celebration "join the party", third-person "they follow me" —
  all fail-closed at the executor (name pins/master gate) and voice a
  note the executor refuses; S7/S8 beat-table or S10 weights side.
  (d) The license store (like the whole GUID-keyed statics family) is
  never erased on bot removal — bounded by the bot population cap;
  same accepted class as S5's statics note. (e) Licensed writes can be
  dropped when a follow-up turn supersedes the license before the next
  UpdateAI tick (sub-100ms window, fail-closed; a recent-license ring
  was judged not worth it). (f) Random-bot gift completion is vetoed by
  the module's own trade heuristics (safe direction). (g) OnDuelComplete
  has no per-bot cadence — bounded by duel duration + governor + queue
  cap 2; chain-dueling costs the duelist governor budget. (h) Bot reply
  prose enters rolling history without ScrubControlTokens (pre-existing;
  a model echoing "[BRIDGE AI]" persists it into assistant history).
  (i) A duel-outcome whisper is silently dropped if the partner logs
  off mid-generation (delayed-packet name lookup; no crash). (j) The
  fieldless base-model `<<perform_emote laugh>>` form (prtools rec #1)
  is NOT accepted by the keyed parser — deferred with the S10 harness
  conversion. (k) A prompt-snapshot of the bridge's own note texts
  should join the checkpoint instrument (S5->S6 wording identity is
  asserted, not artifact-provable). (l) tools/llm_lab remains untracked
  in git — commit it so future instrument-stability claims are
  diffable. (m) A pending (unaccepted) duel lets a redundant 7266 cast
  through (core refuses it); cosmetic. (n) LLMToolsEnabled stays dead
  under format=1 (S5-logged; confirmed — an operator opting out still
  gets licensed tools; the license machinery is the real gate).
**VERDICT: S6 PASSED (4 rounds, converged at zero; duelworld-10 10/10,
event+note measured clean on e2b-tuned, no see-saw in the trained
composite; legacy-composite boundary variance recorded and carried to
S10's per-family gate).**

### S7 — Truth guards (A10 entity guard / A11 lore loop + era policy + corpus scrub / A12 post-filters)
- Round 0 (implementation, 2026-08-30):
  **Pure core** — new `PlayerbotLlmTruthCore.h` (host-compilable, the
  S6 ToolsCore pattern): A10 candidate extraction (capitalized runs +
  connectors + thing-compound absorption + quoted names + ALL-CAPS
  folding + lowercase title anchoring; sentence-initial runs need a
  second capital or quotes), the stakes gate vs the STRICT question
  shape, the FROZEN directive (`GuardDirective` — byte-pinned by the
  battery, the P45 lock); `LoreIndex` (jsonl load over the vendored
  JSON parser, idf-weighted keyword scoring with plural folding +
  phrase keys + title-exact boost, threshold 1.6, POI resolution
  title-first with article strip); the CORRECTED era lists (always-ban
  shattrath/draenei/pandaren/acherus with word boundaries + plural-s;
  sense phrases with all-occurrence scanning and deny-shaped negation
  escape via `EraHit`/`NegatedNear` — negators after, denial VERBS
  before, bare "no" adjacent; the context-allow words are NEVER
  bare-banned and "wrath"/"legion"/"dragonflight" appear only inside
  full expansion titles); `EraLintCards` (title+text+keys); the A12
  pure filters (StripMarkdown line-scoped with run pairing, ClampAscii
  whole-sequence, SplitSayCap 255 with UTF-8 backoff, word 5-grams +
  SharesFiveGram, marker terms, JaccardWords, SentenceSpans +
  InventionClaimSpans with a known-head escape, CannedDeflection).
  **A10/A11 in the bridge** — `BuildNoteInner` computes the question
  path BEFORE the beat ladder and MERGES the outcome (merge-not-defer):
  strict-question turns retrieve a card into `Note.result` (renders as
  the trained `[RESULT]` head); no card + stakes shape => the guard on
  the FIRST unresolved entity (<=1/turn; person-class compounds retry
  the known HEAD word so "Dughan guard captain" is Dughan), landing as
  `note.extra` UNDER whatever beat lines the ladder chose — the
  first-meeting log_fact never defers. Known-name resolution:
  `KnownTemplateNames` built ONCE under mutex from creatures, quests,
  items, gameobjects (hash-storage base iterator) + the area table +
  lore keys + online players; no live DB query remains. move_to is
  executor-UN-GATED by POI cards (bridge resolves the canonical title
  into the licensed `place=`; executor re-resolves and drives the go
  action with `Event("llm action", "to " + canonical, player)` —
  round-1's P0 fixed the param prefix AND the owner-less event that
  stranded masterless bots).
  **A11 corpus + cards** — `tools/llm_lab/build_lore_cards.py`: the
  592-page wiki harvest era-scrubbed at section + line level (expansion
  titles, patch refs, post-2006 dates, later zones, retrofit tells
  like "now known as"/"High King"/"former Warchief", retrospective arcs,
  wiki banners/stubs) with a 40-word stop rule against mid-page era
  drops; build-time lint refuses to write a contaminated card. 585
  cards ship as `android/app/src/main/assets/lore/lore_cards_v112.jsonl`
  (~566 KB; 137 POI), staged atomically by ServerRuntimeFiles and
  emitted as `AiPlayerbot.LLMLoreFile` (native default empty = loop
  off, fail-closed on any staging failure).
  **A12 filters** — new `PlayerbotLlmFilters.{h,cpp}`: HygienePass
  (markdown -> ascii -> invention spans), LeakFailureRaw (5-gram vs the
  request body's system furniture — player-varying lines excluded:
  backstory/relationship/facts/absence — + era scan),
  DuplicateOfRecent (per-bot 8-reply ring), Deflection (4-line
  rotation), EraBiasJson (once, loopback-only /tokenize, both token
  cases, -50, fail-open). The `PocketLlmVoiceFilter` driver anchor (a
  private static member): leak/era <=2 regens -> canned deflection;
  marker + dedupe checks run PRE-EXTRACTION on pure-scanner previews;
  EXACTLY ONE ExtractAndQueue on the finally-chosen content (round-1
  fix: the old form queued the original before the marker check and
  both draws on the dedupe reroll — double tool execution); the dedupe
  resample carries the hidden "Do not repeat your last reply; say
  something new." tail note. The era bias splices into every HTTP body
  behind a tail-clean guard (only-whitespace after the final '}').
  **Hygiene laws extended** — the current turn + rolling history now
  scrub CONTROL tokens through a FIXPOINT `ScrubControlTokens` (round-2
  R6: a single sweep missed cross-token fusions — "[RESU[Memories]LT]"
  re-fused into a live "[RESULT]"); the legacy `<initial message>` fill
  site scrubs too; `EscapeJsonString` gained the strict-UTF-8
  request-side gate (structure-validating, drops invalid sequences).
  The /say splitter anchor reconciled to ONE documented number: 255,
  with the UTF-8 backoff (production anchor + core twin both pinned).
  **S6 leftovers landed** — the negation idiom class fixed at the beat
  layer (`HitNegated`/`EarliestHit`/`ContainsTrigger`: "don't duel me"
  no longer licenses; a comma gap breaks the window so "don't just
  stand there, duel me" still fires); the n3 checkpoint records the
  note text per row (ledger (k)); the prompt-format battery covers the
  S5->S7 wording identity by byte-diff as before.
  **Scorer rebuild (the §6 entry criterion)** — sanity_battery: the
  era section is the CORRECTED 8-trap set (vanilla-ambiguous rows
  purged per A11; SW<->IF trapped via flying-mount phrasing;
  worgen/Gilneas-dead, Dalaran-floats, Ebon Blade, Outland-portal
  added) with per-trap affirmation cue sets that are NOT bare term
  echoes + an era-TRUE control (patch-1.11 Naxxramas must not be
  denied); grounded keys are NON-ECHO (every key absent from its
  question). New gate harness `tools/llm_lab/s7_truth_gates.py`: GUARD
  (invented entities; deny-or-ask-back AND no invented replacement
  entities AND voice), COMPOSED (fire under the guard note), LORE
  (cards from the SHIPPED asset via a python mirror of the native
  scoring, non-echo keys), ERA (bias + guard where the question path
  fires it), LEAK (normal probes through the production check's
  mirror; every flag human-read as a genuine verbatim leak), OVERHEDGE
  (known entities answer confidently), collision diagnostics
  (real-place probes retrieve a card and preempt the guard — the
  production order, measured), invented-attribute rates.
- Round 1 (6 reviewers: R1 cross-layer, R2 Kotlin, R3 tests, R4 laws,
  R5 systems, R6 red-team): **1 P0, 8 P1**. The P0: the move_to
  executor's go action passed "go to X" (GoAction's chat path gets
  "to X"; the raw form matches no prefix branch) and an owner-less
  Event (masterless bots — the usual whisperers — returned false at
  the requester resolve). P1s fixed: sentenceStart computed but unused
  (false guards on "Listen," openers — now wired: sentence-initial
  runs need 2 capitals or quotes); post-extraction rejections left
  queued calls + the dedupe reroll double-queued (single-extraction
  restructure above); the era pseudo-entity guard collided with the
  era backstop (the guard's own denial echoed the term and got canned
  — EraScan reworked with denial-aware escapes); a sync
  WorldDatabase.PQuery ran from async threads + unindexed
  gameobject_template scans per turn (folded into the one-time set);
  person-compound denial of real NPCs (head-word retry); /tokenize
  probes left the device for non-loopback endpoints (loopback gate);
  Kotlin staging ran eagerly for every bot-profile start and could
  break world start with the LLM off (runCatching + LLM-relevant
  gating + atomic write + size-check); the forged-[RESULT] current-
  turn injection (ScrubControlTokens on the current turn + history).
  P2s folded: " magistrate" typo, dup stoplist entries, EraLintCards
  now lints keys, SplitSayCap lead-byte backoff, bias-splice
  tail-clean guard, LeakFailure variant deletion, tmp/ fixture mkdir,
  two-sided Jaccard, real word-boundary era test, below-threshold
  BestCard case, Kotlin external-negative test. P2s logged below.
- Round 2 (3 rotated reviewers, six mandates): **0 P0, 1 P1** - the
  control-token scrub's single ordered sweep missed CROSS-TOKEN
  deletions fusing a live token ("[RESU[Memories]LT]" -> "[RESULT]":
  scratch-proven) -> fixpoint loop (the NeuterMarkers law), plus the
  legacy <initial message> fill site scrubbed. P2s folded:
  interjection + greeting stopword heads ("Listen"/"Good"), "n't
  heard" denial verb, apostrophe-bearing names become candidates
  ("Kel'Thuzad"), invention-scan known-head escape ("the Goldshire
  inn" survives), era tail clipped at sentence terminators + widened
  to 20 bytes, ordinary-speech phrase narrowing (cataclysm/
  shadowlands/battle-for-azeroth dropped from the sense list), the
  splitter pin now asserts the backoff code, the lint-keys fix pinned,
  the ExtractMovePlace stoplist dedup.
- Round 3 (final gate, 2 reviewers, six mandates): **ZERO P0/P1**.
  All round-1/2 fixes re-verified against the tree (the go-action
  prefix table + owner resolution confirmed against GoAction.cpp; the
  single-extraction flow confirmed across all four backends; the GO
  hash-storage iteration confirmed against the loader; the fixpoint
  scrub confirmed closed). All four lanes rebuilt clean; mirror
  byte-identical; lockfiles fresh (incl. the sqlite lane, verified
  manually); host gates 64+9 green; Kotlin green.
  **Gates** (n=5, e2b-tuned, artifact
  s7_truth_e2b-tuned_20260830-194300.json, bias_tokens=14):
  GUARD 5/6, COMPOSED 6/6, LORE 10/12, ERA 0/8 affirmed, LEAK 1-flag-
  human-read-genuine (all flagged draws are verbatim/near-verbatim
  BIBLE_GRUMPH example recitations - the h4 filter catching real
  leaks, exactly its job), OVERHEDGE 0/3, era-TRUE control 0-denied.
  Earlier same-scorer artifacts: 153613 (6/6, 5/6, 10/12, 0/8), 144444
  (5/6, 4/6, 11/12, 0/8). Scorer evolution across the first three runs
  (0/6 -> 2/6 -> 3/6) was scorer-side (contraction grammar flagged as
  invented nouns; real lore names flagged; echo-cue era false
  affirmations) - the harness is committed with this record, and the
  final scorer's judgments were human-read on every flagged row.
  n3 checkpoint (both composites, banklib prompts untouched by any S7
  mechanism - verified against the instrument): trained 5/6 + 5/6
  families majority-fire, S2 all-clean both runs; legacy-composite 4/6
  with adjust_sentiment 1/3. adjust_sentiment on trained went 0/6
  pooled across two runs - artifact-read this is the S5-recorded
  single-'>' closer fidelity class (draws end reason="...">' with
  finish_reason=stop; 2/3 draws in one run emitted the complete line
  with the single-'>' closer, 1/3 omitted), NOT truncation as first
  labeled; the family escalates from boundary-tracking to a BLOCKING
  arm2-prime gate at S10-C2, and S6-ledger-(j)'s single-'>' parser
  tolerance would have scored 5/6 of these draws fired (directly
  on-point for S10's harness conversion). duelworld-10 rerun: 10/10.
- Recorded conditions + deferred P2 ledger (binding later stages):
  (a) A10 class 2 (bot self-introduced proper-noun auto-canonization)
  and class 3 (rumor-class hedge licensing) are DEFERRED to S8 with
  the v2.3 P45 banks; denial-mood rotation likewise (the single frozen
  mood is the P45 lock working as designed - the tension is recorded
  for the plan's next revision). (b) Card-first ordering preempts the
  guard on invented compounds anchored to real places (collision rows:
  Lakeshire/Night Watch retrieve) - consistent with A11's "no card =>
  guard path"; the P45(c) held-out nonce names must be
  index-unanchorable. (c) The invented-attribute blind spot remains
  P45-dependent (rates logged in every gates artifact; the post-filter
  cue list does not catch "Dughan sells rune bread" class replies).
  (d) The dedupe tail note's behavioral evidence is the jaccard
  diagnostics (repeats not observed: 0.113/0.158); its ONE-NOTE-family
  risk is unmeasured and binds S8's A13 beat-exemption work to
  re-checkpoint it. (e) SplitSayCap (core) has no production caller -
  the production splitter is the SayAction anchor; both carry the same
  backoff and both are pinned, but the duplication is a drift surface
  (reconcile at the next plan revision). (f) EscapeJsonString accepts
  overlong C0/C1 and surrogate-half sequences (structure-valid, JSON-
  safe - no breakout; a strict server may still reject them; tighten
  at S10 with the harness conversion). (g) The era backstop's generic
  after-tail negator window still clears affirmation-with-qualifier
  shapes ("a draenei is not welcome here") - fail-safe direction,
  noted. (h) IsKnownName's FindPlayerByName runs on async threads
  (unsynchronized player-name map read; pre-existing class, bounded by
  the read guard). (i) The era-TRUE control's acknowledge keywords
  under-count substantive acknowledgments (human-read 4/5 acknowledge;
  scorer strictness). (j) The guard scorer kept deny-OR-ask-back (not
  AND): run-6 evidence shows 9/30 draws denied via ask-back alone, all
  human-read as substantive in-character denials the cue regexes miss
  - recorded as a judgment call; tighten with P45's fixed phrasings.
  (k) The nudge-strip pin and the S6 event-path anchor shapes updated
  for the scrub wrapper (tests/test_llm_act_tools.py). (l) Kotlin
  staging accepts a same-size stale file on app update (size-check
  only; app-private dir, non-attacker surface).
**VERDICT: S7 PASSED (3 rounds, converged at zero; guard 5/6, composed
6/6, lore 10/12, era 0/8, leak filter catching genuine verbatim
recitation 1/6-flagged-human-read, over-hedge 0/3; adjust_sentiment
escalated to a blocking S10-C2 gate with the single-'>' closer class
correctly labeled).**

### S8 — Memory USE + believability beats (A13 recall / A16 ceremony + unlocks / A17 initiative / A18 crowd / A19 gossip)
- Round 0 (implementation, 2026-08-30):
  **New pure core** `PlayerbotLlmRecallCore.h` (host-compilable, the
  ToolsCore/TruthCore pattern): the fact classifier + FactClassMask
  constants, the recall question shapes (negation-aware; the memory
  question deliberately has no bare "do you remember" - directions
  questions are the lore card's), FactDirect (closed-class me/my→you/your
  with word boundaries + leading-pronoun strip), the beat-cargo builders
  (the recallfix measured table verbatim: DebtCargo "It is UNPAID. You
  are NOT square. Name it.", NewsCargo "Something DID happen ... Tell
  it.", MemoryCargo with the tier-3 ask-after arm, Grudge/Gossip cargo),
  the tier-ceremony wording (plan-verbatim up-shape + a down-shape, never
  naming the mechanic), the GUID-stable secret bank + NicknameOf/
  NicknameTierNote/NicknameAdoptionCargo (the Westfall-law procedure
  form), MoneyPhrase, AbsenceMagnitudeLine, the authored initiative line
  shapes, ContainsWordExact (case-sensitive word-boundary name match),
  and DistortGossipHop (hedge sharpening + one money rung per hop,
  deterministic).
  **A13 in the bridge** — the ladder: ACT > insult > gratitude >
  debt-question > memory-question > news-recall > greeting-gap (gossip
  > grudge > weave, once per absence gap) > first-meeting >
  gossip-window > laughter; every recall beat supplies cargo as
  note.extra and marks the note mandatesContent. The dedupe exemption:
  ToolLicense carries mandatesContent; the voice filter skips the A12
  Jaccard reroll for the generation's OWN stamped note, as a per-bot
  60s-budget claim (NoteMandatesContent - stamp-checked, rate-capped).
  **A16** — GetPreStompState (ONE query: absence bucket + tier, the S5
  law generalized); the ceremony fires on OBSERVED transitions (one
  turn after the async crossing write lands; ACT turns PEEK without
  consuming); tier-4 releases the one-time secret via the licensed
  "secret told:" log_fact line (the durable marker; category-constrained
  probe); tier-5 gains the sysm tierNote (nickname) + the adoption
  procedure on the ceremony; tier>=4 flavors the give_item fill with
  the friend's-price procedure.
  **A17** — TickInitiative (world thread, UpdateAI cadence): greet-first
  arrival packets for remembered players (GreetingLine + absence
  magnitude + town talk), debt reminders (MoneyPhrase), tier-3 goal
  ask-afters, the level-up cheer (the event note licenses
  perform_emote cheer), each fact initiating AT MOST ONCE
  (InitiatedFactIds); every class shares the ambient slot with the
  600s zero-spam cap; eligibility precedes the slot claim. EventReaction
  gained eventKind/notBefore/emote; the drain waits the stagger window.
  **A18** — the crowd tier on non-trigger /say (QueueCrowdEmote from the
  gate anchor: 1/10 roll, 12s world window, 2-5s notBefore stagger,
  deterministic text emotes through the shared PlayTextEmote - the
  extracted perform_emote delivery); say ANSWERS stagger 2-5s via the
  new QueueChatResponse delaySecs param (non-mention says pass
  undelayed - the crowd tier owns that pacing); the say-channel history
  cross-injection caps at 5; the rare authored bot2bot exchange on
  arrival (opener/reply paired, reply on /say, staggered).
  **A19** — OnDuelComplete writes the bot's own fact (news-recall cargo)
  + a player-subject world_gossip row; GossipAbout (newest-8, in-code
  case-sensitive word-boundary match, no LIKE) renders on the greeting
  surfaces with a belief row logged one DistortGossipHop deep.
  The persona-fallback first-meeting fold landed (S5-logged); the
  greeting intercept carries magnitude + town talk; (tone ±) prefixes
  carry the bridge-decided sign and never render raw (journal + sysm
  facts strip them).
  **Tests**: tools/test_llm_recall.cpp + tests/test_llm_recall.py (NEW:
  battery + source pins incl. masks/word-exact/category-constraint/
  timestamp/eligibility/exemption-cap/ceremony-defer/stagger); the two
  changed pins in act_tools/truth justified by the extraction/signature
  changes; s8_beats_gates.py (NEW desktop harness). All four lanes
  rebuilt at every fix point; lockfiles fresh (the sqlite x86_64 lane
  verified manually per the standing protocol).
  **Gates** (final artifact s8_beats_e2b-tuned_20260830-220238.json):
  ASSOCIATIVE 4/4 majority (honesty: measured lift over the no-note
  control on 2/4 - news 2/3 vs 0/3, weave 3/3 vs 0/3; the debt and
  memory controls also majority-recall, recorded in the artifact +
  warned in-run); CEREMONY floor 5/5 (voiced + no mechanic + differs
  from control, controls persisted) and the human-read felt-change
  gate 4/5 PASS (independently read twice: round-1 R4 4/5 on 204615,
  round-2 R4 4/5 on 220238, different draws); EVENT_KIND levelup cheer
  3/3, duel sentiment 2/3 strict / 3/3 tolerant (the S5/S7-recorded
  single-'>' closer class); JACCARD re-checkpoint (the S7-ledger-(d)
  obligation) mean 0.209, none over 0.5; n3 checkpoint BOTH composites
  (instrument untouched): trained 6/6 families majority + S2 all-clean
  (adjust_sentiment 1.00 this run), legacy 5/6 (share_gossip 0.33 - the
  known boundary family, carried with S10's per-family gate).
- Round 1 (6 reviewers: R1 cross-layer, R2 Kotlin, R3 tests, R4 laws,
  R5 systems, R6 red-team): **1 P0, 11 P1**. The P0: the four recall
  call sites passed raw FactClass VALUES as bit masks (FACT_DEBT==1
  selected PLAIN - every recall beat queried the wrong class; the
  harness composes cargo directly so the gates measured intent, not
  plumbing) → FactClassMask constants + all four sites + battery CHECK
  + source pin (mutation-tested in round 2). P1s fixed: TickInitiative
  burned the 10-min slot before eligibility (reordered); the bot2bot
  reply answered the wrong opener on the wrong channel (replies[pick] +
  CHAT_MSG_SAY + drain routing); GossipAbout substring misattribution
  (ContainsWordExact - "Ash" no longer matches "Ashmar"/"the ash of the
  fire"); the "secret told:" marker forgeable via model-filled text
  (probe category-constrained to player-identity); the first-meeting
  beat double-fired from the NULL initial timestamp (both dialect
  INSERTs stamp CURRENT_TIMESTAMP); the dedupe exemption had no rate
  bound (per-bot 60s claim); the ceremony was consumed-but-suppressed
  on ACT turns (Peek defers); crowd emotes double-staggered 4-10s
  (non-mention says now undelayed); ceremony controls were discarded
  from artifacts (persisted); the new core's build wiring unpinned
  (pinned); plus the record-only P1s (deferrals, device legs - below).
- Round 2 (3 rotated reviewers, six mandates): **ZERO P0/P1**. All ten
  fixes verified against the tree; four pins mutation-tested (mask,
  word-exact, timestamp, stagger - all killed their mutations and
  restored green); anchors byte-verified against the pristine submodule
  (incl. the 5-trailing-space QUEUE_CALL line); the 61s-cadence
  verbatim-repeat residual RULED within the exemption's intent; the
  ceremony human-read re-judged 4/5 on the new artifact; Peek/Consume
  interleavings, SQLite CURRENT_TIMESTAMP compatibility, and the
  async-thread exemption claim all held. Post-gate folds (S2 precedent,
  full rerun + lane rebuilds): the stale voice-filter comment, three
  drain-contract pins (SAY routing, notBefore wait, tone-skip
  adjacency), and the tier-4 secret release widened to tier>=4 (a
  compounded +2 crossing no longer permanently skips the Trusted
  unlock). Final: **104 host gates green**; all four lanes rebuilt;
  lockfiles fresh.
- Recorded conditions + deferred P2 ledger (binding later stages):
  (a) A16 Bonded MEETUP INITIATION and A17 dusk appointments are NOT
  implemented (no POI-proximity machinery for the former; no future-time
  scheduler for the latter) - deferred with this record.
  (b) The Bonded nickname is effectively one-shot: the standing
  tierNote measured 0/3 and the adoption procedure 0/3-1/3 across runs
  (pooled 1/6) - the procedure stays in code, the surface needs v2.3
  bank rows (P45-family nickname-adoption examples) at S10.
  (c) A17's 30-min idle-adjacency session gate and A18's in-world
  pacing are DEVICE-PENDING (S3/S6 precedent: code-verified +
  panel-reviewed; the desktop world console has no player-chat
  injection). The plan's "add an unprompted greeting-weave case to G5"
  landed in the desktop harness instead of G5 (S10's device gate).
  (d) The exemption is generation-level, wider than the plan's letter
  ("reroll checks non-beat prose only") - ruled faithful in effect
  (prose-only is not implementable at reply level), discharged by the
  jaccard re-checkpoint (0.209/none-over). The conversational
  share_gossip news beat does NOT mandate (stays deduped - inconsistent
  but fail-safe). The claim burns before the duplicate test (safe
  direction: more dedupe, not less).
  (e) Peek-deferral edges: a restart between a crossing write and the
  next non-ACT conversational turn swallows that ceremony; a
  governor-dropped mandated generation consumes without voicing;
  compounded multi-step crossings fire ONE ceremony at the highest tier
  (the tier>=4 fold keeps the secret releasable).
  (f) Gossip matching residual: capitalized tokens in model-authored
  gossip prose (hyphen compounds, sentence-capitalized common words)
  can misattribute town talk - bounded to greeting flavor + one belief
  row; precision-over-recall accepted.
  (g) Migration footnote: pre-S8 rows with NULL last_interaction_at
  re-fire the first-meeting beat ONCE on upgrade (one extra
  shared-event row per affected pairing).
  (h) Pending staggered reactions can be evicted by the cap-2 queue
  after their budgets burned (12s window / 10-min slot) - the
  burn-in-silence class, bounded, accepted.
  (i) Crowd emotes share the cap-2 reaction deque with generated event
  reactions (eviction contention, fail-quiet); crowd/b2b queue-time
  combat gates are not re-checked at drain. The crowd tier is
  ai-chat-bots-only (strategy-less random bots never reach ChatReplyDo
  on non-mention says).
  (j) Statics growth: LastSeenNear/InitiatedFactIds are the first N-by-P
  members of the accepted never-cleaned GUID-statics class (S6-ledger
  (d)); bounded by populations.
  (k) TickInitiative's steady-state PQuery runs per nearby player per
  20s scan even when the slot is exhausted (indexed lookups, the
  accepted world-thread sync-query class; a slot-peek before the query
  would drop it to ~zero - fold if ever touched again).
  (l) The harness's python cargo mirrors are verified against the C++
  by inspection only (the battery pins the C++; no automated mirror
  pin) - the wording-identity claim is record-carried (S6-ledger (k)
  class).
  (m) A10 class-2/class-3 auto-canonization + rumor hedges +
  denial-mood rotation remain P45-DEFERRED (verified absent, not
  half-implemented - with S10's v2.3 banks). The Trusted "discount" is
  a fill-hint procedure with no mechanical price (no currency leg in
  the licensed toolset - reconcile the plan wording at the next
  revision). A19 belief rows never re-enter the world pool (drift is
  one hop per bot, not a deepening chain). The greeting-shape
  magnitude rides the sysm absence slot on the LLM path (the authored
  surface carries the magnitude line) - shape deviation recorded.
  (n) R2's pre-existing Kotlin P2s deferred to S9: the LlmScreen
  banter-toggle copy under-describes what the switch now gates
  (initiative/crowd ride it), and the debug-build llmOverrides block
  omits LLMBanterEnabled (native default 1 runs the layer on
  regardless of the toggle - debug path only). The lockfile freshness
  test still pins 3 of 4 lanes by design (the sqlite x86_64 lane
  verified manually per close, this session included).
**VERDICT: S8 PASSED (2 rounds, converged at zero; associative 4/4 with
lift honestly split 2/4, ceremony human-read 4/5 twice on independent
draws, event kinds firing, jaccard re-checkpoint clean, no see-saw in
the trained composite; the recall-mask P0 caught by review - the
desktop harness measures intent, the tree needed the pin).**
