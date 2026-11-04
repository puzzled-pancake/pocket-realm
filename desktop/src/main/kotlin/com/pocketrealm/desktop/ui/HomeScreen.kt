package com.pocketrealm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.bots.BotSelection
import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.FlavorChip
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.desktop.DesktopBotAdmissionVerdict
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.RuntimeFailureCopy
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.supervisor.UserAccountStore
import com.pocketrealm.ui.accountCreateEnabled
import com.pocketrealm.ui.accountCredentialFieldError
import com.pocketrealm.ui.accountProvisionFailureMessage
import java.io.File
import kotlinx.coroutines.launch

/**
 * The de-cliented Home screen: realm control, active setup, and the local
 * account card (the Android HomeScreen minus every Wine/renderer/Vulkan
 * surface — the WoW client is native and always available when installed).
 */
@Composable
@Suppress("LongMethod")
fun HomeScreen(
    model: DesktopAppModel,
    onOpenBots: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenPlayers: () -> Unit = {},
    onOpenMultiplayerHost: () -> Unit = {},
    onOpenMultiplayerFind: () -> Unit = {},
) {
    val snapshot by model.state.collectAsState()
    val settings by model.settings.collectAsState()
    val storedAccount by model.storedAccount.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var accountStatus by remember { mutableStateOf<String?>(null) }
    var showForceStop by remember { mutableStateOf(false) }
    var pendingSaveExitPlayers by remember { mutableStateOf<Int?>(null) }
    var pendingJoinInsteadPlayers by remember { mutableStateOf<Int?>(null) }

    val botProfile = remember(settings) {
        BotSelection.resolve(
            savedPresetId = settings.botSavedPresetId,
            // the real launcher-level advanced override — the Home
            // card shows exactly what the world start would resolve (the
            // adv4 25-step target contract normalized like resolveBotProfile).
            advancedEnabled = settings.botAdvancedEnabled,
            advancedTarget = model.backend.advancedLaunchTarget(settings),
            advanced = settings.botAdvanced,
            profileId = settings.botProfileId,
        ).profile
    }
    val botsSelected = settings.botProfileId.isNotEmpty() || settings.botSavedPresetId != null
    val clientDir = remember(settings) { model.resolveClientDir() }
    val clientSelection = remember(settings) { model.clientSelectionState() }
    val activeGeneration = remember(settings) { model.activeClientGeneration() }

    // the decode boundary — the two wedge flags are derived on
    // the RAW detail exactly like the Android RuntimeSupervisorClient, the
    // UI then renders human copy, and the raw string keeps flowing only to
    // the app log (DesktopAppModel logs it verbatim).
    val rawError = snapshot.lastError
    val unverifiedOrphan = rawError?.contains("UNVERIFIED_ORPHAN") == true
    val dbOwnedByDeadSession = rawError?.contains("DB_OWNED_BY_DEAD_SESSION") == true
    val humanizedDetail = rawError?.let(RuntimeFailureCopy::humanize) ?: snapshot.lastDurableAction

    // ------------------------------------------------------------------
    // D2/D9 (design-lan): the interface-loss stop reason. The monitor's
    // graceful stop records the raw marker; the Home card's stopped copy
    // renders the shared humanized text so a hosting session ended by an
    // interface change says WHY.
    // ------------------------------------------------------------------
    val lanStopReason by model.lanStopReason.collectAsState()
    val lanStopCopy = lanStopReason?.let(RuntimeFailureCopy::humanize)

    // for the join-instead affordance: joining while a local realm is up
    // saves-and-stops that realm first (DesktopAppModel's join preflight) —
    // which disconnects any guests in it. The same stop-with-guests consent
    // Save & exit got, at the identical teardown's second button.
    val startRealmThenJoin: () -> Unit = {
        busy = true
        scope.launch {
            model.startRealm(true)
            model.autoLoginIfEnabled()
            busy = false
        }
    }
    val requestJoinInstead: () -> Unit = {
        busy = true
        scope.launch {
            val players = model.lanPlayersOnline()
            busy = false
            if (players != null && players.realPlayers > 1) {
                pendingJoinInsteadPlayers = players.realPlayers
            } else {
                startRealmThenJoin()
            }
        }
    }
    val joinInsteadPlayers = pendingJoinInsteadPlayers
    if (joinInsteadPlayers != null) {
        AlertDialog(
            onDismissRequest = { pendingJoinInsteadPlayers = null },
            title = { Text("Join with guests in the world") },
            text = {
                Text(
                    "$joinInsteadPlayers players are in the world. Joining a LAN host " +
                        "saves and stops this realm first, disconnecting everyone in it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingJoinInsteadPlayers = null
                    startRealmThenJoin()
                }) { Text("Save, stop, and join") }
            },
            dismissButton = {
                TextButton(onClick = { pendingJoinInsteadPlayers = null }) { Text("Cancel") }
            },
        )
    }

    // ------------------------------------------------------------------
    // the Pause/Resume control. Two lanes, one button:
    //  - RUNNING/PAUSED ride the shared supervisor's companion mode (the
    //    journaled RUNNING→PAUSED phase machine, the Android maintenance
    //    op's exact phase guards);
    //  - WORLD_READY/CLIENT_FAILED (world up, phase not companion-capable)
    //    ride the backend's world pause primitive (pauseWorldNative + the
    //    "pause"/"resume" lifecycle records), gated on the world being up.
    // The paused flag resyncs from the native flag on every runtime change
    // and optimistically after a primitive toggle.
    // ------------------------------------------------------------------
    var worldPaused by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot) { worldPaused = model.worldPaused() }
    val worldUp = snapshot.phase == RuntimePhase.WORLD_READY ||
        snapshot.phase == RuntimePhase.RUNNING ||
        snapshot.phase == RuntimePhase.PAUSED ||
        snapshot.phase == RuntimePhase.CLIENT_FAILED
    val pausedNow = snapshot.phase == RuntimePhase.PAUSED || worldPaused
    var pauseError by remember { mutableStateOf<String?>(null) }

    // ------------------------------------------------------------------
    // the projected advertisement for the invite
    // line — the realmlist.address row the host start wrote, never
    // the stored lanAddress, so the card cannot promise an address the
    // realm no longer advertises. Read once per hosting phase change (the
    // same blocking-in-remember pattern as resolveClientDir above);
    // projection is a start contract, so the snapshot endpoint is the
    // honest fallback until/unless the row reads.
    //
    // Design-tunnel: the HOME SUBTITLE carries the advertised address
    // too — the settings-derived effective value (the tunnel address in
    // tunnel mode), shown beside the bind endpoint so the host can see the
    // advertise/bind split at a glance.
    // ------------------------------------------------------------------
    val hosting = settings.runtimeMode == RuntimeMode.LAN_HOST && worldUp
    val inviteAddress = remember(hosting, snapshot.sessionId) {
        if (!hosting) null
        else model.backend.projectedRealmAddress() ?: snapshot.realmEndpoint.address
    }
    val advertisedDisplay = remember(settings) { model.advertisedOrDefaultForDisplay() }
    val tunnelAdvertised = advertisedDisplay != null &&
        advertisedDisplay != snapshot.realmEndpoint.address
    fun togglePause() {
        busy = true
        scope.launch {
            val result = when {
                snapshot.phase == RuntimePhase.RUNNING -> {
                    model.setCompanionMode(true)
                    null
                }
                snapshot.phase == RuntimePhase.PAUSED -> {
                    model.setCompanionMode(false)
                    null
                }
                !pausedNow -> model.pauseWorld().also {
                    if (it.ok) worldPaused = true
                }
                else -> model.resumeWorld().also {
                    if (it.ok) worldPaused = false
                }
            }
            if (result != null && !result.ok) pauseError = result.detail
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The flavor switcher (the multi-flavor lift): the active lane drives
        // per-flavor import, prepared-data resolution and bot populations.
        var flavorChips by remember { mutableStateOf(ActiveFlavor.switcherChips()) }
        // the PERSISTED selection is the source
        // of truth across restarts — re-apply the persisted pick once the
        // settings snapshot arrives. Never overrides an explicit in-session
        // pick, and a persisted id that stopped being qualified stays
        // refused (select throws; the machine fails loud rather than
        // silently downgrading the lane) — the Android HomeScreen
        // LaunchedEffect twin.
        LaunchedEffect(settings) {
            val persisted = settings.selectedFlavorOrNull() ?: return@LaunchedEffect
            if (ActiveFlavor.selectedId == null) {
                runCatching { ActiveFlavor.select(persisted) }.onSuccess {
                    flavorChips = ActiveFlavor.switcherChips()
                }
            }
        }
        FlavorSwitcherCard(
            chips = flavorChips,
            onSelect = { chip ->
                ActiveFlavor.select(chip.game.flavorId)
                flavorChips = ActiveFlavor.switcherChips()
                // the pick must outlive this process — at the
                // multi-qualified flip a restart without the persisted key
                // lands every fail-loud resolver in a refusal until a
                // re-pick. The write rides the same tap (the Android twin's
                // settings.update leg).
                model.updateSettings { it.copy(selectedFlavorId = chip.game.flavorId) }
            },
        )
        RealmControlCard(
            phase = snapshot.phase,
            detail = humanizedDetail,
            forceStopAvailable = snapshot.phase == RuntimePhase.ERROR ||
                unverifiedOrphan || dbOwnedByDeadSession,
            mode = snapshot.runtimeMode,
            joinSelected = settings.runtimeMode == RuntimeMode.LAN_JOIN,
            endpointAddress = snapshot.realmEndpoint.address,
            busy = busy,
            onStartRealm = { includeClient ->
                busy = true
                scope.launch {
                    model.startRealm(includeClient)
                    if (includeClient) model.autoLoginIfEnabled()
                    busy = false
                }
            },
            onJoinInstead = requestJoinInstead,
            onStartGame = {
                busy = true
                scope.launch {
                    // The world is already up: launching the game is the
                    // supervisor's client relaunch verb (start() would
                    // refuse with "runtime is already active" and a world
                    // WRONG_STATE - one world lifetime per process).
                    model.relaunchClient()
                    model.autoLoginIfEnabled()
                    busy = false
                }
            },
            onSaveAndExit = {
                busy = true
                scope.launch {
                    // the stop-with-guests confirm —
                    // with real players beyond the host in the world,
                    // Save & exit becomes a two-step consent naming the
                    // guests the stop will disconnect.
                    val players = model.lanPlayersOnline()
                    if (players != null && players.realPlayers > 1) {
                        busy = false
                        pendingSaveExitPlayers = players.realPlayers
                        return@launch
                    }
                    model.saveAndExit()
                    busy = false
                }
            },
            onForceStop = { showForceStop = true },
            pauseAvailable = worldUp && !busy,
            pausedNow = pausedNow,
            onPauseToggle = ::togglePause,
            // read at recomposition — every runtime-phase change
            // re-renders the card with the current spent-lifetime verdict.
            spentLifetime = model.worldLifetimeSpent(),
            onRelaunchApp = {
                busy = true
                scope.launch {
                    // On success this session drains and the process exits
                    // inside the verb; only a refusal returns (the app
                    // stays open).
                    model.relaunchApp()
                    busy = false
                }
            },
            inviteAddress = inviteAddress,
            lanStopCopy = lanStopCopy?.takeIf { snapshot.phase == RuntimePhase.STOPPED },
            advertisedAddress = if (tunnelAdvertised) advertisedDisplay else null,
            // Design-timeframe: "where in time is my world" is always
            // answerable next to the realm status, without opening Settings.
            timeframeLine = remember(settings) {
                // the ActiveFlavor machine, never a silent
 // VANILLA fallback at the multi-qualified flip —
                // with several qualified flavors and no pick yet the
                // resolver refuses loud — a render-time lane must not crash
                // composition, so the unresolved state renders the honest
                // first-pick copy instead (the flavor cards above own the
                // pick; every OPERATION gate keeps the loud refusal).
                val flavorId = com.pocketrealm.client.ActiveFlavor.displayedId
                if (flavorId == null) {
                    "World timeframe: pick a game version above"
                } else {
                    val stageId = com.pocketrealm.client.TimeframeStages
                        .resolveStored(settings.timeframeStage)
                        .ifEmpty { com.pocketrealm.client.TimeframeStages.defaultStageId(flavorId) }
                    "World timeframe: " + requireNotNull(
                        com.pocketrealm.client.TimeframeStages.byId(flavorId, stageId),
                    ) { "resolved stage $stageId must be registered" }.title
                }
            },
        )
        // The Multiplayer entry points: the LAN host/join cards moved to
        // the Multiplayer screen (Route.Realms, label "Multiplayer"); Home
        // stays the LOCAL play path.
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = onOpenMultiplayerHost,
                        modifier = Modifier.testTag("home-host-online"),
                    ) { Text("Host online") }
                    Text(
                        "Open the Multiplayer screen's Host tab.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = onOpenMultiplayerFind,
                        modifier = Modifier.testTag("home-find-game"),
                    ) { Text("Find a game") }
                    Text(
                        "Open the game list.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        ActiveSetupCard(
            botsSelected = botsSelected,
            profileName = if (botsSelected) botProfile.displayName else null,
            target = if (botsSelected) botProfile.selectedTarget else 0,
            autoLogin = settings.autoLoginOnLaunch,
            storedAccountName = storedAccount?.username,
            clientDir = clientDir,
            clientSelection = clientSelection,
            activeGeneration = activeGeneration,
            onOpenBots = onOpenBots,
            onOpenSettings = onOpenSettings,
            onOpenImport = onOpenImport,
            onChooseClientDir = { dir ->
                model.updateSettings { it.copy(clientDir = dir.absolutePath) }
            },
        )
        AccountCard(
            model = model,
            worldReady = snapshot.phase == RuntimePhase.WORLD_READY ||
                snapshot.phase == RuntimePhase.RUNNING ||
                snapshot.phase == RuntimePhase.PAUSED,
            status = accountStatus,
            onStatus = { accountStatus = it },
            storedAccount = storedAccount,
        )
        DataPreparationCard(model)
    }

    if (showForceStop) {
        AlertDialog(
            onDismissRequest = { showForceStop = false },
            title = { Text("Force stop realm") },
            text = {
                Text(
                    "This force-stops the leftover world from the failed start. " +
                        "Unsaved progress in that world is lost; the realm databases are " +
                        "checked and repaired automatically on the next start.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showForceStop = false
                    busy = true
                    scope.launch {
                        model.consentedForceStopOrphanStack()
                        busy = false
                    }
                }) { Text("Force stop") }
            },
            dismissButton = { TextButton(onClick = { showForceStop = false }) { Text("Cancel") } },
        )
    }

    // the stop-with-guests confirm. The shutdown-hook
    // drain (logoff/power loss) cannot confirm — that is accepted and
    // documented: the hook prioritizes the world save, which is the right
    // order under power loss.
    val saveExitPlayers = pendingSaveExitPlayers
    if (saveExitPlayers != null) {
        AlertDialog(
            onDismissRequest = { pendingSaveExitPlayers = null },
            title = { Text("Save & exit with guests") },
            text = {
                Text(
                    "$saveExitPlayers players are in the world. Saving and exiting " +
                        "disconnects everyone and stops the realm.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingSaveExitPlayers = null
                    busy = true
                    scope.launch {
                        model.saveAndExit()
                        busy = false
                    }
                }) { Text("Save & exit everyone") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSaveExitPlayers = null }) { Text("Cancel") }
            },
        )
    }

    // an honest surface for a refused pause/resume (the
    // companion phase guard or the world primitive's rc).
    pauseError?.let { message ->
        AlertDialog(
            onDismissRequest = { pauseError = null },
            title = { Text("Pause/resume refused") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { pauseError = null }) { Text("OK") }
            },
        )
    }
}

/**
 * The flavor switcher (the multi-flavor lift): one chip per declared flavor
 * — vanilla / TBC / WotLK — over [ActiveFlavor]'s machine. Qualified chips
 * switch the active lane (per-flavor import, prepared-data resolution and
 * bot populations follow); declared-but-disabled flavors render present but
 * unselectable, honestly mirroring the registry (a qualification is a data
 * change, never a UI flag). The Android HomeScreen carries the twin card.
 */
@Composable
private fun FlavorSwitcherCard(
    chips: List<FlavorChip>,
    onSelect: (FlavorChip) -> Unit,
) {
    Card(modifier = Modifier.testTag("version-switcher")) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Game version", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { chip ->
                    AssistChip(
                        onClick = { onSelect(chip) },
                        enabled = chip.selectable,
                        label = { Text(chip.label) },
                        modifier = Modifier.testTag("version-chip-${chip.game.flavorId}"),
                    )
                }
            }
            Text(
                "Switching game versions re-targets imports, prepared world data and " +
                    "bot populations. A version lights up once its client support is finished.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList") // one action row per runtime phase
private fun RealmControlCard(
    phase: RuntimePhase,
    detail: String,
    forceStopAvailable: Boolean,
    mode: RuntimeMode,
    joinSelected: Boolean,
    endpointAddress: String,
    busy: Boolean,
    onStartRealm: (includeClient: Boolean) -> Unit,
    /** the join-instead affordance — probes the guests-in-world
     * consent before tearing a hosted realm down (the outer scope owns the
     * probe and the dialog; this card only renders the buttons). */
    onJoinInstead: () -> Unit,
    onStartGame: () -> Unit,
    onSaveAndExit: () -> Unit,
    onForceStop: () -> Unit,
    pauseAvailable: Boolean,
    pausedNow: Boolean,
    onPauseToggle: () -> Unit,
    spentLifetime: Boolean,
    onRelaunchApp: () -> Unit,
    inviteAddress: String?,
    lanStopCopy: String?,
    /** Design-tunnel: the advertised address when tunnel advertising is
     * active (differs from the bind endpoint) — the subtitle names it, per
     * "the Home hosting subtitle displays the live advertised address". */
    advertisedAddress: String?,
    /** Design-timeframe: the active stage line next to the realm status. */
    timeframeLine: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val lanHost = mode == RuntimeMode.LAN_HOST
            val (title, subtitle) = when (phase) {
                RuntimePhase.STOPPED, RuntimePhase.UNCONFIGURED ->
                    (if (mode == RuntimeMode.LAN_JOIN) "Ready to join" else "Ready to start") to
                        when (mode) {
                            RuntimeMode.LAN_JOIN -> "Stopped. Joining launches your game against the LAN host's realm."
                            RuntimeMode.LAN_HOST -> "Stopped. The next start hosts the realm on the LAN."
                            else -> "Your local realm is stopped and safely saved."
                        }
                RuntimePhase.PREPARING, RuntimePhase.DB_STARTING, RuntimePhase.REALM_STARTING,
                RuntimePhase.WORLD_STARTING, RuntimePhase.CLIENT_STARTING, RuntimePhase.RECOVERING ->
                    "Working" to "Preparing the realm (${
                        phase.name.lowercase().replace('_', ' ')
                    })."
                RuntimePhase.WORLD_READY ->
                    "Realm online" to when {
                        lanHost && advertisedAddress != null && isLoopbackAdvertisement(advertisedAddress) ->
                            "The world is hosted behind your private network bridge; the Host card " +
                                "under Advanced carries the setup steps each friend follows " +
                                "(each player's own machine is the address)."
                        lanHost && advertisedAddress != null ->
                            "The world is hosted at $advertisedAddress; friends join through " +
                                "your private bridge — the realm address only resolves inside it."
                        lanHost -> "The world is hosted on $endpointAddress; LAN players can join."
                        else -> "The world and loopback-only services are ready."
                    }
                RuntimePhase.RUNNING ->
                    "Realm online" to when {
                        lanHost && advertisedAddress != null && isLoopbackAdvertisement(advertisedAddress) ->
                            "The world is hosted behind your private bridge and your game " +
                                "client is up — friends join through their own local forwarder."
                        lanHost && advertisedAddress != null ->
                            "The world is hosted at $advertisedAddress and your game client is " +
                                "up — friends join through the bridge."
                        lanHost -> "The world is hosted on $endpointAddress and your game client is up."
                        else -> "The world and your game client are up."
                    }
                RuntimePhase.PAUSED -> "Realm paused" to "The world is paused for a sit-and-talk session."
                RuntimePhase.CLIENT_FAILED -> "Realm online; game needs retry" to
                    "The game client failed to start or exited unexpectedly."
                RuntimePhase.STOPPING -> "Stopping" to "Closing the client and local services safely."
                RuntimePhase.ERROR -> "Needs attention" to detail
            }
            Text("Realm", style = MaterialTheme.typography.labelMedium)
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            // Design-tunnel disclosure: a tunnel/loopback-advertised
            // session binds the WILDCARD (0.0.0.0) so the advertised address
            // is reachable — which also means the game ports listen on every
            // local interface, the local LAN included. The host gets that
            // stated, not just the tunnel-only story (realmd's login lockout
            // and the world server's authenticated session bound the added
            // exposure; the tunnel alone is not a wall around the ports).
            if (lanHost && advertisedAddress != null && advertisedAddress != endpointAddress &&
                (phase == RuntimePhase.WORLD_READY || phase == RuntimePhase.RUNNING)
            ) {
                Text(
                    "While the private bridge is advertised, the game's connections also listen on this " +
                        "PC's other networks (your local LAN included) — the bridge is " +
                        "not a wall around them.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Design-timeframe: the active stage next to the realm status.
            Text(timeframeLine, style = MaterialTheme.typography.bodySmall)
            // when hosting stopped because the LAN interface was
            // lost, the stopped card says why (the shared humanized copy).
            if (phase == RuntimePhase.STOPPED && lanStopCopy != null) {
                Text(lanStopCopy, style = MaterialTheme.typography.bodySmall)
            }
            // the invite — the PROJECTED address with
            // a one-click copy (the literal alone; the fixed ports spelled
            // out for support conversations), plus the dormant tunnel
            // warning row for a projected non-private address.
            val worldUpPhase = phase == RuntimePhase.WORLD_READY || phase == RuntimePhase.RUNNING
            if (lanHost && inviteAddress != null && worldUpPhase) {
                InviteAddressRow(inviteAddress)
            }
            // the single Pause/Resume control — companion mode
            // (phase machine) at RUNNING/PAUSED, the world pause primitive at
            // the other world-up phases; label from the live pause flag.
            if (pauseAvailable) {
                OutlinedButton(enabled = !busy, onClick = onPauseToggle) {
                    Text(if (pausedNow) "Resume world" else "Pause world")
                }
            }
            when (phase) {
                RuntimePhase.STOPPED, RuntimePhase.UNCONFIGURED, RuntimePhase.ERROR -> {
                    if (mode == RuntimeMode.LAN_JOIN) {
                        // LAN join is client-only by contract (the shared
                        // launch spec forces its client): one join button.
                        Button(enabled = !busy, onClick = { onStartRealm(true) }) {
                            Text(if (busy) "Joining…" else "Join LAN game")
                        }
                        Text(
                            "The LAN host's realm must already be running (its world up).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (spentLifetime) {
                        // the world ran once in this app session —
                        // the pinned one-lifetime contract refuses any second
                        // in-process start (WRONG_STATE), so the honest
                        // action is the app relaunch, not a Start that can
                        // only fail. (LAN join above is client-only and
                        // unaffected by the spent local lifetime.)
                        Text(
                            "The world ran once in this app session; a realm " +
                                "restart needs a fresh app process. Your progress " +
                                "is already saved.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(enabled = !busy, onClick = onRelaunchApp) {
                            Text(if (busy) "Restarting…" else "Restart realm (relaunch app)")
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy, onClick = { onStartRealm(false) }) {
                                Text(if (busy) "Starting realm…" else "Start realm")
                            }
                            OutlinedButton(enabled = !busy, onClick = { onStartRealm(true) }) {
                                Text("Realm + game")
                            }
                        }
                        Text(
                            if (mode == RuntimeMode.LAN_HOST) {
                                "Start hosts the realm on your LAN address (Settings → LAN play)."
                            } else {
                                "Start the realm, create your account, then start the game."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // the repair affordance highlights for the two
                    // wedge states (UNVERIFIED_ORPHAN / DB_OWNED_BY_DEAD_SESSION)
                    // even when the phase has not settled to ERROR yet.
                    if (forceStopAvailable) {
                        OutlinedButton(onClick = onForceStop) { Text("Force stop realm") }
                    }
                }
                RuntimePhase.WORLD_READY -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy, onClick = onStartGame) {
                                Text(if (busy) "Starting game…" else "Start game")
                            }
                            OutlinedButton(enabled = !busy, onClick = onSaveAndExit) {
                                Text("Save & exit")
                            }
                        }
                        if (joinSelected) {
                            // joining stops the local realm (graceful,
                            // with the world save) and launches the game
                            // against the remote host.
                            OutlinedButton(enabled = !busy, onClick = onJoinInstead) {
                                Text(if (busy) "Joining…" else "Join LAN host instead")
                            }
                        }
                    }
                }
                RuntimePhase.CLIENT_FAILED -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy, onClick = onStartGame) {
                                Text(if (busy) "Retrying game…" else "Retry game")
                            }
                            OutlinedButton(enabled = !busy, onClick = onSaveAndExit) {
                                Text("Save & exit")
                            }
                        }
                        if (joinSelected) {
                            OutlinedButton(enabled = !busy, onClick = onJoinInstead) {
                                Text(if (busy) "Joining…" else "Join LAN host instead")
                            }
                        }
                    }
                }
                RuntimePhase.RUNNING, RuntimePhase.PAUSED -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = onSaveAndExit) {
                            Text(if (busy) "Saving…" else "Save & exit")
                        }
                        if (joinSelected) {
                            OutlinedButton(enabled = !busy, onClick = onJoinInstead) {
                                Text(if (busy) "Joining…" else "Join LAN host instead")
                            }
                        }
                    }
                }
                else -> OutlinedButton(enabled = false, onClick = {}) { Text("Working…") }
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun ActiveSetupCard(
    botsSelected: Boolean,
    profileName: String?,
    target: Int,
    autoLogin: Boolean,
    storedAccountName: String?,
    clientDir: File?,
    clientSelection: DesktopAppModel.ClientSelectionState,
    activeGeneration: DesktopAppModel.ClientGenerationStatus?,
    onOpenBots: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenImport: () -> Unit,
    onChooseClientDir: (File) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Active setup", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenBots) { Text("Bots") }
                    OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
                }
            }
            Text(
                if (botsSelected && profileName != null) {
                    "$profileName · $target bots"
                } else {
                    "No bot population selected — the realm starts with bots off (pick a profile in Bots)."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Game client: " + when {
                    activeGeneration != null && activeGeneration.broken == null ->
                        "managed generation ${activeGeneration.generation.take(8)}… " +
                            "(build ${activeGeneration.version ?: activeGeneration.clientId})"
                    activeGeneration != null -> "managed generation failed verification"
                    clientDir != null -> "ready (${clientDir.absolutePath})"
                    else -> "not found — choose the folder that contains WoW.exe"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            // the client-selection affordance is ALWAYS available —
            // never only while no folder resolves — and once managed
            // generations exist it becomes the generation switcher:
            // "Import client…" publishes a new generation and re-points the
            // active pointer.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClientDirPicker(onChooseClientDir, clientSelection != DesktopAppModel.ClientSelectionState.NONE)
                OutlinedButton(onClick = onOpenImport) { Text("Import client…") }
            }
            Text(
                if (autoLogin) {
                    if (storedAccountName != null) {
                        "Auto-login: $storedAccountName"
                    } else {
                        "Auto-login waiting for account"
                    }
                } else {
                    "Manual login"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ClientDirPicker(onChoose: (File) -> Unit, clientReady: Boolean) {
    // java.awt.FileDialog blocks its OWN thread until dismissed; compose on
    // the UI thread would freeze, so the dialog opens on a worker and posts
    // the chosen folder back through state.
    var choosing by remember { mutableStateOf(false) }
    OutlinedButton(enabled = !choosing, onClick = {
        choosing = true
        Thread {
            val frame = java.awt.Window.getWindows().firstOrNull { it is java.awt.Frame } as? java.awt.Frame
            val dialog = java.awt.FileDialog(frame, "Choose the folder that contains WoW.exe")
            dialog.mode = java.awt.FileDialog.LOAD
            dialog.file = "WoW.exe"
            dialog.isVisible = true // blocks this worker thread until dismissed
            val dir = dialog.directory
            if (dir != null && File(dir, "WoW.exe").isFile) {
                onChoose(File(dir))
            }
            choosing = false
        }.apply { isDaemon = true }.start()
    }) { Text(if (choosing) "Choosing…" else if (clientReady) "Change game folder…" else "Choose game folder…") }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber") // gmLevel 3 = realm administrator
private fun AccountCard(
    model: DesktopAppModel,
    worldReady: Boolean,
    status: String?,
    onStatus: (String?) -> Unit,
    storedAccount: UserAccountStore.UserAccount?,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var gmAccount by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // the per-field validate-and-explain legs of the shared
    // AccountStatusPresentation — the same rule the provision path
    // enforces, so the form can never disagree with it. An empty draft is
    // never red (nothing submitted yet); only a malformed draft is.
    val usernameError = accountCredentialFieldError(username)
    val passwordError = accountCredentialFieldError(password)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Local account", style = MaterialTheme.typography.titleMedium)
                if (storedAccount != null) {
                    OutlinedButton(onClick = {
                        model.clearAccount()
                        onStatus("Saved account removed")
                    }) { Text("Clear") }
                }
            }
            if (storedAccount != null) {
                Text("Saved: ${storedAccount.username}", style = MaterialTheme.typography.bodyMedium)
            }
            // validate-and-explain, not input mangling — the raw
            // keystrokes land in the state (16-char cap only); a value the
            // realm rule rejects shows the shared plain-copy reason right
            // at the field instead of being silently deleted.
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.take(CREDENTIAL_MAX_CHARS) },
                label = { Text("Account name") },
                isError = usernameError != null,
                supportingText = {
                    Text(usernameError ?: "1–16 letters or numbers")
                },
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(CREDENTIAL_MAX_CHARS) },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                isError = passwordError != null,
                supportingText = {
                    Text(
                        passwordError
                            ?: "1–16 letters or numbers, stored uppercase — any case works when you log in",
                    )
                },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = gmAccount, onCheckedChange = { gmAccount = it })
                Column {
                    Text(if (gmAccount) "Administrator" else "Player", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (gmAccount) {
                            "Administrator accounts can use privileged realm commands; " +
                                "choose this only for local maintenance."
                        } else {
                            "Player accounts have normal gameplay permissions and are the recommended choice."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Button(
                // the gate stays on the realm rule via the shared
                // presentation helper (world-readiness gates ONLY the
                // create; the pending disable is load-bearing).
                enabled = accountCreateEnabled(
                    realmReady = worldReady,
                    accountOperationPending = pending,
                    username = username,
                    password = password,
                ),
                onClick = {
                    pending = true
                    onStatus("Creating through the core control channel…")
                    scope.launch {
                        val result = model.createAccount(username, password, if (gmAccount) 3 else 0)
                        onStatus(
                            when {
                                result.ok && result.code == "ACCOUNT_CREATED" ->
                                    "Account ${result.accountId} created; auto-login saved"
                                result.ok -> "Existing account verified; auto-login saved"
                                // the shared presentation maps
                                // the stable machine codes to player copy —
                                // the raw code/detail stay in diagnostics/logs.
                                else -> accountProvisionFailureMessage(result.code, result.detail)
                            },
                        )
                        pending = false
                    }
                },
            ) { Text(if (pending) "Creating…" else "Create & remember") }
            Text(
                status ?: if (worldReady) {
                    "Create a local account after the world is ready"
                } else {
                    "Start the realm before creating a new local account."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The account credential cap (the realm rule's 16); the field keeps raw
 * keystrokes up to this length — DESK-136 deleted the character-class
 * mangling that silently discarded what the user typed. */
private const val CREDENTIAL_MAX_CHARS = 16

/**
 * the invite line — the projected address as the
 * plain literal with a one-click Copy address (the copy button is for
 * typing convenience at the same machine; the ports are fixed so the
 * invite is the literal alone), a second line spelling out the two ports
 * for support conversations, and the dormant tunnel warning row for a
 * projected non-private address (cannot fire before the tunnel lane's
 * advertise/bind split exists — specified now so that design inherits the
 * defined behavior).
 */
@Composable
private fun InviteAddressRow(address: String) {
    var copied by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Invite: $address", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = {
                runCatching {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(java.awt.datatransfer.StringSelection(address), null)
                }.onSuccess { copied = true }
            }) { Text(if (copied) "Copied" else "Copy address") }
        }
        Text(
            "Give friends this address; both of its numbers are fixed and must match.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (runCatching { RealmEndpoint.parseLan(address) }.isFailure) {
            Text(
                if (isLoopbackAdvertisement(address)) {
                    "Your realm advertises 127.0.0.1 (the bridge recipe): each player " +
                        "reaches it through their own local forwarder — " +
                        "a LAN guest dialing this address reaches their own machine."
                } else {
                    "Your realm advertises a bridge address; players on your physical LAN " +
                        "(outside the bridge) will see the realm but cannot reach it."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * D7's budget line: "Bots: <effectiveTarget> (admission <lowered: reason |
 * steady>) · Players: N (you + M guests)". The bots half keys off the live
 * admission verdict (null = no measured profile — bots off); the players
 * half off the world's real-player count (falling back to the world's
 * active-session count when the host plays clientless).
 */
// internal (not private) so the bot-labeling pin reaches it
internal fun budgetLine(
    verdict: DesktopBotAdmissionVerdict?,
    players: DesktopAppModel.LanPlayersOnline?,
): String {
    val bots = when {
        verdict == null -> "Bots: off"
        verdict.adapted -> "Bots: ${verdict.effectiveTarget} (admission lowered: ${verdict.reason})"
        else -> "Bots: ${verdict.effectiveTarget} (admission steady)"
    }
    // bots labeled, never padded (the android twin's line).
    val people = when {
        players == null -> "Players: the world has not reported yet"
        players.realPlayers > 0 -> "${players.realPlayers} players in world (you + ${players.guests} guests)"
        players.playerbotsEnabled && players.activeSessions > 0 ->
            "No players in world — ${players.activeSessions} bot session" +
                (if (players.activeSessions == 1L) "" else "s") + " running"
        players.activeSessions > 0 -> "${players.activeSessions} players in world"
        else -> "No players in world"
    }
    return "$bots · $people"
}

/** True when the displayed advertisement is the loopback (cloudflared)
 * recipe: its address model is each player's OWN forwarder target, so the
 * tunnel-membership copy written for CGNAT advertisements would be false
 * here. */
private fun isLoopbackAdvertisement(value: String): Boolean =
    runCatching { com.pocketrealm.supervisor.AdvertisedAddress.parse(value) }
        .getOrNull()?.isLoopback == true
