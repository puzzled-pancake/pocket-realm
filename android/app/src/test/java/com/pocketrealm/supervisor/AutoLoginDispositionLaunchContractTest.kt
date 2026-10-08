package com.pocketrealm.supervisor

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-contract pin for the auto-login proof dispositions inside
 * AndroidRuntimeBackend.startClient: the live launch path needs the Android
 * backend (the device lane), so the wiring is pinned over the shipped source
 * — the same host-untestable-glue pattern as LanTunnelWiringContractTest.
 *
 * The INVALID_CREDENTIAL verdict log-and-continues; this pin exists because
 * AUTHORITY_UNAVAILABLE drifted back to error() once already. Both non-ACCEPT
 * verdicts must degrade the start to the manual login screen through the
 * shared credentialForLaunch seam (which hands over NO credential for them —
 * pinned host-side in AutoLoginPolicyTest), never fail the Android client
 * launch while the DesktopAppModel.autoLoginIfEnabled twin logs and keeps
 * manual login.
 */
class AutoLoginDispositionLaunchContractTest {

    private fun backendSource(): String {
        val candidates = listOf(
            File("src/main/java/com/pocketrealm/supervisor/AndroidRuntimeBackend.kt"),
            File("app/src/main/java/com/pocketrealm/supervisor/AndroidRuntimeBackend.kt"),
            File("android/app/src/main/java/com/pocketrealm/supervisor/AndroidRuntimeBackend.kt"),
            File("../android/app/src/main/java/com/pocketrealm/supervisor/AndroidRuntimeBackend.kt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?: throw AssertionError(
                "AndroidRuntimeBackend.kt not found from ${File(".").absolutePath}",
            )
    }

    /** The disposition-handling span: evaluate() through the credential hand-off. */
    private fun dispositionSpan(source: String): String {
        val start = source.indexOf("proofDisposition = AutoLoginCredentialProof.evaluate")
        assertTrue("the proof evaluation must exist in startClient", start >= 0)
        val end = source.indexOf(
            "verifiedAccount = AutoLoginCredentialProof.credentialForLaunch",
            start,
        )
        assertTrue(
            "the launch credential must flow through the shared credentialForLaunch seam",
            end > start,
        )
        return source.substring(start, end)
    }

    @Test fun noProofVerdictFailsTheAndroidClientLaunch() {
        val source = backendSource()
        val span = dispositionSpan(source)
        assertTrue(
            "both non-ACCEPT verdicts stay explicit log-and-continue legs",
            "Disposition.AUTHORITY_UNAVAILABLE" in span &&
                "Disposition.INVALID_CREDENTIAL" in span,
        )
        assertTrue(
            "each leg logs the kept-account degradation (the desktop twin's shape)",
            "AppLog.w" in span,
        )
        assertTrue(
            "AUTHORITY_UNAVAILABLE must never error() the start - an unavailable " +
                "authority injects no credential (credentialForLaunch yields null) and " +
                "degrades to manual login on BOTH platforms, exactly like " +
                "INVALID_CREDENTIAL",
            "error(" !in span,
        )
        assertTrue(
            "the hand-off decision must take the disposition, not a bypassed record",
            "AutoLoginCredentialProof.credentialForLaunch(proofDisposition, candidate)" in source,
        )
    }
}
