package com.pocketrealm.ui

import android.os.Build

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.bots.BotAdvancedSettings
import com.pocketrealm.bots.BotBehaviorPreset
import com.pocketrealm.bots.matchesBehaviorPreset
import com.pocketrealm.bots.withBehaviorPreset
import com.pocketrealm.client.ArmTranslationBackend
import com.pocketrealm.client.AndroidSystemVulkanProbe
import com.pocketrealm.client.ClientAudioPolicy
import com.pocketrealm.client.ClientDisplayCapabilities
import com.pocketrealm.client.ClientDisplayProfile
import com.pocketrealm.client.ClientFrameCap
import com.pocketrealm.client.ClientRuntimeSelector
import com.pocketrealm.client.ClientTweaksConfig
import com.pocketrealm.client.RendererPackageCatalog
import com.pocketrealm.client.SystemVulkanCapabilities
import com.pocketrealm.client.VulkanDriverCatalog
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.UserAccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Bounded Advanced screen. Values here are presets within safe ranges; runtime
 * tuples / addon profiles / visual overlays are generation-managed elsewhere
 * (O17), not free text here.
 */
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues = PaddingValues(),
    onClientSetup: (() -> Unit)? = null,
    onCapability: (() -> Unit)? = null,
    onDiagnostics: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val settings = remember(context) { Settings(context) }
    val snap by settings.flow.collectAsState(initial = Settings.Snapshot())
    val scope = rememberCoroutineScope()
    val systemVulkanProbe by produceState<Result<SystemVulkanCapabilities>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { AndroidSystemVulkanProbe.probe() } }
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
        SettingCard("ARM client runtime") {
            Text("Box64 + DXVK (Vulkan)", style = MaterialTheme.typography.titleSmall)
            Text("This is the only supported ARM client route. Runtime and renderer fallback are disabled.",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Vulkan driver", style = MaterialTheme.typography.titleSmall)
            Text(
                "Choose how DXVK reaches the device GPU. Unavailable drivers stay visible with their reason and are never substituted at launch.",
                style = MaterialTheme.typography.bodySmall,
            )
            VulkanDriverCatalog.all().forEach { driver ->
                val availability = VulkanDriverCatalog.availabilityForPair(
                    driver.id,
                    snap.selectedDxvkPackageId(),
                    Build.MODEL,
                    systemVulkanProbe?.getOrNull(),
                )
                FilterChip(
                    selected = snap.selectedVulkanDriverId() == driver.id,
                    enabled = availability.available,
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
                    snap.selectedVulkanDriverId(),
                    pkg.id,
                    Build.MODEL,
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
                            settings.update { it.copy(displayProfileId = profile.id) }
                        }
                    },
                    label = { Text(when (profile) {
                        ClientDisplayProfile.BALANCED -> "1280 × 720 · Performance"
                        ClientDisplayProfile.QUALITY -> "1920 × 1080 · Sharp"
                    }) },
                    modifier = Modifier.fillMaxWidth().testTag("display-profile-${profile.id}"),
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
                "Sets both WoW's maxFPS and DXVK's D3D9 limiter. It is a maximum, not a promise that translation can sustain it.",
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

        SettingCard("LAN play") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = snap.allowLanPlayers,
                    onCheckedChange = { enabled ->
                        scope.launch { settings.update { it.copy(allowLanPlayers = enabled) } }
                    },
                    modifier = Modifier.testTag("allow-lan-players"),
                )
                Text("  Allow LAN players", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Off by default. When enabled, Start binds realmd and the world only to the exact active private IPv4 interface. " +
                    "Hosting is experimental; MariaDB, RA, and SOAP remain private or disabled.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Join LAN on Home accepts only a canonical private/link-local IPv4 address. Discovery, hostnames, IPv6, UPnP, and mDNS are not used.",
                style = MaterialTheme.typography.labelMedium,
            )
        }

        val selectedBotProfile = BotProfiles.find(snap.botProfileId)
            ?.takeIf { it.userSelectable }
            ?: BotProfiles.migrateLegacyTarget(snap.botPopulationTarget)
        val effectiveBotProfile = remember(snap) {
            if (snap.botAdvancedEnabled) {
                BotProfiles.advanced(snap.botPopulationTarget, snap.botAdvanced)
            } else selectedBotProfile
        }
        SettingCard("World population") {
            BotProfiles.userSelectable().forEach { profile ->
                FilterChip(
                    selected = !snap.botAdvancedEnabled && selectedBotProfile.id == profile.id,
                    onClick = {
                        scope.launch {
                            settings.update {
                                it.copy(
                                    botPopulationTarget = profile.selectedTarget,
                                    botProfileId = profile.id,
                                    botAdvanced = BotAdvancedSettings.fromProfile(profile),
                                    botAdvancedEnabled = false,
                                )
                            }
                        }
                    },
                    label = { Text(profile.displayName) },
                    modifier = Modifier.fillMaxWidth().testTag("bot-profile-${profile.selectedTarget}"),
                )
                Text(profile.summary, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Selected: ${effectiveBotProfile.minimumOnline}-${effectiveBotProfile.maximumOnline} bots, " +
                    "with ${effectiveBotProfile.nearPlayerTeleportMaxAmount} favored per nearby cluster. " +
                    "Applied when the realm next starts.",
                style = MaterialTheme.typography.labelMedium,
            )

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = snap.botAdvancedEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { settings.update { it.copy(botAdvancedEnabled = enabled) } }
                    },
                    modifier = Modifier.testTag("bot-advanced-enabled"),
                )
                Text("  Advanced bot tuning", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "All controls stay inside the measured mobile limits. Safety floors and automatic load shedding remain active.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (snap.botAdvancedEnabled) {
                AdvancedBotControls(
                    target = snap.botPopulationTarget,
                    advanced = snap.botAdvanced,
                    onTarget = { target ->
                        scope.launch {
                            settings.update {
                                it.copy(
                                    botPopulationTarget = target,
                                    botAdvanced = it.botAdvanced.copy(
                                        nearbyBotLimit = minOf(it.botAdvanced.nearbyBotLimit, target),
                                    ),
                                )
                            }
                        }
                    },
                    onAdvanced = { transform ->
                        scope.launch {
                            settings.update { current ->
                                val transformed = transform(current.botAdvanced)
                                val nearbyLimit = transformed.nearbyBotLimit.coerceAtMost(
                                    minOf(50, current.botPopulationTarget),
                                )
                                current.copy(
                                    botAdvanced = transformed.copy(
                                        nearbyBotLimit = nearbyLimit,
                                        nearbyRadius = if (nearbyLimit == 0) 0
                                            else transformed.nearbyRadius,
                                    ),
                                )
                            }
                        }
                    },
                    onReset = {
                        scope.launch {
                            settings.update {
                                it.copy(botAdvanced = BotAdvancedSettings.fromProfile(
                                    BotProfiles.find(it.botProfileId)
                                        ?: BotProfiles.migrateLegacyTarget(it.botPopulationTarget),
                                ))
                            }
                        }
                    },
                )
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
                "Audio is unavailable on this retained x86 validation provider. Your preference is kept for supported ARM64 devices."
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
                "Import, device information, and troubleshooting are kept here so the Home screen stays focused on play.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AdvancedBotControls(
    target: Int,
    advanced: BotAdvancedSettings,
    onTarget: (Int) -> Unit,
    onAdvanced: ((BotAdvancedSettings) -> BotAdvancedSettings) -> Unit,
    onReset: () -> Unit,
) {
    LabeledSlider(
        label = "Population target",
        valueText = "$target bots",
        value = target.toFloat(),
        range = 25f..700f,
        steps = 26,
        tag = "bot-advanced-target",
    ) { onTarget((it / 25f).roundToInt() * 25) }

    LabeledSlider(
        label = "Nearby density",
        valueText = if (advanced.nearbyBotLimit == 0) "Off" else "${advanced.nearbyBotLimit} bots",
        value = advanced.nearbyBotLimit.toFloat(),
        range = 0f..minOf(50, target).toFloat(),
        steps = minOf(50, target) - 1,
        tag = "bot-advanced-nearby",
    ) { raw ->
        val limit = normalizeNearbyBotLimit(raw, target)
        onAdvanced { current ->
            current.copy(
                nearbyBotLimit = limit,
                nearbyRadius = if (limit == 0) 0 else current.nearbyRadius.coerceAtLeast(100),
            )
        }
    }
    if (advanced.nearbyBotLimit > 0) {
        LabeledSlider(
            label = "Nearby radius",
            valueText = "${advanced.nearbyRadius} yards",
            value = advanced.nearbyRadius.toFloat(),
            range = 100f..500f,
            steps = 7,
            tag = "bot-advanced-radius",
        ) { value ->
            onAdvanced { it.copy(nearbyRadius = (value / 50f).roundToInt() * 50) }
        }
    }

    LabeledSlider(
        label = "Login batch",
        valueText = "${advanced.loginBatchSize} per interval",
        value = advanced.loginBatchSize.toFloat(),
        range = 1f..10f,
        steps = 8,
        tag = "bot-advanced-login-batch",
    ) { value -> onAdvanced { it.copy(loginBatchSize = value.roundToInt()) } }
    LabeledSlider(
        label = "Maintenance batch",
        valueText = "${advanced.maintenanceBatchSize} bots",
        value = advanced.maintenanceBatchSize.toFloat(),
        range = 1f..32f,
        steps = 30,
        tag = "bot-advanced-maintenance-batch",
    ) { value -> onAdvanced { it.copy(maintenanceBatchSize = value.roundToInt()) } }
    LabeledSlider(
        label = "Background update interval",
        valueText = "${advanced.updateIntervalMs / 1000f} seconds",
        value = advanced.updateIntervalMs.toFloat(),
        range = 1_000f..5_000f,
        steps = 15,
        tag = "bot-advanced-update-interval",
    ) { value ->
        onAdvanced { it.copy(updateIntervalMs = (value / 250f).roundToInt() * 250) }
    }

    Text("Nearby teleport cadence", style = MaterialTheme.typography.labelLarge)
    Text(
        "This range controls how often distant bots may be moved near active players.",
        style = MaterialTheme.typography.bodySmall,
    )
    listOf(30 to 120, 60 to 240, 120 to 480, 240 to 720).forEach { (min, max) ->
        FilterChip(
            selected = advanced.teleportMinMinutes == min && advanced.teleportMaxMinutes == max,
            onClick = {
                onAdvanced { it.copy(teleportMinMinutes = min, teleportMaxMinutes = max) }
            },
            label = { Text("${formatMinutes(min)} – ${formatMinutes(max)}") },
            modifier = Modifier.testTag("bot-advanced-teleport-$min-$max"),
        )
    }

    LabeledSlider(
        label = "Bot work per tick",
        valueText = "${advanced.iterationsPerTick} iterations",
        value = advanced.iterationsPerTick.toFloat(),
        range = 1f..20f,
        steps = 18,
        tag = "bot-advanced-iterations",
    ) { value -> onAdvanced { it.copy(iterationsPerTick = value.roundToInt()) } }
    Text(
        "Lower values smooth CPU usage; higher values make bot decisions catch up faster.",
        style = MaterialTheme.typography.bodySmall,
    )

    LabeledSlider(
        label = "Reduce population above world p99",
        valueText = "${advanced.admissionWorldP99Ms} ms",
        value = advanced.admissionWorldP99Ms.toFloat(),
        range = 100f..300f,
        steps = 7,
        tag = "bot-advanced-p99",
    ) { value ->
        onAdvanced { it.copy(admissionWorldP99Ms = (value / 25f).roundToInt() * 25) }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = advanced.syncLevelWithPlayers,
            onCheckedChange = { enabled ->
                onAdvanced { it.copy(syncLevelWithPlayers = enabled) }
            },
            modifier = Modifier.testTag("bot-advanced-sync-level"),
        )
        Column(Modifier.padding(start = 8.dp)) {
            Text("Keep fresh-realm bots near player level")
            Text(
                "New bots are adjusted toward active player levels so early zones remain useful.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    HorizontalDivider()
    Text("Behaviour", style = MaterialTheme.typography.titleSmall)
    Text(
        "Choose a starting style, then change any individual behaviour below.",
        style = MaterialTheme.typography.bodySmall,
    )
    BotBehaviorPreset.values().forEach { preset ->
        FilterChip(
            selected = advanced.matchesBehaviorPreset(preset),
            onClick = { onAdvanced { it.withBehaviorPreset(preset) } },
            label = { Text(preset.label) },
            modifier = Modifier.fillMaxWidth().testTag("bot-behavior-${preset.name.lowercase()}"),
        )
        Text(preset.summary, style = MaterialTheme.typography.bodySmall)
    }

    LabeledSlider(
        label = "Fully active background bots",
        valueText = "${advanced.activeBotPercent}%",
        value = advanced.activeBotPercent.toFloat(),
        range = 1f..20f,
        steps = 18,
        tag = "bot-advanced-active-percent",
    ) { value -> onAdvanced { it.copy(activeBotPercent = value.roundToInt()) } }
    Text(
        "Nearby bots are still promoted separately; lowering this reduces off-screen CPU work.",
        style = MaterialTheme.typography.bodySmall,
    )
    BehaviorSwitch("Limit background combat work", advanced.limitCombatActivity,
        "bot-behavior-limit-combat") { value -> onAdvanced { it.copy(limitCombatActivity = value) } }
    BehaviorSwitch("Quest and level autonomously", advanced.autoDoQuests,
        "bot-behavior-quests") { value -> onAdvanced { it.copy(autoDoQuests = value) } }
    BehaviorSwitch("Chat without a player master", advanced.allowBotChat,
        "bot-behavior-chat") { value -> onAdvanced { it.copy(allowBotChat = value) } }
    BehaviorSwitch("Invite the player", advanced.allowPlayerInvites,
        "bot-behavior-invites") { value -> onAdvanced { it.copy(allowPlayerInvites = value) } }
    BehaviorSwitch("Form groups with nearby bots", advanced.groupNearby,
        "bot-behavior-groups") { value -> onAdvanced { it.copy(groupNearby = value) } }
    BehaviorSwitch("Wander when idle", advanced.wanderWhenIdle,
        "bot-behavior-wander") { value -> onAdvanced { it.copy(wanderWhenIdle = value) } }
    BehaviorSwitch("Use off-spec strategies", advanced.enableOffSpecStrategies,
        "bot-behavior-offspec") { value -> onAdvanced { it.copy(enableOffSpecStrategies = value) } }

    OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
        Text("Use selected profile defaults")
    }
    Text("Advanced changes are validated immediately and apply on the next realm start.",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun BehaviorSwitch(
    label: String,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(advancedExplanation(label), style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun normalizeNearbyBotLimit(raw: Float, target: Int): Int =
    ((raw / 2f).roundToInt() * 2).coerceIn(0, minOf(50, target))

@Composable
private fun AutoLoginTimingControls(
    timings: Settings.AutoLoginTimings,
    onTimings: ((Settings.AutoLoginTimings) -> Settings.AutoLoginTimings) -> Unit,
    onReset: () -> Unit,
) {
    LabeledSlider("Poll interval", "${timings.pollIntervalMs} ms",
        timings.pollIntervalMs.toFloat(), 100f..1000f, 17, "al-poll") { v ->
        onTimings { it.copy(pollIntervalMs = v.toLong().coerceIn(100, 1000)) }
    }
    LabeledSlider("Stable polls", "${timings.requiredStablePolls}",
        timings.requiredStablePolls.toFloat(), 1f..12f, 10, "al-stable") { v ->
        onTimings { it.copy(requiredStablePolls = v.toInt().coerceIn(1, 12)) }
    }
    LabeledSlider("Login UI settle", "${timings.loginUiSettleMs} ms",
        timings.loginUiSettleMs.toFloat(), 1000f..30000f, 57, "al-settle") { v ->
        onTimings { it.copy(loginUiSettleMs = v.toLong().coerceIn(1000, 30000)) }
    }
    LabeledSlider("Session timeout", "${timings.sessionTimeoutMs / 1000} s",
        timings.sessionTimeoutMs.toFloat(), 60000f..900000f, 55, "al-session") { v ->
        onTimings { it.copy(sessionTimeoutMs = v.toLong().coerceIn(60000, 900000)) }
    }
    LabeledSlider("Drain poll", "${timings.drainPollMs} ms",
        timings.drainPollMs.toFloat(), 25f..200f, 34, "al-drain") { v ->
        onTimings { it.copy(drainPollMs = v.toLong().coerceIn(25, 200)) }
    }
    LabeledSlider("Input drain timeout", "${timings.inputDrainTimeoutMs} ms",
        timings.inputDrainTimeoutMs.toFloat(), 1000f..30000f, 57, "al-input-drain") { v ->
        onTimings { it.copy(inputDrainTimeoutMs = v.toLong().coerceIn(1000, 30000)) }
    }
    LabeledSlider("IME key dwell", "${timings.imeKeyDwellMs} ms",
        timings.imeKeyDwellMs.toFloat(), 20f..200f, 35, "al-ime-dwell") { v ->
        onTimings { it.copy(imeKeyDwellMs = v.toLong().coerceIn(20, 200)) }
    }
    LabeledSlider("IME key gap", "${timings.imeKeyGapMs} ms",
        timings.imeKeyGapMs.toFloat(), 0f..100f, 19, "al-ime-gap") { v ->
        onTimings { it.copy(imeKeyGapMs = v.toLong().coerceIn(0, 100)) }
    }
    LabeledSlider("Field settle", "${timings.fieldSettleMs} ms",
        timings.fieldSettleMs.toFloat(), 50f..2000f, 38, "al-field") { v ->
        onTimings { it.copy(fieldSettleMs = v.toLong().coerceIn(50, 2000)) }
    }
    LabeledSlider("Pointer dwell", "${timings.pointerDwellMs} ms",
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
    TweakSwitch("Quickloot reverse (shift = manual)", tweaks.quicklootEnabled, "tweak-quickloot") { on ->
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

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    tag: String,
    onValueChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(valueText, style = MaterialTheme.typography.labelMedium)
    }
    Text(advancedExplanation(label), style = MaterialTheme.typography.bodySmall)
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

private fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
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
    "Population target" to "Sets the long-run bot population requested after the realm finishes ramping up.",
    "Nearby density" to "Caps how many bots are favored around active players; Off disables nearby promotion.",
    "Nearby radius" to "Defines the distance used to count bots as nearby for player-focused population.",
    "Login batch" to "Limits how many bots may log in during one admission interval to avoid startup spikes.",
    "Maintenance batch" to "Limits how many bot records are refreshed in one maintenance pass.",
    "Background update interval" to "Sets the delay between background bot update passes; longer delays reduce CPU use.",
    "Bot work per tick" to "Controls how many queued bot decisions run per world tick.",
    "Reduce population above world p99" to "Starts load shedding when the slowest one percent of world updates exceed this time.",
    "Fully active background bots" to "Sets the percentage of off-screen bots allowed to run full behavior logic.",
    "Limit background combat work" to "Reduces combat simulation for bots far from every player.",
    "Quest and level autonomously" to "Lets bots choose quests and gain levels without a player directing them.",
    "Chat without a player master" to "Allows autonomous bot chat, which can make the world livelier but noisier.",
    "Invite the player" to "Allows nearby bots to send group invitations to the player.",
    "Form groups with nearby bots" to "Allows bots to create local parties instead of acting only as individuals.",
    "Wander when idle" to "Lets idle bots travel locally rather than waiting in place.",
    "Use off-spec strategies" to "Allows bots to use strategies outside their primary role when useful.",
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
    "Sound channel count (64)" to "Raises the available sound channels to reduce effects cutting each other off.",
    "Quickloot reverse (shift = manual)" to "Makes quick loot the default and uses Shift for the original manual behavior.",
    "Nameplate distance (41 yd)" to "Extends the maximum range at which unit nameplates can appear.",
    "Large address aware" to "Lets the 32-bit client use a larger address space under the translated runtime.",
    "Camera skip glitch fix" to "Applies the known camera update fix that prevents sudden skipped movement.",
    "Max camera distance raise" to "Allows a farther third-person camera when the in-game distance setting is increased.",
)

internal fun advancedExplanation(label: String): String =
    advancedSettingExplanations.getValue(label)
