package com.pocketrealm.addons

import kotlinx.coroutines.CancellationException
import okhttp3.Call
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Cancellation fence for one add-on operation.
 *
 * ACTIVE -> CANCELLED is reversible-work cancellation. ACTIVE -> COMMITTING is
 * the atomic publication boundary and makes subsequent Cancel requests a no-op.
 */
internal class AddonOperationToken {
    private val state = AtomicInteger(ACTIVE)
    private val activeCall = AtomicReference<Call?>()

    val isCancellable: Boolean get() = state.get() == ACTIVE
    val isCancelled: Boolean get() = state.get() == CANCELLED

    fun cancel(): Boolean {
        if (!state.compareAndSet(ACTIVE, CANCELLED)) return state.get() == CANCELLED
        activeCall.get()?.cancel()
        return true
    }

    fun checkpoint() {
        if (state.get() == CANCELLED) throw CancellationException("Add-on operation cancelled")
    }

    fun attach(call: Call) {
        checkpoint()
        check(activeCall.compareAndSet(null, call)) { "An add-on request is already active" }
        if (state.get() == CANCELLED) {
            call.cancel()
            activeCall.compareAndSet(call, null)
            checkpoint()
        }
    }

    fun detach(call: Call) {
        activeCall.compareAndSet(call, null)
    }

    /** Cross the irreversible publication boundary or throw if cancellation won. */
    fun beginCommit() {
        if (state.compareAndSet(ACTIVE, COMMITTING)) return
        checkpoint()
        check(state.get() == COMMITTING) { "Invalid add-on operation state" }
    }

    fun finish() {
        while (true) {
            val current = state.get()
            if (current == CANCELLED || current == COMPLETED) return
            if (state.compareAndSet(current, COMPLETED)) return
        }
    }

    private companion object {
        const val ACTIVE = 0
        const val CANCELLED = 1
        const val COMMITTING = 2
        const val COMPLETED = 3
    }
}
