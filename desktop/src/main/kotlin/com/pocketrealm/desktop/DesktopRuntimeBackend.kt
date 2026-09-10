package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.database.DesktopSqliteConnection
import com.pocketrealm.database.DatabaseSqliteControlPlane.DATABASES
import com.pocketrealm.server.RealmNative
import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.server.ServerRuntimeFiles
import com.pocketrealm.server.WorldNative
import com.pocketrealm.supervisor.AccountProvisionResult
import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentObservation
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.RuntimeActionResult
import com.pocketrealm.supervisor.RuntimeBackend
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.client.ClientRealmEndpointProjection
import com.pocketrealm.supervisor.WorldPresenceSample
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The desktop RuntimeBackend: the four components served by ONE process
 * (the co-load gate proved both DLLs live in this JVM), where Android
 * spread them across services.
 *
 *  - DATABASE is the SQLite datadir materialized by the seed-replay twin
 *    (gradlew seedRealmData / the engine's first boot): no daemon exists,
 *    so "running" means every database file + generation marker verified
 *    present and no WAL sidecar left behind;
 *  - REALM/WORLD are the in-process native runtimes driven through the
 *    shared JNI shims with the Android services' exact transition
 *    discipline (stopped-state gate, log rotation between lifetimes,
 *    lifecycle records, CONTROL_TIMEOUT_MS stop);
 *  - CLIENT (WoW.exe via ProcessBuilder) arrives with Phase 4 — its verbs
 *    fail honestly until then, never fake success.
 *
 * Single-transition semantics mirror the services' transitionLock.
 */
@Suppress("TooManyFunctions") // the shared RuntimeBackend seam dictates the verb count
class DesktopRuntimeBackend(
    private val roots: DesktopStorageRoots = DesktopStorageRoots(),
    private val clientDir: File? = null,
) : RuntimeBackend {
    private val files = ServerRuntimeFiles(roots)
    private val transitionLock = Any()

    /** Per-component ownership slots mirroring the Android services'
     * ComponentOwnership handshake minus the IBinder lease: the owner's
     * liveness is this JVM itself. The shared supervisor's readiness proofs
     * (startStage), stopOwned guards, and orphan-heal policy all key off
     * observation.owner, so every start claims, every observe echoes, and
     * every owner-scoped verb verifies — null owners are only ever visible
     * on genuinely ownerless (stopped) components. */
    private val ownership = RuntimeComponent.entries.associateWith { OwnershipSlot(it.name) }

    @Volatile
    private var client: Process? = null

    /** The active prepared-data generation's runtime lease, held from
     * world start to world stop. */
    @Volatile
    private var worldDataLease: AutoCloseable? = null

    init {
        // The shared BotSelection resolution reads saved presets through
        // the process-wide BotCustomPresets store; install it over the
        // desktop storage root once per process (idempotent), mirroring
        // the Android app's filesDir/bots install.
        com.pocketrealm.bots.BotCustomPresets.install(java.io.File(roots.root, "bots"))
    }

    /**
     * The launch bot profile from the settings snapshot — the Android
     * WorldRuntimeService.startBotProfile contract: a selected built-in or
     * saved preset resolves through the shared BotSelection rule; no
     * selection at all keeps the reviewed bots-disabled conf.
     */
    private fun resolveBotProfile(): com.pocketrealm.bots.BotProfile? {
        val snapshot = DesktopSettingsStore(roots.settingsFile).load()
        if (snapshot.botProfileId.isEmpty() && snapshot.botSavedPresetId == null) return null
        val selection = com.pocketrealm.bots.BotSelection.resolve(
            savedPresetId = snapshot.botSavedPresetId,
            advancedEnabled = false,
            advancedTarget = snapshot.botPopulationTarget,
            advanced = com.pocketrealm.bots.BotAdvancedSettings.fromProfile(
                com.pocketrealm.bots.BotProfiles.defaultProfile),
            profileId = snapshot.botProfileId,
        )
        return selection.profile
    }

    override suspend fun preflight(spec: RuntimeLaunchSpec): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            val missing = DATABASES.filterNot { database ->
                DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).isFile
            }
            if (missing.isNotEmpty()) {
                RuntimeActionResult(
                    ok = false,
                    detail = "DB-NOT-SEEDED: ${missing.joinToString()} missing under " +
                        "${roots.sqliteDatadir} (run gradlew seedRealmData from desktop/)",
                )
            } else {
                RuntimeActionResult(ok = true, detail = "seeded datadir verified")
            }
        }

    override suspend fun observe(component: RuntimeComponent): ComponentObservation =
        withContext(Dispatchers.IO) {
            when (component) {
                RuntimeComponent.DATABASE -> observeDatabase()
                RuntimeComponent.REALM -> observeRealm()
                RuntimeComponent.WORLD -> observeWorld()
                RuntimeComponent.CLIENT -> {
                    val process = client
                    ComponentObservation(
                        component = component,
                        state = if (process?.isAlive == true) ComponentLifecycle.READY else ComponentLifecycle.STOPPED,
                        ready = process?.isAlive == true,
                        owner = ownership.getValue(component).current(),
                        pid = process?.pid()?.toInt(),
                        detail = if (process?.isAlive == true) "WoW.exe running" else "client not running",
                    )
                }
            }
        }

    override suspend fun start(
        component: RuntimeComponent,
        owner: ComponentOwner,
        spec: RuntimeLaunchSpec,
    ): ComponentObservation = withContext(Dispatchers.IO) {
        val slot = ownership.getValue(component)
        slot.claim(owner)
        when (component) {
            RuntimeComponent.DATABASE -> startDatabase()
            RuntimeComponent.REALM -> startRealm(spec.endpoint)
            RuntimeComponent.WORLD -> startWorld(spec.endpoint)
            RuntimeComponent.CLIENT -> startClient(spec.endpoint)
        }
    }

    @Suppress("TooGenericExceptionCaught") // any projection failure becomes an honest result, never a crash
    override suspend fun projectRealmEndpoint(
        databaseOwner: ComponentOwner,
        endpoint: RealmEndpoint,
    ): RuntimeActionResult = withContext(Dispatchers.IO) {
        try {
            check(ENDPOINT_ADDRESS.matches(endpoint.address)) {
                "realmlist projection refuses non-IPv4 address: ${endpoint.address}"
            }
            val realmd = DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, "classicrealmd")
            check(realmd.isFile) { "classicrealmd is not seeded" }
            DesktopSqliteConnection(realmd.absolutePath).use { db ->
                db.exec("UPDATE realmlist SET address = '${endpoint.address}' WHERE id = 1;")
                val projected = db.queryText("SELECT address FROM realmlist WHERE id = 1;")
                check(projected == endpoint.address) { "realmlist projection did not stick: $projected" }
            }
            RuntimeActionResult(ok = true, detail = "realmlist -> ${endpoint.address}")
        } catch (failure: Throwable) {
            RuntimeActionResult(ok = false, detail = "REALMLIST-PROJECTION-FAILED: ${failure.message}")
        }
    }

    override suspend fun stop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            val slot = ownership.getValue(component)
            slot.verifyOwner(owner)
            when (component) {
                RuntimeComponent.DATABASE -> stopDatabase().also { if (it.ok) slot.release(owner) }
                RuntimeComponent.REALM -> stopRealm().also { if (it.ok) slot.release(owner) }
                RuntimeComponent.WORLD -> stopWorld().also { if (it.ok) slot.release(owner) }
                RuntimeComponent.CLIENT -> stopClient().also { if (it.ok) slot.release(owner) }
            }
        }

    override suspend fun forceStop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            val slot = ownership.getValue(component)
            slot.verifyOwner(owner)
            when (component) {
                RuntimeComponent.REALM -> {
                    files.writeLifecycle("realm", false, "forced-stop")
                    val rc = runCatching { RealmNative.stopNative(FORCE_STOP_TIMEOUT_MS) }.getOrDefault(-1)
                    if (rc == 0) slot.release(owner)
                    RuntimeActionResult(rc == 0, "forced realm stop rc=$rc")
                }
                RuntimeComponent.WORLD -> {
                    files.writeLifecycle("world", false, "forced-stop")
                    val rc = synchronized(transitionLock) {
                        val stopped = runCatching { WorldNative.stopNative(FORCE_STOP_TIMEOUT_MS) }.getOrDefault(-1)
                        runCatching { worldDataLease?.close() }
                        worldDataLease = null
                        stopped
                    }
                    if (rc == 0) slot.release(owner)
                    RuntimeActionResult(rc == 0, "forced world stop rc=$rc")
                }
                else -> stop(component, owner)
            }
        }

    /** Adoption mirrors the Android contract: refused unless the component
     * is a RUNNING orphan right now (ownerless and not stopped). In-process
     * that state should not occur — every start claims first — but the
     * honest refusal keeps the supervisor's orphan-heal semantics sound. */
    override suspend fun adopt(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            val observed = runCatching { observe(component) }.getOrElse {
                return@withContext RuntimeActionResult(
                    false,
                    "adoption observe $component failed: ${it.message ?: it.javaClass.simpleName}",
                )
            }
            val orphan = observed.state != ComponentLifecycle.STOPPED && observed.owner == null
            if (!orphan) {
                RuntimeActionResult(
                    false,
                    "adoption refused: ${component.name.lowercase()} is not an ownerless running component " +
                        "(state=${observed.state})",
                )
            } else {
                ownership.getValue(component).claim(owner)
                RuntimeActionResult(true, "${component.name.lowercase()} adopted under ${owner.sessionId}")
            }
        }

    override suspend fun observeWorldPresence(): WorldPresenceSample = WorldPresenceSample.EMPTY

    override suspend fun promoteToForeground(component: RuntimeComponent): RuntimeActionResult =
        RuntimeActionResult(ok = false, detail = "foreground promotion arrives with the phase-4 WoW.exe launcher")

    override suspend fun demoteToForeground(component: RuntimeComponent): RuntimeActionResult =
        RuntimeActionResult(ok = false, detail = "foreground demotion arrives with the phase-4 WoW.exe launcher")

    override suspend fun saveWorld(owner: ComponentOwner): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            val rc = runCatching { WorldNative.saveNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS) }
                .getOrDefault(-1)
            RuntimeActionResult(rc == 0, "world save rc=$rc (${ServerRuntimeContract.errorName(rc.toLong())})")
        }

    override suspend fun setCompanionMode(owner: ComponentOwner, enabled: Boolean): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            val rc = runCatching { WorldNative.setCompanionModeNative(if (enabled) 1 else 0) }.getOrDefault(-1)
            RuntimeActionResult(rc == 0, "companion mode rc=$rc")
        }

    @Suppress("TooGenericExceptionCaught") // the service seam converts any native failure to an honest result
    override suspend fun provisionAccount(
        owner: ComponentOwner,
        username: String,
        password: String,
        gmLevel: Int,
    ): AccountProvisionResult = withContext(Dispatchers.IO) {
        val world = observeWorld()
        if (world.state != ComponentLifecycle.READY || world.owner != owner) {
            return@withContext AccountProvisionResult(ok = false, code = "WORLD_NOT_READY")
        }
        try {
            val rc = WorldNative.createAccountNative(username, password, ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            when {
                rc == ERR_ACCOUNT_EXISTS -> {
                    // Never adopt or mutate an existing identity before
                    // proving its password (the Android contract).
                    if (!WorldNative.verifyAccountPasswordNative(username, password)) {
                        AccountProvisionResult(ok = false, code = "ACCOUNT_PASSWORD_MISMATCH")
                    } else {
                        val info = WorldNative.accountInfoNative(username)
                        AccountProvisionResult(
                            ok = true,
                            code = "ACCOUNT_VERIFIED",
                            accountId = accountInfoId(info),
                            gmLevel = accountInfoGmLevel(info),
                        )
                    }
                }
                rc != 0 -> AccountProvisionResult(ok = false, code = ServerRuntimeContract.errorName(rc.toLong()))
                else -> {
                    if (gmLevel != 0) {
                        val gm = WorldNative.setAccountGmLevelNative(
                            username, gmLevel, ServerRuntimeContract.CONTROL_TIMEOUT_MS,
                        )
                        if (gm != 0) {
                            return@withContext AccountProvisionResult(
                                ok = false,
                                code = ServerRuntimeContract.errorName(gm.toLong()),
                            )
                        }
                    }
                    val info = WorldNative.accountInfoNative(username)
                    AccountProvisionResult(
                        ok = true,
                        code = "ACCOUNT_CREATED",
                        accountId = accountInfoId(info),
                        gmLevel = if (gmLevel != 0) gmLevel else accountInfoGmLevel(info),
                    )
                }
            }
        } catch (failure: Throwable) {
            AccountProvisionResult(
                ok = false,
                code = "ACCOUNT_REJECTED",
                detail = "PROVISION-FAILED: ${failure.message}",
            )
        }
    }

    override suspend fun recoverDatabase(): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            // The integrity/rebuild legs ride the seam (control-plane
            // QUICK_CHECK/INTEGRITY_CHECK/VACUUM INTO) with the Phase-3
            // engine twin's recovery flow; until then the honest verdict.
            RuntimeActionResult(ok = false, detail = "DB-RECOVERY arrives with the engine twin's recovery flow")
        }

    override fun close() {
        // Supervisor stop order: client first while the world is still up,
        // then world, then realm. DATABASE is stateless here (no daemon);
        // its clean-stop seal is stopDatabase's sidecar drain, which the
        // supervisor issues separately.
        runCatching { stopClientProcess() }
        synchronized(transitionLock) {
            runCatching { WorldNative.stopNative(FORCE_STOP_TIMEOUT_MS) }
            runCatching { worldDataLease?.close() }
            worldDataLease = null
            runCatching { RealmNative.stopNative(FORCE_STOP_TIMEOUT_MS) }
        }
    }

    // ---------------- client component ----------------

    /** Launch WoW.exe natively: the realmlist is re-projected on every
     * launch (stale topology never survives), then the client spawns in
     * its own directory. The directory resolves per launch: constructor
     * override (LaunchClient's -DclientDir) → the stored client folder
     * (the Home screen's picker) → the conventional default. */
    @Suppress("TooGenericExceptionCaught") // any launch failure surfaces as an honest throw
    private fun startClient(endpoint: RealmEndpoint): ComponentObservation = synchronized(transitionLock) {
        val dir = clientDir ?: resolveConfiguredClientDir()
            ?: error("client directory not configured (choose the game folder on Home)")
        val exe = File(dir, "WoW.exe")
        check(exe.isFile) { "WoW.exe not found under $dir" }
        // Refuse BEFORE projecting the realmlist: a refused launch must
        // have no side effects on the client directory.
        client?.let { if (it.isAlive) error("client already running (pid ${it.pid()})") }
        ClientRealmEndpointProjection.project(File(dir, "realmlist.wtf"), endpoint)
        val spawned = ProcessBuilder(exe.absolutePath)
            .directory(dir)
            .start()
        client = spawned
        ComponentObservation(
            component = RuntimeComponent.CLIENT,
            state = ComponentLifecycle.READY,
            ready = true,
            owner = ownership.getValue(RuntimeComponent.CLIENT).current(),
            pid = spawned.pid().toInt(),
            detail = "WoW.exe launched at ${endpoint.address} (realmlist projected)",
        )
    }

    /** The stored-or-default client folder; null when neither holds a
     * WoW.exe (an honest refusal beats launching from a wrong path). */
    private fun resolveConfiguredClientDir(): File? {
        val stored = DesktopSettingsStore(roots.settingsFile).load().clientDir
        (stored.takeIf { it.isNotBlank() }?.let(::File) ?: File(DEFAULT_CLIENT_DIR))
            .takeIf { File(it, "WoW.exe").isFile }
            ?.let { return it }
        return null
    }

    /** Graceful destroy with a bounded destroyForcibly escalation. The
     * process field is cleared only once exit is proven — a survivor stays
     * observable instead of being forgotten as a leaked WoW.exe. */
    private fun stopClient(): RuntimeActionResult = stopClientProcess().let { result ->
        if (result.ok) client = null
        result
    }

    private fun stopClientProcess(): RuntimeActionResult {
        val process = client ?: return RuntimeActionResult(ok = true, detail = "client not running")
        process.destroy()
        if (!process.waitFor(CLIENT_STOP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(CLIENT_STOP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        val exited = !process.isAlive
        return if (exited) {
            RuntimeActionResult(ok = true, detail = "client exited")
        } else {
            RuntimeActionResult(ok = false, detail = "client survived destroyForcibly (pid ${process.pid()})")
        }
    }

    // ---------------- database component ----------------

    private fun observeDatabase(): ComponentObservation {
        // DATABASE is a materialization, not a daemon: "running" means
        // seeded AND claimed by a live session (start claims, a clean
        // stop releases). A seeded-but-unclaimed datadir is STOPPED —
        // otherwise adoption and orphan-heal semantics would treat every
        // at-rest datadir as a running orphan.
        val seeded = DATABASES.all { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).isFile
        }
        val owner = ownership.getValue(RuntimeComponent.DATABASE).current()
        val running = seeded && owner != null
        return ComponentObservation(
            component = RuntimeComponent.DATABASE,
            state = if (running) ComponentLifecycle.READY else ComponentLifecycle.STOPPED,
            ready = running,
            owner = owner,
            pid = null,
            detail = if (running) "sqlite datadir materialized (${roots.sqliteDatadir})" else "not running",
        )
    }

    private fun startDatabase(): ComponentObservation {
        val seeded = DATABASES.all { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).isFile
        }
        check(seeded) {
            "DB-NOT-SEEDED: seed the datadir first (gradlew seedRealmData from desktop/)"
        }
        return observeDatabase()
    }

    private fun stopDatabase(): RuntimeActionResult {
        // SQLite has no daemon; a clean stop means no connection left a
        // WAL sidecar behind (the shared control plane's clean-stop
        // seal; walSidecars lists candidates, existence is ours). The
        // runtimes' StopServer closes connections on their stop paths —
        // the last close deletes the sidecars — so drain-wait briefly
        // before judging; a REAL leak still fails the gate.
        // Windows wrinkle: sqlite deletes sidecars on close, but a
        // scanner-held handle leaves the NAME in delete-pending state.
        // A delete-pending file cannot be OPENED — only sidecars that
        // actually open are held by a live connection.
        val deadline = System.currentTimeMillis() + WAL_DRAIN_TIMEOUT_MS
        var sidecars = liveWalSidecars()
        while (sidecars.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(WAL_DRAIN_POLL_MS)
            sidecars = liveWalSidecars()
        }
        return if (sidecars.isEmpty()) {
            RuntimeActionResult(ok = true, detail = "no WAL sidecars; datadir at rest")
        } else {
            RuntimeActionResult(
                ok = false,
                detail = "WAL sidecars present: ${sidecars.joinToString { it.name }}",
            )
        }
    }

    private fun liveWalSidecars(): List<File> =
        DatabaseSqliteControlPlane.walSidecars(roots.sqliteDatadir)
            .filter { file ->
                file.isFile && runCatching { java.io.RandomAccessFile(file, "r").use { } }.isSuccess
            }

    // ---------------- realm + world components ----------------

    private fun nativeLifecycle(state: Long): ComponentLifecycle = when (state) {
        ServerRuntimeContract.STOPPED -> ComponentLifecycle.STOPPED
        ServerRuntimeContract.STARTING -> ComponentLifecycle.STARTING
        ServerRuntimeContract.READY -> ComponentLifecycle.READY
        ServerRuntimeContract.STOPPING -> ComponentLifecycle.STOPPING
        ServerRuntimeContract.FAILED -> ComponentLifecycle.FAILED
        else -> ComponentLifecycle.UNKNOWN
    }

    private fun observeRealm(): ComponentObservation {
        val status = RealmNative.statusNative()
        check(status.size == REALM_STATUS_WIDTH && status[0] == ServerRuntimeContract.ABI_VERSION) {
            "realm native status contract mismatch"
        }
        val state = nativeLifecycle(status[1])
        return ComponentObservation(
            component = RuntimeComponent.REALM,
            state = state,
            ready = state == ComponentLifecycle.READY,
            owner = ownership.getValue(RuntimeComponent.REALM).current(),
            pid = null,
            detail = "${RealmNative.detailNative().take(DETAIL_MAX_CHARS)} " +
                "(${ServerRuntimeContract.errorName(status[2])})",
        )
    }

    private fun observeWorld(): ComponentObservation {
        val status = WorldNative.statusNative()
        check(status.size == WORLD_STATUS_WIDTH && status[0] == ServerRuntimeContract.ABI_VERSION) {
            "world native status contract mismatch"
        }
        val state = nativeLifecycle(status[1])
        return ComponentObservation(
            component = RuntimeComponent.WORLD,
            state = state,
            ready = state == ComponentLifecycle.READY,
            owner = ownership.getValue(RuntimeComponent.WORLD).current(),
            pid = null,
            detail = "${WorldNative.detailNative().take(DETAIL_MAX_CHARS)} " +
                "(${ServerRuntimeContract.errorName(status[2])})",
        )
    }

    private fun startRealm(endpoint: RealmEndpoint): ComponentObservation = synchronized(transitionLock) {
        files.prepareRealmLogsForStart(currentRealmState())
        files.writeLifecycle("realm", false, "start", endpoint.address)
        val rc = RealmNative.startNative(files.realmdConfig(endpoint.address).absolutePath)
        if (rc != 0) {
            files.writeLifecycle("realm", false, "start-failed", ServerRuntimeContract.errorName(rc.toLong()))
            error("realm start failed: ${ServerRuntimeContract.errorName(rc.toLong())} (rc=$rc)")
        }
        observeRealm()
    }

    @Suppress("TooGenericExceptionCaught") // config/data failures surface as the honest DATA_MISSING posture
    private fun startWorld(endpoint: RealmEndpoint): ComponentObservation = synchronized(transitionLock) {
        // worldConfig is the honest gate: without prepared data it fails
        // with the prepared-data copy (the DATA_MISSING posture).
        val config = try {
            files.prepareWorldLogsForStart(currentWorldState())
            files.worldConfig(endpoint.address, botProfile = resolveBotProfile())
        } catch (failure: Throwable) {
            files.writeLifecycle("world", false, "start-failed-data", failure.message ?: "config failure")
            error("world start refused: ${failure.message}")
        }
        // Pin the active generation for the world's lifetime: while this
        // lease is held, a concurrent publication cannot swap the data
        // under the running world (the Android twin's lease contract).
        worldDataLease = files.acquireNormalDataLease()
        files.writeLifecycle("world", false, "start", endpoint.address)
        val rc = WorldNative.startNative(config.absolutePath)
        if (rc != 0) {
            runCatching { worldDataLease?.close() }
            worldDataLease = null
            files.writeLifecycle("world", false, "start-failed", ServerRuntimeContract.errorName(rc.toLong()))
            error("world start failed: ${ServerRuntimeContract.errorName(rc.toLong())} (rc=$rc)")
        }
        observeWorld()
    }

    private fun stopWorld(): RuntimeActionResult = synchronized(transitionLock) {
        val rc = WorldNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
        runCatching { worldDataLease?.close() }
        worldDataLease = null
        files.writeLifecycle("world", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
        RuntimeActionResult(rc == 0, "world stop rc=$rc (${ServerRuntimeContract.errorName(rc.toLong())})")
    }

    private fun stopRealm(): RuntimeActionResult = synchronized(transitionLock) {
        val rc = RealmNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
        files.writeLifecycle("realm", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
        RuntimeActionResult(rc == 0, "realm stop rc=$rc (${ServerRuntimeContract.errorName(rc.toLong())})")
    }

    private fun currentRealmState(): Long {
        val status = RealmNative.statusNative()
        check(status.size == REALM_STATUS_WIDTH && status[0] == ServerRuntimeContract.ABI_VERSION) {
            "realm native status contract mismatch"
        }
        return status[1]
    }

    private fun currentWorldState(): Long {
        val status = WorldNative.statusNative()
        check(status.size == WORLD_STATUS_WIDTH && status[0] == ServerRuntimeContract.ABI_VERSION) {
            "world native status contract mismatch"
        }
        return status[1]
    }

    private fun accountInfoId(info: LongArray): Long =
        if (info.size == ACCOUNT_INFO_WIDTH) info[0] else 0L

    private fun accountInfoGmLevel(info: LongArray): Int =
        if (info.size == ACCOUNT_INFO_WIDTH) info[1].toInt() else 0

    /** One component's ownership slot: the in-process mirror of the Android
     * services' ComponentOwnership. claim accepts a fresh owner exactly
     * when the slot is empty (same-owner re-claim is idempotent); the
     * owner-scoped verbs verify; a successful owned stop releases. */
    private class OwnershipSlot(private val component: String) {
        private val lock = Any()
        private var owner: ComponentOwner? = null

        fun claim(requested: ComponentOwner) = synchronized(lock) {
            check(owner == null || owner == requested) {
                "$component is owned by another runtime session"
            }
            owner = requested
        }

        fun verifyOwner(requested: ComponentOwner) = synchronized(lock) {
            checkNotNull(owner?.takeIf { it == requested }) {
                "$component ownership mismatch; operation withheld"
            }
        }

        fun release(requested: ComponentOwner) = synchronized(lock) {
            check(owner == null || owner == requested) {
                "$component ownership mismatch; release withheld"
            }
            owner = null
        }

        fun current(): ComponentOwner? = synchronized(lock) { owner }
    }

    private companion object {
        const val REALM_STATUS_WIDTH = 5
        const val WORLD_STATUS_WIDTH = 8
        const val FORCE_STOP_TIMEOUT_MS = 5_000L
        const val DETAIL_MAX_CHARS = 512
        const val CLIENT_STOP_TIMEOUT_MS = 10_000L
        const val WAL_DRAIN_TIMEOUT_MS = 15_000L
        const val WAL_DRAIN_POLL_MS = 250L
        const val ACCOUNT_INFO_WIDTH = 2

        /** Conventional first-run client location (the dev-box install);
         * overridden by the stored client folder from the Home picker. */
        const val DEFAULT_CLIENT_DIR = "C:/Vanilla wow 1.12.1"

        /** The desktop seam's exec carries no bind parameters (it mirrors
         * the framework's execSQL), so the realmlist UPDATE interpolates.
         * v1 is loopback/LAN-IPv4 only: a strict dotted-quad guard keeps
         * the interpolation closed. */
        val ENDPOINT_ADDRESS = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        /** ServerRuntimeContract error index 10 (the errors table is
         * name-indexed, not constant-exported). */
        const val ERR_ACCOUNT_EXISTS = 10
    }
}
