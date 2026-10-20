package com.pocketrealm.desktop

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R1-arch-2 (android-core review, serious): the friend-provisioning
 * cluster's TWO ends must never drift on the per-flavor expansion write
 * (FriendProvisionProtocol: "so the two ends cannot drift"). The Android
 * twin (AccountExpansionWiringTest) pins WorldRuntimeService; this pin
 * covers the desktop backend:
 *  - provisionAccountVerifier chains the same expansion resolution +
 *    setAccountAddonNative leg its own createAccount path carries (the
 *    verifier path was the only account-creation seam with NO addon write
 *    at all);
 *  - BOTH provision seams run the addon write on the ACCOUNT_EXISTS retry
 *    leg (approvalFailed returns the row to SUBMITTED; the host retries;
 *    the second attempt lands on ACCOUNT_EXISTS) — the retry must converge
 *    to the active flavor's expansion, never answer ok while the row is
 *    still silently classic;
 *  - the desktop expansion resolves through the BOOT lane
 * bootLane ?: ServerRuntimeFiles.serverFlavor —: the lane is
 *    pinned at world start from the persisted selection, so a Home switch
 *    after start can never re-lane the write).
 */
class DesktopAccountExpansionWiringTest {

    private fun backendSource(): String = locateSource(
        "desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackend.kt",
    )

    private fun locateSource(repoRelative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = dir?.let { File(it, repoRelative) }
            if (candidate != null && candidate.isFile) return candidate.readText()
            dir = dir?.parentFile
        }
        error("source not found walking up from ${System.getProperty("user.dir")}: $repoRelative")
    }

    private fun methodBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "method not found: $signature" }
        val bodyStart = source.indexOf('{', start)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        error("unbalanced braces for: $signature")
    }

    @Test
    fun verifierProvisionChainsTheExpansionWriteLikeItsCreateAccountPath() {
        val source = backendSource()
        val verifier = methodBody(
            source,
            "override suspend fun provisionAccountVerifier(",
        )
        val create = methodBody(
            source,
            "override suspend fun provisionAccount(",
        )
        for ((name, body) in listOf("provisionAccountVerifier" to verifier, "provisionAccount" to create)) {
            assertTrue(
                "$name: lost the F1-10 expansion resolution (the BOOT lane " +
                    "binding — R1-r2-1: a live-machine resolve re-lanes the " +
                    "write mid-start)",
                "(bootLane ?: ServerRuntimeFiles.serverFlavor()).accountExpansion" in body,
            )
            assertTrue(
                "$name: lost the addon write",
                "WorldNative.setAccountAddonNative(" in body,
            )
            // A failed addon write must fail the answer (the addon result is
            // answered, never dropped): every addon write site in these two
            // bodies is followed by an errorName answer leg.
            assertTrue(
                "$name: the addon result must feed the answer code",
                "ServerRuntimeContract.errorName(addon" in body,
            )
        }
    }

    @Test
    fun bothProvisionSeamsWriteTheExpansionOnTheAccountExistsRetryLeg() {
        val source = backendSource()
        val verifier = methodBody(
            source,
            "override suspend fun provisionAccountVerifier(",
        )
        val provision = methodBody(
            source,
            "override suspend fun provisionAccount(",
        )
        // The retry leg exists in both seams and writes the addon.
        for ((name, body) in listOf("provisionAccountVerifier" to verifier, "provisionAccount" to provision)) {
            assertTrue(
                "$name: the ACCOUNT_EXISTS retry leg must ensure the addon " +
                    "write (R1-arch-1/2: attempt 1 created the row and died " +
                    "before the expansion write; the retry must converge)",
                Regex(Regex.escape("ERR_ACCOUNT_EXISTS"), RegexOption.IGNORE_CASE)
                    .containsMatchIn(body),
            )
        }
        assertTrue(
            "provisionAccountVerifier: the ACCOUNT_EXISTS branch must prove " +
                "the row before the addon write (accountInfoNative)",
            Regex(
                Regex.escape("ERR_ACCOUNT_EXISTS") + "[\\s\\S]*?" +
                    Regex.escape("accountInfoNative") + "[\\s\\S]*?" +
                    Regex.escape("setAccountAddonNative"),
            ).containsMatchIn(verifier),
        )
        assertTrue(
            "provisionAccount: the ACCOUNT_EXISTS branch must prove the " +
                "password, then ensure the addon write",
            Regex(
                Regex.escape("ERR_ACCOUNT_EXISTS") + "[\\s\\S]*?" +
                    Regex.escape("verifyAccountPasswordNative") + "[\\s\\S]*?" +
                    Regex.escape("setAccountAddonNative"),
            ).containsMatchIn(provision),
        )
    }
}
