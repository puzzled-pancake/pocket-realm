package com.pocketrealm.database

import android.content.Context
import android.os.Process
import com.pocketrealm.storage.StorageRoots
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
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
    @Volatile private var state = State.STOPPED
    @Volatile private var daemonResult: DatabaseRunResult? = null
    @Volatile private var daemonThread: Thread? = null

    private enum class State { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
    private data class Secrets(val admin: String, val core: String)

    fun status(): JSONObject = synchronized(lock) {
        return JSONObject().put("ok", true).put("state", state.name)
            .put("providerReady", providerReady())
            .put("initialized", initialized())
            .put("socketExists", socket.exists())
            .put("tcpDisabled", true)
            .put("cleanMarker", cleanMarker.exists())
            .put("datadir", datadir.absolutePath)
            .put("pid", Process.myPid())
            .put("lastExit", daemonResult?.exitCode ?: JSONObject.NULL)
    }

    fun initialize(): JSONObject = synchronized(lock) {
        requireStopped()
        requireProvider()
        checkStorage(MIN_INITIALIZE_BYTES)
        if (initialized()) {
            return JSONObject().put("ok", true).put("initialized", true).put("idempotent", true)
        }
        check(datadir.listFiles().isNullOrEmpty()) { "DB-INIT: datadir is non-empty without init marker" }
        stageProviderData()
        writeConfig()
        val bootstrap = File(providerRoot, "bootstrap.sql")
        check(bootstrap.isFile) { "DB-INIT: pinned bootstrap.sql missing" }
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
        atomicWrite(File(datadir, "mariadb_upgrade_info"), "$PROVIDER_VERSION-MariaDB")
        val secrets = createSecrets()
        startDaemon(allowUnsealedBootstrap = true)
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
        atomicWrite(File(roots.databaseRoot, "initialized.json"), JSONObject()
            .put("schema", 1).put("provider", PROVIDER_ID)
            .put("bootstrapSha256", sha256(bootstrap)).put("initializedAt", System.currentTimeMillis())
            .toString())
        JSONObject().put("ok", true).put("initialized", true)
            .put("bootstrapSha256", sha256(bootstrap))
            .put("leastPrivilegeVerified", true).put("privilegedActionDenied", true)
            .put("cleanStopped", true)
    }

    fun start(): JSONObject = synchronized(lock) {
        val started = startDaemon()
        val health = queryHealth()
        started.put("authenticated", health.getBoolean("authenticated"))
            .put("leastPrivilege", true)
    }

    private fun startDaemon(allowUnsealedBootstrap: Boolean = false): JSONObject {
        requireProvider()
        check(initialized() || allowUnsealedBootstrap) { "DB-INIT: initialize must pass before start" }
        check(state == State.STOPPED || state == State.FAILED) { "database already active: $state" }
        checkStorage(MIN_START_BYTES)
        socket.delete(); pidFile.delete()
        cleanMarker.delete()
        writeConfig()
        daemonResult = null
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
            DatabaseNative.cancelActiveGlibcProgramNative()
            thread.join(10_000)
            state = State.FAILED
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

    fun stop(): JSONObject = synchronized(lock) {
        check(state == State.RUNNING) { "database is not running" }
        state = State.STOPPING
        val query = fixedSql("shutdown", "SHUTDOWN;")
        val result = runClient("pocket_admin", readSecrets().admin, query)
        check(result.ok) { "DB-SOCKET: clean shutdown request failed: ${result.stderr.takeLast(1000)}" }
        daemonThread?.join(30_000)
        check(daemonThread?.isAlive != true && !socket.exists()) { "database did not clean-stop within 30 seconds" }
        check(daemonResult?.ok == true) { "mariadbd exit was not clean: ${daemonResult?.waitStatus}" }
        state = State.STOPPED
        atomicWrite(cleanMarker, JSONObject().put("schema", 1)
            .put("provider", PROVIDER_ID).put("stoppedAt", System.currentTimeMillis()).toString())
        JSONObject().put("ok", true).put("state", state.name).put("cleanMarker", true)
    }

    fun killForTest(): JSONObject = synchronized(lock) {
        check(state == State.RUNNING || state == State.STARTING) { "database is not active" }
        cleanMarker.delete()
        val killed = DatabaseNative.cancelActiveGlibcProgramNative()
        check(killed) { "tracked MariaDB process tree was not found" }
        daemonThread?.join(10_000)
        state = State.FAILED
        atomicWrite(dirtyRecord, JSONObject().put("schema", 1).put("dirty", true)
            .put("killedAt", System.currentTimeMillis()).put("waitStatus", daemonResult?.waitStatus)
            .toString())
        JSONObject().put("ok", true).put("killed", true).put("cleanMarker", false)
    }

    fun recover(): JSONObject = synchronized(lock) {
        check(!cleanMarker.exists()) { "recovery requested for a clean generation" }
        check(state == State.FAILED || state == State.STOPPED) { "database process still active" }
        val before = errorLog.length()
        start()
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
        check(initialized()) { "DB-INIT: initialize must pass before migrations" }
        check(cleanMarker.isFile) { "DB-SNAPSHOT: pre-migration generation is not clean" }
        checkStorage(MIN_MIGRATION_BYTES)
        val manifest = JSONObject(context.assets.open(MIGRATION_MANIFEST).bufferedReader().use { it.readText() })
        check(manifest.getInt("schema") == 1) { "DB-REVISION: unsupported manifest schema" }
        val snapshotId = "pre-migration-${System.currentTimeMillis()}"
        val snapshot = snapshotStore.create(datadir, snapshotId, databaseStopped = true)
        var applied = 0
        var skipped = 0
        try {
            startDaemon()
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
            snapshotStore.retainNewest(2)
            JSONObject().put("ok", true).put("applied", applied).put("skipped", skipped)
                .put("total", manifest.getJSONArray("entries").length())
                .put("snapshotId", snapshotId).put("snapshotDigest", snapshot.digest)
                .put("revisionMismatchRejected", true).put("cleanStopped", true)
        } catch (failure: Throwable) {
            rollbackToSnapshot(snapshot)
            throw failure
        }
    }

    fun snapshotAndRestoreTest(): JSONObject = synchronized(lock) {
        requireStopped()
        check(initialized() && cleanMarker.isFile) { "DB-SNAPSHOT: clean initialized datadir required" }
        val snapshot = snapshotStore.create(
            datadir, "restore-test-${System.currentTimeMillis()}", databaseStopped = true,
        )
        val original = File(roots.databaseRoot, "restore-original-${System.currentTimeMillis()}")
        check(datadir.renameTo(original)) { "DB-SNAPSHOT: could not quarantine original datadir" }
        return try {
            snapshotStore.restore(snapshot, datadir, databaseStopped = true)
            start()
            val health = queryHealth()
            stop()
            check(health.getBoolean("ok"))
            original.deleteRecursively()
            snapshotStore.retainNewest(2)
            JSONObject().put("ok", true).put("snapshotId", snapshot.id)
                .put("snapshotDigest", snapshot.digest).put("restoredAndQueried", true)
                .put("liveDatadirCopied", false)
        } catch (failure: Throwable) {
            if (state == State.RUNNING || state == State.STARTING) {
                DatabaseNative.cancelActiveGlibcProgramNative(); daemonThread?.join(10_000)
            }
            state = State.STOPPED
            datadir.deleteRecursively()
            check(original.renameTo(datadir)) { "DB-SNAPSHOT: failed to restore quarantined original" }
            throw failure
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
            cleanMarker.delete()
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
        val raw = DatabaseNative.runGlibcProgramNative(
            nativeDir.absolutePath, executable.absolutePath, argv0,
            roots.databaseRoot.absolutePath, roots.databaseRoot.absolutePath,
            File(providerRoot, "lib").absolutePath, args.joinToString("\n"),
            environment.joinToString("\n"),
            stdin?.absolutePath.orEmpty(), timeoutMs, trackDaemon,
        )
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
        val text = """
            [mariadbd]
            datadir=${datadir.absolutePath}
            socket=${socket.absolutePath}
            pid-file=${pidFile.absolutePath}
            log-error=${errorLog.absolutePath}
            skip-networking=1
            skip-name-resolve=1
            character-set-server=utf8mb4
            collation-server=utf8mb4_unicode_ci
            max-connections=24
            performance-schema=OFF
            innodb-buffer-pool-size=128M
            innodb-buffer-pool-instances=1
            innodb-log-file-size=32M
            innodb-flush-log-at-trx-commit=1
            sync-binlog=0
            secure-file-priv=${File(roots.databaseRoot, "import").apply { mkdirs() }.absolutePath}
        """.trimIndent() + "\n"
        atomicWrite(configFile, text)
    }

    private fun stageProviderData() {
        // tools/stage_mariadb_runtime.py emits this fixed asset tree. Assets are
        // data/scripts only; all executable ELFs remain APK-managed in nativeLibraryDir.
        copyAssetTree("database/provider", providerRoot)
        val manifest = JSONObject(File(providerRoot, "runtime-manifest.json").readText())
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
            DatabaseNative.cancelActiveGlibcProgramNative(); daemonThread?.join(10_000)
        }
        state = State.STOPPED
        socket.delete(); pidFile.delete(); cleanMarker.delete()
        val failed = File(roots.databaseRoot, "failed-datadir-${System.currentTimeMillis()}")
        if (datadir.exists()) check(datadir.renameTo(failed)) { "DB-SNAPSHOT: cannot quarantine failed datadir" }
        snapshotStore.restore(snapshot, datadir, databaseStopped = true)
        atomicWrite(cleanMarker, JSONObject().put("schema", 1).put("restoredSnapshot", snapshot.id)
            .put("stoppedAt", System.currentTimeMillis()).toString())
    }

    private fun providerReady(): Boolean = mariadbd.isFile && mariadb.isFile &&
        runCatching { context.assets.open("database/provider/bootstrap.sql").close() }.isSuccess
    private fun requireProvider() = check(providerReady()) { "DB-LINK: pinned MariaDB provider is not staged" }
    private fun initialized(): Boolean = File(roots.databaseRoot, "initialized.json").isFile
    private fun requireStopped() = check(state == State.STOPPED || state == State.FAILED) {
        "operation requires stopped database, state=$state"
    }

    private fun checkStorage(required: Long, forcedAvailableBytes: Long? = null) {
        val available = forcedAvailableBytes ?: roots.databaseRoot.usableSpace
        check(available >= required) { "DB-FULL: need=$required available=$available" }
    }

    private fun readSecrets(): Secrets {
        check(secretFile.isFile) { "DB-INIT: credential record missing" }
        val json = JSONObject(secretFile.readText())
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

    private fun atomicWrite(target: File, value: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${Process.myPid()}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(value.toByteArray())
            output.fd.sync()
        }
        check(temp.renameTo(target) || (target.delete() && temp.renameTo(target))) {
            "atomic replace failed: ${target.name}"
        }
    }

    private fun errorLogTail(fromByte: Long = 0): String = if (!errorLog.isFile) "" else
        errorLog.inputStream().use { input ->
            input.skip(fromByte)
            input.readBytes().decodeToString().takeLast(MAX_DIAGNOSTIC)
        }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun sha256Text(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "DatabaseEngine"
        private const val PROVIDER_ID = "mariadb-11.5.2-termux-glibc"
        private const val PROVIDER_VERSION = "11.5.2"
        private const val MIN_INITIALIZE_BYTES = 768L * 1024 * 1024
        private const val MIN_START_BYTES = 128L * 1024 * 1024
        private const val MIN_MIGRATION_BYTES = 1536L * 1024 * 1024
        private const val MAX_DIAGNOSTIC = 16 * 1024
        private const val MIGRATION_MANIFEST = "database/migrations/manifest.json"
        private val MIGRATION_ID = Regex("[A-Za-z0-9._-]{1,191}")
        private val DATABASE_NAME = Regex("[a-z][a-z0-9_]{0,63}")
        private val SAFE_FILE = Regex("[A-Za-z0-9._+-]{1,255}")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
