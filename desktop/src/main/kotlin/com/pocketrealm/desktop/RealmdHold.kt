package com.pocketrealm.desktop

import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RealmEndpoint
import kotlin.system.exitProcess

/**
 * Dev/probe lane: boot realmd and hold it up (default 60s) so external
 * probes (e.g. tools/realmd_srp_probe.py) can exercise the live
 * listener without racing an in-JVM handshake.
 */
fun main() {
    val roots = DesktopStorageRoots()
    roots.ensureDirectories()
    DesktopLog.attachFile(roots.logs)
    val backend = DesktopRuntimeBackend(roots)
    val spec = RuntimeLaunchSpec(
        mode = RuntimeMode.LOCAL,
        profileId = "local",
        endpoint = RealmEndpoint.LOCAL,
        includeClient = false,
    )
    val owner = ComponentOwner(sessionId = "hold", instanceToken = "hold")
    val holdMs = System.getProperty("holdMs", "60000").toLong()
    kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.DATABASE, owner, spec) }
    kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.REALM, owner, spec) }
    println("REALMD-HELD for ${holdMs}ms")
    Thread.sleep(holdMs)
    val stop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.REALM, owner) }
    println("stop: ${stop.detail}")
    if (!stop.ok) exitProcess(1)
}
