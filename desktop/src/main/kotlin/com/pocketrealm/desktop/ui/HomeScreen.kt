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
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.TunnelState
import com.pocketrealm.desktop.realmstatus.RealmStatusVisibility
import com.pocketrealm.supervisor.FriendInviteStore
import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.RuntimeFailureCopy
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.supervisor.UserAccountStore
import com.pocketrealm.ui.accountCreateEnabled
import com.pocketrealm.ui.accountCredentialFieldError
import com.pocketrealm.ui.accountProvisionFailureMessage
import com.pocketrealm.ui.joinerRefusalCopy
import java.io.File
import kotlinx.coroutines.delay
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
            // DESK-118: the real launcher-level advanced override — the Home
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

    // DESK-137: the decode boundary — the two wedge flags are derived on
    // the RAW detail exactly like the Android RuntimeSupervisorClient, the
    // UI then renders human copy, and the raw string keeps flowing only to
    // the app log (DesktopAppModel logs it verbatim).
    val rawError = snapshot.lastError
    val unverifiedOrphan = rawError?.contains("UNVERIFIED_ORPHAN") == true
    val dbOwnedByDeadSession = rawError?.contains("DB_OWNED_BY_DEAD_SESSION") == true
    val humanizedDetail = rawError?.let(RuntimeFailureCopy::humanize) ?: snapshot.lastDurableAction

    // ------------------------------------------------------------------
    // D2/D9 (design-lan §7): the interface-loss stop reason. The monitor's
    // graceful stop records the raw marker; the Home card's stopped copy
    // renders the shared humanized text so a hosting session ended by an
    // interface change says WHY.
    // ------------------------------------------------------------------
    val lanStopReason by model.lanStopReason.collectAsState()
    val lanStopCopy = lanStopReason?.let(RuntimeFailureCopy::humanize)

    // D8 for the join-instead affordance: joining while a local realm is up
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
    // DESK-117/122: the Pause/Resume control. Two lanes, one button:
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
    // D4 (design-lan §3.1): the projected advertisement for the invite
    // line — the realmlist.address row the host start wrote (§2.2), never
    // the stored lanAddress, so the card cannot promise an address the
    // realm no longer advertises. Read once per hosting phase change (the
    // same blocking-in-remember pattern as resolveClientDir above);
    // projection is a start contract, so the snapshot endpoint is the
    // honest fallback until/unless the row reads.
    //
    // Design-tunnel §5: the HOME SUBTITLE carries the advertised address
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
        // R1-1 (desktop-twin round 1): the PERSISTED selection is the source
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
                ActiveFlavor.select(chip.flavor.flavorId)
                flavorChips = ActiveFlavor.switcherChips()
                // R1-1: the pick must outlive this process — at the W4
                // multi-qualified flip a restart without the persisted key
                // lands every fail-loud resolver in a refusal until a
                // re-pick. The write rides the same tap (the Android twin's
                // settings.update leg).
                model.updateSettings { it.copy(selectedFlavorId = chip.flavor.flavorId) }
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
                    // D8 (design-lan §7): the stop-with-guests confirm —
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
            // DESK-25: read at recomposition — every runtime-phase change
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
            // Design-timeframe §6: "where in time is my world" is always
            // answerable next to the realm status, without opening Settings.
            timeframeLine = remember(settings) {
                // Round-5 R5-2: the ActiveFlavor machine, never a silent
                // VANILLA fallback at the W4 multi-qualified flip. W4 flip:
                // with several qualified flavors and no pick yet the
                // resolver refuses loud — a render-time lane must not crash
                // composition, so the unresolved state renders the honest
                // first-pick copy instead (the flavor cards above own the
                // pick; every OPERATION gate keeps the loud refusal).
                val flavorId = com.pocketrealm.client.ActiveFlavor.displayedId
                if (flavorId == null) {
                    "World timeframe: pick a game flavor above"
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
        // Design-tunnel §4.1/§6/§9.4: the host-over-the-internet card —
        // tunnel detection, the honest reachability line, and the §6
        // invite block (address only). LAN_HOST only: the same start
        // serves both lanes.
        if (settings.runtimeMode == RuntimeMode.LAN_HOST) {
            InternetHostCard(model)
            // Design realm-status-browser.md: the read-only realm-status
            // page's hosting card — the visibility switch (fail-closed
            // OFF default), the MOTD, and the PUBLIC showcase's tunnel
            // surface. LAN_HOST only: the page rides the game's own lane.
            RealmStatusCard(model)
        }
        if (hosting) {
            GuestsCard(model)
            // design-remote-accounts §7: the compact "N pending requests"
            // affordance + invite entry point on the Home column while
            // hosting; the full surface lives in Route.Players.
            FriendAccessSummaryCard(model, onOpenPlayers)
        }
        if (settings.runtimeMode == RuntimeMode.LAN_JOIN) {
            // design-remote-accounts §3: the friend-side request form — the
            // desktop join card is one of the design's request surfaces.
            JoinRequestAccessCard(model)
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

    // D8 (design-lan §7): the stop-with-guests confirm. The shutdown-hook
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

    // DESK-117/122: an honest surface for a refused pause/resume (the
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
    Card(modifier = Modifier.testTag("flavor-switcher")) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Game flavor", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { chip ->
                    AssistChip(
                        onClick = { onSelect(chip) },
                        enabled = chip.selectable,
                        label = { Text(chip.label) },
                        modifier = Modifier.testTag("flavor-chip-${chip.flavor.flavorId}"),
                    )
                }
            }
            Text(
                "Switching the flavor re-targets imports, prepared world data and " +
                    "bot populations on that lane. Unqualified flavors stay off until " +
                    "their client is qualified.",
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
    /** D8: the join-instead affordance — probes the guests-in-world
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
    /** Design-tunnel §5: the advertised address when tunnel advertising is
     * active (differs from the bind endpoint) — the subtitle names it, per
     * "the Home hosting subtitle displays the live advertised address". */
    advertisedAddress: String?,
    /** Design-timeframe §6: the active stage line next to the realm status. */
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
                            "The world is hosted behind your cloudflared tunnel: each friend runs " +
                                "their own `cloudflared access tcp` forwarder and points the client " +
                                "at 127.0.0.1 — the address itself is each player's own machine."
                        lanHost && advertisedAddress != null ->
                            "The world is hosted at $advertisedAddress; friends join through " +
                                "your tunnel network — the realm address only resolves inside it."
                        lanHost -> "The world is hosted on $endpointAddress; LAN players can join."
                        else -> "The world and loopback-only services are ready."
                    }
                RuntimePhase.RUNNING ->
                    "Realm online" to when {
                        lanHost && advertisedAddress != null && isLoopbackAdvertisement(advertisedAddress) ->
                            "The world is hosted behind your cloudflared tunnel and your game " +
                                "client is up — friends join through their own local forwarder."
                        lanHost && advertisedAddress != null ->
                            "The world is hosted at $advertisedAddress and your game client is " +
                                "up — friends join through the tunnel."
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
            // Design-tunnel §4.4.2 disclosure: a tunnel/loopback-advertised
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
                    "While tunnel advertising is on, the game ports also listen on this " +
                        "PC's other networks (your local LAN included) — the tunnel is " +
                        "not a wall around them.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Design-timeframe §6: the active stage next to the realm status.
            Text(timeframeLine, style = MaterialTheme.typography.bodySmall)
            // D2/D9: when hosting stopped because the LAN interface was
            // lost, the stopped card says why (the shared humanized copy).
            if (phase == RuntimePhase.STOPPED && lanStopCopy != null) {
                Text(lanStopCopy, style = MaterialTheme.typography.bodySmall)
            }
            // D4 (design-lan §3.1): the invite — the PROJECTED address with
            // a one-click copy (the literal alone; the fixed ports spelled
            // out for support conversations), plus the dormant tunnel
            // warning row for a projected non-private address.
            val worldUpPhase = phase == RuntimePhase.WORLD_READY || phase == RuntimePhase.RUNNING
            if (lanHost && inviteAddress != null && worldUpPhase) {
                InviteAddressRow(inviteAddress)
            }
            // DESK-117/122: the single Pause/Resume control — companion mode
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
                        // DESK-25: the world ran once in this app session —
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
                    // DESK-137: the repair affordance highlights for the two
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
                            // DESK-24: joining stops the local realm (graceful,
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
            // DESK-135: the client-selection affordance is ALWAYS available —
            // never only while no folder resolves — and once managed
            // generations exist (DESK-97) it becomes the generation switcher:
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
    // DESK-136: the per-field validate-and-explain legs of the shared
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
            // DESK-136: validate-and-explain, not input mangling — the raw
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
                // DESK-136: the gate stays on the realm rule via the shared
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
                                // DESK-120/125: the shared presentation maps
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
 * D4 (design-lan §3.1): the invite line — the projected address as the
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
            "TCP 3724 + 8085 — give friends this address; the ports are fixed.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (runCatching { RealmEndpoint.parseLan(address) }.isFailure) {
            Text(
                if (isLoopbackAdvertisement(address)) {
                    "Your realm advertises 127.0.0.1 (the cloudflared recipe): each player " +
                        "reaches it through their own `cloudflared access tcp` forwarder — " +
                        "a LAN guest dialing this address reaches their own machine."
                } else {
                    "Your realm advertises a tunnel address; players on your physical LAN " +
                        "(outside the tunnel) will see the realm but cannot reach it."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Design-tunnel §4.1/§6/§9.4: the host-over-the-internet card. One card,
 * one copy button, rendered from live state: the tunnel-adapter detection
 * (the §4.4.4 allowlist lane) with Tailscale-first guidance, the honest
 * reachability line (bind vs advertised, plus the §9.4 caveat that the app
 * cannot probe its own NAT reachability), and the §6 invite block —
 * ADDRESS BLOCK ONLY (the credential is the Guests card's separate
 * in-person handoff, never invite text). Rendered from the model's
 * settings-derived effective advertisement, so the block always matches
 * what the next start would project.
 */
@Composable
private fun InternetHostCard(model: DesktopAppModel) {
    var copied by remember { mutableStateOf(false) }
    val snapshot by model.state.collectAsState()
    val advertised = model.advertisedOrDefaultForDisplay()
    val invite = remember(advertised, snapshot.realmEndpoint) {
        model.tunnelInviteBlock()
    }
    val tunnelAdapters = remember {
        runCatching { com.pocketrealm.supervisor.TunnelInterfacePolicy.available() }
            .getOrDefault(emptyList())
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Host over the internet", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    tunnelAdapters.isNotEmpty() ->
                        "Tunnel adapter detected: ${tunnelAdapters.first().value} " +
                            "(${tunnelAdapters.size} total). Set it in Settings → LAN play " +
                            "(Advertise over the internet) to host for friends outside your LAN."
                    else ->
                        "No Tailscale or ZeroTier adapter detected. Install Tailscale " +
                            "(tailscale.com) on this PC and on each friend's PC and join the " +
                            "same tailnet — or use ZeroTier. The realm needs exactly two TCP " +
                            "ports (3724 + 8085), which every personal tunnel carries."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            // §4.1 seat-limit honesty: Tailscale's free tier is account-limited
            // well below the realm's player cap — a host walking this lane
            // should know before ten friends accept.
            Text(
                "Plan note: Tailscale's free tier allows 3 users / 100 devices per " +
                    "tailnet — a full realm of friends can exceed it. Check your plan " +
                    "(or use ZeroTier) before inviting.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (advertised != null) {
                // §9.4: the reachability line — present tense ONLY while a
                // world is actually up. While stopped nothing listens and the
                // endpoint is the configured/last-spec value, so the line
                // states the configuration, not a live fact. The mode label
                // compares against the CONFIGURED LAN address (not the live
                // snapshot endpoint, which while stopped is the default) so a
                // plain-LAN host is never labeled "(tunnel mode)".
                val settingsState = model.settings.collectAsState().value
                val labelTunnel = advertised != settingsState.lanAddress
                val worldUp = snapshot.phase == RuntimePhase.WORLD_READY ||
                    snapshot.phase == RuntimePhase.RUNNING ||
                    snapshot.phase == RuntimePhase.PAUSED ||
                    snapshot.phase == RuntimePhase.CLIENT_FAILED
                Text(
                    if (worldUp) {
                        "Reachability: listening on ${snapshot.realmEndpoint.address}, " +
                            "advertised as $advertised" +
                            if (labelTunnel) " (tunnel mode)." else " (LAN mode)."
                    } else {
                        "Not hosting right now. Configured advertisement: $advertised" +
                            if (labelTunnel) " (tunnel mode)." else " (LAN mode)."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "The app cannot probe its own reachability from outside — a friend's " +
                        "first connection is the real test.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!labelTunnel) {
                    Text(
                        "The invite below carries your LAN address (only devices on your " +
                            "network can use it). Set a tunnel address in Settings to host " +
                            "over the internet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = {
                        runCatching {
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(java.awt.datatransfer.StringSelection(invite), null)
                        }.onSuccess { copied = true }
                    }) { Text(if (copied) "Invite copied" else "Copy invite") }
                }
                Text(
                    "The invite carries the realm address and the client build only — " +
                        "never a password. Create each friend's account on the Guests card " +
                        "and hand the password over separately (in person or a private message).",
                    style = MaterialTheme.typography.bodySmall,
                )
                // §2 encryption honesty: the protocol authenticates but does
                // not conceal — the tunnel is the only confidentiality layer.
                Text(
                    "Encryption honesty: game payloads are NOT encrypted. The tunnel is " +
                        "the only confidentiality layer — anyone in your tunnel network " +
                        "(or LAN) can read world chat and game traffic; accounts and " +
                        "passwords use SRP verification and never cross the wire, but " +
                        "gameplay itself is in the clear.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Design realm-status-browser.md: the realm-status page's hosting card —
 * the three-state visibility switch (fail-closed OFF default, §4), the
 * MOTD editor (clamped at the settings write, §6.3), the roster consent
 * toggle (§3.4), the §5.3 orphan notice (acknowledge/adopt), and the
 * PUBLIC showcase's shape-gated link + honest posture/failure copy (§8.7).
 * The §3.5 consent copy enumerates the published set before the PUBLIC
 * flip (the trim list the payload actually carries).
 */
@Composable
@Suppress("LongMethod") // the card's switch + consent + link blocks render together by design
private fun RealmStatusCard(model: DesktopAppModel) {
    val scope = rememberCoroutineScope()
    val ui by model.realmStatus.uiState.collectAsState()
    val settings by model.settings.collectAsState()
    var motdDraft by remember(settings.realmDescription) {
        mutableStateOf(settings.realmDescription)
    }
    var linkCopied by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Realm status page", style = MaterialTheme.typography.titleMedium)
            Text(
                "A read-only status page for this realm. OFF (the default) runs nothing. " +
                    "Friends serves it on your network; Public adds a disposable shareable " +
                    "link (HTTP only — the game itself never uses quick tunnels).",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RealmVisibilityButton(
                    label = "Off",
                    selected = ui.visibility == RealmStatusVisibility.OFF,
                    onClick = {
                        scope.launch { model.realmStatus.setVisibility(RealmStatusVisibility.OFF) }
                    },
                )
                RealmVisibilityButton(
                    label = "Friends",
                    selected = ui.visibility == RealmStatusVisibility.FRIENDS,
                    onClick = {
                        scope.launch { model.realmStatus.setVisibility(RealmStatusVisibility.FRIENDS) }
                    },
                )
                RealmVisibilityButton(
                    label = "Public",
                    selected = ui.visibility == RealmStatusVisibility.PUBLIC,
                    onClick = {
                        scope.launch { model.realmStatus.setVisibility(RealmStatusVisibility.PUBLIC) }
                    },
                )
            }
            ui.refusalCopy?.let { copy ->
                Text(copy, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (ui.publicArmed) {
                Text(
                    "Your Public showcase was on — enable it again to start a fresh link " +
                        "(a restart never re-opens a public tunnel on its own).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ui.orphanPid?.let { pid ->
                Text(
                    "Orphaned page tunnel detected — pid $pid, not started by this app. " +
                        "It can serve this page through an old public link. Stop it " +
                        "yourself, or adopt it here so the app can manage it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.realmStatus.acknowledgeOrphan() }) {
                        Text("Acknowledge")
                    }
                    OutlinedButton(onClick = { model.realmStatus.adoptOrphan() }) {
                        Text("Adopt")
                    }
                }
            }

            OutlinedTextField(
                value = motdDraft,
                onValueChange = { motdDraft = it },
                label = { Text("Realm description (shown on the page)") },
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text("${motdDraft.length}/280 characters (control characters are stripped)")
                },
            )
            OutlinedButton(onClick = { model.realmStatus.setRealmDescription(motdDraft) }) {
                Text("Save description")
            }

            val public = ui.visibility == RealmStatusVisibility.PUBLIC
            if (public || ui.visibility == RealmStatusVisibility.FRIENDS) {
                // §3.5: the consent copy enumerates the host-relevant
                // published set VERBATIM — and it renders BEFORE the flip
                // (a host reads exactly what enabling Public publishes),
                // staying up after the flip for reference.
                Text(
                    "Enabling the Public showcase publishes: your realm name; this " +
                        "description; the flavor; the patch-timeframe stage (id, title, " +
                        "and whether it is the final stage); a minute-coarse render " +
                        "timestamp; minute-coarse uptime and longest-session figures; " +
                        "the rate multipliers with their source label; player counts " +
                        "(humans / bots labeled / total, plus character names ONLY if " +
                        "you toggle names below); the world-data prepared flag and " +
                        "counts summary; a coarse online/offline flag; the read-only " +
                        "join notice; and the public link itself. Everything else on " +
                        "the page is page posture, not information about your realm " +
                        "or this computer. It never carries IP or join addresses, " +
                        "the supervisor's phase or mode, the data generation id, the " +
                        "configured bot target, wall-clock timestamps, passwords, or " +
                        "character names unless you toggle them on. The link is " +
                        "unguessable but not secret — share it onward freely; it " +
                        "changes each time you re-enable it; Cloudflare terminates " +
                        "its TLS; and it stays live until you turn the Public " +
                        "showcase off or the app stops hosting it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (public) {
                val tunnel = ui.tunnel
                if (tunnel.state == TunnelState.LIVE && tunnel.url != null) {
                    Text("Public link: ${tunnel.url}")
                    OutlinedButton(onClick = {
                        runCatching {
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(java.awt.datatransfer.StringSelection(tunnel.url), null)
                        }.onSuccess { linkCopied = true }
                    }) { Text(if (linkCopied) "Link copied" else "Copy link") }
                } else {
                    tunnel.failureCopy?.let { failure ->
                        Text(
                            failure,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    when (tunnel.state) {
                        TunnelState.STARTING -> Text(
                            "Public tunnel starting…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TunnelState.DOWN -> Text(
                            "Public tunnel down. Toggle Public off and on to retry.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        else -> Unit
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.showCharacterNames,
                    onCheckedChange = { model.realmStatus.setShowCharacterNames(it) },
                )
                Text(
                    "  Show online character names on the page (off by default; never on " +
                        "the MariaDB database lane; never while the realm is offline)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RealmVisibilityButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

/**
 * D5a + D7 (design-lan §4.2/§6.2): the host's Guests card, visible only
 * while hosting. Create guest account (host-typed username, GENERATED
 * password — gmLevel 0 always, never saved into the host's stored-account
 * slot), the in-person handoff copy with the one-line copy affordance, the
 * players-online line, and the budget display (bot admission × players).
 * The credential pair renders ONLY here — no LAN surface embeds a
 * credential in invite text (§4.2's hard rule). Design-tunnel §9.3 adds
 * the duration-preset suspension beside the permanent ban (one moderation
 * lane, one store; both bind at the friend's next logon).
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod") // the guests card: creation, handoff, and revocation live together by design
private fun GuestsCard(model: DesktopAppModel) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var handoff by remember { mutableStateOf<Pair<String, String>?>(null) }
    var players by remember { mutableStateOf<DesktopAppModel.LanPlayersOnline?>(null) }
    var copiedHandoff by remember { mutableStateOf(false) }
    val admissionVerdict by model.botAdmission.collectAsState()

    // The players-online poll (the Android LanScreen placeholder's fill;
    // both platforms share the same data contract).
    LaunchedEffect(Unit) {
        while (true) {
            players = model.lanPlayersOnline()
            delay(5_000)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Guests", style = MaterialTheme.typography.titleMedium)
            // D7: the budget display — bot admission verdict × players online.
            Text(budgetLine(admissionVerdict, players), style = MaterialTheme.typography.bodyMedium)
            Text(
                "Create an account for a guest — a friend in the room with you, or a " +
                    "tunnel friend you are setting up at your invite's direction. The " +
                    "password is generated for you.",
                style = MaterialTheme.typography.bodySmall,
            )
            val usernameError = accountCredentialFieldError(username)
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.take(CREDENTIAL_MAX_CHARS) },
                label = { Text("Guest account name") },
                isError = username.isNotEmpty() && usernameError != null,
                supportingText = { Text(usernameError ?: "1–16 letters or numbers") },
                singleLine = true,
            )
            Button(
                enabled = !pending && username.isNotEmpty() && usernameError == null,
                onClick = {
                    pending = true
                    scope.launch {
                        val created = model.createGuestAccount(username.trim())
                        if (created.provision.ok && created.password.isNotEmpty()) {
                            handoff = created.username to created.password
                            status = null
                        } else {
                            status = accountProvisionFailureMessage(
                                created.provision.code,
                                created.provision.detail,
                            )
                        }
                        pending = false
                    }
                },
            ) { Text(if (pending) "Creating…" else "Create guest account") }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val pair = handoff
            if (pair != null) {
                Text(
                    "Give this to your friend privately — in person, or a one-to-one " +
                        "message for a tunnel friend: username ${pair.first}, password " +
                        "${pair.second} — they type it at the login screen. Don't paste " +
                        "it into a group chat.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = {
                    runCatching {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                            java.awt.datatransfer.StringSelection(
                                "username ${pair.first} password ${pair.second}",
                            ),
                            null,
                        )
                    }.onSuccess { copiedHandoff = true }
                }) { Text(if (copiedHandoff) "Copied" else "Copy as one line") }
            }
            // D5b (as resolved): ban/unban through the realmd database's
            // account_banned rows — a banned guest is refused at their next
            // login attempt. Kick (disconnecting a guest who is online
            // right now) needs the engine's console relay and is deferred;
            // the copy below is the honest fail-closed surface, no fake
            // button.
            Text("Revocation", style = MaterialTheme.typography.titleSmall)
            var banName by remember { mutableStateOf("") }
            var banStatus by remember { mutableStateOf<String?>(null) }
            var bans by remember { mutableStateOf(model.guestBans()) }
            val banError = accountCredentialFieldError(banName)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = banName,
                    onValueChange = { banName = it.take(CREDENTIAL_MAX_CHARS) },
                    label = { Text("Guest account name") },
                    isError = banName.isNotEmpty() && banError != null,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = banName.isNotEmpty() && banError == null,
                    onClick = {
                        scope.launch {
                        val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            model.banGuest(banName.trim(), "Banned by the host")
                        }
                        banStatus = when (outcome) {
                            is com.pocketrealm.desktop.DesktopGuestBans.BanOutcome.Banned ->
                                if (outcome.alreadyBanned) "${banName.trim()} was already banned."
                                else "${banName.trim()} is banned; they cannot log in until you unban them."
                            is com.pocketrealm.desktop.DesktopGuestBans.BanOutcome.Unbanned ->
                                "${banName.trim()} unbanned."
                            is com.pocketrealm.desktop.DesktopGuestBans.BanOutcome.Refused -> outcome.detail
                        }
                        if (outcome !is com.pocketrealm.desktop.DesktopGuestBans.BanOutcome.Refused) {
                            banName = ""
                        }
                        bans = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            model.guestBans()
                        }
                        }
                    },
                ) { Text("Ban") }
                // Design-tunnel §9.3: the duration presets — the middle
                // lever that expires on its own (expires_at = now +
                // duration), so a cool-down never needs an unban to lift.
                listOf(3600L to "1 h", 86_400L to "24 h").forEach { (seconds, label) ->
                    OutlinedButton(
                        enabled = banName.isNotEmpty() && banError == null,
                        onClick = {
                            scope.launch {
                                val outcome = kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.IO,
                                ) { model.suspendGuest(banName.trim(), seconds, "Suspended by the host") }
                                banStatus = when (outcome) {
                                    is com.pocketrealm.desktop.DesktopGuestBans.BanOutcome.Banned ->
                                        "${banName.trim()} is suspended for $label; they can " +
                                            "log in again when it expires."
                                    is com.pocketrealm.desktop.DesktopGuestBans.BanOutcome.Refused ->
                                        outcome.detail
                                    else -> "${banName.trim()} not suspended."
                                }
                                if (outcome !is com.pocketrealm.desktop.DesktopGuestBans.BanOutcome.Refused) {
                                    banName = ""
                                }
                                bans = kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.IO,
                                ) { model.guestBans() }
                            }
                        },
                    ) { Text(label) }
                }
            }
            if (bans.isEmpty()) {
                Text(
                    "No guest is banned. A ban (and its lift) applies at the guest's next " +
                        "login attempt; a guest who is online right now stays until they " +
                        "log out or you Save & exit.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                bans.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            // The model carries the distinction the copy
                            // promises: a timed suspension lifts on its own
                            // (show WHEN), a permanent ban needs the Unban
                            // lever — never render both identically.
                            if (row.permanent) {
                                "${row.username} — banned permanently (${row.reason})"
                            } else {
                                "${row.username} — suspended, lifts " +
                                    "${formatBanLift(row.expiresAt)} (${row.reason})"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = {
                            scope.launch {
                                val outcome = kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.IO,
                                ) { model.unbanGuest(row.username) }
                                banStatus = when (outcome) {
                                    is com.pocketrealm.desktop.DesktopGuestBans.BanOutcome.Unbanned ->
                                        "${row.username} can log in again."
                                    is com.pocketrealm.desktop.DesktopGuestBans.BanOutcome.Refused -> outcome.detail
                                    else -> "${row.username} is still banned."
                                }
                                bans = kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.IO,
                                ) { model.guestBans() }
                            }
                        }) { Text("Unban") }
                    }
                }
            }
            Text(
                "Disconnecting a guest who is online right now (kick) is not available " +
                    "in this build — it needs a runtime update. To end a guest's session " +
                    "now, ask them to log out or use Save & exit.",
                style = MaterialTheme.typography.bodySmall,
            )
            banStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
// internal (not private) so the round-6 R6-1 bot-labeling pin reaches it
internal fun budgetLine(
    verdict: DesktopBotAdmissionVerdict?,
    players: DesktopAppModel.LanPlayersOnline?,
): String {
    val bots = when {
        verdict == null -> "Bots: off"
        verdict.adapted -> "Bots: ${verdict.effectiveTarget} (admission lowered: ${verdict.reason})"
        else -> "Bots: ${verdict.effectiveTarget} (admission steady)"
    }
    // Round-6 R6-1: bots labeled, never padded (the android twin's line).
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

// ---------------------------------------------------------------------------
// Remote friend accounts (design-remote-accounts §3/§5/§7): the Home-side
// affordances — the hosting summary (pending count + Players entry point)
// and the join-mode request form (the friend side of the invite flow).
// The full host surface lives in Route.Players.
// ---------------------------------------------------------------------------

@Composable
private fun FriendAccessSummaryCard(model: DesktopAppModel, onOpenPlayers: () -> Unit) {
    val invites by model.friendAccess.invites.collectAsState()
    val listenerState by model.friendAccess.listenerState.collectAsState()
    val pending = invites.count { it.state == FriendInviteStore.State.SUBMITTED }
    val listening = listenerState as? com.pocketrealm.supervisor.ProvisioningListener.ListenerState.Listening
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Friend access", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    listenerState is com.pocketrealm.supervisor.ProvisioningListener.ListenerState.Failed ->
                        "The provisioning listener failed to bind — check Players for the reason."
                    listening != null ->
                        "Listening on ${listening.address}:${listening.port}."
                    else -> "Friend access is off — the provisioning socket is not bound."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                when {
                    pending == 1 -> "1 pending access request."
                    pending > 1 -> "$pending pending access requests."
                    else -> "No pending access requests."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenPlayers) { Text(if (pending > 0) "Review requests" else "Open Players") }
            }
        }
    }
}

/**
 * The join-mode friend card (§3): host address, invite code, desired
 * username, password (typed twice) — the password is derived into an SRP6
 * verifier ON THIS DEVICE and only `{username, sHex, vHex}` crosses to the
 * host, bound to the invite token. On approval the account is saved for
 * auto-login exactly like the host's own account card (§5); on refusal the
 * derived verifier is dropped (the client drops it for every non-PROVISIONED
 * verdict).
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber") // the friend-side request form is one reviewed surface; the poll loop mirrors the design-remote-accounts §5 flow
private fun JoinRequestAccessCard(model: DesktopAppModel) {
    val settings by model.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var hostAddress by remember(settings.lanAddress) { mutableStateOf(settings.lanAddress) }
    var inviteId by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordRepeat by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val usernameError = accountCredentialFieldError(username)
    val passwordError = accountCredentialFieldError(password)
    val passwordsDiffer = password.isNotEmpty() && passwordRepeat.isNotEmpty() && password != passwordRepeat
    val addressValid = runCatching {
        com.pocketrealm.supervisor.RealmEndpoint.parseLan(hostAddress.trim())
    }.isSuccess
    val fieldsValid = addressValid && inviteId.isNotBlank() && code.isNotBlank() &&
        usernameError == null && passwordError == null && !passwordsDiffer

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Request an account from the host", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pick your own name and password — the password never leaves this computer. " +
                    "The host sees only a verifier and approves you by name.",
                style = MaterialTheme.typography.bodySmall,
            )
            // §6 exposure honesty: the provisioning listener binds the host's
            // LAN address only, so this card cannot reach a host through a
            // tunnel — say so where the address is typed, not just in the
            // field's refusal.
            Text(
                "This card reaches the host over your shared LAN only — a tunnel " +
                    "address joins the realm but cannot submit a request. Over a " +
                    "tunnel, ask the host to create your account on their Guests card.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = hostAddress,
                onValueChange = { hostAddress = it.take(15) },
                label = { Text("Host address") },
                isError = hostAddress.isNotEmpty() && !addressValid,
                supportingText = {
                    Text(
                        when {
                            hostAddress.isEmpty() -> "the LAN host's IPv4 (e.g. 192.168.1.20)"
                            addressValid -> "the host's provisioning port ${ServerRuntimeContract.PROVISION_PORT} is used automatically"
                            else -> "a canonical private IPv4 address is required (no hostnames)"
                        },
                    )
                },
                singleLine = true,
            )
            OutlinedTextField(
                value = inviteId,
                onValueChange = { inviteId = it.take(16) },
                label = { Text("Invite id (FRD-…)") },
                singleLine = true,
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.trim() },
                label = { Text("Invite code") },
                singleLine = true,
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.take(CREDENTIAL_MAX_CHARS) },
                label = { Text("Desired account name") },
                isError = usernameError != null,
                supportingText = { Text(usernameError ?: "1–16 letters or numbers") },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(CREDENTIAL_MAX_CHARS) },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = passwordError != null,
                    supportingText = { Text(passwordError ?: "1–16 letters or numbers") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = passwordRepeat,
                    onValueChange = { passwordRepeat = it.take(CREDENTIAL_MAX_CHARS) },
                    label = { Text("Repeat") },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = passwordsDiffer,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                enabled = pending || fieldsValid,
                onClick = {
                    if (pending) {
                        // The wait is never a dead end: a friend whose host
                        // dropped mid-poll can back out and re-request with
                        // the same (still-valid) invite code.
                        pending = false
                        status = "Request cancelled — nothing was saved. The same invite " +
                            "code still works while it is valid."
                        return@Button
                    }
                    pending = true
                    status = "Sending request to $hostAddress…"
                    scope.launch {
                        when (
                            val verdict = com.pocketrealm.server.FriendAccessClient().requestAccess(
                                host = hostAddress.trim(),
                                port = ServerRuntimeContract.PROVISION_PORT,
                                inviteId = inviteId.trim(),
                                token = code.trim(),
                                username = username.trim(),
                                password = password,
                            )
                        ) {
                            is com.pocketrealm.server.FriendAccessClient.Verdict.Submitted -> {
                                status = "Request submitted — waiting for the host to approve you…"
                                // The read-only poll (§5): the host's approval
                                // is what turns this into a saved account.
                                var unreachableStreak = 0
                                while (pending) {
                                    kotlinx.coroutines.delay(POLL_DELAY_MS)
                                    when (
                                        val poll = com.pocketrealm.server.FriendAccessClient().requestAccess(
                                            host = hostAddress.trim(),
                                            port = ServerRuntimeContract.PROVISION_PORT,
                                            inviteId = verdict.inviteId,
                                            token = code.trim(),
                                            username = username.trim(),
                                            password = password,
                                        )
                                    ) {
                                        is com.pocketrealm.server.FriendAccessClient.Verdict.Submitted -> {
                                            unreachableStreak = 0
                                            status = "Request submitted — waiting for the host to approve you…"
                                        }
                                        is com.pocketrealm.server.FriendAccessClient.Verdict.Unreachable -> {
                                            // §6: a dropped host must never present
                                            // as "waiting for the host to click
                                            // Approve" — surface the first mid-wait
                                            // failure and keep retrying.
                                            unreachableStreak += 1
                                            if (unreachableStreak == 1) {
                                                status = "The host became unreachable while you wait " +
                                                    "(they may be saving or offline) — still retrying " +
                                                    "every few seconds. Cancel backs out."
                                            }
                                        }
                                        is com.pocketrealm.server.FriendAccessClient.Verdict.Provisioned -> {
                                            val stored = runCatching {
                                                model.accountStore.save(
                                                    username.trim(), password, poll.accountId,
                                                )
                                            }.isSuccess
                                            status = if (stored) {
                                                // Honest lane copy: on a JOINED host there is no
                                                // local world owner, so the credential-proof gate
                                                // degrades to manual login — the save is real, the
                                                // "auto-login" promise is not (the wiki's guest
                                                // section states the same).
                                                "Approved — account ${poll.accountId} saved. Join the " +
                                                    "LAN host and log in with it."
                                            } else {
                                                "Approved as account ${poll.accountId}, but saving it " +
                                                    "locally failed — rejoin with this name and password."
                                            }
                                            pending = false
                                        }
                                        is com.pocketrealm.server.FriendAccessClient.Verdict.Refused -> {
                                            // Round-6 R6-r6-2: indistinguishable by protocol design;
                                            // never assert a cause, never invite retries against a lock.
                                            status = joinerRefusalCopy()
                                            pending = false
                                        }
                                    }
                                }
                            }
                            is com.pocketrealm.server.FriendAccessClient.Verdict.Provisioned -> {
                                // Immediate approval answered the submit itself.
                                runCatching {
                                    model.accountStore.save(username.trim(), password, verdict.accountId)
                                }
                                status = "Approved — account ${verdict.accountId} saved. Join the host and log in with it."
                                pending = false
                            }
                            is com.pocketrealm.server.FriendAccessClient.Verdict.Refused -> {
                                // Round-6 R6-r6-2: indistinguishable by protocol design;
                                // never assert a cause, never invite retries against a lock.
                                status = joinerRefusalCopy()
                                pending = false
                            }
                            is com.pocketrealm.server.FriendAccessClient.Verdict.Unreachable -> {
                                status = "Could not reach $hostAddress:${ServerRuntimeContract.PROVISION_PORT} " +
                                    "(${verdict.reason}). Check the host's address, that the realm is up, " +
                                    "and that friend access is enabled there."
                                pending = false
                            }
                        }
                        pending = false
                    }
                },
            ) { Text(if (pending) "Cancel request" else "Request access") }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private const val POLL_DELAY_MS = 3_000L

/** A suspension's lift time in the host's local wall clock (the ban list
 * distinguishes timed suspensions from permanent bans; the model carries
 * [com.pocketrealm.desktop.DesktopGuestBans.BanRow.permanent]/expiresAt). */
private fun formatBanLift(epochSeconds: Long): String =
    java.time.Instant.ofEpochSecond(epochSeconds)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm"))

/** True when the displayed advertisement is the loopback (cloudflared)
 * recipe: its address model is each player's OWN forwarder target, so the
 * tunnel-membership copy written for CGNAT advertisements would be false
 * here. */
private fun isLoopbackAdvertisement(value: String): Boolean =
    runCatching { com.pocketrealm.supervisor.AdvertisedAddress.parse(value) }
        .getOrNull()?.isLoopback == true
