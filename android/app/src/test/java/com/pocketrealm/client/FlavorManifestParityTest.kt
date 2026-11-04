package com.pocketrealm.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parity gate between `schemas/flavor.json` (schema 2) and the [ClientFlavors]
 * registry: every shared field must match exactly, so the manifest and the
 * Kotlin projection cannot silently diverge. The manifest is located by
 * walking up from the working directory (unit tests run from `android/app`);
 * a missing manifest FAILS with an actionable message rather than skipping.
 * The optional top-level `single_qualified_lane` extension is deliberately
 * not asserted — the `flavors` array is the only contract here.
 */
class FlavorManifestParityTest {
    @Test
    fun manifestFlavorsMatchTheRegistryOnEverySharedField() {
        val root = JSONObject(locateManifest().readText())
        val flavors = root.getJSONArray("flavors")
        val manifestById = (0 until flavors.length())
            .associate { index -> flavors.getJSONObject(index).getString("id") to flavors.getJSONObject(index) }
        assertEquals(ClientFlavors.ALL.map { it.flavorId }.toSet(), manifestById.keys.toSet())
        // associate collapses duplicate ids; the size check fails loud on one.
        assertEquals(ClientFlavors.ALL.size, manifestById.size)

        ClientFlavors.ALL.forEach { flavor ->
            val manifest = manifestById.getValue(flavor.flavorId)
            assertEquals(flavor.flavorId, manifest.getString("id"))
            assertEquals(flavor.clientId, manifest.getString("client_id"))
            assertEquals(flavor.build, manifest.getInt("build"))
            assertEquals(flavor.versionResource, manifest.getString("version_resource"))
            assertEquals(flavor.qualified, manifest.getBoolean("qualified"))
            assertEquals(flavor.expansion, manifest.getString("expansion"))

            val databases = manifest.getJSONObject("databases")
            assertEquals(flavor.databases.realmd, databases.getString("realmd"))
            assertEquals(flavor.databases.world, databases.getString("world"))
            assertEquals(flavor.databases.characters, databases.getString("characters"))
            assertEquals(flavor.databases.logs, databases.getString("logs"))

            val addonInterfaces = manifest.getJSONArray("addon_interfaces")
            assertEquals(addonInterfaces.length(), flavor.addonInterfaces.size)
            assertEquals(
                (0 until addonInterfaces.length()).map { addonInterfaces.getInt(it) }.toSet(),
                flavor.addonInterfaces,
            )

            val wine = manifest.getJSONObject("wine")
            assertEquals(flavor.winePrefixSegment, wine.getString("prefix_segment"))
            assertEquals(flavor.knownExeSha256, optionalString(wine, "known_exe_sha256"))

            val required = manifest.getJSONObject("mpq_set").getJSONArray("required")
            assertEquals(required.length(), flavor.requiredMpqs.size)
            assertEquals(
                (0 until required.length()).map { required.getString(it) }.toSet(),
                flavor.requiredMpqs,
            )

            // storage is JSON null until the flavor's budgets are measured.
            val storage = if (manifest.isNull("storage")) null else manifest.getJSONObject("storage")
            assertEquals(flavor.extractedEstimateBytes, storage?.getLong("extracted_estimate_bytes"))
            assertEquals(flavor.maxTotalBytes, storage?.getLong("max_total_bytes"))

            val botPolicy = manifest.getJSONObject("bot_policy")
            assertEquals(flavor.botMaxLevel, botPolicy.getInt("max_level"))
            val randomMaps = botPolicy.getJSONArray("random_maps")
            assertEquals(
                (0 until randomMaps.length()).map { randomMaps.getInt(it) },
                flavor.botRandomMaps,
            )

            // Correction: realmbuilds accepted-build set (never gamebuild).
            val realmd = manifest.getJSONObject("realmd")
            assertEquals("realmbuilds", realmd.getString("column"))
            assertEquals(flavor.realmdAcceptedBuilds, realmd.getString("accepted_builds"))
            assertTrue(
                "realmd.accepted_builds must include the flavor's own build " + flavor.build,
                flavor.realmdAcceptedBuilds.split(" ").contains(flavor.build.toString()),
            )

            // Correction: per-flavor frame descriptor; the OPCODE is the
            // shared constant asserted once below, never per-flavor.
            val authFrame = manifest.getJSONObject("auth_frame")
            assertEquals(flavor.proofResponseBytes, optionalInt(authFrame, "proof_response_bytes"))
            assertFalse(authFrame.has("smsg_auth_challenge_opcode"))

            // Correction: WTF layout + WotLK-only char-level cache set.
            val wtf = manifest.getJSONObject("wtf")
            assertEquals(flavor.wtfLayout, wtf.getString("layout"))
            val cacheFiles = wtf.getJSONArray("character_cache_files")
            assertEquals(
                flavor.wtfCharacterCacheFiles,
                (0 until cacheFiles.length()).map { cacheFiles.getString(it) }.toSet(),
            )

            // Corrections: pristine-exe provenance + the LAA fact.
            val clientBinary = manifest.getJSONObject("client_binary")
            assertEquals(flavor.knownExeMd5, optionalString(clientBinary, "known_exe_md5"))
            assertEquals(flavor.knownExeSizeBytes, optionalLong(clientBinary, "known_exe_size_bytes"))
            assertEquals(flavor.largeAddressAware, optionalBoolean(clientBinary, "large_address_aware"))

            // Correction: the conf matrix's excluded families come from the
            // manifest verbatim (the generator enforces them tool-side).
            val confMatrix = manifest.getJSONObject("conf_matrix")
            val excluded = confMatrix.getJSONArray("excluded_keys")
            for (index in 0 until excluded.length()) {
                val entry = excluded.getJSONObject(index)
                assertTrue(entry.getString("key_family").isNotEmpty())
                assertTrue(entry.getString("reason").isNotEmpty())
                assertTrue(entry.getString("register_ref").isNotEmpty())
                assertTrue(entry.getString("enforced_by").isNotEmpty())
            }

            // the four W3-era shared fields
            // join the parity gate — flavor.json and the registry can no
            // longer drift on them silently.
            assertEquals(
                flavor.graphicsRoutes,
                wine.getJSONArray("graphics_routes")
                    .let { routes -> (0 until routes.length()).map { routes.getString(it) }.toSet() },
            )
            assertEquals(flavor.vanillaTweaksEnabled, wine.getBoolean("vanilla_tweaks"))
        }

        // Exactly the qualified flags the registry projects.
        assertEquals(
            manifestById.values.filter { it.getBoolean("qualified") }.map { it.getString("id") }.toSet(),
            ClientFlavors.qualified().map { it.flavorId }.toSet(),
        )
        assertTrue(ClientFlavors.ALL.isNotEmpty())

        // Correction: ONE shared auth-challenge opcode across the three
        // branches — pinned on both sides of the parity gate.
        assertEquals(0x1EC, ClientFlavors.AUTH_CHALLENGE_OPCODE)
        val auth = root.getJSONObject("auth")
        assertEquals("0x1EC", auth.getString("smsg_auth_challenge_opcode"))
    }

    private fun optionalString(obj: JSONObject, key: String): String? =
        if (obj.isNull(key)) null else obj.getString(key)

    private fun optionalInt(obj: JSONObject, key: String): Int? =
        if (obj.isNull(key)) null else obj.getInt(key)

    private fun optionalLong(obj: JSONObject, key: String): Long? =
        if (obj.isNull(key)) null else obj.getLong(key)

    private fun optionalBoolean(obj: JSONObject, key: String): Boolean? =
        if (obj.isNull(key)) null else obj.getBoolean(key)

    private fun locateManifest(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = dir?.let { File(it, "schemas/flavor.json") }
            if (candidate != null && candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        throw AssertionError(
            "schemas/flavor.json not found walking up from ${System.getProperty("user.dir")}: " +
                "run the unit tests from the repository checkout (workingDir = android/app or the " +
                "repo root) so the manifest stays on the parity gate; this suite must fail loud, not skip.",
        )
    }
}
