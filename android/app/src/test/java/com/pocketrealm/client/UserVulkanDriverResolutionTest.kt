package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The resolution seam where the user lane meets the launch chain.
 * Catalog semantics stay exactly as the characterization suite pinned them;
 * user-lane outcomes fail closed with exact reasons.
 */
class UserVulkanDriverResolutionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun registryWith(vararg drivers: UserVulkanDriver): UserVulkanDriverRegistry {
        val root = temp.newFolder("drivers-${System.nanoTime()}")
        val registry = UserVulkanDriverRegistry(root)
        drivers.forEach { driver ->
            val dir = File(root, driver.slug)
            dir.mkdirs()
            File(dir, UserVulkanDriver.LIBRARY_FILE_NAME).writeBytes(ByteArray(120))
            File(dir, UserVulkanDriver.ICD_FILE_NAME).writeText(
                """{"ICD":{"library_path":"driver.so","api_version":"1.3.290"}}""",
            )
            registry.update(registryEntryFor(root, driver))
        }
        return registry
    }

    /** update() requires the entry to exist; seed it through the file layout. */
    private fun registryEntryFor(root: File, driver: UserVulkanDriver): UserVulkanDriver {
        val file = File(root, "registry.json")
        val document = if (file.isFile) org.json.JSONObject(file.readText()) else null
        val drivers = document?.optJSONArray("drivers") ?: org.json.JSONArray()
        drivers.put(
            org.json.JSONObject()
                .put("id", driver.id)
                .put("label", driver.label)
                .put("libraryFileName", driver.libraryFileName)
                .put("sha256", driver.sha256)
                .putOpt("vulkanApiVersion", driver.vulkanApiVersion ?: org.json.JSONObject.NULL)
                .put("addedAt", driver.addedAt)
                .put("earlyCrashStreak", driver.earlyCrashStreak)
                .put("quarantined", driver.quarantined)
                .putOpt("quarantineReason", driver.quarantineReason ?: org.json.JSONObject.NULL),
        )
        file.writeText(org.json.JSONObject().put("schema", 1).put("drivers", drivers).toString())
        return driver
    }

    private fun userDriver(
        id: String = "user-turnip-26-3",
        quarantined: Boolean = false,
        reason: String? = null,
    ) = UserVulkanDriver(
        id = id,
        label = "Turnip 26.3",
        libraryFileName = "driver.so",
        sha256 = "a".repeat(64),
        vulkanApiVersion = "1.3.290",
        addedAt = 42L,
        quarantined = quarantined,
        quarantineReason = reason,
    )

    @Test
    fun catalogIdsResolveExactlyAsTheClosedCatalogDoes() {
        val registry = registryWith()
        for (id in listOf(VulkanDriverCatalog.SYSTEM_DEFAULT, VulkanDriverCatalog.TURNIP_26_1)) {
            val resolved = UserVulkanDriverResolution.requireSessionDriver(
                id, registry, allowUserDrivers = false, adrenoGpu = true,
            ) as UserVulkanDriverResolution.SessionDriver.CatalogDriver
            assertEquals(id, resolved.driver.id)
        }
    }

    @Test
    fun unknownNonUserIdFailsClosedWithTheCatalogMessage() {
        val registry = registryWith()
        val failure = runCatching {
            UserVulkanDriverResolution.requireSessionDriver(
                "future-driver", registry, allowUserDrivers = true,
                adrenoGpu = true)
        }.exceptionOrNull()
        assertEquals("unknown Vulkan driver package: future-driver", failure?.message)
        val missing = runCatching {
            UserVulkanDriverResolution.requireSessionDriver(null, registry, allowUserDrivers = true,
                adrenoGpu = true)
        }.exceptionOrNull()
        assertEquals("ARM DXVK requires an explicit Vulkan driver package", missing?.message)
    }

    @Test
    fun userIdBehindADisabledLaneFailsWithTheExactReason() {
        val registry = registryWith(userDriver())
        val failure = runCatching {
            UserVulkanDriverResolution.requireSessionDriver(
                "user-turnip-26-3", registry, allowUserDrivers = false, adrenoGpu = true,
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(
            "Imported Vulkan drivers are disabled; enable 'Allow imported drivers' " +
                "in Settings to use the selected driver.",
            failure?.message,
        )
    }

    @Test
    fun registeredUserDriverResolvesAsATurnipSessionDriver() {
        val registry = registryWith(userDriver())
        val resolved = UserVulkanDriverResolution.requireSessionDriver(
            "user-turnip-26-3", registry, allowUserDrivers = true,
                adrenoGpu = true) as UserVulkanDriverResolution.SessionDriver.UserDriver
        assertEquals("user-turnip-26-3", resolved.id)
        assertEquals(VulkanDriverKind.TURNIP, resolved.kind)
        assertEquals(UserVulkanDriver.ICD_FILE_NAME, resolved.icdFileName)
        assertEquals("Turnip 26.3", resolved.driver.label)
    }

    @Test
    fun unregisteredAndQuarantinedUserDriversFailWithExactReasons() {
        val registry = registryWith(
            userDriver(id = "user-gone"),
            userDriver(id = "user-bad", quarantined = true, reason = "quarantined after 2 early crashes"),
        )
        val gone = runCatching {
            UserVulkanDriverResolution.requireSessionDriver(
                "user-missing", registry, allowUserDrivers = true,
                adrenoGpu = true)
        }.exceptionOrNull()
        assertEquals(
            "Imported Vulkan driver user-missing is not registered; " +
                "it may have been deleted. Choose another driver.",
            gone?.message,
        )
        val quarantined = runCatching {
            UserVulkanDriverResolution.requireSessionDriver(
                "user-bad", registry, allowUserDrivers = true,
                adrenoGpu = true)
        }.exceptionOrNull()
        assertEquals(
            "Imported driver Turnip 26.3 is quarantined: quarantined after 2 early crashes",
            quarantined?.message,
        )
    }

    @Test
    fun missingRegistryFileMeansUnregisteredNotACrash() {
        val emptyRoot = temp.newFolder("empty-${System.nanoTime()}")
        val failure = runCatching {
            UserVulkanDriverResolution.requireSessionDriver(
                "user-any", UserVulkanDriverRegistry(emptyRoot),
                allowUserDrivers = true, adrenoGpu = true,
            )
        }.exceptionOrNull()
        assertTrue(failure?.message!!.contains("is not registered"))
    }

    @Test
    fun userTurnipDriversAreAdrenoOnlyLikePackagedTurnip() {
        val registry = registryWith(userDriver())
        val failure = runCatching {
            UserVulkanDriverResolution.requireSessionDriver(
                "user-turnip-26-3", registry, allowUserDrivers = true, adrenoGpu = false,
            )
        }.exceptionOrNull()
        assertTrue(failure?.message!!.contains("Adreno-only"))
        assertTrue(failure.message!!.contains("system Vortek bridge"))
    }

    @Test
    fun displayGateChecksTheRegistryButNotTheToggle() {
        val registry = registryWith(userDriver())
        val resolved = UserVulkanDriverResolution.requireDisplayDriver(
            "user-turnip-26-3", registry,
        ) as UserVulkanDriverResolution.SessionDriver.UserDriver
        assertEquals("user-turnip-26-3", resolved.id)
        val failure = runCatching {
            UserVulkanDriverResolution.requireDisplayDriver("user-missing", registry)
        }.exceptionOrNull()
        assertTrue(failure?.message!!.contains("is not registered"))
    }

    @Test
    fun kindOfMapsUserDriversToTurnipAndUnknownsToNull() {
        assertEquals(VulkanDriverKind.TURNIP, UserVulkanDriverResolution.kindOf("user-x"))
        assertEquals(
            VulkanDriverKind.SYSTEM,
            UserVulkanDriverResolution.kindOf(VulkanDriverCatalog.SYSTEM_DEFAULT),
        )
        assertEquals(
            VulkanDriverKind.TURNIP,
            UserVulkanDriverResolution.kindOf(VulkanDriverCatalog.TURNIP_26_1),
        )
        assertNull(UserVulkanDriverResolution.kindOf("future-driver"))
        assertNull(UserVulkanDriverResolution.kindOf(null))
    }

    @Test
    fun pairPreflightKeepsUserIdsOffTheCatalogsUnknownPackageNotice() {
        val dxvk = RendererPackageCatalog.compatible(ArmTranslationBackend.BOX64).first()
        // Adreno + user driver: a user Turnip ICD never uses Vortek, so it
        // pairs like the packaged Turnip package — the Home/LAN launch gate
        // and the Settings DXVK chips must stay usable.
        val adreno = UserVulkanDriverResolution.availabilityForPairPreflight(
            "user-mine", dxvk.id, adrenoGpu = true,
        )
        assertTrue(adreno.available)

        // Non-Adreno carries the seam's own exact reason, never the catalog's
        // unknown-package notice for a driver that is in fact registered.
        val nonAdreno = UserVulkanDriverResolution.availabilityForPairPreflight(
            "user-mine", dxvk.id, adrenoGpu = false,
        )
        assertFalse(nonAdreno.available)
        assertEquals(UserVulkanDriverResolution.ADRENO_ONLY_REASON, nonAdreno.reason)

        // Catalog ids keep their closed semantics untouched (I1): the system
        // bridge still needs a capability probe and unknown ids still fail
        // closed with the catalog's own wording.
        val unprobedSystem = UserVulkanDriverResolution.availabilityForPairPreflight(
            VulkanDriverCatalog.SYSTEM_DEFAULT, dxvk.id, adrenoGpu = false, system = null,
        )
        assertFalse(unprobedSystem.available)
        assertTrue(unprobedSystem.reason.contains("capability probe"))
        val probedSystem = UserVulkanDriverResolution.availabilityForPairPreflight(
            VulkanDriverCatalog.SYSTEM_DEFAULT, dxvk.id, adrenoGpu = false,
            system = SystemVulkanCapabilities(
                apiVersion = (1 shl 22) or (3 shl 12),
                nativeTextureCompressionBC = true,
                deviceExtensions = emptySet(),
            ),
        )
        assertTrue(probedSystem.available)
        val unknown = UserVulkanDriverResolution.availabilityForPairPreflight(
            "future-driver", dxvk.id, adrenoGpu = true,
        )
        assertFalse(unknown.available)
        assertEquals("Unknown Vulkan driver package: future-driver.", unknown.reason)
    }

    @Test
    fun icdForRootfsRewritesLibraryPathDeterministically() {
        val stored = """{"ICD":{"api_version":"1.3.290","library_path":"libvulkan_freedreno.so"},"file_format_version":"1.0.0"}"""
        val first = UserVulkanDriverResolution.icdForRootfs(stored, "/rfs/usr/lib/driver.so")
        val second = UserVulkanDriverResolution.icdForRootfs(stored, "/rfs/usr/lib/driver.so")
        assertEquals(first, second)
        val icd = org.json.JSONObject(first).getJSONObject("ICD")
        assertEquals("/rfs/usr/lib/driver.so", icd.getString("library_path"))
        assertEquals("1.3.290", icd.getString("api_version"))
        assertEquals("64", icd.getString("library_arch"))
        // An unversioned manifest stays unversioned rather than inventing one.
        val bare = UserVulkanDriverResolution.icdForRootfs(
            """{"ICD":{"library_path":"x.so"}}""", "/rfs/usr/lib/driver.so",
        )
        assertTrue(!org.json.JSONObject(bare).getJSONObject("ICD").has("api_version"))
    }

    @Test
    fun icdForRootfsOmitsACoercedNullApiVersion() {
        // Android's org.json would surface a JSON-null api_version as the
        // literal string "null"; the rewrite must omit it instead of
        // handing the Vulkan loader a garbage version.
        val rewritten = UserVulkanDriverResolution.icdForRootfs(
            """{"ICD":{"library_path":"x.so","api_version":"null"}}""",
            "/rfs/usr/lib/driver.so",
        )
        assertTrue(!org.json.JSONObject(rewritten).getJSONObject("ICD").has("api_version"))
    }

    @Test
    fun userDriverBuildIdFlowsThroughArmRendererBuildId() {
        val dxvk = RendererPackageCatalog.compatible(ArmTranslationBackend.BOX64).first()
        val userBuild = ClientRuntimeContract.armRendererBuildId(
            ArmTranslationBackend.BOX64, "dxvk", dxvk.id, "user-mine",
        )
        assertEquals("user-mine-${dxvk.buildId}", userBuild)
        val failure = runCatching {
            ClientRuntimeContract.armRendererBuildId(
                ArmTranslationBackend.BOX64, "dxvk", dxvk.id, "future-driver",
            )
        }.exceptionOrNull()
        assertEquals("unknown Vulkan driver package: future-driver", failure?.message)
    }

    @Test
    fun userLaneEnvironmentAddsTheDriverFilesAliasOnlyForTheUserLane() {
        val rootfs = File("/pocket-rootfs")
        val env = ArmSessionEnvironment.driverEnv(
            "dxvk", "user-mine", rootfs, "Generic Phone",
            userIcdFileName = UserVulkanDriver.ICD_FILE_NAME,
        )
        val icd = File(rootfs, "usr/share/vulkan/icd.d/${UserVulkanDriver.ICD_FILE_NAME}").absolutePath
        assertEquals(
            listOf(
                "VK_ICD_FILENAMES=$icd",
                "VK_DRIVER_FILES=$icd",
                "MESA_VK_WSI_PRESENT_MODE=mailbox",
                "MESA_VK_WSI_USE_HWBUF=1",
                "TU_DEBUG=noconform",
            ),
            env,
        )
        val rp6 = ArmSessionEnvironment.driverEnv(
            "dxvk", "user-mine", rootfs, "Retroid Pocket 6",
            userIcdFileName = UserVulkanDriver.ICD_FILE_NAME,
        )
        assertTrue(rp6.contains("TU_DEBUG=noconform,sysmem"))
        // Packaged lanes never emit the alias (the characterization pin,
        // restated here for the lane boundary).
        for (id in listOf(VulkanDriverCatalog.SYSTEM_DEFAULT, VulkanDriverCatalog.TURNIP_26_1)) {
            assertTrue(
                ArmSessionEnvironment.driverEnv("dxvk", id, rootfs, "Generic Phone")
                    .none { it.startsWith("VK_DRIVER_FILES=") },
            )
        }
    }
}
