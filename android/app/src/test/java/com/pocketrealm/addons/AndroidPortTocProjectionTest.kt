package com.pocketrealm.addons

import com.pocketrealm.client.ClientFlavors
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in add-on ships one master toc (the vanilla interface line) and
 * the launch projection rewrites it onto the running client's interface: the
 * installer fails closed on a mismatch, so a 2.4.3 client must see 20400 and
 * a 3.3.5a client 30300 — never the vanilla master.
 */
class AndroidPortTocProjectionTest {
    private val assetRoot: File by lazy {
        listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
            File("android/app/src/main/assets"),
            // Desktop JVM lane: gradlew -p desktop runs from the desktop/
            // module, so the shared asset tree sits one level up.
            File("../android/app/src/main/assets"),
        ).first { it.isDirectory }
    }
    private val masterToc: String
        get() = File(assetRoot, "addons/android-port/AndroidPort/AndroidPort.toc").readText()

    @Test fun `the vanilla projection is a byte-preserving pass-through`() {
        val text = masterToc
        assertEquals(text, AndroidPortTocProjection.projectTocText(text, ClientFlavors.VANILLA))
        assertEquals(11200, AndroidPortTocProjection.interfaceFor(ClientFlavors.VANILLA))
    }

    @Test fun `every expansion projection rewrites exactly the interface line`() {
        val text = masterToc
        for (flavor in listOf(ClientFlavors.TBC, ClientFlavors.WOTLK)) {
            val interfaceNumber = AndroidPortTocProjection.interfaceFor(flavor)
            val projected = AndroidPortTocProjection.projectTocText(text, flavor)
            assertNotEquals("the ${flavor.flavorId} projection must change the toc", text, projected)
            val changedLines = text.lineSequence().zip(projected.lineSequence())
                .count { (before, after) -> before != after }
            assertEquals(
                "the ${flavor.flavorId} projection must touch one line",
                1, changedLines,
            )
            assertTrue(
                "the ${flavor.flavorId} projection must declare its own interface",
                Regex("(?m)^## Interface:\\s*$interfaceNumber\\s*$").containsMatchIn(projected),
            )
        }
    }

    @Test fun `a regenerated asset that lost the master interface refuses`() {
        val tampered = masterToc.replace("## Interface: 11200", "## Interface: 20400")
        check(tampered != masterToc)
        for (flavor in ClientFlavors.ALL) {
            assertThrows(IllegalArgumentException::class.java) {
                AndroidPortTocProjection.projectTocText(tampered, flavor)
            }
        }
    }

    @Test fun `a toc without an interface line refuses`() {
        val stripped = masterToc.replaceFirst("## Interface: 11200\n", "")
        assertThrows(IllegalStateException::class.java) {
            AndroidPortTocProjection.projectTocText(stripped, ClientFlavors.TBC)
        }
    }

    @Test fun `the shipped master declares the vanilla interface`() {
        assertTrue(
            Regex("(?m)^## Interface:\\s*11200\\s*$").containsMatchIn(masterToc),
        )
        assertEquals(11200, AndroidPortTocProjection.MASTER_INTERFACE)
    }

    @Test fun `the host layout file loads before Core and names no layout as shipped`() {
        val tocFiles = masterToc.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(listOf(AndroidPortHostLayout.FILE, "Core.lua"), tocFiles.take(2))
        val shipped = File(assetRoot, "addons/android-port/AndroidPort/${AndroidPortHostLayout.FILE}").readText()
        assertTrue(shipped.contains("AndroidPort.HOST_LAYOUT = nil"))
    }

    @Test fun `the host layout projection writes only a known layout`() {
        val shipped = File(assetRoot, "addons/android-port/AndroidPort/${AndroidPortHostLayout.FILE}")
        val dir = kotlin.io.path.createTempDirectory("ap-host-layout").toFile()
        try {
            val target = File(dir, AndroidPortHostLayout.FILE)
            shipped.copyTo(target)
            AndroidPortHostLayout.write(dir, null)
            assertEquals(shipped.readText(), target.readText())
            AndroidPortHostLayout.write(dir, "forever")
            assertTrue(target.readText().contains("AndroidPort.HOST_LAYOUT = \"forever\""))
            assertNotEquals(shipped.readText(), target.readText())

            shipped.copyTo(target, overwrite = true)
            assertThrows(IllegalArgumentException::class.java) {
                AndroidPortHostLayout.write(dir, "\" os.exit() --")
            }
            assertEquals(shipped.readText(), target.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
