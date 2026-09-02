package com.pocketrealm.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Forces BotProfiles' static initializer to run on the HOST JVM. A
 * shipped profile once had constructor requirements that were never
 * executed anywhere before release - BotProfiles failed class-init on
 * the world process's first profile lookup and every world start died
 * with ExceptionInInitializerError. Presence in the APK is not
 * verification; construction is.
 */
class BotProfilesCatalogTest {
    @Test
    fun everyCatalogProfileConstructs() {
        val ids = BotProfiles.ids()
        assertTrue("catalog unexpectedly small: ${ids.size}", ids.size >= 20)
        ids.forEach { id ->
            BotProfiles.find(id) ?: error("profiles map missing its own id $id")
        }
    }

    @Test
    fun forcedBenchmarkProfileMeetsAccountPoolContract() {
        val profile = BotProfiles.find("bench-forced-b1000-v1")
            ?: error("forced benchmark profile missing from catalog")
        assertEquals(1000, profile.selectedTarget)
        assertTrue(
            "account pool ${profile.accountCount} cannot supply ${profile.maximumOnline}",
            profile.accountCount * BotPopulationPolicy.CHARACTERS_PER_BOT_ACCOUNT >=
                profile.maximumOnline,
        )
    }
}
