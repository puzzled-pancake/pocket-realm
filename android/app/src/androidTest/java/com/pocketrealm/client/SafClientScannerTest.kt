package com.pocketrealm.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class SafClientScannerTest {
    private val scanner = SafClientScanner(InstrumentationRegistry.getInstrumentation().targetContext.contentResolver)

    @Test fun validBuildAndFlatMpqsAreAcceptedReadOnly() {
        val access = fixture(5875)
        val result = scanner.scan(access)
        assertTrue(result.failures.joinToString(), result.supported)
        assertTrue(result.warnings.any { it.contains("accepted for vanilla launch") })
        assertFalse(result.sourceMutated)
        assertFalse(result.sourceRuntimeDependency)
        assertTrue(access.readCount > 0)
    }

    @Test fun wrongBuildIsRejected() {
        val result = scanner.scan(fixture(6005))
        assertFalse(result.supported)
        assertTrue(result.failures.any { it.startsWith("VAL-03") })
    }

    @Test fun launcherOnlySelectionIsRejected() {
        val access = FakeAccess(
            mapOf("root" to listOf(entry("launcher", "Launcher.exe"), Entry("data", "Data", true, 0))),
            emptyMap(),
        )
        val result = scanner.scan(access)
        assertFalse(result.supported)
        assertTrue(result.failures.any { it.contains("launcher-only") })
    }

    @Test fun corruptMpqHeaderIsRejected() {
        val access = fixture(5875, corruptMpq = "base.mpq")
        val result = scanner.scan(access)
        assertFalse(result.supported)
        assertTrue(result.failures.any { it.contains("invalid MPQ header") })
    }

    private fun fixture(build: Int, corruptMpq: String? = null): FakeAccess {
        val mpqs = REQUIRED.mapIndexed { index, name -> entry("mpq$index", name) }
        val entries = mapOf(
            "root" to listOf(entry("wow", "WoW.exe", size = 4096), Entry("data", "Data", true, 0)),
            "data" to mpqs,
        )
        val content = buildMap {
            put("wow", syntheticPe(build))
            for ((index, name) in REQUIRED.withIndex()) {
                put("mpq$index", if (name.equals(corruptMpq, true)) byteArrayOf(0, 0, 0, 0) else byteArrayOf(0x4d, 0x50, 0x51, 0x1a))
            }
        }
        return FakeAccess(entries, content)
    }

    private fun syntheticPe(build: Int): ByteArray {
        val bytes = ByteArray(4096)
        bytes[0] = 'M'.code.toByte(); bytes[1] = 'Z'.code.toByte()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x3c, 0x100)
        bytes[0x100] = 0x50; bytes[0x101] = 0x45
        buffer.putShort(0x104, 0x14c.toShort())
        buffer.putShort(0x118, 0x10b.toShort())
        buffer.putInt(0x300, 0xfeef04bd.toInt())
        buffer.putInt(0x308, (1 shl 16) or 12)
        buffer.putInt(0x30c, (1 shl 16) or build)
        return bytes
    }

    private fun entry(id: String, name: String, size: Long = 4) = Entry(id, name, false, size)
    private data class Entry(val id: String, val name: String, val directory: Boolean, val size: Long)

    private class FakeAccess(
        private val entries: Map<String, List<Entry>>,
        private val content: Map<String, ByteArray>,
    ) : SafClientScanner.Access {
        override val rootId = "root"
        var readCount = 0
        override fun children(parentId: String) = entries[parentId].orEmpty().map {
            SafClientScanner.Entry(it.id, it.name, it.directory, it.size)
        }
        override fun read(documentId: String, maxBytes: Int, rejectTruncation: Boolean): ByteArray {
            readCount++
            val bytes = requireNotNull(content[documentId])
            if (rejectTruncation && bytes.size > maxBytes) error("VAL-02: selected executable exceeds fast-scan limit")
            return bytes.copyOfRange(0, minOf(maxBytes, bytes.size))
        }
    }

    private companion object {
        val REQUIRED = listOf("base.MPQ", "dbc.MPQ", "fonts.MPQ", "interface.MPQ", "misc.MPQ", "model.MPQ", "sound.MPQ", "speech.MPQ", "terrain.MPQ", "texture.MPQ", "wmo.MPQ")
    }
}
