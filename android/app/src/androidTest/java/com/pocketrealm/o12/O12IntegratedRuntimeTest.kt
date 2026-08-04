package com.pocketrealm.o12

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.system.Os
import android.system.OsConstants
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketrealm.client.ClientDisplayService
import com.pocketrealm.client.IClientDisplayControl
import com.pocketrealm.client.IntegratedClientDisplay
import com.pocketrealm.diagnostics.SupportBundleExporter
import com.pocketrealm.service.RealmService
import com.pocketrealm.server.IWorldControl
import com.pocketrealm.server.WorldRuntimeService
import com.pocketrealm.ui.MainActivity
import com.pocketrealm.supervisor.AndroidRuntimeBackend
import com.pocketrealm.supervisor.IRuntimeSupervisorControl
import com.pocketrealm.supervisor.RuntimePhase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Device acceptance for the O12 one-tap server + real client integration. */
@RunWith(AndroidJUnit4::class)
class O12IntegratedRuntimeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun integratedStartAccountClientCleanStopBackupAndRestore() {
        val arguments = InstrumentationRegistry.getArguments()
        val soakSeconds = arguments.getString("o12SoakSeconds")?.toIntOrNull() ?: 0
        val cleanCycles = arguments.getString("o12CleanCycles")?.toIntOrNull() ?: 1
        val forcedRecovery = arguments.getString("o12ForcedRecovery")?.toBooleanStrictOrNull() ?: false
        require(soakSeconds in 0..3_600) { "o12SoakSeconds must be in 0..3600" }
        require(cleanCycles in 1..20) { "o12CleanCycles must be in 1..20" }
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val supervisor = bind(
            Intent(context, RealmService::class.java),
            IRuntimeSupervisorControl.Stub::asInterface,
        )
        val display = bind(
            Intent(context, ClientDisplayService::class.java),
            IClientDisplayControl.Stub::asInterface,
        )
        var world: Bound<IWorldControl>? = null
        try {
            normalizeStopped(supervisor.api)
            assertAccepted(supervisor.api.start(AndroidRuntimeBackend.INTEGRATED_PROFILE, true))
            val running = waitPhase(supervisor.api, RuntimePhase.RUNNING, 360_000)
            assertOwnedReady(running, "database", "realm", "world", "client")
            // Bind after normalization/start. A clean native shutdown retires
            // the old :world process by design, so a binding acquired before
            // normalizeStopped can correctly become a dead Binder.
            world = bind(
                Intent(context, WorldRuntimeService::class.java),
                IWorldControl.Stub::asInterface,
            )
            val worldApi = requireNotNull(world).api
            val worldConfig = File(context.noBackupFilesDir, "server/run/mangosd.conf").readText()
            assertTrue("loopback realm must not kick Wine for burst-delivered pings",
                Regex("(?m)^MaxOverspeedPings\\s*=\\s*0$").containsMatchIn(worldConfig))
            val displayStatus = JSONObject(display.api.status())
            assertTrue(displayStatus.toString(), displayStatus.getBoolean("windowVisible"))

            // SurfaceView pixels are absent from UiAutomation screenshots.
            // Read the integrated renderer on its owning GLES thread so O12's
            // real login surface is proved rather than represented as black.
            // Large-client cold starts can map the X window before WineD3D's
            // first non-black swap. Keep the mapped-window proof separate and
            // allow the renderer a bounded two-minute stabilization window.
            waitForVisibleDisplay(120_000)
            // A mapped/non-black frame can still be Wine's short startup fade.
            // Give GlueXML its bounded initialization window before entering
            // credentials; otherwise the keystrokes can precede the edit boxes.
            Thread.sleep(8_000)
            val loginFrame = captureDisplay()
            val nonBlackPixels = loginFrame.getPixelsCopy().count { (it and 0x00ffffff) != 0 }
            val frameFile = File(context.filesDir, "evidence/O12_LOGIN_SCREEN.png")
            frameFile.parentFile!!.mkdirs()
            frameFile.outputStream().use { loginFrame.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val accountName = "o12${(System.currentTimeMillis() / 1_000) % 1_000_000}"
            // Keep the hidden-field credential letter-only. The account name
            // still proves the number row; avoiding mixed hidden-field input
            // makes a failed SRP proof diagnose auth rather than key layout.
            val accountPassword = "realmtest"
            val account = JSONObject(supervisor.api.createAccount(accountName, accountPassword, 0))
            assertTrue(account.toString(), account.getBoolean("ok"))
            assertTrue(account.getLong("accountId") > 0)
            assertEquals(0, account.getInt("gmLevel"))

            // Drive the real build-5875 login screen through the same input
            // bridge used by touch/keyboards. Coordinates come from the
            // renderer-local 912x1260 frame, not device screenshot space.
            tapDisplay(292f, 620f)
            replaceDisplayText(accountName)
            tapDisplay(292f, 661f)
            replaceDisplayText(accountPassword)
            tapDisplay(292f, 696f)
            Thread.sleep(10_000)
            val preWizardWorld = waitForActiveSession(worldApi, 30_000)
            assertTrue("new account did not authenticate before realm setup: $preWizardWorld",
                preWizardWorld.getLong("activeSessions") > 0)
            val postLoginFrame = captureDisplay()
            val postLoginNonBlack = postLoginFrame.getPixelsCopy().count { (it and 0x00ffffff) != 0 }
            val postLoginFile = File(context.filesDir, "evidence/O12_AFTER_LOGIN.png")
            postLoginFile.outputStream().use { postLoginFrame.compress(Bitmap.CompressFormat.PNG, 100, it) }

            // The app owns one loopback realm and pins its name in the managed
            // safe profile, so the world session opens directly. A pristine
            // 1.12 profile still presents its one-time language/style overlay;
            // complete it and accept the already-assigned local realm.
            Thread.sleep(3_000)
            tapDisplay(423f, 544f)
            Thread.sleep(2_000)
            captureEvidenceFrame("O12_REALM_LANGUAGE_SELECTED.png")
            tapDisplay(482f, 664f)
            Thread.sleep(3_000)
            captureEvidenceFrame("O12_REALM_ASSIGNED.png")
            tapDisplay(346f, 621f)
            Thread.sleep(3_000)
            captureEvidenceFrame("O12_REALM_LIST.png")
            // Select the sole app-owned realm explicitly and confirm it. The
            // row is preselected for this one-realm profile, but clicking it
            // also proves normal pointer selection before the Okay action.
            tapDisplay(270f, 503f)
            Thread.sleep(500)
            tapDisplay(355f, 734f)
            Thread.sleep(10_000)
            val characterFrame = captureDisplay()
            val characterNonBlack = characterFrame.getPixelsCopy().count { (it and 0x00ffffff) != 0 }
            val characterFile = File(context.filesDir, "evidence/O12_CHARACTER_SCREEN.png")
            characterFile.outputStream().use { characterFrame.compress(Bitmap.CompressFormat.PNG, 100, it) }

            // Exercise the normal client flow beyond authentication: create a
            // fresh playable character, return to selection, and enter world.
            val characterName = uniqueCharacterName(System.currentTimeMillis())
            tapDisplay(498f, 740f)
            Thread.sleep(8_000)
            captureEvidenceFrame("O12_CHARACTER_CREATE.png")
            tapDisplay(294f, 777f)
            replaceDisplayText(characterName)
            tapDisplay(497f, 775f)
            Thread.sleep(12_000)
            captureEvidenceFrame("O12_CHARACTER_CREATED.png")
            tapDisplay(287f, 789f)
            val onlineWorld = waitForOnlinePlayer(worldApi, 120_000)
            assertTrue("character never entered the embedded world: $onlineWorld",
                onlineWorld.optLong("onlinePlayers") > 0)
            val firstComplexWorldFrame = waitForComplexDisplay(120_000, 128).first
            waitForMateriallyChangedDisplay(firstComplexWorldFrame, 180_000, 128)
            Thread.sleep(10_000)
            val (inWorldFrame, inWorldNonBlack, inWorldDistinctColors) =
                waitForMateriallyChangedDisplay(firstComplexWorldFrame, 30_000, 128)
            val inWorldFile = File(context.filesDir, "evidence/O12_IN_WORLD.png")
            inWorldFile.outputStream().use { inWorldFrame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val soakSamples = runZeroBotSoak(worldApi, soakSeconds)

            val interactionWorld = JSONObject(worldApi.status())
            val interactionSessions = interactionWorld.getLong("activeSessions")
            val interactionRealm = JSONObject(worldApi.realmStatus())
            assertTrue("authenticated client never opened a world session: $interactionWorld",
                interactionSessions > 0)
            val saveResult = JSONObject(worldApi.save())
            assertTrue("world save failed: $saveResult", saveResult.getBoolean("ok"))
            val persistenceBeforeStop = JSONObject(
                worldApi.characterPersistence(accountName, characterName))
            assertTrue("created character was not queryable: $persistenceBeforeStop",
                persistenceBeforeStop.getBoolean("found"))
            assertTrue("created character has no inventory sentinel: $persistenceBeforeStop",
                persistenceBeforeStop.getInt("inventoryCount") > 0 &&
                    persistenceBeforeStop.getJSONObject("inventorySentinel").getInt("itemEntry") > 0)
            val serverLogs = File(context.noBackupFilesDir, "server/logs")
            listOf("realmd.log", "world.log").forEach { name ->
                val source = File(serverLogs, name)
                if (source.isFile) source.copyTo(File(context.filesDir, "evidence/O12_INTERACTION_$name"), true)
            }

            assertAccepted(supervisor.api.stop(false))
            val stopped = waitPhase(supervisor.api, RuntimePhase.STOPPED, 180_000)
            assertTrue(stopped.toString(), stopped.getBoolean("clean"))
            assertEquals("clean-stop-committed", stopped.getString("lastDurableAction"))

            val requestedName = "o12-${System.currentTimeMillis()}"
            assertAccepted(supervisor.api.createBackup(requestedName))
            val backup = waitMaintenance(supervisor.api, "backup", 240_000)
            assertTrue(backup.toString(), backup.getBoolean("ok"))
            val backups = JSONObject(supervisor.api.listBackups()).getJSONArray("backups")
            assertTrue(backups.length() > 0)
            val snapshotId = (0 until backups.length()).map { backups.getJSONObject(it) }
                .first { it.getString("snapshotId").contains(requestedName) }
                .getString("snapshotId")

            assertAccepted(supervisor.api.restoreBackup(snapshotId))
            val restored = waitMaintenance(supervisor.api, "restore", 360_000)
            assertTrue(restored.toString(), restored.getBoolean("ok"))
            assertTrue(restored.getBoolean("worldReadyVerified"))
            assertTrue(waitPhase(supervisor.api, RuntimePhase.STOPPED, 30_000).getBoolean("clean"))

            // Restore verification must prove the user row, not merely that a
            // fresh datadir can boot. Start the restored native realm without
            // launching another client, query the fixed read-only persistence
            // surface, and compare its canonical durable digest exactly.
            assertAccepted(supervisor.api.start(AndroidRuntimeBackend.INTEGRATED_PROFILE, false))
            waitPhase(supervisor.api, RuntimePhase.WORLD_READY, 360_000)
            world?.close()
            world = bind(
                Intent(context, WorldRuntimeService::class.java),
                IWorldControl.Stub::asInterface,
            )
            val persistenceAfterRestore = JSONObject(
                requireNotNull(world).api.characterPersistence(accountName, characterName))
            assertTrue("restored character was not queryable: $persistenceAfterRestore",
                persistenceAfterRestore.getBoolean("found"))
            assertEquals("restored character durable state changed",
                persistenceBeforeStop.getString("durableSha256"),
                persistenceAfterRestore.getString("durableSha256"))
            val cleanCycleEvidence = JSONArray()
            for (cycle in 1..cleanCycles) {
                val cycleState = if (cycle == 1) persistenceAfterRestore else JSONObject(
                    requireNotNull(world).api.characterPersistence(accountName, characterName))
                assertTrue("character missing on clean cycle $cycle: $cycleState",
                    cycleState.getBoolean("found"))
                assertEquals("character durable state changed on clean cycle $cycle",
                    persistenceBeforeStop.getString("durableSha256"),
                    cycleState.getString("durableSha256"))
                cleanCycleEvidence.put(JSONObject()
                    .put("cycle", cycle)
                    .put("durableSha256", cycleState.getString("durableSha256"))
                    .put("inventorySha256", cycleState.getString("inventorySha256"))
                    .put("questSha256", cycleState.getString("questSha256")))
                assertAccepted(supervisor.api.stop(false))
                assertTrue("clean cycle $cycle did not commit cleanly",
                    waitPhase(supervisor.api, RuntimePhase.STOPPED, 180_000).getBoolean("clean"))
                world?.close()
                world = null
                if (cycle < cleanCycles) {
                    assertAccepted(supervisor.api.start(AndroidRuntimeBackend.INTEGRATED_PROFILE, false))
                    waitPhase(supervisor.api, RuntimePhase.WORLD_READY, 360_000)
                    world = bind(
                        Intent(context, WorldRuntimeService::class.java),
                        IWorldControl.Stub::asInterface,
                    )
                }
            }

            var forcedRecoveryEvidence = JSONObject().put("requested", false)
            if (forcedRecovery) {
                assertAccepted(supervisor.api.start(AndroidRuntimeBackend.INTEGRATED_PROFILE, false))
                waitPhase(supervisor.api, RuntimePhase.WORLD_READY, 360_000)
                world = bind(
                    Intent(context, WorldRuntimeService::class.java),
                    IWorldControl.Stub::asInterface,
                )
                val beforeFailure = JSONObject(
                    requireNotNull(world).api.characterPersistence(accountName, characterName))
                assertEquals(persistenceBeforeStop.getString("durableSha256"),
                    beforeFailure.getString("durableSha256"))
                assertAccepted(supervisor.api.forceComponentForTest("world"))
                val failed = waitPhase(supervisor.api, RuntimePhase.ERROR, 120_000)
                assertFalse("forced world failure was incorrectly marked clean", failed.getBoolean("clean"))
                world?.close()
                world = null
                assertAccepted(supervisor.api.start(AndroidRuntimeBackend.INTEGRATED_PROFILE, false))
                waitPhase(supervisor.api, RuntimePhase.WORLD_READY, 360_000, tolerateInitialError = true)
                world = bind(
                    Intent(context, WorldRuntimeService::class.java),
                    IWorldControl.Stub::asInterface,
                )
                val afterRecovery = JSONObject(
                    requireNotNull(world).api.characterPersistence(accountName, characterName))
                assertEquals("character durable state changed across forced recovery",
                    persistenceBeforeStop.getString("durableSha256"),
                    afterRecovery.getString("durableSha256"))
                forcedRecoveryEvidence = JSONObject().put("requested", true)
                    .put("failurePhase", failed.getString("phase"))
                    .put("failureClean", failed.getBoolean("clean"))
                    .put("durableSha256", afterRecovery.getString("durableSha256"))
                assertAccepted(supervisor.api.stop(false))
                assertTrue(waitPhase(supervisor.api, RuntimePhase.STOPPED, 180_000).getBoolean("clean"))
                world?.close()
                world = null
            }

            val support = SupportBundleExporter(context).export(
                explicitCanaries = listOf(accountName, accountPassword),
                testEntries = mapOf("o12-canary.json" to JSONObject()
                    .put("username", accountName).put("password", accountPassword).toString()),
            )
            val evidence = JSONObject().put("schema", 1).put("feature", "O12")
                .put("ok", true).put("api", android.os.Build.VERSION.SDK_INT)
                .put("abi", android.os.Build.SUPPORTED_ABIS.first())
                .put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
                .put("profile", AndroidRuntimeBackend.INTEGRATED_PROFILE)
                .put("clientWindowVisible", true).put("accountCreated", true)
                .put("accountName", accountName)
                .put("loginFrameNonBlackPixels", nonBlackPixels)
                .put("loginFrame", "O12_LOGIN_SCREEN.png")
                .put("postLoginFrameNonBlackPixels", postLoginNonBlack)
                .put("postLoginFrame", "O12_AFTER_LOGIN.png")
                .put("characterFrameNonBlackPixels", characterNonBlack)
                .put("characterFrame", "O12_CHARACTER_SCREEN.png")
                .put("characterCreated", true).put("characterName", characterName)
                .put("characterCreatedFrame", "O12_CHARACTER_CREATED.png")
                .put("inWorldFrameNonBlackPixels", inWorldNonBlack)
                .put("inWorldFrameDistinctColors", inWorldDistinctColors)
                .put("inWorldFrame", "O12_IN_WORLD.png")
                .put("interactionOnlinePlayers", onlineWorld.getLong("onlinePlayers"))
                .put("interactionActiveSessions", interactionSessions)
                .put("interactionRealmStatus", interactionRealm)
                .put("interactionRealmLog", "O12_INTERACTION_realmd.log")
                .put("interactionWorldLog", "O12_INTERACTION_world.log")
                .put("zeroBotSoakRequestedSeconds", soakSeconds)
                .put("zeroBotSoakSamples", soakSamples)
                .put("loopbackOverspeedPingKickDisabled", true)
                .put("accountGmLevel", account.getInt("gmLevel"))
                .put("cleanStop", true).put("backupSnapshot", snapshotId)
                .put("restoreWorldReadyVerified", true)
                .put("persistenceBeforeStop", persistenceBeforeStop)
                .put("persistenceAfterRestore", persistenceAfterRestore)
                .put("restoreDurableStateExact", true)
                .put("cleanCycleCount", cleanCycles)
                .put("cleanCycles", cleanCycleEvidence)
                .put("forcedRecovery", forcedRecoveryEvidence)
                .put("supportBundleEntries", support.entries)
                .put("supportManifestSha256", support.manifestSha256)
                .put("components", running.getJSONObject("components"))
            val output = File(context.getExternalFilesDir(null), "evidence/O12_INTEGRATED_RUNTIME.json")
            output.parentFile!!.mkdirs()
            output.writeText(evidence.toString(2))
        } finally {
            runCatching {
                if (JSONObject(supervisor.api.status()).getString("phase") != RuntimePhase.STOPPED.name) {
                    supervisor.api.stop(true)
                }
            }
            display.close()
            world?.close()
            supervisor.close()
            activity.close()
        }
    }

    /**
     * Recreates the complete UI display and Wine process repeatedly. This is a
     * focused regression for the EGL share-context race: every launch must
     * publish GLRenderer's context before Wine creates a GLX context, and the
     * resulting login surface must be readable as non-black.
     */
    @Test fun rendererShareContextSurvivesRepeatedClientLaunches() {
        val arguments = InstrumentationRegistry.getArguments()
        val cycles = arguments.getString("o12RendererLaunchCycles")?.toIntOrNull() ?: 3
        require(cycles in 1..20) { "o12RendererLaunchCycles must be in 1..20" }
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val supervisor = bind(
            Intent(context, RealmService::class.java),
            IRuntimeSupervisorControl.Stub::asInterface,
        )
        val display = bind(
            Intent(context, ClientDisplayService::class.java),
            IClientDisplayControl.Stub::asInterface,
        )
        val evidence = JSONArray()
        try {
            normalizeStopped(supervisor.api)
            repeat(cycles) { index ->
                assertAccepted(supervisor.api.start(AndroidRuntimeBackend.INTEGRATED_PROFILE, true))
                val running = waitPhase(supervisor.api, RuntimePhase.RUNNING, 360_000)
                assertOwnedReady(running, "database", "realm", "world", "client")
                val status = JSONObject(display.api.status())
                assertTrue("renderer was not ready before client launch: $status",
                    status.getBoolean("rendererReady"))
                assertTrue("client window was not mapped: $status", status.getBoolean("windowVisible"))
                val (frame, nonBlack) = waitForVisibleDisplay(120_000)
                evidence.put(JSONObject()
                    .put("cycle", index + 1)
                    .put("rendererReady", true)
                    .put("width", frame.width)
                    .put("height", frame.height)
                    .put("nonBlackPixels", nonBlack))
                assertAccepted(supervisor.api.stop(false))
                assertTrue("renderer cycle ${index + 1} did not stop cleanly",
                    waitPhase(supervisor.api, RuntimePhase.STOPPED, 180_000).getBoolean("clean"))
            }
            val target = File(context.filesDir, "evidence/O12_RENDERER_RESTARTS.json")
            target.parentFile!!.mkdirs()
            target.writeText(JSONObject()
                .put("schema", 1)
                .put("ok", true)
                .put("cycles", cycles)
                .put("launches", evidence)
                .toString(2))
        } finally {
            runCatching {
                if (JSONObject(supervisor.api.status()).getString("phase") != RuntimePhase.STOPPED.name) {
                    supervisor.api.stop(true)
                }
            }
            display.close()
            supervisor.close()
            activity.close()
        }
    }

    private fun captureDisplay(): Bitmap {
        val host = requireNotNull(IntegratedClientDisplay.host.value) { "integrated display host unavailable" }
        val width = host.view.width
        val height = host.view.height
        assertTrue("integrated display has no size", width > 0 && height > 0)
        val result = AtomicReference<Bitmap>()
        val done = CountDownLatch(1)
        host.view.queueEvent {
            try {
                // EGL may discard the default framebuffer after swap. Render
                // synchronously on the owning GLES thread before glReadPixels
                // so capture does not depend on emulator buffer preservation.
                host.view.renderer.onDrawFrame(null)
                val pixels = host.view.renderer.getPixelsARGB(0, 0, width, height, true)
                result.set(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888))
            } finally {
                done.countDown()
            }
        }
        host.view.requestRender()
        assertTrue("integrated renderer capture timed out", done.await(10, TimeUnit.SECONDS))
        return requireNotNull(result.get()) { "integrated renderer capture unavailable" }
    }

    private fun captureEvidenceFrame(name: String): Bitmap = captureDisplay().also { frame ->
        val target = File(context.filesDir, "evidence/$name")
        target.parentFile!!.mkdirs()
        target.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitForVisibleDisplay(timeoutMs: Long): Pair<Bitmap, Int> {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var lastFrame: Bitmap? = null
        var lastNonBlack = 0
        do {
            lastFrame = captureDisplay()
            lastNonBlack = lastFrame.getPixelsCopy().count { (it and 0x00ffffff) != 0 }
            if (lastNonBlack > lastFrame.width * lastFrame.height / 100) {
                return lastFrame to lastNonBlack
            }
            Thread.sleep(1_000)
        } while (android.os.SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("integrated renderer remained black ($lastNonBlack non-black pixels)")
    }

    private fun Bitmap.getPixelsCopy(): IntArray = IntArray(width * height).also {
        getPixels(it, 0, width, 0, 0, width, height)
    }

    private fun tapDisplay(x: Float, y: Float) {
        val host = requireNotNull(IntegratedClientDisplay.host.value)
        instrumentation.runOnMainSync {
            val now = android.os.SystemClock.uptimeMillis()
            host.dispatchPointer(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
            host.dispatchPointer(MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, x, y, 0))
        }
        // X events are delivered asynchronously to Wine. Wait for the Win32
        // control to consume ButtonRelease and move keyboard focus before the
        // first committed character follows it.
        Thread.sleep(300)
    }

    private fun replaceDisplayText(value: String) {
        val host = requireNotNull(IntegratedClientDisplay.host.value)
        // The managed profile survives package upgrades and clean server
        // cycles. Clear any field value retained by the real client before
        // typing; the old helper's name promised replacement but it only
        // appended, which made a hidden remembered password intermittent.
        instrumentation.runOnMainSync {
            val now = android.os.SystemClock.uptimeMillis()
            host.dispatchKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END, 0))
            host.dispatchKey(KeyEvent(now, now + 20, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_END, 0))
        }
        Thread.sleep(100)
        repeat(32) {
            instrumentation.runOnMainSync {
                val now = android.os.SystemClock.uptimeMillis()
                host.dispatchKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0))
                host.dispatchKey(KeyEvent(now, now + 20, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0))
            }
            Thread.sleep(20)
        }
        value.forEach { character ->
            val keyCode = when (character) {
                in 'a'..'z' -> KeyEvent.KEYCODE_A + (character - 'a')
                in '0'..'9' -> KeyEvent.KEYCODE_0 + (character - '0')
                else -> error("O12 credential uses unsupported test character: $character")
            }
            instrumentation.runOnMainSync {
                val now = android.os.SystemClock.uptimeMillis()
                host.dispatchKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
                host.dispatchKey(KeyEvent(now, now + 20, KeyEvent.ACTION_UP, keyCode, 0))
            }
            Thread.sleep(75)
        }
    }

    private fun waitForActiveSession(api: IWorldControl, timeoutMs: Long): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var latest = JSONObject(api.status())
        while (System.nanoTime() < deadline) {
            if (latest.optLong("activeSessions") > 0) return latest
            Thread.sleep(250)
            latest = JSONObject(api.status())
        }
        return latest
    }

    private fun waitForOnlinePlayer(api: IWorldControl, timeoutMs: Long): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var latest = JSONObject(api.status())
        while (System.nanoTime() < deadline) {
            if (latest.optLong("onlinePlayers") > 0) return latest
            Thread.sleep(250)
            latest = JSONObject(api.status())
        }
        return latest
    }

    private fun waitForComplexDisplay(timeoutMs: Long, minimumDistinctColors: Int): Triple<Bitmap, Int, Int> {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var lastFrame: Bitmap? = null
        var lastNonBlack = 0
        var lastDistinct = 0
        do {
            lastFrame = captureDisplay()
            val pixels = lastFrame.getPixelsCopy()
            lastNonBlack = pixels.count { (it and 0x00ffffff) != 0 }
            lastDistinct = pixels.asSequence().map { it and 0x00ffffff }
                .distinct().take(minimumDistinctColors).count()
            if (lastNonBlack > pixels.size / 100 && lastDistinct >= minimumDistinctColors) {
                return Triple(lastFrame, lastNonBlack, lastDistinct)
            }
            Thread.sleep(1_000)
        } while (android.os.SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("world renderer did not become complex " +
            "(nonBlack=$lastNonBlack distinctColors=$lastDistinct)")
    }

    private fun waitForMateriallyChangedDisplay(
        baseline: Bitmap,
        timeoutMs: Long,
        minimumDistinctColors: Int,
    ): Triple<Bitmap, Int, Int> {
        val baselinePixels = baseline.getPixelsCopy()
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var lastFrame = baseline
        var lastNonBlack = 0
        var lastDistinct = 0
        var lastChanged = 0
        do {
            lastFrame = captureDisplay()
            val pixels = lastFrame.getPixelsCopy()
            lastNonBlack = pixels.count { (it and 0x00ffffff) != 0 }
            lastDistinct = pixels.asSequence().map { it and 0x00ffffff }
                .distinct().take(minimumDistinctColors).count()
            lastChanged = pixels.indices.count { index ->
                val before = baselinePixels[index]
                val after = pixels[index]
                kotlin.math.abs((before and 0xff) - (after and 0xff)) +
                    kotlin.math.abs(((before shr 8) and 0xff) - ((after shr 8) and 0xff)) +
                    kotlin.math.abs(((before shr 16) and 0xff) - ((after shr 16) and 0xff)) > 48
            }
            if (lastNonBlack > pixels.size / 100 &&
                lastDistinct >= minimumDistinctColors &&
                lastChanged >= pixels.size / 10) {
                return Triple(lastFrame, lastNonBlack, lastDistinct)
            }
            Thread.sleep(1_000)
        } while (android.os.SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("world renderer remained on its first complex frame " +
            "(nonBlack=$lastNonBlack distinctColors=$lastDistinct changed=$lastChanged)")
    }

    private fun runZeroBotSoak(api: IWorldControl, requestedSeconds: Int): JSONArray {
        val samples = JSONArray()
        if (requestedSeconds == 0) return samples
        val started = android.os.SystemClock.elapsedRealtime()
        val deadline = started + TimeUnit.SECONDS.toMillis(requestedSeconds.toLong())
        var nextSample = started
        var previousTick = -1L
        while (true) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now >= nextSample || now >= deadline) {
                val status = JSONObject(api.status())
                assertTrue("player disconnected during zero-bot soak: $status",
                    status.getLong("onlinePlayers") > 0 && status.getLong("activeSessions") > 0)
                assertTrue("the O13-capable runtime was not packaged", status.getBoolean("compiledPlayerbots"))
                assertFalse("playerbots were enabled during the zero-bot soak", status.getBoolean("playerbotsEnabled"))
                assertFalse("AHBot was enabled during the zero-bot soak", status.getBoolean("auctionHouseBot"))
                val tick = status.getLong("tickCount")
                if (previousTick >= 0) assertTrue("world ticks stopped during soak: $status", tick > previousTick)
                previousTick = tick
                // Vanilla marks an unattended character AFK and CMaNGOS then
                // applies its normal 15-minute AFK logout policy. This is an
                // active-play soak, so send a harmless in-place jump through
                // the production Android -> X11 -> Wine input bridge once per
                // sample. Do not disable or weaken the server's AFK policy.
                pulsePlayerActivity()
                // Loading fades and a discarded default framebuffer can make
                // an individual readback black even while presentation is
                // healthy. Require recovery to a complex frame within a short
                // bound instead of treating one transient frame as sustained
                // renderer degradation.
                val (_, nonBlack, distinct) = waitForComplexDisplay(10_000, 128)
                samples.put(JSONObject()
                    .put("elapsedSeconds", (now - started) / 1_000)
                    .put("tickCount", tick)
                    .put("lastTickMs", status.getLong("lastTickMs"))
                    .put("maxTickMs", status.getLong("maxTickMs"))
                    .put("onlinePlayers", status.getLong("onlinePlayers"))
                    .put("activeSessions", status.getLong("activeSessions"))
                    .put("frameNonBlackPixels", nonBlack)
                    .put("frameDistinctColors", distinct)
                    .put("activityPulse", "jump"))
                if (now >= deadline) break
                nextSample = minOf(nextSample + 60_000, deadline)
            }
            Thread.sleep(minOf(1_000, maxOf(1, minOf(nextSample, deadline) - android.os.SystemClock.elapsedRealtime())))
        }
        return samples
    }

    private fun pulsePlayerActivity() {
        val host = requireNotNull(IntegratedClientDisplay.host.value)
        instrumentation.runOnMainSync {
            val now = android.os.SystemClock.uptimeMillis()
            host.dispatchKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0))
        }
        Thread.sleep(100)
        instrumentation.runOnMainSync {
            val now = android.os.SystemClock.uptimeMillis()
            host.dispatchKey(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, 0))
        }
    }

    private fun uniqueCharacterName(seed: Long): String {
        var value = seed
        val vowels = "aeiou"
        val consonants = "bcdfghjklmnpqrsvwxyz"
        return buildString {
            append("pocket")
            repeat(5) { index ->
                // Alternating alphabets make adjacent duplicates impossible,
                // including at the fixed "pocket" boundary. Vanilla rejects
                // names containing three equal consecutive letters, so a
                // random base-26 suffix can make this end-to-end test flaky.
                val alphabet = if (index % 2 == 0) vowels else consonants
                append(alphabet[(value % alphabet.length).toInt()])
                value /= alphabet.length
            }
        }
    }

    private fun normalizeStopped(api: IRuntimeSupervisorControl) {
        val status = JSONObject(api.status())
        if (status.getString("phase") == RuntimePhase.STOPPED.name && status.getBoolean("clean")) return
        if (status.getString("phase") !in setOf(RuntimePhase.ERROR.name, RuntimePhase.STOPPED.name,
                RuntimePhase.UNCONFIGURED.name)) {
            assertAccepted(api.stop(true))
            waitPhase(api, RuntimePhase.STOPPED, 120_000)
        }
        assertAccepted(api.start(AndroidRuntimeBackend.INTEGRATED_PROFILE, false))
        waitPhase(api, RuntimePhase.WORLD_READY, 300_000, tolerateInitialError = true)
        assertAccepted(api.stop(false))
        assertTrue(waitPhase(api, RuntimePhase.STOPPED, 180_000).getBoolean("clean"))
    }

    private fun assertOwnedReady(status: JSONObject, vararg names: String) {
        val tokens = mutableSetOf<String>()
        names.forEach { name ->
            val value = status.getJSONObject("components").getJSONObject(name)
            assertEquals("READY", value.getString("state"))
            val token = value.getString("instanceToken")
            assertTrue(token.matches(Regex("[0-9a-f]{64}")))
            tokens += token
        }
        assertEquals(names.size, tokens.size)
    }

    private fun waitPhase(
        api: IRuntimeSupervisorControl,
        phase: RuntimePhase,
        timeoutMs: Long,
        tolerateInitialError: Boolean = false,
    ): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var latest = JSONObject(api.status())
        while (System.nanoTime() < deadline) {
            if (latest.getString("phase") == phase.name) return latest
            if (!tolerateInitialError && latest.getString("phase") == RuntimePhase.ERROR.name &&
                phase != RuntimePhase.ERROR) throw AssertionError("runtime failed before $phase: $latest")
            Thread.sleep(250)
            latest = JSONObject(api.status())
        }
        throw AssertionError("timed out waiting for $phase: $latest")
    }

    private fun waitMaintenance(api: IRuntimeSupervisorControl, kind: String, timeoutMs: Long): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var latest = JSONObject(api.backupStatus())
        while (System.nanoTime() < deadline) {
            if (latest.optString("kind") == kind && latest.optString("phase") in setOf("COMPLETE", "FAILED")) {
                return latest
            }
            Thread.sleep(250)
            latest = JSONObject(api.backupStatus())
        }
        throw AssertionError("timed out waiting for $kind: $latest")
    }

    private fun assertAccepted(raw: String) {
        val value = JSONObject(raw)
        assertTrue("operation rejected: $value", value.optBoolean("ok") && value.optBoolean("accepted"))
    }

    private fun <T> bind(intent: Intent, convert: (IBinder) -> T): Bound<T> {
        val latch = CountDownLatch(1)
        var remote: T? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                remote = convert(service)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return Bound(context, connection, checkNotNull(remote))
    }

    private data class Bound<T>(val context: Context, val connection: ServiceConnection, val api: T) {
        fun close() = runCatching { context.unbindService(connection) }.let { Unit }
    }
}
