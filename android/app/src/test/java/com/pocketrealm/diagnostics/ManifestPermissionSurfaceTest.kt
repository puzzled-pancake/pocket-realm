package com.pocketrealm.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Allowlist gate on the app's declared permission surface: the manifest may
 * only carry permissions the code actually exercises, because every extra
 * entry widens the declared surface and trips Play review. Pinning the exact
 * set means reintroducing an unused permission (e.g. RECEIVE_BOOT_COMPLETED,
 * removed because no BOOT_COMPLETED receiver exists — the only static
 * receiver is SaveExitReceiver's custom action) fails loud here instead of
 * at store review. The manifest is located by walking up from the working
 * directory (unit tests run from `android/app`); a missing manifest FAILS
 * with an actionable message rather than skipping.
 */
class ManifestPermissionSurfaceTest {
    @Test
    fun manifestDeclaresExactlyTheAllowlistedPermissions() {
        val manifest = locateAndroidManifest()
        val declared = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifest)
            .getElementsByTagName("uses-permission")
            .let { nodes -> (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("android:name").nodeValue } }
            .toSet()

        assertEquals(
            "AndroidManifest.xml permission surface changed; update the allowlist " +
                "only together with the code that exercises the new permission",
            ALLOWED_PERMISSIONS,
            declared,
        )
    }

    private fun locateAndroidManifest(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = dir?.let { File(it, "app/src/main/AndroidManifest.xml") }
            if (candidate != null && candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        throw AssertionError(
            "app/src/main/AndroidManifest.xml not found walking up from ${System.getProperty("user.dir")}: " +
                "run the unit tests from android/app or the repository checkout so the declared " +
                "permission surface stays on this gate; this suite must fail loud, not skip.",
        )
    }

    private companion object {
        val ALLOWED_PERMISSIONS = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.WAKE_LOCK",
            "android.permission.REQUEST_INSTALL_PACKAGES",
        )
    }
}
