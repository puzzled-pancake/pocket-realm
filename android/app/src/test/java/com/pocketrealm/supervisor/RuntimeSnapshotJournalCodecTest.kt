package com.pocketrealm.supervisor

import com.pocketrealm.client.ClientFlavors
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSnapshotJournalCodecTest {
    private fun goldenFixtureText(name: String): String =
        javaClass.getResourceAsStream("/fixtures/g3/$name")!!.readBytes().toString(Charsets.UTF_8)

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

    // ---- G3-4: flavor rides as an optional field; vanilla bytes are frozen ----

    @Test fun journalSchemaConstantNeverMovesToThreePlusOne() {
        assertEquals(3, RuntimeSnapshot.JOURNAL_SCHEMA)
    }

    @Test fun currentReleaseGoldenJournalDecodesAsVanilla() {
        val fixture = goldenFixtureText("journal-current-release.json")
        val decoded = RuntimeSnapshotJournalCodec.decode(JSONObject(fixture))
        assertEquals(ClientFlavors.VANILLA.flavorId, decoded.flavor)
        assertEquals("123e4567-e89b-12d3-a456-426614174000", decoded.sessionId)
        assertEquals(RuntimePhase.STOPPED, decoded.phase)
    }

    @Test fun vanillaSnapshotReencodesToTheCommittedCurrentReleaseContent() {
        val fixture = goldenFixtureText("journal-current-release.json")
        val decoded = RuntimeSnapshotJournalCodec.decode(JSONObject(fixture))
        val reencoded = RuntimeSnapshotJournalCodec.encode(decoded).toString()
        // The fixture carries the SHIPPING (Android org.json) byte shape —
        // insertion order and the null-key-absence the codec's `put(name,
        // null)` produces. The JVM classpath's org.json hash-scrambles key
        // order, so this lane pins content exactly (sorted-key canonical
        // form); the deployed-byte equality is pinned on the device
        // (RuntimeSnapshotJournalCodecInstrumentedTest).
        assertEquals(canonical(fixture), canonical(reencoded))
        assertTrue("vanilla journal must not carry a flavor key", !reencoded.contains("flavor"))
        // And the fixture itself must carry the null-key-absence semantics:
        // a stopped component and a null lastError produce NO such keys.
        val fixtureJson = JSONObject(fixture)
        assertTrue(!fixtureJson.has("lastError"))
        assertTrue(!fixtureJson.getJSONObject("components").getJSONObject("database").has("instanceToken"))
    }

    /** Order-insensitive, content-exact serialization (sorted keys, compact). */
    private fun canonical(text: String): String = canonicalJson(JSONObject(text))

    private fun canonicalJson(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonicalJson(value.get(key))
        }
        is org.json.JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index ->
            canonicalJson(value.get(index))
        }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    @Test fun nonVanillaFlavoredJournalDecodesAndSurvivesTheCodecRoundTripAtSchemaThree() {
        val flavored = RuntimeSnapshotJournalCodec.encode(
            currentReleaseSnapshot().copy(flavor = ClientFlavors.TBC.flavorId),
        ).toString()
        assertTrue("non-vanilla journal carries the flavor key", flavored.contains("flavor"))
        val decoded = RuntimeSnapshotJournalCodec.decode(JSONObject(flavored))
        assertEquals(ClientFlavors.TBC.flavorId, decoded.flavor)
        assertEquals(RuntimeSnapshot.JOURNAL_SCHEMA, decoded.schema)
        assertEquals(currentReleaseSnapshot().copy(flavor = ClientFlavors.TBC.flavorId), decoded)
    }

    @Test fun preFlavorJournalWithExplicitVanillaFlavorKeyDecodesIdentically() {
        val fixture = JSONObject(goldenFixtureText("journal-current-release.json"))
        fixture.put("flavor", ClientFlavors.VANILLA.flavorId)
        val decoded = RuntimeSnapshotJournalCodec.decode(fixture)
        assertEquals(ClientFlavors.VANILLA.flavorId, decoded.flavor)
    }

    @Test fun unknownFlavorJournalIsRefused() {
        val fixture = JSONObject(goldenFixtureText("journal-current-release.json"))
        fixture.put("flavor", "galaxy")
        try {
            RuntimeSnapshotJournalCodec.decode(fixture)
            throw AssertionError("unknown journal flavor must not decode")
        } catch (expected: IllegalArgumentException) {
            assertEquals("unsupported journal flavor", expected.message)
        }
    }

    // ---- design-tunnel §5: the optional advertised-address field --------

    @Test fun tunnelAdvertisedJournalRoundTripsAtTheExistingSchemaPair() {
        val advertised = currentReleaseSnapshot().copy(
            runtimeMode = RuntimeMode.LAN_HOST,
            realmEndpoint = RealmEndpoint.parseLan("192.168.1.50"),
            advertisedAddress = "100.64.12.34",
        )
        val encoded = RuntimeSnapshotJournalCodec.encode(advertised).toString()
        assertTrue("the optional key rides the journal", encoded.contains("advertisedAddress"))
        val decoded = RuntimeSnapshotJournalCodec.decode(JSONObject(encoded))
        assertEquals("100.64.12.34", decoded.advertisedAddress)
        assertEquals(advertised, decoded)
    }

    @Test fun plainJournalsStayByteIdenticalAndDecodeWithoutAnAdvertisement() {
        val plain = currentReleaseSnapshot()
        val encoded = RuntimeSnapshotJournalCodec.encode(plain).toString()
        assertFalse(
            "no advertisement key on the plain journal (pre-tunnel bytes unchanged)",
            encoded.contains("advertisedAddress"),
        )
        assertEquals(null, RuntimeSnapshotJournalCodec.decode(JSONObject(encoded)).advertisedAddress)
    }

    @Test fun aMalformedAdvertisedJournalValueIsRefusedAtDecode() {
        val fixture = JSONObject(goldenFixtureText("journal-current-release.json"))
        fixture.put("advertisedAddress", "my-realm.ts.net")
        try {
            RuntimeSnapshotJournalCodec.decode(fixture)
            throw AssertionError("a malformed journal advertisement must not decode")
        } catch (expected: IllegalArgumentException) {
            // The typed grammar's own refusal — durable state is not trusted.
        }
    }

    private fun currentReleaseSnapshot(): RuntimeSnapshot =
        RuntimeSnapshotJournalCodec.decode(JSONObject(goldenFixtureText("journal-current-release.json")))

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
