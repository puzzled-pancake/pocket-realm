package com.pocketrealm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.desktop.realmstatus.RealmStatusServer
import com.pocketrealm.desktop.realmstatus.RealmStatusVisibility
import com.pocketrealm.supervisor.RuntimeFailureCopy
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.ui.LanJoinProbe
import com.pocketrealm.ui.RealmBrowserModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Realm Browser (the multi-flavor/ discovery surface): LAN realms
 * through the shared [LanJoinProbe] seam (this device's own realm, a
 * probe-any-host row), the hosted realm-status page link (the live URL
 * while the page serves, the honest off-copy when it does not), and the
 * honest internet-discovery empty state (relay/Nostr discovery is a
 * future arc — nothing scans or lists internet realms today, and this
 * screen never pretends otherwise). The Android LAN destination's browser
 * card carries the shared [RealmBrowserModel] copy twin.
 */
@Composable
fun RealmBrowserScreen(model: DesktopAppModel) {
    val snapshot by model.state.collectAsState()
    val statusState by model.realmStatus.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    var probeAddress by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<String?>(null) }
    val probe: () -> Unit = {
        if (!probing && probeAddress.isNotBlank()) {
            probing = true
            probeResult = null
            scope.launch {
                probeResult = runCatching {
                    val target = probeAddress.trim()
                    val result = withContext(Dispatchers.IO) { LanJoinProbe.probe(target) }
                    when (result) {
                        is LanJoinProbe.ProbeResult.Reachable ->
                            "Realm answered at ${result.address}:${LanJoinProbe.PROBE_PORT} " +
                                "(the world port ${result.address}:${LanJoinProbe.WORLD_PORT} " +
                                "is used once you log in)."
                        is LanJoinProbe.ProbeResult.Unreachable ->
                            RuntimeFailureCopy.humanize(LanJoinProbe.unreachableDetail(result))
                    }
                }.getOrElse { it.message ?: "Enter a valid host address first" }
                probing = false
            }
        }
        Unit
    }

    val worldUp = snapshot.phase == RuntimePhase.WORLD_READY ||
        snapshot.phase == RuntimePhase.RUNNING ||
        snapshot.phase == RuntimePhase.PAUSED ||
        snapshot.phase == RuntimePhase.CLIENT_FAILED
    val hosting = snapshot.runtimeMode == RuntimeMode.LAN_HOST && worldUp
    val joined = snapshot.runtimeMode == RuntimeMode.LAN_JOIN
    // The hosted realm-status page: FRIENDS serves beside the game's own
    // address; PUBLIC rides the page-only tunnel's shape-gated public URL.
    val statusUrl = when {
        statusState.visibility == RealmStatusVisibility.PUBLIC -> statusState.tunnel.url
        statusState.visibility == RealmStatusVisibility.FRIENDS ->
            RealmBrowserModel.statusPageUrl(
                visibilityOn = true,
                lanAddress = snapshot.realmEndpoint.address,
                pagePort = RealmStatusServer.PAGE_PORT,
            )
        else -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Realm browser",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("realm-browser-title"),
        )
        Card(modifier = Modifier.fillMaxWidth().testTag("realm-browser-lan")) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "LAN realms",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                RealmBrowserModel.thisDeviceCopy(
                    running = hosting || joined,
                    hosting = hosting,
                    endpointAddress = snapshot.realmEndpoint.address,
                ).let { thisDevice ->
                    Text(
                        thisDevice,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("realm-browser-this-device"),
                    )
                }
                OutlinedTextField(
                    value = probeAddress,
                    onValueChange = {
                        probeAddress = it
                        probeResult = null
                    },
                    label = { Text("Host address (LAN or tunnel IPv4)") },
                    supportingText = {
                        Text("A one-shot probe of the realm port ${LanJoinProbe.PROBE_PORT} answers before any launch.")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("realm-browser-probe-address"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = probe,
                        enabled = !probing && probeAddress.isNotBlank(),
                        modifier = Modifier.testTag("realm-browser-probe"),
                    ) { Text(if (probing) "Probing…" else "Probe host") }
                }
                probeResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Discovery, hostnames, IPv6, UPnP, and mDNS are not used — a realm is " +
                        "reachable exactly at the address its host gives you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth().testTag("realm-browser-status-page")) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Realm status page",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (statusUrl != null) {
                    Text(
                        statusUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("realm-browser-status-url"),
                    )
                    Text(
                        "The hosted read-only status page (visibility " +
                            "${statusState.visibility.name.lowercase()}). Anyone with the link " +
                            "and reachability can open it while it serves.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (statusState.visibility == RealmStatusVisibility.PUBLIC) {
                    // On, but the page-only tunnel has no live public URL yet.
                    Text(
                        "The public showcase is on; the page tunnel is not serving " +
                            "a public URL yet.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("realm-browser-status-off"),
                    )
                } else {
                    Text(
                        RealmBrowserModel.statusPageOffCopy(visibilityOn = false) ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("realm-browser-status-off"),
                    )
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth().testTag("realm-browser-internet")) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Internet discovery",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    RealmBrowserModel.INTERNET_DISCOVERY_COPY,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("realm-browser-internet-empty"),
                )
            }
        }
    }
}
