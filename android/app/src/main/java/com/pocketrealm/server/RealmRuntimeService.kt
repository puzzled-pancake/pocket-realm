package com.pocketrealm.server

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.pocketrealm.log.AppLog
import com.pocketrealm.supervisor.ComponentOwnership
import com.pocketrealm.supervisor.RealmEndpoint
import org.json.JSONObject

/** Dedicated realmd fault domain; Binder exposes lifecycle only, never paths or credentials. */
class RealmRuntimeService : Service() {
    private lateinit var files: ServerRuntimeFiles
    private lateinit var ownership: ComponentOwnership
    private val transitionLock = Any()
    @Volatile private var activeBindAddress = RealmEndpoint.LOOPBACK_ADDRESS
    override fun onCreate() {
        super.onCreate()
        files = ServerRuntimeFiles(applicationContext)
        ownership = ComponentOwnership("realm") {
            Thread({
                synchronized(transitionLock) {
                    files.writeLifecycle("realm", false, "owner-lost")
                    runCatching { RealmNative.stopNative(5_000) }
                }
                stopSelf()
            }, "realm-owner-loss").start()
        }
    }

    private val binder = object : IRealmControl.Stub() {
        override fun claim(sessionId: String, instanceToken: String, ownerLease: IBinder) =
            guarded { ownership.claim(sessionId, instanceToken, ownerLease) }
        override fun status() = guarded { ownership.decorate(
            ServerStatusJson.realm(
                RealmNative.statusNative(), RealmNative.detailNative(), activeBindAddress)) }
        override fun start() = startAt(RealmEndpoint.LOOPBACK_ADDRESS)
        override fun startAt(bindAddress: String) = guarded { synchronized(transitionLock) {
            val endpoint = RealmEndpoint.parseStored(bindAddress)
            files.prepareRealmLogsForStart(currentNativeState())
            files.writeLifecycle("realm", false, "start", endpoint.address)
            activeBindAddress = endpoint.address
            val rc = RealmNative.startNative(files.realmdConfig(endpoint.address).absolutePath)
            if (rc != 0) activeBindAddress = RealmEndpoint.LOOPBACK_ADDRESS
            ServerStatusJson.operation("realm", "start", rc)
        } }
        override fun stop() = guarded { synchronized(transitionLock) {
            val rc = RealmNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            if (rc == 0) activeBindAddress = RealmEndpoint.LOOPBACK_ADDRESS
            files.writeLifecycle("realm", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
            ServerStatusJson.operation("realm", "stop", rc)
        } }
        override fun stopOwned(instanceToken: String) = guarded { synchronized(transitionLock) {
            ownership.requireOwner(instanceToken)
            val rc = RealmNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            if (rc == 0) activeBindAddress = RealmEndpoint.LOOPBACK_ADDRESS
            files.writeLifecycle("realm", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
            if (rc == 0) ownership.clear(instanceToken)
            ServerStatusJson.operation("realm", "stop", rc)
        } }
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
    private fun currentNativeState(): Long {
        val status = RealmNative.statusNative()
        check(status.size == 5 && status[0] == ServerRuntimeContract.ABI_VERSION) {
            "realm native status contract mismatch"
        }
        return status[1]
    }
    override fun onDestroy() {
        synchronized(transitionLock) { runCatching { RealmNative.stopNative(5_000) } }
        super.onDestroy()
    }
    private inline fun guarded(block: () -> JSONObject): String = try { block().toString() } catch (error: Throwable) {
        AppLog.e(TAG, "realm control request failed", error); failure(error)
    }
    private fun failure(error: Throwable) = JSONObject().put("schema", 1).put("ok", false)
        .put("component", "realm").put("errorClass", error.javaClass.simpleName)
        .put("error", (error.message ?: "realm request failed").take(512)).toString()
    companion object { private const val TAG = "RealmRuntimeService" }
}
