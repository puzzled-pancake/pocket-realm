package com.pocketrealm.desktop.ui.online

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.desktop.onlineplay.HostBridge
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.ui.online.OnlinePlayCopy as Copy

/**
 * The Multiplayer destination (route "Realms"): three tabs — Find a game,
 * Host, Direct join. The wording and the list logic live in the shared
 * [com.pocketrealm.ui.online.OnlinePlayModel]; the technical controls live
 * under each tab's Advanced fold. (Android also has a Transfer tab; the
 * character transfer is an Android feature today.)
 */
@Composable
fun MultiplayerScreen(
    model: DesktopAppModel,
    onOpenPlayers: () -> Unit = {},
    /** The tab Home asked for ("HOST"/"FIND"), consumed once. */
    initialTab: String? = null,
) {
    val snapshot by model.state.collectAsState()
    val settingsState by model.settings.collectAsState()
    val settings = settingsState

    // One-time: the old "signed up here" file becomes the saved-logins store.
    remember(model) {
        val logins = HostBridge.savedLogins(model)
        HostBridge.migrateOldSignups(model, logins)
        true
    }

    val worldUp = snapshot.phase == RuntimePhase.WORLD_READY ||
        snapshot.phase == RuntimePhase.RUNNING ||
        snapshot.phase == RuntimePhase.PAUSED ||
        snapshot.phase == RuntimePhase.CLIENT_FAILED
    val hosting = snapshot.runtimeMode == RuntimeMode.LAN_HOST && worldUp
    val joined = snapshot.runtimeMode == RuntimeMode.LAN_JOIN
    val realmBusy = snapshot.phase !in setOf(
        RuntimePhase.STOPPED,
        RuntimePhase.ERROR,
        RuntimePhase.UNCONFIGURED,
    )

    var tab by rememberSaveable {
        mutableStateOf(
            when (initialTab) {
                "HOST" -> MpTab.HOST.name
                else -> if (joined) MpTab.DIRECT_JOIN.name else MpTab.FIND.name
            },
        )
    }
    val selected = runCatching { MpTab.valueOf(tab) }.getOrDefault(MpTab.FIND)
    val joinEnabled = !hosting && !joined && !realmBusy

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
                    model = model,
                    modifier = Modifier.fillMaxWidth(),
                )
                MpTab.HOST -> HostTab(
                    model = model,
                    hosting = hosting,
                    settings = settings,
                    onOpenPlayers = onOpenPlayers,
                    modifier = Modifier.fillMaxWidth(),
                )
                MpTab.DIRECT_JOIN -> DirectJoinTab(
                    model = model,
                    joined = joined,
                    joinedAddress = if (joined) snapshot.realmEndpoint.address else null,
                    joinEnabled = joinEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The three tabs (the route object keeps its name; the label is Multiplayer). */
private enum class MpTab(val label: String) {
    FIND(Copy.TAB_FIND),
    HOST(Copy.TAB_HOST),
    DIRECT_JOIN(Copy.TAB_DIRECT_JOIN),
}
