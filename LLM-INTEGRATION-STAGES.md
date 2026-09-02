# LLM INTEGRATION — Staged Execution Plan & Review Protocol (2026-08-29)

Companion to `LLM-INTEGRATION.md` (rev 3). This file defines the execution
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
| S9 | Player surface | E1-E5 (pacing, onboarding, Talk UI, visible progression) | `Hud.lua`/`Core.lua`/`Talk.lua`, LlmScreen, packet pacing code | ack <1s; first line ≤6-8s; Talk flow no name typing; tier lines visible | **PASSED (2 rounds + post-gate folds)** |
| S10 | E6 world chatter (ambient layers) | §4.6b (composer pipeline, event-gated scheduler + queue, power ladder, fatigue/world-ring, cloud routing, master toggle) | SayAction/PlayerbotAI anchors, new PlayerbotLlmChatter overlay, Kotlin chatter queue + toggle (LlmScreen/ServerRuntimeFiles), cloud client routing | event-gated firing (silence default); world-ring zero-repeat 6h soak; ≤1.5% battery/session device-batched; whisper ack unharmed under load; interruption + combat blocks observed; legend hops ≤ cap; composer voice human-read panel; power ladder honored | **PASSED (4 rounds + post-gate folds)** |
| S11 | v2.3 + v2.4 data + retrain (was S10 pre-rev-3) | §5 + §5.1 (P45-P49, P50 long-form, P51 depth arcs, P52 ambient barks, API-drafter protocol, G0-G5 incl. length-conditional sections, arms arm2′/arm1b′/2B) | G:\NPU LLM banks + train scripts | G0-G5 gates per §5; held-out hedge ≥5/6; over-hedge 0/6; tools ≥.93 per-family (S5-C2); length histograms + multi-turn eval per §5.1 | **IN PROGRESS — rounds 0-5 landed (round 5 CONVERGED at zero P0/P1: banks P45-P54 authored, gated, composed — 16,953 examples); retrain arms + G0-G5 pending — ← NEXT** |

Parallel tracks (start after their dependency stage passes): S11 (retrain)
authoring may start after S4; S9 E1 pacing subset may start after S5.
S10 (world chatter) is unblocked now that S9 has passed and may run in
parallel with S11.

Rev-3 renumber note (2026-08-31): world chatter enters as **S10** (it
follows S9); the v2.3/v2.4 retrain is renumbered **S11**. References to
"S10" in the frozen S1-S8 stage records and their ledger items mean the
RETRAIN (now S11) — those records stand as written.

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

### S9 — Player surface (E1 pacing + ack / E2 first contact / E3 Talk UI / E4 progression / E5 picker + §4.4 entry gate + T4 + S8-(n))
- Round 0 (implementation, 2026-08-31):
  **§4.4 entry gate (the S4-record binding condition)** — asset
  `android/app/src/main/assets/llm/chat_template_nonthinking.jinja`
  (the tuned export's template MINUS the thinking machinery;
  render-verified byte-identical to the tuned GGUF's non-thinking path
  on 4 message shapes incl. the thinking-content-stripping shape).
  Warm-up probe in `LlmRuntimeService.runSupervisor`: one 8-token POST
  with NO kwargs (must see the template's OWN default) once /health
  passes — also pays the measured first-request penalty; detection =
  reasoning_content non-empty OR content empty; on detection, ONE
  restart with `--chat-template <staged file's CONTENT>` (CRITICAL:
  the vendored 6d05498 binary has NO --chat-template-file —
  string-extract verified, 0 occurrences in libllama-server-impl.so,
  --chat-template present x2; the desktop b10520 has both, and
  verification on it alone green-lit a flag the shipped binary lacks —
  the plan's "verify on the vendored binary first" warning was exactly
  this trap). `LlmRuntimeConfig` gains chatTemplateFile +
  serialVersionUID = 1L (sticky-restart compat; pre-field streams
  deserialize to null = fail-open). LIVE-VERIFIED end to end on the
  exact §1.4 failure model (gemma-4-E2B it): default template burns
  the 8-token budget (reasoning 20 chars, content empty,
  finish=length); the file-flag override AND the inline-content form
  both return content "Ready." finish=stop — artifacts
  `C:\llm-lab\results\s44_probe_default-template_20260831.json`,
  `s44_probe_nonthinking-override_20260831.json`,
  `s44_probe_nonthinking-inline_20260831.json`.
  **E1 pacing** — the generation timeDiff becomes a RUNNING credit
  consumed across ALL lines (was: zeroed after the first, which then
  still dribbled); MsPerChar 200 -> 35 on the reply call; the busy
  placeholder lands INSTANTLY (busyReply ? 0 : ...; the <1 s busy
  law); instant whisper ack BEFORE the memory reads + generation
  (face the speaker via SetFacingToObject + one deterministic text
  emote nod/wave by GUID parity through PlayTextEmote — zero LLM
  cost, 4 s per-pairing rate cap, whisper + non-event only);
  per-class voice budgets via the pure `pocketllm::ApplyReplyBudget`
  (conversational 2 lines x 160 B, ambient RPG 1 x 80 B, UTF-8-backoff
  cuts) applied BEFORE the history recorder; replyClass threads
  through GenerateResponsePackets (0 chat / 1 RPG); the journal keeps
  its 4 ms/char diary pace; the 255 splitter cap stays S7's one
  documented number.
  **T4** — `AiPlayerbot.LLMConnectTimeout` (default 10, clamped 1-60)
  bounds the TCP connect (non-blocking connect + select + SO_ERROR
  verify + restore-to-blocking); SO_RCVTIMEO/SO_SNDTIMEO = the
  generation budget bound the TLS-handshake + write legs (round-1
  fix; a stuck SSL_connect previously leaked generation slots
  forever). The 30 s T4 gen timeout was already emitted.
  **E2** — one-time login onboarding sys line (Player.cpp anchor at
  SendInitialPacketsAfterAddToMap -> OnPlayerLogin; real players only;
  once per character per world process via the OnboardedPlayers
  dedupe — the anchor site ALSO fires on cross-map teleports,
  round-1 R1); the player's FIRST-EVER bot contact gets the scripted
  welcome (AuthoredFirstContactWelcome: PlayerHasAnyPairing gate + a
  NATIVE LogFact("met X for the first time", shared-event) — the
  memory law beats the voice law at the opening moment) + AppendTurn
  x2 + the relationship touch; first-run tutorial step 5 points at
  Settings -> AI bot speech (honest copy: tuned models are staged from
  a PC until §4.2; the in-app download is the untuned fallback).
  **E3** — new addon module Talk.lua: resolution target-player ->
  last whisperer -> last say speaker (the client-side approximation of
  "nearest": 1.12 has no unit radar), opens the stock composer
  pre-filled "/w Name " via ChatFrame_OpenChat (type-guarded); the
  radial Talk entry takes SOCIAL's slot (the parallel stream PINNED
  Move UI's slot by test — Social is unpinned and touch reaches the
  stock minimap button); the Hud grows the chat to 220 px while a
  whisper conversation is live (10 s linger) and reveals the scroll
  chrome on a 5-lines/2.5 s burst (the journal dump); journaled rects
  are never resized; the module is registered ("talk" bisection
  switch, TOC, package).
  **E4** — tier-change sys line at the A16 ceremony consume site
  (TierShiftSysLine "X seems warmer/colder toward you." — pure
  DB-derived, survives governor-dropped ceremonies, never on ACT
  turns); the AUTOMATIC standing one-liner on a pairing's first
  whisper of the session (MaybeSessionStandingLine, once per pairing
  per process, skips first meetings) + the whisper keywords
  "standing"/"gossip" (zero-generation reads; NO relationship points —
  round-1 R6 killed the tier-5 farm); the whisper keyword set is
  documented in docs/llm-runtime-submenu.md; a conversations counter
  (atomic, the recorder gate's own definition, read on the debug-llm
  surface).
  **E5 + S8-(n)** — the LlmScreen model picker, small-first with
  trade-off copy + the restart-required note (§2.1's "LlmScreen must
  say so"); the banter-toggle copy now describes
  greet-first/initiative/crowd/bot2bot; the debug llmOverrides block
  emits LLMBanterEnabled.
  **Tests**: BudgetLeg (act_tools battery) + TierShiftSysLine rows
  (recall battery); tests/test_llm_player_surface.py (NEW, 19
  source-contract pins); Kotlin: LlmRuntimePolicyTest (+connect line,
  +template override/fail-open, +staging never deletes),
  LlmRuntimeConfigTest (NEW: serialVersionUID/round-trip/null),
  LlmRuntimeServiceWarmUpTest (NEW: the probe/restart source
  contract), FirstRunTutorialTest (5 steps + the LLM step), and the
  debug-banter assertions; the mutation suite kills 26/26 mutants
  (tmp/mutation_s9.py, incl. the ToolsCore budget implementation and
  the service mutants).
  **Verification at close**: 124 host gates green; all Kotlin suites
  green (4 pre-existing parallel-stream AndroidPortAssetTest failures
  remain, theirs); all four lanes rebuilt clean after the final
  folds; lockfiles fresh incl. the sqlite x86_64 lane verified
  manually per the standing protocol; the n3/sanity instruments are
  untouched (no prompt change -> no see-saw checkpoint owed; see-saw
  verified clean by round-2 R4).
- Round 1 (6 reviewers: R1 cross-layer, R2 Kotlin, R3 tests, R4 laws,
  R5 systems, R6 red-team): **1 P0, 8 P1**. The P0 (R3): the new
  BudgetLeg shipped unwired — tests/test_llm_act_tools.py invoked only
  the four old legs, so a ToolsCore budget mutation SURVIVED the suite
  (demonstrated by mutation) -> test_budget_leg added + the mutation
  suite extended to mutate the shipped budget implementation. P1s
  fixed: (R1) OnPlayerLogin re-fired per cross-map teleport ->
  OnboardedPlayers once-per-process dedupe; (R4) the AUTOMATIC
  "standing on first whisper of a session" surface was missing ->
  MaybeSessionStandingLine; (R6) the standing/gossip keywords awarded
  uncapped +1 relationship points (tier-5 in ~120 zero-cost whispers)
  -> points removed (journal precedent); (R6) the §4.4 retry could
  wedge into a permanent 30 s crash loop (a vanished staged file +
  llama-server's parse-time abort) -> arm-time read + inline-content
  flag + armedTemplate revert-to-base on a never-healthy override
  child + staging never deleting the live file; (R6) the
  TLS-handshake/write legs were unbounded past T4's connect budget
  (slot leak) -> SO_RCVTIMEO/SO_SNDTIMEO = the generation budget;
  (R2) tutorial step 5 pointed at a download the q08 entry cannot
  offer -> honest copy; plus the CRITICAL flag-surface find above
  (--chat-template-file absent from the vendored binary; caught by
  re-verifying R4's round-1 P2 against the actual .so). P2s folded:
  registry KDoc, the connect clamp, the WIN32 log error,
  outError->outDebug on the hot pacing path, the false "logged at the
  call site" comment, the Talk write-back, journaled-pin/order pins
  tightened, the docs standing example.
- Round 2 (3 rotated reviewers, six mandates): **ZERO P0/P1**. All
  round-1 fixes verified against the tree (incl. the recv-path
  interplay: RecvWithTimeout runs the socket non-blocking so the
  socket timeouts are inert there; the SSL path's WANT_READ branch
  already retries under the same elapsed budget); independent
  re-verification of the vendored flag surface and the argv safety of
  the inline template (2.1 KB, NUL-free); the mutation rerun killed
  26/26; no double-BuildNote, no sticky-restart double-apply
  (persistConfig stores only the intent config).
- Post-gate folds (S2/S8 precedent, full rerun + lane rebuilds):
  stale --chat-template-file comments, the self-contradictory
  still-thinking log message, the missing debug-banter test
  assertions, the EINTR disposition recorded in-anchor — and the
  socklen_t "portability" fold was itself REVERTED after it broke
  bionic's getsockopt prototype (int* vs socklen_t*); the correct
  finding is that MinGW defines socklen_t via the file's own
  ws2tcpip.h include.
- Recorded conditions + deferred P2 ledger (binding later stages):
  (a) PlayerHasAnyPairing's player-only lookup full-scans
  bot_player_relationship (PK (bot,player), no player index) on the
  world thread at login/first-contact — accepted: the table is
  pairing-population-sized on a single-player device; add KEY(player)
  at the next touch of the (parallel-stream-owned) schema SQL.
  (b) The T4 socket bound is PER-OP, not a total deadline — a peer
  that drips one record per just-under-timeout interval can still
  stretch the SSL legs; the realistic dead-endpoint case is bounded.
  (c) connect select() EINTR falls through as failure (fail-safe;
  recorded in-anchor).
  (d) The warm-up probe's empty-content-without-thinking class is a
  false positive -> one harmless restart (bounded by one-retry +
  revert; logged distinctly).
  (e) stageChatTemplate's copyTo fallback can leave a partial live
  file on a mid-copy IO failure (double fault: rename refused AND copy
  fails); contained by the arm-time read + one-retry + revert + the
  collision classification.
  (f) When a pairing's session-first whisper IS exactly "standing",
  the automatic sys line and the keyword reply voice the same sentence
  once (cosmetic; the player asked).
  (g) The journal keyword branch lacks the whisper+non-event gate its
  siblings have — IMPROBABLE (not unreachable): a 2-letter bot name
  substring of "journal" admitted by the party substring gate could
  dump the journal; self-addressed, pure read, latent.
  (h) Talk resolution has no faction/ignore filter (1.12 offers no
  name->faction API client-side; a cross-faction lastSayFrom makes the
  pre-filled whisper fail silently — player confusion only).
  (i) E2's "one-time" is once per character per WORLD PROCESS (the
  no-pairing DB gate + the dedupe), not once-ever across restarts —
  the accepted reading, recorded.
  (j) MsPerChar 35 applies to all chat-class replies (the plan's
  letter said whisper-class); ambient is separately clamped 1x80 —
  in-spirit, wider than the letter, recorded.
  (k) The E4 conversations counter's app-side transport stays deferred
  (§4.3's declared Workstream-A dependency; WorldNative's ABI array is
  parallel-stream-owned); the native counter + the debug-llm surface
  ship.
  (l) The template override is armed probe-gated for every model, but
  the asset is gemma-dialect: only the gemma base can trip today (the
  qwen-tuned default measured non-thinking with kwargs omitted — the
  S4 artifact s44_thinking_kwargs_q08-tuned.json); a future registry
  family that trips needs its own dialect asset.
  (m) DEVICE-PENDING legs (S3/S6/S8 precedent): ack <1 s, first line
  6-8 s, the Talk flow in-game, tier lines visible, the on-device
  template restart, and executing the vendored binary's flag surface
  on-device (string extraction + the desktop live legs stand in).
  (n) Detekt is red tree-wide in the parallel working tree
  (pre-existing; a 1340-entry baseline with zero Llm entries); S9's
  Kotlin adds generic-catch findings in adjacent style. The 4
  AndroidPortAssetTest failures are the parallel stream's stale pins
  (version 0.6.0, getglobal vs Live) — theirs to finish.
  (o) The sticky-restart pre-field deserialization fixture is not
  exercised (dev-only exposure; serialVersionUID pinned + round-trip
  tested).
**VERDICT: S9 PASSED (2 rounds, converged at zero; §4.4 machinery
live-verified on the §1.4 failure model with three artifacts, the
vendored binary's flag surface checked against the actual .so after
desktop verification green-lit a flag it lacks; the pacing law +
budgets pinned by 19 source-contract pins and 26 killed mutants; four
lanes + lockfiles fresh at close).**

### S10 — E6 world chatter (§4.6b: silence-default ambient layers + power ladder + master toggle)
- Round 0 (implementation, 2026-08-31):
  **New pure core** `PlayerbotLlmChatterCore.h` (host-compilable, the
  ToolsCore/TruthCore/RecallCore pattern): the rung policy table
  (NORMAL cloud-composer batches 270s window/30-60s display; CONSTRAINED
  device single lines 540s/90-120s; CRITICAL global-only 1/3min;
  EMERGENCY authored event-grounded floor 120s — pins absolute), the
  WORLD-LEVEL repetition ring (24-entry, JaccardWords 0.5 — the A12
  metric applied cross-bot), the fatigue + legend ledger (per-fact
  retirement at 5 tellings; per-(template x speaker x listener) spacing;
  per-listener credence heard→never-retell; LegendTellingText = the
  immutable row as truth, deterministic DistortGossipHop per
  non-originator telling, drift frozen at 3 content hops), the murmur
  register tolerance (5-24 words / 120 B — the documented pre-P52 band,
  NOT the 8-20 trained target), the 10 event-grounded floor templates
  (every template addresses {L} + carries {E} — an authored line never
  fabricates ledger state), the frozen device-path wording
  (MurmurSystemMessage/MurmurNote/PartyNote/GlobalNote +
  ComposerSystemPrompt/UserPrompt — the P52 wording lock, byte-pinned),
  ParseComposerScript (speaker-validated, 6-turn cap, full line-safety
  law inline), ClampMurmurBytes, ChatterLineSafe (the banter-core
  LineIsValid law tightened: printable ASCII, no <>{}|, no */[/space
  leads — an autonomous producer may never mint pipes/newlines), the
  8s player-channel hold + the 30s ambient ADMISSION window.
  **New overlay** `PlayerbotLlmChatter.{h,cpp}`: the world-thread
  scheduler Tick (riding RandomPlayerbotMgr's 10s telemetry gate),
  power-file reconcile (missing/disabled/garbage-rung = OFF + queue
  clear — the master kill; stale >10min INCLUDING zero/future stamps =
  EMERGENCY + non-floor flush), the pre-generated queue (cap 12,
  drain <=2/tick scanning past not-due heads, interruption deferral
  requeues front +10s), the murmur refill (batch-window + low-water +
  quiet-channel gates; COMPOSER script over <=3 nearby personas at
  NORMAL with a composer configured — per-bot device calls are the
  fallback, per §4.6b "per-bot only when the player is involved"),
  party banter (per-master 720/900s windows stamped ON THE ROLL win or
  lose at 50%/25%; the duel event note fires once >=60s then is
  CONSUMED, re-armed only while the channel is held), the rare global
  set piece (duel-class preference, spacing burns on the roll), and
  delivery (Say/party/zone-General via the JoinChatChannels idiom;
  ring+fatigue re-vet at delivery; ring/fatigue/credence/template
  ledger + RememberReply + shared-channel AppendTurn; murmur
  MarkHeard for everyone in 30y). Device batches pay the SHARED
  governor (GovernorAdmit — the S3 block extracted) and yield the
  interactive lane (InteractiveGenerationInFlight); workers are
  copy-only detached threads under catch-all wrappers (a spawn throw or
  bad_alloc can neither strand the batch flag nor kill the world);
  composer turns deliver IN ORDER (monotone base+i*perTurn stagger).
  **Kotlin**: ChatterPowerMonitor (the pure rung table vs
  getThermalHeadroom/battery/charging/connectivity; atomic 60s power
  file; epoch-keyed refresher armed at every world start in BOTH
  modes), the two-directional master toggle (the conf arms the
  subsystem + names the file whenever the LLM runs; the FILE's enabled
  flag carries the live llmAmbience switch — mid-session both ways),
  conf emission (LLMChatterEnabled/PowerFile/Composer* riding the
  external endpoint fields), the LlmScreen "World chatter (beta)"
  switch, Settings llmAmbience triple-write (default OFF).
  **Driver anchors**: manifest + copies; PlayerbotAIConfig keys
  (composer URL parse GUARDED — parseUrl throws on the empty default;
  round-1 R6's P0 was exactly this at world boot); the mgr Tick +
  include; the SayAction gate stamp (NotePlayerInteraction on
  real-player conversational triggers); GenerateHttp endpoint/key
  overrides (the composer POSTs its own endpoint through the hardened
  client); the OnDuelComplete hook.
  **Tests**: tools/test_llm_chatter.cpp (412 checks: policy table,
  ring, fatigue/legend incl. absolute constants, register, ChatterLineSafe
  rows, floor templates, frozen wording, composer parse, the 6h soak
  over the REAL core math — silence default at every rung, per-fact
  ceilings, pairwise zero-repeat, interruption full suppression, floor
  <=1/120s, global <=1/180s, determinism, the governor-rate arithmetic
  incl. the composer multi-line cap) + tests/test_llm_chatter.py (10
  legs: battery + source-contract pins, all call-site slices) +
  tmp/mutation_s10.py (67 mutants across core/scheduler/driver/Kotlin;
  green-baseline assert + per-mutant byte-verified restore + leftover
  sweep) + Kotlin (ChatterPowerMonitorTest rung table, the chatter
  emission leg, the write-set).
- Round 1 (6 reviewers): **5 P0, 12 P1**. P0s: the composer URL parse
  crashed every world boot without a composer row (parseUrl throws on
  ""; now guarded like the main endpoint); the duel note was never
  consumed (party machine-gunned every ~60s forever after one duel;
  now consumed on fire + the window stamps on the ROLL win or lose);
  the whisper-lane claim (--parallel 2 absent) RESOLVED by re-extracting
  the vendored .so — the binary ships AUTO-parallel ("n_parallel is set
  to auto, using n_parallel = 4 and kv_unified = true"; 0 hits for
  --parallel; the desktop build agrees) so the plan's lane-pinned flag
  is UNIMPLEMENTABLE there and no eviction/queueing exists; the
  code-side quiet-window admission gate (30s) shipped instead; two
  vacuous pins (rung bounds, queue clear) replaced with call-site
  slices. P1s folded: EMERGENCY pick window (was 0 = re-pick/re-drift
  every tick), the soak modeled the wrong cadence (now window-driven),
  the global floor leaking to generated rungs, thread-spawn try/catch,
  murmur composer-fed at NORMAL, display-policy threading into jobs,
  the Kotlin one-directional toggle (two-directional via the file),
  ChatterLineSafe (newlines/pipes/braces survived HygienePass), party
  pins + the composer mapping/drain-cap pins.
- Round 2 (3 rotated reviewers, six mandates): **0 P0, 6 P1** — the
  composer enqueue loop truncated scripts to ONE line (the factKeys
  loop bound); the floor paths skipped the line-safety law (DB event
  text can carry pipes/newlines past the write chain — model-authored
  share_gossip rows); the staging-condition + duel-arm pins were
  missing; plus folds (stale flush keeps generated lines → now dropped;
  duel note consumed only when dispatch proceeds; the drain scans past
  not-due heads; worker-body catch-alls; stoi out_of_range caught;
  GovernorAdmit claim softened to structural; dead floorQueueMax
  deleted; the harness mirror hardened + the composer floor honestly
  >=1 on the 2B lab stand-in with the >=2 cloud target recorded;
  LlmScreen copy "within about a minute").
- Round 3 (2 reviewers, six mandates — the final-gate attempt):
  **1 P0, 2 P1**. The P0: the mutation harness itself (no baseline
  assert; NO per-mutant restore — mutants accumulated, making every
  kill after the first unattributable; a live un-reverted mutant was
  found mid-run and restored). REBUILT: green_baseline() + per-mutant
  byte-verified restore + leftover sweep — and the properly-isolated
  run then exposed 12 SURVIVORS the accumulating harness had masked
  (all vacuous-pin classes: a missing " in source", name-only pins,
  signature-only pins, a comment-robust pin). All pins repaired to
  exact call-site slices; the battery gained the AmbientAdmissionQuiet
  behavioral rows; final run 67/67 (tmp/mutation_s10_rerun2.log; the
  honest chain is 55/67-survivors → pin repair → 67/67, zero mutants
  dropped). The P1: composer turns delivered out of order (independent
  draws reorder a reply ahead of its setup ~1/3 of the time) — now a
  monotone base+i*perTurn stagger.
- Round 4 (final gate, 2 reviewers, six mandates): **ZERO P0/P1**.
  Both independently reproduced the verification state (10-passed pin
  suite, 124 host gates, the 412-check battery recompiled from source,
  the 67/67 log with clean restore markers, no leftover mutants,
  gradle green) and mechanically swept the monotone-delay arithmetic
  (both cadences, turnCap 0..4, 500 draws — in-bounds, no underflow).
- Gates: host 124 green (19 player_surface + 29 act_tools + 29 recall +
  9 truth + 10 banter + 8 a0 + 5 json + 4 prompt_format + 10 chatter +
  the lockfile pin); Kotlin green (LlmRuntimePolicyTest 23,
  ChatterPowerMonitorTest 5, ServerRuntimeFilesLlmGateTest 11,
  SettingsUpdateWriteSetTest 4, LlmConfMergeOrderTest 5,
  LlmRoutesContractTest); all four lanes rebuilt at close, lockfiles
  fresh incl. the sqlite x86_64 lane verified manually (the standing
  protocol); n3 checkpoint BOTH composites at close (trained 5/6
  majority-fire — adjust_sentiment 0.33, the S5/S7/S8-ledgered family
  carried to S11's per-family gate; legacy 4/6, the ledgered boundary
  families; S2 all-clean both — no see-saw: no conversational prompt
  text changed). Voice gates on e2b-tuned (three artifacts: 120101
  green pre-fix, 124211 the honest pre-fix FAIL kept — composer turns
  [3,1,3] + one ring collision at 0.636, 124321 green under the honest
  floors): murmur/party/global pass 0.8-1.0 across runs, zero tool
  draws, zero delivered-pair repeats (the enqueue veto modeled), the
  composer voice panel human-read in-register and persona-consistent;
  every failing draw was uniform register-overshoot (26-29 words vs
  the 24 tolerance) — anchored, tool-free, and dropped in-tree by the
  register gate: the exact pre-P52 behavior S11's bank trains.
- Recorded conditions + deferred P2 ledger (binding later stages):
  (a) DEVICE-PENDING legs (S3/S6/S8/S9 precedent): the <=1.5%
  battery/session measurement, whisper-ack latency under full murmur
  load on-device, in-game interruption/combat-block observation, the
  power-file refresh loop live, the vendored auto-parallel behavior
  executed (the string-extraction + the S9 flag-law method stand in;
  a captured runtime log would upgrade it), the composer against a
  real cloud endpoint. (b) The plan's SS2.2 "--parallel 2 with lane
  pinning" is UNIMPLEMENTABLE on the vendored binary (auto-parallel=4
  unified-KV; string-extract verified on both binaries); the plan text
  still instructs it — reconcile at the next plan revision. (c) The
  murmur register runtime tolerance (5-24 words/120 B) is deliberately
  wider than the P52/L3c trained target (8-20): pre-P52 acceptance
  measured 0.8-1.0 with uniform overshoot-drops; S11's P52 bank + the
  frozen wording re-gate it (re-run the voice harness at whatever
  checkpoint S11 ships). (d) The composer floor in the lab harness is
  >=1 accepted turn on the 2B stand-in (>=2 is the cloud-class target;
  the 124211 artifact shows the 2B model drawing a 1-turn script); a
  real cloud-composer run is the S11/device leg. (e) Credence is not
  re-checked at delivery (a queued speaker can be marked heard by a
  nearby delivery in its 30-60s window — bounded to one extra
  retell); the global headline's floor bookkeeping records its
  template index. (f) The harness's restore verification compares
  newline-normalized text (content-identity, not byte — inert today,
  all targets LF-native + sha256-pinned); green_baseline covers the
  pytest leg only (the gradle fallback leg's kills rest on the
  independently-verified green gradle runs); no subprocess timeout (a
  hanging mutant hangs the harness; the finally sweep still restores).
  (g) turnCap==0 relies on unsigned wrap semantics (provably safe,
  the loop never runs — a future explicit guard would drop the
  reasoning burden). (h) The global layer's zone-General delivery
  re-derives the channel name via BroadcastHelper::GetLocale() while
  the core rejoins with the session DBC locale — a mismatch mints a
  ghost channel and the line silently drops (the SayToGeneral()
  idiom is the alternative if it ever bites). (i) Stimulus
  propagation (a chatter line re-entering the event store) is
  deliberately absent — echo suppression outranks it; recorded as a
  plan deviation. (j) Event barks are duel-only (the plan lists zone
  entry/kills/loot/level-up); the OnDuelCompleted hook surface
  generalizes. (k) The party device speaker is a random group bot;
  the composer speaker set is the persona list. (l) ChatterState
  ledgers (heard/perFact/lastTemplate/party maps) are process-local
  and never pruned — the accepted never-cleaned GUID-statics class; a
  world restart resets fatigue (a retired fact earns fresh tellings
  per boot; world_gossip rows expire in 7 days). (m) The murmur
  composer script's turns all bind one factKey (capped at 5 by the
  fact budget); multi-fact scripts need the eventRows plumbing to
  carry >1 — future work with the cloud leg.
**VERDICT: S10 PASSED (4 rounds, converged at zero; the silence
doctrine pinned by the 6h soak + per-layer source pins, the
power/ladder machinery two-directional and fail-safe by construction,
the whisper lane protected by the quiet-window admission gate after
the vendored-binary flag law killed the plan's --parallel config, the
wording lock byte-pinned for S11's P52 bank; 67/67 mutants under true
per-mutant isolation after the round-3 harness P0 exposed 12 vacuous
pins; four lanes + lockfiles fresh at close).**

### S11 — v2.3/v2.4 data + retrain (§5 + §5.1; rounds 0-5 landed: the scaffolding, every repo-side coupling, the G0 harness conversion, and the BANKS; the training arms and G0-G5 remain)
- Round 0 (implementation, 2026-08-31):
  **Tier max_tokens raise (the S9-coupled "own reviewed change")** —
  TUNED_E2B 120→230 (clears the P50 long bank: 150 words ≈ 225 tokens
  at ~1.5 tok/word; bank-side law caps tool-bearing cue rows at 130
  words so prose + tool lines never exceed the cap), TUNED_Q08 100→210
  + BASE_E2B 120→210 (clear the existing 110-word corpus worst case;
  NOT long-licensed — see the 225 gate below); LlmRuntimePolicyTest
  pins updated (230 ×2 sites + conf key, q08 210, base 210 + temp-1
  unique-site pins); docs/llm-runtime-submenu.md + the §2.1 tier row +
  §2.2 lane-0/1 rows + the README-INTEGRATION sample reconciled.
  **Fieldless emote fold (S6-ledger (j))** — ExtractToolCalls folds
  `<<perform_emote laugh>>` into fields=[emote=laugh]: pure form only
  (perform_emote + empty fields + single alphabetic dangling token; a
  keyed value always wins; other tools never fold); executor field
  authority unchanged (the licensed line still decides which emote
  plays). 5 battery rows.
  **Beat-cargo VARIANT SETS (rev-3b item 1)** — banklib gains
  BEAT_CARGO_VARIANTS (3 persona flavors × 8 frame kinds; flavor 0 =
  the S8 measured wording BYTE-PRESERVED, programmatically verified
  against the git-index copy) + CEREMONY_UP_PHRASES + the selection
  law `flavor = bot_guid % 3`; emit_prompt_constants.py machine-emits
  the frames + ceremony phrases into RecallCore between markers (new
  splice with a marker-less REFUSAL — the first emission once replaced
  the whole file; recovered byte-exact from the submodule mirror, the
  incident disclosed to and verified-clean by round-1 R1); the seven
  cargo builders take botGuid and select GUID-stably (one flavor per
  bot for life); 9 bridge call sites thread bot->GetGUIDLow();
  static_asserts pin frame-set uniformity (a divergent set would be an
  OOB read in the world process); the battery byte-pins ALL flavors ×
  all kinds + the flavor law (stable, rotating, one-per-bot).
  **P50/P51 long-form layer** — ONE frozen cue (LONGFORM_CUE,
  banklib-authored, machine-emitted as kLongFormCue); LongFormLicensed
  (maxNewTokens >= 225 — T1 licenses, T2/T3/native-default 200 do not)
  lives in ToolsCore beside the budget that consumes it;
  WantsStorytelling + WantsOpenConfidence (second-person-gated at the
  caller; "what do you make of" dropped as third-person-usable) feed
  exactly THREE bridge rungs: the storytelling ask (event-fact
  anchored), tier-5 Bonded open-confidence (goal-fact anchored), and
  the news-recall deep-dive — each gated on the tier token budget and
  each marking the note (see round 2).
  **The reply-budget coupling** — ApplyReplyBudget takes maxNewTokens;
  a CUE-BEARING conversational turn on a licensed tier runs to the
  splitter's own 4×255 budget; every uncued turn keeps 2×160 on every
  tier; ambient stays 1×80 everywhere (the per-turn earning is the
  round-2 fix — round 0 shipped it tier-wide and the panel killed
  that).
  **The S11 bank pipeline (the wording lock made structural)** —
  banklib gains the guard/longform/murmur bank shapes + renderers
  (guard_turn composes the frozen A10 directive via the new
  bridge_wording module; longform_turn composes beat_frame cargo +
  LONGFORM_CUE; murmur_turn composes the frozen S10 murmur wording) —
  the bank FILES carry only content, never prompt text; validate_banks
  gains the three bank branches with the conditional length bands
  (L3b 80-150 cue-bearing only, 80-130 when tooled; L3c 8-20 murmur
  only — via check_reply(band=), the global MIN/MAX untouched), arc
  4-24 turns + the longform/longform_facts flags, content-field laws
  (era/ascii/marker/control-token on entity/fact/event/listener), a
  P45 deny-shape check (a guard reply never INTRODUCES a replacement
  proper noun; the lore-title allowlist bounds legal pivots), and a
  frozen-literal scan (no authored surface quotes the cue / frame
  tokens / guard / murmur wording); compose_banks composes all three
  banks through the frozen renderers + the arc longform plumbing and
  FAILS LOUD on any non-empty bank it cannot compose (the round-2 fix
  — round 0's validator whitelist without composer branches was
  fail-silent); the merge forbidden-span set now includes every frozen
  S11 string; draft_dedup.py (§5.1c) is the explicit 13-gram gate
  against the frozen corpus; extract_bridge_wording.py compiles the
  C++ cores on the host and renders the frozen strings into
  bridge_wording.py with sentinel→placeholder conversion (the reverse
  wording-lock direction: C++ → authoring tree), --check-fresh and
  wired as a pytest leg.
  **Harness** — s8_beats_gates draws its cargo frames from banklib
  (drift impossible) with per-row flavor cycling + a flavor field in
  the artifact; a NEW LONGFORM section measures the cue against the
  current weights (3 kinds × n=3 paired cued/uncued at max_tokens 230).
  **Tests**: recall battery +5 pins (all-flavor byte-pins, flavor law,
  cue bytes, licensing boundary, triggers); act_tools +fold rows
  +budget rows; pytest +6 (guid-threaded call spans paren-balanced,
  emitted-frame block, 3-cue-site exact-call regex + mark-site count +
  rung content anchoring + reader/RecordLicense flag checks, wording
  freshness ×2 directions, reader semantics); tmp/mutation_s11.py
  under the S10 discipline.
- Round 1 (6 reviewers): **1 P0, 1 P1**. The P0 (R1): the news
  deep-dive's `FactClassOf(fact, "") == FACT_EVENT` was ALWAYS FALSE
  (the mask already selects event-class; re-classifying with a lost
  category reads PLAIN) — half the cue-bearing bank had no runtime
  trigger; fixed by deleting the dead conjunct (the mask IS the
  event-class guarantee) + re-anchoring the pin. The P1 (R1): the S9
  reply budget clamped cued tellings to 2×160 — ApplyReplyBudget now
  scales with the configured max new tokens. P2s folded:
  static_asserts, the bonded second-person gate + trigger drop (both
  sides), README + §2.1/§2.2 + conf-comment reconciliations, the
  BASE_E2B exact pin, symmetric G:-absent skips on both --check legs,
  mutant label fixes, the exact-call regex upgrade (a substring count
  survived an argument-scaling mutant).
- Round 2 (3 rotated reviewers, six mandates): **3 P1** — (1) the
  widened budget was TIER-WIDE: a plain uncued turn (or zone-chatter
  class-0) on T1 could voice 4×255 while the comments claimed
  per-turn earning → the longFormCued flag now threads Note →
  ToolLicense → NoteLongFormCued (stamp- AND flag-checked, resolved on
  the world thread as a std::async argument — by-value, so a
  superseding note can neither widen nor narrow an in-flight
  generation; the RPG ambient site passes false explicitly because
  defaults do not bind through the async function pointer — the lane
  build caught exactly that compile error) → ApplyReplyBudget's 4th
  arg; (2) the cue had ZERO model measurement → the LONGFORM harness
  section (measured: cued 32-61 words, 0/9 over 90; uncued 0/9 over
  60 — the honest pre-P50 baseline for both G5 length metrics); (3)
  the merge composer could not compose the new banks (validator-
  whitelisted, zero examples, fail-silent — the S9-unwired-leg class)
  → composer branches + arc longform plumbing + the fail-loud
  RuntimeError (verified OUTSIDE the per-file try, so it aborts the
  merge as intended). P2s folded: extractor round-trip hardening v1
  (backslash-first decode, newline/tab-safe literals, both-brace
  assert), validator content-field laws, the s8 flavor field, the
  prompt_format emitter-staging skip.
- Round 3 (3 rotated reviewers, six mandates — the gate): **ZERO
  P0/P1** across all three slots (the per-turn fix verified on all
  four legs; the pacing arithmetic re-derived — a cued telling's
  residual drip ≈ 11.5-16.5s after the generation credit; the
  composer fail-loud verified uncaught; the restart path fails
  NARROW). Post-gate P2 folds (S2/S8 precedent): the enum-compare
  cast, the boundary comment, extractor control-byte hardening
  (\r/\f/\v escaped + strict-\n split + a post-decode control-char
  assert), the composer forbidden-set extension (every frozen S11
  string is anti-echo corpus), the validator frozen-literal scan, and
  mutant-site uniquification (+the BASE_E2B cap mutant).
- Gates at close: **140 host gates green** (129 LLM-file gates: 19
  player_surface + 29 act_tools + 33 recall + 9 truth + 10 banter +
  8 a0 + 5 json + 5 prompt_format + 11 chatter; +9 sqlite-dialect +
  2 lockfile pins); Kotlin green (LlmRuntimePolicyTest 23,
  LlmModelRegistryTest 5 — including the --rerun verification);
  mutation **22/22** (tmp/mutation_s11_final.log; the honest chain
  15/16 → pin repair → 16/16 → round-2 additions → 19/20 → reader-pin
  → 20/20 → uniquify+BASE → 22/22); all four lanes rebuilt at close,
  lockfiles hash-fresh incl. the sqlite x86_64 lane verified manually
  (27 entries, zero mismatches, each lane).
- Model-measurement artifacts (C:\llm-lab\results\): n3 BOTH composites
  (trained 6/6 families majority + S2 all-clean; legacy 5/6 with
  adjust_sentiment 0.33 — the ledgered boundary family) —
  n3_*_s11-round0_20260831-1756{16,02}.json; s8 175852 (the
  FRAME-BEARING leg — all three flavors rendered in-prompt; n3 is
  composite evidence only, restated per round-3 R4) and 194021 (the
  close run: ASSOC 4/4 — one honest control-also-recalls note —
  ceremony floor 5/5, events 3/3+3/3, jaccard 0.212, and the LONGFORM
  baseline). No see-saw: the instrument's banklib prompts are
  byte-verified unchanged across the session's edits.
- Recorded conditions + deferred P2 ledger (binding the remainder):
  (a) THE STAGE IS NOT CLOSED: the P45-P52 BANKS are unauthored and
  the training arms (arm2′/arm1b′/2B) have not run — the G0-G5 gates
  remain the stage's acceptance; everything landed this session is the
  machinery those gates will exercise. (b) The prtools2/3/4+ambition
  keyed-emote harness conversion (the G0 BLOCKER) is STILL OWED and
  untouched. (c) PROVENANCE: the flavor-1/2 frames and the long-form
  cue are AGENT-authored this session (flavor 0 byte-preserves the
  research program's S8 wording; the rev-3b letter — freeze before
  BANK authoring, banks + bridge move together — is mechanically
  enforced, but no human has read the new wordings; flag at the next
  owner review). (d) The storytelling/news-recall beats carry no
  per-pair cooldown (accepted: the player's own message rate + the
  governor bound them; the mandatesContent exemption is 1/min/bot).
  (e) The S9-(m) "first line 6-8 s" device leg needs UNCUED scoping —
  a cued telling lands post-decode by design (15-21 s decode + the
  residual drip); scope it before the device run measures it. (f) n3
  is composite evidence for the frames; s8 175852 is the frame-bearing
  leg (cited accordingly above). (g) Mutation harness: the Kotlin kill
  leg has no baseline (the standing S10-ledger (f) class) and the
  restore is newline-normalized (inert — all six targets LF-verified).
  (h) The exact-call LongFormLicensed regex pins COUNT, not a per-site
  bijection (brittle to a benign hoist refactor; recorded). (i)
  LlmModelRegistry.kt's mtime overlaps the native-fix window though
  its content is verified fix-free (round-3 R1's traceability note).
  (j) SayAction's delay-removal debug log prints the zeroed value
  (pre-existing upstream shape, cosmetic). (k) banklib.fact_direct
  duplicates the s8 harness mirror (the S8-ledger (l) inspection-only
  class). (l) Device legs (S9-(m)/S10-(a) classes) all still pending
  the device run; the cued-telling battery/latency legs ride it too.
**VERDICT: S11 rounds 0-3 CONVERGED at zero P0/P1 (3 review rounds;
the round-1 P0 dead-gate and the round-2 per-turn-budget/composer/
measurement P1s all fixed with proof; 22/22 mutants; four lanes +
lockfiles fresh; the wording lock now mechanical in BOTH directions
and structural in the bank pipeline). The stage remains OPEN pending
bank authoring P45-P52 and the retrain arms + G0-G5 gates (the G0
harness blocker itself was cleared in round 4 and CONVERGED at zero
P0/P1 through rounds 4b/4c — see the round-4 series below).**

- Round 4 (the G0 blocker: keyed-emote harness conversion, 2026-08-31):
  prtools2/3/4 + ambition converted (plus one adjacent rp-web line);
  ledger (b) CLOSED and S6-ledger (j) fully discharged on both ends.
  **What changed** - (1) prtools3.parse is now a line-by-line mirror of
  the SHIPPED PlayerbotLlmToolsCore.h scanner: blocks open only at `<<`,
  quote-and-nest-aware close with the plain-find fallback, unterminated
  markers dropped, prose truncated at stray `>>`, the key scan accepts
  quoted AND unquoted values with first-key-wins, and the S6-(j) fold
  fires on exactly the C++ condition (perform_emote, no keyed fields,
  single letters-only trailing token; multi-bare-token input folds the
  scan's LAST dangling token, as the C++ loop does). The v3.0-era
  mirrored-opener and short-name-alias tolerances (C++ patch candidates
  that never landed) are REMOVED - they credited calls production drops.
  (2) The emote resolve map is now ResolveTextEmote verbatim (19-name
  whitelist, the shipped alias map, light morphology); the old harness
  maps drifted (grin->laugh, smile->laugh, angry->no) and mis-scored
  TRAINED emotes - grin/shrug/dance are whitelisted, smile resolves to
  grin, angry to glare. The scored value is the resolved one (what the
  game would play). (3) Fixed a LIVE dispatch-norm bug: run_case's
  engine self-check parsed skeletons as bare (`ln.split()[-1]` on a
  keyed skeleton yields `emote="cheer"`), so every emote case would
  have reported ENGINE-MISS; extracted to dispatch_of(turn) and
  verified against EXPECT_DISPATCH for all 13 cases. (4) prtools4's
  four bridge skeletons keyed (win cheer / lose no / duel salute /
  badnews cry); prtools4.py dry re-walked: 25 scenarios, 0
  world-misses, 0 req-mismatches. (5) ambition D_TURNS specs now carry
  full keyed skeleton insides via d_skeletons(); this also fixed two
  latent bugs - the fire check compared the whole spec token
  ("perform_emote laugh") against parsed NAMES (never matchable), and
  the expander taught adjust_sentiment with an off-contract text=
  field (the contract is reason=). (6) prtools2's v2 arm delegates to
  the mirror and its V2_INSTR teaches the keyed line; the v1 arm stays
  the frozen control (BLOCK_STRICT, no fold - that difference IS the
  A/B) but scores through the shipped resolve. (7) rp-web's greet
  skeleton keyed (adjacent, same class). **Mechanical locks**: new
  tests/test_llm_prtools_harness.py (10 pins: the fold and its guards,
  the resolve table, degenerate-block parity, dispatch_of vs
  EXPECT_DISPATCH, both prtools2 arms, prtools4 skeletons, ambition
  expansion, and a no-bare-literal source scan over all five files) -
  plus a NEW harness-to-banklib lock: prtools3.TOOLS_NOTE is pinned
  byte-equal to banklib.TOOLS_NOTE (verified equal; previously nothing
  held the harness prompt to the trained wording). Synthetic smoke
  tmp/prtools_keyed_smoke.py all-green; final.py (G1) and rp-web
  import the shared parser, so they inherit the conversion. Historical
  research harnesses (initiative/recallfix/tricks) deliberately left
  untouched - their recorded artifacts were produced with those exact
  files. Evidence: 150 pytest gates green (140 prior + 10 new), smoke
  log in tmp/, dry 0/0.
- Round 4b (panel round 1 fixes, 2026-08-31 — 0 P0 / 10 P1 across the
  6 roles, all resolved or folded here): (1) **tricks.py IS in the G1
  gate path** (final.py's calibration slice iterates tricks.TOOL_TURNS
  into live prompts, 20% of arm-selection weight) — its two bare
  skeletons keyed; tricks.py + final.py added to the no-bare source
  scan (the scan had covered only the five converted files).
  initiative/recallfix stay untouched (frozen research records, off
  every gate path — G2 hard-creative has no tool syntax at all; the
  G3 lane runs rp-web, keyed). (2) **Copy-fidelity framing fixed**: the
  "score what the game would play" comment was wrong — production plays
  the LICENSED line's value (PlayerbotLlmTools.cpp
  PlayTextEmote(...LicensedField(licensedLine,"emote"))) and never
  reads the model's copy; the harness charge on the model's resolved
  value is a FORMAT-gate copy-fidelity check (stricter than playback,
  plus alias-leniency: hello→wave passes the wave beat). Comments and
  this record now say so — a G0 emote failure is NOT "the wrong emote
  plays in game". (3) **Repair masking surfaced**: score() now prints
  harness stamp + repair pressure + units-passing-with-zero-repairs
  per model (the headline score counts post-repair attempts; G0 must
  read the first-shot numbers separately). (4) **ambition D fills was
  a near-tautology** (whole-fields-blob regex + `or len(blob) > 30`,
  cross-turn keywords, "duel" listed twice) — replaced by per-row
  regexes on the target call's OWN fill field via d_fill_ok()
  (pinned). Also fixed in D: emote rows now copy keyed ready lines
  (fire was name-only before and could never match an emote spec
  token). (5) **Baseline comparability**: every run JSON now carries
  harness=prtools3-v3.4-keyed (prtools2/3/4 + ambition); score()
  prints UNSTAMPED for the frozen pre-conversion logs. SUPERSEDED:
  all pre-conversion prtools2/3/4/ambition/final numbers (old parser
  resurrected mirrored openers + short-name aliases, scored emotes
  through a drifted resolve map, and prompted with bare skeletons) are
  NOT comparable with post-conversion runs — including the G1 "final.py
  ≥ .861" threshold, which must be re-derived on the first
  post-conversion run of the SAME baseline model before it gates
  anything. (6) **prtools2's A/B recharacterized**: V1_INSTR is the
  RETIRED S2-era production contract (A7 replaced it with the
  note-driven TOOLS_NOTE); on v2.3 the v1 arm measures self-dispatch
  suppression under a retired contract (an example-bleed signal), not
  production tool skill — comment updated; v2 (matches v2.3 training)
  is the meaningful arm. (7) **Doubled-line divergence killed**:
  production ExtractAndQueue queues EVERY licensed call (a doubled
  emote would play twice); prtools4's digest dedup (falsely commented
  as a production rule) silently credited doubles — removed, so
  check_turn's dup charge lives and prtools4 now agrees with prtools3;
  needs_repair gained the dup guard so doubles earn the repair pass
  (majority still decides). (8) **Mutation pass run** (the owed S10
  law, skipped in round 4 and now disclosed): tmp/mutation_s11_r4.py —
  16 mutants on the new pins (fold guards, alias/whitelist/suffix
  table, dispatch_of, TOOLS_NOTE, dup guard, skeletons, V2_INSTR,
  d_skeletons want, d_fill_ok length-escape, grammar separator, _SP
  NBSP, trailing-trim), 16/16 killed, per-mutant byte-verified restore
  + leftover sweep, log tmp/mutation_s11_r4.log. One designed mutant
  was swapped after proving semantically equivalent (whitelist bypass
  is shadowed by the plural-strip fallback — equivalent, unkillable,
  replaced with an alias-map bypass). (9) **Phantom citation fixed**:
  round 4 cited a "smoke log in tmp/" that was never persisted — all
  four artifacts now exist: tmp/prtools_keyed_smoke_r4.log,
  tmp/prtools4_dry_r4.log, tmp/pytest_llm_r4.log,
  tmp/mutation_s11_r4.log. (10) P2 folds: ASCII C-locale semantics
  (NBSP glues the name; Kelvin-sign case-folding mints nothing),
  cleaned trims trailing \n/space only (leading + tabs stay, like the
  C++), sorted(EMOTE_ALLOWED) in the morphology scan (kills set-order
  nondeterminism), the GBNF `"\n+"` literal-plus separator bug in
  grammar_for/cont_grammar (`("\n" "\n"* ...)` now), f6's unanchored
  `no` → `\bno\b` (prtools3 + final.py's LONG t26), prtools2 v1
  category-expectation skip + the dead `forbid ["laugh"]` entry
  recorded as accepted legacy. Precision on round 4's record:
  prtools3's TOOLS_NOTE and trigger skeletons were ALREADY keyed by
  the Aug-25 half-conversion (the round's contribution there is the
  banklib equality pin + the dispatch-norm fix, not the keying).
  Evidence: 156 pytest gates (140 + 16 pins), smoke all-green (both
  logs), dry 25/0/0, mutation 16/16.
- Round 4c (panel round 2 folds, 2026-08-31 — round 2 returned 0 P0 /
  1 P1 / ~29 P2 across the six roles): (1) **P1 closed — final.py
  (the G1 instrument) joined the stamp/archive regime**: its result
  JSON now carries harness=prtools3-v3.4-keyed, run() archives any
  unstamped logs/final/<tag>.json to <tag>-pre-keyed-archived.json
  instead of overwriting it (fail-loud if the archive exists), and
  table() prints an UNSTAMPED/not-comparable marker under pre-keyed
  rows — the .861 threshold's provenance can no longer be silently
  destroyed or mixed. (2) One real mirror hole found by TWO roles
  (R1+R3): prtools3's quoted-value CLOSE check still used unicode
  .isspace() (an inner quote + NBSP closed the value in Python but
  not in the C++) — now `in _SP`; pinned (inner-quote+NBSP case) and
  mutation-covered (17th mutant; the pass is 17/17,
  tmp/mutation_s11_r4.log). (3) prtools4.check_turn gained the
  resolves guard prtools3 has (unresolved raw values no longer pass
  beats on substring) — the same emission now scores the same in
  both sections of the G1 battery. (4) prtools.py's FIT f6 regex
  was the third unanchored `cry|no` site (prtools2 imports FIT for
  live scoring, so the frozen-research file WAS gate-path) —
  anchored `cry|\bno\b` like prtools3/final; disclosed edit to an
  otherwise-frozen file. (5) score() surfaces everywhere: prtools2
  + prtools4 score() print the harness stamp (prtools2 also prints
  the v1-arm retired-contract annotation); prtools3's first-shot /
  repair-pressure denominators now scope to the repairable tool
  units (depth rows are single-shot voice checks). (6) Comments made
  honest: prtools2's dead `arm=="v1" and False` now says fail-both;
  prtools4's walk comment states log_fact doubles are neither
  charged nor repaired and double-append to memories (mirroring
  production's double-store). (7) Mutation script hardened:
  try/finally restore (no mutant can survive an abort on disk) +
  per-restore log lines. (8) Recorded from round-2 P2s: tricks.py's
  own logs predate the keying (provenance cut point — tricks was
  edited 2026-08-31 after its recorded runs); prtools5.py is
  referenced by nothing (off every path); the "tools ≥ .93"
  per-family gate is measured by the repo tools/llm_lab battery
  (banklib-driven, independent of the bench-harness parsers), so it
  does NOT inherit the bench-harness supersession; the
  dispatch_of-duplicate-name latent and the two textual (vs
  behavioral) mutation kills stay recorded as accepted; artifacts
  should be persisted BEFORE the record citing them next round
  (round-2 P3: docs were saved before the logs). Evidence: 156
  pytest gates, smoke ALL CHECKS PASSED (tmp/prtools_keyed_smoke_
  r4.log), dry 25/0/0 (tmp/prtools4_dry_r4.log), mutation 17/17
  (tmp/mutation_s11_r4.log), battery log tmp/pytest_llm_r4.log.
- Round 4 CONVERGED (panel round 3, 2026-08-31): all six roles at
  ZERO P0/P1 (R1 byte-diffed the mirror against the compiled C++ on
  50 probes - diverge 0, "byte-faithful everywhere"; R2 confirmed
  its final.py P1 fully resolved; R3/R4/R6 re-ran every artifact
  green; R5's cross-battery parity matrix converged on every probe).
  Post-gate P2 folds applied with proof: (a) the master plan's G0
  bullet carried a stale 16/16 mutation count (round-4c refresh
  missed it) - now current; (b) the UNSTAMPED fallback string was
  missing its closing paren in three score() sites - fixed; (c) R5's
  real parity find: prtools4.check_turn charged a bare-tone extra
  adjust_sentiment that prtools3.check_case forgives (the production
  F6 mood-tag drop - the bridge silently discards a bare tone-label
  sentiment with no ledger event), splitting final.py's own tools vs
  chain/long sections on one emission - prtools4 now carries the
  same F6-mirror exception, pinned by
  test_bare_tone_extra_sentiment_parity and mutation-covered (18th
  mutant; the pass is 18/18). Recorded as accepted (P2 ledger for
  the G0/G3 prep): prtools2's fit scorer stays dup-blind (both A/B
  arms lenient symmetrically; its role is the v2-contract arm);
  final.py overwrites a STAMPED live file on re-run without
  archiving (only unstamped pre-keyed baselines are protected - the
  version-mismatch archive is G0-prep work); battery_report.py
  reads by exact filename and ignores the harness stamp (flag when
  the .861 re-derivation runs); rp-web routes G3 turns through
  initiative.digest, which still name-dedups (the non-production
  rule removed from prtools4) - sim-behavior only, no scoring rides
  on it; two of the 18 mutation kills remain textual rather than
  behavioral (the grammar spelling + TOOLS_NOTE source-scan kills).
  FINAL STATE: 157 pytest gates (140 + 17 harness pins), smoke
  all-green, dry 25/0/0, mutation 18/18, both wording locks fresh.
  The G0 harness blocker stands CLEARED; the G0 gate itself remains
  unrun (banks + checkpoints are the next session's work).
- Round 5 (BANK AUTHORING P45-P54, 2026-09-01, three owner-cut
  segments — see the provenance note below; the round-5 execution
  briefs are LLM-BANK-AUTHORING-BRIEF.md + -R2.md and the two
  HANDOFF-R3/R4 progress files at the repo root):
  **THE OWNER DIRECTIVE (2026-09-01, carried through all three
  segments, binding amendment to §5.1's API-drafter protocol)**:
  drafters are AGENTS THE AUTHORING AGENT SPAWNS (general-purpose
  subagents, foreground, one parallel message per wave) — NEVER
  llama-server, local GGUF, or any external LLM API. Multi-source
  (§5.1a) = >=2 spawned drafter agents per bank with disjoint opener
  menus and fresh prose; the §5.1d per-provider reject log becomes a
  per-DRAFTER-AGENT reject log tracked by part-file origin; ToS
  (§5.1b) is sidestepped entirely (house agents, no provider terms).
  Every drafter read the shared law sheet
  (finetune-data/drafts/SPEC_common.md) + its card bibles + a
  structure seed, wrote ONE part file under drafts/, and returned
  counts only. Precedent: B13 (owner mid-campaign engine switch).
  **BANKS LANDED (all validate CLEAN, all 13-gram gates 0 vs the
  frozen corpus):**
  | bank | rows | notes |
  |---|---|---|
  | P45_entity_hedge | 492 (396 guard + 96 free) | earlier segment; held-out nonces never trained (4 rows merge-dropped); P21(h) inside |
  | P46_era_deflection | 300 free | earlier segment |
  | P47_card_grounded_lore | 500 poi | earlier segment; spans sample the shipped 289-1102-char spread |
  | P48_associative_recall | 300 memory | earlier segment |
  | P50_longform | 600 longform | earlier segment |
  | P51_depth_arcs | 300 arcs / 3,886 turns / 12 cards | this segment (waves A-D below) |
  | P52_ambient_barks | 192 murmur / 8 cards | this segment; L3c 8-20w |
  | P53_persona_sessions | 60 sessions / 825 turns / 10 cards | this segment; persona-locked 12-16 turns |
  | P54_topups | 590 rows / 4 companion cards | this segment; q08 + protocol + nickname + A16/A17 |
  **P21(h) hedge-then-guess rework DONE (the only rework of landed
  v2.2 content):** THREE rows, all in P21B (ayamiss ~line 339,
  sartura ~line 425 found by widened sweep, fankriss ~line 899) —
  each reworked in place to honest-hedge + deny + pivot-to-source;
  P21J:815 checked and is a correct era-trap denial (not a target).
  No other hedge shapes exist in P21* (sweep terms: hazard / don't
  know the name / sounds like one of those / can't say i know /
  never heard of).
  **P49 = REPORT BLOCKER, ZERO ROWS authored.** No banklib bank shape
  can express the enum-classifier sub-call (P49 is a separate
  grammar-locked generation path, not a reply-bearing row); banklib /
  validate_banks / compose_banks are shared tooling and off-limits to
  authoring agents; and the §5 table's "already queued (Qwen intent
  gap)" claim is FALSE — P49 rows exist nowhere (verified: no
  claim=/verdict= enum rows in any bank). Decision owed to the owner:
  either a coordinated banks+bridge change adding a classifier row
  shape to banklib (with re-emission), or strike P49 from the §5
  manifest. Not forced.
  **P53/P54 numbering decisions (recorded per R2 §2):** the §5
  table's un-numbered "persona-locked sessions" row became
  **P53_persona_sessions** (10 cards x 6 sessions, one persona
  start-to-finish — the .778->.5 persona-dip fix); the targeted
  top-ups bundle became **P54_topups** (4 companion cards:
  ~120 protocol rows of adjust_sentiment q08-gap beats + cross-family
  token-mass, ~30 nickname-adoption free rows, ~20 A16
  meetup-initiation events rows, ~20 A17 dusk-appointment memory
  5-tuples).
  **P51 construction (the round's core):** waves A-D, 24 spawned
  drafter agents. Wave A: 6 cards x 18 arcs (betrayal->restitution,
  rescue, long-lost-friend, mourning). Wave B: rumor investigation +
  bar-story contest. Wave C: the 4 COMPANION cards, camp/travel arcs
  with B10 ACT tools (duel_challenge/give_item/follow/party_invite/
  move_to/loot_roll) mid-arc. Wave D (the plan's ~300-arc target):
  +7 arcs per card on all 12 prefixes, each drafter script-banned
  from every opener its landed part used and 6-gram-checked against
  all 12 landed parts. Per arc: 12-14 turns, >=3 planted callback
  tokens reused at turns 8+, 2-3 base tool turns mid-arc (+1-2 ACT on
  companions — see deviations), exactly one 80-150w tool-free cued
  longform turn with a third-person longform_facts entry, guid
  cycling 0/1/2. Merge: tmp/merge_parts.py (24 parts in numeric
  order; two round-5 tooling fixes were needed for session banks —
  session dicts passed through un-tupled, string tool fields split to
  lists — tmp/ scripts only, no shared tooling touched).
  **P53 construction:** 5 agents x 2 cards x 6 sessions; sessions are
  exempt from the opener/L5 passes by design but DO count toward L4
  and the L11 register floors — the floors PASS package-wide
  (messy ~99% / abbrev ~36% / emote ~18% / jargon ~7-8% / confused
  ~8% over 825 player lines; floors 40/15/10/5/5) after a spawned
  edit agent normalized 149 player emote lines to the strict
  RP_EMOTE whitelist forms.
  **P54 + the protocol-share floor:** the first 190-row version left
  the corpus protocol share BELOW the standing floor (measured 13.53%
  vs the 14.83% baseline — the rule "top up to at or above the
  previous proportion" is binding), so +400 protocol rows were
  drafted (2 more agents, 200 each: 50/50 adjust_sentiment +/-
  with substantive act-tied reasons, log_fact, share_gossip, and
  every ACT form, per card) with a snapshot-based delta gate
  (drafts/P54_presnapshot.py = the frozen side). Final measured
  share **16.54%** (floor met) and long-row share **15.13%** (inside
  the 10-20% budget; 150-word hard cap holds corpus-wide).
  **Phrase ledger (B3 reworks, in place):** baseline 47 actionable
  idioms -> 96 after authoring -> 91 after rework. The named
  LEDGER_AVOID stock tics (first light, hold still, take your time,
  sit and let, rinse the cut, hold steady, twice over, next pull)
  were briefed into every drafter and have ZERO new occurrences in
  editable rows (one 'twice over' session occurrence found and
  reworded; 'twice over' x12 at string level sits in authored arc
  docs, which B3 skips). The growth 47->91 is proper nouns (booty
  bay, sentinel hill...) + a threshold artifact: sessions added 60
  editable docs, pushing content phrases (razor hill, honest work,
  grog row) over the editable-df>=6 line — six filler shapes that
  DID pick up editable docs ('which is the only', 'honest work',
  'whole trick', 'strong word', 'third step', 'next week') were
  reworked in the sessions (17 edits).
  **GATES:** per bank validate_banks CLEAN; 13-gram vs frozen
  corpus 0 for every bank at its pre-compose gate; frozen-6g
  0 after fix passes (P51: 53 rows reworded by a spawned edit agent;
  P52: 2; P54: 9 + 15 delta). panel batch: R6's live dup pairs
  (11, all hidden behind session rows the merge dedup cannot see)
  and the mulgore opener violation fixed by content rewords
  (tmp/panel_fixes_p53.py, panel_fixes_p53b.py, panel_fixes_p54.py,
  panel_fixes_p51.py); final reconstructed-frozen gate
  (tmp/s11_final_gate.log, frozen side = P01-P50 bank files because
  compose had already run): 13g 0 everywhere; residual 6g (P51 13
  replies, P54 1 row) all inside the compose dup-vs drop sets — zero
  live.
  **COMPOSE (disclosed deviation): compose_banks ran THREE times** —
  once at the plan's end-gate, a second time because the P54 protocol
  top-up was mandated by the share floor, a third after the panel fix
  batch. The R3 §3 "exactly once" law exists to keep gate_new_banks'
  frozen side pure; each recompose was preceded by in-file validation
  and followed by the reconstructed-side gate above (13g 0), and the
  third compose is the landing state. Final merge: **16,953 examples**
  (train 16,144 / val 809), drops 280 (236 pre-existing old-bank dup
  classes + 44 S11: P51 15 arcs, P52 0, P53 4 sessions forbidden-span,
  P54 13) — every drop has reason + span in reports/P5X_report.json;
  opener max 1.5% global.
  **BATTERY + LOCKS:** 157 pytest gates green after the final compose;
  emit_prompt_constants --check and extract_bridge_wording --check
  fresh; prtools harness 17 passed — run BEFORE the work (pre-flight)
  and after every edit pass and compose.
  **6-REVIEWER PANEL (spawned agents, ONE message, R2 §6 roles):**
  R1 data-methodology/budget, R2 lore+era, R3 validator+hygiene, R4
  wording-lock+repo coupling, R5 evidence/process, R6 adversarial
  dedup+bleed. Verdicts: R1-R5 PASS, R6 FAIL -> **ZERO P0**; the P1s
  all fixed with proof this round: (1) R6: 11 live cross-file 6-gram
  pairs + 3 live in-session pairs + a per-card opener violation, all
  riding the session rows the merge dedup never registers — fixed by
  content rewords (shared tooling untouched); (2) R3+R5: the P54 edit
  pass had been SILENTLY REVERTED by the top-up re-merge (the bank
  rebuilt from pre-edit parts) — re-applied and byte-verified present;
  (3) R1: the evidence generator miscounted P53 (sessions=1, rows=61)
  — fixed, regenerated; the owed per-drafter reject log written
  (reports/S11_round5_rejects.json); (4) R1: 2 arcs ended ON a tool
  turn — trailing tool lists stripped. R2/R4 P2 folds applied: era
  nits ("photographed"->"measured", "postcard"->"card",
  "telegraphs"->"signals", coffee->tea x4) and one cue-tail echo
  ("tell it whole") reworded. R5's reject table (cited in the
  rejects artifact): P51 5.0% arcs, P52 0%, P53 6.7% sessions,
  P54 2.2% rows.
  **Evidence artifacts (all persisted BEFORE this record):**
  reports/S11_round5_baseline.json (pre-authoring: locks green,
  13,673-example corpus, protocol 14.83%, long 0.65%, P45 nonce pool
  304/76, P21(h) set), reports/S11_round5_final.json (corpus
  16,953 / protocol 16.54% / long 15.13% / per-bank words +
  question rates: guard 50.8%, others 18-34%, P51 arcs 7.7%, P52
  murmurs 2.1%; nonce statement: zero single-word held-out names in
  any S11 bank), reports/S11_round5_rejects.json (per-drafter
  table), reports/merge_report.json + P45-P54_report.json (drop
  reasons), reports/phrase_ledger.json, tmp/p51_edit1.py +
  p51_frozen6g_fixlist.txt + p51_gate1.log, p52_gate1.log,
  p53_edit1.py, p53_ledger_edit.py, p54_edit1.py + p54_gate1.log,
  panel_fixes_p5*.py, s11_final_gate.py + s11_final_gate.log.
  **Provenance note (two off-peak interruptions):** the round spanned
  three owner-cut sessions. Segment 1 landed P45-P48/P50 + P21B +
  skeletons + baseline. Segment 2 dispatched wave A: 3 of 6 agents
  landed, 2 died at spawn (off-peak ticket expiry), 1 died after
  reading with nothing on disk. Segment 3 (this record) re-dispatched
  the 3 missing parts (one died AGAIN before writing — the
  write-in-first-3-tool-calls survival law was added to every later
  dispatch — then landed on retry), and lost one wave-D agent to a
  provider rate limit (retried, landed). Per R4 §5: retry only the
  missing parts; verify every wave by FILE EXISTENCE + ast.parse +
  arc counts, never by summary.
  **Provenance flag (S11-ledger (c), carried):** ALL round-5 rows are
  machine-authored by spawned agents and HUMAN-UNREAD. The bibles,
  the validator, the ledger, and the dedup gates bound them
  mechanically, but no human has read the prose. FLAG FOR OWNER
  SPOT-REVIEW: ~20 sampled rows across P51 arcs (one longform turn),
  P52 murmurs, P53 sessions (one full session), P54 nickname rows —
  plus the wave-D arc canon decisions (e.g. Norbin Cogspanner,
  Gatecrasher/Coalkeeper/Duskbell nicknames) which are suite-canon
  invented by the drafters.
  **Deviations recorded (owner may overturn):** (a) compose ran three
  times (above); (b) companion arcs carry 3-5 tool turns total
  (base 2-3 + B10 ACT 1-2) vs the §5.1 "1-3 tool turns" line, bank
  mean 2.68 base + ACT — the ACT lines ARE the dilution compensation
  the same plan mandates; (c) P51 landed 300 arcs (plan ~300) after
  wave D, with 15 lost to compose dup-vs drops -> 285 corpus arcs;
  (d) P53 landed 60 sessions, 4 dropped forbidden-span -> 56 corpus.
  **VERDICT: S11 round 5 CONVERGED at zero P0/P1 (one panel round;
  the R6 opener/dedup P1s, the R3+R5 edit-revert P1, and the R1
  evidence/accounting P1s all fixed with proof). THE STAGE REMAINS
  OPEN for the retrain/gates agent: run arm2' + arm1b' + the open 2B
  arm from the composed corpus (16,953 examples, train 16,144 /
  val 809), then G0-G5 (re-derive the G1 threshold on the first
  post-conversion baseline run), the P49 blocker decision, and the
  owner spot-review of the machine-authored rows. NOTHING IS
  COMMITTED.**
