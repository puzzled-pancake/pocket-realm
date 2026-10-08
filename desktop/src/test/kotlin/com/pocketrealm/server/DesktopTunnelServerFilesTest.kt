package com.pocketrealm.server

import com.pocketrealm.desktop.DesktopStorageRoots
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Design-tunnel §4.4.2/§5: the wildcard-bind lane the tunnel host's
 * start path feeds the conf writers. "0.0.0.0" is the ONE raw conf value
 * accepted beside the strict typed grammar (design-tunnel: keep it out of
 * the typed path — it is never a RealmEndpoint), and it is a non-loopback
 * bind, so the D11 brute-force lockout rides every tunnel start too.
 */
class DesktopTunnelServerFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun files(): Pair<DesktopStorageRoots, ServerRuntimeFiles> {
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        return roots to ServerRuntimeFiles(
            roots,
            llmModelManager = com.pocketrealm.desktop.DesktopLlmModelManager(roots),
        )
    }

    /** The minimal NORMAL generation stage (the golden-pin test's helper). */
    private fun stageActiveGeneration(roots: DesktopStorageRoots) {
        val id = "12345678-1234-1234-1234-123456789abc"
        val dataRoot = File(roots.content, "o11-server")
        val generation = File(dataRoot, "generations/$id").apply { mkdirs() }
        val records = listOf(
            "dbc/A.dbc" to "dbc", "maps/0000000.map" to "map",
            "vmaps/000.vmtree" to "vmtree", "mmaps/000.mmap" to "mmap",
            "mmaps/0000000.mmtile" to "mmtile",
        ).map { (path, contents) ->
            val file = File(generation, path).apply { parentFile?.mkdirs(); writeText(contents) }
            org.json.JSONObject().put("path", path).put("size", file.length()).put("sha256", sha256(file))
        }
        val manifest = org.json.JSONObject().put("schema", 1).put("complete", true)
            .put("mode", "NORMAL").put("clientBuild", 5875).put("cmangosFamily", "classic")
            .put("counts", org.json.JSONObject().put("dbc", 100).put("maps", 100)
                .put("vmapTrees", 1).put("vmapTiles", 0).put("mmapMaps", 1).put("mmapTiles", 1))
            .put("files", org.json.JSONArray(records))
        val manifestFile = File(generation, "data-manifest.json").apply { writeText(manifest.toString()) }
        File(dataRoot, "active.json").writeText(
            org.json.JSONObject().put("schema", 1).put("mode", "NORMAL")
                .put("generation", id).put("manifestSha256", sha256(manifestFile)).toString(),
        )
    }

    private fun sha256(file: File): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun realmdConfigBindsTheWildcardAndLockoutRidesIt() {
        val (_, files) = files()
        val conf = files.realmdConfig(com.pocketrealm.supervisor.RealmEndpoint.WILDCARD_BIND).readText()
        assertTrue("BindIP must carry the wildcard:\n$conf", "BindIP = \"0.0.0.0\"" in conf)
        assertTrue(
            "a wildcard bind accepts remote logons — the D11 lockout must ride it:\n$conf",
            "WrongPass.MaxCount = 10" in conf,
        )
    }

    @Test
    fun worldConfigBindsTheWildcard() {
        val (roots, files) = files()
        stageActiveGeneration(roots)
        val conf = files.worldConfig(com.pocketrealm.supervisor.RealmEndpoint.WILDCARD_BIND).readText()
        assertTrue("world BindIP must carry the wildcard:\n$conf", "BindIP = \"0.0.0.0\"" in conf)
        assertTrue("the world port stays fixed:\n$conf", "WorldServerPort = 8085" in conf)
    }

    @Test
    fun theWildcardLiteralStaysTheOnlyRawBindAccepted() {
        val (_, files) = files()
        listOf("0.0.0.0/0", "0.0.0.00", "0::0", "8.8.8.8", "localhost").forEach { address ->
            try {
                files.realmdConfig(address)
                fail("non-typed bind must be refused: $address")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "refusal names the endpoint contract: ${expected.message}",
                    (expected.message ?: "").contains("endpoint") ||
                        (expected.message ?: "").contains("LAN"),
                )
            }
        }
        assertFalse(
            "the wildcard never becomes a RealmEndpoint",
            runCatching { com.pocketrealm.supervisor.RealmEndpoint.parseLan("0.0.0.0") }.isSuccess,
        )
    }
}
