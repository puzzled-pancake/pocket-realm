package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The shared timeframe core's contract (design §1–§5 of the multiplayer +
 * timeframe design doc), compiled into BOTH lanes via shared_tests:
 *
 * - stage gating: the stage-marker ordering predicate, the game_event
 *   active-window verdict (default passes the seed's truth through;
 *   non-final stages can only close/open within the DB's own content), and
 *   the v1 honesty pin that every availability block is empty + unmeasured;
 * - default stage: every flavor's default is its FINAL patch and the conf
 *   value never goes empty;
 * - impossible stage rejection: unregistered ids fail the strict lookup and
 *   degrade (never throw) at the settings layer;
 * - era-policy linkage: the chosen stage resolves the era class, the
 *   vanilla-1.12.1 baseline is the native lists verbatim, and the never-ban
 *   discipline holds.
 *
 * Shared (android-free) like the core it pins; both platforms run the same
 * file so the twins cannot fork.
 */
class TimeframeStageTest {

    // ---- the stage timelines ---------------------------------------------

    @Test
    fun everyFlavorShipsTheDesignStageList() {
        assertEquals(
            listOf(
                "vanilla-1.1", "vanilla-1.2", "vanilla-1.3", "vanilla-1.4",
                "vanilla-1.5", "vanilla-1.6", "vanilla-1.7", "vanilla-1.8",
                "vanilla-1.9", "vanilla-1.10", "vanilla-1.11", "vanilla-1.12.1",
            ),
            TimeframeStages.VANILLA.map { it.id },
        )
        assertEquals(
            listOf("tbc-2.0.1", "tbc-2.1", "tbc-2.2", "tbc-2.3", "tbc-2.4.3"),
            TimeframeStages.TBC.map { it.id },
        )
        assertEquals(
            listOf("wotlk-3.0.2", "wotlk-3.1", "wotlk-3.2", "wotlk-3.3", "wotlk-3.3.5a"),
            TimeframeStages.WOTLK.map { it.id },
        )
        // Orders are contiguous 0..n-1 per flavor and ids unique repo-wide.
        listOf(TimeframeStages.VANILLA, TimeframeStages.TBC, TimeframeStages.WOTLK)
            .forEach { stages ->
                assertEquals(stages.indices.toList(), stages.map { it.order })
            }
        assertEquals(TimeframeStages.ALL.size, TimeframeStages.ALL.map { it.id }.toSet().size)
    }

    // ---- default stage = the flavor's final patch -------------------------

    @Test
    fun defaultStageIsTheFlavorsFinalPatch() {
        assertEquals("vanilla-1.12.1", TimeframeStages.defaultStageId("vanilla"))
        assertEquals("tbc-2.4.3", TimeframeStages.defaultStageId("tbc"))
        assertEquals("wotlk-3.3.5a", TimeframeStages.defaultStageId("wotlk"))
        TimeframeStages.ALL.forEach { stage ->
            if (stage.isFinal) {
                assertEquals(stage.id, TimeframeStages.defaultStageId(stage.flavorId))
            } else {
                assertFalse(stage.id == TimeframeStages.defaultStageId(stage.flavorId))
            }
        }
        // Exactly one final stage per flavor.
        listOf("vanilla", "tbc", "wotlk").forEach { flavor ->
            assertEquals(1, TimeframeStages.stagesFor(flavor).count { it.isFinal })
        }
    }

    @Test
    fun confValueNeverGoesEmptyAndDefaultsToTheFinalPatch() {
        // The qualified lane today is vanilla-only (the shared registry's
        // exactly-one-qualified contract); the conf always names a CONCRETE
        // stage of that lane.
        assertEquals(
            listOf("vanilla"),
            ClientFlavors.qualified().map { it.flavorId },
        )
        assertEquals("vanilla-1.12.1", TimeframeStages.confValue(""))
        assertEquals("vanilla-1.12.1", TimeframeStages.confValue("vanilla-1.12.1"))
        // An explicit non-default stage of the qualified lane rides verbatim.
        assertEquals("vanilla-1.9", TimeframeStages.confValue("vanilla-1.9"))
    }

    @Test
    fun confValueForTheBootingFlavorNeverNamesAForeignLaneStage() {
        // R5-2 (code-red round 1): with two qualified flavors the conf of a
        // booting tbc world must name a tbc stage — never a vanilla id —
        // and a stored stage of a NON-ACTIVE qualified flavor degrades to
        // the ACTIVE flavor's final patch (no cross-flavor stage bleed the
        // moment per-stage era curation makes stage ids semantically
        // flavor-specific).
        assertEquals("tbc-2.4.3", TimeframeStages.confValueFor("", "tbc"))
        assertEquals("tbc-2.4.3", TimeframeStages.confValueFor("tbc-2.4.3", "tbc"))
        // a stored VANILLA stage while the tbc lane boots: degrade to tbc's
        // default, never emit a vanilla stage id for a tbc world
        assertEquals("tbc-2.4.3", TimeframeStages.confValueFor("vanilla-1.5", "tbc"))
        // and the mirror: a tbc stage while vanilla boots
        assertEquals(
            "vanilla-1.12.1",
            TimeframeStages.confValueFor("tbc-2.4.3", "vanilla"),
        )
        // an unknown/stale stored id degrades to the active flavor's default
        assertEquals("wotlk-3.3.5a", TimeframeStages.confValueFor("retired-stage", "wotlk"))
        // the conf is NEVER empty for a registered flavor
        assertEquals("vanilla-1.9", TimeframeStages.confValueFor("vanilla-1.9", "vanilla"))
    }

    // ---- stage gating ------------------------------------------------------

    @Test
    fun boundaryActivationFollowsTimelineOrder() {
        val early = TimeframeStages.requireForFlavor("vanilla", "vanilla-1.1")
        val mid = TimeframeStages.requireForFlavor("vanilla", "vanilla-1.9")
        val final = TimeframeStages.requireForFlavor("vanilla", "vanilla-1.12.1")
        // The 1.9 marker boundary is active exactly from 1.9 onward.
        assertFalse(TimeframeStages.isBoundaryActiveAt(early, "vanilla-1.9"))
        assertTrue(TimeframeStages.isBoundaryActiveAt(mid, "vanilla-1.9"))
        assertTrue(TimeframeStages.isBoundaryActiveAt(final, "vanilla-1.9"))
        // The final boundary is active only at the final stage itself.
        assertFalse(TimeframeStages.isBoundaryActiveAt(mid, "vanilla-1.12.1"))
        assertTrue(TimeframeStages.isBoundaryActiveAt(final, "vanilla-1.12.1"))
        // Never across flavors: the boundary must be of the same flavor.
        try {
            TimeframeStages.isBoundaryActiveAt(final, "tbc-2.0.1")
            fail("a cross-flavor boundary must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue("unknown vanilla" in expected.message!!)
        }
    }

    @Test
    fun defaultStagePassesTheSeedsOwnEventTruthThrough() {
        val final = TimeframeStages.requireForFlavor("vanilla", "vanilla-1.12.1")
        // Existing worlds are unaffected: whatever the seed says stands.
        assertTrue(TimeframeStages.isEventActiveAt(final, 1, seededActive = true))
        assertFalse(TimeframeStages.isEventActiveAt(final, 1, seededActive = false))
    }

    @Test
    fun nonFinalStagesGateOnlyWithinTheSeedsOwnContent() {
        val early = TimeframeStages.requireForFlavor("vanilla", "vanilla-1.1")
        // v1 honesty pin: every availability block is EMPTY + unmeasured
        // (the curated id sets land with the S4 DB pin), so every stage
        // currently equals the seed — stated, not hidden.
        val gating = TimeframeStages.eventGating(early)
        assertTrue(gating.openEventIds.isEmpty())
        assertTrue(gating.closeEventIds.isEmpty())
        assertFalse(gating.measured)
        assertTrue(TimeframeStages.isEventActiveAt(early, 7, seededActive = true))
        assertFalse(TimeframeStages.isEventActiveAt(early, 7, seededActive = false))
    }

    @Test
    fun availabilityFlagsDegradeToTheSeedUntilCurated() {
        // The directional rule the overlay machinery will implement with
        // measured sets: a non-final stage can only CLOSE what the seed has
        // open or OPEN what the seed has closed — never invent either.
        // Pinned here at the predicate level so the S4 curation cannot flip
        // the semantics silently.
        val early = TimeframeStages.requireForFlavor("vanilla", "vanilla-1.5")
        val seededOpenClosedAtStage = TimeframeStages.isEventActiveAt(early, 42, seededActive = true)
        val seededClosedOpenedAtStage =
            TimeframeStages.isEventActiveAt(early, 43, seededActive = false)
        // With empty blocks both verdicts equal the seed's own state.
        assertTrue(seededOpenClosedAtStage)
        assertFalse(seededClosedOpenedAtStage)
    }

    // ---- impossible stage rejection ----------------------------------------

    @Test
    fun unknownStageIdsAreRejectedAtTheStrictLayer() {
        // A patch string that was never a stage of the flavor.
        listOf(
            "vanilla", // bare flavor, no patch
            "vanilla-9.9", // never existed on the timeline
            "vanilla-1.12", // not the canonical 1.12.1 spelling
            "tbc-2.0.1", // registered, but of ANOTHER flavor
            "wotlk-3.3.5A", // case-sensitive canonical ids
        ).forEach { bogus ->
            assertNull(TimeframeStages.byId("vanilla", bogus))
            try {
                TimeframeStages.requireForFlavor("vanilla", bogus)
                fail("impossible stage \"$bogus\" must be refused")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "refusal must name the registered stages: ${expected.message}",
                    "registered stages" in expected.message!!,
                )
            }
        }
        assertNull(TimeframeStages.byId("nosuchflavor", "vanilla-1.1"))
        assertEquals("", TimeframeStages.defaultStageId("nosuchflavor"))
    }

    @Test
    fun storedStageValuesDegradeToTheDefaultNeverThrow() {
        // The settings layer's degrade-per-field posture (design §4.1):
        // "" stays "", a stage of the QUALIFIED lane survives, and anything
        // else — a stale stage of an unqualified flavor, a typo — degrades
        // to "" so a flavor change can never strand a world silently.
        val qualifiedVanillaOnly = listOf("vanilla")
        assertEquals("", TimeframeStages.resolveStoredIn("", qualifiedVanillaOnly))
        assertEquals(
            "vanilla-1.4",
            TimeframeStages.resolveStoredIn("vanilla-1.4", qualifiedVanillaOnly),
        )
        listOf(
            "vanilla-9.9", // impossible patch
            "tbc-2.4.3", // registered flavor, NOT qualified today
            "wotlk-3.0.2",
            "TBC-2.0.1", // case-sensitive
            "garbage",
        ).forEach { impossible ->
            assertEquals(
                "impossible stored stage \"$impossible\" must degrade to \"\"",
                "",
                TimeframeStages.resolveStoredIn(impossible, qualifiedVanillaOnly),
            )
        }
        // The registry-driven posture: the SAME stored value becomes valid the
        // day its flavor qualifies (a data change in ClientFlavors, not here).
        val qualifiedAll = listOf("vanilla", "tbc", "wotlk")
        assertEquals("tbc-2.4.3", TimeframeStages.resolveStoredIn("tbc-2.4.3", qualifiedAll))
        assertEquals("wotlk-3.0.2", TimeframeStages.resolveStoredIn("wotlk-3.0.2", qualifiedAll))
        assertEquals("", TimeframeStages.resolveStoredIn("vanilla-9.9", qualifiedAll))
        // And through the production wrapper, which reads the live registry.
        assertEquals("", TimeframeStages.resolveStored("tbc-2.4.3"))
        assertEquals("vanilla-1.1", TimeframeStages.resolveStored("vanilla-1.1"))
        assertEquals("", TimeframeStages.resolveStored("vanilla-9.9"))
    }

    // ---- era-policy linkage --------------------------------------------------

    @Test
    fun eraClassResolvesFromTheChosenStage() {
        // The resolution point exists per stage and is total over the registry.
        TimeframeStages.ALL.forEach { stage ->
            val eraClass = TimeframeStages.eraClassFor(stage)
            assertTrue(eraClass.alwaysBanTerms.isNotEmpty())
            assertTrue(eraClass.sensePhrases.isNotEmpty())
        }
    }

    @Test
    fun vanillaFinalStageBaselineIsTheNativeVerbatimLists() {
        val baseline = TimeframeStages.eraClassFor(
            TimeframeStages.requireForFlavor("vanilla", "vanilla-1.12.1"),
        )
        // PlayerbotLlmTruthCore.h EraAlwaysBanTerms, verbatim.
        // Round-4 R5-4-1: draenei/pandaren are corpus-proven vanilla-legitimate
        // (broadcast_text 5296 / npc_text 154 in the shipped 1.12.1 dump) and
        // are NEVER banned for vanilla.
        assertEquals(listOf("shattrath", "acherus"), baseline.alwaysBanTerms)
        // PlayerbotLlmTruthCore.h EraSensePhrases, verbatim (needle + negation flag).
        assertEquals(
            listOf(
                "flying mount" to false,
                "flying mounts" to false,
                "portal is open" to true,
                "portals are open" to true,
                "portal has opened" to true,
                "portals have opened" to true,
                "gilneas opened" to true,
                "gilneas is open" to true,
                "gilneas has opened" to true,
                "playable worgen" to false,
                "dalaran floats" to false,
                "sewers of dalaran" to false,
                "dalaran's sewers" to false,
                "ebon blade" to false,
                "wrath of the lich king" to false,
                "mists of pandaria" to false,
                "warlords of draenor" to false,
                "zereth mortis" to false,
                "korthia" to false,
                "revendreth" to false,
                "maldraxxus" to false,
                "ardenweald" to false,
                "nazjatar" to false,
                "hellfire peninsula" to false,
                "zangarmarsh" to false,
                "terokkar forest" to false,
                "blade's edge" to false,
                "netherstorm" to false,
                "shadowmoon valley" to false,
            ),
            baseline.sensePhrases.map { it.needle to it.negationEscapes },
        )
    }

    @Test
    fun neverBanDisciplineHoldsOverEveryStageClass() {
        // Design §5: context-allow words collide with earlier legitimate
        // vocabulary and are NEVER banned; only full later-era senses are.
        val forbiddenBans = setOf(
            "northrend", "outland", "dalaran", "lich king", "naxxramas",
            "kel'thuzad", "blood elf", "wrath", "legion", "death knight",
            "worgen",
        )
        TimeframeStages.ALL.forEach { stage ->
            val eraClass = TimeframeStages.eraClassFor(stage)
            forbiddenBans.forEach { word ->
                assertFalse(
                    "$word must never be an always-ban term",
                    word in eraClass.alwaysBanTerms,
                )
                assertFalse(
                    "$word must never be a bare sense-phrase needle",
                    eraClass.sensePhrases.any { it.needle == word },
                )
            }
        }
    }
}
