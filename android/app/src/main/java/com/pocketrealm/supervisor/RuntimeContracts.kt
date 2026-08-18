package com.pocketrealm.supervisor

import android.os.SystemClock
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
    suspend fun stop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult
    suspend fun forceStop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult
    suspend fun saveWorld(owner: ComponentOwner): RuntimeActionResult
    suspend fun provisionAccount(
        owner: ComponentOwner,
        username: String,
        password: String,
        gmLevel: Int,
    ): AccountProvisionResult
    suspend fun recoverDatabase(): RuntimeActionResult
    override fun close() = Unit
}

data class AccountProvisionResult(
    val ok: Boolean,
    val code: String,
    val accountId: Long = 0,
    val gmLevel: Int = 0,
    val detail: String = code,
)

interface RuntimeClock {
    fun wallMs(): Long
    fun elapsedMs(): Long
}

object AndroidRuntimeClock : RuntimeClock {
    override fun wallMs() = System.currentTimeMillis()
    override fun elapsedMs() = SystemClock.elapsedRealtime()
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
