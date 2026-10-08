package com.pocketrealm.server

import com.pocketrealm.BuildConfig
import java.lang.ProcessHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESK-127: the desktop JVM twin of the Android ServerStatusJson — the
 * shared ABI's statusNative telemetry indices rendered into status JSON
 * (the desktop previously discarded 3..). Pure JVM: the status arrays are
 * constructed by hand, no DLL lane involved.
 */
class ServerStatusJsonTest {

    /** world width 8: [abi, state, error, heartbeatMs, tickCount,
     * lastTickMs, maxTickMs, activeSessions]. */
    private val worldStatus = longArrayOf(1, 3, 0, 250, 1234, 42, 90, 2)

    /** realm width 5: [abi, state, error, heartbeatMs, heartbeatCount]. */
    private val realmStatus = longArrayOf(1, 2, 0, 250, 7)

    @Test
    fun worldJsonCarriesEveryTelemetryIndex() {
        val json = ServerStatusJson.world(worldStatus, "world detail")
        assertEquals(1234, json.getLong("tickCount"))
        assertEquals(42, json.getLong("lastTickMs"))
        assertEquals(90, json.getLong("maxTickMs"))
        assertEquals(2, json.getLong("activeSessions"))
        assertEquals(250, json.getLong("heartbeatMs"))
        assertEquals(ServerRuntimeContract.WORLD_PORT.toLong(), json.getLong("listenPort"))
        assertEquals("world", json.getString("component"))
        assertTrue(json.getBoolean("ok"))
        assertEquals(3, json.getLong("stateCode"))
        assertEquals(ServerRuntimeContract.stateName(3), json.getString("state"))
        assertEquals(ServerRuntimeContract.errorName(0), json.getString("error"))
    }

    @Test
    fun realmJsonCarriesTheHeartbeatCount() {
        val json = ServerStatusJson.realm(realmStatus, "realm detail")
        assertEquals(7, json.getLong("heartbeatCount"))
        assertEquals(ServerRuntimeContract.REALM_PORT.toLong(), json.getLong("listenPort"))
        assertEquals("realm", json.getString("component"))
        assertEquals(250, json.getLong("heartbeatMs"))
    }

    @Test
    fun baseFieldsCarryTheDesktopProvenance() {
        val json = ServerStatusJson.world(worldStatus, "detail")
        assertEquals(ProcessHandle.current().pid(), json.getLong("pid"))
        assertEquals(BuildConfig.NATIVE_RUNTIME_BUILD_ID, json.getString("runtimeBuildId"))
        assertEquals(BuildConfig.NATIVE_CMANGOS_COMMIT, json.getString("nativeCmangosCommit"))
        assertEquals(BuildConfig.NATIVE_PLAYERBOTS_COMMIT, json.getString("nativePlayerbotsCommit"))
        assertEquals(ServerRuntimeContract.CONTROL_SCHEMA, json.getInt("schema"))
        assertEquals("detail", json.getString("detail"))
        assertEquals(1, json.getLong("abiVersion"))
    }

    @Test
    fun operationJsonMapsTheResultCode() {
        val ok = ServerStatusJson.operation("world", "stop", 0)
        assertTrue(ok.getBoolean("ok"))
        assertEquals(0, ok.getInt("result"))
        assertEquals(ServerRuntimeContract.errorName(0), ok.getString("error"))
        assertEquals(ProcessHandle.current().pid(), ok.getLong("pid"))

        val failed = ServerStatusJson.operation("realm", "start", 1)
        assertFalse(failed.getBoolean("ok"))
        assertEquals(1, failed.getInt("result"))
        assertEquals(ServerRuntimeContract.errorName(1), failed.getString("error"))
    }

    @Test
    fun aDriftedStatusArrayFailsLoud() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerStatusJson.world(longArrayOf(1, 3, 0, 1, 1, 1, 1), "short")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerStatusJson.realm(longArrayOf(0, 1, 0, 1, 1), "wrong abi")
        }
    }

    @Test
    fun detailIsBoundedLikeTheAndroidTwin() {
        val json = ServerStatusJson.world(worldStatus, "x".repeat(4096), listenAddress = "127.0.0.1")
        assertEquals(512, json.getString("detail").length)
    }
}
