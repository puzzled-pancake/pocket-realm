package com.pocketrealm.supervisor

import org.json.JSONObject

object RuntimeSnapshotJson {
    fun encode(value: RuntimeSnapshot): JSONObject = JSONObject()
        .put("ok", true)
        .put("schema", value.schema)
        .put("sessionId", value.sessionId)
        .put("phase", value.phase.name)
        .put("requestedProfile", value.requestedProfile)
        .put("runtimeMode", value.runtimeMode.name)
        .put("realmEndpoint", value.realmEndpoint.address)
        .put("realmPort", RealmEndpoint.REALM_PORT)
        .put("worldPort", RealmEndpoint.WORLD_PORT)
        .put("clean", value.clean)
        .put("components", JSONObject().also { components ->
            RuntimeComponent.entries.forEach { component ->
                val state = value.components.getValue(component)
                components.put(component.name.lowercase(), JSONObject()
                    .put("state", state.state.name)
                    .put("instanceToken", state.instanceToken)
                    .put("startedAtWallMs", state.startedAtWallMs)
                    .put("detail", state.detail.take(512)))
            }
        })
        .put("lastDurableAction", value.lastDurableAction)
        .put("lastError", value.lastError)
        .put("updatedAtWallMs", value.updatedAtWallMs)
        .put("updatedAtElapsedMs", value.updatedAtElapsedMs)
        .put("recoverability", value.recoverability.name)
}
