package com.pocketrealm.onlineplay

import android.content.Context
import com.pocketrealm.discovery.iroh.InviteCode
import com.pocketrealm.service.RealmService
import com.pocketrealm.ui.online.HostStatus
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File

/**
 * The host verbs and status reads behind the Host tab, in plain names.
 */
object HostBridge {

    /** Starts hosting online (the game answers through this device's own
     * internet connection; local-network players are a separate switch). */
    fun hostOnline(context: Context, lanPlayers: Boolean) {
        RealmService.hostOnline(context, includeClient = false, lanPlayers = lanPlayers)
    }

    /** Saves and stops the hosted game. */
    fun stopHosting(context: Context) {
        RealmService.saveExit(context)
    }

    /** One status read of the hosted game (phase, copy, player counts and
     * the invite code), or null while nothing hosts. */
    suspend fun status(client: com.pocketrealm.supervisor.RuntimeSupervisorClient): JSONObject? =
        runCatching { client.publicListingStatus() }.getOrNull()

    /** The shared status state for a status read. */
    fun hostStatus(status: JSONObject?, hosting: Boolean): HostStatus = when {
        !hosting -> HostStatus.Stopped
        status == null -> HostStatus.Starting
        else -> when (status.optString("phase")) {
            "LIVE" -> HostStatus.OnlineListed
            "UNLISTED" -> HostStatus.OnlineUnlisted
            "OFF" -> HostStatus.Starting
            else -> HostStatus.CantGoOnline(status.optString("copy").ifBlank { "the game list did not answer" })
        }
    }

    /** The invite code string from a status read, or null. */
    fun inviteCode(status: JSONObject?): String? =
        status?.optString("joinCode")?.takeIf { it.isNotEmpty() }

    /** Migrates the old "signed up here" set into the saved-logins store:
     * every known game keeps a marker, no password is invented. */
    fun migrateOldSignups(context: Context, logins: com.pocketrealm.storage.JoinedRealmLogins) {
        val nodes = context.getSharedPreferences("public_realm_signups", Context.MODE_PRIVATE)
            .getStringSet("nodes", emptySet()).orEmpty()
        nodes.forEach(logins::markAccountMade)
        if (nodes.isNotEmpty()) {
            context.getSharedPreferences("public_realm_signups", Context.MODE_PRIVATE)
                .edit().remove("nodes").apply()
        }
    }

    /** The saved-logins store for this device. */
    fun savedLogins(context: Context): com.pocketrealm.storage.JoinedRealmLogins =
        com.pocketrealm.storage.JoinedRealmLogins(
            File(context.noBackupFilesDir, "joined-logins/joined.json"),
        )

    /** Encodes an invite code for a host's own game (host side). */
    fun encodeInvite(nodeHex: String, relayUrl: String): String =
        InviteCode.encode(
            com.pocketrealm.discovery.iroh.IrohJoinDescriptor(nodeHex = nodeHex, relayUrl = relayUrl),
        )

    /** Reads one saved login, or null. */
    suspend fun savedLogin(
        logins: com.pocketrealm.storage.JoinedRealmLogins,
        realmKey: String,
    ): com.pocketrealm.storage.JoinedRealmLogins.SavedLogin? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            logins.savedLogin(realmKey)
        }
}
