package com.pocketrealm.database

import com.pocketrealm.database.DatabaseSqliteControlPlane.DATABASES
import com.pocketrealm.database.DatabaseRuntimeContract.SQLITE_PROVIDER_ID
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The desktop seal-identity leg (DESK-19/53/79/98), pure JVM: identity
 * derivation from the provenance, the shared seal round-trips, the
 * generation-marker revision record, and the tamper cross-check. No
 * natives — the identity/proof layer is exactly what must hold without
 * the seam DLL.
 */
class DesktopSqliteSealsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun provenanceJson(): JSONObject {
        val pins = JSONObject()
        DATABASES.forEachIndexed { index, database ->
            pins.put(
                database,
                JSONObject()
                    .put("sha256", (index + 1).toString(16).padStart(64, '0'))
                    .put("gzip_sha256", (index + 100).toString(16).padStart(64, '0'))
                    .put("size", 10L + index)
                    .put("gzip_size", 20L + index),
            )
        }
        return JSONObject()
            .put("schema", 1)
            .put("generator", "synthetic-staging")
            .put("seed_transcripts", pins)
    }

    private fun identity(provenance: JSONObject = provenanceJson()): DesktopSqliteSeals.Identity =
        DesktopSqliteSeals.identityFromProvenance(provenance.toString())

    @Test
    fun identityMirrorsTheAndroidSqlitePosture() {
        val provenance = provenanceJson()
        val derived = identity(provenance)
        assertEquals(SQLITE_PROVIDER_ID, derived.providerId)
        assertEquals(DATABASES.size, derived.migrationCount)
        // bootstrapSha256 aliases the provider-closure digest: the
        // provenance MINUS seed_transcripts.
        val closure = JSONObject(provenance.toString())
        closure.remove("seed_transcripts")
        assertEquals(
            com.pocketrealm.fs.FileDigests.sha256(closure.toString()),
            derived.bootstrapSha256,
        )
        // The corpus digest is the canonical pins over schema-list order.
        val canonical = DATABASES.joinToString("\n") { database ->
            "$database=" + provenance.getJSONObject("seed_transcripts")
                .getJSONObject(database).getString("sha256")
        }
        assertEquals(com.pocketrealm.fs.FileDigests.sha256(canonical), derived.manifestSha256)
    }

    @Test
    fun corpusDigestChangesWhenAnyPinChangesButClosureDoesNot() {
        val base = identity()
        val advanced = provenanceJson()
        advanced.getJSONObject("seed_transcripts").getJSONObject("classiclogs")
            .put("sha256", "f".repeat(64))
        val advancedIdentity = DesktopSqliteSeals.identityFromProvenance(advanced.toString())
        // The corpus identity moved (the stale-seal detector).
        assertTrue(base.manifestSha256 != advancedIdentity.manifestSha256)
        // Ownership stayed corpus-stable.
        assertEquals(base.bootstrapSha256, advancedIdentity.bootstrapSha256)
        assertEquals(base.providerId, advancedIdentity.providerId)
    }

    @Test
    fun migrationSealRoundTripsThroughTheSharedValidator() {
        val derived = identity()
        val sealText = DesktopSqliteSeals.migrationSeal(derived, 1234L)
        assertTrue(DesktopSqliteSeals.migrationSealCurrent(sealText, derived))
        // A different corpus identity must not validate the same marker.
        val other = derived.copy(manifestSha256 = "e".repeat(64))
        assertFalse(DesktopSqliteSeals.migrationSealCurrent(sealText, other))
        // And structurally broken marker text never validates.
        assertFalse(DesktopSqliteSeals.migrationSealCurrent("not json", derived))
        assertFalse(DesktopSqliteSeals.migrationSealCurrent(null, derived))
    }

    @Test
    fun cleanSealRoundTripsThroughTheSharedGenerationSeal() {
        val sealText = DesktopSqliteSeals.cleanSeal(42L)
        assertTrue(DesktopSqliteSeals.cleanSealCurrent(sealText))
        // A foreign provider's clean seal is not ours.
        assertFalse(
            com.pocketrealm.database.DatabaseGenerationSeal.clean(
                JSONObject().put("schema", 1).put("provider", "mariadb-x").put("stoppedAt", 42L).toString(),
                SQLITE_PROVIDER_ID,
            ),
        )
        assertFalse(DesktopSqliteSeals.cleanSealCurrent(null))
        assertFalse(DesktopSqliteSeals.cleanSealCurrent("{}"))
    }

    @Test
    fun revisionRecordRoundTripsAndRejectsLegacyMarkers() {
        val derived = identity()
        val sealText = DesktopSqliteSeals.migrationSeal(derived, 1234L)
        val markerText = DesktopSqliteSeals.generationMarker(
            generationUuid = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a",
            identity = derived,
            migrationSealSha256 = com.pocketrealm.fs.FileDigests.sha256(sealText),
        )
        val record = DesktopSqliteSeals.revisionRecord(markerText)
        assertNotNull(record)
        assertEquals(derived.providerId, record!!.identity.providerId)
        assertEquals(derived.manifestSha256, record.identity.manifestSha256)
        assertEquals(derived.bootstrapSha256, record.identity.bootstrapSha256)
        assertEquals(derived.migrationCount, record.identity.migrationCount)
        assertEquals(com.pocketrealm.fs.FileDigests.sha256(sealText), record.migrationSealSha256)
        // The pre-seal desktop layout (bare generation marker) is the
        // legacy/unprovable case.
        assertEquals(
            null,
            DesktopSqliteSeals.revisionRecord(
                JSONObject().put("schema", 1).put("generationUuid", "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a").toString(),
            ),
        )
        assertEquals(null, DesktopSqliteSeals.revisionRecord(null))
        assertEquals(null, DesktopSqliteSeals.revisionRecord("garbage"))
    }

    @Test
    fun tamperedMigrationMarkerFailsTheRecordCrossCheck() {
        val derived = identity()
        val sealText = DesktopSqliteSeals.migrationSeal(derived, 1234L)
        val record = DesktopSqliteSeals.revisionRecord(
            DesktopSqliteSeals.generationMarker(
                generationUuid = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a",
                identity = derived,
                migrationSealSha256 = com.pocketrealm.fs.FileDigests.sha256(sealText),
            ),
        )!!
        // Untampered: the record proves.
        assertTrue(DesktopSqliteSeals.sealMatchesRecord(sealText, record))
        // Byte-level tamper (a hand-edited completedAt) breaks the digest.
        val tampered = sealText.replace("\"completedAt\":1234", "\"completedAt\":9999")
        assertTrue("tamper must change the bytes", tampered != sealText)
        assertFalse(DesktopSqliteSeals.sealMatchesRecord(tampered, record))
        // A missing marker never proves.
        assertFalse(DesktopSqliteSeals.sealMatchesRecord(null, record))
    }

    @Test
    fun atomicMarkerWriteOverwritesAndRestampProducesAValidSeal() {
        val datadir = folder.newFolder("datadir")
        DesktopSqliteSealMarkers.atomicWriteMarker(datadir, "probe.json", "first")
        assertEquals("first", File(datadir, "probe.json").readText())
        DesktopSqliteSealMarkers.atomicWriteMarker(datadir, "probe.json", "second")
        assertEquals("second", File(datadir, "probe.json").readText())
        assertEquals(
            "no temp files strand in the datadir",
            emptyList<String>(),
            datadir.listFiles()!!.filter { it.name.endsWith(".tmp") }.map { it.name },
        )
        DesktopSqliteSealMarkers.restampCleanSeal(datadir)
        assertTrue(
            DesktopSqliteSeals.cleanSealCurrent(
                DesktopSqliteSealMarkers.cleanMarkerFile(datadir).takeIf(File::isFile)?.readText(),
            ),
        )
    }

    @Test
    fun identityFromProvenanceRejectsUnpinnedCorpus() {
        // A provenance without every schema-list pin must fail loudly
        // (the seeder lane never replays an unpinned transcript).
        val partial = provenanceJson()
        partial.getJSONObject("seed_transcripts").remove("classiclogs")
        try {
            DesktopSqliteSeals.identityFromProvenance(partial.toString())
            fail("identity must refuse a partially pinned provenance")
        } catch (expected: Exception) {
            assertTrue((expected.message ?: "").contains("classiclogs"))
        }
    }
}
