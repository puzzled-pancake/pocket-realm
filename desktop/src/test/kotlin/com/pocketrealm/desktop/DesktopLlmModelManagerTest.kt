package com.pocketrealm.desktop

import com.pocketrealm.llm.LlmModelRegistry
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The embedded-model lane manager's contract (DESK-48/80/103): the staged
 * model gate before any launch, the honest no-binary refusal, the
 * endpoint/health-probe shapes, and idempotent stop. The sidecar PROCESS
 * lane itself (a real llama-server.exe) is not exercised here — the binary
 * is a packaging dependency and is absent from the dev checkout; the
 * health probe IS exercised against a live loopback fixture server.
 */
class DesktopLlmModelManagerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    private fun manager(): DesktopLlmModelManager =
        DesktopLlmModelManager(DesktopStorageRoots(folder.newFolder("roots")))

    @Test
    fun stagedModelIsNullWithoutAStagedFile() {
        val model = manager()
        assertNull(model.stagedModel(LlmModelRegistry.BASE_E2B.id))
        assertFalse(model.state(LlmModelRegistry.BASE_E2B.id).running)
    }

    @Test
    fun localOnlyModelDownloadRefuses() {
        val model = manager()
        try {
            model.downloadModel(LlmModelRegistry.TUNED_E2B.id)
            fail("the tuned checkpoint has no download source; the download must refuse")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "must carry the local-only copy: ${expected.message}",
                (expected.message ?: "").contains("local-only"),
            )
        }
    }

    @Test
    fun ensureRunningRefusesTheUnstagedModelBeforeTheBinary() {
        val model = manager()
        // The staged-model gate precedes the binary gate: an unstaged model
        // refuses with the model copy even where a binary might exist.
        assertNull(model.ensureRunning(LlmModelRegistry.BASE_E2B.id))
        assertTrue(
            "the state must carry the honest model copy",
            model.state(LlmModelRegistry.BASE_E2B.id).detail.contains("not staged"),
        )
    }

    @Test
    fun healthProbeSeesALiveLoopbackEndpointAndAMissingOne() {
        val model = manager()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/health") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        http.start()
        server = http
        assertTrue("a live /health must probe ok", model.healthOk(http.address.port))
        // A port with no listener must probe false (never throw).
        val dead = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val deadPort = dead.localPort
        dead.close()
        assertFalse("a dead port must probe false", model.healthOk(deadPort))
    }

    @Test
    fun endpointFormCarriesTheOpenAIChatCompletionsPath() {
        val model = manager()
        assertEquals(
            "http://127.0.0.1:8081/v1/chat/completions",
            model.endpointFor(8081),
        )
    }

    @Test
    fun stopIsIdempotentWithoutASidecar() {
        val model = manager()
        model.stop()
        assertFalse(model.state(null).running)
        assertNull(model.activeEndpoint())
    }

    @Test
    fun sidecarBinaryResolutionIsHonestAboutAbsence() {
        // The dev checkout does not bundle llama-server.exe; the resolver
        // must return null (the ERR_NO_SIDECAR_BINARY posture), never a
        // guessed path. (If a future checkout stages one, this test is
        // skipped rather than failed — the resolver found real work.)
        val resolved = manager().resolveSidecarBinary()
        if (resolved != null) {
            assertTrue(resolved.isFile)
            assertEquals(DesktopLlmModelManager.SIDECAR_BINARY_NAME, resolved.name)
        }
    }

    @Test
    fun registryDefaultSelectionNeverCrashes() {
        // byId falls back to the default for unknown/absent ids — the
        // settings llmModelId="" lane.
        assertEquals(
            LlmModelRegistry.DEFAULT_MODEL_ID,
            LlmModelRegistry.byId(null).id,
        )
        assertEquals(
            LlmModelRegistry.DEFAULT_MODEL_ID,
            LlmModelRegistry.byId("not-a-model").id,
        )
    }
}
