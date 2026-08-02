package com.pocketrealm.database

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.pocketrealm.log.AppLog
import org.json.JSONObject

/** Fault-isolated, non-exported owner of MariaDB and its live datadir. */
class DatabaseService : Service() {
    private lateinit var engine: DatabaseEngine

    override fun onCreate() {
        super.onCreate()
        DatabaseNative.load()
        engine = DatabaseEngine(applicationContext)
        AppLog.i(TAG, "DatabaseService created pid=${Process.myPid()}")
    }

    private val binder = object : IDatabaseControl.Stub() {
        override fun status(): String = guarded { engine.status() }
        override fun initialize(): String = guarded { engine.initialize() }
        override fun start(): String = guarded { engine.start() }
        override fun queryHealth(): String = guarded { engine.queryHealth() }
        override fun applyPinnedMigrations(): String = guarded { engine.applyPinnedMigrations() }
        override fun stop(): String = guarded { engine.stop() }
        override fun killForTest(): String = guarded { engine.killForTest() }
        override fun recover(): String = guarded { engine.recover() }
        override fun snapshotAndRestoreTest(): String = guarded { engine.snapshotAndRestoreTest() }
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
