package com.pocketrealm.desktop

import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.RuntimePhase
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * Supervisor-path bring-up gate: drives the realm stack through the SAME
 * shared DurableRuntimeSupervisor the app's Home screen uses
 * (model.startRealm -> saveAndExit) and verifies the full cycle —
 * WORLD_READY, every stage's readiness/ownership proof accepted, clean
 * save + stop. The other bring-up gates drive DesktopRuntimeBackend
 * directly and poll, so this is the only machine gate over the
 * supervisor's start contract (backend.start returns READY) — the gap
 * where the first-click start failure shipped unseen.
 *
 * Exit 0 = the whole supervisor cycle passed; 1 = any leg failed.
 */
// bring-up entry: failures exit honestly; the exit codes are the contract
@Suppress("TooGenericExceptionCaught", "MagicNumber")
fun main() {
    val model = DesktopAppModel()
    DesktopLog.attachFile(model.roots.logs)
    DesktopLog.i("SupStartGate", "supervisor start gate starting")
    try {
        val op = runBlocking { model.startRealm(includeClient = false) }
        println("supervisor start ok=${op.ok} detail=${op.detail}")
        if (!op.ok) {
            println("SUPervisor start lastError=${op.snapshot.lastError}")
            println("SUPERVISOR START GATE FAILED (start leg)")
            exitProcess(2)
        }
        val world = runBlocking { model.backend.observe(com.pocketrealm.supervisor.RuntimeComponent.WORLD) }
        println("supervisor world state=${world.state} detail=${world.detail}")
        if (op.snapshot.phase != RuntimePhase.WORLD_READY ||
            world.state != ComponentLifecycle.READY
        ) {
            println("SUPERVISOR START GATE FAILED (world not READY; phase=${op.snapshot.phase})")
            drain(model)
            exitProcess(3)
        }
        val stop = runBlocking { model.saveAndExit() }
        println("supervisor stop ok=${stop.ok} detail=${stop.detail} phase=${stop.snapshot.phase}")
        val journal = DesktopSupervisorJournal(model.roots.supervisorJournalDir).read()
        val clean = stop.ok && journal?.clean == true &&
            journal.phase == RuntimePhase.STOPPED
        if (!clean) {
            println("SUPERVISOR START GATE FAILED (stop leg; journal=$journal)")
            exitProcess(4)
        }
        println("SUPERVISOR START GATE PASSED")
    } catch (failure: Throwable) {
        System.err.println("SUPERVISOR START GATE FAILED: ${failure.message}")
        runCatching { drain(model) }
        exitProcess(5)
    }
}

private fun drain(model: DesktopAppModel) {
    runCatching { kotlinx.coroutines.runBlocking { model.saveAndExit() } }
    runCatching { model.close() }
}
