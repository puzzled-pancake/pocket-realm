package com.pocketrealm.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/** Responsive product shell: bottom navigation on phones, controller-friendly rail in landscape. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketRealmApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination
    val route = current?.route
    val topLevel = topDestinations.any { destination ->
        current?.hierarchy?.any { it.route == destination.route } == true
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val contentWidth = if (wide) maxWidth - 104.dp else maxWidth
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(Screen.fromRoute(route)?.label ?: "Pocket Realm") },
                    navigationIcon = {
                        if (!topLevel) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (!wide) {
                    NavigationBar {
                        topDestinations.forEach { destination ->
                            val selected = current?.hierarchy?.any { it.route == destination.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTop(navController, destination.route) },
                                icon = { Icon(destination.icon, contentDescription = destination.label) },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }
            },
        ) { inner ->
            Row(Modifier.fillMaxSize().padding(inner)) {
                if (wide) {
                    NavigationRail(Modifier.width(104.dp)) {
                        topDestinations.forEach { destination ->
                            val selected = current?.hierarchy?.any { it.route == destination.route } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTop(navController, destination.route) },
                                icon = { Icon(destination.icon, contentDescription = destination.label) },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }
                NavHost(
                    navController = navController,
                    startDestination = Screen.Home.route,
                    modifier = Modifier.width(contentWidth),
                ) {
                    composable(Screen.Home.route) { HomeScreen() }
                    composable(Screen.Addons.route) { AddonsScreen() }
                    composable(Screen.Controls.route) { ControlsScreen() }
                    composable(Screen.Settings.route) {
                        SettingsScreen(
                            onClientSetup = { navController.navigate(Screen.Client.route) },
                            onCapability = { navController.navigate(Screen.Capability.route) },
                            onDiagnostics = { navController.navigate(Screen.Diagnostics.route) },
                        )
                    }
                    composable(Screen.Client.route) { ClientScreen(PaddingValues()) }
                    composable(Screen.Capability.route) { CapabilityScreen() }
                    composable(Screen.Diagnostics.route) { DiagnosticsScreen() }
                }
            }
        }
    }
}

private fun navigateTop(navController: androidx.navigation.NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Screen("home", "Home", Icons.Filled.Home)
    data object Addons : Screen("addons", "Add-ons", Icons.Filled.Extension)
    data object Controls : Screen("controls", "Controls", Icons.Filled.Tune)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
    data object Client : Screen("client", "Game setup", Icons.Filled.Storage)
    data object Capability : Screen("capability", "Device report", Icons.Filled.BugReport)
    data object Diagnostics : Screen("diagnostics", "Diagnostics", Icons.Filled.BugReport)

    companion object {
        fun fromRoute(route: String?): Screen? = when (route) {
            "home" -> Home
            "addons" -> Addons
            "controls" -> Controls
            "settings" -> Settings
            "client" -> Client
            "capability" -> Capability
            "diagnostics" -> Diagnostics
            else -> null
        }
    }
}

private val topDestinations = listOf(Screen.Home, Screen.Addons, Screen.Controls, Screen.Settings)
