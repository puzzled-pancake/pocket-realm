package com.pocketrealm.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

fun main() = application {
    val roots = DesktopStorageRoots()
    roots.ensureDirectories()
    DesktopLog.attachFile(roots.logs)
    val settings = DesktopSettingsStore(roots.settingsFile).load()
    DesktopLog.i("Main", "Pocket Realm for Windows starting (${settings.runtimeMode})")
    Window(
        onCloseRequest = ::exitApplication,
        title = "Pocket Realm (Windows)",
    ) {
        PocketRealmDesktopApp()
    }
}

@Composable
private fun PocketRealmDesktopApp() {
    val router = remember { DesktopRouter() }
    var current by remember { mutableStateOf(router.current) }

    Scaffold { padding ->
        Row(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
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
                    Route.Home -> HomeSkeleton()
                    Route.Bots -> ScreenSkeleton("Bots")
                    Route.Llm -> ScreenSkeleton("LLM")
                    Route.Settings -> ScreenSkeleton("Settings")
                    Route.Diagnostics -> ScreenSkeleton("Diagnostics")
                }
            }
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Text(
        text = "Pocket Realm for Windows",
        style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
    )
    Text(
        text = "Phase 0 skeleton — the shared supervisor/database/server domain " +
            "sources compile in this build; runtime bring-up arrives with the " +
            "native realm lane.",
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ScreenSkeleton(name: String) {
    Text(
        text = name,
        style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
    )
    Text(
        text = "Not yet ported.",
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
    )
}
