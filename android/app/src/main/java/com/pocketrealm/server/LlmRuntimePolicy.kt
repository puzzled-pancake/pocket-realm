package com.pocketrealm.server

import android.content.Context
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
 * The base bot profile conf (BotProfile.playerbotConfig) keeps its reviewed
 * `AiPlayerbot.LLMEnabled = 0`; [confBlock] is APPENDED after it and only when
 * the user enabled the LLM runtime from the LLM submenu, so the reviewed
 * default contract holds until explicit opt-in (native/llm/MILESTONES:
 * everything ships behind AiPlayerbot.LLM*, all defaulting OFF).
 *
 * Backend choice: `LLMBackend = 0` (HTTP) against the embedded llama-server in
 * the :llm process. The vendored C++ client POSTs `LLMApiJson` verbatim as the
 * request body with the `<pre prompt>/<context>/<prompt>/<post prompt>` fill
 * keys replaced (SayAction.cpp). llama-server answers `/v1/chat/completions`
 * with an OpenAI envelope, which the client parses as JSON (A9): the reply is
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
     * client parses the OpenAI chat-completions envelope as JSON (A9) and
     * decodes `choices[0].message.content` with full string semantics, so
     * the regex extraction patterns are dead on every endpoint this block
     * can configure. The keys must still be written (empty) because the
     * reviewed native defaults would otherwise apply — the default start
     * pattern (`("text":\s*")`) never matches decoded prose (total
     * silence), and the default end pattern's first alternative `(")`
     * truncates at the first quote (the same defect the app's pre-A9
     * emission had with escaped quotes — the motivation A9 cites).
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
        )
    }

    /** Off-device endpoints get their own budget, not a device profile. */
    val EXTERNAL_PROFILE = LlmSamplingProfile(
        temperature = 0.7, topP = 0.9, topK = 20,
        repeatPenalty = 1.0, maxTokens = 300,
    )

    /** External-endpoint runtime knobs (16/bot, 48 global; 30s gen + 10s connect; ctx 16384). */
    val EXTERNAL_TIER = LlmTierProfile(
        contextLength = 16384, generationTimeoutSec = 30,
        maxSimultaneousGenerations = 4,
        governorBotMax = 16, governorGlobalMax = 48,
        factsCap = 24, memoriesTail = 8,
        botToBotChatChance = 10,
    )

    /**
     * The shared block body: identical envelope for both sources, so the
     * merge-order contract (append wins over the base conf, patterns
     * trim-proof, `stream:false` tail) holds for embedded and external alike.
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
    ): String {
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
            if (tier.botToBotChatChance > 0) "\n            AiPlayerbot.LLMBotToBotChatChance = ${tier.botToBotChatChance}" else ""
        // The staged lore card index: question turns get [RESULT]
        // cards and move_to resolves POI places; blank/absent keeps the
        // native loop off
        val loreLine =
            if (!loreFile.isNullOrBlank()) "\n            AiPlayerbot.LLMLoreFile = \"$loreFile\"" else ""
        // World-chatter layer. The conf enables the SUBSYSTEM
        // and names the power file whenever the app staged one (LLM on);
        // the FILE's enabled flag is the master switch - it is re-read by
        // the native scheduler every tick, so the ambience toggle works
        // mid-session in BOTH directions (conf keys alone apply only at
        // world start). A staged file reading enabled=0 is silence.
        val chatterLine =
            if (!chatterPowerFile.isNullOrBlank()) {
                "\n            AiPlayerbot.LLMChatterEnabled = 1" +
                    "\n            AiPlayerbot.LLMChatterPowerFile = \"$chatterPowerFile\"" +
                    (if (!composerEndpoint.isNullOrBlank()) {
                        "\n            AiPlayerbot.LLMChatterComposerUrl = $composerEndpoint" +
                            "\n            AiPlayerbot.LLMChatterComposerModel = ${composerModel ?: DEFAULT_EXTERNAL_MODEL}" +
                            (if (!composerApiKey.isNullOrBlank()) "\n            AiPlayerbot.LLMChatterComposerKey = $composerApiKey" else "")
                    } else "")
            } else "\n            AiPlayerbot.LLMChatterEnabled = 0"
        return """
            AiPlayerbot.LLMEnabled = 2
            AiPlayerbot.LLMBackend = 0
            AiPlayerbot.LLMApiEndpoint = $endpoint$keyLine
            AiPlayerbot.LLMApiJson = ${apiJsonTemplate(model, profile, providerSafe)}
            AiPlayerbot.LLMResponseStartPattern =
            AiPlayerbot.LLMResponseEndPattern =
            AiPlayerbot.LLMPromptFormat = 1
            AiPlayerbot.LLMApiModel = $model
            AiPlayerbot.LLMTemp = ${jsonNumber(profile.temperature)}
            AiPlayerbot.LLMTopP = ${jsonNumber(profile.topP)}
            AiPlayerbot.LLMTopK = ${profile.topK}
            AiPlayerbot.LLMRepeatPenalty = ${jsonNumber(profile.repeatPenalty)}$minPLine$presenceLine
            AiPlayerbot.LLMMaxNewTokens = ${profile.maxTokens}
            AiPlayerbot.LLMGenerationTimeout = ${tier.generationTimeoutSec}
            AiPlayerbot.LLMConnectTimeout = ${tier.connectTimeoutSec}
            AiPlayerbot.LLMMaxSimultaniousGenerations = ${tier.maxSimultaneousGenerations}
            AiPlayerbot.LLMGovernorWindow = ${tier.governorWindowSec}
            AiPlayerbot.LLMGovernorBotMax = ${tier.governorBotMax}
            AiPlayerbot.LLMGovernorGlobalMax = ${tier.governorGlobalMax}
            AiPlayerbot.LLMContextLength = ${tier.contextLength}
            AiPlayerbot.LLMFactsCap = ${tier.factsCap}
            AiPlayerbot.LLMMemoriesTail = ${tier.memoriesTail}$botToBotLine$thinkingLine$providerSafeLine
            AiPlayerbot.LLMBanterEnabled = ${if (banterEnabled) 1 else 0}$loreLine$chatterLine
        """.trimIndent() + "\n"
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
     * never drift. The mapped fields are exactly the five the submenu owns;
     * every other knob keeps its measured default. --jinja applies the
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
