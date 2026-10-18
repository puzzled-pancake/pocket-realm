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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.pocketrealm.desktop.nostr.NostrListingPublisher
import com.pocketrealm.desktop.realmstatus.RealmStatusServer
import com.pocketrealm.desktop.realmstatus.RealmStatusVisibility
import com.pocketrealm.discovery.nostr.PublicRealmBrowser
import com.pocketrealm.supervisor.RuntimeFailureCopy
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.ui.LanJoinProbe
import com.pocketrealm.ui.RealmBrowserModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Realm Browser (the multi-flavor discovery surface): LAN realms
 * through the shared [LanJoinProbe] seam (this device's own realm, a
 * probe-any-host row), the hosted realm-status page link, and the REAL
 * Public realms section (W6-N1): a live relay browser over the shared
 * [PublicRealmBrowser] seam — real listings when hosts publish, the
 * honest empty network when none do — plus the host-side publish
 * controls (the realm listing heartbeat) for this device's own realm.
 */
@Composable
fun RealmBrowserScreen(model: DesktopAppModel) {
    val snapshot by model.state.collectAsState()
    val statusState by model.realmStatus.uiState.collectAsState()
    val nostrState by model.nostrListing.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    // The live Public realms browser: connected while this route shows.
    val browser = remember { PublicRealmBrowser(scope, model.effectiveNostrRelays()) }
    fun relaysText(): String = model.effectiveNostrRelays().joinToString("\n")
    val browserState by browser.state.collectAsState()
    LaunchedEffect(Unit) { browser.browse() }
    DisposableBrowserStop(browser)

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
    val nowSeconds = System.currentTimeMillis() / MILLIS_PER_SECOND
    val activeFlavorId = com.pocketrealm.client.ActiveFlavor.displayedId
    // W6-N3: rows carry the honest join posture; the Join action pairs a
    // row with its live listing card (the descriptor itself never renders).
    val publicRealmRows = browserState.listings.map { card ->
        RealmBrowserModel.publicRealmRow(
            RealmBrowserModel.PublicRealmListing(
                eventId = card.eventId,
                name = card.name,
                flavor = card.flavor,
                build = card.build,
                timeframeStage = card.timeframeStage,
                motd = card.motd,
                rates = card.rates,
                humans = card.humans,
                bots = card.bots,
                publishedAtSeconds = card.publishedAtSeconds,
                statusPageUrl = card.statusPageUrl,
                relaysSeen = card.relaysSeen,
                joinCarried = card.join != null,
            ),
            nowSeconds = nowSeconds,
            activeFlavorId = activeFlavorId,
        )
    }
    // Keyed by the LISTING EVENT ID (code-red R1-3/R4-3/R6-2: names are
    // not unique across publishers — a name-keyed map dialed the last
    // same-name listing's node for every row).
    val joinableCards: MutableMap<String, Pair<String, com.pocketrealm.discovery.iroh.IrohJoinDescriptor>> =
        HashMap()
    for (card in browserState.listings) {
        val join = card.join ?: continue
        joinableCards[card.eventId] = Pair(card.flavor, join)
    }
    var joinStatus by remember { mutableStateOf<String?>(null) }

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
        PublicRealmsCard(
            browserState = browserState,
            publicRealmRows = publicRealmRows,
            joinLane = PublicRealmsJoinLane(
                joinState = model.irohJoinState.collectAsState().value,
                relayOnly = model.settings.collectAsState().value.irohRelayOnly,
                onJoin = { row ->
                    val target = row.joinKey?.let { joinableCards[it] }
                    if (target != null) {
                        scope.launch {
                            // The verb suspends on Dispatchers.IO (code-red
                            // R1-2/R4-1/R6-1) — the UI thread only launches it.
                            val operation = model.startIrohJoin(
                                target.second, listingFlavorId = target.first,
                            )
                            joinStatus = if (operation.ok) null else operation.detail
                        }
                    }
                },
                onStopJoin = { scope.launch { model.stopIrohJoin() } },
                onRelayOnlyChange = { model.setIrohRelayOnly(it) },
            ),
            joinActionCopy = { availability -> RealmBrowserModel.joinActionCopy(availability) },
            joinStatus = joinStatus,
        )
        HostPublishControls(
            HostPublishCard(
                nostrState = nostrState,
                statusCopy = NostrListingPublisher.publishStatusCopy(nostrState),
                reportLines = NostrListingPublisher.relayReportLines(nostrState),
                relaysText = relaysText(),
                onRelaysCommit = { raw ->
                    // Throws the actionable VAL copy on an invalid set; the
                    // editor surfaces it and keeps the prior set.
                    model.nostrListing.setRelays(
                        raw.lines().mapNotNull { it.trim().takeIf(String::isNotEmpty) },
                    )
                    browser.browse(model.effectiveNostrRelays())
                },
                onPublishSwitch = { model.nostrListing.setEnabled(it) },
            )
        )
    }
}

/** Stops the screen-scoped browser when the route leaves composition. */
@Composable
private fun DisposableBrowserStop(browser: PublicRealmBrowser) {
    androidx.compose.runtime.DisposableEffect(browser) {
        onDispose { browser.idle() }
    }
}

/** The internet-join lane's inputs (code-red round 1: one parameter
 * object instead of nine — the renderer and the verbs travel together). */
private data class PublicRealmsJoinLane(
    val joinState: com.pocketrealm.desktop.iroh.IrohGatewayManager.JoinState,
    val relayOnly: Boolean,
    val onJoin: (RealmBrowserModel.Row.PublicRealms.PublicRealm) -> Unit,
    val onStopJoin: () -> Unit,
    val onRelayOnlyChange: (Boolean) -> Unit,
)

@Composable
private fun PublicRealmsCard(
    browserState: PublicRealmBrowser.State,
    publicRealmRows: List<RealmBrowserModel.Row.PublicRealms.PublicRealm>,
    joinLane: PublicRealmsJoinLane,
    joinActionCopy: (RealmBrowserModel.Row.PublicRealms.JoinAvailability) -> String?,
    joinStatus: String?,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("realm-browser-public")) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                RealmBrowserModel.PUBLIC_REALMS_TITLE,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            JoinLaneControls(joinLane)
            // R6-2-1: the disclosure holds while the browser is connected.
            Text(
                RealmBrowserModel.RELAY_IP_DISCLOSURE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("realm-browser-public-disclosure"),
            )
            if (browserState.isEmpty) {
                Text(
                    if (browserState.browsing) {
                        RealmBrowserModel.publicRealmsEmptyCopy(
                            browserState.relaysAnsweringRead,
                            browserState.relaysOpen,
                            browserState.relaysTotal,
                        )
                    } else {
                        RealmBrowserModel.PUBLIC_REALMS_IDLE_COPY
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("realm-browser-public-empty"),
                )
            } else {
                publicRealmRows.forEach { row -> PublicRealmRow(row, joinActionCopy, joinLane.onJoin) }
            }
            joinStatus?.let { status ->
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("realm-browser-join-status"),
                )
            }
        }
    }
}

/** One published realm row (bots labeled, never padded); W6-N3 adds the
 * honest join action (the row's availability posture drives it). */
@Composable
private fun PublicRealmRow(
    row: RealmBrowserModel.Row.PublicRealms.PublicRealm,
    joinActionCopy: (RealmBrowserModel.Row.PublicRealms.JoinAvailability) -> String?,
    onJoin: (RealmBrowserModel.Row.PublicRealms.PublicRealm) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            row.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("realm-browser-public-listing"),
        )
        Text(row.detail, style = MaterialTheme.typography.bodySmall)
        Text(row.playersCopy, style = MaterialTheme.typography.bodySmall)
        Text(
            row.ageCopy + if (row.relaysSeen > 1) {
                " · seen on ${row.relaysSeen} relays"
            } else {
                ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        row.statusPageUrl?.let { url ->
            Text(
                "Status page: $url",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // W6-N3: the join action — a real button when joinable, the honest
        // posture copy otherwise (never an address; the descriptor's node
        // id and relay never render).
        joinActionCopy(row.joinAvailability)?.let { copy ->
            if (row.joinAvailability == RealmBrowserModel.Row.PublicRealms.JoinAvailability.Joinable) {
                androidx.compose.material3.TextButton(
                    onClick = { onJoin(row) },
                    modifier = Modifier.testTag("realm-browser-join-button"),
                ) {
                    Text(copy)
                }
            } else {
                Text(
                    copy,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The host-side publish controls' render inputs (R6-1: the report lines
 * ride with the copy so "check the relays below" is always real). */
private data class HostPublishCard(
    val nostrState: com.pocketrealm.desktop.nostr.NostrListingPublisher.UiState,
    val statusCopy: String,
    val reportLines: List<String>,
    val relaysText: String,
    val onRelaysCommit: (String) -> Unit,
    val onPublishSwitch: (Boolean) -> Unit,
)

/** The host-side publish controls: the realm listing heartbeat switch and
 * the user-editable relay set (owner decision 4). */
@Composable
private fun HostPublishControls(card: HostPublishCard) {
    Card(modifier = Modifier.fillMaxWidth().testTag("realm-browser-public-publish")) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(
                    checked = card.nostrState.enabled,
                    onCheckedChange = card.onPublishSwitch,
                    modifier = Modifier.testTag("realm-browser-public-switch"),
                )
                Column {
                    Text("Publish this realm's listing", style = MaterialTheme.typography.bodyMedium)
                    // R2-6/R6-4: the honest publish-status projection —
                    // every-relay-rejected never reads as 'Live', and the
                    // error channel (VAL copies) renders here.
                    Text(
                        card.statusCopy,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("realm-browser-public-status"),
                    )
                    // R6-1: the per-relay reports render under the status
                    // copy — "check the relays below" points at real lines.
                    card.reportLines.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("realm-browser-relay-report"),
                        )
                    }
                    card.nostrState.lastBeatOutcomeCopy?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            RelayEditor(card.relaysText, card.onRelaysCommit)
        }
    }
}

/** The user-editable relay set (one URL per line, committed on Apply). */
@Composable
private fun RelayEditor(relaysText: String, onCommit: (String) -> Unit) {
    var text by remember(relaysText) { mutableStateOf(relaysText) }
    var errorCopy by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                errorCopy = null
            },
            label = { Text("Relays (one wss:// or ws:// URL per line)") },
            modifier = Modifier.fillMaxWidth().testTag("realm-browser-public-relays"),
            supportingText = { Text("Write to all, read from all — 1 to 8 relays.") },
        )
        errorCopy?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = {
                runCatching { onCommit(text) }
                    .onFailure { failure -> errorCopy = failure.message }
            },
            modifier = Modifier.testTag("realm-browser-public-relays-apply"),
        ) { Text("Apply relays") }
    }
}

private const val MILLIS_PER_SECOND = 1_000L


/** W6-N3 code-red R1-4/R4-2/R6-4/R6-5/R7-N3-3: the relay-only switch
 * (default ON; OFF is the friend-join affordance) and the live join
 * lane's stop control — without them the guards' "stop it first" copy
 * was a dead end and the OFF posture unreachable. */
@Composable
private fun JoinLaneControls(lane: PublicRealmsJoinLane) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Switch(
            checked = lane.relayOnly,
            onCheckedChange = lane.onRelayOnlyChange,
            modifier = Modifier.testTag("realm-browser-relay-only"),
        )
        Text(
            "Relay-only joins (recommended): the dial is pinned to the " +
                "listing's home relay. iroh may still move an established " +
                "connection onto a direct path in transport. Off allows " +
                "direct (hole-punched) friend joins.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("realm-browser-relay-only-copy"),
        )
    }
    if (lane.joinState is com.pocketrealm.desktop.iroh.IrohGatewayManager.JoinState.Starting ||
        lane.joinState is com.pocketrealm.desktop.iroh.IrohGatewayManager.JoinState.Ready
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.material3.TextButton(
                onClick = lane.onStopJoin,
                modifier = Modifier.testTag("realm-browser-stop-join"),
            ) {
                Text("Stop internet join")
            }
            Text(
                when (lane.joinState) {
                    is com.pocketrealm.desktop.iroh.IrohGatewayManager.JoinState.Starting ->
                        "Connecting…"
                    else -> "Connected (tunnel up)"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    // Code-red R3-2: a post-READY join failure (the gateway's loud rewrite
    // exit, any crash) renders the actionable code + detail — the stop
    // button and 'Connected' copy must never silently vanish.
    if (lane.joinState is com.pocketrealm.desktop.iroh.IrohGatewayManager.JoinState.Error) {
        val failure = lane.joinState
        Text(
            com.pocketrealm.desktop.iroh.IrohGatewayManager.joinErrorLine(
                failure.code,
                failure.detail,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("realm-browser-join-error"),
        )
    }
}
