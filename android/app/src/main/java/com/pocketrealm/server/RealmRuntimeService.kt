package com.pocketrealm.server

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.pocketrealm.log.AppLog
import org.json.JSONObject

/** Dedicated realmd fault domain; Binder exposes lifecycle only, never paths or credentials. */
class RealmRuntimeService : Service() {
    private lateinit var files: ServerRuntimeFiles
    override fun onCreate() { super.onCreate(); files = ServerRuntimeFiles(applicationContext) }

    private val binder = object : IRealmControl.Stub() {
        override fun status() = guarded { ServerStatusJson.realm(RealmNative.statusNative(), RealmNative.detailNative()) }
        override fun start() = guarded {
            files.writeLifecycle("realm", false, "start")
            val rc = RealmNative.startNative(files.realmdConfig().absolutePath)
            ServerStatusJson.operation("realm", "start", rc)
        }
        override fun stop() = guarded {
            val rc = RealmNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            files.writeLifecycle("realm", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
            ServerStatusJson.operation("realm", "stop", rc)
        }
        override fun killForTest(): String {
            files.writeLifecycle("realm", false, "kill-for-test")
            Process.killProcess(Process.myPid())
            return ""
        }
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() { runCatching { RealmNative.stopNative(5_000) }; super.onDestroy() }
    private inline fun guarded(block: () -> JSONObject): String = try { block().toString() } catch (error: Throwable) {
        AppLog.e(TAG, "realm control request failed", error); failure(error)
    }
    private fun failure(error: Throwable) = JSONObject().put("schema", 1).put("ok", false)
        .put("component", "realm").put("errorClass", error.javaClass.simpleName)
        .put("error", (error.message ?: "realm request failed").take(512)).toString()
    companion object { private const val TAG = "RealmRuntimeService" }
}
