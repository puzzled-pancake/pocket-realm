package com.pocketrealm.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * plan rule 7 — peer addresses are NEVER displayed.
 * The wave added `sourceIp` interpolation to the friend-request rows on
 * both platforms; this pin (shared, runs on both suites) fails if the
 * request-row copy ever interpolates the source address again. The host's
 * audit trail keeps the address; the user-facing surfaces name accounts.
 *
 * (native-server): the same rule now covers the roster
 * — the account_list payload dropped the last-logon ADDRESS member, the
 * shared [com.pocketrealm.supervisor.FriendRosterJson] parser dropped the
 * field, and the desktop Friends card renders only the logon TIMESTAMP.
 */
class FriendRequestFlowSourceIpPinTest {

    private fun repoRoot(): File =
        File(System.getProperty("user.dir")).let { start ->
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "schemas/flavor.json").isFile }
                ?: error("repo root not found from ${start.absolutePath}")
        }

    private fun androidFile(rel: String) = File(repoRoot(), "android/app/src/main/java/com/pocketrealm/$rel")

    private fun desktopFile(rel: String) = File(repoRoot(), "desktop/src/main/kotlin/com/pocketrealm/$rel")

    private fun nativeFile(rel: String) = File(repoRoot(), "native/$rel")

    @Test
    fun `friend-request rows never interpolate the source address`() {
        val directJoinAdvanced = androidFile("ui/online/DirectJoinAdvanced.kt").readText()
        val playersScreen = desktopFile("desktop/ui/PlayersAdvanced.kt").readText()
        for ((name, text) in listOf("DirectJoinAdvanced.kt" to directJoinAdvanced, "PlayersAdvanced.kt" to playersScreen)) {
            assertTrue(
                "$name interpolates sourceIp into the friend-request row " +
                    "(plan §1 rule 7: peer addresses are never displayed)",
                !Regex("Request:.*sourceIp", RegexOption.DOT_MATCHES_ALL).containsMatchIn(text),
            )
        }
        // the shared flow module carries the standing contract marker
        val flow = androidFile("ui/FriendRequestFlow.kt").readText()
        assertTrue(
            "FriendRequestFlow.kt lost the never-displayed contract marker",
            FriendRequestFlowSourceIpPinTest::class.java
                .let { "NEVER" in flow && "sourceIp" in flow },
        )
    }

    @Test
    fun `roster surfaces never carry the last-logon address`() {
        // the producer: the payload emits no address member at all
        val world = nativeFile("realm-runtime/src/world_runtime.cpp").readText()
        assertTrue(
            "world_runtime.cpp still emits a lastLogonIp roster member " +
                "(plan §1 rule 7: peer addresses are never displayed)",
            "lastLogonIp" !in world,
        )
        assertTrue(
            "world_runtime.cpp account_list still selects l.ip from account_logons",
            !Regex("SELECT\\s+l\\.ip", RegexOption.IGNORE_CASE).containsMatchIn(world) &&
                !Regex("\"SELECT l\\.ip").containsMatchIn(world) &&
                !Regex("l\\.ip FROM account_logons").containsMatchIn(world),
        )
        // the shared consumer model: the field is gone, not merely unread
        val rosterJson = androidFile("supervisor/FriendRosterJson.kt").readText()
        assertTrue(
            "FriendRosterJson.AccountRow still carries a lastLogonIp field",
            "lastLogonIp" !in rosterJson,
        )
        // the display: the Friends card copy never interpolates it
        val playersScreen = desktopFile("desktop/ui/PlayersAdvanced.kt").readText()
        assertTrue(
            "PlayersScreen.kt still references lastLogonIp (plan §1 rule 7)",
            "lastLogonIp" !in playersScreen,
        )
        assertTrue(
            "PlayersScreen.kt renders an address after the logon timestamp",
            !Regex("\" from \\\$").containsMatchIn(playersScreen),
        )
    }
}
