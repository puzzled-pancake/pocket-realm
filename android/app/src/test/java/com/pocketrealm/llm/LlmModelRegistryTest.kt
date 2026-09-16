package com.pocketrealm.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registry contract: ids are stable selection keys,
 * tuned checkpoints carry verified 64-hex integrity pins, file names
 * never collide in filesDir/models, and unknown persisted ids resolve to the
 * default rather than crashing a realm start.
 */
class LlmModelRegistryTest {

    @Test
    fun tunedCheckpointsCarryVerifiedSha256Pins() {
        listOf(LlmModelRegistry.TUNED_E2B, LlmModelRegistry.TUNED_Q08).forEach { m ->
            assertEquals(64, m.sha256.length)
            assertTrue(m.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(m.size > 0)
            // exact pins — tamper with these and the
            // download/verify gates silently change meaning
            if (m.id == "gemma4-E2B-TUNED-q4_0") {
                assertEquals(3_360_144_672L, m.size)
                assertEquals(
                    "e267e9793bac7db4340103b840bce8b52412ec3545827160b449119379853665",
                    m.sha256,
                )
            }
            if (m.id == "qwen35-08b-CLEAN-tuned-q4_0") {
                assertEquals(501_452_160L, m.size)
                assertEquals(
                    "d87581a7ddd118f3193073226748881ea5fcbf48af40676e50071574b5e1f7aa",
                    m.sha256,
                )
            }
        }
    }

    @Test
    fun baseModelKeepsItsDownloadUrlAndUpstreamPin() {
        // the base pin is resolved from the upstream hub metadata (LFS
        // sha256), so downloads verify exactly like the tuned models
        assertEquals("e531007218dfab990486a5de7676a6932d6ea8dea233d1f698d7c21cf8a16889", LlmModelRegistry.BASE_E2B.sha256)
        assertEquals(2_620_370_976L, LlmModelRegistry.BASE_E2B.size)
        assertFalse(LlmModelRegistry.BASE_E2B.localOnly)
        assertTrue(LlmModelRegistry.BASE_E2B.url.startsWith("https://huggingface.co/"))
    }

    @Test
    fun idsAreUniqueStableAndFilesDoNotCollide() {
        val ids = LlmModelRegistry.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val files = LlmModelRegistry.all.map { it.fileName }
        assertEquals(files.size, files.toSet().size)
        // ids are persistence keys: changing one silently orphans selections
        assertEquals(
            listOf(
                "gemma4-E2B-TUNED-q4_0",
                "qwen35-08b-CLEAN-tuned-q4_0",
                "gemma-4-E2B-it-qat-UD-Q4_K_XL",
            ),
            ids,
        )
    }

    @Test
    fun unknownIdResolvesToTheDefaultNeverThrows() {
        assertEquals(LlmModelRegistry.DEFAULT_MODEL_ID, LlmModelRegistry.byId(null).id)
        assertEquals(LlmModelRegistry.DEFAULT_MODEL_ID, LlmModelRegistry.byId("").id)
        assertEquals(
            LlmModelRegistry.DEFAULT_MODEL_ID,
            LlmModelRegistry.byId("no-such-model-anymore").id,
        )
        // the default is TUNED_E2B; BASE is a non-default selection
        assertEquals(
            LlmModelRegistry.TUNED_E2B.id,
            LlmModelRegistry.DEFAULT_MODEL_ID,
        )
        assertNotEquals(
            LlmModelRegistry.DEFAULT_MODEL_ID,
            LlmModelRegistry.byId("gemma-4-E2B-it-qat-UD-Q4_K_XL").id,
        )
    }

    @Test
    fun everyProfilePinsRepeatPenaltyToOneForGemma() {
        // llama-server's 1.1 default degrades Gemma output; the registry
        // must never regress this pin
        LlmModelRegistry.all.forEach { m ->
            assertEquals("profile ${m.id}", 1.0, m.profile.repeatPenalty, 0.0)
            assertTrue(m.profile.maxTokens in 80..300)
        }
    }
}
