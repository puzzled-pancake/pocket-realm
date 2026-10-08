package com.pocketrealm.supervisor

import org.json.JSONObject

/**
 * Pure parsers for the roster relay ops' JSON (design-remote-accounts §11/§8
 * — account_list + account_characters, the world_runtime.cpp character_
 * persistence JSON pattern). Extracted from the backend verbs so the shapes
 * are unit-testable without the native DLLs: a test feeds the exact JSON the
 * relay documents, the backend feeds [FriendRosterJson] the live op output.
 */
// ReturnCount: every parser answers early per degrade leg (blank/unparseable/
// refused op) — the honest-refusal shape the backend surfaces verbatim.
@Suppress("ReturnCount")
object FriendRosterJson {
    /** One account row: identity + moderation surfaces (§7/§12). */
    data class AccountRow(
        val id: Long,
        val username: String,
        val gmLevel: Int,
        val failedLogins: Long,
        val banned: Boolean,
        val lastLogonIp: String,
        val lastLogon: String,
        val online: Boolean,
    )

    data class CharacterRow(
        val guid: Long,
        val name: String,
        val race: Int,
        val klass: Int,
        val level: Int,
    )

    /** The account_list op's rows; empty on a refused op (world not ready /
     * query failed) — the UI renders the honest empty table with the reason. */
    fun parseAccounts(json: String?): Pair<String?, List<AccountRow>> {
        if (json.isNullOrBlank()) return "world-not-ready" to emptyList()
        val value = runCatching { JSONObject(json) }.getOrNull() ?: return "unparseable" to emptyList()
        if (!value.optBoolean("ok")) return (value.optString("reason", "unavailable")) to emptyList()
        val rows = ArrayList<AccountRow>()
        val array = value.optJSONArray("accounts")
        if (array != null) {
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                rows += AccountRow(
                    id = row.optLong("id"),
                    username = row.optString("username"),
                    gmLevel = row.optInt("gmlevel"),
                    failedLogins = row.optLong("failedLogins"),
                    banned = row.optBoolean("banned"),
                    lastLogonIp = row.optString("lastLogonIp"),
                    lastLogon = row.optString("lastLogon"),
                    online = row.optBoolean("online"),
                )
            }
        }
        return null to rows
    }

    /** The account_characters op's rows for one account. */
    fun parseCharacters(json: String?): Pair<String?, List<CharacterRow>> {
        if (json.isNullOrBlank()) return "world-not-ready" to emptyList()
        val value = runCatching { JSONObject(json) }.getOrNull() ?: return "unparseable" to emptyList()
        if (!value.optBoolean("ok")) return (value.optString("reason", "unavailable")) to emptyList()
        val rows = ArrayList<CharacterRow>()
        val array = value.optJSONArray("characters")
        if (array != null) {
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                rows += CharacterRow(
                    guid = row.optLong("guid"),
                    name = row.optString("name"),
                    race = row.optInt("race"),
                    klass = row.optInt("class"),
                    level = row.optInt("level"),
                )
            }
        }
        return null to rows
    }

    /** The ban/unban verbs' verdict (the console output rides for the status line). */
    fun parseBanVerdict(json: String?): Pair<Boolean, String> {
        if (json.isNullOrBlank()) return false to "world-not-ready"
        val value = runCatching { JSONObject(json) }.getOrNull() ?: return false to "unparseable"
        val output = value.optString("output").trim()
        if (!value.optBoolean("ok")) {
            return false to output.ifEmpty { value.optString("reason", "ban-refused") }
        }
        return true to output.ifEmpty { "done" }
    }
}
