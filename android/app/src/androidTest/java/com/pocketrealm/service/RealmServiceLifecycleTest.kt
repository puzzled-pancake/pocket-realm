package com.pocketrealm.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketrealm.realm.RealmState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device lifecycle test for [RealmService]. This exercises the real service
 * component (foreground promotion, supervisor, simulated native bring-up) and
 * the H1/M2/M3 fixes together:
 *
 *  - H1: the bring-up Job is owned and the service reaches Running (it cannot
 *    if the launch is never stored).
 *  - M1/M3: the service promotes to foreground and runs the bring-up.
 *  - M2: Save & Exit drives Saving -> Stopping -> Idle and the service tears
 *    down, rather than short-circuiting to Idle before Stopping.
 *
 * The native realm is not built into this APK yet (O04), so the bring-up is the
 * simulated health walk in RealmService. The state transitions under test are
 * identical to the future native path.
 */
@RunWith(AndroidJUnit4::class)
class RealmServiceLifecycleTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun resetState() {
        // Tests run in the app process and share one RealmService instance + one
        // RealmBridge singleton. Before each test, stop any service left up by a
        // prior test. The service's onDestroy cancels its supervisor coroutine,
        // which may leave the bridge at a non-Idle value; reset it explicitly so
        // the next start's requestStart() is not rejected as out-of-order and so
        // a first{Idle} does not hang on stale state.
        runBlocking {
            ctx.stopService(android.content.Intent(ctx, RealmService::class.java))
            // Give the system a brief moment to process stopService before reset.
            kotlinx.coroutines.delay(500)
            RealmBridge.publish(RealmState.Idle)
        }
    }

    @After
    fun teardown() {
        // Best-effort cleanup in case a test left the service up.
        ctx.stopService(android.content.Intent(ctx, RealmService::class.java))
    }

    @Test
    fun start_reaches_running_then_save_exit_reaches_idle() {
        runBlocking {
            // Start the service. On O+ this must go through startForegroundService;
            // RealmService.start does exactly that.
            RealmService.start(ctx)

            // Wait until the simulated bring-up completes and health holds. The
            // walk is ~6 * STARTUP_STEP_MS; allow generous headroom for the emulator.
            val running = withTimeout(20_000) { RealmBridge.state.first { it is RealmState.Running } }
            assertTrue("reached Running", running is RealmState.Running)

            // Trigger Save & Exit. The service must observe Saving, then Stopping,
            // then return to Idle (the M2 path). We assert the terminal Idle is
            // reached; the Stopping intermediate is covered by RealmSupervisorTest.
            RealmService.saveExit(ctx)
            val idle = withTimeout(20_000) { RealmBridge.state.first { it is RealmState.Idle } }
            assertTrue("returned to Idle after save & exit", idle is RealmState.Idle)
        }
    }

    @Test
    fun foreground_service_runs_during_playback() {
        runBlocking {
            RealmService.start(ctx)
            // Block until Running. Reaching Running requires startForeground() to
            // have succeeded (the system kills a service that declares an FGS type
            // but never promotes within the deadline), so this implicitly proves
            // FGS promotion (M1/M3). We additionally assert the process is in a
            // foreground-importance tier via getRunningAppProcesses(), which is
            // visible for the app's own process without the DUMP permission.
            withTimeout(20_000) { RealmBridge.state.first { it is RealmState.Running } }

            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            val procs = am.runningAppProcesses.orEmpty()
            val mine = procs.firstOrNull { it.processName == ctx.packageName }
            val importance = mine?.importance
                ?: android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE
            val fg = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            val fgs = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
            assertTrue(
                "While Running, app must be in a foreground tier (importance=" +
                    "$importance; FOREGROUND=$fg, FOREGROUND_SERVICE=$fgs)",
                importance == fg || importance == fgs
            )

            // Cleanup.
            RealmService.saveExit(ctx)
            withTimeout(20_000) { RealmBridge.state.first { it is RealmState.Idle } }
        }
    }
}
