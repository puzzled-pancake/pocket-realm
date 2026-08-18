package com.pocketrealm.database

import org.json.JSONObject

/** Pure validation for the durable database generation and clean-stop seals. */
internal object DatabaseGenerationSeal {
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val SECRET = Regex("[0-9a-f]{48}")

    data class InitializedInput(
        val markerText: String?,
        val expectedProvider: String,
        val expectedBootstrapSha256: String,
        val upgradeInfo: String?,
        val expectedUpgradeInfo: String,
        val datadirDirectory: Boolean,
        val mysqlDirectory: Boolean,
        val mysqlEntries: Collection<String>,
        val secretsText: String?,
    )

    fun initialized(input: InitializedInput): Boolean = runCatching {
        check(SHA256.matches(input.expectedBootstrapSha256))
        val marker = JSONObject(requireNotNull(input.markerText))
        check(marker.getInt("schema") == 1)
        check(marker.getString("provider") == input.expectedProvider)
        check(marker.getString("bootstrapSha256") == input.expectedBootstrapSha256)
        check(marker.getLong("initializedAt") > 0)
        check(input.datadirDirectory && input.mysqlDirectory)
        check(input.mysqlEntries.any {
            it.startsWith("global_priv.") || it.startsWith("user.")
        })
        check(input.upgradeInfo?.trim() == input.expectedUpgradeInfo)
        check(validSecrets(input.secretsText))
    }.isSuccess

    fun clean(markerText: String?, expectedProvider: String): Boolean = runCatching {
        val marker = JSONObject(requireNotNull(markerText))
        check(marker.getInt("schema") == 1)
        check(marker.getString("provider") == expectedProvider)
        check(marker.getLong("stoppedAt") > 0)
    }.isSuccess

    fun validSecrets(text: String?): Boolean = runCatching {
        val secrets = JSONObject(requireNotNull(text))
        check(secrets.getInt("schema") == 1)
        val admin = secrets.getString("admin")
        val core = secrets.getString("core")
        check(SECRET.matches(admin) && SECRET.matches(core) && admin != core)
    }.isSuccess
}
