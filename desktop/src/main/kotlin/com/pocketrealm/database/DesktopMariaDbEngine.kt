package com.pocketrealm.database

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.desktop.DesktopStorageRoots
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * The desktop MariaDB lifecycle owner (DESK-5/47/78) — the JVM twin of the
 * Android DatabaseEngine's MariaDB leg, process-managed instead of
 * JNI-translated. All paths and commands derive internally from the staged
 * provider closure and the durable records; callers cannot inject SQL or
 * argv.
 *
 * Mirrored discipline (the Android leg's, kept verbatim wherever the JVM
 * allows):
 *  - durable init/migration transactions ([DatabaseDurableState], the
 *    shared codec — the DESK-5 "android-free DatabaseDurableState" leg) so
 *    every crash window is deterministic and testable;
 *  - seals through the SHARED [DatabaseGenerationSeal]/migration marker
 *    codecs, identity minted from the lockfile-verified provider closure;
 *  - the active-provider marker (DatabaseDurableState.ACTIVE_PROVIDER_MARKER_NAME)
 *    written at every provider transition so ServerRuntimeFiles'
 *    databaseInfo branch reaches the SAME durable decision as the engine;
 *  - the migration-ledger re-proof in recovery: a dirty generation
 *    recovers through the daemon, then must PROVE the sealed migration set
 *    current before recovery succeeds (the leg the desktop lane deferred
 *    until "the engine twin" arrived);
 *  - single-writer state machine with daemon drain proofs through the
 *    shared [DatabaseMutationGate].
 *
 * Deliberately NOT in this twin (the Android window's lanes, tracked
 * separately): the user-state translation export (the dual-provider
 * window's Server-A dump leg) and the SQLite control-plane branch — the
 * desktop's SQLite lane is the DesktopSqliteSeeder materialization, not an
 * in-engine branch.
 *
 * The staged-closure posture (the default-flip: MARIADB is the shipped
 * default provider): every mutating verb fails loud with the DB-LINK copy
 * before touching the datadir until the provider distribution is staged
 * (Settings.databaseProvider selects, the staged closure admits — and the
 * SQLITE lane stays selectable as the explicit dev/compat provider).
 *
 * Process seam: [Processes] is this lane's DatabaseNative role —
 * production drives real Windows processes (mariadbd.exe/mariadb.exe);
 * tests inject scripted [DatabaseRunResult]s and a simulated daemon so the
 * durable decisions stay unit-testable without starting MariaDB.
 */
@Suppress("TooManyFunctions", "LargeClass", "MagicNumber")
// The lifecycle's verb count lives together by design; the diagnostic
// truncation caps + protocol timeouts mirror the Android leg's reviewed
// literals.
internal class DesktopMariaDbEngine(
    private val roots: DesktopStorageRoots,
    /** The staged-provider seam (tests inject a synthetic staged
     * distribution); null resolves the dev/packaged staging lazily. */
    providerOverride: DesktopMariaDbProvider? = null,
    /** The process seam (tests fake; production spawns mariadbd.exe and
     * the mariadb.exe client). */
    private val processes: Processes = Processes.SYSTEM,
    /** W4 flip (the R2-7 exactly-one-qualified seam, now load-bearing):
     * the BOOT lane supplier — the backend pins the boot lane from the
     * persisted pick at start, and the engine's per-flavor SQL (bootstrap
     * database names, verifier rows, the realm-endpoint projection) must
     * resolve THAT lane, never the :ui ActiveFlavor holder a headless
     * process (RpHost, the restore gate) never populates. Null keeps the
     * holder-based resolution (tests, standalone tools). */
    private val bootLane: () -> com.pocketrealm.client.ClientFlavor? = { null },
) {
    /** The process seam (the Android DatabaseNative role). */
    interface Processes {
        /** One bounded tool run (client invocation, --bootstrap). */
        fun runTool(
            executable: File,
            args: List<String>,
            environment: Map<String, String>,
            stdin: File?,
            timeoutMs: Long,
        ): DatabaseRunResult

        /** Launch the long-lived daemon; returns its OS pid. */
        fun startDaemon(
            executable: File,
            args: List<String>,
            outputRedirect: File,
            errorRedirect: File,
        ): Long

        /** Whether the pid is observably alive. */
        fun alive(pid: Long): Boolean

        /** Kill the whole process tree; true when the tracked tree was found. */
        fun killTree(pid: Long): Boolean

        /** Whether the named pipe is observable (the readiness probe). */
        fun pipeExists(name: String): Boolean

        companion object {
            /** Production: real Windows processes. The daemon runs DETACHED
             * in its own process group so a launcher crash cannot orphan a
             * half-drained shutdown. */
            val SYSTEM: Processes = object : Processes {
                override fun runTool(
                    executable: File,
                    args: List<String>,
                    environment: Map<String, String>,
                    stdin: File?,
                    timeoutMs: Long,
                ): DatabaseRunResult {
                    val process = ProcessBuilder(
                        buildList {
                            add(executable.absolutePath)
                            addAll(args)
                        },
                    ).apply {
                        redirectErrorStream(false)
                        stdin?.let { redirectInput(it) }
                        environment().putAll(environment)
                    }.start()
                    // Drain stderr on its own thread: a full stderr pipe must
                    // never deadlock the stdout read (the bootstrap's failure
                    // output is exactly the interesting large payload).
                    var stderr = ""
                    val stderrDrain = Thread {
                        stderr = runCatching { process.errorStream.bufferedReader().readText() }
                            .getOrDefault("")
                    }.apply { start() }
                    val stdout = process.inputStream.bufferedReader().readText()
                    val exitedInTime = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    stderrDrain.join(5_000)
                    // The shared decode: waitStatus = exitCode shl 8 with the
                    // low signal bits zero (a clean Windows exit has no signal).
                    val waitStatus = if (exitedInTime) process.exitValue() shl 8 else -1
                    return DatabaseRunResult(
                        launcherRc = 0,
                        waitStatus = waitStatus,
                        timedOut = !exitedInTime,
                        stdout = stdout,
                        stderr = stderr,
                    )
                }

                override fun startDaemon(
                    executable: File,
                    args: List<String>,
                    outputRedirect: File,
                    errorRedirect: File,
                ): Long {
                    val process = ProcessBuilder(
                        buildList {
                            add(executable.absolutePath)
                            addAll(args)
                        },
                    ).apply {
                        redirectErrorStream(false)
                        // R3-r2-1 (desktop-twin round 2, serious): the
                        // capture previously resolved java.io.tmpdir — the
                        // Gradle daemon/test lanes redirect it to E:, but
                        // the rpHost harness and any jpackage install
                        // resolve %TEMP% ON C: (a project writer on C:,
                        // plan §1 hard rule 1; pocket-mariadbd.out/.err were
                        // observed in %TEMP%). The capture files now ride
                        // in from the caller: the engine's DesktopStorageRoots
                        // logs root, which the D50 override relocates with
                        // the whole product.
                        redirectOutput(outputRedirect)
                        redirectError(errorRedirect)
                    }.start()
                    return process.pid().toLong()
                }

                override fun alive(pid: Long): Boolean =
                    runCatching { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }
                        .getOrDefault(false)

                override fun killTree(pid: Long): Boolean = runCatching {
                    val taskkill = ProcessBuilder("taskkill", "/PID", pid.toString(), "/T", "/F")
                        .redirectErrorStream(true)
                        .start()
                    taskkill.inputStream.bufferedReader().readText()
                    taskkill.waitFor() == 0
                }.getOrDefault(false)

                override fun pipeExists(name: String): Boolean = runCatching {
                    // Windows named pipes do NOT enumerate through java.io.File:
                    // the pipe namespace carries no file attributes, so
                    // File("\\.\pipe\").list() returns null on real Windows and
                    // the daemon-readiness poll never sees a live pipe (proven
                    // live 2026-10-07: \\.\pipe\mariadb.sock answered the
                    // mariadb client while File.list() returned null). The
                    // non-invasive existence probe is WaitNamedPipeW: TRUE
                    // when the pipe exists with an available instance,
                    // ERROR_FILE_NOT_FOUND (2) when it does not exist; a busy
                    // all-instances-in-use wait still proves existence (no
                    // connection is consumed either way).
                    val kernel32 = com.sun.jna.Native.load(
                        "kernel32",
                        WaitNamedPipeKernel32::class.java,
                        com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS,
                    )
                    when {
                        kernel32.WaitNamedPipeW(com.sun.jna.WString("\\\\.\\pipe\\$name"), 1) -> true
                        kernel32.GetLastError() == 2 -> false // ERROR_FILE_NOT_FOUND
                        else -> true // ERROR_SEM_TIMEOUT/ERROR_PIPE_BUSY: the pipe is there
                    }
                }.getOrDefault(false)
            }
        }
    }

    /** The JNA shape of kernel32's WaitNamedPipeW (jna-platform is already a
     * desktop dependency — the audio mute seam's pin, build.gradle.kts). */
    private interface WaitNamedPipeKernel32 : com.sun.jna.win32.StdCallLibrary {
        fun WaitNamedPipeW(name: com.sun.jna.WString, timeoutMs: Int): Boolean
        fun GetLastError(): Int
    }

    private val provider = providerOverride ?: DesktopMariaDbProvider()

    private val databaseRoot = roots.database
    private val datadir = roots.mariadbDatadir
    private val runDir = roots.databaseRun
    private val configFile = File(runDir, "my.cnf")
    private val pidFile = File(runDir, "mariadb.pid")
    private val errorLog = File(runDir, "mariadb.err")
    private val cleanMarker = File(databaseRoot, CLEAN_MARKER_NAME)
    private val dirtyRecord = File(databaseRoot, DIRTY_RECORD_NAME)
    private val secretFile = File(databaseRoot, SECRETS_FILE_NAME)
    /**
     * W4 (R5-2): the LANE-SCOPED transaction journal — a lane killed
     * mid-apply leaves its journal in its OWN file, so another lane's
     * initialize/start is never blocked by lane A's pending transaction
     * (the charter's kill-mid-switch requirement). The vanilla lane keeps
     * the historical name.
     */
    private fun laneTransactionFile(): File {
        val lane = laneFlavorId()
        // R4 (round 4): the suffix composes onto the BARE name — the former
        // "$TRANSACTION_FILE_NAME-$lane.json" produced the malformed
        // "database-transaction.json-tbc.json" (the .json baked into the
        // constant), so the lane journal the recovery reads and the one a
        // killed apply wrote could disagree by name.
        val bare = TRANSACTION_FILE_NAME.removeSuffix(".json")
        return if (lane == "vanilla") File(databaseRoot, TRANSACTION_FILE_NAME)
        else File(databaseRoot, "$bare-$lane.json")
    }
    private val restoreRecord = File(databaseRoot, RESTORE_RECORD_NAME)
    private val initializedMarker = File(databaseRoot, INITIALIZED_MARKER_NAME)
    private val activeProviderMarker = File(databaseRoot, DatabaseDurableState.ACTIVE_PROVIDER_MARKER_NAME)
    private val generationMarker = File(datadir, GENERATION_MARKER_NAME)
    private val migrationMarker = File(datadir, MIGRATION_MARKER_NAME)
    // W4 flip (D86 routing law): the restore compatibility gate resolves
    // the lane from the ENGINE'S pinned boot lane — a headless engine never
    // populates the :ui holder, and the restore must target the lane the
    // whole engine routes through, never an ambiguous multi-qualified
    // refusal (or worse, a silent vanilla guess).
    private val snapshotStore = DatabaseSnapshotStore(
        roots.databaseSnapshots,
        // the SAME lane law as bootstrapDatabaseNames below: the pinned
        // boot lane, else the holder resolution (standalone tools).
        persistedActiveFlavorId = {
            (bootLane() ?: com.pocketrealm.server.ServerRuntimeFiles.serverFlavor()).flavorId
        },
    )
    private val secureImportDir = File(databaseRoot, "import")

    // The provider closure is verified ONCE per stopped lifetime and cached;
    // a mid-life staging change never re-owns a running generation.
    private val providerClosure by lazy { provider.verify() }

    private enum class State { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

    private data class Secrets(val admin: String, val core: String)

    /** The applied-stage ledger row (the timeframe overlay's read shape). */
    private data class MariadbAppliedStage(
        val appliedStage: String,
        val overlaySha256: String,
        val appliedAtMs: Long,
    )

    private val lock = Any()
    @Volatile private var state = State.STOPPED
    @Volatile private var daemonPid: Long? = null
    @Volatile private var lastExit: Int? = null
    @Volatile private var projectedRealmEndpoint: String? = null

    /** The restore transaction THIS engine instance is driving (the live
     * BEGIN→PROVE→COMMIT/ROLLBACK window), null when none. A durable
     * restore record with no live driver is an interrupted transaction —
     * that leftover is what [status]'s restorePending reports (the shared
     * preparation machine's ROLLBACK_PENDING_RESTORE input), while the
     * window this process owns is masked so the proof boot between BEGIN
     * and COMMIT passes the same preparation gate instead of being rolled
     * back by the machine meant to recover dead processes' leftovers. */
    @Volatile private var activeRestoreToken: String? = null

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    fun status(): JSONObject = synchronized(lock) {
        val ready = providerReady()
        // An unstaged provider has no identity to prove ownership WITH: the
        // honest verdict is MISSING (nothing initialized) / INVALID (an
        // initialized marker the current closure can never have written).
        val identityOrNull = runCatching { providerClosure.identity() }.getOrNull()
        val ownership = when {
            identityOrNull == null ->
                if (initializedMarker.isFile) DatabaseDurableState.OwnershipCompatibility.INVALID
                else DatabaseDurableState.OwnershipCompatibility.MISSING
            else -> runCatching {
                DatabaseDurableState.ownershipCompatibility(
                    initializedMarker.takeIf(File::isFile)?.readText(),
                    identityOrNull,
                    generationUuid(),
                )
            }.getOrDefault(
                if (initializedMarker.isFile) DatabaseDurableState.OwnershipCompatibility.INVALID
                else DatabaseDurableState.OwnershipCompatibility.MISSING,
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("state", state.name)
            .put("providerMode", DatabaseDurableState.ProviderMode.MARIADB.name)
            .put("providerReady", ready)
            .put("migrationManifestCount", runCatching { providerClosure.migrationCount }.getOrDefault(-1))
            .put("migrationSealedCount", migrationSealedCount())
            .put("initialized", initialized())
            .put("migrationsCurrent", migrationsCurrent())
            .put("socketExists", pipeObservable())
            .put("tcpDisabled", true)
            .put("cleanMarker", cleanGeneration())
            // The in-window state, honestly (DatabaseStartPreparation.kt:28
            // consumes this): true exactly when a durable restore record a
            // DEAD transaction left behind blocks the lane — the window a
            // LIVE engine instance drives reads false (activeRestoreToken).
            .put("restorePending", restoreRecord.isFile && activeRestoreToken == null)
            .put("databaseTransactionPending", laneTransactionFile().isFile)
            .put("databaseTransactionKind", databaseTransactionKind() ?: JSONObject.NULL)
            .put("compatibilityMismatch", when (ownership) {
                DatabaseDurableState.OwnershipCompatibility.PROVIDER_MISMATCH -> "PROVIDER_IDENTITY"
                DatabaseDurableState.OwnershipCompatibility.GENERATION_MISMATCH -> "GENERATION"
                DatabaseDurableState.OwnershipCompatibility.INVALID -> "INVALID_DURABLE_STATE"
                else -> JSONObject.NULL
            })
            .put("datadir", datadir.absolutePath)
            .put("pid", ProcessHandle.current().pid())
            // GAP-5: the daemon the engine spawned and tracks (startDaemon's
            // handle), NOT this JVM's ambient pid — a diagnostics reader must
            // never mistake the app process for mariadbd. NULL whenever the
            // engine is not RUNNING: a fabricated pid is the defect class.
            .put("daemonPid", daemonPid.takeIf { state == State.RUNNING } ?: JSONObject.NULL)
            .put("lastExit", lastExit ?: JSONObject.NULL)
    }

    // ------------------------------------------------------------------
    // Initialize (the bootstrap leg)
    // ------------------------------------------------------------------

    @Suppress("LongMethod", "TooGenericExceptionCaught")
    // The bootstrap transaction's reviewed step order (the Android twin's
    // shape); its failure funnel drains the daemon and rethrows as-is.
    fun initialize(): JSONObject = synchronized(lock) {
        requireStopped()
        requireProvider()
        recoverIncompleteInitialization()
        if (initialized()) {
            // R5-R6-1 (round 6): a pending MIGRATION journal was written
            // with the LANE identity (R3-2) — the disposition must be
            // proven kind-aware (MIGRATION -> lane identity, INIT ->
            // shared), or a kill-mid-migration on a tbc/wotlk lane answers
            // the false FAIL_CLOSED instead of the documented
            // defer-to-applyPinnedMigrations answer.
            return when (
                DatabaseDurableState.initializedDisposition(
                    laneTransactionFile().takeIf(File::isFile)?.readText(),
                    generationMarker.takeIf(File::isFile)?.readText(),
                    transactionRecordIdentity(
                        laneTransactionFile().takeIf(File::isFile)?.readText()
                            ?: "{}",
                    ),
                )
            ) {
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
        check(!laneTransactionFile().exists()) { "DB-TRANSACTION: non-init database transaction is pending" }
        check(datadir.listFiles().isNullOrEmpty()) { "DB-INIT: datadir is non-empty without init marker" }
        checkStorage(MIN_INITIALIZE_BYTES)
        val closure = providerClosure
        val identity = closure.identity()
        val generationUuid = UUID.randomUUID().toString()
        val transactionId = UUID.randomUUID().toString()
        DatabaseDurability.atomicWrite(
            laneTransactionFile(),
            DatabaseDurableState.transaction(
                kind = "INIT", phase = "OWNED", transactionId = transactionId,
                generationUuid = generationUuid, identity = identity,
            ),
        )
        datadir.mkdirs()
        DatabaseDurability.atomicWrite(generationMarker, DatabaseDurableState.generationMarker(generationUuid))
        try {
            writeConfig()
            check(closure.bootstrap.isFile && FileDigestsCompat.sha256(closure.bootstrap) == identity.bootstrapSha256) {
                "DB-INIT: pinned bootstrap.sql identity mismatch"
            }
            updateDatabaseTransactionPhase("INIT", "RUNNING")
            val result = processes.runTool(
                executable = closure.mariadbd,
                args = serverBaseArgs() + listOf(
                    "--bootstrap", "--log-warnings=0", "--enforce-storage-engine=",
                    "--max-allowed-packet=8M", "--net-buffer-length=16K",
                ),
                environment = emptyMap(),
                stdin = closure.bootstrap,
                timeoutMs = BOOTSTRAP_TIMEOUT_MS,
            )
            check(result.ok) {
                "DB-INIT: bootstrap failed exit=${result.exitCode}: ${result.stderr.takeLast(1200)}"
            }
            // mysql_install_db writes this exact marker after a successful
            // bootstrap. Preserve that contract (the Android leg's comment).
            DatabaseDurability.atomicWrite(File(datadir, "mariadb_upgrade_info"), "$PROVIDER_VERSION-MariaDB")
            val secrets = createSecrets()
            startDaemon(allowUnsealedBootstrap = true, allowedTransactionKind = "INIT")
            // runFixedSql deletes the transcript in a finally: the
            // initial-auth SQL embeds every credential literal (the admin
            // GRANT ALL password included), so no copy of it may outlive
            // the verb that consumed it — secrets.json stays the only
            // at-rest copy (the migration/overlay transcript delete law).
            // W1.2: the database names route through the flavor resolver
            // (bootstrapDatabaseNames -> MariaDbBootstrap.initialAuthSql),
            // never a bare vanilla literal; the exactly-one-qualified +
            // vanilla-only law refuses BEFORE any SQL crosses (DECISIONS-PORT
            // D72's W1.2 deferral).
            val auth = runFixedSql(
                "root", "",
                "initial-auth",
                MariaDbBootstrap.initialAuthSql(
                    bootstrapDatabaseNames(), secrets.admin, secrets.core,
                ),
            )
            check(auth.ok) { "DB-INIT: credential/grant setup failed: ${auth.stderr.takeLast(1200)}" }
            val health = queryHealthLocked()
            check(health.getBoolean("ok")) { "DB-INIT: least-privilege query failed" }
            val denied = runFixedSql(
                "pocket_core", secrets.core,
                "least-privilege-negative",
                "CREATE USER 'pocket_forbidden'@'localhost' IDENTIFIED BY 'never';",
            )
            check(!denied.ok) { "DB-INIT: core user unexpectedly has account-administration privilege" }
            stopLocked()
            DatabaseDurability.atomicWrite(
                initializedMarker,
                DatabaseDurableState.initializedSeal(identity, generationUuid, System.currentTimeMillis()),
            )
            writeActiveProviderMarker(DatabaseDurableState.ProviderMode.MARIADB, generationUuid)
            updateDatabaseTransactionPhase("INIT", "COMMITTING")
            durableDelete(laneTransactionFile())
            JSONObject()
                .put("ok", true)
                .put("initialized", true)
                .put("generationUuid", generationUuid)
                .put("bootstrapSha256", identity.bootstrapSha256)
                .put("providerClosureSha256", identity.providerClosureSha256)
                .put("leastPrivilegeVerified", true)
                .put("privilegedActionDenied", true)
                .put("cleanStopped", true)
        } catch (failure: Throwable) {
            if ((state != State.STOPPED && state != State.FAILED) || daemonAlive()) {
                runCatching { cancelAndRequireDaemonDrained("initialization failure") }
            }
            throw failure
        }
    }

    // ------------------------------------------------------------------
    // Start / health / stop
    // ------------------------------------------------------------------

    @Suppress("TooGenericExceptionCaught")
    // The start gates' reviewed order; initialize()'s failure funnel rides
    // the post-spawn legs too: the service's guarded() answers a throw as
    // JSON, so without the funnel a health failure after the daemon came up
    // would park a live mariadbd in RUNNING that no later unwind drains
    // (the leaked-mariadbd-on-start-failure residual).
    fun start(): JSONObject = synchronized(lock) {
        requireProvider()
        when (
            DatabaseDurableState.startBlocker(
                initialized(), migrationsCurrent(), cleanGeneration(), laneTransactionFile().exists(),
            )
        ) {
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
        try {
            val health = queryHealthLocked()
            started.put("authenticated", health.getBoolean("authenticated"))
                .put("leastPrivilege", true)
        } catch (failure: Throwable) {
            if ((state != State.STOPPED && state != State.FAILED) || daemonAlive()) {
                runCatching { cancelAndRequireDaemonDrained("start failure") }
            }
            throw failure
        }
    }

    fun queryHealth(): JSONObject = synchronized(lock) { queryHealthLocked() }

    private fun queryHealthLocked(): JSONObject {
        check(state == State.RUNNING) { "database is not running" }
        val secrets = readSecrets()
        val result = runFixedSql(
            "pocket_core", secrets.core,
            "health",
            "SELECT 'POCKET_DB_OK', VERSION(), @@skip_networking;",
        )
        check(result.ok && result.stdout.contains("POCKET_DB_OK")) {
            "DB-SOCKET: authenticated health query failed: ${result.stderr.takeLast(1200)}"
        }
        return JSONObject()
            .put("ok", true)
            .put("authenticated", true)
            .put("leastPrivilegeConfigured", secrets.core.isNotEmpty())
            .put("result", result.stdout.trim().take(512))
    }

    /** Fixed, owner-gated projection consumed by realmd; arbitrary SQL never
     * crosses the process boundary (the Android twin's contract). The row's
     * realmbuilds advertisement rides the same UPDATE: the pinned realmd
     * seed ships an EMPTY build list (the source dump's row), so without
     * this write realmd answers every logon challenge with ok_build=false
     * (AuthSocket flags the realm offline for the client's build) until the
     * world's own start UPDATE — this projection owns the row from database
     * start, before realmd serves a logon. [realmbuilds] is the active
     * flavor's accepted-build list (ClientFlavor.realmdAcceptedBuilds),
     * caller-resolved and shape-guarded here like every interpolated
     * literal. The projected DATABASE resolves through the flavor registry
     * at projection time (ServerRuntimeFiles.serverDatabases, the sqlite
     * leg's routing) — never a hard-coded vanilla name — so the conf's
     * LoginDatabaseInfo and this row land in the same database by
     * construction, and the ActiveFlavor machine's honesty gates the SQL
     * before any of it crosses the client. */
    /** R1-r3-4 (desktop-twin round 3): the android
     * DatabaseEngine.realmdDatabaseName guard's desktop twin — the realmd
     * database the MariaDB SQL targets is registry-resolved with the
     * exactly-one-qualified + vanilla-only requirement BEFORE any SQL
     * crosses the client (the W1.2 per-flavor-seeding deferral: at a
     * multi-qualified flip the projection/seeding refuses loud instead of
     * mutating the wrong lane's row). ALSO corrects D63's "verified in
     * place" claim: the android guard is projection-only and the desktop
     * had none — both engines now gate their own SQL. W1.2: the bootstrap's
     * CREATE DATABASE literals route through THIS one resolver too
     * ([bootstrapDatabaseNames] -> MariaDbBootstrap), so the projection row
     * and the seeded databases can never disagree. */
    private fun realmdDatabaseName(): String = bootstrapDatabaseNames().realmd

    /**
     * The bootstrap's flavor-routed database names (W1.2): the machine's own
     * fail-loud resolution ([com.pocketrealm.server.ServerRuntimeFiles.serverFlavor]
     * — exactly-one-qualified or an explicit selection), then the W1.2
     * vanilla-only requirement (the recorded W1.2 per-flavor-seeding
     * deferral: the seeded realmd databases are the vanilla family ONLY, so
     * a non-vanilla resolution refuses BEFORE any SQL crosses instead of
     * mutating the wrong lane's row), then the shared shape guard
     * ([MariaDbBootstrap.databaseNames]). The initial-auth bootstrap and the
     * realmlist projection resolve through THIS one law.
     */
    private fun bootstrapDatabaseNames(): ClientFlavor.DatabaseNames {
        val flavor = bootLane()
            ?: com.pocketrealm.server.ServerRuntimeFiles.serverFlavor()
        // W4 (D72 RETIRED, owner ruling 2026-10-12): the vanilla-only
        // requirement is GONE — every qualified lane bootstraps its own
        // database family (the per-flavor migration ledgers stage through
        // tools/stage_mariadb_flavor_migrations.py; applyPinnedMigrations
        // provisions the lane's databases then applies ITS ledger). The
        // shape guard stays: a registry name that is not a plain
        // identifier refuses before any SQL crosses.
        return MariaDbBootstrap.databaseNames(flavor)
    }

    /**
     * W4 (D72 retirement): the LANE the engine's per-flavor state resolves
     * against — the pinned boot lane (the backend supplier), holder
     * fallback for standalone tools. Every per-flavor surface below keys
     * off this: the migration ledger table, the migration seal marker,
     * the manifest/assets, and the provisioned database family.
     */
    private fun laneFlavorId(): String = bootstrapLaneFlavor().flavorId

    private fun bootstrapLaneFlavor(): com.pocketrealm.client.ClientFlavor =
        bootLane() ?: com.pocketrealm.server.ServerRuntimeFiles.serverFlavor()

    /** The lane's migration LEDGER table in pocketrealm_meta: vanilla keeps
     * the historical `migration_ledger` byte-stable; tbc/wotlk add their
     * own suffixed tables (one datadir hosts every qualified family). */
    private fun laneLedgerTable(): String {
        val lane = laneFlavorId()
        return if (lane == "vanilla") "migration_ledger" else "migration_ledger_$lane"
    }

    /** The lane's migration seal marker beside the datadir: vanilla keeps
     * the historical name; tbc/wotlk get suffixed siblings. */
    private fun laneMigrationMarker(): File {
        val lane = laneFlavorId()
        return if (lane == "vanilla") migrationMarker
        else File(datadir, ".pocketrealm-migrations-$lane.json")
    }

    /** R1-r3-5 (desktop-twin round 3): the harness SRP verifier seeding
     * for the SHIPPED-DEFAULT MariaDB lane — the same owner-gated SQL path
     * the realmlist projection rides, so AuthGate's proto-login verifier
     * lands in the database realmd actually reads (it previously went to
     * the sqlite datadir unconditionally, a store the MariaDB lane's
     * realmd never opens). */
    /** R1-r3-5: the persisted session key the protocol gate's proof 3
     * verifies (the MariaDB lane's realmd account row). */
    fun readVerifierSessionKey(account: String): String? = synchronized(lock) {
        check(state == State.RUNNING) { "database is not running" }
        val realmdDatabase = realmdDatabaseName()
        val result = runFixedSql(
            "pocket_core", readSecrets().core,
            "read-verifier-session-key",
            "SELECT sessionkey FROM $realmdDatabase.account WHERE username='$account' LIMIT 1;",
        )
        check(result.ok) { "session key read failed: ${result.stderr.takeLast(800)}" }
        result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .lastOrNull()?.takeIf { it.isNotEmpty() && it != "NULL" }
    }

    /** R1-r3-5: the gate's known-password account row leaves the live
     * realmd database after every proof landed. */
    fun deleteVerifierRow(account: String): JSONObject = synchronized(lock) {
        check(state == State.RUNNING) { "database is not running" }
        val realmdDatabase = realmdDatabaseName()
        val result = runFixedSql(
            "pocket_core", readSecrets().core,
            "delete-verifier-row",
            "DELETE FROM $realmdDatabase.account WHERE username='$account';" +
                "SELECT COUNT(*) FROM $realmdDatabase.account WHERE username='$account' LIMIT 1;",
        )
        check(result.ok) { "verifier cleanup failed: ${result.stderr.takeLast(800)}" }
        val counted = result.stdout.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() }.lastOrNull()
        check(counted == "0") { "gate account row survived cleanup" }
        JSONObject().put("ok", true).put("operation", "srp-verifier-deleted")
    }

    fun seedSrpVerifierRow(account: String, vHex: String, sHex: String): JSONObject =
        synchronized(lock) {
            check(state == State.RUNNING) { "database is not running" }
            val realmdDatabase = realmdDatabaseName()
            // realmd stores account usernames UPPERCASE (AccountMgr's
            // normalizeString); the row the client authenticates against is
            // the uppercase name, so the seeding normalizes to it and
            // refuses anything outside the account-token shape (1..16
            // ASCII alnum) before interpolation — a lowercase or decorated
            // name would seed a row realmd never matches.
            val accountToken = account.uppercase()
            check(ACCOUNT_TOKEN.matches(accountToken)) {
                "verifier account is not a plain account token (1..16 ASCII alnum)"
            }
            val result = runFixedSql(
                "pocket_core", readSecrets().core,
                "seed-srp-verifier",
                """
                DELETE FROM $realmdDatabase.account WHERE username='$accountToken';
                INSERT INTO $realmdDatabase.account
                    (username, gmlevel, v, s, joindate, lockedIp, last_module, locale, os, platform)
                VALUES ('$accountToken', 0, '$vHex', '$sHex',
                        '2026-01-01 00:00:00', '0.0.0.0', '', 'enUS', 'Win', 'x86');
                SELECT COUNT(*) FROM $realmdDatabase.account WHERE username='$accountToken' LIMIT 1;
                """.trimIndent(),
            )
            check(result.ok) { "verifier seeding failed: ${result.stderr.takeLast(800)}" }
            val counted = result.stdout.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() }.lastOrNull()
            check(counted == "1") { "verifier row did not land" }
            JSONObject()
                .put("ok", true)
                .put("operation", "srp-verifier-seeded")
                .put("database", realmdDatabase)
        }

    fun projectRealmEndpoint(address: String, worldPort: Int, realmbuilds: String): JSONObject =
        synchronized(lock) {
        check(state == State.RUNNING) { "database is not running" }
        val endpoint = com.pocketrealm.supervisor.RealmEndpoint.parseStored(address)
        require(worldPort == com.pocketrealm.supervisor.RealmEndpoint.WORLD_PORT) { "world port is fixed" }
        check(realmbuilds.length <= REALMBUILDS_MAX_CHARS && REALMBUILDS.matches(realmbuilds)) {
            "realm builds advertisement is not a build list: $realmbuilds"
        }
        val realmdDatabase = realmdDatabaseName()
        check(DATABASE_NAME.matches(realmdDatabase)) {
            "realmd database name is not a plain identifier: $realmdDatabase"
        }
        projectedRealmEndpoint?.let { prior ->
            check(prior == endpoint.address) {
                "realm endpoint is immutable for the active database generation"
            }
            return JSONObject()
                .put("ok", true)
                .put("operation", "realm-endpoint-already-projected")
                .put("address", endpoint.address)
                .put("worldPort", com.pocketrealm.supervisor.RealmEndpoint.WORLD_PORT)
        }
        val result = runFixedSql(
            "pocket_core", readSecrets().core,
            "project-realm-endpoint",
            """
            UPDATE $realmdDatabase.realmlist
               SET address='${endpoint.address}',
                   port=${com.pocketrealm.supervisor.RealmEndpoint.WORLD_PORT},
                   realmbuilds='$realmbuilds'
             WHERE id=1;
            SELECT address,port,realmbuilds FROM $realmdDatabase.realmlist WHERE id=1 LIMIT 1;
            """.trimIndent(),
        )
        check(result.ok) { "realm endpoint projection failed: ${result.stderr.takeLast(800)}" }
        val projected = result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.lastOrNull()
        check(
            projected == "${endpoint.address}\t${com.pocketrealm.supervisor.RealmEndpoint.WORLD_PORT}\t$realmbuilds",
        ) {
            "realm endpoint projection did not verify"
        }
        projectedRealmEndpoint = endpoint.address
        JSONObject()
            .put("ok", true)
            .put("operation", "realm-endpoint-projected")
            .put("address", endpoint.address)
            .put("worldPort", com.pocketrealm.supervisor.RealmEndpoint.WORLD_PORT)
    }

    fun stop(): JSONObject = synchronized(lock) { stopLocked() }

    private fun stopLocked(): JSONObject {
        check(state == State.RUNNING) { "database is not running" }
        state = State.STOPPING
        val result = runFixedSql("pocket_admin", readSecrets().admin, "shutdown", "SHUTDOWN;")
        check(result.ok) { "DB-SOCKET: clean shutdown request failed: ${result.stderr.takeLast(1000)}" }
        awaitDaemonExit(DRAIN_TIMEOUT_MS)
        // The clean-stop proof (the JVM twin of the Android daemon join +
        // native result check): the SHUTDOWN request was accepted, the
        // tracked process observably exited inside the window, and the pipe
        // + pid control files are gone. A post-detach Windows process exit
        // CODE is not observable without job objects, so the drain evidence
        // IS the clean proof — an unclean death leaves the pipe/pid behind
        // and fails here.
        check(!daemonAlive() && !pipeObservable() && !pidProcessExists()) {
            "database did not clean-stop and drain within ${DRAIN_TIMEOUT_MS / 1000} seconds"
        }
        state = State.STOPPED
        DatabaseDurability.atomicWrite(
            cleanMarker,
            DatabaseDurableState.cleanSeal(identity(), requireGenerationUuid(), System.currentTimeMillis()),
        )
        writeActiveProviderMarker(DatabaseDurableState.ProviderMode.MARIADB, requireGenerationUuid())
        return JSONObject().put("ok", true).put("state", state.name).put("cleanMarker", true)
    }

    /** True when the engine observably has no live generation (STOPPED or
     * FAILED) — the only states in which a claim release needs no engine work. */
    fun isEnded(): Boolean = synchronized(lock) { state == State.STOPPED || state == State.FAILED }

    /**
     * The owner-gated stop verb behind DesktopDatabaseService.stopOwned (the
     * Android stopForOwnerRelease contract): an engine that is observably
     * down has nothing to stop — success-for-the-claim, seals untouched; a
     * live engine takes the ordinary clean-stop path with all of its drain
     * proofs. This path never weakens stop() itself.
     */
    fun stopForOwnerRelease(): JSONObject = synchronized(lock) {
        if (isEnded()) {
            JSONObject()
                .put("ok", true)
                .put("state", state.name)
                .put("alreadyDown", true)
                .put("claimReleased", true)
        } else {
            stopLocked()
        }
    }

    /** The debug-only forced kill (the Android killForTest contract). */
    fun killForTest(): JSONObject = synchronized(lock) {
        check(state == State.RUNNING || state == State.STARTING) { "database is not active" }
        durableDelete(cleanMarker)
        val pid = daemonPid
        val killed = pid != null && processes.killTree(pid)
        check(killed) { "tracked MariaDB process tree was not found" }
        awaitDaemonExit(KILL_DRAIN_TIMEOUT_MS)
        state = State.FAILED
        requireDaemonDrained("forced test stop")
        DatabaseDurability.atomicWrite(
            dirtyRecord,
            JSONObject()
                .put("schema", 1)
                .put("dirty", true)
                .put("killedAt", System.currentTimeMillis())
                .toString(),
        )
        JSONObject().put("ok", true).put("killed", true).put("cleanMarker", false)
    }

    // ------------------------------------------------------------------
    // Dirty recovery + the migration-ledger re-proof
    // ------------------------------------------------------------------

    @Suppress("TooGenericExceptionCaught")
    // The recovery's reviewed order; the same failure funnel initialize()
    // and start() have rides the post-daemon legs: a dirty recovery that
    // fails after the daemon came up must drain the mariadbd it spawned,
    // never strand it over the dirty generation it was recovering.
    fun recover(): JSONObject = synchronized(lock) {
        check(!cleanGeneration()) { "recovery requested for a clean generation" }
        check(state == State.FAILED || state == State.STOPPED) { "database process still active" }
        requireDaemonDrained("dirty recovery")
        check(
            DatabaseDurableState.dirtyRecoveryPermitted(
                initialized(), cleanGeneration(), laneTransactionFile().exists(),
            ),
        ) { "DB-RECOVERY: sealed initialized generation without a pending transaction required" }
        val beforeErrorLogLength = errorLog.length()
        startDaemon(allowDirtyRecovery = true)
        try {
            val health = queryHealthLocked()
            val recoveryOutput = errorLogTail(fromByte = beforeErrorLogLength)
            val classified = recoveryOutput.contains("recover", ignoreCase = true) ||
                recoveryOutput.contains("crash", ignoreCase = true) ||
                recoveryOutput.contains("InnoDB", ignoreCase = true)
            check(health.getBoolean("ok")) { "DB-RECOVERY: post-dirty health failed" }
            stopLocked()
            // The migration-ledger re-proof (the leg the desktop lane
            // deferred until this engine twin arrived — DESK-5's
            // recoverDatabase completion): a recovered generation must
            // PROVE the sealed migration set current, exactly like
            // Android's normal-start gate; a recovered-but-stale ledger
            // refuses instead of silently booting.
            if (!migrationsCurrent()) {
                error(
                    "DB-REVISION: recovered generation's migration ledger is not current " +
                        "(re-proof failed); applyPinnedMigrations owns the pinned set",
                )
            }
            DatabaseDurability.atomicWrite(
                dirtyRecord,
                JSONObject()
                    .put("schema", 1)
                    .put("dirty", false)
                    .put("recoveredAt", System.currentTimeMillis())
                    .put("recoveryOutputObserved", classified)
                    .put("logDigest", FileDigestsCompat.sha256(recoveryOutput))
                    .toString(),
            )
            JSONObject()
                .put("ok", true)
                .put("recovered", true)
                .put("recoveryOutputObserved", classified)
                .put("cleanStopped", true)
        } catch (failure: Throwable) {
            if ((state != State.STOPPED && state != State.FAILED) || daemonAlive()) {
                runCatching { cancelAndRequireDaemonDrained("recovery failure") }
            }
            throw failure
        }
    }

    // ------------------------------------------------------------------
    // Pinned migrations
    // ------------------------------------------------------------------

    @Suppress("LongMethod", "TooGenericExceptionCaught", "CyclomaticComplexMethod")
    // The migration transaction's reviewed step order (the Android twin's
    // shape); its failure funnel rolls back and rethrows as-is.
    fun applyPinnedMigrations(): JSONObject = synchronized(lock) {
        requireStopped()
        requireProvider()
        val recoveredTransaction = recoverPendingMigrationTransaction()
        check(initialized()) { "DB-INIT: initialize must pass before migrations" }
        check(cleanGeneration()) { "DB-SNAPSHOT: pre-migration generation is not clean" }
        val closure = providerClosure
        // W4 (D72 retirement + R3-1/R3-2): the LANE's manifest, digest-verified
        // against the lane lockfile document, and the LANE's OWN identity —
        // the seal/marker/currency gate binds the lane's ledger digest and
        // count (the shared vanilla 414-entry identity would make a
        // re-staged lane ledger invisible to migrationsCurrent). The shared
        // identity still owns the bootstrap/provider fields.
        val lane = laneFlavorId()
        val shared = closure.identity()
        // vanilla reads the SHARED verified manifest; tbc/wotlk route through
        // the digest-gated lane verifier (the sibling manifest + its own
        // identity).
        val manifest = closure.migrationManifestFor(lane)
        // the LANE identity is only needed for non-vanilla lanes (vanilla's
        // seal uses the shared identity and the shared verified manifest)
        val laneIdentity = if (lane == "vanilla") shared
        else closure.laneMigrationReader(lane).second
        val identity = if (lane == "vanilla") shared else laneIdentity
        val total = manifest.getJSONArray("entries").length()
        if (migrationsCurrent()) {
            // R4-5: the recovered-transaction disclosure belongs on EVERY
            // recovery-terminated apply — an interrupted transaction whose
            // lane is already current still rolls its snapshot back (the
            // kill's partial bytes are undone), and the caller must see it.
            return JSONObject()
                .put("ok", true)
                .put("idempotent", true)
                .put("recoveredInterruptedTransaction", recoveredTransaction)
                .put("applied", 0)
                .put("skipped", total)
                .put("total", total)
                .put("cleanStopped", true)
        }
        check(manifest.getInt("schema") == 1) { "DB-REVISION: unsupported manifest schema" }
        checkStorage(MIN_MIGRATION_BYTES)
        val generationUuid = requireGenerationUuid()
        val snapshotId = "pre-migration-${System.currentTimeMillis()}"
        // R4-5 companion fix (the R3-2 identity-drift class): the MIGRATION
        // snapshot's compatibility stamps the LANE identity — the recovery
        // proves the snapshot against the lane's own identity, so a
        // shared-identity stamp could never recover (the historical
        // compatibility mismatch the kill-mid-switch test surfaced).
        val compatibility = databaseCompatibility(generationUuid, laneIdentity)
            .put("flavor", lane)
        val snapshot = snapshotStore.create(
            datadir, snapshotId, databaseStopped = true, compatibility = compatibility,
        )
        DatabaseDurability.atomicWrite(
            laneTransactionFile(),
            DatabaseDurableState.transaction(
                kind = "MIGRATION", phase = "SNAPSHOT_READY",
                transactionId = UUID.randomUUID().toString(), generationUuid = generationUuid,
                identity = if (lane == "vanilla") identity else laneIdentity,
                snapshotId = snapshot.id, snapshotDigest = snapshot.digest,
            ),
        )
        var applied = 0
        var skipped = 0
        try {
            updateDatabaseTransactionPhase("MIGRATION", "RUNNING")
            startDaemon(allowedTransactionKind = "MIGRATION")
            // W4 (D72 retirement): the LANE's database family is provisioned
            // BEFORE its ledger applies — the four CREATE DATABASE IF NOT
            // EXISTS + the pocket_core grants, composed by the shared
            // bootstrap (idempotent; the initialized datadir created only
            // the FIRST lane's family).
            val provision = runFixedSql(
                "pocket_admin", readSecrets().admin,
                "lane-provision",
                MariaDbBootstrap.laneProvisionSql(bootstrapDatabaseNames()),
            )
            check(provision.ok) {
                "DB-REVISION: lane database provisioning failed: " +
                    provision.stderr.takeLast(1200)
            }
            createLedger()
            val entries = manifest.getJSONArray("entries")
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val id = entry.getString("migration_id")
                val sqlHash = entry.getString("sql_sha256")
                check(MIGRATION_ID.matches(id) && SHA256.matches(sqlHash)) {
                    "DB-REVISION: unsafe ledger identity"
                }
                val prior = ledgerStatus(id)
                if (prior != null) {
                    check(prior.first == "APPLIED" && prior.second == sqlHash) {
                        "DB-REVISION: ledger drift for $id status=${prior.first}"
                    }
                    skipped++
                    continue
                }
                val sql = materializeMigration(closure, entry)
                ledgerPending(entry, snapshotId, manifest)
                // R2-r6-1 (round 6): the mandatory family backstop — a
                // ledger entry naming a database outside THIS lane's own
                // family refuses BEFORE any SQL executes (the digest gates
                // cannot catch a mis-generated ledger; the post-apply
                // revision probe runs only after the foreign SQL did).
                val targetDatabase = entry.getString("database")
                val laneFamily = bootstrapDatabaseNames()
                check(
                    targetDatabase == laneFamily.realmd ||
                        targetDatabase == laneFamily.world ||
                        targetDatabase == laneFamily.characters ||
                        targetDatabase == laneFamily.logs,
                ) {
                    "DB-REVISION: migration $id targets database " +
                        "\$targetDatabase, which is not the " +
                        "${lane} lane's own family " +
                        "(${laneFamily.realmd}/${laneFamily.world}/" +
                        "${laneFamily.characters}/${laneFamily.logs})"
                }
                val migrationTimeoutMs = (30_000L + (sql.length() / 1024L) * 40L)
                    .coerceAtMost(600_000L)
                val result = runClient(
                    "pocket_admin", readSecrets().admin, sql,
                    targetDatabase, migrationTimeoutMs,
                )
                val resultDigest = FileDigestsCompat.sha256(result.stdout + "\n" + result.stderr)
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
            val wrongAccepted = revisionColumnExists(
                bootstrapDatabaseNames().world, "db_version", "required_z9999_not_real",
            )
            check(!wrongAccepted) { "DB-REVISION: mismatch negative test was incorrectly accepted" }
            stopLocked()
            DatabaseDurability.atomicWrite(
                laneMigrationMarker(),
                DatabaseDurableState.migrationSeal(
                    if (lane == "vanilla") identity else laneIdentity,
                    generationUuid, System.currentTimeMillis(),
                ),
            )
            durableDelete(laneTransactionFile())
            snapshotStore.retainNewest(2)
            JSONObject()
                .put("ok", true)
                .put("applied", applied)
                .put("skipped", skipped)
                .put("total", total)
                .put("snapshotId", snapshotId)
                .put("snapshotDigest", snapshot.digest)
                .put("recoveredInterruptedTransaction", recoveredTransaction)
                .put("revisionMismatchRejected", true)
                .put("cleanStopped", true)
        } catch (failure: Throwable) {
            if (laneTransactionFile().isFile) {
                runCatching { updateDatabaseTransactionPhase("MIGRATION", "FAILED") }
                rollbackToSnapshot(identity, snapshot)
                durableDelete(laneTransactionFile())
            }
            throw failure
        }
    }

    // ------------------------------------------------------------------
    // Transactional restore (the desktop restore transaction's engine leg)
    // ------------------------------------------------------------------

    /**
     * The restore transaction's BEGIN leg (the Android DatabaseEngine
     * beginRestore twin — a maintenance verb under the claim law, so
     * deliberately claim-less exactly like the Android engine's, which
     * takes no owner either: the maintenance lane admits restore only on
     * a clean-stopped runtime, a state entered via stopOwned, which has
     * already released the claim, and no claim taken before BEGIN could
     * still cover the COMMIT that follows the verification stop; the
     * engine holds the destructive half, refusing anything but a clean
     * stopped datadir): load + digest-verify the named backup, restore it into a
     * candidate directory, probe the candidate's generation, then swap it
     * in under this engine's daemon-drain proofs while quarantining the
     * live datadir — the [rollbackToSnapshot] machinery's clean-seal
     * restamp included, so the swapped-in generation is startable as-is.
     * The COMMIT leg retires the quarantine (the caller proved the
     * candidate world-ready and clean-stopped); the ROLLBACK leg puts the
     * original back. The durable restore record makes an interrupted
     * transaction observable to the next engine ([status]'s
     * restorePending), and this instance's live window is masked so the
     * proof boot between BEGIN and COMMIT is not mistaken for a leftover.
     */
    @Suppress("LongMethod") // the reviewed transaction order (the Android beginRestore twin)
    fun beginRestore(snapshotId: String): JSONObject = synchronized(lock) {
        requireStopped()
        requireProvider()
        check(snapshotId.startsWith("manual-")) {
            "DB-SNAPSHOT: only named backups may be restored"
        }
        check(initialized() && migrationsCurrent() && cleanGeneration()) {
            "DB-SNAPSHOT: clean current stopped datadir required"
        }
        check(!laneTransactionFile().exists()) { "DB-TRANSACTION: init/migration transaction is pending" }
        check(!restoreRecord.exists()) { "DB-SNAPSHOT: another restore verification is pending" }
        val snapshot = snapshotStore.load(snapshotId)
        checkStorage(
            datadir.walkTopDown().filter { it.isFile }.sumOf { it.length() } + MIN_START_BYTES,
        )
        val identity = identity()
        val generationUuid = requireGenerationUuid()
        val token = UUID.randomUUID().toString()
        val candidate = File(databaseRoot, "restore-candidate-$token")
        val quarantine = File(databaseRoot, "restore-original-$token")
        DatabaseDurability.atomicWrite(
            restoreRecord,
            JSONObject().put("schema", 2).put("token", token)
                .put("snapshotId", snapshot.id).put("snapshotDigest", snapshot.digest)
                .put("generationUuid", generationUuid)
                .put("providerClosureSha256", identity.providerClosureSha256)
                .put("candidate", candidate.name).put("quarantine", quarantine.name)
                .put("phase", "PREPARING").toString(),
        )
        activeRestoreToken = token
        try {
            snapshotStore.restore(snapshot, candidate, databaseStopped = true)
            // The generation probe (the Android twin's mode-neutral shape):
            // the seals outside the datadir pin THIS generation, so a
            // candidate carrying any other generation's marker would strand
            // the lane (an initialized seal it can never satisfy) — refuse
            // before the live datadir moves at all.
            check(
                DatabaseDurableState.generationUuid(
                    File(candidate, GENERATION_MARKER_NAME).takeIf(File::isFile)?.readText(),
                ) == generationUuid,
            ) {
                "DB-SNAPSHOT: restored candidate generation mismatch"
            }
            atomicMoveDir(datadir, quarantine)
            atomicMoveDir(candidate, datadir)
            DatabaseDurability.atomicWrite(
                restoreRecord,
                JSONObject(restoreRecord.readText()).put("phase", "CANDIDATE_ACTIVE").toString(),
            )
            DatabaseDurability.atomicWrite(
                cleanMarker,
                DatabaseDurableState.cleanSeal(
                    identity, requireGenerationUuid(), System.currentTimeMillis(),
                    "restoreCandidate", snapshot.id,
                ),
            )
            JSONObject().put("ok", true).put("restoreToken", token)
                .put("snapshotId", snapshot.id).put("snapshotDigest", snapshot.digest)
                .put("requiresWorldReady", true)
        } catch (failure: Throwable) {
            activeRestoreToken = null
            runCatching { rollbackRestoreInternal(token) }
            throw failure
        }
    }

    /** The COMMIT leg: the restored candidate proved itself world-ready and
     * clean-stopped; the quarantine copy is retired and the record cleared. */
    fun commitRestore(restoreToken: String): JSONObject = synchronized(lock) {
        requireStopped()
        val record = requireRestore(restoreToken)
        check(cleanGeneration()) { "DB-SNAPSHOT: restored candidate did not clean-stop" }
        check(requireGenerationUuid() == record.getString("generationUuid")) {
            "DB-SNAPSHOT: restored candidate generation changed before commit"
        }
        val quarantine = File(databaseRoot, record.getString("quarantine"))
        check(quarantine.isDirectory) { "DB-SNAPSHOT: pre-restore safety copy is missing" }
        DatabaseDurability.atomicWrite(
            restoreRecord,
            JSONObject(record.toString()).put("phase", "COMMITTING").toString(),
        )
        finishRestoreCommit(requireRestore(restoreToken))
        activeRestoreToken = null
        JSONObject().put("ok", true).put("committed", true)
            .put("snapshotId", record.getString("snapshotId"))
    }

    /**
     * The ROLLBACK leg: a failure after the swap puts the original datadir
     * back (a record already COMMITTING finishes its commit instead — the
     * crash window between the phase write and the record's retirement).
     */
    fun rollbackRestore(restoreToken: String): JSONObject = synchronized(lock) {
        requireStopped()
        val record = requireRestore(restoreToken)
        if (DatabaseDurableState.restoreRecovery(record.toString()) ==
            DatabaseDurableState.RestoreRecovery.FINISH_COMMIT
        ) {
            finishRestoreCommit(record)
            activeRestoreToken = null
            JSONObject().put("ok", true).put("rolledBack", false).put("committed", true)
        } else {
            rollbackRestoreInternal(restoreToken)
            activeRestoreToken = null
            JSONObject().put("ok", true).put("rolledBack", true)
        }
    }

    /**
     * The ownerless crash-recovery verb (the Android rollbackPendingRestore
     * twin — the shared machine's ROLLBACK_PENDING_RESTORE driver): settles
     * a durable restore record a DEAD process left behind with NO caller
     * token — the in-process one died with the crash, so the record itself
     * is the authority. Its durable phase names the direction (the shared
     * [DatabaseDurableState.restoreRecovery] verdict): COMMITTING finishes
     * the commit (the crash window between the phase write and the record's
     * retirement), anything else rolls the quarantined original back.
     */
    fun rollbackPendingRestore(): JSONObject = synchronized(lock) {
        requireStopped()
        if (!restoreRecord.isFile) return@synchronized JSONObject().put("ok", true).put("pending", false)
        val record = requireRestore(JSONObject(restoreRecord.readText()).getString("token"))
        if (DatabaseDurableState.restoreRecovery(record.toString()) ==
            DatabaseDurableState.RestoreRecovery.FINISH_COMMIT
        ) {
            finishRestoreCommit(record)
            activeRestoreToken = null
            JSONObject().put("ok", true).put("pending", true)
                .put("rolledBack", false).put("committed", true)
        } else {
            rollbackRestoreInternal(record.getString("token"))
            activeRestoreToken = null
            JSONObject().put("ok", true).put("pending", true).put("rolledBack", true)
        }
    }

    /** The restore record's exact-shape proof (the Android requireRestore
     * twin): token, phase, digest, provider closure, generation, and the
     * databaseRoot-confined tree names must all still hold. */
    private fun requireRestore(token: String): JSONObject {
        check(restoreRecord.isFile) { "DB-SNAPSHOT: no restore verification is pending" }
        val record = JSONObject(restoreRecord.readText())
        check(record.length() == 9 && record.getInt("schema") == 2)
        check(UUID.fromString(token).toString() == token && record.getString("token") == token) {
            "DB-SNAPSHOT: restore token mismatch"
        }
        check(record.getString("phase") in setOf("PREPARING", "CANDIDATE_ACTIVE", "COMMITTING"))
        check(SHA256.matches(record.getString("snapshotDigest")))
        check(record.getString("providerClosureSha256") == identity().providerClosureSha256)
        check(
            DatabaseDurableState.generationUuid(
                DatabaseDurableState.generationMarker(record.getString("generationUuid")),
            ) == record.getString("generationUuid"),
        )
        listOf("candidate", "quarantine").forEach { key ->
            val name = record.getString(key)
            check(SAFE_FILE.matches(name) && File(databaseRoot, name).parentFile == databaseRoot)
        }
        if (record.getString("phase") != "COMMITTING") {
            val snapshot = snapshotStore.load(record.getString("snapshotId"))
            check(snapshot.digest == record.getString("snapshotDigest")) {
                "DB-SNAPSHOT: restore transaction snapshot digest drift"
            }
        }
        return record
    }

    /** The rollback body: original back in, candidate retired, clean seal
     * re-stamped for the restored generation (the rollbackToSnapshot
     * machinery's restamp discipline). */
    private fun rollbackRestoreInternal(token: String) {
        val record = requireRestore(token)
        check(record.getString("phase") != "COMMITTING") {
            "DB-SNAPSHOT: committing restore cannot roll back"
        }
        requireDaemonDrained("restore rollback")
        val candidate = File(databaseRoot, record.getString("candidate"))
        val quarantine = File(databaseRoot, record.getString("quarantine"))
        if (quarantine.isDirectory) {
            deleteTreeDurably(datadir)
            atomicMoveDir(quarantine, datadir)
        }
        deleteTreeDurably(candidate)
        check(datadir.isDirectory) { "DB-SNAPSHOT: rollback has no active datadir" }
        DatabaseDurability.atomicWrite(
            cleanMarker,
            DatabaseDurableState.cleanSeal(
                identity(), requireGenerationUuid(), System.currentTimeMillis(),
                "restoreRolledBack", record.getString("snapshotId"),
            ),
        )
        durableDelete(restoreRecord)
    }

    /** The commit's retirement: quarantine + candidate trees and the
     * record itself, only over a verified-and-clean candidate generation. */
    private fun finishRestoreCommit(record: JSONObject) {
        check(record.getString("phase") == "COMMITTING")
        requireDaemonDrained("restore commit")
        check(requireGenerationUuid() == record.getString("generationUuid") && cleanGeneration()) {
            "DB-SNAPSHOT: committing candidate generation is not verified and clean"
        }
        deleteTreeDurably(File(databaseRoot, record.getString("quarantine")))
        deleteTreeDurably(File(databaseRoot, record.getString("candidate")))
        durableDelete(restoreRecord)
    }

    // ------------------------------------------------------------------
    // Timeframe stage overlay (the MariaDB lane's world-start leg)
    // ------------------------------------------------------------------

    /**
     * The MariaDB lane's timeframe-overlay leg (design-timeframe
     * §2/§4.3), the twin of the SQLite seam's DesktopTimeframeOverlay
     * execution: bring the LIVE MariaDB generation from its recorded
     * stage to [desiredStage] at world start, BEFORE the native boot.
     * The applied-stage record lives in the pocketrealm_meta schema
     * (timeframe_stage_state, beside the migration ledger); ANY failure
     * throws loudly so the world start refuses — never boot a world
     * whose DB state contradicts the conf's PocketRealm.TimeframeStage.
     *
     * Provider-aware by construction, in both directions:
     *  - the statements are the SAME pinned catalog plan
     *    (TimeframeOverlayPlan.statementsFor, policy-checked) translated
     *    to MariaDB's INSERT IGNORE dialect — the caller passes a stage
     *    id, never SQL (the engine's no-injected-SQL law);
     *  - the SQLite lane's PRAGMA connection discipline (journal_mode,
     *    the synchronous-class settings — the DB-CONFIG refusal family)
     *    has NO analogue on this lane: MariaDB's durability is the
     *    daemon's own my.cnf (DatabaseConfigPolicy renders
     *    innodb-flush-log-at-trx-commit=1 / sync-binlog=0 per provider),
     *    so the client session carries no per-connection settings at
     *    all. Nothing here emits PRAGMA or SET SESSION.
     *
     * Partial application is safe by the plan's own design: every
     * statement is an idempotent INSERT IGNORE and the stage row is
     * written only after every statement succeeded, so a mid-overlay
     * crash retries cleanly at the next world start.
     */
    @Suppress("LongMethod") // one reviewed world-start verb: gate, ledger read, both stage arms
    fun applyTimeframeOverlay(desiredStage: String): JSONObject = synchronized(lock) {
        check(state == State.RUNNING) { "database is not running" }
        // The stage ledger rides the engine's own meta schema, owner-
        // privileged like every migration-ledger write.
        mariadbMetaExec("timeframe-stage-table", TimeframeOverlayPlan.MARIADB_STAGE_STATE_DDL)
        val recorded = mariadbAppliedStage()
        if (recorded?.appliedStage == desiredStage) {
            return JSONObject()
                .put("ok", true)
                .put("appliedStage", recorded.appliedStage)
                .put("overlaySha256", recorded.overlaySha256)
                .put("appliedAtMs", recorded.appliedAtMs)
                .put("idempotent", true)
        }
        if (TimeframeOverlayPlan.isDefaultStage(desiredStage)) {
            // The seed IS the default stage: record, touch no world DB.
            val appliedAt = System.currentTimeMillis()
            mariadbMetaExec(
                "timeframe-stage-record",
                TimeframeOverlayPlan.mariadbStageStateUpsert(desiredStage, "default-seed", appliedAt),
            )
            return JSONObject()
                .put("ok", true)
                .put("appliedStage", desiredStage)
                .put("overlaySha256", "default-seed")
                .put("appliedAtMs", appliedAt)
        }
        val overlaySha256 = TimeframeOverlayPlan.overlaySha256(desiredStage)
            ?: throw IllegalStateException(TimeframeOverlayPlan.unstagedRefusal(desiredStage))
        val statements = TimeframeOverlayPlan.toMariaadbDialect(
            TimeframeOverlayPlan.statementsFor(desiredStage),
            desiredStage,
        )
        val result = runFixedSql(
            "pocket_core", readSecrets().core,
            "timeframe-overlay",
            statements.joinToString(separator = "\n"),
            TimeframeOverlayPlan.worldDatabase(),
        )
        check(result.ok) {
            "timeframe overlay for \"$desiredStage\" failed exit=${result.exitCode}: " +
                result.stderr.takeLast(1200)
        }
        val appliedAt = System.currentTimeMillis()
        mariadbMetaExec(
            "timeframe-stage-record",
            TimeframeOverlayPlan.mariadbStageStateUpsert(desiredStage, overlaySha256, appliedAt),
        )
        JSONObject()
            .put("ok", true)
            .put("appliedStage", desiredStage)
            .put("overlaySha256", overlaySha256)
            .put("appliedAtMs", appliedAt)
            .put("statements", statements.size)
    }

    /** The recorded applied stage, or null when the ledger table holds no
     * row yet (an empty client answer is data, not an error — the
     * ledgerStatus null discipline). */
    private fun mariadbAppliedStage(): MariadbAppliedStage? {
        val result = runFixedSql(
            "pocket_admin", readSecrets().admin,
            "timeframe-stage-read",
            TimeframeOverlayPlan.MARIADB_APPLIED_STAGE_QUERY,
        )
        check(result.ok) { "timeframe overlay: applied-stage read failed: ${result.stderr.takeLast(800)}" }
        val columns = result.stdout.trim().split('\t')
        if (columns.size < 3 || columns[0].isEmpty()) return null
        return MariadbAppliedStage(
            appliedStage = columns[0],
            overlaySha256 = columns[1],
            appliedAtMs = columns[2].toLongOrNull() ?: 0L,
        )
    }

    /** One owner-privileged meta statement (the migration-ledger legs'
     * user and fail-loud shape). */
    private fun mariadbMetaExec(name: String, sql: String) {
        val result = runFixedSql("pocket_admin", readSecrets().admin, name, sql)
        check(result.ok) {
            "timeframe overlay: meta statement $name failed: ${result.stderr.takeLast(800)}"
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle close
    // ------------------------------------------------------------------

    fun close() {
        synchronized(lock) {
            if (state == State.RUNNING || state == State.STARTING || state == State.STOPPING) {
                runCatching { cancelAndRequireDaemonDrained("engine close") }
            }
        }
    }

    // ------------------------------------------------------------------
    // Internals — daemon, tools, config
    // ------------------------------------------------------------------

    @Suppress("LongMethod", "TooGenericExceptionCaught")
    // the gated daemon start's admission order (the Android twin's shape);
    // the spawn-failure leg rethrows the seam's own failure as-is.
    private fun startDaemon(
        allowUnsealedBootstrap: Boolean = false,
        allowDirtyRecovery: Boolean = false,
        allowedTransactionKind: String? = null,
    ): JSONObject {
        check(!(allowUnsealedBootstrap && allowDirtyRecovery)) {
            "database start cannot be both bootstrap and recovery"
        }
        requireProvider()
        if (laneTransactionFile().exists()) {
            val transaction = JSONObject(laneTransactionFile().readText())
            check(transaction.optString("kind") == allowedTransactionKind) {
                "DB-TRANSACTION: pending ${transaction.optString("kind")} transaction blocks daemon start"
            }
        } else {
            check(allowedTransactionKind == null) {
                "DB-TRANSACTION: expected $allowedTransactionKind transaction is missing"
            }
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
        // Delete stale control files only after independent thread/PID drain
        // proof. Their presence must never be treated as permission.
        pidFile.delete()
        checkStorage(MIN_START_BYTES)
        durableDelete(cleanMarker)
        writeConfig()
        projectedRealmEndpoint = null
        state = State.STARTING
        val pid = try {
            // R3-r2-1: the daemon capture rides the logs root —
            // override-honoring, never java.io.tmpdir (%TEMP% on C: in the
            // rpHost/jpackage lanes).
            processes.startDaemon(
                providerClosure.mariadbd,
                serverBaseArgs(),
                outputRedirect = File(roots.logs.apply { mkdirs() }, "pocket-mariadbd.out"),
                errorRedirect = File(roots.logs.apply { mkdirs() }, "pocket-mariadbd.err"),
            )
        } catch (failure: Throwable) {
            // The spawn itself died before any pid was tracked: nothing was
            // started, so the verdict is FAILED (every admission reads the
            // state) — never a stranded STARTING over a daemon that never
            // came up. The clean marker is already gone (deleted above), so
            // the generation reads dirty for the recovery lane, honestly.
            state = State.FAILED
            throw failure
        }
        daemonPid = pid
        DatabaseDurability.atomicWrite(pidFile, pid.toString())
        state = State.RUNNING
        val deadline = System.currentTimeMillis() + SOCKET_READY_TIMEOUT_MS
        while (!pipeObservable() && System.currentTimeMillis() < deadline) Thread.sleep(SOCKET_POLL_MS)
        if (!pipeObservable()) {
            state = State.FAILED
            cancelAndRequireDaemonDrained("pipe readiness failure")
            throw IllegalStateException(
                "DB-SOCKET: named pipe did not become ready: " + errorLogTail(),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("state", state.name)
            .put("socket", DesktopMariaDbProvider.PIPE_NAME)
            .put("tcpDisabled", true)
    }

    /** my.cnf from the SHARED policy (the windows-x86_64 branch renders the
     * named-pipe enablement under the same skip-networking pin). */
    private fun writeConfig() {
        runDir.mkdirs()
        datadir.mkdirs()
        DatabaseDurability.atomicWrite(
            configFile,
            DatabaseConfigPolicy.render(
                abi = DesktopMariaDbProvider.EXPECTED_ABI,
                datadir = datadir.absolutePath,
                socket = DesktopMariaDbProvider.PIPE_NAME,
                pidFile = pidFile.absolutePath,
                errorLog = errorLog.absolutePath,
                secureFileDirectory = secureImportDir.apply { mkdirs() }.absolutePath,
            ),
        )
    }

    /** The server argv (the Android serverBaseArgs twin; the pipe name rides
     * both the conf and the argument set). */
    private fun serverBaseArgs(): List<String> = listOf(
        "--defaults-file=${configFile.absolutePath}",
        "--basedir=${providerClosure.root.absolutePath}",
        "--datadir=${datadir.absolutePath}",
        "--plugin-dir=${File(providerClosure.root, "plugin").absolutePath}",
        "--lc-messages-dir=${File(providerClosure.root, "share/mysql").absolutePath}",
        "--socket=${DesktopMariaDbProvider.PIPE_NAME}",
        "--pid-file=${pidFile.absolutePath}",
        "--log-error=${errorLog.absolutePath}",
        "--skip-networking", "--skip-name-resolve", "--enable-named-pipe",
    )

    private fun runClient(
        user: String,
        password: String,
        sql: File,
        database: String? = null,
        timeoutMs: Long = CLIENT_TIMEOUT_MS,
    ): DatabaseRunResult {
        val args = buildList {
            addAll(
                listOf(
                    "--no-defaults", "--protocol=pipe", "--socket=${DesktopMariaDbProvider.PIPE_NAME}",
                    "--user=$user", "--batch", "--skip-column-names",
                ),
            )
            if (database != null) {
                check(DATABASE_NAME.matches(database)) { "invalid fixed database name" }
                add("--database=$database")
            }
        }
        val environment = if (password.isEmpty()) emptyMap() else mapOf("MYSQL_PWD" to password)
        return processes.runTool(providerClosure.mariadbClient, args, environment, sql, timeoutMs)
    }

    private fun fixedSql(name: String, sql: String): File = File(runDir, "$name.sql").also {
        DatabaseDurability.atomicWrite(it, sql.trimEnd() + "\n")
    }

    /** One fixed-SQL client run whose transcript never outlives the verb:
     * the run-dir SQL file the client consumed is deleted in a finally —
     * whether the run succeeded, failed, or threw — the migration and
     * overlay legs' delete discipline as a single seam. The initial-auth
     * transcript embeds the generated credentials in plaintext, and every
     * other fixed statement is engine-owned SQL anyway, so no .sql
     * transcript may persist in the run dir past the call that wrote it
     * (secrets.json stays the only at-rest credential copy). */
    @Suppress("LongParameterList") // runClient's own shape plus the fixed name
    private fun runFixedSql(
        user: String,
        password: String,
        name: String,
        sql: String,
        database: String? = null,
        timeoutMs: Long = CLIENT_TIMEOUT_MS,
    ): DatabaseRunResult {
        val file = fixedSql(name, sql)
        try {
            return runClient(user, password, file, database, timeoutMs)
        } finally {
            file.delete()
        }
    }

    private fun createSecrets(): Secrets {
        val secrets = Secrets(admin = randomSecret(), core = randomSecret())
        DatabaseDurability.atomicWrite(
            secretFile,
            JSONObject()
                .put("schema", 1)
                .put("admin", secrets.admin)
                .put("core", secrets.core)
                .toString(),
        )
        return secrets
    }

    private fun readSecrets(): Secrets {
        check(secretFile.isFile) { "DB-SECRET: credential record is missing" }
        val text = secretFile.readText()
        // R2-r5-1 (code-red round 5): the android twin validates the record
        // on every read (DatabaseGenerationSeal.validSecrets) — the desktop
        // lane now does too, so a corrupted or hand-edited secrets.json
        // refuses loud here instead of flowing verbatim into root-context
        // bootstrap SQL.
        check(DatabaseGenerationSeal.validSecrets(text)) {
            "DB-SECRET: credential record is invalid (expected schema 1 with " +
                "two distinct hex-48 secrets)"
        }
        val record = JSONObject(text)
        return Secrets(admin = record.getString("admin"), core = record.getString("core"))
    }

    // ------------------------------------------------------------------
    // Migration ledger legs
    // ------------------------------------------------------------------

    private fun createLedger() {
        val result = runFixedSql(
            "pocket_admin", readSecrets().admin,
            "create-ledger",
            """
            CREATE TABLE IF NOT EXISTS pocketrealm_meta.${laneLedgerTable()} (
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
            """.trimIndent(),
        )
        check(result.ok) { "DB-REVISION: cannot create ledger: ${result.stderr.takeLast(800)}" }
    }

    private fun ledgerStatus(id: String): Pair<String, String>? {
        val result = runFixedSql(
            "pocket_admin", readSecrets().admin,
            "ledger-status",
            "SELECT status,sql_sha256 FROM pocketrealm_meta.${laneLedgerTable()} WHERE migration_id='$id';",
        )
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
        val result = runFixedSql(
            "pocket_admin", readSecrets().admin,
            "ledger-pending",
            """
            INSERT INTO pocketrealm_meta.${laneLedgerTable()}
              (migration_id,component,source_commit,target_commit,sql_sha256,started_at,status,pre_snapshot_id,app_build_id)
            VALUES ('$id','$component','$sourceCommit','$targetCommit','${entry.getString("sql_sha256")}',${System.currentTimeMillis()},'PENDING','$snapshotId','$buildId');
            """.trimIndent(),
        )
        check(result.ok) { "DB-REVISION: ledger PENDING write failed for $id" }
    }

    private fun ledgerFinish(id: String, status: String, digest: String) {
        check(status == "APPLIED" || status == "FAILED")
        val result = runFixedSql(
            "pocket_admin", readSecrets().admin,
            "ledger-finish",
            "UPDATE pocketrealm_meta.${laneLedgerTable()} SET status='$status'," +
                "finished_at=${System.currentTimeMillis()},result_digest='$digest' " +
                "WHERE migration_id='$id';",
        )
        check(result.ok) { "DB-REVISION: ledger final write failed for $id" }
    }

    /** Decompress + digest-verify one staged migration (the Android
     * materializeMigration leg against the staging tree instead of APK
     * assets). */
    @Suppress("NestedBlockDepth") // the digest streaming pipeline is the Android twin's shape
    private fun materializeMigration(closure: DesktopMariaDbProvider.Closure, entry: JSONObject): File {
        val id = entry.getString("migration_id")
        check(id.matches(MIGRATION_ID)) { "DB-REVISION: unsafe migration id" }
        val staged = closure.migrationAssetFor(laneFlavorId(), id)
        check(staged.isFile) { "DB-REVISION: staged migration bytes are missing for $id" }
        val output = File(secureImportDir, "$id.sql")
        output.parentFile?.mkdirs()
        val compressedDigest = java.security.MessageDigest.getInstance("SHA-256")
        val sqlDigest = java.security.MessageDigest.getInstance("SHA-256")
        var sqlSize = 0L
        staged.inputStream().use { raw ->
            java.security.DigestInputStream(raw, compressedDigest).use { checkedRaw ->
                java.util.zip.GZIPInputStream(checkedRaw).use { zipped ->
                    java.io.FileOutputStream(output).use { destination ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zipped.read(buffer)
                            if (count < 0) break
                            destination.write(buffer, 0, count)
                            sqlDigest.update(buffer, 0, count)
                            sqlSize += count
                        }
                        destination.fd.sync()
                    }
                }
            }
        }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        check(hex(compressedDigest.digest()) == entry.getString("asset_sha256")) {
            "DB-REVISION: asset hash mismatch"
        }
        check(hex(sqlDigest.digest()) == entry.getString("sql_sha256") && sqlSize == entry.getLong("sql_size")) {
            "DB-REVISION: SQL hash/size mismatch"
        }
        return output
    }
    private fun verifyExpectedRevisions(manifest: JSONObject): JSONObject {
        val expected = manifest.getJSONObject("expected_revisions")
        // W4 (D72 retirement): the revision probes run against the LANE'S
        // OWN database family — the former vanilla literals queried
        // classicrealmd for the tbc lane's revision column and refused
        // every non-vanilla apply ("realm missing
        // required_s2474_01_realmd_joindate_datetime").
        val names = bootstrapDatabaseNames()
        val checks = listOf(
            Triple("realm", names.realmd, "realmd_db_version"),
            Triple("characters", names.characters, "character_db_version"),
            Triple("logs", names.logs, "logs_db_version"),
            Triple("world", names.world, "db_version"),
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
        val result = runFixedSql(
            "pocket_admin", readSecrets().admin,
            "revision-check",
            "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$database' " +
                "AND TABLE_NAME='$table' AND COLUMN_NAME='$column';",
        )
        return result.ok && result.stdout.trim() == "1"
    }

    // ------------------------------------------------------------------
    // Crash-window recovery legs
    // ------------------------------------------------------------------

    private fun recoverIncompleteInitialization() {
        if (!laneTransactionFile().isFile) return
        val raw = laneTransactionFile().readText()
        if (runCatching { JSONObject(raw).optString("kind") }.getOrNull() != "INIT") return
        val action = DatabaseDurableState.initRecovery(
            recordText = raw,
            datadirGenerationText = generationMarker.takeIf(File::isFile)?.readText(),
            datadirEmpty = datadir.listFiles().isNullOrEmpty(),
            identity = identity(),
            initializedCurrent = initialized(),
            cleanCurrent = cleanGeneration(),
        )
        when (action) {
            DatabaseDurableState.InitRecovery.NONE -> Unit
            DatabaseDurableState.InitRecovery.KEEP_COMPLETED -> durableDelete(laneTransactionFile())
            DatabaseDurableState.InitRecovery.QUARANTINE_AND_RETRY -> {
                requireDaemonDrained("incomplete initialization recovery")
                val generation = DatabaseDurableState.parseTransaction(raw, "INIT", identity())
                    ?.getString("generationUuid") ?: error("DB-INIT: init transaction ownership is invalid")
                if (!datadir.listFiles().isNullOrEmpty()) {
                    val quarantine = File(
                        databaseRoot,
                        "init-quarantine-$generation-${System.currentTimeMillis()}",
                    )
                    atomicMoveDir(datadir, quarantine)
                } else {
                    deleteTreeDurably(datadir)
                }
                listOf(initializedMarker, cleanMarker, secretFile).forEach(::durableDelete)
                datadir.mkdirs()
                DatabaseDurability.syncDirectory(databaseRoot)
                durableDelete(laneTransactionFile())
            }
            DatabaseDurableState.InitRecovery.FAIL_CLOSED -> error(
                "DB-INIT: nonempty datadir is not owned by the exact durable init transaction",
            )
        }
    }

    private fun recoverPendingMigrationTransaction(): Boolean {
        if (!laneTransactionFile().isFile) return false
        val raw = laneTransactionFile().readText()
        // R3-r3-fix: the pending record was written with the LANE identity
        // on a non-vanilla lane (R3-2) — the recovery proof must use the
        // same identity, not the shared vanilla one.
        val recordIdentity = transactionRecordIdentity(raw)
        val action = DatabaseDurableState.migrationRecovery(
            raw, generationMarker.takeIf(File::isFile)?.readText(), recordIdentity,
        )
        check(action == DatabaseDurableState.MigrationRecovery.RESTORE_AND_RETRY) {
            "DB-TRANSACTION: pending transaction cannot be proven as this generation's migration"
        }
        val record = checkNotNull(
            DatabaseDurableState.parseCompatibleMigrationTransaction(raw, recordIdentity),
        )
        val snapshot = snapshotStore.load(record.getString("snapshotId"))
        check(snapshot.digest == record.getString("snapshotDigest")) {
            "DB-TRANSACTION: pre-migration snapshot manifest digest mismatch"
        }
        val compatibility = JSONObject(snapshot.manifest.readText()).getJSONObject("compatibility")
        check(
            DatabaseDurableState.migrationSnapshotCompatible(
                raw, compatibility, recordIdentity, record.getString("generationUuid"),
            ),
        ) { "DB-REVISION: pre-migration snapshot historical compatibility mismatch" }
        requireDaemonDrained("interrupted migration recovery")
        rollbackToSnapshot(recordIdentity, snapshot)
        durableDelete(laneTransactionFile())
        return true
    }

    private fun updateDatabaseTransactionPhase(kind: String, phase: String) {
        check(laneTransactionFile().isFile) { "DB-TRANSACTION: transaction record missing" }
        val raw = laneTransactionFile().readText()
        val identity = transactionRecordIdentity(raw)
        check(DatabaseDurableState.parseTransaction(raw, kind, identity) != null) {
            "DB-TRANSACTION: transaction identity drift"
        }
        DatabaseDurability.atomicWrite(
            laneTransactionFile(),
            DatabaseDurableState.withTransactionPhase(raw, kind, phase),
        )
    }

    private fun transactionRecordIdentity(raw: String): DatabaseDurableState.Identity {
        // A pending transaction's own provider field owns it; the sqlite
        // provider id names the OTHER lane's records and must never be
        // proven against this engine's identity.
        val provider = runCatching { JSONObject(raw).getString("provider") }.getOrNull()
        check(provider != DatabaseRuntimeContract.SQLITE_PROVIDER_ID) {
            "DB-TRANSACTION: a sqlite-lane transaction cannot recover through the MariaDB engine"
        }
        // R3-r3-fix: the record's KIND owns its identity scheme — a
        // MIGRATION record was written with the LANE identity (R3-2),
        // an INIT record with the shared one — prove each against its
        // own writer's scheme.
        val kind = runCatching { JSONObject(raw).optString("kind") }.getOrNull()
        return if (kind == "MIGRATION") migrationRecordIdentity() else identity()
    }

    private fun databaseCompatibility(
        generationUuid: String,
        identity: DatabaseDurableState.Identity = identity(),
    ): JSONObject {
        return JSONObject()
            .put("provider", identity.providerId)
            // W4 (R4-r2-2): the lane rides the stamp so per-lane retention
            // and the restore compatibility gate can group by flavor.
            .put("flavor", laneFlavorId())
            .put("providerClosureSha256", identity.providerClosureSha256)
            .put("bootstrapSha256", identity.bootstrapSha256)
            .put("migrationManifestSha256", identity.migrationManifestSha256)
            .put("migrationCount", identity.migrationCount)
            .put("generationUuid", generationUuid)
    }

    private fun databaseTransactionKind(): String? =
        if (!laneTransactionFile().isFile) {
            null
        } else {
            runCatching {
                DatabaseDurableState.transactionKind(laneTransactionFile().readText())
            }.getOrDefault("UNKNOWN")
        }

    private fun rollbackToSnapshot(
        recordIdentity: DatabaseDurableState.Identity,
        snapshot: DatabaseSnapshotStore.Snapshot,
    ) {
        if (state == State.RUNNING || state == State.STARTING || state == State.STOPPING) {
            cancelAndRequireDaemonDrained("migration rollback")
        }
        state = State.STOPPED
        requireDaemonDrained("migration rollback")
        pidFile.delete()
        durableDelete(cleanMarker)
        if (datadir.exists()) deleteTreeDurably(datadir)
        snapshotStore.restore(snapshot, datadir, databaseStopped = true)
        DatabaseDurability.atomicWrite(
            cleanMarker,
            DatabaseDurableState.cleanSeal(
                recordIdentity, requireGenerationUuid(), System.currentTimeMillis(),
                "restoredSnapshot", snapshot.id,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Seals, identity, markers
    // ------------------------------------------------------------------

    private fun requireStopped() {
        check(state == State.STOPPED || state == State.FAILED) {
            "operation requires stopped database, state=$state"
        }
        requireDaemonDrained("stopped-state mutation")
    }

    private fun identity(): DatabaseDurableState.Identity = providerClosure.identity()

    /**
     * R3-r3-fix (W4 code-red round 3): the identity a MIGRATION
     * transaction record is proven against must be the one its writer
     * used — applyPinnedMigrations writes the LANE identity (the lane
     * manifest's own digest + count) into the lane journal on
     * non-vanilla lanes (R3-2), so the phase-update and recovery reads
     * must resolve the same lane identity. Proving a lane record against
     * the shared (vanilla 414-entry) identity is exactly the
     * "DB-TRANSACTION: transaction identity drift" that refused every
     * non-vanilla apply one phase after SNAPSHOT_READY.
     */
    private fun migrationRecordIdentity(): DatabaseDurableState.Identity {
        val lane = laneFlavorId()
        return if (lane == "vanilla") identity()
        else runCatching { providerClosure.laneMigrationReader(lane).second }
            .getOrDefault(identity())
    }

    private fun requireProvider() {
        // Force the closure's verification and let its PRECISE DB-LINK
        // copy surface (unstaged / lockfile drift / digest mismatch) —
        // never the generic not-staged mask over a different failure.
        providerClosure
    }

    private fun providerReady(): Boolean = runCatching { providerClosure }.isSuccess

    private fun generationUuid(): String? = DatabaseDurableState.generationUuid(
        generationMarker.takeIf(File::isFile)?.readText(),
    )

    private fun requireGenerationUuid(): String = checkNotNull(generationUuid()) {
        "DB-GENERATION: durable datadir generation marker is invalid"
    }

    private fun initialized(): Boolean = runCatching {
        val mysqlDir = File(datadir, "mysql")
        DatabaseDurableState.initializedCurrent(
            initializedMarker.takeIf(File::isFile)?.readText(), identity(), generationUuid(),
        ) && datadir.isDirectory && mysqlDir.isDirectory && mysqlDir.list().orEmpty().any {
            it.startsWith("global_priv.") || it.startsWith("user.")
        } && File(datadir, "mariadb_upgrade_info").takeIf(File::isFile)?.readText()?.trim() ==
            "$PROVIDER_VERSION-MariaDB" &&
            DatabaseGenerationSeal.validSecrets(secretFile.takeIf(File::isFile)?.readText())
    }.getOrDefault(false)

    private fun cleanGeneration(): Boolean = runCatching {
        DatabaseDurableState.cleanCurrent(
            cleanMarker.takeIf(File::isFile)?.readText(), identity(), generationUuid(),
        )
    }.getOrDefault(false)

    private fun migrationsCurrent(): Boolean = initialized() && runCatching {
        // W4 (D72 retirement): the LANE's seal marker and the LANE's own
        // identity — a lane whose ledger has never applied is NOT current
        // even though the vanilla marker (the first-initialized lane's) is
        // present, and a re-staged lane ledger (new digest) goes stale the
        // same way (R3-2).
        val lane = laneFlavorId()
        // R3-2: the LANE identity is only read for NON-vanilla lanes — the
        // vanilla marker keeps the shared identity and never touches the
        // per-lane reader (which would demand a sibling manifest the
        // vanilla lane legitimately does not stage).
        val laneIdentity = if (lane == "vanilla") identity() else {
            providerClosure.laneMigrationReader(lane).second
        }
        DatabaseDurableState.migrationsCurrent(
            laneMigrationMarker().takeIf(File::isFile)?.readText(),
            laneIdentity, generationUuid(),
        )
    }.getOrDefault(false)

    private fun migrationSealedCount(): Int? = runCatching {
        if (!migrationsCurrent()) return@runCatching null
        identity().migrationCount
    }.getOrNull()

    private fun writeActiveProviderMarker(mode: DatabaseDurableState.ProviderMode, generationUuid: String) {
        DatabaseDurability.atomicWrite(
            activeProviderMarker,
            DatabaseDurableState.activeProviderMarker(mode, identity().providerId, generationUuid),
        )
    }

    // ------------------------------------------------------------------
    // Drain proofs
    // ------------------------------------------------------------------

    private fun daemonAlive(): Boolean = daemonPid?.let { processes.alive(it) } == true

    private fun pidProcessExists(): Boolean {
        val pid = pidFile.takeIf(File::isFile)?.readText()?.trim()?.toLongOrNull() ?: return false
        return pid > 1 && processes.alive(pid)
    }

    private fun requireDaemonDrained(operation: String) {
        check(
            DatabaseMutationGate.permits(
                lifecycleStopped = state == State.STOPPED || state == State.FAILED,
                runnerThreadAlive = false,
                nativeProcessGroupDrained = !daemonAlive(),
                pidProcessExists = pidProcessExists(),
            ),
        ) { "DB-DRAIN: cannot prove MariaDB process tree stopped before $operation" }
    }

    private fun cancelAndRequireDaemonDrained(operation: String) {
        daemonPid?.let { processes.killTree(it) }
        awaitDaemonExit(KILL_DRAIN_TIMEOUT_MS)
        state = State.FAILED
        requireDaemonDrained(operation)
    }

    /** Bounded wait for the shutdown to finish and record the exit (the
     * Android daemon-thread join, against the seam's process handle). */
    private fun awaitDaemonExit(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (daemonAlive() && System.currentTimeMillis() < deadline) Thread.sleep(DRAIN_POLL_MS)
        val pid = daemonPid
        if (pid != null && !processes.alive(pid)) {
            daemonPid = null
            lastExit = 0
        }
    }

    private fun pipeObservable(): Boolean =
        processes.pipeExists(DesktopMariaDbProvider.PIPE_NAME)

    private fun errorLogTail(fromByte: Long = 0): String =
        if (!errorLog.isFile || errorLog.length() <= fromByte) {
            ""
        } else {
            errorLog.inputStream().use { stream ->
                stream.skip(fromByte)
                stream.readBytes().toString(Charsets.UTF_8)
            }
        }

    // ------------------------------------------------------------------
    // Durable filesystem helpers (ownership-rooted)
    // ------------------------------------------------------------------

    private fun durableDelete(target: File) = DatabaseDurability.delete(target)

    private fun atomicMoveDir(source: File, target: File) {
        check(source.parentFile == databaseRoot && target.parentFile == databaseRoot) {
            "DB-DURABILITY: database directory move escaped ownership root"
        }
        DatabaseDurability.atomicMove(source, target)
    }

    private fun deleteTreeDurably(target: File) {
        check(target.parentFile == databaseRoot) {
            "DB-DURABILITY: recursive delete escaped database ownership root"
        }
        if (!target.exists()) return
        check(target.deleteRecursively() && !target.exists()) {
            "DB-DURABILITY: could not retire ${target.name}"
        }
        DatabaseDurability.syncDirectory(databaseRoot)
    }

    private fun checkStorage(requiredBytes: Long) {
        val usable = runCatching { databaseRoot.usableSpace }.getOrDefault(-1L)
        check(usable >= requiredBytes) {
            "DB-STORAGE: ${requiredBytes / (1024L * 1024L)} MiB required, $usable usable"
        }
    }

    companion object {
        /** Android lane's storage gates, mirrored (the datadir plus daemon
         * working set lives in the same order of magnitude). */
        private const val MIN_INITIALIZE_BYTES = 768L * 1024 * 1024
        private const val MIN_START_BYTES = 128L * 1024 * 1024
        // R4-r2-3: the gate is a SNAPSHOT budget — applyPinnedMigrations
        // takes a full pre-migration datadir snapshot (measured ~1.76 GiB
        // on the W4 tbc/wotlk lanes) while the live datadir keeps its
        // bytes, and the post-migration family grows on top. 1.5 GiB sat
        // BELOW the measured snapshot cost; 3 GiB covers snapshot + live
        // datadir headroom on the largest qualified lane (wotlk).
        private const val MIN_MIGRATION_BYTES = 3L * 1024 * 1024 * 1024

        private const val BOOTSTRAP_TIMEOUT_MS = 180_000L
        private const val CLIENT_TIMEOUT_MS = 30_000L
        private const val SOCKET_READY_TIMEOUT_MS = 45_000L
        private const val SOCKET_POLL_MS = 100L

        /** The bounded stop-drain window (the Android 30 s stop join). */
        private const val DRAIN_TIMEOUT_MS = 30_000L
        private const val KILL_DRAIN_TIMEOUT_MS = 10_000L
        private const val DRAIN_POLL_MS = 100L

        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        private const val SECRET_HEX_CHARS = 48

        const val CLEAN_MARKER_NAME = "clean-stop.json"
        const val INITIALIZED_MARKER_NAME = "initialized.json"
        const val SECRETS_FILE_NAME = "secrets.json"
        const val DIRTY_RECORD_NAME = "recovery.json"
        const val TRANSACTION_FILE_NAME = "database-transaction.json"

        /** The durable restore-transaction record (the Android engine's
         * name): present exactly while a restore transaction is in its
         * BEGIN→COMMIT/ROLLBACK window, or after an interrupted one. */
        const val RESTORE_RECORD_NAME = "restore-transaction.json"
        const val GENERATION_MARKER_NAME = ".pocketrealm-generation.json"
        const val MIGRATION_MARKER_NAME = ".pocketrealm-migrations.json"

        /** The pinned provider version the identity records in
         * mariadb_upgrade_info and the seals (the shared contract). */
        const val PROVIDER_VERSION = DatabaseRuntimeContract.WINDOWS_PROVIDER_VERSION

        private val MIGRATION_ID = Regex("[A-Za-z0-9._-]{1,191}")
        private val DATABASE_NAME = Regex("[a-z][a-z0-9_]{0,63}")
        /** W4.2: the realmd account-token shape (1..16 ASCII alnum) the
         * verifier seeding interpolates — the core's own create-account
         * gate, mirrored here so a decorated name refuses before SQL. */
        private val ACCOUNT_TOKEN = Regex("[A-Za-z][A-Za-z0-9]{0,15}")
        private val SAFE_FILE = Regex("[A-Za-z0-9._+-]{1,255}")
        private val SHA256 = Regex("[0-9a-f]{64}")

        /** The realmlist realmbuilds column's advertisement shape: the
         * flavor's space-separated accepted-build list
         * (ClientFlavor.realmdAcceptedBuilds, e.g. "5875 6005 6141"),
         * bounded by the varchar(64) column. */
        private val REALMBUILDS = Regex("[0-9]{1,5}( [0-9]{1,5})*")
        private const val REALMBUILDS_MAX_CHARS = 64

        /** SecureRandom-hex secret (the Android createSecrets discipline:
         * 48 lowercase hex chars, the DatabaseGenerationSeal shape). */
        private fun randomSecret(): String {
            val bytes = ByteArray(SECRET_HEX_CHARS / 2)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Tiny file-digest alias so the engine body reads like the Android
 * engine's sha256() helpers without re-importing per call site. */
private object FileDigestsCompat {
    fun sha256(file: File): String = com.pocketrealm.fs.FileDigests.sha256(file)
    fun sha256(text: String): String = com.pocketrealm.fs.FileDigests.sha256(text)
}
