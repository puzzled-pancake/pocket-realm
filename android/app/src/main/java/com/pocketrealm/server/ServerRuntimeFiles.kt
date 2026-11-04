package com.pocketrealm.server

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.pocketrealm.bots.BotProfile
import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ActiveFlavorState
import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.TimeframeStages
import com.pocketrealm.database.DatabaseDurableState
import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.storage.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.pocketrealm.storage.StorageRoots
import com.pocketrealm.supervisor.RealmEndpoint
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Produces only fixed, app-private configuration; credentials never cross Binder or logs. */
internal class ServerRuntimeFiles(context: Context) {
    private val appContext = context.applicationContext
    private val roots = StorageRoots.get(appContext)
    private val root = File(appContext.noBackupFilesDir, "server").apply { mkdirs() }
    private val run = File(root, "run").apply { mkdirs() }
    private val logs = File(root, "logs").apply { mkdirs() }
    private val lifecycle = File(root, "lifecycle").apply { mkdirs() }
    private val baselineData = File(roots.content, "o09-server/active")
    private val normalData = PreparedDataStore(File(roots.content, "o11-server"))
    private val secretFile = File(roots.databaseRoot, "secrets.json")

    /** The conf-stage lane suffix (the same wording worldConfig's persisted
     * machine resolves) for the database-name resolver. */
    private val serverRuntimeLane =
        "the server runtime resolves the persisted lane for its conf and logon clients"

    /** the:world process's
     * per-flavor resolver for every data/verify lane. The [ActiveFlavor]
     * holder's selection is :ui-process-local in-memory state (the Home
     * picker) that never crosses the process boundary, so a bare holder
     * resolve here would refuse at a multi-qualified flip even with a valid
     * persisted pick. Resolve through the PERSISTED Home selection (or the
     * threaded settings snapshot when the caller carries one), threaded as
     * a value exactly like the :supervisor twins (AndroidRuntimeBackend
     * REALMD_BUILDS_LANE): sole qualified degrades to it, the persisted
     * pick is honored, and an ambiguous post-flip state refuses loud. */
    fun worldLaneFlavor(lane: String, settingsSnapshot: Settings.Snapshot? = null): ClientFlavor {
        val persisted = settingsSnapshot?.selectedFlavorOrNull()
            ?: kotlinx.coroutines.runBlocking {
                Settings(appContext).flow.first().selectedFlavorOrNull()
            }
        return com.pocketrealm.client.ActiveFlavorState(persisted)
            .resolve(ActiveFlavor.qualifiedFlavors, lane)
    }

    /**
     * the ANDROID boot-time lane-vs-runtime
     * gate — the desktop requireRuntimeLaneMatches twin. The packaged
     * Android realm runtime is the VANILLA lane's libpocket_world_runtime.so:
     * W5 built and locked the HOST-SIDE per-flavor native lanes
     * (schemas/realm-runtime-lockfile-{tbc,wotlk}-arm64-v8a.json against
     * native/.build-o09-arm64-v8a-{flavor}/realm-staging) but
     * the APK's gradle flavor routing — packaging a per-flavor staging tree
     * into the image — is recorded as open follow-up work in DECISIONS-PORT
     * D90, so the image still carries exactly one realm runtime and booting
     * vanilla core bytes against a tbc/wotlk conf+database family is
     * refused LOUD before startNative — the exact wrong-core boot the
     * desktop refuses with RUNTIME-LANE MISMATCH. Until the D90 wiring
     * ships, every non-vanilla boot on this device refuses with the
     * actionable copy.
     */

    fun acquireNormalDataLease(): PreparedDataStore.GenerationLease = normalData.acquireRuntimeLease()

    /** Rotate only between native process lifetimes; active writers are never renamed. */
    fun prepareRealmLogsForStart(nativeState: Long) {
        StartLogRotationPolicy.requireStopped("realm", nativeState)
        rotateRestartLog(File(logs, "realmd.log"))
    }

    /** Rotate only between native process lifetimes; active writers are never renamed. */
    fun prepareWorldLogsForStart(nativeState: Long) {
        StartLogRotationPolicy.requireStopped("world", nativeState)
        rotateRestartLog(File(logs, "world.log"))
        rotateRestartLog(File(logs, "database-errors.log"), MAX_ERROR_LOG_BYTES)
    }

    fun realmdConfig(
        bindAddress: String = RealmEndpoint.LOOPBACK_ADDRESS,
        settingsSnapshot: Settings.Snapshot? = null,
    ): File {
        val bind = ServerRuntimeContract.resolveBindAddress(bindAddress)
        val databases = serverDatabases(settingsSnapshot)
        return secureWrite(
            File(run, "realmd.conf"),
            realmdConfText(
                loginDatabaseInfo = databaseInfo(databases.realmd),
                bindAddress = bind,
                realmLogFilePath = logs.resolve("realmd.log").absolutePath,
            ),
        )
    }

    fun worldConfig(
        bindAddress: String = RealmEndpoint.LOOPBACK_ADDRESS,
        nearbyInteractTriggerGuardMs: Int = NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
        settingsSnapshot: Settings.Snapshot? = null,
    ): File {
        require(baselineData.isDirectory) {
            "Prepared server world data is missing. Open Game files and finish preparing it."
        }
        require(File(baselineData, "BUILD_PROVENANCE.json").isFile) {
            "Prepared server world data did not pass its integrity check. Prepare it again from Game files."
        }
        return worldConfig(
            baselineData,
            normalPlay = false,
            bindAddress = bindAddress,
            nearbyInteractTriggerGuardMs = nearbyInteractTriggerGuardMs,
            settingsSnapshot = settingsSnapshot,
        )
    }

    /** Production entry point; refuses normal play unless every import artifact verifies. */
    fun worldConfigNormal(
        bindAddress: String = RealmEndpoint.LOOPBACK_ADDRESS,
        nearbyInteractTriggerGuardMs: Int = NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
        settingsSnapshot: Settings.Snapshot? = null,
    ): File {
        // the verification lane resolves the same flavor the
        // conf stage below resolves - the persisted Home selection threaded
        // from the caller's snapshot, never the :ui-local holder.
        val active = normalData.requireActive(worldLaneFlavor(PreparedDataStore.SERVER_DATA_LANE, settingsSnapshot))
        return worldConfig(
            active.root,
            normalPlay = true,
            bindAddress = bindAddress,
            nearbyInteractTriggerGuardMs = nearbyInteractTriggerGuardMs,
            settingsSnapshot = settingsSnapshot,
        )
    }

    /** Measured bot-profile entry point. Auction-house automation remains disabled. */
    fun worldConfigBot(
        profile: BotProfile,
        bindAddress: String = RealmEndpoint.LOOPBACK_ADDRESS,
        nearbyInteractTriggerGuardMs: Int = NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
        settingsSnapshot: Settings.Snapshot? = null,
    ): File {
        val active = normalData.requireActive(worldLaneFlavor(PreparedDataStore.SERVER_DATA_LANE, settingsSnapshot))
        return worldConfig(
            active.root,
            normalPlay = true,
            botProfile = profile,
            bindAddress = bindAddress,
            nearbyInteractTriggerGuardMs = nearbyInteractTriggerGuardMs,
            settingsSnapshot = settingsSnapshot,
        )
    }

    private fun worldConfig(
        data: File,
        normalPlay: Boolean,
        botProfile: BotProfile? = null,
        bindAddress: String,
        nearbyInteractTriggerGuardMs: Int,
        settingsSnapshot: Settings.Snapshot?,
    ): File {
        val bind = ServerRuntimeContract.resolveBindAddress(bindAddress)
        // One blocking settings read at world start feeds the world log
        // level - every world.conf toggle therefore applies on the next
        // realm start, never mid-session. The caller's snapshot wins when
        // provided: WorldRuntimeService resolves the stage ONCE per start
        // and feeds both this conf write and the stage overlay — two
        // independent loads could straddle a settings write and boot a
        // world whose conf names a stage the DB/ledger never reached.
        val snapshot = settingsSnapshot ?: Settings(appContext).blockingSnapshot()
        purgeStaleBotConfSecrets()
        // the boot lane resolves ONCE here — the same resolution the
        // conf's stage value below carries — so the fail-loud conf staging
        // and the conf emission can never name different flavors.
        val laneFlavor = worldLaneFlavor(
            "the conf staging resolves the qualified lane",
            settingsSnapshot,
        )
        val botConfig = if (botProfile != null) {
            // the conf VALUES ride the same pinned boot lane as
            // the staging gate — never the :ui holder.
            val botConfText = botProfile.playerbotConfig(laneFlavor)
            // W3.4 fail-loud conf staging: an unknown key for the flavor's
            // bots build aborts the start instead of being silently dropped
            // by the core's config parser; excluded families (VoiceChat)
            // are refused with their own naming.
            BotConfStaging.requireStagingSafe(laneFlavor.flavorId, botProfile.id, botConfText)
            secureWrite(File(run, "aiplayerbot-${botProfile.id}.conf"), botConfText)
        } else {
            val disabledConfText = aiplayerbotDisabledConfText()
            // the disabled staging leg runs the SAME gate —
            // a drifted or excluded key in the disabled emission fails the
            // world start exactly like a profile conf would.
            BotConfStaging.requireStagingSafe(laneFlavor.flavorId, "disabled", disabledConfText)
            secureWrite(File(run, "aiplayerbot-disabled.conf"), disabledConfText)
        }
        val databases = serverDatabases(settingsSnapshot)
        return secureWrite(
            File(run, "mangosd.conf"),
            mangosdConfText(
                dataDirectoryAbsolutePath = data.absolutePath,
                loginDatabaseInfo = databaseInfo(databases.realmd),
                worldDatabaseInfo = databaseInfo(databases.world),
                characterDatabaseInfo = databaseInfo(databases.characters),
                logsDatabaseInfo = databaseInfo(databases.logs),
                bindAddress = bind,
                normalPlay = normalPlay,
                worldLogFilePath = logs.resolve("world.log").absolutePath,
                worldLogFileLevel = worldLogFileLevel(snapshot.worldDebugLogs),
                databaseErrorsLogFilePath = logs.resolve("database-errors.log").absolutePath,
                nearbyInteractCooldownMs =
                    NearbyInteractPolicy.normalizeTriggerGuardMs(nearbyInteractTriggerGuardMs),
                playerbotConfigPath = botConfig.absolutePath,
                botTarget = botProfile?.initialTarget ?: 0,
                // Design-timeframe: the persisted stage resolved to the
                // "<stage-or-default>" conf value (never empty).
                // the ACTIVE flavor side of the resolution is
                // this process's Settings-threaded lane (the holder's
                // selection is :ui-local) - the same lane the data verify
                // and the overlay binding above resolve (W3.4: one hoisted
                // resolution feeds the conf staging AND the stage value, so
                // they can never name different flavors).
                timeframeStage = TimeframeStages.confValueFor(
                    snapshot.timeframeStage,
                    laneFlavor.flavorId,
                ),
            ) + xpRateConfLines(
                snapshot.xpRateKill,
                snapshot.xpRateQuest,
                snapshot.xpRateExplore,
                snapshot.xpRatePetKill,
            ) + "\n",
        )
    }

    /**
     * The stale-secret purge (the desktop twin's F3 fix): a world start
     * stages exactly ONE bot conf (the active profile's — or the disabled
     * block), so an API key staged by an older build (the retired bot-speech
     * endpoint) would otherwise survive indefinitely in every OTHER preset's
     * staged conf. Before the
     * active conf is rewritten, delete every staged aiplayerbot-*.conf that
     * carries a key line — an inactive conf is never referenced by the
     * running world (mangosd.conf names exactly one) and is re-staged from
     * its profile whenever that profile becomes active again, so nothing is
     * lost but the secret. Keyless confs (the disabled block, a
     * fail-closed profile conf) keep their bytes. Fail-open per file
     * (the staging posture): a locked file must never refuse a world
     * start.
     */
    private fun purgeStaleBotConfSecrets() {
        purgeStaleBotConfSecrets(run)
    }

    /** A fresh app-private scratch path for one character-transfer dump. */
    fun characterTransferFile(): File =
        File(run, "transfer-${java.util.UUID.randomUUID()}.sql")

    fun writeLifecycle(component: String, clean: Boolean, operation: String, detail: String = "") {
        require(component == "realm" || component == "world") { "unknown server component" }
        val value = JSONObject().put("schema", 1).put("component", component)
            .put("clean", clean).put("operation", operation.take(64))
            .put("detail", detail.take(256)).put("at", System.currentTimeMillis()).toString()
        secureWrite(File(lifecycle, "$component.json"), value)
    }

    /**
     * The per-flavor database-name set the conf templates seed, resolved
     * from the flavor registry ([ClientFlavor.databases]) instead of
     * hard-coded names. W4 (D72 retirement, 2026-10-12): the
     * lane is the PERSISTED Home selection through the same machine
     * [worldLaneFlavor] serves — sole qualified degrades, the pick is
     * honored, an ambiguous post-flip state refuses loud. The former
     * exactly-one + vanilla-only requirement (the android W1.2
     * per-flavor-seeding deferral) is retired with the per-flavor sqlite
     * seeds (tools/seed_realm_db.py --flavor, S4 item 3).
     */
    private fun serverDatabases(settingsSnapshot: Settings.Snapshot? = null): ClientFlavor.DatabaseNames {
        return worldLaneFlavor(serverRuntimeLane, settingsSnapshot).databases
    }

    /**
     * The DatabaseInfo connection string for one database. The SQLite
     * provider's runtimes open the datadir files IN-PROCESS (the string is
     * the file path the DO_SQLITE backend feeds to sqlite3_open); MariaDB
     * keeps the socket form. The decision reads the engine's durable
     * active-provider marker COMBINED with this APK's own capability: a
     * marker naming the SQLite provider in a non-sqlite APK (the window's
     * APK-level rollback - a default build installed over a window build)
     * must boot MariaDB, whose datadir this path never deletes.
     */
    private fun databaseInfo(name: String): String {
        val sqliteServing = DatabaseDurableState.parseActiveProviderMarker(
            File(roots.databaseRoot, DatabaseDurableState.ACTIVE_PROVIDER_MARKER_NAME)
                .takeIf(File::isFile)?.readText(),
        )?.mode == DatabaseDurableState.ProviderMode.SQLITE &&
            runCatching {
                appContext.assets.open("database/provider-sqlite/BUILD_PROVENANCE.json").close()
            }.isSuccess
        if (sqliteServing) {
            val datadir = File(roots.databaseRoot, DatabaseSqliteControlPlane.SQLITE_DATADIR_NAME)
            return DatabaseSqliteControlPlane.databaseFile(datadir, name).absolutePath
        }
        val secret = coreSecret()
        val socket = roots.databaseRun.resolve("mariadb.sock").absolutePath
        return ".;$socket;pocket_core;$secret;$name"
    }

    private fun coreSecret(): String {
        check(secretFile.isFile) { "database must be initialized before server start" }
        val secret = JSONObject(secretFile.readText()).getString("core")
        check(secret.matches(Regex("[0-9a-f]{48}"))) { "database credential record is invalid" }
        return secret
    }

    private fun secureWrite(target: File, text: String): File {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${android.os.Process.myPid()}.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8)); stream.fd.sync()
        }
        Os.chmod(temp.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        check(temp.renameTo(target) || (target.delete() && temp.renameTo(target))) {
            "cannot replace ${target.name}"
        }
        return target
    }

    /**
     * Keep one oversized previous-session diagnostic when the native writer
     * is stopped. The Android twin of the desktop rotation fix (desktop
     * [ServerRuntimeFiles.rotateRestartLog]): never delete-then-rename —
     * the live log first steps aside under a unique temp name, and only
     * then takes the archive slot. Every failure keeps the bytes — the
     * previous archive keeps its slot and the live log keeps (or regains)
     * its name — and the refusal carries the pinned WRONG_STATE code: the
     * old delete-then-rename destroyed a 77.8 MB world.log.1 the moment its
     * rename lost (reexec-1), and masked the one-lifetime refusal as
     * "cannot rotate world.log".
     */
    private fun rotateRestartLog(file: File, maximumBytes: Long = MAX_NORMAL_LOG_BYTES) {
        rotateRestartLogBytes(file, maximumBytes, android.os.Process.myPid())
    }

    companion object {
        internal fun requireAndroidRuntimeLaneMatches(bootLane: ClientFlavor) {
            check(bootLane.flavorId == ClientFlavors.VANILLA.flavorId) {
                "RUNTIME-LANE MISMATCH: the Android image ships the vanilla " +
                    "lane's pocket_* runtime only — booting vanilla core bytes " +
                    "against the ${bootLane.flavorId} database family is refused " +
                    "(per-flavor runtime packaging is recorded as open follow-up " +
                    "work in the decision log, D90 — the native lanes and their " +
                    "lockfiles exist host-side, the image does not route them " +
                    "yet). Re-pick Vanilla on Home to boot this device."
            }
        }
        private const val MAX_NORMAL_LOG_BYTES = 4L * 1024L * 1024L
        private const val MAX_ERROR_LOG_BYTES = 8L * 1024L * 1024L

        /**
         * The rotation core the instance lane feeds — pure in its inputs so
         * the android unit set can pin the failed-rotation legs without an
         * app context (the same companion seam [realmdConfText] uses).
         * Ported exactly from the desktop twin: never delete-then-rename —
         * the live log first steps aside under a unique temp name, and only
         * then takes the archive slot. Every failure keeps the bytes — the
         * previous archive keeps its slot and the live log keeps (or
         * regains) its name — and every refusal carries the pinned
         * WRONG_STATE code.
         *
         * @param pid the rotating process id ([android.os.Process.myPid] on
         * device) — a uniqueness salt in the holding temp's name, never a
         * delete permission.
         */
        internal fun rotateRestartLogBytes(file: File, maximumBytes: Long, pid: Int) {
            if (!file.isFile || file.length() <= maximumBytes) return
            val previous = File(file.parentFile, "${file.name}.1")
            // Step 1: the live log steps aside; nothing is ever deleted first.
            // A failure here leaves the live log exactly where it was.
            val holding = File(
                file.parentFile,
                ".${file.name}.$pid.${System.nanoTime()}.rotating.tmp",
            )
            if (!file.renameTo(holding)) {
                throw IllegalStateException(
                    "WRONG_STATE: cannot rotate ${file.name} (the live log stays in place)",
                )
            }
            // Step 2: the temp takes the archive slot. A locked/occupied slot
            // (an antivirus scanner, an indexer) refuses the move with the
            // incumbent archive untouched and the live log restored — pinned
            // by ServerRuntimeFilesRotationTest's failed-rotation legs, the
            // android twin of DesktopServerRuntimeFilesTest's.
            val failure = archiveMove(holding, previous) ?: return
            if (!holding.renameTo(file)) {
                throw IllegalStateException(
                    "WRONG_STATE: cannot rotate ${file.name} — the archive keeps its " +
                        "previous session and the live log's bytes are held at " +
                        "${holding.name} (${failure.javaClass.simpleName}: ${failure.message})",
                    failure,
                )
            }
            throw IllegalStateException(
                "WRONG_STATE: cannot rotate ${file.name} into ${previous.name} — the " +
                    "archive keeps its previous session and the live log is intact " +
                    "(${failure.javaClass.simpleName}: ${failure.message})",
                failure,
            )
        }

        /**
         * The atomic-replace move as a probe: null = [temp] took the
         * [archive] slot; non-null = the failure (the slot untouched — a
         * refused REPLACE_EXISTING move never destroys its target).
         */
        private fun archiveMove(temp: File, archive: File): java.io.IOException? = try {
            try {
                java.nio.file.Files.move(
                    temp.toPath(),
                    archive.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    temp.toPath(),
                    archive.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
            null
        } catch (failure: java.io.IOException) {
            failure
        }

        /**
         * The purge core the instance lane feeds — pure in its inputs so
         * the android unit set can pin the stale-key eviction without an
         * app context (the same companion seam [rotateRestartLogBytes]
         * uses). Ported exactly from the desktop twin
         * (DesktopServerRuntimeFiles.purgeStaleBotConfSecrets): delete
         * every staged aiplayerbot-*.conf whose body carries a key line;
         * keyless confs keep their bytes; fail-open per file.
         */
        internal fun purgeStaleBotConfSecrets(runDir: File) {
            runDir.listFiles()?.forEach { staged ->
                if (!staged.isFile || !staged.name.startsWith("aiplayerbot-") ||
                    !staged.name.endsWith(".conf")
                ) return@forEach
                val body = runCatching { staged.readText() }.getOrNull() ?: return@forEach
                if ("AiPlayerbot.LLMApiKey =" in body ||
                    "AiPlayerbot.LLMChatterComposerKey =" in body
                ) {
                    runCatching { staged.delete() }
                }
            }
        }

        /**
         * the pure conf-template bodies the instance writers feed, so
         * [VanillaConfGoldenTest] can byte-pin the vanilla lane's generated
         * server conf against committed fixtures before any S2/S3
         * parameterization touches the templates. The composition is moved
         * verbatim out of [realmdConfig]/[worldConfig] — same keys, same
         * order, same whitespace, CRLF-free LF-terminated lines.
         */
        internal fun realmdConfText(
            loginDatabaseInfo: String,
            bindAddress: String,
            realmLogFilePath: String,
        ): String = """
            LoginDatabaseInfo = "$loginDatabaseInfo"
            RealmServerPort = ${ServerRuntimeContract.REALM_PORT}
            BindIP = "$bindAddress"
            RealmsStateUpdateDelay = 20
            ListenerThreads = 1
            LogLevel = 1
            LogFile = "$realmLogFilePath"
            LogFileLevel = 1
        """.trimIndent() + "\n" +
            // design-remote-accounts / (the desktop ServerRuntimeFiles
            // D11 twin): login lockout on every non-loopback bind, omitted
            // for loopback-only LOCAL binds. Loopback output is unchanged, so
            // the vanilla golden pin below stays byte-exact.
            ServerRuntimeContract.wrongPassLockoutConfLines(bindAddress)

        internal fun aiplayerbotDisabledConfText(): String = """
            AiPlayerbot.Enabled = 0
            AiPlayerbot.RandomBotAutologin = 0
            AiPlayerbot.RandomBotLoginAtStartup = 0
            AiPlayerbot.RandomBotAutoCreate = 0
            AiPlayerbot.CommandServerPort = 0
            AiPlayerbot.LLMEnabled = 0
            AiPlayerbot.ShowProgressBars = 0
        """.trimIndent() + "\n"

        // The faithful template signature — one parameter per pinned conf
        // line; splitting it would obscure the byte-pinned composition.
        @Suppress("LongParameterList")
        internal fun mangosdConfText(
            dataDirectoryAbsolutePath: String,
            loginDatabaseInfo: String,
            worldDatabaseInfo: String,
            characterDatabaseInfo: String,
            logsDatabaseInfo: String,
            bindAddress: String,
            normalPlay: Boolean,
            worldLogFilePath: String,
            worldLogFileLevel: Int,
            databaseErrorsLogFilePath: String,
            nearbyInteractCooldownMs: Int,
            playerbotConfigPath: String,
            botTarget: Int,
            /** Design-timeframe: the RESOLVED stage id (never empty —
             * callers resolve "" through TimeframeStages.confValue). */
            timeframeStage: String,
        ): String = """
            DataDir = "$dataDirectoryAbsolutePath"
            LoginDatabaseInfo = "$loginDatabaseInfo"
            WorldDatabaseInfo = "$worldDatabaseInfo"
            CharacterDatabaseInfo = "$characterDatabaseInfo"
            LogsDatabaseInfo = "$logsDatabaseInfo"
            RealmID = 1
            WorldServerPort = ${ServerRuntimeContract.WORLD_PORT}
            BindIP = "$bindAddress"
            Network.Threads = 1
            Console.Enable = 0
            Ra.Enable = 0
            SOAP.Enabled = 0
            vmap.enableLOS = ${if (normalPlay) 1 else 0}
            vmap.enableHeight = ${if (normalPlay) 1 else 0}
            vmap.enableIndoorCheck = ${if (normalPlay) 1 else 0}
            mmap.enabled = ${if (normalPlay) 1 else 0}
            LogLevel = 1
            LogFile = "$worldLogFilePath"
            LogFileLevel = $worldLogFileLevel
            DBErrorLogFile = "$databaseErrorsLogFilePath"
            PlayerLimit = ${ServerRuntimeContract.WORLD_PLAYER_LIMIT}
            # The production realm is app-private and loopback-only. Wine's
            # clock can deliver buffered CMSG_PING packets in a burst after a
            # slow emulated frame; CMaNGOS documents 0 as disabling this kick.
            MaxOverspeedPings = 0
            MaxCoreStuckTime = 0
            # Handheld play can make returning to a corpse and aiming the
            # pointer take longer. These timers apply only to creatures that
            # spawned loot and have not yet been fully looted or skinned.
            Corpse.Decay.NORMAL = 1800
            Corpse.Decay.RARE = 3600
            Corpse.Decay.ELITE = 3600
            Corpse.Decay.RAREELITE = 7200
            Corpse.Decay.WORLDBOSS = 14400
            # Vanilla 1.12 has no Interact Target key. The managed controller
            # add-on may request one normal nearby loot/use action through its
            # authenticated session. The realm still applies five-yard range,
            # line-of-sight, lock, ownership and ordinary loot rules. This
            # bounded debounce can be raised for slower realm configurations.
            PocketRealm.NearbyInteract = 1
            PocketRealm.NearbyInteractCooldownMs = $nearbyInteractCooldownMs
            PocketRealm.PlayerbotConfig = "$playerbotConfigPath"
            PocketRealm.BotTarget = $botTarget
            # Design-timeframe §4.2: the chosen content-patch stage of the
            # qualified flavor ("<stage-or-default>", never empty). The game
            # client stays at its fixed version; this gates world content
            # only. Applied at world start — never mid-session.
            PocketRealm.TimeframeStage = "$timeframeStage"
        """.trimIndent() + "\n"

        /** Errors-only world log level, matching realmd's LogFileLevel = 1. */
        internal const val DEFAULT_WORLD_LOG_FILE_LEVEL = 1

        /** The verbose world log level staged by the World debug logs toggle. */
        internal const val DEBUG_WORLD_LOG_FILE_LEVEL = 3

        /**
         * The world.conf LogFileLevel staged at world start. The default
         * drops the vendored level 3 to errors-only: level 3 floods world.log
         * (79.7 MB over a 30-minute soak) with movement and battleground
         * churn that has never diagnosed a field issue, while level 1 still
         * records errors and matches realmd. Level 2 was rejected - it keeps
         * the movement churn. The advanced "World debug logs" toggle opts
         * back into verbose, effective on the next realm start.
         */
        internal fun worldLogFileLevel(worldDebugLogs: Boolean): Int =
            if (worldDebugLogs) DEBUG_WORLD_LOG_FILE_LEVEL else DEFAULT_WORLD_LOG_FILE_LEVEL

        /**
         * The Rate.XP block staged into the generated mangosd.conf, pure in
         * its inputs so the emission is unit-testable. Every rate passes
         * through [XpRatePolicy.normalize] (band + one-decimal snap), so a
         * stale or hand-edited preference store can never stage a malformed
         * conf value. 1x emits `Rate.XP.Kill = 1` — value-equivalent to the
         * CMaNGOS default, so the default realm behaves identically even
         * though the conf gains these four lines.
         */
        internal fun xpRateConfLines(
            kill: Double,
            quest: Double,
            explore: Double,
            petKill: Double,
        ): String =
            listOf("Rate.XP.Kill", "Rate.XP.Quest", "Rate.XP.Explore", "Rate.Pet.XP.Kill")
                .zip(listOf(kill, quest, explore, petKill))
                .joinToString("\n") { (name, rate) ->
                    "$name = ${XpRatePolicy.format(rate)}"
                }
    }
}

internal object StartLogRotationPolicy {
    fun requireStopped(component: String, nativeState: Long) {
        check(nativeState == ServerRuntimeContract.STOPPED) {
            "$component start requires a stopped native runtime; current state=" +
                ServerRuntimeContract.stateName(nativeState)
        }
    }
}
