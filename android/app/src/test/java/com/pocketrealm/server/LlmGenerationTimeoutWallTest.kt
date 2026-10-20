package com.pocketrealm.server

import com.pocketrealm.llm.LlmModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LVF-bots-llm-012/-013 pin: the staged LLM generation wall. ONE staged
 * `AiPlayerbot.LLMGenerationTimeout` bounds both chatter legs (the ambient
 * murmur call AND the interactive whisper) per lane. The live run
 * measured the old 60 s tier default cutting both lanes' legs at exactly the
 * wall — gen end class=empty durMs=60013 / durMs=60058, deliver packets=0,
 * zero replies even with 5-minute drains. Two measured walls came out of
 * that round:
 * - external endpoint (LVF-bots-llm-013): completions needed ~100-120 s
 *    cold (~3.2 tok/s prompt eval on the ~334-386-token whisper prompts);
 *    the staged 240 s budget PROVED the full pass arc (gen end class=ok,
 *    durMs=58885 warm, reply + memory delta + delivery packets) —
 *    [LlmModelRegistry.MEASURED_GENERATION_TIMEOUT_SEC].
 * - embedded sidecar (LVF-bots-llm-012): the closeout re-execution
 *    measured the cold trained whisper at ~967 tokens and this host's
 *    loaded CPU prompt-eval at 3.12 tok/s — ~310 s of eval alone (cancelled
 *    at progress 0.96, n_tokens=931, t=298.55 s; the earlier round's
 *    completed warm turn adds ~33 s of reply decode), while the 240 s
 *    budget cut that same cold dispatch at durMs=240068 class=empty and
 *    the old 300 s wall sat 10 s under the measured need. The embedded
 *    tiers therefore ship the cold-start wall
 *    [LlmModelRegistry.MEASURED_COLD_START_TIMEOUT_SEC].
 * Both walls are finite (never an indefinite hang), configurable through
 * the clamped advanced override, and the window is a pure ceiling
 * (non-streaming requests return early on a fast lane).
 */
class LlmGenerationTimeoutWallTest {

    @Test
    fun everyLaneDefaultsToTheMeasuredCompletionBudget() {
        assertEquals(240, LlmModelRegistry.MEASURED_GENERATION_TIMEOUT_SEC)
        assertEquals(360, LlmModelRegistry.MEASURED_COLD_START_TIMEOUT_SEC)
        for (descriptor in LlmModelRegistry.all) {
            assertEquals(
                "embedded tier ${descriptor.id} must ship the cold-start wall",
                LlmModelRegistry.MEASURED_COLD_START_TIMEOUT_SEC,
                descriptor.tierProfile.generationTimeoutSec,
            )
        }
        assertEquals(
            "the external lane must ship the measured wall",
            LlmModelRegistry.MEASURED_GENERATION_TIMEOUT_SEC,
            LlmRuntimePolicy.EXTERNAL_TIER.generationTimeoutSec,
        )
    }

    @Test
    fun bothLaneConfBlocksStageTheMeasuredWall() {
        // The findings' starvation surface, verbatim: one staged conf per
        // realm bounds both legs, so each block must carry ITS lane's
        // measured wall (the external endpoint mirrors the repro:
        // standalone llama-server on loopback 8090). Delimiter-anchored
        // asserts so a same-prefix regression (24, 2400, 30) cannot pass.
        val embedded = LlmRuntimePolicy.confBlock(llmEnabled = true)!!
        assertTrue(
            "embedded lane must stage the cold-start wall:\n$embedded",
            embedded.contains("AiPlayerbot.LLMGenerationTimeout = 360\n"),
        )
        val external = LlmRuntimePolicy.confBlockExternal(
            "http://127.0.0.1:8090/v1/chat/completions", "local", "",
        )!!
        assertTrue(
            "external lane must stage the measured wall:\n$external",
            external.contains("AiPlayerbot.LLMGenerationTimeout = 240\n"),
        )
        // and neither emission path can stage an unbounded/zero wall
        assertFalse(embedded.contains("LLMGenerationTimeout = 0"))
        assertFalse(external.contains("LLMGenerationTimeout = 0"))
    }

    @Test
    fun embeddedWallCoversTheRecordedColdPrompt() {
        // The window math, pinned to the closeout re-execution measurement
        // (LVF-bots-llm-012, LV-bots-llm-20 closeout sidecar log): the
        // ~967-token cold whisper at this host's 3.12 tok/s loaded
        // prompt-eval = 310 s of eval (the run cancelled at progress 0.96,
        // n_tokens=931, t=298.55 s), plus the ~33 s the earlier recorded
        // reply decode took — the old 300 s wall sat under the eval alone.
        // The shipped embedded wall must cover that sum and stay inside the
        // configurable band; the pure-ceiling law keeps it latency-free on
        // fast lanes.
        val coldEvalSec = kotlin.math.ceil(RECORDED_COLD_PROMPT_TOKENS / RECORDED_EVAL_TOK_PER_SEC)
            .toInt()
        assertEquals(310, coldEvalSec)
        val recordedColdTurnSec = coldEvalSec + RECORDED_REPLY_DECODE_SEC
        assertEquals(343, recordedColdTurnSec)
        assertTrue(
            "embedded wall ${LlmModelRegistry.MEASURED_COLD_START_TIMEOUT_SEC} must cover " +
                "the recorded cold turn ($recordedColdTurnSec s)",
            LlmModelRegistry.MEASURED_COLD_START_TIMEOUT_SEC >= recordedColdTurnSec,
        )
        // the ask's sizing law: measured need + ~16% margin, not a round guess
        assertTrue(
            "wall ${LlmModelRegistry.MEASURED_COLD_START_TIMEOUT_SEC} must keep the " +
                "~16% margin over the measured $coldEvalSec s eval",
            LlmModelRegistry.MEASURED_COLD_START_TIMEOUT_SEC >= coldEvalSec * 116 / 100,
        )
        assertTrue(
            "the embedded wall must stay within the clamped band",
            LlmModelRegistry.MEASURED_COLD_START_TIMEOUT_SEC <=
                LlmRuntimePolicy.MAX_GENERATION_TIMEOUT_SEC,
        )
        // the wall the 240 s budget could not cover: the recorded cut
        assertTrue(recordedColdTurnSec > LlmModelRegistry.MEASURED_GENERATION_TIMEOUT_SEC)
    }

    @Test
    fun overrideWinsInsideTheClampedBandAndZeroFollowsTheTier() {
        // The wall stays configurable: an explicit override outranks the
        // tier default inside the clamped band; 0 follows the tier.
        val override = LlmRuntimePolicy.confBlockExternal(
            "http://127.0.0.1:8090/v1/chat/completions", "local", "",
            generationTimeoutOverride = 360,
        )!!
        assertTrue(
            override.contains("AiPlayerbot.LLMGenerationTimeout = 360\n"),
        )
        // the configurable band itself: below the measured worst need
        // (~310 s at the recorded 3.12 tok/s), above 0, bounded
        assertEquals(15, LlmRuntimePolicy.MIN_GENERATION_TIMEOUT_SEC)
        assertEquals(360, LlmRuntimePolicy.MAX_GENERATION_TIMEOUT_SEC)
        // below the floor clamps up, above the ceiling clamps down,
        // non-positive follows the tier (never an error, never infinite)
        assertEquals(15, LlmRuntimePolicy.normalizeGenerationTimeoutOverride(1))
        assertEquals(360, LlmRuntimePolicy.normalizeGenerationTimeoutOverride(10_000))
        assertEquals(0, LlmRuntimePolicy.normalizeGenerationTimeoutOverride(0))
        assertEquals(0, LlmRuntimePolicy.normalizeGenerationTimeoutOverride(-5))
    }

    @Test
    fun theWallIsAlwaysFinite() {
        // Never an indefinite hang: every shipped wall is positive and
        // finite, and the override can never resolve outside [0, ceiling].
        assertTrue(LlmModelRegistry.MEASURED_GENERATION_TIMEOUT_SEC > 0)
        assertTrue(LlmRuntimePolicy.EXTERNAL_TIER.generationTimeoutSec > 0)
        for (descriptor in LlmModelRegistry.all) {
            assertTrue(
                "tier ${descriptor.id} wall must be positive",
                descriptor.tierProfile.generationTimeoutSec > 0,
            )
        }
        for (seconds in intArrayOf(-100, -1, 0, 1, 15, 60, 240, 300, 360, 100_000)) {
            val normalized = LlmRuntimePolicy.normalizeGenerationTimeoutOverride(seconds)
            assertTrue(
                "input $seconds normalized to $normalized (negative/unbounded)",
                normalized >= 0,
            )
            assertTrue(
                "input $seconds normalized to $normalized (past the ceiling)",
                normalized <= LlmRuntimePolicy.MAX_GENERATION_TIMEOUT_SEC,
            )
        }
    }

    companion object {
        /** The closeout-recorded cold whisper prompt (LVF-bots-llm-012). */
        private const val RECORDED_COLD_PROMPT_TOKENS = 967.0

        /** The loaded CPU prompt-eval rate measured on that prompt. */
        private const val RECORDED_EVAL_TOK_PER_SEC = 3.12

        /** The observed reply decode of the completed warm turn (~54 tokens). */
        private const val RECORDED_REPLY_DECODE_SEC = 33
    }
}
