package com.pocketrealm.server

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.pocketrealm.log.AppLog
import com.pocketrealm.supervisor.ComponentOwnership
import org.json.JSONObject

/** Dedicated no-bot mangosd fault domain with a fixed bounded control protocol. */
class WorldRuntimeService : Service() {
    private lateinit var files: ServerRuntimeFiles
    private lateinit var ownership: ComponentOwnership
    override fun onCreate() {
        super.onCreate()
        files = ServerRuntimeFiles(applicationContext)
        ownership = ComponentOwnership("world") {
            Thread({
                files.writeLifecycle("world", false, "owner-lost")
                runCatching { WorldNative.stopNative(5_000) }
                stopSelf()
                Process.killProcess(Process.myPid())
            }, "world-owner-loss").start()
        }
    }

    private val binder = object : IWorldControl.Stub() {
        override fun claim(sessionId: String, instanceToken: String, ownerLease: IBinder) =
            guarded { ownership.claim(sessionId, instanceToken, ownerLease) }
        override fun status() = guarded { ownership.decorate(
            ServerStatusJson.world(WorldNative.statusNative(), WorldNative.detailNative())) }
        override fun start() = guarded {
            files.writeLifecycle("world", false, "start")
            val rc = WorldNative.startNative(files.worldConfig().absolutePath)
            ServerStatusJson.operation("world", "start", rc)
        }
        override fun createAccount(username: String, password: String) = guarded {
            ServerRuntimeContract.requireAccountToken("username", username)
            ServerRuntimeContract.requireAccountToken("password", password)
            val rc = WorldNative.createAccountNative(username, password, ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            ServerStatusJson.operation("world", "create-account", rc)
        }
        override fun save() = guarded {
            val rc = WorldNative.saveNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            files.writeLifecycle("world", false, "save", ServerRuntimeContract.errorName(rc.toLong()))
            ServerStatusJson.operation("world", "save", rc)
        }
        override fun stop() = guarded {
            val rc = WorldNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            files.writeLifecycle("world", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
            if (rc == 0) retireCleanProcess()
            ServerStatusJson.operation("world", "stop", rc)
        }
        override fun stopOwned(instanceToken: String) = guarded {
            ownership.requireOwner(instanceToken)
            val rc = WorldNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            files.writeLifecycle("world", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
            if (rc == 0) {
                ownership.clear(instanceToken)
                retireCleanProcess()
            }
            ServerStatusJson.operation("world", "stop", rc)
        }
        override fun forceStopOwned(instanceToken: String): String {
            ownership.requireOwner(instanceToken)
            files.writeLifecycle("world", false, "forced-stop")
            Process.killProcess(Process.myPid())
            return ""
        }
        override fun killForTest(): String {
            files.writeLifecycle("world", false, "kill-for-test")
            Process.killProcess(Process.myPid())
            return ""
        }
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() { runCatching { WorldNative.stopNative(5_000) }; super.onDestroy() }
    /**
     * CMaNGOS owns process-lifetime singleton registries and queue threads.
     * Once native shutdown has acknowledged and the clean journal is durable,
     * retire this dedicated fault domain instead of reusing stale globals.
     * The delay lets the successful Binder reply reach the supervisor first.
     */
    private fun retireCleanProcess() {
        Thread({
            Thread.sleep(250)
            stopSelf()
            Process.killProcess(Process.myPid())
        }, "world-clean-retire").start()
    }
    private inline fun guarded(block: () -> JSONObject): String = try { block().toString() } catch (error: Throwable) {
        AppLog.e(TAG, "world control request failed", error); failure(error)
    }
    private fun failure(error: Throwable) = JSONObject().put("schema", 1).put("ok", false)
        .put("component", "world").put("errorClass", error.javaClass.simpleName)
        .put("error", (error.message ?: "world request failed").take(512)).toString()
    companion object { private const val TAG = "WorldRuntimeService" }
}
