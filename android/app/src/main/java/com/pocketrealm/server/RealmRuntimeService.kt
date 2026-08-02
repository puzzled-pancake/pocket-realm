package com.pocketrealm.server

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.pocketrealm.log.AppLog
import com.pocketrealm.supervisor.ComponentOwnership
import org.json.JSONObject

/** Dedicated realmd fault domain; Binder exposes lifecycle only, never paths or credentials. */
class RealmRuntimeService : Service() {
    private lateinit var files: ServerRuntimeFiles
    private lateinit var ownership: ComponentOwnership
    override fun onCreate() {
        super.onCreate()
        files = ServerRuntimeFiles(applicationContext)
        ownership = ComponentOwnership("realm") {
            Thread({
                files.writeLifecycle("realm", false, "owner-lost")
                runCatching { RealmNative.stopNative(5_000) }
                stopSelf()
            }, "realm-owner-loss").start()
        }
    }

    private val binder = object : IRealmControl.Stub() {
        override fun claim(sessionId: String, instanceToken: String, ownerLease: IBinder) =
            guarded { ownership.claim(sessionId, instanceToken, ownerLease) }
        override fun status() = guarded { ownership.decorate(
            ServerStatusJson.realm(RealmNative.statusNative(), RealmNative.detailNative())) }
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
        override fun stopOwned(instanceToken: String) = guarded {
            ownership.requireOwner(instanceToken)
            val rc = RealmNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            files.writeLifecycle("realm", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
            if (rc == 0) ownership.clear(instanceToken)
            ServerStatusJson.operation("realm", "stop", rc)
        }
        override fun forceStopOwned(instanceToken: String): String {
            ownership.requireOwner(instanceToken)
            files.writeLifecycle("realm", false, "forced-stop")
            Process.killProcess(Process.myPid())
            return ""
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
