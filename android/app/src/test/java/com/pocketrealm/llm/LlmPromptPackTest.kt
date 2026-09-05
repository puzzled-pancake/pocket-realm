package com.pocketrealm.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-1 prompt-pack contract (plan v4): the default pack mirrors the
 * trained renderer order with seasoning DISABLED (native renders
 * byte-identical trained output until the player opts in); JSON round-trips
 * losslessly; token estimates stay positive and bounded.
 */
class LlmPromptPackTest {

    @Test
    fun defaultPackMirrorsTheTrainedOrderWithSeasoningDisabled() {
        val ids = LlmPromptPack().blocks.map { it.id }
        // trained-contract order first, seasoning appended inside the
        // instruction span (never a new top-level segment)
        val trainedOrder = listOf(
            "identity", "tools-note", "bible", "no-narrate", "backstory",
            "relationship", "absence", "facts",
            "memories-tail", "state", "bridge-note",
        )
        assertEquals(trainedOrder, ids.take(trainedOrder.size))
        val seasoning = listOf(
            "voice-lock", "rule-autonomy", "rule-anti-omniscient",
            "rule-boldness", "rule-salience", "ban-list", "scene-close",
            "initiative-opener", "mood-weather", "player-persona",
        )
        assertEquals(seasoning, ids.takeLast(seasoning.size))
        // plan v5 S.2: the persona card ships disabled with an EMPTY body -
        // its silence-doctrine default (renders nothing until written)
        val persona = LlmPromptPack().blocks.first { it.id == "player-persona" }
        assertFalse("player-persona must ship disabled", persona.enabledByDefault)
        assertEquals("player-persona must ship empty", "", persona.body)
        // frozen default: every trained block on, every seasoning block off
        val byId = LlmPromptPack().blocks.associateBy { it.id }
        trainedOrder.forEach { assertTrue("trained block $it must ship enabled", byId[it]!!.enabledByDefault) }
        seasoning.forEach { assertFalse("seasoning block $it must ship disabled", byId[it]!!.enabledByDefault) }
    }

    @Test
    fun blockIdsAreUniqueAndStable() {
        val ids = LlmPromptPack().blocks.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // ids are persistence keys (per-preset deltas, JSON): changing one
        // silently orphans player edits
        assertTrue(ids.contains("voice-lock"))
        assertTrue(ids.contains("rule-boldness"))
        assertTrue(ids.contains("scene-close"))
    }

    @Test
    fun tokenEstimatesArePositiveAndBounded() {
        val pack = LlmPromptPack()
        pack.blocks.forEach { block ->
            val est = block.estimatedTokens()
            assertTrue("estimate for ${block.id} must be positive", est >= 1)
            assertTrue("estimate for ${block.id} must stay bounded", est <= 500)
        }
        // the enabled (trained-default) total must leave reply room inside
        // the smallest on-device ctx (6144): prompt + 230 reply + margin
        val total = pack.enabledTokenEstimate()
        assertTrue("enabled total $total must leave reply room", total + 230 + 8 < 6144)
    }

    @Test
    fun jsonRoundTripsLosslessly() {
        val pack = LlmPromptPack()
        val parsed = LlmPromptPack.parse(pack.serialize())
        assertNotNull(parsed)
        assertEquals(pack.version, parsed!!.version)
        assertEquals(pack.blocks.map { it.id }, parsed.blocks.map { it.id })
        assertEquals(
            pack.blocks.map { it.body },
            parsed.blocks.map { it.body },
        )
        assertEquals(
            pack.blocks.map { it.enabledByDefault },
            parsed.blocks.map { it.enabledByDefault },
        )
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(LlmPromptPack.parse(""))
        assertNull(LlmPromptPack.parse("{not json"))
        assertNull(LlmPromptPack.parse("""{"version":1,"blocks":[]}"""))
    }

    @Test
    fun oversizeBodiesFallBackAtResolveKeepingThePlayersFlag() {
        // length rejection happens at RESOLVE (default body + the
        // player's enabled flag), not at parse - a parse-time drop would
        // silently revert the player's on/off edit too
        val big = "y".repeat(2001)
        val withBig = LlmPromptPack().blocks.map {
            if (it.id == "voice-lock") it.copy(body = big, enabledByDefault = true) else it
        }
        val parsed = LlmPromptPack.parse(LlmPromptPack(blocks = withBig).serialize())
        assertNotNull(parsed)
        val resolved = LlmPromptPack.resolve(parsed!!.serialize())
        val voice = resolved.blocks.first { it.id == "voice-lock" }
        assertEquals(
            LlmPromptPack().blocks.first { it.id == "voice-lock" }.body,
            voice.body,
        )
        assertTrue("the enabled flag survives the oversize fallback", voice.enabledByDefault)
    }

    @Test
    fun resolveRestoresTrainedOrderAndDropsUnknownIds() {
        // a hand-edited pack with a reordered trained block and an
        // unknown id: trained ids snap back to the frozen order, the
        // unknown id is dropped, the player's seasoning order stands
        val reordered = LlmPromptPack(
            blocks = listOf(
                LlmPromptPack().blocks.first { it.id == "bible" },
                LlmPromptPack().blocks.first { it.id == "identity" },
                LlmPromptPack().blocks.first { it.id == "scene-close" },
                LlmPromptPack().blocks.first { it.id == "voice-lock" },
                LlmPromptBlock(id = "not-a-block", title = "X", body = "y"),
            ),
        )
        val resolved = LlmPromptPack.resolve(reordered.serialize())
        val ids = resolved.blocks.map { it.id }
        // the trained head snaps back to the FULL canonical order
        assertEquals(
            listOf(
                "identity", "tools-note", "bible", "no-narrate", "backstory",
                "relationship", "absence", "facts",
                "memories-tail", "state", "bridge-note",
            ),
            ids.take(11),
        )
        assertFalse("unknown ids are dropped", "not-a-block" in ids)
        // seasoning follows the player's edit: scene-close stays ahead of
        // voice-lock in the tail
        assertTrue(ids.indexOf("scene-close") < ids.indexOf("voice-lock"))
        // every known block survives resolve
        assertEquals(LlmPromptPack().blocks.size, resolved.blocks.size)
    }

    @Test
    fun resolveRestoresMissingSeasoningBlocksAfterThePlayerTail() {
        // a pack written before mood-weather existed (or hand-trimmed):
        // the missing seasoning block comes back from the defaults,
        // appended after the player's own seasoning order
        val trimmed = LlmPromptPack(
            blocks = LlmPromptPack().blocks.filter { it.id != "mood-weather" },
        )
        val resolved = LlmPromptPack.resolve(trimmed.serialize())
        val ids = resolved.blocks.map { it.id }
        assertTrue("missing seasoning block is restored", "mood-weather" in ids)
        assertEquals(LlmPromptPack().blocks.size, resolved.blocks.size)
        // restored lands AFTER the player's own tail, still inside the
        // seasoning region (never above the trained tail)
        assertEquals(ids.last(), "mood-weather")
        val trainedCount = 11
        assertTrue(ids.indexOf("mood-weather") >= trainedCount)
        // position is distinguishable from "default slot": a REORDERED
        // player tail keeps its order, and the restored block still
        // lands after it (not at its default-relative position)
        val reorderedTail = LlmPromptPack().blocks.toMutableList().apply {
            removeAll { it.id in LlmPromptPack.SEASONING_IDS }
            addAll(
                11,
                LlmPromptPack().blocks.filter { it.id in LlmPromptPack.SEASONING_IDS }
                    .filter { it.id != "mood-weather" }
                    .reversed(),
            )
        }
        val resolved2 = LlmPromptPack.resolve(LlmPromptPack(blocks = reorderedTail).serialize())
        val ids2 = resolved2.blocks.map { it.id }
        // pin the FULL tail: the player's reversal survives verbatim and
        // the restored block lands after it (partial index comparisons
        // are subsumed by the uniqueness + last-position facts)
        assertEquals(
            listOf(
                "player-persona", "initiative-opener", "scene-close", "ban-list",
                "rule-salience", "rule-boldness", "rule-anti-omniscient",
                "rule-autonomy", "voice-lock", "mood-weather",
            ),
            ids2.drop(11),
        )
    }

    @Test
    fun resolveKeepsInvalidBodiesFailingOpenToDefaultBody() {
        // a body edit that breaks transport keeps the player's enabled
        // flag but falls back to the default body (never silence)
        val withBad = LlmPromptPack().blocks.map {
            if (it.id == "voice-lock") it.copy(body = "bad \" quote", enabledByDefault = true) else it
        }
        val resolved = LlmPromptPack.resolve(LlmPromptPack(blocks = withBad).serialize())
        val voice = resolved.blocks.first { it.id == "voice-lock" }
        assertEquals(
            LlmPromptPack().blocks.first { it.id == "voice-lock" }.body,
            voice.body,
        )
        assertTrue(
            "the player's enabled flag survives the body fallback",
            voice.enabledByDefault,
        )
    }

    @Test
    fun resolveFailsOpenToDefaultPackOnCorruptJson() {
        val resolved = LlmPromptPack.resolve("{corrupt")
        assertEquals(
            LlmPromptPack().blocks.map { it.id },
            resolved.blocks.map { it.id },
        )
    }
}
