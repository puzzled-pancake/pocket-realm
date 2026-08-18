package com.pocketrealm.realm

import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.RuntimeMode
/**
 * The supervisor state machine for the offline realm.
 *
 * This is the single source of truth for what the realm is doing. The UI and
 * the foreground notification both reflect THIS state; per the android rules,
 * the app never reports a live/playing or clean/safe state before the relevant
 * health or checkpoint conditions actually hold.
 *
 * Transitions are validated: an illegal request is a no-op that is logged, not
 * an exception. Real work (start/stop/save) happens in [RealmService]; this
 * class only owns the synchronous state and the legal-transition table.
 */
sealed interface RealmState {

    /** App/OS just started or recovered; no realm process exists yet. */
    data object Idle : RealmState

    /**
     * A start was requested and we are bringing the native realm up. Not yet
     * playable: auth/world/health conditions have not all been observed.
     */
    data class Starting(val attempt: Int) : RealmState

    /**
     * The realm is running and all health conditions hold. Only this state
     * (and [Saving]) allows "playing" semantics in the UI.
     */
    data class Running(
        val sinceEpochMs: Long,
        val mode: RuntimeMode = RuntimeMode.LOCAL,
        val endpointAddress: String = RealmEndpoint.LOOPBACK_ADDRESS,
        /** The game is independently startable while a local/LAN-host realm remains online. */
        val clientState: ClientLaunchState = ClientLaunchState.READY,
        val clientFailure: String? = null,
    ) : RealmState

    /**
     * A save-and-exit was requested. We are draining durable writes, saving
     * protected entities, and checkpointing. The process may be killed here
     * and dirty-start recovery must handle it.
     */
    data class Saving(val reason: SaveReason) : RealmState

    /**
     * The realm is tearing down (graceful or forced). Terminal toward [Idle].
     */
    data class Stopping(val forced: Boolean) : RealmState

    /**
     * Dirty-start recovery: a previous generation was left marked dirty
     * (dirty=true). The supervisor inspects DB/WAL/invariants and either
     * resumes, repairs, or presents a restore choice. UI must not call this
     * clean.
     */
    data class Recovering(val note: String) : RealmState

    /**
     * A blocking failure that needs a human decision (e.g. unrepairable
     * corruption, missing storage). The realm is NOT running.
     */
    data class Failed(val message: String) : RealmState
}

enum class ClientLaunchState { NOT_STARTED, READY, FAILED }

enum class SaveReason { USER_SAVE_EXIT, FORCED_CHECKPOINT, LOW_STORAGE, UPDATE_REQUIRED }

/**
 * Explicit health conditions for the running state. The realm is only [Running]
 * once every member of [Running] precondition set that matters has been observed.
 * These map 1:1 to the library-lane health conditions.
 */
enum class HealthCondition {
    DATABASE_OPEN,
    SCHEMA_COMPATIBLE,
    AUTH_READY,
    WORLD_LOOP_RUNNING,
    LOCAL_ENDPOINTS_LISTENING,
    BOT_SUBSYSTEM_INITIALIZED,
}

/** A health snapshot: each condition may be true/false/unknown during startup. */
data class RealmHealth(val conditions: Map<HealthCondition, Boolean>) {
    val allReady: Boolean get() = conditions.values.all { it } && conditions.isNotEmpty()
    val none: Boolean get() = conditions.isEmpty()
    companion object {
        val EMPTY = RealmHealth(emptyMap())
    }
}
