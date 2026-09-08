package com.pocketrealm.database

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import com.pocketrealm.R
import com.pocketrealm.log.AppLog
import com.pocketrealm.service.RealmService
import com.pocketrealm.supervisor.ComponentOwnership
import org.json.JSONObject
import com.pocketrealm.BuildConfig

/** Fault-isolated, non-exported owner of MariaDB and its live datadir. */
class DatabaseService : Service() {
    private lateinit var engine: DatabaseEngine
    private lateinit var ownership: ComponentOwnership
    private val foregroundLock = Any()
    private var foregroundActive = false

    override fun onCreate() {
        super.onCreate()
        DatabaseNative.load()
        engine = DatabaseEngine(applicationContext)
        ownership = ComponentOwnership("database") {
            Thread({
                AppLog.w(TAG, "supervisor owner lease died; stopping database dirty")
                demoteForeground()
                runCatching { engine.close() }
                stopSelf()
            }, "database-owner-loss").start()
        }
        AppLog.i(TAG, "DatabaseService created pid=${Process.myPid()}")
    }

    /**
     * B5: the supervisor promotes :database to a specialUse FGS while a real
     * player is present and demotes it when the realm is playerless. This
     * service has no transition gate; the demote side also runs at engine
     * stop and owner loss so the promotion never outlives the engine.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROMOTE_FOREGROUND -> {
                RealmService.ensureChannel(this)
                startForeground(DATABASE_NOTIF_ID, buildForegroundNotification())
                synchronized(foregroundLock) { foregroundActive = true }
            }
            ACTION_DEMOTE_FOREGROUND -> demoteForeground()
        }
        return START_NOT_STICKY
    }

    private fun demoteForeground() {
        val wasActive = synchronized(foregroundLock) {
            val active = foregroundActive
            foregroundActive = false
            active
        }
        if (wasActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Drop the started half too: this service is otherwise bound-only,
            // and a lingering started state would keep it at service priority
            // after the demotion. Any binding keeps the process alive.
            stopSelf()
        }
    }

    private fun buildForegroundNotification(): Notification =
        NotificationCompat.Builder(this, RealmService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Pocket Realm database")
            .setContentText("Database engine active while a player is in the realm")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private val binder = object : IDatabaseControl.Stub() {
        override fun claim(sessionId: String, instanceToken: String, ownerLease: IBinder): String =
            guarded { ownership.claim(sessionId, instanceToken, ownerLease) }
        override fun status(): String = guarded { ownership.decorate(engine.status()) }
        override fun initialize(): String = guarded { engine.initialize() }
        override fun start(): String = guarded { engine.start() }
        override fun queryHealth(): String = guarded { engine.queryHealth() }
        override fun projectRealmEndpoint(instanceToken: String, address: String, worldPort: Int): String = guarded {
            ownership.requireOwner(instanceToken)
            engine.projectRealmEndpoint(address, worldPort)
        }
        override fun applyPinnedMigrations(): String = guarded { engine.applyPinnedMigrations() }
        override fun provisionSqliteProvider(): String = guarded { engine.provisionSqliteProvider() }
        override fun translateUserStateToSqliteStaging(): String = guarded {
            // The differential lane's Server-A dump leg: the export
            // produces the canonical mysqldump-TSV staging the parity
            // oracle consumes; also the dual-provider window's Binder
            // exposure for the translation.
            engine.translateUserStateToSqliteStaging()
        }
        override fun stop(): String = guarded { engine.stop() }
        override fun stopOwned(instanceToken: String): String = guarded {
            ownership.requireOwner(instanceToken)
            // Releasing the claim is a handshake in THIS process, never an
            // engine operation: an engine that is observably down (STOPPED or
            // FAILED) has nothing to stop, so the owner-gated release
            // succeeds for the claim while the durable seals stay as they
            // are (an unsealed generation keeps its dirty state for the next
            // start's recovery lane). A live engine still takes the ordinary
            // clean stop - and a live-engine stop failure still throws, so
            // the claim survives for the forced-stop escalation's own
            // requireOwner gate.
            val stopped = engine.stopForOwnerRelease()
            ownership.clear(instanceToken)
            if (stopped.optBoolean("ok")) demoteForeground()
            stopped
        }
        override fun forceStopOwned(instanceToken: String): String = guarded {
            ownership.requireOwner(instanceToken)
            // Same law as stopOwned on the forced lane: killing an engine
            // that is already down would refuse (killForTest has nothing to
            // kill) and strand the escalation fallback with a stale claim.
            // The release is still owner-gated, never unconditional.
            if (engine.isEnded()) {
                ownership.clear(instanceToken)
                demoteForeground()
                JSONObject().put("ok", true).put("alreadyDown", true)
                    .put("claimReleased", true)
            } else {
                engine.killForTest()
            }
        }
        override fun killForTest(): String = guarded {
            check(BuildConfig.DEBUG) { "test process kill is debug-only" }
            engine.killForTest()
        }
        override fun recover(): String = guarded { engine.recover() }
        override fun snapshotAndRestoreTest(): String = guarded {
            check(BuildConfig.DEBUG) { "snapshot/restore fault injection is debug-only" }
            engine.snapshotAndRestoreTest()
        }
        override fun createNamedBackup(name: String): String = guarded { engine.createNamedBackup(name) }
        override fun listBackups(): String = guarded { engine.listBackups() }
        override fun beginRestore(snapshotId: String): String = guarded { engine.beginRestore(snapshotId) }
        override fun commitRestore(restoreToken: String): String = guarded { engine.commitRestore(restoreToken) }
        override fun rollbackRestore(restoreToken: String): String = guarded { engine.rollbackRestore(restoreToken) }
        override fun rollbackPendingRestore(): String = guarded { engine.rollbackPendingRestore() }
        override fun storageFullTest(): String = guarded {
            check(BuildConfig.DEBUG) { "storage-full fault injection is debug-only" }
            engine.storageFullTest()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { engine.close() }
        super.onDestroy()
    }

    private inline fun guarded(block: () -> JSONObject): String = try {
        block().toString()
    } catch (error: Throwable) {
        AppLog.e(TAG, "database control request failed", error)
        JSONObject().put("ok", false)
            .put("errorClass", error.javaClass.simpleName)
            .put("error", (error.message ?: "database request failed").take(1024))
            .toString()
    }

    companion object {
        private const val TAG = "DatabaseService"
        /** B5: supervisor-driven specialUse FGS promotion intents. */
        const val ACTION_PROMOTE_FOREGROUND = "com.pocketrealm.action.DATABASE_FOREGROUND_PROMOTE"
        const val ACTION_DEMOTE_FOREGROUND = "com.pocketrealm.action.DATABASE_FOREGROUND_DEMOTE"
        const val DATABASE_NOTIF_ID = 4
    }
}
