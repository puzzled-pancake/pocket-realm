package com.pocketrealm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.pocketrealm.desktop.ui.AddonsScreen
import com.pocketrealm.desktop.ui.BotsScreen
import com.pocketrealm.desktop.ui.ControlsScreen
import com.pocketrealm.desktop.ui.DiagnosticsScreen
import com.pocketrealm.desktop.ui.FirstRunTutorialHost
import com.pocketrealm.desktop.ui.HomeScreen
import com.pocketrealm.desktop.ui.InGameSettingsScreen
import com.pocketrealm.desktop.ui.ImportScreen
import com.pocketrealm.desktop.ui.LlmScreen
import com.pocketrealm.desktop.ui.PlayersScreen
import com.pocketrealm.desktop.ui.RealmBrowserScreen
import com.pocketrealm.desktop.ui.SettingsScreen

fun main() = application {
    // Packaged-image smoke lane: -Dpocketrealm.nativeSmoke=1 makes the
    // very first thing this main does a native DLL touch through the
    // launcher-configured java.library.path (the jpackage $APPDIR
    // expansion), then exit 0/1 without opening a window. That converts
    // "the exe starts" into "the exe can actually load its bundled
    // natives" — packageApp runs this against the finished image with a
    // scrubbed PATH as its boot gate (the exe-dir dependent-DLL
    // resolution proof); manually: PocketRealm.exe with
    // JAVA_TOOL_OPTIONS=-Dpocketrealm.nativeSmoke=1
    if (System.getProperty("pocketrealm.nativeSmoke") != null) {
        // Never returns: exits 0/1 after the DLL-load probe.
        runNativeSmoke()
    }
    val clientDirOverride = System.getProperty("clientDir")?.let { path -> java.io.File(path) }
    // DESK-113 single-owner runtime domain: a second app instance refuses
    // HERE — before any second supervisor/journal exists — with a dialog
    // naming the holder pid.
    //
    // RELAUNCH HANDOFF EXCEPTION: a relaunched instance (the spent-world
    // relaunch AND the updater's restart-into-update spawn the fresh
    // process BEFORE the old one drains — both stamp RelaunchHandoff) is
    // born while its predecessor still holds the lock. A plain double-launch
    // refuses instantly; a handoff launch waits a bounded window for the
    // draining predecessor to release the lock and only refuses if it is
    // still held past that window.
    val model = try {
        DesktopAppModel(clientDirOverride = clientDirOverride)
    } catch (held: AppInstanceLockHeld) {
        val handoff = RelaunchHandoff.isHandoffLaunch()
        val acquired = if (handoff) waitForRelaunchHandoff(clientDirOverride) else null
        if (acquired == null) {
            DesktopLog.attachFile(DesktopStorageRoots().logs)
            DesktopLog.w(
                "Main",
                if (handoff) "relaunch handoff timed out: ${held.message}" else "second instance refused: ${held.message}",
            )
            SecondInstanceRefusalDialog(held)
            return@application
        }
        acquired
    }
    DesktopLog.attachFile(model.roots.logs)
    DesktopLog.i("Main", "Pocket Realm for Windows starting (${model.settings.value.runtimeMode})")
    val drain = registerSaveExitPaths(model)
    // Plain snapshot-state holders (created outside composition on
    // purpose): onCloseRequest writes them from the UI thread, the drain
    // coroutine completes them from IO, and the Window content below
    // observes both. The drain's once-flag is the real guard; this is the
    // scrim's state.
    val closeRequested = mutableStateOf(false)
    val drained = mutableStateOf(false)
    Window(
        onCloseRequest = {
            // Closing the window ends the session: drain the stack
            // (client -> world -> realm -> database) on a background
            // thread so a running realm saves instead of orphaning
            // WoW.exe and WAL sidecars. Bounded by the native stop
            // timeouts; the UI shows a scrim meanwhile and the process
            // exits when the drain settles. A second close click during
            // the drain is absorbed (one drain, one exit).
            if (drain.requestAsync(onSettled = { drained.value = true })) {
                closeRequested.value = true
            }
        },
        title = "Pocket Realm (Windows)",
    ) {
        if (closeRequested.value) {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Saving the realm and stopping…",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        } else {
            PocketRealmDesktopApp(model)
        }
        LaunchedEffect(drained.value) {
            // exitApplication from composition (UI thread), mirroring the
            // button-handler usage the Compose Desktop docs describe.
            if (drained.value) {
                exitApplication()
            }
        }
    }
}

/**
 * DESK-109/111: the one save-exit drain — window close AND the JVM shutdown
 * hook (Windows logoff/shutdown/restart/taskkill never fire onCloseRequest)
 * share its once-flag, so the stack drains exactly once in either order:
 * save world -> stop natives -> client destroy, bounded by the supervisor's
 * native stop timeouts. Internal so the saveExitGate exercise main registers
 * the IDENTICAL hook (not a re-implementation).
 */
internal fun registerSaveExitPaths(model: DesktopAppModel): SaveExitDrain {
    val drain = SaveExitDrain(model)
    Runtime.getRuntime().addShutdownHook(
        Thread({ drain.runBlockingOnShutdown() }, "pocketrealm-save-exit"),
    )
    return drain
}

/**
 * The relaunch handoff wait: a relaunched fresh instance polls the
 * single-instance lock (by re-attempting the model construction — a held
 * lock throws before any runtime seam exists, so a failed attempt
 * constructs nothing) until its draining predecessor releases the lock or
 * [RelaunchHandoff]'s window passes. Only [AppInstanceLockHeld] is
 * retried; any other constructor failure propagates honestly. The pure
 * wait mechanics live in [RelaunchHandoff.waitFor] (pinned by tests);
 * this wrapper carries the log sink and the real constructor.
 */
private fun waitForRelaunchHandoff(clientDirOverride: java.io.File?): DesktopAppModel? {
    DesktopLog.attachFile(DesktopStorageRoots().logs)
    return RelaunchHandoff.waitFor(
        factory = { DesktopAppModel(clientDirOverride = clientDirOverride) },
    )
}

/** The single-instance refusal window: names the holder pid, then exits. */
@Composable
private fun SecondInstanceRefusalDialog(held: AppInstanceLockHeld) {
    Window(
        onCloseRequest = { kotlin.system.exitProcess(0) },
        title = "Pocket Realm is already running",
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Pocket Realm is already running", style = MaterialTheme.typography.headlineSmall)
            Text(
                (held.holderPid?.let { "The running instance's process id is $it. " } ?: "") +
                    "Only one instance can own the realm at a time — close the other " +
                    "window first, then start this one again.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Suppress("TooGenericExceptionCaught") // smoke lane: any native failure is the result
private fun runNativeSmoke(): Nothing {
    try {
        val version = com.pocketrealm.database.DesktopSqlite.versionNative()
        println("nativeSmoke: pocket_sqlite loaded, sqlite $version")
        // The realm runtimes' static imports (libmysql.dll + the VC runtime
        // DLLs) resolve through the EXE's directory, never
        // java.library.path — loading all three DLLs is exactly the
        // dependent-DLL resolution the packageApp boot gate exists to prove.
        System.loadLibrary("pocket_realmd_runtime")
        System.loadLibrary("pocket_world_runtime")
        println("nativeSmoke: pocket_realmd_runtime + pocket_world_runtime loaded")
        println("NATIVE SMOKE PASSED")
    } catch (failure: Throwable) {
        System.err.println("nativeSmoke: native load failed: ${failure.message}")
        System.err.println("NATIVE SMOKE FAILED")
        kotlin.system.exitProcess(1)
    }
    kotlin.system.exitProcess(0)
}

@Composable
private fun PocketRealmDesktopApp(model: DesktopAppModel) {
    val router = remember { DesktopRouter() }
    var current by remember { mutableStateOf(router.current) }

    Scaffold { padding ->
        Row(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                DESKTOP_ROUTES.forEach { route ->
                    NavigationRailItem(
                        selected = route == current,
                        onClick = {
                            router.navigate(route)
                            current = route
                        },
                        label = { Text(route.label) },
                        icon = {},
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (current) {
                    Route.Home -> HomeScreen(
                        model = model,
                        onOpenBots = { current = Route.Bots },
                        onOpenSettings = { current = Route.Settings },
                        onOpenImport = { current = Route.Import },
                        onOpenPlayers = { current = Route.Players },
                    )
                    Route.Import -> ImportScreen(model)
                    Route.Bots -> BotsScreen(model)
                    Route.Llm -> LlmScreen(model)
                    Route.Players -> PlayersScreen(model)
                    Route.Realms -> RealmBrowserScreen(model)
                    Route.Settings -> SettingsScreen(model, onOpenLlm = { current = Route.Llm })
                    Route.InGameSettings -> InGameSettingsScreen(model)
                    Route.Controls -> ControlsScreen(model)
                    Route.Addons -> AddonsScreen(model)

                    Route.Diagnostics -> DiagnosticsScreen(model)
                }
            }
        }
    }
    // DESK-16/31/49/92/105: the first-run tutorial renders over any route
    // (fresh installs: while setupComplete is unset; replays: from the
    // Settings Setup card). The final hand-off opens the Import route —
    // the desktop's pick/import client leg.
    FirstRunTutorialHost(model, onOpenImport = { current = Route.Import })
}
