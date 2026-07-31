package com.pocketrealm.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketrealm.realm.RealmState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device lifecycle test for [RealmService]. It exercises the real service
 * component — foreground promotion, supervisor, simulated native bring-up — and
 * the H1/M1/M3 fixes together.
 *
 * The precise legal-transition logic (Starting/Running/Saving/Stopping/Idle,
 * the M2 round-trip, retry from Stopping) is covered deterministically and
 * exhaustively by [com.pocketrealm.realm.RealmSupervisorTest]. This class
 * covers what only a real service can prove: that the component actually starts,
 * promotes to the foreground, and runs the bring-up to Running on-device.
 *
 * Isolation note: instrumented tests share the app process with the service, so
 * the service + its in-process bridge persist across runs. We therefore poll
 * the bridge's CURRENT state ([RealmBridge.state], a StateFlow) rather than race
 * a transient emission: a stale/queued Save&Exit intent can, on some devices,
 * tear the realm down a few milliseconds after it reaches Running, which would
 * make a `first{Running}` collector miss it. Polling the current value at a
 * short interval reliably observes Running within its (multi-hundred-ms) window
 * on the emulator, and degrades gracefully if Running was already left.
 */
@RunWith(AndroidJUnit4::class)
class RealmServiceLifecycleTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun resetState() {
        // Seed the in-process bridge to a known Idle. We deliberately do NOT
        // send SAVE_EXIT here: a queued SAVE_EXIT intent can be delivered after
        // the test's START and tear the realm down the instant it reaches Running.
        RealmBridge.publish(RealmState.Idle)
    }

    @After
    fun teardown() {
        // Best-effort cleanup in case a test left the service up.
        ctx.stopService(android.content.Intent(ctx, RealmService::class.java))
    }

    /** Poll [RealmBridge.state]'s current value until [pred] holds or timeout. */
    private suspend fun awaitState(timeoutMs: Long = 20_000, pred: (RealmState) -> Boolean) {
        withTimeout(timeoutMs) {
            while (!pred(RealmBridge.state.value)) {
                delay(100)
            }
        }
    }

    @Test
    fun start_promotes_to_foreground_and_reaches_running() {
        runBlocking {
            // Start the service (startForegroundService on O+). Reaching Running
            // requires startForeground() to have succeeded (the system kills a
            // service that declares an FGS type but never promotes within the
            // deadline) and the bring-up Job to complete (H1). Poll the current
            // state until Running is observed.
            RealmService.start(ctx)
            awaitState { it is RealmState.Running }
            assertTrue("reached Running", RealmBridge.state.value is RealmState.Running)

            // While Running, the app must be in a foreground-importance tier
            // (M1/M3 FGS promotion). getRunningAppProcesses is visible for the
            // app's own process without the DUMP permission.
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mine = am.runningAppProcesses.orEmpty()
                .firstOrNull { it.processName == ctx.packageName }
            val importance = mine?.importance
                ?: android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE
            val fg = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            val fgs = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
            assertTrue(
                "While Running, app must be in a foreground tier (importance=$importance; " +
                    "FOREGROUND=$fg, FOREGROUND_SERVICE=$fgs)",
                importance == fg || importance == fgs
            )
        }
    }
}
