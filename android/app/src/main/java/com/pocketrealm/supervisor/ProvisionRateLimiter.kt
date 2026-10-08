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
) {
    private val failuresByIp = ConcurrentHashMap<String, Int>()
    private val lockedUntilByIp = ConcurrentHashMap<String, Long>()
    private val activeConnections = AtomicInteger(0)

    /** True when [ip] may submit (not currently locked). */
    fun isLocked(ip: String): Boolean {
        val until = lockedUntilByIp[ip] ?: persistedLock(ip)
        val lapsed = until != 0L && clock() >= until
        if (lapsed) {
            // The window lapsed: clear live + persisted.
            lockedUntilByIp[ip] = 0L
            failuresByIp[ip] = 0
            store.recordRateFailure(ip, 0, 0L)
        }
        return until != 0L && !lapsed
    }

    /** One failed token/parse attempt from [ip]; returns true when this
     * failure TRIPPED the lockout. */
    fun recordFailure(ip: String): Boolean {
        // JDK merge yields null only when the remapping function removes the
        // mapping; Int::plus never does, so the !! is the contract, not hope.
        val count = failuresByIp.merge(ip, 1, Int::plus)!!
        if (count < maxFailures) {
            store.recordRateFailure(ip, count, lockedUntilByIp[ip] ?: 0L)
            return false
        }
        val until = clock() + lockoutMs
        lockedUntilByIp[ip] = until
        failuresByIp[ip] = 0
        store.recordRateFailure(ip, 0, until)
        return true
    }

    /** A valid submission clears the source's failure counter (hygiene: a
     * friend who typoed twice then succeeded is not near lockout). */
    fun recordSuccess(ip: String) {
        failuresByIp[ip] = 0
        store.recordRateFailure(ip, 0, lockedUntilByIp[ip] ?: 0L)
    }

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
    }
}
