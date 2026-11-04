package com.pocketrealm.addons

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2.0+ clients block the action APIs from add-on code, so the shared Lua
 * tree must never issue them on a 2.4.3/3.3.5a run: every call to the four
 * protected APIs has to live behind an `if AP.SECURE then return` early exit
 * (making it unreachable at runtime on those clients), and the two the
 * controller never needs (CastSpellByName, TargetUnit) must not appear at
 * all. This is the static gate over the tree the per-version projection
 * ships — the Lua itself is shared, so the guard is what makes the tbc/wotlk
 * build clean.
 */
class AndroidPortSecureApiStaticTest {
    private val addon: File by lazy {
        listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
            File("android/app/src/main/assets"),
            // Desktop JVM lane: the shared asset tree sits one level up.
            File("../android/app/src/main/assets"),
        ).first { it.isDirectory }.resolve("addons/android-port/AndroidPort")
    }
    private val luaSources: Map<String, String> by lazy {
        addon.listFiles { file -> file.extension == "lua" }.orEmpty()
            .associate { file -> file.name to file.readText() }
    }

    /** The APIs Blizzard protects from insecure add-on code in 2.0+. */
    private val guardedApis = listOf("UseAction", "UseContainerItem")

    /** The guarded-guard marker: an early exit on the secure-client detect. */
    private val secureGuard = "if AP.SECURE then return"

    @Test fun `every protected api call sits behind the secure-client guard`() {
        var calls = 0
        for ((name, source) in luaSources) {
            for (api in guardedApis) {
                val callRegex = Regex("\\b$api\\s*\\(")
                callRegex.findAll(source).forEach { match ->
                    calls += 1
                    // The enclosing function starts at the last `function`
                    // keyword before the call; the guard must appear between
                    // that boundary and the call itself.
                    val functionStart = source.lastIndexOf("function", match.range.first)
                    assertTrue(
                        "$name: $api call at offset ${match.range.first} has no " +
                            "enclosing function",
                        functionStart >= 0,
                    )
                    val span = source.subSequence(functionStart, match.range.first)
                    assertTrue(
                        "$name: the $api call is not dominated by the " +
                            "secure-client guard — a 2.4.3/3.3.5a client would " +
                            "block it",
                        span.contains(secureGuard),
                    )
                }
            }
        }
        assertTrue(
            "the scan must find the legacy call sites (a rename silently " +
                "empties this gate)",
            calls >= 2,
        )
    }

    @Test fun `cast and target protected apis never appear`() {
        val all = luaSources.values.joinToString("\n")
        assertFalse(
            "CastSpellByName must never be called by the controller",
            Regex("\\bCastSpellByName\\s*\\(").containsMatchIn(all),
        )
        assertFalse(
            "TargetUnit must never be called by the controller",
            Regex("\\bTargetUnit\\s*\\(").containsMatchIn(all),
        )
        val bindings = File(addon, "Bindings.xml").readText()
        assertFalse(Regex("\\b(CastSpellByName|TargetUnit|UseAction|UseContainerItem)\\s*\\(")
            .containsMatchIn(bindings))
    }

    @Test fun `the secure machinery exists and is version-gated`() {
        val bars = checkNotNull(luaSources["ActionBars.lua"])
        assertTrue(
            "the controller buttons must ride the secure action template",
            bars.contains("SecureActionButtonTemplate"),
        )
        assertTrue(
            "the secure template creation must sit behind the client detect",
            bars.contains("if AP.SECURE then"),
        )
        assertTrue(
            "the controller chords must drive the secure buttons through " +
                "owner-scoped override bindings",
            bars.contains("SetOverrideBindingClick("),
        )
        assertTrue(
            "the override bindings must never claim the player's binding set",
            !bars.contains("SetBindingClick("),
        )
        val core = checkNotNull(luaSources["Core.lua"])
        assertEquals(
            "the client detect is the single source of the secure branch",
            1, Regex("AP\\.SECURE = ").findAll(core).count(),
        )
        assertTrue(
            "combat deferrals must ride the shared queue",
            core.contains("AP.combatQueue") && core.contains("PLAYER_REGEN_ENABLED"),
        )
    }

    @Test fun `combat-sensitive stock frame work defers instead of blocking`() {
        val bars = checkNotNull(luaSources["ActionBars.lua"])
        assertTrue(
            "the stock bars must not be hidden mid-combat on 2.0+ clients",
            bars.contains("if AndroidPort:InCombat() then"),
        )
        val mover = checkNotNull(luaSources["FrameMover.lua"])
        assertTrue(
            "a frame shown mid-combat must defer its re-anchor",
            mover.contains("AndroidPort:DeferCombat(apply)"),
        )
        val core = checkNotNull(luaSources["Core.lua"])
        assertTrue(
            "Move UI must refuse politely while in combat",
            core.contains("Move UI waits until combat ends"),
        )
    }
}
