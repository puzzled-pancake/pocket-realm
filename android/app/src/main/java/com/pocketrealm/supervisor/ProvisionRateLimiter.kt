package com.pocketrealm.supervisor

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The provisioning listener's rate limiting, layer 1 (design-remote-accounts
 * §6): per source IP — 5 failed token/parse attempts → a 10-minute lockout;
 * a global cap on concurrent connections. The live counters are in-memory;
 * the LOCKOUT rows also persist through the [FriendInviteStore] so an app
 * restart does not amnesty a locked source. (Layer 2 is the invite's own
 * single-use/expiry/cap design; layer 3 is realmd's WrongPass lockout on
 * non-loopback binds — out of this object's scope.)
 *
 * The listener answers EVERY refusal with the identical error blob (§5), so
 * these counters must never leak state into a response — they only decide
 * serve vs refuse.
 */
class ProvisionRateLimiter(
    private val store: FriendInviteStore,
    private val clock: () -> Long = System::currentTimeMillis,
    internal val maxFailures: Int = MAX_FAILURES,
    internal val lockoutMs: Long = LOCKOUT_MS,
    internal val maxConcurrentConnections: Int = MAX_CONCURRENT_CONNECTIONS,
    internal val maxTrackedIps: Int = MAX_TRACKED_IPS,
) {
    // R4-3 (android-core review round 1): the live per-IP maps are keyed by
    // ATTACKER-SUPPLIED source IPs, so they are bounded — a
    // many-unique-source spray (the exact pattern §6 layer 1 exists for)
    // must not grow the host heap for the process lifetime. Insertion-ORDER
    // maps under one lock (eviction is oldest-first); the persisted rows
    // keep their own MAX_RATE_ROWS bound in FriendInviteStore.
    private val ipStateLock = Any()
    private val failuresByIp = LinkedHashMap<String, Int>()
    private val lockedUntilByIp = LinkedHashMap<String, Long>()
    private val activeConnections = AtomicInteger(0)

    /** True when [ip] may submit (not currently locked). */
    fun isLocked(ip: String): Boolean = synchronized(ipStateLock) {
        val until = lockedUntilByIp[ip] ?: persistedLock(ip)
        val lapsed = until != 0L && clock() >= until
        if (lapsed) {
            // The window lapsed: clear live + persisted.
            lockedUntilByIp[ip] = 0L
            failuresByIp[ip] = 0
            store.recordRateFailure(ip, 0, 0L)
        }
        enforceIpBudgetLocked()
        until != 0L && !lapsed
    }

    /** One failed token/parse attempt from [ip]; returns true when this
     * failure TRIPPED the lockout. */
    fun recordFailure(ip: String): Boolean = synchronized(ipStateLock) {
        // JDK merge yields null only when the remapping function removes the
        // mapping; Int::plus never does, so the !! is the contract, not hope.
        val count = failuresByIp.merge(ip, 1, Int::plus)!!
        val tripped = if (count < maxFailures) {
            store.recordRateFailure(ip, count, lockedUntilByIp[ip] ?: 0L)
            false
        } else {
            val until = clock() + lockoutMs
            lockedUntilByIp[ip] = until
            failuresByIp[ip] = 0
            store.recordRateFailure(ip, 0, until)
            true
        }
        enforceIpBudgetLocked()
        tripped
    }

    /** A valid submission clears the source's failure counter (hygiene: a
     * friend who typoed twice then succeeded is not near lockout). */
    fun recordSuccess(ip: String) = synchronized(ipStateLock) {
        failuresByIp[ip] = 0
        store.recordRateFailure(ip, 0, lockedUntilByIp[ip] ?: 0L)
        enforceIpBudgetLocked()
        Unit
    }

    /** Distinct sources currently tracked live (both maps) - the budget the
     * R4-3 test pins. */
    internal fun trackedIpCount(): Int = synchronized(ipStateLock) {
        (failuresByIp.keys + lockedUntilByIp.keys).distinct().size
    }

    /** R4-3: the live-map budget. When over [maxTrackedIps], the OLDEST
     * tracked sources are evicted - never one that is actively locked (a
     * locked source must not amnesty itself by aging out of the live map).
     * An evicted unlocked source loses its in-memory failure counter, the
     * same amnesty the persisted layer's own MAX_RATE_ROWS rule applies
     * (newest rows displace the oldest unlocked rows) - the host answered
     * those request windows by then, and the bounded-heap requirement wins
     * over remembering a sprayed source forever. */
    private fun enforceIpBudgetLocked() {
        var guard = 0
        while (trackedCountLocked() > maxTrackedIps && guard++ < maxTrackedIps * 4) {
            val order = failuresByIp.keys.asSequence() +
                lockedUntilByIp.keys.asSequence().filter { it !in failuresByIp }
            val evictable = order.firstOrNull { candidate ->
                val until = lockedUntilByIp[candidate] ?: 0L
                until == 0L || clock() >= until
            } ?: return  // every tracked source is actively locked: keep them
            failuresByIp.remove(evictable)
            lockedUntilByIp.remove(evictable)
        }
    }

    private fun trackedCountLocked(): Int =
        (failuresByIp.keys + lockedUntilByIp.keys).distinct().size

    /** The global concurrency gate (§6: 10 concurrent connections). Returns
     * false when the host is at capacity; the caller closes the socket. */
    fun tryAcquireConnection(): Boolean {
        while (true) {
            val current = activeConnections.get()
            if (current >= maxConcurrentConnections) return false
            if (activeConnections.compareAndSet(current, current + 1)) return true
        }
    }

    fun releaseConnection() {
        activeConnections.decrementAndGet()
    }

    fun activeConnections(): Int = activeConnections.get()

    private fun persistedLock(ip: String): Long {
        val until = store.lockedUntilMs(ip)
        lockedUntilByIp[ip] = until
        return until
    }

    companion object {
        internal const val MAX_FAILURES = 5
        internal const val LOCKOUT_MS = 10L * 60L * 1_000L
        internal const val MAX_CONCURRENT_CONNECTIONS = 10
        /** R4-3: the live per-IP map budget (distinct sources). Mirrors the
         * persisted layer's MAX_RATE_ROWS scale: beyond this, the oldest
         * unlocked sources age out of the LIVE maps (the persisted rows keep
         * their own bound), so a many-unique-source spray cannot grow the
         * host heap for the process lifetime. */
        internal const val MAX_TRACKED_IPS = 512
    }
}
