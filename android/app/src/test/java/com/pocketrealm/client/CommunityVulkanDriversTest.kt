package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The curated community driver list is reviewed compile-time data —
 * pinned GitHub release digests, MIT Mesa builds only, ids that can never be
 * mistaken for catalog or user-lane driver ids.
 */
class CommunityVulkanDriversTest {

    @Test
    fun theListIsTheReviewedManifestWithPinnedEntries() {
        val r8 = CommunityVulkanDrivers.find("community-turnip-26.0.0-r8")
        assertNotNull(r8)
        assertEquals(
            "https://github.com/K11MCH1/AdrenoToolsDrivers/releases/download/" +
                "v26.0.0-rc08/Turnip_v26.0.0_R8.zip",
            r8!!.url,
        )
        assertEquals(3_478_359L, r8.size)
        assertEquals(64, r8.sha256.length)
        assertNotNull(r8.librarySha256)
        assertEquals("adrenotools-zip", r8.format)
        assertEquals("MIT", r8.license)

        val r2 = CommunityVulkanDrivers.find("community-turnip-25.1.0-r2")!!
        assertEquals("bare-so", r2.format)
        // The bare-.so entry IS the library: artifact and library digests match.
        assertEquals(r2.sha256, r2.librarySha256)
        assertNull(CommunityVulkanDrivers.find("community-nope"))
        assertNull(CommunityVulkanDrivers.find(null))
    }

    @Test
    fun idsNeverCollideWithCatalogOrUserNamespaces() {
        val ids = CommunityVulkanDrivers.all().map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        ids.forEach { id ->
            assertTrue(id.startsWith("community-"))
            assertFalse(UserVulkanDriver.isUserId(id))
            assertNull(VulkanDriverCatalog.find(id))
        }
        // Labels become import display names — the 64-char label cap must hold,
        // and every note carries the not-qualified disclaimer verbatim.
        CommunityVulkanDrivers.all().forEach { entry ->
            assertTrue(entry.label.length <= 64)
            assertTrue(entry.summary.isNotBlank())
            assertTrue(entry.note.contains("not qualified by Pocket Realm"))
        }
    }

    @Test
    fun librarySha256LookupSupportsTheAlreadyImportedMark() {
        val entry = CommunityVulkanDrivers.all().first { it.librarySha256 != null }
        assertEquals(entry.id, CommunityVulkanDrivers.forLibrarySha256(entry.librarySha256!!)!!.id)
        assertNull(CommunityVulkanDrivers.forLibrarySha256("0".repeat(64)))
    }

    @Test
    fun modelRejectsUnpinnedOrUnprovenancedEntries() {
        val valid = CommunityVulkanDrivers.all().first()
        fun broken(mutate: (CommunityVulkanDriver) -> CommunityVulkanDriver) {
            assertTrue(runCatching { mutate(valid) }.isFailure)
        }
        broken { it.copy(url = "http://github.com/${it.repo}/releases/download/x/y.zip") }
        broken { it.copy(url = "https://evil.example.com/Turnip.zip") }
        broken { it.copy(url = "https://github.com/not-the-repo/x/releases/download/${it.release}/y.zip") }
        broken { it.copy(url = "https://github.com/${it.repo}/releases/download/other-release/y.zip") }
        broken { it.copy(url = it.url + "?token=1") }
        broken { it.copy(size = 0L) }
        broken { it.copy(size = CommunityVulkanDriver.MAX_DOWNLOAD_BYTES + 1) }
        broken { it.copy(sha256 = "zz".repeat(32)) }
        broken { it.copy(librarySha256 = "nope") }
        broken { it.copy(license = "Proprietary") }
        broken { it.copy(format = "qualcomm-blob") }
        broken { it.copy(id = VulkanDriverCatalog.TURNIP_26_1) }
        broken { it.copy(label = "x".repeat(65)) }
        broken { it.copy(version = " ") }
        broken { it.copy(repo = "../x") }
        broken { it.copy(repo = "nodots") }
        broken { it.copy(release = " ") }
        broken { it.copy(upstream = " ") }
        broken { it.copy(summary = "") }
        broken { it.copy(note = " ") }
    }

    @Test
    fun manifestProvenanceConstantsAreGenerated() {
        // Digest freshness (an upstream asset replaced after review) is a
        // download-time check — CommunityVulkanDriverDownload. Here
        // we pin that the projection carries the reviewed manifest's identity.
        assertEquals(1, GeneratedCommunityVulkanDrivers.SCHEMA)
        assertEquals("pinned-digest-download-only", GeneratedCommunityVulkanDrivers.POLICY)
        assertTrue(
            GeneratedCommunityVulkanDrivers.SOURCE_MANIFEST_SHA256.matches(Regex("[0-9a-f]{64}")),
        )
    }
}
