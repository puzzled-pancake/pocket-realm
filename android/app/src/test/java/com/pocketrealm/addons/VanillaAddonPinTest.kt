package com.pocketrealm.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * G3-6 wave-0 pins: the vanilla addon surface is frozen so the H4-3
 * per-flavor catalog rework (S7) can never re-key or re-validate what
 * existing vanilla users have installed.
 *
 *  - `addons/catalog-v1.json` is byte-frozen: the committed golden sha here
 *    mirrors the digest gate in tools/check_repo.py (either gate failing
 *    means the vanilla catalog file was edited; per-flavor catalogs are NEW
 *    assets, never an edit of the vanilla file);
 *  - the `AddonArchiveValidator` vanilla policy constants (VANILLA,
 *    VOICEOVER_PLAYER, VOICEOVER_DATA) are asserted field-by-field against
 *    this committed literal list — an accidental edit of a cap, an accepted
 *    interface, or an expected folder fails CI;
 *  - the VoiceOver resolver's vanilla install id and folder/name mapping are
 *    fixed (per-expansion artifacts extend the resolver with new ids).
 */
class VanillaAddonPinTest {

    /**
     * The bundled asset tree, resolved for every JVM lane that compiles the
     * shared addon sources: the :app module directory (Android), the repo
     * root, and — DESK-39 — one level up from the desktop module so the
     * shared test suite runs unmodified under `gradlew -p desktop test`.
     */
    private val assetRoot: File by lazy {
        listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
            File("android/app/src/main/assets"),
            File("../android/app/src/main/assets"),
        ).first { it.isDirectory }
    }

    @Test fun `vanilla catalog asset is byte-frozen at the pinned sha`() {
        // Resolved relative to the JVM lane's module directory (see assetRoot).
        val catalog = File(assetRoot, "addons/catalog-v1.json")
        assertTrue("catalog-v1.json asset not found at ${catalog.absolutePath}", catalog.isFile)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(catalog.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(
            "1741a486e919696be6569c5f28b2ef53841737cd339f40d7e85be2ee569d1dba",
            digest,
        )
    }

    @Test fun `VANILLA validator policy stays at the pinned vanilla literals`() {
        val policy = AddonArchiveValidator.Policy.VANILLA
        assertEquals(128L * 1024 * 1024, policy.maxArchiveBytes)
        assertEquals(512L * 1024 * 1024, policy.maxExpandedBytes)
        assertEquals(20_000, policy.maxEntries)
        assertEquals(setOf(11200), policy.acceptedInterfaces)
        assertEquals(null, policy.expectedFolders)
        assertEquals(emptyMap<String, String>(), policy.requiredTocFields)
        assertEquals(null, policy.preferredRootToc)
    }

    @Test fun `VOICEOVER_PLAYER validator policy stays at the pinned vanilla literals`() {
        val policy = AddonArchiveValidator.Policy.VOICEOVER_PLAYER
        assertEquals(128L * 1024 * 1024, policy.maxArchiveBytes)
        assertEquals(512L * 1024 * 1024, policy.maxExpandedBytes)
        assertEquals(20_000, policy.maxEntries)
        assertEquals(setOf(11200), policy.acceptedInterfaces)
        assertEquals(setOf("AI_VoiceOver"), policy.expectedFolders)
        assertEquals(emptyMap<String, String>(), policy.requiredTocFields)
        assertEquals("AI_VoiceOver.toc", policy.preferredRootToc)
    }

    @Test fun `VOICEOVER_DATA validator policy stays at the pinned vanilla literals`() {
        val policy = AddonArchiveValidator.Policy.VOICEOVER_DATA
        assertEquals(1536L * 1024 * 1024, policy.maxArchiveBytes)
        assertEquals(1536L * 1024 * 1024, policy.maxExpandedBytes)
        assertEquals(20_000, policy.maxEntries)
        assertEquals(setOf(11200, 100000), policy.acceptedInterfaces)
        assertEquals(setOf("AI_VoiceOverData_Vanilla"), policy.expectedFolders)
        assertEquals(
            mapOf(
                "OptionalDeps" to "AI_VoiceOver_112, AI_VoiceOver",
                "X-VoiceOver-DataModule-Version" to "1",
            ),
            policy.requiredTocFields,
        )
        assertEquals(null, policy.preferredRootToc)
    }

    @Test fun `VoiceOver resolver vanilla install identity stays fixed`() {
        assertEquals("mrthinger__wow-voiceover", VoiceOverReleaseResolver.INSTALL_ID)
        assertEquals("AI_VoiceOver", VoiceOverReleaseResolver.PLAYER_FOLDER)
        assertEquals("AI_VoiceOverData_Vanilla", VoiceOverReleaseResolver.DATA_FOLDER)
        assertEquals(128L * 1024 * 1024, VoiceOverReleaseResolver.MAX_PLAYER_BYTES)
        assertEquals(
            AddonArchiveValidator.VOICEOVER_DATA_MAX_ARCHIVE_BYTES,
            VoiceOverReleaseResolver.MAX_DATA_BYTES,
        )
        assertEquals(1536L * 1024 * 1024, VoiceOverReleaseResolver.MAX_DATA_BYTES)
    }

    @Test fun `catalog parse pins the vanilla registry invariants`() {
        val catalog = AddonCatalog.parse(
            File(assetRoot, "addons/catalog-v1.json").readText(),
        )
        // The exact count and recommended ids the vanilla lane has shipped:
        // renumbering matrixIds or retuning recommendations breaks installed
        // addons' update checks and addonForInstalled resolution.
        assertEquals(154, catalog.addons.count())
        assertTrue(catalog.addons.all { it.matrixId.matches(Regex("\\d{3}")) })
        assertEquals(listOf("151", "002", "059"), catalog.recommended.map { it.matrixId })
    }

    /**
     * G3-6 item 3: a pre-multi-flavor registry.json fixture loads through the
     * pure codec and resolves its catalog entries exactly as before — the ids
     * are never flavor-prefixed; F3-5's per-flavor scoping rides the
     * projection layer above this representation.
     */
    @Test fun `pre-change registry fixture loads and resolves catalog entries identically`() {
        val registryRoot = tempRegistryRoot()
        // The package directories the fixture's records point at (the
        // presence requirement the strict reader has always enforced).
        File(registryRoot, "packages/mrthinger__wow-voiceover/AI_VoiceOver").apply { mkdirs() }
        File(registryRoot, "packages/builtin__androidport/AndroidPort").apply { mkdirs() }
        val installed = AddonRegistryCodec.decodeInstalled(
            registryRoot,
            fixtureText("registry-current-release.json"),
        )
        assertEquals(listOf("mrthinger__wow-voiceover", "builtin__androidport"), installed.map { it.id })
        val catalog = AddonCatalog.parse(
            File(assetRoot, "addons/catalog-v1.json").readText(),
        )
        assertEquals("AI_VoiceOver", installed[0].folders.single())
        // The installed id IS the catalog's cross-flavor identity key: the
        // resolution the update checks and rollback matching rely on.
        assertEquals("mrthinger__wow-voiceover", catalog.addonForInstalled(installed[0])?.installId)
        assertEquals("builtin__androidport", catalog.addonForInstalled(installed[1])?.installId)
        assertEquals("Android Port", installed[1].displayName)
        // The round-trip is content-stable: encode(decode(fixture)) equals the
        // fixture in the sorted-key canonical form. The committed fixture
        // carries the shipping (Android org.json) insertion order — the
        // writer's chain order; the JVM classpath's org.json hash-scrambles
        // order, so byte equality is asserted only where the platform
        // serializer runs (the instrumented device pins).
        assertEquals(
            canonical(fixtureText("registry-current-release.json")),
            canonical(AddonRegistryCodec.encode(installed)),
        )
    }

    /**
     * The projector's tolerance lane over the same durable format: the pure
     * [AddonRegistryCodec.decode] parses with no filesystem coupling, while
     * the strict repository reader still requires every row's package
     * directory to exist. A launch boundary whose cached package is gone must
     * read the registry and fail at its own per-folder checks — never while
     * the format is being parsed.
     */
    @Test fun `pure decode reads a registry whose package directories are absent`() {
        val installed = AddonRegistryCodec.decode(fixtureText("registry-current-release.json"))
        assertEquals(listOf("mrthinger__wow-voiceover", "builtin__androidport"), installed.map { it.id })
        assertEquals("Android Port", installed[1].displayName)
        try {
            AddonRegistryCodec.decodeInstalled(
                tempRegistryRoot(),
                fixtureText("registry-current-release.json"),
            )
            fail("decodeInstalled must keep requiring the package directories to exist")
        } catch (expected: IllegalArgumentException) {
            // The presence requirement the strict reader has always enforced.
        }
    }

    /** Order-insensitive, content-exact serialization (sorted keys, compact). */
    private fun canonical(text: String): String = canonicalJson(org.json.JSONObject(text))

    private fun canonicalJson(value: Any): String = when (value) {
        is org.json.JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { key ->
            org.json.JSONObject.quote(key) + ":" + canonicalJson(value.get(key))
        }
        is org.json.JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index ->
            canonicalJson(value.get(index))
        }
        is String -> org.json.JSONObject.quote(value)
        else -> value.toString()
    }

    private val scratchRoots = mutableListOf<File>()

    private fun fixtureText(name: String): String =
        javaClass.getResourceAsStream("/fixtures/g3/$name")!!.readBytes().toString(Charsets.UTF_8)

    /** Scratch root for the registry fixture's package-tree presence checks. */
    private fun tempRegistryRoot(): File =
        java.nio.file.Files.createTempDirectory("g3-registry").toFile().also { scratchRoots.add(it) }

    @org.junit.After fun cleanScratch() {
        scratchRoots.forEach { it.deleteRecursively() }
    }
}
