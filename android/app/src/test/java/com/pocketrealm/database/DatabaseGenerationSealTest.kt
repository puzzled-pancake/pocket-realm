package com.pocketrealm.database

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseGenerationSealTest {
    private val provider = "provider-v1"
    private val bootstrap = "a".repeat(64)
    private val secrets = JSONObject().put("schema", 1)
        .put("admin", "b".repeat(48)).put("core", "c".repeat(48)).toString()
    private val marker = JSONObject().put("schema", 1).put("provider", provider)
        .put("bootstrapSha256", bootstrap).put("initializedAt", 1).toString()

    private fun input(
        markerText: String? = marker,
        providerId: String = provider,
        bootstrapSha: String = bootstrap,
        upgrade: String? = "12.3.2-MariaDB",
        mysqlEntries: Collection<String> = listOf("global_priv.MAI"),
        secretsText: String? = secrets,
    ) = DatabaseGenerationSeal.InitializedInput(
        markerText = markerText,
        expectedProvider = providerId,
        expectedBootstrapSha256 = bootstrapSha,
        upgradeInfo = upgrade,
        expectedUpgradeInfo = "12.3.2-MariaDB",
        datadirDirectory = true,
        mysqlDirectory = true,
        mysqlEntries = mysqlEntries,
        secretsText = secretsText,
    )

    @Test fun validGenerationAndCleanSealPass() {
        assertTrue(DatabaseGenerationSeal.initialized(input()))
        val clean = JSONObject().put("schema", 1).put("provider", provider)
            .put("stoppedAt", 1).toString()
        assertTrue(DatabaseGenerationSeal.clean(clean, provider))
    }

    @Test fun providerBootstrapUpgradeAndSystemTableDriftFailClosed() {
        assertFalse(DatabaseGenerationSeal.initialized(input(providerId = "other")))
        assertFalse(DatabaseGenerationSeal.initialized(input(bootstrapSha = "d".repeat(64))))
        assertFalse(DatabaseGenerationSeal.initialized(input(upgrade = "wrong")))
        assertFalse(DatabaseGenerationSeal.initialized(input(mysqlEntries = emptyList())))
    }

    @Test fun malformedMarkersAndSecretsFailClosed() {
        assertFalse(DatabaseGenerationSeal.initialized(input(markerText = null)))
        assertFalse(DatabaseGenerationSeal.initialized(input(markerText = "{")))
        assertFalse(DatabaseGenerationSeal.clean(null, provider))
        assertFalse(DatabaseGenerationSeal.initialized(input(secretsText = "{}")))
        assertFalse(DatabaseGenerationSeal.validSecrets(null))
        assertFalse(DatabaseGenerationSeal.validSecrets(JSONObject().put("schema", 1)
            .put("admin", "b".repeat(48)).put("core", "b".repeat(48)).toString()))
        assertFalse(DatabaseGenerationSeal.clean("{}", provider))
        assertFalse(DatabaseGenerationSeal.clean(
            JSONObject().put("schema", 1).put("provider", "other")
                .put("stoppedAt", 1).toString(),
            provider,
        ))
    }
}
