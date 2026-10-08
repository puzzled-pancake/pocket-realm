package com.pocketrealm.server

import org.json.JSONObject

/**
 * Desktop JVM twin of the Android ServerStatusJson: the shared native ABI's
 * status telemetry rendered into status/operation JSON. The only
 * Android-flavored bit of the original — android.os.Process.myPid() — is
 * ProcessHandle.current().pid() here; the field map is the Android twin's:
 * world width 8 carries heartbeatMs=values[3], tickCount=values[4],
 * lastTickMs=values[5], maxTickMs=values[6], activeSessions=values[7];
 * realm width 5 carries heartbeatCount=values[4]. The ABI indices are
 * named constants (documented, not bare offsets).
 */
internal object ServerStatusJson {
    private const val REALM_STATUS_WIDTH = 5
    private const val WORLD_STATUS_WIDTH = 8
    private const val INDEX_STATE = 1
    private const val INDEX_ERROR = 2
    private const val INDEX_HEARTBEAT_MS = 3
    private const val INDEX_HEARTBEAT_COUNT = 4
    private const val INDEX_TICK_COUNT = 4
    private const val INDEX_LAST_TICK_MS = 5
    private const val INDEX_MAX_TICK_MS = 6
    private const val INDEX_ACTIVE_SESSIONS = 7
    private const val DETAIL_MAX_CHARS = 512

    fun realm(values: LongArray, detail: String, listenAddress: String = "127.0.0.1"): JSONObject {
        require(values.size == REALM_STATUS_WIDTH && values[0] == ServerRuntimeContract.ABI_VERSION)
        return base("realm", values, detail)
            .put("heartbeatCount", values[INDEX_HEARTBEAT_COUNT])
            .put("listenAddress", listenAddress)
            .put("listenPort", ServerRuntimeContract.REALM_PORT)
    }

    fun world(values: LongArray, detail: String, listenAddress: String = "127.0.0.1"): JSONObject {
        require(values.size == WORLD_STATUS_WIDTH && values[0] == ServerRuntimeContract.ABI_VERSION)
        return base("world", values, detail)
            .put("tickCount", values[INDEX_TICK_COUNT])
            .put("lastTickMs", values[INDEX_LAST_TICK_MS])
            .put("maxTickMs", values[INDEX_MAX_TICK_MS])
            .put("activeSessions", values[INDEX_ACTIVE_SESSIONS])
            .put("listenAddress", listenAddress)
            .put("listenPort", ServerRuntimeContract.WORLD_PORT)
    }

    fun operation(component: String, operation: String, result: Int): JSONObject = JSONObject()
        .put("schema", ServerRuntimeContract.CONTROL_SCHEMA).put("ok", result == 0)
        .put("component", component).put("operation", operation)
        .put("result", result).put("error", ServerRuntimeContract.errorName(result.toLong()))
        .put("pid", ProcessHandle.current().pid())
        .put("runtimeBuildId", ServerRuntimeContract.RUNTIME_BUILD_ID)
        .put("nativeCmangosCommit", ServerRuntimeContract.NATIVE_CMANGOS_COMMIT)
        .put("nativePlayerbotsCommit", ServerRuntimeContract.NATIVE_PLAYERBOTS_COMMIT)

    private fun base(component: String, values: LongArray, detail: String) = JSONObject()
        .put("schema", ServerRuntimeContract.CONTROL_SCHEMA).put("ok", true)
        .put("component", component).put("abiVersion", values[0])
        .put("state", ServerRuntimeContract.stateName(values[INDEX_STATE]))
        .put("stateCode", values[INDEX_STATE])
        .put("error", ServerRuntimeContract.errorName(values[INDEX_ERROR]))
        .put("errorCode", values[INDEX_ERROR])
        .put("heartbeatMs", values[INDEX_HEARTBEAT_MS])
        .put("detail", detail.take(DETAIL_MAX_CHARS)).put("pid", ProcessHandle.current().pid())
        .put("runtimeBuildId", ServerRuntimeContract.RUNTIME_BUILD_ID)
        .put("nativeCmangosCommit", ServerRuntimeContract.NATIVE_CMANGOS_COMMIT)
        .put("nativePlayerbotsCommit", ServerRuntimeContract.NATIVE_PLAYERBOTS_COMMIT)
}
