package com.pocketrealm.bots

import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * G3-10 work item 3: the classic constants ARE the vanilla bot-population
 * row — the 9-characters-per-account invariant comes from the pinned
 * `RandomPlayerbotFactory`. H3R2-1's per-flavor conf matrix enters as NEW
 * rows in the flavor-keyed table; the vanilla row can never be retuned in
 * place without failing this golden.
 */
class BotPopulationPolicyVanillaRowTest {

    @Test fun `vanilla row equals the literal classic constants`() {
        assertEquals(
            BotPopulationPolicy.PopulationRow(
                charactersPerBotAccount = 9,
                minCustomTarget = 10,
                maxSupportedTarget = 10_000,
                maxBuiltInTarget = 1_000,
                maxAccounts = 2_000,
            ),
            BotPopulationPolicy.VANILLA_ROW,
        )
        // And the constants themselves are the pinned literals.
        assertEquals(9, BotPopulationPolicy.CHARACTERS_PER_BOT_ACCOUNT)
        assertEquals(10, BotPopulationPolicy.MIN_CUSTOM_TARGET)
        assertEquals(10_000, BotPopulationPolicy.MAX_SUPPORTED_TARGET)
        assertEquals(1_000, BotPopulationPolicy.MAX_BUILT_IN_TARGET)
        assertEquals(2_000, BotPopulationPolicy.MAX_ACCOUNTS)
    }

    @Test fun `vanilla flavor resolves the vanilla row and unknown flavors fail loud`() {
        assertEquals(BotPopulationPolicy.VANILLA_ROW, BotPopulationPolicy.forFlavor(ClientFlavors.VANILLA))
        assertThrows(IllegalStateException::class.java) {
            BotPopulationPolicy.forFlavor(ClientFlavors.TBC.copy(flavorId = "cata"))
        }
    }
}
