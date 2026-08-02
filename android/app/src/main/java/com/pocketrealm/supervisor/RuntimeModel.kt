package com.pocketrealm.supervisor

enum class RuntimePhase {
    UNCONFIGURED,
    PREPARING,
    STOPPED,
    DB_STARTING,
    REALM_STARTING,
    WORLD_STARTING,
    WORLD_READY,
    CLIENT_STARTING,
    RUNNING,
    CLIENT_FAILED,
    STOPPING,
    RECOVERING,
    ERROR,
}

enum class RuntimeComponent { DATABASE, REALM, WORLD, CLIENT }
enum class ComponentLifecycle { STOPPED, STARTING, READY, STOPPING, FAILED, UNKNOWN }
enum class Recoverability { NONE, RETRY, RELAUNCH_CLIENT, RECOVERY_REQUIRED, USER_ACTION_REQUIRED }
enum class StopMode { GRACEFUL, FORCED }

data class ComponentSnapshot(
    val state: ComponentLifecycle = ComponentLifecycle.STOPPED,
    val instanceToken: String? = null,
    val startedAtWallMs: Long? = null,
    val detail: String = "",
)

data class RuntimeSnapshot(
    val schema: Int = JOURNAL_SCHEMA,
    val sessionId: String? = null,
    val phase: RuntimePhase = RuntimePhase.STOPPED,
    val requestedProfile: String? = null,
    val clean: Boolean = true,
    val components: Map<RuntimeComponent, ComponentSnapshot> = stoppedComponents(),
    val lastDurableAction: String = "stopped",
    val lastError: String? = null,
    val updatedAtWallMs: Long = 0,
    val updatedAtElapsedMs: Long = 0,
    val recoverability: Recoverability = Recoverability.NONE,
) {
    companion object {
        const val JOURNAL_SCHEMA = 2
        fun stoppedComponents(): Map<RuntimeComponent, ComponentSnapshot> =
            RuntimeComponent.entries.associateWith { ComponentSnapshot() }
    }
}

data class ComponentOwner(val sessionId: String, val instanceToken: String)

data class ComponentObservation(
    val component: RuntimeComponent,
    val state: ComponentLifecycle,
    val ready: Boolean,
    val owner: ComponentOwner? = null,
    val pid: Int? = null,
    val detail: String = "",
)

data class RuntimeActionResult(val ok: Boolean, val detail: String = "")
data class RuntimeOperation(val ok: Boolean, val snapshot: RuntimeSnapshot, val detail: String)
