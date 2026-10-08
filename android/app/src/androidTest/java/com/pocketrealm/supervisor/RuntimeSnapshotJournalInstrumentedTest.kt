package com.pocketrealm.supervisor

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketrealm.client.ClientFlavors
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * G3-4 instrumented proof, on the platform org.json the shipping build
 * serializes with (insertion order preserved; `put(name, null)` removes the
 * mapping):
 *
 *  - the committed current-release journal fixture is the exact bytes the
 *    vanilla encode path writes on device (the deployed-byte pin the JVM
 *    lane cannot assert — its org.json artifact hash-scrambles key order);
 *  - a journaled `flavor:"tbc"` snapshot survives
 *    [AtomicSupervisorJournal] append/reload with the schema asserted 3
 *    throughout — the flavor entered as an optional field, never a schema
 *    bump (no `JOURNAL_SCHEMA = 4` anywhere; enforced by the leak sweep).
 */
@RunWith(AndroidJUnit4::class)
class RuntimeSnapshotJournalInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val journal = AtomicSupervisorJournal(context)

    @After fun cleanup() {
        journal.fileForTest().delete()
    }

    private fun fixtureBytes(name: String): ByteArray =
        javaClass.getResourceAsStream("/fixtures/g3/$name")!!.readBytes()

    private fun currentReleaseSnapshot(): RuntimeSnapshot =
        RuntimeSnapshotJournalCodec.decode(
            org.json.JSONObject(String(fixtureBytes("journal-current-release.json"), Charsets.UTF_8)),
        )

    @Test fun vanillaEncodeMatchesTheDeployedFixtureBytesOnDevice() {
        val encoded = RuntimeSnapshotJournalCodec.encode(currentReleaseSnapshot()).toString()
        assertArrayEquals(
            "device journal serialization drifted from the committed fixture:\n" +
                "fixture=${String(fixtureBytes("journal-current-release.json"), Charsets.UTF_8)}\n" +
                "actual=$encoded",
            fixtureBytes("journal-current-release.json"),
            encoded.toByteArray(Charsets.UTF_8),
        )
    }

    @Test fun tbcFlavoredJournalSurvivesAtomicAppendAndReloadAtSchemaThree() {
        val flavored = currentReleaseSnapshot()
            .copy(flavor = ClientFlavors.TBC.flavorId)
        assertEquals(RuntimeSnapshot.JOURNAL_SCHEMA, flavored.schema)

        journal.write(flavored)
        val reloaded = journal.read()
        assertNotNull("journal must reload", reloaded)
        assertEquals(RuntimeSnapshot.JOURNAL_SCHEMA, reloaded!!.schema)
        assertEquals(ClientFlavors.TBC.flavorId, reloaded.flavor)
        assertEquals(flavored.phase, reloaded.phase)
        assertEquals(flavored.sessionId, reloaded.sessionId)
        // The reloaded snapshot re-encodes to the same bytes the journal now
        // holds (round-trip is byte-stable on the platform serializer).
        assertArrayEquals(
            journal.fileForTest().readBytes(),
            RuntimeSnapshotJournalCodec.encode(reloaded).toString().toByteArray(Charsets.UTF_8),
        )
    }

    @Test fun preFlavorJournalFileReloadsAsVanillaWithoutQuarantine() {
        val journalFile: File = journal.fileForTest()
        journalFile.parentFile?.mkdirs()
        java.io.FileOutputStream(journalFile).use { it.write(fixtureBytes("journal-current-release.json")) }

        val reloaded = journal.read()
        assertNotNull("pre-flavor journal must load", reloaded)
        assertEquals(ClientFlavors.VANILLA.flavorId, reloaded!!.flavor)
        assertEquals(RuntimeSnapshot.JOURNAL_SCHEMA, reloaded.schema)
        assertEquals(RuntimePhase.STOPPED, reloaded.phase)
    }
}
