package com.pocketrealm.server

import android.os.Process
import org.json.JSONObject

internal object ServerStatusJson {
    fun realm(values: LongArray, detail: String): JSONObject {
        require(values.size == 5 && values[0] == ServerRuntimeContract.ABI_VERSION)
        return base("realm", values, detail)
            .put("heartbeatCount", values[4])
            .put("listenAddress", "127.0.0.1")
            .put("listenPort", ServerRuntimeContract.REALM_PORT)
    }

    fun world(values: LongArray, detail: String): JSONObject {
        require(values.size == 8 && values[0] == ServerRuntimeContract.ABI_VERSION)
        return base("world", values, detail)
            .put("tickCount", values[4]).put("lastTickMs", values[5])
            .put("maxTickMs", values[6]).put("activeSessions", values[7])
            .put("listenAddress", "127.0.0.1").put("listenPort", ServerRuntimeContract.WORLD_PORT)
    }

    fun operation(component: String, operation: String, result: Int): JSONObject = JSONObject()
        .put("schema", ServerRuntimeContract.CONTROL_SCHEMA).put("ok", result == 0)
        .put("component", component).put("operation", operation)
        .put("result", result).put("error", ServerRuntimeContract.errorName(result.toLong()))
        .put("pid", Process.myPid()).put("runtimeBuildId", ServerRuntimeContract.RUNTIME_BUILD_ID)

    private fun base(component: String, values: LongArray, detail: String) = JSONObject()
        .put("schema", ServerRuntimeContract.CONTROL_SCHEMA).put("ok", true)
        .put("component", component).put("abiVersion", values[0])
        .put("state", ServerRuntimeContract.stateName(values[1]))
        .put("stateCode", values[1]).put("error", ServerRuntimeContract.errorName(values[2]))
        .put("errorCode", values[2]).put("heartbeatMs", values[3])
        .put("detail", detail.take(512)).put("pid", Process.myPid())
        .put("runtimeBuildId", ServerRuntimeContract.RUNTIME_BUILD_ID)
}
