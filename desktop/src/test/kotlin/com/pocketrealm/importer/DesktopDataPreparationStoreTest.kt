package com.pocketrealm.importer

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.TwoQualifiedFlavors
import com.pocketrealm.desktop.DesktopStorageRoots
import com.pocketrealm.server.publicationMinimums
import com.pocketrealm.desktop.requireNativeIfDemanded
import com.pocketrealm.server.PreparedDataStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
                    // R1-1/R6-3: the twin's publication gate enforces the
                    // ACTIVE flavor's floors, so the stub emits the WORST
                    // (wotlk) row's counts — every lane's gate passes.
                    val floors = listOf("vanilla", "tbc", "wotlk").map { publicationMinimums(it) }
                    repeat(floors.maxOf { it.dbc }) { index ->
                        File(workDir, "dbc/%05d.dbc".format(index)).writeBytes(byteArrayOf(1))
                    }
                    val mapIds = MAP_IDS
                    val mapsPerId = floors.maxOf { it.maps } / mapIds + 1
                    repeat(mapIds) { id ->
                        repeat(mapsPerId) { tile ->
                            File(workDir, "maps/%03d%04d.map".format(id, tile)).writeBytes(byteArrayOf(1))
                        }
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
                DataStage.VMAP_ASSEMBLE -> {
                    File(workDir, "vmaps").mkdirs()
                    // vmapTrees floor for the worst flavor, over distinct ids.
                    val vtreeIds = listOf("vanilla", "tbc", "wotlk")
                        .map { publicationMinimums(it).vmapTrees }.max()
                    repeat(vtreeIds) { id ->
                        File(workDir, "vmaps/%03d.vmtree".format(id)).writeBytes(byteArrayOf(1))
                    }
                }
                DataStage.MMAPS -> {
                    val mapId = arguments.first().toInt()
                    File(workDir, "mmaps").mkdirs()
                    File(workDir, "mmaps/%03d.mmap".format(mapId)).writeBytes(byteArrayOf(1))
                    // mmapTiles floor / distinct ids: enough tiles per id that
                    // the aggregate clears the worst flavor's mmapTiles floor.
                    val tilesPerId = listOf("vanilla", "tbc", "wotlk")
                        .map { publicationMinimums(it).mmapTiles }.max() / MAP_IDS + 1
                    repeat(tilesPerId) { tile ->
                        File(workDir, "mmaps/%03d%02d%02d.mmtile".format(mapId, 0, tile))
                            .writeBytes(byteArrayOf(1))
                    }
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

    @Test
    fun `publication floors mirror the python lane per flavor`() {
        // R1-1/R6-3 (W2 code-red round 1): the Kotlin floors are the measured
        // mirror of tools/win_prepare_data.py's FLAVOR_MIN_COUNTS — the
        // desktop prep gate AND the shared boot envelope both consume
        // publicationMinimums, so a truncated tbc/wotlk generation the
        // official lane refuses can neither publish through the twin nor
        // boot through the envelope.
        val source = File("../tools/win_prepare_data.py").readText()
        val block = source.substringAfter("FLAVOR_MIN_COUNTS = {").substringBefore("\n}")
        for (flavor in listOf("vanilla", "tbc", "wotlk")) {
            val row = block.substringAfter("\"" + flavor + "\": {").substringBefore("}")
            val minimums = publicationMinimums(flavor)
            fun floor(key: String): Int =
                Regex("\"" + key + "\": (\\d+)").find(row)!!.groupValues[1].toInt()
            assertEquals("$flavor dbc floor", floor("dbc"), minimums.dbc)
            assertEquals("$flavor maps floor", floor("maps"), minimums.maps)
            assertEquals("$flavor vmapTrees floor", floor("vmapTrees"), minimums.vmapTrees)
            assertEquals("$flavor mmapMaps floor", floor("mmapMaps"), minimums.mmapMaps)
            assertEquals("$flavor mmapTiles floor", floor("mmapTiles"), minimums.mmapTiles)
        }
        // the wiring is structural, not aspirational: both consumers route
        // through the shared resolver (the repo's source-pin discipline).
        val twin = File("src/main/kotlin/com/pocketrealm/importer/DataPreparationStore.kt").readText()
        assertTrue("the twin's publication gate routes the flavor",
            "validatePublicationCounts(counts, flavor)" in twin)
        assertTrue("the twin consumes the shared resolver",
            "publicationMinimums(flavor.flavorId)" in twin)
        val envelope = File("../android/app/src/main/java/com/pocketrealm/server/PreparedDataStore.kt").readText()
        assertTrue("the boot envelope consumes the shared resolver",
            "publicationMinimums(flavor.flavorId)" in envelope)
        // R4-r5-2 (W2 code-red round 5): an unregistered flavorId FAILS LOUD
        // (the python dict raises KeyError) — never a silent vanilla-flat
        // default for a future registry-added flavor. requireNotNull's
        // IllegalArgumentException, mirroring the sibling
        // ImportStoragePlanner.flavorExtractedEstimate's fail-loud posture.
        val unregistered = assertThrows(IllegalArgumentException::class.java) {
            publicationMinimums("cata")
        }
        assertTrue(
            unregistered.message,
            unregistered.message.orEmpty().contains("no registered publication floors"))
    }

    @Test
    fun `mmtile and oracle contracts mirror the python lane mechanically`() {
        // R1-r5-1 (W2 code-red round 5): the twin's mmtile contracts and the
        // MoveMapGen failure oracle are PARSED out of tools/win_prepare_data.py
        // and held to the Kotlin patterns — the same read-and-hold mechanism
        // the floors pin uses. A python-side contract edit (a new alt-form
        // leg, a new alloc-failure spelling beside FIALED) then fails THIS
        // pin until the twin is mirrored, instead of silently diverging the
        // lanes (the exact W2.1 defect class on the best-effort lane).
        // Python anchors its patterns with .match() (prefix); Kotlin's
        // .matches() anchors both ends, so the leading ^ / trailing $ are
        // stripped before comparing.
        val source = File("../tools/win_prepare_data.py").readText()
        val mmtileBlock = source.substringAfter("FLAVOR_MMTILE_NAME = {").substringBefore("\n}")
        fun pythonPattern(flavor: String): String =
            Regex("\"" + flavor + "\": re\\.compile\\(r\"([^\"]+)\"\\)")
                .find(mmtileBlock)!!.groupValues[1].removePrefix("^").removeSuffix("$")
        for (flavor in listOf("vanilla", "tbc", "wotlk")) {
            assertEquals(
                "$flavor mmtile contract mirrors the python lane",
                pythonPattern(flavor),
                mmtileNamePattern(flavor).pattern,
            )
        }
        val pythonOracle = Regex(
            "MOVEMAP_FAILURE = re\\.compile\\(r\"([^\"]+)\"",
        ).find(source)!!.groupValues[1]
        assertEquals(
            "the MoveMapGen failure oracle mirrors the python lane",
            pythonOracle,
            DataPreparationStore.MOVEMAP_FAILURE.pattern,
        )
    }

    @Test
    fun `twin tools resolution and provenance stamps follow the verified flavor`() {
        // R1-2 (W2 code-red round 1): the extractor home mirrors
        // WIN_BUILD_ROOTS per flavor (vanilla unsuffixed, tbc/wotlk suffixed)
        // and the provenance stamp is each flavor's OWN server_core commit —
        // vanilla's stays the D36 four-way-pinned CMANGOS_COMMIT.
        assertEquals(
            File("repo/native/.build-win-x86_64/bin/x64_Release/Extractors"),
            extractorToolsDir(File("repo"), "vanilla"),
        )
        assertEquals(
            File("repo/native/.build-win-x86_64-tbc/bin/x64_Release/Extractors"),
            extractorToolsDir(File("repo"), "tbc"),
        )
        assertEquals(
            File("repo/native/.build-win-x86_64-wotlk/bin/x64_Release/Extractors"),
            extractorToolsDir(File("repo"), "wotlk"),
        )
        val manifest = org.json.JSONObject(File("../schemas/flavor.json").readText())
        val flavors = manifest.getJSONArray("flavors")
        for (index in 0 until flavors.length()) {
            val flavor = flavors.getJSONObject(index)
            val id = flavor.getString("id")
            val commit = flavor.getJSONObject("server_core").getString("commit")
            assertEquals(
                "$id provenance stamp == its server_core pin",
                commit,
                DataPreparationStore.CMANGOS_COMMIT_BY_FLAVOR[id]
                    ?: fail("$id carries no provenance stamp"),
            )
        }
        // the tool resolution routes the ACTIVE lane through the same home
        // (source pin; the walk itself is dev-box dependent).
        val twin = File("src/main/kotlin/com/pocketrealm/importer/DataPreparationStore.kt").readText()
        assertTrue(
            "toolFile resolves the active lane's extractor home",
            "extractorToolsDir(repo, ActiveFlavor.resolve(SERVER_DATA_PREPARATION_LANE_TOP_LEVEL).flavorId)" in twin,
        )
    }

    @Test
    fun `mmtile refusal copy states the lane contract and the oracle catches FIALED`() {
        // R6-2 + R5-1 (W2 code-red round 1): the refusal copy names the ACTIVE
        // shape (wotlk's gate accepts the alt form), and the twin's failure
        // oracle matches the wotlk generator's misspelled alloc failures
        // (MapBuilder.cpp:950/:961) beside the plain "Failed" shapes.
        assertEquals("7 digits", mmtileExpectedShape("vanilla"))
        assertEquals("7 digits", mmtileExpectedShape("tbc"))
        assertEquals("7 digits, or 7 digits + _NN", mmtileExpectedShape("wotlk"))
        for (line in listOf(
            "[Map 615] [30,30] [00] alloc iv.polyMesh FIALED!",
            "[Map 615] [30,30] [00] alloc m_dmesh FIALED!",
            "[Map 615] Failed creating navmesh!",
        )) {
            assertTrue("oracle misses: $line", DataPreparationStore.MOVEMAP_FAILURE.containsMatchIn(line))
        }
        assertTrue(
            "the oracle must not fire on normal progress lines",
            !DataPreparationStore.MOVEMAP_FAILURE.containsMatchIn("[Map 615] [30,30] [00] Building navmesh tile!"),
        )
    }

    @Test
    fun `mmtile name contract accepts the wotlk alt tile on no other lane`() {
        // W2: wotlk's MoveMapGen emits %03u%02i%02i_NN.mmtile alt tiles
        // (cmangos-wotlk MapBuilder.cpp:1090; its runtime loads both forms,
        // MoveMap.cpp:28 TILE_ALT_FILE_NAME_FORMAT) — the wotlk lane accepts
        // exactly one _NN suffix; vanilla/tbc keep the strict 7-digit shape
        // (an alt tile there is cross-flavor contamination), and garbage
        // names refuse everywhere.
        val wotlk = mmtileNamePattern(ClientFlavors.WOTLK.flavorId)
        assertTrue("6313035_01".matches(wotlk))
        assertTrue("6163029_24".matches(wotlk))
        for (other in listOf(ClientFlavors.VANILLA.flavorId, ClientFlavors.TBC.flavorId)) {
            val pattern = mmtileNamePattern(other)
            assertTrue("0302731".matches(pattern))
            assertTrue("an alt tile is a wotlk-only shape: $other", !"6313035_01".matches(pattern))
        }
        for (lane in listOf(ClientFlavors.VANILLA.flavorId, ClientFlavors.TBC.flavorId, ClientFlavors.WOTLK.flavorId)) {
            val pattern = mmtileNamePattern(lane)
            assertTrue(!"go0042".matches(pattern))
            assertTrue(!"6313035_011".matches(pattern))
        }
    }

    private companion object {
        // 100 map ids → 100 terrain-backed map ids, all MoveMapGen-driven;
        // the stub emits each flavor gate's worst (wotlk) publication floors
        // over these ids, so every lane's gate passes.
        const val MAP_IDS = 100
    }
}
