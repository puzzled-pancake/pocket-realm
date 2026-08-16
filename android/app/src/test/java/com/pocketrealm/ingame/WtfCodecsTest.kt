package com.pocketrealm.ingame

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class ConfigWtfCodecTest {

    @Test
    fun `preserves unknown lines verbatim in content and order`() {
        val base = "SET readTOS \"1\"\r\n" +
            "SET someAddonLine \"keep me\"\r\n" +
            "a stray line\r\n" +
            "SET gxWindow \"1\"\r\n"
        val result = ConfigWtfCodec.merge(
            base,
            enforced = listOf(ConfigWtfCodec.EnforcedLine("maxFPS", "30")),
        )
        assertTrue(result.text.contains("SET someAddonLine \"keep me\""))
        assertTrue(result.text.contains("a stray line"))
        assertTrue(result.text.indexOf("readTOS") < result.text.indexOf("someAddonLine"))
        assertTrue(result.text.indexOf("someAddonLine") < result.text.indexOf("gxWindow"))
        // Appended enforced line lands after existing content.
        assertTrue(result.text.indexOf("gxWindow") < result.text.indexOf("maxFPS"))
    }

    @Test
    fun `replaces enforced values in place and appends missing ones`() {
        val base = "SET gxVSync \"1\"\r\nSET farclip \"500.000000\"\r\n"
        val result = ConfigWtfCodec.merge(
            base,
            enforced = listOf(
                ConfigWtfCodec.EnforcedLine("gxVSync", "0"),
                ConfigWtfCodec.EnforcedLine("gxApi", "d3d"),
            ),
        )
        assertEquals("SET gxVSync \"0\"\r\nSET farclip \"500.000000\"\r\nSET gxApi \"d3d\"\r\n", result.text)
    }

    @Test
    fun `conditional enforced line with null value deletes stale copy`() {
        val base = "SET MasterSoundEffects \"0\"\r\nSET movie \"0\"\r\n"
        val result = ConfigWtfCodec.merge(
            base,
            enforced = listOf(ConfigWtfCodec.EnforcedLine("MasterSoundEffects", null)),
        )
        assertFalse(result.text.contains("MasterSoundEffects"))
        assertTrue(result.text.contains("SET movie \"0\""))
    }

    @Test
    fun `user overrides apply after enforced and are skipped on enforced keys`() {
        val result = ConfigWtfCodec.merge(
            "SET farclip \"177\"\r\n",
            enforced = listOf(ConfigWtfCodec.EnforcedLine("ffxGlow", "0")),
            overrides = listOf(
                ConfigWtfCodec.UserOverride("farclip", "777.000000"),
                ConfigWtfCodec.UserOverride("ffxGlow", "1"),
            ),
        )
        assertEquals(listOf("ffxGlow"), result.skippedOverrides)
        assertTrue(result.text.contains("SET farclip \"777.000000\""))
        assertTrue(result.text.contains("SET ffxGlow \"0\""))
        assertFalse(result.text.contains("SET ffxGlow \"1\""))
    }

    @Test
    fun `user override with null value removes the line`() {
        val result = ConfigWtfCodec.merge(
            "SET MasterVolume \"0.500000\"\r\nSET movie \"0\"\r\n",
            enforced = emptyList(),
            overrides = listOf(ConfigWtfCodec.UserOverride("MasterVolume", null)),
        )
        assertEquals("SET movie \"0\"\r\n", result.text)
    }

    @Test
    fun `duplicate keys resolve last wins keeping first position`() {
        val base = "SET farclip \"177\"\r\nSET movie \"0\"\r\nSET farclip \"500.000000\"\r\n"
        val result = ConfigWtfCodec.merge(base, enforced = emptyList())
        assertTrue(result.text.indexOf("farclip") < result.text.indexOf("movie"))
        assertEquals("500.000000", ConfigWtfCodec.valueForKey(result.text, "farclip"))
        assertEquals(1, Regex("farclip").findAll(result.text).count())
    }

    @Test
    fun `tolerates client-written terminators and normalizes to CRLF`() {
        val base = "SET movie \"0\"\r\r\nSET gxWindow \"1\"\r"
        val result = ConfigWtfCodec.merge(base, enforced = emptyList())
        assertEquals("SET movie \"0\"\r\nSET gxWindow \"1\"\r\n", result.text)
    }

    @Test
    fun `parses quote-optional and case-insensitive SET lines`() {
        val values = ConfigWtfCodec.parse("set farclip 177\nSET movie \"0\"\n")
        assertEquals("177", values["farclip"])
        assertEquals("0", values["movie"])
    }

    @Test
    fun `fresh base renders enforced lines in order with trailing CRLF`() {
        val result = ConfigWtfCodec.merge(
            null,
            enforced = listOf(
                ConfigWtfCodec.EnforcedLine("readTOS", "1"),
                ConfigWtfCodec.EnforcedLine("movie", "0"),
            ),
        )
        assertEquals("SET readTOS \"1\"\r\nSET movie \"0\"\r\n", result.text)
    }

    @Test
    fun `float values render with six decimals like the client`() {
        assertEquals("500.000000", ConfigWtfCodec.formatValue(500f))
        assertEquals("45.000000", ConfigWtfCodec.formatValue(180f * 0.25f))
    }

    @Test
    fun `float rendering is locale-independent`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("45.000000", ConfigWtfCodec.formatValue(180f * 0.25f))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}

class SavedVariablesCodecTest {

    private val sample = "TALENT_FRAME_WAS_SHOWN = nil\r\r\n" +
        "SHOW_FULLSCREEN_STATUS = \"1\"\r\r\n" +
        "AUTO_QUEST_WATCH = 1\r\r\n" +
        "PARTYBACKGROUND_OPACITY = 0.5\r\r\n"

    @Test
    fun `parses observed scalar forms`() {
        val values = SavedVariablesCodec.parse(sample)
        assertEquals(SavedVariablesCodec.Value.Nil, values["TALENT_FRAME_WAS_SHOWN"])
        assertEquals(SavedVariablesCodec.Value.Str("1"), values["SHOW_FULLSCREEN_STATUS"])
        assertEquals(SavedVariablesCodec.Value.Num("1"), values["AUTO_QUEST_WATCH"])
        assertEquals(SavedVariablesCodec.Value.Num("0.5"), values["PARTYBACKGROUND_OPACITY"])
    }

    @Test
    fun `replaces an existing string scalar keeping its quoted form`() {
        val updated = SavedVariablesCodec.assign(sample, "SHOW_FULLSCREEN_STATUS", "0")
        assertTrue(updated!!.contains("SHOW_FULLSCREEN_STATUS = \"0\""))
        assertTrue(updated.contains("AUTO_QUEST_WATCH = 1"))
    }

    @Test
    fun `replaces an existing number scalar keeping its bare form`() {
        val updated = SavedVariablesCodec.assign(sample, "AUTO_QUEST_WATCH", "0", numberForm = true)
        assertTrue(updated!!.contains("AUTO_QUEST_WATCH = 0"))
        assertTrue(updated.contains("PARTYBACKGROUND_OPACITY = 0.5"))
    }

    @Test
    fun `appends a new scalar in the declared form with the file terminator`() {
        val updated = SavedVariablesCodec.assign(sample, "SHOW_NEWBIE_TIPS", "1")
        assertTrue(updated!!.endsWith("SHOW_NEWBIE_TIPS = \"1\"\r\r\n"))
        val asNumber = SavedVariablesCodec.assign(sample, "SOME_COUNT", "2", numberForm = true)
        assertTrue(asNumber!!.endsWith("SOME_COUNT = 2\r\r\n"))
    }

    @Test
    fun `appends a separator when the file lacks a final terminator`() {
        val updated = SavedVariablesCodec.assign("A = \"1\"", "B", "0")
        assertEquals("A = \"1\"\r\r\nB = \"0\"\r\r\n", updated)
    }

    @Test
    fun `refuses a structural assignment instead of editing it`() {
        val structural = "SOME_TABLE = {\r\r\n\t[1] = \"x\",\r\r\n}\r\r\n"
        assertNull(SavedVariablesCodec.assign(structural, "SOME_TABLE", "1"))
        assertTrue(SavedVariablesCodec.isAssigned(structural, "SOME_TABLE"))
        assertFalse(SavedVariablesCodec.isEditableScalar(structural, "SOME_TABLE"))
    }

    @Test
    fun `removal drops every assignment of the name`() {
        val twice = "A = \"1\"\r\r\nB = \"0\"\r\r\nA = \"0\"\r\r\n"
        val updated = SavedVariablesCodec.assign(twice, "A", null)
        assertEquals("B = \"0\"\r\r\n", updated)
        // Removing an absent name is a no-op, byte-identical.
        assertEquals(twice, SavedVariablesCodec.assign(twice, "Z", null))
    }

    @Test
    fun `reassignment collapses duplicates to one line`() {
        val twice = "A = \"1\"\r\r\nB = \"0\"\r\r\nA = \"0\"\r\r\n"
        val updated = SavedVariablesCodec.assign(twice, "A", "1")
        assertEquals("A = \"1\"\r\r\nB = \"0\"\r\r\n", updated)
    }
}

class BindingsFileCodecTest {

    private val sample = "bind W MOVEFORWARD\r\r\n" +
        "bind \"SPACE\" \"JUMP\"\r\r\n" +
        "bind NUMPAD0 JUMP\r\r\n" +
        "bind ALT-1 SELFACTIONBUTTON1\r\r\n" +
        "bind CTRL-SHIFT-TAB TARGETPREVIOUSFRIEND\r\r\n"

    @Test
    fun `parses quoted and unquoted lines with chords`() {
        val bindings = BindingsFileCodec.parse(sample)
        assertEquals("MOVEFORWARD", bindings["W"])
        assertEquals("JUMP", bindings["SPACE"])
        assertEquals("JUMP", bindings["NUMPAD0"])
        assertEquals("SELFACTIONBUTTON1", bindings["ALT-1"])
        assertEquals("TARGETPREVIOUSFRIEND", bindings["CTRL-SHIFT-TAB"])
    }

    @Test
    fun `two slots are the ordered keys bound to one command`() {
        assertEquals(listOf("SPACE", "NUMPAD0"), BindingsFileCodec.keysForCommand(sample, "jump"))
    }

    @Test
    fun `assign replaces a key in place unquoted`() {
        val updated = BindingsFileCodec.assign(sample, "SPACE", "TOGGLEAUTORUN")
        assertTrue(updated.contains("bind SPACE TOGGLEAUTORUN"))
        assertFalse(updated.contains("bind SPACE JUMP"))
        // Other lines untouched.
        assertTrue(updated.contains("bind NUMPAD0 JUMP"))
    }

    @Test
    fun `assign a fresh key appends after the last bind line`() {
        val updated = BindingsFileCodec.assign(sample, "G", "TARGETLASTHOSTILE")
        val appendedAt = updated.indexOf("bind G TARGETLASTHOSTILE")
        val lastBindAt = updated.indexOf("bind CTRL-SHIFT-TAB TARGETPREVIOUSFRIEND")
        assertTrue(appendedAt > lastBindAt)
    }

    @Test
    fun `null command unbinds the key`() {
        val updated = BindingsFileCodec.assign(sample, "NUMPAD0", null)
        assertFalse(updated.contains("NUMPAD0"))
        assertTrue(updated.contains("bind W MOVEFORWARD"))
    }

    @Test
    fun `round-trips capture-style bytes`() {
        // Byte fidelity for unchanged lines: only terminators of edited or
        // appended lines are regenerated; existing terminators survive.
        val updated = BindingsFileCodec.assign(sample, "W", "MOVEFORWARD")
        assertEquals(sample, updated)
    }
}
