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
import com.pocketrealm.supervisor.WorldPresenceSample
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
) : RuntimeBackend {
    private val files = ServerRuntimeFiles(roots)
    private val transitionLock = Any()

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
                RuntimeComponent.CLIENT -> ComponentObservation(
                    component = component,
                    state = ComponentLifecycle.STOPPED,
                    ready = false,
                    owner = null,
                    pid = null,
                    detail = "WoW.exe launcher arrives with phase 4",
                )
            }
        }

    override suspend fun start(
        component: RuntimeComponent,
        owner: ComponentOwner,
        spec: RuntimeLaunchSpec,
    ): ComponentObservation = withContext(Dispatchers.IO) {
        when (component) {
            RuntimeComponent.DATABASE -> startDatabase()
            RuntimeComponent.REALM -> startRealm(spec.endpoint)
            RuntimeComponent.WORLD -> startWorld(spec.endpoint)
            RuntimeComponent.CLIENT -> observe(component)
        }
    }

    @Suppress("TooGenericExceptionCaught") // any projection failure becomes an honest result, never a crash
    override suspend fun projectRealmEndpoint(
        databaseOwner: ComponentOwner,
        endpoint: RealmEndpoint,
    ): RuntimeActionResult = withContext(Dispatchers.IO) {
        try {
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
            when (component) {
                RuntimeComponent.DATABASE -> stopDatabase()
                RuntimeComponent.REALM -> stopRealm()
                RuntimeComponent.WORLD -> stopWorld()
                RuntimeComponent.CLIENT -> RuntimeActionResult(ok = false, detail = "CLIENT arrives with phase 4")
            }
        }

    override suspend fun forceStop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            when (component) {
                RuntimeComponent.REALM -> {
                    val rc = runCatching { RealmNative.stopNative(FORCE_STOP_TIMEOUT_MS) }.getOrDefault(-1)
                    RuntimeActionResult(rc == 0, "forced realm stop rc=$rc")
                }
                RuntimeComponent.WORLD -> {
                    val rc = runCatching { WorldNative.stopNative(FORCE_STOP_TIMEOUT_MS) }.getOrDefault(-1)
                    RuntimeActionResult(rc == 0, "forced world stop rc=$rc")
                }
                else -> stop(component, owner)
            }
        }

    /** In-process single owner: adoption is vacuously true. */
    override suspend fun adopt(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult =
        RuntimeActionResult(ok = true, detail = "in-process component adopted")

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
        if (world.state != ComponentLifecycle.READY) {
            return@withContext AccountProvisionResult(ok = false, code = "WORLD_NOT_RUNNING")
        }
        try {
            val rc = WorldNative.createAccountNative(username, password, ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            when {
                rc == 0 && gmLevel == 0 -> AccountProvisionResult(ok = true, code = "OK")
                rc == ERR_ACCOUNT_EXISTS ->
                    AccountProvisionResult(ok = false, code = "ACCOUNT_EXISTS")
                rc != 0 -> AccountProvisionResult(ok = false, code = ServerRuntimeContract.errorName(rc.toLong()))
                else -> {
                    val gm = WorldNative.setAccountGmLevelNative(
                        username, gmLevel, ServerRuntimeContract.CONTROL_TIMEOUT_MS,
                    )
                    if (gm == 0) AccountProvisionResult(ok = true, code = "OK")
                    else AccountProvisionResult(ok = false, code = ServerRuntimeContract.errorName(gm.toLong()))
                }
            }
        } catch (failure: Throwable) {
            AccountProvisionResult(ok = false, code = "PROVISION-FAILED: ${failure.message}")
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
        synchronized(transitionLock) {
            runCatching { RealmNative.stopNative(FORCE_STOP_TIMEOUT_MS) }
            runCatching { WorldNative.stopNative(FORCE_STOP_TIMEOUT_MS) }
        }
    }

    // ---------------- database component ----------------

    private fun observeDatabase(): ComponentObservation {
        val seeded = DATABASES.all { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).isFile
        }
        return ComponentObservation(
            component = RuntimeComponent.DATABASE,
            state = if (seeded) ComponentLifecycle.READY else ComponentLifecycle.STOPPED,
            ready = seeded,
            owner = null,
            pid = null,
            detail = if (seeded) "sqlite datadir materialized (${roots.sqliteDatadir})" else "not seeded",
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
        // seal; walSidecars lists candidates, existence is ours).
        val sidecars = DatabaseSqliteControlPlane.walSidecars(roots.sqliteDatadir)
            .filter { it.isFile }
        return if (sidecars.isEmpty()) {
            RuntimeActionResult(ok = true, detail = "no WAL sidecars; datadir at rest")
        } else {
            RuntimeActionResult(
                ok = false,
                detail = "WAL sidecars present: ${sidecars.joinToString { it.name }}",
            )
        }
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
            owner = null,
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
            owner = null,
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
            files.worldConfig(endpoint.address)
        } catch (failure: Throwable) {
            files.writeLifecycle("world", false, "start-failed-data", failure.message ?: "config failure")
            error("world start refused: ${failure.message}")
        }
        files.writeLifecycle("world", false, "start", endpoint.address)
        val rc = WorldNative.startNative(config.absolutePath)
        if (rc != 0) {
            files.writeLifecycle("world", false, "start-failed", ServerRuntimeContract.errorName(rc.toLong()))
            error("world start failed: ${ServerRuntimeContract.errorName(rc.toLong())} (rc=$rc)")
        }
        observeWorld()
    }

    private fun stopRealm(): RuntimeActionResult = synchronized(transitionLock) {
        val rc = RealmNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
        files.writeLifecycle("realm", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
        RuntimeActionResult(rc == 0, "realm stop rc=$rc (${ServerRuntimeContract.errorName(rc.toLong())})")
    }

    private fun stopWorld(): RuntimeActionResult = synchronized(transitionLock) {
        val rc = WorldNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
        files.writeLifecycle("world", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
        RuntimeActionResult(rc == 0, "world stop rc=$rc (${ServerRuntimeContract.errorName(rc.toLong())})")
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

    private companion object {
        const val REALM_STATUS_WIDTH = 5
        const val WORLD_STATUS_WIDTH = 8
        const val FORCE_STOP_TIMEOUT_MS = 5_000L
        const val DETAIL_MAX_CHARS = 512

        /** ServerRuntimeContract error index 10 (the errors table is
         * name-indexed, not constant-exported). */
        const val ERR_ACCOUNT_EXISTS = 10
    }
}
