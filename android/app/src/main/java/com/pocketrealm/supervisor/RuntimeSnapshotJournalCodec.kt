package com.pocketrealm.supervisor

import org.json.JSONObject

/**
 * Pure journal schema codec (encode + decode) so the durable format is
 * unit-testable and shared without an Android Context. Lives in its own file
 * (not AtomicSupervisorJournal.kt) so the Windows desktop build compiles it
 * from this same tree; the desktop journal is a JVM twin of the atomic file
 * mechanics around it.
 */
internal object RuntimeSnapshotJournalCodec {
    fun encode(value: RuntimeSnapshot): JSONObject = JSONObject()
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
                    .put("detail", state.detail.take(DETAIL_MAX_CHARS)))
            }
        })
        .put("lastDurableAction", value.lastDurableAction.take(ACTION_MAX_CHARS))
        .put("lastError", value.lastError?.take(DETAIL_MAX_CHARS))
        .put("updatedAtWallMs", value.updatedAtWallMs)
        .put("updatedAtElapsedMs", value.updatedAtElapsedMs)
        .put("recoverability", value.recoverability.name)

    fun decode(value: JSONObject): RuntimeSnapshot {
        val storedSchema = value.getInt("schema")
        require(storedSchema == LEGACY_SCHEMA || storedSchema == RuntimeSnapshot.JOURNAL_SCHEMA) {
            "unsupported journal schema"
        }
        fun nullable(name: String): String? = if (value.isNull(name)) null else value.getString(name)
        val session = nullable("sessionId")
        if (session != null) java.util.UUID.fromString(session)
        val componentsValue = value.getJSONObject("components")
        val components = RuntimeComponent.entries.associateWith { component ->
            val encoded = componentsValue.getJSONObject(component.name.lowercase())
            val token = if (encoded.isNull("instanceToken")) null else encoded.getString("instanceToken")
            require(token == null || TOKEN.matches(token)) { "invalid component token" }
            ComponentSnapshot(
                state = ComponentLifecycle.valueOf(encoded.getString("state")),
                instanceToken = token,
                startedAtWallMs = if (encoded.isNull("startedAtWallMs")) null else encoded.getLong("startedAtWallMs"),
                detail = encoded.optString("detail").take(DETAIL_MAX_CHARS),
            )
        }
        val mode = if (storedSchema == LEGACY_SCHEMA) RuntimeMode.LOCAL
            else RuntimeMode.valueOf(value.getString("runtimeMode"))
        val endpoint = if (storedSchema == LEGACY_SCHEMA) RealmEndpoint.LOCAL else {
            require(value.getInt("realmPort") == RealmEndpoint.REALM_PORT &&
                value.getInt("worldPort") == RealmEndpoint.WORLD_PORT) {
                "journal contains non-canonical realm ports"
            }
            RealmEndpoint.parseStored(value.getString("realmEndpoint"))
        }
        require((mode == RuntimeMode.LOCAL) == endpoint.isLoopback) {
            "journal topology and endpoint disagree"
        }
        if (mode == RuntimeMode.LAN_JOIN) {
            require(listOf(RuntimeComponent.DATABASE, RuntimeComponent.REALM, RuntimeComponent.WORLD)
                .all { component ->
                    val state = components.getValue(component)
                    state.state == ComponentLifecycle.STOPPED && state.instanceToken == null
                }) { "client-only journal contains server ownership state" }
        }
        return RuntimeSnapshot(
            sessionId = session,
            phase = RuntimePhase.valueOf(value.getString("phase")),
            requestedProfile = nullable("requestedProfile"),
            runtimeMode = mode,
            realmEndpoint = endpoint,
            clean = value.getBoolean("clean"),
            components = components,
            lastDurableAction = value.getString("lastDurableAction").take(ACTION_MAX_CHARS),
            lastError = nullable("lastError")?.take(DETAIL_MAX_CHARS),
            updatedAtWallMs = value.getLong("updatedAtWallMs"),
            updatedAtElapsedMs = value.getLong("updatedAtElapsedMs"),
            recoverability = Recoverability.valueOf(value.getString("recoverability")),
        )
    }

    private val TOKEN = Regex("[0-9a-f]{64}")

    private const val LEGACY_SCHEMA = 2
    private const val DETAIL_MAX_CHARS = 512
    private const val ACTION_MAX_CHARS = 128
}
