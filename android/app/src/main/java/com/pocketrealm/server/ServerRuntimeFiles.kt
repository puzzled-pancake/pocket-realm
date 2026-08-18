package com.pocketrealm.server

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.pocketrealm.bots.BotProfile
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
        val endpoint = RealmEndpoint.parseStored(bindAddress)
        val secret = coreSecret()
        return secureWrite(File(run, "realmd.conf"), """
            LoginDatabaseInfo = ".;${roots.databaseRun.resolve("mariadb.sock").absolutePath};pocket_core;$secret;classicrealmd"
            RealmServerPort = ${ServerRuntimeContract.REALM_PORT}
            BindIP = "${endpoint.address}"
            RealmsStateUpdateDelay = 20
            ListenerThreads = 1
            LogLevel = 1
            LogFile = "${logs.resolve("realmd.log").absolutePath}"
            LogFileLevel = 1
        """.trimIndent() + "\n")
    }

    fun worldConfig(
        bindAddress: String = RealmEndpoint.LOOPBACK_ADDRESS,
        nearbyInteractTriggerGuardMs: Int = NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
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
        )
    }

    /** Production entry point; refuses normal play unless every import artifact verifies. */
    fun worldConfigNormal(
        bindAddress: String = RealmEndpoint.LOOPBACK_ADDRESS,
        nearbyInteractTriggerGuardMs: Int = NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
    ): File = worldConfig(
        normalData.requireActive().root,
        normalPlay = true,
        bindAddress = bindAddress,
        nearbyInteractTriggerGuardMs = nearbyInteractTriggerGuardMs,
    )

    /** Measured bot-profile entry point. Auction-house automation remains disabled. */
    fun worldConfigBot(
        profile: BotProfile,
        bindAddress: String = RealmEndpoint.LOOPBACK_ADDRESS,
        nearbyInteractTriggerGuardMs: Int = NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
    ): File = worldConfig(
        normalData.requireActive().root,
        normalPlay = true,
        botProfile = profile,
        bindAddress = bindAddress,
        nearbyInteractTriggerGuardMs = nearbyInteractTriggerGuardMs,
    )

    private fun worldConfig(
        data: File,
        normalPlay: Boolean,
        botProfile: BotProfile? = null,
        bindAddress: String,
        nearbyInteractTriggerGuardMs: Int,
    ): File {
        val endpoint = RealmEndpoint.parseStored(bindAddress)
        val secret = coreSecret()
        val socket = roots.databaseRun.resolve("mariadb.sock").absolutePath
        fun db(name: String) = ".;$socket;pocket_core;$secret;$name"
        val botConfig = botProfile?.let {
            secureWrite(File(run, "aiplayerbot-${it.id}.conf"), it.playerbotConfig())
        } ?: secureWrite(File(run, "aiplayerbot-disabled.conf"), """
            AiPlayerbot.Enabled = 0
            AiPlayerbot.RandomBotAutologin = 0
            AiPlayerbot.RandomBotLoginAtStartup = 0
            AiPlayerbot.RandomBotAutoCreate = 0
            AiPlayerbot.CommandServerPort = 0
            AiPlayerbot.LLMEnabled = 0
            AiPlayerbot.ShowProgressBars = 0
        """.trimIndent() + "\n")
        return secureWrite(File(run, "mangosd.conf"), """
            DataDir = "${data.absolutePath}"
            LoginDatabaseInfo = "${db("classicrealmd")}"
            WorldDatabaseInfo = "${db("classicmangos")}"
            CharacterDatabaseInfo = "${db("classiccharacters")}"
            LogsDatabaseInfo = "${db("classiclogs")}"
            RealmID = 1
            WorldServerPort = ${ServerRuntimeContract.WORLD_PORT}
            BindIP = "${endpoint.address}"
            Network.Threads = 1
            Console.Enable = 0
            Ra.Enable = 0
            SOAP.Enabled = 0
            vmap.enableLOS = ${if (normalPlay) 1 else 0}
            vmap.enableHeight = ${if (normalPlay) 1 else 0}
            vmap.enableIndoorCheck = ${if (normalPlay) 1 else 0}
            mmap.enabled = ${if (normalPlay) 1 else 0}
            LogLevel = 1
            LogFile = "${logs.resolve("world.log").absolutePath}"
            LogFileLevel = 3
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
            PocketRealm.BotTarget = ${botProfile?.initialTarget ?: 0}
        """.trimIndent() + "\n")
    }

    fun writeLifecycle(component: String, clean: Boolean, operation: String, detail: String = "") {
        require(component == "realm" || component == "world") { "unknown server component" }
        val value = JSONObject().put("schema", 1).put("component", component)
            .put("clean", clean).put("operation", operation.take(64))
            .put("detail", detail.take(256)).put("at", System.currentTimeMillis()).toString()
        secureWrite(File(lifecycle, "$component.json"), value)
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
