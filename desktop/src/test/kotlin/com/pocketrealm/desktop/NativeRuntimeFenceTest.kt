package com.pocketrealm.desktop

import com.pocketrealm.server.NativeRuntimeFreshness
import java.io.File
import java.io.PrintStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the DESK-115 build-fence driver: the win-lane lockfile + provenance +
 * staged bytes are fed through the shared NativeRuntimeFreshness.evaluate,
 * a coherent lane exits 0, any drift exits 1 with the REMEDY copy, missing
 * staging bytes honest-skip (0) exactly like packageApp, and an ambiguous
 * skip property is refused. The committed documents and staged bytes below
 * mirror the real mariadb-win lane's shape.
 */
class NativeRuntimeFenceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val cmangosCommit = "ce83805d48f9c98b2af617096be5347acb8a1f17"
    private val playerbotsCommit = "7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d"
    private val otherCommit = "1111111111111111111111111111111111111111"

    private lateinit var repoRoot: File
    private lateinit var dllBytes: Map<String, ByteArray>
    private lateinit var seedBytes: Map<String, ByteArray>

    /** Builds a coherent lane; tests then tamper with one document at a time. */
    private fun writeLane(lockCmangos: String = cmangosCommit, provCmangos: String = cmangosCommit) {
        repoRoot = folder.newFolder("repo")
        val realmd = ByteArray(64) { it.toByte() }
        val world = ByteArray(96) { (it * 3).toByte() }
        val sqlite = ByteArray(32) { (it * 7).toByte() }
        val libmysql = ByteArray(48) { (it * 5).toByte() }
        // The full packaged seed set (packageApp's hardcoded four), each DB
        // with its own bytes so tampering one is attributable.
        seedBytes = NativeRuntimeFence.PACKAGED_SEED_DATABASES.withIndex().associate { (index, database) ->
            database to ByteArray(24) { ((it + index) * 11).toByte() }
        }
        // The mariadb lane's artifact rows live under its OWN -mariadb
        // build root (select_win_flavor); the seam stays the shared root.
        dllBytes = mapOf(
            "native/.build-win-x86_64-mariadb/pocket-runtime-build/pocket_realmd_runtime.dll" to realmd,
            "native/.build-win-x86_64-mariadb/pocket-runtime-build/pocket_world_runtime.dll" to world,
            "native/.build-win-x86_64/sqlite-seam-build/pocket_sqlite.dll" to sqlite,
            "native/.build-win-x86_64-mariadb/pocket-runtime-build/libmysql.dll" to libmysql,
        )
        dllBytes.forEach { (relative, bytes) ->
            val file = repoRoot.resolve(relative)
            file.parentFile.mkdirs()
            file.writeBytes(bytes)
        }
        seedBytes.forEach { (database, bytes) ->
            repoRoot
                .resolve("native/.build-o09-x86_64/realm-staging-sqlite/assets/seed/$database.sqlz")
                .apply {
                    parentFile.mkdirs()
                    writeBytes(bytes)
                }
        }
        writeLockfileDocument(lockCmangos)
        val sources = repoRoot.resolve("schemas/sources.json")
        sources.parentFile.mkdirs()
        sources.writeText(
            """
            {"schema": 1, "sources": [
              {"id": "cmangos-core", "path": "native/cmangos", "commit": "$cmangosCommit"},
              {"id": "playerbots", "path": "native/playerbots", "commit": "$playerbotsCommit"}
            ]}
            """.trimIndent(),
        )
        val provenance =
            repoRoot.resolve("native/.build-o09-x86_64/realm-staging/BUILD_PROVENANCE.json")
        provenance.parentFile.mkdirs()
        provenance.writeText(
            """
            {"schema": 1, "cmangos_commit": "$provCmangos",
             "playerbots_commit": "$playerbotsCommit", "artifacts": []}
            """.trimIndent(),
        )
    }

    /** The lane's lockfile: four pinned DLL rows (libmysql.dll included —
     * the fence rehashes artifact rows) plus the seed transcript pins. */
    private fun writeLockfileDocument(lockCmangos: String) {
        val lockfile = repoRoot.resolve("schemas/realm-runtime-lockfile-mariadb-win.json")
        lockfile.parentFile.mkdirs()
        lockfile.writeText(
            """
            {
              "schema": 1,
              "abi": "windows-x86_64",
              "database_backend": "mariadb",
              "cmangos_commit": "$lockCmangos",
              "playerbots_commit": "$playerbotsCommit",
              "artifacts": [
                ${dllBytes.entries.joinToString(",") { (relative, bytes) ->
                """{"path": "$relative", "size": ${bytes.size}, "sha256": "${sha256(bytes)}"}"""
            }}
              ],
              "seed_transcripts": {
                ${seedBytes.entries.joinToString(",") { (database, bytes) ->
                """$database: {
                      "gzip_sha256": "${sha256(bytes)}",
                      "gzip_size": ${bytes.size}
                    }"""
            }}
              }
            }
            """.trimIndent(),
        )
    }

    private fun run(staged: Boolean, skip: String = ""): Pair<Int, String> {
        val buffer = java.io.ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8.name()))
        try {
            val exit = NativeRuntimeFence.run(
                repoRoot = repoRoot,
                laneLabel = if (staged) "test staged lane" else "test pins lane",
                requireStagedBytes = staged,
                skipRequested = skip,
            )
            return exit to buffer.toString(Charsets.UTF_8.name())
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun coherentPinsPass() {
        writeLane()
        val (exit, output) = run(staged = false)
        assertEquals(0, exit)
        assertTrue(output, "PASSED" in output)
    }

    /**
     * Pins the lane identity itself: the fence must read the mariadb-win
     * lockfile + the non-sqlite provenance and expect the mariadb backend
     * (the app's shipped default), and the COMMITTED lockfile must describe
     * exactly that lane — pointed at the sqlite-win document instead, staged
     * MariaDB-lane DLLs fail as STALE (the R2 packaging finding this pins).
     */
    @Test
    fun fencePinsTheShippedMariaDbWinLane() {
        assertEquals(
            "schemas/realm-runtime-lockfile-mariadb-win.json",
            NativeRuntimeFence.LOCKFILE_RELATIVE,
        )
        assertEquals(
            "native/.build-o09-x86_64/realm-staging/BUILD_PROVENANCE.json",
            NativeRuntimeFence.PROVENANCE_RELATIVE,
        )
        assertEquals("mariadb", NativeRuntimeFence.EXPECTED_BACKEND)
        assertEquals("windows-x86_64", NativeRuntimeFence.EXPECTED_ABI)
        val lockfile = File("../schemas/realm-runtime-lockfile-mariadb-win.json")
        assertTrue("committed lane lockfile missing: $lockfile", lockfile.isFile)
        val record = org.json.JSONObject(lockfile.readText(Charsets.UTF_8))
        assertEquals(NativeRuntimeFence.EXPECTED_ABI, record.getString("abi"))
        assertEquals(NativeRuntimeFence.EXPECTED_BACKEND, record.getString("database_backend"))
        // The fourth shipped DLL is pinned by BYTES, not just by the runtimes'
        // pe_imports NAME references: the fence rehashes artifact rows, so an
        // unpinned libmysql.dll would be the stale-DLL-ships-silently hole.
        val pinnedNames = record.getJSONArray("artifacts").map { row ->
            (row as org.json.JSONObject).getString("path").substringAfterLast('/')
        }
        assertEquals(
            listOf(
                "pocket_realmd_runtime.dll",
                "pocket_world_runtime.dll",
                "pocket_sqlite.dll",
                "libmysql.dll",
            ),
            pinnedNames,
        )
        assertTrue("committed lockfile has no seed_transcripts pins", record.has("seed_transcripts"))
        // Every database packageApp bundles must carry a parseable pin in
        // the COMMITTED lockfile — a deleted row used to silently narrow
        // the fence's seed coverage below what ships (the R3 twin-drift
        // finding; the android validateRealmRuntime leg errors on the
        // same gap).
        val seedRows = record.getJSONObject("seed_transcripts")
        NativeRuntimeFence.PACKAGED_SEED_DATABASES.forEach { database ->
            val row = seedRows.optJSONObject(database)
            assertTrue(
                "committed lockfile seed_transcripts.$database lacks a parseable " +
                    "gzip_sha256/gzip_size pin",
                row != null &&
                    !row.optString("gzip_sha256").isNullOrEmpty() &&
                    row.has("gzip_size") &&
                    runCatching { row.getLong("gzip_size") }.isSuccess,
            )
        }
        // Fence-vs-packaging twin pin: the fence's fixed set must stay the
        // set packageApp hardcodes into the image, or the coverage this
        // test pins above would silently stop matching what ships.
        val buildScript = File("build.gradle.kts")
        assertTrue("desktop build script not found at ${buildScript.absolutePath}", buildScript.isFile)
        val seedList = Regex("val seeds = listOf\\(([^)]*)\\)")
            .find(buildScript.readText(Charsets.UTF_8))
            ?.let { match ->
                Regex("\"([^\"]+)\"").findAll(match.groupValues[1]).map { it.groupValues[1] }.toList()
            }
        assertTrue(
            "desktop/build.gradle.kts no longer declares the packaged 'val seeds' listOf",
            seedList != null,
        )
        assertEquals(NativeRuntimeFence.PACKAGED_SEED_DATABASES, seedList)
    }

    /**
     * The R3 twin-drift pin: deleting a seed_transcripts row (its .sqlz
     * still staged, so packageApp would still bundle it) must FAIL staged
     * mode loudly instead of silently un-verifying that seed — the desktop
     * twin of the android `lockfile has no seed pin for $db` error.
     */
    @Test
    fun deletedSeedRowFailsStagedLoudlyNotSilently() {
        writeLane()
        val lockfile = repoRoot.resolve("schemas/realm-runtime-lockfile-mariadb-win.json")
        val record = org.json.JSONObject(lockfile.readText(Charsets.UTF_8))
        record.getJSONObject("seed_transcripts").remove("classicmangos")
        lockfile.writeText(record.toString())
        val (exit, output) = run(staged = true)
        assertEquals(1, exit)
        assertTrue(output, "no parseable seed pin for classicmangos" in output)
        assertTrue(output, "packageApp still bundles classicmangos.sqlz" in output)
        assertTrue(output, "Remedy: " + NativeRuntimeFreshness.REMEDY in output)
    }

    /**
     * The malformed-row shape of the same hole (gzip_size present but not
     * a number): pins mode — the leg that rides every `test` run where the
     * untracked staging bytes do not exist — must catch it off the
     * COMMITTED lockfile alone, so a dropped/unparseable row is loud even
     * on machines that never built the native lanes.
     */
    @Test
    fun malformedSeedRowFailsPinsWithoutStagedBytes() {
        writeLane()
        repoRoot.resolve("native/.build-o09-x86_64/realm-staging-sqlite").deleteRecursively()
        val lockfile = repoRoot.resolve("schemas/realm-runtime-lockfile-mariadb-win.json")
        val record = org.json.JSONObject(lockfile.readText(Charsets.UTF_8))
        record.getJSONObject("seed_transcripts")
            .put("classiccharacters", org.json.JSONObject().put("gzip_sha256", "abc").put("gzip_size", "not-a-number"))
        lockfile.writeText(record.toString())
        val (exit, output) = run(staged = false)
        assertEquals(1, exit)
        assertTrue(output, "no parseable seed pin for classiccharacters" in output)
    }

    @Test
    fun lockfileCommitDriftFailsPinsWithRemedy() {
        writeLane(lockCmangos = otherCommit)
        val (exit, output) = run(staged = false)
        assertEquals(1, exit)
        assertTrue(output, "STALE source pins" in output)
        assertTrue(output, "Remedy: rerun the full lane build" in output)
    }

    @Test
    fun coherentStagedBytesPass() {
        writeLane()
        val (exit, output) = run(staged = true)
        assertEquals(0, exit)
        assertTrue(output, "PASSED" in output)
    }

    @Test
    fun tamperedStagedBytesFailWithRemedy() {
        writeLane()
        // Same size, different bytes: exactly the stale-but-ABI-stable case
        // the runtime ABI check cannot see.
        val victim = repoRoot
            .resolve("native/.build-win-x86_64/sqlite-seam-build/pocket_sqlite.dll")
        val tampered = ByteArray(victim.length().toInt()) { (it * 9).toByte() }
        victim.writeBytes(tampered)
        val (exit, output) = run(staged = true)
        assertEquals(1, exit)
        assertTrue(output, "pocket_sqlite.dll sha256" in output)
        assertTrue(output, "STALE relative to the lockfile lane pins" in output)
        assertTrue(output, "Remedy: " + NativeRuntimeFreshness.REMEDY in output)
    }

    /**
     * The connector DLL's own rehash leg: libmysql.dll rides the lockfile
     * as a fourth artifact row, so tampering ITS staged bytes (same size,
     * different bytes — the stale-but-loadable shape) must fail the fence
     * naming libmysql.dll. Before the row existed, the runtimes'
     * pe_imports pinned only the import NAME and a tampered connector DLL
     * shipped silently — exactly the DESK-115 defect class.
     */
    @Test
    fun tamperedConnectorDllFailsStagedWithRemedy() {
        writeLane()
        val victim = repoRoot
            .resolve("native/.build-win-x86_64-mariadb/pocket-runtime-build/libmysql.dll")
        val tampered = ByteArray(victim.length().toInt()) { (it * 11).toByte() }
        victim.writeBytes(tampered)
        val (exit, output) = run(staged = true)
        assertEquals(1, exit)
        assertTrue(output, "libmysql.dll sha256" in output)
        assertTrue(output, "STALE relative to the lockfile lane pins" in output)
        assertTrue(output, "Remedy: " + NativeRuntimeFreshness.REMEDY in output)
    }

    @Test
    fun provenanceCommitDriftFailsStaged() {
        writeLane(provCmangos = otherCommit)
        val (exit, output) = run(staged = true)
        assertEquals(1, exit)
        assertTrue(output, "BUILD_PROVENANCE.json cmangos_commit" in output)
    }

    /**
     * The desktop seed leg (the android validateRealmRuntime .sqlz twin):
     * a staged transcript whose bytes drifted from the lockfile's
     * gzip_sha256/gzip_size pin fails staged mode with the fence's REMEDY —
     * packageApp bundles exactly these bytes, so they are fence inputs like
     * the DLLs. Same size, different bytes: the replace-one-byte shape a
     * size-only check would wave through.
     */
    @Test
    fun tamperedSeedTranscriptFailsStagedWithRemedy() {
        writeLane()
        val victim = repoRoot
            .resolve("native/.build-o09-x86_64/realm-staging-sqlite/assets/seed/classicrealmd.sqlz")
        victim.writeBytes(ByteArray(seedBytes.getValue("classicrealmd").size) { (it * 13).toByte() })
        val (exit, output) = run(staged = true)
        assertEquals(1, exit)
        assertTrue(output, "seed transcript classicrealmd.sqlz" in output)
        assertTrue(output, "STALE relative to the lockfile lane pins" in output)
        assertTrue(output, "Remedy: " + NativeRuntimeFreshness.REMEDY in output)
    }

    /** Absent transcript = the staging lane never ran: the same honest
     * skip packageApp takes on its missing inputs, never a silent pass. */
    @Test
    fun absentSeedTranscriptHonestSkipsLikePackageApp() {
        writeLane()
        repoRoot
            .resolve("native/.build-o09-x86_64/realm-staging-sqlite/assets/seed/classicrealmd.sqlz")
            .delete()
        val (exit, output) = run(staged = true)
        assertEquals(0, exit)
        assertTrue(output, "SKIPPED" in output)
        assertTrue(output, "classicrealmd.sqlz" in output)
    }

    /** Pins-only mode (the `test` leg) never requires untracked staging
     * bytes — the seed leg stays off exactly like the DLL legs. */
    @Test
    fun pinsModeIgnoresAbsentSeedTranscripts() {
        writeLane()
        repoRoot.resolve("native/.build-o09-x86_64/realm-staging-sqlite").deleteRecursively()
        val (exit, output) = run(staged = false)
        assertEquals(0, exit)
        assertTrue(output, "PASSED" in output)
    }

    @Test
    fun absentStagedBytesHonestSkipLikePackageApp() {
        writeLane()
        dllBytes.keys.forEach { relative -> repoRoot.resolve(relative).delete() }
        val (exit, output) = run(staged = true)
        assertEquals(0, exit)
        assertTrue(output, "SKIPPED" in output)
    }

    @Test
    fun ambiguousSkipPropertyIsRefused() {
        writeLane()
        assertThrows(IllegalArgumentException::class.java) {
            NativeRuntimeFence.run(repoRoot, "test", false, "false")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
