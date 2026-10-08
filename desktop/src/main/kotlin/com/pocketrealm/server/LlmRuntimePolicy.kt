package com.pocketrealm.server

import com.pocketrealm.bots.BotLlmSpeech
import com.pocketrealm.llm.LlmModelRegistry
import com.pocketrealm.llm.LlmSamplingProfile
import com.pocketrealm.llm.LlmTierProfile

/**
 * Desktop twin of the Android server/LlmRuntimePolicy: the external-endpoint
 * emission verbatim, plus the ON-DEVICE lane's block (DESK-80/103) — the
 * local llama-server sidecar's loopback URL rides [confBlockEmbedded], the
 * Android confBlock twin: model "local", keyless, providerSafe OFF (the
 * peer IS llama.cpp, so the llama.cpp body keys — repeat_penalty 1.0 is
 * load-bearing for Gemma per the registry — survive) and the SELECTED
 * registry model's measured sampling/tier profiles. The NPU/core-placement
 * and :llm-process supervision pieces of the Android object stay
 * android-specific (the desktop sidecar is a plain CPU/GPU child process
 * owned by DesktopLlmModelManager). Android parity for the external
 * subset is what the conf golden test in ServerRuntimeFiles pins.
 */
@Suppress(
    "LongParameterList",
    "LongMethod",
    "CyclomaticComplexMethod",
    "TooManyFunctions",
    "ComplexCondition",
    "MagicNumber",
    "ReturnCount",
) // verbatim twin of the Android lane's emission (linted there under the app baseline)
internal object LlmRuntimePolicy {

    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    /** A7.3: bot-to-bot chance on the cloud lane (player-visible life
     * outweighs background chat the other way). */
    const val CLOUD_LANE_BOT_TO_BOT_CHANCE = 25

    /**
     * Advanced-tier generation overrides. 0 means "follow the tier value";
     * anything above 0 is clamped into the supported band. The band stays
     * finite on purpose — 0 follows the tier's measured wall (the embedded
     * sidecar lane's LlmModelRegistry.MEASURED_COLD_START_TIMEOUT_SEC, the
     * external endpoint's MEASURED_GENERATION_TIMEOUT_SEC, the round-2
     * live-verified budget; the old 60 s default cut both chatter legs at
     * exactly the wall), a positive value clamps into
     * [MIN_GENERATION_TIMEOUT_SEC, MAX_GENERATION_TIMEOUT_SEC]; no input
     * resolves to an unbounded wait. The ceiling sits above the measured
     * worst need (~260 s), encloses the embedded tier's cold-start wall
     * (the shared registry's MEASURED_COLD_START_TIMEOUT_SEC, 360 s) so an
     * override of exactly the tier default is never clamped below the wall
     * the tier itself ships, and EQUALS the Android twin's ceiling (the
     * twins are deliberately separate sources, so
     * DesktopLlmUserOverrideCeilingsTest pins the equality) while staying
     * under the native compiled default (600 s).
     */
    const val MIN_MAX_NEW_TOKENS = 24
    const val MAX_MAX_NEW_TOKENS = 600
    const val MIN_GENERATION_TIMEOUT_SEC = 15
    const val MAX_GENERATION_TIMEOUT_SEC = 360

    fun normalizeMaxNewTokensOverride(tokens: Int): Int =
        if (tokens <= 0) 0 else tokens.coerceIn(MIN_MAX_NEW_TOKENS, MAX_MAX_NEW_TOKENS)

    fun normalizeGenerationTimeoutOverride(seconds: Int): Int =
        if (seconds <= 0) 0 else seconds.coerceIn(MIN_GENERATION_TIMEOUT_SEC, MAX_GENERATION_TIMEOUT_SEC)

    /** Path appended to a bare external origin (no path component). */
    const val EXTERNAL_CHAT_COMPLETIONS_PATH = "/v1/chat/completions"

    /** Model name emitted when the external-mode model field is left empty. */
    const val DEFAULT_EXTERNAL_MODEL = "local"

    /**
     * The user-supplied external endpoint, normalized for conf emission, or
     * null when it is unusable (fail-closed: no LLM block is then emitted at
     * all). Same rules as the Android twin — the native conf parser and the
     * JSON body are the downstream consumers. http:// survives normalization
     * (keyless LAN endpoints stay usable) but can never pair with a key —
     * [isCleartextKeyedEndpoint].
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
        if (authority.contains(':')) {
            val port = authority.substringAfterLast(':')
            val asInt = port.toIntOrNull() ?: return null
            if (asInt < 1 || asInt > MAX_PORT) return null
            if (authority.dropLast(port.length + 1).isEmpty()) return null
        }
        return if (rest.contains('/')) trimmed else "$trimmed$EXTERNAL_CHAT_COMPLETIONS_PATH"
    }

    /** The external request model name, conf/JSON safe, or null when the
     * value cannot be embedded in the LLMApiJson body. */
    fun normalizeExternalModel(model: String): String? {
        val trimmed = model.trim()
        if (trimmed.isEmpty()) return DEFAULT_EXTERNAL_MODEL
        if (trimmed.length > 128) return null
        if (trimmed.any { it.isWhitespace() || it == '"' || it == '\\' || it.isISOControl() }) {
            return null
        }
        return trimmed
    }

    /** The external API key, or null when the value cannot be emitted.
     * Empty means no Authorization header. */
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
     * True when the pair would put the bearer key on the wire in cleartext:
     * an http:// endpoint with a non-empty key (the Android twin's rule).
     * The native client sends `Authorization: Bearer` over the endpoint's
     * own socket, so http + key hands the cloud key to anything on the
     * path. Keyless http stays usable (a LAN llama box needs no key);
     * https carries keys. Enforced at both key choke points:
     * [confBlockExternal] fails closed to no block, and the composer lane
     * ([chatterLines]) drops its lines.
     */
    fun isCleartextKeyedEndpoint(endpoint: String?, apiKey: String?): Boolean =
        !endpoint.isNullOrBlank() && !apiKey.isNullOrBlank() &&
            endpoint.substringBefore("://").equals("http", ignoreCase = true)

    // ---------------- embedded-sidecar engine knobs (DESK-9/80) ----------------
    // The CPU/GPU argv twins of the Android :llm runtimeConfig's threads
    // and offload knobs (the NPU compute-mode and cores-mask pieces stay
    // android-specific and are NOT twinned here).

    /** The embedded-sidecar decode-threads band (llama-server -t). */
    const val MIN_THREADS = 1
    const val MAX_THREADS = 8

    /** The desktop decode-threads default (the Android runtimeConfig's
     * shipped value — a game coexistence budget, not hardware concurrency). */
    const val DEFAULT_THREADS = 3

    fun normalizeThreads(threads: Int): Int = threads.coerceIn(MIN_THREADS, MAX_THREADS)

    /**
     * The embedded-sidecar offload knob (llama-server -ngl). DESKTOP
     * DELIBERATE DEFAULT: 0 = CPU-ONLY — no GPU offload lane is bundled on
     * Windows, so the default launches CPU-serving; 1..128 offloads that
     * many layers (the Android band). Android's default 99 is the NPU/dGPU
     * posture and is deliberately NOT twinned.
     */
    const val MIN_OFFLOAD_LAYERS = 0
    const val MAX_OFFLOAD_LAYERS = 128
    const val DEFAULT_OFFLOAD_LAYERS = 0

    fun normalizeOffloadLayers(layers: Int): Int =
        if (layers <= 0) DEFAULT_OFFLOAD_LAYERS else layers.coerceIn(MIN_OFFLOAD_LAYERS, MAX_OFFLOAD_LAYERS)

    /**
     * The appended block for the EMBEDDED lane (the desktop twin of the
     * Android confBlock, DESK-80/103): the local llama-server sidecar's
     * loopback endpoint with the SELECTED registry model's measured
     * sampling/tier profiles, model "local", keyless, providerSafe OFF —
     * the llama.cpp body keys ride because the peer IS llama.cpp.
     * [endpoint] is the health-probed sidecar URL (the full
     * /v1/chat/completions form); null means the lane cannot serve —
     * no block (fail-closed, the shared posture).
     */
    fun confBlockEmbedded(
        endpoint: String?,
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
        if (endpoint == null) return null
        return confLines(
            endpoint = endpoint,
            model = DEFAULT_EXTERNAL_MODEL,
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
     * OpenAI-compatible /v1/chat/completions service. [endpoint] must
     * already be normalized; any null input means "no block" — and so does
     * an http:// endpoint paired with a non-empty key (the Bearer key must
     * never cross in cleartext — [isCleartextKeyedEndpoint]).
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
        // http + key never pairs: the Bearer key must not cross in
        // cleartext — fail closed exactly like any other unusable field.
        if (isCleartextKeyedEndpoint(endpoint, apiKey)) return null
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
     * Off-device endpoints get their own budget, not a device profile
     * (the Android twin's measured values, verbatim).
     */
    val EXTERNAL_PROFILE = LlmSamplingProfile(
        temperature = 0.7, topP = 0.9, topK = 20,
        repeatPenalty = 1.0, maxTokens = 600,
    )

    /**
     * External-endpoint runtime knobs (16/bot, 48 global; ctx 128k). The
     * wall is the MEASURED budget (the Android twin's value, verbatim;
     * LVF-bots-llm-013): the old 60 s default cut both chatter legs of the
     * round-2 external run at exactly the wall (class=empty,
     * durMs=60013/60058) against ~100-120 s cold completions.
     */
    val EXTERNAL_TIER = LlmTierProfile(
        contextLength = 131072,
        generationTimeoutSec = LlmModelRegistry.MEASURED_GENERATION_TIMEOUT_SEC,
        maxSimultaneousGenerations = 4,
        governorBotMax = 16, governorGlobalMax = 48,
        factsCap = 48, memoriesTail = 16,
        botToBotChatChance = 10,
    )

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
     * The shared block body — the Android twin's emission verbatim (the conf
     * keys are the native contract; drift between platforms would be a
     * behavior fork). See the Android twin for the per-line rationale.
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
        val replyTokensOverride =
            if (speech.replyTokens > 0) speech.replyTokens else maxNewTokensOverride
        val (effectiveProfile, generationTimeout) = effectiveGeneration(
            profile, tier, replyTokensOverride, generationTimeoutOverride,
        )
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
        val botToBotLine =
            if (botToBotChance > 0) "\n            AiPlayerbot.LLMBotToBotChatChance = $botToBotChance" else ""
        // The Android twin's thinking-template kwarg (emit_chat_template_
        // kwargs {"enable_thinking": false}): set on model families whose
        // export template defaults to thinking (the qwen tier) — load-
        // bearing for the embedded lane's tuned checkpoints.
        val thinkingLine =
            if (tier.thinkingKwargs) "\n            AiPlayerbot.LLMThinkingKwargs = 1" else ""
        val logLinesLine =
            if (logLines) "\n            AiPlayerbot.LLMLogLines = 1" else ""
        val providerSafeLine =
            if (providerSafe) "\n            AiPlayerbot.LLMProviderSafe = 1" else ""
        val loreLine =
            if (!loreFile.isNullOrBlank()) "\n            AiPlayerbot.LLMLoreFile = \"$loreFile\"" else ""
        val chatterLine =
            chatterLines(chatterPowerFile, composerEndpoint, composerModel, composerApiKey)
        val promptPackLine =
            if (!promptPackFile.isNullOrBlank()) {
                "\n            AiPlayerbot.LLMPromptPackFile = \"$promptPackFile\""
            } else ""
        val defaultPromptsLine =
            if (!defaultPromptsFile.isNullOrBlank()) {
                "\n            AiPlayerbot.LLMDefaultPromptsFile = \"$defaultPromptsFile\""
            } else ""
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
     * The world-chatter conf lines (the Android twin's emission; the staged
     * power file's rung comes from DesktopChatterPowerMonitor's
     * GetSystemPowerStatus read — DESK-81's battery-aware pacing).
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
        // An http composer endpoint paired with a key drops the composer
        // lines entirely (the native composer-off state) — the key must
        // never cross in cleartext (isCleartextKeyedEndpoint).
        val composerLines =
            if (composerEndpoint.isNullOrBlank() ||
                isCleartextKeyedEndpoint(composerEndpoint, composerApiKey)
            ) ""
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

    /** The LEGACY request-body template (the Android twin's emission
     * verbatim; provider-safe strips llama.cpp-specific body keys). */
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
}
