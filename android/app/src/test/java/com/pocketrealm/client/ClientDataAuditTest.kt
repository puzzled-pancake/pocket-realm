package com.pocketrealm.client

import com.pocketrealm.supervisor.RealmEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClientDataAuditTest {
    @get:Rule val folder = TemporaryFolder()

    private val mpq = byteArrayOf('M'.code.toByte(), 'P'.code.toByte(), 'Q'.code.toByte(), 0x1a)
    private val wotlk = ClientFlavors.WOTLK.let { ClientPeIdentity.Identity(it.versionResource, it.build, "sha", it.flavorId) }

    private fun wotlkSet(locale: String) = ClientFlavors.WOTLK.requiredMpqs.map { it.replace("enus", locale) }

    private fun audit(
        identity: ClientPeIdentity.Identity?,
        paths: List<String>,
        header: (String) -> ByteArray? = { mpq },
    ) = ClientDataAudit.audit(identity, paths, "x", readHeader = header)

    @Test fun aGermanWotlkClientPassesTheRequiredSet() {
        val verdict = audit(wotlk, wotlkSet("dede"))
        assertEquals(ClientFlavors.WOTLK, verdict.flavor)
        assertTrue(verdict.failures.toString(), verdict.failures.isEmpty())
    }

    @Test fun aClientMissingABaseArchiveStillRefuses() {
        val base = ClientFlavors.WOTLK.requiredMpqs.last { '/' !in it }
        val verdict = audit(wotlk, wotlkSet("enus") - base)
        assertTrue(verdict.failures.single().contains(base))
    }

    @Test fun extraPatchArchivesAreKeptWithAWarning() {
        val verdict = audit(wotlk, wotlkSet("enus") + listOf("patch-4.mpq", "patch-z.mpq"))
        assertTrue(verdict.failures.isEmpty())
        assertTrue(verdict.warnings.any { "Data/patch-4.mpq" in it })
    }

    @Test fun headersAreJudgedByRole() {
        val paths = wotlkSet("enus") + "patch-4.mpq"
        val userData = byteArrayOf('M'.code.toByte(), 'P'.code.toByte(), 'Q'.code.toByte(), 0x1b)
        assertTrue(audit(wotlk, paths) { userData }.failures.isEmpty())
        val brokenOptional = audit(wotlk, paths) { if (it == "patch-4.mpq") ByteArray(0) else mpq }
        assertTrue(brokenOptional.failures.isEmpty())
        assertTrue(brokenOptional.warnings.any { "Data/patch-4.mpq may not be a valid MPQ" in it })
        val brokenRequired = audit(wotlk, paths) { if (it == "common.mpq") ByteArray(0) else mpq }
        assertTrue(brokenRequired.failures.single().contains("not a valid MPQ: Data/common.mpq"))
    }

    @Test fun anExeWithoutAVersionResourceIsIdentifiedFromItsData() {
        val unstamped = ClientPeIdentity.Identity(null, null, "sha")
        val verdict = audit(unstamped, wotlkSet("enus"))
        assertEquals(ClientFlavors.WOTLK, verdict.flavor)
        assertTrue(verdict.warnings.any { "no version info" in it })
    }

    @Test fun anotherClientVersionStillRefuses() {
        val cata = ClientPeIdentity.Identity("4.3.4.15595", 15595, "sha")
        val verdict = audit(cata, wotlkSet("enus"))
        assertNull(verdict.flavor)
        assertTrue(verdict.failures.single().startsWith("Unsupported game version"))
    }

    @Test fun siblingVanillaBuildsTheRealmAcceptsResolve() {
        assertEquals(ClientFlavors.VANILLA, ClientFlavors.qualifiedByVersionResource("1.12.2.6005"))
        assertEquals(ClientFlavors.VANILLA, ClientFlavors.qualifiedByVersionResource("1.12.3.6141"))
        assertNull(ClientFlavors.qualifiedByVersionResource("1.11.2.5464"))
        assertNull(ClientFlavors.qualifiedByVersionResource("3.3.0.11159"))
    }

    @Test fun everyLocaleRealmlistIsProjected() {
        val root = folder.newFolder("client")
        File(root, "Data/deDE").mkdirs()
        File(root, "Data/deDE/locale-deDE.MPQ").writeText("x")
        File(root, "Data/enUS").mkdirs()
        File(root, "Data/enUS/realmlist.wtf").writeText("set realmlist logon.example.com\r\n")
        File(root, "Data/Interface").mkdirs()
        val written = ClientRealmEndpointProjection.projectAll(root, RealmEndpoint.LOCAL)
        assertEquals(3, written.size)
        val line = ClientRealmEndpointProjection.text(RealmEndpoint.LOCAL)
        assertEquals(line, File(root, "Data/enUS/realmlist.wtf").readText())
        assertEquals(line, File(root, "Data/deDE/realmlist.wtf").readText())
        assertFalse(File(root, "Data/Interface/realmlist.wtf").exists())
    }
}
