package com.pocketrealm.desktop

import com.pocketrealm.supervisor.ComponentOwner

/**
 * One component's ownership slot: the in-process mirror of the Android
 * services' ComponentOwnership. claim accepts a fresh owner exactly
 * when the slot is empty (same-owner re-claim is idempotent); the
 * owner-scoped verbs verify; a successful owned stop releases.
 */
internal class DesktopOwnershipSlot(private val component: String) {
    private val lock = Any()
    private var owner: ComponentOwner? = null

    fun claim(requested: ComponentOwner) = synchronized(lock) {
        check(owner == null || owner == requested) {
            "$component is owned by another runtime session"
        }
        owner = requested
    }

    fun verifyOwner(requested: ComponentOwner) = synchronized(lock) {
        checkNotNull(owner?.takeIf { it == requested }) {
            "$component ownership mismatch; operation withheld"
        }
    }

    fun release(requested: ComponentOwner) = synchronized(lock) {
        check(owner == null || owner == requested) {
            "$component ownership mismatch; release withheld"
        }
        owner = null
    }

    fun current(): ComponentOwner? = synchronized(lock) { owner }
}
