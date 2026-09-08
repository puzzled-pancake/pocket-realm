package com.pocketrealm.desktop

import com.pocketrealm.supervisor.AccountProvisionResult
import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentObservation
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.RuntimeActionResult
import com.pocketrealm.supervisor.RuntimeBackend
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.WorldPresenceSample

/**
 * Phase-1 stub of the desktop RuntimeBackend. Every mutating verb fails
 * loudly with DESKTOP_BACKEND_NOT_WIRED — this placeholder never fakes
 * success — while observe() reports every component honestly STOPPED/UNKNOWN
 * so the reused supervisor state machine can be exercised end-to-end around
 * it. Phase 3 replaces the internals with the SQLite control plane + the
 * two in-process realm DLLs + the WoW.exe child; the seam signature stays.
 */
@Suppress("TooManyFunctions") // the shared RuntimeBackend seam dictates the verb count
class DesktopRuntimeBackend : RuntimeBackend {
    override suspend fun preflight(spec: RuntimeLaunchSpec): RuntimeActionResult =
        notWired("preflight")

    override suspend fun observe(component: RuntimeComponent): ComponentObservation =
        ComponentObservation(
            component = component,
            state = ComponentLifecycle.STOPPED,
            ready = false,
            owner = null,
            pid = null,
            detail = "desktop backend not yet wired (phase 3)",
        )

    override suspend fun start(
        component: RuntimeComponent,
        owner: ComponentOwner,
        spec: RuntimeLaunchSpec,
    ): ComponentObservation = observe(component)

    override suspend fun projectRealmEndpoint(
        databaseOwner: ComponentOwner,
        endpoint: RealmEndpoint,
    ): RuntimeActionResult = notWired("projectRealmEndpoint")

    override suspend fun stop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult =
        notWired("stop")

    override suspend fun forceStop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult =
        notWired("forceStop")

    override suspend fun adopt(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult =
        notWired("adopt")

    override suspend fun observeWorldPresence(): WorldPresenceSample = WorldPresenceSample.EMPTY

    override suspend fun promoteToForeground(component: RuntimeComponent): RuntimeActionResult =
        notWired("promoteToForeground")

    override suspend fun demoteToForeground(component: RuntimeComponent): RuntimeActionResult =
        notWired("demoteToForeground")

    override suspend fun saveWorld(owner: ComponentOwner): RuntimeActionResult =
        notWired("saveWorld")

    override suspend fun setCompanionMode(owner: ComponentOwner, enabled: Boolean): RuntimeActionResult =
        notWired("setCompanionMode")

    override suspend fun provisionAccount(
        owner: ComponentOwner,
        username: String,
        password: String,
        gmLevel: Int,
    ): AccountProvisionResult = AccountProvisionResult(
        ok = false,
        code = "DESKTOP_BACKEND_NOT_WIRED",
    )

    override suspend fun recoverDatabase(): RuntimeActionResult = notWired("recoverDatabase")

    override fun close() = Unit

    private fun notWired(verb: String) = RuntimeActionResult(
        ok = false,
        detail = "DESKTOP_BACKEND_NOT_WIRED: $verb arrives with the phase-3 native lane",
    )
}
