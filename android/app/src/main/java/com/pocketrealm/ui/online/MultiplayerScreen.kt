package com.pocketrealm.ui.online

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketrealm.client.AndroidGladioCapabilityProbe
import com.pocketrealm.client.AndroidSystemVulkanProbe
import com.pocketrealm.client.ArmClientRenderer
import com.pocketrealm.client.ArmClientRendererCatalog
import com.pocketrealm.client.ArmRendererAuto
import com.pocketrealm.client.GladioCapability
import com.pocketrealm.client.SystemVulkanCapabilities
import com.pocketrealm.client.UserVulkanDriverResolution
import com.pocketrealm.onlineplay.HostBridge
import kotlinx.coroutines.launch
import com.pocketrealm.realm.RealmState
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimeSupervisorClient
import com.pocketrealm.ui.online.OnlinePlayCopy as Copy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The Multiplayer destination (route "lan"): four tabs — Find a game, Host,
 * Direct join, Transfer. The wording and the list logic live in
 * [OnlinePlayModel]; the technical controls live under each tab's Advanced
 * fold.
 */
@Composable
fun MultiplayerScreen() {
    val context = LocalContext.current
    val supervisorClient = remember(context) { RuntimeSupervisorClient(context) }
    val state by remember(supervisorClient) {
        supervisorClient.observeRealmState()
    }.collectAsState(initial = RealmState.Idle())
    val settings = remember(context) { Settings(context) }
    val snapshotState: Settings.Snapshot? by settings.flow.collectAsState(initial = null)
    val snapshot = snapshotState ?: Settings.Snapshot()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // One-time: the old "signed up here" set becomes the saved-logins store.
    remember(context) {
        HostBridge.migrateOldSignups(context, HostBridge.savedLogins(context))
        true
    }

    val systemVulkanProbe by produceState<Result<SystemVulkanCapabilities>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { AndroidSystemVulkanProbe.probe() } }
    }
    val gladioProbe by produceState<Result<GladioCapability>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching { AndroidGladioCapabilityProbe.probe(context) }
        }
    }
    // A join is a client-only session; it cannot start when the selected
    // renderer stack is unavailable on this device.
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

    val running = state as? RealmState.Running
    val hosting = running?.mode == RuntimeMode.LAN_HOST
    val joined = running?.mode == RuntimeMode.LAN_JOIN
    val internetJoined = joined && running?.endpointAddress == com.pocketrealm.supervisor.RealmEndpoint.LOOPBACK_ADDRESS
    val realmBusy = !(state is RealmState.Idle || state is RealmState.Failed || state is RealmState.Recovering)

    // The hosted game's status (one read per second while hosting).
    var hostingStatus by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(hosting) {
        while (hosting) {
            hostingStatus = HostBridge.status(supervisorClient)
            kotlinx.coroutines.delay(2_000L)
        }
        hostingStatus = null
    }

    var tab by rememberSaveable {
        mutableStateOf(
            if (joined && !internetJoined) MpTab.DIRECT_JOIN.name else MpTab.FIND.name,
        )
    }
    val selected = runCatching { MpTab.valueOf(tab) }.getOrDefault(MpTab.FIND)
    val joinEnabled = !hosting && !joined && !realmBusy && clientUnavailableReason == null

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = selected.ordinal, edgePadding = 8.dp) {
            MpTab.entries.forEach { entry ->
                Tab(
                    selected = entry == selected,
                    onClick = { tab = entry.name },
                    text = { Text(entry.label) },
                    modifier = Modifier.testTag("mp-tab-" + entry.name.lowercase()),
                )
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (selected) {
                MpTab.FIND -> FindGameTab(
                    joinEnabled = joinEnabled,
                    clientUnavailableReason = clientUnavailableReason,
                    supervisorClient = supervisorClient,
                    modifier = Modifier.fillMaxWidth(),
                )
                MpTab.HOST -> HostTab(
                    hosting = hosting,
                    hostingStatusJson = hostingStatus,
                    settingsReady = snapshotState != null,
                    snapshot = snapshot,
                    onSettings = { transform ->
                        scope.launch { settings.update(transform) }
                    },
                    supervisorClient = supervisorClient,
                    modifier = Modifier.fillMaxWidth(),
                )
                MpTab.DIRECT_JOIN -> DirectJoinTab(
                    joined = joined,
                    joinedAddress = if (joined) running?.endpointAddress else null,
                    joinEnabled = joinEnabled,
                    clientUnavailableReason = clientUnavailableReason,
                    supervisorClient = supervisorClient,
                    modifier = Modifier.fillMaxWidth(),
                )
                MpTab.TRANSFER -> TransferTab(
                    realmRunning = running != null && !joined,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The four tabs (the route name stays "lan" so saved state still works). */
private enum class MpTab(val label: String) {
    FIND(Copy.TAB_FIND),
    HOST(Copy.TAB_HOST),
    DIRECT_JOIN(Copy.TAB_DIRECT_JOIN),
    TRANSFER(Copy.TAB_TRANSFER),
}
