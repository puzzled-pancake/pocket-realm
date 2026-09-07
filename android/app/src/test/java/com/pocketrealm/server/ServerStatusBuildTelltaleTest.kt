package com.pocketrealm.server

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay status surface (WorldConsoleRelay world-status / realm-status /
 * operation passthroughs) must carry the lockfile-derived runtime telltale,
 * so a harness session can compare it against the CURRENT lane lockfile at
 * attach time and refuse a stale APK instead of hitting missing JNI ops
 * mid-run (the QA incident this prevents).
 */
class ServerStatusBuildTelltaleTest {
    private val realmValues = longArrayOf(
        ServerRuntimeContract.ABI_VERSION, 2L, 0L, 500L, 7L,
    )
    private val worldValues = longArrayOf(
        ServerRuntimeContract.ABI_VERSION, 2L, 0L, 500L, 8L, 11L, 40L, 1L,
    )

    @Test fun worldStatusCarriesRuntimeBuildIdAndSourceCommits() {
        val status = ServerStatusJson.world(worldValues, "READY")
        assertTelltale(status)
    }

    @Test fun realmStatusCarriesRuntimeBuildIdAndSourceCommits() {
        val status = ServerStatusJson.realm(realmValues, "READY")
        assertTelltale(status)
    }

    @Test fun operationResultCarriesRuntimeBuildIdAndSourceCommits() {
        val result = ServerStatusJson.operation("world", "world-chat", 0)
        assertTelltale(result)
    }

    @Test fun buildIdIsLockfileDerivedNotTheRetiredHandWrittenLabel() {
        // The retired constant could never detect staleness: it never moved
        // with the native lane. The BuildConfig-derived id must carry the
        // lane identity instead of that dead label.
        assertTrue(
            ServerRuntimeContract.RUNTIME_BUILD_ID.isNotEmpty() &&
                ServerRuntimeContract.RUNTIME_BUILD_ID != "o13-cmangos-c096bada-playerbots-v1",
        )
        assertTrue(ServerRuntimeContract.RUNTIME_BUILD_ID.contains("cmangos-"))
    }

    @Test fun sourceCommitPinsAreFullFortyCharHexIds() {
        assertEquals(40, ServerRuntimeContract.NATIVE_CMANGOS_COMMIT.length)
        assertEquals(40, ServerRuntimeContract.NATIVE_PLAYERBOTS_COMMIT.length)
        assertTrue(ServerRuntimeContract.NATIVE_CMANGOS_COMMIT.all { it.isDigit() || it in 'a'..'f' })
        assertTrue(ServerRuntimeContract.NATIVE_PLAYERBOTS_COMMIT.all { it.isDigit() || it in 'a'..'f' })
    }

    private fun assertTelltale(payload: JSONObject) {
        assertEquals(
            ServerRuntimeContract.RUNTIME_BUILD_ID,
            payload.getString("runtimeBuildId"),
        )
        assertEquals(
            ServerRuntimeContract.NATIVE_CMANGOS_COMMIT,
            payload.getString("nativeCmangosCommit"),
        )
        assertEquals(
            ServerRuntimeContract.NATIVE_PLAYERBOTS_COMMIT,
            payload.getString("nativePlayerbotsCommit"),
        )
    }
}
