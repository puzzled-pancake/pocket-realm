package com.pocketrealm.desktop

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.pocketrealm.desktop.ui.BotsScreen
import com.pocketrealm.desktop.ui.DiagnosticsScreen
import com.pocketrealm.desktop.ui.HomeScreen
import com.pocketrealm.desktop.ui.LlmScreen
import com.pocketrealm.desktop.ui.SettingsScreen

fun main() = application {
    // Packaged-image smoke lane: -Dpocketrealm.nativeSmoke=1 makes the
    // very first thing this main does a native DLL touch through the
    // launcher-configured java.library.path (the jpackage $APPDIR
    // expansion), then exit 0/1 without opening a window. That converts
    // "the exe starts" into "the exe can actually load its bundled
    // natives" — run it as: PocketRealm.exe with
    // JAVA_TOOL_OPTIONS=-Dpocketrealm.nativeSmoke=1
    if (System.getProperty("pocketrealm.nativeSmoke") != null) {
        runNativeSmoke()
        return@application
    }
    val clientDirOverride = System.getProperty("clientDir")?.let { path -> java.io.File(path) }
    val model = DesktopAppModel(clientDirOverride = clientDirOverride)
    DesktopLog.attachFile(model.roots.logs)
    DesktopLog.i("Main", "Pocket Realm for Windows starting (${model.settings.value.runtimeMode})")
    Window(
        onCloseRequest = {
            // Closing the window ends the session: drain the stack through
            // the supervisor (client -> world -> realm -> database) so a
            // running realm saves instead of orphaning WoW.exe and WAL
            // sidecars. Bounded by the native stop timeouts.
            kotlinx.coroutines.runBlocking { runCatching { model.saveAndExit() } }
            model.close()
            ::exitApplication
        },
        title = "Pocket Realm (Windows)",
    ) {
        PocketRealmDesktopApp(model)
    }
}

@Suppress("TooGenericExceptionCaught") // smoke lane: any native failure is the result
private fun runNativeSmoke(): Nothing {
    try {
        val version = com.pocketrealm.database.DesktopSqlite.versionNative()
        println("nativeSmoke: pocket_sqlite loaded, sqlite $version")
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
                    )
                    Route.Bots -> BotsScreen(model)
                    Route.Llm -> LlmScreen(model)
                    Route.Settings -> SettingsScreen(model, onOpenLlm = { current = Route.Llm })
                    Route.Diagnostics -> DiagnosticsScreen(model)
                }
            }
        }
    }
}
