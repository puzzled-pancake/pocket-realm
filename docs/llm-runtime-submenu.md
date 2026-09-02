# AI bot LLM runtime — design and operations

Status: implemented (2026-08-28; M6 personality wiring + external endpoints
2026-08-29), pending on-device validation.
Related: `native/llm/MILESTONES.md`, `android/pocketrealm-llm/prebuilt/README.md`,
`android/pocketrealm-llm/README-INTEGRATION.md`.

## What it is

An opt-in settings submenu (Settings → "AI bot LLM") that manages an
embedded llama-server for playerbot speech. The server runs in a
fault-isolated `:llm` process (foreground service, `dataSync` type) provided
by the vendored `:pocketrealm-llm` Android library module. The realm's C++
playerbot client talks to it over loopback HTTP — **no native changes**: the
existing `AiPlayerbot.LLMBackend = 0` HTTP path is used.

Compute placement is selectable:

- **CPU** — llama.cpp on pinned cores (KleidiAI + dotprod build).
- **NPU (Hexagon hybrid)** — prefill runs on the Hexagon HTP (QCS8550 v73,
  measured 430-540 tok/s, core-invariant); decode runs on the pinned CPU
  cores (llama.cpp). This is the geniex v0.5.0 prebuilt backend
  (`libggmlhex.so`) loaded by the server at runtime.
- **Auto** — NPU when detection passes, CPU otherwise.

## Architecture

```
UI (LlmScreen) ──Settings(DataStore)──┐
                                      ▼
:supervisor  AndroidRuntimeBackend.ensureLlmRuntime()
  starts/stops :llm BEFORE the world AIDL start ──► :llm LlmRuntimeService
  (model memory is claimed before the game claims     │ fork/exec llama-server
   its RAM — the measured coexistence ordering)       │ (affinity+nice in child)
                                                      ▼
:world  ServerRuntimeFiles.worldConfig*()  writes  aiplayerbot-<id>.conf
  = BotProfile.playerbotConfig()  (reviewed, LLMEnabled = 0)
  + LlmRuntimePolicy.confBlock()  (appended only when enabled AND model staged)
```

Config keys are read by the native client in
`native/cmangos/src/modules/PlayerBots/playerbot/PlayerbotAIConfig.cpp`.

### The appended conf block (LLMBackend = 0, HTTP)

```ini
AiPlayerbot.LLMEnabled = 2
AiPlayerbot.LLMBackend = 0
AiPlayerbot.LLMApiEndpoint = http://127.0.0.1:8080/v1/chat/completions
AiPlayerbot.LLMApiJson = {"model":"local","messages":[{"role":"system","content":"<pre prompt> <context>"},{"role":"user","content":"<prompt> <post prompt>"}],"max_tokens":230,"temperature":0.7,"top_p":0.8,"top_k":20,"repeat_penalty":1,"presence_penalty":1,"cache_prompt":true,"stream":false}
AiPlayerbot.LLMResponseStartPattern =
AiPlayerbot.LLMResponseEndPattern =
AiPlayerbot.LLMPromptFormat = 1
AiPlayerbot.LLMApiModel = local
AiPlayerbot.LLMTemp = 0.7
AiPlayerbot.LLMTopP = 0.8
AiPlayerbot.LLMTopK = 20
AiPlayerbot.LLMRepeatPenalty = 1
AiPlayerbot.LLMPresencePenalty = 1
AiPlayerbot.LLMMaxNewTokens = 230
AiPlayerbot.LLMGenerationTimeout = 60
AiPlayerbot.LLMConnectTimeout = 10
AiPlayerbot.LLMMaxSimultaniousGenerations = 2
AiPlayerbot.LLMGovernorWindow = 60
AiPlayerbot.LLMGovernorBotMax = 8
AiPlayerbot.LLMGovernorGlobalMax = 8
AiPlayerbot.LLMContextLength = 8192
AiPlayerbot.LLMFactsCap = 12
AiPlayerbot.LLMMemoriesTail = 6
AiPlayerbot.LLMBotToBotChatChance = 10
AiPlayerbot.LLMBanterEnabled = 1
AiPlayerbot.LLMLoreFile = "<server run dir>/lore_cards_v112.jsonl"
AiPlayerbot.LLMChatterEnabled = 0
```

The S7 `LLMLoreFile` row stages the era-scrubbed vanilla lore card index
(app asset `lore/lore_cards_v112.jsonl`, built by
`tools/llm_lab/build_lore_cards.py`): question-shaped turns retrieve a
`[RESULT]` card at full density for grounded answers, and `move_to`
places resolve against its POI cards. An absent or unreadable file fails
closed to "no cards" — the entity guard still works without it.

The S10 `LLMChatterEnabled` row stays `0` until the app stages the
chatter power file (it does whenever the LLM subsystem is enabled);
enabled, the block also carries `AiPlayerbot.LLMChatterPowerFile =
"<server run dir>/chatter-power.txt"` — the FILE's `enabled` flag is the
master switch behind the **World chatter (beta)** toggle, re-read by the
native scheduler every tick so the switch works mid-session in both
directions, and it carries the app-refreshed power-ladder rung (a missing
or stale file stops chatter or degrades it to the authored event floor —
silence is the default). The `LLMChatterComposer*` rows ride the
External server fields when filled (the cloud script composer for party
banter and murmur; without it the NORMAL rung runs device single-line
batches).

The sampling fields come from the selected registry model's profile
(`LlmModelRegistry`; the sample above is the TUNED_E2B default since the
S4 default flip — the base tier keeps its own maker profile, and
`repeat_penalty:1` is pinned for every Gemma profile). The tier rows
(timeout, simultaneity, governor, context, memory depth) follow the §2.1
tier table. The S9 `LLMConnectTimeout` row bounds the client's TCP
connect (a dead external endpoint fails inside 10 s instead of hanging
for the OS default; loopback connects are instant either way).

### Whisper keywords (S9/E4, zero-generation surfaces)

Whispering the bare word `journal`, `standing`, or `gossip` to a bot reads
a memory surface instead of generating: `journal` paces out the bot's fact
rows (the diary read), `standing` answers with the tier one-liner
("{Bot} thinks of you as a comrade of many battles."), and `gossip` renders
what the town says about you when a `world_gossip` row names you. They are
whisper-only, never fire on event turns, and award no relationship points
(they are reads, not conversations). The standing line is also voiced
automatically, system-colored, on the first whisper of a session with each
bot.

Since S4 the request is built NATIVELY in the trained prompt format
(`LLMPromptFormat = 1`): the system message speaks the exact trained
contract (identity → TOOLS_NOTE variant → bible → Backstory → tier →
absence → inline facts — byte-diffed against the training renderer by
`tests/test_llm_prompt_format.py`), the user turn carries the
`[Memories]`/`[State]` tail, and prior turns ride as role-separated
history messages. The `LLMApiJson` template stays as the legacy fallback
for hand-configured servers (`LLMPromptFormat = 0`) and its sampling
fields derive from the same profile as the conf keys, so the two paths
can never disagree. `LLMThinkingKwargs = 1` (emitted for qwen-family
models only) adds `chat_template_kwargs {"enable_thinking": false}` to
the request (§4.4; the q08-tuned export template's kwargs-omitted default
was verified non-thinking — see
`C:\llm-lab\results\s44_thinking_kwargs_q08-tuned.json`).

The C++ client POSTs `LLMApiJson` verbatim after replacing the
`<pre prompt>/<context>/<prompt>/<post prompt>` fill keys (SayAction.cpp).
The response is parsed as a real OpenAI chat-completions envelope (A9):
the reply is `choices[0].message.content`, decoded with full JSON string
semantics — escaped quotes, newlines and `\uXXXX` escapes survive intact,
which the old regex extraction could not do (its end pattern `(")`
truncated the reply at the first escaped quote). When the content comes
back empty with the budget burned into `reasoning_content` (the pinned
base model's thinking-preamble failure mode), the client retries once
with a direct-answer instruction spliced onto the same user turn and
otherwise stays quiet; a `finish_reason:"length"` reply has its dangling
partial sentence trimmed before line splitting. The response pattern keys
are emitted as EMPTY values because the regex path is dead on every
endpoint this block configures — but they must be written, since an unset
key would fall back to the reviewed JSON-era native defaults. The regex
fallback remains in native code for endpoints that return non-OpenAI text
shapes (hand-edited conf; the raw body must still pass a voicing
plausibility gate — error pages, BOM remnants and binary garbage are never
voiced). On every path — envelope content and raw fallback alike — tool
markers (`<<tool …>>`) are extracted from the reply before anything is
voiced, so prose spans that legitimately contain `<< … >>` (guillemets,
generic-code chatter from a hand-configured text endpoint) lose those
spans. A desktop/dev conf that keeps the NATIVE default patterns while
pointing at a chat-completions endpoint stays silent: the default start
pattern targets `"text":` and never matches decoded prose — empty the
pattern keys there too. Delete/split patterns keep their reviewed defaults
(the two JSON-era residue transforms are skipped automatically when the
reply arrived as decoded envelope content). The conf parser strips only leading/trailing quotes and later
duplicate keys overwrite earlier ones, so the appended block wins over the
base conf's `LLMEnabled = 0` (the same mechanism the M1 debug override
used).

## External endpoint mode (M6)

The submenu's Source choice selects where the model lives. **External
server** mode never starts the `:llm` process (the supervisor's
`ensureLlmRuntime` stop-branch reclaims any leftover embedded server, so
conf and process stay in lockstep) and the conf gate needs no staged GGUF:
`ServerRuntimeFiles.llmOverrides` emits `LlmRuntimePolicy.confBlockExternal`
first, before the model gate and the debug fallback. The block is the same
body as the embedded one with three fields from settings:

- `LLMApiEndpoint` — the user URL, normalized by
  `normalizeExternalEndpoint` (fail-closed null: no scheme, whitespace,
  quotes, backslash, control chars, or >512 chars; a bare origin gets
  `/v1/chat/completions` appended, an explicit path is kept as-is).
- `LLMApiKey` — optional; the pristine native client already sends it as an
  `Authorization: Bearer` header (PlayerbotLLMInterface.GenerateHttp).
  `normalizeExternalApiKey` fails closed on quotes/backslash/whitespace.
- the `model` slot in `LLMApiJson` — `normalizeExternalModel` (empty →
  `local`), validated JSON-safe before interpolation.

Any OpenAI-compatible `/v1/chat/completions` service works: hosted APIs,
OpenRouter, a PC llama.cpp/LM Studio server, ollama. Two hardening overlays
make real endpoints survivable (`PB_LLM_IFACE_HTTP_*`): GenerateHttp now
treats non-2xx as `"error"` (bots stay silent instead of reading an error
page as a completion) and de-chunks `Transfer-Encoding: chunked` bodies.
The memory layer serves external mode identically: the ChatReplyDo gate was
widened from llama-only to every real player, so journal, persona beats and
`BuildPromptContext` work for remote models too. Tool instructions are NOT
appended on the external path (tools are an in-process feature;
`ExtractAndQueue` runs only on the llama branch), and injection hygiene
covers both: every history write is neutered in `AppendTurn`, and the raw
`<initial message>` echo is neutered at its single placeholder fill site
(`PB_SAY_NEUTER_*`).

## Authored banter layer (M6)

`AiPlayerbot.LLMBanterEnabled` (default 1; the submenu's "Authored banter"
toggle rides both conf blocks) gates the free, non-generated speech the
dormant banter corpora now perform:

- **Trait seasoning** — `BuildPromptContext` gained two stable per-bot
  segments after the backstory (demeanor + habit from the GUID), so the
  model keeps the same voice the authored layer uses. This inserts a segment
  → `POCKETREALM_LLAMA_PROMPT_FORMAT_VERSION` bumped 3→4 (warm slots
  invalidated once).
- **Kill quips** — a new `Unit::Kill` credit-block hook (`CORE_UNIT_KILL_*`)
  calls `OnPlayerGroupKill` on the world thread: ~4% of real-player group
  kills even roll, one bot speaks at most, the party-wide stagger is 8 min,
  and every bot-initiated line claims a shared 15-min ambient slot. The line
  is rendered from `POOL_KILL` (with `{P}`/`{B}` names and the seeded verbal
  tic) at queue time and delivered by `UpdateAI` as an `authored`
  `EventReaction` — no model call anywhere.
- **Tier greetings** — a bare "hi"-class whisper (`IsSimpleGreeting`, ≤2
  words, ≤24 chars) from a player whose absence bucket is "most of a day" or
  "many days" gets the authored greeting pool matching the relationship
  tier instead of a generation.
- **Idle/mood lines** — `MaybeAmbientLine` (called from the widened
  `PB_UPDATEAI_*` block): master within 20 y, out of combat, 90 s per-bot
  cadence, 1-in-16 roll, 15-min shared slot; picks from POOL_IDLE + the five
  mood pools with the 1% wildcard bank, delivers to party/say, and records
  the line into the rolling history so prompts stay coherent.
- **Busy pool** — the governor-busy placeholder draws from POOL_BUSY via the
  recency ring (`PlayerbotLlmPersona::BusyReply`) instead of the old global
  counter rotation, falling back to the `LLMBusyReply` conf line.
- **Persona cells 2→12** — all twenty fallback cells grew an order of
  magnitude; selection was already ring-based, so the cells were the limit.

Expected cadence at full throttle: a couple of ambient lines per hour of
active adventuring, never more than one per bot per 15 min, never more than
one quip per party per 8 min.

## Safety rails (each traces to a measured failure)

| Rail | Protects against |
|---|---|
| `HexagonProbe` pre-flight before any NPU spawn | a failed HTP session open poisons the ggml backend registry and kills the whole server process, CPU mode included |
| MemAvailable gate: `file × 1.1 + 0.7 GB` | an unsatisfiable anon-for-DMA allocation kernel-panics the device (no LMK rescue) |
| `GGML_BACKEND_PATH` only when NPU is active; CPU fallback clears it | accidental backend load on a dead-DSP device (graceful skip only covers a missing driver, not a failed session) |
| SIGTERM only, never SIGKILL | killing a hybrid process mid-load leaks the DSP session; only a reboot clears it |
| 2 consecutive NPU load deaths → persisted block + CPU + Reset in UI | crash-looping against a wedged DSP |
| Fresh process per (re)start; model swap = service restart | DSP address-space slots are never reclaimed on munmap (32-bit VA, ~4 GB per session) |
| Supervisor generation token around fork/exec | a stop/new-config racing a start could orphan an untracked multi-GB child |
| Conf emitted only when enabled AND model staged | bots configured against an endpoint with no listener behind it |
| `PR_SET_PDEATHSIG(SIGTERM)` in the llmexec child | the `:llm` process dying (LMK) would otherwise leave an orphaned server holding the port past every restart, beyond Stop's reach |
| Loopback-only cleartext carve-out in the app network security config | the health probe is plain HTTP to 127.0.0.1; without it `healthy` is permanently false and every NPU exit is misattributed |
| Restart backoff resets only on proven health; PSI protective stops restart only once pressure clears | a 1 s fork/die churn re-extracting DSP skels each cycle, and reloads that worsen the pressure that triggered the kill |

### Death attribution (round 6 discipline)

An exit of a never-healthy NPU child is classified by three signals in
order of trust (`NpuDeathAttribution.classify`, the pure table the service's
`attributeNpuDeath` forwards to; a superseded generation's worker never
attributes at all):

1. **waitpid status.** Deliberate stops (our SIGTERM: stop/restart, the PSI
   watchdog, PDEATHSIG) take the server's graceful exit-0 path or die by
   signal 15 — never counted. Crash signals (ABRT/SEGV/BUS) count
   unconditionally: the poisoned-registry session abort dies on a null-deref
   before any log line lands. Pre-exec child failures (parent-gone/nice/
   affinity/exec = exits 124-127) are staging errors, not NPU failures.
2. **The `--log-file` tail.** Listener failures (`couldn't bind http server
   socket`, …) are startup collisions — never counted. ggml-hex error
   signatures (`failed to open session`, `failed to create device/session`,
   `failed to enable unsigned pd`, `unable to get domain struct`) are load
   deaths. An EMPTY tail also counts: `--log-file` is emitted before
   `--device` in argv, and parsing `--device` is what triggers the backend
   load — an NPU exec that never wrote a log line died inside backend init.
   Markers are specific error text, never bare `htp`/`hexagon`/`fastrpc`:
   benign INFO/WARN lines carry those substrings at default verbosity and
   would miscount bind collisions.
3. **Uptime** (< 15 s ⇒ collision) only when the tail is inconclusive.

Only the supervisor thread waitpids the live child — the PSI/stats watcher
threads check liveness via `/proc/<pid>/stat` (a waitpid from any thread
reaps, stealing the exit status the attribution needs).

## Files

- `android/pocketrealm-llm/` — vendored runtime module (unique-name jniLibs:
  `libllamaserver.so`, `libllama-server-impl.so`, `libmtmd.so`,
  `libggmlhex.so`; DSP skels as assets). The shared llama.cpp closure comes
  from the app's own staged jniLibs — byte-identical accelerated build,
  lockfile-verified. See `prebuilt/README.md` for the re-vendoring coupling.
- `android/app/src/main/java/com/pocketrealm/server/LlmRuntimePolicy.kt` —
  pure clamps + conf block (unit-tested).
- `android/app/src/main/java/com/pocketrealm/server/ServerRuntimeFiles.kt` —
  `llmConfigOverrides()` resolved at world start (one blocking multi-process
  DataStore read inside the transition gate).
- `android/app/src/main/java/com/pocketrealm/supervisor/AndroidRuntimeBackend.kt` —
  `ensureLlmRuntime(snapshot)` in the WORLD start path.
- `android/app/src/main/java/com/pocketrealm/storage/Settings.kt` —
  `llm*` keys (Snapshot + update() write-set + read normalization; default OFF).
- `android/app/src/main/java/com/pocketrealm/ui/LlmScreen.kt` — the submenu.

## Settings → runtime mapping

| Setting | Goes to |
|---|---|
| Enable | conf emission + supervisor start/stop |
| Compute mode | `LlmRuntimeConfig.computeMode` (service resolves Auto → NPU only when the probe is READY) |
| Decode cores | `cpuMaskHex` (child affinity — the decode knob; NPU prefill ignores it) |
| Decode threads | `-t` |
| NPU offload layers | `-ngl` (99 = all; files > ~2.9 GB should use partial offload; the Hexagon VA window is ~4 GB per session) |

Toggles persist immediately but apply on the next realm start (the conf is
generated at world start); "Start now" applies immediately.

## Pending on-device validation

1. ABI gate for the prebuilt backend vs the vendored server: run
   `--list-devices` WITH the app's env (`GGML_BACKEND_PATH` pointing at
   `libggmlhex.so`, `LD_LIBRARY_PATH` incl. `/vendor/lib64`,
   `ADSP_LIBRARY_PATH` incl. the extracted skels — via `run-as` on the
   debuggable bench app, with the `;` in ADSP_LIBRARY_PATH quoted inside
   the device-side script; a bare invocation never loads the renamed
   backend). It must list `HTP0`, then one real generation on the 0.8B
   model, before NPU mode is trusted (struct-layout drift cannot be
   proven statically). Also confirm on-device whether `/proc/net/tcp` is
   readable for the health-ownership gate — on Android 10+ it is
   SELinux-denied and the gate degrades (logged once) to probe-only,
   which accepts that a 200-answering local port squatter can flip
   `healthy` (bind-collision classification still keeps such exits
   uncounted; see the accepted residuals in the continuation plan).
2. Hybrid A/B against the reference numbers on the Qwen 0.8B-t Q4_0:
   decode ≈ 16.5 tok/s at 3×mid (0x38), 8.1 at 1×mid (cpu4); prefill
   ≈ 430-540 tok/s core-invariant. (The 5.0 @ 2×mid cell is a 4B-model
   reference; no 0.8B 2×mid cell exists yet.)
3. Memory-guard refusal path and the crash-block → CPU fallback → Reset
   flow (to force counted load failures: the E2B Q8_0 FORTIFY-crash
   model, or the arch-matching skel replaced by a NON-EMPTY directory in
   `filesDir/dsp` — an empty directory is silently deleted by
   re-extraction, and skel removal never reaches the counter at all).
4. End-to-end: enable the runtime in the submenu, start a realm with a bot
   profile, verify bot speech through the HTTP path (conf block above).

## Review convergence record (2026-08-29)

The capped 10-scope review protocol (continuation-plan-llm-submenu-v2.md
§2) is converged and closed:

- **Round 12** (first capped dispatch): 9/10 scopes at zero; scope 3
  confirmed one defect — the bench app's `isHealthy` derived value ignored
  the 4 s stats-freshness gate, so a SIGKILL'd `:llm` process (which sends
  no final ACTION_STATS) latched the Start button disabled and Chat/Bench
  actions enabled against a dead port. Fixed same session
  (`isHealthy = stats.running && stats.healthy && statsFresh`), plus a
  zero-risk JMM hardening flagged by three scopes as non-blocking (the
  ChatHttp deadline watchdog now disconnects through the `@Volatile conn`
  field instead of the captured local).
- **Round 13** (same 10 scopes re-dispatched): **all ten report
  `ISSUE COUNT: 0`** — convergence per the plan's rule. Coverage note for
  the record: round 13's scope 9 could not read the game-app tree, but
  every line changed since its round-12 full pass lives in the bench app
  (which it did review); the game-app surfaces it missed were re-reviewed
  green by scopes 2, 5, and 6 in the same round.

Rounds 12-13 also landed the four guard tests the plan required before
convergence could be declared, each behind a small behavior-identical
extraction: `ProcNetTcp.listenInodes` (pure /proc/net/tcp LISTEN-row
parser + fixture test, module, mirrored to both copies),
`ServerRuntimeFiles.llmOverrides` (pure tri-state conf gate + test),
`LlmRuntimePolicy.runtimeConfig` (single-source Snapshot→runtime-config
mapping used by BOTH the supervisor's pre-world-start launch and the
submenu's Start-now button, + tests), and `BoundedInputStream` cap tests
(new bench unit-test source set).

Final verification state: module unit tests 12/12 in BOTH copies
(NpuDeathAttributionTest 8 + ProcNetTcpTest 4); bench app 5/5; game app
970 tests with exactly the 5 known pre-existing addon failures
(AddonCatalogTest ×1, AndroidPortAssetTest ×4 — in-flight android-port
work, not this feature); both APKs and all four custom build types
assemble; lanes/impl-lib sha256/NSC/lane-leakage/config-cache/
check_repo/mirror-re-simulation verified green twice (10/10 checks,
rounds 12 and 13); module src byte-identical dev↔vendored.

Next step: Phase 3 device validation (the "Pending on-device validation"
list above; full runbook in the continuation plan §3) — user-gated.
