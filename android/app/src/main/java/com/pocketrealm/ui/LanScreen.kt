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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import com.pocketrealm.client.VulkanDriverCatalog
import com.pocketrealm.realm.RealmState
import com.pocketrealm.service.RealmService
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimeSupervisorClient
import com.pocketrealm.supervisor.RealmEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated LAN destination (brief §44). Owns host/join networking only;
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
    }.collectAsState(initial = RealmState.Idle)
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
            "dxvk" -> VulkanDriverCatalog.availabilityForPair(
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

    val running = state as? RealmState.Running
    val hosting = running?.mode == RuntimeMode.LAN_HOST
    val joined = running?.mode == RuntimeMode.LAN_JOIN

    val joinLan = {
        runCatching {
            val canonical = RealmEndpoint.parseLan(lanAddress).address
            RealmService.joinLan(context, canonical)
        }.onSuccess { lanError = null }
            .onFailure { lanError = it.message ?: "Enter a private IPv4 address" }
        Unit
    }
    val startHost = startHost@{
        val snap = snapshotState ?: return@startHost
        val selection = BotSelection.resolve(
            savedPresetId = snap.botSavedPresetId,
            advancedEnabled = snap.botAdvancedEnabled,
            advancedTarget = snap.botPopulationTarget,
            advanced = snap.botAdvanced,
            profileId = snap.botProfileId,
        )
        RealmService.hostLan(context, selection.profile.id, includeClient = false)
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
                onStartHost = startHost,
                onSaveExit = { RealmService.saveExit(context) },
                modifier = modifier,
            )
        }
        val joinPane: @Composable (Modifier) -> Unit = { modifier ->
            LanJoinPane(
                lanAddress = lanAddress,
                onLanAddress = { lanAddress = it; lanError = null },
                onJoinLan = joinLan,
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
                if (hosting || joined) {
                    Text(
                        "Connected players: no remote player tracking yet; this section appears when the realm reports sessions.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                PlannedLanSection(Modifier.fillMaxWidth())
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                hostPane(Modifier.fillMaxWidth())
                joinPane(Modifier.fillMaxWidth())
                if (hosting || joined) {
                    Text(
                        "Connected players: no remote player tracking yet; this section appears when the realm reports sessions.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                PlannedLanSection(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Planned LAN capabilities (brief §45): designed, honestly labeled, and
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
        PlannedCard(
            title = "Play over the internet",
            tag = "lan-planned-tunnel",
            body = "A host can publish their realm through an encrypted tunnel so friends " +
                "connect from anywhere — no port forwarding — and travellers can connect to a " +
                "tunnel address the same way they enter a LAN address today. Connections stay " +
                "authenticated end to end; nothing is routed until you explicitly start a tunnel.",
        )
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
                    hosting -> "Hosting on ${running.endpointAddress}"
                    joined -> "Joined to ${running.endpointAddress}; stop the client session before hosting"
                    running != null -> "Local realm already online"
                    state is RealmState.Starting -> "Realm starting…"
                    state is RealmState.Saving || state is RealmState.Stopping -> "Realm busy…"
                    state is RealmState.Recovering -> "Recovering…"
                    state is RealmState.Failed -> "Last start failed; try again"
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = allowLanPlayers,
                    onCheckedChange = onAllowLanPlayers,
                    modifier = Modifier.testTag("allow-lan-players"),
                )
                Text("  Allow LAN players", style = MaterialTheme.typography.bodyMedium)
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
                                ?: "Private/link-local IPv4; ports 3724 and 8085 are fixed",
                        )
                    },
                    isError = lanError != null,
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("lan-join-address"),
                )
                Button(
                    onClick = onJoinLan,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("lan-join"),
                ) { Text("Join LAN") }
                Text(
                    "Discovery, hostnames, IPv6, UPnP, and mDNS are not used.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
