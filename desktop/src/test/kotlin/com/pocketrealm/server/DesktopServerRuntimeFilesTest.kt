package com.pocketrealm.server

import com.pocketrealm.desktop.DesktopStorageRoots
import com.pocketrealm.storage.Settings
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The desktop ServerRuntimeFiles twin contract: conf contents pinned
 * against the Android twin's shape (ports, database paths, log layout),
 * the honest DATA_MISSING refusal without prepared world data, the
 * lifecycle record, and the log-rotation gate. Pure JVM — no DLLs.
 */
class DesktopServerRuntimeFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun files(): Pair<DesktopStorageRoots, ServerRuntimeFiles> {
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        return roots to ServerRuntimeFiles(roots)
    }

    @Test
    fun realmdConfigPinsTheContractShape() {
        val (roots, files) = files()
        val conf = files.realmdConfig().readText()
        val realmd = File(roots.sqliteDatadir, "classicrealmd.sqlite3").absolutePath
        assertTrue("must point at the seeded datadir: $conf", "LoginDatabaseInfo = \"$realmd\"" in conf)
        assertTrue("RealmServerPort = 3724" in conf)
        assertTrue("BindIP = \"127.0.0.1\"" in conf)
        assertTrue(conf.contains("LogFile = \"") && "realmd.log" in conf)
        assertEquals("realmd.conf", File(files.realmdConfig().parentFile, "realmd.conf").name)
    }

    @Test
    fun worldConfigWithoutPreparedDataRefusesHonestly() {
        val (_, files) = files()
        try {
            files.worldConfig()
            fail("world config must refuse without prepared data")
        } catch (expected: IllegalStateException) {
            val message = expected.message ?: ""
            assertTrue(
                "must carry the prepared-data copy: $message",
                "Server world data is not ready" in message,
            )
        }
    }

    @Test
    fun lifecycleRecordsCarryTheTwinShape() {
        val (_, files) = files()
        files.writeLifecycle("realm", true, "stop", "OK")
        val record = JSONObject(
            File(files.realmdConfig().parentFile.parentFile.resolve("lifecycle"), "realm.json").readText(),
        )
        assertEquals(1, record.getInt("schema"))
        assertEquals("realm", record.getString("component"))
        assertTrue(record.getBoolean("clean"))
        assertEquals("stop", record.getString("operation"))
        assertTrue(record.getLong("at") > 0)
    }

    @Test
    fun logRotationOnlyBetweenLifetimes() {
        val (_, files) = files()
        try {
            files.prepareRealmLogsForStart(ServerRuntimeContract.READY)
            fail("rotation must refuse while the native runtime is live")
        } catch (expected: IllegalStateException) {
            assertTrue((expected.message ?: "").contains("requires a stopped native runtime"))
        }
    }

    @Test
    fun oversizedPreviousSessionLogRotatesExactlyOnce() {
        val (roots, files) = files()
        val logs = File(File(roots.runtime, "server"), "logs").apply { mkdirs() }
        val world = File(logs, "world.log").apply { writeText("x".repeat(5 * 1024 * 1024)) }
        File(logs, "world.log.1").writeText("previous")
        files.prepareWorldLogsForStart(ServerRuntimeContract.STOPPED)
        // The retired .1 is deleted, the oversized live log rotates into
        // its place, and the native writer recreates world.log on start.
        val rotated = File(logs, "world.log.1")
        assertTrue(rotated.isFile)
        assertEquals(5L * 1024L * 1024L, rotated.length())
        assertTrue(!world.isFile)
    }

    @Test
    fun worldLogLevelFollowsTheToggle() {
        assertEquals(1, ServerRuntimeFiles.worldLogFileLevel(worldDebugLogs = false))
        assertEquals(3, ServerRuntimeFiles.worldLogFileLevel(worldDebugLogs = true))
    }

    @Test
    fun settingsSnapshotCarriesWorldDebugLogs() {
        val round = Settings.Snapshot(worldDebugLogs = true)
        assertTrue(Settings.Snapshot.fromJson(round.toJson()).worldDebugLogs)
        assertTrue(!Settings.Snapshot().worldDebugLogs)
    }
}
