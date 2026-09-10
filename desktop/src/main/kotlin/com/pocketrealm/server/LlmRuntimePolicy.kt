package com.pocketrealm.server

import com.pocketrealm.bots.BotLlmSpeech
import com.pocketrealm.llm.LlmSamplingProfile
import com.pocketrealm.llm.LlmTierProfile

/**
 * Desktop twin of the Android server/LlmRuntimePolicy: the EXTERNAL-endpoint
 * subset only (the Windows lane has no embedded llama-server — cloud is the
 * only LLM lane on x86_64). The normalizers and the conf emission are the
 * same code, same semantics, same conf keys; the embedded-model,
 * NPU/core-placement and :llm-process pieces of the Android object do not
 * exist here. Android parity for this subset is what the conf golden test
 * in ServerRuntimeFiles pins.
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
     * anything above 0 is clamped into the supported band.
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

    /**
     * The user-supplied external endpoint, normalized for conf emission, or
     * null when it is unusable (fail-closed: no LLM block is then emitted at
     * all). Same rules as the Android twin — the native conf parser and the
     * JSON body are the downstream consumers.
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
     * The appended block for external-endpoint mode: the realm talks to any
     * OpenAI-compatible /v1/chat/completions service. [endpoint] must
     * already be normalized; any null input means "no block".
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

    /** External-endpoint runtime knobs (16/bot, 48 global; 60s generation; ctx 128k). */
    val EXTERNAL_TIER = LlmTierProfile(
        contextLength = 131072, generationTimeoutSec = 60,
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
            AiPlayerbot.LLMMemoriesTail = $memoriesTail$botToBotLine$providerSafeLine
            AiPlayerbot.LLMBanterEnabled = ${if (banterEnabled) 1 else 0}$loreLine$chatterLine$promptPackLine$defaultPromptsLine$tlsCaLine${if (providerSafe) cloudLane.confLines() else ""}$packDeltaLines$rpDialLines
        """.trimIndent() + "\n"
    }

    /**
     * The world-chatter conf lines (the Android twin's emission; on the
     * desktop the staged power file carries enabled=1/rung=NORMAL whenever
     * ambience is on — there is no battery to dim it).
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
