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

    /** Stages a minimal valid NORMAL generation the way
     * tools/win_prepare_data.py publishes (manifest + authenticated
     * active pointer) so worldConfig's full body can be pinned. */
    private fun stageActiveGeneration(roots: DesktopStorageRoots) {
        val id = "12345678-1234-1234-1234-123456789abc"
        val dataRoot = File(roots.content, "o11-server")
        val generation = File(dataRoot, "generations/$id").apply { mkdirs() }
        val records = listOf(
            "dbc/A.dbc" to "dbc", "maps/0000000.map" to "map",
            "vmaps/000.vmtree" to "vmtree", "mmaps/000.mmap" to "mmap",
            "mmaps/0000000.mmtile" to "mmtile",
        ).map { (path, contents) ->
            val file = File(generation, path).apply { parentFile?.mkdirs(); writeText(contents) }
            org.json.JSONObject().put("path", path).put("size", file.length()).put("sha256", sha256(file))
        }
        val manifest = org.json.JSONObject().put("schema", 1).put("complete", true)
            .put("mode", "NORMAL").put("clientBuild", 5875).put("cmangosFamily", "classic")
            .put("counts", org.json.JSONObject().put("dbc", 100).put("maps", 100)
                .put("vmapTrees", 1).put("vmapTiles", 0).put("mmapMaps", 1).put("mmapTiles", 1))
            .put("files", org.json.JSONArray(records))
        val manifestFile = File(generation, "data-manifest.json").apply { writeText(manifest.toString()) }
        File(dataRoot, "active.json").writeText(
            org.json.JSONObject().put("schema", 1).put("mode", "NORMAL")
                .put("generation", id).put("manifestSha256", sha256(manifestFile)).toString(),
        )
    }

    private fun sha256(file: File): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun worldConfigPinsTheFullConfBody() {
        val (roots, files) = files()
        stageActiveGeneration(roots)
        val conf = files.worldConfig().readText()
        // The load-bearing contract lines, pinned so a drift on either
        // twin (this one or the Android original) shows up as a test
        // failure rather than a silent divergence.
        listOf(
            "RealmID = 1",
            "WorldServerPort = 8085",
            "BindIP = \"127.0.0.1\"",
            "Network.Threads = 1",
            "Console.Enable = 0",
            "Ra.Enable = 0",
            "SOAP.Enabled = 0",
            "vmap.enableLOS = 1",
            "vmap.enableHeight = 1",
            "vmap.enableIndoorCheck = 1",
            "mmap.enabled = 1",
            "LogLevel = 1",
            "LogFileLevel = 1", // errors-only default (the debug toggle flips this to 3)
            "PlayerLimit = 10",
            "MaxOverspeedPings = 0",
            "MaxCoreStuckTime = 0",
            "Corpse.Decay.NORMAL = 1800",
            "Corpse.Decay.RARE = 3600",
            "Corpse.Decay.ELITE = 3600",
            "Corpse.Decay.RAREELITE = 7200",
            "Corpse.Decay.WORLDBOSS = 14400",
            "PocketRealm.NearbyInteract = 1",
            "PocketRealm.BotTarget = 0",
        ).forEach { line ->
            assertTrue("mangosd.conf must pin \"$line\":\n$conf", line in conf)
        }
        // The bots-disabled block is its own conf staged beside mangosd.conf.
        val botConf = File(File(File(roots.runtime, "server"), "run"), "aiplayerbot-disabled.conf").readText()
        listOf(
            "AiPlayerbot.Enabled = 0",
            "AiPlayerbot.RandomBotAutologin = 0",
            "AiPlayerbot.RandomBotLoginAtStartup = 0",
            "AiPlayerbot.RandomBotAutoCreate = 0",
            "AiPlayerbot.CommandServerPort = 0",
            "AiPlayerbot.LLMEnabled = 0",
            "AiPlayerbot.ShowProgressBars = 0",
        ).forEach { line ->
            assertTrue("aiplayerbot-disabled.conf must pin \"$line\":\n$botConf", line in botConf)
        }
        assertTrue("DataDir must point at the prepared generation", "DataDir = \"" in conf)
        assertTrue(
            "all four database infos must point at the sqlite datadir",
            File(roots.sqliteDatadir, "classicrealmd.sqlite3").absolutePath in conf &&
                File(roots.sqliteDatadir, "classicmangos.sqlite3").absolutePath in conf &&
                File(roots.sqliteDatadir, "classiccharacters.sqlite3").absolutePath in conf &&
                File(roots.sqliteDatadir, "classiclogs.sqlite3").absolutePath in conf,
        )
    }

    @Test
    fun worldConfigWithBotProfileStagesTheProfileConf() {
        val (roots, files) = files()
        stageActiveGeneration(roots)
        val profile = com.pocketrealm.bots.BotProfiles.defaultProfile
        val conf = files.worldConfig(botProfile = profile).readText()
        assertTrue(
            "BotTarget must carry the profile's initial target: $conf",
            "PocketRealm.BotTarget = ${profile.initialTarget}" in conf,
        )
        val botConf = File(File(roots.runtime, "server/run"), "aiplayerbot-${profile.id}.conf")
        assertTrue("the profile conf must be staged: ${botConf.absolutePath}", botConf.isFile)
        val body = botConf.readText()
        assertTrue(
            "the profile conf starts with the profile's population emission",
            body.startsWith(profile.playerbotConfig()),
        )
        assertTrue(
            "no LLM block without the LLM lane enabled",
            "AiPlayerbot.LLMEnabled = 2" !in body,
        )
        assertTrue(
            "mangosd.conf points at the profile conf",
            "PocketRealm.PlayerbotConfig = \"${botConf.absolutePath}\"" in conf,
        )
    }

    @Test
    fun llmExternalBlockFailsClosedWithoutEndpoint() {
        val (roots, files) = files()
        stageActiveGeneration(roots)
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            Settings.Snapshot(llmEnabled = true, llmExternalUrl = "not-a-url").toJson(),
        )
        val profile = com.pocketrealm.bots.BotProfiles.defaultProfile
        files.worldConfig(botProfile = profile)
        val botConf = File(File(roots.runtime, "server/run"), "aiplayerbot-${profile.id}.conf")
        val body = botConf.readText()
        assertTrue(
            "an invalid endpoint must fail closed to no LLM block: $body",
            "AiPlayerbot.LLMEnabled = 2" !in body,
        )
    }

    @Test
    fun llmExternalBlockEmitsTheExternalLane() {
        val (roots, files) = files()
        stageActiveGeneration(roots)
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            Settings.Snapshot(
                llmEnabled = true,
                llmExternalUrl = "https://api.openai.com",
                llmExternalModel = "gpt-test",
                llmExternalApiKey = "sk-test",
                llmBanter = true,
                llmAmbience = true,
                llmCloudChatter = true,
            ).toJson(),
        )
        val profile = com.pocketrealm.bots.BotProfiles.defaultProfile
        files.worldConfig(botProfile = profile)
        val botConf = File(File(roots.runtime, "server/run"), "aiplayerbot-${profile.id}.conf")
        val body = botConf.readText()
        // The external-lane emission, pinned against the Android confLines twin.
        listOf(
            "AiPlayerbot.LLMEnabled = 2",
            "AiPlayerbot.LLMBackend = 0",
            "AiPlayerbot.LLMApiEndpoint = https://api.openai.com/v1/chat/completions",
            "AiPlayerbot.LLMApiKey = sk-test",
            "AiPlayerbot.LLMApiModel = gpt-test",
            "AiPlayerbot.LLMPromptFormat = 1",
            "AiPlayerbot.LLMProviderSafe = 1",
            "AiPlayerbot.LLMBanterEnabled = 1",
            "AiPlayerbot.LLMChatterEnabled = 1",
            "AiPlayerbot.LLMCloudChatter = 1",
        ).forEach { line ->
            assertTrue("external block must carry: $line in body", line in body)
        }
        // The chatter power file is staged enabled with the NORMAL rung.
        val power = File(File(roots.runtime, "server/run"), "chatter-power.conf")
        assertTrue("chatter power file staged", power.isFile)
        val powerBody = power.readText()
        assertTrue("enabled=1: $powerBody", "enabled=1" in powerBody)
        assertTrue("rung=4 (NORMAL): $powerBody", "rung=4" in powerBody)
    }

    @Test
    fun worldConfigDebugToggleFlipsTheLogFileLevel() {
        val (roots, files) = files()
        stageActiveGeneration(roots)
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(Settings.Snapshot(worldDebugLogs = true).toJson())
        val conf = files.worldConfig().readText()
        assertTrue("debug toggle must stage LogFileLevel = 3:\n$conf", "LogFileLevel = 3" in conf)
    }

    @Test
    fun secureWriteReplacesAtomicallyAcrossRewrites() {
        val (_, files) = files()
        repeat(3) { round ->
            val conf = files.realmdConfig()
            assertTrue(conf.isFile)
            assertTrue("rewrite $round must not strand a temp", conf.parentFile!!.list()!!.none { it.endsWith(".tmp") })
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
