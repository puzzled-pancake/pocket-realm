# AI bot LLM — every setting, explained

This is the plain-language reference for every knob in the **Settings →
AI bot LLM** submenu, the **Bots → (preset) → AI tab** overrides, and the
`AiPlayerbot.LLM*` conf keys behind them. Defaults are what the app emits;
the conf keys that the app never writes are marked **conf-only** and keep
their native defaults.

The lane is opt-in. With the master switch off, the LLM runtime never
starts and the bots fall back to their fully scripted lines — the authored
banter layer is gated by the same switch (the app's own copy: "Off keeps
bots silent").

---

## 1. Settings → AI bot LLM (the submenu)

The compute/generation/pack/connection cards below live behind the
submenu's **Advanced engine settings** disclosure.

### Master and source

| Setting | What it does |
|---|---|
| **Let bots talk with an AI** (Settings card, behind the **Experimental AI bot chat** gate switch; the submenu's own switch is "AI bot LLM speech") (`llmEnabled`) | The master switch. It only appears after the card's experimental gate is on. When ON, the app appends the LLM conf block to the bot profile at world start (`AiPlayerbot.LLMEnabled = 2`) and the supervisor starts the `:llm` process before the world claims RAM. When OFF the base conf's `LLMEnabled = 0` wins and bots stay scripted. Nothing applies mid-session — flip, then (re)start the realm, or use *Start now* for the runtime. |
| **Source: on-device / external** (`llmExternalMode`) | On-device serves OpenAI-style requests from llama-server in a fault-isolated `:llm` process on `127.0.0.1:8080` (`LLMBackend = 0`). External points the same request path at any OpenAI-compatible HTTP(S) endpoint. Switching sources stops/starts the `:llm` process to match. |
| **Cloud conversation** (`llmCloudChatter`, external only) | Widens the conversation lanes on a provider-safe external endpoint with ≥65,536 ctx: street `/say` reactions and the shared world-chatter budget pool open. Unaddressed party replies have a separate arm that ships OFF today (`LLMPartyReplyEnabled = 0`); addressed party lines always trigger on both lanes. Gated by the conjunction law — the tier must be active, so this can never widen the on-device lane. Quotas below bound it. |

### Model and runtime

| Setting | What it does |
|---|---|
| **Bot brain model** (`llmModelId`) | Which GGUF the `:llm` process loads. The selection also pins the whole tier profile (context, sampling, governor budgets, memory depth — see §4). Tuned models are local-only today (staged by hand to `filesDir/models/`); the untuned base tier is downloadable in-app. A hand-staged file passes the existence gate only — the sha256 pin is enforced on the in-app download path. |
| **Start now / Stop** | Applies the runtime config immediately instead of waiting for the next realm start. Note the conf side (sampling, budgets, prompt pack) still re-reads only at world start, so a mid-session model swap is half-applied until the next realm restart. |
| **Compute mode** (`llmComputeMode`: Auto / CPU / NPU) | Auto picks the Hexagon NPU hybrid when the probe reports READY and no crash-block persists; CPU pins mid cores. NPU load deaths ×2 persist a block and fall back to CPU; *Reset NPU* clears it. |
| **Decode cores** (`llmCoresMask`) | Affinity mask for decode threads (default cores 3-5, the fast mid cluster on the RP6). Runtime-only — it never enters the conf. |
| **Decode threads** (`llmThreads`, conf `-t`) | llama-server decode thread count. More threads ≠ faster past the fast cluster; the tier default is tuned. |
| **NPU offload layers** (`llmOffloadLayers`, conf `-ngl`) | How many model layers prefill on the Hexagon HTP. Default is the vendor-style "all layers" value with three manual steps; wrong values degrade or crash the HTP path (which is why the crash-block exists). |

### Replies and budgets

| Setting | What it does |
|---|---|
| **Reply length** (`llmMaxNewTokens`) | `max_tokens` per generation (tier default 230 on E2B — sized for the trained long-form bank; 210 on the 0.8B tier, which deliberately does **not** license long-form). The delivered reply is further budgeted per class: whispers/say get at most two 160-byte lines, ambient one 80-byte line, and only a turn whose own game-note carried the long-form cue widens to the 3-4 line budget. |
| **Generation timeout** (`llmGenerationTimeout`) | Queue-inclusive wait for one generation (45-60 s per tier). Hitting it counts as a hard failure: after two consecutive transport failures the player gets a one-time system-styled notice that bot voices are offline (re-armed when a generation succeeds again). |
| **Authored banter** (`llmBanter`) | The zero-generation greet/kill-quip/initiative layer. OFF keeps the bots reply-only (and keeps the preemptive authored greeting + persona beats from firing on the on-device lane). |
| **World chatter (beta)** (`llmAmbience`) | Stages the chatter power file; the native murmur/party-banter/global-set-piece scheduler reads it every tick. Off clears the queue. Ambient work always yields the interactive lane (it never queues behind your whisper). |

### External endpoint (when Source = external)

| Setting | What it does |
|---|---|
| **Endpoint URL** (`llmExternalUrl`) | Any OpenAI-compatible `/v1/chat/completions` endpoint. HTTPS gets a TLS 1.2 floor, verified chains against the app-staged Mozilla bundle, and hostname pinning (`LLMTLSVerify = 1` default; 0 restores an unverified handshake for self-signed LAN endpoints — conf-only). |
| **Model name** (`llmExternalModel`) | The `model` string in the request body. |
| **API key** (`llmExternalApiKey`) | Sent as `Authorization: Bearer …`. Never echoed — debug output redacts it. The "cloud tier" that the cloud-lane widenings require is provider-safe mode + ≥65,536 ctx context plus the Cloud conversation toggle — the key is not itself part of the gate (a keyless LAN endpoint can open it). |
| **Prompt pack (advanced)** (`llmPromptPackJson`) | The pack editor: eleven trained-contract rows (read-only — they are rendered natively from the frozen constants; toggling or editing them does nothing, and the UI now says so) plus ten seasoning rules you can enable, edit, and reorder. The meter reads the **seasoning tail only** (trained placeholders would be fiction) and shows the joined size against the **native 1,200-byte cap** — over the cap the renderer silently cuts the END of the joined text — the last-ordered blocks are the ones dropped. Import reports any bodies that fell back to defaults at resolve. Quotes and backslashes are legal in bodies; control characters are not. |

### Whisper keywords (player surface, zero generation cost)

Whisper a bot: **`journal`** (its fact ledger as readable diary lines),
**`standing`** (the relationship tier one-liner), **`gossip`** (what the
town is saying about you), **`notice`** (the live scene read), **`story`**
(the campfire saga codex). These are English literals by design and award
no relationship points.

---

## 2. Bots → (preset) → AI tab

| Setting | Conf key | What it does |
|---|---|---|
| **Reply length** | `LLMMaxNewTokens` | Per-preset override of the submenu's reply length. |
| **Bots speak on their own** | *(not an LLM key — `RandomBotSayWithoutMaster`)* | Gates the SCRIPTED initiative chatter. The LLM trigger law is separate: any whisper, name-addressed `/say`, addressed party/raid always can trigger a generation. |
| **Bot-to-bot chance** | `LLMBotToBotChatChance` | Percent of eligible bot-to-bot encounters that generate (trained tier: 10 on E2B, 0 on the 0.8B tier). Bot-authored lines never trigger a generation themselves. |
| **Facts cap** | `LLMFactsCap` | System-prompt fact window (12/8 per tier). Identity rows, the told secret, and debt rows are **pinned**: they survive the cap while recent trivia ages out. |
| **Memories tail** | `LLMMemoriesTail` | The `[Memories]` recap of the newest facts on the user turn (trained recall cue). |
| **Pack block deltas** | `AiPlayerbot.LLMPromptBlock.<id>` | Per-preset on/off of each seasoning block (preset > global pack). Trained ids are inert. |
| **RP dials: Initiative** | `LLMRpInitiative` | How eagerly the bot opens conversations; scales the authored-initiative floor (20 min at 0, 10 min at 50, 5 min at 100). Note 0 does not mute initiative entirely — it sets the longest floor. **Live** — it rides the preset conf and applies at the next realm start. |
| **RP dials: Volatility** | `LLMRpVolatility` | Mood-weather weight (0.5×-1.5×): how fast the bot's mood shifts under grudge/smitten/grief nudges. |
| **RP dials: Reactivity** | `LLMRpReactivity` | Event-shortcut eagerness. ≤25 strips the licensed emote/sentiment extras from event turns (quiet bots still remember — they just cheer less). |
| **RP dials: Long form** | `LLMRpLongForm` | Long-form license. 100 lowers the token bar to 150 (storytelling can fire at the tier's normal budget); 0 raises it to 300. The cue still rides only turns whose own game-note carries it. |

---

## 3. Conf keys beyond the submenu

Grouped by what they actually control. **Defaults shown are the shipped
behavior** — the app-pinned value where the app writes the key, the native
fallback where it does not. A few keys in the runtime group ARE app-written
(marked); the rest are hand-edit-only.

**Identity and lane**
- `LLMEnabled` (native fallback 1; the app's base conf pins 0 and the active block writes 2) — 2 = an app-managed lane.
- `LLMBackend` (0) — 0 HTTP (embedded server or external), 1 in-process llama (debug builds only).
- `LLMPromptFormat` (native fallback 0; the app writes 1) — 1 = the native trained prompt builder; 0 = legacy conf templates.
- `LLMProviderSafe` (0) — strips llama.cpp-only body keys for strict endpoints.
- `LLMGlobalContext` (0) — share one rolling context across bots (legacy lane).

**Trigger and reply law**
- `LLMBlockedReplyChannels` (empty) — extra channel deny-set.
- `LLMRpgAIChatChance` (100) — autonomous RPG chat admission percent.
- `LLMDialogueFastLane` (1) — the per-zone dialogue occupancy fast lane.
- `LLMBusyReply` (authored line) — fallback busy text if the recency-ring draw fails.
- `LLMToolsEnabled` (1) — **real kill switch for tool execution**: 0 strips tool markers from prose and never executes a call (the extractor, queue and executor all gate on it; the TOOLS_NOTE text still ships in the trained system prompt).
- `LLMEraBias` (1) — the era logit-bias token set; 0 disables only the bias, the era reply backstop still runs on the HTTP lane.
- `LLMRpgPrompt`, `LLMPrePrompt`, `LLMPrompt`, `LLMPostPrompt` — legacy-template fills (trained lane ignores them).

**Response shaping (legacy/raw paths)**
- `LLMResponseStart/End/Delete/SplitPattern` — legacy extraction regexes. An invalid pattern is now **cleared at load** (logged, pass-through) instead of being left armed to throw inside the generation worker.
- `LLMResponseStartPattern` default `("text":\s*")` mutes the lane on a stock conf — the app writes empty patterns; don't hand-edit without knowing this.

**Sampling**
- `LLMTemp` (0.8), `LLMTopK` (64), `LLMTopP` (0.95), `LLMRepeatPenalty` (1.1), `LLMMinP` (0), `LLMPresencePenalty` (0) — native fallbacks; the app's tier profile pins Gemma-correct values (repeat penalty 1.0 is load-bearing).
- `LLMThinkingKwargs` — emits `enable_thinking:false` for thinking-default model families (Qwen).
- `LLMMaxSimultaniousGenerations` (native fallback 100; the app pins 2 on-device tiers, 4 external) — the in-flight cap. Note the misspelling is the shipped key name.
- `LLMConnectTimeout` (10 s app-pinned) — bounded TCP connect.

**Duty cycle (the governor)**
- `LLMGovernorWindow` (60 s), `LLMGovernorBotMax` (8), `LLMGovernorGlobalMax` (native fallback 24; the tiers pin 8 / 20 / 8, the external tier 48) — admissions per bot/window and globally. Every HTTP call in a turn pays admission — first try, leak regenerations, dedupe resample, empty-content retry — so the budget counts requests, not turns.

**Prompt furniture**
- `LLMContextLength` (12288 on-device; 131072 external) — a **character** window for the legacy context builder, not tokens — and also the cloud-tier gate operand (≥65536 required).
- `LLMPromptPackFile`, `LLMDefaultPromptsFile`, `LLMLoreFile` — staged file paths (pack JSON, character-card fills, lore cards). Lore cards are era-linted at load; contaminated cards are dropped and logged.
- `LLMPromptDumpFile` — request dump for debugging (never ships by default).
- `LLMPromptBlock.<id>` — global pack overrides (see §2).
- `LLMMoodSeasoning` (1) — the per-bot mood/weather line in the system prompt; 0 silences it. Conf-only by design (default is the shipped behavior).

**Memory, deeds, quotas**
- `LLMHistoryPersist` (1), `LLMGreetMemory` (1), `LLMPartyDigestPerDay` (6) — persistence and digest switches.
- `LLMTurnAwardDailyCap` (20), `LLMTurnAwardWeighting` (1), `LLMDeedPoints*` (trade 3 / quest 4 / kill 2 / first visit 5) — relationship-point economics; a deed of 0 disables only the award, never the fact.
- `LLMCloudChatter` (1), `LLMPartyReplyEnabled` (0), `LLMCloudStreetSayPct` (25), `LLMStreetSayPerDay` (200), `LLMRpgChatPerDay` (300), `LLMBotToBotPerDay` (300), `LLMCloudLineBudgetPerHour` (90), `LLMCloudInteractivePerPlayerHour` (240) — the cloud-lane widenings and their quotas.
- `LLMRecap*`, `LLMSaga*`, `LLMRoundtablePerDay`, `LLMDossier*`, `LLMDramaEnabled`, `LLMCuriosityEnabled`, `LLMSceneReadEnabled`, `LLMGrudgeRefusalEnabled`, `LLMEventReactionsEnabled`, `LLMAuthoredLinesPerHour` (8) — the authored engagement layer (all default-on, fail-open, conf-internal by design).
- `LLMWorldTruthAmbient` (1), `LLMWorldTruthFurniture` (0) — scene/homeland prompt furniture (furniture defaults off).
- `LLMChatter*` (Url/Model/Key/Enabled/PowerFile) — the composer/street endpoints; smuggled from the external fields on the external lane.
- `LLMModelPath`, `LLMCtxSize`, `LLMSlots`, `LLMCpuFirstCore` — runtime placement (the app writes these; the debug in-process lane pins its own).

---

## 4. The tier profiles (what the model choice changes)

| Tier | Ctx | Sampling (temp / topP / topK) | Timeout | Slots | Governor | Facts/Tail | B2B |
|---|---|---|---|---|---|---|---|
| Tuned E2B (default) | 12288 | 0.7 / 0.8 / 20, rep 1.0 | 60 s | 2 | 8/bot/min, 8 global/min | 12 / 6 | 10 % |
| Tuned Qwen 0.8B | 6144 | 0.5 / 0.8 / 20, rep 1.0 | 45 s | 2 | 8/bot/min, 20 global/min | 8 / 4 | 0 % |
| Base E2B (untuned, downloadable) | 12288 | vendor-leaning profile | 60 s | 2 | 8 / 8 | 12 / 6 | 0 % |

Long-form is not licensed by the 0.8B tier's 210-token budget at the default bar — but a long-form dial ≥75 lowers the bar to 150 tokens, which that tier can pass; the "never" holds only at the default 225-token bar.
Decode speed varies by model and quant; the reference measurement (~16.5 tok/s, Qwen 0.8B on the mid cores, pending on-device validation) puts a full 230-token reply at roughly a quarter of a minute worst-case. The conversational budget (two 160-byte lines) usually finishes much sooner, the typing pace keeps a 400 ms per-line floor, and the first line lands after a 0.4-1.2 s reaction beat.

## 5. Failure behavior (what you see when it breaks)

- **Governor busy** → an in-character one-liner from the per-bot busy ring ("Hm. I will be thinking on that a while."-class), instantly.
- **Transport failure (timeout / HTTP error / dead endpoint)** → the bot stays silent; after two consecutive hard failures you get ONE system-styled notice naming Settings → AI bot LLM, re-armed after recovery.
- **Leak/era guard fires** (HTTP lane) → up to two silent regenerations, then an in-character deflection. The on-device debug lane now shares the era/marker rejection with a deflection.
- **Model says nothing voicable** → silence (not an outage; never trips the notice).
- **`:llm` process death** → sticky restart with backoff; NPU crashes ×2 persist a block and fall back to CPU.
