package com.pocketrealm.realm

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Legacy UI-transition model retained for its compatibility tests.
 * Production lifecycle ownership moved to
 * [com.pocketrealm.supervisor.DurableRuntimeSupervisor].
 *
 * The legal-transition table is intentionally narrow: it is safer to ignore an
 * out-of-order request than to act on one and corrupt realm state.
 */
class RealmSupervisor {

    private val _state = MutableStateFlow<RealmState>(RealmState.Idle)
    val state: StateFlow<RealmState> = _state.asStateFlow()

    private val startAttempts = AtomicInteger(0)

    /** True only when the realm is actually live and healthy. */
    val isLive: Boolean
        get() = _state.value is RealmState.Running || _state.value is RealmState.Saving

    /**
     * Transition to [Starting]. Allowed from Idle, Recovering (resume),
     * Failed (retry), or Stopping (a prior teardown was interrupted — e.g. the
     * process/service was destroyed mid-teardown — so the realm is not actually
     * running and a fresh start is valid). Rejects only if genuinely up:
     * Starting or Running or Saving.
     */
    fun requestStart(): Boolean {
        val current = _state.value
        val allowed = current is RealmState.Idle ||
            current is RealmState.Recovering ||
            current is RealmState.Failed ||
            current is RealmState.Stopping
        if (!allowed) {
            Log.w(TAG, "requestStart rejected in state $current")
            return false
        }
        val attempt = startAttempts.incrementAndGet()
        _state.value = RealmState.Starting(attempt)
        Log.i(TAG, "Starting realm (attempt $attempt)")
        return true
    }

    /**
     * Mark the realm running after all health conditions hold. Only legal from
     * Starting. This is the ONLY way into [RealmState.Running], enforcing the
     * android rule: never report playing before health holds.
     */
    fun markRunning(health: RealmHealth) {
        check(health.allReady) {
            "markRunning requires allReady; got ${health.conditions}"
        }
        if (_state.value is RealmState.Starting) {
            _state.value = RealmState.Running(System.currentTimeMillis())
            Log.i(TAG, "Realm Running (health ok)")
        } else {
            Log.w(TAG, "markRunning ignored in state ${_state.value}")
        }
    }

    /** Begin a save. Legal from Starting, Running, Recovering. */
    fun requestSave(reason: SaveReason): Boolean {
        val current = _state.value
        if (current !is RealmState.Starting && current !is RealmState.Running && current !is RealmState.Recovering) {
            Log.w(TAG, "requestSave rejected in state $current")
            return false
        }
        _state.value = RealmState.Saving(reason)
        Log.i(TAG, "Saving realm (${reason.name})")
        return true
    }

    /** Begin teardown. Legal from any non-Idle state. */
    fun requestStop(forced: Boolean): Boolean {
        val current = _state.value
        if (current is RealmState.Idle) {
            Log.w(TAG, "requestStop ignored; already Idle")
            return false
        }
        _state.value = RealmState.Stopping(forced)
        Log.i(TAG, "Stopping realm (forced=$forced)")
        return true
    }

    /** Confirm teardown complete and return to Idle. */
    fun markIdle() {
        _state.value = RealmState.Idle
        Log.i(TAG, "Realm Idle")
    }

    /** Enter recovery. Legal from Starting/Running on a dirty-start signal. */
    fun markRecovering(note: String) {
        val current = _state.value
        if (current is RealmState.Starting || current is RealmState.Running) {
            _state.value = RealmState.Recovering(note)
            Log.w(TAG, "Recovering: $note")
        }
    }

    /** Record a blocking failure. */
    fun markFailed(message: String) {
        _state.value = RealmState.Failed(message)
        Log.e(TAG, "Realm Failed: $message")
    }

    companion object {
        private const val TAG = "PR/Supervisor"
    }
}
