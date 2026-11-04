package com.pocketrealm.supervisor

import com.pocketrealm.client.ClientFlavors

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
    /** Companion mode: world ticking deliberately paused for a sit-and-talk session. */
    PAUSED,
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
    val runtimeMode: RuntimeMode = RuntimeMode.LOCAL,
    val realmEndpoint: RealmEndpoint = RealmEndpoint.LOCAL,
    /**
     * Design-tunnel /: the ADVERTISED address this session projects
     * into the realmlist row when tunnel mode is on (the launch spec's
     * override, already validated by [AdvertisedAddress]'s grammar), null
     * for the plain endpoint advertisement. The monitor keys its
     * interface-loss leg off this ([InterfaceWatch]), and the host UI
     * renders it as the live advertisement — never re-deriving it from
     * settings, so the copy cannot promise an address the running session
     * does not carry. Optional journal field: snapshots written before it
     * existed decode as null (plain advertisement).
     */
    val advertisedAddress: String? = null,
    val clean: Boolean = true,
    val components: Map<RuntimeComponent, ComponentSnapshot> = stoppedComponents(),
    val lastDurableAction: String = "stopped",
    val lastError: String? = null,
    val updatedAtWallMs: Long = 0,
    val updatedAtElapsedMs: Long = 0,
    val recoverability: Recoverability = Recoverability.NONE,
    /**
     * the flavor this session's server lane runs, carried as an
     * optional journal field — defaulted so every existing constructor call
     * site compiles unchanged, and encoded/decoded by
     * [RuntimeSnapshotJournalCodec] without incrementing JOURNAL_SCHEMA
     * (journals written before the field existed decode as vanilla).
     */
    val flavor: String = ClientFlavors.VANILLA.flavorId,
    /**
     * Design-lan D9: WHY a graceful stop ran, when the stop was the
     * interface-loss monitor's call (LAN_HOST_INTERFACE_LOST /
     * TUNNEL_HOST_INTERFACE_LOST) — so the host UI can say why hosting
     * ended on BOTH platforms (the desktop keeps its own in-model copy;
     * this field is the Android monitor's producer for the shared D9
     * copy). Optional journal/JSON field: snapshots written before it
     * existed decode as null (an ordinary stop, nothing to explain).
     * Cleared by construction at the next start.
     */
    val stopReason: String? = null,
) {
    companion object {
        const val JOURNAL_SCHEMA = 3
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
