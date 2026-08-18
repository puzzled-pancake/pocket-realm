package com.pocketrealm.supervisor

import android.os.IBinder
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/** In-process ownership handshake; a new Android process starts unclaimed. */
class ComponentOwnership(
    private val component: String,
    private val onOwnerDied: (ComponentOwner) -> Unit = {},
) {
    private val lock = Any()
    private val processGeneration = ByteArray(16).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }
    private var owner: ComponentOwner? = null
    private var ownerLease: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    fun claim(sessionId: String, instanceToken: String, lease: IBinder): JSONObject = synchronized(lock) {
        UUID.fromString(sessionId)
        require(TOKEN.matches(instanceToken)) { "invalid instance token" }
        val requested = ComponentOwner(sessionId, instanceToken)
        check(owner == null || owner == requested) { "$component is owned by another runtime session" }
        check(lease.isBinderAlive) { "$component owner lease is already dead" }
        if (owner == null) {
            val recipient = IBinder.DeathRecipient { ownerDied() }
            // Publish the owner before linking so a death racing linkToDeath()
            // cannot be observed as an ownerless no-op.
            owner = requested
            ownerLease = lease
            deathRecipient = recipient
            try {
                lease.linkToDeath(recipient, 0)
            } catch (error: Throwable) {
                owner = null
                ownerLease = null
                deathRecipient = null
                throw error
            }
        } else {
            check(ownerLease == lease) { "$component owner lease changed within a session" }
        }
        owner = requested
        JSONObject().put("ok", true).put("component", component)
            .put("sessionId", sessionId).put("instanceToken", instanceToken)
            .put("processGeneration", processGeneration)
    }

    fun requireOwner(instanceToken: String): ComponentOwner = synchronized(lock) {
        check(TOKEN.matches(instanceToken)) { "invalid instance token" }
        checkNotNull(owner?.takeIf { it.instanceToken == instanceToken }) {
            "$component ownership mismatch; operation withheld"
        }
    }

    fun clear(instanceToken: String) = synchronized(lock) {
        requireOwner(instanceToken)
        unlinkLease()
        owner = null
    }

    fun decorate(value: JSONObject): JSONObject = synchronized(lock) {
        val current = owner
        value.put("component", component)
            .put("processGeneration", processGeneration)
            .put("hasOwner", current != null)
        if (current != null) value.put("ownerSessionId", current.sessionId)
            .put("instanceToken", current.instanceToken)
        value
    }

    private fun ownerDied() {
        val expired = synchronized(lock) {
            val value = owner ?: return
            owner = null
            ownerLease = null
            deathRecipient = null
            value
        }
        onOwnerDied(expired)
    }

    private fun unlinkLease() {
        val lease = ownerLease
        val recipient = deathRecipient
        if (lease != null && recipient != null) runCatching { lease.unlinkToDeath(recipient, 0) }
        ownerLease = null
        deathRecipient = null
    }

    companion object { private val TOKEN = Regex("[0-9a-f]{64}") }
}
