package com.pocketrealm.supervisor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSnapshotJournalCodecTest {
    @Test fun schemaTwoJournalMigratesToLocalLoopback() {
        val legacy = legacyJournal()

        val migrated = RuntimeSnapshotJournalCodec.decode(legacy)

        assertEquals(RuntimeSnapshot.JOURNAL_SCHEMA, migrated.schema)
        assertEquals(RuntimeMode.LOCAL, migrated.runtimeMode)
        assertEquals(RealmEndpoint.LOCAL, migrated.realmEndpoint)
        assertTrue(migrated.clean)
    }

    @Test fun dirtySchemaTwoJournalRetainsOwnershipForSafeLocalRecovery() {
        val legacy = legacyJournal()
        val token = "ab".repeat(32)
        legacy.put("sessionId", "123e4567-e89b-12d3-a456-426614174000")
            .put("phase", RuntimePhase.RUNNING.name)
            .put("requestedProfile", "mobile-low-v1")
            .put("clean", false)
            .put("lastDurableAction", "database-ready")
            .put("recoverability", Recoverability.RECOVERY_REQUIRED.name)
        legacy.getJSONObject("components").getJSONObject("database")
            .put("state", ComponentLifecycle.READY.name)
            .put("instanceToken", token)
            .put("startedAtWallMs", 1)

        val migrated = RuntimeSnapshotJournalCodec.decode(legacy)

        assertEquals(RuntimeMode.LOCAL, migrated.runtimeMode)
        assertEquals(RealmEndpoint.LOCAL, migrated.realmEndpoint)
        assertEquals(token, migrated.components.getValue(RuntimeComponent.DATABASE).instanceToken)
        assertEquals(ComponentLifecycle.READY,
            migrated.components.getValue(RuntimeComponent.DATABASE).state)
    }

    private fun legacyJournal(): JSONObject {
        val components = JSONObject()
        RuntimeComponent.entries.forEach { component ->
            components.put(component.name.lowercase(), JSONObject()
                .put("state", ComponentLifecycle.STOPPED.name)
                .put("instanceToken", JSONObject.NULL)
                .put("startedAtWallMs", JSONObject.NULL)
                .put("detail", ""))
        }
        return JSONObject()
            .put("schema", 2)
            .put("sessionId", JSONObject.NULL)
            .put("phase", RuntimePhase.STOPPED.name)
            .put("requestedProfile", JSONObject.NULL)
            .put("clean", true)
            .put("components", components)
            .put("lastDurableAction", "stopped")
            .put("lastError", JSONObject.NULL)
            .put("updatedAtWallMs", 1)
            .put("updatedAtElapsedMs", 1)
            .put("recoverability", Recoverability.NONE.name)
    }
}
