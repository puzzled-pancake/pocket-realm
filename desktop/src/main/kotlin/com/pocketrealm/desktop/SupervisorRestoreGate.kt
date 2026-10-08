package com.pocketrealm.desktop

import com.pocketrealm.supervisor.RuntimePhase
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * GAP-2 (was DESKPROD-2) live lane: the restore → proof → ordinary-start
 * sequence end to end, through the SAME model verbs the Diagnostics card
 * uses — the deeper exit criterion the fix plan records for the restore
 * pair (supervisorStartGate's ordinary cycle cannot also host this: the
 * proof's READY spends the process's one world lifetime, DESK-25, so this
 * sequence owns its own process).
 *
 * Legs, each failing loudly with its own exit code:
 *  1. the process must be fresh (phase at rest, lifetime unspent);
 *  2. a named backup of the stopped datadir;
 *  3. model.restoreBackup — the PUBLIC path (consent gate re-check,
 *     BEGIN, the production world-ready proof — which GAP-2 now boots
 *     BOTS-DISABLED, so the committed state is the picked bytes, no bot
 *     population — clean stop, COMMIT), asserting the completion copy
 *     discloses the spent lifetime;
 *  4. the DESK-25 consequence is real: worldLifetimeSpent() == true;
 *  5. the next ORDINARY start refuses WRONG_STATE — the exact refusal
 *     the disclosure names (an accepted start here would mean the
 *     one-lifetime contract broke, not that restore got friendlier);
 *  6. the journal settles clean at STOPPED.
 *
 * Exit 0 = the whole sequence passed; 2..8 = the named leg failed; 9 = an
 * unexpected throw. Run from desktop/: gradlew supervisorRestoreGate
 * (needs the staged natives, seeds, and prepared data, exactly like
 * supervisorStartGate).
 */
// bring-up entry: failures exit honestly; the exit codes are the contract
@Suppress("TooGenericExceptionCaught", "MagicNumber")
fun main() {
    val model = DesktopAppModel()
    DesktopLog.attachFile(model.roots.logs)
    DesktopLog.i("SupRestoreGate", "supervisor restore gate starting")
    try {
        exitProcess(runRestoreSequence(model))
    } catch (failure: Throwable) {
        System.err.println("SUPERVISOR RESTORE GATE FAILED: ${failure.message}")
        System.err.println(failure.stackTraceToString().take(4_000))
        runCatching { drain(model) }
        exitProcess(9)
    }
}

/** The leg sequence; returns the exit code (0 = passed). Each leg returns
 * its own early honest verdict (the DesktopAppModel.startRealm
 * ReturnCount posture); the drain rides drainExit, not more nesting. */
@Suppress("MagicNumber", "ReturnCount")
private fun runRestoreSequence(model: DesktopAppModel): Int {
    // Leg 1: this lane owns a fresh process (the proof spends the one
    // world lifetime; an already-spent process cannot host the proof).
    if (model.state.value.phase != RuntimePhase.STOPPED || model.worldLifetimeSpent()) {
        println("phase=${model.state.value.phase} spent=${model.worldLifetimeSpent()}")
        println("SUPERVISOR RESTORE GATE FAILED (process not fresh; a spent world cannot host the proof)")
        return drainExit(model, 2)
    }

    // Leg 2: the backup the restore will commit.
    val backup = model.backend.createNamedBackup("supervisor-restore-gate")
    println("backup ok=${backup.ok} id=${backup.snapshotId} detail=${backup.detail}")
    if (!backup.ok || backup.snapshotId == null) {
        println("SUPERVISOR RESTORE GATE FAILED (backup leg)")
        return drainExit(model, 3)
    }

    // Leg 3: the production restore — proof boots BOTS-DISABLED, the
    // completion copy must disclose the spent lifetime (GAP-2).
    val restored = runBlocking { model.restoreBackup(requireNotNull(backup.snapshotId)) }
    println("restore ok=${restored.ok} detail=${restored.detail}")
    if (!restored.ok) {
        println("SUPERVISOR RESTORE GATE FAILED (restore leg)")
        return drainExit(model, 4)
    }
    if ("app relaunch" !in restored.detail) {
        println("SUPERVISOR RESTORE GATE FAILED (restore copy does not disclose the relaunch requirement)")
        return drainExit(model, 5)
    }

    // Leg 4: DESK-25 — the proof's READY spent the one world lifetime.
    if (!model.worldLifetimeSpent()) {
        println("SUPERVISOR RESTORE GATE FAILED (proof reached READY but the lifetime is not spent?)")
        return drainExit(model, 6)
    }

    // Leg 5: the next ordinary start refuses WRONG_STATE — the
    // disclosed consequence, pinned (BootWorld's one-lifetime gate's
    // own marker). An ACCEPTED start fails the gate.
    val refused = runBlocking { model.startRealm(includeClient = false) }
    println("ordinary start after restore ok=${refused.ok} detail=${refused.detail}")
    if (refused.ok || "WRONG_STATE" !in refused.detail) {
        println("SUPERVISOR RESTORE GATE FAILED (ordinary-start leg; expected the WRONG_STATE one-lifetime refusal)")
        return drainExit(model, 7)
    }

    // Leg 6: the journal settles clean at STOPPED after the
    // transaction's own clean stop (the supervisorStartGate stop-leg
    // posture).
    val journal = DesktopSupervisorJournal(model.roots.supervisorJournalDir).read()
    val clean = journal?.clean == true && journal.phase == RuntimePhase.STOPPED
    println("journal clean=$clean journal=$journal")
    if (!clean) {
        println("SUPERVISOR RESTORE GATE FAILED (journal leg)")
        return drainExit(model, 8)
    }
    model.close()
    println("SUPERVISOR RESTORE GATE PASSED")
    return 0
}

/** A failed leg drains best-effort, then returns its exit code. */
private fun drainExit(model: DesktopAppModel, code: Int): Int {
    drain(model)
    return code
}

private fun drain(model: DesktopAppModel) {
    runCatching { runBlocking { model.saveAndExit() } }
    runCatching { model.close() }
}
