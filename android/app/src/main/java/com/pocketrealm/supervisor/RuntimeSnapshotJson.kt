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
        // Design-tunnel: the live session's advertised address when
        // tunnel mode is on (optional key; absent = plain advertisement).
        .also { encoded ->
            value.advertisedAddress?.let { encoded.put("advertisedAddress", it) }
        }
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
        // Design-lan D9: WHY hosting stopped, when the stop was the
        // interface-loss monitor's call (optional key; absent = ordinary
        // stop). The client humanizes it into the shared copy.
        .also { encoded -> value.stopReason?.let { encoded.put("stopReason", it) } }
}
