package com.pocketrealm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.pocketrealm.client.ClientTweaksConfig
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.server.NearbyInteractPolicy
import com.pocketrealm.storage.Settings

/**
 * The Settings screen, Windows subset: the verbose world log toggle, auto-login,
 * the update check, and the provenance/about card (the Android
 * renderer/display/input sections are not carried over — the Windows
 * client runs natively; the LAN host/join topology moved to the
 * Multiplayer screen).
 */
@Composable
@Suppress("LongMethod")
fun SettingsScreen(model: DesktopAppModel, onOpenBots: () -> Unit = {}) {
    val settings by model.settings.collectAsState()
    val storedAccount by model.storedAccount.collectAsState()
    // Design-timeframe: no mid-session switch — the conf is generated at
    // world start, so the timeframe card disables while a world is up and
    // directs the user to stop first (the conf-riding-toggle discipline).
    val runtime by model.state.collectAsState()
    val realmRunning = realmRunningPhase(runtime.phase)

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // the Settings "Prepare world data" flow (the DESK-44/102
        // progress card) — the same lane the Home card renders.
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bots", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "In use: " + (
                            if (settings.botSavedPresetId != null) "custom preset"
                            else com.pocketrealm.bots.BotProfiles.find(settings.botProfileId)?.displayName
                                ?: com.pocketrealm.bots.BotProfiles.defaultProfile.displayName
                            ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                androidx.compose.material3.Button(onClick = onOpenBots) { Text("Manage bots") }
            }
        }
        DataPreparationCard(model)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("World", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(
                        checked = settings.worldDebugLogs,
                        onCheckedChange = { value ->
                            model.updateSettings { it.copy(worldDebugLogs = value) }
                        },
                    )
                    Text("Verbose world-server log", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Raises the world log level to verbose when the realm next starts and can " +
                        "grow world.log very large. Keep it off unless investigating a problem.",
                    style = MaterialTheme.typography.bodySmall,
                )
                // the nearby-use debounce stepper (the Android
                // nearbyInteractTriggerGuardMs surface) — bounds come from
                // the shared NearbyInteractPolicy; applies at the next realm
                // start like every world.conf knob.
                NearbyGuardStepper(model, settings.nearbyInteractTriggerGuardMs)
            }
        }
        // Design-timeframe: the per-flavor content-patch knob — the
        // qualified flavor's ordered stages as a selectable list, default =
        // final patch, disabled while the realm runs.
        TimeframeCard(model, settings.timeframeStage, realmRunning)
        // the client-tweak lane's Controls card (the
        // Android ClientTweakControls port). Toggling any patch on IS the
        // consent to patch the user's client copy at the next launch; the
        // card's copy says so.
        ClientTweaksCard(model, settings.tweaks)
        // the game-audio mute toggle (the Android audio-mode card's
        // desktop twin). Off enforces the Config.wtf master-sound overlay leg
        // at the next launch and mutes the client's OS audio session.
        AudioModeCard(model, settings.audioMode)
        // the database provider selection — the shipped SQLite
        // lane vs the staged-provider MariaDB engine lane.
        DatabaseProviderCard(model, settings.databaseProvider)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Auto-login", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(
                        checked = settings.autoLoginOnLaunch,
                        onCheckedChange = { value ->
                            model.updateSettings { it.copy(autoLoginOnLaunch = value) }
                        },
                    )
                    Text("Log in automatically when the client opens", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    if (storedAccount != null) {
                        "Stored account: ${storedAccount!!.username}"
                    } else {
                        "No stored account - create one on Home after the realm is online."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (storedAccount != null) {
                    OutlinedButton(onClick = { model.clearAccount() }) { Text("Clear") }
                }
                // the persisted auto-login timing surface (the
                // Android AutoLoginTimings twin) — bounds-clamped, shown
                // here so the automation's waits are never invisible.
                val timings = settings.effectiveAutoLoginTimings()
                Text(
                    "Timing: waits up to ${timings.sessionTimeoutMs / 1000} s for the login " +
                        "window, lets the login screen settle ${timings.loginUiSettleMs / 1000} s, " +
                        "then types (${timings.imeKeyDwellMs} ms keystrokes, " +
                        "${timings.imeKeyGapMs} ms gaps). Advanced timing: edit " +
                        "autoLoginTimings in settings.json while the app is closed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        RealmArchiveCard(model)
        // the input safe-mode gate — the addon projection honors it
        // at every client launch (safe mode projects none of the managed
        // add-on folders).
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add-on input safe mode", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(
                        checked = settings.inputSafeMode,
                        onCheckedChange = { value ->
                            model.updateSettings { it.copy(inputSafeMode = value) }
                        },
                    )
                    Text("Launch without managed add-ons", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Keeps every managed add-on out of the game at the next launch — nothing is " +
                        "projected into the client and no key bindings are reserved for them. Use it " +
                        "to rule out an add-on after a crash or stuck input; turn it off to bring " +
                        "them back.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        UpdatesCard(model)
        // the replayable Setup card (the Android
        // Settings → Setup card's desktop twin, minus the SAF/report legs
        // that have no desktop lane). The replay bumps the tutorial's
        // process-wide channel; the overlay renders over any route.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Setup", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { model.replayTutorial() }) {
                    Text("Show first-run setup guide")
                }
                Text(
                    "Setup covers the game-folder import, the one-time world-data " +
                        "preparation, and the first-run setup guide, which you can replay " +
                        "anytime. Start here on a new install.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pocket Realm for Windows ${com.pocketrealm.desktop.APP_VERSION}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Native runtime: " + com.pocketrealm.BuildConfig.nativeRuntimeBuildIdFor(
 // Flip: the RENDER line resolves through
                        // the machine's canonical displayedId — no pick yet
                        // renders the vanilla lane's telltale rather than
                        // crashing composition.
                        com.pocketrealm.client.ActiveFlavor.displayedId ?: "vanilla",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Storage: ${model.roots.root}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Sources: recorded in schemas/sources.json and " +
                        "schemas/realm-runtime-lockfile-sqlite-win.json (every dependency is version-locked).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * the nearby-use debounce stepper — the persisted
 * `nearbyInteractTriggerGuardMs` (world.conf's
 * PocketRealm.NearbyInteractCooldownMs), bounds clamped through the shared
 * [NearbyInteractPolicy]; applies at the next realm start.
 */
@Composable
private fun NearbyGuardStepper(model: DesktopAppModel, currentMs: Int) {
    val normalized = NearbyInteractPolicy.normalizeTriggerGuardMs(currentMs)
    Text(
        "Nearby loot/use debounce",
        style = MaterialTheme.typography.titleSmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            enabled = normalized > NearbyInteractPolicy.MIN_TRIGGER_GUARD_MS,
            onClick = {
                model.updateSettings {
                    it.copy(
                        nearbyInteractTriggerGuardMs = NearbyInteractPolicy.normalizeTriggerGuardMs(
                            normalized - NearbyInteractPolicy.TRIGGER_GUARD_STEP_MS,
                        ),
                    )
                }
            },
        ) { Text("−${NearbyInteractPolicy.TRIGGER_GUARD_STEP_MS} ms") }
        Text("${normalized} ms", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            enabled = normalized < NearbyInteractPolicy.MAX_TRIGGER_GUARD_MS,
            onClick = {
                model.updateSettings {
                    it.copy(
                        nearbyInteractTriggerGuardMs = NearbyInteractPolicy.normalizeTriggerGuardMs(
                            normalized + NearbyInteractPolicy.TRIGGER_GUARD_STEP_MS,
                        ),
                    )
                }
            },
        ) { Text("+${NearbyInteractPolicy.TRIGGER_GUARD_STEP_MS} ms") }
    }
    Text(
        "How long a nearby loot/use request waits before it can fire again " +
            "(higher helps on slower machines). Applies at the next realm start.",
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * the lane the
 * "World timeframe" card resolves — the Home switcher's ACTIVE flavor through
 * the [com.pocketrealm.client.ActiveFlavor] machine, replacing the former
 * `singleOrNull() ?: VANILLA` silent default that pinned the card to vanilla
 * while a second lane was qualified (and, with a stored stage of the other
 * lane, crashed Settings composition at the `requireNotNull`). Pure and
 * internal so the desktop suite pins the resolution without a UI harness.
 */
internal fun timeframeCardFlavor(): com.pocketrealm.client.ClientFlavor =
    com.pocketrealm.client.ActiveFlavor.resolve(TIMEFRAME_CARD_LANE)

/**
 * Flip: with several qualified flavors and no pick yet the resolver
 * refuses loud — a render-time card must not crash composition, so the
 * unresolved state surfaces as null (the cards render the honest
 * "pick a game flavor" copy; every OPERATION gate keeps the loud refusal).
 * The resolved id is the machine's own displayedId (selection, else the
 * sole qualified lane, else null) — the same canonical resolution the
 * flavor switcher highlights.
 */
internal fun renderedFlavorIdOrNull(): String? =
    com.pocketrealm.client.ActiveFlavor.displayedId

/** the [com.pocketrealm.client.ActiveFlavor] lane suffix naming the
 * timeframe card's resolution gate. */
private const val TIMEFRAME_CARD_LANE = "the world-timeframe card resolves the active game version"

/**
 * Design-timeframe: the "World timeframe" card — the ordered stages of the
 * ACTIVE QUALIFIED flavor as a selectable list (the flavor's timeline
 * top-to-bottom), default selection = the final patch. The mandatory copy
 * rides the card: the client-version sentence, the apply-time sentence,
 * and — for a backward selection — the consequence sentence. The card never
 * fabricates TBC/WotLK stages for a lane that cannot run them: when no flavor
 * is qualified beyond the shipped vanilla lane it shows exactly the vanilla
 * stage list. Disabled while the realm runs (no mid-session switch).
 */
@Composable
private fun TimeframeCard(model: DesktopAppModel, storedStage: String, realmRunning: Boolean) {
 // Flip: no pick yet under several qualified flavors renders the
    // first-pick copy instead of crashing composition (the operation gates
    // keep the loud refusal; see [renderedFlavorIdOrNull]).
    val flavorId = remember { renderedFlavorIdOrNull() }
    if (flavorId == null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("World timeframe", style = MaterialTheme.typography.titleMedium)
                Text("Pick a game version on the Home screen first — the timeline follows it.")
            }
        }
        return
    }
    val stages = remember(flavorId) {
        com.pocketrealm.client.TimeframeStages.stagesFor(flavorId)
    }
    val selectedId = com.pocketrealm.client.TimeframeStages
        .resolveStored(storedStage)
        .ifEmpty { com.pocketrealm.client.TimeframeStages.defaultStageId(flavorId) }
    val selected = requireNotNull(
        com.pocketrealm.client.TimeframeStages.byId(flavorId, selectedId),
    ) { "resolved stage $selectedId must be registered for $flavorId" }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("World timeframe", style = MaterialTheme.typography.titleMedium)
            stages.chunked(TIMEFRAME_CHIP_ROW).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { stage ->
                        FilterChip(
                            selected = stage.id == selectedId,
                            enabled = !realmRunning,
                            onClick = {
                                // The final patch persists as "" (the
                                // default), so existing-world behavior is
                                // the stored state too, not a special case.
                                model.updateSettings {
                                    it.copy(timeframeStage = if (stage.isFinal) "" else stage.id)
                                }
                            },
                            label = { Text(stage.id.removePrefix("$flavorId-")) },
                        )
                    }
                }
            }
            Text(
                "The game client stays at its fixed version; this gates world content only. " +
                    "Applies the next time the realm starts.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!selected.isFinal) {
                // Design honesty: the present-tense "later content
                // closes" claim is only true once the stage's availability
                // data is measured. Until the measured per-stage sets land,
                // no build gates anything — asserting closure would promise
                // a restriction the shipped data does not enforce. A stage
                // with no staged overlay for its flavor refuses the world
                // start outright, so its copy must say that instead.
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
                            "characters already have stay. Want the world's characters back at an " +
                            "earlier point too? Restore a realm-data archive taken before that stage."
                    } else {
                        "${selected.title}: this stage's content availability is not measured " +
                            "yet, so nothing closes yet — the world still runs the current " +
                            "release's content and the choice is only recorded for the realm " +
                            "(the AI guides speak the era's wording). The measured per-stage " +
                            "data ships in a later update. Want the world's characters back at " +
                            "an earlier point anyway? Restore a realm-data archive taken " +
                            "before that stage."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (realmRunning) {
                Text(
                    "The realm is running — Save & exit first, then choose the stage.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Stage chips per row in the timeframe card. */
private const val TIMEFRAME_CHIP_ROW = 4

/**
 * Design-timeframe: the phases in which the world holds the conf — the
 * timeframe card disables exactly while one of these holds (no mid-session
 * switch; the spent-world restart contract rides the app relaunch).
 */
private fun realmRunningPhase(phase: com.pocketrealm.supervisor.RuntimePhase): Boolean =
    phase == com.pocketrealm.supervisor.RuntimePhase.WORLD_READY ||
        phase == com.pocketrealm.supervisor.RuntimePhase.RUNNING ||
        phase == com.pocketrealm.supervisor.RuntimePhase.PAUSED ||
        phase == com.pocketrealm.supervisor.RuntimePhase.CLIENT_FAILED

/**
 * the client-tweak Controls card (the Android
 * ClientTweakControls port). Each switch is one upstream vanilla-tweaks
 * patch; off restores the upstream game default. Toggling any patch on is
 * the consent that lets the launch boundary publish the verified
 * `WoW.exe.patched` (upstream output plus the Pocket-Realm nearby-loot
 * acceptance patch) next to the user's pristine WoW.exe — the original
 * executable is never modified, and the server-side nearby-use knob follows
 * the same consent.
 */
@Composable
private fun ClientTweaksCard(model: DesktopAppModel, tweaks: ClientTweaksConfig) {
    // The vanilla tweak set is validated against
    // the 1.12.1 client only — a non-vanilla lane (reachable since the
    // qualification flip) must render the honest "not available" copy and
    // NEVER expose the vanilla-client patch-consent toggles (the desktop
    // twin of the android ClientRuntimeContract.flavorTweaksCopy gate).
 // Flip: with several qualified flavors and no pick yet the resolver
    // refuses loud — render the first-pick copy instead of crashing
    // composition (the operation gates keep the loud refusal).
    val tweaksLane = remember { com.pocketrealm.client.ActiveFlavor.displayedId }
    val tweaksUnavailable = when {
        tweaksLane == null -> "Pick a game version on the Home screen first — tweaks follow it."
        else -> com.pocketrealm.client.ClientRuntimeContract.flavorTweaksCopy(
            com.pocketrealm.client.ClientFlavors.ALL.first { it.flavorId == tweaksLane },
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Client tweaks", style = MaterialTheme.typography.titleMedium)
            if (tweaksUnavailable != null) {
                TweaksUnavailableCopy(tweaksUnavailable)
                return@Column
            }
            VanillaTweakToggles(model, tweaks)
            Text(
                "Off switches restore the upstream game default. Turning any patch on consents " +
                    "to patching this install's client copy: the next game start verifies the " +
                    "executable against the authorized vanilla client layout and writes the patched " +
                    "WoW.exe.patched beside it (your original WoW.exe is never modified; the " +
                    "patched copy also enables the one-button nearby loot/use feature, whose " +
                    "server knob follows this card). Wrong-layout installs keep launching " +
                    "unpatched. Applied on the next client launch.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun VanillaTweakToggles(model: DesktopAppModel, tweaks: ClientTweaksConfig) {
            TweakSwitch("Widescreen FoV fix", tweaks.fovEnabled) { on ->
                model.updateSettings { it.copy(tweaks = it.tweaks.copy(fovEnabled = on)) }
            }
            TweakSwitch("Farclip cap raise", tweaks.farclipEnabled) { on ->
                model.updateSettings { it.copy(tweaks = it.tweaks.copy(farclipEnabled = on)) }
            }
            TweakSwitch("Frill distance raise", tweaks.frilldistanceEnabled) { on ->
                model.updateSettings { it.copy(tweaks = it.tweaks.copy(frilldistanceEnabled = on)) }
            }
            TweakSwitch("Sound in background", tweaks.soundInBackgroundEnabled) { on ->
                model.updateSettings {
                    it.copy(tweaks = it.tweaks.copy(soundInBackgroundEnabled = on))
                }
            }
            TweakSwitch("Sound channel count (64)", tweaks.soundChannelsEnabled) { on ->
                model.updateSettings {
                    it.copy(tweaks = it.tweaks.copy(soundChannelsEnabled = on))
                }
            }
            TweakSwitch("Auto-loot opened corpses", tweaks.quicklootEnabled) { on ->
                model.updateSettings { it.copy(tweaks = it.tweaks.copy(quicklootEnabled = on)) }
            }
            TweakSwitch("Nameplate distance (41 yd)", tweaks.nameplateEnabled) { on ->
                model.updateSettings { it.copy(tweaks = it.tweaks.copy(nameplateEnabled = on)) }
            }
            TweakSwitch("Large address aware", tweaks.largeAddressAwareEnabled) { on ->
                model.updateSettings {
                    it.copy(tweaks = it.tweaks.copy(largeAddressAwareEnabled = on))
                }
            }
            TweakSwitch("Camera skip glitch fix", tweaks.cameraSkipFixEnabled) { on ->
                model.updateSettings { it.copy(tweaks = it.tweaks.copy(cameraSkipFixEnabled = on)) }
            }
            TweakSwitch("Max camera distance raise", tweaks.maxCameraDistanceEnabled) { on ->
                model.updateSettings {
                    it.copy(tweaks = it.tweaks.copy(maxCameraDistanceEnabled = on))
                }
            }
}

@Composable
private fun TweaksUnavailableCopy(unavailable: String) {
    Text(unavailable, style = MaterialTheme.typography.bodySmall)
    Text(
        "Switch back to Vanilla on Home to use the tweaks.",
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun TweakSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The database provider selection. MARIADB is the shipped DEFAULT —
 * matching the on-device product; a first boot initializes the MariaDB
 * datadir and an unstaged provider distribution refuses with the DB-LINK
 * message at start, never a silent fallback. SQLITE remains selectable as
 * the explicit dev/compat provider: it materializes the seeded datadir
 * in-process and adopts pre-existing SQLite realms. Applies at the next
 * start.
 */
@Composable
private fun DatabaseProviderCard(model: DesktopAppModel, provider: Settings.DatabaseProvider) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Database provider", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    Settings.DatabaseProvider.MARIADB to "MariaDB (default)",
                    Settings.DatabaseProvider.SQLITE to "SQLite (dev/compat)",
                ).forEach { (candidate, label) ->
                    FilterChip(
                        selected = provider == candidate,
                        onClick = {
                            model.updateSettings { it.copy(databaseProvider = candidate) }
                        },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                when (provider) {
                    Settings.DatabaseProvider.MARIADB ->
                        "MariaDB (the default, like the on-device product) runs the staged " +
                            "provider distribution as a managed daemon; a first boot " +
                            "initializes the MariaDB datadir. It must be staged first " +
                            "(tools/build_win_realm_runtime.py --database-backend mariadb); " +
                            "until then starting the database refuses with a DB-LINK error " +
                            "instead of silently falling back. Applies at the next start."
                    Settings.DatabaseProvider.SQLITE ->
                        "SQLite materializes the seeded datadir in-process (the explicit " +
                            "dev/compat choice — keep it selected to keep using an existing " +
                            "SQLite realm). Applies at the next start."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * the game-audio mode toggle (the Android audio-mode card's
 * desktop twin). Off takes effect at the next game start: the Config.wtf
 * overlay enforces the master-sound CVar off AND the app mutes the client's
 * Windows audio session for that launch; On leaves both user-owned.
 */
@Composable
private fun AudioModeCard(model: DesktopAppModel, audioMode: Settings.AudioMode) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Game audio", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(
                    checked = audioMode == Settings.AudioMode.ON,
                    onCheckedChange = { on ->
                        model.updateSettings {
                            it.copy(audioMode = if (on) Settings.AudioMode.ON else Settings.AudioMode.OFF)
                        }
                    },
                )
                Text("Game sound on", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Turning the game sound off applies at the next game start: the client opens " +
                    "muted (its Windows audio session is muted and the in-game master sound " +
                    "stays off until you turn this back on and relaunch). The toggle never " +
                    "touches any other application's audio.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Review R3 (the import lane of the card below): the status the realm-archive
 * IMPORT shows — the export lane's runCatching fold discipline applied to the
 * import. [DesktopAppModel.importRealmArchive] can THROW after the character
 * restore already committed (the credentials write is a raw FileOutputStream
 * + Files.move with no catch inside the model), and reading `.detail` bare
 * would kill the coroutine before status/busy reset — wedging the card at
 * "Importing…" with busy=true forever while the committed restore goes
 * unreported. Pure and internal so the desktop suite pins the mapping
 * without a UI harness (the [timeframeCardFlavor] pattern); the failure
 * copy matches the model's own extract-failure wording.
 */
internal suspend fun realmArchiveImportStatus(
    import: suspend () -> com.pocketrealm.supervisor.RuntimeOperation,
): String = runCatching { import() }.fold(
    { operation -> operation.detail },
    { failure -> "Import failed: ${failure.message ?: failure.javaClass.simpleName}" },
)

/**
 * the realm-characters archive card (the Android
 * SettingsScreen archive section's desktop twin): export the current
 * characters as the shared single-file ZIP (a stopped-state database
 * snapshot + the paired credentials when explicitly opted in + a manifest,
 * cross-compatible with the Android app), inspect a picked archive without
 * extracting, and import it through the transactional restore. Exports
 * land in the exports root — the ONLY user-facing output root; only the
 * import side uses a picker (java.awt FileDialog, the BotsScreen pattern).
 */
@Composable
@Suppress("LongMethod") // one card, the export/import/confirm flow together
private fun RealmArchiveCard(model: DesktopAppModel) {
    var includeAccount by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf("Characters live in the stopped-realm database snapshots.")
    }
    var busy by remember { mutableStateOf(false) }
    var pendingImport by remember {
        mutableStateOf<Pair<java.io.File, com.pocketrealm.ui.RealmDataArchive.ArchiveInfo>?>(null)
    }
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Realm characters archive", style = MaterialTheme.typography.titleMedium)
            Text(
                "Move characters between installs (including to an Android device): one zip " +
                    "with a database snapshot, the paired credentials when you opt in, and a " +
                    "manifest. Exports land in ${model.roots.exports}.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(checked = includeAccount, onCheckedChange = { includeAccount = it })
                Text("Include stored account credentials", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        status = "Working…"
                        scope.launch {
                            status = runCatching { model.exportRealmArchive(includeAccount) }.fold(
                                { export ->
                                    "Characters exported (snapshot ${export.snapshotId}): ${export.file}"
                                },
                                { failure ->
                                    "Export failed: ${failure.message ?: failure.javaClass.simpleName}"
                                },
                            )
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Working…" else "Export characters…") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        pickRealmArchive { picked ->
                            val info = model.inspectRealmArchive(picked)
                            if (info == null) {
                                status = "Could not read the selected archive."
                            } else {
                                pendingImport = picked to info
                                status = "Archive read: snapshot ${info.snapshotId} " +
                                    "(from app ${info.appVersionName})."
                            }
                        }
                    },
                ) { Text("Inspect / import…") }
            }
            val pending = pendingImport
            if (pending != null) {
                Text(
                    "Characters from snapshot ${pending.second.snapshotId} (from app " +
                        "${pending.second.appVersionName}). Importing restores this snapshot " +
                        "over the current realm databases — the app boots the restored stack to " +
                        "prove it, and rolls the original databases back on any failure. If the " +
                        "archive carries saved login credentials, they also replace this " +
                        "machine's saved auto-login account.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            status = "Importing…"
                            scope.launch {
                                status = realmArchiveImportStatus { model.importRealmArchive(pending.first) }
                                pendingImport = null
                                busy = false
                            }
                        },
                    ) { Text("Import snapshot ${pending.second.snapshotId}") }
                    OutlinedButton(enabled = !busy, onClick = { pendingImport = null }) { Text("Cancel") }
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The java.awt FileDialog pick (the BotsScreen preset pattern); the
 * callback runs on the picker thread and writes Compose snapshot state
 * (safe from any thread; recomposition stays on the UI thread). */
private fun pickRealmArchive(onPicked: (java.io.File) -> Unit) {
    Thread {
        val frame = java.awt.Window.getWindows().firstOrNull { it is java.awt.Frame } as? java.awt.Frame
        val dialog = java.awt.FileDialog(frame, "Import realm characters", java.awt.FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.endsWith(".zip", ignoreCase = true) }
        dialog.isVisible = true
        val file = dialog.directory?.let { dir -> dialog.file?.let { name -> java.io.File(dir, name) } }
        if (file != null && file.isFile) onPicked(file)
    }.apply { isDaemon = true }.start()
}

/**
 * The Updates card: the opt-in feed check plus — when
 * the feed carries download legs — the resumable digest-verified download
 * and the two-step consented install. Network and file I/O stay off the UI
 * thread; the unsigned-build copy rides every step; nothing applies without
 * the confirmation click.
 */
@Composable
private fun UpdatesCard(model: DesktopAppModel) {
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember {
        mutableStateOf<com.pocketrealm.desktop.UpdateCheck.CheckResult?>(null)
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Updates", style = MaterialTheme.typography.titleMedium)
            Text(
                "The Windows build is unsigned and never updates silently; releases " +
                    "ship as new packages. The provenance card below identifies the " +
                    "exact build.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(enabled = !checking, onClick = {
                checking = true
                scope.launch {
                    val result = kotlinx.coroutines.withContext(
                        kotlinx.coroutines.Dispatchers.IO,
                    ) { com.pocketrealm.desktop.UpdateCheck.checkDetailed(model.roots) }
                    checkResult = result
                    updateStatus = com.pocketrealm.desktop.UpdateCheck.displayStatus(result)
                    checking = false
                }
            }) { Text(if (checking) "Checking…" else "Check for updates") }
            updateStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val available = checkResult
                as? com.pocketrealm.desktop.UpdateCheck.CheckResult.Available
            if (available != null && available.manifest.downloadable) {
                UpdatesDownloadRow(model, available.manifest, scope)
            }
        }
    }
}

/**
 * The download + consented install half of the Updates card (DESK-14/32/45/
 * 91/104): resumable digest-verified download into the settings downloads
 * dir, then a TWO-STEP explicit install (arm, then confirm) that swaps the
 * jpackage app image or launches the release installer. Progress renders in
 * this card; nothing here runs on the UI thread and nothing applies without
 * the confirmation click. The unsigned-build copy rides every step.
 */
@Composable
@Suppress("MagicNumber") // MB progress units
private fun UpdatesDownloadRow(
    model: DesktopAppModel,
    manifest: com.pocketrealm.desktop.UpdateCheck.UpdateManifest,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var downloading by remember { mutableStateOf(false) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var stagedPackage by remember { mutableStateOf<java.io.File?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var installArmed by remember { mutableStateOf(false) }
    var outcome by remember {
        mutableStateOf<com.pocketrealm.desktop.UpdateApply.ApplyOutcome?>(null)
    }

    val stage = stagedPackage
    if (stage == null) {
        DownloadControls(
            model,
            manifest,
            scope,
            DownloadUiState(
                downloading,
                onDownloading = { downloading = it },
                onBytes = { downloadedBytes = it },
                onError = { downloadError = it },
                onStaged = { stagedPackage = it },
            ),
        )
        if (downloading) {
            Text(
                "${downloadedBytes / MIB} / ${(manifest.size ?: 0) / MIB} MB " +
                    "(resumable; verified against the feed's sha256)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        downloadError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    } else if (!installArmed) {
        Text("Staged: ${stage.absolutePath}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { installArmed = true }) {
                Text("Install downloaded update…")
            }
        }
    } else {
        InstallConfirmRow(
            stage, manifest.sha256, scope,
            onArmed = { installArmed = it },
            onOutcome = { outcome = it },
        )
    }
    outcome?.let { applied -> ApplyOutcomeView(applied) { outcome = it } }
}

private const val MIB = 1024L * 1024

/** The download card's UI callbacks, bundled to keep the composable readable. */
private class DownloadUiState(
    val downloading: Boolean,
    val onDownloading: (Boolean) -> Unit,
    val onBytes: (Long) -> Unit,
    val onError: (String?) -> Unit,
    val onStaged: (java.io.File?) -> Unit,
)

/** The download button + its coroutine (network I/O off the UI thread). */
@Composable
private fun DownloadControls(
    model: DesktopAppModel,
    manifest: com.pocketrealm.desktop.UpdateCheck.UpdateManifest,
    scope: kotlinx.coroutines.CoroutineScope,
    ui: DownloadUiState,
) {
    OutlinedButton(enabled = !ui.downloading, onClick = {
        ui.onDownloading(true)
        ui.onError(null)
        scope.launch {
            val result = kotlinx.coroutines.withContext(
                kotlinx.coroutines.Dispatchers.IO,
            ) {
                runCatching {
                    com.pocketrealm.desktop.UpdateDownload.download(
                        model.roots,
                        manifest,
                    ) { bytes -> ui.onBytes(bytes) }
                }
            }
            ui.onStaged(result.getOrNull())
            ui.onError(
                result.exceptionOrNull()?.let { failure ->
                    "Download failed: ${failure.message}"
                },
            )
            ui.onDownloading(false)
        }
    }) { Text(if (ui.downloading) "Downloading…" else "Download ${manifest.version}") }
}

/** The armed confirm/cancel pair; the confirm click IS the consent. */
@Composable
private fun InstallConfirmRow(
    stagedPackage: java.io.File,
    expectedSha256: String?,
    scope: kotlinx.coroutines.CoroutineScope,
    onArmed: (Boolean) -> Unit,
    onOutcome: (com.pocketrealm.desktop.UpdateApply.ApplyOutcome) -> Unit,
) {
    Text(
        "The build is UNSIGNED: the staged package was verified only against the " +
            "feed's sha256, not a code signature. Review the source before installing.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            onArmed(false)
            scope.launch {
                // The apply folds ANY
                // escape into the honest Failed outcome — the same guard
                // posture the download click above carries — so an IO
                // failure mid-swap can never kill the coroutine (the app)
                // instead of landing the copy.
                val applied = kotlinx.coroutines.withContext(
                    kotlinx.coroutines.Dispatchers.IO,
                ) {
                    runCatching {
                        com.pocketrealm.desktop.UpdateApply.applyDownloadedUpdate(
                            stagedPackage = stagedPackage,
                            currentImage = com.pocketrealm.desktop.UpdateApply.currentAppImageDir(),
                            consent = true,
                            // the hash gate re-runs AT APPLY — the
                            // download-finalize verification ran long before
                            // this click and the staged file is mutable.
                            expectedSha256 = expectedSha256,
                        )
                    }
                }.getOrElse { failure ->
                    com.pocketrealm.desktop.UpdateApply.ApplyOutcome.Failed(
                        "Install failed: ${failure.message}; nothing else was changed.",
                    )
                }
                onOutcome(applied)
            }
        }) { Text("Confirm install (unsigned build)") }
        OutlinedButton(onClick = { onArmed(false) }) { Text("Cancel") }
    }
}
/**
 * The apply outcome line + the restart affordance after a successful swap.
 */
@Composable
private fun ApplyOutcomeView(
    applied: com.pocketrealm.desktop.UpdateApply.ApplyOutcome,
    onOutcome: (com.pocketrealm.desktop.UpdateApply.ApplyOutcome) -> Unit,
) {
    val message = when (applied) {
        is com.pocketrealm.desktop.UpdateApply.ApplyOutcome.Swapped -> applied.message
        is com.pocketrealm.desktop.UpdateApply.ApplyOutcome.InstallerLaunched -> applied.message
        is com.pocketrealm.desktop.UpdateApply.ApplyOutcome.Refused -> applied.reason
        is com.pocketrealm.desktop.UpdateApply.ApplyOutcome.Failed -> applied.reason
    }
    Text(message, style = MaterialTheme.typography.bodySmall)
    if (applied is com.pocketrealm.desktop.UpdateApply.ApplyOutcome.Swapped) {
        OutlinedButton(onClick = {
            runCatching {
                com.pocketrealm.desktop.UpdateApply.relaunchAndExit(applied.newImage)
            }.onFailure { failure ->
                onOutcome(
                    com.pocketrealm.desktop.UpdateApply.ApplyOutcome.Failed(
                        "cannot restart automatically (${failure.message}); start " +
                            "PocketRealm.exe manually",
                    ),
                )
            }
        }) { Text("Restart now") }
    }
}
