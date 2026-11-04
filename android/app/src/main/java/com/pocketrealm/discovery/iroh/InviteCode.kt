package com.pocketrealm.discovery.iroh

import org.json.JSONObject

/**
 * The invite code (an unlisted game's shareable join): `PR1-` + base64url
 * of the join descriptor's frozen JSON. Decoding re-uses
 * [IrohJoinDescriptor]'s own refusal rules (exactly the three keys, node
 * id shape, hostname-only https relay), so a code that decodes is a code
 * that joins. Shared by both platforms and the wizard; never contains a
 * password or an address.
 */
object InviteCode {

    const val PREFIX: String = "PR1-"

    /** A code longer than this is junk before it is anything else. */
    const val MAX_CODE_LENGTH: Int = 4_096

    fun encode(descriptor: IrohJoinDescriptor): String =
        PREFIX + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(descriptor.toJsonString().toByteArray(Charsets.UTF_8))

    /** True when [text] looks like an invite code (the Direct-join tab's
     * field sniffing). */
    fun looksLikeInviteCode(text: String): Boolean = text.trim().startsWith(PREFIX)

    /** Strict decode: wrong prefix, non-base64url characters, an
     * oversized code, non-JSON bytes, or any descriptor rule violation
     * refuses with [IllegalArgumentException]. */
    fun decode(text: String): IrohJoinDescriptor {
        require(text.length <= MAX_CODE_LENGTH) { "the invite code is too long" }
        require(text.startsWith(PREFIX)) { "an invite code starts with $PREFIX" }
        val body = text.removePrefix(PREFIX)
        require(body.isNotEmpty()) { "the invite code is empty after $PREFIX" }
        val bytes = try {
            java.util.Base64.getUrlDecoder().decode(body)
        } catch (malformed: IllegalArgumentException) {
            throw IllegalArgumentException("the invite code is not valid text", malformed)
        }
        val json = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (malformed: org.json.JSONException) {
            throw IllegalArgumentException("the invite code is not a valid code", malformed)
        }
        return IrohJoinDescriptor.fromListingJson(json)
    }
}
