package com.pocketrealm.server

import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.desktop.DesktopSettingsStore
import com.pocketrealm.desktop.DesktopStorageRoots
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
 *  - databaseInfo is SQLite-ONLY (the Windows v1 provider): the
 *    DatabaseInfo string is the datadir file path, and no MariaDB branch
 *    or active-provider marker exists here;
 *  - the world always starts from the NORMAL prepared-data lane
 *    (PreparedDataStore, shared) — mmaps are mandatory on the desktop,
 *    so the o09 baseline shortcut lane does not exist;
 *  - the playerbot LLM conf append arrives with the Phase-5
 *    LlmRuntimePolicy twin; until then world starts with the bots-
 *    disabled block, which is exactly what the shared conf pins;
 *  - secureWrite has no chmod leg (POSIX-only); the run dir lives under
 *    %LOCALAPPDATA%, private to the user by NTFS profile ACLs.
 */
internal class ServerRuntimeFiles(private val roots: DesktopStorageRoots) {
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

    fun realmdConfig(bindAddress: String = RealmEndpoint.LOOPBACK_ADDRESS): File {
        val endpoint = RealmEndpoint.parseStored(bindAddress)
        return secureWrite(File(run, "realmd.conf"), """
            LoginDatabaseInfo = "${databaseInfo("classicrealmd")}"
            RealmServerPort = ${ServerRuntimeContract.REALM_PORT}
            BindIP = "${endpoint.address}"
            RealmsStateUpdateDelay = 20
            ListenerThreads = 1
            LogLevel = 1
            LogFile = "${logs.resolve("realmd.log").absolutePath}"
            LogFileLevel = 1
        """.trimIndent() + "\n")
    }

    /** Production entry point; refuses world start unless every import
     * artifact verifies (Phase 4 prepares it; until then this fails
     * honestly with the prepared-data copy). */
    fun worldConfig(
        bindAddress: String = RealmEndpoint.LOOPBACK_ADDRESS,
        nearbyInteractTriggerGuardMs: Int = NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
    ): File {
        val data = normalData.requireActive().root
        val snapshot = settings.load()
        val botConfig = secureWrite(File(run, "aiplayerbot-disabled.conf"), """
            AiPlayerbot.Enabled = 0
            AiPlayerbot.RandomBotAutologin = 0
            AiPlayerbot.RandomBotLoginAtStartup = 0
            AiPlayerbot.RandomBotAutoCreate = 0
            AiPlayerbot.CommandServerPort = 0
            AiPlayerbot.LLMEnabled = 0
            AiPlayerbot.ShowProgressBars = 0
        """.trimIndent() + "\n")
        val endpoint = RealmEndpoint.parseStored(bindAddress)
        return secureWrite(File(run, "mangosd.conf"), """
            DataDir = "${data.absolutePath}"
            LoginDatabaseInfo = "${databaseInfo("classicrealmd")}"
            WorldDatabaseInfo = "${databaseInfo("classicmangos")}"
            CharacterDatabaseInfo = "${databaseInfo("classiccharacters")}"
            LogsDatabaseInfo = "${databaseInfo("classiclogs")}"
            RealmID = 1
            WorldServerPort = ${ServerRuntimeContract.WORLD_PORT}
            BindIP = "${endpoint.address}"
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
            PocketRealm.NearbyInteractCooldownMs = ${NearbyInteractPolicy.normalizeTriggerGuardMs(nearbyInteractTriggerGuardMs)}
            PocketRealm.PlayerbotConfig = "${botConfig.absolutePath}"
            PocketRealm.BotTarget = 0
        """.trimIndent() + "\n")
    }

    fun writeLifecycle(component: String, clean: Boolean, operation: String, detail: String = "") {
        require(component == "realm" || component == "world") { "unknown server component" }
        val value = JSONObject().put("schema", 1).put("component", component)
            .put("clean", clean).put("operation", operation.take(OPERATION_MAX_CHARS))
            .put("detail", detail.take(DETAIL_MAX_CHARS)).put("at", System.currentTimeMillis()).toString()
        secureWrite(File(lifecycle, "$component.json"), value)
    }

    /** The SQLite provider's runtimes open the datadir files IN-PROCESS:
     * the DatabaseInfo string is the file path the DO_SQLITE backend
     * feeds to sqlite3_open. SQLite is the only provider in the Windows
     * v1 lane — no MariaDB socket form, no provider marker. */
    private fun databaseInfo(name: String): String =
        DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, name).absolutePath

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

    /** Keep one oversized previous-session diagnostic when the native writer is stopped. */
    private fun rotateRestartLog(file: File, maximumBytes: Long = MAX_NORMAL_LOG_BYTES) {
        if (!file.isFile || file.length() <= maximumBytes) return
        val previous = File(file.parentFile, "${file.name}.1")
        if (previous.exists()) check(previous.delete()) { "cannot retire ${previous.name}" }
        check(file.renameTo(previous)) { "cannot rotate ${file.name}" }
    }

    companion object {
        private const val MAX_NORMAL_LOG_BYTES = 4L * 1024L * 1024L
        private const val MAX_ERROR_LOG_BYTES = 8L * 1024L * 1024L
        private const val OPERATION_MAX_CHARS = 64
        private const val DETAIL_MAX_CHARS = 256

        /** B2: errors-only world log level, matching realmd's LogFileLevel = 1. */
        internal const val DEFAULT_WORLD_LOG_FILE_LEVEL = 1

        /** B2: the verbose world log level staged by the World debug logs toggle. */
        internal const val DEBUG_WORLD_LOG_FILE_LEVEL = 3

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
