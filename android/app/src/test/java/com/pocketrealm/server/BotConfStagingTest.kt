package com.pocketrealm.server

import com.pocketrealm.bots.BotProfiles
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.pocketrealm.client.ActiveFlavor
import org.junit.After
import org.junit.Before

/**
 * Fail-loud conf staging: the staged
 * `aiplayerbot-*.conf` keys must all be KNOWN for the flavor's bots build —
 * the core silently drops unknown keys, so an unvalidated emission ships a
 * world that quietly ignores the app's policy values. The known set is the
 * flavor's conf matrix (dist-documented) + the built lane's core-read keys
 * (the patch-injected LLM* family) + the PocketRealm.* overlay family, and
 * the excluded families (correction: the wotlk VoiceChat inert stub) are
 * honored with their own naming — the matrix consumers honor
 * excluded_keys, not just the generator.
 */
class BotConfStagingTest {
    /** flip fixture: the conf staging resolves the ACTIVE flavor's
     * known keys — pin the VANILLA lane (holder select). */
    @Before
    fun pinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After
    fun unpinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
    }


    @Test fun `the shipped vanilla profile emission stages clean`() {
        // The REAL base-profile emission (the fresh-install default) must
        // pass the vanilla gate — the gate is wired into worldConfig, so a
        // failing emission would abort every vanilla realm start.
        val profile = BotProfiles.defaultProfile
        val conf = profile.playerbotConfig(com.pocketrealm.client.ClientFlavors.VANILLA)
        val verdict = BotConfStaging.validate("vanilla", conf)
        assertTrue(
            "the shipped vanilla bot emission carries unknown keys: " +
                "${verdict.unknownKeys}",
            verdict.isClean,
        )
        BotConfStaging.requireStagingSafe("vanilla", profile.id, conf)
    }

    @Test fun `every shipped preset stages clean for vanilla`() {
        // The whole built-in ladder through the public enumeration, plus
        // the legacy retained preset.
        val profiles = BotProfiles.userSelectable() + BotProfiles.legacySelectablePresets
        assertTrue("the profile ladder must not be empty", profiles.isNotEmpty())
        profiles.forEach { profile ->
            BotConfStaging.requireStagingSafe(
                "vanilla", profile.id,
                profile.playerbotConfig(com.pocketrealm.client.ClientFlavors.VANILLA),
            )
        }
    }

    @Test fun `an unknown key aborts staging with the regeneration path`() {
        val verdict = BotConfStaging.validate(
            "vanilla",
            "AiPlayerbot.Enabled = 1\nAiPlayerbot.DefinitelyNotARealKey = 7\n",
        )
        assertTrue(verdict.unknownKeys == listOf("AiPlayerbot.DefinitelyNotARealKey"))
        try {
            BotConfStaging.requireStagingSafe("vanilla", "test-profile",
                "AiPlayerbot.DefinitelyNotARealKey = 7\n")
            fail("an unknown key must abort staging")
        } catch (expected: IllegalStateException) {
            val message = expected.message!!
            assertTrue(
                "the refusal must name the key: $message",
                "AiPlayerbot.DefinitelyNotARealKey" in message,
            )
            assertTrue(
                "the refusal must name the flavor matrix (the vanilla lane's " +
                    "honest source note rides the same message — R3-W3-3): $message",
                "conf-matrix-vanilla.json" in message &&
                    "commits no matrix" in message,
            )
            assertTrue(
                "the refusal must name the silent-drop hazard: $message",
                "silently drop" in message,
            )
        }
    }

    @Test fun `excluded families are honored with their own naming`() {
        // correction: the wotlk VoiceChat family is an inert stub with
        // zero conf keys — the matrix never emits it and the validator must
        // refuse a staged key from the family with the EXCLUDED naming
        // (never the generic unknown-key error, never a silent pass).
        val verdict = BotConfStaging.validate(
            "wotlk",
            "AiPlayerbot.Enabled = 1\nVoiceChat.EmoteVoiceChat = 1\n",
        )
        assertTrue(verdict.excludedKeys == listOf("VoiceChat.EmoteVoiceChat"))
        assertTrue(verdict.unknownKeys.isEmpty())
        try {
            BotConfStaging.requireStagingSafe("wotlk", "test-profile",
                "VoiceChat.EmoteVoiceChat = 1\n")
            fail("an excluded-family key must abort staging")
        } catch (expected: IllegalStateException) {
            val message = expected.message!!
            assertTrue(
                "the refusal must name the C9 exclusion: $message",
                "correction C9" in message,
            )
            assertTrue(
                "the refusal must name the excluded family: $message",
                "VoiceChat.EmoteVoiceChat" in message,
            )
        }
        // The vanilla and tbc lanes exclude nothing — an unknown VoiceChat
        // key there is plain unknown.
        assertTrue(
            BotConfStaging.validate("vanilla", "VoiceChat.EmoteVoiceChat = 1\n")
                .unknownKeys.isNotEmpty(),
        )
    }

    @Test fun `an unknown overlay key is refused - the family is enumerated not accepted`() {
        // the PocketRealm family has NO blanket accept — the four keys
        // the built core reads are ENUMERATED in the catalog's code-read
        // sets, so a typo'd overlay key (the exact silent-drop class) fails
        // like any other unknown key.
        val verdict = BotConfStaging.validate(
            "vanilla",
            "AiPlayerbot.Enabled = 1\nPocketRealm.TimframeStage = 4242\n",
        )
        assertTrue(
            "the typo'd overlay key must be unknown, got " +
                "unknown=${verdict.unknownKeys} excluded=${verdict.excludedKeys}",
            verdict.unknownKeys == listOf("PocketRealm.TimframeStage"),
        )
        try {
            BotConfStaging.requireStagingSafe(
                "vanilla", "test-profile",
                "PocketRealm.TimframeStage = 4242\n",
            )
            fail("an unknown overlay key must abort staging")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "the refusal must name the typo'd key: ${expected.message}",
                "PocketRealm.TimframeStage" in expected.message!!,
            )
        }
    }

    @Test fun `the overlay family and the patch-read llm family are known`() {
        // The four ENUMERATED PocketRealm keys the built core reads...
        assertTrue(
            BotConfStaging.validate(
                "vanilla",
                "AiPlayerbot.Enabled = 1\n" +
                    "PocketRealm.GenerationBatchSize = 4\n" +
                    "PocketRealm.GenerationYieldMs = 10\n" +
                    "PocketRealm.ActivationBatchSize = 8\n" +
                    "PocketRealm.TimeframeStage = \"vanilla-1.12.1\"\n",
            ).isClean,
        )
        // ...and the patch-injected LLM keys the built core reads (the
        // emission block's surface) are known via the core-read leg.
        val llmBlock = """
            AiPlayerbot.LLMEnabled = 2
            AiPlayerbot.LLMBackend = 1
            AiPlayerbot.LLMModelPath = "E:/models/q4.gguf"
            AiPlayerbot.LLMThreads = 3
            AiPlayerbot.LLMCtxSize = 4096
            AiPlayerbot.LLMSlots = 4
            AiPlayerbot.LLMBanterEnabled = 1
            AiPlayerbot.LLMLoreFile = "lore_cards_v112.jsonl"
            AiPlayerbot.LLMDefaultPromptsFile = "llm_character_card"
            AiPlayerbot.LLMTLSCaFile = "cacert.pem"
        """.trimIndent()
        for (flavor in listOf("vanilla", "tbc", "wotlk")) {
            val verdict = BotConfStaging.validate(flavor, llmBlock)
            assertTrue(
                "$flavor: the LLM emission block must stage clean, got " +
                    "unknown=${verdict.unknownKeys} excluded=${verdict.excludedKeys}",
                verdict.isClean,
            )
        }
    }

    @Test fun `comments and prose lines never false positive`() {
        // A `#`-commented line parses identically to its uncommented form
        // (the generator's grammar); a non-`#` prose line that carries no
        // key-shaped token at all never matches.
        assertTrue(
            BotConfStaging.validate(
                "vanilla",
                "# AiPlayerbot.Enabled = 1 (documented header prose)\n" +
                    "## section header prose ==========\n" +
                    "AiPlayerbot.Enabled = 1\n",
            ).isClean,
        )
    }

    @Test fun `another lane's exclusive keys are refused - R4-R2-2 wrong-flavor staging`() {
        // R4-R2-2: the per-flavor known-key sets must stay DISJOINT — a key
        // exclusive to one flavor's build must be unknown on every other
        // lane (derived from the owning matrices, not literals).
        val tbcOnly = com.pocketrealm.server.GeneratedBotConfKeyCatalog.keysByFlavor["tbc"]!!
            .filter { it !in GeneratedBotConfKeyCatalog.keysByFlavor["vanilla"]!! }
            .filter { it !in GeneratedBotConfKeyCatalog.keysByFlavor["wotlk"]!! }
            .firstOrNull()
        if (tbcOnly != null) {
            val verdict = BotConfStaging.validate("wotlk", "$tbcOnly = 1\n")
            assertTrue(
                "the tbc-only key $tbcOnly must be unknown on the wotlk lane",
                verdict.unknownKeys.contains(tbcOnly),
            )
        }
        val wotlkOnly = com.pocketrealm.server.GeneratedBotConfKeyCatalog.keysByFlavor["wotlk"]!!
            .filter { it !in GeneratedBotConfKeyCatalog.keysByFlavor["vanilla"]!! }
            .filter { it !in GeneratedBotConfKeyCatalog.keysByFlavor["tbc"]!! }
            .firstOrNull()
        if (wotlkOnly != null) {
            val verdict = BotConfStaging.validate("tbc", "$wotlkOnly = 1\n")
            assertTrue(
                "the wotlk-only key $wotlkOnly must be unknown on the tbc lane",
                verdict.unknownKeys.contains(wotlkOnly),
            )
        }
    }

    @Test fun `unknown flavor ids fail loud never a silent pass`() {
        try {
            BotConfStaging.validate("wotlk-classic-demo", "AiPlayerbot.Enabled = 1")
            fail("an unknown flavor must fail loud")
        } catch (expected: IllegalStateException) {
            assertTrue("wotlk-classic-demo" in expected.message!!)
        }
    }

    @Test fun `a hyphenated unknown key is refused - R4-1 grammar fix`() {
        // the KEY_LINE grammar accepted hyphens, but a hostile
        // hyphenated unknown key must be caught (the old grammar silently
        // skipped every hyphenated line).
        val verdict = BotConfStaging.validate(
            "vanilla",
            "AiPlayerbot.Enabled = 1\nAiPlayerbot.DefinitelyNot-ARealKey = 7\n",
        )
        assertTrue(
            "the hostile hyphenated key must be unknown",
            verdict.unknownKeys == listOf("AiPlayerbot.DefinitelyNot-ARealKey"),
        )
    }

    @Test fun `the LLMPromptBlock emission stages clean for every flavor - R4-1`() {
        // The composed emission: every pack-delta id the UI can toggle must
        // stage clean for every flavor (the catalog's kBlocks dynamic-read
        // leg covers the family; tool-exemplar is excluded from the
        // emission because the core never reads its conf key).
        val packDeltaIds = setOf(
            "voice-lock", "rule-autonomy", "rule-anti-omniscient",
            "rule-boldness", "rule-salience", "ban-list", "scene-close",
            "initiative-opener", "mood-weather", "player-persona",
        )
        assertTrue(
            "tool-exemplar must be excluded from the pack-delta emission",
            "tool-exemplar" !in packDeltaIds,
        )
        val confText = packDeltaIds.joinToString("\n") { id ->
            "AiPlayerbot.LLMPromptBlock.$id = 1"
        } + "\nAiPlayerbot.Enabled = 1"
        for (flavor in listOf("vanilla", "tbc", "wotlk")) {
            val verdict = BotConfStaging.validate(flavor, confText)
            assertTrue(
                "$flavor: the LLMPromptBlock emission must stage clean, got " +
                    "unknown=${verdict.unknownKeys}",
                verdict.isClean,
            )
        }
    }

    @Test fun `the tool-exemplar conf key is refused - the core never reads it`() {
        // tool-exemplar is a SEASONING_ID whose conf key the built
        // core's kBlocks array never looks up — staging it must abort.
        val verdict = BotConfStaging.validate(
            "vanilla",
            "AiPlayerbot.LLMPromptBlock.tool-exemplar = 1\n",
        )
        assertTrue(
            "tool-exemplar must be unknown (the core never reads its conf key)",
            verdict.unknownKeys.contains("AiPlayerbot.LLMPromptBlock.tool-exemplar"),
        )
    }

    @Test fun `the disabled emission stages clean for every flavor - R2-1`() {
        // the aiplayerbot-disabled.conf staging leg runs
        // the SAME fail-loud gate as the profile legs (ServerRuntimeFiles
        // wires it), so the emission itself must validate clean for every
        // registered flavor — a drifted disabled key fails here first.
        val flavors = GeneratedBotConfKeyCatalog.keysByFlavor.keys.sorted()
        assertTrue(
            "the catalog must register every lane: $flavors",
            flavors.containsAll(listOf("vanilla", "tbc", "wotlk")),
        )
        val confText = ServerRuntimeFiles.aiplayerbotDisabledConfText()
        assertTrue(
            "the disabled emission must actually toggle the bots off",
            "AiPlayerbot.Enabled = 0" in confText,
        )
        for (flavor in flavors) {
            val verdict = BotConfStaging.validate(flavor, confText)
            assertTrue(
                "$flavor: the disabled emission must stage clean, got " +
                    "unknown=${verdict.unknownKeys} excluded=${verdict.excludedKeys}",
                verdict.isClean,
            )
        }
    }
}
