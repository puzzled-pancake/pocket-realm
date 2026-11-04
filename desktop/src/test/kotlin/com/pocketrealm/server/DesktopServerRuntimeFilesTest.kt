package com.pocketrealm.server

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.testFlavor
import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.database.DesktopSqliteConnection
import com.pocketrealm.desktop.DesktopRuntimeBackend
import com.pocketrealm.desktop.DesktopStorageRoots
import com.pocketrealm.desktop.requireNativeIfDemanded
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.RealmEndpoint
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The desktop ServerRuntimeFiles twin contract: conf contents pinned
 * against the Android twin's shape (ports, database paths, log layout),
 * the honest DATA_MISSING refusal without prepared world data, the
 * lifecycle record, and the log-rotation gate. Pure JVM — no realm
 * DLLs; the realmlist-projection leg alone drives the SQLite
 * seam (skipped unless pocket_sqlite.dll is built, like every other
 * seam-consuming test).
 */
class DesktopServerRuntimeFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** the S5-flip tests swap [ActiveFlavor]'s qualified source
     * for synthetic two-qualified lanes; the reset keeps every golden
     * pin in this class resolving today's real (vanilla-only)
     * registry, whatever a flip test leaves behind. */
    @After
    fun restoreTheFlavorMachine() {
        ActiveFlavor.reset()
    }

    /** The projection leg's SQLite seam (see [requireNativeIfDemanded]). */
    private val seamDll = File("../native/.build-win-x86_64/sqlite-seam-build/pocket_sqlite.dll")

    private val owner = ComponentOwner(sessionId = "test", instanceToken = "test")

    private fun files(): Pair<DesktopStorageRoots, ServerRuntimeFiles> {
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
 // Flip: the sole-qualified degrade is RETIRED - with three
        // qualified flavors an absent pick refuses loud (the
        // contract). The fixture pins the lane the tests were written
        // against BOTH ways (persisted + holder); per-test picks
        // override it (the boot-lane-binding tests write their own).
        pickVanilla(roots)
        return roots to ServerRuntimeFiles(roots)
    }

 /** Flip: pins the fixture's lane BOTH ways the flip contract reads
     * a lane - the persisted settings pick (the boot path's
     * worldLaneFlavor) AND the holder's selection (the companion
     * [ServerRuntimeFiles.serverFlavor]'s ActiveFlavor.resolve default).
     * The sole-qualified degrade is retired; every lane read needs one. */
    private fun pickVanilla(roots: DesktopStorageRoots) {
        com.pocketrealm.desktop.DesktopSettingsStore(roots.settingsFile).save(
            com.pocketrealm.desktop.DesktopSettingsStore(roots.settingsFile)
                .load().copy(selectedFlavorId = "vanilla"),
        )
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @Test
    fun realmdConfigPinsTheContractShape() {
        val (roots, files) = files()
        val conf = files.realmdConfig().readText()
        val realmd = File(
            roots.sqliteDatadir,
            "${ServerRuntimeFiles.serverDatabases().realmd}.sqlite3",
        ).absolutePath
        assertTrue("must point at the seeded datadir: $conf", "LoginDatabaseInfo = \"$realmd\"" in conf)
        assertTrue("RealmServerPort = 3724" in conf)
        assertTrue("BindIP = \"127.0.0.1\"" in conf)
        assertTrue(conf.contains("LogFile = \"") && "realmd.log" in conf)
        assertEquals("realmd.conf", File(files.realmdConfig().parentFile, "realmd.conf").name)
    }

    /** the LAN host bind — the shared RealmEndpoint parsing
     * accepts a private dotted quad and the conf carries it as BindIP
     * (the non-loopback threading the supervisor's lanHost spec rides). */
    @Test
    fun realmdConfigBindsTheLanAddress() {
        val (_, files) = files()
        val conf = files.realmdConfig("192.168.1.50").readText()
        assertTrue("BindIP must carry the LAN address:\n$conf", "BindIP = \"192.168.1.50\"" in conf)
        assertTrue("the realm port stays fixed: $conf", "RealmServerPort = 3724" in conf)
        val linkLocal = files.realmdConfig("169.254.10.20").readText()
        assertTrue("link-local binds too:\n$linkLocal", "BindIP = \"169.254.10.20\"" in linkLocal)
    }

    /**
     * the brute-force lockout keys are emitted for a
     * non-loopback bind with the decided values — MaxCount 10 / BanTime 900 /
     * BanType 0 (IP autoban into ip_banned, the engine default when the
     * lockout is enabled).
     */
    @Test
    fun realmdConfigEmitsTheWrongPassLockoutForNonLoopbackBinds() {
        val (_, files) = files()
        val conf = files.realmdConfig("192.168.1.50").readText()
        assertTrue("MaxCount must be pinned:\n$conf", "WrongPass.MaxCount = 10" in conf)
        assertTrue("BanTime must be pinned:\n$conf", "WrongPass.BanTime = 900" in conf)
        assertTrue("BanType 0 (IP autoban) must be pinned:\n$conf", "WrongPass.BanType = 0" in conf)
        val linkLocal = files.realmdConfig("169.254.10.20").readText()
        assertTrue(
            "link-local exposure is a non-loopback bind too:\n$linkLocal",
            "WrongPass.MaxCount = 10" in linkLocal,
        )
    }

    /**
     * the LOCAL (loopback) bind emits NO lockout keys. An unconditional
     * emission would self-lockout the host: BanType 0 autobans the SOURCE IP,
     * and the logon challenge's ip_banned gate matches loopback too
     * (AuthSocket.cpp:416-423), so ten mistyped LOCAL passwords would lock
     * 127.0.0.1 out of the user's own realm for 900 s with no in-app
     * explanation.
     */
    @Test
    fun realmdConfigOmitsTheWrongPassLockoutForLoopback() {
        val (_, files) = files()
        val conf = files.realmdConfig().readText()
        assertFalse("loopback must not lock out:\n$conf", "WrongPass." in conf)
        val explicit = files.realmdConfig("127.0.0.1").readText()
        assertFalse("explicit loopback must not lock out:\n$explicit", "WrongPass." in explicit)
    }

    /** The endpoint guard is the shared RealmEndpoint's: a non-LAN address
     * (public, or not canonical IPv4) is refused before any conf exists. */
    @Test
    fun nonLanBindAddressesAreRefused() {
        val (_, files) = files()
        listOf("8.8.8.8", "192.168.001.050", "example.com", "localhost").forEach { address ->
            try {
                files.realmdConfig(address)
                fail("non-LAN bind address must be refused: $address")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "refusal must name the endpoint contract: ${expected.message}",
                    (expected.message ?: "").contains("endpoint") ||
                        (expected.message ?: "").contains("LAN"),
                )
            }
        }
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
            "PlayerLimit = 100",
            "MaxOverspeedPings = 0",
            "MaxCoreStuckTime = 0",
            "Corpse.Decay.NORMAL = 1800",
            "Corpse.Decay.RARE = 3600",
            "Corpse.Decay.ELITE = 3600",
            "Corpse.Decay.RAREELITE = 7200",
            "Corpse.Decay.WORLDBOSS = 14400",
            // the nearby-use knob follows the patch lane's consent —
            // the DEFAULT snapshot carries no tweak consent, so the reviewed
            // knob stays 0 (the nearby-use acceptance leg pins below).
            "PocketRealm.NearbyInteract = 0",
            // the default debounce rides the conf at the shared
            // policy's default.
            "PocketRealm.NearbyInteractCooldownMs = " +
                com.pocketrealm.server.NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
            "PocketRealm.BotTarget = 0",
            // Design-timeframe: the golden conf carries the DEFAULT
            // stage (the qualified flavor's final patch, resolved from the
            // empty stored default) — the contract line rides the same
            // golden-pinned body every other PocketRealm.* key does.
            "PocketRealm.TimeframeStage = \"vanilla-1.12.1\"",
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
        val databases = ServerRuntimeFiles.serverDatabases()
        assertTrue(
            "all four database infos must point at the sqlite datadir",
            File(roots.sqliteDatadir, "${databases.realmd}.sqlite3").absolutePath in conf &&
                File(roots.sqliteDatadir, "${databases.world}.sqlite3").absolutePath in conf &&
                File(roots.sqliteDatadir, "${databases.characters}.sqlite3").absolutePath in conf &&
                File(roots.sqliteDatadir, "${databases.logs}.sqlite3").absolutePath in conf,
        )
    }

    /**
     * W4.1 (the desktop-lane E2E boot defect): the native Config parser
     * (cmangos Config.cpp Reload) accepts ONLY '#'-/'['-led comment lines
     * and refuses ANY keyless line - a '//' note line fails the WHOLE
     * config with POCKET_SERVER_CONFIG and bricks every world boot. The
     * staged confs are parser-shaped here so the class dies at the gate
     * instead of at boot: every line carries '=', or is blank, or leads
     * with '#'/'['.
     */
    @Test
    fun stagedConfsAreShapedForTheNativeConfigParser() {
        val (roots, files) = files()
        stageActiveGeneration(roots)
        val run = File(File(roots.runtime, "server"), "run")
        val staged = listOf(
            "mangosd.conf" to files.worldConfig(),
            "realmd.conf" to files.realmdConfig(),
            "aiplayerbot-disabled.conf" to File(run, "aiplayerbot-disabled.conf"),
        )
        staged.forEach { (name, file) ->
            val body = file.readText()
            body.lines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                val shaped = trimmed.isEmpty() || trimmed.startsWith("#") ||
                    trimmed.startsWith("[") || trimmed.contains("=")
                assertTrue(
                    "$name:${index + 1} is not shaped for the native Config " +
                        "parser (a keyless line rejects the WHOLE config): [$trimmed]",
                    shaped,
                )
            }
        }
    }

    /**
     * the conf database names are the QUALIFIED lane's
     * [com.pocketrealm.client.ClientFlavor.databases] — resolved through
     * the shared registry with the exactly-one-qualified fail-loud gate
     * — never a hard-coded vanilla literal. Today the qualified lane is
     * vanilla, so the emission is byte-identical to the old literals;
     * the pin is that the conf follows the REGISTRY, so the day a second
     * flavor qualifies the names move with it (and any drift between the
     * gate's answer and the conf body fails here first).
     */
    @Test
    fun confDatabaseNamesResolveThroughTheFlavorRegistry() {
        val (roots, files) = files()
 // Flip: all three lanes are qualified and the server lane is
        // the persisted selection's (default vanilla when the
        // test roots' settings carry no pick under... the sole-qualified degrade is
        // RETIRED - the test pins the DEFAULT settings state explicitly:
        // no persisted pick + several qualified = the loud refusal the
        // flip contract requires, so the test pins the boot lane through
        // the persisted pick the harness/UI writes.
        assertEquals(
            setOf("vanilla", "tbc", "wotlk"),
            com.pocketrealm.client.ClientFlavors.qualified().map { it.flavorId }.toSet(),
        )
        val databases = ServerRuntimeFiles.serverDatabases()
        assertEquals(com.pocketrealm.client.ClientFlavors.VANILLA.databases, databases)
        val realmdConf = files.realmdConfig().readText()
        assertTrue(
            "realmd.conf LoginDatabaseInfo must be the registry's realmd database:\n$realmdConf",
            "LoginDatabaseInfo = \"${datadirPath(roots, databases.realmd)}\"" in realmdConf,
        )
        stageActiveGeneration(roots)
        val worldConf = files.worldConfig().readText()
        listOf(
            "LoginDatabaseInfo" to databases.realmd,
            "WorldDatabaseInfo" to databases.world,
            "CharacterDatabaseInfo" to databases.characters,
            "LogsDatabaseInfo" to databases.logs,
        ).forEach { (key, name) ->
            assertTrue(
                "mangosd.conf $key must be the registry's $name database:\n$worldConf",
                "$key = \"${datadirPath(roots, name)}\"" in worldConf,
            )
        }
    }

    private fun datadirPath(roots: DesktopStorageRoots, name: String): String =
        com.pocketrealm.database.DatabaseSqliteControlPlane.databaseFile(
            roots.sqliteDatadir,
            name,
        ).absolutePath

    // ---- DESK-5/47/78: the MariaDB databaseInfo branch ------------------

    /** Writes the engine's durable active-provider marker + credential
     * record (the durable state the conf branch's shared decision reads). */
    private fun stageMariadbDurableState(roots: DesktopStorageRoots, coreSecret: String) {
        File(roots.database, "active-provider.json").writeText(
            com.pocketrealm.database.DatabaseDurableState.activeProviderMarker(
                com.pocketrealm.database.DatabaseDurableState.ProviderMode.MARIADB,
                com.pocketrealm.database.DatabaseRuntimeContract.WINDOWS_PROVIDER_ID,
                "11111111-1111-4111-8111-111111111111",
            ),
        )
        File(roots.database, "secrets.json").writeText(
            JSONObject()
                .put("schema", 1)
                .put("admin", "a".repeat(48))
                .put("core", coreSecret)
                .toString(),
        )
    }

    @Test
    fun mariadbMarkerSwitchesTheConfToTheSharedSocketForm() {
        val roots = DesktopStorageRoots(folder.newFolder("roots")).apply { ensureDirectories() }
        pickVanilla(roots)
        val secret = "c".repeat(48)
        stageMariadbDurableState(roots, secret)
        val files = ServerRuntimeFiles(roots, providerStagedOverride = true)
        val conf = files.realmdConfig().readText()
        assertTrue(
            "the conf must carry the shared MariaDB socket form:\n$conf",
            "LoginDatabaseInfo = \".;mariadb.sock;pocket_core;$secret;" +
                ServerRuntimeFiles.serverDatabases().realmd + "\"" in conf,
        )
    }

    @Test
    fun mariadbMarkerWithoutTheStagedDistributionRefusesTheConf() {
        val roots = DesktopStorageRoots(folder.newFolder("roots")).apply { ensureDirectories() }
        pickVanilla(roots)
        stageMariadbDurableState(roots, "c".repeat(48))
        val files = ServerRuntimeFiles(roots, providerStagedOverride = false)
        try {
            files.realmdConfig()
            fail("a MARIADB marker without the staged distribution must refuse the conf")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "refusal must carry the DB-LINK copy: ${expected.message}",
                (expected.message ?: "").contains("DB-LINK"),
            )
        }
    }

    @Test
    fun mariadbMarkerWithoutCredentialsRefusesTheConf() {
        val roots = DesktopStorageRoots(folder.newFolder("roots")).apply { ensureDirectories() }
        pickVanilla(roots)
        stageMariadbDurableState(roots, "c".repeat(48))
        assertTrue(File(roots.database, "secrets.json").delete())
        val files = ServerRuntimeFiles(roots, providerStagedOverride = true)
        try {
            files.realmdConfig()
            fail("the conf must never carry a guessed secret")
        } catch (expected: IllegalStateException) {
            assertTrue(
                (expected.message ?: "").contains("database must be initialized before server start"),
            )
        }
    }

    @Test
    fun absentMarkerKeepsTheSqliteFileForm() {
        // No active-provider marker: the v1 SQLite posture serves (the
        // existing pins cover the bodies; this leg pins the decision).
        val (roots, files) = files()
        val conf = files.realmdConfig().readText()
        assertTrue(
            datadirPath(roots, ServerRuntimeFiles.serverDatabases().realmd) in conf,
        )
    }

    /**
     * the world's LAN-bind golden body — the hosted world binds
     * the exact private interface endpoint, port fixed at 8085. */
    @Test
    fun worldConfigBindsTheLanAddress() {
        val (roots, files) = files()
        stageActiveGeneration(roots)
        val conf = files.worldConfig("192.168.1.50").readText()
        assertTrue("world BindIP must carry the LAN address:\n$conf", "BindIP = \"192.168.1.50\"" in conf)
        assertTrue("the world port stays fixed:\n$conf", "WorldServerPort = 8085" in conf)
    }

    // design-timeframe: the content-patch stage knob ----------------

    /**
     * Design-timeframe: a persisted NON-DEFAULT stage of the qualified
     * flavor lands in the conf verbatim; the DEFAULT persists as "" in
     * settings and still names the final patch in the conf ("<stage-or-
     * default>" — never an empty value).
     */
    @Test
    fun worldConfigCarriesTheChosenTimeframeStage() {
        val (roots, files) = files()
        stageActiveGeneration(roots)
        val explicit = files.worldConfig(nearbyUseEnabled = false).readText()
        // No settings file at all: the default stage, still concrete.
        assertTrue(
            "the conf must always name a concrete stage:\n$explicit",
            "PocketRealm.TimeframeStage = \"vanilla-1.12.1\"" in explicit,
        )
        // A persisted non-default selection rides verbatim (the emission
        // reads the snapshot's resolveStored result through confValue).
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            Settings.Snapshot(
                timeframeStage = "vanilla-1.5",
                selectedFlavorId = "vanilla",
            ).toJson())
        val chosen = files.worldConfig().readText()
        assertTrue(
            "the chosen stage must ride the conf:\n$chosen",
            "PocketRealm.TimeframeStage = \"vanilla-1.5\"" in chosen,
        )
        // The default selection stores "" and STILL names the final patch.
        roots.settingsFile.writeText(
            Settings.Snapshot(
                timeframeStage = "vanilla-1.12.1",
                selectedFlavorId = "vanilla",
            ).toJson())
        val final = files.worldConfig().readText()
        assertTrue(
            "the final stage must ride the conf:\n$final",
            "PocketRealm.TimeframeStage = \"vanilla-1.12.1\"" in final,
        )
    }

    /**
     * Design-timeframe, the one-read discipline: startWorld resolves
     * the stage ONCE and passes its snapshot via [ServerRuntimeFiles.worldConfig]'s
     * settingsSnapshot, feeding BOTH the conf write and the overlay's
     * desiredStage — two independent loads could straddle a settings write
     * and boot a world whose conf names a stage the DB/ledger were never
     * brought to. The pin: the caller's snapshot wins over the on-disk
     * store (no second read inside the conf writer).
     */
    @Test
    fun worldConfigConsumesTheCallerSnapshotNotASecondStoreRead() {
        val (roots, files) = files()
        stageActiveGeneration(roots)
        roots.settingsFile.parentFile?.mkdirs()
        // The on-disk store names a DIFFERENT stage than the caller's
        // snapshot; the conf must ride the caller's snapshot alone.
        roots.settingsFile.writeText(
            Settings.Snapshot(
                timeframeStage = "vanilla-1.5",
                selectedFlavorId = "vanilla",
            ).toJson())
        val conf = files.worldConfig(
            settingsSnapshot = Settings.Snapshot(timeframeStage = "vanilla-1.9"),
        ).readText()
        assertTrue(
            "the caller's snapshot drives the conf stage:\n$conf",
            "PocketRealm.TimeframeStage = \"vanilla-1.9\"" in conf,
        )
        assertTrue(
            "the store's own stage must not leak into the conf:\n$conf",
            "vanilla-1.5" !in conf,
        )
    }

    /**
     * Design-timeframe: an IMPOSSIBLE persisted stage (a stale stage of
     * an unqualified flavor, a never-registered id) degrades to the default
     * at the settings layer — the conf can never carry a stage the lane
     * cannot run, and the world start is never refused for one.
     */
    @Test
    fun worldConfigDegradesAnImpossibleStageToTheDefault() {
        val (roots, files) = files()
        stageActiveGeneration(roots)
        roots.settingsFile.parentFile?.mkdirs()
        // A raw (unvalidated) snapshot write simulates a stale/corrupted
        // persisted value that predates a flavor change or a hand edit.
        val stale = Settings.Snapshot(selectedFlavorId = "vanilla").toJson()
            .replace("\"timeframeStage\":\"\"", "\"timeframeStage\":\"tbc-2.4.3\"")
        roots.settingsFile.writeText(stale)
        val conf = files.worldConfig().readText()
        assertTrue(
            "an impossible stage must degrade to the qualified lane's default:\n$conf",
            "PocketRealm.TimeframeStage = \"vanilla-1.12.1\"" in conf,
        )
        assertTrue("tbc-2.4.3" !in conf)
    }

    /**
     * a non-default nearbyInteractTriggerGuardMs lands in the
     * conf's PocketRealm.NearbyInteractCooldownMs, normalized through the
     * shared policy (the bound-clamped value, never the raw one).
     */
    @Test
    fun worldConfigCarriesANonDefaultNearbyTriggerGuard() {
        val (roots, files) = files()
        stageActiveGeneration(roots)
        val conf = files.worldConfig(nearbyInteractTriggerGuardMs = 500).readText()
        assertTrue(
            "the 500 ms guard must ride the conf:\n$conf",
            "PocketRealm.NearbyInteractCooldownMs = 500" in conf,
        )
        // A guard outside the supported band is normalized (clamped), not
        // echoed.
        val clamped = files.worldConfig(nearbyInteractTriggerGuardMs = 99_999).readText()
        assertTrue(
            "an out-of-band guard must clamp to the policy maximum:\n$clamped",
            "PocketRealm.NearbyInteractCooldownMs = " +
                com.pocketrealm.server.NearbyInteractPolicy.MAX_TRIGGER_GUARD_MS in clamped,
        )
    }

    /**
     * the knob leg — the conf's PocketRealm.NearbyInteract rides
     * the backend-computed EFFECTIVE verdict (the authorized-layout
     * resolution of the requested tweaks over the user's actual exe), NOT
     * the requested consent alone: a consented request on a non-qualified
     * layout launches unpatched, so the requested-consent-without-verdict
     * leg must keep the knob at 0 (the original DESK-58 failure shape).
     */
    @Test
    fun nearbyUseKnobFollowsTheEffectiveVerdictNotTheRawConsent() {
        val (roots, files) = files()
        stageActiveGeneration(roots)
        // The consented request alone must NOT flip the knob...
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            Settings.Snapshot(
                tweaks = com.pocketrealm.client.ClientTweaksConfig(quicklootEnabled = true),
                selectedFlavorId = "vanilla",
            ).toJson(),
        )
        val unproven = files.worldConfig().readText()
        assertTrue(
            "requested consent without an effective verdict keeps the knob off:\n$unproven",
            "PocketRealm.NearbyInteract = 0" in unproven,
        )
        // ...only the backend's effective verdict (the exe hashed as the
        // authorized build-5875 layout) turns it on.
        val effective = files.worldConfig(nearbyUseEnabled = true).readText()
        assertTrue(
            "the effective verdict must turn the nearby-use knob on:\n$effective",
            "PocketRealm.NearbyInteract = 1" in effective,
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
            body.startsWith(profile.playerbotConfig(com.pocketrealm.client.ClientFlavors.VANILLA)),
        )
        assertTrue(
            "the retired bot-speech block is never emitted",
            "AiPlayerbot.LLMEnabled = 2" !in body,
        )
        assertTrue(
            "mangosd.conf points at the profile conf",
            "PocketRealm.PlayerbotConfig = \"${botConf.absolutePath}\"" in conf,
        )
    }

    /**
     * The stale-secret purge: only the ACTIVE profile's conf is rewritten
     * at each world start, so an endpoint key the retired bot-speech lane
     * staged would survive forever in every OTHER preset's conf. The next
     * world start must delete every staged bot conf carrying a key line —
     * the inactive preset's conf goes away entirely (it is re-staged from
     * its profile when that profile is next active), while keyless confs
     * (the disabled block) keep their bytes.
     */
    @Test
    fun worldStartPurgesStaleKeysFromInactivePresetConfs() {
        val (roots, files) = files()
        stageActiveGeneration(roots)
        val runDir = File(File(roots.runtime, "server"), "run")
        // An older build's bot-speech lane staged another preset's conf
        // with an endpoint key in it.
        val lively = com.pocketrealm.bots.BotProfiles.LIVELY_160
        files.worldConfig(botProfile = lively)
        val staleConf = File(runDir, "aiplayerbot-${lively.id}.conf")
        staleConf.appendText("AiPlayerbot.LLMApiKey = sk-old\n")
        // An earlier keyless start's disabled block shares the run dir.
        files.worldConfig()
        val disabled = File(runDir, "aiplayerbot-disabled.conf")
        assertTrue("precondition: the keyless disabled conf is staged", disabled.isFile)

        val profile = com.pocketrealm.bots.BotProfiles.defaultProfile
        val conf = files.worldConfig(botProfile = profile).readText()
        val active = File(runDir, "aiplayerbot-${profile.id}.conf")
        assertTrue(
            "mangosd.conf still points at the active conf",
            "PocketRealm.PlayerbotConfig = \"${active.absolutePath}\"" in conf,
        )
        assertFalse(
            "the stale-key preset conf must not survive the start: ${staleConf.absolutePath}",
            staleConf.isFile,
        )
        assertTrue("a keyless staged conf keeps its bytes", disabled.isFile)
        runDir.listFiles { file -> file.name.startsWith("aiplayerbot-") }!!
            .forEach { staged ->
                assertFalse("no staged bot conf may carry a key: ${staged.name}", "sk-old" in staged.readText())
            }
    }

    @Test
    fun worldConfigDebugToggleFlipsTheLogFileLevel() {
        val (roots, files) = files()
        stageActiveGeneration(roots)
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(Settings.Snapshot(worldDebugLogs = true, selectedFlavorId = "vanilla").toJson())
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
        // The oversized live log takes the .1 slot atomically (the incumbent
        // is REPLACED, never retired first — the failed-rotation pins below
        // hold that line), and the native writer recreates world.log on start.
        val rotated = File(logs, "world.log.1")
        assertTrue(rotated.isFile)
        assertEquals(5L * 1024L * 1024L, rotated.length())
        assertTrue(!world.isFile)
        assertTrue(
            "no stranded temp may survive a completed rotation: ${logs.list()?.toList()}",
            logs.list()!!.none { it.endsWith(".tmp") },
        )
    }

    /**
     * worldlog-rotation pin, leg 1 — the archive survives a FAILED rotation.
     * A locked archive slot (the antivirus/indexer hold that destroyed a
     * 77.8 MB world.log.1 under the old delete-then-rename in reexec-1)
     * refuses the rotation with the pinned WRONG_STATE code and leaves the
     * incumbent archive byte-identical: the slot is never retired before the
     * successor is in hand.
     */
    @Test
    fun failedRotationPreservesTheExistingArchive() {
        val (roots, files) = files()
        val logs = File(File(roots.runtime, "server"), "logs").apply { mkdirs() }
        File(logs, "world.log").apply { writeText("x".repeat(5 * 1024 * 1024)) }
        val archive = File(logs, "world.log.1").apply { writeText("previous-archive") }
        java.io.FileInputStream(archive).use { _ ->
            try {
                files.prepareWorldLogsForStart(ServerRuntimeContract.STOPPED)
                fail("a rotation whose archive slot is locked must refuse")
            } catch (expected: IllegalStateException) {
                assertTrue(
                    "refusal must carry the WRONG_STATE code: ${expected.message}",
                    "WRONG_STATE" in expected.message!!,
                )
            }
        }
        assertEquals(
            "the incumbent archive must survive the failed rotation",
            "previous-archive",
            archive.readText(),
        )
    }

    /**
     * worldlog-rotation pin, legs 2+3 — a failed rotation loses no log data
     * and every refusal surfaces WRONG_STATE. Both refusal points leave
     * every byte on disk under its honest name: an archive-move failure
     * restores the stepped-aside live log byte-identical, and a live-rename
     * failure never moves the live log at all — no delete-then-rename
     * window, no stranded temp.
     */
    @Test
    fun failedRotationKeepsTheLiveLogIntactAndRefusesWrongState() {
        val (roots, files) = files()
        val logs = File(File(roots.runtime, "server"), "logs").apply { mkdirs() }
        val live = File(logs, "world.log")

        // Leg A: the archive slot refuses the successor move (locked by
        // another reader, the realistic AV/indexer hold) — the live log
        // must come back under its own name, byte-identical.
        val oversized = "y".repeat(5 * 1024 * 1024)
        live.writeText(oversized)
        File(logs, "world.log.1").writeText("previous-archive")
        java.io.FileInputStream(File(logs, "world.log.1")).use { _ ->
            try {
                files.prepareWorldLogsForStart(ServerRuntimeContract.STOPPED)
                fail("the locked archive slot must refuse the rotation")
            } catch (expected: IllegalStateException) {
                assertTrue(
                    "refusal must carry the WRONG_STATE code: ${expected.message}",
                    "WRONG_STATE" in expected.message!!,
                )
            }
        }
        assertEquals(
            "the live log must be restored byte-identical",
            oversized,
            live.readText(),
        )
        assertTrue(
            "no stranded temp may survive a refused rotation: ${logs.list()?.toList()}",
            logs.list()!!.none { it.endsWith(".tmp") },
        )
        assertEquals(
            "the archive the refused rotation kept must be intact too",
            "previous-archive",
            File(logs, "world.log.1").readText(),
        )

        // Leg B: the live log itself refuses to step aside (locked) —
        // nothing was renamed away, so world.log must sit exactly as the
        // native writer left it, under its own name.
        val locked = "z".repeat(5 * 1024 * 1024)
        live.writeText(locked)
        java.io.FileInputStream(live).use { _ ->
            try {
                files.prepareWorldLogsForStart(ServerRuntimeContract.STOPPED)
                fail("a rotation whose live log is locked must refuse")
            } catch (expected: IllegalStateException) {
                assertTrue(
                    "refusal must carry the WRONG_STATE code: ${expected.message}",
                    "WRONG_STATE" in expected.message!!,
                )
            }
        }
        assertEquals(
            "the locked live log must be untouched",
            locked,
            live.readText(),
        )
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

    /** the LAN topology rides the settings snapshot round trip
     * (mode + the explicit allowLanPlayers gate + the canonical endpoint). */
    @Test
    fun settingsSnapshotCarriesTheLanTopology() {
        val hosted = Settings.Snapshot(
            runtimeMode = com.pocketrealm.supervisor.RuntimeMode.LAN_HOST,
            allowLanPlayers = true,
            lanAddress = "192.168.1.50",
        )
        val round = Settings.Snapshot.fromJson(hosted.toJson())
        assertEquals(com.pocketrealm.supervisor.RuntimeMode.LAN_HOST, round.runtimeMode)
        assertTrue(round.allowLanPlayers)
        assertEquals("192.168.1.50", round.lanAddress)
        val defaults = Settings.Snapshot.fromJson(Settings.Snapshot().toJson())
        assertEquals(com.pocketrealm.supervisor.RuntimeMode.LOCAL, defaults.runtimeMode)
        assertTrue(!defaults.allowLanPlayers)
        assertEquals("", defaults.lanAddress)
    }

    /**
     * the client-lane settings round trip through the
     * JSON store — the tweaks request, the audio mode (DESK-139's "setting
     * round-trips in the JSON store" leg), the normalized nearby guard
     * , and the prompt-pack JSON.
     */
    @Test
    fun settingsSnapshotCarriesTheClientLaunchLaneFields() {
        val tweaks = com.pocketrealm.client.ClientTweaksConfig(
            fovEnabled = true,
            quicklootEnabled = true,
            soundChannelsEnabled = true,
            soundChannels = 32,
        )
        val written = Settings.Snapshot(
            tweaks = tweaks,
            audioMode = Settings.AudioMode.OFF,
            nearbyInteractTriggerGuardMs = 600,
        )
        val store = com.pocketrealm.desktop.DesktopSettingsStore(
            folder.newFolder("store").resolve("settings.json"),
        )
        store.save(written)
        val loaded = store.load()
        assertEquals(tweaks, loaded.tweaks)
        assertEquals(Settings.AudioMode.OFF, loaded.audioMode)
        assertEquals(600, loaded.nearbyInteractTriggerGuardMs)

        // The defaults: audio ON (the Android twin's default), the policy's
        // default guard, the pristine tweak request.
        val defaults = Settings.Snapshot()
        assertEquals(Settings.AudioMode.ON, defaults.audioMode)
        assertEquals(
            com.pocketrealm.server.NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
            defaults.nearbyInteractTriggerGuardMs,
        )
        assertEquals(com.pocketrealm.client.ClientTweaksConfig(), defaults.tweaks)

        // The normalize-on-load/write contract (the Android :801/:1077 twin):
        // an out-of-band persisted guard clamps to the policy bounds on
        // decode, and the write path normalizes too.
        val outOfBand = Settings.Snapshot.fromJson(
            Settings.Snapshot(nearbyInteractTriggerGuardMs = 99_999).toJson(),
        )
        assertEquals(
            com.pocketrealm.server.NearbyInteractPolicy.MAX_TRIGGER_GUARD_MS,
            outOfBand.nearbyInteractTriggerGuardMs,
        )
        val corruptAudio = Settings.Snapshot.fromJson(
            Settings.Snapshot(audioMode = Settings.AudioMode.ON)
                .toJson()
                .replace("\"audioMode\":\"ON\"", "\"audioMode\":\"LOUD\""),
        )
        assertEquals("an unknown audio mode degrades to ON", Settings.AudioMode.ON, corruptAudio.audioMode)
        val corruptTweaks = Settings.Snapshot.fromJson(
            Settings.Snapshot().toJson().replace("\"tweaks\":{", "\"tweaks\":{\"bogus\":1,"),
        )
        assertEquals(
            "a corrupt tweaks payload degrades to the pristine request",
            com.pocketrealm.client.ClientTweaksConfig(),
            corruptTweaks.tweaks,
        )
    }


    /** serverDatabases resolves through the [ActiveFlavor]
     * machine, not the single-qualified vanilla pin. At the S5 flip
     * (two qualified flavors) the machine's honesty holds — with no
     * Home-switcher selection the resolution refuses loud instead of
     * guessing vanilla — and a selection resolves THAT lane's database
     * names, exactly the shape the old require() bricked. */
    @Test
    fun serverDatabasesResolveTheActiveLaneWithTwoQualifiedFlavors() {
        val vanilla = testFlavor("vanilla", qualified = true)
        val wotlk = testFlavor("wotlk", qualified = true, build = 12340)
        ActiveFlavor.qualifiedSource = { listOf(vanilla, wotlk) }
        try {
            ServerRuntimeFiles.serverDatabases()
            fail("two qualified flavors without a selection must refuse, not guess")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "the refusal must stay the machine's honest one: ${expected.message}",
                expected.message.orEmpty().contains("found 2"),
            )
        }
        ActiveFlavor.select("wotlk")
        assertEquals(wotlk.databases, ServerRuntimeFiles.serverDatabases())
        ActiveFlavor.select("vanilla")
        assertEquals(vanilla.databases, ServerRuntimeFiles.serverDatabases())
        // The old refusal copy's promise ("pass its ClientFlavor
        // explicitly") finally holds — the requireActive idiom.
        assertEquals(wotlk.databases, ServerRuntimeFiles.serverDatabases(wotlk))
    }

    /** realmd.conf tracks the active flavor's realmd database —
     * LoginDatabaseInfo names the selected lane's file at the flip, and
     * today's sole-qualified registry keeps emitting the exact vanilla
     * bytes (the golden pins' hard constraint: resolution changes,
     * vanilla bytes do not). */
    @Test
    fun realmdConfigTracksTheActiveFlavorAtTheS5Flip() {
        val (roots, files) = files()
        val vanilla = testFlavor("vanilla", qualified = true)
        val wotlk = testFlavor("wotlk", qualified = true, build = 12340)
        ActiveFlavor.qualifiedSource = { listOf(vanilla, wotlk) }
        // the START path resolves the PERSISTED pick (the /
        // binding the desktop never had) — the switcher's tap persists it,
        // so the fixture persists the pick instead of flipping only the
        // in-memory machine.
        com.pocketrealm.desktop.DesktopSettingsStore(roots.settingsFile).save(Settings.Snapshot(selectedFlavorId = "wotlk"))
        val flipped = files.realmdConfig().readText()
        val wotlkRealmd = DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, "wotlkrealmd")
        assertTrue(
            "the active lane's realmd must drive LoginDatabaseInfo:\n$flipped",
            "LoginDatabaseInfo = \"${wotlkRealmd.absolutePath}\"" in flipped,
        )
        com.pocketrealm.desktop.DesktopSettingsStore(roots.settingsFile).save(Settings.Snapshot(selectedFlavorId = "vanilla"))
        val vanillaConf = files.realmdConfig().readText()
        val vanillaRealmd = DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, "vanillarealmd")
        assertTrue(
            "vanilla selected must drive its own lane:\n$vanillaConf",
            "LoginDatabaseInfo = \"${vanillaRealmd.absolutePath}\"" in vanillaConf,
        )
        ActiveFlavor.reset()
        val registryConf = files.realmdConfig().readText()
        val classicRealmd = DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, "classicrealmd")
        assertTrue(
            "the real registry's vanilla lane must keep its exact bytes:\n$registryConf",
            "LoginDatabaseInfo = \"${classicRealmd.absolutePath}\"" in registryConf,
        )
    }

    /** the realmlist projection (DESK-93's flavor-registry
     * resolution site in DesktopRuntimeBackend) projects the ACTIVE
     * flavor's realmd — at the S5 flip the selected lane's database
     * gets the advertised address, where the old require() turned the
     * same call into REALMLIST-PROJECTION-FAILED (the bricked lane). */
    @Test
    fun projectRealmAdvertisementProjectsTheActiveFlavorsRealmlist() {
        requireNativeIfDemanded("sqlite seam", seamDll)
        val roots = DesktopStorageRoots(folder.newFolder("s5-flip-projection"))
        pickVanilla(roots)
        // The DESK-5/47/78 default flip made MARIADB the fresh-init default;
        // this projection seeds and asserts the SQLITE datadir, so — like
        // every sibling sqlite-lane projection test — it selects the
        // provider explicitly instead of riding the pre-flip default.
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            com.pocketrealm.storage.Settings.Snapshot(
                databaseProvider = com.pocketrealm.storage.Settings.DatabaseProvider.SQLITE,
                selectedFlavorId = "vanilla",
            ).toJson(),
        )
        roots.sqliteDatadir.mkdirs()
        val vanilla = testFlavor("vanilla", qualified = true)
        val wotlk = testFlavor("wotlk", qualified = true, build = 12340)
        ActiveFlavor.qualifiedSource = { listOf(vanilla, wotlk) }
        ActiveFlavor.select("wotlk")
        val wotlkRealmd = DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, "wotlkrealmd")
        DesktopSqliteConnection(wotlkRealmd.absolutePath).use { db ->
            db.exec(
                "CREATE TABLE realmlist (id INTEGER PRIMARY KEY, address TEXT, port INTEGER, " +
                    "realmbuilds TEXT NOT NULL DEFAULT '');",
            )
            db.exec("INSERT INTO realmlist VALUES (1, '127.0.0.1', 3724, '');")
        }
        val backend = DesktopRuntimeBackend(roots)
        val endpoint = RealmEndpoint.parseLan("192.168.1.50")
        val result = runBlocking { backend.projectRealmAdvertisement(owner, endpoint, "192.168.1.50") }
        assertTrue("the active flavor's projection must succeed: ${result.detail}", result.ok)
        DesktopSqliteConnection(wotlkRealmd.absolutePath).use { db ->
            assertEquals(
                "the advertised address must land in the ACTIVE lane's realmlist",
                "192.168.1.50",
                db.queryText("SELECT address FROM realmlist WHERE id = 1;"),
            )
            // The accepted-builds advertisement follows the SAME active
            // lane (testFlavor's realmdAcceptedBuilds is "$build"): the
            // seed's realmbuilds is EMPTY, so this write is what makes
            // realmd's ok_build gate pass for the lane's clients.
            assertEquals(
                "the ACTIVE lane's accepted builds must land in its realmlist",
                "12340",
                db.queryText("SELECT realmbuilds FROM realmlist WHERE id = 1;"),
            )
        }
        assertFalse(
            "the vanilla lane's database must stay untouched at the flip",
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, "classicrealmd").isFile,
        )
    }

    @Test
    fun `the disabled conf staging leg runs the fail-loud gate R6-5`() {
        // the desktop aiplayerbot-disabled
        // leg is the desktop twin of the android fix — the
        // staged body must pass BotConfStaging for the boot lane (proven
        // here by driving the real worldConfig write), and the gate must
        // textually precede the disabled write (the source-shape pin).
        val (roots, files) = files()
        stageActiveGeneration(roots)
        files.worldConfig()
        val staged = File(
            File(File(roots.runtime, "server"), "run"),
            "aiplayerbot-disabled.conf",
        ).readText()
        val bootFlavor = ActiveFlavor.resolve(
            "the disabled-leg test resolves the boot lane",
        ).flavorId
        val verdict = BotConfStaging.validate(bootFlavor, staged)
        assertTrue(
            "$bootFlavor: the STAGED disabled conf must pass the gate, got " +
                "unknown=${verdict.unknownKeys} excluded=${verdict.excludedKeys}",
            verdict.isClean,
        )
        // The literal is flavor-invariant: it validates clean on every
        // registered lane (the independent jshell probe
        // confirmed all three before the gate was wired).
        for (flavor in listOf("vanilla", "tbc", "wotlk")) {
            assertTrue(
                "$flavor: the disabled emission must stage clean",
                BotConfStaging.validate(flavor, staged).isClean,
            )
        }
        // Source-shape pin: the gate call textually precedes the disabled
        // write (a revert to the ungated inline write fails here).
        val source = File(
            "src/main/kotlin/com/pocketrealm/server/ServerRuntimeFiles.kt",
        ).readText()
        val gateAt = source.indexOf(
            "BotConfStaging.requireStagingSafe(lane.flavorId, \"disabled\"",
        )
        val writeAt = source.indexOf("\"aiplayerbot-disabled.conf\"")
        assertTrue(
            "the desktop disabled leg must call requireStagingSafe for the " +
                "disabled body (the R2-1 parity fix)",
            gateAt >= 0,
        )
        assertTrue(
            "the gate must precede the disabled write",
            gateAt in 0 until writeAt,
        )
    }
}
