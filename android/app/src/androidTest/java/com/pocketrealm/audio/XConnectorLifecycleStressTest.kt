package com.pocketrealm.audio

import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.xconnector.ConnectedClient
import com.winlator.xconnector.ConnectionHandler
import com.winlator.xconnector.RequestHandler
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xconnector.XConnectorEpoll
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises native connector ownership through the same JNI surface used by ALSA. */
@RunWith(AndroidJUnit4::class)
class XConnectorLifecycleStressTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun repeatedPeerCloseReapsEveryClientAndThread() {
        val fixture = fixture()
        val baselineTasks = taskCount()
        val counters = Counters()
        val connector = connector(fixture, counters)

        try {
            repeat(200) { iteration ->
                connect(fixture.socket).use { socket ->
                    socket.outputStream.write(iteration)
                    socket.outputStream.flush()
                }
                await("client $iteration reaped") {
                    counters.closed.get() == iteration + 1 && connector.clients.isEmpty()
                }
            }
            assertEquals(200, counters.opened.get())
            assertEquals(200, counters.closed.get())
        } finally {
            connector.destroy()
        }

        await("connector and client threads returned to baseline") {
            taskCount() <= baselineTasks + TASK_COUNT_TOLERANCE
        }
        assertTrue(connector.clients.isEmpty())
        assertConnectFails(fixture.socket)
        fixture.cleanup()
    }

    @Test
    fun destroyDrainsLiveClientsBeforeListenerRefusesNewConnections() {
        val fixture = fixture()
        val baselineTasks = taskCount()
        val counters = Counters()
        val connector = connector(fixture, counters)
        val sockets = ArrayList<LocalSocket>()

        try {
            repeat(32) { sockets += connect(fixture.socket) }
            await("all live clients accepted") { counters.opened.get() == sockets.size }

            connector.destroy()

            assertEquals(32, counters.closed.get())
            assertTrue(connector.clients.isEmpty())
            assertConnectFails(fixture.socket)
        } finally {
            sockets.forEach { runCatching { it.close() } }
            connector.destroy()
        }

        await("destroy joined listener and client threads") {
            taskCount() <= baselineTasks + TASK_COUNT_TOLERANCE
        }
        fixture.cleanup()
    }

    @Test
    fun singleThreadedTransportReapsPeerCloseWithoutStoppingListener() {
        val fixture = fixture()
        val baselineTasks = taskCount()
        val counters = Counters()
        val connector = connector(fixture, counters, multithreaded = false)

        try {
            repeat(100) { iteration ->
                connect(fixture.socket).use { socket ->
                    socket.outputStream.write(iteration)
                    socket.outputStream.flush()
                }
                await("single-threaded client $iteration reaped") {
                    counters.closed.get() == iteration + 1 && connector.clients.isEmpty()
                }
            }
            assertEquals(100, counters.opened.get())
            assertEquals(100, counters.closed.get())
        } finally {
            connector.destroy()
        }

        await("single listener thread returned to baseline") {
            taskCount() <= baselineTasks + TASK_COUNT_TOLERANCE
        }
        assertConnectFails(fixture.socket)
        fixture.cleanup()
    }

    private fun connector(
        fixture: Fixture,
        counters: Counters,
        multithreaded: Boolean = true,
    ): XConnectorEpoll {
        val handler = object : ConnectionHandler {
            override fun handleNewConnection(client: ConnectedClient) {
                counters.opened.incrementAndGet()
            }

            override fun handleConnectionShutdown(client: ConnectedClient) {
                counters.closed.incrementAndGet()
            }
        }
        val requests = RequestHandler { client ->
            val input = client.inputStream
            while (input != null && input.available() > 0) input.readByte()
            false
        }
        return XConnectorEpoll(
            UnixSocketConfig.create(fixture.root.absolutePath, ".sound/AS0"),
            handler,
            requests,
        ).apply {
            setMultithreadedClients(multithreaded)
            start()
        }
    }

    private fun fixture(): Fixture {
        val root = File(context.cacheDir, "xconnector-lifecycle-${UUID.randomUUID()}")
        File(root, ".sound").mkdirs()
        return Fixture(root, File(root, ".sound/AS0"))
    }

    private fun connect(socketFile: File): LocalSocket {
        var lastFailure: Throwable? = null
        repeat(50) {
            val socket = LocalSocket()
            try {
                socket.connect(
                    LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM),
                )
                return socket
            } catch (error: Throwable) {
                lastFailure = error
                socket.close()
                Thread.sleep(10)
            }
        }
        throw AssertionError("connector did not accept a client", lastFailure)
    }

    private fun assertConnectFails(socketFile: File) {
        val socket = LocalSocket()
        var connected = false
        try {
            socket.connect(
                LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM),
            )
            connected = true
        } catch (_: Throwable) {
            // The filesystem node may remain, but the listener must be closed.
        } finally {
            socket.close()
        }
        assertFalse("destroyed listener accepted a client", connected)
    }

    private fun taskCount(): Int = File("/proc/self/task").list()?.size ?: 0

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(label, condition())
    }

    private data class Counters(
        val opened: AtomicInteger = AtomicInteger(),
        val closed: AtomicInteger = AtomicInteger(),
    )

    private data class Fixture(val root: File, val socket: File) {
        fun cleanup() {
            socket.delete()
            File(root, ".sound").delete()
            root.delete()
        }
    }

    companion object {
        private const val TASK_COUNT_TOLERANCE = 1
    }
}
