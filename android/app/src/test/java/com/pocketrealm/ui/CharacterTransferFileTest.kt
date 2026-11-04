package com.pocketrealm.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterTransferFileTest {
    private val dump = "IDENTITY: 1\nINSERT INTO `characters` VALUES ('1','2','Thrall');\n"

    @Test fun encodedFileRoundTrips() {
        val text = CharacterTransferFile.encode("vanilla", "Thrall", dump, 1_700_000_000_000L)
        val parsed = CharacterTransferFile.decode(text)
        assertEquals("vanilla", parsed.flavorId)
        assertEquals("Thrall", parsed.characterName)
        assertEquals(1_700_000_000_000L, parsed.createdAtMs)
        assertEquals(dump, parsed.dump)
    }

    @Test fun anEditedDumpFailsItsChecksum() {
        val json = JSONObject(CharacterTransferFile.encode("vanilla", "Thrall", dump, 0L))
        json.put("dump", dump.replace("Thrall", "Jaina"))
        val failure = runCatching { CharacterTransferFile.decode(json.toString()) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("checksum"))
    }

    @Test fun foreignJsonIsNotACharacterFile() {
        val failure = runCatching {
            CharacterTransferFile.decode(JSONObject().put("kind", "pocket-realm-characters").toString())
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("not a Pocket Realm character file"))
    }

    @Test fun transferVerdictsReadAsPlainLanguage() {
        assertTrue(characterTransferCopy("runtime-outdated").contains("update Pocket Realm"))
        assertTrue(characterTransferCopy("world-not-ready").contains("Start the realm"))
        assertEquals("Character imported.", characterTransferCopy(" Character imported. "))
    }
}
