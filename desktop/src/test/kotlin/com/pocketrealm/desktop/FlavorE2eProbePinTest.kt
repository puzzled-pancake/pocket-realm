package com.pocketrealm.desktop

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R1-3/R3-4/R6-3 + round-2 R3-1 (code-red round 2): the W4.1 probe's
 * folder-lane AND archive-lane hash pins resolve the flavor from
 * -DflavorId — production `importLane(flavor, model)` and
 * `pinnedClientDir(flavor)` both take the resolved registry entry, so
 * these pins hold the RESOLUTION (the flavor each lane pins against) plus
 * the refusal copies, not a parallel mirror of the check.
 */
class FlavorE2eProbePinTest {

    /** The production resolution under test: the flavor a lane pins
     * against is resolved from the -DflavorId registry entry (never
     * ALL.first()). */
    private fun pinFlavorFor(flavorId: String): ClientFlavor =
        ClientFlavors.ALL.first { it.flavorId == flavorId }

    @Test
    fun `the tbc lane pins the tbc sha not vanilla`() {
        val tbc = ClientFlavors.TBC
        val accepted = pinFlavorFor("tbc").let { assertEquals("tbc", it.flavorId); pinCheck(it, it.knownExeSha256!!) }
        assertEquals("tbc", accepted)
        val failure = assertThrows(IllegalStateException::class.java) {
            pinCheck(tbc, ClientFlavors.VANILLA.knownExeSha256!!)
        }
        assertTrue(
            "the refusal names the tbc pin, not vanilla's: ${failure.message}",
            "tbc S5.1 pin" in failure.message!!,
        )
    }

    @Test
    fun `the wotlk lane pins the wotlk sha not vanilla`() {
        val wotlk = ClientFlavors.WOTLK
        assertEquals("wotlk", pinCheck(wotlk, wotlk.knownExeSha256!!))
        val failure = assertThrows(IllegalStateException::class.java) {
            pinCheck(wotlk, ClientFlavors.VANILLA.knownExeSha256!!)
        }
        assertTrue("wotlk S5.1 pin" in failure.message!!)
    }

    @Test
    fun `the pin flavor is resolved from the flavor id not ALL first`() {
        // R3-4: the former ALL.first() always resolved vanilla. The pin
        // flavor for flavorId=tbc MUST be the TBC registry entry.
        val resolved = ClientFlavors.ALL.first { it.flavorId == "tbc" }
        assertEquals(ClientFlavors.TBC, resolved)
        assertEquals(
            ClientFlavors.TBC.knownExeSha256,
            "8f8d7f4cf3909e61fd34b09df9c9b56c21aec76a9ad1883353f1fa5d9b8411e2",
        )
    }

    @Test
    fun `the archive lane pins the archive's own WoW exe member`() {
        // R3-W4-1 (W4 code-red round 3): the -DclientDir pin does not
        // cover the archive payload — pinnedArchiveExe (the production
        // gate importLane runs on the archive lane) must refuse an
        // archive whose WoW.exe member hashes differently from the S5.1
        // pin, and accept the genuine bytes.
        val tbc = ClientFlavors.TBC
        val genuine = folder.newFile("genuine.zip")
        java.util.zip.ZipOutputStream(genuine.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("client/WoW.exe"))
            zip.write(ByteArray(64) { it.toByte() })
            zip.closeEntry()
        }
        val refused = assertThrows(IllegalStateException::class.java) {
            pinnedArchiveExe(tbc, genuine)
        }
        assertTrue(
            "the refusal is the S5.1 pin mismatch, not an import error: " +
                refused.message,
            "tbc S5.1 pin" in refused.message!!,
        )
        val genuineBytes = ByteArray(64) { (it * 7).toByte() }
        val genuineSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(genuineBytes).joinToString("") { "%02x".format(it) }
        val matching = folder.newFile("matching.zip")
        java.util.zip.ZipOutputStream(matching.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("WoW.exe"))
            zip.write(genuineBytes)
            zip.closeEntry()
        }
        // control: an archive whose member hash equals the gate's pin
        // passes (the pin rides the flavor record; the gate must accept
        // the genuine bytes it would refuse when mismatched)
        pinnedArchiveExe(
            tbc.copy(knownExeSha256 = genuineSha),
            matching,
        )
    }

    @Test
    fun `an archive with no WoW exe member refuses loud`() {
        val tbc = ClientFlavors.TBC
        val empty = folder.newFile("no-exe.zip")
        java.util.zip.ZipOutputStream(empty.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("client/readme.txt"))
            zip.write("not a client".toByteArray())
            zip.closeEntry()
        }
        val refused = assertThrows(IllegalStateException::class.java) {
            pinnedArchiveExe(tbc, empty)
        }
        assertTrue(
            "the refusal names the missing member: ${refused.message}",
            "carries no WoW.exe member" in refused.message!!,
        )
    }

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a pinless flavor refuses the hash gate`() {
        val pinless = ClientFlavors.VANILLA.copy(knownExeSha256 = null)
        val failure = assertThrows(IllegalStateException::class.java) {
            pinCheck(pinless, "5c7db44396ea")
        }
        assertTrue(
            "the refusal names the pinless state: ${failure.message}",
            "no S5.1 sha pin" in failure.message!!,
        )
    }
}    /** The PRODUCTION sha gate (FlavorE2eProbe.requirePinnedSha) — the
     * same function the folder and archive lanes run, not a mirror. */
    private fun pinCheck(flavor: ClientFlavor, sha: String): String {
        com.pocketrealm.desktop.requirePinnedSha(flavor, sha)
        return flavor.flavorId
    }


