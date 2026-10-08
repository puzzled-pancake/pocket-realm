package com.pocketrealm.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round-6 R6-r6-1: plan §1 rule 7 — peer addresses are NEVER displayed.
 * The wave added `sourceIp` interpolation to the friend-request rows on
 * both platforms; this pin (shared, runs on both suites) fails if the
 * request-row copy ever interpolates the source address again. The host's
 * audit trail keeps the address; the user-facing surfaces name accounts.
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

    @Test
    fun `friend-request rows never interpolate the source address`() {
        val lanScreen = androidFile("ui/LanScreen.kt").readText()
        val playersScreen = desktopFile("desktop/ui/PlayersScreen.kt").readText()
        for ((name, text) in listOf("LanScreen.kt" to lanScreen, "PlayersScreen.kt" to playersScreen)) {
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
}
