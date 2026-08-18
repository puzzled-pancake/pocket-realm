package com.pocketrealm.database

import org.json.JSONObject

/** Immutable proof that the datadir contains the APK's complete migration set. */
internal object DatabaseMigrationSeal {
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val PROVIDER = Regex("[A-Za-z0-9._+-]{1,96}")

    fun create(
        providerId: String,
        bootstrapSha256: String,
        manifestSha256: String,
        migrationCount: Int,
        completedAt: Long,
    ): String {
        require(PROVIDER.matches(providerId))
        require(SHA256.matches(bootstrapSha256))
        require(SHA256.matches(manifestSha256))
        require(migrationCount >= 0)
        return JSONObject()
            .put("schema", 1)
            .put("provider", providerId)
            .put("bootstrapSha256", bootstrapSha256)
            .put("manifestSha256", manifestSha256)
            .put("migrationCount", migrationCount)
            .put("completedAt", completedAt)
            .toString()
    }

    fun current(
        markerText: String?,
        expectedProviderId: String,
        expectedBootstrapSha256: String,
        expectedManifestSha256: String,
        expectedMigrationCount: Int,
    ): Boolean = runCatching {
        if (markerText == null || !PROVIDER.matches(expectedProviderId) ||
            !SHA256.matches(expectedBootstrapSha256) ||
            !SHA256.matches(expectedManifestSha256) || expectedMigrationCount < 0
        ) return@runCatching false
        val marker = JSONObject(markerText)
        marker.length() == 6 &&
            marker.getInt("schema") == 1 &&
            marker.getString("provider") == expectedProviderId &&
            marker.getString("bootstrapSha256") == expectedBootstrapSha256 &&
            marker.getString("manifestSha256") == expectedManifestSha256 &&
            marker.getInt("migrationCount") == expectedMigrationCount &&
            marker.getLong("completedAt") > 0L
    }.getOrDefault(false)
}
