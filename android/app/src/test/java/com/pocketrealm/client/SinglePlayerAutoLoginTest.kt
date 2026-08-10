package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue

class SinglePlayerAutoLoginTest {
    @Test
    fun armMatcherAcceptsOnlyTheQualifiedSingleLoginSurface() {
        val login = AutoLoginWindow("", "", 1920, 1080, 0, renderable = true, topLevel = true)
        assertTrue(Build5875LoginTopology.matches(listOf(login), AutoLoginWindowLane.ARM_TRANSLATED))
        assertTrue(Build5875LoginTopology.matches(
            listOf(login.copy(name = "World of Warcraft", className = "wow.exe", processId = 42)),
            AutoLoginWindowLane.ARM_TRANSLATED,
        ))
        assertTrue(Build5875LoginTopology.matches(
            listOf(login, login.copy(width = 1, height = 1)),
            AutoLoginWindowLane.ARM_TRANSLATED,
        ))
        assertTrue(Build5875LoginTopology.matches(
            listOf(login, login.copy(topLevel = false)),
            AutoLoginWindowLane.ARM_TRANSLATED,
        ))
        assertFalse(Build5875LoginTopology.matches(
            listOf(login.copy(width = 800, height = 600)),
            AutoLoginWindowLane.ARM_TRANSLATED,
        ))
        assertFalse(Build5875LoginTopology.matches(
            listOf(login, login.copy(width = 800, height = 600)),
            AutoLoginWindowLane.ARM_TRANSLATED,
        ))
        assertFalse(Build5875LoginTopology.matches(
            listOf(login, login.copy(width = 265, height = 63, name = "Hardware changed")),
            AutoLoginWindowLane.ARM_TRANSLATED,
        ))
        val balanced = login.copy(width = 1280, height = 720)
        assertTrue(Build5875LoginTopology.matches(
            listOf(balanced), AutoLoginWindowLane.ARM_TRANSLATED, 1280, 720,
        ))
        assertFalse(Build5875LoginTopology.matches(
            listOf(login), AutoLoginWindowLane.ARM_TRANSLATED, 1280, 720,
        ))
    }

    @Test
    fun armVirtualDesktopIsNotMistakenForTheGameWindow() {
        val desktop = AutoLoginWindow(
            name = "Wine desktop",
            className = "explorer.exe",
            width = 1920,
            height = 1080,
            processId = 10,
            renderable = true,
            topLevel = true,
            desktop = true,
        )
        val game = AutoLoginWindow(
            name = "World of Warcraft",
            className = "wow.exe",
            width = 1920,
            height = 1080,
            processId = 11,
            renderable = true,
            topLevel = false,
        )

        assertFalse(Build5875LoginTopology.matches(
            listOf(desktop), AutoLoginWindowLane.ARM_TRANSLATED,
        ))
        assertTrue(Build5875LoginTopology.matches(
            listOf(desktop, game), AutoLoginWindowLane.ARM_TRANSLATED,
        ))
        assertFalse(Build5875LoginTopology.matches(
            listOf(desktop, game, game.copy(width = 800, height = 600, topLevel = true)),
            AutoLoginWindowLane.ARM_TRANSLATED,
        ))
    }

    @Test
    fun controllerWaitsForStableNeutralSurfaceAndQueuesSecretsExactlyOnce() {
        val scheduler = FakeScheduler()
        val bridge = FakeBridge()
        val controller = SinglePlayerAutoLoginController(
            username = "PRTESTUSER12",
            password = "TESTPASSWORD1234",
            generation = 7,
            lane = AutoLoginWindowLane.ARM_TRANSLATED,
            bridge = bridge,
            scheduler = scheduler,
        )

        bridge.neutral = false
        controller.onTopologyChanged()
        scheduler.runNext()
        assertEquals(0, bridge.queueCount)

        bridge.neutral = true
        scheduler.runThrough(8_000L)
        assertEquals(0, bridge.queueCount)

        scheduler.runNext()
        assertEquals(1, bridge.queueCount)
        assertEquals(SinglePlayerAutoLoginController.State.INJECTING, controller.state)

        bridge.idle = true
        scheduler.runNext()
        assertEquals(SinglePlayerAutoLoginController.State.COMPLETE, controller.state)
        assertEquals(1, bridge.cancelCount)

        controller.onTopologyChanged()
        scheduler.runAll()
        assertEquals(1, bridge.queueCount)
    }

    @Test
    fun topologyLossCancelsAnInFlightCredentialQueue() {
        val scheduler = FakeScheduler()
        val bridge = FakeBridge()
        val controller = SinglePlayerAutoLoginController(
            username = "PRTESTUSER12",
            password = "TESTPASSWORD1234",
            generation = 7,
            lane = AutoLoginWindowLane.ARM_TRANSLATED,
            bridge = bridge,
            scheduler = scheduler,
        )
        controller.onTopologyChanged()
        scheduler.runThrough(8_000L)
        assertEquals(1, bridge.queueCount)

        bridge.windows = emptyList()
        controller.onTopologyChanged()
        assertEquals(SinglePlayerAutoLoginController.State.CANCELLED, controller.state)
        assertEquals(1, bridge.cancelCount)
    }

    @Test
    fun topologyLossRestartsTheEightSecondLoginUiSettleGate() {
        val scheduler = FakeScheduler()
        val bridge = FakeBridge()
        val controller = SinglePlayerAutoLoginController(
            username = "PRTESTUSER12",
            password = "TESTPASSWORD1234",
            generation = 7,
            lane = AutoLoginWindowLane.ARM_TRANSLATED,
            bridge = bridge,
            scheduler = scheduler,
        )

        controller.onTopologyChanged()
        scheduler.runThrough(4_000L)
        assertEquals(0, bridge.queueCount)

        bridge.windows = emptyList()
        controller.onTopologyChanged()
        scheduler.runNext()
        bridge.windows = listOf(
            AutoLoginWindow("", "", 1920, 1080, 0, renderable = true, topLevel = true),
        )
        controller.onTopologyChanged()

        scheduler.runThrough(12_000L)
        assertEquals(0, bridge.queueCount)
        scheduler.runThrough(12_500L)
        assertEquals(1, bridge.queueCount)
    }

    @Test
    fun slowArmStartupCanQualifyAfterTheOldFortyFiveSecondDeadline() {
        val scheduler = FakeScheduler()
        val bridge = FakeBridge().apply { windows = emptyList() }
        val controller = SinglePlayerAutoLoginController(
            username = "PRTESTUSER12",
            password = "TESTPASSWORD1234",
            generation = 7,
            lane = AutoLoginWindowLane.ARM_TRANSLATED,
            bridge = bridge,
            scheduler = scheduler,
        )

        controller.onTopologyChanged()
        scheduler.runThrough(60_000L)
        assertEquals(SinglePlayerAutoLoginController.State.WATCHING, controller.state)
        bridge.windows = listOf(
            AutoLoginWindow("", "", 1920, 1080, 0, renderable = true, topLevel = true),
        )
        controller.onTopologyChanged()
        scheduler.runThrough(68_250L)

        assertEquals(1, bridge.queueCount)
        assertEquals(SinglePlayerAutoLoginController.State.INJECTING, controller.state)
    }

    @Test
    fun irrelevantChildWindowNotificationsDoNotRestartTheSettleGate() {
        val scheduler = FakeScheduler()
        val bridge = FakeBridge()
        val controller = SinglePlayerAutoLoginController(
            username = "PRTESTUSER12",
            password = "TESTPASSWORD1234",
            generation = 7,
            lane = AutoLoginWindowLane.ARM_TRANSLATED,
            bridge = bridge,
            scheduler = scheduler,
        )

        controller.onTopologyChanged()
        scheduler.runThrough(2_000L)
        bridge.windows = bridge.windows +
            AutoLoginWindow("", "", 800, 600, 0, renderable = true, topLevel = false)
        controller.onTopologyChanged()
        scheduler.runThrough(4_000L)
        controller.onTopologyChanged()
        scheduler.runThrough(6_000L)
        controller.onTopologyChanged()
        scheduler.runThrough(8_250L)

        assertEquals(1, bridge.queueCount)
        assertEquals(SinglePlayerAutoLoginController.State.INJECTING, controller.state)
    }

    @Test
    fun transientModalSnapshotRestartsTheFullSettleGate() {
        val scheduler = FakeScheduler()
        val bridge = FakeBridge()
        val controller = controller(bridge, scheduler)
        controller.start(bridge.windows)
        scheduler.advanceTo(7_900L)

        val modal = AutoLoginWindow(
            "Hardware changed", "", 265, 63, 0, renderable = true, topLevel = true,
        )
        controller.onTopologyChanged(bridge.windows + modal)
        controller.onTopologyChanged(bridge.windows)

        scheduler.runThrough(15_999L)
        assertEquals(0, bridge.queueCount)
        scheduler.runThrough(16_000L)
        assertEquals(1, bridge.queueCount)
    }

    @Test
    fun transientNonTopLevelChildDoesNotRestartTheSettleGate() {
        val scheduler = FakeScheduler()
        val bridge = FakeBridge()
        val controller = controller(bridge, scheduler)
        controller.start(bridge.windows)
        scheduler.advanceTo(7_900L)

        controller.onTopologyChanged(bridge.windows + AutoLoginWindow(
            "", "", 800, 600, 0, renderable = true, topLevel = false,
        ))
        controller.onTopologyChanged(bridge.windows)
        scheduler.runThrough(8_000L)

        assertEquals(1, bridge.queueCount)
    }

    @Test
    fun transientModalDuringInjectionCancelsTheCredentialQueue() {
        val scheduler = FakeScheduler()
        val bridge = FakeBridge()
        val controller = controller(bridge, scheduler)
        controller.start(bridge.windows)
        scheduler.runThrough(8_000L)
        assertEquals(SinglePlayerAutoLoginController.State.INJECTING, controller.state)

        controller.onTopologyChanged(bridge.windows + AutoLoginWindow(
            "Hardware changed", "", 265, 63, 0, renderable = true, topLevel = true,
        ))

        assertEquals(SinglePlayerAutoLoginController.State.CANCELLED, controller.state)
        assertEquals(1, bridge.cancelCount)
    }

    @Test
    fun credentialsExpireWhenWineNeverMapsAWindow() {
        val scheduler = FakeScheduler()
        val bridge = FakeBridge().apply { windows = emptyList() }
        val controller = controller(bridge, scheduler)
        controller.start(emptyList())

        scheduler.runThrough(300_000L)

        assertEquals(SinglePlayerAutoLoginController.State.CANCELLED, controller.state)
        assertEquals(0, bridge.queueCount)
    }

    @Test
    fun credentialValueNeverIncludesItsSecretInStringForm() {
        val credentials = SinglePlayerAutoLoginCredentials("PRIVATEUSER", "PRIVATEPASSWORD")
        assertFalse(credentials.toString().contains("PRIVATEUSER"))
        assertFalse(credentials.toString().contains("PRIVATEPASSWORD"))
        assertTrue(credentials.toString().contains("redacted"))
    }

    private fun controller(
        bridge: FakeBridge,
        scheduler: FakeScheduler,
    ) = SinglePlayerAutoLoginController(
        username = "PRTESTUSER12",
        password = "TESTPASSWORD1234",
        generation = 7,
        lane = AutoLoginWindowLane.ARM_TRANSLATED,
        bridge = bridge,
        scheduler = scheduler,
    )

    private class FakeBridge : AutoLoginBridge {
        var active = true
        var rendererReady = true
        var neutral = true
        var idle = false
        var queueCount = 0
        var cancelCount = 0
        var windows: List<AutoLoginWindow> = listOf(
            AutoLoginWindow("", "", 1920, 1080, 0, renderable = true, topLevel = true),
        )

        override fun isActive(generation: Long): Boolean = active && generation == 7L
        override fun isRendererReady(): Boolean = rendererReady
        override fun isInputNeutral(generation: Long): Boolean = neutral
        override fun mappedWindows(): List<AutoLoginWindow> = windows
        override fun queueCredentials(username: String, password: String, generation: Long): Boolean {
            queueCount++
            return true
        }
        override fun isCredentialQueueIdle(): Boolean = idle
        override fun cancelCredentialQueue(generation: Long) { cancelCount++ }
    }

    private class FakeScheduler : AutoLoginScheduler {
        private data class Task(val whenMs: Long, val order: Long, val action: () -> Unit)
        private val tasks = PriorityQueue<Task>(compareBy<Task> { it.whenMs }.thenBy { it.order })
        private var sequence = 0L
        private var now = 0L

        override fun nowMs(): Long = now

        override fun postDelayed(delayMs: Long, action: () -> Unit) {
            tasks += Task(now + delayMs, sequence++, action)
        }

        fun runNext() {
            val task = tasks.remove()
            now = task.whenMs
            task.action()
        }

        fun runAll() {
            while (tasks.isNotEmpty()) runNext()
        }

        fun runThrough(targetMs: Long) {
            while ((tasks.peek()?.whenMs ?: Long.MAX_VALUE) <= targetMs) runNext()
        }

        fun advanceTo(targetMs: Long) {
            runThrough(targetMs)
            now = targetMs
        }
    }
}
