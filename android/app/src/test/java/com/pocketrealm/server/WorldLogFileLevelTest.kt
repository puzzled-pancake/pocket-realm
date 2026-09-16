package com.pocketrealm.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The world.conf LogFileLevel staged at world start. The default drops
 * the vendored level 3 to errors-only, matching realmd's LogFileLevel = 1
 * (level 3 floods world.log with movement and battleground churn — 79.7 MB
 * over a 30-minute soak, with no field-diagnostic value; level 2 is
 * avoided because it keeps the movement churn). The advanced "World
 * debug logs" toggle opts back into verbose, effective on the next realm
 * start. ServerRuntimeFiles.worldConfig interpolates this pure mapping into
 * the emission; the mapping, not the string template, is the contract.
 */
class WorldLogFileLevelTest {

    @Test
    fun defaultKeepsErrorsOnlyMatchingRealmd() {
        assertEquals(1, ServerRuntimeFiles.worldLogFileLevel(worldDebugLogs = false))
        assertEquals(ServerRuntimeFiles.DEFAULT_WORLD_LOG_FILE_LEVEL, 1)
    }

    @Test
    fun advancedToggleStagesVerboseForTheNextRealmStart() {
        assertEquals(3, ServerRuntimeFiles.worldLogFileLevel(worldDebugLogs = true))
        assertEquals(ServerRuntimeFiles.DEBUG_WORLD_LOG_FILE_LEVEL, 3)
    }
}
