package com.pocketrealm.desktop.onlineplay

import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.desktop.nostr.NostrListingPublisher
import com.pocketrealm.discovery.iroh.InviteCode
import com.pocketrealm.discovery.iroh.IrohJoinDescriptor
import com.pocketrealm.storage.JoinedRealmLogins
import com.pocketrealm.supervisor.PublicHostModerationStore
import com.pocketrealm.supervisor.PublicHostModerator
import com.pocketrealm.ui.online.HostStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The host verbs and status reads behind the Host tab, in plain names
 * (the Android `com.pocketrealm.onlineplay` twin). Desktop has no
 * supervisor status JSON for this — the read assembles the same facts from
 * the listing publisher's phase, the live gateway descriptor and the
 * shared moderation store.
 */
internal object HostBridge {

    /** Starts hosting online (the game answers through this machine's own
     * internet connection; local-network players are a separate switch). */
    suspend fun hostOnline(model: DesktopAppModel, lanPlayers: Boolean) =
        model.hostOnline(includeClient = false, lanPlayers = lanPlayers)

    /** Saves and stops the hosted game. */
    suspend fun stopHosting(model: DesktopAppModel) = model.saveAndExit()

    /** The hosted game's live status stream (the screens never name the
     * listing machinery directly). */
    fun hostState(model: DesktopAppModel): kotlinx.coroutines.flow.StateFlow<NostrListingPublisher.UiState> =
        model.nostrListing.uiState

    /** The shared status state for the publisher's live phase. */
    fun hostStatus(state: NostrListingPublisher.UiState, hosting: Boolean): HostStatus = when {
        !hosting -> HostStatus.Stopped
        else -> when (state.phase) {
            NostrListingPublisher.Phase.LIVE -> HostStatus.OnlineListed
            NostrListingPublisher.Phase.UNLISTED -> HostStatus.OnlineUnlisted
            NostrListingPublisher.Phase.FAILED ->
                HostStatus.CantGoOnline(state.detail ?: state.errorCopy ?: "the game list did not answer")
            else -> HostStatus.Starting
        }
    }

    /** This host's own invite code, or null while the endpoint is down. */
    fun inviteCode(model: DesktopAppModel): String? =
        model.irohGateway.hostDescriptor()?.let { encodeInvite(it.nodeHex, it.relayUrl) }

    /** Encodes an invite code for a host's own game (host side). */
    fun encodeInvite(nodeHex: String, relayUrl: String): String =
        InviteCode.encode(IrohJoinDescriptor(nodeHex = nodeHex, relayUrl = relayUrl))

    /** The host's game name as the list shows it. */
    fun gameName(model: DesktopAppModel): String = model.backend.projectedRealmName() ?: "my realm"

    /** Everyone who signed up or logged in over the internet. */
    fun players(model: DesktopAppModel): List<PublicHostModerationStore.PlayerRow> = model.publicPlayers()

    /** The banned devices (label + token rows). */
    fun deviceBans(model: DesktopAppModel): List<PublicHostModerationStore.DeviceBan> =
        model.publicDeviceBans()

    /** One moderation action (ban/unban by account or device); its
     * outcome carries the plain refusal detail when the realm answers. */
    suspend fun moderate(
        model: DesktopAppModel,
        action: PublicHostModerator.Action,
        target: String,
    ): com.pocketrealm.supervisor.ModerationResult = model.moderatePublicPlayer(action, target)

    /** The saved-logins store for this machine. */
    fun savedLogins(model: DesktopAppModel): JoinedRealmLogins =
        JoinedRealmLogins(File(model.roots.realm, "joined-logins/joined.json"))

    /** Migrates the old "signed up here" file into the saved-logins store:
     * every known game keeps a marker, no password is invented. */
    fun migrateOldSignups(model: DesktopAppModel, logins: JoinedRealmLogins) {
        val old = File(model.roots.realm, "public-realm-signups.txt")
        if (!old.isFile) return
        runCatching {
            old.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList()).forEach(logins::markAccountMade)
        runCatching { old.delete() }
    }

    /** Reads one saved login, or null. */
    suspend fun savedLogin(
        logins: JoinedRealmLogins,
        realmKey: String,
    ): JoinedRealmLogins.SavedLogin? = withContext(Dispatchers.IO) {
        logins.savedLogin(realmKey)
    }
}
