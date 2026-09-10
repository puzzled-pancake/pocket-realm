package com.pocketrealm.desktop

import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentObservation
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RealmEndpoint
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.exitProcess

/**
 * Phase-4 gate: the FULL world boot. Starts database + realm + world
 * in-process against the prepared world data (gradlew realmdHold's
 * siblings), waits for the world to reach READY (vmaps/mmaps load,
 * grid preheating), proves 127.0.0.1:8085 accepts a connection, saves,
 * and stops everything cleanly with the WAL-sidecar seal.
 *
 * -DbootCycles=2 adds the one-lifetime restart gate: after a successful
 * boot+stop, a SECOND world start in the same process must be REFUSED
 * honestly with the WRONG_STATE in-process-restart error (the pinned v1
 * contract — the embedded cmangos lane cannot re-initialize in place;
 * see the windows-port review-fix PLAN-LOG entry). The gate fails if the
 * second start HANGS, crashes, or is accepted.
 *
 * Run from desktop/: gradlew bootWorld   (after win_prepare_data.py)
 */
@Suppress("TooGenericExceptionCaught") // bring-up gate: any failure exits honestly
fun main() {
    val cycles = Integer.max(1, System.getProperty("bootCycles")?.toIntOrNull() ?: 1)
    val roots = DesktopStorageRoots()
    roots.ensureDirectories()
    DesktopLog.attachFile(roots.logs)
    DesktopLog.i("BootWorld", "phase-4 world boot gate starting (cycles=$cycles)")

    val backend = DesktopRuntimeBackend(roots)
    val spec = RuntimeLaunchSpec(
        mode = RuntimeMode.LOCAL,
        profileId = "local",
        endpoint = RealmEndpoint.LOCAL,
        includeClient = false,
    )
    val owner = ComponentOwner(sessionId = "boot-world", instanceToken = "boot-world")

    val preflight = kotlinx.coroutines.runBlocking { backend.preflight(spec) }
    if (!preflight.ok) {
        System.err.println("PREFLIGHT FAILED: ${preflight.detail}")
        exitProcess(EXIT_PREFLIGHT)
    }
    kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.DATABASE, owner, spec) }
    kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.REALM, owner, spec) }
    println("realm up; starting world (this loads vmaps/mmaps and takes a while)")

    var saveAll = true
    for (cycle in 1..cycles) {
        try {
            saveAll = runWorldCycle(backend, owner, spec, cycle) && saveAll
        } catch (failure: Throwable) {
            System.err.println("WORLD CYCLE $cycle FAILED: ${failure.message}")
            System.err.println(failure.stackTraceToString().take(STACK_TAIL_CHARS))
            drain(backend, owner)
            exitProcess(EXIT_WORLD_CYCLE)
        }
    }

    val realmStop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.REALM, owner) }
    val dbStop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.DATABASE, owner) }
    println("stops: realm=${realmStop.ok} db=${dbStop.ok}")
    if (!realmStop.ok || !dbStop.ok || !saveAll) {
        System.err.println("CLEAN STOP OR SAVE FAILED")
        exitProcess(EXIT_STOP)
    }
    DesktopLog.i("BootWorld", "phase-4 world boot gate passed (cycles=$cycles)")
    println("PHASE-4 WORLD BOOT GATE PASSED (cycles=$cycles)")
}

/** One world start → READY → 8085 connect → save → clean stop cycle.
 * Cycle ≥ 2 pins the one-lifetime refusal instead. Returns the save
 * verdict; any leg failure throws and the caller decides the drain. */
@Suppress("TooGenericExceptionCaught", "ThrowsCount") // bring-up gate: rethrow the start refusal verbatim
private fun runWorldCycle(
    backend: DesktopRuntimeBackend,
    owner: ComponentOwner,
    spec: RuntimeLaunchSpec,
    cycle: Int,
): Boolean {
    if (cycle >= 2) {
        // The pinned v1 contract: one world lifetime per process. The
        // second start must FAIL FAST with the specific refusal — an
        // accepted start, a hang, or a crash all fail this gate.
        try {
            kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.WORLD, owner, spec) }
            error("cycle $cycle: second in-process world start was ACCEPTED (the one-lifetime gate is gone?)")
        } catch (expected: IllegalStateException) {
            val message = expected.message ?: ""
            // The thrown message carries the WRONG_STATE error name; the
            // longer "in-process world restart" explanation rides the
            // native detail line.
            check(WRONG_STATE_REFUSAL in message) {
                "cycle $cycle: expected the one-lifetime restart refusal, got: $message"
            }
            val observed = kotlinx.coroutines.runBlocking { backend.observe(RuntimeComponent.WORLD) }
            println("cycle $cycle second start refused honestly: ${message.take(DETAIL_TAIL_CHARS)}")
            println("cycle $cycle native detail: ${observed.detail.take(DETAIL_TAIL_CHARS)}")
            println("cycle $cycle one-lifetime restart gate pinned")
            return true
        }
    }
    val world = try {
        kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.WORLD, owner, spec) }
    } catch (failure: Throwable) {
        System.err.println("WORLD START REFUSED: ${failure.message}")
        throw failure
    }
    println("cycle $cycle world: state=${world.state} detail=${world.detail.take(DETAIL_TAIL_CHARS)}")

    val settled = waitFor(backend, RuntimeComponent.WORLD, ComponentLifecycle.READY, WORLD_READY_TIMEOUT_MS)
    val observed = kotlinx.coroutines.runBlocking { backend.observe(RuntimeComponent.WORLD) }
    // FAILED is a settled-but-failed boot: report it honestly here, not
    // as a bogus READY followed by a mysterious 60 s port timeout.
    if (!settled || observed.state != ComponentLifecycle.READY) {
        error(
            "world cycle $cycle never reached READY: ${observed.state} " +
                "${observed.detail.take(DETAIL_TAIL_CHARS)}",
        )
    }
    println("cycle $cycle world READY")

    check(waitForPort(ServerRuntimeContract.WORLD_PORT.toInt(), LISTEN_TIMEOUT_MS)) {
        "world cycle $cycle never listened on ${ServerRuntimeContract.WORLD_PORT}"
    }
    println("cycle $cycle gate: 127.0.0.1:${ServerRuntimeContract.WORLD_PORT} accepted a connection")

    // Save while the world is still READY — a stopped world refuses
    // saves (WRONG_STATE) and the clean-stop gate needs the save verdict.
    val save = kotlinx.coroutines.runBlocking { backend.saveWorld(owner) }
    println("cycle $cycle save: ok=${save.ok} detail=${save.detail}")

    val worldStop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.WORLD, owner) }
    check(worldStop.ok) { "world cycle $cycle stop failed: ${worldStop.detail}" }
    println("cycle $cycle world stopped (sidecars sealed)")
    return save.ok
}

/** Best-effort stack drain for the error exits: world, realm, database. */
private fun drain(backend: DesktopRuntimeBackend, owner: ComponentOwner) {
    runCatching { kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.WORLD, owner) } }
    runCatching { kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.REALM, owner) } }
    runCatching { kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.DATABASE, owner) } }
}

private const val EXIT_PREFLIGHT = 2
private const val EXIT_WORLD_CYCLE = 3
private const val EXIT_STOP = 4
private const val DETAIL_TAIL_CHARS = 160
private const val STACK_TAIL_CHARS = 4_000
private const val WORLD_READY_TIMEOUT_MS = 15 * 60 * 1000L
private const val LISTEN_TIMEOUT_MS = 60_000L
private const val POLL_SLEEP_MS = 1_000L
private const val CONNECT_TIMEOUT_MS = 2_000
private const val WRONG_STATE_REFUSAL = "WRONG_STATE"

/** Wait until the component reaches [target]; FAILED settles the wait as
 * a terminal state (the caller must check WHICH state settled). */
private fun waitFor(
    backend: DesktopRuntimeBackend,
    component: RuntimeComponent,
    target: ComponentLifecycle,
    timeoutMs: Long,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    var settled = false
    while (!settled && System.currentTimeMillis() < deadline) {
        val observed: ComponentObservation = kotlinx.coroutines.runBlocking { backend.observe(component) }
        settled = observed.state == target || observed.state == ComponentLifecycle.FAILED
        if (!settled) Thread.sleep(POLL_SLEEP_MS)
    }
    return settled
}

private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        try {
            Socket().use { it.connect(InetSocketAddress(RealmEndpoint.LOOPBACK_ADDRESS, port), CONNECT_TIMEOUT_MS) }
            return true
        } catch (_: java.io.IOException) {
            Thread.sleep(POLL_SLEEP_MS)
        }
    }
    return false
}
