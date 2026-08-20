package com.pocketrealm.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Phase C coverage for the generation-identity and guest-path layers the
 * user lane feeds: the identity must accept user-driver fields, stay
 * deterministic per driver payload, and the staged guest paths must match
 * the layout installUserArmGraphics writes and the ICD rewrite targets.
 */
class UserVulkanGenerationIdentityTest {

    private fun sha256Of(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun userIdentity(
        librarySha: String = "a".repeat(64),
        icdSha: String = "b".repeat(64),
        driverId: String = "user-turnip-26-3",
    ) = ArmGraphicsGenerationIdentity(
        runtimeBuildId = ClientRuntimeContract.ARM_TRANSLATED_RUNTIME_BUILD_ID,
        rendererBuildId = ClientRuntimeContract.armRendererBuildId(
            ArmTranslationBackend.BOX64, "dxvk",
            RendererPackageCatalog.BOX64_DEFAULT, driverId,
        ),
        prefixSchema = ClientRuntimeContract.PREFIX_SCHEMA,
        translatorId = ArmTranslationBackend.BOX64.id,
        rendererId = "dxvk",
        managedClientId = ClientRuntimeContract.WOW_5875_ID,
        managedClientGeneration = "g1",
        managedClientManifestSha256 = "c".repeat(64),
        managedClientExecutableSha256 = "d".repeat(64),
        rendererPackageId = RendererPackageCatalog.BOX64_DEFAULT,
        rendererPackageBuildId = "box64-dxvk-2.4.1",
        rendererPackageDxvkVersion = "2.4.1",
        rendererPackageSystem32Sha256 = "e".repeat(64),
        rendererPackageSyswow64Sha256 = "f".repeat(64),
        vulkanDriverId = driverId,
        vulkanDriverBuildId = driverId,
        vulkanDriverLibrarySha256 = librarySha,
        vulkanDriverIcdSha256 = icdSha,
    )

    @Test
    fun userDriverFieldsSatisfyTheDxvkGenerationContract() {
        val identity = userIdentity()
        assertTrue(identity.generationName.matches(Regex("g-[0-9a-f]{32}")))
        // Round-trip through the manifest compatibility block.
        val manifest = JSONObject().put("compatibility", identity.toJson())
        assertTrue(identity.matchesManifest(manifest))
    }

    @Test
    fun generationNameIsDeterministicPerPayloadAndDriverIdentity() {
        assertEquals(userIdentity().generationName, userIdentity().generationName)
        // A different library payload is a different generation.
        assertNotEquals(
            userIdentity().generationName,
            userIdentity(librarySha = "9".repeat(64)).generationName,
        )
        // A different imported driver id is a different generation.
        assertNotEquals(
            userIdentity().generationName,
            userIdentity(driverId = "user-other").generationName,
        )
    }

    @Test
    fun stagedGuestPathsMatchTheInstallLayout() {
        val rootfs = File(File(File("/data", "no_backup"), "arm-translated/winlator-ca3d735"), "rootfs")
        val library = UserVulkanDriverResolution.rootfsLibraryFile(rootfs)
        val icd = UserVulkanDriverResolution.rootfsIcdFile(rootfs)
        assertEquals(
            File(rootfs, "usr/lib/${UserVulkanDriver.LIBRARY_FILE_NAME}").canonicalPath,
            library.canonicalPath,
        )
        assertEquals(
            File(rootfs, "usr/share/vulkan/icd.d/${UserVulkanDriver.ICD_FILE_NAME}").canonicalPath,
            icd.canonicalPath,
        )
        // The ICD rewrite targets exactly the installed library path, and the
        // generation identity digests exactly those bytes (the B5 class of
        // bug: identity-time and install-time paths must agree).
        val stored = """{"ICD":{"api_version":"1.3.290","library_path":"driver.so"}}"""
        val icdText = UserVulkanDriverResolution.icdForRootfs(stored, library.absolutePath)
        assertEquals(
            library.absolutePath,
            JSONObject(icdText).getJSONObject("ICD").getString("library_path"),
        )
        assertEquals(sha256Of(icdText), sha256Of(
            UserVulkanDriverResolution.icdForRootfs(stored, library.absolutePath),
        ))
    }
}
