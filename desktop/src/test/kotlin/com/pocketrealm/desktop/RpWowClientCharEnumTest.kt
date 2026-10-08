package com.pocketrealm.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SMSG_CHAR_ENUM decode fixtures (LV-bot-actors-04): the harness probe
 * client's enum parser against the pinned vanilla lane's per-record wire
 * layout.
 *
 * Fixture provenance: the packet bytes are produced by an INDEPENDENT
 * encoder written from the server's own writer —
 * CharacterHandler::HandleCharEnum (u8 count,
 * native/cmangos/src/game/Entities/CharacterHandler.cpp:289-314) plus
 * Player::BuildEnumData field-for-field
 * (native/cmangos/src/game/Entities/Player.cpp:1724-1806) — never from
 * the Kotlin under test, so agreement is a cross-check of
 * [RpWowClient.parseCharEnum]'s record stride and field order. The
 * character rows carry the live probe account's ground truth
 * (out/live-verification/evidence/LV-bot-actors-04/diag2-multichar.json:
 * RPTEST held Rpprobe guid 57 + Davos guid 58; the shipped 60-byte-skip
 * parser decoded record 2 as name "" / guid 10875269510332416, which is
 * exactly what this suite pins out).
 */
@Suppress("MagicNumber") // fixed-width 1.12 wire fixtures
class RpWowClientCharEnumTest {
    /** One character row to encode; defaults are the live RPTEST row
     * shape: human warrior (race 1 / class 1 - the charCreate lane),
     * level 1, at the Northshire Abbey spawn recorded in
     * tools/reset_davos.py (map 0, zone 12, x/y/z), no guild, no pet
     * (BuildEnumData still writes the pet block, zeroed), empty equipment
     * (BuildEnumData writes u32(0)+u8(0) per empty slot,
     * Player.cpp:1796-1801 - the parser must treat those bytes as pure
     * stride). */
    private class CharRow(
        val guid: Long,
        val name: String,
        val race: Int = 1,
        val classId: Int = 1,
        val gender: Int = 0,
        val playerBytes: Int = 0,
        val facialHair: Int = 0,
        val level: Int = 1,
        val zone: Int = 12,
        val mapId: Int = 0,
        val x: Float = -8913.0f,
        val y: Float = -133.0f,
        val z: Float = 81.0f,
        val guildId: Int = 0,
        val charFlags: Int = 0,
        val firstLogin: Int = 0,
        val petDisplayId: Int = 0,
        val petLevel: Int = 0,
        val petFamily: Int = 0,
        val equipment: List<Pair<Int, Int>> = List(ENUM_SLOTS) { 0 to 0 },
    )

    @Test
    fun twoCharPacketDecodesBothRecordsLikeTheLiveProbe() {
        // The exact captured scenario: RPTEST's two rows in guid order.
        val records = RpWowClient.parseCharEnum(enumPacket(listOf(RPPROBE, DAVOS)))
        assertEquals(2, records.size)
        assertEquals(57L, records[0].guid)
        assertEquals("Rpprobe", records[0].name)
        assertEquals(58L, records[1].guid)
        assertEquals("Davos", records[1].name)
    }

    @Test
    fun surfacedFieldsCarryTheSeededRowValues() {
        val records = RpWowClient.parseCharEnum(enumPacket(listOf(DAVOS)))
        assertEquals(1, records.size)
        with(records[0]) {
            assertEquals(58L, guid)
            assertEquals("Davos", name)
            assertEquals(1, race)
            assertEquals(1, classId)
            assertEquals(0, gender)
            assertEquals(1, level)
            assertEquals(12, zone)
            assertEquals(0, mapId)
        }
    }

    @Test
    fun singleCharPacketStillDecodes() {
        // LV-multiplayer-06 ran this op chain green on a single-char
        // account; the layout walk must keep that lane intact.
        val records = RpWowClient.parseCharEnum(enumPacket(listOf(RPPROBE)))
        assertEquals(listOf(57L to "Rpprobe"), records.map { it.guid to it.name })
    }

    @Test
    fun wireBytesMatchTheBuildEnumDataLayoutByteForByte() {
        val payload = enumPacket(listOf(RPPROBE))
        // Hand-derived from Player::BuildEnumData (Player.cpp:1724-1741),
        // through the map field: count 01, guid 57 little-endian,
        // "Rpprobe" NUL-terminated, race 1 human, class 1 warrior,
        // gender 0, the zeroed playerBytes looks + facial hair, level 1,
        // zone 12 (Northshire), map 0.
        val prefixHex = (
            "01" +
                "3900000000000000" +
                "527070726F626500" +
                "01" + "01" + "00" +
                "00000000" + "00" +
                "01" +
                "0C000000" +
                "00000000"
            )
        val prefix = prefixHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue(
            "packet ${payload.size}B must start with the 34-byte hand-derived enum prefix",
            payload.size > prefix.size &&
                payload.copyOfRange(0, prefix.size).contentEquals(prefix),
        )
        assertEquals(1, RpWowClient.parseCharEnum(payload).size)
    }

    // ---- the independent encoder ---------------------------------------

    /** Encode the SMSG_CHAR_ENUM payload exactly as the pinned server
     * writes it; each line cites the native writer it mirrors. */
    private fun enumPacket(chars: List<CharRow>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(chars.size)                                              // CharacterHandler.cpp:295
        for (c in chars) {
            out.write(u64(c.guid))            // ObjectGuid raw u64          // Player.cpp:1724
            out.write(zstr(c.name))                                           // :1725
            out.write(u8(c.race))                                             // :1726
            out.write(u8(c.classId))                                          // :1727
            out.write(u8(c.gender))                                           // :1728
            out.write(                                                        // :1730-1734
                u8(c.playerBytes and 0xFF) +
                    u8((c.playerBytes shr 8) and 0xFF) +
                    u8((c.playerBytes shr 16) and 0xFF) +
                    u8((c.playerBytes shr 24) and 0xFF),
            )
            out.write(u8(c.facialHair))                                       // :1736-1737
            out.write(u8(c.level))                                            // :1739
            out.write(u32(c.zone))                                            // :1740
            out.write(u32(c.mapId))                                           // :1741
            out.write(f32(c.x) + f32(c.y) + f32(c.z))                         // :1743-1745
            out.write(u32(c.guildId))                                         // :1747
            out.write(u32(c.charFlags))                                       // :1761
            out.write(u8(c.firstLogin))                                       // :1764
            out.write(u32(c.petDisplayId) + u32(c.petLevel) + u32(c.petFamily)) // :1785-1787
            assertEquals(ENUM_SLOTS, c.equipment.size)                        // :1792 (Player.h:585)
            for ((displayId, inventoryType) in c.equipment) {
                out.write(u32(displayId) + u8(inventoryType))                 // :1804-1805
            }
        }
        return out.toByteArray()
    }

    private fun u8(v: Int) = byteArrayOf(v.toByte())

    private fun u32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun u64(v: Long): ByteArray {
        val out = ByteArray(8)
        for (b in 0 until 8) out[b] = ((v shr (8 * b)) and 0xFF).toByte()
        return out
    }

    /** ByteBuffer writes IEEE 754 floats little-endian. */
    private fun f32(v: Float) = u32(v.toRawBits())

    private fun zstr(v: String) = v.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    private companion object {
        /** INVENTORY_SLOT_BAG_START + 1 (Player.h:585) equipment slots. */
        const val ENUM_SLOTS = 20

        /** diag2-multichar.json's ground-truth RPTEST rows. */
        val RPPROBE = CharRow(guid = 57, name = "Rpprobe")
        val DAVOS = CharRow(guid = 58, name = "Davos")
    }
}
