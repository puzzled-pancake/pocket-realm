package com.pocketrealm.server

import com.pocketrealm.bots.BotProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merge-order guard for the appended playerbot LLM conf block.
 * ServerRuntimeFiles writes `BotProfile.playerbotConfig() +
 * LlmRuntimePolicy.confBlock(...)`, relying on Config.cpp's sequential
 * last-wins parse for the append to win over the base `LLMEnabled = 0`. The
 * parser below mirrors native/cmangos/src/shared/Config/Config.cpp Reload():
 * keys are lowercased, values have leading/trailing `"` characters trimmed,
 * the LAST assignment of a duplicated key wins, and — critically — any
 * non-comment line without `=` aborts the parse of the ENTIRE file, so the
 * guard also fails if an appended line can ever be malformed.
 */
class LlmConfMergeOrderTest {

    private fun parseConf(text: String): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trimStart()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) {
                return@forEach
            }
            val equals = line.indexOf('=')
            check(equals != -1) { "Config.cpp aborts the whole parse on this line: $line" }
            val key = line.substring(0, equals).trim().lowercase()
            val value = line.substring(equals + 1).trim().trim('"')
            entries[key] = value
        }
        return entries
    }

    private val launchableProfiles =
        BotProfiles.experiencePresets + BotProfiles.legacySelectablePresets

    @Test
    fun appendedBlockWinsOverTheBaseProfileDisabledLlmContract() {
        launchableProfiles.forEach { profile ->
            val conf = parseConf(
                profile.playerbotConfig() + LlmRuntimePolicy.confBlock(llmEnabled = true)!!,
            )
            assertEquals("2", conf["aiplayerbot.llmenabled"])
            assertEquals("0", conf["aiplayerbot.llmbackend"])
            assertEquals(
                "http://127.0.0.1:8080/v1/chat/completions",
                conf["aiplayerbot.llmapiendpoint"],
            )
            assertEquals("8192", conf["aiplayerbot.llmcontextlength"])
            // The base contract survives the append everywhere else.
            assertEquals("1", conf["aiplayerbot.enabled"])
            assertEquals("0", conf["aiplayerbot.commandserverport"])
        }
    }

    @Test
    fun quoteTrimmingParserLeavesTheJsonTemplateIntactAndPatternsEmpty() {
        val conf = parseConf(LlmRuntimePolicy.confBlock(llmEnabled = true)!!)
        // A9: the response patterns are emitted as EMPTY values - the native
        // client parses the chat-completions envelope as JSON, and empty keys
        // disable the regex path (an unset key would fall back to the
        // JSON-era native defaults, whose end pattern truncates at the first
        // escaped quote). The parser must record the empty override, not
        // drop the key.
        assertEquals("", conf["aiplayerbot.llmresponsestartpattern"])
        assertEquals("", conf["aiplayerbot.llmresponseendpattern"])
        // The JSON template survives edge-quote trimming untouched (it starts
        // with `{` and ends with `}`) and still carries every fill key the
        // C++ client substitutes before POSTing.
        val apiJson = conf["aiplayerbot.llmapijson"]!!
        listOf("<pre prompt>", "<context>", "<prompt>", "<post prompt>").forEach { key ->
            assertTrue("missing fill key $key in $apiJson", apiJson.contains(key))
        }
        assertTrue(apiJson.startsWith("{\"model\":"))
        assertTrue(apiJson.endsWith("\"stream\":false}"))
    }

    @Test
    fun baseProfilesCarryNoOtherLlmKeysThatCouldGoStaleAfterTheAppend() {
        // When the submenu runtime is enabled it supersedes the debug
        // in-process block (LLMBackend = 1, LLMModelPath, ...) by replacement,
        // not by per-key override — so the base profile must not pin any other
        // LLM key that would then linger with a stale value.
        launchableProfiles.forEach { profile ->
            val llmKeys = parseConf(profile.playerbotConfig()).keys
                .filter { it.startsWith("aiplayerbot.llm") }
            assertEquals(setOf("aiplayerbot.llmenabled"), llmKeys.toSet())
        }
    }

    @Test
    fun disabledRuntimeLeavesTheReviewedBaseContractAtLlmEnabledZero() {
        assertNull(LlmRuntimePolicy.confBlock(llmEnabled = false))
        launchableProfiles.forEach { profile ->
            val conf = parseConf(profile.playerbotConfig())
            assertEquals("0", conf["aiplayerbot.llmenabled"])
        }
    }

    @Test
    fun externalBlockWinsOverTheBaseProfileAndSurvivesQuoteTrimming() {
        launchableProfiles.forEach { profile ->
            val conf = parseConf(
                profile.playerbotConfig() + LlmRuntimePolicy.confBlockExternal(
                    endpoint = "https://api.openai.com/v1/chat/completions",
                    model = "gpt-4o-mini",
                    apiKey = "sk-test",
                )!!,
            )
            assertEquals("2", conf["aiplayerbot.llmenabled"])
            assertEquals("0", conf["aiplayerbot.llmbackend"])
            assertEquals(
                "https://api.openai.com/v1/chat/completions",
                conf["aiplayerbot.llmapiendpoint"],
            )
            assertEquals("sk-test", conf["aiplayerbot.llmapikey"])
            // the model lands in the request body; the shared envelope keeps
            // the fill keys and the stream:false tail under the same parser,
            // and the pattern override is the same empty value as embedded
            assertEquals("gpt-4o-mini", conf["aiplayerbot.llmapijson"]!!.takeAfterModelSlot())
            assertEquals("", conf["aiplayerbot.llmresponsestartpattern"])
            assertEquals("", conf["aiplayerbot.llmresponseendpattern"])
            assertEquals("1", conf["aiplayerbot.llmbanterenabled"])
            assertEquals("16384", conf["aiplayerbot.llmcontextlength"])
        }
    }

    private fun String.takeAfterModelSlot(): String =
        substringAfter("\"model\":\"").substringBefore("\"")
}
