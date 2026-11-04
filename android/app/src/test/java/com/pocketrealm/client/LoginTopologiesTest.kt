package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue

/**
 * P11 (android lane): the per-build login topologies and the
 * [LoginTopologies] resolver. The 5875 contract stays the verified pin; the
 * 8606/12340 twins are the build-keyed seams for the staged clients (same
 * Winlator transport family), and an unknown build/flavor resolves to null —
 * refusing honestly instead of guessing a topology.
 */
class LoginTopologiesTest {

    @Test
    fun resolverKeysEveryPinnedBuildAndRefusesUnknownBuilds() {
        assertEquals(Build5875LoginTopology, LoginTopologies.forBuild(5875))
        assertEquals(Build8606LoginTopology, LoginTopologies.forBuild(8606))
        assertEquals(Build12340LoginTopology, LoginTopologies.forBuild(12340))
        assertNull(LoginTopologies.forBuild(9999))
    }

    @Test
    fun resolverKeysEveryFlavorIdAndRefusesUnknownFlavors() {
        assertEquals(Build5875LoginTopology, LoginTopologies.forFlavorId("vanilla"))
        assertEquals(Build8606LoginTopology, LoginTopologies.forFlavorId("tbc"))
        assertEquals(Build12340LoginTopology, LoginTopologies.forFlavorId("wotlk"))
        assertNull(LoginTopologies.forFlavorId("nosuchflavor"))
    }

    @Test
    fun resolverAgreesWithTheClientFlavorRegistry() {
        for (flavor in ClientFlavors.ALL) {
            assertEquals(
                "topology for ${flavor.flavorId} must key off the shared registry",
                LoginTopologies.forBuild(flavor.build),
                LoginTopologies.forFlavorId(flavor.flavorId),
            )
        }
    }

    @Test
    fun tbcTwinsAreDistinctSeamsNotAliasesOfTheVerifiedPin() {
        assertNotEquals(Build5875LoginTopology, Build8606LoginTopology)
        assertNotEquals(Build5875LoginTopology, Build12340LoginTopology)
        assertNotEquals(Build8606LoginTopology, Build12340LoginTopology)
    }

    @Test
    fun tbcTopologyHoldsTheVerifiedArmLoginSurfaceContract() {
        val login = AutoLoginWindow("", "", 1920, 1080, 0, renderable = true, topLevel = true)
        assertTrue(Build8606LoginTopology.matches(listOf(login), AutoLoginWindowLane.ARM_TRANSLATED))
        // A stray non-tiny top-level (modal/decoy) cancels credential injection.
        assertFalse(Build8606LoginTopology.matches(
            listOf(login, login.copy(name = "Hardware changed")),
            AutoLoginWindowLane.ARM_TRANSLATED,
        ))
        assertFalse(Build8606LoginTopology.matches(
            listOf(login.copy(width = 800, height = 600)),
            AutoLoginWindowLane.ARM_TRANSLATED,
        ))
    }

    @Test
    fun wotlkTopologyHoldsTheVerifiedX86ExactDesktopPairContract() {
        fun pair(width: Int, height: Int) = listOf(
            AutoLoginWindow("", "wow.exe", width, height, 42, true, true),
            AutoLoginWindow("", "", width, height, 0, true, true),
        )
        assertTrue(Build12340LoginTopology.matches(
            pair(1280, 720), AutoLoginWindowLane.X86_DIRECT, 1280, 720,
        ))
        assertFalse(Build12340LoginTopology.matches(
            pair(1280, 720), AutoLoginWindowLane.X86_DIRECT, 1920, 1080,
        ))
    }

    @Test
    fun controllerHonorsTheInjectedBuildTopologyWhenSettling() {
        val scheduler = FakeScheduler()
        val bridge = FakeBridge()
        val controller = SinglePlayerAutoLoginController(
            username = "PRTESTUSER12",
            password = "TESTPASSWORD1234",
            generation = 7,
            lane = AutoLoginWindowLane.ARM_TRANSLATED,
            loginTopology = Build12340LoginTopology,
            bridge = bridge,
            scheduler = scheduler,
        )
        controller.onTopologyChanged()
        scheduler.runThrough(8_000L)
        assertEquals(1, bridge.queueCount)
        assertEquals(SinglePlayerAutoLoginController.State.INJECTING, controller.state)
    }

    private class FakeBridge : AutoLoginBridge {
        var queueCount = 0
        var windows: List<AutoLoginWindow> = listOf(
            AutoLoginWindow("", "", 1920, 1080, 0, renderable = true, topLevel = true),
        )
        override fun isActive(generation: Long): Boolean = generation == 7L
        override fun isRendererReady(): Boolean = true
        override fun isInputNeutral(generation: Long): Boolean = true
        override fun mappedWindows(): List<AutoLoginWindow> = windows
        override fun queueCredentials(username: String, password: String, generation: Long): Boolean {
            queueCount++
            return true
        }
        override fun isCredentialQueueIdle(): Boolean = false
        override fun cancelCredentialQueue(generation: Long) {}
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
        fun runThrough(targetMs: Long) {
            while ((tasks.peek()?.whenMs ?: Long.MAX_VALUE) <= targetMs) runNext()
        }
    }
}
