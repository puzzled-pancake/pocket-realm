package com.pocketrealm.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * the DesktopAppModel companion-mode wrapper over the shared
 * supervisor's journaled RUNNING→PAUSED phase operation (the Android
 * RealmService "companion-mode" op). The phase guards come from the shared
 * DurableRuntimeSupervisor; these legs pin the model's delegation honestly
 * refusing in the phases where the transition does not apply (the happy
 * RUNNING→PAUSED transition rides the native-lane gates, whose boot legs
 * the shared DurableRuntimeSupervisorTest already pins with fake backends).
 */
class DesktopAppModelCompanionModeTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun companionEnterRequiresTheRunningPhase() {
        val model = DesktopAppModel(DesktopStorageRoots(folder.newFolder("roots")))
        try {
            val refused = runBlocking { model.setCompanionMode(true) }
            assertFalse("enter from a non-RUNNING phase must refuse: ${refused.detail}", refused.ok)
            assertTrue(
                "the refusal is the shared supervisor's phase guard: ${refused.detail}",
                "companion mode requires phase RUNNING" in refused.detail,
            )
        } finally {
            model.close()
        }
    }

    @Test
    fun companionExitRequiresThePausedPhase() {
        val model = DesktopAppModel(DesktopStorageRoots(folder.newFolder("roots")))
        try {
            val refused = runBlocking { model.setCompanionMode(false) }
            assertFalse("exit from a non-PAUSED phase must refuse: ${refused.detail}", refused.ok)
            assertTrue(
                "the refusal is the shared supervisor's phase guard: ${refused.detail}",
                "companion exit requires phase PAUSED" in refused.detail,
            )
        } finally {
            model.close()
        }
    }
}
