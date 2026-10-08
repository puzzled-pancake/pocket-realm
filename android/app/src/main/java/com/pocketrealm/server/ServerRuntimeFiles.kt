package com.pocketrealm.server

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.pocketrealm.BuildConfig
import com.pocketrealm.bots.BotLlmSpeech
import com.pocketrealm.bots.BotProfile
import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.TimeframeStages
import com.pocketrealm.database.DatabaseDurableState
import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.llm.LlmModelCoordinator
import com.pocketrealm.llm.LlmModelRegistry
import com.pocketrealm.llm.LlmPromptPack
import com.pocketrealm.llm.LlmTierProfile
import com.pocketrealm.llm.LlmSamplingProfile
import com.pocketrealm.storage.Settings
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

    fun realmdConfig(bindAddress: String = RealmEndpoint.LOOPBACK_ADDRESS): File {
        val bind = ServerRuntimeContract.resolveBindAddress(bindAddress)
        val databases = serverDatabases()
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
    ): File = worldConfig(
        normalData.requireActive().root,
        normalPlay = true,
        bindAddress = bindAddress,
        nearbyInteractTriggerGuardMs = nearbyInteractTriggerGuardMs,
        settingsSnapshot = settingsSnapshot,
    )

    /** Measured bot-profile entry point. Auction-house automation remains disabled. */
    fun worldConfigBot(
        profile: BotProfile,
        bindAddress: String = RealmEndpoint.LOOPBACK_ADDRESS,
        nearbyInteractTriggerGuardMs: Int = NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
        settingsSnapshot: Settings.Snapshot? = null,
    ): File = worldConfig(
        normalData.requireActive().root,
        normalPlay = true,
        botProfile = profile,
        bindAddress = bindAddress,
        nearbyInteractTriggerGuardMs = nearbyInteractTriggerGuardMs,
        settingsSnapshot = settingsSnapshot,
    )

    private fun worldConfig(
        data: File,
        normalPlay: Boolean,
        botProfile: BotProfile? = null,
        bindAddress: String,
        nearbyInteractTriggerGuardMs: Int,
        settingsSnapshot: Settings.Snapshot?,
    ): File {
        val bind = ServerRuntimeContract.resolveBindAddress(bindAddress)
        // One blocking settings read at world start feeds both the world
        // log level and (through llmConfigOverrides) the appended playerbot
        // LLM block - every world.conf toggle therefore applies on the next
        // realm start, never mid-session. The caller's snapshot wins when
        // provided: WorldRuntimeService resolves the stage ONCE per start
        // and feeds both this conf write and the stage overlay — two
        // independent loads could straddle a settings write and boot a
        // world whose conf names a stage the DB/ledger never reached.
        val snapshot = settingsSnapshot ?: Settings(appContext).blockingSnapshot()
        purgeStaleBotConfSecrets()
        val botConfig = botProfile?.let {
            secureWrite(File(run, "aiplayerbot-${it.id}.conf"),
                it.playerbotConfig() + (llmConfigOverrides(it, snapshot) ?: ""))
        } ?: secureWrite(File(run, "aiplayerbot-disabled.conf"), aiplayerbotDisabledConfText())
        val databases = serverDatabases()
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
                // Design-timeframe §4.2: the persisted stage resolved to the
                // "<stage-or-default>" conf value (never empty).
                timeframeStage = TimeframeStages.confValue(snapshot.timeframeStage),
            ),
        )
    }

    /**
     * The stale-secret purge (the desktop twin's F3 fix): a world start
     * stages exactly ONE bot conf (the active profile's — or the disabled
     * block), so a rotated or cleared external LLM key would otherwise
     * survive indefinitely in every OTHER preset's staged conf. Before the
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

    /**
     * Playerbot LLM overrides, resolved at world start from the settings
     * snapshot read by [worldConfig] (one blocking read inside the
     * transition gate; toggles therefore apply on the next realm start).
     * All decisions live in the pure [llmOverrides] companion function (see
     * its contract there). The selected profile's per-preset speech
     * overrides (Bots → AI tab) ride the same appended block; sentinels
     * follow the model/global values.
     */
    private fun llmConfigOverrides(profile: BotProfile, snapshot: Settings.Snapshot): String? {
        val selected = LlmModelRegistry.byId(snapshot.llmModelId)
        val model = LlmModelCoordinator.modelPathFor(appContext, snapshot.llmModelId)
        // Stage the lore card index only when an LLM block can be
        // emitted, and degrade to no-cards on any staging failure - a
        // lore asset problem must never fail a world start for a feature
        // the user never enabled
        val lore = if (snapshot.llmEnabled || (BuildConfig.DEBUG && model.isFile))
            runCatching { stageLoreCards().absolutePath }.getOrNull()
        else
            null
        // Stage the EMPTY default-prompts file under the same gate (the
        // debug lane carries the line too, so the gate matches the lore
        // index, not the pack). The native default for
        // AiPlayerbot.LLMDefaultPromptsFile is the bare relative name
        // llm_character_card, resolved by the loader against CWD - never
        // the run dir - so a missing file fails open with a "not found or
        // unreadable" startup line. An EMPTY file loads zero prompts
        // cleanly: identical fail-open behavior minus the error line and
        // with zero DB writes. A staging failure also fails open (null
        // simply omits the conf line); a staging problem must never fail
        // a world start for a feature the user never configured.
        val defaultPrompts = if (snapshot.llmEnabled || (BuildConfig.DEBUG && model.isFile))
            runCatching { stageDefaultPromptsFile().absolutePath }.getOrNull()
        else
            null
        // Stage the Mozilla CA bundle under the same gate. The native
        // HTTPS client verifies external-endpoint certificates against it
        // (LLMTLSCaFile absolute path; empty falls back to the Android
        // system store, which often has nothing usable for user CAs). A
        // staging failure fails open to the fallback - verification stays
        // on, the store just changes.
        val tlsCa = if (snapshot.llmEnabled || (BuildConfig.DEBUG && model.isFile))
            runCatching { stageTlsCaBundle().absolutePath }.getOrNull()
        else
            null
        // The power file is staged once at world start whenever the LLM
        // subsystem can run, carrying the CURRENT ambience toggle in its
        // enabled flag plus the low-battery courtesy dim - the native
        // scheduler re-reads it every tick, but the toggle itself applies
        // at realm start like every llm* setting. A staging failure fails
        // CLOSED: no power file path in the conf means chatter stays off
        // (the silence doctrine).
        val chatterPower = if (snapshot.llmEnabled)
            runCatching {
                // The selected profile's per-preset rung cap rides the
                // staged power file (-1 follows the computed ambience state)
                ChatterPowerMonitor.refreshOnce(
                    appContext,
                    enabled = snapshot.llmAmbience,
                    rungCap = profile.llmSpeech.chatterRung,
                ).absolutePath
            }.getOrNull()
        else
            null
        // The prompt pack stages the RESOLVED player pack (the
        // edited JSON from Settings, fail-open to default on corrupt edits).
        // The file carries the enabled flags + bodies the Advanced prompt
        // manager edits. Staging failure fails OPEN to trained-default,
        // never closed: the renderer treats a missing path as "pack off".
        val promptPack = if (snapshot.llmEnabled)
            runCatching {
                stagePromptPack(
                    LlmPromptPack.resolve(snapshot.llmPromptPackJson).serialize(),
                ).absolutePath
            }.getOrNull()
        else
            null
        // the composer rides the external endpoint fields the user filled
        // in (validated by the same normalizers; null on any invalid value)
        val composerEndpoint =
            if (snapshot.llmEnabled)
                LlmRuntimePolicy.normalizeExternalEndpoint(snapshot.llmExternalUrl)
            else
                null
        return llmOverrides(
            uiEnabled = snapshot.llmEnabled,
            modelPresent = model.isFile,
            modelAbsolutePath = model.absolutePath,
            profile = selected.profile,
            tier = selected.tierProfile,
            debugBuild = BuildConfig.DEBUG,
            externalMode = snapshot.llmExternalMode,
            externalEndpoint = LlmRuntimePolicy.normalizeExternalEndpoint(snapshot.llmExternalUrl),
            externalModel = LlmRuntimePolicy.normalizeExternalModel(snapshot.llmExternalModel),
            externalApiKey = LlmRuntimePolicy.normalizeExternalApiKey(snapshot.llmExternalApiKey),
            banterEnabled = snapshot.llmBanter,
            loreFile = lore,
            chatterPowerFile = chatterPower,
            composerEndpoint = composerEndpoint,
            composerModel = LlmRuntimePolicy.normalizeExternalModel(snapshot.llmExternalModel),
            composerApiKey = LlmRuntimePolicy.normalizeExternalApiKey(snapshot.llmExternalApiKey),
            maxNewTokensOverride = snapshot.llmMaxNewTokens,
            generationTimeoutOverride = snapshot.llmGenerationTimeout,
            speech = profile.llmSpeech,
            promptPackFile = promptPack,
            defaultPromptsFile = defaultPrompts,
            tlsCaFile = tlsCa,
            cloudLane = CloudLaneConf(cloudChatter = snapshot.llmExternalMode && snapshot.llmCloudChatter),
            logLines = snapshot.worldDebugLogs,
        )
    }

    /**
     * Stage the resolved prompt pack next to the conf atomically.
     * Re-staged when the BYTES change (a same-length body edit or a pure
     * reorder must not keep serving the previous pack). The payload is the
     * RESOLVED player pack (custom edits or default), so one file serves
     * both the default and the edited states.
     */
    private fun stagePromptPack(payloadText: String): File {
        val target = File(run, PROMPT_PACK_FILE_NAME)
        val payload = payloadText.toByteArray(Charsets.UTF_8)
        if (target.isFile && target.readBytes().contentEquals(payload))
            return target
        val temp = File(run, ".$PROMPT_PACK_FILE_NAME.${android.os.Process.myPid()}.tmp")
        java.io.FileOutputStream(temp).use { stream ->
            stream.write(payload); stream.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    /**
     * The lore card index ships as an app asset and is staged
     * next to the conf atomically (pid-temp + rename, the class's write
     * discipline). A staged copy whose size no longer matches the asset
     * (an app update shipping revised cards, or a partial write from an
     * older non-atomic path) is re-staged. Callers treat any failure as
     * "no cards" - the native retrieval loop fails closed to quiet and
     * the entity guard still works.
     */
    private fun stageLoreCards(): File {
        val target = File(run, LORE_CARDS_FILE_NAME)
        val assetPath = "lore/$LORE_CARDS_FILE_NAME"
        val expected = appContext.assets.open(assetPath).use { it.available() }.toLong()
        if (target.isFile && target.length() == expected)
            return target
        val temp = File(run, ".$LORE_CARDS_FILE_NAME.${android.os.Process.myPid()}.tmp")
        appContext.assets.open(assetPath).use { input ->
            FileOutputStream(temp).use { output -> input.copyTo(output) }
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    /**
     * Stage an EMPTY default-prompts file next to the conf atomically
     * (pid-temp + rename, the class's write discipline). An empty file is
     * the deliberate payload: the native loader loads zero prompts from it
     * cleanly - identical to the missing-file fail-open of the bare
     * relative default, minus the "not found or unreadable" startup line,
     * and with none of the per-line ERROR spam and junk DB writes a JSON
     * payload would produce. Already-staged empty copies are kept as-is;
     * only a missing or non-empty file is (re)staged.
     */
    private fun stageDefaultPromptsFile(): File {
        val target = File(run, DEFAULT_PROMPTS_FILE_NAME)
        if (target.isFile && target.length() == 0L)
            return target
        val temp = File(run, ".$DEFAULT_PROMPTS_FILE_NAME.${android.os.Process.myPid()}.tmp")
        FileOutputStream(temp).use { stream -> stream.fd.sync() }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    /**
     * The Mozilla CA bundle ships as an app asset and stages next to
     * the conf (size-verified, the stageLoreCards discipline) so the
     * native HTTPS client verifies external certificates by absolute
     * path - Android has no /etc/ssl/certs for native code; the system
     * hashed-dir store is only the fallback.
     */
    private fun stageTlsCaBundle(): File {
        val target = File(run, TLS_CA_BUNDLE_FILE_NAME)
        val expected = appContext.assets.open("llm/$TLS_CA_BUNDLE_FILE_NAME").use { it.available() }.toLong()
        if (target.isFile && target.length() == expected)
            return target
        val temp = File(run, ".$TLS_CA_BUNDLE_FILE_NAME.${android.os.Process.myPid()}.tmp")
        appContext.assets.open("llm/$TLS_CA_BUNDLE_FILE_NAME").use { input ->
            FileOutputStream(temp).use { output -> input.copyTo(output) }
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

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
     * hard-coded names. The server runtime runs the vanilla lane today,
     * so exactly one flavor may be qualified: zero or multiple qualified
     * flavors fail loud here, before the vanilla-lane guard below.
     */
    private fun serverDatabases(): ClientFlavor.DatabaseNames {
        val qualified = ClientFlavors.qualified()
        require(qualified.size == 1) {
            "expected exactly one qualified flavor, found ${qualified.size} — " +
                "the server runtime seeds the qualified lane's databases"
        }
        val flavor = qualified.single()
        require(flavor.flavorId == ClientFlavors.VANILLA.flavorId) {
            "server runtime resolves only the vanilla lane; pass its ClientFlavor explicitly"
        }
        return flavor.databases
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
         * G3-7: the pure conf-template bodies the instance writers feed, so
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
            // design-remote-accounts §4.3/§6.3 (the desktop ServerRuntimeFiles
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
            /** Design-timeframe §4.2: the RESOLVED stage id (never empty —
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
            PlayerLimit = 10
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

        /** The staged lore card index file (asset: lore/). */
        private const val LORE_CARDS_FILE_NAME = "lore_cards_v112.jsonl"

        /** The staged default prompt-pack file (run dir). */
        private const val PROMPT_PACK_FILE_NAME = "llm_prompt_pack.json"

        /** The staged EMPTY default-prompts file (run dir; native default name). */
        private const val DEFAULT_PROMPTS_FILE_NAME = "llm_character_card"

        /** The staged Mozilla CA bundle for external-endpoint TLS verification (asset: llm/). */
        private const val TLS_CA_BUNDLE_FILE_NAME = "cacert.pem"

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
         * The four-state playerbot LLM gate, pure in its inputs so the
         * verdicts are unit-testable. (0) External-endpoint mode (submenu on
         * + a normalized endpoint) emits the external conf block BEFORE any
         * local-model gate — an external service needs no staged GGUF, and an
         * unusable endpoint/key/model (any normalizer null) suppresses the
         * block entirely instead of pointing bots at a broken URL; it also
         * shadows the debug fallback, matching the supervisor which never
         * starts the :llm process in this mode. (1) The submenu runtime's
         * HTTP block when the user enabled it AND the model GGUF is staged —
         * the same gate the supervisor applies before starting the :llm
         * process, so the conf and the running server can never disagree; the
         * base profile conf keeps its LLMEnabled = 0 and the append
         * wins by Config.cpp's last-wins parse. (2) With the submenu off, the
         * debug-only in-process llama override (still
         * model-gated), so adb-driven native debugging keeps working
         * on debuggable builds. (3) Otherwise nothing — release builds
         * without the submenu opt-in emit nothing.
         */
        internal fun llmOverrides(
            uiEnabled: Boolean,
            modelPresent: Boolean,
            modelAbsolutePath: String,
            debugBuild: Boolean,
            externalMode: Boolean = false,
            externalEndpoint: String? = null,
            externalModel: String? = null,
            externalApiKey: String? = null,
            banterEnabled: Boolean = true,
            profile: LlmSamplingProfile = LlmModelRegistry.TUNED_E2B.profile,
            tier: LlmTierProfile = LlmModelRegistry.TUNED_E2B.tierProfile,
            loreFile: String? = null,
            chatterPowerFile: String? = null,
            composerEndpoint: String? = null,
            composerModel: String? = null,
            composerApiKey: String? = null,
            maxNewTokensOverride: Int = 0,
            generationTimeoutOverride: Int = 0,
            speech: BotLlmSpeech = BotLlmSpeech(),
            promptPackFile: String? = null,
            defaultPromptsFile: String? = null,
            tlsCaFile: String? = null,
            cloudLane: CloudLaneConf = CloudLaneConf(),
            logLines: Boolean = false,
        ): String? {
            if (uiEnabled && externalMode) {
                return LlmRuntimePolicy.confBlockExternal(
                    externalEndpoint, externalModel, externalApiKey, banterEnabled,
                    loreFile = loreFile,
                    chatterPowerFile = chatterPowerFile,
                    composerEndpoint = composerEndpoint,
                    composerModel = composerModel,
                    composerApiKey = composerApiKey,
                    maxNewTokensOverride = maxNewTokensOverride,
                    generationTimeoutOverride = generationTimeoutOverride,
                    speech = speech,
                    promptPackFile = promptPackFile,
                    defaultPromptsFile = defaultPromptsFile,
                    tlsCaFile = tlsCaFile,
                    cloudLane = cloudLane,
                    logLines = logLines,
                )
            }
            if (uiEnabled && modelPresent) {
                return LlmRuntimePolicy.confBlock(
                    true, banterEnabled = banterEnabled, profile = profile, tier = tier,
                    loreFile = loreFile,
                    chatterPowerFile = chatterPowerFile,
                    composerEndpoint = composerEndpoint,
                    composerModel = composerModel,
                    composerApiKey = composerApiKey,
                    maxNewTokensOverride = maxNewTokensOverride,
                    generationTimeoutOverride = generationTimeoutOverride,
                    speech = speech,
                    promptPackFile = promptPackFile,
                    defaultPromptsFile = defaultPromptsFile,
                    tlsCaFile = tlsCaFile,
                )
            }
            if (!debugBuild || !modelPresent) return null
            // the lore line rides the debug block too: the retrieval
            // loop is backend-independent (the bridge loads it for both
            // the HTTP and in-process paths)
            val debugLore = if (!loreFile.isNullOrBlank())
                "\n            AiPlayerbot.LLMLoreFile = \"$loreFile\"" else ""
            // the empty default-prompts file rides the debug block for
            // the same reason - the native loader opens it on every LLM
            // path, not only the HTTP ones
            val debugDefaultPrompts = if (!defaultPromptsFile.isNullOrBlank())
                "\n            AiPlayerbot.LLMDefaultPromptsFile = \"$defaultPromptsFile\"" else ""
            // the staged CA bundle rides the debug block too - the TLS
            // client is shared by the embedded-server HTTP path
            val debugTlsCa = if (!tlsCaFile.isNullOrBlank())
                "\n            AiPlayerbot.LLMTLSCaFile = \"$tlsCaFile\"" else ""
            // The banter toggle must gate the in-process debug
            // path too - the native default (1) otherwise runs the authored
            // initiative layer regardless of the toggle
            val debugBanter =
                "\n            AiPlayerbot.LLMBanterEnabled = ${if (banterEnabled) 1 else 0}"
            return """
                AiPlayerbot.LLMEnabled = 2
                AiPlayerbot.LLMBackend = 1
                AiPlayerbot.LLMModelPath = "$modelAbsolutePath"
                AiPlayerbot.LLMThreads = 3
                AiPlayerbot.LLMCpuFirstCore = 3
                AiPlayerbot.LLMCtxSize = 4096
                AiPlayerbot.LLMSlots = 4$debugBanter$debugLore$debugDefaultPrompts$debugTlsCa
            """.trimIndent() + "\n"
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
