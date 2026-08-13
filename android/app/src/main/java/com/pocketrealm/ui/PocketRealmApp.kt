package com.pocketrealm.ui

import android.net.Uri
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navigation

/** Responsive product shell: bottom navigation on phones, controller-friendly rail in landscape. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketRealmApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination
    val route = current?.route
    val topLevel = route in setOf(
        Screen.Home.route,
        AddonRoutes.HUB,
        Screen.Controls.route,
        Screen.Settings.route,
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = paneLayout(maxWidth.value, maxHeight.value) == PaneLayout.WIDE
        val contentWidth = if (wide) maxWidth - 88.dp else maxWidth
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(screenTitle(route)) },
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
                    NavigationRail(Modifier.width(88.dp)) {
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
                    navigation(startDestination = AddonRoutes.HUB, route = Screen.Addons.route) {
                        composable(AddonRoutes.HUB) {
                            AddonsHubScreen(
                                onRecommended = { navController.navigate(AddonRoutes.RECOMMENDED) },
                                onInstalled = { navController.navigate(AddonRoutes.INSTALLED) },
                                onBrowse = { navController.navigate(AddonRoutes.BROWSE) },
                                onCustom = { navController.navigate(AddonRoutes.CUSTOM) },
                            )
                        }
                        composable(AddonRoutes.RECOMMENDED) {
                            RecommendedAddonsScreen { matrixId ->
                                navController.navigate(AddonRoutes.catalogDetail(matrixId))
                            }
                        }
                        composable(AddonRoutes.INSTALLED) {
                            InstalledAddonsScreen(
                                onOpenCatalogAddon = { matrixId ->
                                    navController.navigate(AddonRoutes.catalogDetail(matrixId))
                                },
                                onOpenCustomAddon = { installId ->
                                    navController.navigate(AddonRoutes.installedDetail(installId))
                                },
                            )
                        }
                        composable(AddonRoutes.BROWSE) {
                            BrowseAddonsScreen { matrixId ->
                                navController.navigate(AddonRoutes.catalogDetail(matrixId))
                            }
                        }
                        composable(AddonRoutes.CUSTOM) { CustomAddonInstallScreen() }
                        composable(
                            AddonRoutes.CATALOG_DETAIL,
                            arguments = listOf(navArgument("matrixId") { type = NavType.StringType }),
                        ) { entry ->
                            CatalogAddonDetailScreen(checkNotNull(entry.arguments?.getString("matrixId")))
                        }
                        composable(
                            AddonRoutes.INSTALLED_DETAIL,
                            arguments = listOf(navArgument("installId") { type = NavType.StringType }),
                        ) { entry ->
                            InstalledAddonDetailScreen(
                                Uri.decode(checkNotNull(entry.arguments?.getString("installId"))),
                            )
                        }
                    }
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

internal object AddonRoutes {
    const val HUB = "addons/home"
    const val RECOMMENDED = "addons/recommended"
    const val INSTALLED = "addons/installed"
    const val BROWSE = "addons/browse"
    const val CUSTOM = "addons/custom"
    const val CATALOG_DETAIL = "addons/catalog/{matrixId}"
    const val INSTALLED_DETAIL = "addons/installed/{installId}"

    fun catalogDetail(matrixId: String): String {
        require(matrixId.matches(Regex("\\d{3}"))) { "invalid catalog add-on identity" }
        return "addons/catalog/$matrixId"
    }

    fun installedDetail(installId: String): String = "addons/installed/${Uri.encode(installId)}"
}

internal fun screenTitle(route: String?): String = when {
    route == AddonRoutes.HUB -> "Add-ons"
    route == AddonRoutes.RECOMMENDED -> "Recommended add-ons"
    route == AddonRoutes.INSTALLED -> "My add-ons"
    route == AddonRoutes.BROWSE -> "Browse add-ons"
    route == AddonRoutes.CUSTOM -> "Install from GitHub"
    route == AddonRoutes.CATALOG_DETAIL -> "Add-on details"
    route == AddonRoutes.INSTALLED_DETAIL -> "Installed add-on"
    else -> Screen.fromRoute(route)?.label ?: "Pocket Realm"
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

internal enum class PaneLayout { WIDE, STACKED }

/** Shared, pure responsive rule used by landscape screens and host UI tests. */
internal fun paneLayout(widthDp: Float, heightDp: Float): PaneLayout =
    if (widthDp >= 600f && widthDp > heightDp) PaneLayout.WIDE else PaneLayout.STACKED
