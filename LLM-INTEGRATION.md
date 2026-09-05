# LLM INTEGRATION — Master Plan (rev 3, 2026-08-31; amended 2026-09-02..03 by plan v4: prompt packs, RP dials, 12288 ctx bump, 6-flavor cargo, collapsed power state, API-tier retune)

**Rev 3 (2026-08-31):** §5 expanded with the long-form + conversation-depth
generation plan (banks P50/P51, §5.1 API-drafter protocol, length-conditional
G5 sections, S9 token-cap coupling) from the 2026-08-31 four-agent literature
review; §5.1 extended the same day (rev 3b) with the anti-stiffness ledger
items — beat-cargo/ceremony variant rotation and the question-ending-rate
counter-metric. Pre-rev-3 text stands as written elsewhere. Rev-3 renumber:
E6 world chatter enters as §4.6b and stage **S10** (executes after S9); the
v2.3/v2.4 retrain stage is renumbered **S11** — references to "S10" in the
pre-rev-3 stage records and ledger items mean the RETRAIN (now S11) and
stand as written. P52 ambient-bark bank added to §5 (the murmur register
for the S10 device-fallback voice).

**Scope:** wire the validated bot-brain stack (tuned models + bridge-dispatch
protocol + memory/relationship systems) into the production app end-to-end,
fix every gap found in the 2026-08-29 integration audit, and set up the
model-tier, evaluation, and retraining loops that keep it excellent.

**Inputs of record:**
- Integration audit (2026-08-29, this repo) — the shipped-path gap list.
- `G:\NPU LLM\docs\TIMELINE.md` — the merged research log (Phases I–XII, 30 laws).
- `G:\NPU LLM\docs\fix-plan.md` — authorized production fixes A1–A8, training fixes F1–F7.
- `G:\NPU LLM\docs\continuation-plan-llm-submenu-v2.md` — embedded NPU-hybrid runtime, review-converged (13 rounds, 0 issues); Phase 3 device validation pending, user-gated.
- **Fresh capability tests run today** (`tools/llm_lab/`, results in `C:\llm-lab\results\`) — every claim in §2 was re-measured, not remembered.

**Prime directive from the research program (measured, repeatedly):**
deterministic game AI in front, the LLM as voice, the game as truth.
The bridge owns decisions; the model owns prose. Everything below either
lands that architecture on the shipped code path or extends it.

---

## 1. Current state (verified, not remembered)

### 1.1 What the shipped app actually does today

Settings → `LlmRuntimePolicy.confBlock` (appended to `aiplayerbot.conf`,
last-wins) → native `ChatReplyAction::ChatReplyDo`
(`native/cmangos/src/modules/PlayerBots/playerbot/strategy/actions/SayAction.cpp`)
→ `PlayerbotLLMInterface::Generate` → `GenerateHttp` → embedded llama-server
(`:llm` process, Hexagon-NPU hybrid or pinned CPU) → pinned base model.

The audit's structural findings — all still open in the working tree:

| # | Finding | Where |
|---|---------|-------|
| G-1 | **Tool calling unreachable in shipped config.** `<<tool …>>` extraction + `ToolInstructions()` only run under `LLMBackend=1` (in-process, debug-only). App always emits `LLMBackend=0`. | `SayAction.cpp:744-749`, `PlayerbotLLMInterface.cpp:378-426`, `LlmRuntimePolicy.kt:202` |
| G-2 | **Entire memory/relationship/persona/journal stack gated the same way** (`SayAction.cpp:604-688`: journal, persona fallback, `AppendTurn`, `BuildPromptContext`, `AddRelationshipPoints`). HTTP path keeps only the legacy rolling string that never records bot replies. | `SayAction.cpp:602,859` |
| G-3 | Model integrity verification is a no-op (`sha256=""`; `modelIfVerified` always null). | `LlmModelCoordinator.kt:72` |
| G-4 | Sampling params wrong for the pinned Gemma (temp 0.8, no top_k; llama-server default `repeat_penalty 1.1` applies, documented to degrade Gemma). | `LlmRuntimePolicy.kt:204` |
| G-5 | Response parsed by regex over the raw HTTP body, not JSON. | `PlayerbotLLMInterface.cpp` (`ParseResponse`) |
| G-6 | Hard-trigger gate asymmetry: the llama-only gate means the HTTP path answers trade/general/bystander chatter. | `SayAction.cpp:581` |
| G-7 | No model selection: one fixed HF download, no sideload, no tuned models. | `LlmModelCoordinator.kt` |
| G-8 | No tier system for off-device API models (external mode exists but emits identical budgets/protocol). | `LlmRuntimePolicy.kt:171-184` |
| G-9 | `LlmRuntime.endpoint()` dead code; phantom fallback-model comment; stale cmangos mirror vs `native/patches` overlay. | misc |

### 1.2 What the research program built and validated (G:\NPU LLM)

- **Tuned models on disk** (`G:\NPU LLM\models\`):
  `gemma4-E2B-TUNED-q4_0/q4_k_m/q8_0/f16` (arm2, epoch-3, **best overall —
  only bot2bot-capable model, 24/24 distinct**) and
  `qwen35-08b-CLEAN-tuned-*` (arm1b epoch-3, device-efficiency pick;
  never use the quarantined PILOT run).
- **Trained prompt contract** (`G:\NPU LLM\scripts\finetune\banklib.py`):
  system = identity → TOOLS_NOTE (per-card variant) → bible + NO_NARRATE →
  Backstory → Relationship tier (Wary/Civil/Warm/Trusted/Bonded 1–5) →
  absence → Facts; user = `[say]/[EVENT]/[RESULT]` → `[Memories]` (last ≤6) →
  `[State]` → single `[BRIDGE AI]` note (skeleton lines + fill hints below);
  assistant = spoken words + keyed `<<tool field="value">>` lines. 13,673
  examples; loss on responses only.
- **Bridge-dispatch protocol validated to 100%** (prtools3: E2B 17/17 twice;
  prtools4 chains 25/25, 92% confirmation; scale-portable to 1B at 88%).
- **30 measured laws** (see-saw, one-note, grammar-reroute, initiation-vs-
  completion, secrets 42%→0%, pinned-memory promotion, lore-density 140w,
  procedures-not-dispositions, cache law static-first/volatile-last, ~96%/turn
  → ~80%/6-turn chain envelope…).
- **NPU live on RP6** (rp6-npu-unlock, no root): HTP = prefill accelerator
  (~430-540 t/s, core-invariant), CPU/KleidiAI = decode; hybrid runtime
  embedded into llm-bench + pocketrealm with review converged; Phase 3
  device validation user-gated.

### 1.3 Today's capability tests (2026-08-29, RTX 5060 Ti, llama.cpp b10520 CUDA, exact trained prompts via `banklib`)

Harness: `tools/llm_lab/sanity_battery.py` + `arm_d_entity_guard.py`
(sections S1 tools / S2 restraint / S3 hallucination+era+lore / S4 memory /
S5 persona / S7 multiturn / S6 A-B arms; auto-scores are floors, human-read
recorded where it changed the verdict).

| capability | e2b-tuned q4_0 | q08-tuned q4_0 | q4b-base Q4_0 | e2b-base UD-Q4_K_XL (app pin) |
|---|---|---|---|---|
| produces any chat content (`--jinja`) | yes | yes | yes | **NO — 51/51 empty** (see §1.4) |
| S1 note-driven tool fire / fill / voice | **6/6 / 6/6 / 6/6** | 5/6 / 4/6 / 6/6 | 6/6 / 6/6 / 6/6 | n/a |
| S2 restraint (no spurious tools) | **7/7** | **7/7** | 5/7 | n/a |
| S3 hedge on invented entities (baseline) | 0/6 | 0/6 | 0/6 | n/a |
| S3 + hedge-line in system (arm B) | 0/6, voice degraded ("barrel roll") | — | — | — |
| S3 + `[RESULT]` denial card (arm C) | 1/6 | — | — | — |
| **S3 + entity-guard directive (arm D, human-read)** | **~4/6 clean denials** | ~2-3/6 | ~3-4/6 but emits spurious tools | n/a |
| S3 era traps (auto / human-read) | 1/5 / **~3-4 affirmed** | 1/5 | 3/5 | n/a |
| S3 grounded vanilla lore (human-read) | **~0-1/4** ("King Llane Blackhand", Barrens east of Goldshire) | low | low | n/a |
| S4 memory: direct-cue / associative | **2/2 / 0/2** | 3/4 mixed | 4/4 / partial | n/a |
| S5 persona (narration/AI-speak/md/ascii/len) | clean 6/6 all axes | clean | ascii 3/6 (emoji) | n/a |
| S7 multiturn name + planted callback | both pass | both pass | both pass | n/a |
| median gen speed (desktop CUDA) | 169 t/s | 374 t/s | 116 t/s | — |

**Verdicts that drive this plan:**
1. The trained contract works exactly as designed **when the bridge sends
   `[BRIDGE AI]` notes** — G-1/A1 is confirmed as the single biggest lever.
2. **Universal confabulation on invented entities (0/6 hedge everywhere).**
   The corpus has only 57/13,673 assistant hedges (0.42%) and essentially no
   entity-grounded "never heard of him" rows; 151 era-trap user turns exist
   but deflection targets are thin. Prompt-side fixes (arm B) fail; the
   **bridge-side entity guard (arm D) works** and a v2.3 hedge bank must
   back it in weights.
3. **Vanilla lore in the weights is unreliable at every size** — the lore
   brain must be the DB (retrieval loop), matching the westfall law
   ("keyword lists are a retrieval index, not knowledge").
4. Memory USE (associative recall) remains the gap on every model — recall
   beats, not reformatting (recallfix law).
5. Era leakage persists through prompts (logit-bias + guard + data needed).

### 1.4 NEW production bug found today: the pinned base model speaks nothing

`gemma-4-E2B-it-qat-UD-Q4_K_XL` + llama-server `--jinja` +
`/v1/chat/completions` + `max_tokens ≤ 200` ⇒ **empty `content`**; the
gemma-4 "it" template routes a "Thinking Process:" preamble into
`reasoning_content`, burns the whole budget (`finish_reason: "length"`), and
`chat_template_kwargs {"disable_thinking": true}` does not change it
(verified on b10520; same mechanism as the 26B finding). The tuned GGUFs
(unsloth export template + response-only training) do not exhibit this.
**Consequences:** the app's current embedded-LLM default cannot produce bot
chat at all until the model pin changes or the template is overridden. This
promotes the model-registry work (§4) from important to **P0**.

---

## 2. Target architecture

Three layers, unchanged in spirit from the record, now landed on the shipped
HTTP path:

```
┌─ Layer 0: GAME OWNS TRUTH (cmangos + playerbots) ─ combat, movement, duels,
│   trades, quests, loot, world_gossip rows, LLM memory tables (MySQL/SQLite)
├─ Layer 1: BRIDGE OWNS DECISIONS (C++ in world server, no LLM) ─
│   trigger engine (regex classes + game events + ledger) → beat selection →
│   [BRIDGE AI] note construction → entity guard → lore retrieval loop →
│   sampling profile per model tier → post-filters (leak/era/markdown/
│   dedupe-reroll) → tool licensing (whitelist ×2: ban-grammar + scanner) →
│   world-thread execution of queued tools
└─ Layer 2: MODEL OWNS VOICE (one model at a time) ─ in-register prose,
    skeleton copy + short fills, keyed tool lines. Never dispatches, never
    decides direction, never sees ungated secrets.
```

### 2.1 Model tiers and their knobs

One model resident at a time on device (12 GB with client+server live);
off-device API is a first-class tier with its own budget set. The conf and
runtime derive everything from the tier + model descriptor.

| knob | T1 on-device tuned E2B (default) | T2 on-device tuned 0.8B (efficiency / try-first) | T3 on-device base (fallback) | T4 off-device API (OpenAI-compatible) |
|---|---|---|---|---|
| model | **gemma4-E2B-TUNED-q4_0 only** (3.36 GB; q8_0 = 4.97 GB exceeds the NPU 4 GiB VA ceiling AND the game-open RAM budget on every path — not an upgrade path; q8_0_htp quarantined by the loader bug regardless) | qwen35-08b-CLEAN-tuned **q4_0 default** (q8_0 only if the battery passes at the exact tier config — armC: 0.8B unraveled at 6k ctx with grammar leaks) | gemma-4-E2B-it **with non-thinking template file** (§4.4) | any; recommended: Qwen3.5-4B+ / gemma-4-9B+ class |
| runtime profile (game-open) | NPU-hybrid q4_0, 1×mid voice (2.99 GB anon; load-gate: refuse below peak+0.7 GB ⇒ E2B needs ≥3.7 GB MemAvailable — violation is reboot-class) — **provisional until hybrid coexistence trial passes; else CPU-mmap kai 1.41 GB reclaimable** | hybrid 1×A510 or CPU no-repack | CPU kai no-repack | n/a |
| runtime profile (game-closed) | CPU repack + `--load-mode none`, full mids | same | same | n/a |
| sampling | t0.7 / p0.8 / k20 / rep1.0 / presence1.0 (+min_p 0.05 allowed) | t0.5 / p0.8 / k20 / rep1.0 | t1.0 / p0.95 / k64 / rep1.0 (maker) | per-provider ≈ t0.7 / p0.9; rep1.0 |
| max_tokens (whisper / ambient) | 230 / 60 (S11 §5.1 raise: 230 clears the P50 long bank — 150 words ≈ 225 tokens; tool-bearing cue rows cap at 130 words bank-side) | 210 / 60 (clears the 110-word corpus worst case; the 0.8B tier does NOT license the 150-word shapes — the bridge gates the cue on max tokens ≥ 225) | 210 / 60 (same corpus-clearing rationale as T2; not long-licensed) | 600 (the API tier's headroom; history depth keys on providerSafe && ctx ≥ 65536) |
| LLMContextLength (chars) | 12288 | 6144 | 12288 | 131072 |
| server ctx `-c` / KV | 12288, KV f16 (KV quant is a phantom lever on E2B: weights ≈98% of footprint; hybrid runs f16 KV regardless; Phase 1 plan-v4 bump from 8192 — headroom for prompt-pack seasoning + reply caps at 2 concurrent slots) | 6144 | 12288 | n/a |
| generation timeout (queue-inclusive) | 60 s | 45 s | 60 s | 60 s |
| `LLMMaxSimultaniousGenerations` | **2** (was 100 — see §2.2 lanes) | 2 | 2 | 4 |
| duty governor (post-A0 hoist) | recomputed from capacity: NPU-E2B ≈ 10-12/60 s global; CPU-E2B ≈ 8 | ≈ 20/60 s | ≈ 8 | 16/bot, 48 global |
| ambient/banter rate | subcritical: (listeners−1)×chance<1; party chance ≤0.20 | ≤0.12 | ≤0.20 | ≤0.30 |
| tool licensing | full protocol incl. ACT tools | ACT subset (emote/log_fact/sentiment; no duel/move) | full | full protocol, **scanner-only licensing** (ban-grammar impossible on external endpoints) |
| memory depth | facts cap 12, [Memories] tail 6 | facts cap 8, tail 4 | facts cap 12, tail 6 | facts cap 48, tail 16 (the 128k-ctx API tier; 32-turn rolling history vs 8 on-device) |
| bot2bot chat | enabled (trained) | disabled | disabled | enabled, chain-depth ≤2 |
| hard trigger gate | whisper or name-mention (all tiers — fixes G-6) | same | same | same |
| lore answering | retrieval loop only (never from weights) | same | same | retrieval loop preferred; direct answers only if the T4 validation battery (M4) passes |

Knob plumbing: tier descriptor table in Kotlin (`LlmModelRegistry`) →
**sampling + max_tokens interpolate into the `LLMApiJson` template** (the
only path the HTTP client actually consumes — conf keys like
`LLMMaxNewTokens`/`LLMTopK` are read by PlayerbotAIConfig but consumed only
by the in-process backend); governor/context/bot2bot keys go as conf.
Native keys that do not exist yet (memory-depth caps, ambient-chance,
tool-subset licensing) are added in Workstream A with the real key names.
Tier changes take effect at **world-process restart** (PlayerbotAIConfig
reads LLM* keys at startup only) — the LlmScreen must say so. The protocol
(marker grammar + notes) stays identical on every tier — that is what the
batteries validated; do not fork it (T4's "richer enums" was struck for
exactly this reason).

### 2.2 Priority lanes + latency/RAM budgets (replaces the count-only governor)

**Lanes (bridge-side admission, uses measured service time E[t] = Δtokens/PP
+ tokens/TG from rolling server timings — not counts):**
- **Lane 0 interactive** (whispers, direct say, tool-chain continuations):
  deadline 8 s, max_tokens = the tier's cap (230 on the long-licensed T1
  after the S11 §5.1 raise — a cued telling rides this lane; short tiers
  stay 210 or lower), queue cap 2 → busy-variant beyond.
- **Lane 1 party/nearby**: deadline 15 s, max_tokens = the tier's cap.
- **Lane 2 bot2bot**: admit at rolling utilization <50%, depth ≤2.
- **Lane 3 ambient**: admit at utilization <60% AND lane 0 empty,
  max_tokens 60, silent drop.
- Server: lane-pinned `--parallel 2` (slot 0 = interactive conversations
  only, slot 1 = everything else; 2× KV q8 ≈ noise on E2B) so ambient
  rotation can never evict the player's cached prefix.

**Latency budget (per whisper reply; player-visible = TTFT + full decode —
the native client is blocking, no streaming):**

| path | PP / TG t/s | cold TTFT (1.8k sys) | warm Δ150 tok | +decode 55 tok | reply P50 warm | P95 (2 calls) |
|---|---|---|---|---|---|---|
| T1 NPU-hybrid q4_0 | 450 / 11.4 | 4.0 s | 0.33 s | +4.8 s | ~5.2 s | ~10 s |
| T1 CPU kai q4_0 (mids) | 66 / 15.5 | 27 s | 2.3 s | +3.5 s | ~5.8 s | ~12 s |
| T2 0.8B hybrid | 432 / 11.3 | 4.2 s | 0.35 s | +4.8 s | ~2-5 s | ~5-8 s |

M6 gates on these P50/P95 percentiles under the **CPU-only fallback**
(NPU is upside), measured with a prefix-cache rotation simulation
(realistic 24-bot duty cycle) — not on a bare TTFT number.

**RAM budget (12 GB device, game-open):** OS+zram ~2.5-3.0 · `:world`
in-session 1.1→2.0 GB · client+wine ~1.5-2.5 · `:llm` hybrid 2.99 GB
private-anon (load-gate floor 3.7 GB MemAvailable) or CPU-mmap 1.41 GB
reclaimable (proven sub-2 GB survival, −28% TG). Conclusion the budget
forces: **T1 game-open = q4_0 hybrid (if the coexistence trial passes) or
q4_0 CPU-mmap; T2 is the proven game-open fallback.**

**NPU operational laws (binding on the registry):** refuse load below
peak+0.7 GB (violation = measured reboot-class); DSP VA slots are never
reclaimed on munmap → **model swap = full child restart** (one-shot
process law); an LMK SIGKILL of the hybrid child leaks the DSP session and
wedges subsequent NPU loads until reboot — the crash-block flow must treat
this case explicitly; E2B-hybrid live coexistence with the client is
pending trial #4 — until it passes, T2-CPU or T1-CPU-mmap is the shipped
default.

---

## 3. Workstream A — production C++ (close G-1/G-2/G-5/G-6 + land A1–A8 + new guards)

Order below is implementation order; A-items from fix-plan keep their IDs.
Every item lists files, change, and its acceptance gate. "The battery" =
`tools/llm_lab` (§6) run on desktop CUDA against the pinned GGUF, n=3
majority per case for gates (single draws are dishonest at these sizes).

### 3.0 Build mechanics (binding — read before touching any A-item)

The `native/cmangos` tree is **regenerated on every build**: `tools/
build_o09_realm_runtime.py` wipes `native/cmangos/src/modules/PlayerBots`
from the pristine submodule, hard-fails dirty submodules, applies SayAction/
PlayerbotAI/PlayerbotLLMInterface/PlayerbotAIConfig/RpgSubActions/DebugAction
changes as `replace_anchor` text patches, and copies whole files from
`native/patches/playerbots/` (manifest in the driver). Therefore:
- Edits to SayAction.cpp / PlayerbotLLMInterface.cpp / PlayerbotAI.cpp /
  PlayerbotAIConfig.cpp land as **driver-anchor patches**; edits to the
  Llm* files land as **overlay files**; the checked-in mirror must never be
  edited directly (and is stale until refreshed).
- Every A-item's acceptance gate includes: mirror refresh +
  `patches_content_digests` lockfile update.
- **See-saw discipline (law 12, three confirmations):** ONE prompt-text
  change per battery checkpoint, in the order A7 → A5 → A1 notes → A13 →
  A8, each with an n=3 battery run scored **per tool family** (the original
  see-saw signal was emotes 18→0 — invisible in aggregates) before the next
  lands. Re-run the ladder after M5's v2.3 weights (instruction edits must
  be re-tested per model). Prompt-snapshot + revert-and-bisect on any
  regression.

### A0 — Unify the backends: memory/tools/persona on the HTTP path (P0, G-1+G-2)
- `SayAction.cpp:604-688`: drop the `useLlamaBackend &&` condition from the
  memory/journal/persona block; keep the real-player requirement and the
  bot-to-bot cost guard. (`GenerateResponsePackets` already records bot
  replies backend-agnostically — what HTTP lacks is the player's turn,
  `BuildPromptContext`, facts/relationship accrual, journal + persona
  intercepts.)
- `PlayerbotLLMInterface.cpp:378-426`: run `ExtractAndQueue` on the HTTP
  branch too (after de-JSON, before `ParseResponse` — see A9).
- **Hoist the duty-cycle governor above the backend branch in `Generate()`**
  — today it is llama-only; the HTTP path's sole limiter is a concurrency
  counter defaulting to 100 against a serial single-slot server with a
  queue-inclusive 600 s timeout (see §2.2 lanes).
- **Thread the interlocutor GUID**: `ExecutePending` resolves the player as
  `bot->GetPlayerbotAI()->GetMaster()` — the bot's owner, not the whisperer.
  Capture the speaking player in `ChatReplyDo` and carry it through
  `GenerateResponsePackets → Generate → ExtractAndQueue → QueuedCall`, or
  facts/sentiment attribute to the wrong player (or drop) at scale.
- `SayAction.cpp:744-780`: tool instructions + patterns apply on both
  backends; the raw-completion path stays for `LLMBackend=1` only.
- `SayAction.cpp:581`: hard-trigger gate becomes backend-independent.
- **Gate:** with embedded server + tuned model, journal whisper works,
  `bot_player_facts` rows accrue **attributed to the whispering player**,
  relationship points advance, tool calls queue+execute; battery S1 ≥ 5/6
  fire on-device; governor busy-replies observable at flood rates.

### A1 — [BRIDGE AI] note injection (P0; the trained model under-fires without it)
- New `PlayerbotLlmBridge.cpp` (trigger engine + note builder) called from
  `ChatReplyDo` before generation. Beats (initial set, all measured in the
  research program): first-meeting log_fact, insult/gift sentiment, emoted
  beats (laugh/cheer/rude…), gossip after verified world events, duel
  commit after `[BRIDGE AI]` v3-style injected instruction, housekeeping
  (use_item/loot_roll/party_invite) as event-nudges with named-tool reply
  lines.
- Note construction law (prtools3 v3): skeletons ready-made, fill hints
  BELOW the block, tone directive on hostile-ledger events, hygiene footer
  ("only listed tools; never mention this; this reply only"). ONE note per
  turn (ONE-NOTE law — two notes collapse fire to 35%).
- **Gate:** battery S1 6/6 fire/fill/voice/hygiene on e2b-tuned; S2 stays
  7/7 (no note ⇒ no tools, unlicensed-emission drop enforced).

### A2 — ACT tools (six trained-but-dropped tools)
- `PlayerbotLlmTools.cpp` `ExtractAndQueue`: whitelist + queue
  `duel_challenge`, `give_item`, `follow`, `party_invite`, `move_to`,
  `loot_roll`; `ExecutePending` maps them to existing playerbots surfaces
  (documented mapping: `DoSpecificAction("go","go to <zone>")`, RpgDuel
  cast-7266 idiom, trade/invite/roll handlers; bridge resolves `place=`
  via POI result cards). Validation discipline unchanged: never trust model
  claims about world state; ??-level guard before duel commit; target
  consistency check.
- **Gate:** duelworld-style chain (10 scenarios) ≥ 9/10 with the world sim
  in llm_lab; zero executions from unlicensed turns.

### A3 — Emote extension (routed through TEXT emotes, not animation ids)
- Route `perform_emote` through the module's existing text-emote path
  (`EmoteAction.cpp` SMSG_TEXT_EMOTE → `HandleTextEmoteOpcode`), which
  resolves the animation via EmotesText.dbc where one exists AND prints the
  authentic "X grins." line. The current `EmoteId()` feeds
  `HandleEmoteCommand()`, which takes the ONESHOT animation enum — pasting
  the trained emote names' TEXTEMOTE values there plays wrong animations
  (dance→wound-critical) or none (grin/shrug/whistle/glare/hug were
  text-only in 1.12 — which is itself authentic).
- Support all 19 trained emotes (names verified against 1.12
  SharedDefines.h text-emote enum: wave bow salute laugh cry nod no point
  cheer dance flex kiss rude grin shrug chicken whistle glare hug);
  fuzzy-map off-whitelist values (frown→no, etc.) per prtools2.
- **Gate:** battery S1 emote cases fire with the correct text-emote line
  visible to the player.

### A4 — Chat template on the generation path (P0 for base tier; prompt format v4)
- HTTP path: assemble the **messages array** exactly as trained (system via
  `sysm_for_card` order; user via compose(); assistant turns appended to
  history) — replaces the `<post prompt>` completion trick; `--jinja` applies
  the GGUF template. The `LLMApiJson` template gains a `<history>` fill
  key rendering prior turns as `{"role":"assistant","content":…}` entries,
  or the native side builds messages programmatically (preferred — one JSON
  builder, no conf-string surgery).
- In-process path: `llama_chat_apply_template()` with the GGUF template,
  thinking disabled; bump `POCKETREALM_LLAMA_PROMPT_FORMAT_VERSION`.
- **Gate:** byte-diff the rendered prompt vs the training renderer for 20
  sampled rows (≤ whitespace); battery S7 passes with true multi-turn
  history (not string concatenation).

### A5 — Segment phrasing alignment (memory renderer speaks the trained dialect)
- `PlayerbotLlmMemory::BuildPromptContext` emits the exact trained order and
  wording: identity → tools-note slot (A7) → bible+NO_NARRATE → Backstory →
  `Relationship with {player}: {Wary|Civil|Warm|Trusted|Bonded} (tier N of 5)`
  → absence → `Facts you remember about {player}: a; b`. Map existing
  stranger/acquaintance/ally/trusted → 1-5 with the point thresholds the
  DB already uses; facts inline (not bulleted); `[State]` line in the
  wrapped message every turn (zone/subzone from the bridge; companion
  `state_flavors` when in party).
- **Gate:** rendered context passes the same 20-row byte-diff; tier visible
  in prompt matches `bot_player_relationship` row.

### A6 — Context/token defaults
- `llmMaxNewTokens` 120 → 200 (tier table). (The duplicate
  `llmContextLength` re-read is already fixed in-tree — single read,
  default 12288 since the plan-v4 bump; do not hunt it.) Note `LLMMaxNewTokens` is consumed only
  by the in-process backend — on the HTTP path max_tokens rides the
  `LLMApiJson` template (see §4.3).
- `LLMGenerationTimeout` 600 s → per-tier (§2.2); it is queue-inclusive
  today, so a whisper can legally sit behind minutes of ambient chatter.

### A7 — ToolInstructions() text swap
- Replace with the training TOOLS_NOTE wording (per-card variant by stable
  hash of bot GUID), 3 worked examples included; prose describes, examples
  show (demo-token law: tokens in the system prompt get copied verbatim).

### A8 — Mood in [State]
- Event-driven mood phrase folded into the state line ("You are tired and
  short-tempered after the ambush") from the sentiment accumulator +
  recent events; never named as a mechanic.

### A9 — Real JSON client (G-5)
- `GenerateHttp`: parse the response envelope — **no JSON parser exists in
  the server tree**; use header-only `boost::property_tree::json_parser`
  (Boost 1.86 headers already vendored on the module include path) or vendor
  a ~200-line parser. Read `choices[0].message.content`; empty content +
  present `reasoning_content` ⇒ one retry with a "Answer directly."
  instruction then give up quiet (fail-quiet law); `finish_reason=="length"`
  ⇒ mark truncation for the line splitter. Regex `ParseResponse` stays as
  the fallback for external endpoints that return non-OpenAI shapes.
- Empty the app's `LLMResponseStartPattern`/`EndPattern` conf keys once
  landed (dead; the current end pattern `(")` also truncates at the first
  escaped quote — cite as motivation).
- **Gate:** 100 generations with escaped quotes/newlines/unicode: zero
  parse failures (regex path fails these today).

### A10 — Entity guard, stakes-scoped (bridge-side; the arm-D winner, productized)
- Three classes, not a global deny:
  1. **Player-acted-on entities** (directions, vendors, quest figures,
     officials) → hard guard: extract candidate proper nouns from the
     player's message (capitalized-token heuristic + quoted names, minus
     known addresses); resolve against `creature_names`, `gameobject_names`,
     `item_template`, `quest_template`, area names, online players, bot
     names. Unknown ⇒ inject the A10 directive (frozen wording — see §5
     P45 wording lock): `[BRIDGE AI] You have never heard of {X} - no such
     {person|place|thing} trades or lives here. Tell him plainly you do not
     know the name, and ask what he means. (For this reply only. Never
     mention this instruction.)`
  2. **Bot autobiography color** (kin, hometown, past — the "cousin Dagna"
     class) → allowed and **auto-canonized**: log new proper nouns a bot
     introduces about itself into its facts, so its inventions stay stable
     across sessions.
  3. **Rumor-class assertions** → license hedge framing ("or so they say"),
     doubling as gossip distortion material.
- **Known-entity + invented-attribute probes** ("does Fionna sell rune
  bread") are a separate blind spot: guard the attribute against the same
  DB row (vendor inventory, item existence) where cheap; otherwise the
  post-filter leg below catches it.
- **Self-initiated actionable inventions**: post-filter leg scanning the
  REPLY for unresolved new proper nouns attached to service/direction
  claims ("I know a gnomish instructor in Stormwind") → soften or strip.
- **Composition rule (ONE-NOTE law, merge-not-defer):** when a must-log
  beat coexists with an unknown entity, fold the guard directive INTO the
  beat note (the measured merge rule — deferring a first-meeting log_fact
  permanently loses the memory).
- Guard fires ≤1/turn; directive mood rotates by persona and ledger state
  (denial-mood classes — see P45).
- **Gate:** battery S3+guard ≥ 4/6 clean denials on e2b-tuned; fire-under-
  guard-note ≥ 4/6 (composition rule); invented-name + invented-attribute
  rates logged in diagnostics.

### A11 — Lore retrieval loop + era hardening (lore brain = DB)
- Question-shape trigger ("what/where/who/why" + known entity) → keyword
  index over the lore corpus → card injected as `[RESULT] …` (full 140-word
  density — short denial cards must NOT ship as lore substitutes) →
  generation answers FROM the card. No card ⇒ entity-guard hedging path.
- **Era-scrub the corpus before shipping it as truth:** `G:\Wow llm stuff\
  lore` contains ~5,710 lines with unambiguous post-1.12 markers (e.g.
  Molten Core citing "Patch 5.1", Legion-era bestiary). Cards injected as
  `[RESULT]` bypass logit bias and post-filters by construction. Re-source
  from Classic-era snapshots or scrub; add an era lint over shipped cards
  to the G5 battery.
- **Corrected era ban policy** (the naive list damages legitimate vanilla
  lore — worgen, Dalaran, and death knights are all 1.12 content):
  - Always-ban (logit_bias −20/−50, both token cases): `shattrath`,
    `draenei`, `pandaren`, `flying mount`, `portal is open`, `Gilneas
    opened`, `playable worgen`.
  - Context-allow (never bare-ban; ban only later-expansion senses):
    `Dalaran` (vanilla: the dome/crater, Ambermill mages — ban only
    "Dalaran floats/sewers/portal"), `death knight` (vanilla: Four
    Horsemen, Rivendare, Gorefiend — ban "Acherus"/"Ebon Blade"/playable
    premises), `Northrend`/`Outland`/`blood elf` (WC2/WC3-known lore —
    card-grounded answers fine), `worgen` (Silverpine/SFK/Scythe of Elune
    are vanilla), `Lich King`/`Naxxramas`/`Kel'Thuzad` (patch 1.11
    content). Never ban the word "wrath" (common speech).
  - Era n-gram backstop post-filter unchanged.
- **Era-true event class:** verified world events (Naxxramas over EPL,
  Scourge invasion, Ahn'Qiraj gates) are affirmable "news" via A1 beats —
  deflection training must not make bots deny patch-1.11 happenings.
- **Gate:** grounded-lore battery (12 vanilla questions, non-echo keys)
  ≥ 10/12 answered from cards; era affirmations 0/8 on a corrected trap
  set (SW↔IF gryphon flight is TRUE in 1.12 — trap it via flying-mount
  phrasing, not "can you fly").

### A12 — Post-generation hygiene stack (ordered, all measured)
- Leak filter: 5-gram overlap vs system prompt + marker terms → regenerate
  ≤2× → canned in-character deflection (h4 law).
- Markdown stripper; ASCII clamp (non-BMP stripped for the 1.12 client);
  `/say` 255-char cap enforcement in the line splitter.
- Dedupe-reroll: Jaccard > 0.5 vs the bot's last 8 replies ⇒ one resample +
  hidden "do not repeat" tail note (tricks-C law: deterministic > samplers).
- Emote/sentiment direction is bridge-decided where the note supplied the
  skeleton (model supplies reason only) — already true in v3; keep.

### A13 — Recall beats (memory USE) — the full measured retrieval table
- Beat shapes (recallfix's measured table, all content-supplying):
  - **Question-shape**: "Brannoc OWES YOU 5 silver from the ale. It is
    UNPAID. You are NOT square. Name it."
  - **Greeting-shape**: absence beat (magnitude, never a passive line) +
    exactly ONE weave pick + any unsettled grudge.
  - **News-shape**: "Something DID happen: you LOST a duel to him
    yesterday. That is your news. Tell it." (3/3 vs 1/6 instruction-only.)
- One beat per turn max (validated fold). Beats carry cargo, never bare
  instructions.
- **Dedupe-reroll exemption:** A12's Jaccard reroll must exempt
  beat-mandated content (a debt beat WANTS "you still owe me five silver"
  to resemble its last mention) — reroll checks non-beat prose only.
- **Gate:** battery S4 associative recall 0/2 → ≥ 3/4 with beats; add an
  unprompted greeting-weave case to G5.

### A14 — Duel outcome hook: `Player::DuelComplete` (Player.cpp:6932)
  One hook covers all nine outcome call sites (damage-win, forfeit,
  interrupt, flee…); Unit.cpp:1260 is damage-win only. Mirrors the
  OnPlayerLevelUp pattern; every duel-outcome beat (sentiment, gossip,
  banter) hangs off it.

### A15 — Prefill matrix integration (voice differentiation at zero prompt cost)
- Ship `merged/prefills_v2.json` (finalize `tools/matrix_finalize.py` first,
  510/538 validated + ~10 missing contexts); bridge selects by verified
  ledger/event context (never text match), appends exactly one token;
  continuation-hygiene line + meta-marker scanner stays.

### A16 — Relationship compiler + tier ceremony (progression must be FELT)
- Westfall's measured law: system-prompt disposition lines are ignored —
  E2B follows procedures. Compile tier × request-type into per-reply
  procedures for the POSITIVE ladder too (not just hostile tone
  directives): warmth changes what the bot does, not what it "feels".
- **Tier ceremony:** one-time transition beat on tier change (never naming
  the mechanic): "[BRIDGE AI] You have quietly decided Brannoc is a true
  friend. Show it your own way, briefly - and do not explain yourself."
  Plus a player-visible cheap surface (see E-workstream).
- **Per-tier unlock table:** Warm licenses ask-after beats (reciprocity —
  "how's that ram fund coming?"); Trusted unlocks one backstory secret +
  a discount procedure; Bonded shifts address (nickname) and licenses
  meetup initiation. Optional loyalty/jealousy beat class keyed on the
  gift ledger (bot comments when its rival got the player's trade).
- **Gate:** battery arc crossing a tier boundary shows felt change in
  ≥ 4/5 rolls (human-read panel).

### A17 — Initiative scheduler (the single biggest believability ceiling)
- Every A1/A13 beat as specced is player-triggered; immersion-design's #1
  gap and the validated greet-first finding say bots must speak first.
- Per-bot timers (debt reminder, follow-up on a remembered player goal,
  dusk appointments), event hooks (player level-up → cheer beat via the
  existing OnPlayerLevelUp), range-state arrival injection.
- Recouple the nag-cap to a freshness-weighted cadence: new facts want out
  once; stale facts stay quiet (blanket suppression kills the one
  validated unprompted behavior).
- **Gate:** 30-min idle-adjacency session: ≥ 3 unprompted in-character
  initiations per interacted bot, zero spam (cap 1/10 min).

### A18 — Crowd arbiter + pacing (a town, not isolated voices)
- Per ambient event: 1–2 full-LLM replies (addressed/most-related bots);
  everyone else deterministic emotes or authored one-liners (the fallback
  library was measured BETTER than the model on calm beats — it doubles as
  the crowd tier at zero LLM cost).
- Staggered 2–5 s persona-paced reply delays (instant uniform replies are
  uncanny); cross-injection capped at last 3–5 /say lines with speaker
  names; visible bot2bot banter beat licensed when the player walks up.

### A19 — Gossip lifecycle (the world knows what I did)
- Verified event → `world_gossip` row → distortion-per-hop (belief rows,
  validated design) → receiving bot renders it on a greeting beat.
- Player-subject rows (duel outcomes via A14, gifts, scandals) get
  delivery priority — "the world knows what I did" is the cheapest legend
  mechanic available and A14 produces the source events.

---

## 4. Workstream B — model registry, app runtime, tier knobs (Kotlin)

### 4.1 `LlmModelRegistry` (replaces the single-descriptor coordinator) (P0)
- Descriptors (id, file name, HF source or LOCAL, size, sha256, tier,
  default sampling profile, notes). Initial registry (sha256 verified
  2026-08-29):
  1. `gemma4-E2B-TUNED-q4_0` — **default** — local-only until published;
     3,360,144,672 bytes;
     `e267e9793bac7db4340103b840bce8b52412ec3545827160b449119379853665`.
     (q8_0 variant: 4,967,480,608 bytes;
     `adb3fa268db4946178fd19ba8f3e4759039bfc4559cfb3d6222bcc93df285035`.)
  2. `qwen35-08b-CLEAN-tuned-q8_0` (+q4_0:
     501,452,160 bytes;
     `d87581a7ddd118f3193073226748881ea5fcbf48af40676e50071574b5e1f7aa`)
     — efficiency tier.
  3. `gemma-4-E2B-it-qat-UD-Q4_K_XL` — fallback/base tier, **requires the
     template fix (§4.4) and is not chat-usable without it (§1.4)**.
  4. External API endpoint (existing external mode) — T4 budgets.
- Fixes G-3: sha256 pins enforced on download AND on first load; mismatch ⇒
  quarantine + user notification (never silent).

### 4.2 Distribution of the tuned models (decision required, recommendation given)
- Recommended: publish both tuned GGUFs to a Hugging Face repo (private or
  gated; the LoRA merges are the user's IP) and pin the URL+sha256 in the
  registry — the existing resumable downloader already handles HF.
- Alternative/additional: in-app sideload (SAF content URI →
  `filesDir/models/<name>` + sha256 verify) for offline/apk-independent
  installs — same code path as llm-bench's adb push, but user-facing.

### 4.3 Per-tier conf/runtime emission (G-4, G-8)
- **Emission split (the HTTP path consumes only what reaches the request
  body):** sampling fields (`temperature`, `top_p`, `top_k`,
  `repeat_penalty`, `min_p`) and `max_tokens` interpolate into the
  `LLMApiJson` template per tier — verified pass-through: SayAction fills
  the placeholders and `GenerateHttp` POSTs the body verbatim. Governor /
  context / bot2bot / timeout keys go as conf keys (`LLMGovernor*`,
  `LLMContextLength`, `LLMBotToBotChatChance`, `LLMGenerationTimeout`,
  `LLMMaxSimultaniousGenerations`). Conf keys that exist but are inert on
  HTTP (`LLMMaxNewTokens`, `LLMTopK`, …) are emitted only for the
  in-process backend or dropped.
- Missing native keys added by Workstream A with real names: memory-depth
  caps (facts cap, memories tail), ambient-chance, tool-subset licensing
  (per-tier licensed-tool set consumed by `ExtractAndQueue`/`ExecutePending`,
  threaded per-bot, thread-safe).
- `LlmRuntimeConfig` per tier: `-c`, `--parallel 2` with lane pinning,
  `--slot-prompt-similarity 0.5`, `--cache-prompt`, `--load-mode none` on
  repack profiles, MTP off. Add `serialVersionUID` before adding fields
  (sticky-restart deserialization) and a template-file field for §4.4.
- `LlmScreen`: model picker ordered small-first (the 501 MB q08-tuned is
  the "try it first" download with trade-off copy; 3.36 GB E2B is the
  upgrade), tier label, per-model sampling preset display + advanced
  override, API tier endpoint/model/key + budget display, storage policy
  (eviction prompt for non-selected models, free-space preflight,
  per-descriptor download fileName — the service hard-pins one name today —
  in-screen `.part` progress), and **restart-required notice on tier/model
  change**.
- **Breaking-test inventory (rewrite + extend in M1/M4):**
  `LlmRuntimePolicyTest` (contextSize 4096 + extraArgs pins, fill-key
  list), `ServerRuntimeFilesLlmGateTest` (positional args), Settings
  triple-write for `llmModelId` (Snapshot field + read + write — the
  silent-wipe trap), plus new per-tier emission table tests.
- Diagnostics counters (guard rate, tool fire/fill, cache hit %): need a
  native→app transport (world-server status API or log-derived) — declared
  dependency on Workstream A; spec it there, not on LlmScreen alone.

### 4.4 Base-model template fix (required for descriptor 3) + thinking-suppression per family
- Ship a non-thinking `chat_template.jinja` asset; when the loaded model's
  template emits `reasoning_content` (detect on warm-up call), restart with
  `--chat-template-file <asset>` (verify the flag on the vendored
  6d05498 binary first — `llama-server --help`). Warm-up probe: one
  8-token generation post-`healthy` inside `runSupervisor` (also fixes
  "first request 40% slow" measurements); a deliberate post-healthy kill
  is not attributed as an NPU load death (attribution only fires
  pre-healthy). `LlmRuntimeConfig` gains the template-file field WITH
  `serialVersionUID` (sticky-restart deserialization).
- **Qwen family thinking suppression:** §1.4's empty-content bug was only
  excluded for the tuned GGUFs with `enable_thinking:false` sent
  harness-side; the production `LLMApiJson` sends no such field. A4 emits
  `chat_template_kwargs {"enable_thinking": false}` per model-family
  descriptor flag, and one battery pass with the kwargs OMITTED verifies
  each export template's default before the tier table freezes.

### 4.5 Dead-code/doc hygiene (G-9)
- Delete `LlmRuntime.endpoint()` or wire it as the single source the policy
  consumes; fix the phantom fallback-model comment; refresh the checked-in
  cmangos mirror from the overlay (or note prominently it is regenerated);
  strike the phantom `LLMBanterEnabled` conf emission (zero native readers)
  and the caller-less `LlmModelDownloadService.expectedModelPath`; note
  `--parallel 1` is already llama-server's default.

### 4.6 Workstream E — the player surface (believability is player-visible)

The plan's M1-M3 make bots that CAN talk; nothing above makes a player
FIND it or FEEL it. Minimum viable scope (pulled INTO the milestones —
E1-E3 before M4 ships):

- **E1 Pacing + acknowledgment law:**
  - Bridge emits an instant turn-to-face + generic emote on whisper
    receipt, BEFORE generation (A3 machinery; zero LLM cost) —
    acknowledgment < 1 s.
  - Re-credit generation `timeDiff` across ALL lines (today it discounts
    the first only); drop MsPerChar 200 → ~30-40 ms for whisper-class
    replies (the journal's 4 ms/char is in-code precedent; 200 ms/char
    means a 100-char line dribbles for 20 s AFTER an 8 s generation).
  - Per-class budgets: whisper ~120 tok / ≤2 lines ≤160 chars; ambient
    1 line ≤80 chars; journal pace unchanged. Reconcile the splitter
    constant (code splits at 200; client cap 255) — one number, documented.
  - Busy/governor reply fires within 1 s of the trigger, not after a
    generation-timeout wait.
  - M1 exit criteria add: acknowledgment <1 s, first text line ≤6-8 s
    after whisper.
- **E2 First-contact onboarding:** one-time in-game system line after
  model load ("The people of this realm will talk back — walk up and
  greet them by name"); the first first-meeting `log_fact` beat becomes a
  scripted welcome that hints bots remember; a 30-second first-run app
  step pointing at the submenu + download. (Today the activation chain is:
  buried Android submenu → 3.36 GB download → "ai chat" strategy → type a
  bot's name correctly on a controller keyboard.)
- **E3 Conversation UI MVP (client addon, PR6I precedent):** radial-menu
  "Talk" entry targeting nearest/last bot (no name typing); pre-filled
  `/w <botname> ` composer; taller conversation overlay while talking
  (the minimal frame is ~4 lines and scrolls the player's own words away;
  reveal scroll buttons while a journal dump is in flight).
- **E4 Visible progression:** tier-change announcements as cheap
  system-colored lines (pure DB read, zero generation: "Kromgrit seems
  warmer toward you"); "standing" one-liner on first whisper of a
  session; expand + document the whisper keyword set (journal, standing,
  gossip); player-facing conversations counter in diagnostics.
- **E5 Model-picker ordering + try-first:** §4.3's small-first ordering
  with trade-off copy.

### 4.6b E6 World chatter (added rev 3, 2026-08-31; executes as S10 after S9 — the retrain stage is now S11)

DOCTRINE (three-agent research review 2026-08-31: shipped-game bark
systems, LLM multi-agent societies, mobile power architecture): **silence
is the default state.** Every line consumes a real event from the server's
own history — the fact bank ALREADY EXISTS (world_gossip rows,
bot_player_facts, verified events). No event → no line. Novelty for
150-hour players is pumped by the event stream, never by bigger pools:
unconstrained LLM iteration provably converges to phrasing attractors
(small models worst), and idle-timer chatter with thin context is the
shipped-game repetition meme (Oblivion guards, Skyrim radiant). Authored
pools demote to the lowest-specificity authored row (the floor lanes
sit dormant under the collapsed ladder - every live rung generates).

Layers (all three, owner-directed):
- **Party banter** — companion bots banter while the player quests.
  Phase-5 cadence: probabilistic roll every 6 min at 50% (down from the
  ~10-15 min Dragon Age timer), a combat hard-block, interrupted by any
  player interaction or higher-priority event; event barks (zone entry,
  kills, loot, level-up, a duel nearby) fire immediately and outrank the
  idle tier 5-10x.
- **Proximity murmur** — bots near the player exchange event-grounded
  lines at the 20-40s display cadence (Phase-5, after the corpus grew),
  drained from a pre-generated QUEUE (never just-in-time generation).
- **Global channel** — rare set-piece exchanges in general chat (gossip
  headlines, player-legend lines); the world-ring rules bind hardest
  here because the audience is the whole server.

Composer pipeline: ONE call carries 2-4 personas + the retrieved event
rows and emits a speaker-tagged 2-4 turn script (the multi-party composer
pattern; 3-5x cheaper and more coherent than per-bot calls). Per-bot
calls only when the player is involved. DEVICE FALLBACK generates single
lines — the fine-tuned 0.8B/2B models are single-bot-trained;
multi-speaker scripts are a cloud-class job. A bot's emitted line
re-enters the event store as a stimulus other bots may react to
(Watch Dogs 2 stimulus propagation).

Fatigue + legend law (the counter Skyrim lacked): hearings tracked per
(template × speaker × listener) AND per world-fact — no one comments on
the same duel twice; topic saturation retires a story after K tellings
near the player; a bot who has heard a legend will not retell it
(per-listener credence). Legend propagation extends A19's
DistortGossipHop: immutable event row as truth, persona-rephrase hops
for color, content-bearing hops capped ~3-5 (small-model telephone-game
collapse), credence-gated retransmission. A WORLD-LEVEL Jaccard ring
(recent N chatter lines, JaccardWords-checked) vets EVERY queued line —
cross-bot echo is unchecked by the per-bot A12 rings. Voice:
multidimensional persona cards (job + mood + current gripe —
anti-flanderization), min_p 0.05-0.1 with T ~1.0-1.2 for ambience, and
SelectLine rotation (the authored floor lanes sit dormant under the
collapsed ladder).

Phase-4 legends (engine-first, no new tables): counters escalate the Nth
telling ("again", "still", "legend by now") and retire at the 5-telling
cap; anniversaries derive from the oldest fact row's created_at (30/100/
365-day buckets, journal-visible); tier beats ride the ceremony (vouch at
ally, bonded bickering at tier 5, journal-visible); rumor drift stays
deterministic (one DistortGossipHop per hop, cap 3, originator verbatim)
with POI-biased sampling (place-named rows travel farther, so the player
hears their own legend warped across distance).

POWER STATE (collapsed 2026-09-03: the five-rung ladder is gone — the user
asked for a loud world or they did not, and no sensor second-guesses that.
Thermal throttling is the OS's job underneath. Measured 2026-08-31 costs
stand: naive just-in-time on-device murmur = 146 mJ/token ≈ 10-22%
battery/session; batched on-device ≈ 1-1.5%; cloud ≈ $0.6-1.0/month at
60-120 lines/hour):
1. NORMAL (ambience on): cloud composer batches — one request of
   4-6 exchanges every 4-6 min, drained from the queue at display cadence.
2. DIM (ambience on AND battery ≤15% off the charger): every layer stays
   on, device single lines, cadence stretched ~1 line/90s. Charging rescues
   the dim; an unreadable battery read never dims.
Never spawn a second model runtime — the device path shares the
resident llama-server (the dim threshold lives app-side in
ChatterPowerMonitor.computeRung, pinned by ChatterPowerMonitorTest). **MASTER TOGGLE:** one ambience
switch kills all three layers (owner-directed battery surface).

ACCEPTANCE (S10 gates): event-gated firing measured (the idle tier may
not fire with an empty event queue — silence default, soaked); world-ring
zero-repeat over a scripted 6h soak; battery ≤1.5%/session on the
device-batched path; whisper ack latency unharmed under full murmur load;
interruption rules observed (player chat owns the channel; combat
blocks); legend hops ≤ cap with truth anchored to rows; composer-script
voice per persona (human-read panel, the S8 ceremony precedent); fatigue
retirement proven (no same-fact double-tell in the soak).

---

## 5. Workstream C — v2.3 dataset + retraining (the weights-side fixes)

Today's audit of `v2_all.jsonl` (13,673 rows): hygiene clean (0 markdown,
0 non-ASCII, mean 36.5 words), **but** hedge rows 57 (0.42%, almost none
entity-grounded), era-trap user turns 151 with thin deflection targets,
trivia depth thin (lore weakest everywhere .33–.83), and the existing
hedge shape is the ENEMY: P21 lore rows teach "Don't know the name, but
…I will hazard a guess!" — hedge-prefix-then-confabulation. v2.3 additions
(same bank pipeline, banklib gates, LEDGER_AVOID/NOT_BUT caps live):

| bank | rows (target) | content |
|---|---|---|
| P45 entity-hedge | ~400 positive + **~100 negative controls** | (a) *Row shape*: extend banklib with a `guard` bank routing through `compose(extra=…)` (the exact arm-D shape) — or author as `memory`-shape with `beat=` guard directive and `mem=None`; pick one and record it. (b) *Wording lock*: A10's directive text is FROZEN before authoring — the bank must train the same distribution the bridge injects. (c) *Collision gate*: invented names generated from a fixed syllable pool, validated absent from the lore corpus, the 158 card names, player pools, and (when wired) `creature_names`/`gameobject_names`/`item_template`/`quest_template`; near-miss famous names only as a deliberate subclass. (d) *Reply shape*: deny + pivot question, **zero speculation, never invent a replacement entity** (arm-D's "Torbin? No. The gnome who ran his own smithy is dead" is a failure to train out); pivot to the player or a DB-verified entity only. (e) *Denial-mood classes* (suspicion/dismissal/deflect-to-authority/humor/worry) rotated by persona and ledger state so denials don't calcify into a formula. (f) *Negatives*: known-entity lore questions answered confidently + no-proper-noun vague questions answered normally — the trigger taught is exactly "unknown capitalized proper noun", nothing broader. (g) *Held-out nonce names* excluded from training, reserved for the G5 gate. (h) *Rework the P21 hedge-then-guess rows* or the two banks fight in weights. |
| P46 era-deflection | ~300 | era-trap user turns → denial/defusion/rumor-dismissal in voice (never affirm, avoid echoing the era term — echoes are unbannable). Trap targets use the corrected 1.12-aware framing (§3 A11): flying-mount phrasing not "can you fly"; worgen-as-vanilla excluded. Includes era-TRUE affirmations (Naxx 1.11, Scourge invasion) — deflection must not deny patch-1.11 events the NPC lived through. |
| P47 card-grounded lore | ~500 on the `poi_turn` shape | question + `[RESULT]` card → answer-FROM-card in voice; off-card pairs → hedge. Weights-trivia capped at ~100 ultra-common facts (capital rulers, adjacent geography) so bots aren't vacuously ignorant — the retrieval loop remains the lore brain; "≥8/12 without cards" is struck as a gate (it licensed the failure the architecture bans). (rev 3c: card spans must sample the SHIPPED asset's real length spread up to its ~1,166-char max — the trained 468-char `[RESULT]` max vs the runtime's longer cards is a measured distribution gap; close it at authoring; an optional TruthCore render-side trim rides S10's native lane discipline.) |
| P48 associative recall | ~300 | planted-fact turns where the ANSWER reaches for an adjacent memory (spider→hates spiders) + register-variety rows (calm beats without the echo-and-twist device; plain-answer directive rows) — trains memory USE and voice range, not just storage |
| P49 classifier sub-call | ~150 | already queued (Qwen intent gap) |
| persona-locked sessions | ~60 | persona-dip fix (.778→.5 on e2b-tuned): sessions locked to single persona bibles |
| P50 long-form (rev 3) | ~600-800 | 90-150-word replies, cue-bearing ONLY (storytelling / news-recall deep-dive / gossip / tier-5 Bonded shapes; cue strings frozen per §5.1): trains a SECOND length mode behind explicit in-prompt cues — the short default is untouched doctrine. All §5.1 budgets bind (≤10-20% of assistant token mass; 150-word hard cap; L3b). |
| P51 depth arcs (rev 3) | ~300-400 arc rows, 12-24 turns | grows multi-turn mass ~127 → ~450-500 (~3% of rows): ≥3 FORCED callbacks per arc, terse turns embedded INSIDE arcs, protocol turns mid-arc, loss on all assistant turns — arc QUALITY (cross-turn dependency) is the lever, not arc count; see §5.1. |
| P52 ambient barks (rev 3) | ~150-250 | the MURMUR register for S10's device-fallback chatter: event row → ONE 8-20-word in-voice line, tool-free, no opener convention (validator law L3c — a floor exception, distinct from L3/L3b). Rides the existing events/crowd shapes; authored per the §5.1 pipeline; dilution top-up per the standing rule. Without it, offline/critical ambience sounds like replies to nobody — the register is the point. |

- Retraining arms: **arm2′ (E2B)** and **arm1b′ (0.8B, from v2.3 — reusing
  the CLEAN run makes the hedge gate incomparable)**, plus the **open 2B
  arm** sequenced AFTER arm2′ (fresh untrained screening run first; third
  family — know the transfer before spending the gate suite). Recipe
  unchanged (fix-plan §3: padding_free=False, ctx 8192, dropout 0.05,
  3 epochs); rev-3 mechanics apply per §5.1 (LoRA preferred at these sizes,
  EOS/terminator verify, LR re-screen for the larger corpus, masking
  ablation at the arm2′ first checkpoint).
- **Winner rule (epoch-3 precedent, twice confirmed):** best G0-G5
  behavioral score among checkpoints within ~0.1 eval loss of the best;
  eval loss is a screen, never the decider.
- **Tool-row dilution management:** ~1,710 v2.3 rows + the P50/P51 banks
  are nearly all tool-free; measure the current protocol-row share in
  assistant-TOKEN mass and top up to at or above its previous proportion
  (the P45 guard-half rows are note-bearing but tool-free),
  or M5's "fix tools ≥ .93" fights the dilution it caused. Place a share
  of the protocol turns INSIDE P51 arcs (mid-conversation) rather than
  only as isolated short rows; track tool-FIRE calibration (per-family S1
  rate), not just syntax validity — the tool-free banks also teach the
  trigger negative space, and triggering judgment is the fragile half of
  the tool skill, not the `<<tool>>` syntax (small models learn exact
  formats cheaply — a 0.5B reached 100% valid JSON from ~8.7k rows).
  Add the 0.8B cadence-tic check ("X, and I will Y") as a G5 section —
  known bug, no gate today.
- Gates: G0 (tools/format + example-bleed scan; the conversion BLOCKER is
  cleared — 2026-08-31, S11 rounds 4+4b+4c: prtools2/3/4+ambition+tricks
  converted to keyed emote syntax, prtools3's parser made a faithful mirror
  of the shipped scanner (keyed primary, the S6-(j) lone-word fold,
  ResolveTextEmote verbatim), final.py and rp-web inherit it, and 17 pins +
  an 18/18 mutation pass hold it; final.py stamps + archives pre-keyed
  baselines; the G0 GATE itself has not run — no banks,
  no checkpoints yet, and pre-conversion harness numbers (incl. the G1 .861
  threshold below) are superseded and must be re-derived post-conversion),
  G1 final.py ≥ .861, G2 hard-creative ≥ 66, G3 live RP 100-turn
(ctx 12288 since the plan-v4 Phase-1 bump),
  G4 device matrix, and **G5 = this repo's `tools/llm_lab` battery, as a
  real gate**: n=3 majority with fixed seeds, ≥4 personas spanning tiers
  1/3/5, deterministic scorers only, held-out nonce names for the hedge
  gate, per-tool-family S1 rates, over-hedge counter-metric 0/6 on known
  entities, era 0/8 per-trap (no trap may fail majority), from-card lore
  ≥10/12, S1 ≥ .93, S2 7/7, S4/S5 not worse than the pinned baseline.
  Rev-3 G5 sections: length-conditional histograms per persona —
  P(reply > 60 words | uncued) ≈ 0 (the inflation alarm) and P(reply >
  90 words | cued) at or above the first-checkpoint threshold (the
  delivery metric; no literature prior exists — these two numbers ARE
  the experiment); multi-turn eval at DEPLOYMENT-length histories —
  persona fidelity + callback recall + distinct-n repetition at turns
  8/16/24; the per-epoch protocol-compliance curve recorded per arm; the
  question-ending-RATE counter-metric per persona/beat-class (rev 3b); and
  the worst-case TOKEN-BUDGET gate (rev 3c) — compose the worst-case
  trained request (largest row + chat template + bias splice + a full
  asset card), tokenize with the REAL model tokenizer, assert
  ≤ n_ctx − max_tokens − margin, so the chars-based native budget and the
  token-based server budget can never silently diverge again.
- Export discipline: one bf16 merge; each target quant derived from THAT
  merge (never quant-on-quant).

### 5.1 Long-form + depth generation (rev 3, 2026-08-31) — strategy + authoring protocol

Ruling (four-agent literature review, 2026-08-31): **ADD, never rewrite.**
The 13.7k short rows are the terse-default calibration AND the human anchor;
bulk lengthening would shift the default voice (SFT targets define output
length — prompting cannot redefine it) and invalidate every pinned baseline.
The only reworks are the mandated ones: P21(h) hedge-then-guess rows and
phrase-ledger offenders. The frozen corpus satisfies the
accumulate-don't-replace regime that formally bounds degradation; the
"≥10% human data" rule is RETIRED as folklore (the measured effect is a
100%-synthetic cliff — 90% substitution tolerable, 100% catastrophic — and
this corpus is ~85-90% hand-gated anyway).

**API-drafter protocol (banks P45-P51):** external LLM APIs are drafters
INSIDE the existing pipeline, never a bypass: few-shot seed each draft from
real corpus rows in the target card's voice → edit pass → validate_banks.py
CLEAN (L1-L10) → phrase_ledger pass → compose. Rev-3 additions:
(a) MULTI-SOURCE — at least two providers per bank; NON-Qwen providers for
the Qwen arms (multi-source synthetic measurably beats single-source as a
human-text proxy; the honest justification is diversity, NOT model collapse
— that literature is iterative/pretraining and does not govern one-shot SFT).
(b) ToS: check each provider's training-on-outputs terms BEFORE drafting at
scale, or draft from open-weight local models (sidesteps it entirely).
(c) DRAFT-DEDUP GATE: minhash/13-gram dedup of every draft batch against the
frozen corpus — an explicit named gate, not an emergent property of the
ledger.
(d) PER-PROVIDER REJECT LOG: validator reject rates logged per provider
(style fingerprinting; also catches a provider silently swapping its model
mid-pipeline).
(e) P45(b)'s wording lock GENERALIZES: the long-form cue strings (the
storytelling/news-recall/gossip note shapes) are FROZEN before authoring —
the bank trains the same byte strings the bridge injects at runtime.
Cue-explicitness is load-bearing: conditional length is proven only with
EXPLICIT in-prompt control signals, and only at 4-8B scale — natural
situational cueing at 0.8B/2B is unproven extrapolation; verify per
checkpoint.

**Budgets + laws:**
- Accounting unit is assistant TOKEN mass, not row count: a 120-word reply
  carries ~3x the gradient of the 37-word mean. Long rows (≥80 words) stay
  under ~10-20% of assistant token mass (heuristic — no literature threshold
  exists; confirm at the first checkpoint).
- Reply cap 150 words HARD (small models slop-drift on long generations:
  documented repetition collapse at 256+ tokens for ~1B models).
- Validator L3 extends CONDITIONALLY: 6-110 words stands for every existing
  bank; new L3b admits 80-150 words ONLY in cue-bearing rows of the
  P50/P51 banks; new L3c admits 8-20-word rows ONLY in the P52 bark bank
  (the murmur register — a floor exception). Never a global cap raise.
- Arcs (P51): every arc carries ≥3 FORCED callbacks (explicit cross-turn
  dependencies — the quality lever, more than arc count; no published
  minimum arc count exists and 127→475 is only ~3% density, so the
  multi-turn eval is the proof, not the row count); terse turns
  INSIDE arcs (depth and length stay decoupled features); protocol turns
  placed mid-arc (trains tool emission in long-context state — the
  deployed shape); loss on ALL assistant turns (a 16-turn arc is up to 16
  supervised targets).
- Loss masking is NOT settled: ablate assistant-only vs train-on-inputs at
  the arm2′ first checkpoint (small-dataset + long-system-prompt is exactly
  the regime where prompt-loss-as-regularizer has evidence).
- Beat-cargo/ceremony VARIANT ROTATION (rev 3b): the shared byte-pinned
  cargo strings (DebtCargo/NewsCargo/MemoryCargo/Grudge/Gossip) and the two
  ceremony shapes get persona-flavored variant sets (the P45(e) denial-mood
  pattern applied to beats), selected GUID-stably per bot — a player
  running many bots must not hear the same anchor sentence from all of
  them. The wording lock applies: variants are FROZEN before authoring and
  the banks train the same variant set the bridge injects; the RecallCore
  byte-pins + mutation tests update in the SAME change (pins and constants
  move together).
- Question-ending RATE counter-metric (rev 3b): G5 tracks the share of
  replies ending in a question, per persona and per beat class — the
  deny+pivot training (P45d), ask-afters (A16), and reminders (A17) can
  stack into a question-machine tic. No literature threshold: baseline at
  the first checkpoint, gate the DELTA against the pinned baseline, and
  require the guard beat not to raise it.

**Training mechanics (rev 3):** prefer LoRA at these sizes (0.5B-class
full-FT is documented unstable; LoRA forgets least); verify chat-template
EOS/terminator in composed rows (small-Qwen stopping failure mode); re-screen
LR for the larger corpus (optimal LR moved ~10x between 5k and 100k rows in
the Qwen study); run a per-epoch protocol-compliance harness on a held-out
set (accuracy-style metrics alone mislead — log format-validity rate AND
reply-quality proxies).

**Inference coupling (S9/app scope, NOT dataset):** per-tier max_tokens must
clear the bank — at ~1.4-1.5 tokens/word (Qwen tokenizer), 90-150 words =
130-225 tokens; today's 120/100/120 tiers truncate (the current 102-word
max already exceeds them). Raise the long-licensed tiers to ~200-230 tokens
(§4.3 emission) or cap the long bank at ~85 words on those tiers; combat/
casual tiers stay short. Decided WITH S9's pacing work: longer replies cost
device decode latency and E1 pacing owns that interaction (a 150-word reply
splits to 2-3 say lines via the 255-char splitter — mechanically fine,
pacing-budgeted).

**Context fix (rev 3c, 2026-08-31, MEASURED):** the server window was the
real binding constraint, not the 8192 training ctx — LlmRuntimeConfig ran
llama-server at `-c 4096` (its default; `runtimeConfig()` never overrode
it) while the native waterfall budget (LLMContextLength) is expressed in
CHARS: 8192 chars ≈ ~2.0k tokens at the MEASURED 4.07 chars/token (real
Qwen3.5-2B tokenizer, llama-tokenize on the largest session row cut to the
8-pair runtime shape). FIXED: `LlmRuntimeConfig.contextSize` default
4096 → 8192 (data class + Builder; LlmRuntimePolicyTest pin updated;
:app and :pocketrealm-llm test classes green). Measured accounting:
worst-case runtime request = 2,047 tokens + ~150 template + 14 bias +
a full asset lore card (~287 max) ≈ ~2.5k; + a 300-token reply ≈ ~2.8k
of 8192 — ~3x headroom, so P50/P51 growth is generation-side only.
Deliberately NOT changed: the in-process debug path's LLMCtxSize=4096
(its n_ctx = ctxSize × 4 slots — KV RAM multiplies per slot; debug-only;
revisit only with the device matrix in hand). G4 adds the KV-RAM reading
at the deployment ctx (12288 since the plan-v4 bump);
`--cache-type k/v q8_0` is the fallback lever if pressure shows.

---

## 6. Workstream D — evaluation + ops

- `tools/llm_lab/` is the standing regression harness (committed today):
  `sanity_battery.py` (S1-S7 + arms B/C), `arm_d_entity_guard.py`.
  **Scorer rebuild before M2** (the era/hedge gates are load-bearing and
  today's instrument is demonstrably broken): per-trap affirmation cue
  sets (auto 1/5 vs human 3-4/5 on e2b-tuned), purge/re-tag
  vanilla-ambiguous traps (SW↔IF flight is TRUE in 1.12; worgen are
  vanilla Silverpine), non-echo grounded keys (the "defias" key
  echo-passes a fully confabulated reply), "no invented proper nouns
  beyond input" check for clean denials, invented-attribute probes.
  Re-baseline §1.3 numbers from re-scored transcripts.
- Extend: n=3 majority with fixed seeds; persona spread (≥4 cards,
  tiers 1/3/5); held-out nonce names; per-tool-family fire rates; device
  -run mode (BASE URL → phone `:llm` over adb reverse); G5 gate script;
  era lint over shipped lore cards; prefix-cache rotation simulation (24
  bots, duty cycle) for the M6 latency percentiles; bot2bot +
  multi-bot-contention sections through the real server.
- **Prompt-dump hook in the C++ on day 1** (M1a): the A4/A5 byte-diff
  gates require a dump mode replicating banklib's TOOLS_NOTE variant
  selection (`crc32(card_id) % 4`) and history rendering — build it before
  the builder it verifies.
- Results JSON + md summary per run; keep every pinned model's last run
  in `C:\llm-lab\results\` and copy summaries into `G:\NPU LLM\logs\llm_lab\`.
- Artifacts from today already on disk: `C:\llm-lab\results\{e2b-tuned,
  q08-tuned,e2b-base,q4b-base,arm_d,all_summary}.json`; sha256 pins for
  the tuned GGUFs embedded in §4.1.
- Phase 3 device validation (user-gated, already planned): ABI gate, probe
  states, hybrid A/B, crash-block flow (incl. the LMK-SIGKILL
  DSP-session-wedge case), SIGTERM-during-load, end-to-end bot chat —
  after A0/A1/A9 land, its "end-to-end bot chat" step becomes the first
  on-device G5 run.
- **Day-1 distribution spike** (independent of the §4.2 decision):
  auth'd gated-HF download through the resumable downloader + one 3.4 GB
  SAF sideload with sha256 on the target device + adb-push formalized as
  the sanctioned dev channel for M1-M4; a plain-HTTPS static mirror is
  the cheapest de-risk.
- Ops residuals accepted of record (do not "fix" silently): loopback :8080
  no key + squatter flip in degraded mode; unbounded llama-server.log;
  ≤30 s stale-worker linger; two module copies invariant.

---

## 7. Milestones

| M | contents | exit criteria | effort est. |
|---|---|---|---|
| M1a "it speaks (desktop parity)" | A0, A4(HTTP messages builder + prompt-dump hook), A9, registry + tuned pin + LLMApiJson sampling fix + §4.4 template/thinking guards; adb-push dev channel | byte-diff vs training renderer on 20 rows; desktop battery S1 ≥5/6, S2 7/7; zero empty-content incl. kwargs-omitted pass | 1-1.5 wk |
| M1b "it speaks (on device)" | device-run battery mode; interlocutor-GUID + governor-hoist verification at flood | on-device S1 ≥5/6; facts attributed to the whisperer; busy-replies under flood | 3-4 days |
| M1.5 "first contact" | E1 pacing law + E2 onboarding | acknowledgment <1 s; first line ≤6-8 s; first-run moment shipped | 3-4 days |
| M2 "it remembers and acts" | A1, A2, A3, A5, A6, A7, A13 (one prompt change per checkpoint: A7 → A5 → A1 → A13) | journal/facts/tier prompts byte-match training renderer; ACT tools execute with gates; S4 associative ≥3/4 with beats; duelworld-10 ≥9/10; per-tool-family fire rates no see-saw | 2-2.5 wk |
| M3 "it doesn't lie" | A10, A11, A12, A14 (+ corpus era-scrub, scorer rebuild) | entity guard ≥4/6 clean denials + fire-under-guard ≥4/6; grounded lore ≥10/12 from cards (non-echo keys); era 0/8 corrected traps; leak filter passes h4 | 1-1.5 wk |
| M4 "it's findable + tiered" | E3/E4/E5 conversation UI MVP + visible progression; B4.2/4.3 full; **T4 validation battery (n=3 × ≥2 providers under the marker protocol)** | Talk flow needs no name typing; tier-change lines visible; T4 battery passes or T4 direct-lore stays off; contract tests rewritten | 1-1.5 wk |
| M5 weights v2.3 | C banks P45-P49 + persona sessions + keyed-syntax harness conversion; retrain arm2′/arm1b′ (2B after) | G0-G5 gates per §5 incl. held-out nonce hedge ≥5/6, over-hedge 0/6, tools ≥.93, cadence-tic clean | data 1 wk + 3 train runs |
| M6 "believable world" + device matrix | A16-A19; on-device G5 incl. rotation simulation; NPU hybrid A/B + coexistence trial; thermal/coexistence re-run | latency P50/P95 per §2.2 under CPU fallback; tier-ceremony felt-change ≥4/5; initiative ≥3/30-min session; Phase 3 checklist green | 1.5-2 wk |
| M7 polish | A15 prefill matrix, A8 mood, G-9 hygiene, docs refresh | prefill finalize + composition-rule test; PLAN-LOG + submenu docs updated | 3-4 days |

Sequencing law: **M1a before anything else** (today the shipped path cannot
talk at all); M5 authoring can start after M1a (training is desktop-side);
A16-A19 beat design can be authored during M2-M3 but lands in M6.

---

## 8. Risks

| risk | mitigation |
|---|---|
| Tuned-model confabulation persists in weights until v2.3 | bridge guards (A10/A11) are model-agnostic and land first; battery tracks the delta per retrain; P45/P46 treated as necessary-not-sufficient (era traps had 151 in-corpus rows and still failed — volume alone was insufficient) |
| Prompt see-saw on any instruction edit (3× confirmed) | §3.0 discipline: one change per checkpoint, per-tool-family scoring, fixed ladder A7→A5→A1→A13→A8, re-run after v2.3 weights |
| Gate machinery built on a broken scorer | scorer rebuild is an M3 entry criterion; per-trap pass (no trap fails majority), not aggregates |
| E2B-hybrid coexistence with the live client (trial #4 pending) | T2-CPU / T1-CPU-mmap shipped default until the trial passes; load-gate + one-shot-process + SIGKILL-wedge laws encoded in the registry |
| Slot/cache loss from conversation rotation | lane-pinned `--parallel 2` (interactive slot isolated), TAIL [Memories], M6 rotation simulation |
| NPU Q8 loader FORTIFY bug strands the q8 tier | q4_0 default on every tier; q8_0_htp quarantined until upstream clears |
| Model distribution (HF publish vs sideload) undecided | day-1 spike on both channels + adb dev channel; plain-HTTPS mirror as cheapest de-risk |
| Off-device API tier drifts from protocol | marker protocol validated per provider (n=3 × ≥2) in M4 before any T4-only feature; scanner-only licensing documented |
| Era leakage via prompt echo (unbannable) | A10-style deflection + P46; corrected context-aware ban list (bare-word bans damaged vanilla lore) |
| Voice register calcification (echo-and-twist tic, LLM-zen breaks) | P48 register-variety rows; calm-beat plain-answer directives; A15 prefill; battery tracks opener diversity |
| Over-hedging after P45 | negative-control rows + G5 over-hedge counter-metric 0/6 on known entities |
| Lore corpus is post-vanilla (~5.7k contaminated lines) | era-scrub/re-source BEFORE A11 ships cards as `[RESULT]` truth; G5 era lint on shipped cards |

---

## 10. Review record (8-agent review, 2026-08-29 — revisions folded in above)

Eight parallel reviewers (C++ production, Kotlin app, systems/perf,
narrative/immersion, player engagement, adversarial red-team, data/training,
vanilla-1.12 authenticity) reviewed this plan against the code, the G:\NPU
LLM docs of record, and today's transcripts. Verdicts: architecture sound
across all eight; **do not execute as originally written** — revisions
folded into this revision 2: build-mechanics section; governor hoist +
lanes/latency/RAM budgets; interlocutor-GUID attribution fix; tier-table
corrections (T1 q4_0-only, T2 default q4_0, timeouts, runtime profiles,
NPU operational laws); A3 text-emote routing; A6/A9/A14 factual fixes;
A10 stakes-scoped guard with merge-not-defer composition; A11 corrected
era policy + corpus scrub; A13 full beat table + dedupe exemption; NEW
A16-A19 (relationship ceremony, initiative, crowd arbiter, gossip
lifecycle); NEW Workstream E player surface (pacing law, first-contact,
conversation UI, visible progression); §4.3 emission split (LLMApiJson vs
conf) + missing keys + breaking-test inventory + marker-based hash verify;
§5 P45/P46/P47 authorable specs, winner rule, tool-dilution management,
G5 as a real gate; §6 scorer rebuild + prompt-dump hook + distribution
spike; §7 milestone split (M1a/M1b/M1.5) with realistic efforts.
Believability verdict that motivated the new sections: voice 8/10,
relationships 4/10, memory 6/10, world fabric 5/10 as originally written —
relationships and world fabric are where players decide bots are real,
and both now have dedicated machinery (A16-A19 + E).

---

## 9. Appendix — evidence & references

- Audit (2026-08-29): this conversation's working notes; gap table §1.1
  verified at `SayAction.cpp:568-786`, `PlayerbotLLMInterface.cpp:376-427`,
  `LlmRuntimePolicy.kt:145-228`, `LlmModelCoordinator.kt:62-87`,
  `PlayerbotLlmTools.cpp/.h`, `PlayerbotLlmMemory.*`, fix-plan §2.
- Battery: `tools/llm_lab/sanity_battery.py`, `tools/llm_lab/arm_d_entity_guard.py`;
  results `C:\llm-lab\results\*.json`; transcripts embedded in the JSONs
  (human-read verdicts in §1.3 flagged where auto-score undercounts).
- Trained-format source of truth: `G:\NPU LLM\scripts\finetune\banklib.py`
  (`sysm_for_card`, `compose`, TOOLS_NOTE variants, EMOTES, TIER_LABELS).
- Research docs of record: `G:\NPU LLM\docs\{TIMELINE,fix-plan,prtools3-5,
  immersion*,hardening,memory-gaps,recallfix,westfall,tooldose,bridge-first,
  final-report,ambition,screening-report,maker-settings}.md`.
- Laws referenced by number: see TIMELINE "WHERE EVERYTHING LANDED" §B.
