package com.pocketrealm.database

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.pocketrealm.log.AppLog
import com.pocketrealm.supervisor.ComponentOwnership
import org.json.JSONObject

/** Fault-isolated, non-exported owner of MariaDB and its live datadir. */
class DatabaseService : Service() {
    private lateinit var engine: DatabaseEngine
    private lateinit var ownership: ComponentOwnership

    override fun onCreate() {
        super.onCreate()
        DatabaseNative.load()
        engine = DatabaseEngine(applicationContext)
        ownership = ComponentOwnership("database") {
            Thread({
                AppLog.w(TAG, "supervisor owner lease died; stopping database dirty")
                runCatching { engine.close() }
                stopSelf()
            }, "database-owner-loss").start()
        }
        AppLog.i(TAG, "DatabaseService created pid=${Process.myPid()}")
    }

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
        override fun stop(): String = guarded { engine.stop() }
        override fun stopOwned(instanceToken: String): String = guarded {
            ownership.requireOwner(instanceToken)
            engine.stop().also { ownership.clear(instanceToken) }
        }
        override fun forceStopOwned(instanceToken: String): String = guarded {
            ownership.requireOwner(instanceToken)
            engine.killForTest()
        }
        override fun killForTest(): String = guarded { engine.killForTest() }
        override fun recover(): String = guarded { engine.recover() }
        override fun snapshotAndRestoreTest(): String = guarded { engine.snapshotAndRestoreTest() }
        override fun createNamedBackup(name: String): String = guarded { engine.createNamedBackup(name) }
        override fun listBackups(): String = guarded { engine.listBackups() }
        override fun beginRestore(snapshotId: String): String = guarded { engine.beginRestore(snapshotId) }
        override fun commitRestore(restoreToken: String): String = guarded { engine.commitRestore(restoreToken) }
        override fun rollbackRestore(restoreToken: String): String = guarded { engine.rollbackRestore(restoreToken) }
        override fun rollbackPendingRestore(): String = guarded { engine.rollbackPendingRestore() }
        override fun storageFullTest(): String = guarded { engine.storageFullTest() }
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
    }
}
