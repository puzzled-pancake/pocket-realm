package com.pocketrealm.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WineRuntimeGenerationIdentityTest {
    private val base = ArmGraphicsGenerationIdentity(
        runtimeBuildId = "runtime-a",
        rendererBuildId = "renderer-a",
        prefixSchema = 1,
        translatorId = "box64",
        rendererId = "dxvk",
        managedClientId = "wow-1.12.1-5875",
        managedClientGeneration = "11111111-1111-1111-1111-111111111111",
        managedClientManifestSha256 = "1".repeat(64),
        managedClientExecutableSha256 = "2".repeat(64),
        rendererPackageId = "box64-dxvk-2.4.1",
        rendererPackageBuildId = "dxvk-2.4.1-d3d9",
        rendererPackageDxvkVersion = "2.4.1",
        rendererPackageSystem32Sha256 = "3".repeat(64),
        rendererPackageSyswow64Sha256 = "4".repeat(64),
        vulkanDriverId = "turnip-26.1.0",
        vulkanDriverBuildId = "turnip-26.1.0",
        vulkanDriverLibrarySha256 = "5".repeat(64),
        vulkanDriverIcdSha256 = "6".repeat(64),
    )

    @Test
    fun physicalGenerationChangesForEveryCompatibilityComponent() {
        val gladio = base.copy(
            rendererBuildId = ArmClientRendererCatalog.GLADIO_BUILD_ID,
            rendererId = "opengl",
            rendererPackageId = null,
            rendererPackageBuildId = null,
            rendererPackageDxvkVersion = null,
            rendererPackageSystem32Sha256 = null,
            rendererPackageSyswow64Sha256 = null,
            vulkanDriverId = null,
            vulkanDriverBuildId = null,
            vulkanDriverLibrarySha256 = null,
            vulkanDriverIcdSha256 = null,
            gladioPackageId = ArmClientRendererCatalog.GLADIO_PACKAGE_ID,
            gladioPackageBuildId = ArmClientRendererCatalog.GLADIO_BUILD_ID,
            gladioClientSha256 = ArmClientRendererCatalog.GLADIO_CLIENT_SHA256,
            gladioServerBuildId = ArmClientRendererCatalog.GLADIO_SERVER_BUILD_ID,
        )
        val variants = listOf(
            base.copy(runtimeBuildId = "runtime-b"),
            base.copy(rendererBuildId = "renderer-b"),
            base.copy(prefixSchema = 2),
            base.copy(translatorId = "box64-next"),
            gladio,
            base.copy(managedClientId = "client-b"),
            base.copy(managedClientGeneration = "22222222-2222-2222-2222-222222222222"),
            base.copy(managedClientManifestSha256 = "5".repeat(64)),
            base.copy(managedClientExecutableSha256 = "6".repeat(64)),
            base.copy(rendererPackageId = "box64-dxvk-1.10.3"),
            base.copy(rendererPackageBuildId = "dxvk-1.10.3-d3d9"),
            base.copy(rendererPackageDxvkVersion = "1.10.3"),
            base.copy(rendererPackageSystem32Sha256 = "7".repeat(64)),
            base.copy(rendererPackageSyswow64Sha256 = "8".repeat(64)),
            base.copy(vulkanDriverId = "turnip-next"),
            base.copy(vulkanDriverBuildId = "turnip-next-build"),
            base.copy(vulkanDriverLibrarySha256 = "9".repeat(64)),
            base.copy(vulkanDriverIcdSha256 = "a".repeat(64)),
        )
        assertTrue(base.generationName.matches(Regex("g-[0-9a-f]{32}")))
        variants.forEach { variant ->
            assertNotEquals(base.generationName, variant.generationName)
        }
    }

    @Test
    fun manifestCompatibilityIsExactAndRejectsUnknownOrChangedFields() {
        val manifest = JSONObject().put("compatibility", base.toJson())
        assertTrue(base.matchesManifest(manifest))

        val changed = JSONObject(manifest.toString())
        changed.getJSONObject("compatibility").put("vulkan_driver_id", "other-driver")
        assertFalse(base.matchesManifest(changed))

        for (field in listOf(
            "renderer_package_system32_sha256",
            "renderer_package_syswow64_sha256",
        )) {
            val changedDll = JSONObject(manifest.toString())
            changedDll.getJSONObject("compatibility").put(field, "f".repeat(64))
            assertFalse(base.matchesManifest(changedDll))
        }

        val extended = JSONObject(manifest.toString())
        extended.getJSONObject("compatibility").put("unattested_field", true)
        assertFalse(base.matchesManifest(extended))
    }

    @Test
    fun gladioManifestIsExactAndContainsNoDxvkOrVulkanIdentity() {
        val gladio = base.copy(
            rendererBuildId = ArmClientRendererCatalog.GLADIO_BUILD_ID,
            rendererId = "opengl",
            rendererPackageId = null,
            rendererPackageBuildId = null,
            rendererPackageDxvkVersion = null,
            rendererPackageSystem32Sha256 = null,
            rendererPackageSyswow64Sha256 = null,
            vulkanDriverId = null,
            vulkanDriverBuildId = null,
            vulkanDriverLibrarySha256 = null,
            vulkanDriverIcdSha256 = null,
            gladioPackageId = ArmClientRendererCatalog.GLADIO_PACKAGE_ID,
            gladioPackageBuildId = ArmClientRendererCatalog.GLADIO_BUILD_ID,
            gladioClientSha256 = ArmClientRendererCatalog.GLADIO_CLIENT_SHA256,
            gladioServerBuildId = ArmClientRendererCatalog.GLADIO_SERVER_BUILD_ID,
        )
        val json = gladio.toJson()
        assertFalse(json.has("renderer_package_id"))
        assertFalse(json.has("vulkan_driver_id"))
        assertEquals(ArmClientRendererCatalog.GLADIO_CLIENT_SHA256,
            json.getString("gladio_client_sha256"))
        assertTrue(gladio.matchesManifest(JSONObject().put("compatibility", json)))

        val changed = JSONObject().put("compatibility", JSONObject(json.toString()))
        changed.getJSONObject("compatibility").put("gladio_server_build_id", "other")
        assertFalse(gladio.matchesManifest(changed))
    }

    @Test
    fun virglManifestIsExactAndCannotCrossUseGladioOrDxvk() {
        val virgl = base.copy(
            rendererBuildId = ArmClientRendererCatalog.VIRGL_BUILD_ID,
            rendererId = "virgl",
            rendererPackageId = null,
            rendererPackageBuildId = null,
            rendererPackageDxvkVersion = null,
            rendererPackageSystem32Sha256 = null,
            rendererPackageSyswow64Sha256 = null,
            vulkanDriverId = null,
            vulkanDriverBuildId = null,
            vulkanDriverLibrarySha256 = null,
            vulkanDriverIcdSha256 = null,
            virglPackageId = ArmClientRendererCatalog.VIRGL_PACKAGE_ID,
            virglPackageBuildId = ArmClientRendererCatalog.VIRGL_BUILD_ID,
            virglClientSha256 = ArmClientRendererCatalog.VIRGL_CLIENT_SHA256,
            virglServerBuildId = ArmClientRendererCatalog.VIRGL_SERVER_BUILD_ID,
        )
        val json = virgl.toJson()
        assertFalse(json.has("renderer_package_id"))
        assertFalse(json.has("vulkan_driver_id"))
        assertFalse(json.has("gladio_package_id"))
        assertEquals(
            ArmClientRendererCatalog.VIRGL_CLIENT_SHA256,
            json.getString("virgl_client_sha256"),
        )
        assertTrue(virgl.matchesManifest(JSONObject().put("compatibility", json)))
        assertNotEquals(base.generationName, virgl.generationName)

        val changed = JSONObject().put("compatibility", JSONObject(json.toString()))
        changed.getJSONObject("compatibility").put("virgl_server_build_id", "other")
        assertFalse(virgl.matchesManifest(changed))
    }

    @Test
    fun manifestCompatibilityRejectsJsonTypeSubstitution() {
        val schemaAsString = JSONObject().put("compatibility", base.toJson())
        schemaAsString.getJSONObject("compatibility").put("prefix_schema", "1")
        assertFalse(base.matchesManifest(schemaAsString))

        val schemaAsLong = JSONObject().put("compatibility", base.toJson())
        schemaAsLong.getJSONObject("compatibility").put("prefix_schema", 1L)
        assertFalse(base.matchesManifest(schemaAsLong))

        val versionAsNumber = JSONObject().put("compatibility", base.toJson())
        versionAsNumber.getJSONObject("compatibility")
            .put("renderer_package_dxvk_version", 2.4)
        assertFalse(base.matchesManifest(versionAsNumber))

        val digestAsBoolean = JSONObject().put("compatibility", base.toJson())
        digestAsBoolean.getJSONObject("compatibility")
            .put("renderer_package_system32_sha256", true)
        assertFalse(base.matchesManifest(digestAsBoolean))
    }

    @Test
    fun heldGenerationLeaseCannotBeAcquiredForPruning() {
        val workspace = Files.createTempDirectory("arm-generation-lease").toFile()
        val generations = File(workspace, "generations").apply { mkdirs() }
        val root = File(generations, "g-${"b".repeat(32)}")
        try {
            val sessionLease = ArmGraphicsGenerationLease.acquire(root)
            try {
                assertTrue(sessionLease.isHeld)
                assertNull(ArmGraphicsGenerationLease.tryAcquire(root))
            } finally {
                sessionLease.close()
            }
            val pruneLease = ArmGraphicsGenerationLease.tryAcquire(root)
            assertTrue(pruneLease?.isHeld == true)
            pruneLease?.close()
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun waiterAndPrunerCannotSplitAcrossRenamedLeaseInode() {
        val workspace = Files.createTempDirectory("arm-generation-race").toFile()
        val generations = File(workspace, "generations").apply { mkdirs() }
        val retired = File(workspace, "retired").apply { mkdirs() }
        val root = File(generations, "g-${"c".repeat(32)}")
        val first = ArmGraphicsGenerationLease.acquire(root)
        val executor = Executors.newFixedThreadPool(2)
        val waiterStarted = CountDownLatch(1)
        val waiterAcquired = CountDownLatch(1)
        val releaseWaiter = CountDownLatch(1)
        val waiterLease = AtomicReference<ArmGraphicsGenerationLease>()
        try {
            val waiter = executor.submit {
                waiterStarted.countDown()
                val lease = ArmGraphicsGenerationLease.acquire(root)
                waiterLease.set(lease)
                waiterAcquired.countDown()
                try {
                    releaseWaiter.await(5, TimeUnit.SECONDS)
                } finally {
                    lease.close()
                }
            }
            assertTrue(waiterStarted.await(5, TimeUnit.SECONDS))

            val heldTarget = File(retired, "held")
            assertFalse(ArmGraphicsGenerationLease.retireIfInactive(root, heldTarget))
            assertTrue(root.isDirectory)

            val raceGate = CountDownLatch(1)
            val raceTarget = File(retired, "race")
            val pruner = executor.submit<Boolean> {
                raceGate.await(5, TimeUnit.SECONDS)
                ArmGraphicsGenerationLease.retireIfInactive(root, raceTarget)
            }
            first.close()
            raceGate.countDown()

            assertTrue(waiterAcquired.await(5, TimeUnit.SECONDS))
            val oldGenerationRetired = pruner.get(5, TimeUnit.SECONDS)
            assertEquals(oldGenerationRetired, raceTarget.isDirectory)
            assertTrue("waiter must hold a recreated or retained current path", root.isDirectory)
            assertEquals(root.canonicalFile, waiterLease.get().generationRoot.canonicalFile)

            val waiterHeldTarget = File(retired, "waiter-held")
            assertFalse(ArmGraphicsGenerationLease.retireIfInactive(root, waiterHeldTarget))
            assertTrue(root.isDirectory)

            releaseWaiter.countDown()
            waiter.get(5, TimeUnit.SECONDS)
            val finalTarget = File(retired, "final")
            assertTrue(ArmGraphicsGenerationLease.retireIfInactive(root, finalTarget))
            assertFalse(root.exists())
            assertTrue(finalTarget.isDirectory)
        } finally {
            first.close()
            releaseWaiter.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            workspace.deleteRecursively()
        }
    }
}
