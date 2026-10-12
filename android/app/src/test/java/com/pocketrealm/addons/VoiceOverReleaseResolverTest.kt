package com.pocketrealm.addons

import com.pocketrealm.client.ClientFlavors
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

        val resolved = VoiceOverReleaseResolver.resolve(
            latest,
            { tag ->
                assertEquals("v1.3.1", tag)
                data
            },
            VoiceOverReleaseResolver.VoiceOverEra.VANILLA,
        )

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

    @Test fun `each era selects its own player zip and era data pack - R5-3`() {
        // The upstream v1.4.5 release ships AI_VoiceOver-WoW_{1.12,2.4.3,3.3.5}
        // player zips; each era must pick ITS OWN and the era's data pack.
        val eras = listOf(
            Triple(
                VoiceOverReleaseResolver.VoiceOverEra.VANILLA, "1.12",
                "AI_VoiceOverData_Vanilla",
            ),
            Triple(
                VoiceOverReleaseResolver.VoiceOverEra.TBC, "2.4.3",
                "AI_VoiceOverData_TBC",
            ),
            Triple(
                VoiceOverReleaseResolver.VoiceOverEra.WOTLK, "3.3.5",
                "AI_VoiceOverData_WotLK",
            ),
        )
        eras.forEach { (era, playerToken, dataFolder) ->
            val dataTag = "data-$playerToken"
            val dataUrl = "https://github.com/mrthinger/wow-voiceover/releases/download/$dataTag/$dataFolder-v1.4.5.zip"
            val latest = release(
                "v1.4.5",
                body = dataUrl,
                assets = listOf(
                    "AI_VoiceOver-WoW_1.12-v1.4.5.zip" to 10L,
                    "AI_VoiceOver-WoW_2.4.3-v1.4.5.zip" to 11L,
                    "AI_VoiceOver-WoW_3.3.5-v1.4.5.zip" to 12L,
                ),
            )
            val data = release(dataTag, assets = listOf("$dataFolder-v1.4.5.zip" to 20L))
            val resolved = VoiceOverReleaseResolver.resolve(latest, { data }, era)
            assertEquals(
                "$era: the era's own player zip",
                "AI_VoiceOver-WoW_${playerToken}-v1.4.5.zip",
                resolved.player.name,
            )
            assertTrue(
                "$era: the era's own data pack folder",
                resolved.data.name.startsWith(dataFolder),
            )
        }
    }

    @Test fun `a wrong-era data pack is refused for the wotlk lane - R5-3`() {
        // The old resolver pinned AI_VoiceOverData_Vanilla for every lane;
        // a wotlk resolve must refuse the vanilla pack, never silently
        // install it.
        val latest = release(
            "v1.4.5",
            body = "https://github.com/mrthinger/wow-voiceover/releases/download/data/AI_VoiceOverData_Vanilla-v1.zip",
            assets = listOf("AI_VoiceOver-WoW_3.3.5-v1.4.5.zip" to 12L),
        )
        val data = release("data", assets = listOf("AI_VoiceOverData_Vanilla-v1.zip" to 20L))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            VoiceOverReleaseResolver.resolve(
                latest, { data }, VoiceOverReleaseResolver.VoiceOverEra.WOTLK,
            )
        }
        assertTrue(
            "the refusal must name the era's data folder: ${failure.message}",
            "AI_VoiceOverData_WotLK" in failure.message!!,
        )
    }

    @Test fun `the era data policy accepts the era folder and refuses vanilla-only pins R6-r2-2`() {
        // The install path validates the downloaded data zip against the
        // era-parameterized policy: the TBC/WotLK packs (folder named for
        // the era) must validate — the old hard-pinned Vanilla
        // expectedFolders refused every era pack end-to-end.
        for (era in listOf(
            VoiceOverReleaseResolver.VoiceOverEra.TBC,
            VoiceOverReleaseResolver.VoiceOverEra.WOTLK,
        )) {
            val policy = com.pocketrealm.addons.AddonArchiveValidator.Policy
                .voiceOverDataPolicy(era)
            assertEquals(
                "$era: the policy expects the era's own data folder",
                setOf(era.dataFolder),
                policy.expectedFolders,
            )
        }
        // And the vanilla default keeps the frozen folder pin.
        assertEquals(
            setOf("AI_VoiceOverData_Vanilla"),
            com.pocketrealm.addons.AddonArchiveValidator.Policy
                .VOICEOVER_DATA.expectedFolders,
        )
    }

    @Test fun `no hard-coded vanilla-era copy rides the era flow R6-r2-3`() {
        // Source pin: the era-threaded install path derives its labels from
        // the era (playerToken/dataFolder) — the vanilla literals must not
        // reappear in the era-threaded call path. Android-only: the desktop
        // twin is a separate file (DesktopAddonRepository.kt) pinned by its
        // own desktop test.
        val sourceFile = java.io.File(
            "src/main/java/com/pocketrealm/addons/AddonRepository.kt",
        )
        if (!sourceFile.exists()) return  // desktop lane: android source not packaged
        val source = sourceFile.readText()
        val install = source.substring(
            source.indexOf("private fun installVoiceOverLocked"),
            source.indexOf("private fun resolveVoiceOverAssets"),
        )
        assertTrue(
            "the era-threaded install path must not hard-code '(1.12 add-on)'",
            "(1.12 add-on)" !in install,
        )
        assertTrue(
            "the era-threaded install path must not hard-code 'Vanilla sounds'",
            "Vanilla sounds" !in install,
        )
        assertTrue(
            "the install path must validate against the era-parameterized policy",
            "voiceOverDataPolicy(era)" in install,
        )
    }

    @Test fun `eraFor resolves the registry and refuses unknown flavors - R5-3`() {
        assertEquals(
            VoiceOverReleaseResolver.VoiceOverEra.VANILLA,
            VoiceOverReleaseResolver.eraFor(ClientFlavors.VANILLA.flavorId),
        )
        assertEquals(
            VoiceOverReleaseResolver.VoiceOverEra.TBC,
            VoiceOverReleaseResolver.eraFor(ClientFlavors.TBC.flavorId),
        )
        assertEquals(
            VoiceOverReleaseResolver.VoiceOverEra.WOTLK,
            VoiceOverReleaseResolver.eraFor(ClientFlavors.WOTLK.flavorId),
        )
        assertThrows(IllegalStateException::class.java) {
            VoiceOverReleaseResolver.eraFor("wotlk-classic-demo")
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
