package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.database.DatabaseSqliteControlPlane.DATABASES
import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentObservation
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.RuntimeActionResult
import com.pocketrealm.supervisor.RuntimeComponent
import java.io.File

/**
 * The DATABASE component (a materialization, not a daemon): observe /
 * start (the prepare-for-start gate) / stop (the WAL-drain clean-stop
 * seal). Extracted from the RuntimeBackend to keep the backend at its
 * seam's size; the verbs keep the exact shapes the shared supervisor's
 * readiness proofs consume.
 */
internal class DesktopDatabaseComponent(
    private val roots: DesktopStorageRoots,
    private val preparation: DesktopDatabasePreparation,
) {

    fun observe(owner: ComponentOwner?): ComponentObservation {
        // DATABASE is a materialization, not a daemon: "running" means
        // seeded AND claimed by a live session (start claims, a clean
        // stop releases). A seeded-but-unclaimed datadir is STOPPED —
        // otherwise adoption and orphan-heal semantics would treat every
        // at-rest datadir as a running orphan.
        val seeded = DATABASES.all { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).isFile
        }
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

    fun start(owner: ComponentOwner?): ComponentObservation {
        // The seeded gate (DESK-53 keeps the distinct DB-NOT-SEEDED code
        // for the truly-unseeded datadir; preflight owns the DESK-108
        // bootstrap — a direct start on an empty datadir still refuses).
        val missing = DATABASES.filterNot { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).isFile
        }
        check(missing.isEmpty()) {
            "$CODE_DB_NOT_SEEDED: seed the datadir first (relaunch to bootstrap from the " +
                "packaged transcripts, or gradlew seedRealmData from desktop/)"
        }
        // The prepare-for-start gate: the shared
        // DatabaseStartPreparation machine must say READY before the DB
        // component reports READY — INITIALIZATION/MIGRATIONS/dirty
        // states refuse with their own codes, never a silent boot.
        val verdict = preparation.evaluate()
        check(verdict.ready) { "${verdict.code}: ${verdict.detail}" }
        return observe(owner)
    }

    fun stop(): RuntimeActionResult {
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

    private companion object {
        const val WAL_DRAIN_TIMEOUT_MS = 15_000L
        const val WAL_DRAIN_POLL_MS = 250L
    }
}
