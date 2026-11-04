package com.pocketrealm.server

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ActiveFlavorState
import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.TimeframeStages
import com.pocketrealm.database.DatabaseDurableState
import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.database.DesktopMariaDbEngine
import com.pocketrealm.database.DesktopMariaDbProvider
import com.pocketrealm.desktop.DesktopSettingsStore
import com.pocketrealm.desktop.DesktopStorageRoots
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.RealmEndpoint
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/**
 * Desktop twin of the Android ServerRuntimeFiles: the conf-generation
 * contract with the realm DLLs. Same run/logs/lifecycle layout under the
 * desktop storage roots, same conf text, same write discipline (pid-temp
 * + fsync + atomic rename, log rotation only between native runtimes).
 *
 * Lane differences, all deliberate:
 * - databaseInfo follows the SHARED active-provider contract (the DESK-5
 *    engine lane): the SQLite materialization keeps the file-path form,
 *    and a durable active-provider marker naming MARIADB switches the conf
 *    to the MariaDB socket form — the Android twin's exact
 *    `.;<socket>;pocket_core;<secret>;<database>` shape. There is no
 *    silent fallback: a MARIADB marker with the provider distribution
 *    unstaged FAILS the conf write (a desktop install has no APK-rollback
 *    window, and pointing the servers at the empty SQLite datadir would
 *    fork user state);
 *  - the world always starts from the NORMAL prepared-data lane
 *    (PreparedDataStore, shared) — mmaps are mandatory on the desktop,
 *    so the o09 baseline shortcut lane does not exist;
 *  - world starts with the bots-disabled block when no bot profile is
 *    selected, which is exactly what the shared conf pins;
 *  - secureWrite has no chmod leg (POSIX-only); the run dir lives under
 *    %LOCALAPPDATA%, private to the user by NTFS profile ACLs.
 */
@Suppress("TooManyFunctions") // the conf-generation contract with the realm DLLs (the Android twin's verb count)
internal class ServerRuntimeFiles(
    private val roots: DesktopStorageRoots,
    /** the staged-provider presence seam (tests inject; production
     * resolves the real staging lazily). Null = resolve for real. */
    private val providerStagedOverride: Boolean? = null,
) {
    private val root = File(roots.runtime, "server").apply { mkdirs() }
    private val run = File(root, "run").apply { mkdirs() }
    private val logs = File(root, "logs").apply { mkdirs() }
    private val lifecycle = File(root, "lifecycle").apply { mkdirs() }
    private val normalData = PreparedDataStore(File(roots.content, "o11-server"))

    /** The world runtime pins the active generation for its whole
     * lifetime (the Android twin's lease contract): while held, a
     * concurrent publication cannot swap the data under a running
     * world. Acquire in startWorld, close on stop. */
    fun acquireNormalDataLease(): PreparedDataStore.GenerationLease = normalData.acquireRuntimeLease()
    private val settings = DesktopSettingsStore(roots.settingsFile)

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

    /**
     * Design-tunnel: the bind value for both confs. The typed path
     * parses through the strict [RealmEndpoint] grammar (loopback or
     * canonical private/link-local IPv4); the ONE extra literal accepted is
     * [RealmEndpoint.WILDCARD_BIND] — "0.0.0.0", the raw conf value tunnel
     * hosting binds so the advertised tunnel address is actually reachable
     * (design-tunnel keeps it out of the typed path deliberately).
     * Digits and dots only, interpolated into `BindIP = "..."`; anything
     * else is refused before any conf exists.
     */
    private fun resolveBindAddress(bindAddress: String): String =
        if (bindAddress == RealmEndpoint.WILDCARD_BIND) {
            RealmEndpoint.WILDCARD_BIND
        } else {
            RealmEndpoint.parseStored(bindAddress).address
        }

    /**
     * The realmd conf. D11 (design-lan): the brute-force lockout keys
     * are emitted WHEN AND ONLY WHEN the bind is not loopback. The engine
     * default is lockout-off (`WrongPass.MaxCount` default 0,
     * native/cmangos/src/realmd/AuthSocket.cpp:664-665), so the un-exposed
     * LOCAL lane keeps the reviewed baseline; every non-loopback bind (LAN
     * today, tunnel/remote lanes later) gets IP autoban lockout because a
     * ban row matches ANY source address — including loopback
     * (AuthSocket.cpp:416-423) — and an unconditional emission would let
     * ten mistyped LOCAL passwords lock the host's own client out of its
     * own realm for [WRONG_PASS_BAN_TIME_SECONDS] with no in-app
     * explanation. BanType = 0 autobans the SOURCE IP into ip_banned
     * (AuthSocket.cpp:691-696): on a LAN each guest device has its own IP,
     * so the lockout hits only the offender. The counter self-heals on a
     * successful logon (failed_logins = 0, AuthSocket.cpp:1161), and the
     * conf is regenerated at every start, so the keys take effect on the
     * next hosting start with no migration.
     */
    fun realmdConfig(
        bindAddress: String = RealmEndpoint.LOOPBACK_ADDRESS,
        /** The START's threaded snapshot — the same
         * one startWorld consumes — so the realm and world legs resolve ONE
         * lane even when a persisted write lands between the two component
         * starts (the up-to-25s realm READY window). Null keeps the file's
         * own load (older callers, tests). */
        settingsSnapshot: Settings.Snapshot? = null,
    ): File {
        val bind = resolveBindAddress(bindAddress)
        val databases = serverDatabases(worldLaneFlavor(SERVER_RUNTIME_LANE, settingsSnapshot))
        val wrongPassLockout = if (bind == RealmEndpoint.LOOPBACK_ADDRESS) {
            ""
        } else {
            """
                WrongPass.MaxCount = $WRONG_PASS_MAX_COUNT
                WrongPass.BanTime = $WRONG_PASS_BAN_TIME_SECONDS
                WrongPass.BanType = $WRONG_PASS_BAN_TYPE_IP_AUTOBAN
            """.trimIndent() + "\n"
        }
        return secureWrite(File(run, "realmd.conf"), """
            LoginDatabaseInfo = "${databaseInfo(databases.realmd)}"
            RealmServerPort = ${ServerRuntimeContract.REALM_PORT}
            BindIP = "$bind"
            RealmsStateUpdateDelay = 20
            ListenerThreads = 1
            LogLevel = 1
            LogFile = "${logs.resolve("realmd.log").absolutePath}"
            LogFileLevel = 1
        """.trimIndent() + "\n" + wrongPassLockout)
    }


    /**
     * the START-path lane resolver —
     * the PERSISTED Home selection (or the threaded settings snapshot
     * when the start leg carries one), never the :ui-local in-memory
     * machine a mid-start switch would flip. [ActiveFlavorState]
     * semantics: a sole qualified flavor degrades to it, the persisted
     * pick is honored, an ambiguous post-flip state refuses loud —
     * the Android ServerRuntimeFiles.worldLaneFlavor twin, which
     * the desktop boot path had never received. The backend pins this
     * once at world start ([bootLaneFlavor]) and the whole start —
     * prepared-data generation, conf database names, projection —
     * binds to that lane.
     */
    fun worldLaneFlavor(
        lane: String,
        settingsSnapshot: Settings.Snapshot? = null,
    ): ClientFlavor {
        val persisted = settingsSnapshot?.selectedFlavorOrNull()
            ?: settings.load().selectedFlavorOrNull()
        return ActiveFlavorState(persisted).resolve(ActiveFlavor.qualifiedFlavors, lane)
    }

    /** The start seam's boot-lane pin (the server-runtime lane over the
     * persisted selection; see [worldLaneFlavor]). */
    fun bootLaneFlavor(settingsSnapshot: Settings.Snapshot? = null): ClientFlavor =
        worldLaneFlavor(SERVER_RUNTIME_LANE, settingsSnapshot)

    /** Production entry point; refuses world start unless every import
     * artifact verifies (win_prepare_data.py prepares it; until then this
     * fails honestly with the prepared-data copy). [botProfile] null keeps the
     * bots-disabled block (the reviewed default); a profile stages
     * aiplayerbot-<id>.conf with the profile's population conf — the
     * Android twin's worldConfigBot contract. [nearbyUseEnabled] is the DESK-58
     * knob verdict the backend computes at world start: the AUTHORIZED-
     * layout EFFECTIVE resolution of the requested tweaks over the user's
     * actual client executable ([com.pocketrealm.client.nearbyUseEnabledForExecutable])
     * — requested consent alone does not turn the knob on, because a
     * non-vanilla-build layout launches unpatched. Default false = the
     * fail-closed conf. */
    @Suppress("LongMethod", "MagicNumber") // the world.conf body is one reviewed literal (golden-pinned by test)
    fun worldConfig(
        bindAddress: String = RealmEndpoint.LOOPBACK_ADDRESS,
        nearbyInteractTriggerGuardMs: Int = NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
        botProfile: com.pocketrealm.bots.BotProfile? = null,
        nearbyUseEnabled: Boolean = false,
        /** The caller's already-loaded settings snapshot. startWorld resolves
         * the timeframe stage ONCE and feeds both the conf's
         * PocketRealm.TimeframeStage and the overlay's desiredStage — two
         * independent loads could straddle a settings write and boot a world
         * whose conf names a stage the DB/ledger were not brought to (the
         * one-stage invariant the overlay leg documents). Null keeps the
         * file's own load (older callers, tests). */
        settingsSnapshot: Settings.Snapshot? = null,
    ): File {
        // the whole start resolves ONE lane — the persisted
        // selection (or the threaded snapshot) read ONCE here — so a
        // mid-start switch of the :ui-local machine can never tear the
        // prepared-data generation vs the conf's database names vs the
        // projection across different lanes in one boot.
        val lane = worldLaneFlavor(SERVER_RUNTIME_LANE, settingsSnapshot)
        val data = normalData.requireActive(lane).root
        val snapshot = settingsSnapshot ?: settings.load()
        purgeStaleBotConfSecrets()
        val botConfig = if (botProfile != null) {
            // the conf VALUES ride the same pinned boot lane as
            // the staging gate — never the :ui holder.
            val botConfText = botProfile.playerbotConfig(lane)
            // W3.4 fail-loud conf staging: an unknown key for the flavor's
            // bots build aborts the start instead of being silently dropped
            // by the core's config parser; excluded families (VoiceChat)
            // are refused with their own naming.
            BotConfStaging.requireStagingSafe(lane.flavorId, botProfile.id, botConfText)
            secureWrite(
                File(run, "aiplayerbot-${botProfile.id}.conf"),
                botConfText,
            )
        } else {
            val disabledConfText = """
            AiPlayerbot.Enabled = 0
            AiPlayerbot.RandomBotAutologin = 0
            AiPlayerbot.RandomBotLoginAtStartup = 0
            AiPlayerbot.RandomBotAutoCreate = 0
            AiPlayerbot.CommandServerPort = 0
            AiPlayerbot.LLMEnabled = 0
            AiPlayerbot.ShowProgressBars = 0
        """.trimIndent() + "\n"
            // the disabled staging leg runs
            // the SAME fail-loud gate as the profile leg — the desktop twin
            // of the android fix; a drifted disabled key
            // fails the start instead of silently dropping (the
            // bots-off enforcement is exactly the key class the core
            // silently drops).
            BotConfStaging.requireStagingSafe(lane.flavorId, "disabled", disabledConfText)
            secureWrite(File(run, "aiplayerbot-disabled.conf"), disabledConfText)
        }
        val bind = resolveBindAddress(bindAddress)
        // the boot lane's database names, never the live machine.
        val databases = serverDatabases(lane)
        return secureWrite(File(run, "mangosd.conf"), """
            DataDir = "${data.absolutePath}"
            LoginDatabaseInfo = "${databaseInfo(databases.realmd)}"
            WorldDatabaseInfo = "${databaseInfo(databases.world)}"
            CharacterDatabaseInfo = "${databaseInfo(databases.characters)}"
            LogsDatabaseInfo = "${databaseInfo(databases.logs)}"
            RealmID = 1
            WorldServerPort = ${ServerRuntimeContract.WORLD_PORT}
            BindIP = "$bind"
            Network.Threads = 1
            Console.Enable = 0
            Ra.Enable = 0
            SOAP.Enabled = 0
            vmap.enableLOS = 1
            vmap.enableHeight = 1
            vmap.enableIndoorCheck = 1
            mmap.enabled = 1
            LogLevel = 1
            LogFile = "${logs.resolve("world.log").absolutePath}"
            LogFileLevel = ${worldLogFileLevel(snapshot.worldDebugLogs)}
            DBErrorLogFile = "${logs.resolve("database-errors.log").absolutePath}"
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
            PocketRealm.NearbyInteract = ${if (nearbyUseEnabled) 1 else 0}
            PocketRealm.NearbyInteractCooldownMs = ${NearbyInteractPolicy.normalizeTriggerGuardMs(nearbyInteractTriggerGuardMs)}
            PocketRealm.PlayerbotConfig = "${botConfig.absolutePath}"
            PocketRealm.BotTarget = ${botProfile?.initialTarget ?: 0}
            # Design-timeframe §4.2: the chosen content-patch stage of the
            # qualified flavor ("<stage-or-default>", never empty). The game
            # client stays at its fixed version; this gates world content
            # only. Applied at world start — never mid-session.
            # R1-r3-1 (round 3): the stage resolves through the PINNED
            # start lane (confValueFor — the android D47-10/D62 twin), so
            # conf, data, and overlay can never name different flavors at
            # one start. The bare confValue( delegates to the LIVE holder
            # machine and would tear the stage leg off the boot lane.
            # W4.1: the whole block rides '#' — the native Config parser
            # accepts ONLY '#'/']'-leads (Config.cpp Reload: a keyless
            # line is a hard config REJECTION, and '//' is not a comment),
            # so the former '//' note lines failed every world boot with
            # POCKET_SERVER_CONFIG.
            PocketRealm.TimeframeStage = "${TimeframeStages.confValueFor(snapshot.timeframeStage, lane.flavorId)}"
        """.trimIndent() + "\n")
    }

    /**
     * The stale-secret purge: a world start stages exactly ONE bot conf
     * (the active profile's — or the disabled block), so a rotated or
     * key staged by an older build (the retired bot-speech endpoint) would otherwise survive indefinitely in
     * every OTHER preset's staged conf. Before the active conf is
     * rewritten, delete every staged aiplayerbot-*.conf that carries a
     * key line — an inactive conf is never referenced by the running
     * world (mangosd.conf names exactly one) and is re-staged from its
     * profile whenever that profile becomes active again, so nothing is
     * lost but the secret. Keyless confs (the disabled block, a
     * fail-closed profile conf) keep their bytes. Fail-open per file
     * (the staging posture): a locked file must never refuse a world
     * start.
     */
    private fun purgeStaleBotConfSecrets() {
        run.listFiles()?.forEach { staged ->
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

    fun writeLifecycle(component: String, clean: Boolean, operation: String, detail: String = "") {
        require(component == "realm" || component == "world") { "unknown server component" }
        val value = JSONObject().put("schema", 1).put("component", component)
            .put("clean", clean).put("operation", operation.take(OPERATION_MAX_CHARS))
            .put("detail", detail.take(DETAIL_MAX_CHARS)).put("at", System.currentTimeMillis()).toString()
        secureWrite(File(lifecycle, "$component.json"), value)
    }

    /**
     * the CLIENT component's lifecycle record. WoW.exe is a
     * real OS process (not an in-process native), so its exit code is the
     * crash-triage evidence: the backend journals every client death it
     * did not cause here — clean=false for a non-zero (abnormal) exit —
     * mirroring the realm/world records' write discipline. Newest death
     * overwrites the single record.
     */
    fun writeClientExit(pid: Int, exitCode: Int, clean: Boolean, detail: String) {
        val value = JSONObject().put("schema", 1).put("component", "client")
            .put("pid", pid).put("exitCode", exitCode).put("clean", clean)
            .put("operation", "exit")
            .put("detail", detail.take(DETAIL_MAX_CHARS))
            .put("at", System.currentTimeMillis())
            .toString()
        secureWrite(File(lifecycle, "client.json"), value)
    }

    /** The last client exit record, or null when none was written (or the
     * file is unreadable — evidence must never throw into the UI path). */
    fun readClientExit(): JSONObject? {
        val file = File(lifecycle, "client.json")
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    /**
     * The DatabaseInfo connection string for one database, per the SHARED
     * active-provider contract (the Android twin's decision, driven from
     * durable state so the engine and this writer never disagree): the
     * SQLite materialization opens the datadir files IN-PROCESS (the string
     * is the file path the DO_SQLITE backend feeds to sqlite3_open); the
     * MariaDB engine lane keeps the socket form
     * `.;<socket>;pocket_core;<secret>;<database>`. The decision reads the
     * engine's durable active-provider marker; a MARIADB marker requires
     * the staged provider closure (fail-loud — see the class doc).
     */
    private fun databaseInfo(name: String): String {
        val markerMode = DatabaseDurableState.parseActiveProviderMarker(
            File(roots.database, DatabaseDurableState.ACTIVE_PROVIDER_MARKER_NAME)
                .takeIf(File::isFile)?.readText(),
        )?.mode
        if (markerMode == DatabaseDurableState.ProviderMode.MARIADB) {
            check(providerStaged()) {
                "DB-LINK: the active-provider marker names MARIADB but the pinned " +
                    "provider distribution is not staged; stage it (or clear the marker) " +
                    "before any server conf can be written"
            }
            val secret = coreSecret()
            return ".;${DesktopMariaDbProvider.PIPE_NAME};pocket_core;$secret;$name"
        }
        return DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, name).absolutePath
    }

    private fun providerStaged(): Boolean =
        providerStagedOverride ?: (DesktopMariaDbProvider().stagedRoot() != null)

    /** The MariaDB core credential from the engine's durable record (the
     * Android coreSecret twin; the conf must never carry a guessed secret). */
    private fun coreSecret(): String {
        val secretFile = File(roots.database, DesktopMariaDbEngine.SECRETS_FILE_NAME)
        check(secretFile.isFile) { "database must be initialized before server start" }
        val secret = JSONObject(secretFile.readText()).getString("core")
        check(secret.matches(Regex("[0-9a-f]{48}"))) { "database credential record is invalid" }
        return secret
    }

    private fun secureWrite(target: File, text: String): File {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${ProcessHandle.current().pid()}.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        // File.renameTo cannot replace an existing target on Windows, and
        // the delete-then-rename fallback leaves a crash window with no
        // conf at all. Files.move + REPLACE_EXISTING maps to MoveFileEx's
        // atomic replace; ATOMIC_MOVE first, plain replace as the fallback
        // for filesystems that reject the atomic flag.
        try {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            try {
                java.nio.file.Files.move(
                    temp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (failure: java.io.IOException) {
                throw IllegalStateException("cannot replace ${target.name}: ${failure.message}", failure)
            }
        } catch (failure: java.io.IOException) {
            throw IllegalStateException("cannot replace ${target.name}: ${failure.message}", failure)
        }
        return target
    }

    /**
     * Keep one oversized previous-session diagnostic when the native writer
     * is stopped. Never delete-then-rename: the live log first steps aside
     * under a unique temp name, and only then takes the archive slot (the
     * [secureWrite] atomic-replace contract). Every failure keeps the bytes —
     * the previous archive keeps its slot and the live log keeps (or
     * regains) its name — and the refusal carries the pinned WRONG_STATE
     * code: the old delete-then-rename destroyed a 77.8 MB world.log.1 the
     * moment its rename lost (reexec-1), and masked the one-lifetime
     * refusal as "cannot rotate world.log".
     */
    private fun rotateRestartLog(file: File, maximumBytes: Long = MAX_NORMAL_LOG_BYTES) {
        if (!file.isFile || file.length() <= maximumBytes) return
        val previous = File(file.parentFile, "${file.name}.1")
        // Step 1: the live log steps aside; nothing is ever deleted first.
        // A failure here leaves the live log exactly where it was.
        val holding = File(
            file.parentFile,
            ".${file.name}.${ProcessHandle.current().pid()}.${System.nanoTime()}.rotating.tmp",
        )
        if (!file.renameTo(holding)) {
            throw IllegalStateException(
                "WRONG_STATE: cannot rotate ${file.name} (the live log stays in place)",
            )
        }
        // Step 2: the temp takes the archive slot. A locked/occupied slot
        // (an antivirus scanner, an indexer) refuses the move with the
        // incumbent archive untouched and the live log restored — pinned by
        // DesktopServerRuntimeFilesTest's failed-rotation legs.
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
     * The [secureWrite] atomic-replace move as a probe: null = [temp] took
     * the [archive] slot; non-null = the failure (the slot untouched — a
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

    companion object {
        private const val MAX_NORMAL_LOG_BYTES = 4L * 1024L * 1024L
        private const val MAX_ERROR_LOG_BYTES = 8L * 1024L * 1024L
        private const val OPERATION_MAX_CHARS = 64
        private const val DETAIL_MAX_CHARS = 256

        /** the [ActiveFlavor] lane suffix naming the server
         * runtime's resolution gate (conf writes, the realmlist
         * projection, the SRP6 logon clients). */
        private const val SERVER_RUNTIME_LANE =
            "the server runtime resolves the active lane for its conf and logon clients"

        /**
         * The active client flavor the in-process server lane runs,
         * resolved through the shared [ActiveFlavor] machine — the
         * Android twin's identity gate lifted from the
         * single-qualified vanilla pin to multi-flavor resolution
         * (GAP-4, the MFL-owned S5 treatment's desktop code leg):
         * every desktop consumer of a per-flavor identity (conf
         * database names, the realmlist projection, the SRP6 logon
         * client's build and proof frame) resolves through here
         * instead of a hard-coded vanilla constant. The machine keeps
         * the gate's honesty — zero qualified flavors, and several
         * qualified flavors with no Home-switcher selection, still
         * fail loud here, before any conf or packet is written — while
         * a selection resolves that lane, so the S5 flip yields a
         * second live lane instead of a bricked one. A caller that
         * already holds the active flavor may pass it explicitly (the
         * Android PreparedDataStore.requireActive idiom).
         *
         * Companion-scope (the Android twin keeps this private on the
         * instance) so the desktop's cross-package consumers
         * [com.pocketrealm.desktop.DesktopRuntimeBackend] and
         * [com.pocketrealm.desktop.AuthGate] share this one gate
         * instead of re-implementing it.
         */
        internal fun serverFlavor(
            flavor: ClientFlavor = ActiveFlavor.resolve(SERVER_RUNTIME_LANE),
        ): ClientFlavor = flavor

        /**
         * The per-flavor database-name set the conf templates seed,
         * resolved from [ClientFlavor.databases] instead of hard-coded
         * names (the Android serverDatabases() twin). Tracks
         * [serverFlavor]'s active lane: at the S5 flip the conf's
         * LoginDatabaseInfo and the realmlist projection name the
         * selected lane's databases. The seed transcripts themselves
         * stay the vanilla lane until a second flavor re-seeds
         * (DESK-93: routing-now, names-change-later).
         */
        internal fun serverDatabases(
            flavor: ClientFlavor = serverFlavor(),
        ): ClientFlavor.DatabaseNames = flavor.databases


        /** B2: errors-only world log level, matching realmd's LogFileLevel = 1. */
        internal const val DEFAULT_WORLD_LOG_FILE_LEVEL = 1

        /** B2: the verbose world log level staged by the World debug logs toggle. */
        internal const val DEBUG_WORLD_LOG_FILE_LEVEL = 3

        /** realmd brute-force lockout, non-loopback
         * binds only. 10 attempts / 900 s ban / BanType 0 = IP autoban into
         * ip_banned (the engine default when the lockout is enabled). */
        internal const val WRONG_PASS_MAX_COUNT = 10
        internal const val WRONG_PASS_BAN_TIME_SECONDS = 900
        internal const val WRONG_PASS_BAN_TYPE_IP_AUTOBAN = 0

        /** B2: the world.conf LogFileLevel staged at world start (see the
         * Android twin for the level-selection history). */
        internal fun worldLogFileLevel(worldDebugLogs: Boolean): Int =
            if (worldDebugLogs) DEBUG_WORLD_LOG_FILE_LEVEL else DEFAULT_WORLD_LOG_FILE_LEVEL
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
