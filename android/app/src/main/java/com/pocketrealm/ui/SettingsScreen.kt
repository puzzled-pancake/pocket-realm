package com.pocketrealm.ui

import android.os.Build

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pocketrealm.BuildConfig
import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ArmTranslationBackend
import com.pocketrealm.client.ArmRendererAuto
import com.pocketrealm.client.ArmClientRenderer
import com.pocketrealm.client.ArmClientRendererCatalog
import com.pocketrealm.client.AndroidGladioCapabilityProbe
import com.pocketrealm.client.AndroidSystemVulkanProbe
import com.pocketrealm.client.ClientAudioPolicy
import com.pocketrealm.client.ClientDisplayCapabilities
import com.pocketrealm.client.ClientDisplayProfile
import com.pocketrealm.client.ClientFrameCap
import com.pocketrealm.client.ClientRuntimeSelector
import com.pocketrealm.client.ClientRuntimeContract
import com.pocketrealm.client.ClientTweaksConfig
import com.pocketrealm.client.CommunityVulkanDriverDownload
import com.pocketrealm.client.CommunityVulkanDrivers
import com.pocketrealm.client.ManagedClientStore
import com.pocketrealm.client.RendererPackageCatalog
import com.pocketrealm.client.SystemVulkanCapabilities
import com.pocketrealm.client.GladioCapability
import com.pocketrealm.client.VulkanDriverCatalog
import com.pocketrealm.client.UserVulkanDriver
import com.pocketrealm.client.UserVulkanDriverImport
import com.pocketrealm.client.UserVulkanDriverRegistry
import com.pocketrealm.client.UserVulkanDriverResolution
import com.pocketrealm.client.UserVulkanDriverValidator
import com.pocketrealm.server.NearbyInteractPolicy
import com.pocketrealm.server.XpRatePolicy
import com.pocketrealm.storage.Settings
import com.pocketrealm.update.AppUpdateCoordinator
import com.pocketrealm.storage.ContentVolume
import com.pocketrealm.storage.ContentVolumeSetting
import com.pocketrealm.storage.StorageRoots
import com.pocketrealm.supervisor.RuntimeSupervisorClient
import com.pocketrealm.supervisor.UserAccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/*
 * General application/server settings. Bot world configuration lives in the
 * dedicated Bots destination; LAN host/join lives in the LAN destination.
 */

private const val MIB = 1024L * 1024

/** Stage chips per row in the World-timeframe card (design-timeframe ). */
private const val TIMEFRAME_CHIP_ROW = 3

/**
 * Settings is a short hub of categories; each category page shows only its
 * own cards, so the long tail of expert options sits one tap deeper.
 */
enum class SettingsCategory(val slug: String, val title: String, val summary: String) {
    WORLD("world", "Bots & world", "Bot population, presets and the world timeline"),
    GRAPHICS(
        "graphics", "Graphics & display",
        "Renderer, Vulkan driver, DXVK, resolution, frame-rate limit, interface scale",
    ),
    GAME(
        "game", "Game client",
        "Sound, auto-login, client tweaks, in-game options, nearby use, input safe mode",
    ),
    DATA("data", "Storage & realm data", "Where game files live, realm backup and import"),
    APP("app", "App & support", "Updates, setup guide, game files, device report, diagnostics, logs"),
    ;

    companion object {
        fun fromSlug(slug: String?): SettingsCategory? = entries.firstOrNull { it.slug == slug }
    }
}

@Composable
// The category pair joins the sibling navigation params; the list is
// longer than the default cap by design.
@Suppress("LongParameterList")
fun SettingsScreen(
    contentPadding: PaddingValues = PaddingValues(),
    category: SettingsCategory? = null,
    onOpenCategory: (SettingsCategory) -> Unit = {},
    onClientSetup: (() -> Unit)? = null,
    onCapability: (() -> Unit)? = null,
    onDiagnostics: (() -> Unit)? = null,
    onInGameSettings: (() -> Unit)? = null,
) {
    if (category == null) {
        SettingsHub(contentPadding, onOpenCategory)
        return
    }
    val context = LocalContext.current
    val settings = remember(context) { Settings(context) }
    val snap by settings.flow.collectAsState(initial = Settings.Snapshot())
    val scope = rememberCoroutineScope()
    val systemVulkanProbe by produceState<Result<SystemVulkanCapabilities>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { AndroidSystemVulkanProbe.probe() } }
    }
    val gladioProbe by produceState<Result<GladioCapability>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching { AndroidGladioCapabilityProbe.probe(context) }
        }
    }
    val physicalDisplay = remember(context) {
        runCatching { ClientDisplayCapabilities.physicalLandscapeBounds(context) }
            .getOrElse {
                val fallback = ClientDisplayProfile.forDevice(
                    Build.SUPPORTED_ABIS.asList(), Build.MODEL,
                )
                fallback.virtualWidth to fallback.virtualHeight
            }
    }
    val availableDisplays = remember(physicalDisplay) {
        ClientDisplayProfile.availableForPhysical(physicalDisplay.first, physicalDisplay.second)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (category == SettingsCategory.GRAPHICS) SettingCard("ARM client runtime") {
            var showExperimentalRenderers by rememberSaveable { mutableStateOf(false) }
            val experimentalSelected =
                snap.armRendererId == ArmClientRenderer.LEGACY_GLADIO.id ||
                snap.armRendererId == ArmClientRenderer.MESA_VIRGL.id
            Text("Renderer", style = MaterialTheme.typography.titleSmall)
            Text("Auto follows the GPU like the wow-mobile Winlator build: Adreno uses DXVK with the packaged Turnip driver, every other GPU uses DXVK over the system Vortek bridge. Manual choices stay exact.",
                style = MaterialTheme.typography.bodySmall)
            FilterChip(
                selected = snap.armRendererId == ArmClientRendererCatalog.AUTO_ID,
                onClick = {
                    scope.launch {
                        settings.update { it.copy(armRendererId = ArmClientRendererCatalog.AUTO_ID) }
                    }
                },
                label = { Text("Auto (recommended)") },
                modifier = Modifier.fillMaxWidth().testTag("arm-renderer-auto"),
            )
            Text(
                "Adreno: Turnip/DXVK. Other GPUs: system Vortek/DXVK, stepping down to " +
                    "DXVK 1.10.3 when the device Vulkan version is older than DXVK 2.4.1 needs.",
                style = MaterialTheme.typography.bodySmall,
            )
            val dxvkAvailability = ArmClientRendererCatalog.availability(
                ArmClientRenderer.DXVK,
                gladioProbe,
                Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            )
            FilterChip(
                selected = snap.armRendererId == ArmClientRenderer.DXVK.id,
                enabled = dxvkAvailability.available,
                onClick = {
                    scope.launch {
                        settings.update { it.copy(armRendererId = ArmClientRenderer.DXVK.id) }
                    }
                },
                label = { Text(ArmClientRenderer.DXVK.label) },
                modifier = Modifier.fillMaxWidth().testTag("arm-renderer-${ArmClientRenderer.DXVK.id}"),
            )
            Text(ArmClientRenderer.DXVK.summary, style = MaterialTheme.typography.bodySmall)
            Text(
                dxvkAvailability.reason,
                color = if (dxvkAvailability.available) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
            )
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = showExperimentalRenderers || experimentalSelected,
                    onCheckedChange = { showExperimentalRenderers = it },
                    modifier = Modifier.testTag("experimental-renderers"),
                )
                Text("  Experimental renderers", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "Legacy OpenGL and Mesa VirGL are very experimental and kept " +
                    "only for device testing. They are never chosen automatically; selecting " +
                    "one is an exact manual choice with no fallback.",
                style = MaterialTheme.typography.bodySmall,
            )
            // Per-game-version graphics-route notice — on TBC/WotLK the GL
            // routes are refused at launch; the renderer surface states
            // that at selection time.
            // With several game versions available and no pick yet, the
            // resolver throws — a render-time card shows the first-pick
            // hint instead of crashing composition.
            val settingsLaneFlavorId = remember {
                com.pocketrealm.client.ActiveFlavor.displayedId
            }
            val glRouteCopy = settingsLaneFlavorId?.let { laneId ->
                com.pocketrealm.client.ClientRuntimeContract.flavorGraphicsRouteCopy(
                    com.pocketrealm.client.ClientFlavors.ALL.first { it.flavorId == laneId },
                    "opengl",
                )
            }
            if (glRouteCopy != null) {
                Text(
                    glRouteCopy,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("renderer-version-copy"),
                )
            }
            if (showExperimentalRenderers || experimentalSelected) {
                listOf(
                    ArmClientRenderer.LEGACY_GLADIO,
                    ArmClientRenderer.MESA_VIRGL,
                ).forEach { renderer ->
                    val availability = ArmClientRendererCatalog.availability(
                        renderer,
                        gladioProbe,
                        Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                    )
                    FilterChip(
                        selected = snap.armRendererId == renderer.id,
                        enabled = availability.available,
                        onClick = {
                            scope.launch {
                                settings.update { it.copy(armRendererId = renderer.id) }
                            }
                        },
                        label = { Text(renderer.label) },
                        modifier = Modifier.fillMaxWidth().testTag("arm-renderer-${renderer.id}"),
                    )
                    Text(renderer.summary, style = MaterialTheme.typography.bodySmall)
                    Text(
                        availability.reason,
                        color = if (availability.available) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            // The section's whole operation state lives OUTSIDE both the
            // renderer conditional and the toggle conditional:
            // import/download coroutines run on the screen scope and keep
            // writing their results while the renderer is switched
            // and switched back, so the state they captured must survive
            // those disposals instead of resetting and silently dropping
            // final notices and list refreshes.
            var communityDownloadId by remember { mutableStateOf<String?>(null) }
            var communityProgress by remember { mutableStateOf(0f) }
            var userDriverRefresh by remember { mutableStateOf(0) }
            var userVulkanStatus by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingDriverDelete by remember { mutableStateOf<String?>(null) }
            var userImportInProgress by remember { mutableStateOf(false) }
            var userDriversUnreadable by remember { mutableStateOf(false) }
            // The in-progress lines (import copy, community download) must
            // not survive process death: nothing is running after restore,
            // so the stale notice is dropped on first composition.
            LaunchedEffect(Unit) {
                if (!userImportInProgress &&
                    UserVulkanDriverPresentation.isTransientInProgressNotice(userVulkanStatus)
                ) {
                    userVulkanStatus = null
                }
            }
            if (snap.selectedArmRendererId() == "dxvk" ||
                snap.selectedArmRendererId() == ArmClientRendererCatalog.AUTO_ID) {
            HorizontalDivider()
            // The driver/DXVK lists are expert choices: Auto picks both, so
            // they fold behind one switch. The current choice and every
            // selection notice stay visible below the fold.
            var showDriverOptions by rememberSaveable { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = showDriverOptions || userImportInProgress || communityDownloadId != null,
                    onCheckedChange = { showDriverOptions = it },
                    modifier = Modifier.testTag("renderer-driver-options"),
                )
                Text("  Vulkan driver & DXVK options", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "Auto picks the driver and DXVK version for this GPU. Open to choose a " +
                    "driver, import your own, or pick an exact DXVK version.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (showDriverOptions || userImportInProgress || communityDownloadId != null) {
            Text("Vulkan driver", style = MaterialTheme.typography.titleSmall)
            Text(
                "Auto follows the GPU vendor like Winlator. Manual drivers stay exact; nothing is substituted at launch.",
                style = MaterialTheme.typography.bodySmall,
            )
            FilterChip(
                selected = snap.selectedVulkanDriverId() == VulkanDriverCatalog.AUTO_ID,
                onClick = {
                    scope.launch {
                        settings.update { current ->
                            current.copy(armVulkanDriverId = VulkanDriverCatalog.AUTO_ID)
                        }
                    }
                },
                label = {
                    val resolved = VulkanDriverCatalog.resolveId(
                        VulkanDriverCatalog.AUTO_ID, ArmRendererAuto.isAdrenoGpu())
                    Text("Auto (currently ${resolved?.label ?: "Turnip"})")
                },
                modifier = Modifier.fillMaxWidth().testTag("vulkan-driver-auto"),
            )
            VulkanDriverCatalog.userSelectable().forEach { driver ->
                val availability = VulkanDriverCatalog.availabilityForPair(
                    driver.id,
                    snap.selectedDxvkPackageId(),
                    ArmRendererAuto.isAdrenoGpu(),
                    systemVulkanProbe?.getOrNull(),
                )
                FilterChip(
                    selected = snap.selectedVulkanDriverId() == driver.id,
                    onClick = {
                        scope.launch {
                            settings.update { current -> current.copy(armVulkanDriverId = driver.id) }
                        }
                    },
                    label = { Text(driver.label) },
                    modifier = Modifier.fillMaxWidth().testTag("vulkan-driver-${driver.id}"),
                )
                Text(driver.summary, style = MaterialTheme.typography.bodySmall)
                Text(
                    if (availability.available) driver.qualification else availability.reason,
                    color = if (availability.available) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = snap.allowUserVulkanDrivers,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settings.update { it.copy(allowUserVulkanDrivers = enabled) }
                        }
                    },
                    modifier = Modifier.testTag("allow-user-vulkan-drivers"),
                )
                Text("  Allow imported drivers", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                UserVulkanDriverPresentation.SECTION_NOTE,
                style = MaterialTheme.typography.bodySmall,
            )
            if (snap.allowUserVulkanDrivers) {
                val userVulkanRegistry = remember(context) {
                    UserVulkanDriverRegistry(
                        UserVulkanDriverRegistry.registryRoot(context.filesDir),
                    )
                }
                val userDrivers by produceState<List<UserVulkanDriver>>(
                    emptyList(), userDriverRefresh,
                ) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { userVulkanRegistry.list() }
                            .onSuccess { userDriversUnreadable = false }
                            .getOrElse { failure ->
                                userDriversUnreadable = true
                                userVulkanStatus =
                                    "Imported drivers could not be read: " +
                                        "${failure.message ?: failure.javaClass.simpleName}"
                                emptyList()
                            }
                    }
                }
                val adrenoGpu = remember { ArmRendererAuto.isAdrenoGpu() }
                val userDriverPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        // enabled only takes effect after recomposition; guard
                        // the frame gap synchronously so a double tap cannot
                        // stage two imports.
                        userImportInProgress = true
                        userVulkanStatus =
                            UserVulkanDriverPresentation.IMPORT_IN_PROGRESS_NOTICE
                        scope.launch(Dispatchers.IO) {
                            try {
                                // A failed import is a status line, never a crash.
                                val outcome = runCatching {
                                    importUserVulkanDriverFromUri(context, uri)
                                }.getOrElse { failure ->
                                    UserVulkanDriverImport.Rejected(
                                        "Import failed: " +
                                            "${failure.message ?: failure.javaClass.simpleName}",
                                    )
                                }
                                userVulkanStatus =
                                    UserVulkanDriverPresentation.importResultNotice(outcome)
                                userDriverRefresh++
                            } finally {
                                userImportInProgress = false
                            }
                        }
                    }
                }
                if (!adrenoGpu) {
                    Text(
                        "This device has a non-Adreno GPU: imported drivers are Mesa Turnip " +
                            "builds and cannot run here. They stay listed for reference only.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(
                    onClick = {
                        userDriverPicker.launch(
                            arrayOf(
                                "application/zip",
                                "application/octet-stream",
                                "application/x-sharedlib",
                            ),
                        )
                    },
                    enabled = !userImportInProgress && communityDownloadId == null,
                    modifier = Modifier.testTag("import-user-vulkan-driver"),
                ) { Text("Import driver (.so / .zip)") }
                var showCommunityDrivers by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { showCommunityDrivers = true },
                    enabled = communityDownloadId == null && !userImportInProgress,
                    modifier = Modifier.testTag("community-vulkan-drivers"),
                ) { Text(UserVulkanDriverPresentation.COMMUNITY_BUTTON_LABEL) }
                if (communityDownloadId != null) {
                    LinearProgressIndicator(
                        progress = { communityProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (showCommunityDrivers) {
                    AlertDialog(
                        onDismissRequest = {
                            if (communityDownloadId == null) showCommunityDrivers = false
                        },
                        title = {
                            Text(UserVulkanDriverPresentation.COMMUNITY_DIALOG_TITLE)
                        },
                        text = {
                            Column {
                                Text(
                                    UserVulkanDriverPresentation.COMMUNITY_DIALOG_NOTE,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                UserVulkanDriverPresentation.communityDriverRows(
                                    CommunityVulkanDrivers.all(),
                                    importedLibrarySha256s =
                                        userDrivers.map { it.sha256 }.toSet(),
                                ).forEach { row ->
                                    val entry =
                                        CommunityVulkanDrivers.find(row.id) ?: return@forEach
                                    TextButton(
                                        onClick = {
                                            // enabled only takes effect after
                                            // recomposition; guard the frame gap
                                            // synchronously (updates-card pattern)
                                            // — including a running SAF import,
                                            // so the two lanes can never overwrite
                                            // each other's final notice.
                                            if (communityDownloadId != null || userImportInProgress) {
                                                return@TextButton
                                            }
                                            showCommunityDrivers = false
                                            communityDownloadId = row.id
                                            communityProgress = 0f
                                            userVulkanStatus =
                                                UserVulkanDriverPresentation
                                                    .communityDownloadStatus(
                                                        row.label, 0, entry.size,
                                                    )
                                            scope.launch(Dispatchers.IO) {
                                                // Pinned download → digest verify → the
                                                // ordinary import path; no bypass (C2/C3).
                                                val staged = File(
                                                    context.cacheDir,
                                                    "community-driver-" +
                                                        "${System.currentTimeMillis()}",
                                                )
                                                val outcome = runCatching {
                                                    CommunityVulkanDriverDownload.download(
                                                        entry, staged,
                                                    ) { bytes ->
                                                        communityProgress =
                                                            bytes.toFloat() / entry.size
                                                        userVulkanStatus =
                                                            UserVulkanDriverPresentation
                                                                .communityDownloadStatus(
                                                                    row.label,
                                                                    bytes,
                                                                    entry.size,
                                                                )
                                                    }
                                                }.getOrElse { failure ->
                                                    CommunityVulkanDriverDownload
                                                        .Outcome
                                                        .Failed(
                                                            UserVulkanDriverPresentation
                                                                .communityDownloadFailureNotice(
                                                                    failure,
                                                                ),
                                                        )
                                                }
                                                val importOutcome = try {
                                                    when (outcome) {
                                                        is CommunityVulkanDriverDownload
                                                        .Outcome.Downloaded ->
                                                            runCatching {
                                                                userVulkanRegistry.import(
                                                                    entry.label, staged,
                                                                )
                                                            }.getOrElse { failure ->
                                                                UserVulkanDriverImport.Rejected(
                                                                    UserVulkanDriverPresentation
                                                                        .communityImportFailureNotice(
                                                                            failure,
                                                                        ),
                                                                )
                                                            }
                                                        is CommunityVulkanDriverDownload
                                                        .Outcome.Failed ->
                                                            UserVulkanDriverImport.Rejected(
                                                                outcome.reason,
                                                            )
                                                    }
                                                } finally {
                                                    // Every path consumes or discards the
                                                    // temp; cacheDir keeps no partials (C2).
                                                    staged.delete()
                                                }
                                                communityDownloadId = null
                                                userVulkanStatus =
                                                    UserVulkanDriverPresentation
                                                        .importResultNotice(importOutcome)
                                                userDriverRefresh++
                                            }
                                        },
                                        enabled = communityDownloadId == null,
                                    ) {
                                        Column {
                                            Text(row.label)
                                            Text(
                                                row.detailLine +
                                                    (row.importedMark?.let { " — $it" }
                                                        ?: ""),
                                                style =
                                                    MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = { showCommunityDrivers = false },
                            ) { Text(UserVulkanDriverPresentation.COMMUNITY_DIALOG_CLOSE) }
                        },
                    )
                }
                UserVulkanDriverPresentation.rows(
                    userDrivers, snap.selectedVulkanDriverId(), adrenoGpu,
                ).forEach { row ->
                    FilterChip(
                        selected = row.selected,
                        enabled = row.enabled,
                        onClick = {
                            scope.launch {
                                settings.update { current ->
                                    current.copy(armVulkanDriverId = row.id)
                                }
                            }
                        },
                        label = { Text(row.label) },
                        modifier = Modifier.fillMaxWidth().testTag("vulkan-driver-${row.id}"),
                    )
                    Text(row.importedLine, style = MaterialTheme.typography.bodySmall)
                    Text(
                        row.statusLine,
                        color = if (row.enabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    TextButton(onClick = { pendingDriverDelete = row.id }) { Text("Delete") }
                }
                if (userDrivers.isEmpty()) {
                    Text(
                        "No imported drivers yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (userDriversUnreadable) {
                    // The repair path for a corrupt registry: the list has no
                    // rows to delete and an import would fail the same way,
                    // so the reset is the only working action.
                    Text(
                        "Imported-driver storage is unreadable. Resetting removes " +
                            "every imported driver; the packaged drivers are unaffected.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = {
                            val wasUserSelection = UserVulkanDriver.isUserId(
                                snap.selectedVulkanDriverId(),
                            )
                            scope.launch(Dispatchers.IO) {
                                runCatching { userVulkanRegistry.reset() }
                                    .onSuccess {
                                        // Live-snapshot guard (crash-guard revert
                                        // pattern): only fall back to Auto when the
                                        // selection is still a user id by the time
                                        // the reset finished.
                                        settings.update { current ->
                                            if (UserVulkanDriver.isUserId(
                                                    current.armVulkanDriverId,
                                                )
                                            ) {
                                                current.copy(
                                                    armVulkanDriverId =
                                                        VulkanDriverCatalog.AUTO_ID,
                                                )
                                            } else {
                                                current
                                            }
                                        }
                                        userDriversUnreadable = false
                                        userVulkanStatus =
                                            UserVulkanDriverPresentation
                                                .resetNotice(wasUserSelection)
                                    }
                                    .onFailure {
                                        userVulkanStatus =
                                            "Reset failed: " +
                                                "${it.message ?: it.javaClass.simpleName}"
                                    }
                                userDriverRefresh++
                            }
                        },
                        modifier = Modifier.testTag("reset-user-vulkan-drivers"),
                    ) { Text("Reset imported drivers") }
                }
                userVulkanStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                pendingDriverDelete?.let { pendingId ->
                    val driver = userDrivers.firstOrNull { it.id == pendingId }
                    AlertDialog(
                        onDismissRequest = { pendingDriverDelete = null },
                        title = { Text("Delete imported driver?") },
                        text = {
                            Text(
                                if (driver != null) {
                                    "Remove \"${driver.label}\" from app storage. The packaged " +
                                        "drivers are unaffected."
                                } else {
                                    "This driver is no longer registered."
                                },
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        pendingDriverDelete = null
                                        if (driver != null) {
                                            val wasSelected =
                                                snap.selectedVulkanDriverId() == driver.id
                                            runCatching { userVulkanRegistry.remove(driver.id) }
                                                .onSuccess {
                                                    // The guard is re-evaluated on the live
                                                    // snapshot inside the transform: a selection
                                                    // made while the payload deletion was still
                                                    // running must not be clobbered to Auto
                                                    // (crash-guard revert pattern).
                                                    settings.update { current ->
                                                        if (current.armVulkanDriverId == driver.id) {
                                                            current.copy(
                                                                armVulkanDriverId =
                                                                    VulkanDriverCatalog.AUTO_ID,
                                                            )
                                                        } else {
                                                            current
                                                        }
                                                    }
                                                    userVulkanStatus =
                                                        UserVulkanDriverPresentation
                                                            .deletionNotice(driver, wasSelected)
                                                }
                                                .onFailure {
                                                    userVulkanStatus =
                                                        "Delete failed: " +
                                                            "${it.message ?: it.javaClass.simpleName}"
                                                }
                                            userDriverRefresh++
                                        }
                                    }
                                },
                            ) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { pendingDriverDelete = null },
                            ) { Text("Cancel") }
                        },
                    )
                }
            }
            HorizontalDivider()
            Text("DXVK version", style = MaterialTheme.typography.titleSmall)
            Text(
                "This is independent of the Vulkan driver. DXVK 2.4.1 needs Vulkan 1.3; 1.10.3 is the compatibility option.",
                style = MaterialTheme.typography.bodySmall,
            )
            RendererPackageCatalog.compatible(ArmTranslationBackend.BOX64).forEach { pkg ->
                val availability = UserVulkanDriverResolution.availabilityForPairPreflight(
                    snap.effectiveVulkanDriverId(),
                    pkg.id,
                    ArmRendererAuto.isAdrenoGpu(),
                    systemVulkanProbe?.getOrNull(),
                )
                FilterChip(
                    selected = snap.selectedDxvkPackageId() == pkg.id,
                    enabled = availability.available,
                    onClick = {
                        scope.launch {
                            settings.update { current ->
                                current.copy(box64DxvkPackageId = pkg.id)
                            }
                        }
                    },
                    label = { Text(pkg.label) },
                    modifier = Modifier.fillMaxWidth().testTag("renderer-package-${pkg.id}"),
                )
                Text(pkg.qualification, style = MaterialTheme.typography.bodySmall)
                if (!availability.available) {
                    Text(
                        availability.reason,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            }
            if (snap.selectedVulkanDriverId() == VulkanDriverCatalog.SYSTEM_DEFAULT) {
                systemVulkanProbe?.exceptionOrNull()?.let { failure ->
                    Text(
                        "System Vulkan could not be verified: ${failure.message ?: "device probe failed"}. No driver will be substituted.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            snap.rendererSelectionNotice?.let { notice ->
                Text(notice, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("The selected driver and DXVK package get their own prefix and shader cache on the next launch.",
                style = MaterialTheme.typography.labelMedium)
            } else if (snap.selectedArmRendererId() == "legacy-gladio") {
                Text(
                    "The exact source-matched Gladio client is installed only inside its own generation. " +
                        "WoW launches with -opengl and GLX enabled; Vulkan and DXVK are not loaded.",
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                Text(
                    "The exact Mesa 23.1.9 virpipe client and source-matched Android VirGL server use their own generation and V0 socket. GLX is advertised only for Mesa's Fake-GLX negotiation; rendering does not use Gladio. Vulkan and DXVK are not loaded.",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        if (category == SettingsCategory.GRAPHICS) SettingCard("Display") {
            Text("Resolution", style = MaterialTheme.typography.titleSmall)
            Text(
                "Lower resolution improves performance; the final image is scaled to fit the screen.",
                style = MaterialTheme.typography.bodySmall,
            )
            availableDisplays.forEach { profile ->
                FilterChip(
                    selected = snap.displayProfileId == profile.id,
                    onClick = {
                        scope.launch {
                            settings.update { current ->
                                if (profile == ClientDisplayProfile.CLASSIC_43) {
                                    // 4:3 couples to the widescreen tweaks:
                                    // selecting it disables them in the same
                                    // write. Switching back to a widescreen
                                    // profile leaves tweaks off (re-enable
                                    // deliberately in Client tweaks).
                                    current.copy(
                                        displayProfileId = profile.id,
                                        tweaks = current.tweaks.copy(fovEnabled = false),
                                    )
                                } else {
                                    current.copy(displayProfileId = profile.id)
                                }
                            }
                        }
                    },
                    label = {
                        val suffix = when (profile) {
                            ClientDisplayProfile.BALANCED -> "Performance"
                            ClientDisplayProfile.QUALITY -> "Sharp"
                            ClientDisplayProfile.CLASSIC_43 -> "Classic 4:3"
                        }
                        Text(
                            profile.resolveFor(physicalDisplay.first, physicalDisplay.second)
                                .resolution.replace("x", " × ") + " · $suffix",
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("display-profile-${profile.id}"),
                )
            }
            if (snap.displayProfileId == ClientDisplayProfile.CLASSIC_43.id) {
                Text(
                    "Classic 4:3 turns the widescreen FoV tweak off; the pillarboxed " +
                        "image keeps the vanilla UI's intended framing. Switching aspect " +
                        "resets customized control layouts to their defaults.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            snap.displaySelectionNotice?.let { notice ->
                Text(
                    notice,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()
            Text("Frame-rate limit", style = MaterialTheme.typography.titleSmall)
            Text(
                if (snap.selectedArmRendererId() == "dxvk" ||
                    snap.selectedArmRendererId() == ArmClientRendererCatalog.AUTO_ID) {
                    "Sets WoW's maxFPS and DXVK's frame limiter."
                } else {
                    "Sets WoW's maxFPS. The OpenGL route does not use DXVK's limiter."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ClientFrameCap.entries.forEach { cap ->
                    FilterChip(
                        selected = snap.clientFrameCap == cap.fps,
                        onClick = {
                            scope.launch {
                                settings.update { it.copy(clientFrameCap = cap.fps) }
                            }
                        },
                        label = { Text("${cap.fps} FPS") },
                        modifier = Modifier.testTag("frame-cap-${cap.fps}"),
                    )
                }
            }
            InterfaceScaleControls(
                snap = snap,
                onCommit = { scale ->
                    scope.launch {
                        settings.update { it.copy(clientUiScale = scale) }
                    }
                },
            )
            Text("Applies on the next game launch.", style = MaterialTheme.typography.labelMedium)
        }

        if (category == SettingsCategory.GAME) SettingCard("In-Game Settings") {
            Text(
                "Change the game's own graphics, sound, interface, and key-binding " +
                    "options here; they apply before the client starts and stay in sync " +
                    "with changes made in game.",
                style = MaterialTheme.typography.bodySmall,
            )
            onInGameSettings?.let { action ->
                OutlinedButton(
                    onClick = action,
                    modifier = Modifier.fillMaxWidth().testTag("settings-open-ingame"),
                ) { Text("Open In-Game Settings →") }
            }
        }

        if (category == SettingsCategory.GAME) SettingCard("Sound") {
            val audioSupported = remember {
                ClientAudioPolicy.isSupported(
                    ClientRuntimeSelector.selectForAbis(Build.SUPPORTED_ABIS.asList()).provider,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = audioSupported && snap.audioMode == Settings.AudioMode.ON,
                    enabled = audioSupported,
                    onCheckedChange = { on ->
                        scope.launch {
                            settings.update {
                                it.copy(audioMode = if (on) Settings.AudioMode.ON else Settings.AudioMode.OFF)
                            }
                        }
                    },
                    modifier = Modifier.testTag("audio-mode"),
                )
                Text("  Enable game audio", style = MaterialTheme.typography.bodyMedium)
            }
            Text(if (audioSupported) {
                "On by default for ARM64 devices. Changes take effect on the next client launch through the provider-matched Android ALSA backend."
            } else {
                "Audio is unavailable on this device. Your preference is kept for supported devices."
            },
                style = MaterialTheme.typography.bodySmall)
        }

        if (category == SettingsCategory.GAME) SettingCard("Auto-login") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = snap.autoLoginOnLaunch,
                    onCheckedChange = { enabled ->
                        scope.launch { settings.update { it.copy(autoLoginOnLaunch = enabled) } }
                    },
                    modifier = Modifier.testTag("auto-login-on-launch"),
                )
                Text("  Log in automatically when the client opens",
                    style = MaterialTheme.typography.bodyMedium)
            }
            Text("Uses only the user-chosen account stored on this device. Without one, the client stays at the login screen.",
                style = MaterialTheme.typography.bodySmall)
            var storedName by remember {
                mutableStateOf(UserAccountStore(context).loadOrQuarantine()?.username)
            }
            if (storedName != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("auto-login-stored"),
                ) {
                    Text("Stored account: $storedName",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        scope.launch {
                            val cleared = withContext(Dispatchers.IO) {
                                runCatching { UserAccountStore(context).clear() }
                            }
                            if (cleared.isSuccess) storedName = null
                        }
                    }) { Text("Clear") }
                }
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = snap.autoLoginAdvanced,
                    onCheckedChange = { enabled ->
                        scope.launch { settings.update { it.copy(autoLoginAdvanced = enabled) } }
                    },
                    modifier = Modifier.testTag("auto-login-advanced"),
                )
                Text("  Advanced timing", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "These timings are recovery knobs for unusually slow login screens; defaults suit normal play.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (snap.autoLoginAdvanced) {
                AutoLoginTimingControls(
                    timings = snap.autoLoginTimings,
                    onTimings = { transform ->
                        scope.launch {
                            settings.update { it.copy(autoLoginTimings = transform(it.autoLoginTimings)) }
                        }
                    },
                    onReset = {
                        scope.launch { settings.update { it.copy(autoLoginTimings = Settings.AutoLoginTimings()) } }
                    },
                )
            }
        }

        // The tweaks card is version-aware — vanilla_tweaks is a
        // vanilla-only feature (the manifest's wine.vanilla_tweaks); on
        // TBC/WotLK the "not available" copy renders instead of the
        // toggles (ClientRuntimeContract.flavorTweaksCopy).
        // With no game version picked yet, the first-pick copy renders
        // instead of crashing composition (the operation gates keep the
        // loud refusal).
        val activeSettingsFlavorId = remember {
            com.pocketrealm.client.ActiveFlavor.displayedId
        }
        val tweaksUnavailable = when {
            activeSettingsFlavorId == null ->
                "Pick a game version on Home first — tweaks follow it."
            else -> com.pocketrealm.client.ClientRuntimeContract.flavorTweaksCopy(
                com.pocketrealm.client.ClientFlavors.ALL.first {
                    it.flavorId == activeSettingsFlavorId
                },
            )
        }
        if (category == SettingsCategory.GAME) SettingCard("Client tweaks") {
            Text(
                tweaksUnavailable
                    ?: "Optional quality-of-life patches applied on the next launch. Any genuine 1.12.1 build 5875 client can run; if its exact file layout does not support patches, that launch safely uses the original Vanilla client instead.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (tweaksUnavailable != null) {
                Text(
                    "Switch back to Vanilla on Home to use the tweaks.",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("tweaks-unavailable"),
                )
            }
            val managedExecutableSha by produceState<String?>(null, context, tweaksUnavailable) {
                value = if (tweaksUnavailable == null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ManagedClientStore(context).load(ClientRuntimeContract.WOW_5875_ID).executableSha256
                        }.getOrNull()
                    }
                } else null
            }
            val executableUnsupported = managedExecutableSha?.let {
                !it.equals(ClientTweaksConfig.AUTHORIZED_CLIENT_SHA256, ignoreCase = true)
            } == true
            if (tweaksUnavailable == null && snap.tweaks.hasAnyPatch() && executableUnsupported) {
                Text(
                    "Client tweaks unavailable: this client executable is not the qualified patch image, " +
                        "so launches will run pristine Vanilla (nearby loot, FoV, and quickloot stay off).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("tweaks-unavailable-warning"),
                )
            }
            if (tweaksUnavailable == null) Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = { scope.launch { settings.update { it.copy(tweaks = ClientTweaksConfig()) } } },
                    modifier = Modifier.weight(1f).testTag("tweaks-all-off"),
                ) { Text("Vanilla (all off)") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            settings.update { it.copy(tweaks = ClientTweaksConfig.commonPreset()) }
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("tweaks-common"),
                ) { Text("Common tweaks") }
            }
            if (tweaksUnavailable == null) ClientTweakControls(
                tweaks = snap.tweaks,
                onTweaks = { transform ->
                    scope.launch { settings.update { it.copy(tweaks = transform(it.tweaks)) } }
                },
            )
        }

        if (category == SettingsCategory.GAME) SettingCard("Nearby use / open") {
            Text(
                "L1 chooses the nearest eligible corpse, chest, or ordinary usable loot object and opens it as one realm action. " +
                    "Select+L1 remains the precise pointer-based fallback.",
                style = MaterialTheme.typography.bodySmall,
            )
            LabeledSlider(
                label = "Repeated-press guard",
                valueText = { "${it.roundToInt()} ms" },
                value = snap.nearbyInteractTriggerGuardMs.toFloat(),
                range = NearbyInteractPolicy.MIN_TRIGGER_GUARD_MS.toFloat()..
                    NearbyInteractPolicy.MAX_TRIGGER_GUARD_MS.toFloat(),
                steps = (NearbyInteractPolicy.MAX_TRIGGER_GUARD_MS -
                    NearbyInteractPolicy.MIN_TRIGGER_GUARD_MS) /
                    NearbyInteractPolicy.TRIGGER_GUARD_STEP_MS - 1,
                tag = "nearby-interact-trigger-guard",
            ) { raw ->
                val stepped = (raw / NearbyInteractPolicy.TRIGGER_GUARD_STEP_MS)
                    .roundToInt() * NearbyInteractPolicy.TRIGGER_GUARD_STEP_MS
                scope.launch {
                    settings.update {
                        it.copy(nearbyInteractTriggerGuardMs =
                            NearbyInteractPolicy.normalizeTriggerGuardMs(stepped))
                    }
                }
            }
            Text(
                "Filters accidental duplicate presses; it does not split selection and opening into two actions. Applies when the realm next starts.",
                style = MaterialTheme.typography.labelMedium,
            )
        }

        if (category == SettingsCategory.GAME) SettingCard("Input safe mode") {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Switch(
                    checked = snap.inputSafeMode,
                    onCheckedChange = { enabled ->
                        scope.launch { settings.update { it.copy(inputSafeMode = enabled) } }
                    },
                    modifier = Modifier.testTag("input-safe-mode"),
                )
                Text("  Disable project addons and the touch overlay",
                    style = MaterialTheme.typography.bodyMedium)
            }
            Text("Realm and character data are not changed.",
                style = MaterialTheme.typography.bodySmall)
        }

        if (category == SettingsCategory.WORLD) SettingCard("Bots") {
            BotsScreen()
        }

        if (category == SettingsCategory.WORLD) SettingCard("XP rates") {
            Text(
                "Sets the leveling pace the realm awards for kills, quests, exploration, and pets. " +
                    "1x keeps authentic vanilla pacing; higher presets shorten the grind without " +
                    "touching character data. Applies when the realm next starts.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XpRatePolicy.PRESET_RATES.forEach { preset ->
                    val current = listOf(
                        snap.xpRateKill, snap.xpRateQuest, snap.xpRateExplore, snap.xpRatePetKill,
                    )
                    val selectedTenths = XpRatePolicy.normalizeTenths(preset)
                    FilterChip(
                        selected = current.all { XpRatePolicy.normalizeTenths(it) == selectedTenths },
                        onClick = {
                            scope.launch {
                                settings.update {
                                    it.copy(
                                        xpRateKill = preset,
                                        xpRateQuest = preset,
                                        xpRateExplore = preset,
                                        xpRatePetKill = preset,
                                    )
                                }
                            }
                        },
                        label = { Text("${XpRatePolicy.format(preset)}x") },
                        modifier = Modifier.testTag("xp-rate-preset-${XpRatePolicy.format(preset)}"),
                    )
                }
            }
            var xpAdvanced by rememberSaveable { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = xpAdvanced,
                    onCheckedChange = { xpAdvanced = it },
                    modifier = Modifier.testTag("xp-rates-advanced"),
                )
                Text("  Per-rate tuning", style = MaterialTheme.typography.titleSmall)
            }
            if (xpAdvanced) {
                xpRateSlider("Kill XP rate", snap.xpRateKill, "xp-rate-kill") { rate ->
                    scope.launch { settings.update { it.copy(xpRateKill = rate) } }
                }
                xpRateSlider("Quest XP rate", snap.xpRateQuest, "xp-rate-quest") { rate ->
                    scope.launch { settings.update { it.copy(xpRateQuest = rate) } }
                }
                xpRateSlider("Explore XP rate", snap.xpRateExplore, "xp-rate-explore") { rate ->
                    scope.launch { settings.update { it.copy(xpRateExplore = rate) } }
                }
                xpRateSlider("Pet kill XP rate", snap.xpRatePetKill, "xp-rate-pet-kill") { rate ->
                    scope.launch { settings.update { it.copy(xpRatePetKill = rate) } }
                }
            }
        }

        // The per-game-version content-patch knob, wired to the shared
        // TimeframeStages core — the active version's ordered stages,
        // default = final patch, applies at the next realm start.
        if (category == SettingsCategory.WORLD) SettingCard("World timeframe") {
            // Resolve through the ActiveFlavor machine — the documented
            // fail-loud resolver; never a silent VANILLA fallback. With no
            // pick yet the resolver throws — this render path shows the
            // first-pick hint instead of crashing composition (operation
            // gates keep the refusal).
            val flavorId = remember {
                com.pocketrealm.client.ActiveFlavor.displayedId
            }
            if (flavorId == null) {
                Text(
                    "Pick a game version on Home first — the timeline follows it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@SettingCard
            }
            val stages = remember(flavorId) {
                com.pocketrealm.client.TimeframeStages.stagesFor(flavorId)
            }
            val selectedId = com.pocketrealm.client.TimeframeStages
                .resolveStored(snap.timeframeStage)
                .ifEmpty { com.pocketrealm.client.TimeframeStages.defaultStageId(flavorId) }
            val selected = requireNotNull(
                com.pocketrealm.client.TimeframeStages.byId(flavorId, selectedId),
            ) { "resolved stage $selectedId must be registered for $flavorId" }
            stages.chunked(TIMEFRAME_CHIP_ROW).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { stage ->
                        FilterChip(
                            selected = stage.id == selectedId,
                            onClick = {
                                // The final patch persists as "" (the
                                // default): existing-world behavior is the
                                // stored state too, not a special case.
                                scope.launch {
                                    settings.update {
                                        it.copy(
                                            timeframeStage =
                                                if (stage.isFinal) "" else stage.id,
                                        )
                                    }
                                }
                            },
                            label = { Text(stage.id.removePrefix("$flavorId-")) },
                            modifier = Modifier.testTag("timeframe-stage-${stage.id}"),
                        )
                    }
                }
            }
            Text(
                "The game client stays at its fixed version; this gates world content only. " +
                    "Applies when the realm next starts.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!selected.isFinal) {
                // The present-tense "later content closes" claim is only
                // true once the stage's availability data is measured.
                // Until the measured per-stage sets land, no build gates
                // anything — asserting closure would promise a restriction
                // the shipped data does not enforce. A stage with no staged
                // overlay for its game version refuses the world start
                // outright, so its copy must say that instead.
                val overlayStaged =
                    com.pocketrealm.client.GeneratedTimeframeOverlayCatalog
                        .overlaySql(selected.id) != null
                val measured = com.pocketrealm.client.TimeframeStages
                    .eventGating(selected).measured
                Text(
                    if (!overlayStaged) {
                        com.pocketrealm.client.TimeframeStages
                            .unstagedOverlayNotice(selected)
                    } else if (measured) {
                        "${selected.title}: later content closes; items, levels, and spells your " +
                            "characters already have stay."
                    } else {
                        "${selected.title}: this stage's content availability is not measured " +
                            "yet, so nothing closes yet — the world still runs the current " +
                            "release's content and the choice is only recorded for the realm. " +
                            "The measured per-stage data ships in a later update."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (category == SettingsCategory.DATA) SettingCard("Realm data") {
            RealmDataControls()
        }

        if (category == SettingsCategory.DATA) SettingCard("Client storage") {
            var contentVolume by remember { mutableStateOf(ContentVolumeSetting.read(context)) }
            val removablePresent = remember { ContentVolumeSetting.removableDir(context) != null }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().testTag("settings-content-volume"),
            ) {
                Checkbox(
                    checked = contentVolume == ContentVolume.REMOVABLE,
                    onCheckedChange = { onSd ->
                        val next = if (onSd) ContentVolume.REMOVABLE else ContentVolume.INTERNAL
                        ContentVolumeSetting.write(context, next)
                        StorageRoots.reset()
                        contentVolume = next
                    },
                    enabled = removablePresent || contentVolume == ContentVolume.REMOVABLE,
                )
                Text(
                    if (removablePresent) {
                        "Imported clients and prepared data live on the SD card"
                    } else {
                        "Imported clients and prepared data live on the SD card (no SD card detected)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                if (contentVolume == ContentVolume.REMOVABLE) {
                    "Client archives, extracted clients, and prepared server data are stored on the " +
                        "SD card. The realm database and runtime stay on internal storage. Clients " +
                        "imported while this was off stay on internal storage and are not visible " +
                        "until you switch back and re-import; a running realm must be stopped first."
                } else {
                    "Everything is stored on internal storage (default). Turning this on applies to " +
                        "new imports only."
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }

        if (category == SettingsCategory.APP) SettingCard("App updates") {
            var updateStatus by remember { mutableStateOf<String?>(null) }
            var availableUpdate by remember {
                mutableStateOf<AppUpdateCoordinator.UpdateManifest?>(null)
            }
            Text(
                "Installed: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}). " +
                    "Updates install in place over this app — game files, realm data, and " +
                    "settings are preserved.",
                style = MaterialTheme.typography.bodySmall,
            )
            updateStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    updateStatus = "Checking..."
                    availableUpdate = null
                    scope.launch(Dispatchers.IO) {
                        val message = when (val result = AppUpdateCoordinator.check()) {
                            is AppUpdateCoordinator.CheckResult.Available -> {
                                availableUpdate = result.manifest
                                "Update ${result.manifest.versionName} available " +
                                    "(${result.manifest.size / MIB} MB). " +
                                    result.manifest.notes
                            }
                            is AppUpdateCoordinator.CheckResult.UpToDate -> {
                                // A superseded staged download is dead weight.
                                AppUpdateCoordinator.clearDownloadedApk(context)
                                "You are up to date."
                            }
                            is AppUpdateCoordinator.CheckResult.Unavailable -> result.reason
                        }
                        updateStatus = message
                    }
                }) { Text("Check for updates") }
                OutlinedButton(onClick = {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(AppUpdateCoordinator.RELEASES_PAGE_URL),
                        ),
                    )
                }) { Text("Release page") }
            }
            val manifest = availableUpdate
            if (manifest != null) {
                var installBusy by remember { mutableStateOf(false) }
                var meteredWarningShown by remember { mutableStateOf(false) }
                var waitingForInstallPermission by remember { mutableStateOf(false) }
                val startInstallFn: () -> Unit = {
                    // Re-entry guard: the button's enabled state only takes
                    // effect after recomposition, and the ON_RESUME
                    // auto-continue below adds a second entry point.
                    if (!installBusy) {
                        if (!AppUpdateCoordinator.canRequestPackageInstalls(context)) {
                            waitingForInstallPermission = true
                            updateStatus =
                                "Allow installing from this app in the system settings that " +
                                    "just opened — the install continues when you return."
                            context.startActivity(
                                AppUpdateCoordinator.manageUnknownSourcesIntent(),
                            )
                        } else {
                            val connectivity = context.getSystemService(
                                android.net.ConnectivityManager::class.java)
                            val metered =
                                connectivity != null && connectivity.isActiveNetworkMetered
                            if (metered && !meteredWarningShown) {
                                meteredWarningShown = true
                                updateStatus =
                                    "This looks like a metered connection — the update is " +
                                        "${manifest.size / MIB} MB. Tap Install again to " +
                                        "proceed on mobile data."
                            } else {
                                meteredWarningShown = false
                                installBusy = true
                                val reuse = AppUpdateCoordinator
                                    .downloadedApkIfVerified(context, manifest) != null
                                updateStatus = if (reuse) {
                                    "Using the previously downloaded update..."
                                } else {
                                    "Downloading ${manifest.versionName}..."
                                }
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        val apk = AppUpdateCoordinator.downloadApk(
                                            context, manifest,
                                        ) { progress ->
                                            // The final ==size callback is skipped so a
                                            // reused download keeps its status line.
                                            if (progress < manifest.size) {
                                                updateStatus =
                                                    "Downloading... ${progress / MIB} / " +
                                                        "${manifest.size / MIB} MB"
                                            }
                                        }
                                        AppUpdateCoordinator.install(context, apk)
                                    }.onSuccess {
                                        updateStatus =
                                            "Install offered to the system — confirm in the " +
                                                "installer dialog. The new version starts on " +
                                                "the next launch."
                                    }.onFailure { failure ->
                                        updateStatus =
                                            "Update failed: " +
                                                "${failure.message ?: failure.javaClass.simpleName}"
                                    }
                                    installBusy = false
                                }
                            }
                        }
                    }
                }
                val startInstall by rememberUpdatedState(startInstallFn)
                // Auto-continue after the unknown-sources grant. The wait
                // flag is cleared as soon as the permission is confirmed —
                // before invoking the install — so the metered-connection
                // early return above can never leave it armed to
                // auto-install on a later resume without consent.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME &&
                            waitingForInstallPermission
                        ) {
                            waitingForInstallPermission = false
                            if (availableUpdate != null &&
                                AppUpdateCoordinator.canRequestPackageInstalls(context)
                            ) {
                                startInstall()
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                Button(
                    enabled = !installBusy,
                    onClick = { startInstall() },
                ) { Text("Install update") }
            }
        }

        if (category == SettingsCategory.APP) SettingCard("Setup") {
            onClientSetup?.let { action ->
                OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                    Text("Game files and import")
                }
            }
            OutlinedButton(
                onClick = { TutorialReplayRequests.bump() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Show first-run setup guide")
            }
            onCapability?.let { action ->
                OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                    Text("Device capability report")
                }
            }
            onDiagnostics?.let { action ->
                OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                    Text("Diagnostics and logs")
                }
            }
            Text(
                "Setup covers the game-files import for your game version's WoW " +
                    "client and the server's world data, a report of what this " +
                    "device's hardware can run, diagnostics with logs for " +
                    "investigating problems, and the first-run setup guide, " +
                    "which you can replay anytime. Start here on a new install. " +
                    "Character backups and restores live in Storage & realm data.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (category == SettingsCategory.APP) SettingCard("World debug logs") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = snap.worldDebugLogs,
                    onCheckedChange = { enabled ->
                        scope.launch { settings.update { it.copy(worldDebugLogs = enabled) } }
                    },
                    modifier = Modifier.testTag("world-debug-logs"),
                )
                Text("  Verbose world-server log", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Raises the world log level to verbose when the realm next starts and can " +
                    "grow world.log very large. Keep it off unless investigating a problem.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

    }
}

@Composable
private fun AutoLoginTimingControls(
    timings: Settings.AutoLoginTimings,
    onTimings: ((Settings.AutoLoginTimings) -> Settings.AutoLoginTimings) -> Unit,
    onReset: () -> Unit,
) {
    LabeledSlider("Poll interval", { "${it.toLong()} ms" },
        timings.pollIntervalMs.toFloat(), 100f..1000f, 17, "al-poll") { v ->
        onTimings { it.copy(pollIntervalMs = v.toLong().coerceIn(100, 1000)) }
    }
    LabeledSlider("Stable polls", { "${it.toInt()}" },
        timings.requiredStablePolls.toFloat(), 1f..12f, 10, "al-stable") { v ->
        onTimings { it.copy(requiredStablePolls = v.toInt().coerceIn(1, 12)) }
    }
    LabeledSlider("Login UI settle", { "${it.toLong()} ms" },
        timings.loginUiSettleMs.toFloat(), 1000f..30000f, 57, "al-settle") { v ->
        onTimings { it.copy(loginUiSettleMs = v.toLong().coerceIn(1000, 30000)) }
    }
    LabeledSlider("Session timeout", { "${it.toLong() / 1000} s" },
        timings.sessionTimeoutMs.toFloat(), 60000f..900000f, 55, "al-session") { v ->
        onTimings { it.copy(sessionTimeoutMs = v.toLong().coerceIn(60000, 900000)) }
    }
    LabeledSlider("Drain poll", { "${it.toLong()} ms" },
        timings.drainPollMs.toFloat(), 25f..200f, 34, "al-drain") { v ->
        onTimings { it.copy(drainPollMs = v.toLong().coerceIn(25, 200)) }
    }
    LabeledSlider("Input drain timeout", { "${it.toLong()} ms" },
        timings.inputDrainTimeoutMs.toFloat(), 1000f..30000f, 57, "al-input-drain") { v ->
        onTimings { it.copy(inputDrainTimeoutMs = v.toLong().coerceIn(1000, 30000)) }
    }
    LabeledSlider("IME key dwell", { "${it.toLong()} ms" },
        timings.imeKeyDwellMs.toFloat(), 20f..200f, 35, "al-ime-dwell") { v ->
        onTimings { it.copy(imeKeyDwellMs = v.toLong().coerceIn(20, 200)) }
    }
    LabeledSlider("IME key gap", { "${it.toLong()} ms" },
        timings.imeKeyGapMs.toFloat(), 0f..100f, 19, "al-ime-gap") { v ->
        onTimings { it.copy(imeKeyGapMs = v.toLong().coerceIn(0, 100)) }
    }
    LabeledSlider("Field settle", { "${it.toLong()} ms" },
        timings.fieldSettleMs.toFloat(), 50f..2000f, 38, "al-field") { v ->
        onTimings { it.copy(fieldSettleMs = v.toLong().coerceIn(50, 2000)) }
    }
    LabeledSlider("Pointer dwell", { "${it.toLong()} ms" },
        timings.pointerDwellMs.toFloat(), 20f..500f, 47, "al-pointer") { v ->
        onTimings { it.copy(pointerDwellMs = v.toLong().coerceIn(20, 500)) }
    }
    OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth().testTag("al-reset")) {
        Text("Reset to defaults")
    }
}

@Composable
private fun ClientTweakControls(
    tweaks: ClientTweaksConfig,
    onTweaks: ((ClientTweaksConfig) -> ClientTweaksConfig) -> Unit,
) {
    TweakSwitch("Widescreen FoV fix", tweaks.fovEnabled, "tweak-fov") { on ->
        onTweaks { it.copy(fovEnabled = on) }
    }
    TweakSwitch("Farclip cap raise", tweaks.farclipEnabled, "tweak-farclip") { on ->
        onTweaks { it.copy(farclipEnabled = on) }
    }
    TweakSwitch("Frill distance raise", tweaks.frilldistanceEnabled, "tweak-frill") { on ->
        onTweaks { it.copy(frilldistanceEnabled = on) }
    }
    TweakSwitch("Sound in background", tweaks.soundInBackgroundEnabled, "tweak-sound-bg") { on ->
        onTweaks { it.copy(soundInBackgroundEnabled = on) }
    }
    TweakSwitch("Sound channel count (64)", tweaks.soundChannelsEnabled, "tweak-sound-channels") { on ->
        onTweaks { it.copy(soundChannelsEnabled = on) }
    }
    TweakSwitch("Auto-loot opened corpses", tweaks.quicklootEnabled, "tweak-quickloot") { on ->
        onTweaks { it.copy(quicklootEnabled = on) }
    }
    TweakSwitch("Nameplate distance (41 yd)", tweaks.nameplateEnabled, "tweak-nameplate") { on ->
        onTweaks { it.copy(nameplateEnabled = on) }
    }
    TweakSwitch("Large address aware", tweaks.largeAddressAwareEnabled, "tweak-laa") { on ->
        onTweaks { it.copy(largeAddressAwareEnabled = on) }
    }
    TweakSwitch("Camera skip glitch fix", tweaks.cameraSkipFixEnabled, "tweak-camera-skip") { on ->
        onTweaks { it.copy(cameraSkipFixEnabled = on) }
    }
    TweakSwitch("Max camera distance raise", tweaks.maxCameraDistanceEnabled, "tweak-camera-max") { on ->
        onTweaks { it.copy(maxCameraDistanceEnabled = on) }
    }
    Text("Off switches restore the upstream game default; applied on the next client launch.",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TweakSwitch(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(advancedExplanation(label), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The app-managed WoW interface-scale control. Both the shown value and the
 * slider range derive from the same per-profile clamp the launch path
 * enforces, so a persisted value that exceeds the current profile's cap
 * (profile switch, restore downgraded to a smaller panel) renders clamped
 * rather than out-of-range; the raw value survives and re-applies on a
 * bigger profile.
 */
@Composable
private fun InterfaceScaleControls(
    snap: Settings.Snapshot,
    onCommit: (Float?) -> Unit,
) {
    val virtualHeight = snap.displaySelection().virtualHeight
    val cap = minOf(2f, virtualHeight / 512f)
    val effective = snap.effectiveClientUiScale(virtualHeight)
    Text("Interface scale", style = MaterialTheme.typography.titleSmall)
    Text(
        "Grows or shrinks the whole game UI beyond the stock slider's 1.0 cap — useful on small " +
            "screens. It overrides the in-game Advanced Options slider. Values above 1.0 can push " +
            "stock frames off-screen, and a raised scale can leave custom Move-UI layouts off-screen " +
            "(recover with /ap resetui).",
        style = MaterialTheme.typography.bodySmall,
    )
    FloatSteppedSlider(
        label = "Scale",
        value = effective,
        valueText = effective?.let { "%.2f".format(java.util.Locale.US, it) } ?: "Default",
        range = 0.5f..cap,
        step = 0.05f,
        tag = "ui-scale-slider",
        onCommit = { onCommit(it) },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = snap.clientUiScale == null,
            onClick = { onCommit(null) },
            label = { Text("Default (1.0)") },
            modifier = Modifier.testTag("ui-scale-default"),
        )
    }
}

/**
 * Slider that persists only when the drag finishes; every onValueChange
 * frame stays local so a drag does not open one DataStore transaction per
 * frame (the multi-process preferences file is contended with :supervisor).
 */
@Composable
private fun LabeledSlider(
    label: String,
    valueText: (Float) -> String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    tag: String,
    onCommit: (Float) -> Unit,
) {
    var position by remember(value) { mutableStateOf(value) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(valueText(position), style = MaterialTheme.typography.labelMedium)
    }
    Text(advancedExplanation(label), style = MaterialTheme.typography.bodySmall)
    Slider(
        value = position,
        onValueChange = { position = it },
        onValueChangeFinished = { onCommit(position) },
        valueRange = range,
        steps = steps,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** The Settings landing page: one tappable row per [SettingsCategory]. */
@Composable
private fun SettingsHub(contentPadding: PaddingValues, onOpenCategory: (SettingsCategory) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsCategory.entries.forEach { category ->
            Card(
                onClick = { onOpenCategory(category) },
                modifier = Modifier.fillMaxWidth().testTag("settings-category-${category.slug}"),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            category.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            category.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        Text(
            "Pocket Realm ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** One XP-rate slider: 0.1x..10x in 0.1 steps, committed snapped to the policy band. */
@Composable
private fun xpRateSlider(
    label: String,
    rate: Double,
    tag: String,
    onCommit: (Double) -> Unit,
) {
    LabeledSlider(
        label = label,
        valueText = { "${XpRatePolicy.format(it.toDouble())}x" },
        value = XpRatePolicy.normalize(rate).toFloat(),
        range = XpRatePolicy.MIN_RATE.toFloat()..XpRatePolicy.MAX_RATE.toFloat(),
        steps = XpRatePolicy.MAX_TENTHS - XpRatePolicy.MIN_TENTHS - 1,
        tag = tag,
    ) { raw ->
        onCommit(XpRatePolicy.normalize(raw.toDouble()))
    }
}

internal val advancedSettingExplanations: Map<String, String> = mapOf(
    "Kill XP rate" to "Multiplies experience from slain creatures; 1x is the vanilla curve.",
    "Quest XP rate" to "Multiplies experience from completed quests; 1x is the vanilla curve.",
    "Explore XP rate" to "Multiplies experience from discovering new areas; 1x is the vanilla curve.",
    "Pet kill XP rate" to "Multiplies experience the hunter pet earns from kills; 1x is the vanilla curve.",
    "Repeated-press guard" to "Raise this on slower or high-latency realms if one physical press is reported more than once.",
    "Poll interval" to "Sets how often auto-login checks whether the login screen is ready.",
    "Stable polls" to "Requires this many unchanged readiness checks before auto-login sends input.",
    "Login UI settle" to "Adds a bounded wait for login controls to finish appearing before input begins.",
    "Session timeout" to "Stops the auto-login attempt if the complete login flow takes longer than this limit.",
    "Drain poll" to "Sets how often auto-login checks that previously sent input has been released.",
    "Input drain timeout" to "Stops the attempt if keys or buttons do not return to a neutral state in time.",
    "IME key dwell" to "Controls how long each generated keyboard key remains pressed.",
    "IME key gap" to "Controls the pause between generated keyboard keys.",
    "Field settle" to "Adds a pause after changing login fields so the client can process the text.",
    "Pointer dwell" to "Controls how long an automated pointer press is held before release.",
    "Widescreen FoV fix" to "Corrects field of view for widescreen displays instead of stretching the original view.",
    "Farclip cap raise" to "Allows the game to draw terrain farther away when the setting requests it.",
    "Frill distance raise" to "Allows decorative ground objects to remain visible at greater distances.",
    "Sound in background" to "Keeps game audio active when the gameplay activity temporarily loses focus.",
    "Sound channel count (64)" to "Raises simultaneous sounds from 12 to 64. This can cost more CPU, so it is opt-in.",
    "Auto-loot opened corpses" to "Takes every available slot after you right-click a corpse; hold Shift for the original manual loot window.",
    "Nameplate distance (41 yd)" to "Extends the maximum range at which unit nameplates can appear.",
    "Large address aware" to "Lets the 32-bit client use a larger address space under the translated runtime.",
    "Camera skip glitch fix" to "Applies the known camera update fix that prevents sudden skipped movement.",
    "Max camera distance raise" to "Raises the third-person camera limit from the vanilla 50 to 100.",
)

internal fun advancedExplanation(label: String): String =
    advancedSettingExplanations.getValue(label)

/** Copy the picked document into app-private cache immediately (I6: the URI is never retained), then import through the registry. */
private suspend fun importUserVulkanDriverFromUri(
    context: android.content.Context,
    uri: Uri,
): UserVulkanDriverImport = withContext(Dispatchers.IO) {
    val staged = java.io.File(
        context.cacheDir, "user-vulkan-import-${System.currentTimeMillis()}",
    )
    try {
        val opened = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        if (opened == null) {
            return@withContext UserVulkanDriverImport.Rejected(
                "The selected document could not be opened.",
            )
        }
        // Bounded staging: the size cap applies while copying, not after the
        // whole document has hit internal storage.
        val cap = UserVulkanDriverValidator.DEFAULT_MAX_IMPORT_BYTES
        // Cloud documents can stall mid-stream with no read timeout; a
        // watchdog closes the input after a progress-free minute so the
        // player gets an exact reason instead of a permanently disabled
        // import button.
        var lastProgressNanos = System.nanoTime()
        val copyFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        val stalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = Thread {
            while (!copyFinished.get()) {
                Thread.sleep(1_000)
                if (!copyFinished.get() &&
                    System.nanoTime() - lastProgressNanos > USER_IMPORT_STALL_NANOS
                ) {
                    stalled.set(true)
                    runCatching { opened.close() }
                    return@Thread
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            opened.use { input ->
                staged.outputStream().use { output ->
                    var copied = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        lastProgressNanos = System.nanoTime()
                        copied += read
                        if (copied > cap) {
                            return@withContext UserVulkanDriverImport.Rejected(
                                UserVulkanDriverValidator.sizeRejection(copied, cap),
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: java.io.IOException) {
            if (stalled.get()) {
                return@withContext UserVulkanDriverImport.Rejected(
                    "The import stalled while reading the selected document; " +
                        "pick the file again.",
                )
            }
            throw error
        } finally {
            copyFinished.set(true)
        }
        val displayName = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(
                    android.provider.OpenableColumns.DISPLAY_NAME,
                )
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()?.substringBeforeLast('.') ?: "Imported driver"
        UserVulkanDriverRegistry(UserVulkanDriverRegistry.registryRoot(context.filesDir))
            .import(displayName, staged)
    } finally {
        staged.delete()
    }
}

/** One minute without a single byte of progress marks a stalled import stream. */
private val USER_IMPORT_STALL_NANOS = 60_000_000_000L
