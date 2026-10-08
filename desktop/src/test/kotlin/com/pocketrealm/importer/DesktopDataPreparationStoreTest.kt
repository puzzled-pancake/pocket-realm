package com.pocketrealm.importer

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.TwoQualifiedFlavors
import com.pocketrealm.desktop.DesktopStorageRoots
import com.pocketrealm.desktop.requireNativeIfDemanded
import com.pocketrealm.server.PreparedDataStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DESK-44/69/102: the desktop data-preparation twin's checkpoint contract,
 * pinned through a STUB-EXTRACTOR seam (the store's open runTool/requireTools
 * legs): the stage ORDER (DBC_MAPS → VMAP_EXTRACT → VMAP_ASSEMBLE → MMAPS →
 * MANIFEST journaled in row order), the per-map MoveMapGen lane, publication
 * through the SHARED PreparedDataStore (per-flavor active pointer + manifest
 * identity), and resumability — a crash mid-pipeline skips the verified
 * stages on the retry, and a fully published generation recovers with zero
 * extractor invocations. The journal half runs on the real DesktopSqlite
 * seam (skips cleanly when the DLL is not built).
 */
class DesktopDataPreparationStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun stagedDll(): File =
        File("../native/.build-win-x86_64/sqlite-seam-build/pocket_sqlite.dll").also {
            requireNativeIfDemanded("sqlite seam", it)
        }

    private val importId = "1f2e3d4c-5b6a-7789-aabb-ccddeeff0011"

    /** The synthetic client root: WoW.exe plus enough Data/ presence for the
     * store's root gate (the extractors themselves are stubbed). Tolerates
     * reuse: the recovery leg of a test drives prepare() with the SAME root. */
    private fun clientRoot(): File = File(folder.root, "client").apply {
        mkdirs()
        File(this, "WoW.exe").writeBytes(ByteArray(16))
        File(this, "Data").mkdirs()
    }

    /** The stub-extractor store: emulates each staged EXE's outputs and
     * records the invocation order; [failAt] simulates a crash at one stage. */
    private class StubStore(
        roots: DesktopStorageRoots,
        journal: ImportJournal,
        val calls: MutableList<String>,
        val failAt: DataStage? = null,
    ) : DataPreparationStore(roots, journal)

    /** Builds the stub store with the extractor emulation installed. */
    private fun stubStore(
        roots: DesktopStorageRoots,
        journal: ImportJournal,
        calls: MutableList<String>,
        failAt: DataStage? = null,
    ): DataPreparationStore = object : DataPreparationStore(roots, journal) {
        override fun requireTools() = Unit

        override suspend fun runTool(
            importId: String,
            stage: DataStage,
            workDir: File,
            tool: String,
            arguments: List<String>,
            suffix: String,
            onTick: (() -> Unit)?,
        ): String {
            calls += if (stage == DataStage.MMAPS) "MoveMapGen:${arguments.first()}" else tool
            if (stage == failAt) throw IllegalStateException("stub extractor crashed at $stage")
            when (stage) {
                DataStage.DBC_MAPS -> {
                    File(workDir, "dbc").mkdirs()
                    File(workDir, "maps").mkdirs()
                    repeat(PUBLICATION_MINIMUM_FILES) { index ->
                        File(workDir, "dbc/%05d.dbc".format(index)).writeBytes(byteArrayOf(1))
                        File(workDir, "maps/%03d%04d.map".format(index, 0)).writeBytes(byteArrayOf(1))
                    }
                }
                DataStage.VMAP_EXTRACT -> if (suffix == "repair") {
                    val repairRoot = File(arguments[arguments.indexOf("-o") + 1])
                    File(workDir, "Buildings").listFiles().orEmpty().filter { it.isFile }
                        .forEach { original ->
                            File(repairRoot, "Buildings/${original.name}").apply {
                                parentFile?.mkdirs()
                                writeBytes(byteArrayOf(1, 2, 3))
                            }
                        }
                } else {
                    File(workDir, "Buildings").mkdirs()
                    File(workDir, "Buildings/Human.wmo").writeBytes(byteArrayOf(1))
                }
                DataStage.VMAP_ASSEMBLE ->
                    File(workDir, "vmaps/000.vmtree").writeBytes(byteArrayOf(1))
                DataStage.MMAPS -> {
                    val mapId = arguments.first().toInt()
                    File(workDir, "mmaps").mkdirs()
                    File(workDir, "mmaps/%03d.mmap".format(mapId)).writeBytes(byteArrayOf(1))
                    File(workDir, "mmaps/%03d%02d%02d.mmtile".format(mapId, 0, 0))
                        .writeBytes(byteArrayOf(1))
                }
                DataStage.MANIFEST -> Unit // manifest hashing needs no extractor
            }
            return ""
        }
    }

    @Test
    fun `stages run in order, publish through PreparedDataStore, and recover with zero tool calls`() {
        stagedDll()
        val roots = DesktopStorageRoots(folder.newFolder("roots")).also { it.ensureDirectories() }
        val journal = ImportJournal(roots)
        val calls = mutableListOf<String>()
        try {
            val published = runBlocking {
                stubStore(roots, journal, calls).prepare(importId, clientRoot(), onTick = {})
            }
            assertEquals(
                "the five stages journal in lane order",
                listOf(DataStage.DBC_MAPS, DataStage.VMAP_EXTRACT, DataStage.VMAP_ASSEMBLE,
                    DataStage.MMAPS, DataStage.MANIFEST),
                journal.dataStages(importId).map { it.stage },
            )
            assertTrue(journal.dataStages(importId).all { it.state == DataStageState.VERIFIED })
            assertEquals(
                "the tool invocation order mirrors the lane: ad → vmap_extractor → " +
                    "vmap_assembler → MoveMapGen per map",
                listOf("ad.exe", "vmap_extractor.exe", "vmap_assembler.exe") +
                    (0 until MAP_IDS).map { "MoveMapGen:$it" },
                calls,
            )
            assertEquals(importId, published.id)
            // The shared fail-closed reader accepts the publication.
            val reader = PreparedDataStore(File(roots.content, "o11-server"))
            val active = reader.requireActiveEnvelope()
            assertEquals(importId, active.generation)
            // Per-flavor pointer namespace (the vanilla lane's canonical name).
            assertTrue(
                File(File(roots.content, "o11-server"), "active-${ClientFlavors.VANILLA.flavorId}.json")
                    .isFile,
            )
            assertTrue(
                "the manifest carries the qualified lane's build identity",
                File(active.root, "data-manifest.json").readText()
                    .contains("\"clientBuild\": ${ClientFlavors.VANILLA.build}"),
            )
        } finally {
            journal.close()
        }

        // A re-run after full publication recovers (re-hash + activate) and
        // invokes NO extractor.
        val recall = mutableListOf<String>()
        val recoveryJournal = ImportJournal(roots)
        try {
            val recovered = runBlocking {
                stubStore(roots, recoveryJournal, recall).prepare(importId, clientRoot())
            }
            assertEquals(importId, recovered.id)
            assertEquals("recovery re-hashes, it never re-extracts", emptyList<String>(), recall)
        } finally {
            recoveryJournal.close()
        }
    }

    @Test
    fun `a crash mid-pipeline resumes from the last verified stage`() {
        stagedDll()
        val roots = DesktopStorageRoots(folder.newFolder("roots")).also { it.ensureDirectories() }
        val journal = ImportJournal(roots)
        val failedCalls = mutableListOf<String>()
        try {
            runBlocking {
                stubStore(roots, journal, failedCalls, failAt = DataStage.VMAP_ASSEMBLE)
                    .prepare(importId, clientRoot())
            }
            fail("the stubbed crash must surface")
        } catch (expected: IllegalStateException) {
            assertEquals("stub extractor crashed at VMAP_ASSEMBLE", expected.message)
        }
        // The failed stage is journaled FAILED; the previous ones stay VERIFIED.
        assertEquals(DataStageState.FAILED, journal.dataStage(importId, DataStage.VMAP_ASSEMBLE)?.state)
        assertEquals(DataStageState.VERIFIED, journal.dataStage(importId, DataStage.DBC_MAPS)?.state)
        assertEquals(
            "the crash happened at the third tool (the stub records then throws)",
            listOf("ad.exe", "vmap_extractor.exe", "vmap_assembler.exe"),
            failedCalls,
        )
        journal.close()

        val resumeJournal = ImportJournal(roots)
        val resumeCalls = mutableListOf<String>()
        try {
            runBlocking {
                stubStore(roots, resumeJournal, resumeCalls).prepare(importId, clientRoot())
            }
            assertEquals(
                "the retry re-runs only the failed stage onward",
                listOf("vmap_assembler.exe") + (0 until MAP_IDS).map { "MoveMapGen:$it" },
                resumeCalls,
            )
        } finally {
            resumeJournal.close()
        }
    }

    /**
     * GAP-3 (owner census UIUX-3, the DataPreparationStore publish/recover
     * legs): with two qualified flavors, preparation resolves the ACTIVE
     * lane through the flavor machine — publication stamps the selected
     * lane's manifest identity and points ITS per-flavor pointer (the
     * former `qualified().single()` refused data preparation wholesale),
     * and recovery re-resolves the same lane with zero extractor calls.
     */
    @Test
    fun publishResolvesTheActiveLaneWhenTwoFlavorsQualify() {
        stagedDll()
        val roots = DesktopStorageRoots(folder.newFolder("roots-two-qualified")).also { it.ensureDirectories() }
        val journal = ImportJournal(roots)
        val calls = mutableListOf<String>()
        try {
            TwoQualifiedFlavors.install(selection = ClientFlavors.TBC.flavorId)
            val published = runBlocking {
                stubStore(roots, journal, calls).prepare(importId, clientRoot(), onTick = {})
            }
            assertEquals(importId, published.id)
            assertTrue(
                "publication points the ACTIVE (tbc) lane's pointer",
                File(File(roots.content, "o11-server"), "active-${ClientFlavors.TBC.flavorId}.json").isFile,
            )
            assertTrue(
                "a tbc publication never writes the vanilla lane's pointer",
                !File(File(roots.content, "o11-server"), "active-${ClientFlavors.VANILLA.flavorId}.json").isFile,
            )
            assertTrue(
                "the manifest carries the ACTIVE lane's build identity",
                File(published.root, "data-manifest.json").readText()
                    .contains("\"clientBuild\": ${ClientFlavors.TBC.build}"),
            )
        } finally {
            ActiveFlavor.reset()
            journal.close()
        }

        // Recovery (the same resolution, second call site) re-activates the
        // tbc lane's pointer with zero extractor invocations.
        val recoveryJournal = ImportJournal(roots)
        val recall = mutableListOf<String>()
        try {
            TwoQualifiedFlavors.install(selection = ClientFlavors.TBC.flavorId)
            val recovered = runBlocking {
                stubStore(roots, recoveryJournal, recall).prepare(importId, clientRoot())
            }
            assertEquals(importId, recovered.id)
            assertEquals("recovery re-hashes, it never re-extracts", emptyList<String>(), recall)
            assertTrue(
                "recovery re-points the ACTIVE lane's pointer",
                File(File(roots.content, "o11-server"), "active-${ClientFlavors.TBC.flavorId}.json").isFile,
            )
        } finally {
            ActiveFlavor.reset()
            recoveryJournal.close()
        }
    }

    @Test
    fun `prepare refuses a root without WoW exe before any stage runs`() {
        stagedDll()
        val roots = DesktopStorageRoots(folder.newFolder("roots")).also { it.ensureDirectories() }
        val journal = ImportJournal(roots)
        val calls = mutableListOf<String>()
        try {
            val empty = folder.newFolder("not-a-client")
            runBlocking { stubStore(roots, journal, calls).prepare(importId, empty) }
            fail("a client root without WoW.exe must refuse")
        } catch (expected: IllegalStateException) {
            assertTrue((expected.message ?: "").contains("WoW.exe"))
        } finally {
            journal.close()
        }
        assertEquals("the identity gate runs before any extractor", emptyList<String>(), calls)
    }

    private companion object {
        // The publication minimums the store validates (PreparedDataStore's
        // envelope gate: dbc ≥ 100, maps ≥ 100, vmapTrees ≥ 1, mmaps ≥ 1).
        const val PUBLICATION_MINIMUM_FILES = 100
        // 100 map tiles → 100 terrain-backed map ids, all MoveMapGen-driven.
        const val MAP_IDS = 100
    }
}
