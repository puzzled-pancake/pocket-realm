package com.pocketrealm.desktop.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.TunnelState
import com.pocketrealm.desktop.realmstatus.RealmStatusVisibility
import com.pocketrealm.desktop.ui.budgetLine
import com.pocketrealm.supervisor.FriendInviteStore
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.ui.accountCredentialFieldError
import com.pocketrealm.ui.accountProvisionFailureMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Multiplayer Host tab's Advanced fold (moved from the Home screen and
 * the Settings LAN-play card): the LAN-host switches — local-network
 * players and friend access — followed by the host cards verbatim: the
 * internet-hosting card, the realm-status page card, and (only while
 * hosting) the Guests and friend-access summary cards.
 */
@Composable
internal fun HostAdvancedPane(
    model: DesktopAppModel,
    hosting: Boolean,
    onOpenPlayers: () -> Unit,
    modifier: Modifier,
) {
    val settings by model.settings.collectAsState()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.testTag("lan-host-allow-lan-players"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(
                checked = settings.allowLanPlayers,
                onCheckedChange = { enabled ->
                    model.updateSettings { s -> s.copy(allowLanPlayers = enabled) }
                },
            )
            Text("Allow local-network players", style = MaterialTheme.typography.bodyMedium)
        }
        Row(
            modifier = Modifier.testTag("lan-host-friend-access"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(
                checked = settings.friendAccessEnabled,
                onCheckedChange = { enabled ->
                    model.updateSettings { s -> s.copy(friendAccessEnabled = enabled) }
                },
            )
            Text("Friend access (invite-code account requests)", style = MaterialTheme.typography.bodyMedium)
        }
        InternetHostCard(model)
        RealmStatusCard(model)
        if (hosting) {
            GuestsCard(model)
            FriendAccessSummaryCard(model, onOpenPlayers)
        }
    }
}

/**
 * Design-tunnel / /: the host-over-the-internet card. One card,
 * one copy button, rendered from live state: the tunnel-adapter detection
 * (the allowlist lane) with Tailscale-first guidance, the honest
 * reachability line (bind vs advertised, plus the caveat that the app
 * cannot probe its own NAT reachability), and the invite block —
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
            // seat-limit honesty: Tailscale's free tier is account-limited
            // well below the realm's player cap — a host walking this lane
            // should know before ten friends accept.
            Text(
                "Plan note: Tailscale's free tier allows 3 users / 100 devices per " +
                    "tailnet — a full realm of friends can exceed it. Check your plan " +
                    "(or use ZeroTier) before inviting.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (advertised != null) {
                // the reachability line — present tense ONLY while a
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
                // encryption honesty: the protocol authenticates but does
                // not conceal — the tunnel is the only confidentiality layer.
                Text(
                    "Encryption: game traffic is NOT encrypted. The tunnel is " +
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
 * the three-state visibility switch (fail-closed OFF default), the
 * MOTD editor (clamped at the settings write), the roster consent
 * toggle, the orphan notice (acknowledge/adopt), and the
 * PUBLIC showcase's shape-gated link + honest posture/failure copy .
 * The consent copy enumerates the published set before the PUBLIC
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
                // the consent copy enumerates the host-relevant
                // published set VERBATIM — and it renders BEFORE the flip
                // (a host reads exactly what enabling Public publishes),
                // staying up after the flip for reference.
                Text(
                    "Enabling the Public showcase publishes: your realm name; this " +
                        "description; the game version; the patch-timeframe stage (id, title, " +
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
                    "  Show online character names on the page (off by default; never with " +
                        "the MariaDB database; never while the realm is offline)",
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
 * D5a + D7 (design-lan): the host's Guests card, visible only
 * while hosting. Create guest account (host-typed username, GENERATED
 * password — gmLevel 0 always, never saved into the host's stored-account
 * slot), the in-person handoff copy with the one-line copy affordance, the
 * players-online line, and the budget display (bot admission × players).
 * The credential pair renders ONLY here — no LAN surface embeds a
 * credential in invite text (the hard rule). Design-tunnel adds
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
            // the budget display — bot admission verdict × players online.
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
                // Design-tunnel: the duration presets — the middle
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

// ---------------------------------------------------------------------------
// Remote friend accounts (design-remote-accounts /): the Home-side
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

/** A suspension's lift time in the host's local wall clock (the ban list
 * distinguishes timed suspensions from permanent bans; the model carries
 * [com.pocketrealm.desktop.DesktopGuestBans.BanRow.permanent]/expiresAt). */
private fun formatBanLift(epochSeconds: Long): String =
    java.time.Instant.ofEpochSecond(epochSeconds)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm"))

/** The account credential cap (the realm rule's 16); the field keeps raw
 * keystrokes up to this length — DESK-136 deleted the character-class
 * mangling that silently discarded what the user typed. */
private const val CREDENTIAL_MAX_CHARS = 16
