package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.supervisor.RuntimePhase
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * DESK-109/111 save-exit exercise gate: boots the realm stack through the
 * supervisor the app uses, registers the PRODUCTION save-exit shutdown hook
 * (registerSaveExitPaths — the exact registration Main.kt performs), and
 * EXITS MID-SESSION via exitProcess so only the hook can drain the running
 * stack — no window, no onCloseRequest. A second phase then verifies from
 * the durable state that the hook's drain saved the world and stopped the
 * stack cleanly:
 *
 *   gradlew saveExitGate
 *
 *   boot phase  (-DsaveExitGate.phase=boot): model.startRealm to WORLD_READY,
 *               print the mid-session proof, exitProcess(0) — the JVM runs
 *               its shutdown hooks, our hook drains (save world -> stop
 *               natives -> client destroy).
 *   verify phase (-DsaveExitGate.phase=verify): journal clean=true at
 *               STOPPED, the world lifecycle record says a clean stop, and
 *               no WAL sidecar survived (the datadir's at-rest seal).
 *
 * Exit 0 = the phase passed; the named constants below = the failed leg.
 * Machine-state honest: natives, seeds, and prepared data are the boot
 * phase's inputs — a missing input fails loudly with its remedy (never a
 * fake pass).
 */
@Suppress("TooGenericExceptionCaught") // gate lane: every failure exits with its code
fun main() {
    when (System.getProperty("saveExitGate.phase") ?: PHASE_BOOT) {
        PHASE_BOOT -> bootAndExitMidSession()
        PHASE_VERIFY -> verifyCleanDrain()
        else -> {
            System.err.println("SAVE-EXIT GATE FAILED: unknown saveExitGate.phase")
            exitProcess(EXIT_UNKNOWN_PHASE)
        }
    }
}

private const val PHASE_BOOT = "boot"
private const val PHASE_VERIFY = "verify"

/** Exit codes: the failed leg is the contract. */
private const val EXIT_UNKNOWN_PHASE = 2
private const val EXIT_BOOT_LOCKED = 2
private const val EXIT_BOOT_NOT_READY = 3
private const val EXIT_BOOT_FAILED = 4
private const val EXIT_VERIFY_DIRTY = 5
private const val EXIT_VERIFY_FAILED = 6

/** The DESK-109 exercise: hook registered, stack up, mid-session exit. */
@Suppress("TooGenericExceptionCaught") // gate lane: failures exit with the phase's code
private fun bootAndExitMidSession() {
    val model = try {
        DesktopAppModel()
    } catch (held: AppInstanceLockHeld) {
        System.err.println("SAVE-EXIT GATE FAILED (boot): ${held.message}")
        exitProcess(EXIT_BOOT_LOCKED)
    }
    DesktopLog.attachFile(model.roots.logs)
    try {
        // THE production registration path (Main.kt uses the same function).
        registerSaveExitPaths(model)
        val op = runBlocking { model.startRealm(includeClient = false) }
        println("save-exit gate boot: start ok=${op.ok} detail=${op.detail}")
        if (!op.ok || op.snapshot.phase != RuntimePhase.WORLD_READY) {
            System.err.println(
                "SAVE-EXIT GATE FAILED (boot): stack never reached WORLD_READY " +
                    "(phase=${op.snapshot.phase} lastError=${op.snapshot.lastError}). " +
                    "The gate needs the staged natives, the seeded datadir, and the " +
                    "prepared world data (gradlew seedRealmData / tools/win_prepare_data.py).",
            )
            exitProcess(EXIT_BOOT_NOT_READY)
        }
        // Mid-session: the world is up and unsaved. ONLY the shutdown hook
        // can drain from here — exactly the Windows logoff/shutdown shape.
        println("save-exit gate boot: WORLD_READY; exiting mid-session via exitProcess(0)")
        kotlin.system.exitProcess(0)
    } catch (failure: Throwable) {
        System.err.println("SAVE-EXIT GATE FAILED (boot): ${failure.message}")
        runCatching { kotlinx.coroutines.runBlocking { model.saveAndExit() } }
        runCatching { model.close() }
        exitProcess(EXIT_BOOT_FAILED)
    }
}

/** The DESK-111 proof: the mid-session exit drained a booted stack without
 * a window — read from the durable state the drain left behind. The at-rest
 * datadir check is provider-aware: [datadirAtRest]. */
@Suppress("TooGenericExceptionCaught") // gate lane: failures exit with the phase's code
private fun verifyCleanDrain() {
    try {
        val roots = DesktopStorageRoots()
        val journal = DesktopSupervisorJournal(roots.supervisorJournalDir).read()
        println("save-exit gate verify: journal phase=${journal?.phase} clean=${journal?.clean} " +
            "action=${journal?.lastDurableAction}")
        val lifecycle = File(roots.runtime, "server/lifecycle/world.json")
        val worldRecord = if (lifecycle.isFile) org.json.JSONObject(lifecycle.readText()) else null
        println("save-exit gate verify: world lifecycle operation=${worldRecord?.optString("operation")} " +
            "clean=${worldRecord?.optBoolean("clean")}")
        val provider = DesktopSettingsStore(roots.settingsFile).load().databaseProvider
        val atRest = datadirAtRest(provider, roots)
        val journalClean = journal != null && journal.clean && journal.phase == RuntimePhase.STOPPED
        val worldStopped = worldRecord != null && worldRecord.optString("operation") == "stop" &&
            worldRecord.optBoolean("clean")
        if (!journalClean || !worldStopped || !atRest) {
            System.err.println(
                "SAVE-EXIT GATE FAILED (verify): the mid-session exit did not drain cleanly " +
                    "(journalClean=$journalClean worldStopped=$worldStopped " +
                    "datadirAtRest($provider)=$atRest)",
            )
            exitProcess(EXIT_VERIFY_DIRTY)
        }
        println("SAVE-EXIT GATE PASSED (hook drained the booted stack without a window)")
    } catch (failure: Throwable) {
        System.err.println("SAVE-EXIT GATE FAILED (verify): ${failure.message}")
        exitProcess(EXIT_VERIFY_FAILED)
    }
}

/** The at-rest datadir proof, per selected provider: the SQLITE
 * dev/compat lane proves no WAL sidecar survived; the MariaDB default lane
 * proves the daemon's pid file names no live process (a clean shutdown
 * retires it; a survivor is a dirty drain). */
private fun datadirAtRest(
    provider: com.pocketrealm.storage.Settings.DatabaseProvider,
    roots: DesktopStorageRoots,
): Boolean = when (provider) {
    com.pocketrealm.storage.Settings.DatabaseProvider.SQLITE -> {
        val sidecars = DatabaseSqliteControlPlane.walSidecars(roots.sqliteDatadir)
            .filter { it.isFile }
        println("save-exit gate verify: provider=$provider wal sidecars at rest=${sidecars.size}")
        sidecars.isEmpty()
    }
    com.pocketrealm.storage.Settings.DatabaseProvider.MARIADB -> mariadbDaemonDrained(roots)
}

/** True when the MariaDB run dir's pid file is gone or names no live pid —
 * the engine's clean-shutdown retire, read durably from a fresh process. */
private fun mariadbDaemonDrained(roots: DesktopStorageRoots): Boolean {
    val pidFile = File(roots.databaseRun, "mariadb.pid")
    val livePid = pidFile.takeIf(File::isFile)
        ?.readText()?.trim()?.toLongOrNull()
        ?.takeIf { it > 1 }
        ?.let { pid ->
            val alive = runCatching {
                ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            }.getOrDefault(false)
            if (alive) pid else null
        }
    println(
        "save-exit gate verify: provider=MARIADB pid file=${pidFile.isFile} " +
            "live pid=${livePid ?: "none"}",
    )
    return livePid == null
}
