# Pocket Realm LLM Companion — Round-Robin Review & Research Plan

> ## ⛔ DIRECTIVE — READ THIS FIRST, BEFORE STARTING ANY ROUND ⛔
>
> **MariaDB work is NOT your job.** The database-backend study
> (`docs/plans/mariadb-replacement-research-plan.md` — MariaDB replacement,
> database performance, schema/DB backend changes of any kind) is owned by
> **other agents**. Do not open, continue, review, or "help" that study, do
> not execute its rounds, and do not touch any database-backend files on its
> behalf. If a task, file, or finding smells like database-backend work,
> skip it — it is already covered elsewhere. (Authoring that plan and
> running its rounds was scope drift — do not repeat it.)
>
> **Your job is THIS plan only:** the LLM-companion round-robin review loop
> (Part 2) with the **player-immersion audit as the priority angle**, plus
> the per-round Ideas Ledger contributions (Part 4). Read Part 0, then
> execute the next pending round below.

This document is the standing task runbook for two coupled loops:

1. **The round-robin review loop** — repeat multi-agent code review of the
   on-device LLM companion integration until a full review round returns
   **zero findings**, then keep it converged as the feature evolves.
2. **The research & ideas loop** — every review round also *adds* to this
   document: each reviewer agent contributes a few new ideas (outside the
   scope of defect-fixing) toward **player immersion, model compliance,
   and runtime performance**, which feed the research tracks in Part 3.

Everything here is written so a fresh session (or a new reviewer) can run
the loop without any other context.

---

## Part 0 — Status snapshot (update every round)

| Round | Reviewers | Findings | Fixed | Builds after fixes |
|-------|-----------|----------|-------|--------------------|
| 1 | 4 × code-reviewer | 10 defects | all | green |
| 2 | 4 × code-reviewer | 12 defects (incl. silently-failed splices) | all | green |
| 3 | 6 agents (5 angles + build) | 1 critical, ~8 major, ~10 minor | all | green (x64=0, arm=0, kt=0) |
| 4 | 6 agents | 1 critical (scanner infinite loop), 4 major, ~8 minor | all | green (x64=0, arm=0, kt=0) |
| 5 | 6 agents | 4 major, 13 minor | all | green (x64=0, arm=0, kt=0) |
| 6 | 6 agents (immersion-priority) | 1 critical, 6 major, 7 minor | all | green (x64=0, arm=0, kt=0 incl. unit-test compile) |

Convergence rule: a round is **clean** when all six reviewers return
"clean" in every category and contribute nothing outside the
**Accepted Caveats Registry** (Part 2c). Round 5+ continue until two
consecutive clean rounds. (Round 6 was not clean — 14 findings — so the
loop is NOT converged.)

> **NEXT ACTION (mandatory):** run **Round 7** of THIS plan — the
> immersion-focused review loop per Part 2. NOT the MariaDB study: that is
> out of scope, owned by other agents (see the directive at the top of this
> file). Round 7 verifies the round-6 fixes with fresh eyes across all six
> angles (the round-6 fix list is in the Round 6 log at the bottom) and
> adds its Ideas Ledger entries.

---

## Part 1 — What is being reviewed (architecture map)

Pocket Realm runs a CMangos WoW 1.12.1 realm (mangosd + client + playerbots)
as one supervised Android runtime. The LLM companion feature adds an
in-process llama.cpp backend (Gemma-class model, vendored kai/KleidiAI build)
for playerbot chat, role-play memory, tool calling, and a companion
"sit-and-talk" pause mode.

Hard constraints every reviewer must know:

- `native/playerbots` (@3b77c5f) and `native/cmangos` (@082afd6) are **pinned,
  pristine git submodules — never edited**. All source changes are:
  - whole replacement files in `native/patches/playerbots/` (8 files), and
  - `replace_anchor`/`replace_all` overlays defined as string constants in
    `tools/build_o09_realm_runtime.py` (`PB_*`, `CORE_*`; CORE_* have matching
    restore entries). The build rmtree's a mirror, applies overlays, builds,
    restores. **The effective source = upstream + overlays applied.**
- Build lane: `python tools/build_o09_realm_runtime.py --abi x86_64|arm64-v8a`.
  arm64 adds `-DPOCKETREALM_LLAMA=ON` and links the 5 vendored llama libs
  into `libpocket_world_runtime`; x86_64 compiles the llama backend out via
  `#ifdef` (HTTP/deterministic path remains).
- Migration discipline: new SQL only in `native/llm/sql/`, appended at the
  tail of `select_inputs()` in `tools/stage_database_migrations.py`
  (manifest = 412 entries; never touch `sql/base/`).
- The user's unrelated in-progress files — `UserVulkanDriverRegistry.kt`,
  `UserVulkanDriverValidator.kt`, `UserVulkanDriverRegistryTest.kt` — are
  **out of scope and must never be touched or reviewed**.

Key files:

| Area | Files |
|------|-------|
| llama runtime | `native/patches/playerbots/PlayerbotLlamaRuntime.h/.cpp` (worker thread pinned to cores 3–5, watchdog + ggml abort, per-bot warm slots, prefill-only prewarm, duty-cycle governor entry) |
| memory | `native/patches/playerbots/PlayerbotLlmMemory.h/.cpp`, `native/llm/sql/*.sql` (backstory, facts, relationship tiers, gossip, absence buckets, byte-stable prompt segments) |
| tools | `native/patches/playerbots/PlayerbotLlmTools.h/.cpp` (`<<tool field="value">>` extraction, world-thread executor, validation) |
| persona | `native/patches/playerbots/PlayerbotLlmPersona.h/.cpp` (keyword classifier + authored fallback beats) |
| integration overlays | `tools/build_o09_realm_runtime.py`: gate (`PB_SAY_GATE`), prompt build (`PB_SAY_PROMPT*`), recorder (`PB_SAY_RECORDER`), pattern handling (`PB_SAY_JSON_DUP`), raid/say routing (`PB_SAY_RAID_CASE`), RPG path (`PB_RPG_PROMPT`, `PB_RPG_ASYNC`), event hooks (`PB_UPDATEAI`, `CORE_GIVELEVEL`, `CORE_LOOT`), config (`PB_LLM_CONFIG_*`, `PB_LLM_CONF`) |
| Kotlin | `android/app/src/main/java/com/pocketrealm/llm/LlmModelCoordinator.kt` + `LlmModelDownloadService.kt` (HF GGUF download); `.../supervisor/DurableRuntimeSupervisor.kt`, `RuntimeModel.kt`, `AndroidRuntimeBackend.kt`, `RuntimeContracts.kt`, `RuntimeSupervisorClient.kt`; `.../service/RealmService.kt`, `.../server/WorldRuntimeService.kt`, `WorldNative.kt`, `IWorldControl.aidl`, `IRuntimeSupervisorControl.aidl` |
| native pause | `native/realm-runtime/src/world_runnable.cpp` (1 Hz reduced-duty pause loop), `world_runtime.cpp` (pause/companion JNI) |
| build gating | `native/realm-runtime/CMakeLists.txt` (POCKETREALM_LLAMA), `android/app/build.gradle.kts` (closure validator + `native/llm/lockfile-arm64-v8a.json`) |

Core mechanics reviewers must understand:

- Hard-trigger gate: whispers, party/raid name-mentions, and /say
  name-mentions from real players only. Everything else is a non-trigger.
- Governor: rolling per-bot/global windows inside `PlayerbotLLMInterface::Generate`;
  player-facing paths get `POCKETREALM_LLM_BUSY` ("\x02busy\x02") mapped to a
  busy line; the autonomous RPG path returns "" (silence).
- Markers: tool lines `<<name key="value">>` are extracted from raw model
  output **before** `ParseResponse`, stripped from displayed text, queued
  (cap 8), executed on the world thread in `UpdateAI` with live-state
  validation (`log_fact`, `adjust_sentiment`, `share_gossip` gated by a 120 s
  verified-event window, `perform_emote` one-shot whitelist).
- Prompt: backstory → tier/traits → absence bucket → facts (newest-8,
  oldest-first) → pinned gossip (60 s TTL) → rolling turns (newest-first
  within a 3000-char budget so upstream `LimitContext` can never
  front-truncate the stable segments; since round 6 the segments are
  budgeted against the character window — oldest facts, then gossip,
  then rolling turns drop under pressure — and the llama lane defaults
  the window to 12288 since the plan-v4 bump). `PROMPT_FORMAT_VERSION = 3` wired into
  slot invalidation.
- Channel keys: `0x80000000 | ChatChannelSource` for shared channels vs
  player GUID lows for whispers; synthetic event prompts recorded under an
  "(event)" pseudo-speaker.
- Patterns on the llama path: start pattern cleared, end pattern replaced
  with a pure speaker-cut `\b(?!BotName\b)(\w+):` (config default also cuts
  at a bare quote — right for JSON, wrong for prose).
- Companion mode: journaled `PAUSED` phase; native 1 Hz reduced-duty world
  update (chat path stays alive); `relaunchClient` clears a stranded pause
  flag; AIDL verb `setCompanionMode` wired supervisor → binder → JNI.

---

## Part 2 — The round-robin review protocol

### 2a. Roster (six reviewers, launched in parallel, fresh agents each round)

Each reviewer gets a **self-contained prompt**: the Part 1 context block,
its angle specialization, the Accepted Caveats Registry, and the round's
"verify these recent fixes" list. Launch all six in one batch:

1. **llama runtime** (`feature-dev:code-reviewer`) — `PlayerbotLlamaRuntime.*`
   against vendored `native/llm/include/llama.h` as API ground truth: batch
   filling, decode/sampling loop, KV-cache/seq management, slot format-version
   invalidation, watchdog/abort, worker queue priority + drain, reload
   locking, resource pairing, prewarm path, x86_64 stub.
2. **memory layer** — `PlayerbotLlmMemory.*` + SQL + staging appends + event
   hooks: thread safety (`StateMutex` coverage), SQL escaping/width vs
   schema (UTF-8-safe truncation), gossip cache/prune, relationship math,
   absence buckets, prompt byte-stability, journal surface.
3. **tools, gate & governor** — `PlayerbotLlmTools.*` + gate/pattern/RPG
   overlays + `PlayerbotLLMInterface::Generate`: scanner robustness
   (adversarial marker inputs — stray `=`, unbalanced quotes, `>>` in values),
   emote whitelist vs `SharedDefines.h` (one-shots only), gate coverage of
   every `Generate` call site, governor windows, debug path.
4. **Kotlin layer** — downloader (redirects/allowlist/resume/cancel/etag),
   FGS lifecycle + notifications + wake lock, supervisor state machine
   (PAUSED journal round-trip, `actualFrom`/`exitPhase`/rollback,
   `relaunchClient` pause-clear), AIDL wiring, binder thread-safety.
5. **player immersion audit** (the angle the user cares most about) — trace
   every player-visible string/behavior: no literal markers/`error`/prompt
   fragments ever in chat, channel fidelity (whisper/party/raid/say), tone
   and era-appropriateness (no AI/modern vocabulary), memory continuity feel,
   timing feel, journal, persona fallbacks, edge states (model missing,
   watchdog abort, busy).
6. **build & integration integrity** (`general-purpose`, read-only bash
   allowed) — every anchor still byte-matches pristine upstream (simulate the
   overlay chain in order; the one documented exception is `PB_SAY_PROMPT_V2`
   which anchors on stage-1 output), restore symmetry for CORE_*, file-copy
   completeness, submodule pristine-ness (`git status` in both), lockfile
   sha256 vs vendored files, ELF gates (DT_NEEDED allowlist + 16 KB LOAD
   alignment now also run over the vendored llama libs), CMake checks,
   migration manifest = 412 with the two LLM entries last.

### 2b. Per-round workflow

0. **Scope check (mandatory first step of every round):** this loop reviews
   the LLM companion integration only — immersion, compliance, performance,
   integration. Database-backend / MariaDB-replacement work is explicitly
   **out of scope**: it is owned by other agents running
   `docs/plans/mariadb-replacement-research-plan.md`. Do not work on, review,
   or extend that study or its files. If a reviewer's finding concerns the
   database backend itself (outside the LLM SQL in `native/llm/sql/`), do not
   act on it; at most note it in the round log as
   "external: mariadb study (other agents)".
1. **Launch** the six reviewers in parallel with self-contained prompts.
2. **Verify** every reported finding yourself in source before fixing —
   reviewers are high-quality but not infallible; false positives happen
   (e.g. an indentation mismatch that only looked like a bug).
3. **Fix** confirmed findings:
   - Patch files (`native/patches/playerbots/*.cpp/.h`) and Kotlin — edit
     directly with exact-byte `Edit` operations.
   - Overlay constants — edit inside `tools/build_o09_realm_runtime.py`;
     after editing run the validation snippet (2d) to prove Python syntax
     and that every anchor still matches pristine upstream exactly once.
   - **Windows Git Bash warning**: nested heredocs mangle Python
     triple-quotes/backslashes and have caused silent anchor misses. Prefer
     the Edit tool or byte-splice scripts that locate markers by index with
     asserts, and always verify content afterward.
4. **Rebuild + verify green** (all three must be 0 errors):
   ```bash
   python tools/build_o09_realm_runtime.py --abi x86_64
   python tools/build_o09_realm_runtime.py --abi arm64-v8a
   cd android && ./gradlew compileDebugKotlin -q -PpocketAbi=arm64-v8a
   ```
5. **Log the round** in Part 0 and append the round's new ideas to the
   Ideas Ledger (Part 4) — every reviewer is required to contribute
   2–3 ideas per round (see Part 4).
6. **Repeat** with fresh agents until a full round is clean (two consecutive
   clean rounds to declare convergence).

### 2c. Accepted Caveats Registry (reviewers must NOT report these)

1. `ChatReplyAction::RecordBotLine` is dead code (non-LLM chatter never
   enters LLM history).
2. Second-ever-turn shows "a first meeting" phrasing (absence bucket reads
   before the first `last_interaction_at` stomp).
3. `LlmModelCoordinator.primaryModel` size/sha256 are placeholders pending
   the real GGUF pin.
4. Sit-and-talk UI screen not built (service/supervisor plumbing complete,
   AIDL verb exposed).
5. No newline stop condition (EOG/EOT + max-token cap only).
6. Governor consumes budget even on failed generations.
7. `CreatePinnedThreadpool` doesn't validate against the device's real core
   count (defaults are safe on target hardware).
8. Synchronous `PQuery` on the world thread in `BuildPromptContext`
   (accepted, low frequency after the hard gate).
9. `DebugAction`'s blocking synchronous `Generate` (pre-existing upstream
   pattern, mod-gated).
10. Speaker-cut end pattern can truncate prose at any `word:` ("Note:",
    "3:30") — deliberate trade-off, strictly narrower than the JSON-era
    default it replaced.
11. `PB_SAY_PROMPT_V2` anchors on stage-1 overlay output, not pristine
    upstream (documented at its registration; reordering fails loudly).
12. Single non-preemptive worker: a whisper arriving mid-prefill waits for
    it, but prewarm prefills are now bounded by a dedicated 10 s deadline
    and never re-tried after abort.
13. A player literally typing "(event) …" is recorded as narration in their
    own conversation log (cosmetic).
14. No unit tests yet for the new Kotlin classes (noted gap; candidates:
    supervisor state machine, downloader resume/cancel).

### 2d. Post-edit validation snippet (run after any overlay change)

```bash
python - <<'EOF'
import py_compile
py_compile.compile('tools/build_o09_realm_runtime.py', doraise=True)
import importlib.util
spec = importlib.util.spec_from_file_location('drv', 'tools/build_o09_realm_runtime.py')
drv = importlib.util.module_from_spec(spec); spec.loader.exec_module(drv)
def check(name, path, anchor):
    data = open(path, 'rb').read()
    n = data.count(anchor.encode()) or data.count(anchor.replace('\n','\r\n').encode())
    print(name, n); assert n == 1, name
# add one check per touched/new overlay, e.g.:
rb = 'native/playerbots/playerbot/strategy/actions/'
check('RPG_PROMPT', rb + 'RpgSubActions.cpp', drv.PB_RPG_PROMPT_UPSTREAM)
check('RAID_CASE',  rb + 'SayAction.cpp',     drv.PB_SAY_RAID_CASE_UPSTREAM)
EOF
```

---

## Part 3 — Research plan: playerbots integration, immersion, compliance,
performance

These tracks are **not** defect work; they run alongside review rounds and
consume Ideas Ledger entries. Each track lists its first concrete
experiments. Baseline discipline: every experiment needs a measurement
before/after (see Track D) and must respect Part 1's hard constraints
(pristine submodules → everything via overlays/patch files; defaults OFF).

### Track A — Player immersion via deeper playerbots integration

Goal: the LLM should feel like it inhabits the same world the player sees —
reacting to real game state, not just chat text.

A1. **State-aware prompts**: enrich `BuildPromptContext` with live combat
    state (in-combat flag, nearby enemy count, bot health/mana), current
    zone/subzone name, time of day, weather — as short narration segments
    (byte-stable ordering, budgeted like the gossip segment).
A2. **Quest/duel/trade reactions**: extend the event allowlist (currently
    level-up + rare loot) with quest turn-in, duel end, battleground queue,
    party join/leave, resurrection — each with an authored reaction ceiling
    so ambient chatter stays bounded by the governor.
A3. **Idle ambient RP**: when unattended but in a party, occasionally
    produce one-liners tied to actual nearby world events (killed mob
    rarity, discovered flight path), rate-limited harder than reactive chat.
A4. **Personas across the whole bot population**: per-bot persistent persona
    cards (voice, verbal tics, taboos) generated at first spawn from the
    guid-seeded backstory, surfaced to *all* playerbot text paths, not just
    the LLM branch.
A5. **Emote/vocal layering**: let the model combine short emotes with speech
    (already whitelisted), and study whether 1.12 text-emotes
    (`/thank`, `/bow`) via the existing text-emote packet path read more
    naturally than state animations.

### Track B — Model compliance (making the model follow the contract)

B1. **Chat-template conformance**: today prompts are raw completion
    transcripts ending `BotName:`. Evaluate applying the model's real chat
    template (Gemma start-of-turn/end-of-turn tokens) per turn — measure
    leak rate of next-speaker text and tool-marker format errors before and
    after.
B2. **Grammar-constrained tool emission**: GBNF/grammar sampling restricted
    *only* while a tool section is being emitted (marker-first strategy:
    free-form speech, then a constrained tool tail) to eliminate malformed
    markers rather than tolerate them.
B3. **Prompt-structure ablation**: measure fact retention, tier-tone
    adherence, and tool-call correctness across prompt variants (segment
    order, second-person vs third-person backstory, explicit "stay in
    character, never mention being an AI/system" clause).
B4. **Few-shot seeding**: 2–3 compact exemplar turns (one with a tool call,
    one refusing to gossip without a verified event) at the pre-prompt tail;
    measure compliance delta vs token cost.
B5. **Refusal/deflection ladder**: authored, tier-flavored deflection lines
    for the busy/error paths (currently 3 busy variants) so fallbacks stay
    in-voice.

### Track C — Runtime performance

C1. **Slot scheduling**: measure prefill/decode throughput per slot count
    (2/4/8) at fixed total context; find the sweet spot for a 5-bot party.
C2. **Preemption**: evaluate `llama_decode` splitting (smaller ubatch) or
    aborting a prefill mid-stream at a checkpoint so whispers wait <1 s
    instead of up to the 10 s prefill deadline.
C3. **Quantization matrix**: bench the vendored Q4_K_XL against Q4_0
    fallback and (if the kai build supports it) IQ4_XS — tokens/s, first
    token latency, resident RSS, thermal slope under the 77 °C ceiling.
C4. **KV-cache economics**: measure warm-slot hit rates in real sessions
    (log `common`/`tokens.size()` per generation) and tune the rolling-tail
    budget (3000 chars today) to maximize prefix reuse.
C5. **NPU/GPU offload survey**: the vendored build is CPU-only; assess what
    a future KleidiAI/OpenCL/Vulkan backend would buy on the target SoC and
    what it would cost in battery/thermal coexistence with the game client.
C6. **Speculative decoding**: small-draft speculative decode for chat
    replies (short outputs, highly repetitive persona phrasing — likely a
    good fit); needs a second small model vendored, so measure first with
    self-speculation if the build supports it.

### Track D — Measurement & harness (prerequisite for A–C)

D1. **Offline compliance harness**: a driver-side test mode that replays a
    fixed prompt corpus through the runtime and dumps raw outputs + scanner
    results to a log — enables before/after comparisons without a device.
D2. **On-device bench**: reuse `G:\NPU llm\scripts\coexist-test-e2b.sh`
    methodology with `LLMBackend=1`: sustained busy-party session, log
    tokens/s, reply latency percentiles, CPU temps, frame rate of the
    running client.
D3. **Telemetry counters** (debug builds only): generations per minute,
    busy-trip rate, prefill-hit rate, tool-call acceptance rate, per-channel
    reply latency.
D4. **Immersion play-review checklist**: a fixed 20-minute scripted play
    session (whisper, party mention, raid mention, /say mention, level-up,
    rare loot, journal, governor storm, model-missing) with expected
    outcomes per step — the human counterpart to agent E.

---

## Part 4 — Ideas Ledger (every reviewer contributes, every round)

**Rule for review agents:** after the defect report, each reviewer MUST
append 2–3 new ideas (things that are *not* defects — enhancements,
experiments, integration opportunities) to its round's ledger entry below,
tagged `[immersion]`, `[compliance]`, `[performance]`, or `[integration]`.
The main agent triages: dedupe, rank by (impact × feasibility), promote the
best into Tracks A–D as concrete experiments. Never delete entries — mark
them `[done]`, `[promoted]`, or `[rejected: reason]`.

### Seed entries (from rounds 3–4 reviewers)

- `[performance]` Give prewarm prefills a dedicated short deadline and never
  retry them after abort — **promoted & implemented in round 4** (10 s).
- `[immersion]` Personas should flavor the busy/fallback lines per archetype
  (gruff vs shy "thinking" lines) rather than sharing one configured string —
  partially implemented (3 generic variants); archetype variants pending.
- `[immersion]` Bots could reference the *zone name* and time of day in
  greetings ("quiet night in Darkshire") — feeds Track A1.
- `[compliance]` Grammar-constrain only the tool tail of the output — feeds
  Track B2.
- `[performance]` Log prefix-hit ratio per generation to tune the rolling
  budget — feeds Track C4/D3.
- `[immersion]` Idle bots occasionally comment on nearby world events —
  feeds Track A3.
- `[integration]` Playerbots' existing rpg action graph (RpgSubActions) is a
  ready-made behavior skeleton: LLM picks the *intent* (rpg action), the
  deterministic graph executes it — inverts the current "LLM generates text
  only" split. Needs design review.
- `[performance]` Self-speculative decoding for short chat outputs — feeds
  Track C6.
- `[immersion]` Journal could be delivered as an in-game readable item
  (letter/lore item) instead of a whisper wall of text.
- `[compliance]` Measure (don't assume) how often the model emits
  next-speaker text today; if low, the speaker-cut end pattern could be
  relaxed to avoid the "3:30" truncation trade-off.

### Round 5 (2026-08-21)

- `[performance]` Skip the final `llama_decode` when the token cap is one
  away — the last decoded token is never sampled, so token-capped replies
  pay one wasted ~50–150 ms decode. (R1)
- `[immersion]` Feed the prompt tail into the penalties sampler via
  `llama_sampler_accept` before the first sample — repeat penalty currently
  only sees self-generated tokens, so bots can parrot the player verbatim. (R1)
- `[performance]` Pair `m_activeDeadline` with a generation counter so the
  abort check re-validates after storing `m_abort` (closes the stale-deadline
  TOCTOU) — would make sub-10 s prewarm deadlines safe. (R1)
- `[performance]` Coalesce `BuildPromptContext`'s per-segment queries (4
  sync round-trips per build, paid per real turn AND per 30 s prewarm) into
  one snapshot or short per-bot cache; also makes tier+absence atomic. (R2)
- `[integration]` Make every core hook anchor self-disambiguating with a
  unique neighbor line — **[done] implemented this round for
  CORE_GIVELEVEL** (SendPacket line prepended; verify before the next pin
  bump for the remaining multi-match anchors). (R2)
- `[compliance]` Host-side unit harness for the DB write path: emoji /
  3-byte CJK / lone backslashes / exact-width strings against schema
  widths — locks in the utf8mb3 invariant round 5 fixed in code. (R2)
- `[compliance]` Generalize the round-5 per-source tool policy: a
  `QueuedCall.source` field now exists — per-tool allowlists per source are
  a one-table change away if RPG turns ever earn emotes beyond perform. (R3)
- `[performance]` Host-side table/fuzz test for `ExtractAndQueue` (stray
  `=`, unbalanced quotes, nested `<<`, `>>`-in-value, 8+ blocks, non-UTF-8)
  — **[promoted] to Track D1** (locks in the round-4/round-5 scanner fixes
  in milliseconds without a device). (R3)
- `[immersion]` Expose `yes` (=273 `EMOTE_ONESHOT_YES`, already verified in
  the whitelist research) alongside `nod`/`no` so bots can answer yes/no
  questions physically. (R3)
- `[performance]` Fold sha256 into the download stream (digest per chunk)
  instead of a second full 2.6 GB read pass; adopt a complete `.part` on
  entry rather than deleting it. (R4)
- `[compliance]` Persist the in-flight descriptor as a sidecar file so
  every retry/redelivery path restores the exact pinned target — structural
  version of the round-5 retry-extras fix. (R4)
- `[integration]` Surface native pause state in `IWorldControl.status()`
  output so the UI can assert journaled PAUSED and the native flag agree
  (drift becomes observable instead of best-effort-patched). (R4)
- `[immersion]` Deliver the journal as paced whisper lines ("page 1 of 3")
  reusing `LinesToPackets` pacing — reads like a diary, solves journal
  length management. — **[done] implemented in round 6** (paced
  `GetJournalLines` delivery at diary pace; the "page 1 of 3" paging variant
  remains open if 40 facts still feels long in play).
- `[immersion]` Open the journal with a tier-voiced first-person greeting
  (reuse `TierTrait` wording) so surface tone matches chat tone. (R5)
- `[compliance]` Self-test asserting no string reachable from
  Say/Whisper/busy/persona surfaces contains `error`, `failed`, `<<`, `>>`,
  or modern vocabulary. (R5)
- `[compliance]` **[promoted] to build integrity checklist**: a
  `--verify-anchors` dry-run mode simulating the overlay chain (anchor
  count == 1 at apply time, documented exceptions enumerated) so pin bumps
  fail loudly with the constant name. (R6)
- `[integration]` Extend the Gradle closure gate to also verify the two LLM
  migration entries' sha256 against `native/llm/sql/` (ledger invariant
  enforced at packaging too). (R6)
- `[performance]` On arm64, assert `libpocket_world_runtime.so`'s DT_NEEDED
  llama set EQUALS all 5 vendored libs (today superset allowlist — a
  partial link would only fail at first dlopen). (R6)

### Round 6 (2026-08-21)

- `[performance]` Skip re-tokenization on byte-identical prompts: the
  exact-prefix path still runs `llama_tokenize` over the whole prompt
  before discovering the prefix matches the slot; cache tokenization
  keyed by (botGuid, prompt hash). (R1)
- `[performance]` Graceful degradation on KV pressure: when `llama_decode`
  returns 1, evict the least-recently-used *other* slot and retry the
  ubatch instead of failing the generation — pairs with the round-6 fix
  that stopped the final decode from discarding finished output. (R1)
- `[compliance]` Render generated pieces with `special=false`
  (`llama_token_to_piece` currently passes `true`): a stray control token
  a Gemma-class model emits mid-reply would render as literal
  `<start_of_turn>`-style text into chat. (R1)
- `[immersion]` Tier-transition acknowledgment: when the SQL tier
  expression promotes a relationship, queue a one-shot EventReaction so
  the bot verbally acknowledges the deepened bond in its own words. (R2)
- `[performance]` Per-(bot,player) relationship row micro-cache with the
  60 s gossip-cache pattern (invalidated by AddRelationshipPoints writes)
  — **dedupes** the round-5 "coalesce BuildPromptContext queries" entry;
  prewarm re-runs the queries every 30 s per bot on the world thread. (R2)
- `[integration]` Master-loot coverage for the rare-loot event: hook the
  master-looter distribution path (LootHandler.cpp:229,
  `pLoot->SendItem(target, …)`) with the same OnPlayerRareLoot call so
  raid master-looted rares open the same verified-event window. (R2)
- `[performance]` Reserve governor budget for player-facing turns: let
  `LLM_SRC_RPG_CHAT` only run when global usage is under half the cap so
  ambient RPG chatter, not the player's whisper, absorbs busy trips. (R3)
- `[compliance]` Echo a one-line tool reminder near the generation point
  (post-prompt tail): Gemma-class models keep marker syntax byte-exact far
  more reliably with the syntax adjacent to the generation — directly
  reduces the degenerate markers the scanner defends against. (R3)
- `[immersion]` Nickname/alias mention matching: with the round-6
  case-insensitive gate in place, a short alias (first syllable of the bot
  name) would let "byg, heal me" trigger like real party speech. (R3)
- `[immersion]` Surface PAUSED as a first-class UI state:
  `decodeRealmState` folds PAUSED into generic Running; a companionActive
  flag would let the sit-and-talk screen (caveat 4) and Home reflect
  "world paused for conversation" and drive the exit verb. (R4)
- `[performance]` Consolidate supervisor polling: four screens each bind
  and poll `remote?.status()` every 250 ms on main; one shared
  IO-dispatched poll fanned out to subscribers cuts binder traffic 4x. (R4)
- `[compliance]` Downloader lifecycle under the Android 14+ `dataSync`
  FGS time budget: override `Service.onTimeout` for a clean stop (the
  `.part`+ETag resume makes that safe) plus a `statvfs` free-space
  preflight. (R4)
- `[immersion]` Event-aware busy variants: when the governor trips on an
  `(event)` reaction turn, pick from event-flavored variants ("Give me a
  breath — that's worth a proper word.") so the busy line has an
  antecedent. (R5)
- `[immersion]` Journal front-matter copy: the journal opens with the
  prompt-side sentence "…Their past is their own." which reads as an
  instruction when shown to a player; render a player-facing header in
  `GetJournalLines` instead. (R5)
- `[compliance]` share_gossip content guard: the 120 s window verifies
  *an* event happened, not that the gossip text describes it — require the
  text to contain the triggering player's name or a keyword from the
  verified event before INSERT. (R5)
- `[integration]` Pre-build assertion that `native/llm/prebuilt/arm64-v8a/`
  still matches `native/llm/lockfile-arm64-v8a.json` — today the equality
  is enforced only transitively at the Gradle APK gate, far from its
  cause. (R6)
- `[integration]` Commit reviewer 6's AST overlay dry-run (apply+restore
  chain replay asserting exactly-once anchors and byte-identical restore,
  <2 s) as a `--verify-anchors` mode / CI check — machine-checks what the
  round-6 reviewer had to hand-roll. — extends the round-5 promoted entry.
  (R6)
- `[performance]` Extend prewarm to per-(bot,player) whisper contexts
  after a first whisper — cuts first-response latency on the most
  immersion-critical path (direct conversation) reusing the existing
  KV-prefix machinery. (R6)

### Round 6+ (template)

- _(copy the heading per round)_

---

## Part 5 — Round log template

```
## Round N — <date>
Reviewers: 6/6 returned
Findings: <count> (critical <c>, major <m>, minor <x>) — or CLEAN
Fixes: <list, file:line>
Builds: x64=<e> arm64-v8a=<e> kotlin=<e>
Ideas contributed: <count> → <promoted/done/rejected>
Notes: <anything unusual — e.g. anchor drift, build quirks>
```

## Round 5 — 2026-08-21

Reviewers: 6/6 returned
Findings: 17 (critical 0, major 4, minor 13) — all verified in source
before fixing; none rejected as false positives
Fixes:
- PlayerbotLlamaRuntime.cpp: exact-prefix resubmission no longer decodes a
  duplicate (pos, seq) KV cell (offset clamped to size-1 so the final
  token is re-decoded by the normal prefill loop; special-case block
  deleted); AcquireSeq takes keepPrefix by reference and forces 0 on a
  format-version drop so RunGeneration can never sample against a cleared
  sequence with a stale offset; new lock-free m_available gate —
  PreWarmSlot (map thread) checks it instead of Enabled()→Start() and can
  no longer cold-start or block on a companion reload's m_mutex.
- PlayerbotLlmMemory.cpp: StripAstral on all three DB write paths
  (utf8mb3 columns reject 4-byte UTF-8 in strict mode → whole async INSERT
  silently dropped); dead TierForPoints deleted; journal renders tier as
  prose (TierProse) and reads the NEWEST 40 facts (DESC + reverse) instead
  of freezing at the first 40 forever.
- PlayerbotLlmTools.h/.cpp: close-scan tracks nested `<<` depth (a stray
  `>>` can no longer leak into chat from degenerate markers) with a
  quote-only fallback when a nest terminator sat inside a quoted value;
  QueuedCall carries the generation source and ExecutePending refuses
  log_fact/adjust_sentiment from non-CHAT_REPLY turns (autonomous RPG
  chatter can no longer write facts/drift sentiment about a master who
  never spoke).
- build_o09_realm_runtime.py: journal delivery isPrivate=true (was
  broadcasting the whole journal to party/raid); busy-variant selection
  rotates per trip (atomic counter) instead of one fixed line per bot;
  the event drain's "say something!" cue is stripped from the recorded
  history turn (still reaches the current generation via <initial
  message>); ExtractAndQueue call site passes the source; CORE_GIVELEVEL
  anchor prepended with the unique SendPacket line (was 2 pristine
  matches, correct only by file order); the doubled
  PB_LOGIN_DB_SCHEDULE call is documented as the two-SendHolders idiom.
- LlmModelCoordinator.kt: 403/412 on the FIRST get now re-resolves and
  retries once (was gated on resume state and misreported as
  "incomplete"), with a distinct gated-repo error on the second rejection.
- LlmModelDownloadService.kt: failure notification's retry intent carries
  the failed descriptor's url/size/sha256 extras (was silently falling
  back to primaryModel).
- RuntimeSupervisorClient.kt: transact() only resumes from the
  ServiceConnection (main thread) and runs the binder call on
  Dispatchers.IO (createAccount's 30 s remote wait can no longer stall
  main).
Builds: x64=0 arm64-v8a=0 kotlin=0
Ideas contributed: 18 → 1 done, 2 promoted, 15 open (ledger above)
Notes: reviewer 6's full-chain anchor simulation ran clean over all 69
constant families (PB_SAY_PROMPT_V2 stage-1 exception confirmed);
submodules pristine at 082afd6 / 3b77c5f; manifest 412 with LLM entries
last; llama lockfile sha256 verified. Two below-reporting-bar dead
functions (HasToolCalls/ToolBudgetAvailable) left in place. One
intermediate x86_64 build failure during the fix batch (const char*
concatenation in the nudge-strip overlay) — corrected and rebuilt green;
submodule restore verified clean after every build, including the failed
one.

## Round 6 — 2026-08-21

Reviewers: 6/6 returned (reviewers 2 memory and 6 build-integrity: CLEAN)
Findings: 14 (critical 1, major 6, minor 7) — all verified in source
before fixing; none rejected as false positives
Fixes:
- PlayerbotLlamaRuntime.cpp: ReloadInPlace now clears the lock-free
  availability gate for the whole model free+load window and restores it
  only on success — a map-thread prewarm could previously pass
  ReadyForPrewarm() and then stall inside Submit() on m_mutex for the
  entire multi-second reload (the exact stall class round-5 fix 3 claimed
  to close); prewarm submits through a new try-lock TrySubmit that drops
  the request on contention instead of blocking the simulation tick; the
  decode loop breaks before the final never-sampled token at the cap
  (that wasted pass's failure used to discard the finished output and
  return "error").
- PlayerbotLlmTools.h/.cpp: prose segments are truncated at a stray `>>`
  (appendProse helper at all three append sites) — reversed
  `>>tool key="value"<<` markers and bare stray terminators no longer
  leak protocol text into chat (also closes round-5 fix 1's residual
  unterminated-quote edge); dead HasToolCalls/ToolBudgetAvailable
  deleted (the header claimed an extractor coupling that never existed;
  the extractor uses the literal 8 under one lock).
- PlayerbotLlmMemory.cpp/.h: BuildPromptContext budgets its segments
  against AiPlayerbot.LLMContextLength minus a reserve for the caller's
  pre/post prompt + tool instructions (facts drop oldest-first under
  pressure, gossip skips, rolling turns take the remainder, capped at
  3000) — the worst-case context (~6 KB) previously exceeded the 4096
  default and upstream LimitContext front-cut the backstory/tier/absence
  segments in sustained conversations; absence buckets add "a few hours"
  (< 6 h; 1–24 h previously all read "most of a day"); new GetJournalLines
  returns the journal as one line per entry (GetJournalText joins them).
- build_o09_realm_runtime.py: the name-mention hard trigger is
  case-insensitive (boost::algorithm::icontains — lowercase party/raid/say
  mentions were silently dropped to the canned path); "journal" delivers
  via ChatReplyAction::LinesToPackets on a whisper template at diary pace
  (MsPerChar 4, 200-char packet limit) instead of one multi-KB SMSG;
  TryFallback receives the whisper flag.
- PlayerbotLlmPersona.cpp/.h: two rotating variants per (archetype,
  category) cell (per-bot rotation via an atomic counter, mirroring the
  busy-variant fix) and whisper-specific vouch/de-escalate beats —
  crowd-directed lines ("stand aside", "all of you") were being whispered
  privately to the asker.
- DurableRuntimeSupervisorTest.kt: FakeBackend implements the abstract
  RuntimeBackend.setCompanionMode — unit-test compilation was broken
  (round 5's gate only compiled the main source set; this round's gate
  adds compileDebugUnitTestKotlin permanently).
- RuntimeSupervisorClient.kt: transact() handles onBindingDied — the
  supervisor process dying before publishing its binder previously hung
  the caller on Dispatchers.IO forever and leaked the connection
  (observeRealmState already handled it).
- LlmModelDownloadService.kt: the success notification has no dead Cancel
  action (it targeted the progress id), is autoCancel, and taps through
  to the app's launcher intent.
- LlmModelCoordinator.kt: a `.part` matching the pinned size exactly is
  adopted (verify + rename) instead of deleted — the process dying during
  the tens-of-seconds checksum pass previously cost a full re-download;
  only strictly oversize partials (corruption) are discarded.
Builds: x64=0 arm64-v8a=0 kotlin=0 (compileDebugKotlin AND
compileDebugUnitTestKotlin)
Ideas contributed: 18 (all open; 1 dedupes the round-5 query-coalescing
entry). Separately, the round-5 paced-journal idea was implemented this
round and marked done in the ledger.
Notes: reviewer 6's AST overlay simulation passed over the full chain
(70 apply ops, 0 anchor drift; PB_LOGIN_DB_SCHEDULE 2→1 idiom and
PB_RPG_MANUAL_SET replace_all 4× confirmed as the documented exceptions)
and all 412 migration hashes verified against source. Round 5's
"silent" fix-3 claim was partially wrong — its m_available gate did not
cover the reload window (found by round-6 reviewer 1). One intermediate
x86_64 build failure during the fix batch (const char* + std::string
concatenation in the facts header) — corrected and rebuilt green;
submodules verified pristine (082afd6 / 3b77c5f) after every build
including the failed one.

Post-round verification pass (same day, coordinator self-audit of the
round-6 fixes) — 4 issues found and fixed:
- The segment waterfall over-trimmed in the common case: nothing
  configures AiPlayerbot.LLMContextLength, so the window was the legacy
  4096-CHARACTER default while the real constraint is per-slot TOKENS
  (LLMCtxSize 4096 ≈ 14K chars) — rolling history would have been
  squeezed from its 3000-char cap to ~700 routinely. The llama lane now
  re-reads LLMContextLength with an 8192 default via the
  PB_LLM_CONFIG_CPP overlay (an explicit conf value still wins);
  worst-case composition ≈ 7.9K fits under it with full richness.
- PROMPT_FORMAT_VERSION bumped 2 → 3: segment assembly changed (the
  header contract requires the bump on any assembly change; slots are
  in-process so the bump is free at runtime).
- GetJournalText was dead after the paced-delivery switch (the overlay
  uses GetJournalLines) — deleted.
- Reserve slack raised 256 → 384: a max-length (255-char) whisper can
  land whole inside <initial message> expansion, which the smaller
  slack could under-cover and let LimitContext trim a few front bytes.
Builds after the pass: x64=0 arm64-v8a=0 kotlin=0; anchors re-validated
(including the config overlay); submodules pristine.
