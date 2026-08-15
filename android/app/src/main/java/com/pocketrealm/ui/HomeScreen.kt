package com.pocketrealm.ui

import android.os.Build

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketrealm.bots.BotProfile
import com.pocketrealm.bots.BotSelection
import com.pocketrealm.log.AppLog
import com.pocketrealm.client.IntegratedClientDisplay
import com.pocketrealm.client.ArmRendererAuto
import com.pocketrealm.client.ArmClientRenderer
import com.pocketrealm.client.ArmClientRendererCatalog
import com.pocketrealm.client.AndroidGladioCapabilityProbe
import com.pocketrealm.client.AndroidSystemVulkanProbe
import com.pocketrealm.client.SystemVulkanCapabilities
import com.pocketrealm.client.GladioCapability
import com.pocketrealm.client.ClientAudioPolicy
import com.pocketrealm.client.ClientRuntimeSelector
import com.pocketrealm.client.RendererPackageCatalog
import com.pocketrealm.client.VulkanDriverCatalog
import com.pocketrealm.realm.RealmState
import com.pocketrealm.realm.ClientLaunchState
import com.pocketrealm.service.RealmService
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.RuntimeSupervisorClient
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.UserAccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Landscape-first launch dashboard (brief §58): Home answers what will start,
 * whether it is running, and which account is used. Bot configuration lives
 * in the Bots destination; LAN host/join lives in the LAN destination.
 */
@Composable
fun HomeScreen(
    contentPadding: PaddingValues = PaddingValues(),
    onOpenBots: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val supervisorClient = remember(context) { RuntimeSupervisorClient(context) }
    val stateUpdates = remember(supervisorClient) { supervisorClient.observeRealmState() }
    val state by stateUpdates.collectAsState(initial = RealmState.Idle)
    val settings = remember(context) { Settings(context) }
    // Null until DataStore's first emission: launching with the all-default
    // snapshot in that cold-start window would ignore the saved preset and
    // LAN preference, so realm start actions gate on the real snapshot.
    val settingsSnapshotState: Settings.Snapshot? by settings.flow.collectAsState(initial = null)
    val settingsSnapshot = settingsSnapshotState ?: Settings.Snapshot()
    val systemVulkanProbe by produceState<Result<SystemVulkanCapabilities>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { AndroidSystemVulkanProbe.probe() } }
    }
    val gladioProbe by produceState<Result<GladioCapability>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching { AndroidGladioCapabilityProbe.probe(context) }
        }
    }
    val clientUnavailableReason = if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
        when (settingsSnapshot.selectedArmRendererId()) {
            ArmClientRendererCatalog.AUTO_ID -> null
            "dxvk" -> VulkanDriverCatalog.availabilityForPair(
                settingsSnapshot.effectiveVulkanDriverId(),
                settingsSnapshot.selectedDxvkPackageId(),
                ArmRendererAuto.isAdrenoGpu(),
                systemVulkanProbe?.getOrNull(),
            ).takeUnless { it.available }?.reason
            "legacy-gladio" -> ArmClientRendererCatalog.availability(
                ArmClientRenderer.LEGACY_GLADIO, gladioProbe,
                Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            ).takeUnless { it.available }?.reason
            "mesa-virgl" -> ArmClientRendererCatalog.availability(
                ArmClientRenderer.MESA_VIRGL, gladioProbe,
                Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            ).takeUnless { it.available }?.reason
            else -> null
        }
    } else null
    // Re-resolve when a saved preset gains a revision so Home shows and
    // launches the latest applied configuration, not a stale one.
    val savedPresets by remember {
        com.pocketrealm.bots.BotCustomPresets.store()?.presets
            ?: kotlinx.coroutines.flow.MutableStateFlow(
                emptyList<com.pocketrealm.bots.BotPresetStore.SavedPreset>(),
            )
    }.collectAsState()
    val selection = remember(settingsSnapshot, savedPresets) {
        BotSelection.resolve(
            savedPresetId = settingsSnapshot.botSavedPresetId,
            advancedEnabled = settingsSnapshot.botAdvancedEnabled,
            advancedTarget = settingsSnapshot.botPopulationTarget,
            advanced = settingsSnapshot.botAdvanced,
            profileId = settingsSnapshot.botProfileId,
        )
    }
    val botProfile = selection.profile
    val displayHost by IntegratedClientDisplay.host.collectAsState()
    // Accounts are provisioned by the local world; hide the card while
    // joined to a remote host as a client-only session.
    val lanJoinActive = (state as? RealmState.Running)?.mode == RuntimeMode.LAN_JOIN
    val scope = rememberCoroutineScope()
    val accountStore = remember(context) { UserAccountStore(context) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var gmAccount by rememberSaveable { mutableStateOf(false) }
    var accountStatus by rememberSaveable { mutableStateOf("Create a local account after the world is ready") }
    var storedAccount by remember { mutableStateOf(accountStore.loadOrQuarantine()?.username) }
    var clientRetryPending by remember { mutableStateOf(false) }
    var accountOperationPending by remember { mutableStateOf(false) }

    val canLaunchGame = canLaunchGameWithAccount(
        autoLoginOnLaunch = settingsSnapshot.autoLoginOnLaunch,
        storedAccount = storedAccount,
        accountOperationPending = accountOperationPending,
    )

    // Auto-enter follows only an explicit client-start tap on this screen,
    // and only once the client publishes a generation other than the one
    // published at tap time. Generations are process-local counters that
    // restart at zero whenever the app process is recreated, so reacting to
    // generation changes alone re-entered the fullscreen client after a
    // crash-relaunch and trapped the user out of Home; and a retry tapped
    // while a failed host is still published must wait for the fresh
    // generation instead of entering the dead display.
    var pendingAutoEnterBase by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(pendingAutoEnterBase, displayHost?.generation) {
        val base = pendingAutoEnterBase ?: return@LaunchedEffect
        val generation = displayHost?.generation ?: return@LaunchedEffect
        if (generation != base) {
            AppLog.i("Home", "auto-enter generation $generation (base $base)")
            pendingAutoEnterBase = null
            context.startActivity(ClientActivity.intent(context, generation))
        }
    }

    // A rejected authoritative proof may remove an obsolete saved record in
    // the supervisor process. Refresh the Home summary when launch state moves.
    LaunchedEffect(state) {
        if (state is RealmState.Running) {
            storedAccount = withContext(Dispatchers.IO) {
                accountStore.loadOrQuarantine()?.username
            }
        }
    }

    val startRealmOnly = startRealmOnly@{
        if (settingsSnapshotState == null) return@startRealmOnly
        if (settingsSnapshot.allowLanPlayers) {
            RealmService.hostLan(context, botProfile.id, includeClient = false)
        } else RealmService.start(context, botProfile.id, includeClient = false)
    }
    val startRealmAndGame = startRealmAndGame@{
        if (settingsSnapshotState == null) return@startRealmAndGame
        pendingAutoEnterBase = displayHost?.generation ?: -1L
        if (settingsSnapshot.allowLanPlayers) {
            RealmService.hostLan(context, botProfile.id, includeClient = true)
        } else RealmService.start(context, botProfile.id, includeClient = true)
    }
    val saveAndExit = { RealmService.saveExit(context) }
    val retryGame: () -> Unit = {
        if (!clientRetryPending) {
            clientRetryPending = true
            pendingAutoEnterBase = displayHost?.generation ?: -1L
            scope.launch {
                runCatching { supervisorClient.relaunchClient() }
                clientRetryPending = false
            }
        }
        Unit
    }
    val enterGame = displayHost?.let { host ->
        { context.startActivity(ClientActivity.intent(context, host.generation)) }
    }
    val createAccount: () -> Unit = createAccount@{
        if (accountOperationPending) return@createAccount
        val savedUsername = username
        val savedPassword = password
        accountOperationPending = true
        accountStatus = "Creating through the core control channel..."
        scope.launch {
            try {
                val result = runCatching {
                    supervisorClient.createAccount(savedUsername, savedPassword, if (gmAccount) 3 else 0)
                }
                password = ""
                accountStatus = result.fold(
                    onSuccess = { value ->
                        val code = value.optString("code")
                        val accountId = value.optLong("accountId")
                        if (value.optBoolean("ok") &&
                            code in setOf("ACCOUNT_CREATED", "ACCOUNT_VERIFIED") && accountId > 0) {
                            val gmLevel = value.optInt("gmLevel")
                            accountStatus = "Saving account securely..."
                            val persisted = withContext(Dispatchers.IO) {
                                runCatching {
                                    accountStore.save(savedUsername, savedPassword, accountId, gmLevel)
                                }
                            }
                            if (persisted.isSuccess) {
                                storedAccount = savedUsername
                                if (code == "ACCOUNT_CREATED") {
                                    "Account $accountId created; auto-login saved"
                                } else {
                                    "Existing account verified; auto-login saved"
                                }
                            } else {
                                "Account created, but auto-login could not be saved"
                            }
                        } else {
                            accountProvisionFailureMessage(
                                code,
                                value.optString("detail").takeIf(String::isNotBlank),
                            )
                        }
                    },
                    onFailure = { "Account control failed: ${it.javaClass.simpleName}" },
                )
            } finally {
                accountOperationPending = false
            }
        }
        Unit
    }
    val clearAccount: () -> Unit = {
        if (!accountOperationPending) {
            accountOperationPending = true
            accountStatus = "Removing saved account..."
            scope.launch {
                try {
                    val cleared = withContext(Dispatchers.IO) { runCatching { accountStore.clear() } }
                    if (cleared.isSuccess) {
                        storedAccount = null
                        accountStatus = "Saved account removed"
                    } else accountStatus = "Stored account could not be cleared"
                } finally {
                    accountOperationPending = false
                }
            }
        }
        Unit
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        // Orientation, not a second tablet-width threshold, is authoritative
        // for the landscape home layout.
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RealmControlCard(
                    state = state,
                    botProfile = botProfile,
                    botPresetName = selection.savedPreset?.name,
                    onStartRealm = startRealmOnly,
                    onStartAll = startRealmAndGame,
                    onSaveExit = saveAndExit,
                    onEnterGame = enterGame,
                    onRetryGame = retryGame,
                    clientRetryPending = clientRetryPending,
                    canLaunchGame = canLaunchGame,
                    settingsReady = settingsSnapshotState != null,
                    allowLanPlayers = settingsSnapshot.allowLanPlayers,
                    clientUnavailableReason = clientUnavailableReason,
                    landscape = true,
                    modifier = Modifier.fillMaxWidth().testTag("home-realm-card"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    CurrentSetupCard(
                        profile = botProfile,
                        settings = settingsSnapshot,
                        storedAccount = storedAccount,
                        onOpenBots = onOpenBots,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.weight(1f),
                    )
                    if (!lanJoinActive) AccountCard(
                        username = username,
                        onUsername = { username = it },
                        password = password,
                        onPassword = { password = it },
                        gmAccount = gmAccount,
                        onGmAccount = { gmAccount = it },
                        accountStatus = accountStatus,
                        storedAccount = storedAccount,
                        onCreate = createAccount,
                        onClear = clearAccount,
                        landscape = true,
                        creationEnabled = state is RealmState.Running &&
                            !lanJoinActive && !accountOperationPending,
                        operationPending = accountOperationPending,
                        modifier = Modifier.weight(1.18f),
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RealmControlCard(
                    state = state,
                    botProfile = botProfile,
                    botPresetName = selection.savedPreset?.name,
                    onStartRealm = startRealmOnly,
                    onStartAll = startRealmAndGame,
                    onSaveExit = saveAndExit,
                    onEnterGame = enterGame,
                    onRetryGame = retryGame,
                    clientRetryPending = clientRetryPending,
                    canLaunchGame = canLaunchGame,
                    settingsReady = settingsSnapshotState != null,
                    allowLanPlayers = settingsSnapshot.allowLanPlayers,
                    clientUnavailableReason = clientUnavailableReason,
                    landscape = false,
                    modifier = Modifier.fillMaxWidth().testTag("home-realm-card"),
                )
                CurrentSetupCard(
                    profile = botProfile,
                    settings = settingsSnapshot,
                    storedAccount = storedAccount,
                    onOpenBots = onOpenBots,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state is RealmState.Running && !lanJoinActive) {
                    AccountCard(
                        username = username,
                        onUsername = { username = it },
                        password = password,
                        onPassword = { password = it },
                        gmAccount = gmAccount,
                        onGmAccount = { gmAccount = it },
                        accountStatus = accountStatus,
                        storedAccount = storedAccount,
                        onCreate = createAccount,
                        onClear = clearAccount,
                        landscape = false,
                        creationEnabled = !accountOperationPending,
                        operationPending = accountOperationPending,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Compact realm card (brief §3): identity, status, and start actions only. */
@Composable
private fun RealmControlCard(
    state: RealmState,
    botProfile: BotProfile,
    botPresetName: String?,
    onStartRealm: () -> Unit,
    onStartAll: () -> Unit,
    onSaveExit: () -> Unit,
    onEnterGame: (() -> Unit)?,
    onRetryGame: () -> Unit,
    clientRetryPending: Boolean,
    canLaunchGame: Boolean,
    settingsReady: Boolean,
    allowLanPlayers: Boolean,
    clientUnavailableReason: String?,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    val (statusText, detailText) = realmStatus(state)
    val actions = homeActionAvailability(
        settingsReady = settingsReady,
        clientUnavailableReason = clientUnavailableReason,
        canLaunchGame = canLaunchGame,
        clientRetryPending = clientRetryPending,
    )
    val presetLabel = botPresetName?.let { "Custom · $it" } ?: botProfile.displayName
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val textArea: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Realm", style = MaterialTheme.typography.labelMedium)
                    Text(statusText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                // Preset display names already carry their bot count.
                Text(presetLabel, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Starts at ${botProfile.initialTarget} → target ${botProfile.selectedTarget} · " +
                        "Adaptive AI on",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(detailText, style = MaterialTheme.typography.labelSmall)
                clientUnavailableReason?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        // Portrait stacks the actions under the status so the fixed button
        // widths can never starve the text column to zero.
        val actionArea: @Composable (fillRow: Boolean) -> Unit = { fillRow ->
            when (state) {
                is RealmState.Idle, is RealmState.Failed, is RealmState.Recovering -> {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = onStartRealm,
                                enabled = actions.startRealm,
                                modifier = (if (fillRow) Modifier.weight(1f) else Modifier)
                                    .testTag("realm-primary-action"),
                            ) { Text(if (allowLanPlayers) "Host realm" else "Start realm") }
                            OutlinedButton(
                                onClick = onStartAll,
                                enabled = actions.startRealmAndGame,
                                modifier = (if (fillRow) Modifier.weight(1f) else Modifier)
                                    .testTag("realm-start-all"),
                            ) { Text("Realm + game") }
                        }
                        if (!canLaunchGame) Text(
                            "Start the realm, create your account, then start the game.",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.widthIn(max = 260.dp),
                        )
                    }
                }
                is RealmState.Running -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.clientState == ClientLaunchState.FAILED) {
                            Button(
                                onClick = onRetryGame,
                                enabled = actions.launchClient,
                                modifier = (if (fillRow) Modifier.weight(1f) else Modifier)
                                    .testTag("retry-client"),
                            ) { Text(if (clientRetryPending) "Retrying game..." else "Retry game") }
                        } else if (state.clientState == ClientLaunchState.NOT_STARTED) {
                            Button(
                                onClick = onRetryGame,
                                enabled = actions.launchClient,
                                modifier = (if (fillRow) Modifier.weight(1f) else Modifier)
                                    .testTag("start-client"),
                            ) { Text(if (clientRetryPending) "Starting game..." else "Start game") }
                        } else {
                            Button(
                                onClick = { onEnterGame?.invoke() },
                                enabled = onEnterGame != null,
                                modifier = (if (fillRow) Modifier.weight(1f) else Modifier)
                                    .testTag("enter-fullscreen-client"),
                            ) { Text(if (onEnterGame == null) "Preparing game..." else "Enter game") }
                        }
                        OutlinedButton(
                            onClick = onSaveExit,
                            modifier = (if (fillRow) Modifier.weight(1f) else Modifier)
                                .testTag("save-exit"),
                        ) {
                            Text(if (state.mode == RuntimeMode.LAN_JOIN) "Exit client" else "Save & exit")
                        }
                    }
                }
                is RealmState.Starting, is RealmState.Saving, is RealmState.Stopping -> {
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = (if (fillRow) Modifier.fillMaxWidth() else Modifier)
                            .testTag("realm-primary-action"),
                    ) {
                        Text(contextualWorkingLabel(state))
                    }
                }
            }
        }
        if (landscape) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                textArea(Modifier.weight(1f))
                actionArea(false)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                textArea(Modifier.fillMaxWidth())
                actionArea(true)
            }
        }
    }
}

/** Dense active-setup summary (brief §4): compact chips, not one line per value. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrentSetupCard(
    profile: BotProfile,
    settings: Settings.Snapshot,
    storedAccount: String?,
    onOpenBots: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val display = remember(context, settings.displayProfileId, settings.clientFrameCap) {
        runCatching {
            com.pocketrealm.client.ClientDisplayCapabilities.requireSelection(
                context, settings.displayProfileId, settings.clientFrameCap,
            )
        }.getOrElse { settings.displaySelection() }
    }
    val graphics = when (settings.selectedArmRendererId()) {
        ArmClientRendererCatalog.AUTO_ID ->
            "Auto (${if (ArmRendererAuto.isAdrenoGpu()) "Turnip" else "System Vortek"} / DXVK)"
        "dxvk" -> {
            val dxvk = RendererPackageCatalog.find(settings.selectedDxvkPackageId())?.dxvkVersion
                ?: "pinned"
            val vulkan = VulkanDriverCatalog.find(settings.effectiveVulkanDriverId())?.label
                ?: "Vulkan"
            "$vulkan / DXVK $dxvk"
        }
        "legacy-gladio" -> "Legacy OpenGL / Gladio (experimental)"
        "mesa-virgl" -> "Mesa VirGL / virpipe (experimental)"
        else -> "Auto"
    }
    val activity = when {
        profile.iterationsPerTick >= 18 && profile.activeBotPercent >= 15 -> "Very active AI"
        profile.activeBotPercent >= 10 -> "Active AI"
        profile.activeBotPercent >= 6 -> "Balanced AI"
        else -> "Light AI"
    }
    val audioSupported = remember {
        ClientAudioPolicy.isSupported(
            ClientRuntimeSelector.selectForAbis(Build.SUPPORTED_ABIS.asList()).provider,
        )
    }
    val sound = when {
        !audioSupported -> "Audio unavailable on this device"
        settings.audioMode == Settings.AudioMode.ON -> "Sound on"
        else -> "Muted"
    }
    val login = when {
        !settings.autoLoginOnLaunch -> "Manual login"
        storedAccount == null -> "Auto-login waiting for account"
        else -> "Auto-login: $storedAccount"
    }

    Card(modifier = modifier.testTag("active-setup-card")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Active setup",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = onOpenBots,
                    modifier = Modifier.testTag("setup-open-bots"),
                ) { Text("Bots") }
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("setup-open-graphics"),
                ) { Text("Graphics") }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AssistChip(onClick = onOpenBots, label = { Text("${profile.selectedTarget} bots") })
                AssistChip(onClick = onOpenBots, label = { Text(activity) })
                AssistChip(onClick = onOpenSettings, label = {
                    Text("${display.resolution} · ${display.frameCap.fps} FPS")
                })
                AssistChip(onClick = onOpenSettings, label = { Text(graphics) })
            }
            Text(
                login + " · $sound" +
                    if (settings.tweaks.hasAnyPatch()) " · Client tweaks on" else " · Vanilla client",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AccountCard(
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    gmAccount: Boolean,
    onGmAccount: (Boolean) -> Unit,
    accountStatus: String,
    storedAccount: String?,
    onCreate: () -> Unit,
    onClear: () -> Unit,
    landscape: Boolean,
    creationEnabled: Boolean,
    operationPending: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.testTag("account-card")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Local account", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                storedAccount?.let {
                    Text("Saved: $it", style = MaterialTheme.typography.labelMedium)
                    OutlinedButton(onClick = onClear, enabled = !operationPending) { Text("Clear") }
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountFields(username, onUsername, password, onPassword, creationEnabled, Modifier.weight(1f))
                }
            } else {
                AccountFields(username, onUsername, password, onPassword, creationEnabled, Modifier.fillMaxWidth())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(
                    checked = gmAccount,
                    onCheckedChange = onGmAccount,
                    enabled = creationEnabled,
                    modifier = Modifier.testTag("account-gm"),
                )
                Text(
                    if (gmAccount) "Administrator" else "Player",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onCreate,
                    enabled = creationEnabled && username.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.testTag("account-create"),
                ) { Text("Create & remember") }
            }
            Text(
                if (gmAccount) {
                    "Administrator accounts can use privileged realm commands; choose this only for local maintenance."
                } else {
                    "Player accounts have normal gameplay permissions and are the recommended choice."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                when {
                    operationPending -> accountStatus
                    creationEnabled -> accountStatus
                    else -> "Start the realm before creating a new local account."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("account-status"),
            )
        }
    }
}

@Composable
private fun AccountFields(
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = username,
            onValueChange = onUsername,
            enabled = enabled,
            singleLine = true,
            label = { Text("Account name") },
            modifier = Modifier.weight(1f).testTag("account-username"),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            enabled = enabled,
            singleLine = true,
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.weight(1f).testTag("account-password"),
        )
    }
}

private fun realmStatus(state: RealmState): Pair<String, String> = when (state) {
    is RealmState.Idle -> "Ready to start" to "Your local realm is stopped and safely saved."
    is RealmState.Starting -> "Starting" to "Preparing the realm (attempt ${state.attempt})."
    is RealmState.Running -> if (state.clientState == ClientLaunchState.FAILED) {
        "Realm online; game needs retry" to gamePreparationFailureMessage(state.clientFailure)
    } else if (state.clientState == ClientLaunchState.NOT_STARTED) {
        "Realm online" to "Create or verify your local account, then start the game when you are ready."
    } else when (state.mode) {
        RuntimeMode.LOCAL -> "Realm online" to "The world and loopback-only services are ready."
        RuntimeMode.LAN_HOST -> "LAN realm online" to
            "Hosting on ${state.endpointAddress}; manage LAN in the LAN destination."
        RuntimeMode.LAN_JOIN -> "LAN client online" to
            "Client-only session for ${state.endpointAddress}; manage it in the LAN destination."
    }
    is RealmState.Saving -> "Saving" to "Draining durable writes (${state.reason.name.lowercase()})."
    is RealmState.Stopping -> "Stopping" to "Closing the client and local services safely."
    is RealmState.Recovering -> "Recovering" to state.note
    is RealmState.Failed -> "Needs attention" to state.message
}

private fun gamePreparationFailureMessage(detail: String?): String = when {
    detail == null -> "The game client stopped during preparation. The realm stayed online; tap Retry game."
    detail.contains("rootfs", ignoreCase = true) ||
        detail.contains("Wine prefix", ignoreCase = true) ->
        "The game runtime could not be prepared. Your realm and imported client are safe; tap Retry game."
    detail.contains("account", ignoreCase = true) ->
        "The saved game account could not be verified. Check the Local account card, then retry."
    else -> "The game client stopped during preparation. The realm stayed online; tap Retry game."
}

private fun contextualWorkingLabel(state: RealmState): String = when (state) {
    is RealmState.Starting -> "Starting realm..."
    is RealmState.Saving -> "Saving..."
    is RealmState.Stopping -> "Stopping..."
    else -> "Working..."
}

internal data class HomeActionAvailability(
    val startRealm: Boolean,
    val startRealmAndGame: Boolean,
    val joinLan: Boolean,
    val launchClient: Boolean,
)

/** Graphics/client failures never block the independent DB/realm/world action. */
internal fun homeActionAvailability(
    settingsReady: Boolean,
    clientUnavailableReason: String?,
    canLaunchGame: Boolean,
    clientRetryPending: Boolean,
): HomeActionAvailability {
    val clientAvailable = clientUnavailableReason == null
    return HomeActionAvailability(
        // Before the first real settings emission the saved preset and LAN
        // preference are unknown; the buttons must look unavailable, not
        // enabled-but-silent.
        startRealm = settingsReady,
        startRealmAndGame = settingsReady && clientAvailable && canLaunchGame,
        joinLan = clientAvailable,
        launchClient = clientAvailable && canLaunchGame && !clientRetryPending,
    )
}
