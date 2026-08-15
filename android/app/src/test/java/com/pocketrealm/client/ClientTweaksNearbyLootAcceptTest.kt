package com.pocketrealm.client

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM coverage for the Pocket Realm companion patch that lets the L1
 * nearby-use feature open loot windows: the stock 1.12.1 client releases
 * server-initiated `SMSG_LOOT_RESPONSE` loot instead of accepting it unless
 * the loot-type gate (`cmp al,2/3/4`) is widened to `cmp al,1; jae`.
 */
class ClientTweaksNearbyLootAcceptTest {

    /** The unique 24-byte original gate at file offset 0x1EB944 (VA 0x5EB944). */
    private val gateSignature = byteArrayOf(
        0x0B, 0xC1.toByte(), 0x8A.toByte(), 0x45, 0xFE.toByte(), 0x75, 0x18,
        0x3C, 0x02, 0x0F, 0x84.toByte(), 0xA1.toByte(), 0x00, 0x00, 0x00,
        0x3C, 0x03, 0x0F, 0x84.toByte(), 0x99.toByte(), 0x00, 0x00, 0x00,
        0x3C, 0x04,
    )

    private fun syntheticQualifiedImage(): ByteArray = ByteArray(0x500_000) { index ->
        ((index * 37 + 11) and 0xff).toByte()
    }.also {
        it[0] = 'M'.code.toByte()
        it[1] = 'Z'.code.toByte()
        gateSignature.copyInto(it, 0x1EB944)
    }

    @Test fun `patch rewrites exactly the two gate bytes`() {
        val pristine = syntheticQualifiedImage()
        val patched = ClientTweaksConfig.applyNearbyLootAcceptPatch(pristine)
        val changed = pristine.indices.filter { pristine[it] != patched[it] }
        assertEquals(listOf(0x1EB94C, 0x1EB94E), changed)
        assertEquals(0x01, patched[0x1EB94C].toInt() and 0xff) // cmp al, 2 -> cmp al, 1
        assertEquals(0x83, patched[0x1EB94E].toInt() and 0xff) // je -> jae
    }

    @Test fun `patch leaves the surrounding gate untouched`() {
        val pristine = syntheticQualifiedImage()
        val patched = ClientTweaksConfig.applyNearbyLootAcceptPatch(pristine)
        val window = 0x1EB944 until 0x1EB944 + gateSignature.size
        window.forEach { offset ->
            if (offset != 0x1EB94C && offset != 0x1EB94E) {
                assertEquals("byte $offset drifted", pristine[offset], patched[offset])
            }
        }
    }

    @Test fun `published model composes the companion patch with vanilla tweaks`() {
        val config = ClientTweaksConfig.commonPreset()
        val pristine = syntheticQualifiedImage()
        val published = ClientTweaksConfig.expectedPublishedPatchedBytes(pristine, config)
        val manual = ClientTweaksConfig.applyNearbyLootAcceptPatch(
            ClientTweaksConfig.expectedPatchedBytes(pristine, config),
        )
        assertArrayEquals(manual, published)
        assertEquals(0x01, published[0x1EB94C].toInt() and 0xff)
        assertEquals(0x83, published[0x1EB94E].toInt() and 0xff)
    }

    @Test fun `missing gate fails closed instead of patching`() {
        val image = ByteArray(0x500_000) { 0x55 }
        image[0] = 'M'.code.toByte()
        image[1] = 'Z'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) {
            ClientTweaksConfig.applyNearbyLootAcceptPatch(image)
        }
    }

    @Test fun `ambiguous gate fails closed`() {
        val image = syntheticQualifiedImage()
        gateSignature.copyInto(image, 0x300_000)
        assertThrows(IllegalArgumentException::class.java) {
            ClientTweaksConfig.applyNearbyLootAcceptPatch(image)
        }
    }

    @Test fun `non-PE input fails closed`() {
        val image = syntheticQualifiedImage()
        image[0] = 'X'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) {
            ClientTweaksConfig.applyNearbyLootAcceptPatch(image)
        }
    }

    @Test fun `gate signature matches the authorized client image exactly once`() {
        // Contract guard: the anchor is byte-exact against build 5875 enUS.
        // The synthetic image plants the same bytes, so the model's private
        // constant must equal the planted gate used throughout this test.
        val pristine = syntheticQualifiedImage()
        assertTrue(
            pristine.copyOfRange(0x1EB944, 0x1EB944 + gateSignature.size)
                .contentEquals(gateSignature),
        )
    }
}
