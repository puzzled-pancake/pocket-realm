# LLM companion — milestone notes (M1–M5)

Implementation status of the on-device LLM companion per the review-approved
plan. Everything ships behind `AiPlayerbot.LLM*` config keys. OFF is enforced
by the app always writing the keys explicitly (the base bot conf pins
`AiPlayerbot.LLMEnabled = 0`); note the native fallback default for a
missing key is ON (`PlayerbotAIConfig.cpp`), so no generated conf may omit
the key. `AiPlayerbot.LLMBackend = 0` = HTTP path unchanged.

## LLM runtime submenu (2026-08-28, :llm server path)

The Android app gained an opt-in "AI bot LLM" settings submenu that manages
the embedded llama-server runtime (`:pocketrealm-llm` module, fault-isolated
`:llm` process): compute placement (CPU vs Hexagon NPU hybrid, auto-detected
via HexagonProbe with a MemAvailable panic guard), decode-core selection,
HTP offload layers, model staging (reuses LlmModelCoordinator). When the user
enables it, `ServerRuntimeFiles.llmConfigOverrides()` appends the HTTP block
(`LLMEnabled = 2, LLMBackend = 0, LLMApiEndpoint …/v1/chat/completions` +
`LLMApiJson` chat template + content response pattern) after the reviewed
base conf, and the supervisor starts the runtime BEFORE the world AIDL start.
Disabled (the default) keeps `LLMEnabled = 0` and preserves the M1
debug-only in-process override for the adb-driven workflow. Conf emission
clamps live in `LlmRuntimePolicy` (tested) — the reviewed-keys contract
otherwise holds.

## Landed

- **M1 runtime**: vendored kai llama.cpp (native/llm/), in-process backend in
  PlayerbotLlamaRuntime (pinned mid-core worker, watchdog abort, warm slots,
  PROMPT_FORMAT_VERSION=2), Generate caller-identity routing, session-lifetime
  guards in Send/ReceiveDelayedPacket, Gradle closure+lockfile enforcement,
  HF-capable model downloader (LlmModelCoordinator + dataSync FGS).
- **M2 memory**: characters-DB tables (bot_backstory, bot_player_facts,
  bot_player_relationship) + world-DB world_gossip, tail-appended in the
  migration manifest; PlayerbotLlmMemory (deterministic backstory seed,
  append-only category-prioritized facts, tier/threshold relationship with
  staged traits, wall-clock absence buckets, gossip pool with expiry, journal);
  byte-stable segment builder wired into ChatReplyDo; the RpgSubActions
  `manual<N>` global context keys migrated to per-(bot,target) storage.
- **M3 gate/governor**: whisper-or-addressed-name hard-trigger gate;
  mutex-guarded rolling per-bot/global duty-cycle governor inside
  PlayerbotLLMInterface::Generate (silent skip for autonomous chatter, busy
  placeholder line for player-facing paths); level-up + rare-loot core hooks
  (Player::GiveLevel, HandleAutostoreLootItemOpcode) feeding bounded party
  reactions; periodic active-party slot pre-warm in PlayerbotAI::UpdateAI.
- **M4 tools**: `<<tool ...>>` marker protocol (cannot collide with the `*`/
  `[` emote-line routing), extracted from raw output BEFORE ParseResponse,
  hardcoded instructions appended to the pre-prompt, world-thread executor in
  UpdateAI with execution-time validation; share_gossip gated to the verified
  -event window; adjust_sentiment clamped + rate-limited feeding the tier
  accumulator; perform_emote on a fixed 1.12 emote whitelist.
- **M5 companion mode**: native pause primitive in world_runnable.cpp (timer
  keeps ticking so resume sees one normal diff), pauseWorldNative/
  setCompanionModeNative JNI, IWorldControl.setWorldPaused/setCompanionMode
  verbs, journaled RUNNING<->PAUSED transition in DurableRuntimeSupervisor +
  RuntimePhase.PAUSED wired through RealmService/RuntimeSupervisorClient,
  admission monitor suspended while paused, llama runtime reloads between
  coexistence (mmap) and companion (full residency) profiles between
  generations; persona fallback library (4 archetypes x vouch/refuse/
  de-escalate) with keyword classifier ahead of generation; journal surface
  via whispering "journal" to a bot.

## Pre-ship gates still open (manual, per plan)

1. **Model descriptor verification**: LlmModelCoordinator.primaryModel's
   size/sha256 are placeholders — pin them from the actual Unsloth GGUF
   before shipping the download UI.
2. **Non-English consistency pass** (language-practice mode): the gauntlet
   that justified E2B ran in English only.
3. **Sustained busy-party thermal session** against the 77°C mid-core ceiling
   to tune LLMGovernorBotMax/GlobalMax.
4. **On-device coexistence bench** reusing `G:\NPU llm\scripts\coexist-test-e2b.sh`
   with LLMBackend=1 enabled.
5. **UI surfaces**: the sit-and-talk screen and settings toggle call
   IWorldControl.setCompanionMode / LlmModelDownloadService.start; the
   service/supervisor plumbing is complete, presentation is not.
