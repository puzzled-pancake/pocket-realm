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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.BuildConfig
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
import com.pocketrealm.client.ClientTweaksConfig
import com.pocketrealm.client.RendererPackageCatalog
import com.pocketrealm.client.SystemVulkanCapabilities
import com.pocketrealm.client.GladioCapability
import com.pocketrealm.client.VulkanDriverCatalog
import com.pocketrealm.server.NearbyInteractPolicy
import com.pocketrealm.storage.Settings
import com.pocketrealm.update.AppUpdateCoordinator
import com.pocketrealm.update.AppUpdateInstallReceiver
import com.pocketrealm.storage.StorageRoots
import com.pocketrealm.supervisor.RuntimeSupervisorClient
import com.pocketrealm.supervisor.UserAccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * General application/server settings. Bot world configuration lives in the
 * dedicated Bots destination; LAN host/join lives in the LAN destination.
 */
private const val BACKUP_AWAIT_TIMEOUT_MINUTES = 10L
private const val BACKUP_AWAIT_TIMEOUT_MS = BACKUP_AWAIT_TIMEOUT_MINUTES * 60_000L

private const val MIB = 1024L * 1024

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues = PaddingValues(),
    onBots: (() -> Unit)? = null,
    onClientSetup: (() -> Unit)? = null,
    onCapability: (() -> Unit)? = null,
    onDiagnostics: (() -> Unit)? = null,
    onInGameSettings: (() -> Unit)? = null,
) {
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

    val supervisor = remember(context) { RuntimeSupervisorClient(context) }
    val roots = remember(context) { StorageRoots.get(context) }
    var realmDataBusy by remember { mutableStateOf(false) }
    var exportIncludesAccount by remember { mutableStateOf(false) }
    var realmDataStatus by remember { mutableStateOf("Realm characters live in the stopped-realm database snapshots.") }
    var pendingImport by remember { mutableStateOf<Pair<Uri, RealmDataArchive.ArchiveInfo>?>(null) }

    suspend fun awaitBackupCompletion(): Boolean {
        // Bounded (de-vibe A5): a backup whose phase never settles used to
        // spin this coroutine forever with a busy status line.
        val deadline = System.currentTimeMillis() + BACKUP_AWAIT_TIMEOUT_MS
        while (true) {
            val status = runCatching { supervisor.backupStatus() }.getOrNull() ?: return false
            realmDataStatus = "${status.optString("kind")}: ${status.optString("phase")}"
            if (status.optString("phase") == "COMPLETE") return true
            if (status.optString("phase") == "FAILED") return false
            if (System.currentTimeMillis() >= deadline) {
                realmDataStatus = "Backup did not settle within $BACKUP_AWAIT_TIMEOUT_MINUTES minutes."
                return false
            }
            delay(500)
        }
    }

    suspend fun exportRealmCharacters(target: Uri) {
        realmDataBusy = true
        try {
            val name = "manual-export-${System.currentTimeMillis()}"
            val accepted = runCatching { supervisor.createBackup(name) }
            if (accepted.getOrNull()?.optBoolean("ok") != true) {
                realmDataStatus = accepted.fold(
                    { it.optString("error").ifBlank { "backup rejected" } },
                    { "backup failed: ${it.javaClass.simpleName}" },
                )
                return
            }
            if (!awaitBackupCompletion()) return
            val snapshotId = runCatching { supervisor.listBackups() }.getOrNull()
                ?.optJSONArray("backups")?.optJSONObject(0)?.optString("snapshotId")
            if (snapshotId.isNullOrBlank()) {
                realmDataStatus = "snapshot not found after backup"
                return
            }
            val snapshotDir = java.io.File(roots.databaseSnapshots, snapshotId)
            // Credentials are excluded by default (de-vibe A2): an exported ZIP
            // travels wherever the user sends it; re-entering a password on
            // import is cheap, un-leaking a credentials file is not.
            val accountFile = if (exportIncludesAccount) {
                java.io.File(context.noBackupFilesDir, "user-account/account.json").takeIf { it.isFile }
            } else {
                null
            }
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(target)
                    ?: throw IllegalStateException("could not open export target")
                output.use { stream ->
                    RealmDataArchive.writeArchive(
                        stream, snapshotDir, accountFile,
                        RealmDataArchive.meta(
                            snapshotId, System.currentTimeMillis(),
                            BuildConfig.VERSION_NAME, Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                        ),
                    )
                }
            }
            realmDataStatus = "Characters exported (snapshot $snapshotId)."
        } catch (failure: Throwable) {
            realmDataStatus = "Export failed: ${failure.message ?: failure.javaClass.simpleName}"
        } finally {
            realmDataBusy = false
        }
    }

    suspend fun inspectRealmArchive(source: Uri) {
        val info = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(source)?.use { input -> RealmDataArchive.inspect(input) }
            }.getOrNull()
        }
        if (info == null) {
            realmDataStatus = "Could not read the selected archive."
            return
        }
        if (info.snapshotId.isBlank()) {
            realmDataStatus = "Archive has no snapshot id."
            return
        }
        pendingImport = source to info
    }

    suspend fun restoreRealmCharacters(source: Uri, info: RealmDataArchive.ArchiveInfo) {
        realmDataBusy = true
        try {
            val target = java.io.File(roots.databaseSnapshots, info.snapshotId)
            if (target.exists()) {
                realmDataStatus = "Snapshot ${info.snapshotId} already exists on this device."
                return
            }
            val accountBytes = withContext(Dispatchers.IO) {
                check(target.mkdirs()) { "could not create snapshot directory" }
                val input = context.contentResolver.openInputStream(source)
                    ?: throw IllegalStateException("could not open archive")
                input.use { stream -> RealmDataArchive.extractSnapshot(stream, target).second }
            }
            val accepted = runCatching { supervisor.restoreBackup(info.snapshotId) }
            if (accepted.getOrNull()?.optBoolean("ok") != true) {
                realmDataStatus = accepted.fold(
                    { it.optString("error").ifBlank { "restore rejected" } },
                    { "restore failed: ${it.javaClass.simpleName}" },
                )
                return
            }
            if (!awaitBackupCompletion()) return
            accountBytes?.let { bytes ->
                withContext(Dispatchers.IO) {
                    val accountFile = java.io.File(context.noBackupFilesDir, "user-account/account.json")
                    accountFile.parentFile?.mkdirs()
                    accountFile.writeBytes(bytes)
                    android.system.Os.chmod(accountFile.absolutePath,
                        android.system.OsConstants.S_IRUSR or android.system.OsConstants.S_IWUSR)
                }
            }
            realmDataStatus = "Realm data imported."
        } catch (failure: Throwable) {
            realmDataStatus = "Import failed: ${failure.message ?: failure.javaClass.simpleName}"
        } finally {
            realmDataBusy = false
        }
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch { exportRealmCharacters(uri) }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { inspectRealmArchive(uri) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingCard("ARM client runtime") {
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
                "Legacy OpenGL and Mesa VirGL are very experimental, unqualified lanes kept " +
                    "only for device testing. They are never chosen automatically; selecting " +
                    "one is an exact manual choice with no fallback.",
                style = MaterialTheme.typography.bodySmall,
            )
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
            if (snap.selectedArmRendererId() == "dxvk" ||
                snap.selectedArmRendererId() == ArmClientRendererCatalog.AUTO_ID) {
            HorizontalDivider()
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
            Text("DXVK version", style = MaterialTheme.typography.titleSmall)
            Text(
                "This is independent of the Vulkan driver. DXVK 2.4.1 needs Vulkan 1.3; 1.10.3 is the compatibility option.",
                style = MaterialTheme.typography.bodySmall,
            )
            RendererPackageCatalog.compatible(ArmTranslationBackend.BOX64).forEach { pkg ->
                val availability = VulkanDriverCatalog.availabilityForPair(
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
            Text("The exact driver and DXVK package receive an isolated prefix and shader cache on the next launch.",
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

        SettingCard("Display") {
            Text("Resolution", style = MaterialTheme.typography.titleSmall)
            Text(
                "This changes WoW's real 3D/X desktop workload. Lower resolution improves performance; the final image is scaled to the panel.",
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
                    "Sets both WoW's maxFPS and DXVK's D3D9 limiter. It is a maximum, not a performance promise."
                } else {
                    "Sets WoW's maxFPS. The experimental OpenGL route does not load DXVK's limiter."
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
            Text("Applies on the next game launch.", style = MaterialTheme.typography.labelMedium)
        }

        SettingCard("Bots") {
            Text(
                "Bot population, presets, behaviour, scheduling and custom realms are configured in the dedicated Bots destination.",
                style = MaterialTheme.typography.bodySmall,
            )
            onBots?.let { action ->
                OutlinedButton(
                    onClick = action,
                    modifier = Modifier.fillMaxWidth().testTag("settings-open-bots"),
                ) { Text("Configure in Bots →") }
            }
        }

        HorizontalDivider()
        SettingCard("Input safe mode") {
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

        SettingCard("Nearby use / open") {
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

        HorizontalDivider()
        SettingCard("Auto-login") {
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

        HorizontalDivider()
        SettingCard("Client tweaks") {
            Text("Optional quality-of-life patches applied on the next launch. Any genuine 1.12.1 build 5875 client can run; if its exact byte layout is not qualified for patches, that launch safely uses pristine Vanilla instead.",
                style = MaterialTheme.typography.bodySmall)
            Row(
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
            ClientTweakControls(
                tweaks = snap.tweaks,
                onTweaks = { transform ->
                    scope.launch { settings.update { it.copy(tweaks = transform(it.tweaks)) } }
                },
            )
        }

        HorizontalDivider()
        SettingCard("In-Game Settings") {
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

        HorizontalDivider()
        SettingCard("Sound") {
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

        HorizontalDivider()
        SettingCard("Setup") {
            onClientSetup?.let { action ->
                OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                    Text("Game files and import")
                }
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
                "Setup covers three tools: importing the WoW 1.12.1 game files and " +
                    "generating the server's world data, a report of what this " +
                    "device's hardware can run, and diagnostics with logs for " +
                    "investigating problems. Start here on a new install. Character " +
                    "backups and restores live in the Realm data section below.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()
        SettingCard("App updates") {
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
                            is AppUpdateCoordinator.CheckResult.UpToDate -> "You are up to date."
                            is AppUpdateCoordinator.CheckResult.Unavailable -> result.reason
                        }
                        updateStatus = message
                    }
                }) { Text("Check for updates") }
                OutlinedButton(onClick = {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(AppUpdateInstallReceiver.RELEASES_PAGE_URL),
                        ),
                    )
                }) { Text("Release page") }
            }
            val manifest = availableUpdate
            if (manifest != null) {
                var installBusy by remember { mutableStateOf(false) }
                var meteredWarningShown by remember { mutableStateOf(false) }
                Button(
                    enabled = !installBusy,
                    onClick = {
                        if (!AppUpdateCoordinator.canRequestPackageInstalls(context)) {
                            updateStatus =
                                "Allow installing from this app in the system settings that " +
                                    "just opened, then tap Install again."
                            context.startActivity(AppUpdateCoordinator.manageUnknownSourcesIntent())
                            return@Button
                        }
                        val connectivity = context.getSystemService(
                            android.net.ConnectivityManager::class.java)
                        val metered = connectivity != null && connectivity.isActiveNetworkMetered
                        if (metered && !meteredWarningShown) {
                            meteredWarningShown = true
                            updateStatus =
                                "This looks like a metered connection — the update is " +
                                    "${manifest.size / MIB} MB. Tap Install again to proceed " +
                                    "on mobile data."
                            return@Button
                        }
                        meteredWarningShown = false
                        installBusy = true
                        updateStatus = "Downloading ${manifest.versionName}..."
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                AppUpdateCoordinator.downloadApk(context, manifest) { progress ->
                                    updateStatus =
                                        "Downloading... ${progress / MIB} / " +
                                            "${manifest.size / MIB} MB"
                                }
                            }.onSuccess { apk ->
                                AppUpdateCoordinator.install(context, apk)
                                updateStatus =
                                    "Install offered to the system — confirm in the installer " +
                                        "dialog. The new version starts on the next launch."
                            }.onFailure { failure ->
                                updateStatus =
                                    "Update failed: ${failure.message ?: failure.javaClass.simpleName}"
                            }
                            installBusy = false
                        }
                    },
                ) { Text("Install update") }
            }
        }

        HorizontalDivider()
        SettingCard("Realm data") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = exportIncludesAccount,
                    onCheckedChange = { exportIncludesAccount = it },
                )
                Text(
                    "Include login credentials in the export (PLAIN TEXT)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = {
                    exportPicker.launch(
                        "pocket-realm-characters-" +
                            java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                                .format(java.util.Date()) + ".zip",
                    )
                },
                enabled = !realmDataBusy,
                modifier = Modifier.fillMaxWidth().testTag("settings-realm-export"),
            ) { Text(if (realmDataBusy) "Working…" else "Back up realm characters") }
            OutlinedButton(
                onClick = { importPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                enabled = !realmDataBusy,
                modifier = Modifier.fillMaxWidth().testTag("settings-realm-import"),
            ) { Text("Import realm data") }
            Text(
                realmDataStatus,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("settings-realm-data-status"),
            )
            Text(
                "Exports the realm database (characters, bot accounts, login credentials) as one " +
                    "file you choose. Importing replaces the current realm database; archives from a " +
                    "different app build are rejected by the database seals.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    pendingImport?.let { (source, info) ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import realm data?") },
            text = {
                Text(
                    "Characters from snapshot ${info.snapshotId} (from app ${info.appVersionName}, " +
                        "created " + java.text.SimpleDateFormat("d MMM yyyy HH:mm", java.util.Locale.US)
                            .format(java.util.Date(info.createdAtMs)) + ") will replace the current " +
                        "realm database. The realm stops during the restore.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val pending = pendingImport
                    pendingImport = null
                    pending?.let { (uri, archiveInfo) ->
                        scope.launch { restoreRealmCharacters(uri, archiveInfo) }
                    }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
            },
        )
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

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

internal val advancedSettingExplanations: Map<String, String> = mapOf(
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
