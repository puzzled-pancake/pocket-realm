package com.pocketrealm.server

import com.pocketrealm.llm.LlmModelRegistry
import com.pocketrealm.storage.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop embedded-lane conf block (DESK-80/103): the LlmRuntimePolicy
 * twin's emission for the local llama-server sidecar, pinned against the
 * Android confBlock's shape — model "local", keyless, providerSafe OFF
 * (the llama.cpp body keys survive; repeat_penalty 1.0 is load-bearing for
 * Gemma per the registry), the SELECTED registry model's measured
 * sampling/tier profiles. Pure emission — no sidecar process needed.
 */
class DesktopLlmEmbeddedConfTest {

    @Test
    fun embeddedBlockCarriesTheAndroidEmbeddedEmission() {
        val body = LlmRuntimePolicy.confBlockEmbedded(
            "http://127.0.0.1:8080/v1/chat/completions",
        ) ?: error("a usable endpoint must emit the block")
        // ORDER-SENSITIVE: the desktop twin is a verbatim emission of the
        // Android confBlock — a key that moved position must fail here.
        // The emission order is the Android confLines twin's: the governor
        // keys ride BEFORE the context/memory keys.
        val pinnedLines = listOf(
            "AiPlayerbot.LLMEnabled = 2",
            "AiPlayerbot.LLMBackend = 0",
            "AiPlayerbot.LLMApiEndpoint = http://127.0.0.1:8080/v1/chat/completions",
            "AiPlayerbot.LLMPromptFormat = 1",
            "AiPlayerbot.LLMApiModel = local",
            "AiPlayerbot.LLMTemp = 0.7", // TUNED_E2B's measured profile
            "AiPlayerbot.LLMTopP = 0.8",
            "AiPlayerbot.LLMTopK = 20",
            "AiPlayerbot.LLMRepeatPenalty = 1",
            "AiPlayerbot.LLMMaxNewTokens = 230",
            // LVF-bots-llm-012: the embedded lane's cold-start wall (the
            // shared registry's MEASURED_GENERATION_TIMEOUT_SEC, 360). The
            // desktop pin rode an earlier 300 s constant; the measured
            // wall moved to 360 (the Android twins' LlmRuntimePolicyTest
            // and LlmGenerationTimeoutWallTest both pin 360); the external
            // lane keeps its own 240 s wall.
            "AiPlayerbot.LLMGenerationTimeout = 360",
            "AiPlayerbot.LLMGovernorBotMax = 8", // NOT the external 16/bot
            "AiPlayerbot.LLMContextLength = 12288", // the measured E2B tier
            "AiPlayerbot.LLMBotToBotChatChance = 10",
        )
        var searchFrom = 0
        pinnedLines.forEach { line ->
            val at = body.indexOf(line, searchFrom)
            assertTrue("embedded block must carry, IN ORDER: $line\n$body", at >= 0)
            searchFrom = at + line.length
        }
        // Keyless (no Authorization header), and providerSafe OFF: no
        // LLMProviderSafe switch, and the legacy body keeps the
        // llama.cpp-specific keys the sidecar expects.
        assertFalse("the embedded lane emits no API key:\n$body", "LLMApiKey" in body)
        assertFalse("providerSafe must stay off:\n$body", "LLMProviderSafe" in body)
        val apiJson = body.lineSequence().first { it.startsWith("AiPlayerbot.LLMApiJson") }
        assertTrue(
            "repeat_penalty must ride the embedded body (Gemma's load-bearing pin)",
            "repeat_penalty" in apiJson,
        )
        assertTrue("top_k must ride the embedded body", "top_k" in apiJson)
    }

    @Test
    fun embeddedBlockEmitsTheThinkingKwargForThinkingTiers() {
        val body = LlmRuntimePolicy.confBlockEmbedded(
            "http://127.0.0.1:8080/v1/chat/completions",
            profile = LlmModelRegistry.TUNED_Q08.profile,
            tier = LlmModelRegistry.TUNED_Q08.tierProfile,
        ) ?: error("a usable endpoint must emit the block")
        assertTrue(
            "the qwen tier must emit the non-thinking kwarg:\n$body",
            "AiPlayerbot.LLMThinkingKwargs = 1" in body,
        )
        assertTrue("small-model tier context:\n$body", "AiPlayerbot.LLMContextLength = 6144" in body)
        // The measured profile: 210-token cap, NOT the 230 E2B value.
        assertTrue("qwen reply cap:\n$body", "AiPlayerbot.LLMMaxNewTokens = 210" in body)
    }

    @Test
    fun embeddedBlockFailsClosedWithoutAnEndpoint() {
        assertNullish(
            LlmRuntimePolicy.confBlockEmbedded(null),
        )
    }

    @Test
    fun externalLaneStaysFreeOfTheThinkingLine() {
        // The thinkingLine leg added for the embedded lane is a no-op for
        // the external tier (thinkingKwargs = false) — the reviewed
        // external emission must be byte-stable.
        val body = LlmRuntimePolicy.confBlockExternal(
            "https://api.openai.com/v1/chat/completions",
            "gpt-test",
            "sk-test",
        ) ?: error("a usable external endpoint must emit the block")
        assertFalse(
            "LLMThinkingKwargs must not ride the external lane: $body",
            "LLMThinkingKwargs" in body,
        )
    }

    @Test
    fun engineKnobNormalizersPinTheDesktopBands() {
        // Decode threads: the Android 1..8 band, default 3.
        assertEquals(3, LlmRuntimePolicy.normalizeThreads(3))
        assertEquals(LlmRuntimePolicy.MIN_THREADS, LlmRuntimePolicy.normalizeThreads(-4))
        assertEquals(LlmRuntimePolicy.MAX_THREADS, LlmRuntimePolicy.normalizeThreads(64))
        // Offload layers: 0 (and any non-positive) means CPU-only and emits
        // no -ngl; 1..128 is the Android band.
        assertEquals(0, LlmRuntimePolicy.normalizeOffloadLayers(0))
        assertEquals(0, LlmRuntimePolicy.normalizeOffloadLayers(-1))
        assertEquals(1, LlmRuntimePolicy.normalizeOffloadLayers(1))
        assertEquals(99, LlmRuntimePolicy.normalizeOffloadLayers(99))
        assertEquals(
            LlmRuntimePolicy.MAX_OFFLOAD_LAYERS,
            LlmRuntimePolicy.normalizeOffloadLayers(999),
        )
    }

    @Test
    fun settingsEngineDefaultsMatchThePolicyDefaults() {
        // Settings cannot reach the internal policy object; the two
        // constants are pinned in step here so a drift fails a test.
        assertEquals(LlmRuntimePolicy.DEFAULT_THREADS, Settings.DEFAULT_LLM_THREADS)
        assertEquals(LlmRuntimePolicy.DEFAULT_OFFLOAD_LAYERS, Settings.DEFAULT_LLM_OFFLOAD_LAYERS)
    }

    @Test
    fun settingsSnapshotRoundTripsTheEngineFields() {
        val round = Settings.Snapshot(
            llmExternalMode = false,
            llmModelId = LlmModelRegistry.TUNED_Q08.id,
            llmThreads = 5,
            llmOffloadLayers = 16,
            llmAdvanced = true,
        )
        val parsed = Settings.Snapshot.fromJson(round.toJson())
        assertFalse(parsed.llmExternalMode)
        assertEquals(LlmModelRegistry.TUNED_Q08.id, parsed.llmModelId)
        assertEquals(5, parsed.llmThreads)
        assertEquals(16, parsed.llmOffloadLayers)
        assertTrue(parsed.llmAdvanced)
        // A settings.json written before the embedded lane existed loads
        // with the reviewed external posture and CPU-only engine defaults.
        val legacy = Settings.Snapshot.fromJson(
            org.json.JSONObject()
                .put("llmEnabled", true)
                .put("llmExternalUrl", "https://api.openai.com")
                .toString(),
        )
        assertTrue(legacy.llmExternalMode)
        assertEquals(Settings.DEFAULT_LLM_THREADS, legacy.llmThreads)
        assertEquals(Settings.DEFAULT_LLM_OFFLOAD_LAYERS, legacy.llmOffloadLayers)
        assertFalse(legacy.llmAdvanced)
    }

    @Test
    fun cleartextKeyedEndpointPairsAreDetected() {
        // the Android twin's rule: http + key never pairs (the bearer key
        // must not cross in cleartext); scheme casing must not matter.
        assertTrue(
            LlmRuntimePolicy.isCleartextKeyedEndpoint(
                "http://192.168.1.10:8080/v1/chat/completions", "sk-x",
            )
        )
        assertTrue(
            LlmRuntimePolicy.isCleartextKeyedEndpoint(
                "HTTP://example.com/v1/chat/completions", "sk-x",
            )
        )
        assertFalse(
            LlmRuntimePolicy.isCleartextKeyedEndpoint(
                "https://api.openai.com/v1/chat/completions", "sk-x",
            )
        )
        // keyless http stays a legal LAN lane; an absent lane/key never blocks
        assertFalse(
            LlmRuntimePolicy.isCleartextKeyedEndpoint(
                "http://192.168.1.10:8080/v1/chat/completions", "",
            )
        )
        assertFalse(LlmRuntimePolicy.isCleartextKeyedEndpoint(null, "sk-x"))
        assertFalse(
            LlmRuntimePolicy.isCleartextKeyedEndpoint(
                "http://192.168.1.10:8080/v1/chat/completions", null,
            )
        )
    }

    @Test
    fun externalBlockFailsClosedOnAnHttpEndpointPairedWithAKey() {
        assertNull(
            "http + key must emit no block (cleartext bearer key)",
            LlmRuntimePolicy.confBlockExternal(
                "http://192.168.1.10:8080/v1/chat/completions", "local", "sk-test",
            ),
        )
        // keyless http (a LAN llama box) still emits, key line absent
        val keyless = LlmRuntimePolicy.confBlockExternal(
            "http://192.168.1.10:8080/v1/chat/completions", "local", "",
        ) ?: error("keyless http must keep the LAN lane usable")
        assertFalse("AiPlayerbot.LLMApiKey" in keyless)
    }

    @Test
    fun composerLaneDropsACleartextKeyedComposerButKeepsTheChatterSubsystem() {
        // the composer rides the external URL/key fields on BOTH lanes
        // (embedded included): the http + key pair drops the composer
        // lines (native composer-off) without silencing chatter itself —
        // and the keyless loopback embedded lane keeps emitting.
        val on = LlmRuntimePolicy.confBlockEmbedded(
            "http://127.0.0.1:8080/v1/chat/completions",
            chatterPowerFile = "/srv/run/chatter-power.txt",
            composerEndpoint = "http://192.168.1.10:8080/v1/chat/completions",
            composerModel = "script-writer",
            composerApiKey = "sk-x",
        ) ?: error("the keyless embedded lane must keep emitting")
        assertTrue("AiPlayerbot.LLMChatterEnabled = 1\n" in on)
        assertTrue("AiPlayerbot.LLMChatterPowerFile = \"/srv/run/chatter-power.txt\"\n" in on)
        assertFalse("LLMChatterComposerUrl" in on)
        assertFalse("LLMChatterComposerModel" in on)
        assertFalse("LLMChatterComposerKey" in on)
    }

    /** Local assert helper (keeps the null-assertion import count down). */
    private fun assertNullish(block: String?) {
        assertTrue("a null endpoint must emit no block", block == null)
    }
}
