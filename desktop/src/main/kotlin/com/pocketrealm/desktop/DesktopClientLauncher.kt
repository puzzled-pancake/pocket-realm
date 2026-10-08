package com.pocketrealm.desktop

import com.pocketrealm.client.ClientRealmEndpointProjection
import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentObservation
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.RuntimeActionResult
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The desktop WoW.exe launcher (the RuntimeBackend's CLIENT component,
 * extracted to keep the backend at its seam's size): launch the real
 * client natively with the realmlist re-projected on every launch (stale
 * topology never survives), then a graceful destroy with a bounded
 * destroyForcibly escalation. The directory resolves per launch:
 * constructor override (LaunchClient's -DclientDir) → the stored client
 * folder (the Home screen's picker) — no implicit default: a machine
 * with no chosen folder refuses honestly (the dev lane passes its own
 * -DclientDir; see RpHost).
 */
internal class DesktopClientLauncher(
    private val settingsFile: File,
    private val clientDirOverride: File?,
) {
    @Volatile
    private var process: Process? = null

    fun isAlive(): Boolean = process?.isAlive == true

    fun pid(): Int? = process?.pid()?.toInt()

    /** Launch WoW.exe; the refusal order mirrors the Android twin: the
     * directory, then the exe, then the already-running check — all
     * BEFORE any side effect on the client directory. */
    fun launch(endpoint: RealmEndpoint, owner: ComponentOwner?): ComponentObservation {
        val dir = clientDirOverride ?: resolveConfiguredClientDir()
            ?: error("client directory not configured (choose the game folder on Home)")
        val exe = File(dir, "WoW.exe")
        check(exe.isFile) { "WoW.exe not found under $dir" }
        // Refuse BEFORE projecting the realmlist: a refused launch must
        // have no side effects on the client directory.
        process?.let { if (it.isAlive) error("client already running (pid ${it.pid()})") }
        ClientRealmEndpointProjection.project(File(dir, "realmlist.wtf"), endpoint)
        // The no-manifest 1.12 client trips Windows' installer-detection
        // heuristic and would come up ELEVATED: UIPI then eats every
        // synthetic input (the app's own auto-login included). RUNASINVOKER
        // disables the heuristic for this launch only.
        val spawned = ProcessBuilder(exe.absolutePath)
            .directory(dir)
            .apply { environment()["__COMPAT_LAYER"] = "RUNASINVOKER" }
            .start()
        process = spawned
        return ComponentObservation(
            component = com.pocketrealm.supervisor.RuntimeComponent.CLIENT,
            state = ComponentLifecycle.READY,
            ready = true,
            owner = owner,
            pid = spawned.pid().toInt(),
            detail = "WoW.exe launched at ${endpoint.address} (realmlist projected)",
        )
    }

    /** The stored client folder (the Home picker); null when it is unset
     * or holds no WoW.exe (an honest refusal beats launching from a wrong
     * path — there is deliberately NO conventional default to fall back
     * to: any machine-local install would be a folder the user never
     * chose). The dev lane passes -DclientDir instead. */
    private fun resolveConfiguredClientDir(): File? {
        val stored = DesktopSettingsStore(settingsFile).load().clientDir
        return stored.takeIf { it.isNotBlank() }?.let(::File)
            ?.takeIf { File(it, "WoW.exe").isFile }
    }

    /** Graceful destroy with a bounded destroyForcibly escalation. The
     * process field is cleared only once exit is proven — a survivor
     * stays observable instead of being forgotten as a leaked WoW.exe. */
    fun stop(): RuntimeActionResult =
        stopProcess().let { result ->
            if (result.ok) process = null
            result
        }

    /** Stop without clearing the slot (the backend's close() drain). */
    fun stopProcess(): RuntimeActionResult {
        val current = process ?: return RuntimeActionResult(ok = true, detail = "client not running")
        current.destroy()
        if (!current.waitFor(CLIENT_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            current.destroyForcibly()
            current.waitFor(CLIENT_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        val exited = !current.isAlive
        return if (exited) {
            RuntimeActionResult(ok = true, detail = "client exited")
        } else {
            RuntimeActionResult(
                ok = false,
                detail = "client survived destroyForcibly (pid ${current.pid()})",
            )
        }
    }

    private companion object {
        const val CLIENT_STOP_TIMEOUT_MS = 10_000L
    }
}
