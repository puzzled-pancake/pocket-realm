package com.pocketrealm.ui

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.bots.BotAdvancedSettings
import com.pocketrealm.client.ArmTranslationBackend
import com.pocketrealm.client.RendererPackageCatalog
import com.pocketrealm.storage.Settings
import kotlinx.coroutines.launch
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Advanced", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // Runtime provider — FEX remains laboratory (flavor.json: Automatic never selects it).
        SettingCard("Runtime provider") {
            Settings.RuntimeProvider.values().forEach { p ->
                val selected = snap.provider == p
                FilterChip(
                    selected = selected,
                    onClick = { scope.launch { settings.update { it.copy(provider = p) } } },
                    label = { Text(label(p)) },
                )
            }
            Text("Applied on the next client launch. Box64 remains the fallback; FEXCore uses native ARM64EC Wine.",
                style = MaterialTheme.typography.bodySmall)
        }

        SettingCard("Renderer") {
            Settings.Renderer.values().forEach { r ->
                FilterChip(
                    selected = snap.renderer == r,
                    onClick = { scope.launch { settings.update { it.copy(renderer = r) } } },
                    label = { Text(if (r == Settings.Renderer.DXVK) "DXVK (Vulkan)" else "Client OpenGL (Android GLES)") },
                )
            }
            Text("Independent of the Box64/FEX translator choice; applied on the next client launch.",
                style = MaterialTheme.typography.bodySmall)
            if (snap.renderer == Settings.Renderer.DXVK) {
                HorizontalDivider()
                val translator = if (snap.provider == Settings.RuntimeProvider.FEX) {
                    ArmTranslationBackend.FEX
                } else ArmTranslationBackend.BOX64
                Text("DXVK package", style = MaterialTheme.typography.titleSmall)
                RendererPackageCatalog.compatible(translator).forEach { pkg ->
                    FilterChip(
                        selected = snap.selectedDxvkPackageId() == pkg.id,
                        onClick = {
                            scope.launch {
                                settings.update { current ->
                                    if (translator == ArmTranslationBackend.FEX) {
                                        current.copy(fexDxvkPackageId = pkg.id)
                                    } else current.copy(box64DxvkPackageId = pkg.id)
                                }
                            }
                        },
                        label = { Text(pkg.label) },
                        modifier = Modifier.fillMaxWidth().testTag("renderer-package-${pkg.id}"),
                    )
                    Text(pkg.qualification, style = MaterialTheme.typography.bodySmall)
                }
                snap.rendererSelectionNotice?.let { notice ->
                    Text(notice, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("Versioned package and shader cache are applied on the next launch.",
                    style = MaterialTheme.typography.labelMedium)
            }
        }

        SettingCard("Frame rate (${snap.fpsProfile.hz} FPS)") {
            Slider(
                value = snap.fpsProfile.ordinal.toFloat(),
                onValueChange = { idx ->
                    val profile = Settings.FpsProfile.values()[idx.toInt()]
                    scope.launch { settings.update { it.copy(fpsProfile = profile) } }
                },
                valueRange = 0f..(Settings.FpsProfile.values().lastIndex.toFloat()),
                steps = Settings.FpsProfile.values().lastIndex - 1,
            )
        }

        val selectedBotProfile = BotProfiles.forRequestedTarget(snap.botPopulationTarget)
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
                                    BotProfiles.forRequestedTarget(it.botPopulationTarget),
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
        Text("  Keep fresh-realm bots near player level")
    }

    OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
        Text("Use selected profile defaults")
    }
    Text("Advanced changes are validated immediately and apply on the next realm start.",
        style = MaterialTheme.typography.bodySmall)
}

internal fun normalizeNearbyBotLimit(raw: Float, target: Int): Int =
    ((raw / 2f).roundToInt() * 2).coerceIn(0, minOf(50, target))

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

private fun label(p: Settings.RuntimeProvider) = when (p) {
    Settings.RuntimeProvider.BOX64 -> "Box64 (default)"
    Settings.RuntimeProvider.FEX -> "FEXCore (ARM64EC)"
}
