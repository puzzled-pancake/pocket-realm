package com.pocketrealm.server

import com.pocketrealm.bots.BotLlmSpeech
import com.pocketrealm.llm.ComputeMode
import com.pocketrealm.llm.LlmModelRegistry
import com.pocketrealm.llm.LlmSamplingProfile
import com.pocketrealm.storage.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmRuntimePolicyTest {

    @Test
    fun normalizeThreadsClampToTheMeasuredCoreBudget() {
        assertEquals(1, LlmRuntimePolicy.normalizeThreads(-3))
        assertEquals(1, LlmRuntimePolicy.MIN_THREADS)
        assertEquals(3, LlmRuntimePolicy.normalizeThreads(LlmRuntimePolicy.DEFAULT_THREADS))
        assertEquals(8, LlmRuntimePolicy.normalizeThreads(8))
        assertEquals(8, LlmRuntimePolicy.normalizeThreads(64))
    }

    @Test
    fun normalizeOffloadLayersClampsToTheNglKnobRange() {
        assertEquals(1, LlmRuntimePolicy.normalizeOffloadLayers(0))
        assertEquals(99, LlmRuntimePolicy.normalizeOffloadLayers(99))
        assertEquals(128, LlmRuntimePolicy.normalizeOffloadLayers(10_000))
    }

    @Test
    fun zeroCoresMaskFallsBackToTheMidClusterDefault() {
        assertEquals(0x38L, LlmRuntimePolicy.normalizeCoresMask(0L))
        assertEquals(0x07L, LlmRuntimePolicy.normalizeCoresMask(0x07L))
    }

    @Test
    fun disabledRuntimeEmitsNothingSoTheDefaultOffContractHolds() {
        assertNull(LlmRuntimePolicy.confBlock(llmEnabled = false))
    }

    @Test
    fun enabledRuntimeTargetsLlamaServerOverHttp() {
        val block = LlmRuntimePolicy.confBlock(llmEnabled = true)
        assertTrue(block!!.contains("AiPlayerbot.LLMEnabled = 2\n"))
        assertTrue(block.contains("AiPlayerbot.LLMBackend = 0"))
        assertTrue(block.contains("AiPlayerbot.LLMApiEndpoint = http://127.0.0.1:8080/v1/chat/completions"))
        // The C++ client POSTs LLMApiJson verbatim; the template must carry the
        // OpenAI chat envelope with the documented fill keys.
        listOf("<pre prompt>", "<context>", "<prompt>", "<post prompt>").forEach { key ->
            assertTrue("missing fill key $key", block.contains(key))
        }
        // The response patterns are emitted EMPTY - the native client
        // parses the chat-completions envelope as JSON, so the regexes are
        // dead here, but the keys must still be written to override the
        // native JSON-era defaults (whose end pattern truncates at
        // the first escaped quote). The lines end at the '=' so a regression
        // to any non-empty value cannot pass.
        assertTrue(block.contains("AiPlayerbot.LLMResponseStartPattern =\n"))
        assertTrue(block.contains("AiPlayerbot.LLMResponseEndPattern =\n"))
        assertTrue(block.endsWith("\n"))
    }

    @Test
    fun embeddedBlockCarriesTheProfileSamplingPinnedInTheRegistry() {
        // The C++ client POSTs LLMApiJson verbatim: these body fields are the
        // working sampling knobs on the legacy template path (the trained
        // format's native builder reads the same values from the conf keys
        // below), and the repeat_penalty 1.0 pin is load-bearing for Gemma
        // (llama-server's 1.1 default degrades it). Default = TUNED_E2B.
        // Each assertion ends at the JSON
        // delimiter so a value regression to a longer number with the same
        // prefix cannot pass (1 vs 1.5, 120 vs 1200)
        val block = LlmRuntimePolicy.confBlock(llmEnabled = true)!!
        listOf(
            "\"max_tokens\":230,", "\"temperature\":0.7,", "\"top_p\":0.8,",
            "\"top_k\":20,", "\"repeat_penalty\":1,", "\"presence_penalty\":1,",
        ).forEach { field ->
            assertTrue("missing sampling field $field", block.contains(field))
        }
        // Tier emission: the conf-side knobs the native consumes, with
        // the tuned E2B tier values (a silent regression of any of these is
        // exactly the bug class these pins exist for)
        listOf(
            "AiPlayerbot.LLMPromptFormat = 1",
            "AiPlayerbot.LLMApiModel = local",
            "AiPlayerbot.LLMTemp = 0.7",
            "AiPlayerbot.LLMTopP = 0.8",
            "AiPlayerbot.LLMTopK = 20",
            "AiPlayerbot.LLMRepeatPenalty = 1",
            "AiPlayerbot.LLMPresencePenalty = 1",
            "AiPlayerbot.LLMMaxNewTokens = 230",
            "AiPlayerbot.LLMGenerationTimeout = 60",
            "AiPlayerbot.LLMConnectTimeout = 10",
            "AiPlayerbot.LLMMaxSimultaniousGenerations = 2",
            "AiPlayerbot.LLMGovernorWindow = 60",
            "AiPlayerbot.LLMGovernorBotMax = 8",
            "AiPlayerbot.LLMGovernorGlobalMax = 8",
            "AiPlayerbot.LLMContextLength = 12288",
            "AiPlayerbot.LLMFactsCap = 12",
            "AiPlayerbot.LLMMemoriesTail = 6",
            "AiPlayerbot.LLMBotToBotChatChance = 10",
            "AiPlayerbot.LLMBanterEnabled = 1",
        ).forEach { key ->
            assertTrue("missing tier line $key", block.contains(key + "\n"))
        }
        // The default tuned tier is gemma: no thinking kwargs line; minP is
        // off on the profile
        assertFalse(block.contains("LLMThinkingKwargs"))
        assertFalse(block.contains("LLMMinP"))
        // tuned E2B profile: cooled arm + presence penalty, same rep pin
        val tuned = LlmRuntimePolicy.confBlock(
            llmEnabled = true, profile = LlmModelRegistry.TUNED_E2B.profile,
        )!!
        listOf(
            "\"max_tokens\":230,", "\"temperature\":0.7,", "\"top_p\":0.8,",
            "\"top_k\":20,", "\"repeat_penalty\":1,", "\"presence_penalty\":1,",
        ).forEach { field ->
            assertTrue("missing tuned sampling field $field", tuned.contains(field))
        }
        // the efficiency tier drops presence_penalty entirely (profile=0) and
        // carries its own smaller cap
        val q08 = LlmRuntimePolicy.confBlock(
            llmEnabled = true, profile = LlmModelRegistry.TUNED_Q08.profile,
        )!!
        assertTrue(q08.contains("\"temperature\":0.5,"))
        assertTrue(q08.contains("\"max_tokens\":210,"))
        assertFalse(q08.contains("presence_penalty"))
        // the base tier's max tokens carries its own exact pin (a silent
        // regression to 120 passes the loose 80..300 range)
        val base = LlmRuntimePolicy.confBlock(
            llmEnabled = true, profile = LlmModelRegistry.BASE_E2B.profile,
        )!!
        assertTrue(base.contains("\"max_tokens\":210,"))
        assertTrue(base.contains("\"temperature\":1,"))
    }

    @Test
    fun minPEmitsOnlyWhenAProfileSetsIt() {
        // min_p is plumbed but off on every shipped profile (unmeasured on
        // top of the tuned winners); this pins the emission branch so it can
        // never be deleted silently nor leak into the external envelope
        val withMinP = LlmRuntimePolicy.confBlock(
            llmEnabled = true,
            profile = LlmSamplingProfile(
                temperature = 0.5, topP = 0.8, topK = 20,
                repeatPenalty = 1.0, minP = 0.05, maxTokens = 100,
            ),
        )!!
        assertTrue(withMinP.contains("\"min_p\":0.05,"))
        val withoutMinP = LlmRuntimePolicy.confBlock(
            llmEnabled = true, profile = LlmModelRegistry.TUNED_E2B.profile,
        )!!
        assertFalse(withoutMinP.contains("min_p"))
    }

    @Test
    fun refusedPortFailsClosed() {
        var thrown = false
        try {
            LlmRuntimePolicy.confBlock(llmEnabled = true, port = 80)
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun defaultsMatchTheValidatedCoexistenceProfile() {
        assertEquals(8080, LlmRuntimePolicy.DEFAULT_PORT)
        assertEquals(0x38L, LlmRuntimePolicy.DEFAULT_CORES_MASK)
        assertEquals(3, LlmRuntimePolicy.DEFAULT_THREADS)
        assertEquals(99, LlmRuntimePolicy.DEFAULT_OFFLOAD_LAYERS)
        assertEquals(ComputeMode.AUTO, LlmRuntimePolicy.DEFAULT_COMPUTE_MODE)
        assertFalse(LlmRuntimePolicy.confBlock(false) != null)
    }

    @Test
    fun runtimeConfigMapsEverySnapshotFieldAndLeavesTheRestAtDefaults() {
        // The single mapping both the supervisor's pre-world-start launch and
        // the submenu's Start-now button go through; a field wired to the
        // wrong snapshot knob here is exactly the drift this guard pins.
        val snapshot = Settings.Snapshot(
            llmEnabled = true,
            llmComputeMode = ComputeMode.NPU,
            llmCoresMask = 0x30L,
            llmThreads = 2,
            llmOffloadLayers = 42,
        )
        val config = LlmRuntimePolicy.runtimeConfig(snapshot, "/data/models/qwen.gguf")
        assertEquals("/data/models/qwen.gguf", config.modelFile)
        assertEquals(2, config.threads)
        assertEquals(0x30L, config.cpuMaskHex)
        assertEquals(ComputeMode.NPU, config.computeMode)
        assertEquals(42, config.npuLayers)
        // Everything the submenu does not own keeps its measured default.
        assertEquals(8080, config.port)
        assertTrue(config.bindLoopbackOnly)
        assertEquals("", config.apiKey)
        assertEquals(10, config.nice)
        // E2B tier 12288 — the measured worst-case
        // trained request is ~2.2k tokens; the extra headroom carries the
        // prompt-pack seasoning + reply caps with 2 concurrent slots.
        // runtimeConfig follows the SELECTED tier (Qwen stays 6144); the
        // default snapshot selects the E2B default model.
        assertEquals(12288, config.contextSize)
        val qwen = LlmRuntimePolicy.runtimeConfig(
            Settings.Snapshot(llmModelId = LlmModelRegistry.TUNED_Q08.id),
            "/data/models/qwen.gguf",
        )
        assertEquals(6144, qwen.contextSize)
        assertFalse(config.useMtp)
        assertEquals(3, config.mtpDraftMax)
        // --jinja applies the model's real chat template; --load-mode none is
        // the measured coexistence profile (no second mmap copy).
        assertEquals(listOf("--jinja", "--load-mode", "none"), config.extraArgs)
    }

    @Test
    fun runtimeConfigCarriesTheStagedChatTemplateOverride() {
        // the staged non-thinking template arms the warm-up probe's
        // one-shot --chat-template retry (inline staged content); null
        // (staging failed) must fail open to the model's own template
        val snapshot = Settings.Snapshot(llmEnabled = true)
        val armed = LlmRuntimePolicy.runtimeConfig(
            snapshot, "/data/models/gemma.gguf",
            chatTemplateFile = "/data/data/app/files/llm/chat_template_nonthinking.jinja",
        )
        assertEquals(
            "/data/data/app/files/llm/chat_template_nonthinking.jinja",
            armed.chatTemplateFile,
        )
        // the override NEVER rides extraArgs itself - the service appends
        // --chat-template only after the probe proves it is needed
        assertEquals(listOf("--jinja", "--load-mode", "none"), armed.extraArgs)
        val unarmed = LlmRuntimePolicy.runtimeConfig(snapshot, "/data/models/gemma.gguf")
        assertNull(unarmed.chatTemplateFile)
    }

    @Test
    fun stagingNeverDeletesTheLiveTemplateOnAFailedRename() {
        // the old delete-then-retry rename path could destroy
        // the staged file a persisted sticky-restart config still points
        // at; the fallback must stream-copy over the target instead
        val policy = sequenceOf(
            java.io.File("src/main/java/com/pocketrealm/server/LlmRuntimePolicy.kt"),
            java.io.File("app/src/main/java/com/pocketrealm/server/LlmRuntimePolicy.kt"),
        ).firstOrNull { it.isFile } ?: return // source tree not present
        val text = policy.readText()
        assertTrue("staging must not delete the live staged file", !text.contains("out.delete()"))
        assertTrue(
            "failed rename falls back to a stream copy",
            text.contains("tmp.copyTo(out, overwrite = true)"),
        )
    }

    @Test
    fun snapshotDefaultsLandOnTheValidatedRuntimeDefaults() {
        val config = LlmRuntimePolicy.runtimeConfig(Settings.Snapshot(), "/data/models/qwen.gguf")
        assertEquals(LlmRuntimePolicy.DEFAULT_THREADS, config.threads)
        assertEquals(LlmRuntimePolicy.DEFAULT_CORES_MASK, config.cpuMaskHex)
        assertEquals(LlmRuntimePolicy.DEFAULT_COMPUTE_MODE, config.computeMode)
        assertEquals(LlmRuntimePolicy.DEFAULT_OFFLOAD_LAYERS, config.npuLayers)
    }

    @Test
    fun enabledRuntimeBlockCarriesTheBanterLine() {
        val block = LlmRuntimePolicy.confBlock(llmEnabled = true)
        assertTrue(block!!.contains("AiPlayerbot.LLMBanterEnabled = 1\n"))
        val off = LlmRuntimePolicy.confBlock(llmEnabled = true, banterEnabled = false)
        assertTrue(off!!.contains("AiPlayerbot.LLMBanterEnabled = 0"))
    }

    @Test
    fun loreFileLineEmitsOnlyWhenStaged() {
        // the staged lore card index path rides both conf blocks;
        // absent keeps the native retrieval loop off
        val with = LlmRuntimePolicy.confBlock(
            llmEnabled = true, loreFile = "/srv/run/lore_cards_v112.jsonl",
        )!!
        assertTrue(with.contains("AiPlayerbot.LLMLoreFile = \"/srv/run/lore_cards_v112.jsonl\"\n"))
        val without = LlmRuntimePolicy.confBlock(llmEnabled = true)!!
        assertFalse(without.contains("LLMLoreFile"))
        val external = LlmRuntimePolicy.confBlockExternal(
            "http://127.0.0.1:9/v1/chat/completions", "m", "",
            loreFile = "/srv/run/lore_cards_v112.jsonl",
        )!!
        assertTrue(external.contains("AiPlayerbot.LLMLoreFile = \"/srv/run/lore_cards_v112.jsonl\"\n"))
        val externalWithout = LlmRuntimePolicy.confBlockExternal(
            "http://127.0.0.1:9/v1/chat/completions", "m", "",
        )!!
        assertFalse(externalWithout.contains("LLMLoreFile"))
    }

    @Test
    fun promptPackLineEmitsOnlyWhenStaged() {
        // The staged pack path rides both conf blocks; absent keeps
        // the native renderer on the trained default (byte-identical output).
        val with = LlmRuntimePolicy.confBlock(
            llmEnabled = true, promptPackFile = "/srv/run/llm_prompt_pack.json",
        )!!
        assertTrue(with.contains("AiPlayerbot.LLMPromptPackFile = \"/srv/run/llm_prompt_pack.json\"\n"))
        val without = LlmRuntimePolicy.confBlock(llmEnabled = true)!!
        assertFalse(without.contains("LLMPromptPackFile"))
        val external = LlmRuntimePolicy.confBlockExternal(
            "http://127.0.0.1:9/v1/chat/completions", "m", "",
            promptPackFile = "/srv/run/llm_prompt_pack.json",
        )!!
        assertTrue(external.contains("AiPlayerbot.LLMPromptPackFile = \"/srv/run/llm_prompt_pack.json\"\n"))
        val externalWithout = LlmRuntimePolicy.confBlockExternal(
            "http://127.0.0.1:9/v1/chat/completions", "m", "",
        )!!
        assertFalse(externalWithout.contains("LLMPromptPackFile"))
    }

    @Test
    fun defaultPromptsLineEmitsOnlyWhenStaged() {
        // The staged EMPTY default-prompts file rides both conf blocks
        // BY ABSOLUTE PATH - the native loader resolves the bare relative
        // default (llm_character_card) against CWD, never the run dir, so a
        // relative value here would regress to the "not found or unreadable"
        // startup line. Absent keeps the native default's fail-open behavior.
        val with = LlmRuntimePolicy.confBlock(
            llmEnabled = true, defaultPromptsFile = "/srv/run/llm_character_card",
        )!!
        assertTrue(with.contains("AiPlayerbot.LLMDefaultPromptsFile = \"/srv/run/llm_character_card\"\n"))
        val without = LlmRuntimePolicy.confBlock(llmEnabled = true)!!
        assertFalse(without.contains("LLMDefaultPromptsFile"))
        val external = LlmRuntimePolicy.confBlockExternal(
            "http://127.0.0.1:9/v1/chat/completions", "m", "",
            defaultPromptsFile = "/srv/run/llm_character_card",
        )!!
        assertTrue(external.contains("AiPlayerbot.LLMDefaultPromptsFile = \"/srv/run/llm_character_card\"\n"))
        val externalWithout = LlmRuntimePolicy.confBlockExternal(
            "http://127.0.0.1:9/v1/chat/completions", "m", "",
        )!!
        assertFalse(externalWithout.contains("LLMDefaultPromptsFile"))
    }

    @Test
    fun tlsCaLineEmitsOnlyWhenStaged() {
        // The staged Mozilla CA bundle rides every appended conf block
        // by absolute path (the native client loads it for SSL_VERIFY_PEER;
        // absent = the Android system-store fallback, never a boot failure)
        val with = LlmRuntimePolicy.confBlock(llmEnabled = true, tlsCaFile = "/srv/run/cacert.pem")!!
        assertTrue(with.contains("AiPlayerbot.LLMTLSCaFile = \"/srv/run/cacert.pem\"\n"))
        val without = LlmRuntimePolicy.confBlock(llmEnabled = true)!!
        assertFalse(without.contains("LLMTLSCaFile"))
        val external = LlmRuntimePolicy.confBlockExternal(
            "https://api.example.com/v1/chat/completions", "m", "k",
            tlsCaFile = "/srv/run/cacert.pem",
        )!!
        assertTrue(external.contains("AiPlayerbot.LLMTLSCaFile = \"/srv/run/cacert.pem\"\n"))
    }

    @Test
    fun cloudLaneEmitsTheToggleAndItsEconomicsOnlyWhenOn() {
        // The Cloud conversation toggle rides the external block.
        // OFF (the shipped default): the explicit 0
        // line and NOTHING else, so every native default governs and the
        // widening stays conjunction-keyed OFF. ON: the toggle plus the
        // lane's economics, so a staged conf is self-describing.
        // The OFF absence pins the WHOLE economics family
        // (all 8 non-toggle keys, not a 2-key sample).
        val off = LlmRuntimePolicy.confBlockExternal(
            "https://api.example.com/v1/chat/completions", "m", "k",
        )!!
        assertTrue(off.contains("AiPlayerbot.LLMCloudChatter = 0"))
        for (key in listOf("LLMPartyReplyEnabled", "LLMCloudStreetSayPct",
                           "LLMCloudLineBudgetPerHour", "LLMStreetSayPerDay",
                           "LLMRpgChatPerDay", "LLMBotToBotPerDay",
                           "LLMCloudInteractivePerPlayerHour", "LLMDialogueFastLane")) {
            assertFalse(key, off.contains(key))
        }
        // and the DEBUG lane carries none of the family either (it is a
        // fixed literal - same defense-in-depth as the device block)
        val debugBlock = ServerRuntimeFiles.llmOverrides(
            uiEnabled = false,
            modelPresent = true,
            modelAbsolutePath = "/data/models/qwen.gguf",
            debugBuild = true,
        )!!
        for (key in listOf("LLMCloudChatter", "LLMPartyReplyEnabled",
                           "LLMCloudStreetSayPct", "LLMCloudLineBudgetPerHour",
                           "LLMStreetSayPerDay", "LLMRpgChatPerDay",
                           "LLMBotToBotPerDay", "LLMCloudInteractivePerPlayerHour",
                           "LLMDialogueFastLane")) {
            assertFalse(key, debugBlock.contains(key))
        }
        val on = LlmRuntimePolicy.confBlockExternal(
            "https://api.example.com/v1/chat/completions", "m", "k",
            cloudLane = CloudLaneConf(cloudChatter = true),
        )!!
        assertTrue(on.contains("AiPlayerbot.LLMCloudChatter = 1\n"))
        // the staged-0 ninth key is value-asserted too (the
        // whole family is present-and-self-describing on the ON lane);
        // every value assert ends at the line
        // delimiter so a regression to a longer same-prefix number
        // (25 -> 250, 1 -> 10) cannot pass - the file's own convention
        assertTrue(on.contains("AiPlayerbot.LLMPartyReplyEnabled = 0\n"))
        assertTrue(on.contains("AiPlayerbot.LLMCloudStreetSayPct = 25\n"))
        assertTrue(on.contains("AiPlayerbot.LLMStreetSayPerDay = 200\n"))
        assertTrue(on.contains("AiPlayerbot.LLMRpgChatPerDay = 300\n"))
        assertTrue(on.contains("AiPlayerbot.LLMBotToBotPerDay = 300\n"))
        assertTrue(on.contains("AiPlayerbot.LLMCloudLineBudgetPerHour = 90\n"))
        assertTrue(on.contains("AiPlayerbot.LLMCloudInteractivePerPlayerHour = 240\n"))
        assertTrue(on.contains("AiPlayerbot.LLMDialogueFastLane = 1\n"))
    }

    @Test
    fun cloudLaneRaisesBotToBotChanceOnlyWhenToggledOn() {
        // 25 on the cloud lane, the tier default otherwise; an
        // explicit preset override wins over both
        val on = LlmRuntimePolicy.confBlockExternal(
            "https://api.example.com/v1/chat/completions", "m", "k",
            cloudLane = CloudLaneConf(cloudChatter = true),
        )!!
        assertTrue(on.contains("AiPlayerbot.LLMBotToBotChatChance = 25\n"))
        val off = LlmRuntimePolicy.confBlockExternal(
            "https://api.example.com/v1/chat/completions", "m", "k",
        )!!
        assertFalse(off.contains("AiPlayerbot.LLMBotToBotChatChance = 25\n"))
        // the OFF lane carries the tier default 10 -
        // pin it positively (delimiter-anchored) so a cloud-OFF regression
        // to inverted-priority economics cannot pass silently
        assertTrue(off.contains("AiPlayerbot.LLMBotToBotChatChance = 10\n"))
        val override = LlmRuntimePolicy.confBlockExternal(
            "https://api.example.com/v1/chat/completions", "m", "k",
            speech = BotLlmSpeech(botToBotChatChance = 7),
            cloudLane = CloudLaneConf(cloudChatter = true),
        )!!
        assertTrue(override.contains("AiPlayerbot.LLMBotToBotChatChance = 7\n"))
    }

    @Test
    fun deviceLaneNeverCarriesCloudKeys() {
        // Mirror case: the device-lane block (confBlock) never
        // emits any cloud key - LLMCloudChatter included - so key-on +
        // tier-off is byte-identical to plain device behavior.
        // The enumeration is the WHOLE CloudLaneConf
        // family - all 9 keys (LLMPartyReplyEnabled included).
        val block = LlmRuntimePolicy.confBlock(llmEnabled = true)!!
        for (key in listOf("LLMCloudChatter", "LLMPartyReplyEnabled",
                           "LLMCloudStreetSayPct",
                           "LLMCloudLineBudgetPerHour", "LLMStreetSayPerDay",
                           "LLMRpgChatPerDay", "LLMBotToBotPerDay",
                           "LLMCloudInteractivePerPlayerHour", "LLMDialogueFastLane")) {
            assertFalse(key, block.contains(key))
        }
    }

    @Test
    fun cloudTierGroundCannotLeakOntoTheDeviceLane() {
        // Leak-proof pins: ExternalApiTierActive() (the 0.13
        // conjunction ground) requires LLMProviderSafe AND ctx >= 65536.
        // Every device tier stays under the cloud ctx floor, the device
        // and debug blocks never emit the key, and the external block
        // always emits it as 1 - no device emission can open the cloud
        // lane, and no cloud emission can carry a stale provider flag.
        for (descriptor in LlmModelRegistry.all) {
            assertTrue(
                "tier ${descriptor.id} ctx ${descriptor.tierProfile.contextLength} " +
                    "crosses the cloud floor",
                descriptor.tierProfile.contextLength < CLOUD_TIER_MIN_CTX,
            )
        }
        assertFalse(
            LlmRuntimePolicy.confBlock(llmEnabled = true)!!.contains("LLMProviderSafe"),
        )
        val debugBlock = ServerRuntimeFiles.llmOverrides(
            uiEnabled = false,
            modelPresent = true,
            modelAbsolutePath = "/data/models/qwen.gguf",
            debugBuild = true,
        )!!
        assertFalse(debugBlock.contains("LLMProviderSafe"))
        val external = LlmRuntimePolicy.confBlockExternal(
            "https://api.example.com/v1/chat/completions", "m", "k",
        )!!
        assertTrue(external.contains("AiPlayerbot.LLMProviderSafe = 1\n"))
    }

    @Test
    fun noEmissionSurfaceEverWiresThePromptDumpFile() {
        // LLMPromptDumpFile writes full prompts (facts + verbatim
        // history) to a plaintext file - the app must never emit it on
        // any lane (device, debug in-process, or external).
        assertFalse(
            LlmRuntimePolicy.confBlock(llmEnabled = true)!!.contains("LLMPromptDumpFile"),
        )
        val debugBlock = ServerRuntimeFiles.llmOverrides(
            uiEnabled = false,
            modelPresent = true,
            modelAbsolutePath = "/data/models/qwen.gguf",
            debugBuild = true,
        )!!
        assertFalse(debugBlock.contains("LLMPromptDumpFile"))
        val external = LlmRuntimePolicy.confBlockExternal(
            "https://api.example.com/v1/chat/completions", "m", "k",
        )!!
        assertFalse(external.contains("LLMPromptDumpFile"))
    }

    @Test
    fun externalEndpointPortMustBeARealPort() {
        // A huge or non-numeric port literal is rejected here
        // (out of the UI entirely). The native parseUrl's std::stoi throws
        // out_of_range on such literals; the native catch fails the endpoint
        // closed instead of failing the boot, and this bound keeps the
        // value from being typed at all.
        assertNull(LlmRuntimePolicy.normalizeExternalEndpoint("http://127.0.0.1:99999999999999/api"))
        assertNull(LlmRuntimePolicy.normalizeExternalEndpoint("http://127.0.0.1:0/v1"))
        assertNull(LlmRuntimePolicy.normalizeExternalEndpoint("http://127.0.0.1:-1/v1"))
        assertNull(LlmRuntimePolicy.normalizeExternalEndpoint("http://127.0.0.1:abc/v1"))
        assertNull(LlmRuntimePolicy.normalizeExternalEndpoint("http://:8080/v1"))
        // real ports still normalize (bare origin gains the chat path)
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            LlmRuntimePolicy.normalizeExternalEndpoint("http://127.0.0.1:8080"),
        )
        assertEquals(
            "http://127.0.0.1:1/api/v1/generate",
            LlmRuntimePolicy.normalizeExternalEndpoint("http://127.0.0.1:1/api/v1/generate"),
        )
    }

    @Test
    fun chatterLinesEmitWheneverThePowerFileIsStagedAndGateOnItsFlag() {
        // the conf enables the SUBSYSTEM whenever the app staged
        // the power file (LLM on) - the FILE's enabled flag is the master
        // switch, re-read natively every tick, so the ambience toggle
        // works mid-session in both directions. No staged file = the
        // explicit silence-default 0 line and nothing else.
        val off = LlmRuntimePolicy.confBlock(llmEnabled = true)!!
        assertTrue(off.contains("AiPlayerbot.LLMChatterEnabled = 0\n"))
        assertFalse(off.contains("LLMChatterPowerFile"))
        assertFalse(off.contains("LLMChatterComposer"))

        // a staged power file always arms the subsystem (the file gates)
        val stagedOff = LlmRuntimePolicy.confBlock(
            llmEnabled = true, chatterPowerFile = "/srv/run/chatter-power.txt",
        )!!
        assertTrue(stagedOff.contains("AiPlayerbot.LLMChatterEnabled = 1\n"))
        assertTrue(stagedOff.contains("AiPlayerbot.LLMChatterPowerFile = \"/srv/run/chatter-power.txt\"\n"))
        assertFalse(stagedOff.contains("LLMChatterComposer"))

        // the full surface with a composer endpoint configured
        val on = LlmRuntimePolicy.confBlock(
            llmEnabled = true,
            chatterPowerFile = "/srv/run/chatter-power.txt",
            composerEndpoint = "https://api.example.com/v1/chat/completions",
            composerModel = "script-writer",
            composerApiKey = "sk-x",
        )!!
        assertTrue(on.contains("AiPlayerbot.LLMChatterEnabled = 1\n"))
        assertTrue(on.contains("AiPlayerbot.LLMChatterPowerFile = \"/srv/run/chatter-power.txt\"\n"))
        assertTrue(on.contains("AiPlayerbot.LLMChatterComposerUrl = https://api.example.com/v1/chat/completions\n"))
        assertTrue(on.contains("AiPlayerbot.LLMChatterComposerModel = script-writer\n"))
        assertTrue(on.contains("AiPlayerbot.LLMChatterComposerKey = sk-x\n"))

        // the external block carries the same surface
        val external = LlmRuntimePolicy.confBlockExternal(
            "http://127.0.0.1:9/v1/chat/completions", "m", "",
            chatterPowerFile = "/srv/run/chatter-power.txt",
        )!!
        assertTrue(external.contains("AiPlayerbot.LLMChatterEnabled = 1\n"))
        assertTrue(external.contains("AiPlayerbot.LLMChatterPowerFile = \"/srv/run/chatter-power.txt\"\n"))
    }

    // ---- external endpoint mode ------------------------------------------------

    @Test
    fun externalEndpointNormalizationAppendsTheChatCompletionsPath() {
        assertEquals(
            "https://api.openai.com" + LlmRuntimePolicy.EXTERNAL_CHAT_COMPLETIONS_PATH,
            LlmRuntimePolicy.normalizeExternalEndpoint("https://api.openai.com"),
        )
        // explicit paths are kept (proxies, gateways, /ollama/v1 routes)
        assertEquals(
            "http://192.168.1.10:5001/v1/chat/completions",
            LlmRuntimePolicy.normalizeExternalEndpoint("  http://192.168.1.10:5001/v1/chat/completions  "),
        )
        assertEquals(
            "https://gateway.example.com/custom/route",
            LlmRuntimePolicy.normalizeExternalEndpoint("https://gateway.example.com/custom/route"),
        )
    }

    @Test
    fun externalEndpointNormalizationFailsClosed() {
        assertNull(LlmRuntimePolicy.normalizeExternalEndpoint(""))
        assertNull(LlmRuntimePolicy.normalizeExternalEndpoint("   "))
        assertNull(LlmRuntimePolicy.normalizeExternalEndpoint("api.openai.com"))
        assertNull(LlmRuntimePolicy.normalizeExternalEndpoint("ftp://api.openai.com"))
        assertNull(LlmRuntimePolicy.normalizeExternalEndpoint("https://host with space.com"))
        assertNull(LlmRuntimePolicy.normalizeExternalEndpoint("https://host/\"quoted\""))
        assertNull(LlmRuntimePolicy.normalizeExternalEndpoint("https://host/back\\slash"))
        assertNull(LlmRuntimePolicy.normalizeExternalEndpoint("https://"))
    }

    @Test
    fun externalModelNormalizationDefaultsAndFailsClosed() {
        assertEquals("local", LlmRuntimePolicy.normalizeExternalModel(""))
        assertEquals("gpt-4o-mini", LlmRuntimePolicy.normalizeExternalModel(" gpt-4o-mini "))
        assertNull(LlmRuntimePolicy.normalizeExternalModel("model\"quoted"))
        assertNull(LlmRuntimePolicy.normalizeExternalModel("model with spaces"))
        assertNull(LlmRuntimePolicy.normalizeExternalModel("back\\slash"))
    }

    @Test
    fun externalApiKeyNormalizationFailsClosedOnQuoteCharacters() {
        assertEquals("", LlmRuntimePolicy.normalizeExternalApiKey(""))
        assertEquals("sk-abc123", LlmRuntimePolicy.normalizeExternalApiKey("  sk-abc123 "))
        assertNull(LlmRuntimePolicy.normalizeExternalApiKey("sk \"quoted\""))
        assertNull(LlmRuntimePolicy.normalizeExternalApiKey("back\\slash"))
    }

    @Test
    fun externalBlockTargetsTheEndpointAndCarriesTheKeyLine() {
        val block = LlmRuntimePolicy.confBlockExternal(
            endpoint = "https://api.openai.com/v1/chat/completions",
            model = "gpt-4o-mini",
            apiKey = "sk-test",
        )
        assertTrue(block!!.contains("AiPlayerbot.LLMEnabled = 2\n"))
        assertTrue(block.contains("AiPlayerbot.LLMBackend = 0"))
        assertTrue(block.contains("AiPlayerbot.LLMApiEndpoint = https://api.openai.com/v1/chat/completions"))
        assertTrue(block.contains("AiPlayerbot.LLMApiKey = sk-test"))
        // the model lands in the request body's model slot; the envelope and
        // emptied pattern keys are shared with the embedded block
        // (merge-order contract)
        assertTrue(block.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(block.contains("AiPlayerbot.LLMResponseStartPattern =\n"))
        assertTrue(block.endsWith("\n"))
        // provider-safe: external endpoints may reject unknown body keys
        assertFalse(block.contains("top_k"))
        assertFalse(block.contains("repeat_penalty"))
        assertFalse(block.contains("presence_penalty"))
        assertFalse(block.contains("min_p"))
        // Dedicated external budget: not a recycled device profile (API-class
        // models get room to use their context: 600-token replies, 128k
        // ctx, deeper memory — no on-device KV constraint off-device)
        assertTrue(block.contains("\"temperature\":0.7,"))
        assertTrue(block.contains("\"top_p\":0.9,"))
        assertTrue(block.contains("\"max_tokens\":600,"))
        assertTrue(block.contains("AiPlayerbot.LLMContextLength = 131072\n"))
        assertTrue(block.contains("AiPlayerbot.LLMFactsCap = 48\n"))
        assertTrue(block.contains("AiPlayerbot.LLMMemoriesTail = 16\n"))
        assertTrue(block.contains("AiPlayerbot.LLMGenerationTimeout = 60\n"))
        // The external-block governor
        // trio, value-pinned like the embedded one - a silent regression
        // of EXTERNAL_TIER's governor knobs would otherwise pass a looser
        // assert. The legacy
        // "Simultanious" spelling IS the conf key the native reads.
        listOf(
            "AiPlayerbot.LLMMaxSimultaniousGenerations = 4",
            "AiPlayerbot.LLMGovernorBotMax = 16",
            "AiPlayerbot.LLMGovernorGlobalMax = 48",
            // the window joins the trio (the whole
            // EXTERNAL_TIER governor block, one forEach)
            "AiPlayerbot.LLMGovernorWindow = 60",
        ).forEach { key ->
            assertTrue("missing governor line $key", block.contains(key + "\n"))
        }
    }

    @Test
    fun externalBlockOmitsTheKeyLineWhenNoKeyIsSet() {
        val block = LlmRuntimePolicy.confBlockExternal(
            endpoint = "http://192.168.1.10:8080/v1/chat/completions",
            model = "local",
            apiKey = "",
        )
        assertTrue(block!!.contains("AiPlayerbot.LLMApiEndpoint = http://192.168.1.10:8080/v1/chat/completions"))
        assertFalse(block.contains("AiPlayerbot.LLMApiKey"))
    }

    @Test
    fun externalBlockFailsClosedOnAnyUnusableField() {
        val endpoint = "https://api.openai.com/v1/chat/completions"
        assertNull(LlmRuntimePolicy.confBlockExternal(null, "m", ""))
        assertNull(LlmRuntimePolicy.confBlockExternal(endpoint, null, ""))
        assertNull(LlmRuntimePolicy.confBlockExternal(endpoint, "m", null))
    }

    companion object {
        // ExternalApiTierActive()'s ctx floor (the 0.13 conjunction
        // ground): every device tier must stay strictly under it.
        private const val CLOUD_TIER_MIN_CTX = 65536
    }
}
