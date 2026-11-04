package com.pocketrealm.desktop.onlineplay

import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.onlineplay.GameBrowserBridge
import com.pocketrealm.discovery.iroh.InviteCode
import com.pocketrealm.discovery.iroh.IrohJoinDescriptor
import com.pocketrealm.discovery.iroh.JoinPasscode
import com.pocketrealm.discovery.iroh.SignupProtocol
import com.pocketrealm.server.Srp6AccountMath
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.RuntimeOperation
import com.pocketrealm.ui.LanJoinProbe

/**
 * The join verbs behind the Multiplayer screens, in plain names: joining a
 * game from the list, decoding an invite code, checking a local-network
 * address, and building an account request whose secret math happens on
 * this device (the Android `com.pocketrealm.onlineplay` twin).
 */
object JoinBridge {

    /** The live join lane's state (the screens never name the transport
     * manager directly). */
    internal fun joinState(
        model: DesktopAppModel,
    ): kotlinx.coroutines.flow.StateFlow<com.pocketrealm.desktop.iroh.IrohGatewayManager.JoinState> =
        model.irohJoinState

    /** The join password's derived key — the password itself never moves. */
    fun passwordKey(password: String): String = JoinPasscode.keyHex(password)

    /** Joins a game from the list; [password] is used only when the game
     * asks for one. A sign-up name/password creates the account first.
     * Returns when the join resolves (Ready + client launch, or the loud
     * refusal detail). */
    suspend fun joinGame(
        model: DesktopAppModel,
        target: GameBrowserBridge.GameTarget,
        password: String? = null,
        signup: AccountRequest? = null,
        login: Pair<String, String>? = null,
    ): RuntimeOperation {
        val descriptor = IrohJoinDescriptor(
            nodeHex = target.nodeHex,
            relayUrl = target.relayUrl,
        )
        return model.startIrohJoin(
            descriptor,
            target.flavorId,
            signup = signup?.toRequest(),
            passwordKeyHex = password?.takeIf { it.isNotEmpty() }?.let(::passwordKey),
            login = login,
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
