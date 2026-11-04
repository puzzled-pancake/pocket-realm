package com.pocketrealm.onlineplay

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.discovery.nostr.PublicRealmBrowser
import com.pocketrealm.discovery.nostr.RelayUrls
import com.pocketrealm.ui.online.GameSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The plain-worded bridge between the Multiplayer screens and the game-list
 * machinery: the screens see only clean names and the shared
 * [com.pocketrealm.ui.online] model types, never the technology's own
 * vocabulary.
 */
class GameBrowserBridge(scope: CoroutineScope) {

    /** One game as the list shows it, plus what a Join needs (null while
     * the host's connection is down). */
    data class GameEntry(val summary: GameSummary, val target: GameTarget?)

    /** Everything a Join of that game dials, by the list row's identity. */
    data class GameTarget(
        val id: String,
        val name: String,
        val nodeHex: String,
        val relayUrl: String,
        val flavorId: String,
        val signupOpen: Boolean,
        val locked: Boolean,
    )

    data class Snapshot(
        val browsing: Boolean = false,
        val games: List<GameEntry> = emptyList(),
        /** Browsing finished and no source answered — the honest
         * "can't reach the game list". */
        val unreachable: Boolean = false,
    )

    private val browser = PublicRealmBrowser(scope, RelayUrls.DEFAULTS)

    val snapshot: StateFlow<Snapshot> = browser.state
        .map { state ->
            Snapshot(
                browsing = state.browsing,
                games = state.listings.map { it.toEntry() },
                unreachable = state.browsing && state.relaysTotal > 0 &&
                    state.relaysAnsweringRead <= 0,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, Snapshot(browsing = false))

    fun browse() = browser.browse()

    fun idle() = browser.idle()

    private fun PublicRealmBrowser.ListingCard.toEntry(): GameEntry {
        val flavor = ClientFlavors.ALL.firstOrNull { it.flavorId == this.flavor }
        val patch = flavor?.versionResource?.split('.')?.take(3)?.joinToString(".")
        return GameEntry(
            summary = GameSummary(
                id = eventId,
                name = name,
                gameVersion = flavor?.expansion ?: this.flavor,
                needsVersionLabel = flavor?.expansion ?: this.flavor,
                patch = patch ?: this.flavor,
                playersOnline = humans,
                playerLimit = maxPlayers,
                bots = bots,
                upSeconds = uptimeSeconds,
                region = regionOf(join?.relayUrl),
                needsPassword = locked,
                newPlayersWelcome = signupOpen,
                description = motd,
                seenMinutesAgo = 0,
                hostAppVersion = appVersion,
                joinReady = join != null,
                sameVersion = com.pocketrealm.client.ActiveFlavor.displayedId == this.flavor,
            ),
            target = join?.let {
                GameTarget(
                    id = eventId,
                    name = name,
                    nodeHex = it.nodeHex,
                    relayUrl = it.relayUrl,
                    flavorId = this.flavor,
                    signupOpen = signupOpen,
                    locked = locked,
                )
            },
        )
    }

    /** A coarse region word from the host's connection address, or null. */
    private fun regionOf(host: String?): String? = when {
        host == null -> null
        host.contains("eu", ignoreCase = true) -> "Europe"
        host.contains("us", ignoreCase = true) -> "US East"
        host.contains("ap", ignoreCase = true) -> "Asia"
        else -> null
    }
}
