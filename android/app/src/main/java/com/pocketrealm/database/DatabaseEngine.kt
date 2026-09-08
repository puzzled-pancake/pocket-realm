package com.pocketrealm.database

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.os.Build
import android.os.Process
import com.pocketrealm.storage.StorageRoots
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.wine.WineSpikeNative
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * MariaDB lifecycle owner. All paths and commands are derived internally from
 * immutable APK artifacts; Binder callers cannot inject either SQL or argv.
 */
internal class DatabaseEngine(private val context: Context) {
    private val lock = Any()
    private val roots = StorageRoots.get(context)
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val mariadbd = File(nativeDir, "libpocket_mariadbd.so")
    private val mariadb = File(nativeDir, "libpocket_mariadb_client.so")
    private val providerRoot = File(roots.databaseRoot, "provider")
    private val selectedAbi = Build.SUPPORTED_ABIS.firstOrNull {
        it == "arm64-v8a" || it == "x86_64"
    } ?: throw IllegalStateException(
        "DB-ABI: unsupported device ABI list ${Build.SUPPORTED_ABIS.joinToString(",")}",
    )
    private val providerId = when (selectedAbi) {
        "arm64-v8a" -> DatabaseRuntimeContract.ARM_PROVIDER_ID
        "x86_64" -> DatabaseRuntimeContract.X86_PROVIDER_ID
        else -> error("DB-ABI: unsupported selected ABI $selectedAbi")
    }
    private val providerVersion = when (selectedAbi) {
        "arm64-v8a" -> DatabaseRuntimeContract.ARM_PROVIDER_VERSION
        "x86_64" -> DatabaseRuntimeContract.X86_PROVIDER_VERSION
        else -> error("DB-ABI: unsupported selected ABI $selectedAbi")
    }
    private val datadir = roots.databaseDatadir
    private val runDir = roots.databaseRun
    private val socket = File(runDir, "mariadb.sock")
    private val pidFile = File(runDir, "mariadb.pid")
    private val errorLog = File(runDir, "mariadb.err")
    private val cleanMarker = File(roots.databaseRoot, "clean-stop.json")
    private val dirtyRecord = File(roots.databaseRoot, "recovery.json")
    private val secretFile = File(roots.databaseRoot, "secrets.json")
    private val configFile = File(runDir, "my.cnf")
    private val snapshotStore = DatabaseSnapshotStore(roots.databaseSnapshots)
    private val restoreRecord = File(roots.databaseRoot, "restore-transaction.json")
    private val databaseTransaction = File(roots.databaseRoot, "database-transaction.json")
    private val initializedMarker = File(roots.databaseRoot, "initialized.json")
    private val generationMarker = File(datadir, ".pocketrealm-generation.json")
    private val migrationMarker = File(datadir, ".pocketrealm-migrations.json")
    private val sqliteTranslationDir = File(roots.databaseRoot, "sqlite-translation")
    private val sqliteTranslationRecord = File(roots.databaseRoot, "sqlite-translation.json")
    private val secureImportDir = File(roots.databaseRoot, "import")
    // The SQLite provider's own datadir (sibling of the MariaDB
    // datadir - the dual-provider window keeps both providers' state
    // distinct; the MariaDB datadir stays as the rollback anchor) + its seals.
    private val sqliteDatadir = File(roots.databaseRoot, DatabaseSqliteControlPlane.SQLITE_DATADIR_NAME)
    private val sqliteInitializedMarker = File(roots.databaseRoot, "sqlite-initialized.json")
    private val sqliteCleanMarker = File(roots.databaseRoot, "sqlite-clean-stop.json")
    private val sqliteMigrationMarker = File(sqliteDatadir, ".pocketrealm-migrations.json")
    private val sqliteGenerationMarker = File(sqliteDatadir, ".pocketrealm-generation.json")
    private val activeProviderMarkerFile = File(roots.databaseRoot, DatabaseDurableState.ACTIVE_PROVIDER_MARKER_NAME)
    private val expectedBootstrapSha256 by lazy { sha256Asset(BOOTSTRAP_ASSET) }
    private val expectedMigrationManifest by lazy {
        context.assets.open(MIGRATION_MANIFEST).bufferedReader().use { it.readText() }
    }
    private val expectedMigrationManifestSha256 by lazy { sha256Text(expectedMigrationManifest) }
    private val expectedMigrationCount by lazy {
        JSONObject(expectedMigrationManifest).getJSONArray("entries").length()
    }
    private val providerIdentity by lazy { loadAndVerifyProviderIdentity() }
    // The SQLite provider identity (window APKs only - the staged
    // provenance asset is the single verified source; see
    // loadAndVerifySqliteIdentity). Both identities coexist in the
    // dual-provider window: the MariaDB one still boots to serve the
    // user-state translation export.
    private val sqliteProvenance by lazy { runCatching { loadAndVerifySqliteIdentity() } }
    private val sqliteCapable by lazy {
        runCatching { context.assets.open(SQLITE_IDENTITY_ASSET).use { it.readBytes() } }.isSuccess
    }
    @Volatile private var state = State.STOPPED
    @Volatile private var daemonResult: DatabaseRunResult? = null
    @Volatile private var daemonThread: Thread? = null
    @Volatile private var projectedRealmEndpoint: String? = null
    // The minutes-long provisioning phases (translation export, seed
    // replay, user-state import) run with `lock` FREE - only their state
    // transitions take it, so Binder callers are never blocked behind
    // them. provisioningPhase
    // is what status() serves mid-phase; the depth+owner pair is the
    // single-writer guard every mutating method refuses under (nesting is
    // same-thread only: provision -> translate/import).
    private var provisioningDepth = 0
    private var provisioningOwner: Thread? = null
    @Volatile private var provisioningPhase: String? = null

    private fun beginProvisioning() {
        check(provisioningDepth == 0 || provisioningOwner === Thread.currentThread()) {
            "DB-PROVISION: a provider provisioning operation is in flight"
        }
        provisioningDepth++
        provisioningOwner = Thread.currentThread()
    }

    private fun endProvisioning() {
        provisioningDepth--
        if (provisioningDepth == 0) provisioningOwner = null
    }

    private fun requireProvisioningIdle() {
        check(provisioningDepth == 0 || provisioningOwner === Thread.currentThread()) {
            "DB-PROVISION: a provider provisioning operation is in flight"
        }
    }

    private enum class State { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
    private data class Secrets(val admin: String, val core: String)

    fun status(): JSONObject = synchronized(lock) {
        val mode = providerModeLocked()
        val transactionKind = databaseTransactionKind()
        val sqliteReady = sqliteProvenance.isSuccess
        val ownership = if (mode == DatabaseDurableState.ProviderMode.SQLITE) {
            runCatching {
                DatabaseDurableState.ownershipCompatibility(
                    sqliteInitializedMarker.takeIf(File::isFile)?.readText(),
                    sqliteIdentity(),
                    sqliteGenerationUuid(),
                )
            }.getOrDefault(
                if (sqliteInitializedMarker.isFile) DatabaseDurableState.OwnershipCompatibility.INVALID
                else DatabaseDurableState.OwnershipCompatibility.MISSING,
            )
        } else {
            runCatching {
                DatabaseDurableState.ownershipCompatibility(
                    initializedMarker.takeIf(File::isFile)?.readText(),
                    providerIdentity,
                    generationUuid(datadir),
                )
            }.getOrDefault(
                if (initializedMarker.isFile) DatabaseDurableState.OwnershipCompatibility.INVALID
                else DatabaseDurableState.OwnershipCompatibility.MISSING,
            )
        }
        val activeDatadir = if (mode == DatabaseDurableState.ProviderMode.SQLITE) sqliteDatadir else datadir
        return JSONObject().put("ok", true).put("state", state.name)
            .put("providerMode", mode.name)
            .put("providerReady", if (mode == DatabaseDurableState.ProviderMode.SQLITE) sqliteReady else providerReady())
            .put("sqliteCapable", sqliteCapable)
            .put("sqliteInitialized", sqliteInitializedSealValid())
            .put("legacyMariadbInitialized", initializedMarker.isFile)
            .put("migrationManifestCount", expectedMigrationCount)
            .put("migrationSealedCount", migrationSealedCount(mode) ?: JSONObject.NULL)
            .put("provisioning", provisioningPhase ?: JSONObject.NULL)
            .put("initialized", if (mode == DatabaseDurableState.ProviderMode.SQLITE) sqliteInitializedSealValid() else initialized())
            .put("migrationsCurrent", if (mode == DatabaseDurableState.ProviderMode.SQLITE) sqliteMigrationsCurrent() else migrationsCurrent())
            .put("socketExists", if (mode == DatabaseDurableState.ProviderMode.SQLITE) false else socket.exists())
            .put("tcpDisabled", true)
            .put("cleanMarker", if (mode == DatabaseDurableState.ProviderMode.SQLITE) sqliteCleanGeneration() else cleanGeneration())
            .put("restorePending", restoreRecord.isFile)
            .put("databaseTransactionPending", databaseTransaction.isFile)
            .put("databaseTransactionKind", transactionKind ?: JSONObject.NULL)
            .put("sqliteTranslationPhase", sqliteTranslationPhase() ?: JSONObject.NULL)
            .put("compatibilityMismatch", when (ownership) {
                DatabaseDurableState.OwnershipCompatibility.PROVIDER_MISMATCH -> "PROVIDER_IDENTITY"
                DatabaseDurableState.OwnershipCompatibility.GENERATION_MISMATCH -> "GENERATION"
                DatabaseDurableState.OwnershipCompatibility.INVALID -> "INVALID_DURABLE_STATE"
                else -> JSONObject.NULL
            })
            .put("datadir", activeDatadir.absolutePath)
            .put("pid", Process.myPid())
            .put("lastExit", if (mode == DatabaseDurableState.ProviderMode.SQLITE) JSONObject.NULL
                else daemonResult?.exitCode ?: JSONObject.NULL)
    }

    fun initialize(): JSONObject = when (providerModeLocked()) {
        DatabaseDurableState.ProviderMode.SQLITE -> sqliteInitialize()
        DatabaseDurableState.ProviderMode.MARIADB -> mariadbInitialize()
    }

    private fun mariadbInitialize(): JSONObject = synchronized(lock) {
        requireStopped()
        requireProvider()
        recoverIncompleteInitialization()
        if (initialized()) {
            return when (DatabaseDurableState.initializedDisposition(
                databaseTransaction.takeIf(File::isFile)?.readText(),
                generationMarker.takeIf(File::isFile)?.readText(),
                providerIdentity,
            )) {
                DatabaseDurableState.InitializedDisposition.IDEMPOTENT ->
                    JSONObject().put("ok", true).put("initialized", true).put("idempotent", true)
                DatabaseDurableState.InitializedDisposition.DEFER_MIGRATION ->
                    JSONObject().put("ok", true).put("initialized", true).put("idempotent", true)
                        .put("migrationTransactionPending", true)
                        .put("deferredTo", "applyPinnedMigrations")
                DatabaseDurableState.InitializedDisposition.FAIL_CLOSED -> error(
                    "DB-TRANSACTION: initialized generation has an incompatible pending transaction",
                )
            }
        }
        check(!databaseTransaction.exists()) { "DB-TRANSACTION: non-init database transaction is pending" }
        check(datadir.listFiles().isNullOrEmpty()) { "DB-INIT: datadir is non-empty without init marker" }
        checkStorage(MIN_INITIALIZE_BYTES)
        val generationUuid = UUID.randomUUID().toString()
        val transactionId = UUID.randomUUID().toString()
        atomicWrite(databaseTransaction, DatabaseDurableState.transaction(
            kind = "INIT", phase = "OWNED", transactionId = transactionId,
            generationUuid = generationUuid, identity = providerIdentity,
        ))
        datadir.mkdirs()
        atomicWrite(generationMarker, DatabaseDurableState.generationMarker(generationUuid))
        try {
            stageProviderData()
            writeConfig()
            val bootstrap = File(providerRoot, "bootstrap.sql")
            check(bootstrap.isFile && sha256(bootstrap) == expectedBootstrapSha256) {
                "DB-INIT: pinned bootstrap.sql identity mismatch"
            }
            updateDatabaseTransactionPhase("INIT", "RUNNING")
            val result = runTool(
                executable = mariadbd,
                argv0 = "mariadbd",
                args = serverBaseArgs() + listOf(
                    "--bootstrap", "--log-warnings=0", "--enforce-storage-engine=",
                    "--max-allowed-packet=8M", "--net-buffer-length=16K",
                ),
                stdin = bootstrap,
                timeoutMs = 180_000,
            )
            check(result.ok) { "DB-INIT: bootstrap failed exit=${result.exitCode}: ${result.stderr.takeLast(1200)}" }
            // mysql_install_db writes this exact marker after a successful
            // bootstrap. Preserve that contract even though the service feeds the pinned
            // bootstrap SQL directly instead of executing the Perl shell wrapper.
            atomicWrite(File(datadir, "mariadb_upgrade_info"), "$providerVersion-MariaDB")
            val secrets = createSecrets()
            startDaemon(allowUnsealedBootstrap = true, allowedTransactionKind = "INIT")
            val setup = fixedSql("initial-auth", """
                CREATE USER IF NOT EXISTS 'pocket_admin'@'localhost' IDENTIFIED BY '${secrets.admin}';
                ALTER USER 'pocket_admin'@'localhost' IDENTIFIED BY '${secrets.admin}';
                GRANT ALL PRIVILEGES ON *.* TO 'pocket_admin'@'localhost' WITH GRANT OPTION;
                CREATE DATABASE IF NOT EXISTS classicrealmd CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
                CREATE DATABASE IF NOT EXISTS classiccharacters CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
                CREATE DATABASE IF NOT EXISTS classiclogs CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
                CREATE DATABASE IF NOT EXISTS classicmangos CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
                CREATE DATABASE IF NOT EXISTS pocketrealm_meta CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
                CREATE USER IF NOT EXISTS 'pocket_core'@'localhost' IDENTIFIED BY '${secrets.core}';
                ALTER USER 'pocket_core'@'localhost' IDENTIFIED BY '${secrets.core}';
                GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,ALTER,INDEX,LOCK TABLES,CREATE TEMPORARY TABLES ON classicrealmd.* TO 'pocket_core'@'localhost';
                GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,ALTER,INDEX,LOCK TABLES,CREATE TEMPORARY TABLES ON classiccharacters.* TO 'pocket_core'@'localhost';
                GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,ALTER,INDEX,LOCK TABLES,CREATE TEMPORARY TABLES ON classiclogs.* TO 'pocket_core'@'localhost';
                GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,ALTER,INDEX,LOCK TABLES,CREATE TEMPORARY TABLES,EXECUTE,CREATE ROUTINE,ALTER ROUTINE ON classicmangos.* TO 'pocket_core'@'localhost';
                GRANT SELECT ON pocketrealm_meta.* TO 'pocket_core'@'localhost';
                ALTER USER 'root'@'localhost' IDENTIFIED BY '${secrets.admin}';
                FLUSH PRIVILEGES;
            """.trimIndent())
            val auth = runClient("root", "", setup)
            check(auth.ok) { "DB-INIT: credential/grant setup failed: ${auth.stderr.takeLast(1200)}" }
            val health = queryHealth()
            check(health.getBoolean("ok")) { "DB-INIT: least-privilege query failed" }
            val forbidden = fixedSql("least-privilege-negative", "CREATE USER 'pocket_forbidden'@'localhost' IDENTIFIED BY 'never';")
            val denied = runClient("pocket_core", secrets.core, forbidden)
            check(!denied.ok) { "DB-INIT: core user unexpectedly has account-administration privilege" }
            stop()
            atomicWrite(initializedMarker, DatabaseDurableState.initializedSeal(
                providerIdentity, generationUuid, System.currentTimeMillis(),
            ))
            writeActiveProviderMarker(DatabaseDurableState.ProviderMode.MARIADB)
            updateDatabaseTransactionPhase("INIT", "COMMITTING")
            durableDelete(databaseTransaction)
            JSONObject().put("ok", true).put("initialized", true)
                .put("generationUuid", generationUuid)
                .put("bootstrapSha256", expectedBootstrapSha256)
                .put("providerClosureSha256", providerIdentity.providerClosureSha256)
                .put("leastPrivilegeVerified", true).put("privilegedActionDenied", true)
                .put("cleanStopped", true)
        } catch (failure: Throwable) {
            if (state != State.STOPPED && state != State.FAILED || daemonThread?.isAlive == true) {
                runCatching { cancelAndRequireDaemonDrained("initialization failure") }
            }
            throw failure
        }
    }

    fun start(): JSONObject = when (providerModeLocked()) {
        DatabaseDurableState.ProviderMode.SQLITE -> sqliteStart()
        DatabaseDurableState.ProviderMode.MARIADB -> mariadbStart()
    }

    private fun mariadbStart(): JSONObject = synchronized(lock) {
        requireProvisioningIdle()
        when (DatabaseDurableState.startBlocker(
            initialized(), migrationsCurrent(), cleanGeneration(), databaseTransaction.exists(),
        )) {
            DatabaseDurableState.StartBlocker.UNINITIALIZED ->
                error("DB-INIT: initialized generation seal is not current")
            DatabaseDurableState.StartBlocker.MIGRATIONS_STALE ->
                error("DB-REVISION: pinned migrations are not current")
            DatabaseDurableState.StartBlocker.DIRTY ->
                error("DB-RECOVERY: database generation is not sealed by a valid clean-stop marker")
            DatabaseDurableState.StartBlocker.TRANSACTION_PENDING ->
                error("DB-TRANSACTION: pending init/migration transaction blocks start")
            null -> Unit
        }
        val started = startDaemon()
        val health = queryHealth()
        started.put("authenticated", health.getBoolean("authenticated"))
            .put("leastPrivilege", true)
    }

    private fun startDaemon(
        allowUnsealedBootstrap: Boolean = false,
        allowDirtyRecovery: Boolean = false,
        allowedTransactionKind: String? = null,
    ): JSONObject {
        check(!(allowUnsealedBootstrap && allowDirtyRecovery)) {
            "database start cannot be both bootstrap and recovery"
        }
        requireProvider()
        if (databaseTransaction.exists()) {
            val transaction = JSONObject(databaseTransaction.readText())
            check(transaction.optString("kind") == allowedTransactionKind) {
                "DB-TRANSACTION: pending ${transaction.optString("kind")} transaction blocks daemon start"
            }
        } else {
            check(allowedTransactionKind == null) { "DB-TRANSACTION: expected $allowedTransactionKind transaction is missing" }
        }
        check(initialized() || allowUnsealedBootstrap) { "DB-INIT: initialize must pass before start" }
        when {
            allowUnsealedBootstrap -> Unit
            allowDirtyRecovery -> check(!cleanGeneration()) {
                "DB-RECOVERY: dirty recovery requested for a clean generation"
            }
            else -> check(cleanGeneration()) {
                "DB-RECOVERY: database generation is not sealed by a valid clean-stop marker"
            }
        }
        check(state == State.STOPPED || state == State.FAILED) { "database already active: $state" }
        requireDaemonDrained("daemon start")
        // Delete stale control files only after independent native/thread/PID
        // drain proof. Their presence must never be treated as permission.
        socket.delete()
        pidFile.delete()
        checkStorage(MIN_START_BYTES)
        // nativeLibraryDir changes whenever Android installs a replacement
        // APK. The persistent provider/lib symlinks therefore must be
        // revalidated and repointed on every stopped start, not only during
        // first-time database initialization.
        stageProviderData()
        durableDelete(cleanMarker)
        writeConfig()
        daemonResult = null
        projectedRealmEndpoint = null
        state = State.STARTING
        val thread = Thread({
            val result = runTool(
                executable = mariadbd,
                argv0 = "mariadbd",
                args = serverBaseArgs(),
                timeoutMs = 0,
                trackDaemon = true,
            )
            daemonResult = result
            if (state != State.STOPPING && state != State.STOPPED) state = State.FAILED
        }, "PocketRealm-MariaDB")
        daemonThread = thread
        thread.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (!socket.exists() && thread.isAlive && System.nanoTime() < deadline) Thread.sleep(100)
        if (!socket.exists()) {
            state = State.FAILED
            cancelAndRequireDaemonDrained("socket readiness failure")
            throw IllegalStateException(
                "DB-SOCKET: socket did not become ready: " +
                    (daemonResult?.stderr?.takeLast(1200) ?: errorLogTail())
            )
        }
        state = State.RUNNING
        return JSONObject().put("ok", true).put("state", state.name)
            .put("socket", socket.absolutePath).put("tcpDisabled", true)
    }

    fun queryHealth(): JSONObject = when (providerModeLocked()) {
        DatabaseDurableState.ProviderMode.SQLITE -> sqliteQueryHealth()
        DatabaseDurableState.ProviderMode.MARIADB -> synchronized(lock) {
            check(state == State.RUNNING) { "database is not running" }
            val secrets = readSecrets()
            val query = fixedSql("health", "SELECT 'POCKET_DB_OK', VERSION(), @@skip_networking;")
            val result = runClient("pocket_core", secrets.core, query)
            check(result.ok && result.stdout.contains("POCKET_DB_OK")) {
                "DB-SOCKET: authenticated health query failed: ${result.stderr.takeLast(1200)}"
            }
            JSONObject().put("ok", true).put("authenticated", true)
                .put("leastPrivilegeConfigured", secrets.core.isNotEmpty())
                .put("result", result.stdout.trim().take(512))
        }
    }

    /** Fixed, owner-gated projection consumed by realmd; arbitrary SQL never crosses Binder. */
    fun projectRealmEndpoint(address: String, worldPort: Int): JSONObject = when (providerModeLocked()) {
        DatabaseDurableState.ProviderMode.SQLITE -> sqliteProjectRealmEndpoint(address, worldPort)
        DatabaseDurableState.ProviderMode.MARIADB -> mariadbProjectRealmEndpoint(address, worldPort)
    }

    private fun mariadbProjectRealmEndpoint(address: String, worldPort: Int): JSONObject = synchronized(lock) {
        check(state == State.RUNNING) { "database is not running" }
        val endpoint = RealmEndpoint.parseStored(address)
        require(worldPort == RealmEndpoint.WORLD_PORT) { "world port is fixed" }
        projectedRealmEndpoint?.let { prior ->
            check(prior == endpoint.address) {
                "realm endpoint is immutable for the active database generation"
            }
            return JSONObject().put("ok", true).put("operation", "realm-endpoint-already-projected")
                .put("address", endpoint.address).put("worldPort", RealmEndpoint.WORLD_PORT)
        }
        val sql = fixedSql("project-realm-endpoint", """
            UPDATE classicrealmd.realmlist
               SET address='${endpoint.address}', port=${RealmEndpoint.WORLD_PORT}
             WHERE id=1;
            SELECT address,port FROM classicrealmd.realmlist WHERE id=1 LIMIT 1;
        """.trimIndent())
        val result = runClient("pocket_core", readSecrets().core, sql)
        check(result.ok) { "realm endpoint projection failed: ${result.stderr.takeLast(800)}" }
        val projected = result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.lastOrNull()
        check(projected == "${endpoint.address}\t${RealmEndpoint.WORLD_PORT}") {
            "realm endpoint projection did not verify"
        }
        projectedRealmEndpoint = endpoint.address
        JSONObject().put("ok", true).put("operation", "realm-endpoint-projected")
            .put("address", endpoint.address).put("worldPort", RealmEndpoint.WORLD_PORT)
    }

    fun stop(): JSONObject = when (providerModeLocked()) {
        DatabaseDurableState.ProviderMode.SQLITE -> sqliteStop()
        DatabaseDurableState.ProviderMode.MARIADB -> synchronized(lock) {
            requireProvisioningIdle()
            check(state == State.RUNNING) { "database is not running" }
            state = State.STOPPING
            val query = fixedSql("shutdown", "SHUTDOWN;")
            val result = runClient("pocket_admin", readSecrets().admin, query)
            check(result.ok) { "DB-SOCKET: clean shutdown request failed: ${result.stderr.takeLast(1000)}" }
            daemonThread?.join(30_000)
            check(daemonThread?.isAlive != true && !socket.exists() && nativeProcessGroupDrained()) {
                "database did not clean-stop and drain within 30 seconds"
            }
            check(daemonResult?.ok == true) { "mariadbd exit was not clean: ${daemonResult?.waitStatus}" }
            state = State.STOPPED
            atomicWrite(cleanMarker, DatabaseDurableState.cleanSeal(
                providerIdentity, requireGenerationUuid(), System.currentTimeMillis(),
            ))
            writeActiveProviderMarker(DatabaseDurableState.ProviderMode.MARIADB)
            JSONObject().put("ok", true).put("state", state.name).put("cleanMarker", true)
        }
    }

    fun killForTest(): JSONObject = when (providerModeLocked()) {
        DatabaseDurableState.ProviderMode.SQLITE -> sqliteKillForTest()
        DatabaseDurableState.ProviderMode.MARIADB -> synchronized(lock) {
            check(state == State.RUNNING || state == State.STARTING) { "database is not active" }
            durableDelete(cleanMarker)
            val killed = DatabaseNative.cancelActiveGlibcProgramNative()
            check(killed) { "tracked MariaDB process tree was not found" }
            daemonThread?.join(10_000)
            state = State.FAILED
            requireDaemonDrained("forced test stop")
            atomicWrite(dirtyRecord, JSONObject().put("schema", 1).put("dirty", true)
                .put("killedAt", System.currentTimeMillis()).put("waitStatus", daemonResult?.waitStatus)
                .toString())
            JSONObject().put("ok", true).put("killed", true).put("cleanMarker", false)
        }
    }

    /** True when the engine observably has no live generation (STOPPED or
     * FAILED) - the only states in which a claim release needs no engine work. */
    fun isEnded(): Boolean = synchronized(lock) { state == State.STOPPED || state == State.FAILED }

    /**
     * The owner-gated stop verb behind DatabaseService.stopOwned. Releasing
     * a CLAIM is a handshake in the :database process, not an engine
     * operation: an engine that is observably down (STOPPED or FAILED -
     * killed, crashed, or already recovered-and-stopped by a later lane)
     * has nothing to stop, so the call is success-for-the-claim and the
     * durable seals are left exactly as they are (an unsealed generation
     * stays dirty for the next start's recovery lane; a valid clean seal is
     * neither fabricated nor deleted). A live engine takes the ordinary
     * clean-stop path with all of its drain proofs - this path never
     * weakens stop() itself.
     */
    fun stopForOwnerRelease(): JSONObject = when (providerModeLocked()) {
        DatabaseDurableState.ProviderMode.SQLITE -> synchronized(lock) {
            if (isEnded()) releaseEndedEngineClaim() else sqliteStop()
        }
        DatabaseDurableState.ProviderMode.MARIADB -> synchronized(lock) {
            if (isEnded()) releaseEndedEngineClaim() else stop()
        }
    }

    /** The claim-release verdict for an ended engine; seals untouched. */
    private fun releaseEndedEngineClaim(): JSONObject = JSONObject().put("ok", true)
        .put("state", state.name)
        .put("alreadyDown", true)
        .put("claimReleased", true)

    fun recover(): JSONObject = when (providerModeLocked()) {
        DatabaseDurableState.ProviderMode.SQLITE -> sqliteRecover()
        DatabaseDurableState.ProviderMode.MARIADB -> synchronized(lock) {
            check(!cleanGeneration()) { "recovery requested for a clean generation" }
            check(state == State.FAILED || state == State.STOPPED) { "database process still active" }
            requireDaemonDrained("dirty recovery")
            check(DatabaseDurableState.dirtyRecoveryPermitted(
                initialized(), cleanGeneration(), databaseTransaction.exists(),
            )) { "DB-RECOVERY: sealed initialized generation without a pending transaction required" }
            val before = errorLog.length()
            startDaemon(allowDirtyRecovery = true)
            val health = queryHealth()
            val recoveryOutput = errorLogTail(fromByte = before)
            val classified = recoveryOutput.contains("recover", ignoreCase = true) ||
                recoveryOutput.contains("crash", ignoreCase = true) ||
                recoveryOutput.contains("InnoDB", ignoreCase = true)
            check(health.getBoolean("ok")) { "DB-RECOVERY: post-dirty health failed" }
            stop()
            atomicWrite(dirtyRecord, JSONObject().put("schema", 1).put("dirty", false)
                .put("recoveredAt", System.currentTimeMillis())
                .put("recoveryOutputObserved", classified)
                .put("logDigest", sha256Text(recoveryOutput)).toString())
            JSONObject().put("ok", true).put("recovered", true)
                .put("recoveryOutputObserved", classified).put("cleanStopped", true)
        }
    }

    fun applyPinnedMigrations(): JSONObject = when (providerModeLocked()) {
        DatabaseDurableState.ProviderMode.SQLITE -> sqliteApplyPinnedMigrations()
        DatabaseDurableState.ProviderMode.MARIADB -> synchronized(lock) {
            requireProvisioningIdle()
            requireStopped()
        val recoveredTransaction = recoverPendingMigrationTransaction()
        check(initialized()) { "DB-INIT: initialize must pass before migrations" }
        check(cleanGeneration()) { "DB-SNAPSHOT: pre-migration generation is not clean" }
        if (migrationsCurrent()) {
            return JSONObject().put("ok", true).put("idempotent", true)
                .put("applied", 0).put("skipped", expectedMigrationCount)
                .put("total", expectedMigrationCount).put("cleanStopped", true)
        }
        checkStorage(MIN_MIGRATION_BYTES)
        val manifest = JSONObject(expectedMigrationManifest)
        check(manifest.getInt("schema") == 1) { "DB-REVISION: unsupported manifest schema" }
        val generationUuid = requireGenerationUuid()
        val snapshotId = "pre-migration-${System.currentTimeMillis()}"
        val compatibility = databaseCompatibility(generationUuid)
        val snapshot = snapshotStore.create(
            datadir, snapshotId, databaseStopped = true, compatibility = compatibility,
        )
        atomicWrite(databaseTransaction, DatabaseDurableState.transaction(
            kind = "MIGRATION", phase = "SNAPSHOT_READY",
            transactionId = UUID.randomUUID().toString(), generationUuid = generationUuid,
            identity = providerIdentity, snapshotId = snapshot.id, snapshotDigest = snapshot.digest,
        ))
        var applied = 0
        var skipped = 0
        try {
            updateDatabaseTransactionPhase("MIGRATION", "RUNNING")
            startDaemon(allowedTransactionKind = "MIGRATION")
            createLedger()
            val entries = manifest.getJSONArray("entries")
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val id = entry.getString("migration_id")
                val sqlHash = entry.getString("sql_sha256")
                check(MIGRATION_ID.matches(id) && SHA256.matches(sqlHash)) { "DB-REVISION: unsafe ledger identity" }
                val prior = ledgerStatus(id)
                if (prior != null) {
                    check(prior.first == "APPLIED" && prior.second == sqlHash) {
                        "DB-REVISION: ledger drift for $id status=${prior.first}"
                    }
                    skipped++
                    continue
                }
                val sql = materializeMigration(entry)
                ledgerPending(entry, snapshotId, manifest)
                // The pinned full world database is ~73 MiB. Keep every
                // client invocation bounded, but scale that bound with the
                // already hash-verified SQL size so slower emulator storage is
                // not misclassified as a revision failure.
                val migrationTimeoutMs = (30_000L + (sql.length() / 1024L) * 40L)
                    .coerceAtMost(600_000L).toInt()
                val result = runClient(
                    "pocket_admin", readSecrets().admin, sql,
                    entry.getString("database"), migrationTimeoutMs,
                )
                val resultDigest = sha256Text(result.stdout + "\n" + result.stderr)
                ledgerFinish(id, if (result.ok) "APPLIED" else "FAILED", resultDigest)
                sql.delete()
                check(result.ok) {
                    "DB-REVISION: migration $id failed exit=${result.exitCode}: ${result.stderr.takeLast(1500)}"
                }
                applied++
            }
            val revisions = verifyExpectedRevisions(manifest)
            check(revisions.getBoolean("ok")) { "DB-REVISION: ${revisions.getString("detail")}" }
            // Mandatory negative test: a deliberately wrong expected column
            // must be rejected without applying or editing any migration.
            val wrongAccepted = revisionColumnExists("classicmangos", "db_version", "required_z9999_not_real")
            check(!wrongAccepted) { "DB-REVISION: mismatch negative test was incorrectly accepted" }
            stop()
            atomicWrite(migrationMarker, DatabaseDurableState.migrationSeal(
                providerIdentity, generationUuid, System.currentTimeMillis(),
            ))
            durableDelete(databaseTransaction)
            snapshotStore.retainNewest(2)
            // The datadir content just changed - any prior user-state
            // translation staging is stale and must not be consumed.
            deleteSqliteTranslationStaging()
            JSONObject().put("ok", true).put("applied", applied).put("skipped", skipped)
                .put("total", manifest.getJSONArray("entries").length())
                .put("snapshotId", snapshotId).put("snapshotDigest", snapshot.digest)
                .put("recoveredInterruptedTransaction", recoveredTransaction)
                .put("revisionMismatchRejected", true).put("cleanStopped", true)
        } catch (failure: Throwable) {
            if (databaseTransaction.isFile) {
                runCatching { updateDatabaseTransactionPhase("MIGRATION", "FAILED") }
                rollbackToSnapshot(snapshot)
                durableDelete(databaseTransaction)
            }
            throw failure
        }
        }
    }

    /**
     * First-boot translation orchestrator (dual-provider window):
     * export the sealed MariaDB datadir's user-state slice as batch-TSV
     * under databaseRoot/sqlite-translation/, behind the same fail-closed
     * seal gates as migrations (clean + current + stopped, no pending
     * transaction or restore verification). The OLD provider must still
     * boot to serve this export - this method starts and clean-stops it
     * through the existing seal machinery, leaving the MariaDB datadir
     * untouched (the export is read-only; staging is disposable).
     *
     * Export wire: per-page SELECT ... INTO OUTFILE (the mysqldump --tab
     * mechanism itself - server-side escaping, no client-stdout
     * boundary) under secure-file-priv, run as pocket_admin (FILE
     * privilege); every table's staged row count is cross-checked
     * against a source COUNT(*) and pages are ordered by PRIMARY
     * KEY where one exists.
     *
     * The SQLite import leg (seed transcripts -> datadir -> INSERT OR
     * REPLACE of these TSVs -> new provider identity seal) is the SQLite
     * control plane's on-device validation lane; the per-table row
     * counts, sha256 digests, and source column lists recorded here are
     * the byte-for-byte baseline that leg verifies against. The record
     * is invalidated when the datadir content changes EXPLICITLY
     * (restore, migration); normal provider operation after the export
     * is detected by the recorded cleanStopSealSha256 — the sha of the
     * clean-stop seal this export's final stop() wrote (every later
     * start()/stop() cycle rewrites the seal with a fresh timestamp, so
     * the import leg can mechanically refuse a baseline that
     * predates any subsequent datadir writes).
     */
    fun translateUserStateToSqliteStaging(): JSONObject {
        // The minutes-long export runs with `lock` FREE - only the entry
        // gates and the internal daemon transitions take it - so status()
        // stays answerable throughout via the @Volatile phase mirror.
        synchronized(lock) {
            check(providerModeLocked() == DatabaseDurableState.ProviderMode.MARIADB) {
                "DB-TRANSLATE: the MariaDB provider must be the active one to export"
            }
            requireProvisioningIdle()
            requireStopped()
            // Idempotent entry: a still-consumable EXPORTED record is
            // returned as-is instead of being swept by a retry - a
            // headroom refusal must not destroy a valid export.
            existingConsumableTranslation()?.let { return it }
            check(initialized() && migrationsCurrent() && cleanGeneration()) {
                "DB-TRANSLATE: clean current initialized datadir required"
            }
            check(!databaseTransaction.exists()) { "DB-TRANSLATE: init/migration transaction is pending" }
            check(!restoreRecord.exists()) { "DB-TRANSLATE: restore verification is pending" }
            // Sweep stale staging BEFORE the storage gate so a failed prior
            // attempt is reclaimed even when headroom would refuse the retry
            // (mirrors beginRestore's ordering). Staging from
            // an interrupted attempt is disposable wholesale: the source
            // datadir was never mutated, so a fresh export is the correct
            // recovery (the SQLite-side .partial/os.replace leg lives in
            // the bridge Importer and is exercised by the host harness).
            deleteSqliteTranslationStaging()
            checkStorage(MIN_TRANSLATE_BYTES)
            beginProvisioning()
        }
        provisioningPhase = "TRANSLATING"
        try {
            return runTranslationExport()
        } finally {
            provisioningPhase = null
            synchronized(lock) { endProvisioning() }
        }
    }

    /** The export body, executed with `lock` free; the internal
     * start()/stop() transitions acquire it themselves. */
    private fun runTranslationExport(): JSONObject {
        sqliteTranslationDir.mkdirs()
        atomicWrite(sqliteTranslationRecord, JSONObject()
            .put("schema", 1).put("phase", "EXPORTING")
            .put("startedAt", System.currentTimeMillis())
            .put("generationUuid", requireGenerationUuid())
            .put("providerClosureSha256", providerIdentity.providerClosureSha256)
            .put("migrationManifestSha256", providerIdentity.migrationManifestSha256)
            .put("migrationCount", providerIdentity.migrationCount)
            .toString())
        try {
            val started = start()
            check(started.getBoolean("authenticated"))
            val databases = JSONObject()
            for (database in SQLITE_TRANSLATION_DATABASES) {
                val tables = when (database) {
                    "classicrealmd" -> DatabaseUserStateBridge.REALMD_USER_STATE_TABLES
                    "classiccharacters" -> sourceTables(database)
                        .filterNot { it.startsWith("ai_playerbot") }
                    else -> error("DB-TRANSLATE: unplanned database $database")
                }
                check(tables.isNotEmpty()) { "DB-TRANSLATE: $database export plan is empty" }
                val perTable = JSONObject()
                for (table in tables) {
                    provisioningPhase = "TRANSLATING:$database.$table"
                    perTable.put(table, exportUserStateTable(database, table))
                }
                databases.put(database, perTable)
            }
            stop()
            DatabaseDurability.syncDirectory(sqliteTranslationDir)
            // The export's final stop() re-sealed the generation; pin the
            // seal's bytes so the import leg can detect ANY later provider
            // cycle (start deletes the seal, stop rewrites it with a new
            // timestamp - without the pin, normal operation after the
            // export would be indistinguishable from a still-fresh
            // baseline).
            val cleanStopSeal = cleanMarker.readText()
            atomicWrite(sqliteTranslationRecord, JSONObject(sqliteTranslationRecord.readText())
                .put("phase", "EXPORTED")
                .put("finishedAt", System.currentTimeMillis())
                .put("cleanStopSealSha256", sha256Text(cleanStopSeal))
                .put("databases", databases)
                .toString())
            return JSONObject().put("ok", true).put("phase", "EXPORTED")
                .put("stagingDir", sqliteTranslationDir.name)
                .put("databases", databases).put("cleanStopped", true)
        } catch (failure: Throwable) {
            // Prefer a clean stop (re-seals the generation); only if the
            // daemon cannot clean-stop, cancel-and-drain and leave the
            // dirty state to the existing recovery path.
            if (state == State.RUNNING) runCatching { stop() }
            if (state == State.RUNNING || state == State.STARTING || state == State.STOPPING ||
                daemonThread?.isAlive == true
            ) {
                runCatching { cancelAndRequireDaemonDrained("user-state translation failure") }
            }
            if (sqliteTranslationRecord.isFile) {
                runCatching {
                    atomicWrite(sqliteTranslationRecord, JSONObject(sqliteTranslationRecord.readText())
                        .put("phase", "EXPORT_FAILED").toString())
                }
            }
            throw failure
        }
    }

    /** Idempotent translate entry: the existing EXPORTED record when it
     * would still pass every consumer gate (seal pin, identity, generation,
     * staging intactness) - a retry must not sweep a valid export. */
    private fun existingConsumableTranslation(): JSONObject? {
        val recordText = sqliteTranslationRecord.takeIf(File::isFile)?.readText() ?: return null
        val consumption = DatabaseDurableState.translationConsumable(
            recordText = recordText,
            liveCleanSealText = cleanMarker.takeIf(File::isFile)?.readText(),
            identity = providerIdentity,
            liveGenerationUuid = generationUuid(datadir),
            stagedTableVerified = { database, table, rows, sha256, bytes ->
                stagedTableIntact(database, table, rows, sha256, bytes)
            },
        )
        if (consumption != DatabaseDurableState.TranslationConsumption.CONSUMABLE) return null
        return JSONObject().put("ok", true).put("phase", "EXPORTED").put("idempotent", true)
            .put("stagingDir", sqliteTranslationDir.name)
            .put("databases", JSONObject(recordText).optJSONObject("databases") ?: JSONObject())
            .put("cleanStopped", true)
    }

    /** The translationConsumable staging predicate: byte-exact verification
     * of one staged TSV against its recorded rows/sha256/bytes. */
    private fun stagedTableIntact(database: String, table: String, rows: Long, sha256: String, bytes: Long): Boolean =
        runCatching {
            val staged = File(sqliteTranslationDir, "$database.$table.tsv")
            staged.isFile && staged.length() == bytes && sha256(staged) == sha256 &&
                staged.inputStream().use { it.readBytes().count { byte -> byte == '\n'.code.toByte() }.toLong() == rows }
        }.getOrDefault(false)

    /** Dispose of any prior translation staging + record (disposable by
     * design; also the invalidation hook for datadir-changing events).
     * Also sweeps leftover INTO OUTFILE page files (a killed run's page
     * files under import/sqlite-translation would otherwise linger
     * forever whenever the next attempt never re-touches the same
     * (table, offset) name). */
    private fun deleteSqliteTranslationStaging() {
        if (sqliteTranslationDir.exists()) deleteTreeDurably(sqliteTranslationDir)
        durableDelete(sqliteTranslationRecord)
        val pageDir = File(secureImportDir, "sqlite-translation")
        if (pageDir.isDirectory) {
            pageDir.listFiles()?.forEach { file ->
                check(file.isFile && file.delete()) {
                    "DB-TRANSLATE: cannot sweep stale page file ${file.name}"
                }
            }
            check(pageDir.delete() || !pageDir.isDirectory) {
                "DB-TRANSLATE: cannot remove the stale page directory"
            }
            DatabaseDurability.syncDirectory(secureImportDir)
        }
    }

    // ==================================================================
    // The SQLite provider branch. Daemon-less: the realm
    // runtimes open the datadir's databases in-process, so DATABASE
    // start/stop is seal + integrity bookkeeping, and the supervisor
    // contract (DATABASE -> REALM -> WORLD; stop saveWorld-first) is
    // untouched. The MariaDB machinery above stays intact as the
    // rollback provider.
    // ==================================================================

    fun sqliteInitialize(): JSONObject {
        val generationUuid = synchronized(lock) {
            requireProvisioningIdle()
            requireStopped()
            requireSqliteProvider()
            // Route by the record's provider (a MariaDB-kind record on a
            // fresh window install recovers the legacy datadir; a sqlite
            // record runs the provisioning recovery below).
            recoverIncompleteInitialization()
            if (sqliteInitializedSealValid()) {
                when (DatabaseDurableState.initializedDisposition(
                    databaseTransaction.takeIf(File::isFile)?.readText(),
                    sqliteGenerationMarker.takeIf(File::isFile)?.readText(),
                    sqliteIdentity(),
                )) {
                    DatabaseDurableState.InitializedDisposition.IDEMPOTENT ->
                        return JSONObject().put("ok", true).put("initialized", true)
                            .put("idempotent", true)
                    DatabaseDurableState.InitializedDisposition.DEFER_MIGRATION ->
                        return JSONObject().put("ok", true).put("initialized", true)
                            .put("idempotent", true)
                            .put("migrationTransactionPending", true)
                            .put("deferredTo", "applyPinnedMigrations")
                    DatabaseDurableState.InitializedDisposition.FAIL_CLOSED -> error(
                        "DB-TRANSACTION: initialized generation has an incompatible pending transaction",
                    )
                }
            }
            check(!databaseTransaction.exists()) { "DB-TRANSACTION: non-init database transaction is pending" }
            check(sqliteDatadir.listFiles().isNullOrEmpty()) {
                "DB-INIT: sqlite datadir is non-empty without init seal"
            }
            checkStorage(MIN_SQLITE_SEED_BYTES)
            val generation = UUID.randomUUID().toString()
            // The record spans the ENTIRE provision (seed +
            // verify + seals) and is deleted only after the commit block -
            // a crash anywhere recovers through quarantine-and-retry.
            atomicWrite(databaseTransaction, DatabaseDurableState.transaction(
                kind = "INIT", phase = "OWNED", transactionId = UUID.randomUUID().toString(),
                generationUuid = generation, identity = sqliteIdentity(),
            ))
            beginProvisioning()
            generation
        }
        provisioningPhase = "SEEDING"
        try {
            val summary = seedSqliteDatadir(generationUuid)
            return synchronized(lock) {
                val now = System.currentTimeMillis()
                atomicWrite(sqliteInitializedMarker, DatabaseDurableState.initializedSeal(
                    sqliteIdentity(), generationUuid, now,
                ))
                // The seed already folded the manifest and
                // verifySqliteRevisions proved it, so the migration seal is
                // stamped at birth - and CLEAN IS WRITTEN LAST (the
                // sibling legs' order): KEEP_COMPLETED's liveSealsValid
                // proxy (initialized+clean) then implies every seal is on
                // disk, and a kill mid-block can never leave
                // initialized+clean without the migration seal (the state
                // that wedged corpus-advanced APKs one layer deeper).
                atomicWrite(sqliteMigrationMarker, DatabaseDurableState.migrationSeal(
                    sqliteIdentity(), generationUuid, now,
                ))
                // A freshly initialized datadir is born clean-stopped
                // (MariaDB parity - initialize's final stop() seals); the
                // first boot then skips a spurious RECOVER detour.
                atomicWrite(sqliteCleanMarker, DatabaseDurableState.cleanSeal(
                    sqliteIdentity(), generationUuid, now,
                ))
                writeActiveProviderMarker(DatabaseDurableState.ProviderMode.SQLITE)
                updateDatabaseTransactionPhase("INIT", "COMMITTING")
                durableDelete(databaseTransaction)
                summary
                    .put("initialized", true)
                    .put("generationUuid", generationUuid)
                    .put("provider", DatabaseRuntimeContract.SQLITE_PROVIDER_ID)
                    .put("cleanStopped", true)
            }
        } finally {
            provisioningPhase = null
            synchronized(lock) { endProvisioning() }
        }
    }

    fun sqliteApplyPinnedMigrations(): JSONObject {
        // An unclean death must not wedge the provider until a full
        // uninstall clears the datadir, so recover in place first; the
        // heal admission keeps every fail-closed
        // invariant (valid seal, current migrations, NO pending
        // transaction - that record's own protocol owns it).
        var selfHealed = false
        if (!sqliteCleanGeneration()) {
            val healed = sqliteSelfHealDirtyGeneration()
            selfHealed = !healed.optBoolean("alreadyClean")
        }
        val result = synchronized(lock) {
        requireProvisioningIdle()
        requireStopped()
        requireSqliteProvider()
        check(sqliteInitializedSealValid()) { "DB-INIT: initialize must pass before migrations" }
        check(sqliteCleanGeneration()) { "DB-SNAPSHOT: pre-migration generation is not clean" }
        if (sqliteMigrationsCurrent()) {
            return@synchronized JSONObject().put("ok", true).put("idempotent", true)
                .put("applied", 0).put("skipped", expectedMigrationCount)
                .put("total", expectedMigrationCount).put("cleanStopped", true)
        }
        // The seed replay folded the entire pinned manifest; the ledger
        // must reflect it exactly. A ledger BEHIND the manifest means the
        // corpus advanced since this datadir was provisioned: the SQLite
        // lane re-provisions (fresh seed + user-state carry), never
        // applies untranslated MariaDB dialect on device.
        verifySeedLedgerMatchesManifest(strict = true)
        verifySqliteRevisions()
        atomicWrite(sqliteMigrationMarker, DatabaseDurableState.migrationSeal(
            sqliteIdentity(), sqliteGenerationUuid()!!, System.currentTimeMillis(),
        ))
        JSONObject().put("ok", true).put("applied", 0).put("skipped", expectedMigrationCount)
            .put("total", expectedMigrationCount)
            .put("seedFoldedManifest", true)
            .put("revisionMismatchRejected", true).put("cleanStopped", true)
        }
        return result.put("selfHealed", selfHealed)
    }

    fun sqliteStart(): JSONObject {
        var selfHealed = false
        synchronized(lock) {
            // The guard is acquired INSIDE the entry critical
            // section (check-then-begin under one lock hold).
            requireProvisioningIdle()
            requireSqliteProvider()
            check(state == State.STOPPED || state == State.FAILED) {
                "database already active: $state"
            }
            when (DatabaseDurableState.startBlocker(
                sqliteInitializedSealValid(), sqliteMigrationsCurrent(), sqliteCleanGeneration(),
                databaseTransaction.exists(),
            )) {
                DatabaseDurableState.StartBlocker.UNINITIALIZED ->
                    error("DB-INIT: initialized generation seal is not current")
                DatabaseDurableState.StartBlocker.MIGRATIONS_STALE ->
                    error("DB-REVISION: pinned migrations are not current")
                // An unclean death recovers in place instead of refusing
                // until a full uninstall clears the datadir. The
                // pending-transaction
                // fail-closed invariant does NOT come from this ordering
                // (startBlocker tests !clean before transactionPending) -
                // sqliteSelfHealDirtyGeneration's own admission re-check
                // refuses a pending transaction; do not "simplify" it away.
                DatabaseDurableState.StartBlocker.DIRTY -> selfHealed = true
                DatabaseDurableState.StartBlocker.TRANSACTION_PENDING ->
                    error("DB-TRANSACTION: pending init/migration transaction blocks start")
                null -> Unit
            }
            if (!selfHealed) {
                // The clean seal is retired BEFORE any gate write (MariaDB
                // parity - startDaemon deletes it before starting), and the
                // permit is acquired under this same entry critical
                // section - the ONLY acquisition on the clean path.
                durableDelete(sqliteCleanMarker)
                beginProvisioning()
            }
        }
        if (selfHealed) {
            // alreadyClean = a concurrent actor sealed the generation
            // between the entry check and the heal admission: nothing
            // healed, and the normal-start sequence below applies as-is.
            sqliteSelfHealDirtyGeneration()
            synchronized(lock) {
                // Retire the seal the heal just wrote and take the
                // permit the clean path already holds; the integrity phase
                // runs under exactly one permit either way (the heal's own
                // verify pass matches the explicit recover()+start()
                // sequence, never a weaker gate).
                requireProvisioningIdle()
                durableDelete(sqliteCleanMarker)
                beginProvisioning()
            }
        }
        provisioningPhase = "INTEGRITY_CHECK"
        try {
            // Boot integrity gate (lock-free: full integrity_check over
            // the 1.36M-row world database is a long read; status() stays
            // answerable via the @Volatile phase mirror).
            val gate = JSONObject()
            var rebuilt = false
            for (database in DatabaseSqliteControlPlane.DATABASES +
                DatabaseSqliteControlPlane.META_DATABASE
            ) {
                val outcome = verifyOrRebuildSqlite(DatabaseSqliteControlPlane.databaseFile(sqliteDatadir, database))
                gate.put(database, outcome)
                rebuilt = rebuilt || outcome == "rebuilt"
            }
            // A rebuild replaced database bytes the revision probe
            // validates - re-prove the pinned revisions after any rebuild.
            if (rebuilt) verifySqliteRevisions()
            synchronized(lock) {
                state = State.RUNNING
                // Refresh the marker idempotently - a crash inside a
                // prior seal-commit block could have left it stale while
                // the engine (and the resolved mode) already serve sqlite.
                writeActiveProviderMarker(DatabaseDurableState.ProviderMode.SQLITE)
                return JSONObject().put("ok", true).put("state", state.name)
                    .put("provider", DatabaseRuntimeContract.SQLITE_PROVIDER_ID)
                    .put("socketless", true).put("tcpDisabled", true)
                    .put("authenticated", true)
                    .put("selfHealed", selfHealed)
                    .put("databases", JSONArray(
                        DatabaseSqliteControlPlane.DATABASES.map {
                            DatabaseSqliteControlPlane.databaseFile(sqliteDatadir, it).absolutePath
                        },
                    ))
                    .put("integrityGate", gate)
            }
        } finally {
            provisioningPhase = null
            synchronized(lock) { endProvisioning() }
        }
    }

    fun sqliteStop(): JSONObject {
        synchronized(lock) {
            requireProvisioningIdle()
            check(state == State.RUNNING) { "database is not running" }
        }
        // The ACTIVE drain. The :world runtime's clean stop path never
        // closes its database connections (Master::StopEmbedded ends with
        // HaltDelayThread) and the process is kill-retired 250 ms
        // later - so passive sidecar absence can never be produced by the
        // runtimes. The engine itself performs the last-connection proof:
        // open read-write, checkpoint the WAL, close. If a peer process
        // still holds the database, the checkpoint leaves the sidecars in
        // place and the check below refuses (the supervisor's
        // saveWorld-first ordering was violated).
        // The :world process is kill-retired ~250 ms AFTER its stop
        // ack (the supervisor proceeds immediately), so the first drain
        // pass can legitimately race that window on a fast stop. Bounded
        // retry: after the world process dies, the engine's next open is
        // the last connection - checkpoint + close deletes the sidecars.
        // A sidecar that survives the deadline means a LIVE peer holds
        // the database (the ordering contract genuinely violated).
        val checkpointed = JSONObject()
        val drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SQLITE_DRAIN_TIMEOUT_SECONDS)
        while (true) {
            for (database in DatabaseSqliteControlPlane.DATABASES +
                DatabaseSqliteControlPlane.META_DATABASE
            ) {
                val file = DatabaseSqliteControlPlane.databaseFile(sqliteDatadir, database)
                sqliteOpen(file, writable = true).use { db ->
                    checkpointed.put(database, execPragma(db, "PRAGMA wal_checkpoint(TRUNCATE);"))
                }
            }
            val present = DatabaseSqliteControlPlane.walSidecars(sqliteDatadir).filter { it.exists() }
            if (present.isEmpty()) break
            if (System.nanoTime() >= drainDeadline) {
                synchronized(lock) {
                    // Leave the engine recover-eligible, not wedged in
                    // RUNNING with nothing running.
                    state = State.FAILED
                }
                throw IllegalStateException(
                    "DB-SQLITE: WAL sidecars survived ${SQLITE_DRAIN_TIMEOUT_SECONDS}s of " +
                        "engine checkpoints - a realm/world connection is still open " +
                        "(saveWorld-first ordering violated): " + present.joinToString { it.name },
                )
            }
            Thread.sleep(250)
        }
        synchronized(lock) {
            state = State.STOPPED
            atomicWrite(sqliteCleanMarker, DatabaseDurableState.cleanSeal(
                sqliteIdentity(), sqliteGenerationUuid()!!, System.currentTimeMillis(),
            ))
            return JSONObject().put("ok", true).put("state", state.name).put("cleanMarker", true)
                .put("checkpoint", checkpointed)
        }
    }

    fun sqliteQueryHealth(): JSONObject = synchronized(lock) {
        check(state == State.RUNNING) { "database is not running" }
        val meta = DatabaseSqliteControlPlane.databaseFile(sqliteDatadir, DatabaseSqliteControlPlane.META_DATABASE)
        sqliteOpen(meta, writable = false).use { database ->
            val tables = database.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table';", null)
                .use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
            check(tables >= 1) { "DB-SQLITE: ledger database is empty" }
            JSONObject().put("ok", true).put("authenticated", true)
                .put("socketless", true)
                .put("result", "POCKET_DB_OK sqlite ledger_tables=$tables")
        }
    }

    fun sqliteProjectRealmEndpoint(address: String, worldPort: Int): JSONObject = synchronized(lock) {
        check(state == State.RUNNING) { "database is not running" }
        val endpoint = RealmEndpoint.parseStored(address)
        require(worldPort == RealmEndpoint.WORLD_PORT) { "world port is fixed" }
        projectedRealmEndpoint?.let { prior ->
            check(prior == endpoint.address) {
                "realm endpoint is immutable for the active database generation"
            }
            return@synchronized JSONObject().put("ok", true).put("operation", "realm-endpoint-already-projected")
                .put("address", endpoint.address).put("worldPort", RealmEndpoint.WORLD_PORT)
        }
        val realmd = DatabaseSqliteControlPlane.databaseFile(sqliteDatadir, "classicrealmd")
        sqliteOpen(realmd, writable = true).use { database ->
            database.execSQL(
                "UPDATE realmlist SET address = ?, port = ? WHERE id = 1",
                arrayOf(endpoint.address, RealmEndpoint.WORLD_PORT.toString()),
            )
            database.rawQuery("SELECT address, port FROM realmlist WHERE id = 1 LIMIT 1;", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == endpoint.address &&
                    cursor.getInt(1) == RealmEndpoint.WORLD_PORT) {
                    "realm endpoint projection did not verify"
                }
            }
        }
        projectedRealmEndpoint = endpoint.address
        JSONObject().put("ok", true).put("operation", "realm-endpoint-projected")
            .put("address", endpoint.address).put("worldPort", RealmEndpoint.WORLD_PORT)
    }

    fun sqliteRecover(): JSONObject {
        synchronized(lock) {
            requireProvisioningIdle()
            check(!sqliteCleanGeneration()) { "recovery requested for a clean generation" }
            check(state == State.FAILED || state == State.STOPPED) { "database is still active" }
            requireSqliteProvider()
            check(sqliteInitializedSealValid()) { "DB-RECOVERY: initialized sqlite generation required" }
            beginProvisioning()
        }
        return sqliteRecoverDirtyGenerationCore()
    }

    /**
     * Self-heal an unclean death in place: the DIRTY start blocker and
     * the pre-migration clean check would otherwise refuse until a full
     * uninstall cleared the datadir. Admission here is
     * exactly DatabaseDurableState.dirtyRecoveryPermitted, enforced
     * fail-closed: a valid initialized seal, current pinned migrations,
     * and NO pending init/migration transaction (that record's own
     * recovery protocol owns it). Returns alreadyClean when another
     * caller sealed the generation between the caller's check and this
     * admission - callers proceed down their normal path.
     */
    private fun sqliteSelfHealDirtyGeneration(): JSONObject {
        synchronized(lock) {
            requireProvisioningIdle()
            requireSqliteProvider()
            check(state == State.FAILED || state == State.STOPPED) { "database is still active" }
            check(sqliteInitializedSealValid()) { "DB-RECOVERY: initialized sqlite generation required" }
            check(sqliteMigrationsCurrent()) { "DB-REVISION: pinned migrations are not current" }
            check(!databaseTransaction.exists()) {
                "DB-TRANSACTION: pending init/migration transaction blocks recovery"
            }
            if (sqliteCleanGeneration()) {
                return JSONObject().put("ok", true).put("alreadyClean", true)
            }
            beginProvisioning()
        }
        val healed = sqliteRecoverDirtyGenerationCore()
        healed.put("selfHealed", true)
        return healed
    }

    /** The shared checkpoint / integrity / re-seal sequence. The caller
     * holds the provisioning permit (explicit recover() or self-heal). */
    private fun sqliteRecoverDirtyGenerationCore(): JSONObject {
        provisioningPhase = "RECOVERING"
        try {
            // Opening each database read-write triggers SQLite's own WAL
            // recovery + checkpoint-on-close; the integrity gate then
            // verifies (or VACUUM INTO rebuilds) what survived.
            val gate = JSONObject()
            var rebuilt = false
            for (database in DatabaseSqliteControlPlane.DATABASES +
                DatabaseSqliteControlPlane.META_DATABASE
            ) {
                val file = DatabaseSqliteControlPlane.databaseFile(sqliteDatadir, database)
                sqliteOpen(file, writable = true).use { handle ->
                    execPragma(handle, "PRAGMA wal_checkpoint(TRUNCATE);")
                }
                val outcome = verifyOrRebuildSqlite(file)
                gate.put(database, outcome)
                rebuilt = rebuilt || outcome == "rebuilt"
            }
            // The sqliteStart gate's discipline: a rebuild replaced
            // database bytes the revision probe validates - re-prove the
            // pinned revisions after any rebuild, or the clean re-seal
            // below would cover unrecovered salvage damage. The callers
            // (explicit recover / start self-heal / migrations pre-step)
            // all route through here, and on the self-healed start path
            // sqliteStart's own rebuilt-check sees "ok" (already rebuilt)
            // and would never fire this itself.
            if (rebuilt) {
                verifySqliteRevisions()
            }
            synchronized(lock) {
                state = State.STOPPED
                atomicWrite(sqliteCleanMarker, DatabaseDurableState.cleanSeal(
                    sqliteIdentity(), sqliteGenerationUuid()!!, System.currentTimeMillis(),
                ))
                writeActiveProviderMarker(DatabaseDurableState.ProviderMode.SQLITE)
                atomicWrite(dirtyRecord, JSONObject().put("schema", 1).put("dirty", false)
                    .put("recoveredAt", System.currentTimeMillis())
                    .put("recoveryOutputObserved", true).toString())
                return JSONObject().put("ok", true).put("recovered", true)
                    .put("recoveryOutputObserved", true).put("cleanStopped", true)
                    .put("integrityGate", gate)
            }
        } finally {
            provisioningPhase = null
            synchronized(lock) { endProvisioning() }
        }
    }

    fun sqliteKillForTest(): JSONObject = synchronized(lock) {
        // FAILED is accepted too - a refused stop (sidecar deadline)
        // leaves FAILED, and the supervisor's forceStop fallback routes
        // here; refusing would strand the fallback.
        check(state == State.RUNNING || state == State.STARTING || state == State.FAILED) {
            "database is not active"
        }
        // The daemon-less provider has no process of its own to kill; the
        // debug injection models the lost clean-stop (the real dirty-kill
        // testing drives kills against :world).
        durableDelete(sqliteCleanMarker)
        state = State.FAILED
        atomicWrite(dirtyRecord, JSONObject().put("schema", 1).put("dirty", true)
            .put("killedAt", System.currentTimeMillis()).toString())
        JSONObject().put("ok", true).put("killed", true).put("cleanMarker", false)
    }

    /**
     * The SQLite provider's Binder provisioning entry (runs with `lock`
     * free during the minutes-long phases). Two variants share this
     * method: the WINDOW TRANSITION (MariaDB active, sqlite datadir
     * absent - run the translation then import and activate) and the
     * DELTA RE-PROVISION (SQLite active but the manifest advanced - fresh
     * seed at the new corpus plus a sqlite-to-sqlite user-state carry
     * from the outgoing datadir; the frozen MariaDB datadir is NOT the
     * source here, or every post-cutover change would be lost).
     */
    fun provisionSqliteProvider(): JSONObject {
        val transition = synchronized(lock) {
            requireProvisioningIdle()
            requireStopped()
            requireSqliteProvider()
            val isTransition = when (providerModeLocked()) {
                DatabaseDurableState.ProviderMode.MARIADB -> {
                    // Tombstone semantics: a durable marker naming the
                    // SQLite provider means a cutover ONCE completed - the
                    // MariaDB
                    // datadir is frozen pre-cutover state and must never be
                    // re-translated over a device whose live data is (or
                    // was) sqlite. Fail loud; the interrupted-provisioning
                    // recovery (via the INIT record) is the only path back.
                    check(DatabaseDurableState.parseActiveProviderMarker(
                        activeProviderMarkerFile.takeIf(File::isFile)?.readText(),
                    )?.mode != DatabaseDurableState.ProviderMode.SQLITE) {
                        "DB-PROVISION: this device already cut over to the SQLite provider " +
                            "(active-provider marker); refusing the MariaDB window transition - " +
                            "an interrupted re-provision must recover through its transaction record"
                    }
                    check(initialized() && migrationsCurrent() && cleanGeneration()) {
                        "DB-PROVISION: clean current MariaDB datadir required for the window translation"
                    }
                    check(!databaseTransaction.exists()) { "DB-TRANSACTION: init/migration transaction is pending" }
                    check(!restoreRecord.exists()) { "DB-SNAPSHOT: restore verification is pending" }
                    // Double-fault hardening: a non-empty sqlite
                    // datadir means sqlite state EXISTS even if the marker
                    // is lost; the transition must not run over it.
                    check(sqliteDatadir.listFiles().isNullOrEmpty()) {
                        "DB-PROVISION: sqlite datadir is non-empty; refusing the MariaDB " +
                            "window transition (recover the sqlite datadir first)"
                    }
                    true
                }
                DatabaseDurableState.ProviderMode.SQLITE -> {
                    // Manifest-advance re-provision: the migration seal is
                    // stale by COUNT (never applied out from under us).
                    check(sqliteInitializedSealValid()) { "DB-PROVISION: sqlite datadir is not initialized" }
                    check(sqliteCleanGeneration()) { "DB-PROVISION: sqlite generation is not clean" }
                    check(!databaseTransaction.exists()) { "DB-TRANSACTION: init/migration transaction is pending" }
                    check(!sqliteMigrationsCurrent()) { "DB-PROVISION: sqlite provider is already current" }
                    val sealed = checkNotNull(
                        migrationSealedCount(DatabaseDurableState.ProviderMode.SQLITE),
                    ) { "DB-PROVISION: migration seal absent - apply migrations first" }
                    check(sealed < expectedMigrationCount) {
                        "DB-PROVISION: refusing to re-provision a same-revision datadir"
                    }
                    false
                }
            }
            // Acquired inside the entry critical section.
            beginProvisioning()
            isTransition
        }
        try {
            return if (transition) {
                provisioningPhase = "TRANSLATING"
                val translation = translateUserStateToSqliteStaging()
                check(translation.getBoolean("ok"))
                provisioningPhase = "IMPORTING"
                importSqliteUserState()
            } else {
                provisioningPhase = "REPROVISIONING"
                reProvisionSqliteDatadir()
            }
        } finally {
            provisioningPhase = null
            synchronized(lock) { endProvisioning() }
        }
    }

    /** The import leg: seed a fresh sqlite datadir from the pinned
     * transcripts, then INSERT OR REPLACE the translation staging's user
     * state over it (user wins; the per-table row counts recorded in the
     * record are enforced at import time), through the translation
     * consumer gate. Runs ONLY under
     * provisionSqliteProvider's provisioning guard (same thread). */
    private fun importSqliteUserState(): JSONObject {
        val recordText = synchronized(lock) {
            check(provisioningDepth > 0 && provisioningOwner === Thread.currentThread()) {
                "DB-IMPORT: must run under an owned provisioning operation"
            }
            val text = sqliteTranslationRecord.takeIf(File::isFile)?.readText()
            val consumption = DatabaseDurableState.translationConsumable(
                recordText = text,
                liveCleanSealText = cleanMarker.takeIf(File::isFile)?.readText(),
                identity = providerIdentity,
                liveGenerationUuid = generationUuid(datadir),
                stagedTableVerified = { database, table, rows, sha256, bytes ->
                    stagedTableIntact(database, table, rows, sha256, bytes)
                },
            )
            check(consumption == DatabaseDurableState.TranslationConsumption.CONSUMABLE) {
                "DB-IMPORT: translation staging is not consumable ($consumption)"
            }
            check(sqliteDatadir.listFiles().isNullOrEmpty()) {
                "DB-IMPORT: sqlite datadir must be empty before import"
            }
            checkStorage(MIN_SQLITE_SEED_BYTES)
            text!!
        }
        provisioningPhase = "SEEDING"
        val generationUuid = UUID.randomUUID().toString()
        // The record spans the ENTIRE provision (it is deleted
        // only after the seal+marker commit below) - a kill during the
        // import/verify minutes recovers through quarantine-and-retry.
        atomicWrite(databaseTransaction, DatabaseDurableState.transaction(
            kind = "INIT", phase = "RUNNING", transactionId = UUID.randomUUID().toString(),
            generationUuid = generationUuid, identity = sqliteIdentity(),
        ))
        val seedSummary = seedSqliteDatadir(generationUuid)
        provisioningPhase = "IMPORTING"
        val record = JSONObject(recordText)
        var importedRows = 0L
        val imported = JSONObject()
        val databases = record.getJSONObject("databases")
        for (database in databases.keys()) {
            val tables = databases.getJSONObject(database)
            val perTable = JSONObject()
            for (table in tables.keys()) {
                provisioningPhase = "IMPORTING:$database.$table"
                importedRows += importUserStateTable(
                    database, table, tables.getJSONObject(table),
                    DatabaseSqliteControlPlane.databaseFile(sqliteDatadir, database),
                )
                perTable.put(table, tables.getJSONObject(table).getLong("rows"))
            }
            imported.put(database, perTable)
        }
        provisioningPhase = "VERIFYING"
        verifySeedLedgerMatchesManifest(strict = true)
        for (database in DatabaseSqliteControlPlane.DATABASES) {
            integrityRequired(DatabaseSqliteControlPlane.databaseFile(sqliteDatadir, database))
        }
        verifySqliteRevisions()
        return synchronized(lock) {
            val now = System.currentTimeMillis()
            atomicWrite(sqliteInitializedMarker, DatabaseDurableState.initializedSeal(
                sqliteIdentity(), generationUuid, now,
            ))
            atomicWrite(sqliteMigrationMarker, DatabaseDurableState.migrationSeal(
                sqliteIdentity(), generationUuid, now,
            ))
            atomicWrite(sqliteCleanMarker, DatabaseDurableState.cleanSeal(
                sqliteIdentity(), generationUuid, now,
            ))
            writeActiveProviderMarker(DatabaseDurableState.ProviderMode.SQLITE)
            updateDatabaseTransactionPhase("INIT", "COMMITTING")
            durableDelete(databaseTransaction)
            // The consumed staging is now this datadir's provenance; keep
            // the record for audit (a later re-translate sweeps it).
            seedSummary.put("ok", true).put("imported", true)
                .put("importedRows", importedRows)
                .put("importedTables", imported)
                .put("generationUuid", generationUuid)
                .put("provider", DatabaseRuntimeContract.SQLITE_PROVIDER_ID)
                .put("cleanStopped", true)
        }
    }

    /** One staged table's rows over the freshly seeded database. */
    private fun importUserStateTable(database: String, table: String, recorded: JSONObject, target: File): Long {
        val staged = File(sqliteTranslationDir, "$database.$table.tsv")
        val columns = sqliteTargetColumns(target, table)
        // The staged column list must match the
        // seeded target's columns by NAME AND ORDER before any positional
        // zip - a schema drift between corpus revisions fails loud here,
        // never as silently misbound columns.
        val recordedColumns = recorded.getJSONArray("columns").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        check(recordedColumns == columns.map { it.name }) {
            "DB-IMPORT: $database.$table column drift: staged ${recordedColumns.size} " +
                "vs seeded ${columns.size} columns"
        }
        val rows = DatabaseUserStateBridge.decodeTsvBytes(staged.readBytes())
        check(rows.size.toLong() == recorded.getLong("rows")) {
            "DB-IMPORT: $database.$table decoded ${rows.size} rows vs recorded ${recorded.getLong("rows")}"
        }
        sqliteOpen(target, writable = true).use { db ->
            val statement = db.compileStatement(DatabaseUserStateBridge.Importer.importSql(table, columns))
            db.beginTransaction()
            try {
                for (row in rows) {
                    val params = DatabaseUserStateBridge.Importer.rowParams(table, columns, row)
                    bindAll(statement, params)
                    statement.execute()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return rows.size.toLong()
    }

    /** The manifest-advance re-provision: fresh seed at the new corpus +
     * sqlite-to-sqlite user-state carry (same engine, same slice rule as
     * the translation), retiring the outgoing datadir to a FIXED name
     * (the record is written BEFORE the move and spans the whole
     * provision, so every crash window recovers by RESTORING the retired
     * datadir - never by re-translating the frozen MariaDB one). */
    private fun reProvisionSqliteDatadir(): JSONObject {
        val carryFrom = sqliteDatadir
        val retired = File(roots.databaseRoot, RETIRED_DATADIR_NAME)
        val generationUuid = UUID.randomUUID().toString()
        // Symmetric with the other two provisioning callers - the
        // fresh seed + WAL + carry run while the retired datadir also
        // occupies the volume; refuse BEFORE burning a seed.
        checkStorage(MIN_SQLITE_SEED_BYTES)
        provisioningPhase = "SEEDING"
        synchronized(lock) {
            // Record first (the entire provision is crash-covered), then
            // retire the PREVIOUS cycle's anchor and move the live datadir.
            atomicWrite(databaseTransaction, DatabaseDurableState.transaction(
                kind = "INIT", phase = "RUNNING", transactionId = UUID.randomUUID().toString(),
                generationUuid = generationUuid, identity = sqliteIdentity(),
            ))
            if (retired.exists()) deleteTreeDurably(retired)
            atomicMove(carryFrom, retired)
        }
        val seedSummary = seedSqliteDatadir(generationUuid)
        provisioningPhase = "CARRYING"
        var carriedRows = 0L
        for ((database, source) in listOf(
            "classicrealmd" to DatabaseSqliteControlPlane.databaseFile(retired, "classicrealmd"),
            "classiccharacters" to DatabaseSqliteControlPlane.databaseFile(retired, "classiccharacters"),
        )) {
            val target = DatabaseSqliteControlPlane.databaseFile(sqliteDatadir, database)
            val sourceTablesList = when (database) {
                "classicrealmd" -> DatabaseUserStateBridge.REALMD_USER_STATE_TABLES
                else -> sqliteUserTables(source).filterNot { it.startsWith("ai_playerbot") }
            }
            for (table in sourceTablesList) {
                provisioningPhase = "CARRYING:$database.$table"
                carriedRows += carryUserStateTable(source, target, table)
            }
        }
        provisioningPhase = "VERIFYING"
        verifySeedLedgerMatchesManifest(strict = true)
        verifySqliteRevisions()
        return synchronized(lock) {
            val now = System.currentTimeMillis()
            atomicWrite(sqliteInitializedMarker, DatabaseDurableState.initializedSeal(
                sqliteIdentity(), generationUuid, now,
            ))
            atomicWrite(sqliteMigrationMarker, DatabaseDurableState.migrationSeal(
                sqliteIdentity(), generationUuid, now,
            ))
            atomicWrite(sqliteCleanMarker, DatabaseDurableState.cleanSeal(
                sqliteIdentity(), generationUuid, now,
            ))
            writeActiveProviderMarker(DatabaseDurableState.ProviderMode.SQLITE)
            updateDatabaseTransactionPhase("INIT", "COMMITTING")
            durableDelete(databaseTransaction)
            // Keep the retired datadir for one provisioning cycle as the
            // rollback anchor; a later re-provision's retire deletes it.
            seedSummary.put("ok", true).put("reProvisioned", true)
                .put("carriedRows", carriedRows)
                .put("retiredDatadir", retired.name)
                .put("generationUuid", generationUuid)
                .put("provider", DatabaseRuntimeContract.SQLITE_PROVIDER_ID)
                .put("cleanStopped", true)
        }
    }

    /** sqlite-to-sqlite user-state copy, columns matched BY NAME (the
     * corpus may have drifted: shared columns carry, new columns take
     * defaults, removed columns drop - standard data-migration semantics,
     * and loud when a slice table vanishes from the new corpus). */
    private fun carryUserStateTable(source: File, target: File, table: String): Long {
        val sourceColumns = sqliteTargetColumns(source, table)
        val targetColumns = sqliteTargetColumns(target, table)
        check(targetColumns.isNotEmpty()) {
            "DB-REPROVISION: slice table $table is absent from the new corpus"
        }
        val shared = targetColumns.filter { target -> sourceColumns.any { it.name == target.name } }
        check(shared.isNotEmpty()) { "DB-REPROVISION: no shared columns for $table" }
        val names = shared.joinToString(", ") { "\"${it.name}\"" }
        var rows = 0L
        sqliteOpen(source, writable = false).use { from ->
            sqliteOpen(target, writable = true).use { to ->
                val insert = to.compileStatement(
                    "INSERT OR REPLACE INTO \"$table\" ($names) VALUES (" +
                        shared.joinToString(", ") { "?" } + ")",
                )
                to.beginTransaction()
                try {
                    from.rawQuery("SELECT $names FROM \"$table\";", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            shared.forEachIndexed { index, _ ->
                                when (cursor.getType(index)) {
                                    Cursor.FIELD_TYPE_NULL -> insert.bindNull(index + 1)
                                    Cursor.FIELD_TYPE_BLOB -> insert.bindBlob(index + 1, cursor.getBlob(index))
                                    else -> insert.bindString(index + 1, cursor.getString(index))
                                }
                            }
                            insert.execute()
                            rows++
                        }
                    }
                    to.setTransactionSuccessful()
                } finally {
                    to.endTransaction()
                }
            }
        }
        return rows
    }

    // ---------------- seed replay machinery + integrity ---------------

    /**
     * Seed the sqlite datadir from the pinned .sqlz transcripts. The
     * INIT transaction record is owned by the CALLER (written before
     * entry, deleted only after the caller's seal+marker commit - the
     * record spans the entire provision). This function only
     * materializes the datadir: generation marker, one database at a
     * time into `<db>.sqlite3.partial` (single transaction per
     * transcript, the synchronous=NORMAL commit happening exactly
     * once), integrity check, atomic rename, then the meta ledger
     * recording the folded manifest. NO seals - the callers own those.
     */
    private fun seedSqliteDatadir(generationUuid: String): JSONObject {
        val pins = sqliteProvenance().seeds
        // Repeated crash-loop provisioning would leak
        // datadir-sized quarantine dirs until the storage gate wedges -
        // a NEW seed means every prior partial is irrelevant; reclaim.
        roots.databaseRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("sqlite-init-quarantine-") }
            ?.forEach { deleteTreeDurably(it) }
        sqliteDatadir.mkdirs()
        atomicWrite(File(sqliteDatadir, ".pocketrealm-generation.json"),
            DatabaseDurableState.generationMarker(generationUuid))
        val perDatabase = JSONObject()
        for (database in DatabaseSqliteControlPlane.DATABASES) {
            provisioningPhase = "SEEDING:$database"
            perDatabase.put(database, replaySeedDatabase(database, pins.getValue(database)))
        }
        provisioningPhase = "SEEDING:meta"
        createSqliteLedger()
        verifySqliteRevisions()
        DatabaseDurability.syncDirectory(sqliteDatadir)
        return JSONObject().put("ok", true)
            .put("provider", DatabaseRuntimeContract.SQLITE_PROVIDER_ID)
            .put("databases", perDatabase)
    }

    /** One transcript: gunzip-verify-execute against a .partial database,
     * then atomically publish. Verifies BOTH the gzip bytes and the raw
     * transcript bytes against the provenance pins, extended onto the
     * device replay itself. */
    private fun replaySeedDatabase(database: String, pin: SqliteSeedPin): JSONObject {
        val live = DatabaseSqliteControlPlane.databaseFile(sqliteDatadir, database)
        val partial = DatabaseUserStateBridge.Importer.partialFor(live)
        check(!live.exists()) { "DB-SEED: $database already exists" }
        partial.delete()
        val gzipDigest = MessageDigest.getInstance("SHA-256")
        val rawDigest = MessageDigest.getInstance("SHA-256")
        var rawBytes = 0L
        val scanner = DatabaseSqliteControlPlane.SeedStatementScanner()
        var executed = 0
        val database_ = SQLiteDatabase.openDatabase(
            partial.absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
        )
        try {
            // Row-returning PRAGMAs (journal_mode, wal_checkpoint)
            // cannot cross execSQL on the framework API - route every
            // policy pragma through the rawQuery-based executor.
            for (pragma in DatabaseSqliteConfigPolicy.renderConnectionPragmas()) {
                execPragma(database_, pragma)
            }
            database_.beginTransaction()
            try {
                context.assets.open(DatabaseSqliteControlPlane.seedAsset(database)).use { asset ->
                    DigestInputStream(asset, gzipDigest).use { checked ->
                        GZIPInputStream(checked).use { zipped ->
                            InputStreamReader(zipped, Charsets.UTF_8).use { reader ->
                                val buffer = CharArray(64 * 1024)
                                while (true) {
                                    val read = reader.read(buffer)
                                    if (read < 0) break
                                    val chunk = String(buffer, 0, read)
                                    // The pin is BYTES; a char count
                                    // would under-count the multibyte-dense
                                    // corpus (the z2815 row alone is
                                    // 1,041,717 bytes over <=800k chars).
                                    val encoded = chunk.toByteArray(Charsets.UTF_8)
                                    rawDigest.update(encoded)
                                    rawBytes += encoded.size.toLong()
                                    for (statement in scanner.feed(chunk)) {
                                        executeSeedStatement(database_, database, statement)
                                        executed++
                                    }
                                }
                            }
                        }
                    }
                }
                scanner.finish()?.let { statement ->
                    executeSeedStatement(database_, database, statement)
                    executed++
                }
                database_.setTransactionSuccessful()
            } finally {
                database_.endTransaction()
            }
            fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
            check(hex(gzipDigest.digest()) == pin.gzipSha256) { "DB-SEED: $database gzip digest mismatch" }
            check(hex(rawDigest.digest()) == pin.sha256) { "DB-SEED: $database transcript digest mismatch" }
            check(rawBytes == pin.size) { "DB-SEED: $database transcript size mismatch" }
            integrityRequired(partial)
            execPragma(database_, "PRAGMA wal_checkpoint(TRUNCATE);")
        } finally {
            database_.close()
        }
        check(!File(partial.parentFile, partial.name + "-wal").exists() &&
            !File(partial.parentFile, partial.name + "-shm").exists()) {
            "DB-SEED: $database replay left WAL sidecars behind"
        }
        check(partial.renameTo(live) || (live.delete() && partial.renameTo(live))) {
            "DB-SEED: cannot publish $database"
        }
        DatabaseDurability.syncDirectory(sqliteDatadir)
        return JSONObject().put("statements", executed).put("bytes", live.length())
    }

    /** Seed-statement failures carry the exact statement index + offset
     * assigned by the seed splitter. */
    private fun executeSeedStatement(
        database: SQLiteDatabase,
        name: String,
        statement: DatabaseSqliteControlPlane.SeedStatement,
    ) {
        try {
            database.execSQL(statement.sql)
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "DB-SEED: $name statement ${statement.index} at offset ${statement.offset} failed " +
                    "(...${statement.sql.takeLast(200)}): ${failure.message}",
                failure,
            )
        }
    }

    /** The meta database + the folded-manifest ledger (all entries
     * APPLIED with their pinned hashes). */
    private fun createSqliteLedger() {
        val meta = DatabaseSqliteControlPlane.databaseFile(sqliteDatadir, DatabaseSqliteControlPlane.META_DATABASE)
        val partial = DatabaseUserStateBridge.Importer.partialFor(meta)
        partial.delete()
        val database = SQLiteDatabase.openDatabase(
            partial.absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
        )
        try {
            // The connection policy applies to EVERY writable
            // engine open - this one included (mirrors replaySeedDatabase's
            // blanket pragma loop; busy_timeout rides the same list).
            for (pragma in DatabaseSqliteConfigPolicy.renderConnectionPragmas()) {
                execPragma(database, pragma)
            }
            database.execSQL(DatabaseSqliteControlPlane.LEDGER_DDL)
            val manifest = JSONObject(expectedMigrationManifest)
            val commits = manifest.getJSONObject("source_commits")
            val buildId = manifest.getString("app_build_id")
            val entries = manifest.getJSONArray("entries")
            database.beginTransaction()
            try {
                val statement = database.compileStatement(DatabaseSqliteControlPlane.LEDGER_APPLIED_INSERT)
                val now = System.currentTimeMillis()
                for (index in 0 until entries.length()) {
                    val entry = entries.getJSONObject(index)
                    val component = entry.getString("component")
                    val sourceKey = when {
                        component.startsWith("playerbot") -> "playerbots"
                        component == "world" && entry.getString("source_path").startsWith("native/classic-db") -> "classic_db"
                        else -> "cmangos"
                    }
                    statement.bindString(1, entry.getString("migration_id"))
                    statement.bindString(2, component)
                    statement.bindString(3, commits.getString(sourceKey))
                    statement.bindString(4, commits.getString("cmangos"))
                    statement.bindString(5, entry.getString("sql_sha256"))
                    statement.bindLong(6, now)
                    statement.bindLong(7, now)
                    statement.bindString(8, "seed-folded")
                    statement.bindString(9, buildId)
                    statement.execute()
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            integrityRequired(partial)
            execPragma(database, "PRAGMA wal_checkpoint(TRUNCATE);")
        } finally {
            database.close()
        }
        check(!File(partial.parentFile, partial.name + "-wal").exists() &&
            !File(partial.parentFile, partial.name + "-shm").exists()) {
            "DB-SEED: ledger replay left WAL sidecars behind"
        }
        check(partial.renameTo(meta) || (meta.delete() && partial.renameTo(meta))) {
            "DB-SEED: cannot publish the ledger database"
        }
    }

    /** The ledger must contain EXACTLY the manifest's entries, all
     * APPLIED with the pinned hashes (strict) - the sqlite-lane
     * refuse-on-mismatch contract. */
    private fun verifySeedLedgerMatchesManifest(strict: Boolean) {
        val meta = DatabaseSqliteControlPlane.databaseFile(sqliteDatadir, DatabaseSqliteControlPlane.META_DATABASE)
        val recorded = mutableMapOf<String, Pair<String, String>>()
        sqliteOpen(meta, writable = false).use { database ->
            database.rawQuery(
                "SELECT migration_id, status, sql_sha256 FROM migration_ledger;", null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    recorded[cursor.getString(0)] = cursor.getString(1) to cursor.getString(2)
                }
            }
        }
        val manifest = JSONObject(expectedMigrationManifest)
        val entries = manifest.getJSONArray("entries")
        check(!strict || recorded.size == entries.length()) {
            "DB-REVISION: sqlite ledger has ${recorded.size} entries vs the manifest's ${entries.length()} " +
                "(corpus advanced - re-provision required)"
        }
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val status = recorded[entry.getString("migration_id")]
                ?: error("DB-REVISION: sqlite ledger is missing ${entry.getString("migration_id")}")
            check(status.first == "APPLIED" && status.second == entry.getString("sql_sha256")) {
                "DB-REVISION: sqlite ledger drift for ${entry.getString("migration_id")} status=${status.first}"
            }
        }
    }

    /** The SQLite revision probe: pragma_table_info over each component's
     * version table, plus the MANDATORY negative test (a deliberately
     * wrong expected column must be rejected). */
    private fun verifySqliteRevisions() {
        val expected = JSONObject(expectedMigrationManifest).getJSONObject("expected_revisions")
        val checks = listOf(
            Triple("realm", "classicrealmd", "realmd_db_version"),
            Triple("characters", "classiccharacters", "character_db_version"),
            Triple("logs", "classiclogs", "logs_db_version"),
            Triple("world", "classicmangos", "db_version"),
        )
        for ((component, database, table) in checks) {
            val column = expected.getString(component)
            check(sqliteRevisionColumnExists(database, table, column)) {
                "DB-REVISION: $component missing $column"
            }
        }
        check(!sqliteRevisionColumnExists("classicmangos", "db_version", "required_z9999_not_real")) {
            "DB-REVISION: mismatch negative test was incorrectly accepted"
        }
    }

    private fun sqliteRevisionColumnExists(database: String, table: String, column: String): Boolean {
        check(DATABASE_NAME.matches(database) && SAFE_FILE.matches(table) && SAFE_FILE.matches(column))
        val file = DatabaseSqliteControlPlane.databaseFile(sqliteDatadir, database)
        return sqliteOpen(file, writable = false).use { db ->
            db.rawQuery(
                DatabaseSqliteControlPlane.REVISION_PROBE,
                DatabaseSqliteControlPlane.revisionProbeBinds(table, column),
            ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) == 1L }
        }
    }

    /** quick_check first (fast pass), then the full integrity verdict. */
    private fun integrityOk(file: File): Boolean {
        if (!file.isFile) return false
        return sqliteOpen(file, writable = false).use { database ->
            fun verdict(query: String): Boolean = database.rawQuery(query, null).use { cursor ->
                cursor.moveToFirst() && cursor.count == 1 && cursor.getString(0) == DatabaseSqliteControlPlane.INTEGRITY_OK
            }
            verdict(DatabaseSqliteControlPlane.QUICK_CHECK) &&
                verdict(DatabaseSqliteControlPlane.INTEGRITY_CHECK)
        }
    }

    /** Fail loud unless clean (the post-replay/post-import gate). */
    private fun integrityRequired(file: File) {
        check(integrityOk(file)) { "DB-SQLITE: integrity_check failed for ${file.name}" }
    }

    /** Corruption handling: verify or VACUUM INTO rebuild. Returns the
     * gate outcome for status evidence; throws when unrecoverable. */
    private fun verifyOrRebuildSqlite(file: File): String {
        if (integrityOk(file)) return "ok"
        val rebuilt = File(file.parentFile, file.name + ".rebuilt")
        rebuilt.delete()
        sqliteOpen(file, writable = false).use { database ->
            database.execSQL(DatabaseSqliteControlPlane.VACUUM_INTO, arrayOf(rebuilt.absolutePath))
        }
        check(integrityOk(rebuilt)) {
            "DB-SQLITE: ${file.name} failed integrity_check and the VACUUM INTO rebuild is also not clean"
        }
        // VACUUM INTO output carries a DELETE rollback journal, not
        // WAL - restore the persisted journal mode before publishing.
        sqliteOpen(rebuilt, writable = true).use { database ->
            check(execPragma(database, "PRAGMA journal_mode=WAL;") == "wal") {
                "DB-SQLITE: rebuilt ${file.name} did not accept WAL mode"
            }
        }
        check(!File(rebuilt.parentFile, rebuilt.name + "-wal").exists() &&
            !File(rebuilt.parentFile, rebuilt.name + "-shm").exists()) {
            "DB-SQLITE: rebuilt ${file.name} left WAL sidecars behind"
        }
        check(rebuilt.renameTo(file) || (file.delete() && rebuilt.renameTo(file))) {
            "DB-SQLITE: cannot replace ${file.name} with its rebuild"
        }
        DatabaseDurability.syncDirectory(sqliteDatadir)
        return "rebuilt"
    }

    /** The import leg's target columns for one table (bridge
     * Importer.TargetColumn shape, declaration order). */
    private fun sqliteTargetColumns(database: File, table: String): List<DatabaseUserStateBridge.Importer.TargetColumn> =
        sqliteOpen(database, writable = false).use { db ->
            db.rawQuery(
                DatabaseSqliteControlPlane.TABLE_COLUMNS_PROBE,
                DatabaseSqliteControlPlane.tableColumnsProbeBinds(table),
            ).use { cursor ->
                val columns = mutableListOf<DatabaseUserStateBridge.Importer.TargetColumn>()
                while (cursor.moveToNext()) {
                    columns.add(
                        DatabaseUserStateBridge.Importer.TargetColumn(
                            cursor.getString(0),
                            cursor.getString(1) ?: "",
                            cursor.getInt(2) != 0,
                        ),
                    )
                }
                columns
            }
        }

    /** User tables of one sqlite database (the carry slice's discovery). */
    private fun sqliteUserTables(database: File): List<String> =
        sqliteOpen(database, writable = false).use { db ->
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;",
                null,
            ).use { cursor ->
                val tables = mutableListOf<String>()
                while (cursor.moveToNext()) tables.add(cursor.getString(0))
                tables
            }
        }

    private fun sqliteOpen(file: File, writable: Boolean): SQLiteDatabase {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath, null,
            if (writable) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY,
        )
        // The engine's OWN connections must honor the same policy
        // the runtimes apply (WAL journal mode with synchronous=NORMAL;
        // the busy_timeout) - the framework library's defaults do NOT
        // match the amalgamation recipe. journal_mode is persisted in the
        // file from the seed replay; re-asserting it is a no-op that
        // returns a row (execPragma handles that); synchronous and
        // busy_timeout are connection-local and must be set on EVERY
        // connection.
        execPragma(database, "PRAGMA busy_timeout=" +
            DatabaseSqliteConfigPolicy.BUSY_TIMEOUT_MS + ";")
        if (writable) {
            for (pragma in DatabaseSqliteConfigPolicy.renderConnectionPragmas()) {
                execPragma(database, pragma)
            }
        }
        return database
    }

    /** Execute one PRAGMA on the framework SQLiteDatabase. PRAGMAs may
     * return a row (journal_mode, wal_checkpoint) - execSQL forbids that,
     * so every pragma routes through rawQuery. */
    private fun execPragma(database: SQLiteDatabase, pragma: String): String? =
        database.rawQuery(pragma, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun bindAll(statement: SQLiteStatement, params: List<Any?>) {
        params.forEachIndexed { index, value ->
            when (value) {
                null -> statement.bindNull(index + 1)
                is String -> statement.bindString(index + 1, value)
                is ByteArray -> statement.bindBlob(index + 1, value)
                else -> error("DB-IMPORT: unsupported bind parameter ${value.javaClass}")
            }
        }
    }

    // ---------------- identity, mode, seals ----------------------------

    /** Identity source: the staged provenance asset (byte-identical to
     * the committed sibling lockfile - Gradle asserts the equality at
     * assembly). Every artifact the window APK actually packages is
     * verified here: the runtimes in nativeLibraryDir AND the four seed
     * .sqlz assets (gzip digest + size). */
    private fun loadAndVerifySqliteIdentity(): SqliteProvenance {
        val provenanceText = context.assets.open(SQLITE_IDENTITY_ASSET)
            .bufferedReader().use { it.readText() }
        val provenance = JSONObject(provenanceText)
        check(provenance.getInt("schema") == 1 &&
            provenance.getString("database_backend") == "sqlite" &&
            provenance.getString("abi") == selectedAbi) {
            "DB-LINK: sqlite provenance identity mismatch"
        }
        check(provenance.getBoolean("playerbots")) { "DB-LINK: sqlite provenance is not the playerbots build" }
        val artifacts = mutableMapOf<String, String>()
        val artifactsJson = provenance.getJSONArray("artifacts")
        for (index in 0 until artifactsJson.length()) {
            val record = artifactsJson.getJSONObject(index)
            val apkName = File(record.getString("path")).name
            check(SAFE_FILE.matches(apkName) && artifacts.put(apkName, record.getString("sha256")) == null) {
                "DB-LINK: invalid or duplicate sqlite artifact entry $apkName"
            }
            val staged = File(nativeDir, apkName)
            check(staged.isFile && sha256(staged) == record.getString("sha256")) {
                "DB-LINK: sqlite native closure hash mismatch for $apkName"
            }
        }
        val seedsJson = provenance.getJSONObject("seed_transcripts")
        check(seedsJson.length() == DatabaseSqliteControlPlane.DATABASES.size) {
            "DB-LINK: sqlite provenance does not pin exactly the four seed transcripts"
        }
        val seeds = mutableMapOf<String, SqliteSeedPin>()
        for (database in DatabaseSqliteControlPlane.DATABASES) {
            val pin = seedsJson.getJSONObject(database)
            check(pin.getString("gzip_sha256").matches(SHA256) && pin.getString("sha256").matches(SHA256)) {
                "DB-LINK: malformed seed pin for $database"
            }
            seeds[database] = SqliteSeedPin(
                sha256 = pin.getString("sha256"),
                size = pin.getLong("size"),
                gzipSha256 = pin.getString("gzip_sha256"),
                gzipSize = pin.getLong("gzip_size"),
            )
        }
        // Verify the ACTUAL APK seed assets against the pins (the replay
        // re-verifies stream digests too; this is the pre-flight shape).
        for ((database, pin) in seeds) {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            context.assets.open(DatabaseSqliteControlPlane.seedAsset(database)).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    bytes += count.toLong()
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            check(bytes == pin.gzipSize && sha == pin.gzipSha256) {
                "DB-LINK: seed asset digest mismatch for $database"
            }
        }
        // OWNSHIP FIELDS MUST BE CORPUS-STABLE. The provenance
        // pins the seed transcripts, so hashing the whole asset would
        // change the identity on every corpus advance - breaking seal
        // ownership exactly the way the MariaDB lane forbids
        // ("initialization and clean-stop ownership deliberately exclude
        // the pinned migration revision"). The provider closure is
        // therefore the provenance MINUS seed_transcripts (the runtimes/
        // overlays/patches - changes only when the provider build
        // changes); the seed corpus identity rides the migration-manifest
        // identity (sha+count), which the migration seal enforces. The
        // sqlite provider has no separate bootstrap artifact, so
        // bootstrapSha256 aliases the closure digest (the corpus is
        // pinned by the manifest leg, never by ownership).
        val closureSha256 = DatabaseDurableState.sqliteClosureDigest(provenanceText)
        return SqliteProvenance(
            identity = DatabaseDurableState.Identity(
                providerId = DatabaseRuntimeContract.SQLITE_PROVIDER_ID,
                providerClosureSha256 = closureSha256,
                bootstrapSha256 = closureSha256,
                migrationManifestSha256 = expectedMigrationManifestSha256,
                migrationCount = expectedMigrationCount,
            ),
            artifacts = artifacts,
            seeds = seeds,
        )
    }

    private fun requireSqliteProvider() {
        check(sqliteProvenance.isSuccess) { "DB-LINK: pinned SQLite provider is not staged" }
    }

    private fun sqliteIdentity(): DatabaseDurableState.Identity = sqliteProvenance.getOrThrow().identity

    private fun sqliteProvenance(): SqliteProvenance = sqliteProvenance.getOrThrow()

    private fun providerModeLocked(): DatabaseDurableState.ProviderMode =
        DatabaseDurableState.resolveProviderMode(
            sqliteCapable = sqliteCapable,
            sqliteInitializedSealValid = sqliteInitializedSealValid(),
            mariadbInitializedMarkerPresent = initializedMarker.isFile,
        )

    private fun sqliteInitializedSealValid(): Boolean = runCatching {
        DatabaseDurableState.initializedCurrent(
            sqliteInitializedMarker.takeIf(File::isFile)?.readText(),
            sqliteIdentity(), sqliteGenerationUuid(),
        ) && sqliteDatadir.isDirectory && DatabaseSqliteControlPlane.DATABASES.all {
            DatabaseSqliteControlPlane.databaseFile(sqliteDatadir, it).isFile
        } && DatabaseSqliteControlPlane.databaseFile(
            sqliteDatadir, DatabaseSqliteControlPlane.META_DATABASE,
        ).isFile
    }.getOrDefault(false)

    private fun sqliteCleanGeneration(): Boolean = runCatching {
        DatabaseDurableState.cleanCurrent(
            sqliteCleanMarker.takeIf(File::isFile)?.readText(),
            sqliteIdentity(), sqliteGenerationUuid(),
        )
    }.getOrDefault(false)

    private fun sqliteMigrationsCurrent(): Boolean = sqliteInitializedSealValid() && runCatching {
        DatabaseDurableState.migrationsCurrent(
            sqliteMigrationMarker.takeIf(File::isFile)?.readText(),
            sqliteIdentity(), sqliteGenerationUuid(),
        )
    }.getOrDefault(false)

    private fun sqliteGenerationUuid(): String? = DatabaseDurableState.generationUuid(
        File(sqliteDatadir, ".pocketrealm-generation.json").takeIf(File::isFile)?.readText(),
    )

    private fun migrationSealedCount(mode: DatabaseDurableState.ProviderMode): Int? {
        val marker = if (mode == DatabaseDurableState.ProviderMode.SQLITE) sqliteMigrationMarker else migrationMarker
        val text = marker.takeIf(File::isFile)?.readText() ?: return null
        return runCatching { JSONObject(text).optInt("migrationCount", -1) }.getOrDefault(-1)
    }

    /** The interrupted sqlite INIT recovery (the MariaDB analog over the
     * sqlite datadir + markers; the transaction record's identity decides
     * which provider's recovery owns it). */
    private fun recoverIncompleteSqliteInitialization() {
        if (!databaseTransaction.isFile) return
        val raw = databaseTransaction.readText()
        if (runCatching { JSONObject(raw).optString("kind") }.getOrNull() != "INIT") return
        // The record spans the ENTIRE provision (seed/import/
        // carry/verify/seals). Every crash window in it recovers here:
        // KEEP a fully sealed datadir; DISCARD a record that predates any
        // move (the old datadir is intact); RESTORE the retired datadir
        // over a partial re-provision (NEVER re-translate the frozen
        // MariaDB datadir); QUARANTINE a partial fresh provision.
        val record = DatabaseDurableState.parseTransaction(raw, "INIT", sqliteIdentity())
            ?: error("DB-INIT: sqlite init transaction ownership is invalid")
        val retired = File(roots.databaseRoot, RETIRED_DATADIR_NAME)
        val action = DatabaseDurableState.provisioningRecovery(
            recordGenerationUuid = record.getString("generationUuid"),
            liveGenerationUuid = sqliteGenerationUuid(),
            // "All seals stamped" must include the
            // in-datadir migration marker's PRESENCE (not currency - a
            // legitimately corpus-stale marker must still KEEP), so the
            // KEEP_COMPLETED proxy can never bless a half-sealed datadir
            // regardless of future commit-block write orders.
            liveSealsValid = sqliteInitializedSealValid() && sqliteCleanGeneration() &&
                sqliteMigrationMarker.isFile,
            retiredPresent = retired.isDirectory,
        )
        when (action) {
            DatabaseDurableState.ProvisioningRecovery.KEEP_COMPLETED ->
                durableDelete(databaseTransaction)
            DatabaseDurableState.ProvisioningRecovery.DISCARD_RECORD -> {
                // The compound window - a kill during the
                // interrupted generation's seal writes followed by a kill
                // between the RESTORE move-back and its re-mint - leaves
                // the RESTORED datadir under the interrupted generation's
                // seals, and this branch's usual premise ("the old state
                // is intact") is then false. When the live datadir's
                // generation differs from the record's and its ownership
                // seals do not validate, re-mint for the LIVE generation
                // (the same recovery-time re-stamp rollbackToSnapshot
                // established); the intact case is a no-op.
                val liveGeneration = sqliteGenerationUuid()
                if (liveGeneration != null &&
                    liveGeneration != record.getString("generationUuid") &&
                    !sqliteInitializedSealValid()
                ) {
                    val now = System.currentTimeMillis()
                    atomicWrite(sqliteInitializedMarker, DatabaseDurableState.initializedSeal(
                        sqliteIdentity(), liveGeneration, now,
                    ))
                    atomicWrite(sqliteCleanMarker, DatabaseDurableState.cleanSeal(
                        sqliteIdentity(), liveGeneration, now,
                    ))
                    DatabaseDurability.syncDirectory(roots.databaseRoot)
                }
                durableDelete(databaseTransaction)
            }
            DatabaseDurableState.ProvisioningRecovery.RESTORE_RETIRED -> {
                requireDaemonDrained("interrupted sqlite provisioning recovery")
                if (!sqliteDatadir.listFiles().isNullOrEmpty()) {
                    val quarantine = File(
                        roots.databaseRoot,
                        "sqlite-init-quarantine-${record.getString("generationUuid")}-" +
                            System.currentTimeMillis(),
                    )
                    atomicMove(sqliteDatadir, quarantine)
                } else {
                    deleteTreeDurably(sqliteDatadir)
                }
                atomicMove(retired, sqliteDatadir)
                // A crash inside the new generation's commit block
                // left ITS partial seals in databaseRoot; re-mint the
                // RESTORED generation's ownership seals (the retired
                // datadir was initialized+clean when it was retired - the
                // entry gates required it; rollbackToSnapshot is the
                // MariaDB precedent for recovery-time re-stamping). The
                // migration marker rides INSIDE the restored datadir -
                // its count is the restored corpus's, and the policy
                // routes a stale count back to re-provision.
                val restoredGeneration = checkNotNull(sqliteGenerationUuid()) {
                    "DB-INIT: restored datadir has no generation marker"
                }
                val now = System.currentTimeMillis()
                atomicWrite(sqliteInitializedMarker, DatabaseDurableState.initializedSeal(
                    sqliteIdentity(), restoredGeneration, now,
                ))
                atomicWrite(sqliteCleanMarker, DatabaseDurableState.cleanSeal(
                    sqliteIdentity(), restoredGeneration, now,
                ))
                DatabaseDurability.syncDirectory(roots.databaseRoot)
                durableDelete(databaseTransaction)
            }
            DatabaseDurableState.ProvisioningRecovery.QUARANTINE_AND_RETRY -> {
                requireDaemonDrained("incomplete sqlite initialization recovery")
                val quarantine = File(
                    roots.databaseRoot,
                    "sqlite-init-quarantine-${record.getString("generationUuid")}-" +
                        System.currentTimeMillis(),
                )
                atomicMove(sqliteDatadir, quarantine)
                listOf(sqliteInitializedMarker, sqliteCleanMarker).forEach(::durableDelete)
                sqliteDatadir.mkdirs()
                DatabaseDurability.syncDirectory(roots.databaseRoot)
                durableDelete(databaseTransaction)
            }
        }
    }

    /** The durable provider-mode cache read by :realm/:world config
     * generation (ServerRuntimeFiles) - single source of truth. */
    private fun writeActiveProviderMarker(mode: DatabaseDurableState.ProviderMode) {
        val identity = if (mode == DatabaseDurableState.ProviderMode.SQLITE) sqliteIdentity() else providerIdentity
        val generation = if (mode == DatabaseDurableState.ProviderMode.SQLITE) {
            sqliteGenerationUuid()
        } else {
            requireGenerationUuid()
        }
        atomicWrite(
            activeProviderMarkerFile,
            DatabaseDurableState.activeProviderMarker(mode, identity.providerId, generation),
        )
    }

    // ---------------- mode-routed accessors (backup/restore family) ----

    private fun activeDatadir(): File =
        if (providerModeLocked() == DatabaseDurableState.ProviderMode.SQLITE) sqliteDatadir else datadir

    private fun activeIdentity(): DatabaseDurableState.Identity =
        if (providerModeLocked() == DatabaseDurableState.ProviderMode.SQLITE) sqliteIdentity() else providerIdentity

    private fun activeCleanMarker(): File =
        if (providerModeLocked() == DatabaseDurableState.ProviderMode.SQLITE) sqliteCleanMarker else cleanMarker

    private fun activeInitialized(): Boolean =
        if (providerModeLocked() == DatabaseDurableState.ProviderMode.SQLITE) sqliteInitializedSealValid() else initialized()

    private fun activeMigrationsCurrent(): Boolean =
        if (providerModeLocked() == DatabaseDurableState.ProviderMode.SQLITE) sqliteMigrationsCurrent() else migrationsCurrent()

    private fun activeCleanGeneration(): Boolean =
        if (providerModeLocked() == DatabaseDurableState.ProviderMode.SQLITE) sqliteCleanGeneration() else cleanGeneration()

    private fun activeRequireGenerationUuid(): String =
        if (providerModeLocked() == DatabaseDurableState.ProviderMode.SQLITE) {
            checkNotNull(sqliteGenerationUuid()) { "DB-GENERATION: durable sqlite generation marker is invalid" }
        } else {
            requireGenerationUuid()
        }

    /** Base tables of one source database (information_schema, batch TSV). */
    private fun sourceTables(database: String): List<String> {
        val sql = fixedSql(
            "translate-tables",
            "SELECT TABLE_NAME FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA='$database' AND TABLE_TYPE='BASE TABLE' " +
                "ORDER BY TABLE_NAME;",
        )
        val result = runClient("pocket_core", readSecrets().core, sql)
        check(result.ok) { "DB-TRANSLATE: table discovery failed: ${result.stderr.takeLast(600)}" }
        val discovered = result.stdout.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() }.toList()
        check(discovered.isNotEmpty()) { "DB-TRANSLATE: no base tables discovered in $database" }
        // A name outside SAFE_TABLE must refuse, not silently narrow the
        // exported slice (the column leg already fails loud).
        val rejected = discovered.filterNot { SAFE_TABLE.matches(it) }
        check(rejected.isEmpty()) {
            "DB-TRANSLATE: unsafe table name in $database: ${rejected.joinToString()}"
        }
        return discovered
    }

    /** One table's paged INTO OUTFILE export into staging. Returns the
     * row count, sha256, byte size, source column list, and primary key
     * the import leg verifies against (count cross-checked against
     * the source; pages ordered by PRIMARY KEY when present). */
    private fun exportUserStateTable(database: String, table: String): JSONObject {
        val columnsSql = fixedSql(
            "translate-columns",
            "SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA='$database' AND TABLE_NAME='$table' " +
                "ORDER BY ORDINAL_POSITION;",
        )
        val columnsResult = runClient("pocket_core", readSecrets().core, columnsSql)
        check(columnsResult.ok) { "DB-TRANSLATE: column discovery failed for $table" }
        val columns = columnsResult.stdout.lineSequence()
            .map { it.split('\t') }
            .filter { it.size == 2 }
            .map { (name, type) -> name to type.uppercase() }
            .toList()
        check(columns.isNotEmpty()) { "DB-TRANSLATE: no columns discovered for $database.$table" }
        check(columns.none { (name, _) -> !SAFE_TABLE.matches(name) }) {
            "DB-TRANSLATE: unsafe column name in $database.$table"
        }
        val primaryKeySql = fixedSql(
            "translate-primary-key",
            "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE " +
                "WHERE TABLE_SCHEMA='$database' AND TABLE_NAME='$table' " +
                "AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION;",
        )
        val primaryKeyResult = runClient("pocket_core", readSecrets().core, primaryKeySql)
        check(primaryKeyResult.ok) { "DB-TRANSLATE: primary key discovery failed for $table" }
        val pkDiscovered = primaryKeyResult.stdout.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() }.toList()
        // silent filtering here would order by a PK SUBSET (page-boundary
        // ties) - refuse loud, symmetric with the table/column legs
        val pkRejected = pkDiscovered.filterNot { SAFE_TABLE.matches(it) }
        check(pkRejected.isEmpty()) {
            "DB-TRANSLATE: unsafe primary key column in $database.$table: ${pkRejected.joinToString()}"
        }
        val primaryKey = pkDiscovered
        check(primaryKey.all { pk -> columns.any { (name, _) -> name == pk } }) {
            "DB-TRANSLATE: primary key column outside the column list for $database.$table"
        }
        val target = File(sqliteTranslationDir, "$database.$table.tsv")
        val pageDir = File(secureImportDir, "sqlite-translation").apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
        var rows = 0L
        var offset = 0
        FileOutputStream(target).use { output ->
            while (true) {
                val pageFile = File(pageDir, "$database.$table.$offset.tsv")
                pageFile.delete()
                val query = DatabaseUserStateBridge.outfileExportQuery(
                    database, table, columns, pageFile.absolutePath,
                    primaryKey, offset, EXPORT_PAGE_ROWS,
                )
                // INTO OUTFILE needs the FILE privilege: pocket_admin,
                // like the migration runner. The statement itself is
                // read-only against the datadir.
                val page = runClient("pocket_admin", readSecrets().admin, fixedSql("translate-export", query))
                check(page.ok) {
                    "DB-TRANSLATE: $database.$table page at offset $offset failed: " +
                        page.stderr.takeLast(600)
                }
                check(pageFile.isFile) {
                    "DB-TRANSLATE: $database.$table page at offset $offset produced no outfile"
                }
                val pageBytes = pageFile.readBytes()
                pageFile.delete()
                if (pageBytes.isEmpty()) break
                check(pageBytes.last() == '\n'.code.toByte()) {
                    "DB-TRANSLATE: $database.$table page at offset $offset is not row-terminated"
                }
                output.write(pageBytes)
                digest.update(pageBytes)
                val pageRows = pageBytes.count { it == '\n'.code.toByte() }
                check(pageRows <= EXPORT_PAGE_ROWS) {
                    "DB-TRANSLATE: $database.$table page at offset $offset exceeded its LIMIT"
                }
                rows += pageRows
                offset += EXPORT_PAGE_ROWS
                if (pageRows < EXPORT_PAGE_ROWS) break
            }
            output.fd.sync()
        }
        // Source-truth cross-check: the staged row count must equal the
        // source COUNT(*) - catches any page-loss/duplication class
        // independent of transport or ordering.
        val countResult = runClient(
            "pocket_core", readSecrets().core,
            fixedSql("translate-count", DatabaseUserStateBridge.countQuery(table)),
            database,
        )
        check(countResult.ok) { "DB-TRANSLATE: $database.$table COUNT failed" }
        val sourceCount = countResult.stdout.trim().toLongOrNull()
        check(sourceCount != null && sourceCount == rows) {
            "DB-TRANSLATE: $database.$table staged $rows rows but source has $sourceCount"
        }
        return JSONObject().put("rows", rows)
            .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
            .put("bytes", target.length())
            .put("columns", JSONArray(columns.map { it.first }))
            .put("primaryKey", JSONArray(primaryKey))
    }

    private fun sqliteTranslationPhase(): String? = if (!sqliteTranslationRecord.isFile) null else
        runCatching { JSONObject(sqliteTranslationRecord.readText()).optString("phase") }
            .getOrDefault("UNKNOWN").ifEmpty { "UNKNOWN" }

    fun snapshotAndRestoreTest(): JSONObject = synchronized(lock) {
        requireProvisioningIdle()
        requireStopped()
        check(activeInitialized() && activeMigrationsCurrent() && activeCleanGeneration()) {
            "DB-SNAPSHOT: clean current initialized datadir required"
        }
        val snapshot = snapshotStore.create(
            activeDatadir(), "restore-test-${System.currentTimeMillis()}", databaseStopped = true,
            compatibility = databaseCompatibility(activeRequireGenerationUuid()),
        )
        val original = File(roots.databaseRoot, "restore-original-${System.currentTimeMillis()}")
        atomicMove(activeDatadir(), original)
        return try {
            snapshotStore.restore(snapshot, activeDatadir(), databaseStopped = true)
            val started = start()
            stop()
            check(started.getBoolean("authenticated"))
            deleteTreeDurably(original)
            snapshotStore.retainNewest(2)
            JSONObject().put("ok", true).put("snapshotId", snapshot.id)
                .put("snapshotDigest", snapshot.digest).put("restoredAndQueried", true)
                .put("liveDatadirCopied", false)
        } catch (failure: Throwable) {
            if (state == State.RUNNING || state == State.STARTING || state == State.STOPPING ||
                daemonThread?.isAlive == true
            ) {
                cancelAndRequireDaemonDrained("snapshot restore test failure")
            }
            state = State.STOPPED
            requireDaemonDrained("snapshot restore test rollback")
            deleteTreeDurably(activeDatadir())
            atomicMove(original, activeDatadir())
            throw failure
        }
    }

    fun createNamedBackup(name: String): JSONObject = synchronized(lock) {
        requireProvisioningIdle()
        requireStopped()
        check(activeInitialized() && activeMigrationsCurrent() && activeCleanGeneration()) {
            "DB-SNAPSHOT: clean current stopped datadir required"
        }
        check(BACKUP_NAME.matches(name)) { "DB-SNAPSHOT: invalid backup name" }
        check(!restoreRecord.exists()) { "DB-SNAPSHOT: restore verification is pending" }
        val id = "manual-$name-${System.currentTimeMillis()}"
        val compatibility = databaseCompatibility(activeRequireGenerationUuid())
            .put("runtimeBuildId", "o09-cmangos-c096bada-nobots-v1")
            .put("databaseFamily", "cmangos-classic")
        val snapshot = snapshotStore.create(activeDatadir(), id, databaseStopped = true,
            compatibility = compatibility)
        JSONObject().put("ok", true).put("snapshotId", snapshot.id)
            .put("snapshotDigest", snapshot.digest).put("liveDatadirCopied", false)
    }

    fun listBackups(): JSONObject = synchronized(lock) {
        val values = JSONArray()
        snapshotStore.list().filter { it.id.startsWith("manual-") }.forEach { snapshot ->
            val manifest = JSONObject(snapshot.manifest.readText())
            values.put(JSONObject().put("snapshotId", snapshot.id)
                .put("snapshotDigest", snapshot.digest)
                .put("createdAt", manifest.getLong("createdAt")))
        }
        JSONObject().put("ok", true).put("backups", values)
    }

    fun beginRestore(snapshotId: String): JSONObject = synchronized(lock) {
        requireProvisioningIdle()
        requireStopped()
        check(snapshotId.startsWith("manual-") && snapshotId.length <= 128) {
            "DB-SNAPSHOT: only named backups may be restored"
        }
        check(activeInitialized() && activeMigrationsCurrent() && activeCleanGeneration()) {
            "DB-SNAPSHOT: clean current stopped datadir required"
        }
        check(!databaseTransaction.exists()) { "DB-TRANSACTION: init/migration transaction is pending" }
        check(!restoreRecord.exists()) { "DB-SNAPSHOT: another restore verification is pending" }
        if (providerModeLocked() == DatabaseDurableState.ProviderMode.MARIADB) {
            // A restore swaps in different datadir content even though
            // the generation uuid stays the same - invalidate any prior
            // user-state translation staging at attempt time (conservative:
            // a failed+rolled-back restore also discards it). The SQLite
            // provider's datadir carries no translation staging of its own.
            deleteSqliteTranslationStaging()
        }
        val snapshot = snapshotStore.load(snapshotId)
        val manifest = JSONObject(snapshot.manifest.readText())
        val compatibility = manifest.optJSONObject("compatibility") ?: JSONObject()
        requireCompatibleSnapshot(compatibility, activeRequireGenerationUuid())
        val required = activeDatadir().walkTopDown().filter { it.isFile }.sumOf { it.length() } + MIN_START_BYTES
        checkStorage(required)
        val token = UUID.randomUUID().toString()
        val candidate = File(roots.databaseRoot, "restore-candidate-$token")
        val quarantine = File(roots.databaseRoot, "restore-original-$token")
        atomicWrite(restoreRecord, JSONObject().put("schema", 2).put("token", token)
            .put("snapshotId", snapshot.id).put("snapshotDigest", snapshot.digest)
            .put("generationUuid", compatibility.getString("generationUuid"))
            .put("providerClosureSha256", activeIdentity().providerClosureSha256)
            .put("candidate", candidate.name).put("quarantine", quarantine.name)
            .put("phase", "PREPARING").toString())
        try {
            snapshotStore.restore(snapshot, candidate, databaseStopped = true)
            // Both providers' datadirs carry the generation marker at the
            // same relative name, so the candidate probe is mode-neutral.
            check(generationUuid(candidate) == compatibility.getString("generationUuid")) {
                "DB-SNAPSHOT: restored candidate generation mismatch"
            }
            atomicMove(activeDatadir(), quarantine)
            atomicMove(candidate, activeDatadir())
            atomicWrite(restoreRecord, JSONObject(restoreRecord.readText())
                .put("phase", "CANDIDATE_ACTIVE").toString())
            atomicWrite(activeCleanMarker(), DatabaseDurableState.cleanSeal(
                activeIdentity(), activeRequireGenerationUuid(), System.currentTimeMillis(),
                "restoreCandidate", snapshot.id,
            ))
            JSONObject().put("ok", true).put("restoreToken", token)
                .put("snapshotId", snapshot.id).put("snapshotDigest", snapshot.digest)
                .put("candidateActive", false).put("requiresWorldReady", true)
        } catch (failure: Throwable) {
            rollbackRestoreInternal(token)
            throw failure
        }
    }

    fun commitRestore(restoreToken: String): JSONObject = synchronized(lock) {
        requireStopped()
        val record = requireRestore(restoreToken)
        check(activeCleanGeneration()) { "DB-SNAPSHOT: restored candidate did not clean-stop" }
        check(activeRequireGenerationUuid() == record.getString("generationUuid")) {
            "DB-SNAPSHOT: restored candidate generation changed before commit"
        }
        val quarantine = File(roots.databaseRoot, record.getString("quarantine"))
        check(quarantine.isDirectory) { "DB-SNAPSHOT: pre-restore safety copy is missing" }
        atomicWrite(restoreRecord, JSONObject(record.toString()).put("phase", "COMMITTING").toString())
        finishRestoreCommit(requireRestore(restoreToken))
        JSONObject().put("ok", true).put("committed", true)
            .put("snapshotId", record.getString("snapshotId"))
    }

    fun rollbackRestore(restoreToken: String): JSONObject = synchronized(lock) {
        requireStopped()
        val record = requireRestore(restoreToken)
        if (DatabaseDurableState.restoreRecovery(record.toString()) ==
            DatabaseDurableState.RestoreRecovery.FINISH_COMMIT
        ) {
            finishRestoreCommit(record)
            JSONObject().put("ok", true).put("rolledBack", false).put("committed", true)
        } else {
            rollbackRestoreInternal(restoreToken)
            JSONObject().put("ok", true).put("rolledBack", true)
        }
    }

    fun rollbackPendingRestore(): JSONObject = synchronized(lock) {
        requireStopped()
        if (!restoreRecord.isFile) return@synchronized JSONObject().put("ok", true).put("pending", false)
        val record = requireRestore(JSONObject(restoreRecord.readText()).getString("token"))
        if (DatabaseDurableState.restoreRecovery(record.toString()) ==
            DatabaseDurableState.RestoreRecovery.FINISH_COMMIT
        ) {
            finishRestoreCommit(record)
            JSONObject().put("ok", true).put("pending", true)
                .put("rolledBack", false).put("committed", true)
        } else {
            rollbackRestoreInternal(record.getString("token"))
            JSONObject().put("ok", true).put("pending", true).put("rolledBack", true)
        }
    }

    fun storageFullTest(): JSONObject = synchronized(lock) {
        requireStopped()
        val before = activeDatadir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val refused = runCatching { checkStorage(MIN_START_BYTES, forcedAvailableBytes = 0) }.isFailure
        val after = activeDatadir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
        check(refused && before == after) { "DB-FULL: refusal was not side-effect free" }
        JSONObject().put("ok", true).put("classification", "DB-FULL")
            .put("refusedBeforeWrite", true).put("datadirBytesUnchanged", true)
    }

    fun close() {
        if (state == State.RUNNING || state == State.STARTING) {
            durableDelete(activeCleanMarker())
            if (providerModeLocked() == DatabaseDurableState.ProviderMode.MARIADB) {
                DatabaseNative.cancelActiveGlibcProgramNative()
            }
        }
    }

    private fun runClient(
        user: String,
        password: String,
        sql: File,
        database: String? = null,
        timeoutMs: Int = 30_000,
    ): DatabaseRunResult = runTool(
        executable = mariadb,
        argv0 = "mariadb",
        args = buildList {
            addAll(listOf("--no-defaults", "--protocol=socket", "--socket=${socket.absolutePath}",
                "--user=$user", "--batch", "--skip-column-names"))
            if (database != null) {
                check(DATABASE_NAME.matches(database)) { "invalid fixed database name" }
                add("--database=$database")
            }
        },
        environment = if (password.isEmpty()) emptyList() else listOf("MYSQL_PWD=$password"),
        stdin = sql,
        timeoutMs = timeoutMs,
    )

    private fun runTool(
        executable: File,
        argv0: String,
        args: List<String>,
        environment: List<String> = emptyList(),
        stdin: File? = null,
        timeoutMs: Int,
        trackDaemon: Boolean = false,
    ): DatabaseRunResult {
        check(executable.parentFile == nativeDir && executable.isFile) { "untrusted executable path" }
        check(args.size <= 48 && args.none { '\n' in it }) { "invalid fixed argument set" }
        check(environment.size <= 4 && environment.none { '\n' in it }) { "invalid fixed environment" }
        if (stdin != null) check(stdin.startsWith(roots.databaseRoot) && stdin.isFile) { "untrusted stdin path" }
        val common = arrayOf(
            nativeDir.absolutePath, executable.absolutePath, argv0,
            roots.databaseRoot.absolutePath, roots.databaseRoot.absolutePath,
            File(providerRoot, "lib").absolutePath, args.joinToString("\n"),
            environment.joinToString("\n"), stdin?.absolutePath.orEmpty(),
            timeoutMs.toString(), trackDaemon.toString(),
        )
        val raw = if (selectedAbi == "arm64-v8a") {
            DatabaseNative.runBionicProgramNative(
                common[0], common[1], common[2], common[3], common[4], common[5],
                common[6], common[7], common[8], timeoutMs, trackDaemon,
            )
        } else {
            DatabaseNative.runGlibcProgramNative(
                common[0], common[1], common[2], common[3], common[4], common[5],
                common[6], common[7], common[8], timeoutMs, trackDaemon,
            )
        }
        return DatabaseRunResult.parse(raw)
    }

    private fun serverBaseArgs(): List<String> = listOf(
        "--defaults-file=${configFile.absolutePath}",
        "--basedir=${providerRoot.absolutePath}",
        "--datadir=${datadir.absolutePath}",
        "--plugin-dir=${File(providerRoot, "plugin").absolutePath}",
        "--lc-messages-dir=${File(providerRoot, "share/mysql").absolutePath}",
        "--socket=${socket.absolutePath}",
        "--pid-file=${pidFile.absolutePath}",
        "--log-error=${errorLog.absolutePath}",
        "--skip-networking", "--skip-name-resolve",
    )

    private fun writeConfig() {
        runDir.mkdirs(); datadir.mkdirs()
        val text = DatabaseConfigPolicy.render(
            abi = selectedAbi,
            datadir = datadir.absolutePath,
            socket = socket.absolutePath,
            pidFile = pidFile.absolutePath,
            errorLog = errorLog.absolutePath,
            secureFileDirectory = File(roots.databaseRoot, "import").apply { mkdirs() }.absolutePath,
        )
        atomicWrite(configFile, text)
    }

    private fun stageProviderData() {
        // tools/stage_mariadb_runtime.py emits this fixed asset tree. Assets are
        // data/scripts only; all executable ELFs remain APK-managed in nativeLibraryDir.
        copyAssetTree("database/provider", providerRoot)
        val runtimeManifest = File(providerRoot, "runtime-manifest.json")
        check(runtimeManifest.isFile && sha256(runtimeManifest) == providerIdentity.providerClosureSha256) {
            "DB-LINK: staged provider manifest digest mismatch"
        }
        check(File(providerRoot, "bootstrap.sql").let {
            it.isFile && sha256(it) == providerIdentity.bootstrapSha256
        }) { "DB-LINK: staged bootstrap digest mismatch" }
        val manifest = JSONObject(runtimeManifest.readText())
        check(manifest.getString("provider") == providerId) {
            "DB-LINK: provider manifest does not match selected provider $providerId"
        }
        check(manifest.getString("abi") == selectedAbi) {
            "DB-LINK: provider manifest ABI does not match $selectedAbi"
        }
        val links = manifest.getJSONArray("links")
        val lib = File(providerRoot, "lib").apply { mkdirs() }
        for (index in 0 until links.length()) {
            val link = links.getJSONObject(index)
            val logical = link.getString("logical")
            val apkName = link.getString("apk_name")
            check(SAFE_FILE.matches(logical) && SAFE_FILE.matches(apkName)) { "unsafe provider link" }
            val target = File(nativeDir, apkName)
            check(target.isFile && sha256(target) == link.getString("sha256")) { "DB-LINK: $apkName hash mismatch" }
            val destination = File(lib, logical)
            destination.delete()
            check(runCatching { java.nio.file.Files.createSymbolicLink(destination.toPath(), target.toPath()) }.isSuccess) {
                "DB-LINK: failed to create $logical"
            }
        }
        val plugins = manifest.optJSONArray("plugins")
        if (plugins != null) {
            val pluginDir = File(providerRoot, "plugin").apply { mkdirs() }
            for (index in 0 until plugins.length()) {
                val item = plugins.getJSONObject(index)
                val logical = item.getString("logical")
                val apkName = item.getString("apk_name")
                check(SAFE_FILE.matches(logical) && SAFE_FILE.matches(apkName)) { "unsafe provider plugin" }
                val target = File(nativeDir, apkName)
                check(target.isFile && sha256(target) == item.getString("sha256")) {
                    "DB-LINK: $apkName plugin hash mismatch"
                }
                val destination = File(pluginDir, logical)
                destination.delete()
                check(runCatching { java.nio.file.Files.createSymbolicLink(destination.toPath(), target.toPath()) }.isSuccess)
            }
        }
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                val temp = File(target.parentFile, ".${target.name}.tmp")
                temp.outputStream().use { input.copyTo(it) }
                check(temp.renameTo(target) || (target.delete() && temp.renameTo(target)))
            }
            return
        }
        target.mkdirs()
        children.forEach { child -> copyAssetTree("$assetPath/$child", File(target, child)) }
    }

    private fun fixedSql(name: String, sql: String): File = File(runDir, "$name.sql").also {
        atomicWrite(it, sql.trimEnd() + "\n")
    }

    private fun createLedger() {
        val sql = fixedSql("create-ledger", """
            CREATE TABLE IF NOT EXISTS pocketrealm_meta.migration_ledger (
              migration_id VARCHAR(191) PRIMARY KEY,
              component VARCHAR(64) NOT NULL,
              source_commit CHAR(40) NOT NULL,
              target_commit CHAR(40) NOT NULL,
              sql_sha256 CHAR(64) NOT NULL,
              started_at BIGINT NOT NULL,
              finished_at BIGINT NULL,
              status ENUM('PENDING','APPLIED','ROLLED_BACK','FAILED') NOT NULL,
              pre_snapshot_id VARCHAR(96) NOT NULL,
              app_build_id VARCHAR(96) NOT NULL,
              result_digest CHAR(64) NULL
            ) ENGINE=InnoDB;
        """.trimIndent())
        val result = runClient("pocket_admin", readSecrets().admin, sql)
        check(result.ok) { "DB-REVISION: cannot create ledger: ${result.stderr.takeLast(800)}" }
    }

    private fun ledgerStatus(id: String): Pair<String, String>? {
        val sql = fixedSql("ledger-status", "SELECT status,sql_sha256 FROM pocketrealm_meta.migration_ledger WHERE migration_id='$id';")
        val result = runClient("pocket_admin", readSecrets().admin, sql)
        check(result.ok) { "DB-REVISION: ledger read failed" }
        val values = result.stdout.trim().split(Regex("\\s+"))
        return if (values.size >= 2) values[0] to values[1] else null
    }

    private fun ledgerPending(entry: JSONObject, snapshotId: String, manifest: JSONObject) {
        val id = entry.getString("migration_id")
        val component = entry.getString("component")
        val sourceKey = when {
            component.startsWith("playerbot") -> "playerbots"
            component == "world" && entry.getString("source_path").startsWith("native/classic-db") -> "classic_db"
            else -> "cmangos"
        }
        val commits = manifest.getJSONObject("source_commits")
        val sourceCommit = commits.getString(sourceKey)
        val targetCommit = commits.getString("cmangos")
        val buildId = manifest.getString("app_build_id")
        val sql = fixedSql("ledger-pending", """
            INSERT INTO pocketrealm_meta.migration_ledger
              (migration_id,component,source_commit,target_commit,sql_sha256,started_at,status,pre_snapshot_id,app_build_id)
            VALUES ('$id','$component','$sourceCommit','$targetCommit','${entry.getString("sql_sha256")}',${System.currentTimeMillis()},'PENDING','$snapshotId','$buildId');
        """.trimIndent())
        val result = runClient("pocket_admin", readSecrets().admin, sql)
        check(result.ok) { "DB-REVISION: ledger PENDING write failed for $id" }
    }

    private fun ledgerFinish(id: String, status: String, digest: String) {
        check(status == "APPLIED" || status == "FAILED")
        val sql = fixedSql("ledger-finish", "UPDATE pocketrealm_meta.migration_ledger SET status='$status',finished_at=${System.currentTimeMillis()},result_digest='$digest' WHERE migration_id='$id';")
        val result = runClient("pocket_admin", readSecrets().admin, sql)
        check(result.ok) { "DB-REVISION: ledger final write failed for $id" }
    }

    private fun materializeMigration(entry: JSONObject): File {
        val assetPath = entry.getString("asset")
        check(assetPath.startsWith("database/migrations/") && assetPath.endsWith(".sqlz"))
        val output = File(roots.databaseRoot, "import/${entry.getString("migration_id")}.sql")
        output.parentFile?.mkdirs()
        val compressedDigest = MessageDigest.getInstance("SHA-256")
        val sqlDigest = MessageDigest.getInstance("SHA-256")
        var sqlSize = 0L
        context.assets.open(assetPath).use { raw ->
            DigestInputStream(raw, compressedDigest).use { checkedRaw ->
                GZIPInputStream(checkedRaw).use { zipped ->
                    FileOutputStream(output).use { destination ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zipped.read(buffer)
                            if (count < 0) break
                            destination.write(buffer, 0, count); sqlDigest.update(buffer, 0, count); sqlSize += count
                        }
                        destination.fd.sync()
                    }
                }
            }
        }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        check(hex(compressedDigest.digest()) == entry.getString("asset_sha256")) { "DB-REVISION: asset hash mismatch" }
        check(hex(sqlDigest.digest()) == entry.getString("sql_sha256") && sqlSize == entry.getLong("sql_size")) {
            "DB-REVISION: SQL hash/size mismatch"
        }
        return output
    }

    private fun verifyExpectedRevisions(manifest: JSONObject): JSONObject {
        val expected = manifest.getJSONObject("expected_revisions")
        val checks = listOf(
            Triple("realm", "classicrealmd", "realmd_db_version"),
            Triple("characters", "classiccharacters", "character_db_version"),
            Triple("logs", "classiclogs", "logs_db_version"),
            Triple("world", "classicmangos", "db_version"),
        )
        for ((component, database, table) in checks) {
            val column = expected.getString(component)
            if (!revisionColumnExists(database, table, column)) {
                return JSONObject().put("ok", false).put("detail", "$component missing $column")
            }
        }
        return JSONObject().put("ok", true).put("detail", "all pinned revisions present")
    }

    private fun revisionColumnExists(database: String, table: String, column: String): Boolean {
        check(DATABASE_NAME.matches(database) && SAFE_FILE.matches(table) && SAFE_FILE.matches(column))
        val sql = fixedSql("revision-check", "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$database' AND TABLE_NAME='$table' AND COLUMN_NAME='$column';")
        val result = runClient("pocket_admin", readSecrets().admin, sql)
        return result.ok && result.stdout.trim() == "1"
    }

    private fun rollbackToSnapshot(snapshot: DatabaseSnapshotStore.Snapshot) {
        if (state == State.RUNNING || state == State.STARTING || state == State.STOPPING) {
            cancelAndRequireDaemonDrained("migration rollback")
        }
        state = State.STOPPED
        requireDaemonDrained("migration rollback")
        socket.delete(); pidFile.delete(); durableDelete(cleanMarker)
        if (datadir.exists()) deleteTreeDurably(datadir)
        snapshotStore.restore(snapshot, datadir, databaseStopped = true)
        atomicWrite(cleanMarker, DatabaseDurableState.cleanSeal(
            providerIdentity, requireGenerationUuid(), System.currentTimeMillis(),
            "restoredSnapshot", snapshot.id,
        ))
    }

    private fun requireRestore(token: String): JSONObject {
        check(restoreRecord.isFile) { "DB-SNAPSHOT: no restore verification is pending" }
        val record = JSONObject(restoreRecord.readText())
        check(record.length() == 9 && record.getInt("schema") == 2)
        check(UUID.fromString(token).toString() == token && record.getString("token") == token) {
            "DB-SNAPSHOT: restore token mismatch"
        }
        check(record.getString("phase") in setOf("PREPARING", "CANDIDATE_ACTIVE", "COMMITTING"))
        check(SHA256.matches(record.getString("snapshotDigest")))
        check(record.getString("providerClosureSha256") == activeIdentity().providerClosureSha256)
        check(DatabaseDurableState.generationUuid(
            DatabaseDurableState.generationMarker(record.getString("generationUuid")),
        ) == record.getString("generationUuid"))
        listOf("candidate", "quarantine").forEach { key ->
            val name = record.getString(key)
            check(SAFE_TRANSACTION_PATH.matches(name) && File(roots.databaseRoot, name).parentFile == roots.databaseRoot)
        }
        if (record.getString("phase") != "COMMITTING") {
            val snapshot = snapshotStore.load(record.getString("snapshotId"))
            check(snapshot.digest == record.getString("snapshotDigest")) {
                "DB-SNAPSHOT: restore transaction snapshot digest drift"
            }
        }
        return record
    }

    private fun rollbackRestoreInternal(token: String) {
        val record = requireRestore(token)
        check(record.getString("phase") != "COMMITTING") {
            "DB-SNAPSHOT: committing restore cannot roll back"
        }
        requireDaemonDrained("restore rollback")
        val candidate = File(roots.databaseRoot, record.getString("candidate"))
        val quarantine = File(roots.databaseRoot, record.getString("quarantine"))
        if (quarantine.isDirectory) {
            deleteTreeDurably(activeDatadir())
            atomicMove(quarantine, activeDatadir())
        }
        deleteTreeDurably(candidate)
        check(activeDatadir().isDirectory) { "DB-SNAPSHOT: rollback has no active datadir" }
        atomicWrite(activeCleanMarker(), DatabaseDurableState.cleanSeal(
            activeIdentity(), activeRequireGenerationUuid(), System.currentTimeMillis(),
            "restoreRolledBack", record.getString("snapshotId"),
        ))
        durableDelete(restoreRecord)
    }

    private fun finishRestoreCommit(record: JSONObject) {
        check(record.getString("phase") == "COMMITTING")
        requireDaemonDrained("restore commit")
        check(activeRequireGenerationUuid() == record.getString("generationUuid") && activeCleanGeneration()) {
            "DB-SNAPSHOT: committing candidate generation is not verified and clean"
        }
        deleteTreeDurably(File(roots.databaseRoot, record.getString("quarantine")))
        deleteTreeDurably(File(roots.databaseRoot, record.getString("candidate")))
        durableDelete(restoreRecord)
    }

    private fun recoverIncompleteInitialization() {
        if (!databaseTransaction.isFile) return
        val raw = databaseTransaction.readText()
        if (runCatching { JSONObject(raw).optString("kind") }.getOrNull() != "INIT") return
        // A pending INIT written by the SQLite provider (seed replay /
        // import interrupted) recovers against the SQLITE datadir even
        // while the MariaDB datadir is the active provider - the record's
        // identity field decides ownership (window crash window: mode is
        // still MARIADB with a half-built sqlite datadir on disk).
        if (runCatching { JSONObject(raw).getString("provider") }.getOrNull() ==
            DatabaseRuntimeContract.SQLITE_PROVIDER_ID
        ) {
            recoverIncompleteSqliteInitialization()
            return
        }
        val action = DatabaseDurableState.initRecovery(
            recordText = raw,
            datadirGenerationText = generationMarker.takeIf(File::isFile)?.readText(),
            datadirEmpty = datadir.listFiles().isNullOrEmpty(),
            identity = providerIdentity,
            initializedCurrent = initialized(),
            cleanCurrent = cleanGeneration(),
        )
        when (action) {
            DatabaseDurableState.InitRecovery.NONE -> Unit
            DatabaseDurableState.InitRecovery.KEEP_COMPLETED -> durableDelete(databaseTransaction)
            DatabaseDurableState.InitRecovery.QUARANTINE_AND_RETRY -> {
                requireDaemonDrained("incomplete initialization recovery")
                val generation = DatabaseDurableState.parseTransaction(raw, "INIT", providerIdentity)
                    ?.getString("generationUuid") ?: error("DB-INIT: init transaction ownership is invalid")
                if (!datadir.listFiles().isNullOrEmpty()) {
                    val quarantine = File(
                        roots.databaseRoot,
                        "init-quarantine-$generation-${System.currentTimeMillis()}",
                    )
                    atomicMove(datadir, quarantine)
                } else {
                    deleteTreeDurably(datadir)
                }
                listOf(initializedMarker, cleanMarker, secretFile).forEach(::durableDelete)
                datadir.mkdirs()
                DatabaseDurability.syncDirectory(roots.databaseRoot)
                durableDelete(databaseTransaction)
            }
            DatabaseDurableState.InitRecovery.FAIL_CLOSED -> error(
                "DB-INIT: nonempty datadir is not owned by the exact durable init transaction",
            )
        }
    }

    private fun recoverPendingMigrationTransaction(): Boolean {
        if (!databaseTransaction.isFile) return false
        val raw = databaseTransaction.readText()
        val action = DatabaseDurableState.migrationRecovery(
            raw, generationMarker.takeIf(File::isFile)?.readText(), providerIdentity,
        )
        check(action == DatabaseDurableState.MigrationRecovery.RESTORE_AND_RETRY) {
            "DB-TRANSACTION: pending transaction cannot be proven as this generation's migration"
        }
        val record = checkNotNull(
            DatabaseDurableState.parseCompatibleMigrationTransaction(raw, providerIdentity),
        )
        val snapshot = snapshotStore.load(record.getString("snapshotId"))
        check(snapshot.digest == record.getString("snapshotDigest")) {
            "DB-TRANSACTION: pre-migration snapshot manifest digest mismatch"
        }
        val compatibility = JSONObject(snapshot.manifest.readText()).getJSONObject("compatibility")
        check(DatabaseDurableState.migrationSnapshotCompatible(
            raw, compatibility, providerIdentity, record.getString("generationUuid"),
        )) { "DB-REVISION: pre-migration snapshot historical compatibility mismatch" }
        requireDaemonDrained("interrupted migration recovery")
        rollbackToSnapshot(snapshot)
        durableDelete(databaseTransaction)
        return true
    }

    private fun updateDatabaseTransactionPhase(kind: String, phase: String) {
        check(databaseTransaction.isFile) { "DB-TRANSACTION: transaction record missing" }
        val raw = databaseTransaction.readText()
        check(DatabaseDurableState.parseTransaction(raw, kind, transactionRecordIdentity(raw)) != null) {
            "DB-TRANSACTION: transaction identity drift"
        }
        atomicWrite(databaseTransaction, DatabaseDurableState.withTransactionPhase(raw, kind, phase))
    }

    /** The INIT/MIGRATION transaction record carries its provider's
     * identity flat in the JSON; the sqlite provider's transactions (seed
     * replay, import) must validate against the SQLITE identity, not the
     * MariaDB one (the record is the shared file's only
     * disambiguator during the window). */
    private fun transactionRecordIdentity(raw: String): DatabaseDurableState.Identity {
        val provider = runCatching { JSONObject(raw).getString("provider") }.getOrNull()
        return if (provider == DatabaseRuntimeContract.SQLITE_PROVIDER_ID) sqliteIdentity() else providerIdentity
    }

    private fun databaseCompatibility(generationUuid: String): JSONObject {
        // Pin the ACTIVE provider's identity - a sqlite-datadir
        // snapshot must not claim MariaDB compatibility.
        val identity = activeIdentity()
        return JSONObject()
            .put("provider", identity.providerId)
            .put("providerClosureSha256", identity.providerClosureSha256)
            .put("bootstrapSha256", identity.bootstrapSha256)
            .put("migrationManifestSha256", identity.migrationManifestSha256)
            .put("migrationCount", identity.migrationCount)
            .put("generationUuid", generationUuid)
    }

    private fun databaseTransactionKind(): String? = if (!databaseTransaction.isFile) null else
        runCatching { DatabaseDurableState.transactionKind(databaseTransaction.readText()) }
            .getOrDefault("UNKNOWN")

    private fun requireCompatibleSnapshot(value: JSONObject, expectedGenerationUuid: String) {
        val identity = activeIdentity()
        check(value.optString("provider") == identity.providerId &&
            value.optString("providerClosureSha256") == identity.providerClosureSha256 &&
            value.optString("bootstrapSha256") == identity.bootstrapSha256 &&
            value.optString("migrationManifestSha256") == identity.migrationManifestSha256 &&
            value.optInt("migrationCount", -1) == identity.migrationCount &&
            value.optString("generationUuid") == expectedGenerationUuid) {
            "DB-REVISION: snapshot provider closure or generation is incompatible"
        }
    }

    private fun loadAndVerifyProviderIdentity(): DatabaseDurableState.Identity {
        check(mariadbd.isFile && mariadb.isFile) { "DB-LINK: MariaDB executables are missing" }
        val manifestText = context.assets.open(RUNTIME_MANIFEST_ASSET)
            .bufferedReader().use { it.readText() }
        val manifest = JSONObject(manifestText)
        check(manifest.getInt("schema") == 1 && manifest.getString("provider") == providerId &&
            manifest.getString("abi") == selectedAbi) { "DB-LINK: provider runtime manifest identity mismatch" }
        check(manifest.getString("bootstrap_sha256") == expectedBootstrapSha256) {
            "DB-LINK: provider bootstrap identity mismatch"
        }
        val seenApkNames = mutableSetOf<String>()
        fun verifyRecord(record: JSONObject, expectedApkName: String? = null) {
            val apkName = record.getString("apk_name")
            val expectedHash = record.getString("sha256")
            check(SAFE_FILE.matches(apkName) && SHA256.matches(expectedHash) && seenApkNames.add(apkName)) {
                "DB-LINK: invalid or duplicate native closure entry"
            }
            if (expectedApkName != null) check(apkName == expectedApkName)
            val target = File(nativeDir, apkName)
            check(target.isFile && target.length() == record.optLong("size", target.length()) &&
                sha256(target) == expectedHash) { "DB-LINK: native closure hash mismatch for $apkName" }
        }
        val executables = manifest.getJSONObject("executables")
        check(executables.length() == 2)
        verifyRecord(executables.getJSONObject("mariadbd"), mariadbd.name)
        verifyRecord(executables.getJSONObject("mariadb"), mariadb.name)
        listOf("links", "plugins").forEach { key ->
            val entries = manifest.optJSONArray(key) ?: JSONArray()
            for (index in 0 until entries.length()) verifyRecord(entries.getJSONObject(index))
        }
        return DatabaseDurableState.Identity(
            providerId = providerId,
            providerClosureSha256 = sha256Text(manifestText),
            bootstrapSha256 = expectedBootstrapSha256,
            migrationManifestSha256 = expectedMigrationManifestSha256,
            migrationCount = expectedMigrationCount,
        )
    }

    private fun generationUuid(root: File): String? = DatabaseDurableState.generationUuid(
        File(root, ".pocketrealm-generation.json").takeIf(File::isFile)?.readText(),
    )

    private fun requireGenerationUuid(): String = checkNotNull(generationUuid(datadir)) {
        "DB-GENERATION: durable datadir generation marker is invalid"
    }

    private fun nativeProcessGroupDrained(): Boolean =
        runCatching { WineSpikeNative.isTrackedBionicProcessGroupDrainedNative() }.getOrDefault(false)

    private fun pidProcessExists(): Boolean {
        val pid = pidFile.takeIf(File::isFile)?.readText()?.trim()?.toIntOrNull() ?: return false
        return pid > 1 && File("/proc/$pid").exists()
    }

    private fun requireDaemonDrained(operation: String) {
        check(DatabaseMutationGate.permits(
            lifecycleStopped = state == State.STOPPED || state == State.FAILED,
            runnerThreadAlive = daemonThread?.isAlive == true,
            nativeProcessGroupDrained = nativeProcessGroupDrained(),
            pidProcessExists = pidProcessExists(),
        )) { "DB-DRAIN: cannot prove MariaDB process tree stopped before $operation" }
    }

    private fun cancelAndRequireDaemonDrained(operation: String) {
        DatabaseNative.cancelActiveGlibcProgramNative()
        daemonThread?.join(10_000)
        state = State.FAILED
        requireDaemonDrained(operation)
    }

    private fun atomicMove(source: File, target: File) {
        check(source.parentFile == roots.databaseRoot && target.parentFile == roots.databaseRoot) {
            "DB-DURABILITY: database directory move escaped ownership root"
        }
        DatabaseDurability.atomicMove(source, target)
    }

    private fun deleteTreeDurably(target: File) {
        check(target.parentFile == roots.databaseRoot) {
            "DB-DURABILITY: recursive delete escaped database ownership root"
        }
        if (!target.exists()) return
        check(target.deleteRecursively() && !target.exists()) {
            "DB-DURABILITY: could not retire ${target.name}"
        }
        DatabaseDurability.syncDirectory(roots.databaseRoot)
    }

    private fun durableDelete(target: File) = DatabaseDurability.delete(target)

    private fun providerReady(): Boolean = runCatching { providerIdentity }.isSuccess
    private fun requireProvider() = check(providerReady()) { "DB-LINK: pinned MariaDB provider is not staged" }
    private fun initialized(): Boolean = runCatching {
        val mysqlDir = File(datadir, "mysql")
        DatabaseDurableState.initializedCurrent(
            initializedMarker.takeIf(File::isFile)?.readText(), providerIdentity, generationUuid(datadir),
        ) && datadir.isDirectory && mysqlDir.isDirectory && mysqlDir.list().orEmpty().any {
            it.startsWith("global_priv.") || it.startsWith("user.")
        } && File(datadir, "mariadb_upgrade_info").takeIf(File::isFile)?.readText()?.trim() ==
            "$providerVersion-MariaDB" &&
            DatabaseGenerationSeal.validSecrets(secretFile.takeIf(File::isFile)?.readText())
    }.getOrDefault(false)

    private fun cleanGeneration(): Boolean = runCatching {
        DatabaseDurableState.cleanCurrent(
            cleanMarker.takeIf(File::isFile)?.readText(), providerIdentity, generationUuid(datadir),
        )
    }.getOrDefault(false)
    private fun migrationsCurrent(): Boolean = initialized() && runCatching {
        DatabaseDurableState.migrationsCurrent(
            migrationMarker.takeIf(File::isFile)?.readText(), providerIdentity, generationUuid(datadir),
        )
    }.getOrDefault(false)
    private fun requireStopped() {
        requireProvisioningIdle()
        check(state == State.STOPPED || state == State.FAILED) {
            "operation requires stopped database, state=$state"
        }
        requireDaemonDrained("stopped-state mutation")
    }

    private fun checkStorage(required: Long, forcedAvailableBytes: Long? = null) {
        val available = forcedAvailableBytes ?: roots.databaseRoot.usableSpace
        check(available >= required) { "DB-FULL: need=$required available=$available" }
    }

    private fun readSecrets(): Secrets {
        check(secretFile.isFile) { "DB-INIT: credential record missing" }
        val text = secretFile.readText()
        check(DatabaseGenerationSeal.validSecrets(text)) { "DB-INIT: credential record is invalid" }
        val json = JSONObject(text)
        return Secrets(json.getString("admin"), json.getString("core"))
    }

    private fun createSecrets(): Secrets {
        val random = SecureRandom()
        fun next(): String = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val secrets = Secrets(next(), next())
        atomicWrite(secretFile, JSONObject().put("schema", 1)
            .put("admin", secrets.admin).put("core", secrets.core).toString())
        secretFile.setReadable(false, false); secretFile.setWritable(false, false)
        secretFile.setReadable(true, true); secretFile.setWritable(true, true)
        return secrets
    }

    private fun atomicWrite(target: File, value: String) = DatabaseDurability.atomicWrite(target, value)

    private fun errorLogTail(fromByte: Long = 0): String {
        if (!errorLog.isFile) return ""
        val length = errorLog.length()
        if (fromByte >= length) return ""
        val start = maxOf(fromByte, length - MAX_DIAGNOSTIC)
        val bytes = ByteArray((length - start).toInt())
        val count = RandomAccessFile(errorLog, "r").use { input ->
            input.seek(start)
            var total = 0
            while (total < bytes.size) {
                val read = input.read(bytes, total, bytes.size - total)
                if (read <= 0) break
                total += read
            }
            total
        }
        return bytes.decodeToString(endIndex = count)
    }

    private fun sha256(file: File): String = com.pocketrealm.fs.FileDigests.sha256(file)
    private fun sha256Asset(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun sha256Text(value: String): String = com.pocketrealm.fs.FileDigests.sha256(value)

    companion object {
        private const val TAG = "DatabaseEngine"
        private const val MIN_INITIALIZE_BYTES = 768L * 1024 * 1024
        private const val MIN_START_BYTES = 128L * 1024 * 1024
        private const val MIN_MIGRATION_BYTES = 1536L * 1024 * 1024
        private const val MIN_TRANSLATE_BYTES = 512L * 1024 * 1024
        // The sqlite seed replay: raw transcripts ~119 MiB become ~119 MiB
        // of databases plus a same-order WAL during the single-transaction
        // replay, plus import/carry headroom.
        private const val MIN_SQLITE_SEED_BYTES = 768L * 1024 * 1024
        private const val EXPORT_PAGE_ROWS = 500
        private val SQLITE_TRANSLATION_DATABASES = listOf("classicrealmd", "classiccharacters")
        /** The re-provision retire name (FIXED - recovery restores by
         * convention). */
        private const val RETIRED_DATADIR_NAME = "sqlite-retired"
        /** The bounded stop-drain window - sized well above the
         * :world 250 ms kill-retire delay, well inside the supervisor's
         * stop budget. */
        private const val SQLITE_DRAIN_TIMEOUT_SECONDS = 10L
        private val SAFE_TABLE = Regex("[A-Za-z0-9_]{1,64}")
        private const val MAX_DIAGNOSTIC = 16 * 1024
        private const val BOOTSTRAP_ASSET = "database/provider/bootstrap.sql"
        private const val RUNTIME_MANIFEST_ASSET = "database/provider/runtime-manifest.json"
        private const val SQLITE_IDENTITY_ASSET = "database/provider-sqlite/BUILD_PROVENANCE.json"
        private const val MIGRATION_MANIFEST = "database/migrations/manifest.json"
        private val BACKUP_NAME = Regex("[A-Za-z0-9._-]{1,32}")
        private val MIGRATION_ID = Regex("[A-Za-z0-9._-]{1,191}")
        private val DATABASE_NAME = Regex("[a-z][a-z0-9_]{0,63}")
        private val SAFE_FILE = Regex("[A-Za-z0-9._+-]{1,255}")
        private val SAFE_TRANSACTION_PATH = Regex("(?:restore-candidate|restore-original)-[0-9a-f-]{36}")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/** The staged SQLite provenance asset, parsed and verified: the
 * verified provider identity plus the artifact/seed pins the replay
 * cross-checks against. */
internal data class SqliteSeedPin(
    val sha256: String,
    val size: Long,
    val gzipSha256: String,
    val gzipSize: Long,
)

internal data class SqliteProvenance(
    val identity: DatabaseDurableState.Identity,
    val artifacts: Map<String, String>,
    val seeds: Map<String, SqliteSeedPin>,
)
