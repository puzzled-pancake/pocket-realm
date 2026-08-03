package com.pocketrealm.server

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.pocketrealm.storage.StorageRoots
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

    fun realmdConfig(): File {
        val secret = coreSecret()
        return secureWrite(File(run, "realmd.conf"), """
            LoginDatabaseInfo = ".;${roots.databaseRun.resolve("mariadb.sock").absolutePath};pocket_core;$secret;classicrealmd"
            RealmServerPort = ${ServerRuntimeContract.REALM_PORT}
            BindIP = "127.0.0.1"
            RealmsStateUpdateDelay = 20
            ListenerThreads = 1
            LogLevel = 3
            LogFile = "${logs.resolve("realmd.log").absolutePath}"
            LogFileLevel = 3
        """.trimIndent() + "\n")
    }

    fun worldConfig(): File {
        require(baselineData.isDirectory) { "verified O09 client-derived data generation is missing" }
        require(File(baselineData, "BUILD_PROVENANCE.json").isFile) { "O09 data provenance is missing" }
        return worldConfig(baselineData, normalPlay = false)
    }

    /** O12+ production entry point; refuses normal play unless every O11 artifact verifies. */
    fun worldConfigNormal(): File = worldConfig(normalData.requireActive().root, normalPlay = true)

    private fun worldConfig(data: File, normalPlay: Boolean): File {
        val secret = coreSecret()
        val socket = roots.databaseRun.resolve("mariadb.sock").absolutePath
        fun db(name: String) = ".;$socket;pocket_core;$secret;$name"
        return secureWrite(File(run, "mangosd.conf"), """
            DataDir = "${data.absolutePath}"
            LoginDatabaseInfo = "${db("classicrealmd")}"
            WorldDatabaseInfo = "${db("classicmangos")}"
            CharacterDatabaseInfo = "${db("classiccharacters")}"
            LogsDatabaseInfo = "${db("classiclogs")}"
            RealmID = 1
            WorldServerPort = ${ServerRuntimeContract.WORLD_PORT}
            BindIP = "127.0.0.1"
            Network.Threads = 1
            Console.Enable = 0
            Ra.Enable = 0
            SOAP.Enabled = 0
            vmap.enableLOS = ${if (normalPlay) 1 else 0}
            vmap.enableHeight = ${if (normalPlay) 1 else 0}
            vmap.enableIndoorCheck = ${if (normalPlay) 1 else 0}
            mmap.enabled = ${if (normalPlay) 1 else 0}
            LogLevel = 3
            LogFile = "${logs.resolve("world.log").absolutePath}"
            LogFileLevel = 3
            DBErrorLogFile = "${logs.resolve("database-errors.log").absolutePath}"
            PlayerLimit = 10
            # The production realm is app-private and loopback-only. Wine's
            # clock can deliver buffered CMSG_PING packets in a burst after a
            # slow emulated frame; CMaNGOS documents 0 as disabling this kick.
            MaxOverspeedPings = 0
            MaxCoreStuckTime = 0
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
}
