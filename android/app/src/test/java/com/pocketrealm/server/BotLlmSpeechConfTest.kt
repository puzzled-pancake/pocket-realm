package com.pocketrealm.server

import com.pocketrealm.bots.BotLlmSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-preset AI speech overrides (Bots → AI tab → BotLlmSpeech): how
 * they ride the appended LLM conf block, their precedence over the global
 * advanced-tier overrides, and the sentinel semantics (0/-1 = follow the
 * model's tuned profile).
 */
class BotLlmSpeechConfTest {

    private fun conf(speech: BotLlmSpeech, globalMaxNewTokens: Int = 0): String =
        ServerRuntimeFiles.llmOverrides(
            uiEnabled = true,
            modelPresent = true,
            modelAbsolutePath = "/data/models/tuned.gguf",
            debugBuild = false,
            maxNewTokensOverride = globalMaxNewTokens,
            speech = speech,
        )!!

    @Test
    fun defaultSpeechFollowsTheModelProfilesAndEmitsNoOverrides() {
        val conf = conf(BotLlmSpeech())
        // the tuned E2B tier values surface unchanged (facts 12 / tail 6 /
        // bot-to-bot 10 come from the registry tier, not the speech object)
        assertTrue(conf.contains("AiPlayerbot.LLMFactsCap = 12"))
        assertTrue(conf.contains("AiPlayerbot.LLMMemoriesTail = 6"))
        assertTrue(conf.contains("AiPlayerbot.LLMBotToBotChatChance = 10"))
        // default RP layer emits nothing (sentinels = follow the global pack)
        assertFalse(conf.contains("LLMPromptBlock"))
        assertFalse(conf.contains("LLMRp"))
    }

    @Test
    fun packDeltasAndRpDialsRideTheConfWithPresetPrecedence() {
        val conf = conf(
            BotLlmSpeech(
                packDeltas = mapOf("voice-lock" to true, "rule-boldness" to false),
                initiative = 80,
                volatility = 20,
                reactivity = -1,
                longForm = 100,
            ),
        )
        // sorted emission: rule-boldness before voice-lock
        val bold = conf.indexOf("AiPlayerbot.LLMPromptBlock.rule-boldness = 0")
        val voice = conf.indexOf("AiPlayerbot.LLMPromptBlock.voice-lock = 1")
        assertTrue(bold >= 0 && voice >= 0 && bold < voice)
        assertTrue(conf.contains("AiPlayerbot.LLMRpInitiative = 80"))
        assertTrue(conf.contains("AiPlayerbot.LLMRpVolatility = 20"))
        assertFalse(conf.contains("LLMRpReactivity"))
        assertTrue(conf.contains("AiPlayerbot.LLMRpLongForm = 100"))
        // external mode carries the same RP surface
        val external = ServerRuntimeFiles.llmOverrides(
            uiEnabled = true,
            modelPresent = false,
            modelAbsolutePath = "",
            debugBuild = false,
            externalMode = true,
            externalEndpoint = "https://api.openai.com/v1/chat/completions",
            externalModel = "gpt-4o-mini",
            externalApiKey = "",
            speech = BotLlmSpeech(packDeltas = mapOf("scene-close" to true)),
        )!!
        assertTrue(external.contains("AiPlayerbot.LLMPromptBlock.scene-close = 1"))
    }

    @Test
    fun rpNormalizeClampsAndDropsUnknownBlocks() {
        val speech = BotLlmSpeech.normalize(
            replyTokens = 0,
            botToBotChatChance = -1,
            factsCap = 0,
            memoriesTail = 0,
            packDeltas = mapOf("voice-lock" to true, "nope-unknown" to true),
            initiative = 999,
            volatility = -5,
            reactivity = 40,
            longForm = -9,
        )
        assertEquals(mapOf("voice-lock" to true), speech.packDeltas)
        assertEquals(100, speech.initiative)
        assertEquals(-1, speech.volatility)
        assertEquals(40, speech.reactivity)
        assertEquals(-1, speech.longForm)
        assertFalse(speech.isDefault())
    }

    @Test
    fun speechOverridesReplaceTheTierValuesInTheAppendedBlock() {
        val conf = conf(
            BotLlmSpeech(
                replyTokens = 96,
                botToBotChatChance = 20,
                factsCap = 16,
                memoriesTail = 4,
            ),
        )
        assertTrue(conf.contains("AiPlayerbot.LLMMaxNewTokens = 96"))
        assertTrue(conf.contains("AiPlayerbot.LLMBotToBotChatChance = 20"))
        assertTrue(conf.contains("AiPlayerbot.LLMFactsCap = 16"))
        assertTrue(conf.contains("AiPlayerbot.LLMMemoriesTail = 4"))
        assertFalse(conf.contains("AiPlayerbot.LLMFactsCap = 12"))
        assertFalse(conf.contains("AiPlayerbot.LLMBotToBotChatChance = 10"))
        // the reply-length override also feeds the legacy JSON template
        assertTrue(conf.contains("\"max_tokens\":96"))
    }

    @Test
    fun explicitBotToBotOffSuppressesTheLineLikeTheNativeDefault() {
        // native default is 0; both "off" and "follow a zero tier" emit nothing
        val conf = conf(BotLlmSpeech(botToBotChatChance = 0))
        assertFalse(conf.contains("AiPlayerbot.LLMBotToBotChatChance"))
    }

    @Test
    fun presetSpeechOutranksTheGlobalAdvancedTierOverride() {
        val conf = conf(BotLlmSpeech(replyTokens = 96), globalMaxNewTokens = 300)
        assertTrue(conf.contains("AiPlayerbot.LLMMaxNewTokens = 96"))
        assertFalse(conf.contains("AiPlayerbot.LLMMaxNewTokens = 300"))
        // ...and the global override still applies when the preset follows the model
        val globalWins = conf(BotLlmSpeech(), globalMaxNewTokens = 300)
        assertTrue(globalWins.contains("AiPlayerbot.LLMMaxNewTokens = 300"))
    }

    @Test
    fun externalModeCarriesTheSameSpeechOverrides() {
        val conf = ServerRuntimeFiles.llmOverrides(
            uiEnabled = true,
            modelPresent = false,
            modelAbsolutePath = "",
            debugBuild = false,
            externalMode = true,
            externalEndpoint = "https://api.openai.com/v1/chat/completions",
            externalModel = "gpt-4o-mini",
            externalApiKey = "",
            speech = BotLlmSpeech(replyTokens = 48, factsCap = 8),
        )!!
        assertTrue(conf.contains("AiPlayerbot.LLMMaxNewTokens = 48"))
        assertTrue(conf.contains("AiPlayerbot.LLMFactsCap = 8"))
    }

    @Test
    fun disabledRuntimeStillEmitsNothingRegardlessOfSpeech() {
        assertNull(
            ServerRuntimeFiles.llmOverrides(
                uiEnabled = false,
                modelPresent = true,
                modelAbsolutePath = "/data/models/tuned.gguf",
                debugBuild = false,
                speech = BotLlmSpeech(replyTokens = 96),
            ),
        )
    }

    @Test
    fun normalizeClampsPersistedOrImportedValuesIntoTheBands() {
        // below-band positives clamp up, above-band clamp down, negatives
        // collapse to the follow-the-model sentinels
        assertEquals(
            BotLlmSpeech(replyTokens = 24, botToBotChatChance = -1, factsCap = 4, memoriesTail = 2),
            BotLlmSpeech.normalize(replyTokens = 10, botToBotChatChance = -5, factsCap = 1, memoriesTail = 1),
        )
        assertEquals(
            BotLlmSpeech(replyTokens = 600, botToBotChatChance = 100, factsCap = 24, memoriesTail = 12),
            BotLlmSpeech.normalize(replyTokens = 9_000, botToBotChatChance = 300, factsCap = 99, memoriesTail = 99),
        )
        assertTrue(BotLlmSpeech().isDefault())
        assertFalse(BotLlmSpeech(replyTokens = 96).isDefault())
    }

    @Test
    fun stepperRoutingKeepsBothDirectionsLiveFromEveryState() {
        // up from the 0 sentinel snaps to the floor (not back to 0);
        // down into the gap lands on 0; in-band and the sentinel itself
        // pass through
        assertEquals(4, BotLlmSpeech.routeStepper(2, 0, 4))
        assertEquals(0, BotLlmSpeech.routeStepper(2, 4, 4))
        assertEquals(4, BotLlmSpeech.routeStepper(4, 0, 4))  // the boundary itself passes through
        // boundary reached from ABOVE keeps the value (an arm-1 `>` flip would drop it to 0)
        assertEquals(4, BotLlmSpeech.routeStepper(4, 6, 4))
        assertEquals(2, BotLlmSpeech.routeStepper(2, 3, 2))
        assertEquals(6, BotLlmSpeech.routeStepper(6, 4, 4))
        assertEquals(0, BotLlmSpeech.routeStepper(0, 0, 4))
        assertEquals(0, BotLlmSpeech.routeStepper(0, 8, 4))
        assertEquals(2, BotLlmSpeech.routeStepper(1, 0, 2))
        assertEquals(0, BotLlmSpeech.routeStepper(1, 2, 2))
    }
}
