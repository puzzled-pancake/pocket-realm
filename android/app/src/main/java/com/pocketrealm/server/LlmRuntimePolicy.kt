package com.pocketrealm.server

import android.content.Context
import com.pocketrealm.bots.BotLlmSpeech
import com.pocketrealm.llm.ComputeMode
import com.pocketrealm.llm.LlmModelRegistry
import com.pocketrealm.llm.LlmSamplingProfile
import com.pocketrealm.llm.LlmTierProfile
import com.pocketrealm.llm.LlmRuntimeConfig
import com.pocketrealm.storage.Settings
import java.io.File

/**
 * Pure derivation of the playerbot LLM configuration from user settings.
 *
 * The base bot profile conf (BotProfile.playerbotConfig) keeps
 * `AiPlayerbot.LLMEnabled = 0`; [confBlock] is APPENDED after it and only when
 * the user enabled the LLM runtime from the LLM submenu, so the default stays
 * off until explicit opt-in (every LLM knob ships behind an
 * AiPlayerbot.LLM* key, all defaulting OFF).
 *
 * Backend choice: `LLMBackend = 0` (HTTP) against the embedded llama-server in
 * the :llm process. The vendored C++ client POSTs `LLMApiJson` verbatim as the
 * request body with the `<pre prompt>/<context>/<prompt>/<post prompt>` fill
 * keys replaced (SayAction.cpp). llama-server answers `/v1/chat/completions`
 * with an OpenAI envelope, which the client parses as JSON: the reply is
 * `choices[0].message.content`, decoded with full string semantics, with one
 * direct-answer retry when the base model burns the budget on a thinking
 * preamble, and a truncation-aware line splitter when `finish_reason` is
 * `length`. The response pattern keys are emitted empty (the regex path stays
 * in native code only as the fallback for non-OpenAI response shapes — see
 * [confBlock]'s rationale).
 *
 * Values clamp through [normalizeThreads] / [normalizeOffloadLayers] /
 * [normalizeCoresMask] — the same discipline as NearbyInteractPolicy. Threads
 * and the core mask do NOT appear in the conf: they configure the :llm process
 * affinity directly (LlmRuntimeConfig), which is what actually controls
 * decode-core placement.
 */
internal object LlmRuntimePolicy {

    /** llama-server inside pocketrealm binds loopback on this fixed port. */
    const val DEFAULT_PORT = 8080

    /** Bot-to-bot chance on the cloud lane (player-visible life
     * outweighs background chat the other way). */
    const val CLOUD_LANE_BOT_TO_BOT_CHANCE = 25

    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    /** Decodes pinned to the mid cluster (cores 3-5): the coexistence profile. */
    const val DEFAULT_CORES_MASK = 0x38L

    const val DEFAULT_THREADS = 3

    const val DEFAULT_OFFLOAD_LAYERS = 99

    const val MIN_THREADS = 1
    const val MAX_THREADS = 8

    /** -ngl: 99 = all layers (0.8B/2B-class files); >4 GB files need partial offload. */
    const val MIN_OFFLOAD_LAYERS = 1
    const val MAX_OFFLOAD_LAYERS = 128

    /**
     * Advanced-tier generation overrides. 0 means "follow the model's
     * sampling profile / tier profile"; anything above 0 is clamped into the
     * supported band. Reply length bounds every bot generation
     * (AiPlayerbot.LLMMaxNewTokens, also interpolated into the legacy
     * LLMApiJson body); the timeout bounds the queue-inclusive generation
     * wait (AiPlayerbot.LLMGenerationTimeout).
     */
    const val MIN_MAX_NEW_TOKENS = 24
    const val MAX_MAX_NEW_TOKENS = 600
    const val MIN_GENERATION_TIMEOUT_SEC = 15
    const val MAX_GENERATION_TIMEOUT_SEC = 240

    fun normalizeMaxNewTokensOverride(tokens: Int): Int =
        if (tokens <= 0) 0 else tokens.coerceIn(MIN_MAX_NEW_TOKENS, MAX_MAX_NEW_TOKENS)

    fun normalizeGenerationTimeoutOverride(seconds: Int): Int =
        if (seconds <= 0) 0 else seconds.coerceIn(MIN_GENERATION_TIMEOUT_SEC, MAX_GENERATION_TIMEOUT_SEC)

    /** Path appended to a bare external origin (no path component). */
    const val EXTERNAL_CHAT_COMPLETIONS_PATH = "/v1/chat/completions"

    /** Model name emitted when the external-mode model field is left empty. */
    const val DEFAULT_EXTERNAL_MODEL = "local"

    val DEFAULT_COMPUTE_MODE = ComputeMode.AUTO

    fun normalizeCoresMask(mask: Long): Long = if (mask == 0L) DEFAULT_CORES_MASK else mask

    fun normalizeThreads(threads: Int): Int = threads.coerceIn(MIN_THREADS, MAX_THREADS)

    fun normalizeOffloadLayers(layers: Int): Int =
        layers.coerceIn(MIN_OFFLOAD_LAYERS, MAX_OFFLOAD_LAYERS)

    /**
     * The user-supplied external endpoint, normalized for conf emission, or
     * null when it is unusable (fail-closed: [ServerRuntimeFiles.llmOverrides]
     * then emits no LLM block at all, exactly like a missing local model).
     * Rejected: anything without an http(s) scheme, any whitespace, quotes or
     * backslash — quotes would be eaten by the native conf parser's
     * leading/trailing trim and a backslash has no safe conf encoding here.
     * A bare origin (no path) gets [EXTERNAL_CHAT_COMPLETIONS_PATH] appended,
     * so `https://api.openai.com` just works; an explicit path is kept as-is
     * (proxies, `/ollama/v1`, custom gateway routes).
     */
    fun normalizeExternalEndpoint(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        if (trimmed.length > 512) return null
        if (trimmed.any { it.isWhitespace() || it == '"' || it == '\\' || it.isISOControl() }) {
            return null
        }
        val rest = trimmed.substringAfter("://")
        val authority = rest.substringBefore('/')
        if (authority.isEmpty()) return null
        // An explicit port must be a real port (1..65535). The
        // native parseUrl's std::stoi throws out_of_range on huge port
        // literals; the native catch fails the endpoint closed instead of
        // aborting world boot, and bounding here keeps such a value out
        // of the UI at all.
        if (authority.contains(':')) {
            val port = authority.substringAfterLast(':')
            val asInt = port.toIntOrNull() ?: return null
            if (asInt < 1 || asInt > MAX_PORT) return null
            // host part before the port separator must be non-empty
            if (authority.dropLast(port.length + 1).isEmpty()) return null
        }
        return if (rest.contains('/')) trimmed else "$trimmed$EXTERNAL_CHAT_COMPLETIONS_PATH"
    }

    /**
     * The external request model name, conf/JSON safe, or null when the value
     * cannot be embedded in the LLMApiJson body (quotes/backslashes would
     * corrupt the JSON envelope). Empty normalizes to [DEFAULT_EXTERNAL_MODEL]
     * (endpoints that ignore the model field accept it unchanged).
     */
    fun normalizeExternalModel(model: String): String? {
        val trimmed = model.trim()
        if (trimmed.isEmpty()) return DEFAULT_EXTERNAL_MODEL
        if (trimmed.length > 128) return null
        if (trimmed.any { it.isWhitespace() || it == '"' || it == '\\' || it.isISOControl() }) {
            return null
        }
        return trimmed
    }

    /**
     * The external API key, or null when the value cannot be emitted (a quote
     * would be eaten by the conf parser's trim; whitespace/control characters
     * are never valid key content). Empty means no Authorization header.
     */
    fun normalizeExternalApiKey(key: String): String? {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.length > 512) return null
        if (trimmed.any { it.isWhitespace() || it == '"' || it == '\\' || it.isISOControl() }) {
            return null
        }
        return trimmed
    }

    /**
     * The appended aiplayerbot.conf block, or null when the runtime is off.
     * Computed at world start: toggles take effect on the next realm start.
     *
     * The response start/end pattern keys are emitted EMPTY: the native
     * client parses the OpenAI chat-completions envelope as JSON and
     * decodes `choices[0].message.content` with full string semantics, so
     * the regex extraction patterns are dead on every endpoint this block
     * can configure. The keys must still be written (empty) because the
     * native defaults would otherwise apply — the default start
     * pattern (`("text":\s*")`) never matches decoded prose (total
     * silence), and the default end pattern's first alternative `(")`
     * truncates the reply at the first quote (escaped quotes in decoded
     * prose would truncate it early).
     * Endpoints that return non-OpenAI text shapes keep the regex fallback
     * in native code with whatever patterns a hand-edited conf supplies.
     */
    fun confBlock(
        llmEnabled: Boolean,
        port: Int = DEFAULT_PORT,
        banterEnabled: Boolean = true,
        profile: LlmSamplingProfile = LlmModelRegistry.TUNED_E2B.profile,
        tier: LlmTierProfile = LlmModelRegistry.TUNED_E2B.tierProfile,
        loreFile: String? = null,
        chatterPowerFile: String? = null,
        composerEndpoint: String? = null,
        composerModel: String? = null,
        composerApiKey: String? = null,
        maxNewTokensOverride: Int = 0,
        generationTimeoutOverride: Int = 0,
        speech: BotLlmSpeech = BotLlmSpeech(),
        promptPackFile: String? = null,
        defaultPromptsFile: String? = null,
        tlsCaFile: String? = null,
    ): String? {
        if (!llmEnabled) return null
        require(port in MIN_PORT..MAX_PORT) { "llm port out of range: $port" }
        return confLines(
            endpoint = "http://127.0.0.1:$port/v1/chat/completions",
            model = "local",
            apiKey = "",
            banterEnabled = banterEnabled,
            profile = profile,
            tier = tier,
            providerSafe = false,
            loreFile = loreFile,
            chatterPowerFile = chatterPowerFile,
            composerEndpoint = composerEndpoint,
            composerModel = composerModel,
            composerApiKey = composerApiKey,
            maxNewTokensOverride = maxNewTokensOverride,
            generationTimeoutOverride = generationTimeoutOverride,
            speech = speech,
            promptPackFile = promptPackFile,
            defaultPromptsFile = defaultPromptsFile,
            tlsCaFile = tlsCaFile,
        )
    }

    /**
     * The appended block for external-endpoint mode: the realm talks to any
     * OpenAI-compatible `/v1/chat/completions` service instead of the
     * embedded llama-server. [endpoint] must already be normalized
     * ([normalizeExternalEndpoint]); [model] likewise
     * ([normalizeExternalModel]) and is interpolated into the LLMApiJson
     * body — both were validated conf/JSON-safe by their normalizers, so raw
     * interpolation is safe here. [apiKey] empty emits no key line; the
     * native client sends it as an `Authorization: Bearer` header
     * (PlayerbotLLMInterface.GenerateHttp). Any null input means "no block".
     */
    fun confBlockExternal(
        endpoint: String?,
        model: String?,
        apiKey: String?,
        banterEnabled: Boolean = true,
        loreFile: String? = null,
        chatterPowerFile: String? = null,
        composerEndpoint: String? = null,
        composerModel: String? = null,
        composerApiKey: String? = null,
        maxNewTokensOverride: Int = 0,
        generationTimeoutOverride: Int = 0,
        speech: BotLlmSpeech = BotLlmSpeech(),
        promptPackFile: String? = null,
        defaultPromptsFile: String? = null,
        tlsCaFile: String? = null,
        cloudLane: CloudLaneConf = CloudLaneConf(),
        logLines: Boolean = false,
    ): String? {
        if (endpoint == null || model == null || apiKey == null) return null
        return confLines(
            endpoint = endpoint,
            model = model,
            apiKey = apiKey,
            banterEnabled = banterEnabled,
            profile = EXTERNAL_PROFILE,
            tier = EXTERNAL_TIER,
            providerSafe = true,
            loreFile = loreFile,
            chatterPowerFile = chatterPowerFile,
            composerEndpoint = composerEndpoint,
            composerModel = composerModel,
            composerApiKey = composerApiKey,
            maxNewTokensOverride = maxNewTokensOverride,
            generationTimeoutOverride = generationTimeoutOverride,
            speech = speech,
            promptPackFile = promptPackFile,
            defaultPromptsFile = defaultPromptsFile,
            tlsCaFile = tlsCaFile,
            cloudLane = cloudLane,
            logLines = logLines,
        )
    }

    /**
     * Off-device endpoints get their own budget, not a device profile.
     * API-class models (1M-ctx generation) get room to actually use it:
     * 600-token replies for long-form tellings, deeper memory, longer
     * history. On-device tiers stay small (KV RAM is the binding
     * constraint there); off-device there is no KV constraint.
     */
    val EXTERNAL_PROFILE = LlmSamplingProfile(
        temperature = 0.7, topP = 0.9, topK = 20,
        repeatPenalty = 1.0, maxTokens = 600,
    )

    /** External-endpoint runtime knobs (16/bot, 48 global; 60s generation; ctx 128k). */
    val EXTERNAL_TIER = LlmTierProfile(
        contextLength = 131072, generationTimeoutSec = 60,
        maxSimultaneousGenerations = 4,
        governorBotMax = 16, governorGlobalMax = 48,
        factsCap = 48, memoriesTail = 16,
        botToBotChatChance = 10,
    )

    /**
     * The advanced-tier overrides folded onto the measured profiles: 0 keeps
     * the model's sampling/tier value, anything above 0 is clamped into the
     * supported band. Returns the effective sampling profile (reply length
     * replaced) paired with the effective generation timeout.
     */
    private fun effectiveGeneration(
        profile: LlmSamplingProfile,
        tier: LlmTierProfile,
        maxNewTokensOverride: Int,
        generationTimeoutOverride: Int,
    ): Pair<LlmSamplingProfile, Int> {
        val effectiveProfile =
            if (maxNewTokensOverride > 0) {
                profile.copy(maxTokens = normalizeMaxNewTokensOverride(maxNewTokensOverride))
            } else {
                profile
            }
        val timeout =
            if (generationTimeoutOverride > 0) {
                normalizeGenerationTimeoutOverride(generationTimeoutOverride)
            } else {
                tier.generationTimeoutSec
            }
        return effectiveProfile to timeout
    }

    /**
     * The shared block body: identical envelope for both sources, so the
     * merge-order contract (append wins over the base conf, patterns
     * trim-proof, `stream:false` tail) holds for embedded and external alike.
     *
     * [promptPackFile] stages the prompt pack (ordered blocks +
     * enabled flags) for the native renderer. Empty pack = trained default
     * output, byte-identical (the frozen-output test pins this); the native
     * side appends only the enabled seasoning blocks inside the existing
     * instruction span, never a new top-level segment.
     *
     * [defaultPromptsFile] names the staged EMPTY default-prompts file by
     * absolute path. The native default is the bare relative name
     * `llm_character_card`, which the loader resolves against CWD (never
     * the run dir) and reports as "not found or unreadable" - the absolute
     * empty file keeps the fail-open prompts minus that startup line.
     */
    private fun confLines(
        endpoint: String,
        model: String,
        apiKey: String,
        banterEnabled: Boolean,
        profile: LlmSamplingProfile,
        tier: LlmTierProfile,
        providerSafe: Boolean,
        loreFile: String? = null,
        chatterPowerFile: String? = null,
        composerEndpoint: String? = null,
        composerModel: String? = null,
        composerApiKey: String? = null,
        maxNewTokensOverride: Int = 0,
        generationTimeoutOverride: Int = 0,
        speech: BotLlmSpeech = BotLlmSpeech(),
        promptPackFile: String? = null,
        defaultPromptsFile: String? = null,
        tlsCaFile: String? = null,
        cloudLane: CloudLaneConf = CloudLaneConf(),
        logLines: Boolean = false,
    ): String {
        // Advanced-tier overrides folded onto the measured profiles (0 keeps
        // the model/tier value); one effective profile feeds both the conf
        // keys and the legacy JSON template, so the two prompt-format paths
        // can never disagree. The per-preset speech override (Bots → AI)
        // outranks the global advanced-tier override, which outranks the
        // model's tuned profile.
        val replyTokensOverride =
            if (speech.replyTokens > 0) speech.replyTokens else maxNewTokensOverride
        val (effectiveProfile, generationTimeout) = effectiveGeneration(
            profile, tier, replyTokensOverride, generationTimeoutOverride,
        )
        // -1 = follow the model tier; an explicit preset 0 (off) equals the
        // native default, so only a positive value emits the line.
        // Bot-to-bot chat: the cloud lane emits 25 when the Cloud
        // conversation toggle is on (more life), the device/default lanes
        // keep the tier's value - an explicit preset override always wins.
        val botToBotChance =
            if (speech.botToBotChatChance >= 0) speech.botToBotChatChance
            else if (cloudLane.cloudChatter) CLOUD_LANE_BOT_TO_BOT_CHANCE
            else tier.botToBotChatChance
        val factsCap = if (speech.factsCap > 0) speech.factsCap else tier.factsCap
        val memoriesTail = if (speech.memoriesTail > 0) speech.memoriesTail else tier.memoriesTail
        val keyLine =
            if (apiKey.isEmpty()) ""
            else "\n            AiPlayerbot.LLMApiKey = $apiKey"
        val minPLine =
            if (profile.minP > 0.0) "\n            AiPlayerbot.LLMMinP = ${jsonNumber(profile.minP)}"
            else ""
        val presenceLine =
            if (profile.presencePenalty > 0.0) {
                "\n            AiPlayerbot.LLMPresencePenalty = ${jsonNumber(profile.presencePenalty)}"
            } else ""
        val thinkingLine =
            if (tier.thinkingKwargs) "\n            AiPlayerbot.LLMThinkingKwargs = 1" else ""
        val providerSafeLine =
            if (providerSafe) "\n            AiPlayerbot.LLMProviderSafe = 1" else ""
        val botToBotLine =
            if (botToBotChance > 0) "\n            AiPlayerbot.LLMBotToBotChatChance = $botToBotChance" else ""
        val logLinesLine =
            if (logLines) "\n            AiPlayerbot.LLMLogLines = 1" else ""
        // The staged lore card index: question turns get [RESULT]
        // cards and move_to resolves POI places; blank/absent keeps the
        // native loop off
        val loreLine =
            if (!loreFile.isNullOrBlank()) "\n            AiPlayerbot.LLMLoreFile = \"$loreFile\"" else ""
        val chatterLine =
            chatterLines(chatterPowerFile, composerEndpoint, composerModel, composerApiKey)
        // The staged prompt pack: path only, the native renderer parses it.
        // Blank/absent keeps the trained default (byte-identical output).
        // Per-preset pack deltas (Bots → AI) ride as explicit block
        // switches after the file line — same last-wins parse, so preset >
        // global pack > trained default. RP dials ride as native weights
        // (the native layer consumes them; the app persists + emits them).
        val promptPackLine =
            if (!promptPackFile.isNullOrBlank()) {
                "\n            AiPlayerbot.LLMPromptPackFile = \"$promptPackFile\""
            } else ""
        // The staged EMPTY default-prompts file, by absolute path (the
        // native loader resolves the bare relative default against CWD,
        // never the run dir). Blank/absent keeps the native default's
        // fail-open behavior.
        val defaultPromptsLine =
            if (!defaultPromptsFile.isNullOrBlank()) {
                "\n            AiPlayerbot.LLMDefaultPromptsFile = \"$defaultPromptsFile\""
            } else ""
        // The staged CA bundle for external-endpoint TLS verification.
        // The native LLMTLSVerify switch defaults ON (hand-editable conf,
        // not an app knob); this path is the staged-file half of the pair -
        // absent falls back to the Android system store, never fails boot.
        val tlsCaLine =
            if (!tlsCaFile.isNullOrBlank()) {
                "\n            AiPlayerbot.LLMTLSCaFile = \"$tlsCaFile\""
            } else ""
        val packDeltaLines = speech.packDeltas.toSortedMap().entries.joinToString("") { (id, on) ->
            "\n            AiPlayerbot.LLMPromptBlock.$id = ${if (on) 1 else 0}"
        }
        val rpDialLines = buildString {
            if (speech.initiative >= 0) append("\n            AiPlayerbot.LLMRpInitiative = ${speech.initiative}")
            if (speech.volatility >= 0) append("\n            AiPlayerbot.LLMRpVolatility = ${speech.volatility}")
            if (speech.reactivity >= 0) append("\n            AiPlayerbot.LLMRpReactivity = ${speech.reactivity}")
            if (speech.longForm >= 0) append("\n            AiPlayerbot.LLMRpLongForm = ${speech.longForm}")
        }
        return """
            AiPlayerbot.LLMEnabled = 2
            AiPlayerbot.LLMBackend = 0
            AiPlayerbot.LLMApiEndpoint = $endpoint$keyLine
            AiPlayerbot.LLMApiJson = ${apiJsonTemplate(model, effectiveProfile, providerSafe)}
            AiPlayerbot.LLMResponseStartPattern =
            AiPlayerbot.LLMResponseEndPattern =
            AiPlayerbot.LLMPromptFormat = 1
            AiPlayerbot.LLMApiModel = $model
            AiPlayerbot.LLMTemp = ${jsonNumber(effectiveProfile.temperature)}
            AiPlayerbot.LLMTopP = ${jsonNumber(effectiveProfile.topP)}
            AiPlayerbot.LLMTopK = ${effectiveProfile.topK}
            AiPlayerbot.LLMRepeatPenalty = ${jsonNumber(effectiveProfile.repeatPenalty)}$minPLine$presenceLine
            AiPlayerbot.LLMMaxNewTokens = ${effectiveProfile.maxTokens}
            AiPlayerbot.LLMGenerationTimeout = $generationTimeout
            AiPlayerbot.LLMConnectTimeout = ${tier.connectTimeoutSec}
            AiPlayerbot.LLMMaxSimultaniousGenerations = ${tier.maxSimultaneousGenerations}
            AiPlayerbot.LLMGovernorWindow = ${tier.governorWindowSec}
            AiPlayerbot.LLMGovernorBotMax = ${tier.governorBotMax}
            AiPlayerbot.LLMGovernorGlobalMax = ${tier.governorGlobalMax}
            AiPlayerbot.LLMContextLength = ${tier.contextLength}
            AiPlayerbot.LLMFactsCap = $factsCap
            AiPlayerbot.LLMMemoriesTail = $memoriesTail$botToBotLine$thinkingLine$providerSafeLine
            AiPlayerbot.LLMBanterEnabled = ${if (banterEnabled) 1 else 0}$loreLine$chatterLine$promptPackLine$defaultPromptsLine$tlsCaLine${if (providerSafe) cloudLane.confLines() else ""}$packDeltaLines$rpDialLines$logLinesLine
        """.trimIndent() + "\n"
    }

    /**
     * The world-chatter conf lines. The conf enables the SUBSYSTEM and
     * names the power file whenever the app staged one (LLM on); the
     * FILE's enabled flag is the master switch - the native scheduler
     * re-reads it every tick, and the app re-stages it at world start and
     * on battery events, so the ambience toggle applies at the next
     * realm start (the LLM screen says so) while battery dims land
     * mid-session. A staged file reading enabled=0 is silence; no staged
     * file leaves the subsystem off.
     */
    private fun chatterLines(
        chatterPowerFile: String?,
        composerEndpoint: String?,
        composerModel: String?,
        composerApiKey: String?,
    ): String {
        if (chatterPowerFile.isNullOrBlank()) {
            return "\n            AiPlayerbot.LLMChatterEnabled = 0"
        }
        val composerLines =
            if (composerEndpoint.isNullOrBlank()) ""
            else {
                "\n            AiPlayerbot.LLMChatterComposerUrl = $composerEndpoint" +
                    "\n            AiPlayerbot.LLMChatterComposerModel = ${composerModel ?: DEFAULT_EXTERNAL_MODEL}" +
                    (if (!composerApiKey.isNullOrBlank()) {
                        "\n            AiPlayerbot.LLMChatterComposerKey = $composerApiKey"
                    } else "")
            }
        return "\n            AiPlayerbot.LLMChatterEnabled = 1" +
            "\n            AiPlayerbot.LLMChatterPowerFile = \"$chatterPowerFile\"" +
            composerLines
    }

    /**
     * The LEGACY request-body template (profile-driven), used when the
     * native prompt format is off (`LLMPromptFormat = 0`: hand-configured
     * servers) — the app itself emits `LLMPromptFormat = 1`, whose native
     * builder reads the same profile-derived conf keys (LLMTemp/TopP/TopK/
     * RepeatPenalty/MinP/PresencePenalty/MaxNewTokens), so the two paths
     * carry identical sampling by construction. Gemma needs repeat_penalty
     * pinned to 1.0 (llama-server's 1.1 default degrades it); `providerSafe`
     * strips the llama.cpp-specific fields for external endpoints that may
     * reject unknown body keys.
     */
    internal fun apiJsonTemplate(
        model: String,
        profile: LlmSamplingProfile,
        providerSafe: Boolean,
    ): String {
        val json = StringBuilder("{\"model\":\"").append(model)
            .append("\",\"messages\":[{\"role\":\"system\",\"content\":\"<pre prompt> <context>\"},")
            .append("{\"role\":\"user\",\"content\":\"<prompt> <post prompt>\"}],")
            .append("\"max_tokens\":").append(profile.maxTokens)
            .append(",\"temperature\":").append(jsonNumber(profile.temperature))
            .append(",\"top_p\":").append(jsonNumber(profile.topP))
        if (!providerSafe) {
            json.append(",\"top_k\":").append(profile.topK)
                .append(",\"repeat_penalty\":").append(jsonNumber(profile.repeatPenalty))
            if (profile.minP > 0.0) json.append(",\"min_p\":").append(jsonNumber(profile.minP))
            if (profile.presencePenalty > 0.0) {
                json.append(",\"presence_penalty\":").append(jsonNumber(profile.presencePenalty))
            }
        }
        return json.append(",\"cache_prompt\":true,\"stream\":false}").toString()
    }

    private fun jsonNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    /**
     * The :llm runtime config derived from the LLM submenu snapshot. Both
     * consumers — the supervisor's pre-world-start launch and the submenu's
     * Start-now button — go through this single mapping so their configs can
     * never drift. The server context follows the
     * SELECTED model's tier profile (E2B 12288, Qwen 6144) — the runtime
     * and the conf's LLMContextLength can never disagree. --jinja applies the
     * model's real chat template, and --load-mode none is the measured
     * coexistence profile (no second mmap copy of the weights).
     * [chatTemplateFile] is the staged non-thinking override: when the
     * warm-up probe detects a thinking template, the service restarts once
     * with --chat-template <the staged content> (stage it via
     * [stageChatTemplate]).
     */
    fun runtimeConfig(
        snapshot: Settings.Snapshot,
        modelAbsolutePath: String,
        chatTemplateFile: String? = null,
    ): LlmRuntimeConfig =
        LlmRuntimeConfig.Builder(modelAbsolutePath).apply {
            threads = snapshot.llmThreads
            cpuMaskHex = snapshot.llmCoresMask
            computeMode = snapshot.llmComputeMode
            npuLayers = snapshot.llmOffloadLayers
            contextSize = LlmModelRegistry.byId(snapshot.llmModelId).tierProfile.contextLength
            extraArgs = listOf("--jinja", "--load-mode", "none")
            this.chatTemplateFile = chatTemplateFile
        }.build()

    /**
     * Stage the packaged non-thinking chat template (the gemma-dialect
     * export shape the tuned GGUFs use — render-verified byte-identical to
     * the tuned template's non-thinking path) into filesDir and return its
     * absolute path, or null when staging fails. Null fails OPEN: the runtime
     * keeps the model's own template and the warm-up probe simply never arms
     * the override. The staged path persists across sticky restarts (the
     * persisted config carries it).
     */
    fun stageChatTemplate(context: Context): String? = try {
        val out = File(File(context.filesDir, "llm"), TEMPLATE_ASSET_NAME)
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, ".${TEMPLATE_ASSET_NAME}.${Thread.currentThread().id}.tmp")
        context.assets.open("llm/$TEMPLATE_ASSET_NAME").use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        check(tmp.length() > 0L) { "empty template asset" }
        if (!tmp.renameTo(out)) {
            // rename refused (target locked etc.): stream-copy over the
            // target. A failed stage must never DELETE the previously
            // staged file - the persisted sticky-restart config may still
            // point at it.
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
        out.absolutePath
    } catch (e: Exception) {
        android.util.Log.w("LlmRuntimePolicy", "chat template staging failed: ${e.message}")
        null
    }

    /** File name inside assets/llm/ and filesDir/llm/. */
    private const val TEMPLATE_ASSET_NAME = "chat_template_nonthinking.jinja"
}
