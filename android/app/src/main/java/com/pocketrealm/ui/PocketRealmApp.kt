package com.pocketrealm.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navigation
import com.pocketrealm.ingame.WowSettingSection
import com.pocketrealm.log.AppLog
import com.pocketrealm.realm.RealmState
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.RuntimeSupervisorClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Responsive product shell: bottom navigation on phones, controller-friendly
 * rail in landscape. Landscape keeps a compact 52 dp app bar (route title +
 * realm status) so content starts immediately under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketRealmApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination
    val route = current?.route
    val topLevel = route in setOf(
        Screen.Home.route,
        Screen.Bots.route,
        Screen.Lan.route,
        AddonRoutes.HUB,
        Screen.Controls.route,
        Screen.Settings.route,
    )
    val context = LocalContext.current
    val supervisorClient = remember(context) { RuntimeSupervisorClient(context) }
    val realmState by remember(supervisorClient) {
        supervisorClient.observeRealmState()
    }.collectAsState(initial = RealmState.Idle)

    // First-run tutorial gate. Null until DataStore's first emission and
    // until the managed-client pointer probe resolves: deciding on the
    // default snapshot (setupComplete=false) in that cold-start window
    // would flash the dialog at returning users on every launch.
    val settings = remember(context) { Settings(context) }
    val settingsSnapshot by settings.flow.collectAsState(initial = null)
    val hasImportedClient by produceState<Boolean?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            managedClientImported(
                pointerFileExists = File(context.noBackupFilesDir, "client/active.json").isFile,
                legacyDirExists = File(context.noBackupFilesDir, "client/active").isDirectory,
            )
        }
    }
    val replayRequest by TutorialReplayRequests.requests.collectAsState()
    var tutorialDismissed by rememberSaveable { mutableStateOf(false) }
    val tutorialScope = rememberCoroutineScope()

    // A replay request re-opens the guide even after an earlier dismissal;
    // reset as a side effect so the flag is never written during composition.
    LaunchedEffect(replayRequest) {
        if (replayRequest > 0) tutorialDismissed = false
    }

    // Returning users: an already-imported client seals setup once, silently
    // (the tutorial is for first-run only). Guarded so sealed installs never
    // rewrite the whole preferences snapshot on every launch.
    LaunchedEffect(settingsSnapshot?.setupComplete, hasImportedClient) {
        if (settingsSnapshot?.setupComplete == false && hasImportedClient == true) {
            settings.update { it.copy(setupComplete = true) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = paneLayout(maxWidth.value, maxHeight.value) == PaneLayout.WIDE
        val contentWidth = if (wide) maxWidth - 88.dp else maxWidth
        Scaffold(
            topBar = {
                PocketRealmTopBar(
                    title = screenTitle(route),
                    topLevel = topLevel,
                    realmState = realmState,
                    onBack = { navController.popBackStack() },
                    onSettings = { navigateTop(navController, Screen.Settings.route) },
                )
            },
            bottomBar = {
                if (!wide) {
                    NavigationBar {
                        topDestinations.forEach { destination ->
                            val selected =
                                current?.hierarchy?.any { it.route == destination.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTop(navController, destination.route) },
                                icon = {
                                    Icon(destination.icon, contentDescription = destination.label)
                                },
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
                        // The weight bounds the scroll viewport to the remaining
                        // rail height, so short landscape screens scroll to the
                        // last destination instead of clipping it. spacedBy
                        // mirrors material3's internal NavigationRailVerticalPadding
                        // (4.dp, an internal token) so spacing is unchanged.
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            topDestinations.forEach { destination ->
                                val selected =
                                    current?.hierarchy?.any { it.route == destination.route } == true
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateTop(navController, destination.route) },
                                    icon = {
                                        Icon(destination.icon, contentDescription = destination.label)
                                    },
                                    label = { Text(destination.label) },
                                )
                            }
                        }
                    }
                }
                NavHost(
                    navController = navController,
                    startDestination = Screen.Home.route,
                    modifier = Modifier.width(contentWidth),
                ) {
                    composable(Screen.Home.route) {
                        HomeScreen(
                            onOpenBots = { navigatePush(navController, Screen.Bots.route) },
                            onOpenSettings = { navigatePush(navController, Screen.Settings.route) },
                        )
                    }
                    composable(Screen.Bots.route) {
                        BotsScreen()
                    }
                    composable(Screen.Lan.route) {
                        LanScreen()
                    }
                    navigation(startDestination = AddonRoutes.HUB, route = Screen.Addons.route) {
                        composable(AddonRoutes.HUB) {
                            AddonsHubScreen(
                                onRecommended = { navigatePush(navController, AddonRoutes.RECOMMENDED) },
                                onInstalled = { navigatePush(navController, AddonRoutes.INSTALLED) },
                                onBrowse = { navigatePush(navController, AddonRoutes.BROWSE) },
                                onCustom = { navigatePush(navController, AddonRoutes.CUSTOM) },
                            )
                        }
                        composable(AddonRoutes.RECOMMENDED) {
                            RecommendedAddonsScreen { matrixId ->
                                navigatePush(navController, AddonRoutes.catalogDetail(matrixId))
                            }
                        }
                        composable(AddonRoutes.INSTALLED) {
                            InstalledAddonsScreen(
                                onOpenCatalogAddon = { matrixId ->
                                    navigatePush(navController, AddonRoutes.catalogDetail(matrixId))
                                },
                                onOpenCustomAddon = { installId ->
                                    navigatePush(navController, AddonRoutes.installedDetail(installId))
                                },
                            )
                        }
                        composable(AddonRoutes.BROWSE) {
                            BrowseAddonsScreen { matrixId ->
                                navigatePush(navController, AddonRoutes.catalogDetail(matrixId))
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
                    navigation(startDestination = InGameRoutes.HUB, route = InGameRoutes.GRAPH) {
                        composable(InGameRoutes.HUB) {
                            InGameSettingsHubScreen(
                                onOpenRoute = { route -> navigatePush(navController, route) },
                            )
                        }
                        composable(InGameRoutes.GRAPHICS) {
                            InGameCategoryScreen(WowSettingSection.GRAPHICS)
                        }
                        composable(InGameRoutes.SOUND) {
                            InGameCategoryScreen(WowSettingSection.SOUND)
                        }
                        composable(InGameRoutes.INTERFACE) {
                            InGameCategoryScreen(WowSettingSection.INTERFACE)
                        }
                        composable(InGameRoutes.INTERFACE_ADVANCED) {
                            InGameCategoryScreen(WowSettingSection.INTERFACE_ADVANCED)
                        }
                        composable(InGameRoutes.BINDINGS) { InGameBindingsScreen() }
                    }
                    composable(Screen.Controls.route) { ControlsScreen() }
                    composable(Screen.Settings.route) {
                        SettingsScreen(
                            onBots = { navigatePush(navController, Screen.Bots.route) },
                            onClientSetup = { navigatePush(navController, Screen.Client.route) },
                            onCapability = { navigatePush(navController, Screen.Capability.route) },
                            onDiagnostics = { navigatePush(navController, Screen.Diagnostics.route) },
                            onInGameSettings = { navigatePush(navController, InGameRoutes.HUB) },
                        )
                    }
                    composable(Screen.Client.route) { ClientScreen(PaddingValues()) }
                    composable(Screen.Capability.route) { CapabilityScreen() }
                    composable(Screen.Diagnostics.route) { DiagnosticsScreen() }
                }
            }
        }

        if (tutorialVisible(
                settingsSnapshot?.setupComplete,
                hasImportedClient,
                replayRequest,
            ) && !tutorialDismissed
        ) {
            FirstRunTutorialOverlay(
                onFinish = { chooseFolder ->
                    TutorialReplayRequests.consume()
                    tutorialDismissed = true
                    val snapshotSetupComplete = settingsSnapshot?.setupComplete
                    // NonCancellable: a rotation right after the tap must not
                    // cancel the persisted seal (the local flag alone dies
                    // with the process), or the tutorial would return.
                    tutorialScope.launch {
                        withContext(NonCancellable) {
                            if (tutorialSealWrite(snapshotSetupComplete, dismissed = true)) {
                                settings.update { it.copy(setupComplete = true) }
                            }
                        }
                    }
                    if (chooseFolder) {
                        ClientPickerAutoOpen.pending = true
                        navigatePush(navController, Screen.Client.route)
                    }
                },
            )
        }
    }
}

/**
 * Compact landscape app header (brief §2): 52 dp tall, route title left,
 * short realm status and a settings gear right. The side rail already
 * communicates location, so no large decorative title band is spent.
 */
@Composable
private fun PocketRealmTopBar(
    title: String,
    topLevel: Boolean,
    realmState: RealmState,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxWidth().height(52.dp).testTag("app-top-bar")) {
            // The weighted, single-line title keeps long titles from colliding
            // with the status badge and gear on narrow windows. The horizontal
            // safe-drawing inset keeps the gear clear of the edge-to-edge
            // navigation-bar strip (the shell window spans the full display).
            Row(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!topLevel) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("top-bar-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (topLevel) 12.dp else 0.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    realmStatusBadge(realmState),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .testTag("top-bar-realm-status"),
                    maxLines = 1,
                )
                IconButton(onClick = onSettings, modifier = Modifier.testTag("top-bar-settings")) {
                    Icon(Icons.Filled.Settings, contentDescription = "Open settings")
                }
            }
            HorizontalDivider(Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Short realm state label for the compact top bar. */
internal fun realmStatusBadge(state: RealmState): String = when (state) {
    is RealmState.Idle -> "Realm stopped"
    is RealmState.Starting -> "Starting…"
    is RealmState.Running ->
        if (state.mode == com.pocketrealm.supervisor.RuntimeMode.LAN_JOIN) "LAN client" else "Realm online"
    is RealmState.Saving -> "Saving…"
    is RealmState.Stopping -> "Stopping…"
    is RealmState.Recovering -> "Recovering…"
    is RealmState.Failed -> "Needs attention"
}

/**
 * Top-level selection must always land on the destination. Re-tapping the
 * current destination is a no-op; otherwise reveal an existing back-stack
 * entry directly (anything above it pops, saving state) and only fall back
 * to pop-to-Home + a plain singleTop push when no entry exists yet. This
 * deliberately avoids navigate()+restoreState, which on androidx.navigation
 * 2.8.x can silently do nothing when saved state exists for the target.
 */
private fun navigateTop(navController: androidx.navigation.NavHostController, route: String) {
    if (navController.currentDestination?.hierarchy?.any { it.route == route } == true) return
    AppLog.i("Nav", "top: $route (from=${navController.currentDestination?.route})")
    // Graph routes never appear as back-stack entries; reveal via the
    // Add-ons hub instead so an existing hub entry is reused, not rebuilt.
    val revealRoute = if (route == Screen.Addons.route) AddonRoutes.HUB else route
    if (navController.popBackStack(revealRoute, inclusive = false, saveState = true)) return
    if (route != Screen.Home.route) {
        // Keep top-level destinations siblings of Home, not a growing stack.
        navController.popBackStack(Screen.Home.route, inclusive = false, saveState = true)
    }
    navController.navigate(route) { launchSingleTop = true }
}

/** In-content pushes keep the back stack; singleTop guards double-taps. */
private fun navigatePush(navController: androidx.navigation.NavHostController, route: String) {
    AppLog.i("Nav", "push: $route (from=${navController.currentDestination?.route})")
    navController.navigate(route) { launchSingleTop = true }
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
    route?.startsWith(InGameRoutes.GRAPH) == true -> InGameRoutes.titleFor(route)
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
    data object Bots : Screen("bots", "Bots", Icons.Filled.SmartToy)
    data object Lan : Screen("lan", "LAN", Icons.Filled.Lan)
    data object Addons : Screen("addons", "Add-ons", Icons.Filled.Extension)
    data object Controls : Screen("controls", "Controls", Icons.Filled.Tune)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
    data object Client : Screen("client", "Game setup", Icons.Filled.Storage)
    data object Capability : Screen("capability", "Device report", Icons.Filled.BugReport)
    data object Diagnostics : Screen("diagnostics", "Diagnostics", Icons.Filled.BugReport)

    companion object {
        fun fromRoute(route: String?): Screen? = when (route) {
            "home" -> Home
            "bots" -> Bots
            "lan" -> Lan
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

/** Landscape side-rail order per the brief: Home, Bots, LAN, Add-ons, Controls, Settings. */
private val topDestinations = listOf(
    Screen.Home,
    Screen.Bots,
    Screen.Lan,
    Screen.Addons,
    Screen.Controls,
    Screen.Settings,
)

internal enum class PaneLayout { WIDE, STACKED }

/** Shared, pure responsive rule used by landscape screens and host UI tests. */
internal fun paneLayout(widthDp: Float, heightDp: Float): PaneLayout =
    if (widthDp >= 600f && widthDp > heightDp) PaneLayout.WIDE else PaneLayout.STACKED
