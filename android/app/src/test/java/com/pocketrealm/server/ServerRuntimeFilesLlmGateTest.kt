package com.pocketrealm.server

import com.pocketrealm.llm.LlmModelRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four-state guard for the playerbot LLM conf gate resolved at world start.
 * ServerRuntimeFiles.llmConfigOverrides reads settings and the staged model
 * and delegates every decision to the pure [ServerRuntimeFiles.llmOverrides]
 * under test here, so these verdicts are the contract those reads only feed:
 * (0) the external-endpoint block when external mode is on (no staged model
 * required; unusable endpoint/key/model suppress the whole block); (1) the
 * submenu runtime's HTTP block when the user enabled it AND the model GGUF
 * is staged — the same gate the supervisor applies before starting the :llm
 * process, so the conf and the running server can never disagree; (2) with
 * the submenu off, the debug-build-only in-process llama override (still
 * model-gated) so the adb-driven native/llm workflow keeps working;
 * (3) otherwise nothing, and the base profile's reviewed LLMEnabled = 0
 * stands.
 */
class ServerRuntimeFilesLlmGateTest {

    @Test
    fun enabledRuntimeWithStagedModelEmitsTheHttpBlock() {
        val block = ServerRuntimeFiles.llmOverrides(
            uiEnabled = true,
            modelPresent = true,
            modelAbsolutePath = "/data/models/qwen.gguf",
            debugBuild = false,
        )!!
        assertTrue(block.contains("AiPlayerbot.LLMBackend = 0"))
        assertTrue(block.contains("AiPlayerbot.LLMApiEndpoint = http://127.0.0.1:8080/v1/chat/completions"))
    }

    @Test
    fun enabledRuntimeWinsOverTheDebugFallback() {
        val block = ServerRuntimeFiles.llmOverrides(
            uiEnabled = true,
            modelPresent = true,
            modelAbsolutePath = "/data/models/qwen.gguf",
            debugBuild = true,
        )!!
        assertTrue(block.contains("AiPlayerbot.LLMBackend = 0"))
        assertTrue(!block.contains("LLMModelPath"))
    }

    @Test
    fun missingModelSuppressesEvenTheEnabledRuntime() {
        // The conf and the running :llm process share one gate: no staged
        // model means no LLM conf at all, whatever the toggle says.
        assertNull(
            ServerRuntimeFiles.llmOverrides(
                uiEnabled = true,
                modelPresent = false,
                modelAbsolutePath = "/data/models/qwen.gguf",
                debugBuild = true,
            ),
        )
    }

    @Test
    fun disabledRuntimeOnADebugBuildFallsBackToTheInProcessOverride() {
        val block = ServerRuntimeFiles.llmOverrides(
            uiEnabled = false,
            modelPresent = true,
            modelAbsolutePath = "/data/models/qwen.gguf",
            debugBuild = true,
        )!!
        assertTrue(block.contains("AiPlayerbot.LLMBackend = 1"))
        assertTrue(block.contains("""AiPlayerbot.LLMModelPath = "/data/models/qwen.gguf""""))
        // S8-ledger (n), S9: the banter toggle gates the in-process debug
        // path too - the native default (1) otherwise runs the authored
        // initiative layer regardless of the toggle
        assertTrue(block.contains("AiPlayerbot.LLMBanterEnabled = 1"))
        assertTrue(
            "the debug block follows the passed banter flag",
            ServerRuntimeFiles.llmOverrides(
                uiEnabled = false,
                modelPresent = true,
                modelAbsolutePath = "/data/models/qwen.gguf",
                debugBuild = true,
                banterEnabled = false,
            )!!.contains("AiPlayerbot.LLMBanterEnabled = 0"),
        )
    }

    @Test
    fun disabledRuntimeOnAReleaseBuildEmitsNothing() {
        assertNull(
            ServerRuntimeFiles.llmOverrides(
                uiEnabled = false,
                modelPresent = true,
                modelAbsolutePath = "/data/models/qwen.gguf",
                debugBuild = false,
            ),
        )
        assertNull(
            ServerRuntimeFiles.llmOverrides(
                uiEnabled = false,
                modelPresent = false,
                modelAbsolutePath = "",
                debugBuild = false,
            ),
        )
    }

    // ---- external-endpoint mode (state 0 of the four-state gate) ---------------

    @Test
    fun externalModeEmitsTheExternalBlockWithoutAnyStagedModel() {
        // An external service needs no local GGUF: the endpoint stands in for
        // the model gate entirely.
        val block = ServerRuntimeFiles.llmOverrides(
            uiEnabled = true,
            modelPresent = false,
            modelAbsolutePath = "/data/models/qwen.gguf",
            debugBuild = false,
            externalMode = true,
            externalEndpoint = "https://api.openai.com/v1/chat/completions",
            externalModel = "gpt-4o-mini",
            externalApiKey = "sk-test",
        )!!
        assertTrue(block.contains("AiPlayerbot.LLMBackend = 0"))
        assertTrue(block.contains("AiPlayerbot.LLMApiEndpoint = https://api.openai.com/v1/chat/completions"))
        assertTrue(block.contains("AiPlayerbot.LLMApiKey = sk-test"))
        assertTrue(block.contains("\"model\":\"gpt-4o-mini\""))
    }

    @Test
    fun externalModeBeatsBothTheEmbeddedBlockAndTheDebugFallback() {
        val block = ServerRuntimeFiles.llmOverrides(
            uiEnabled = true,
            modelPresent = true,
            modelAbsolutePath = "/data/models/qwen.gguf",
            debugBuild = true,
            externalMode = true,
            externalEndpoint = "https://api.openai.com/v1/chat/completions",
            externalModel = "gpt-4o-mini",
            externalApiKey = "",
        )!!
        assertTrue(block.contains("AiPlayerbot.LLMApiEndpoint = https://api.openai.com/v1/chat/completions"))
        assertTrue(!block.contains("LLMModelPath"))
    }

    @Test
    fun unusableExternalEndpointSuppressesTheWholeBlock() {
        // Fail-closed: bots must never be pointed at a broken URL (a null
        // normalizer here means the UI stored something un-emittable).
        assertNull(
            ServerRuntimeFiles.llmOverrides(
                uiEnabled = true,
                modelPresent = false,
                modelAbsolutePath = "",
                debugBuild = false,
                externalMode = true,
                externalEndpoint = null,
                externalModel = "gpt-4o-mini",
                externalApiKey = "",
            ),
        )
        assertNull(
            ServerRuntimeFiles.llmOverrides(
                uiEnabled = true,
                modelPresent = false,
                modelAbsolutePath = "",
                debugBuild = false,
                externalMode = true,
                externalEndpoint = "https://api.openai.com/v1/chat/completions",
                externalModel = null,
                externalApiKey = null,
            ),
        )
    }

    @Test
    fun externalModeWithoutTheMasterToggleEmitsNothing() {
        assertNull(
            ServerRuntimeFiles.llmOverrides(
                uiEnabled = false,
                modelPresent = false,
                modelAbsolutePath = "",
                debugBuild = false,
                externalMode = true,
                externalEndpoint = "https://api.openai.com/v1/chat/completions",
                externalModel = "gpt-4o-mini",
                externalApiKey = "",
            ),
        )
    }

    @Test
    fun banterToggleReachesBothRuntimeBlocks() {
        val embedded = ServerRuntimeFiles.llmOverrides(
            uiEnabled = true,
            modelPresent = true,
            modelAbsolutePath = "/data/models/qwen.gguf",
            debugBuild = false,
            banterEnabled = false,
        )!!
        assertTrue(embedded.contains("AiPlayerbot.LLMBanterEnabled = 0"))
        val external = ServerRuntimeFiles.llmOverrides(
            uiEnabled = true,
            modelPresent = false,
            modelAbsolutePath = "",
            debugBuild = false,
            externalMode = true,
            externalEndpoint = "https://api.openai.com/v1/chat/completions",
            externalModel = "gpt-4o-mini",
            externalApiKey = "",
            banterEnabled = true,
        )!!
        assertTrue(external.contains("AiPlayerbot.LLMBanterEnabled = 1"))
    }

    @Test
    fun defaultPromptsFileReachesEveryEmittedBlockLane() {
        // B8: the staged EMPTY default-prompts file must ride every lane the
        // gate can emit - the native loader opens it on the HTTP and
        // in-process paths alike, so a lane without the line regresses to
        // the "not found or unreadable" startup line. Null (staging failed
        // or the LLM is off) omits the line everywhere.
        val http = ServerRuntimeFiles.llmOverrides(
            uiEnabled = true,
            modelPresent = true,
            modelAbsolutePath = "/data/models/qwen.gguf",
            debugBuild = false,
            defaultPromptsFile = "/srv/run/llm_character_card",
        )!!
        assertTrue(http.contains("AiPlayerbot.LLMDefaultPromptsFile = \"/srv/run/llm_character_card\"\n"))
        val external = ServerRuntimeFiles.llmOverrides(
            uiEnabled = true,
            modelPresent = false,
            modelAbsolutePath = "",
            debugBuild = false,
            externalMode = true,
            externalEndpoint = "https://api.openai.com/v1/chat/completions",
            externalModel = "gpt-4o-mini",
            externalApiKey = "sk-test",
            defaultPromptsFile = "/srv/run/llm_character_card",
        )!!
        assertTrue(external.contains("AiPlayerbot.LLMDefaultPromptsFile = \"/srv/run/llm_character_card\"\n"))
        val debug = ServerRuntimeFiles.llmOverrides(
            uiEnabled = false,
            modelPresent = true,
            modelAbsolutePath = "/data/models/qwen.gguf",
            debugBuild = true,
            defaultPromptsFile = "/srv/run/llm_character_card",
        )!!
        assertTrue(debug.contains("AiPlayerbot.LLMDefaultPromptsFile = \"/srv/run/llm_character_card\"\n"))
        val without = ServerRuntimeFiles.llmOverrides(
            uiEnabled = true,
            modelPresent = true,
            modelAbsolutePath = "/data/models/qwen.gguf",
            debugBuild = false,
        )!!
        assertFalse(without.contains("LLMDefaultPromptsFile"))
    }
    @Test
    fun selectedModelsSamplingProfileReachesTheEmbeddedConfBlock() {
        // llmOverrides must forward the SELECTED model's profile to
        // confBlock; falling back to the BASE default fails nothing else
        val block = ServerRuntimeFiles.llmOverrides(
            uiEnabled = true, modelPresent = true, modelAbsolutePath = "/x.gguf",
            debugBuild = false, profile = LlmModelRegistry.TUNED_E2B.profile,
        )!!
        assertTrue(block.contains("\"temperature\":0.7,"))
        assertTrue(block.contains("\"top_k\":20,"))
        assertFalse(block.contains("\"top_k\":64,"))
    }
}
