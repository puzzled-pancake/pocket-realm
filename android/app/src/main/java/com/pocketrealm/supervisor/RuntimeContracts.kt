package com.pocketrealm.supervisor

import java.security.SecureRandom
import java.util.UUID

interface SupervisorJournal {
    fun read(): RuntimeSnapshot?
    fun write(snapshot: RuntimeSnapshot)
}
interface RuntimeBackend : AutoCloseable {
    suspend fun preflight(spec: RuntimeLaunchSpec): RuntimeActionResult
    suspend fun observe(component: RuntimeComponent): ComponentObservation
    suspend fun start(
        component: RuntimeComponent,
        owner: ComponentOwner,
        spec: RuntimeLaunchSpec,
    ): ComponentObservation
    suspend fun projectRealmEndpoint(
        databaseOwner: ComponentOwner,
        endpoint: RealmEndpoint,
    ): RuntimeActionResult

    /**
     * Design-tunnel §4.4.2 (bind/advertise split): write the realm
     * ADVERTISEMENT — the address friends' clients actually dial, from the
     * launch spec's `advertisedOrDefault` — which may diverge from the
     * [endpoint] the servers bound (a tunnel address). The default keeps
     * the endpoint-address advertisement: only lanes that implement the
     * split (the desktop host) override it, so the Android lane's behavior
     * is unchanged and any future Android advertisement work is a
     * deliberate override, not a silent reinterpretation of this seam.
     */
    suspend fun projectRealmAdvertisement(
        databaseOwner: ComponentOwner,
        endpoint: RealmEndpoint,
        advertised: String,
    ): RuntimeActionResult = projectRealmEndpoint(databaseOwner, endpoint)
    suspend fun stop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult
    suspend fun forceStop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult

    /**
     * Claims an ownerless RUNNING component for [owner]. Legal on
     * the service side exactly when the current owner is null - the same
     * ComponentOwnership.claim path a start uses; a component with any
     * non-null owner must refuse.
     */
    suspend fun adopt(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult

    /** One world presence sample feeding the foreground-promotion policy. */
    suspend fun observeWorldPresence(): WorldPresenceSample

    /** Supervisor-owned FGS promotion verbs for :world and :database. */
    suspend fun promoteToForeground(component: RuntimeComponent): RuntimeActionResult
    suspend fun demoteToForeground(component: RuntimeComponent): RuntimeActionResult
    suspend fun saveWorld(owner: ComponentOwner): RuntimeActionResult
    suspend fun setCompanionMode(owner: ComponentOwner, enabled: Boolean): RuntimeActionResult
    suspend fun provisionAccount(
        owner: ComponentOwner,
        username: String,
        password: String,
        gmLevel: Int,
    ): AccountProvisionResult

    /**
     * The READ-ONLY credential proof (the join-friend lifecycle's parity
     * seam): prove a stored username + password against the live world
     * WITHOUT creating or mutating anything — never a provision leg, so a
     * credential saved from a friend-join can never mint a local identity,
     * on either platform. Authoritative answers: ACCOUNT_VERIFIED (row
     * exists + password proven), ACCOUNT_PASSWORD_MISMATCH (row exists,
     * password refuted), ACCOUNT_UNKNOWN (the world never knew the
     * identity). Everything else is transport-class and never rejects a
     * stored record. Default refuses (transport-class) so test fakes and
     * backends without the lane stay compiling.
     */
    suspend fun verifyAccount(
        owner: ComponentOwner,
        username: String,
        password: String,
    ): AccountProvisionResult = AccountProvisionResult(false, "UNSUPPORTED")
    suspend fun recoverDatabase(): RuntimeActionResult
    override fun close() = Unit

    // ------------------------------------------------------------------
    // Remote friend accounts (design-remote-accounts §3/§8/§9/§11): the
    // host-side verbs the Players surfaces and the approval flow ride.
    // Every default REFUSES honestly so a backend that has not adopted the
    // lane (and every test fake) stays compiling — the same
    // default-override pattern as projectRealmAdvertisement above; the
    // desktop and Android runtimes both override all six.
    // ------------------------------------------------------------------

    /** §3: inserts the friend-derived (s, v) verbatim — no password ever
     * crosses the host. ACCOUNT_EXISTS on a name collision; the HOST
     * rejects the request, the friend picks another name. */
    suspend fun provisionAccountVerifier(
        username: String,
        sHex: String,
        vHex: String,
    ): AccountProvisionResult = AccountProvisionResult(false, "UNSUPPORTED")

    /** §11: the Players roster read (identity + ban state + the live
     * failed-logins counter + the last SUCCESSFUL logon + online). */
    suspend fun accountRoster(): RosterRead = RosterRead("unsupported")

    /** §8: one account's characters. */
    suspend fun accountCharacters(username: String): CharactersRead = CharactersRead("unsupported")

    /** §9: `ban account <name> <duration> <reason>` through the console
     * relay (duration "0" = permanent). */
    suspend fun banFriendAccount(username: String, duration: String, reason: String): ModerationResult =
        ModerationResult(false, "unsupported")

    /** §9: `unban account <name>`. */
    suspend fun unbanFriendAccount(username: String): ModerationResult = ModerationResult(false, "unsupported")

    /** §10: per-friend gmlevel 0..3. */
    suspend fun setFriendGmLevel(username: String, level: Int): Boolean = false
}

data class AccountProvisionResult(
    val ok: Boolean,
    val code: String,
    val accountId: Long = 0,
    val gmLevel: Int = 0,
    val detail: String = code,
)

/** One moderation verb's honest verdict (the console output rides as detail). */
data class ModerationResult(val ok: Boolean, val detail: String)

/** A roster read: null [reason] = success; otherwise the honest refusal copy. */
data class RosterRead(val reason: String?, val accounts: List<FriendRosterJson.AccountRow> = emptyList())

/** A characters read for one account. */
data class CharactersRead(val reason: String?, val characters: List<FriendRosterJson.CharacterRow> = emptyList())

interface RuntimeClock {
    fun wallMs(): Long
    fun elapsedMs(): Long
}

interface RuntimeTokenSource {
    fun sessionId(): String
    fun instanceToken(): String
}

class SecureRuntimeTokenSource : RuntimeTokenSource {
    private val random = SecureRandom()
    override fun sessionId(): String = UUID.randomUUID().toString()
    override fun instanceToken(): String = ByteArray(32).also(random::nextBytes)
        .joinToString("") { "%02x".format(it) }
}

data class RuntimeTimeouts(
    // Fresh ARM bootstrap and the pinned full-world migrations are part of the
    // production database start stage. The foreground service owns an
    // unbounded wake lease; this remains a bounded supervisor deadline.
    val databaseStartMs: Long = 1_800_000,
    val realmStartMs: Long = 30_000,
    val worldStartMs: Long = 120_000,
    val clientStartMs: Long = 90_000,
    val componentStopMs: Long = 60_000,
    val databaseStopMs: Long = 30_000,
    val recoveryMs: Long = 1_800_000,
    val botWorldStartMs: Long = 600_000,
) {
    fun start(component: RuntimeComponent, botProfile: Boolean = false) = when (component) {
        RuntimeComponent.DATABASE -> databaseStartMs
        RuntimeComponent.REALM -> realmStartMs
        RuntimeComponent.WORLD -> if (botProfile) botWorldStartMs else worldStartMs
        RuntimeComponent.CLIENT -> clientStartMs
    }

    fun stop(component: RuntimeComponent) =
        if (component == RuntimeComponent.DATABASE) databaseStopMs else componentStopMs
}
