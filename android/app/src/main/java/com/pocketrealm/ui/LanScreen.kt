package com.pocketrealm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.os.Build
import com.pocketrealm.bots.BotSelection
import com.pocketrealm.client.AndroidGladioCapabilityProbe
import com.pocketrealm.client.ArmRendererAuto
import com.pocketrealm.client.AndroidSystemVulkanProbe
import com.pocketrealm.client.ArmClientRenderer
import com.pocketrealm.client.ArmClientRendererCatalog
import com.pocketrealm.client.GladioCapability
import com.pocketrealm.client.SystemVulkanCapabilities
import com.pocketrealm.client.UserVulkanDriverResolution
import com.pocketrealm.client.VulkanDriverCatalog
import com.pocketrealm.discovery.nostr.PublicRealmBrowser
import com.pocketrealm.discovery.nostr.RelayUrls
import com.pocketrealm.realm.RealmState
import com.pocketrealm.server.FriendAccessClient
import com.pocketrealm.supervisor.AdvertisedAddress
import com.pocketrealm.supervisor.TunnelInterfacePolicy
import com.pocketrealm.supervisor.RuntimeFailureCopy
import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.supervisor.FriendInviteStore
import com.pocketrealm.service.RealmService
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.UserAccountStore
import com.pocketrealm.supervisor.GuestCredentials
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimeSupervisorClient
import com.pocketrealm.supervisor.RealmEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated LAN destination. Owns host/join networking only;
 * how bots behave around several humans belongs to the Bots playstyle
 * selector. Future sections (player lists, discovery, invitations) are
 * deliberately absent until actually supported.
 */
@Composable
fun LanScreen() {
    val context = LocalContext.current
    val supervisorClient = remember(context) { RuntimeSupervisorClient(context) }
    val state by remember(supervisorClient) {
        supervisorClient.observeRealmState()
    }.collectAsState(initial = RealmState.Idle())
    val settings = remember(context) { Settings(context) }
    // Null until DataStore's first emission: hosting with the all-default
    // snapshot in that cold-start window would ignore the saved bot preset,
    // so host actions gate on the real snapshot (HomeScreen pattern).
    val snapshotState: Settings.Snapshot? by settings.flow.collectAsState(initial = null)
    val snapshot = snapshotState ?: Settings.Snapshot()
    val scope = rememberCoroutineScope()
    val systemVulkanProbe by produceState<Result<SystemVulkanCapabilities>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { AndroidSystemVulkanProbe.probe() } }
    }
    val gladioProbe by produceState<Result<GladioCapability>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching { AndroidGladioCapabilityProbe.probe(context) }
        }
    }
    // LAN join is a client-only session; it cannot start when the selected
    // renderer stack is unavailable on this device. The Vulkan/DXVK pair only
    // applies to DXVK — the OpenGL renderers never load it.
    val clientUnavailableReason = if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
        when (snapshot.selectedArmRendererId()) {
            ArmClientRendererCatalog.AUTO_ID -> null
            "dxvk" -> UserVulkanDriverResolution.availabilityForPairPreflight(
                snapshot.effectiveVulkanDriverId(),
                snapshot.selectedDxvkPackageId(),
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
    val realmBusy = !(state is RealmState.Idle || state is RealmState.Failed ||
        state is RealmState.Recovering)
    var lanAddress by remember { mutableStateOf("") }
    var lanError by remember { mutableStateOf<String?>(null) }
    var joinProbing by remember { mutableStateOf(false) }
    var joinProbeStatus by remember { mutableStateOf<String?>(null) }
    var hostError by remember { mutableStateOf<String?>(null) }

    val running = state as? RealmState.Running
    val hosting = running?.mode == RuntimeMode.LAN_HOST
    val joined = running?.mode == RuntimeMode.LAN_JOIN

    val joinLan = {
        runCatching {
            // Endpoint model v2 (design-tunnel §4.4.3): the Join pane takes
            // a private/link-local LAN host OR the host's numeric tunnel
            // address (CGNAT 100.64/10). Hostnames and public IPv4 stay
            // refused by the same shared grammar the start builds with.
            val canonical = RealmEndpoint.parseJoinTarget(lanAddress).address
            RealmService.joinLan(context, canonical)
        }.onSuccess { lanError = null }
            .onFailure {
                lanError = it.message ?: "Enter a private, link-local, or tunnel (CGNAT) IPv4 address"
            }
        Unit
    }
    // D6 (design-lan §5.2, the desktop JoinProbeRow twin): the one-shot
    // reachability probe answers BEFORE a client launch; the failure copy
    // is mode-aware (a tunnel address only resolves inside the host's
    // tunnel — design-tunnel §5).
    val probeJoin = {
        scope.launch {
            joinProbing = true
            joinProbeStatus = null
            joinProbeStatus = runCatching {
                val target = RealmEndpoint.parseJoinTarget(lanAddress.trim()).address
                val result = withContext(Dispatchers.IO) { LanJoinProbe.probe(target) }
                when (result) {
                    is LanJoinProbe.ProbeResult.Reachable ->
                        "Realm answered at ${result.address}:3724 (its world port " +
                            "${result.address}:8085 is used once you log in)."
                    is LanJoinProbe.ProbeResult.Unreachable ->
                        if (LanJoinProbe.tunnelShapedAddress(target)) {
                            TunnelInvite.joinFailureHint(target)
                        } else {
                            RuntimeFailureCopy.humanize(LanJoinProbe.unreachableDetail(result))
                        }
                }
            }.getOrElse { it.message ?: "Enter a valid join address first" }
            joinProbing = false
        }
        Unit
    }
    val startHost = startHost@{
        val snap = snapshotState ?: return@startHost
        // Design-tunnel §4/§5: the stored advertised tunnel address is
        // validated HERE (the honest refusal beats a mid-start failure);
        // empty = plain LAN hosting, byte-identical behavior.
        val advertised = snap.tunnelAdvertisedAddress.trim().takeIf { it.isNotEmpty() }
        if (advertised != null) {
            val failure = runCatching { AdvertisedAddress.parse(advertised) }
                .exceptionOrNull()?.message
            if (failure != null) {
                hostError = failure
                return@startHost
            }
        }
        hostError = null
        val selection = BotSelection.resolve(
            savedPresetId = snap.botSavedPresetId,
            advancedEnabled = snap.botAdvancedEnabled,
            advancedTarget = snap.botPopulationTarget,
            advanced = snap.botAdvanced,
            profileId = snap.botProfileId,
        )
        RealmService.hostLan(context, selection.profile.id, includeClient = false, advertised = advertised)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth > 720.dp
        val hostPane: @Composable (Modifier) -> Unit = { modifier ->
            LanHostPane(
                state = state,
                settingsReady = snapshotState != null,
                allowLanPlayers = snapshot.allowLanPlayers,
                onAllowLanPlayers = { enabled ->
                    scope.launch { settings.update { it.copy(allowLanPlayers = enabled) } }
                },
                friendAccessEnabled = snapshot.friendAccessEnabled,
                onFriendAccess = { enabled ->
                    scope.launch { settings.update { it.copy(friendAccessEnabled = enabled) } }
                },
                tunnelAdvertisedAddress = snapshot.tunnelAdvertisedAddress,
                onTunnelAdvertised = { value ->
                    scope.launch {
                        settings.update { it.copy(tunnelAdvertisedAddress = value.trim()) }
                    }
                },
                hostError = hostError,
                onStartHost = startHost,
                onSaveExit = { RealmService.saveExit(context) },
                modifier = modifier,
            )
        }
        val joinPane: @Composable (Modifier) -> Unit = { modifier ->
            LanJoinPane(
                lanAddress = lanAddress,
                onLanAddress = { lanAddress = it; lanError = null; joinProbeStatus = null },
                onJoinLan = joinLan,
                onProbeJoin = probeJoin,
                probing = joinProbing,
                probeStatus = joinProbeStatus,
                lanError = lanError,
                joined = joined,
                joinedAddress = if (joined) running?.endpointAddress else null,
                enabled = !hosting && !joined && !realmBusy && clientUnavailableReason == null,
                clientUnavailableReason = clientUnavailableReason,
                modifier = modifier,
            )
        }
        if (twoPane) {
            Column(
                Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    hostPane(Modifier.weight(1f))
                    joinPane(Modifier.weight(1f))
                }
                if (hosting) {
                    FriendAccessHostPane(
                        supervisorClient = supervisorClient,
                        endpointAddress = running?.endpointAddress.orEmpty(),
                        advertisedAddress = running?.advertisedAddress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (joined) {
                    JoinedPlayersLine(supervisorClient)
                }
                RealmBrowserCard(
                    state = state,
                    modifier = Modifier.fillMaxWidth(),
                )
                PlannedLanSection(Modifier.fillMaxWidth())
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                hostPane(Modifier.fillMaxWidth())
                joinPane(Modifier.fillMaxWidth())
                if (hosting) {
                    FriendAccessHostPane(
                        supervisorClient = supervisorClient,
                        endpointAddress = running?.endpointAddress.orEmpty(),
                        advertisedAddress = running?.advertisedAddress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (snapshot.runtimeMode == RuntimeMode.LAN_JOIN) {
                    // design-remote-accounts: the join-mode device gains the
                    // Request access panel - the friend side of the invite
                    // flow, producing the same {username, sHex, vHex} payload.
                    FriendRequestPane(
                        suggestedHostAddress = lanAddress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (joined) {
                    JoinedPlayersLine(supervisorClient)
                }
                RealmBrowserCard(
                    state = state,
                    modifier = Modifier.fillMaxWidth(),
                )
                PlannedLanSection(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * F9 (census-backend-tools): the joined-mode players-online line — the
 * desktop Guests card's players-online poll twin over the SAME shared
 * contract (RuntimeSupervisorClient.worldPresence →
 * DurableRuntimeSupervisor.worldPresence → RuntimeBackend
 * .observeWorldPresence). Live shape only: while no world reports through
 * the contract (a joined device runs client-only, so its supervisor
 * truthfully samples no world), NOTHING renders —
 * DesktopAppModel.lanPlayersOnline's own null rule ("the card renders
 * nothing rather than guessing"), never a guessed count, never a promise
 * that one is coming.
 */
@Composable
private fun JoinedPlayersLine(
    supervisorClient: RuntimeSupervisorClient,
    modifier: Modifier = Modifier,
) {
    var players by remember { mutableStateOf<LanPresenceModel.LanPlayersOnline?>(null) }
    androidx.compose.runtime.LaunchedEffect(supervisorClient) {
        while (true) {
            players = LanPresenceModel.decode(supervisorClient.worldPresence())
            delay(LanPresenceModel.POLL_MS)
        }
    }
    LanPresenceModel.line(players)?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier.testTag("lan-joined-players"),
        )
    }
}

/**
 * The Realm Browser card (the desktop Realms route's twin, on the LAN
 * destination): this device's own realm, a probe-any-host LAN row over the
 * shared [LanJoinProbe] seam, and the honest internet-discovery empty
 * state — [RealmBrowserModel]'s copy is pinned and shared with the desktop.
 * The hosted realm-status page is a desktop-host surface; Android hosts do
 * not serve one, so no status row is rendered here (an absent row, never a
 * fake link).
 */
@Composable
private fun RealmBrowserCard(
    state: RealmState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var browserAddress by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    var probeStatus by remember { mutableStateOf<String?>(null) }
    // The live Public realms browser: connected while the card
    // shows, honestly idle when it does not.
    val publicBrowser = remember { PublicRealmBrowser(scope, RelayUrls.DEFAULTS) }
    androidx.compose.runtime.LaunchedEffect(Unit) { publicBrowser.browse() }
    androidx.compose.runtime.DisposableEffect(publicBrowser) {
        onDispose { publicBrowser.idle() }
    }
    val running = state as? RealmState.Running
    val hosting = running?.mode == RuntimeMode.LAN_HOST
    val joined = running?.mode == RuntimeMode.LAN_JOIN

    Card(modifier = modifier.testTag("realm-browser-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Realm browser",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("realm-browser-title"),
            )
            Text(
                RealmBrowserModel.thisDeviceCopy(
                    running = hosting || joined,
                    hosting = hosting,
                    endpointAddress = running?.endpointAddress,
                ).ifEmpty { "This device — realm stopped" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("realm-browser-this-device"),
            )
            OutlinedTextField(
                value = browserAddress,
                onValueChange = {
                    browserAddress = it
                    probeStatus = null
                },
                label = { Text("Host address (LAN or tunnel IPv4)") },
                supportingText = {
                    Text(
                        "A one-shot probe of the realm port ${LanJoinProbe.PROBE_PORT} " +
                            "answers before any launch.",
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("realm-browser-probe-address"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (!probing && browserAddress.isNotBlank()) {
                            probing = true
                            probeStatus = null
                            scope.launch {
                                probeStatus = runCatching {
                                    val target = com.pocketrealm.supervisor.RealmEndpoint
                                        .parseJoinTarget(browserAddress.trim()).address
                                    val result = withContext(Dispatchers.IO) {
                                        LanJoinProbe.probe(target)
                                    }
                                    when (result) {
                                        is LanJoinProbe.ProbeResult.Reachable ->
                                            "Realm answered at ${result.address}:${LanJoinProbe.PROBE_PORT}."
                                        is LanJoinProbe.ProbeResult.Unreachable ->
                                            if (LanJoinProbe.tunnelShapedAddress(target)) {
                                                TunnelInvite.joinFailureHint(target)
                                            } else {
                                                RuntimeFailureCopy.humanize(
                                                    LanJoinProbe.unreachableDetail(result),
                                                )
                                            }
                                    }
                                }.getOrElse { it.message ?: "Enter a valid host address first" }
                                probing = false
                            }
                        }
                        Unit
                    },
                    enabled = !probing && browserAddress.isNotBlank(),
                    modifier = Modifier.testTag("realm-browser-probe"),
                ) { Text(if (probing) "Probing…" else "Probe host") }
            }
            probeStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            PublicRealmsSection(publicBrowser)
        }
    }
}

/**
 * Planned LAN capabilities: designed, honestly labeled, and
 * deliberately not interactive until a real implementation ships. Nothing
 * here pretends to work today.
 */
@Composable
private fun PlannedLanSection(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Coming to LAN", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("lan-planned-section"))
        PlannedCard(
            title = "Move a character to another realm",
            tag = "lan-planned-transfer",
            body = "Export your character — with inventory, skills and quest log — from this " +
                "realm into a signed transfer file. The receiving realm imports it with fresh " +
                "identifiers and a name-conflict check, so a character built here can move to a " +
                "friend's hosted world (and back) without overwriting anyone.",
        )
        PlannedCard(
            title = "Sync your realm between devices",
            tag = "lan-planned-sync",
            body = "Versioned save bundles that move your whole realm — accounts, characters " +
                "and world state — between your own devices. The newest save wins by default, " +
                "with a side-by-side choice whenever two devices changed the same character.",
        )
        // "Play over the internet" is NOT listed here: the tunnel-hosting
        // lane SHIPS — the Host pane above carries the advertise-over-the-
        // internet field (validated tunnel addresses + adapter quick-picks),
        // and the Friend access pane renders the tunnel invite block. A
        // "Coming soon" card describing a capability this same screen
        // provides would break this section's own contract ("Nothing here
        // pretends to work today").
    }
}

@Composable
private fun PlannedCard(title: String, tag: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Coming soon",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LanHostPane(
    state: RealmState,
    settingsReady: Boolean,
    allowLanPlayers: Boolean,
    onAllowLanPlayers: (Boolean) -> Unit,
    friendAccessEnabled: Boolean,
    onFriendAccess: (Boolean) -> Unit,
    tunnelAdvertisedAddress: String,
    onTunnelAdvertised: (String) -> Unit,
    hostError: String?,
    onStartHost: () -> Unit,
    onSaveExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = state as? RealmState.Running
    val hosting = running?.mode == RuntimeMode.LAN_HOST
    val joined = running?.mode == RuntimeMode.LAN_JOIN
    Card(modifier = modifier.testTag("lan-host-pane")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Host", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    hosting -> "Hosting on ${running.endpointAddress}" +
                        running.advertisedAddress?.let { " — advertising $it (tunnel)" }.orEmpty()
                    joined -> "Joined to ${running.endpointAddress}; stop the client session before hosting"
                    running != null -> "Local realm already online"
                    state is RealmState.Starting -> "Realm starting…"
                    state is RealmState.Saving || state is RealmState.Stopping -> "Realm busy…"
                    state is RealmState.Recovering -> "Recovering…"
                    state is RealmState.Failed -> "Last start failed; try again"
                    // Design-lan D9: an interface-loss stop explains itself
                    // (the supervisor snapshot carries the reason; the shared
                    // copy is humanized at the decode boundary) — a network
                    // change mid-hosting never presents as a clean,
                    // unexplained stop.
                    state is RealmState.Idle && state.stopCopy != null -> state.stopCopy!!
                    else -> "Local realm stopped"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("lan-host-status"),
            )
            Text(
                "Hosting binds realmd and the world to the exact active private IPv4 interface. " +
                    "Ports 3724 and 8085 only; MariaDB, RA, and SOAP remain private or disabled.",
                style = MaterialTheme.typography.bodySmall,
            )
            // Design-tunnel §4.4.2 disclosure (the desktop Home card twin):
            // tunnel advertising binds the WILDCARD, so the game ports also
            // listen on every local interface — the local LAN included. The
            // host gets that stated, not just the tunnel-only story.
            if (hosting && !running?.advertisedAddress.isNullOrBlank() &&
                running?.advertisedAddress != running?.endpointAddress
            ) {
                Text(
                    "While tunnel advertising is on, the game ports also listen on this " +
                        "device's other networks (your local LAN included) — the tunnel is " +
                        "not a wall around them.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = allowLanPlayers,
                    onCheckedChange = onAllowLanPlayers,
                    modifier = Modifier.testTag("allow-lan-players"),
                )
                Text("  Allow LAN players", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = friendAccessEnabled,
                    onCheckedChange = onFriendAccess,
                    modifier = Modifier.testTag("friend-access-switch"),
                )
                Text(
                    "  Allow friend access (invite codes, port " +
                        ServerRuntimeContract.PROVISION_PORT + ")",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Design-tunnel §4/§5 (the desktop TunnelAdvertisedField twin):
            // the advertised tunnel address — internet hosting rides
            // Tailscale/ZeroTier, the servers bind all interfaces, and the
            // one realmlist row carries THIS address (never the LAN IP)
            // while it is set. Validated for display through the shared v2
            // grammar; the start re-validates.
            val advertisedHint = remember(tunnelAdvertisedAddress) {
                when {
                    tunnelAdvertisedAddress.isBlank() -> null
                    else -> runCatching {
                        AdvertisedAddress.parse(tunnelAdvertisedAddress.trim())
                    }.exceptionOrNull()?.message
                }
            }
            OutlinedTextField(
                value = tunnelAdvertisedAddress,
                onValueChange = onTunnelAdvertised,
                label = { Text("Advertise over the internet — tunnel address (e.g. 100.64.1.20)") },
                isError = tunnelAdvertisedAddress.isNotBlank() && advertisedHint != null,
                supportingText = {
                    Text(
                        when {
                            tunnelAdvertisedAddress.isBlank() ->
                                "Optional. Host over the internet through Tailscale or ZeroTier: " +
                                    "paste this host's tunnel address. Leave empty for LAN-only hosting."
                            advertisedHint != null -> advertisedHint ?: "invalid tunnel address"
                            else -> "The realm will advertise this address. Plain-LAN guests will " +
                                "still see the realm, but only friends inside the tunnel can reach it."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("tunnel-advertised-address"),
            )
            val tunnelAddresses = remember {
                runCatching { TunnelInterfacePolicy.available() }.getOrDefault(emptyList())
            }
            if (tunnelAddresses.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tunnelAddresses.take(MAX_INTERFACE_QUICK_PICKS).forEach { advertised ->
                        OutlinedButton(onClick = { onTunnelAdvertised(advertised.value) }) {
                            Text("Use ${advertised.value}")
                        }
                    }
                }
            }
            if (tunnelAdvertisedAddress.isNotBlank() && advertisedHint == null) {
                Text(
                    "How to host over the internet: 1) Install Tailscale (tailscale.com) on this " +
                        "device and on each friend's device, and join the same tailnet (ZeroTier " +
                        "works too). 2) Paste this host's tunnel address above — the quick-pick " +
                        "buttons show detected tunnel adapters. 3) Start hosting; the Friend access " +
                        "card's invite has the exact line your friends paste into realmlist.wtf. " +
                        "While tunnel mode is on, plain-LAN guests see a realm they cannot enter " +
                        "(one advertisement for everyone).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            hostError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (hosting) {
                OutlinedButton(
                    onClick = onSaveExit,
                    modifier = Modifier.fillMaxWidth().testTag("lan-host-stop"),
                ) { Text("Stop hosting & save") }
            } else {
                Button(
                    onClick = onStartHost,
                    enabled = settingsReady &&
                        (state is RealmState.Idle ||
                            state is RealmState.Failed ||
                            state is RealmState.Recovering),
                    modifier = Modifier.fillMaxWidth().testTag("lan-host-start"),
                ) { Text("Start host") }
            }
        }
    }
}

@Composable
private fun LanJoinPane(
    lanAddress: String,
    onLanAddress: (String) -> Unit,
    onJoinLan: () -> Unit,
    onProbeJoin: () -> Unit,
    probing: Boolean,
    probeStatus: String?,
    lanError: String?,
    joined: Boolean,
    joinedAddress: String?,
    enabled: Boolean,
    clientUnavailableReason: String? = null,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.testTag("lan-join-pane")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Join", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (joined) {
                Text(
                    "Connected to $joinedAddress",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("lan-join-status"),
                )
                Text(
                    "Log in with an account from that host. Save & exit returns you to your own realm.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                clientUnavailableReason?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = lanAddress,
                    onValueChange = onLanAddress,
                    label = { Text("Host IPv4") },
                    supportingText = {
                        Text(
                            lanError
                                ?: "Private, link-local, or tunnel (CGNAT) IPv4; " +
                                    "ports 3724 and 8085 are fixed",
                        )
                    },
                    isError = lanError != null,
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("lan-join-address"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onJoinLan,
                        enabled = enabled,
                        modifier = Modifier.weight(1f).testTag("lan-join"),
                    ) { Text("Join LAN") }
                    // D6 (design-lan §5.2): the one-shot probe answers
                    // BEFORE the launch, with the mode-aware failure copy.
                    OutlinedButton(
                        onClick = onProbeJoin,
                        enabled = enabled && lanAddress.isNotBlank() && !probing,
                        modifier = Modifier.testTag("lan-join-probe"),
                    ) { Text(if (probing) "Probing" + "\u2026" else "Test") }
                }
                probeStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Discovery, hostnames, IPv6, UPnP, and mDNS are not used.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private const val FRIEND_REQUEST_FIELD_MAX_CHARS = 16
private const val MAX_INTERFACE_QUICK_PICKS = 3
private const val FRIEND_ROSTER_POLL_MS = 5_000L
private const val FRIEND_STATE_POLL_MS = 4_000L

/** The desktop PlayersScreen twin's moderation surface constants: the
 * honest per-level permission labels (level 3 is "full console",
 * never an unlabeled "gm3") and the ban-reason cap. */
private val GM_LEVELS = listOf(0 to "Player", 1 to "Helper", 2 to "Moderator", 3 to "Full console")
private const val BAN_REASON_MAX_CHARS = 96

/**
 * The HOST side of friend access on Android (design-remote-accounts §6/§11):
 * the provisioning listener's live state, the invite composer (the code
 * shows ONCE — the raw token is never stored), the pending-request cards
 * with Approve/Reject, and the roster with ban/unban + gmlevel — all
 * through the shared supervisor contract (RuntimeSupervisorClient →
 * IRuntimeSupervisorControl), the same seam the desktop Players surface
 * rides. The host's OWN account stays the Home account card.
 */
@Composable
private fun FriendAccessHostPane(
    supervisorClient: RuntimeSupervisorClient,
    endpointAddress: String,
    advertisedAddress: String?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var stateJson by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var rosterJson by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var note by remember { mutableStateOf("") }
    var freshCode by remember { mutableStateOf<Pair<String, String>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refreshState() {
        stateJson = runCatching { supervisorClient.friendAccessState() }.getOrNull()
    }
    suspend fun refreshRoster() {
        rosterJson = runCatching { supervisorClient.friendRoster() }.getOrNull()
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            refreshState()
            refreshRoster()
            delay(FRIEND_ROSTER_POLL_MS)
        }
    }

    Card(modifier = modifier.testTag("friend-access-host-pane")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Friend access", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val listener = stateJson?.optJSONObject("listener")
            Text(
                when {
                    listener?.optString("kind") == "Failed" ->
                        "Listener FAILED to bind: " + listener.optString("reason")
                    listener?.optString("kind") == "Listening" ->
                        "Listening on " + listener.optString("address") + ":" + listener.optString("port") +
                            " — friend requests reach the host over your shared LAN while this realm is up."
                    else -> "Off — nothing listens while friend access is off or the realm is not hosting."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("friend-access-listener-state"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(64) },
                    label = { Text("Note (e.g. \"Dave\")") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("friend-invite-note"),
                )
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val created = runCatching { supervisorClient.createFriendInvite(note) }.getOrNull()
                            if (created?.optBoolean("ok") == true) {
                                freshCode = created.optString("inviteId") to created.optString("token")
                                status = null
                            } else {
                                // structured refusal copy: a cap
                                // refusal and an unreachable supervisor
                                // are different situations with different
                                // remedies - never one guessed line
                                status = FriendInviteStatusCopy.statusFor(created)
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag("friend-invite-create"),
                ) { Text("Invite") }
            }
            freshCode?.let { (inviteId, token) ->
                Text(
                    "Invite $inviteId — give this code to your friend (shown only once):",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    token,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("friend-invite-code"),
                )
                OutlinedButton(onClick = { freshCode = null }) { Text("Done") }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            // Design-tunnel §6 / design-lan D4 (the shared TunnelInvite
            // artifact): the copyable ADDRESS BLOCK ONLY — it carries the
            // live advertisement (the tunnel address when tunnel mode is
            // on, never the LAN IP then) and NEVER a credential. Mode-honest
            // split: with no tunnel advertisement this is the LAN lane, so
            // the block is the no-tunnel LAN invite — a LAN address is never
            // dressed up as a tunnel setup.
            val clipboard = LocalClipboardManager.current
            val inviteBlock = if (advertisedAddress.isNullOrBlank()) {
                TunnelInvite.lanInviteText(
                    realmName = "my realm",
                    lanAddress = endpointAddress,
                )
            } else {
                val inviteKind = runCatching { AdvertisedAddress.parse(advertisedAddress) }
                    .getOrNull()
                    ?.let { TunnelInvite.tunnelKindFor(it) }
                    ?: TunnelInvite.TunnelKind.OTHER
                TunnelInvite.inviteText(
                    realmName = "my realm",
                    advertised = advertisedAddress,
                    kind = inviteKind,
                )
            }
            Text(
                "Invite block (the line your friend pastes into realmlist.wtf):",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                inviteBlock,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("friend-invite-block"),
            )
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(inviteBlock))
                },
                modifier = Modifier.testTag("friend-invite-copy"),
            ) { Text("Copy invite block") }

            // Guest logins (design-lan D5a, the desktop Guests card twin):
            // the host types the name, the password is GENERATED on this
            // device (80-bit, unambiguous alphabet), provisioned at gmLevel
            // 0 through the createAccount relay, and handed over PRIVATELY
            // — the invite block above never carries a credential.
            var guestName by remember { mutableStateOf("") }
            var guestPending by remember { mutableStateOf(false) }
            var guestStatus by remember { mutableStateOf<String?>(null) }
            var guestHandoff by remember { mutableStateOf<Pair<String, String>?>(null) }
            val guestNameValid = guestName.matches(Regex("[A-Za-z0-9]{1,16}"))
            Text("Guest login", style = MaterialTheme.typography.titleSmall)
            Text(
                "Create an account for a friend without the app — in the room with you, " +
                    "or a tunnel friend you are setting up at your invite's direction. " +
                    "The password is generated for you.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = guestName,
                    onValueChange = { guestName = it.take(16) },
                    label = { Text("Guest account name") },
                    isError = guestName.isNotEmpty() && !guestNameValid,
                    supportingText = { Text(if (guestName.isNotEmpty() && !guestNameValid) "1-16 letters or numbers" else "1-16 letters or numbers") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("guest-account-name"),
                )
                Button(
                    onClick = {
                        guestPending = true
                        guestStatus = null
                        scope.launch {
                            val password = GuestCredentials.generatePassword()
                            val created = runCatching {
                                supervisorClient.createAccount(guestName.trim(), password, 0)
                            }.getOrNull()
                            if (created?.optBoolean("ok") == true) {
                                guestHandoff = guestName.trim().uppercase() to password
                            } else {
                                guestStatus = "Refused (" +
                                    (created?.optString("code")
                                        ?.takeIf { it.isNotEmpty() } ?: "no answer") +
                                    ") — try another name."
                            }
                            guestPending = false
                        }
                    },
                    enabled = !guestPending && guestNameValid,
                    modifier = Modifier.testTag("guest-account-create"),
                ) { Text(if (guestPending) "Creating…" else "Create") }
            }
            guestStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            guestHandoff?.let { (name, password) ->
                Text(
                    "Give this to your friend privately — in person, or a one-to-one " +
                        "message for a tunnel friend: username $name, password $password — " +
                        "they type it at the login screen. Don't paste it into a group chat.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("guest-handoff"),
                )
                val clipboard = LocalClipboardManager.current
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString("username $name password $password"))
                }) { Text("Copy as one line") }
            }

            val invites = stateJson?.optJSONArray("invites")
            if (invites != null) {
                for (index in 0 until invites.length()) {
                    val row = invites.optJSONObject(index) ?: continue
                    when (row.optString("state")) {
                        "SUBMITTED" -> {
                            // Plan rule 7: peer addresses are NEVER
                            // displayed - the request row names the account
                            // only (the sourceIp stays in the audit trail
                            // the diagnostics lane already guards).
                            Text(
                                "Request: " + row.optString("username"),
                                modifier = Modifier.testTag("friend-request-row"),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            val verdict = runCatching {
                                                supervisorClient.approveFriendRequest(row.optString("inviteId"))
                                            }.getOrNull()
                                            status = verdict?.optString("verdict")
                                            refreshState(); refreshRoster()
                                            busy = false
                                        }
                                    },
                                    enabled = !busy,
                                ) { Text("Approve") }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        runCatching { supervisorClient.rejectFriendRequest(row.optString("inviteId")) }
                                        refreshState()
                                    }
                                }) { Text("Reject") }
                            }
                        }
                        "OPEN" -> Row {
                            Text(
                                row.optString("inviteId") + " — waiting for your friend to submit",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = {
                                scope.launch {
                                    runCatching { supervisorClient.revokeFriendInvite(row.optString("inviteId")) }
                                    refreshState()
                                }
                            }) { Text("Revoke") }
                        }
                    }
                }
            }

            val roster = rosterJson
            if (roster != null && roster.optBoolean("ok")) {
                val accounts = roster.optJSONArray("accounts")
                if (accounts != null && accounts.length() > 0) {
                    Text("Players", style = MaterialTheme.typography.titleSmall)
                    var onlineCount = 0
                    var banTarget by remember { mutableStateOf<String?>(null) }
                    for (index in 0 until accounts.length()) {
                        val row = accounts.optJSONObject(index) ?: continue
                        if (row.optBoolean("online")) onlineCount += 1
                        Text(
                            row.optString("username") + " · account " + row.optLong("id") + " · " +
                                (if (row.optBoolean("online")) "online" else "offline") +
                                (if (row.optBoolean("banned")) " · BANNED" else ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (row.optBoolean("banned")) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        runCatching {
                                            supervisorClient.unbanFriendAccount(row.optString("username"))
                                        }
                                        refreshRoster()
                                    }
                                }) { Text("Unban") }
                            } else {
                                OutlinedButton(onClick = { banTarget = row.optString("username") }) {
                                    Text("Ban…")
                                }
                            }
                            GM_LEVELS.forEach { (level, label) ->
                                if (row.optInt("gmlevel") != level) {
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            runCatching {
                                                supervisorClient.setFriendGmLevel(row.optString("username"), level)
                                            }
                                            refreshRoster()
                                        }
                                    }) { Text(label) }
                                }
                            }
                        }
                        Text(
                            "Full console outranks these moderation tools — grant it only when you mean it.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "$onlineCount of ${accounts.length()} in world — failed-logins and last-logon " +
                            "detail live on the desktop Players screen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    banTarget?.let { username ->
                        FriendBanDialog(
                            username = username,
                            onDismiss = { banTarget = null },
                            onBan = { duration, reason ->
                                banTarget = null
                                scope.launch {
                                    runCatching {
                                        supervisorClient.banFriendAccount(username, duration, reason)
                                    }
                                    refreshRoster()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The shared moderation surface (design-remote-accounts §9, the desktop
 * PlayersScreen BanDialog twin): an editable reason kept in the ban record
 * plus the 1h/24h/permanent presets — an Android host can permanently ban
 * a misbehaving friend, exactly like the desktop host. Duration "0" is the
 * relay's permanent-ban convention (same strings the desktop dialog sends).
 */
@Composable
private fun FriendBanDialog(
    username: String,
    onDismiss: () -> Unit,
    onBan: (duration: String, reason: String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ban $username") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(BAN_REASON_MAX_CHARS) },
                    label = { Text("Reason (kept in the ban record)") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onBan("1h", reason) }) { Text("1 hour") }
                    TextButton(onClick = { onBan("1d", reason) }) { Text("24 hours") }
                    TextButton(onClick = { onBan("0", reason) }) { Text("Permanent") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The JOIN side of friend access on Android (design-remote-accounts §3/§5):
 * host address, invite code, desired username, password typed twice — the
 * password derives an SRP6 verifier ON THIS DEVICE and only
 * {username, sHex, vHex} crosses to the host. On approval the account is
 * saved for auto-login exactly like the host's account card; on refusal
 * nothing derived from the password survives.
 */
@Composable
private fun FriendRequestPane(
    suggestedHostAddress: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hostAddress by remember { mutableStateOf(suggestedHostAddress) }
    var inviteId by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordRepeat by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var savedAccountId by remember { mutableStateOf(0L) }

    // Design-remote-accounts §6 (the desktop JoinRequestAccessCard twin):
    // the provisioning listener binds the HOST'S PRIVATE LAN address only,
    // so this field validates the LAN grammar (parseLan — private/link-local
    // only, hostnames and public IPv4 refused) and the card carries the
    // note. A tunnel (CGNAT) address can join the realm through the Join
    // pane, but it can never reach this card — green-lighting it here would
    // only guarantee a "could not reach" after the connect timeout.
    val addressValid = runCatching { RealmEndpoint.parseLan(hostAddress.trim()) }.isSuccess
    val fieldsValid = addressValid && inviteId.isNotBlank() && code.isNotBlank() &&
        username.matches(Regex("[A-Za-z0-9]{1,16}")) &&
        password.matches(Regex("[A-Za-z0-9]{1,16}")) && password == passwordRepeat

    Card(modifier = modifier.testTag("friend-request-pane")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Request an account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Pick your own name and password — the password never leaves this device. " +
                    "The host sees only a verifier and approves you by name.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = hostAddress,
                onValueChange = { hostAddress = it.take(15) },
                label = { Text("Host IPv4") },
                isError = hostAddress.isNotEmpty() && !addressValid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("friend-request-host"),
            )
            Text(
                "This card reaches the host over your shared LAN only — a tunnel " +
                    "address joins the realm but cannot submit a request.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("friend-request-lan-only-note"),
            )
            OutlinedTextField(
                value = inviteId,
                onValueChange = { inviteId = it.take(16) },
                label = { Text("Invite id (FRD-…)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("friend-request-invite-id"),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.trim() },
                label = { Text("Invite code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("friend-request-code"),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.take(FRIEND_REQUEST_FIELD_MAX_CHARS) },
                label = { Text("Desired account name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("friend-request-username"),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(FRIEND_REQUEST_FIELD_MAX_CHARS) },
                label = { Text("Password") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("friend-request-password"),
            )
            OutlinedTextField(
                value = passwordRepeat,
                onValueChange = { passwordRepeat = it.take(FRIEND_REQUEST_FIELD_MAX_CHARS) },
                label = { Text("Repeat password") },
                isError = passwordRepeat.isNotEmpty() && password != passwordRepeat,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("friend-request-password-repeat"),
            )
            Button(
                onClick = {
                    pending = true
                    status = "Sending request to " + hostAddress.trim() + "…"
                        scope.launch {
                            val outcome = requestFriendAccess(
                                transport = RealFriendAccessTransport(FriendAccessClient()),
                                host = hostAddress.trim(),
                                port = ServerRuntimeContract.PROVISION_PORT,
                                inviteId = inviteId.trim(),
                                token = code.trim(),
                                username = username.trim(),
                                password = password,
                                onApproved = { accountId ->
                                    // (design-remote-accounts §5): save for
                                    // auto-login exactly like the host's account card.
                                    savedAccountId = runCatching {
                                        UserAccountStore(context).save(username.trim(), password, accountId)
                                        accountId
                                    }.getOrDefault(0L)
                                },
                                onWaitUpdate = { waitStatus -> status = waitStatus },
                            )
                            when (outcome) {
                                is FriendRequestOutcome.Approved -> {
                                    status = if (savedAccountId > 0) {
                                        "Approved — account $savedAccountId saved; join the host and play."
                                    } else {
                                        "Approved as account " + outcome.accountId + ", but saving it failed — " +
                                            "log in with this name and password."
                                    }
                                }
                                FriendRequestOutcome.Refused ->
                                    // the refusal cause is
                                    // indistinguishable by protocol design -
                                    // never assert one, never invite retries
                                    // against a rate lock.
                                    status = joinerRefusalCopy()
                                is FriendRequestOutcome.Unreachable -> status = outcome.reason
                                FriendRequestOutcome.Submitted ->
                                    // The poll budget spans the host's whole
                                    // 15-minute decision window: no decision means
                                    // the request expired host-side. Say the checking
                                    // stopped and what to do next — never a
                                    // present-tense "the host has not decided yet".
                                    status = "Stopped checking after 15 minutes — the host's " +
                                        "decision window closed without a decision, so this " +
                                        "request has expired. Ask for a fresh invite and " +
                                        "submit again."
                            }
                            pending = false
                        }
                },
                enabled = !pending && fieldsValid,
                modifier = Modifier.fillMaxWidth().testTag("friend-request-submit"),
            ) { Text(if (pending) "Waiting for the host…" else "Request access") }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
