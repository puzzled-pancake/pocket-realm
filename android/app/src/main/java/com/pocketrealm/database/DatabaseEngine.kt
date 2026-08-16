package com.pocketrealm.database

import android.content.Context
import android.os.Build
import android.os.Process
import com.pocketrealm.storage.StorageRoots
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.wine.WineSpikeNative
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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
    private val expectedBootstrapSha256 by lazy { sha256Asset(BOOTSTRAP_ASSET) }
    private val expectedMigrationManifest by lazy {
        context.assets.open(MIGRATION_MANIFEST).bufferedReader().use { it.readText() }
    }
    private val expectedMigrationManifestSha256 by lazy { sha256Text(expectedMigrationManifest) }
    private val expectedMigrationCount by lazy {
        JSONObject(expectedMigrationManifest).getJSONArray("entries").length()
    }
    private val providerIdentity by lazy { loadAndVerifyProviderIdentity() }
    @Volatile private var state = State.STOPPED
    @Volatile private var daemonResult: DatabaseRunResult? = null
    @Volatile private var daemonThread: Thread? = null
    @Volatile private var projectedRealmEndpoint: String? = null

    private enum class State { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
    private data class Secrets(val admin: String, val core: String)

    fun status(): JSONObject = synchronized(lock) {
        val transactionKind = databaseTransactionKind()
        val ownership = runCatching {
            DatabaseDurableState.ownershipCompatibility(
                initializedMarker.takeIf(File::isFile)?.readText(),
                providerIdentity,
                generationUuid(datadir),
            )
        }.getOrDefault(
            if (initializedMarker.isFile) DatabaseDurableState.OwnershipCompatibility.INVALID
            else DatabaseDurableState.OwnershipCompatibility.MISSING,
        )
        return JSONObject().put("ok", true).put("state", state.name)
            .put("providerReady", providerReady())
            .put("initialized", initialized())
            .put("migrationsCurrent", migrationsCurrent())
            .put("socketExists", socket.exists())
            .put("tcpDisabled", true)
            .put("cleanMarker", cleanGeneration())
            .put("restorePending", restoreRecord.isFile)
            .put("databaseTransactionPending", databaseTransaction.isFile)
            .put("databaseTransactionKind", transactionKind ?: JSONObject.NULL)
            .put("compatibilityMismatch", when (ownership) {
                DatabaseDurableState.OwnershipCompatibility.PROVIDER_MISMATCH -> "PROVIDER_IDENTITY"
                DatabaseDurableState.OwnershipCompatibility.GENERATION_MISMATCH -> "GENERATION"
                DatabaseDurableState.OwnershipCompatibility.INVALID -> "INVALID_DURABLE_STATE"
                else -> JSONObject.NULL
            })
            .put("datadir", datadir.absolutePath)
            .put("pid", Process.myPid())
            .put("lastExit", daemonResult?.exitCode ?: JSONObject.NULL)
    }

    fun initialize(): JSONObject = synchronized(lock) {
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
            // bootstrap. Preserve that contract even though O08 feeds the pinned
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

    fun start(): JSONObject = synchronized(lock) {
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

    fun queryHealth(): JSONObject = synchronized(lock) {
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

    /** Fixed, owner-gated projection consumed by realmd; arbitrary SQL never crosses Binder. */
    fun projectRealmEndpoint(address: String, worldPort: Int): JSONObject = synchronized(lock) {
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

    fun stop(): JSONObject = synchronized(lock) {
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
        JSONObject().put("ok", true).put("state", state.name).put("cleanMarker", true)
    }

    fun killForTest(): JSONObject = synchronized(lock) {
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

    fun recover(): JSONObject = synchronized(lock) {
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

    fun applyPinnedMigrations(): JSONObject = synchronized(lock) {
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

    fun snapshotAndRestoreTest(): JSONObject = synchronized(lock) {
        requireStopped()
        check(initialized() && migrationsCurrent() && cleanGeneration()) {
            "DB-SNAPSHOT: clean current initialized datadir required"
        }
        val snapshot = snapshotStore.create(
            datadir, "restore-test-${System.currentTimeMillis()}", databaseStopped = true,
            compatibility = databaseCompatibility(requireGenerationUuid()),
        )
        val original = File(roots.databaseRoot, "restore-original-${System.currentTimeMillis()}")
        atomicMove(datadir, original)
        return try {
            snapshotStore.restore(snapshot, datadir, databaseStopped = true)
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
            deleteTreeDurably(datadir)
            atomicMove(original, datadir)
            throw failure
        }
    }

    fun createNamedBackup(name: String): JSONObject = synchronized(lock) {
        requireStopped()
        check(initialized() && migrationsCurrent() && cleanGeneration()) {
            "DB-SNAPSHOT: clean current stopped datadir required"
        }
        check(BACKUP_NAME.matches(name)) { "DB-SNAPSHOT: invalid backup name" }
        check(!restoreRecord.exists()) { "DB-SNAPSHOT: restore verification is pending" }
        val id = "manual-$name-${System.currentTimeMillis()}"
        val compatibility = databaseCompatibility(requireGenerationUuid())
            .put("runtimeBuildId", "o09-cmangos-c096bada-nobots-v1")
            .put("databaseFamily", "cmangos-classic")
        val snapshot = snapshotStore.create(datadir, id, databaseStopped = true,
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
        requireStopped()
        check(snapshotId.startsWith("manual-") && snapshotId.length <= 128) {
            "DB-SNAPSHOT: only named backups may be restored"
        }
        check(initialized() && migrationsCurrent() && cleanGeneration()) {
            "DB-SNAPSHOT: clean current stopped datadir required"
        }
        check(!databaseTransaction.exists()) { "DB-TRANSACTION: init/migration transaction is pending" }
        check(!restoreRecord.exists()) { "DB-SNAPSHOT: another restore verification is pending" }
        val snapshot = snapshotStore.load(snapshotId)
        val manifest = JSONObject(snapshot.manifest.readText())
        val compatibility = manifest.optJSONObject("compatibility") ?: JSONObject()
        requireCompatibleSnapshot(compatibility, requireGenerationUuid())
        val required = datadir.walkTopDown().filter { it.isFile }.sumOf { it.length() } + MIN_START_BYTES
        checkStorage(required)
        val token = UUID.randomUUID().toString()
        val candidate = File(roots.databaseRoot, "restore-candidate-$token")
        val quarantine = File(roots.databaseRoot, "restore-original-$token")
        atomicWrite(restoreRecord, JSONObject().put("schema", 2).put("token", token)
            .put("snapshotId", snapshot.id).put("snapshotDigest", snapshot.digest)
            .put("generationUuid", compatibility.getString("generationUuid"))
            .put("providerClosureSha256", providerIdentity.providerClosureSha256)
            .put("candidate", candidate.name).put("quarantine", quarantine.name)
            .put("phase", "PREPARING").toString())
        try {
            snapshotStore.restore(snapshot, candidate, databaseStopped = true)
            check(generationUuid(candidate) == compatibility.getString("generationUuid")) {
                "DB-SNAPSHOT: restored candidate generation mismatch"
            }
            atomicMove(datadir, quarantine)
            atomicMove(candidate, datadir)
            atomicWrite(restoreRecord, JSONObject(restoreRecord.readText())
                .put("phase", "CANDIDATE_ACTIVE").toString())
            atomicWrite(cleanMarker, DatabaseDurableState.cleanSeal(
                providerIdentity, requireGenerationUuid(), System.currentTimeMillis(),
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
        check(cleanGeneration()) { "DB-SNAPSHOT: restored candidate did not clean-stop" }
        check(requireGenerationUuid() == record.getString("generationUuid")) {
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
        val before = datadir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val refused = runCatching { checkStorage(MIN_START_BYTES, forcedAvailableBytes = 0) }.isFailure
        val after = datadir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        check(refused && before == after) { "DB-FULL: refusal was not side-effect free" }
        JSONObject().put("ok", true).put("classification", "DB-FULL")
            .put("refusedBeforeWrite", true).put("datadirBytesUnchanged", true)
    }

    fun close() {
        if (state == State.RUNNING || state == State.STARTING) {
            durableDelete(cleanMarker)
            DatabaseNative.cancelActiveGlibcProgramNative()
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
        check(record.getString("providerClosureSha256") == providerIdentity.providerClosureSha256)
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
            deleteTreeDurably(datadir)
            atomicMove(quarantine, datadir)
        }
        deleteTreeDurably(candidate)
        check(datadir.isDirectory) { "DB-SNAPSHOT: rollback has no active datadir" }
        atomicWrite(cleanMarker, DatabaseDurableState.cleanSeal(
            providerIdentity, requireGenerationUuid(), System.currentTimeMillis(),
            "restoreRolledBack", record.getString("snapshotId"),
        ))
        durableDelete(restoreRecord)
    }

    private fun finishRestoreCommit(record: JSONObject) {
        check(record.getString("phase") == "COMMITTING")
        requireDaemonDrained("restore commit")
        check(requireGenerationUuid() == record.getString("generationUuid") && cleanGeneration()) {
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
        check(DatabaseDurableState.parseTransaction(raw, kind, providerIdentity) != null) {
            "DB-TRANSACTION: transaction identity drift"
        }
        atomicWrite(databaseTransaction, DatabaseDurableState.withTransactionPhase(raw, kind, phase))
    }

    private fun databaseCompatibility(generationUuid: String): JSONObject = JSONObject()
        .put("provider", providerIdentity.providerId)
        .put("providerClosureSha256", providerIdentity.providerClosureSha256)
        .put("bootstrapSha256", providerIdentity.bootstrapSha256)
        .put("migrationManifestSha256", providerIdentity.migrationManifestSha256)
        .put("migrationCount", providerIdentity.migrationCount)
        .put("generationUuid", generationUuid)

    private fun databaseTransactionKind(): String? = if (!databaseTransaction.isFile) null else
        runCatching { DatabaseDurableState.transactionKind(databaseTransaction.readText()) }
            .getOrDefault("UNKNOWN")

    private fun requireCompatibleSnapshot(value: JSONObject, expectedGenerationUuid: String) {
        check(value.optString("provider") == providerIdentity.providerId &&
            value.optString("providerClosureSha256") == providerIdentity.providerClosureSha256 &&
            value.optString("bootstrapSha256") == providerIdentity.bootstrapSha256 &&
            value.optString("migrationManifestSha256") == providerIdentity.migrationManifestSha256 &&
            value.optInt("migrationCount", -1) == providerIdentity.migrationCount &&
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
        private const val MAX_DIAGNOSTIC = 16 * 1024
        private const val BOOTSTRAP_ASSET = "database/provider/bootstrap.sql"
        private const val RUNTIME_MANIFEST_ASSET = "database/provider/runtime-manifest.json"
        private const val MIGRATION_MANIFEST = "database/migrations/manifest.json"
        private val BACKUP_NAME = Regex("[A-Za-z0-9._-]{1,32}")
        private val MIGRATION_ID = Regex("[A-Za-z0-9._-]{1,191}")
        private val DATABASE_NAME = Regex("[a-z][a-z0-9_]{0,63}")
        private val SAFE_FILE = Regex("[A-Za-z0-9._+-]{1,255}")
        private val SAFE_TRANSACTION_PATH = Regex("(?:restore-candidate|restore-original)-[0-9a-f-]{36}")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
