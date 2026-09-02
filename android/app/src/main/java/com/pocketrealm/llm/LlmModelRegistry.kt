package com.pocketrealm.llm

/**
 * The model registry (LLM-INTEGRATION.md §4.1): every selectable bot-brain
 * GGUF with its identity, integrity pins, the sampling profile the emitted
 * request body carries, and the tier knobs (§2.1/§4.3) the conf block
 * derives. The tuned checkpoints are the research program's LoRA merges
 * (G:\NPU LLM\finetune-runs, epoch-3 finals); their sha256 pins were
 * verified on disk 2026-08-29.
 *
 * Since S4 (trained prompt format) the default selection is the TUNED E2B:
 * the trained contract only speaks usefully to the tuned weights (the base
 * tier's chat usability rides the §4.4 template override - the warm-up
 * probe detects the thinking-template failure shape and restarts once
 * with the staged non-thinking template). Tuned models are LOCAL-ONLY
 * until a distribution channel lands (§4.2), so a fresh install ships
 * with the LLM runtime off until the tuned model is staged (adb-push dev
 * channel per §6); the S9 model picker (LlmScreen) offers every registry
 * entry small-first, with the hand-staging note on the localOnly ones.
 *
 * Sampling profiles are the measured winners per tier (plan §2.1): the
 * repeat_penalty 1.0 pin is load-bearing for Gemma (llama-server's 1.1
 * default degrades it — discussion #5751), and vendor recipes are
 * calibrated for full-precision serving, not on-device quants.
 */
data class LlmSamplingProfile(
    val temperature: Double,
    val topP: Double,
    val topK: Int,
    val repeatPenalty: Double = 1.0,
    val minP: Double = 0.0,
    val presencePenalty: Double = 0.0,
    val maxTokens: Int,
)

/**
 * The per-tier runtime knobs that ride the conf (§4.3 emission split:
 * governor/context/timeout/simultaneity/memory-depth keys; sampling rides
 * both the legacy template and the native request builder). Values follow
 * the §2.1 tier table.
 */
data class LlmTierProfile(
    val contextLength: Int,
    val generationTimeoutSec: Int,
    val maxSimultaneousGenerations: Int,
    val governorWindowSec: Int = 60,
    val governorBotMax: Int,
    val governorGlobalMax: Int,
    val factsCap: Int,
    val memoriesTail: Int,
    /** §2.1 T4 (S9): bounded TCP connect for the endpoint — the native
     *  client's blocking connect hangs minutes on a dead external server;
     *  10 s everywhere (loopback embedded connects are instant). */
    val connectTimeoutSec: Int = 10,
    /** §2.1 bot2bot chat: enabled (trained) on T1/T4, disabled on T2/T3.
     *  Percent of eligible bot-to-bot encounters (native
     *  AiPlayerbot.LLMBotToBotChatChance). */
    val botToBotChatChance: Int = 0,
    /** §4.4: emit chat_template_kwargs {"enable_thinking": false} — set for
     *  model families whose export template defaults to thinking (qwen). */
    val thinkingKwargs: Boolean = false,
)

data class LlmModelDescriptor(
    val id: String,
    val fileName: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val profile: LlmSamplingProfile,
    val tierProfile: LlmTierProfile,
) {
    /** No URL means staged-by-hand (sideload/adb) until §4.2 decides. */
    val localOnly: Boolean get() = url.isEmpty()
}

object LlmModelRegistry {
    /** Tuned Gemma 4 E2B (arm2 epoch-3, Q4_0) — the default since S4. */
    val TUNED_E2B = LlmModelDescriptor(
        id = "gemma4-E2B-TUNED-q4_0",
        fileName = "gemma4-E2B-TUNED-q4_0.gguf",
        url = "",
        size = 3_360_144_672L,
        sha256 = "e267e9793bac7db4340103b840bce8b52412ec3545827160b449119379853665",
        profile = LlmSamplingProfile(
            // S11 raise (plan §5.1 rev-3c): 230 clears the long-form bank —
            // 150 words ≈ 225 tokens at the measured ~1.5 tok/word, and the
            // bank-side law caps tool-bearing cue rows at 130 words so prose
            // + tool lines never exceed the cap. The pre-S11 120 truncated
            // even the existing 110-word corpus rows.
            temperature = 0.7, topP = 0.8, topK = 20,
            repeatPenalty = 1.0, presencePenalty = 1.0, maxTokens = 230,
        ),
        tierProfile = LlmTierProfile(
            contextLength = 8192, generationTimeoutSec = 60,
            maxSimultaneousGenerations = 2,
            // CPU-safe capacity (§2.1: NPU-E2B ≈10-12, CPU-E2B ≈8): the
            // shipped steady-state may be CPU until the hybrid coexistence
            // trial passes - admission never exceeds the lower capacity
            governorBotMax = 8, governorGlobalMax = 8,
            factsCap = 12, memoriesTail = 6,
            botToBotChatChance = 10,
        ),
    )

    /** Tuned Qwen3.5 0.8B CLEAN (arm1b epoch-3, Q4_0) — efficiency tier. */
    val TUNED_Q08 = LlmModelDescriptor(
        id = "qwen35-08b-CLEAN-tuned-q4_0",
        fileName = "qwen35-08b-CLEAN-tuned-q4_0.gguf",
        url = "",
        size = 501_452_160L,
        sha256 = "d87581a7ddd118f3193073226748881ea5fcbf48af40676e50071574b5e1f7aa",
        profile = LlmSamplingProfile(
            // S11 raise (plan §5.1): 210 clears the existing 110-word corpus
            // worst case (~165 prose + ~40 for two tool lines); the 0.8B tier
            // does NOT license the 150-word P50 shapes (small-model
            // repetition collapse at 256+ tokens) — the bridge gates the
            // long-form cue on the configured max tokens.
            temperature = 0.5, topP = 0.8, topK = 20,
            repeatPenalty = 1.0, maxTokens = 210,
        ),
        tierProfile = LlmTierProfile(
            contextLength = 6144, generationTimeoutSec = 45,
            maxSimultaneousGenerations = 2,
            governorBotMax = 8, governorGlobalMax = 20,
            factsCap = 8, memoriesTail = 4,
            // qwen export templates default to thinking; the tuned GGUFs
            // excluded the empty-content bug only with the kwarg sent
            // harness-side (§4.4) - production must send it too
            thinkingKwargs = true,
        ),
    )

    /** Base Gemma 4 E2B QAT — the fallback tier (chat-unusable until the
     *  §4.4 template override ships; plan §1.4/§4.1 descriptor 3). */
    val BASE_E2B = LlmModelDescriptor(
        id = "gemma-4-E2B-it-qat-UD-Q4_K_XL",
        fileName = "gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf",
        url = "https://huggingface.co/unsloth/gemma-4-E2B-it-qat-GGUF/resolve/main/" +
            "gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf",
        size = 2_815_000_000L,
        // upstream pin still unverified (plan G-3): empty sha skips download
        // verification exactly as before; the tuned pins do not.
        sha256 = "",
        profile = LlmSamplingProfile(
            // S11 raise (plan §5.1): same 210 clearing rationale as T2 — the
            // base tier serves the same corpus; it stays short-licensed.
            temperature = 1.0, topP = 0.95, topK = 64,
            repeatPenalty = 1.0, maxTokens = 210,
        ),
        tierProfile = LlmTierProfile(
            contextLength = 8192, generationTimeoutSec = 60,
            maxSimultaneousGenerations = 2,
            governorBotMax = 8, governorGlobalMax = 8,
            factsCap = 12, memoriesTail = 6,
        ),
    )

    val DEFAULT_MODEL_ID: String = TUNED_E2B.id

    val all: List<LlmModelDescriptor> = listOf(TUNED_E2B, TUNED_Q08, BASE_E2B)

    /** Unknown/absent ids fall back to the default — never to a crash. */
    fun byId(id: String?): LlmModelDescriptor =
        all.firstOrNull { it.id == id } ?: TUNED_E2B
}
