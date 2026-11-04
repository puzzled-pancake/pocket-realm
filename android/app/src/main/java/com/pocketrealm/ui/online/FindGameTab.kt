package com.pocketrealm.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.onlineplay.GameBrowserBridge
import com.pocketrealm.ui.online.OnlinePlayCopy as Copy

/**
 * The Multiplayer › Find a game tab: the game list (search, three filters),
 * a details sheet per game, and the join flow (password prompt + wizard).
 */
@Composable
internal fun FindGameTab(
    joinEnabled: Boolean,
    clientUnavailableReason: String?,
    supervisorClient: com.pocketrealm.supervisor.RuntimeSupervisorClient,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val browser = remember { GameBrowserBridge(scope) }
    DisposableEffect(browser) {
        browser.browse()
        onDispose { browser.idle() }
    }
    val snapshot by browser.snapshot.collectAsState()

    var query by remember { mutableStateOf("") }
    var myVersionOnly by remember { mutableStateOf(false) }
    var hasRoom by remember { mutableStateOf(false) }
    var noPassword by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var privacyOpen by remember { mutableStateOf(false) }
    var joinEntry by remember { mutableStateOf<GameBrowserBridge.GameEntry?>(null) }

    val rows = GameList.sort(snapshot.games.map { GameList.row(it.summary) })
    val facts = snapshot.games.associate { it.summary.id to it.summary }
    val shown = GameList.filter(rows, facts, query, myVersionOnly, hasRoom, noPassword)
    val details = snapshot.games.firstOrNull { it.summary.id == selectedId }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        clientUnavailableReason?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(Copy.SEARCH_HINT + "…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("mp-find-search"),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterCheckbox(Copy.FILTER_MY_VERSION, myVersionOnly, { myVersionOnly = it }, "mp-find-my-version")
            FilterCheckbox(Copy.FILTER_HAS_ROOM, hasRoom, { hasRoom = it }, "mp-find-has-room")
            FilterCheckbox(Copy.FILTER_NO_PASSWORD, noPassword, { noPassword = it }, "mp-find-no-password")
        }
        when {
            snapshot.unreachable -> Column(
                Modifier.fillMaxWidth().testTag("mp-find-unreachable"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(Copy.LIST_UNREACHABLE, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = { browser.idle(); browser.browse() },
                    modifier = Modifier.testTag("mp-find-retry"),
                ) { Text(Copy.TRY_AGAIN) }
            }
            shown.isEmpty() -> Text(
                Copy.LIST_EMPTY,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("mp-find-empty"),
            )
            else -> Column(Modifier.fillMaxWidth().testTag("mp-find-list")) {
                // A plain Column, not a lazy list: the tab's outer Column
                // already scrolls, and Compose refuses a height-unbounded
                // lazy list inside it (the packaged-app crash the first
                // live listing triggered — the empty-list path never
                // composed the list, so suites never saw it).
                shown.forEach { row ->
                    GameCard(
                        row = row,
                        joinEnabled = joinEnabled,
                        joinReady = facts[row.id]?.joinReady == true,
                        onOpen = { selectedId = row.id },
                        onJoin = {
                            snapshot.games.firstOrNull { it.summary.id == row.id }
                                ?.let { joinEntry = it }
                        },
                    )
                }
            }
        }
        Text(
            Copy.ABOUT_PRIVACY,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable { privacyOpen = true }
                .testTag("mp-find-privacy"),
        )
        Text(
            Copy.LIST_CONNECTION_DISCLOSURE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("mp-find-disclosure"),
        )
    }

    details?.let { entry -> DetailsSheet(entry, onDismiss = { selectedId = null }) }

    if (privacyOpen) {
        AlertDialog(
            onDismissRequest = { privacyOpen = false },
            title = { Text(Copy.ABOUT_PRIVACY) },
            text = { Text(Copy.PRIVACY_COPY) },
            confirmButton = { TextButton(onClick = { privacyOpen = false }) { Text("Close") } },
        )
    }

    joinEntry?.let { entry ->
        GameJoinFlow(
            entry = entry,
            supervisorClient = supervisorClient,
            onDismiss = { joinEntry = null },
        )
    }
}

@Composable
private fun FilterCheckbox(label: String, checked: Boolean, onChecked: (Boolean) -> Unit, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked, modifier = Modifier.testTag(tag))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** One game in the list. */
@Composable
private fun GameCard(
    row: GameRow,
    joinEnabled: Boolean,
    joinReady: Boolean,
    onOpen: () -> Unit,
    onJoin: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .testTag("mp-game-" + row.id),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (row.needsPassword) "🔒 " else "") + row.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(row.playersLine, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                row.versionLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                row.factsLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.description.isNotEmpty()) {
                Text(
                    "\"" + row.description + "\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onJoin,
                enabled = joinEnabled && row.sameVersion && joinReady,
                modifier = Modifier.testTag("mp-game-join-" + row.id),
            ) { Text(row.joinLabel) }
        }
    }
}

/** The every-field sheet for one game. */
@Composable
private fun DetailsSheet(entry: GameBrowserBridge.GameEntry, onDismiss: () -> Unit) {
    val row = GameList.row(entry.summary)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Copy.DETAILS_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DetailLine(entry.summary.name)
                DetailLine(row.versionLine)
                DetailLine(row.playersLine)
                DetailLine(row.factsLine)
                entry.summary.hostAppVersion?.let { DetailLine("Host app $it") }
                if (entry.summary.description.isNotEmpty()) {
                    DetailLine("\"" + entry.summary.description + "\"")
                }
                if (!row.sameVersion) {
                    DetailLine(row.joinLabel)
                } else if (!row.joinReady) {
                    DetailLine(GameList.JOIN_OFFLINE_NOW)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DetailLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}
