package com.pocketrealm.database

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseMigrationSealTest {
    private val provider = "mariadb-arm64-v1"
    private val bootstrap = "a".repeat(64)
    private val manifest = "b".repeat(64)

    private fun marker(): String = DatabaseMigrationSeal.create(
        providerId = provider,
        bootstrapSha256 = bootstrap,
        manifestSha256 = manifest,
        migrationCount = 4,
        completedAt = 1234L,
    )

    private fun current(value: String? = marker()): Boolean = DatabaseMigrationSeal.current(
        markerText = value,
        expectedProviderId = provider,
        expectedBootstrapSha256 = bootstrap,
        expectedManifestSha256 = manifest,
        expectedMigrationCount = 4,
    )

    @Test fun exactSealIsCurrent() = assertTrue(current())

    @Test fun missingMalformedOrStaleSealIsRejected() {
        assertFalse(current(null))
        assertFalse(current("{"))
        assertFalse(current(JSONObject(marker()).put("provider", "other").toString()))
        assertFalse(current(JSONObject(marker()).put("bootstrapSha256", "c".repeat(64)).toString()))
        assertFalse(current(JSONObject(marker()).put("manifestSha256", "d".repeat(64)).toString()))
        assertFalse(current(JSONObject(marker()).put("migrationCount", 3).toString()))
        assertFalse(current(JSONObject(marker()).put("completedAt", 0).toString()))
        assertFalse(current(JSONObject(marker()).put("unexpected", true).toString()))
    }
}
