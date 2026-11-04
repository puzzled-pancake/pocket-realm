package com.pocketrealm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketrealm.bots.BotActivityPreset
import com.pocketrealm.bots.BotAdvancedSettings
import com.pocketrealm.bots.BotCustomConfiguration
import com.pocketrealm.bots.BotPlaystylePreset
import com.pocketrealm.bots.BotPopulationPolicy
import com.pocketrealm.bots.BotPresetStore
import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.desktop.DesktopBotAdmissionVerdict

/**
 * The Bots screen's editor sections and the preset rail — split from
 * BotsScreen.kt only for file size; same package, private to the UI.
 */

@Composable
@Suppress("LongParameterList", "LongMethod") // one callback per preset verb, mirroring the Android rail
internal fun PresetRail(
    modifier: Modifier,
    presets: List<BotPresetStore.SavedPreset>,
    selectedSavedId: String?,
    selectedBuiltInId: String?,
    appliedSavedId: String?,
    appliedBuiltInId: String?,
    onSelectBuiltIn: (String) -> Unit,
    onSelectSaved: (String) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onExport: (BotPresetStore.SavedPreset) -> Unit,
    onDelete: (BotPresetStore.SavedPreset) -> Unit,
    onRename: (BotPresetStore.SavedPreset) -> Unit,
    onDuplicate: (BotPresetStore.SavedPreset) -> Unit,
    onFavorite: (BotPresetStore.SavedPreset, Boolean) -> Unit,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("PRESETS", style = MaterialTheme.typography.labelMedium)
            Text("Recommended", style = MaterialTheme.typography.titleSmall)
            PresetRow(
                title = "* " + BotProfiles.defaultProfile.displayName,
                summary = BotProfiles.defaultProfile.summary,
                selected = selectedBuiltInId == BotProfiles.defaultProfile.id,
                applied = appliedBuiltInId == BotProfiles.defaultProfile.id,
                onClick = { onSelectBuiltIn(BotProfiles.defaultProfile.id) },
            )
            Text("Built-in", style = MaterialTheme.typography.titleSmall)
            BotProfiles.experiencePresets
                .filter { it.id != BotProfiles.defaultProfile.id }
                .forEach { profile ->
                    PresetRow(
                        title = profile.displayName,
                        summary = profile.summary,
                        selected = selectedBuiltInId == profile.id,
                        applied = appliedBuiltInId == profile.id,
                        onClick = { onSelectBuiltIn(profile.id) },
                    )
                }
            Text("My presets", style = MaterialTheme.typography.titleSmall)
            if (presets.isEmpty()) {
                Text(
                    "Custom presets you save appear here.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            presets.sortedWith(
                compareByDescending<BotPresetStore.SavedPreset> { it.favorite }.thenBy { it.name },
            ).forEach { preset ->
                PresetRow(
                    title = (if (preset.favorite) "* " else "") + preset.name,
                    summary = preset.configuration.selectedTarget.toString() + " bots · " +
                        preset.configuration.activeBotPercent + "% active",
                    selected = selectedSavedId == preset.id,
                    applied = appliedSavedId == preset.id,
                    onClick = { onSelectSaved(preset.id) },
                    actions = {
                        TextButton(onClick = { onFavorite(preset, !preset.favorite) }) {
                            Text(if (preset.favorite) "Unstar" else "Star")
                        }
                        TextButton(onClick = { onRename(preset) }) { Text("Rename") }
                        TextButton(onClick = { onDuplicate(preset) }) { Text("Duplicate") }
                        TextButton(onClick = { onExport(preset) }) { Text("Export") }
                        TextButton(onClick = { onDelete(preset) }) { Text("Delete") }
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onNew) { Text("New") }
                OutlinedButton(onClick = onImport) { Text("Import") }
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun PresetRow(
    title: String,
    summary: String,
    selected: Boolean,
    applied: Boolean,
    onClick: () -> Unit,
    actions: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        TextButton(onClick = onClick) {
            Text(
                (if (selected) "> " else "") + title + (if (applied) "  | applied" else ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp))
        if (actions != null) {
            Row(modifier = Modifier.padding(start = 4.dp)) { actions() }
        }
    }
}

@Composable
@Suppress("LongMethod", "MagicNumber") // world-size chips + editor ranges are the content
internal fun BasicsTab(
    configuration: BotCustomConfiguration,
    running: Boolean,
    edit: ((BotCustomConfiguration) -> BotCustomConfiguration) -> Unit,
) {
    if (running) {
        Text(
            "Realm is running - a changed configuration applies on the next start. " +
                "The running realm keeps its launch snapshot.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    val worldSizes = listOf(80, 160, 240, 320, 400, 500, 600)
    Text("WORLD SIZE", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        worldSizes.forEach { size ->
            FilterChip(
                selected = configuration.selectedTarget == size && configuration.maximumOnline == size,
                onClick = { edit { it.withTarget(size) } },
                label = { Text(size.toString()) },
            )
        }
        FilterChip(
            selected = configuration.selectedTarget !in worldSizes ||
                configuration.maximumOnline != configuration.selectedTarget,
            onClick = { edit { it.withTarget(725) } },
            label = { Text("Custom") },
        )
    }
    if (configuration.selectedTarget !in worldSizes) {
        var text by remember(configuration.selectedTarget) {
            mutableStateOf(configuration.selectedTarget.toString())
        }
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                text = value.filter(Char::isDigit).take(5)
                text.toIntOrNull()?.let { target ->
                    if (target in 10..10_000) edit { it.withTarget(target) }
                }
            },
            label = { Text("Target bots") },
            supportingText = {
                Text(
                    "Valid range 10..10000; " +
                        BotPopulationPolicy.allocatedAccounts(configuration.selectedTarget) +
                        " accounts auto-provisioned",
                )
            },
            isError = (text.toIntOrNull() ?: 0) < 10,
            singleLine = true,
            modifier = Modifier.width(260.dp),
        )
    }
    SummaryRows(configuration)
    Text("ACTIVITY", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BotActivityPreset.entries.forEach { preset ->
            FilterChip(
                selected = preset.matches(configuration),
                onClick = { edit { preset.applyTo(it) } },
                label = { Text(preset.label) },
            )
        }
    }
    Text("PLAYSTYLE", style = MaterialTheme.typography.labelMedium)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BotPlaystylePreset.entries.forEach { preset ->
            FilterChip(
                selected = preset.matches(configuration),
                onClick = { edit { preset.applyTo(it) } },
                label = { Text(preset.label + " - " + preset.summary) },
            )
        }
    }
}

@Composable
@Suppress("MagicNumber") // 9 = characters per bot account (the shared policy)
private fun SummaryRows(configuration: BotCustomConfiguration) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Preview - press Apply before starting the realm to use this configuration.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            configuration.selectedTarget.toString() + " bots - ~" +
                configuration.estimatedActiveBots() + " normally active - human groups highest priority",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Ramp " + configuration.initialTarget + " -> " + configuration.selectedTarget,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "AI pass " + configuration.randomBotUpdateIntervalMs + "ms - " +
                configuration.iterationsPerTick + " it/tick",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Accounts auto - " + configuration.accountCount + " (capacity " +
                configuration.accountCount * 9 + " characters)",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Adaptive on - reduce above world p99 " + configuration.admission.maxWorldP99Ms + " ms",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
@Suppress("LongMethod", "MagicNumber")
internal fun PopulationTab(
    configuration: BotCustomConfiguration,
    edit: ((BotCustomConfiguration) -> BotCustomConfiguration) -> Unit,
) {
    Stepper(
        label = "Minimum online",
        value = configuration.minimumOnline,
        range = 0..configuration.selectedTarget,
        onValue = { value ->
            edit {
                it.copy(
                    minimumOnline = value,
                    initialTarget = maxOf(it.initialTarget, value),
                )
            }
        },
    )
    Stepper(
        label = "Initial bots",
        value = configuration.initialTarget,
        range = configuration.minimumOnline..configuration.selectedTarget,
        onValue = { value -> edit { it.copy(initialTarget = value) } },
    )
    Text("ADVANCED TUNING", style = MaterialTheme.typography.labelMedium)
    Stepper(
        label = "Startup increase step",
        value = configuration.startupIncreaseStep,
        range = 1..configuration.selectedTarget,
        onValue = { value -> edit { it.copy(startupIncreaseStep = value) } },
    )
    Stepper(
        label = "Startup ramp interval (s)",
        value = (configuration.startupRampIntervalMs / 1000).toInt(),
        range = 0..1800,
        step = 30,
        onValue = { value -> edit { it.copy(startupRampIntervalMs = value * 1000L) } },
    )
    Stepper(
        label = "Activation batch",
        value = configuration.activationBatchSize,
        range = 1..64,
        onValue = { value -> edit { it.copy(activationBatchSize = value) } },
    )
    Stepper(
        label = "Maximum configured",
        value = configuration.maximumOnline,
        range = configuration.selectedTarget..10_000,
        onValue = { value ->
            edit {
                it.copy(
                    maximumOnline = value,
                    accountCount = maxOf(it.accountCount, BotPopulationPolicy.allocatedAccounts(value)),
                )
            }
        },
    )
    Stepper(
        label = "Teleport min (min)",
        value = configuration.teleportMinIntervalSeconds / 60,
        range = 1..2880,
        step = 30,
        onValue = { value ->
            edit {
                it.copy(
                    teleportMinIntervalSeconds = value * 60,
                    teleportMaxIntervalSeconds = maxOf(it.teleportMaxIntervalSeconds, value * 60),
                )
            }
        },
    )
    Stepper(
        label = "Teleport max (min)",
        value = configuration.teleportMaxIntervalSeconds / 60,
        range = 1..2880,
        step = 30,
        onValue = { value ->
            edit {
                it.copy(
                    teleportMaxIntervalSeconds = value * 60,
                    teleportMinIntervalSeconds = minOf(it.teleportMinIntervalSeconds, value * 60),
                )
            }
        },
    )
    Text("ADAPTIVE ADMISSION", style = MaterialTheme.typography.labelMedium)
    SliderRow(
        label = "Reduce above world p99 (ms)",
        value = configuration.admission.maxWorldP99Ms.toFloat(),
        range = 100f..300f,
        steps = 7,
        onValue = { value ->
            edit { it.copy(admission = it.admission.copy(maxWorldP99Ms = value.toInt())) }
        },
    )
    SliderRow(
        label = "Memory floor (MiB)",
        value = configuration.admission.minFreeMemoryMiB.toFloat(),
        range = 256f..8192f,
        steps = 30,
        onValue = { value ->
            edit { it.copy(admission = it.admission.copy(minFreeMemoryMiB = value.toLong())) }
        },
    )
    SliderRow(
        label = "Storage floor (MiB)",
        value = configuration.admission.minFreeStorageMiB.toFloat(),
        range = 256f..8192f,
        steps = 30,
        onValue = { value ->
            edit { it.copy(admission = it.admission.copy(minFreeStorageMiB = value.toLong())) }
        },
    )
    Stepper(
        label = "Warm-up (min)",
        value = (configuration.admission.performanceWarmupMs / 60_000).toInt(),
        range = 0..30,
        onValue = { value ->
            edit { it.copy(admission = it.admission.copy(performanceWarmupMs = value * 60_000L)) }
        },
    )
    Stepper(
        label = "Healthy ramp (min)",
        value = (configuration.admission.healthyRampMs / 60_000).toInt(),
        range = 1..120,
        onValue = { value ->
            edit {
                it.copy(
                    admission = it.admission.copy(
                        healthyRampMs = value * 60_000L,
                        changeCooldownMs = minOf(it.admission.changeCooldownMs, value * 60_000L),
                    ),
                )
            }
        },
    )
    Stepper(
        label = "Change cooldown (s)",
        value = (configuration.admission.changeCooldownMs / 1000).toInt(),
        range = 10..300,
        step = 5,
        onValue = { value ->
            edit {
                it.copy(
                    admission = it.admission.copy(
                        changeCooldownMs = value * 1000L,
                        healthyRampMs = maxOf(it.admission.healthyRampMs, value * 1000L),
                    ),
                )
            }
        },
    )
}

@Composable
@Suppress("LongMethod", "MagicNumber")
internal fun BehaviourTab(
    configuration: BotCustomConfiguration,
    edit: ((BotCustomConfiguration) -> BotCustomConfiguration) -> Unit,
) {
    Text("SCHEDULING", style = MaterialTheme.typography.labelMedium)
    Stepper(
        label = "AI update interval (ms)",
        value = configuration.randomBotUpdateIntervalMs,
        range = 500..5000,
        step = 250,
        onValue = { value -> edit { it.copy(randomBotUpdateIntervalMs = value) } },
    )
    Stepper(
        label = "Bot work per tick (iterations)",
        value = configuration.iterationsPerTick,
        range = 1..20,
        onValue = { value -> edit { it.copy(iterationsPerTick = value) } },
    )
    Stepper(
        label = "Fully active background bots (%)",
        value = configuration.activeBotPercent,
        range = 1..20,
        onValue = { value -> edit { it.copy(activeBotPercent = value) } },
    )
    Text("TEMPERAMENT", style = MaterialTheme.typography.labelMedium)
    SwitchRow(
        label = "Quest and level autonomously",
        checked = configuration.autoDoQuests,
        onChecked = { value -> edit { it.copy(autoDoQuests = value) } },
    )
    SwitchRow(
        label = "Form groups with nearby bots",
        checked = configuration.groupNearby,
        onChecked = { value -> edit { it.copy(groupNearby = value) } },
    )
    SwitchRow(
        label = "Wander when idle",
        checked = configuration.wanderWhenIdle,
        onChecked = { value -> edit { it.copy(wanderWhenIdle = value) } },
    )
    SwitchRow(
        label = "Use off-spec strategies",
        checked = configuration.enableOffSpecStrategies,
        onChecked = { value -> edit { it.copy(enableOffSpecStrategies = value) } },
    )
    SwitchRow(
        label = "Bots may invite the player",
        checked = configuration.allowPlayerInvites,
        onChecked = { value -> edit { it.copy(allowPlayerInvites = value) } },
    )
    SwitchRow(
        label = "Limit background combat work",
        checked = configuration.limitCombatActivity,
        onChecked = { value -> edit { it.copy(limitCombatActivity = value) } },
    )
    Text("LEVELING", style = MaterialTheme.typography.labelMedium)
    SwitchRow(
        label = "Match bot levels to players",
        checked = configuration.syncLevelWithPlayers,
        onChecked = { value -> edit { it.copy(syncLevelWithPlayers = value) } },
    )
    Stepper(
        label = "Max levels above player",
        value = configuration.syncLevelMaxAbove,
        range = 0..10,
        onValue = { value -> edit { it.copy(syncLevelMaxAbove = value) } },
    )
    Stepper(
        label = "Level when no players online",
        value = configuration.syncLevelNoPlayer,
        range = 1..60,
        onValue = { value -> edit { it.copy(syncLevelNoPlayer = value) } },
    )
    Stepper(
        label = "Max-level bot chance (%)",
        value = (configuration.randomBotMaxLevelChance * 100).toInt(),
        range = 0..100,
        step = 5,
        onValue = { value -> edit { it.copy(randomBotMaxLevelChance = value / 100f) } },
    )
    Text("LOGIN & ACCOUNTS", style = MaterialTheme.typography.labelMedium)
    Text(
        "Account pool: automatic - prefix " + configuration.accountPrefix + " - " +
            configuration.accountCount + " accounts - capacity " +
            configuration.accountCount * 9 + " characters",
        style = MaterialTheme.typography.bodySmall,
    )
    Stepper(
        label = "Login batch",
        value = configuration.loginBatchSize,
        range = 1..10,
        onValue = { value -> edit { it.copy(loginBatchSize = value) } },
    )
    Stepper(
        label = "Maintenance batch",
        value = configuration.maintenanceBatchSize,
        range = 1..64,
        onValue = { value -> edit { it.copy(maintenanceBatchSize = value) } },
    )
    Stepper(
        label = "Generation batch",
        value = configuration.generationBatchSize,
        range = 1..10,
        onValue = { value -> edit { it.copy(generationBatchSize = value) } },
    )
    Stepper(
        label = "Generation yield (ms)",
        value = configuration.generationYieldMs.toInt(),
        range = 0..5000,
        step = 50,
        onValue = { value -> edit { it.copy(generationYieldMs = value.toLong()) } },
    )
    SwitchRow(
        label = "Bots log in at realm start",
        checked = configuration.loginAtStartup,
        onChecked = { value -> edit { it.copy(loginAtStartup = value) } },
    )
    SwitchRow(
        label = "Bot joins with its player",
        checked = configuration.loginWithPlayer,
        onChecked = { value -> edit { it.copy(loginWithPlayer = value) } },
    )
    SwitchRow(
        label = "Bots speak on their own",
        checked = configuration.allowBotChat,
        onChecked = { value -> edit { it.copy(allowBotChat = value) } },
    )
}

@Composable
@Suppress("MagicNumber")
private fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onValue: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { incoming ->
            text = incoming.filter(Char::isDigit).take(6)
            text.toIntOrNull()?.let { parsed ->
                if (parsed in range && (parsed - range.first) % step == 0) onValue(parsed)
            }
        },
        label = { Text(label) },
        supportingText = {
            Text(
                if (step == 1) {
                    "range " + range.first + ".." + range.last
                } else {
                    "range " + range.first + ".." + range.last + " step " + step
                },
            )
        },
        isError = (text.toIntOrNull() ?: -1).let { it !in range || (it - range.first) % step != 0 },
        singleLine = true,
        modifier = Modifier.width(340.dp),
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValue: (Float) -> Unit,
) {
    Column {
        Text("$label: ${value.toInt()}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            steps = steps,
            modifier = Modifier.width(340.dp),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = checked, onCheckedChange = onChecked)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * DESK-8/23/43/99: the live adaptive-admission verdict — the shared
 * controller's latest decision for the running world (effective vs
 * selected target, the reason, and the sample it came from). Null while no
 * measured profile runs; the world-side knob still rides the next
 * `PocketRealm.BotTarget` conf via the persisted admission record.
 */
@Composable
internal fun BotAdmissionCard(verdict: DesktopBotAdmissionVerdict?, running: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("ADAPTIVE ADMISSION (LIVE)", style = MaterialTheme.typography.labelMedium)
            if (verdict == null) {
                Text(
                    if (running) {
                        "No measured bot profile is being adapted in this session."
                    } else {
                        "Start the realm with a bot profile to see the live load-protection verdict."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }
            Text(
                verdict.profileId + " - target " + verdict.effectiveTarget + " of " +
                    verdict.selectedTarget + " (" + verdict.reason + ")" +
                    if (verdict.adapted) " - adapted" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                verdict.onlineBots.toString() + " bots online - world tick p99 " +
                    verdict.worldP99Ms + " ms",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "free memory " + verdict.freeMemoryMiB + " MiB - free storage " +
                    verdict.freeStorageMiB + " MiB - process cpu " +
                    (if (verdict.processCpuLoad >= 0) {
                        (verdict.processCpuLoad * 100).toInt().toString() + "%"
                    } else {
                        "n/a"
                    }) + " - footprint " + verdict.committedMemoryMiB + " MiB",
                style = MaterialTheme.typography.bodySmall,
            )
            if (verdict.error.isNotEmpty()) {
                Text(
                    "monitor: " + verdict.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * DESK-118/124: the launcher-level advanced tuning editor — the Android
 * per-tab "Advanced tuning" disclosures' launch-time override lane
 * (storage `botAdvancedEnabled`/`botAdvanced`), rendered as one disclosure
 * card editing the shared [BotAdvancedSettings] draft. When enabled and no
 * saved preset is applied, the shared BotSelection.resolve takes the
 * advanced (non-preset) branch at world start; applying a preset clears
 * the override (the Android apply() parity).
 */
@Composable
@Suppress("LongMethod", "MagicNumber") // the Android advanced editor's control set
internal fun AdvancedTuningCard(
    enabled: Boolean,
    advanced: BotAdvancedSettings,
    presetApplied: Boolean,
    onEnabled: (Boolean) -> Unit,
    onAdvanced: (BotAdvancedSettings) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("LAUNCH-TIME ADVANCED TUNING", style = MaterialTheme.typography.labelMedium)
            Text(
                "The adv-style launch override: when no saved preset is selected, the realm " +
                    "launches a profile built from these values instead of the plain built-in. " +
                    "Applies at the next realm start (or Apply now on a running world).",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = enabled, onCheckedChange = onEnabled)
                Text(
                    if (enabled) "Advanced launch tuning is on" else "Advanced launch tuning is off",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (presetApplied) {
                Text(
                    "A saved preset is applied — it wins over this override until you apply " +
                        "a built-in profile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (enabled) {
                Stepper(
                    label = "Nearby bots per human",
                    value = advanced.nearbyBotLimit,
                    range = 0..50,
                    onValue = { value ->
                        onAdvanced(
                            advanced.copy(
                                nearbyBotLimit = value,
                                nearbyRadius = if (value == 0) 0 else maxOf(advanced.nearbyRadius, 100),
                            ),
                        )
                    },
                )
                if (advanced.nearbyBotLimit > 0) {
                    Stepper(
                        label = "Nearby radius (yd)",
                        value = advanced.nearbyRadius,
                        range = 100..500,
                        onValue = { value -> onAdvanced(advanced.copy(nearbyRadius = value)) },
                    )
                }
                Stepper(
                    label = "Login batch",
                    value = advanced.loginBatchSize,
                    range = 1..10,
                    onValue = { value -> onAdvanced(advanced.copy(loginBatchSize = value)) },
                )
                Stepper(
                    label = "Maintenance batch",
                    value = advanced.maintenanceBatchSize,
                    range = 1..32,
                    onValue = { value -> onAdvanced(advanced.copy(maintenanceBatchSize = value)) },
                )
                Stepper(
                    label = "AI update interval (ms)",
                    value = advanced.updateIntervalMs,
                    range = 1000..5000,
                    step = 250,
                    onValue = { value -> onAdvanced(advanced.copy(updateIntervalMs = value)) },
                )
                Stepper(
                    label = "Teleport min (min)",
                    value = advanced.teleportMinMinutes,
                    range = 10..2880,
                    step = 5,
                    onValue = { value ->
                        onAdvanced(
                            advanced.copy(
                                teleportMinMinutes = value,
                                teleportMaxMinutes = maxOf(value, advanced.teleportMaxMinutes),
                            ),
                        )
                    },
                )
                Stepper(
                    label = "Teleport max (min)",
                    value = advanced.teleportMaxMinutes,
                    range = advanced.teleportMinMinutes..2880,
                    step = 5,
                    onValue = { value -> onAdvanced(advanced.copy(teleportMaxMinutes = value)) },
                )
                Stepper(
                    label = "Bot work per tick (iterations)",
                    value = advanced.iterationsPerTick,
                    range = 1..20,
                    onValue = { value -> onAdvanced(advanced.copy(iterationsPerTick = value)) },
                )
                SliderRow(
                    label = "Admission: reduce above world p99 (ms)",
                    value = advanced.admissionWorldP99Ms.toFloat(),
                    range = 100f..300f,
                    steps = 7,
                    onValue = { value ->
                        onAdvanced(
                            advanced.copy(admissionWorldP99Ms = ((value.toInt() / 25) * 25)),
                        )
                    },
                )
                Stepper(
                    label = "Fully active background bots (%)",
                    value = advanced.activeBotPercent,
                    range = 1..20,
                    onValue = { value -> onAdvanced(advanced.copy(activeBotPercent = value)) },
                )
                SwitchRow(
                    label = "Match bot levels to players",
                    checked = advanced.syncLevelWithPlayers,
                    onChecked = { value -> onAdvanced(advanced.copy(syncLevelWithPlayers = value)) },
                )
                SwitchRow(
                    label = "Limit background combat work",
                    checked = advanced.limitCombatActivity,
                    onChecked = { value -> onAdvanced(advanced.copy(limitCombatActivity = value)) },
                )
                SwitchRow(
                    label = "Quest and level autonomously",
                    checked = advanced.autoDoQuests,
                    onChecked = { value -> onAdvanced(advanced.copy(autoDoQuests = value)) },
                )
                SwitchRow(
                    label = "Bots may chat",
                    checked = advanced.allowBotChat,
                    onChecked = { value -> onAdvanced(advanced.copy(allowBotChat = value)) },
                )
                SwitchRow(
                    label = "Bots may invite the player",
                    checked = advanced.allowPlayerInvites,
                    onChecked = { value -> onAdvanced(advanced.copy(allowPlayerInvites = value)) },
                )
                SwitchRow(
                    label = "Form groups with nearby bots",
                    checked = advanced.groupNearby,
                    onChecked = { value -> onAdvanced(advanced.copy(groupNearby = value)) },
                )
                SwitchRow(
                    label = "Wander when idle",
                    checked = advanced.wanderWhenIdle,
                    onChecked = { value -> onAdvanced(advanced.copy(wanderWhenIdle = value)) },
                )
                SwitchRow(
                    label = "Use off-spec strategies",
                    checked = advanced.enableOffSpecStrategies,
                    onChecked = { value -> onAdvanced(advanced.copy(enableOffSpecStrategies = value)) },
                )
            }
        }
    }
}
