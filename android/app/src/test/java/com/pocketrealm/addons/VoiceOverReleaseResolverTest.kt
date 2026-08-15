package com.pocketrealm.addons

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceOverReleaseResolverTest {
    @Test fun `resolves exact 112 player and separately linked Vanilla sound pack`() {
        val latest = release(
            tag = "v1.4.5",
            body = "[Vanilla Sounds](https://github.com/mrthinger/wow-voiceover/releases/download/v1.3.1/AI_VoiceOverData_Vanilla-v1.0.0.zip)",
            assets = listOf("AI_VoiceOver-WoW_1.12-v1.4.5.zip" to 722_780L),
        )
        val data = release(
            tag = "v1.3.1",
            assets = listOf("AI_VoiceOverData_Vanilla-v1.0.0.zip" to 1_166_727_217L),
        )

        val resolved = VoiceOverReleaseResolver.resolve(latest) { tag ->
            assertEquals("v1.3.1", tag)
            data
        }

        assertEquals("AI_VoiceOver-WoW_1.12-v1.4.5.zip", resolved.player.name)
        assertEquals("AI_VoiceOverData_Vanilla-v1.0.0.zip", resolved.data.name)
        assertEquals(1_166_727_217L, resolved.data.size)
        assertEquals(64, resolved.remoteIdentity.length)
        assertEquals(resolved.remoteIdentity, VoiceOverReleaseResolver.resolve(latest) { data }.remoteIdentity)
    }

    @Test fun `composite update identity changes for either exact release asset`() {
        val dataUrl = "https://github.com/mrthinger/wow-voiceover/releases/download/data/AI_VoiceOverData_Vanilla-v1.zip"
        val latest = release(
            "v2",
            body = dataUrl,
            assets = listOf("AI_VoiceOver-WoW_1.12-v2.zip" to 10L),
        )
        val data = release(
            "data",
            assets = listOf("AI_VoiceOverData_Vanilla-v1.zip" to 20L),
        )
        val baseline = VoiceOverReleaseResolver.resolve(latest) { data }.remoteIdentity
        assertTrue(baseline != "a".repeat(40)) // Legacy commit-only installations migrate once.

        val changedPlayer = JSONObject(latest.toString()).also {
            it.getJSONArray("assets").getJSONObject(0).put("id", 999_001L)
        }
        val changedData = JSONObject(data.toString()).also {
            it.getJSONArray("assets").getJSONObject(0).put("size", 21L)
        }

        assertTrue(baseline != VoiceOverReleaseResolver.resolve(changedPlayer) { data }.remoteIdentity)
        assertTrue(baseline != VoiceOverReleaseResolver.resolve(latest) { changedData }.remoteIdentity)
    }

    @Test fun `missing or ambiguous sound links fail closed`() {
        val player = "AI_VoiceOver-WoW_1.12-v2.zip" to 10L
        assertThrows(IllegalArgumentException::class.java) {
            VoiceOverReleaseResolver.resolve(release("v2", assets = listOf(player))) { error("unused") }
        }

        val first = "https://github.com/mrthinger/wow-voiceover/releases/download/v1/AI_VoiceOverData_Vanilla-v1.zip"
        val second = "https://github.com/mrthinger/wow-voiceover/releases/download/v2/AI_VoiceOverData_Vanilla-v2.zip"
        assertThrows(IllegalArgumentException::class.java) {
            VoiceOverReleaseResolver.resolve(
                release("v2", body = "$first\n$second", assets = listOf(player)),
            ) { error("unused") }
        }
    }

    @Test fun `asset URL identity and narrowly scoped size limits fail closed`() {
        val dataUrl = "https://github.com/mrthinger/wow-voiceover/releases/download/data/AI_VoiceOverData_Vanilla-v1.zip"
        val latest = release(
            "v2",
            body = dataUrl,
            assets = listOf("AI_VoiceOver-WoW_1.12-v2.zip" to 10L),
        )
        val oversizedData = release(
            "data",
            assets = listOf(
                "AI_VoiceOverData_Vanilla-v1.zip" to (VoiceOverReleaseResolver.MAX_DATA_BYTES + 1),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            VoiceOverReleaseResolver.resolve(latest) { oversizedData }
        }

        val wrongUrl = release(
            "data",
            assets = listOf("AI_VoiceOverData_Vanilla-v1.zip" to 10L),
        ).also {
            it.getJSONArray("assets").getJSONObject(0)
                .put("browser_download_url", "https://github.com/attacker/repo/releases/download/data/file.zip")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceOverReleaseResolver.resolve(latest) { wrongUrl }
        }
    }

    private fun release(
        tag: String,
        body: String = "",
        assets: List<Pair<String, Long>> = emptyList(),
    ): JSONObject = JSONObject()
        .put("tag_name", tag)
        .put("body", body)
        .put("assets", JSONArray().apply {
            assets.forEachIndexed { index, (name, size) ->
                put(JSONObject()
                    .put("id", 10_000L + index)
                    .put("name", name)
                    .put("size", size)
                    .put(
                        "browser_download_url",
                        "https://github.com/mrthinger/wow-voiceover/releases/download/$tag/$name",
                    ))
            }
        })
}
