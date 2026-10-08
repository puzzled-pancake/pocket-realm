package com.pocketrealm.bots

import com.pocketrealm.client.ClientFlavors
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * H3R2-1 (census lanes item 16): the per-flavor population rows resolve for
 * every declared flavor and mirror `schemas/flavor.json` `bot_policy`
 * (`characters_per_account`), located by the same walk-up as
 * [com.pocketrealm.client.FlavorManifestParityTest]. The engine ceiling
 * behind the manifest value: vanilla/TBC take the pinned factory's non-TWO
 * path (`maxAllowed = 9 - count`), WotLK's MANGOSBOT_TWO path allows 10 —
 * the manifest (and therefore every row here) pins the headroom-conservative
 * 9 ("player character occupies one slot; client UI cap is 10 per realm").
 *
 * G3-10 add-only: this suite pins the LATER-ERA rows only; the vanilla row
 * stays pinned by [BotPopulationPolicyVanillaRowTest].
 */
class BotPopulationPolicyFlavorRowsTest {

    @Test fun `tbc and wotlk flavors resolve their own rows`() {
        assertEquals(BotPopulationPolicy.TBC_ROW, BotPopulationPolicy.forFlavor(ClientFlavors.TBC))
        assertEquals(BotPopulationPolicy.WOTLK_ROW, BotPopulationPolicy.forFlavor(ClientFlavors.WOTLK))
    }

    @Test fun `rows mirror the manifest bot_policy characters_per_account`() {
        val root = JSONObject(locateManifest().readText())
        val manifestById = (0 until root.getJSONArray("flavors").length())
            .associate { index ->
                val flavor = root.getJSONArray("flavors").getJSONObject(index)
                flavor.getString("id") to flavor.getJSONObject("bot_policy")
            }
        assertEquals(
            BotPopulationPolicy.CHARACTERS_PER_BOT_ACCOUNT,
            manifestById.getValue(ClientFlavors.VANILLA.flavorId).getInt("characters_per_account"),
        )
        assertEquals(
            BotPopulationPolicy.TBC_ROW.charactersPerBotAccount,
            manifestById.getValue(ClientFlavors.TBC.flavorId).getInt("characters_per_account"),
        )
        assertEquals(
            BotPopulationPolicy.WOTLK_ROW.charactersPerBotAccount,
            manifestById.getValue(ClientFlavors.WOTLK.flavorId).getInt("characters_per_account"),
        )
    }

    @Test fun `every row keeps the shared device bounds and the pool invariant`() {
        for (row in listOf(BotPopulationPolicy.VANILLA_ROW, BotPopulationPolicy.TBC_ROW, BotPopulationPolicy.WOTLK_ROW)) {
            assertEquals(BotPopulationPolicy.MIN_CUSTOM_TARGET, row.minCustomTarget)
            assertEquals(BotPopulationPolicy.MAX_SUPPORTED_TARGET, row.maxSupportedTarget)
            assertEquals(BotPopulationPolicy.MAX_BUILT_IN_TARGET, row.maxBuiltInTarget)
            assertEquals(BotPopulationPolicy.MAX_ACCOUNTS, row.maxAccounts)
            // The class's own pool invariant (RandomBotAccountCount * 9 >=
            // MaxRandomBots) must hold for every flavor's row.
            assertTrue(
                "row ${row.charactersPerBotAccount}x${row.maxAccounts} cannot cover ${row.maxSupportedTarget}",
                row.maxAccounts * row.charactersPerBotAccount >= row.maxSupportedTarget,
            )
            assertTrue(row.charactersPerBotAccount in 1..10)
        }
    }

    private fun locateManifest(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = dir?.let { File(it, "schemas/flavor.json") }
            if (candidate != null && candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        throw AssertionError(
            "schemas/flavor.json not found walking up from ${System.getProperty("user.dir")}: " +
                "run the unit tests from the repository checkout (workingDir = android/app or the " +
                "repo root) so the manifest stays on the parity gate; this suite must fail loud, not skip.",
        )
    }
}
