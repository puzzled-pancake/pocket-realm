package com.pocketrealm.onlineplay

import android.content.Context
import com.pocketrealm.discovery.iroh.InviteCode
import com.pocketrealm.discovery.iroh.JoinPasscode
import com.pocketrealm.discovery.iroh.SignupProtocol
import com.pocketrealm.server.Srp6AccountMath
import com.pocketrealm.service.RealmService
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.ui.LanJoinProbe

/**
 * The join verbs behind the Multiplayer screens, in plain names: joining a
 * game from the list, decoding an invite code, checking a local-network
 * address, and building an account request whose secret math happens on
 * this device.
 */
object JoinBridge {

    /** The join password's derived key — the password itself never moves. */
    fun passwordKey(password: String): String = JoinPasscode.keyHex(password)

    /** Joins a game from the list; [password] is used only when the game
     * asks for one. A sign-up name/password creates the account first. */
    fun joinGame(
        context: Context,
        target: GameBrowserBridge.GameTarget,
        password: String? = null,
        signup: AccountRequest? = null,
    ) {
        val descriptor = com.pocketrealm.discovery.iroh.IrohJoinDescriptor(
            nodeHex = target.nodeHex,
            relayUrl = target.relayUrl,
        )
        RealmService.joinInternet(
            context,
            descriptor,
            target.flavorId,
            signup = signup?.toRequest(),
            passwordKeyHex = password?.takeIf { it.isNotEmpty() }?.let(::passwordKey),
        )
    }

    /** An account the wizard asked the host to create (the secret math ran
     * on this device; only a one-way code travels). */
    data class AccountRequest(val name: String, val sHex: String, val vHex: String) {
        internal fun toRequest(): SignupProtocol.Request? =
            if (SignupProtocol.isValidUsername(name)) {
                SignupProtocol.Request(name, sHex, vHex)
            } else {
                null
            }
    }

    fun accountRequest(name: String, password: String): AccountRequest? {
        val trimmed = name.trim()
        if (!SignupProtocol.isValidUsername(trimmed)) return null
        if (!password.matches(Regex("[A-Za-z0-9]{1,16}"))) return null
        val verifier = Srp6AccountMath.verifierFor(trimmed, password)
        return AccountRequest(trimmed, verifier.sHex, verifier.vHex)
    }

    /** Decodes an invite code; null when it is not a valid code. */
    fun inviteTarget(code: String): GameBrowserBridge.GameTarget? = runCatching {
        val descriptor = InviteCode.decode(code.trim())
        GameBrowserBridge.GameTarget(
            id = "invite",
            name = "Invited game",
            nodeHex = descriptor.nodeHex,
            relayUrl = descriptor.relayUrl,
            flavorId = com.pocketrealm.client.ActiveFlavor.displayedId ?: "",
            signupOpen = false,
            locked = false,
        )
    }.getOrNull()

    /** A local-network address in canonical form, or null when refused. */
    fun localAddress(raw: String): String? = runCatching {
        RealmEndpoint.parseJoinTarget(raw.trim()).address
    }.getOrNull()

    /** Asks a local-network host for an account (D7): the verifier is
     * derived on this device; the game password (when the friend typed
     * one) crosses as the lane's proof only. */
    fun signupOnLocalHost(
        host: String,
        username: String,
        accountPassword: String,
        gamePassword: String?,
    ): com.pocketrealm.server.FriendAccessClient.SignupOutcome =
        com.pocketrealm.server.FriendAccessClient().requestSignup(
            host,
            com.pocketrealm.server.ServerRuntimeContract.PROVISION_PORT,
            username,
            accountPassword,
            gamePassword,
        )

    /** Joins a local-network host's realm by address. */
    fun joinLocalHost(context: android.content.Context, canonicalAddress: String) {
        com.pocketrealm.service.RealmService.joinLan(context, canonicalAddress)
    }

    /** True when the text looks like an invite code. */
    fun looksLikeInvite(text: String): Boolean = InviteCode.looksLikeInviteCode(text)

    /** One probe of a local-network host: plain copy, or null on a bad
     * address. */
    suspend fun probeLocalHost(raw: String): String? {
        val address = localAddress(raw) ?: return null
        return when (val result = LanJoinProbe.probe(address)) {
            is LanJoinProbe.ProbeResult.Reachable -> "The host answered at ${result.address}."
            is LanJoinProbe.ProbeResult.Unreachable ->
                com.pocketrealm.supervisor.RuntimeFailureCopy.humanize(LanJoinProbe.unreachableDetail(result))
        }
    }
}
