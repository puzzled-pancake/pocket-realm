package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase B: offline validation + registry semantics for user-imported Turnip
 * drivers. Every fixture is synthetic — no real driver binaries, no device.
 */
class UserVulkanDriverValidatorTest {

    // --- ELF fixture builder -------------------------------------------------

    private fun put16(out: ByteArray, at: Int, v: Int) {
        out[at] = (v and 0xff).toByte(); out[at + 1] = ((v ushr 8) and 0xff).toByte()
    }

    private fun put32(out: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) out[at + i] = ((v ushr (8 * i)) and 0xff).toByte()
    }

    private fun put64(out: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) out[at + i] = ((v ushr (8 * i)) and 0xff).toByte()
    }

    private fun elf64(
        machine: Int = UserVulkanDriverValidator.EM_AARCH64,
        loads: List<Long> = listOf(0x4000),
        phoff: Long = 64,
    ): ByteArray {
        val phentsize = 56
        val phnum = loads.size
        val out = ByteArray(64 + phnum * phentsize)
        out[0] = 0x7f; out[1] = 'E'.code.toByte(); out[2] = 'L'.code.toByte(); out[3] = 'F'.code.toByte()
        out[4] = 2; out[5] = 1; out[6] = 1
        put16(out, 16, 3)
        put16(out, 18, machine)
        put32(out, 20, 1)
        put64(out, 32, phoff)
        put16(out, 52, 64)
        put16(out, 54, phentsize)
        put16(out, 56, phnum)
        loads.forEachIndexed { i, align ->
            val h = (phoff + i * phentsize).toInt()
            put32(out, h, 1) // PT_LOAD
            put32(out, h + 4, 5)
            put64(out, h + 8, 0)
            put64(out, h + 16, 0x1000)
            put64(out, h + 24, 0x1000)
            put64(out, h + 32, 0x1000)
            put64(out, h + 40, 0x1000)
            put64(out, h + 48, align)
        }
        return out
    }

    @Test
    fun valid16KbAlignedAarch64LibraryIsAccepted() {
        assertNull(UserVulkanDriverValidator.elfRejection(elf64()))
        assertNull(UserVulkanDriverValidator.elfRejection(elf64(loads = listOf(0x4000, 0x10000))))
    }

    @Test
    fun fourKbAlignedLibraryIsRejectedWithTheExactRemediation() {
        val reason = UserVulkanDriverValidator.elfRejection(elf64(loads = listOf(0x1000)))
        assertNotNull(reason)
        assertTrue(reason!!.contains("p_align=0x1000"))
        assertTrue(
            reason.contains(
                "the RP6 kernel uses 16 KB pages and this build will crash on load. " +
                    "Use a build made with `-Wl,-z,max-page-size=0x4000`.",
            ),
        )
    }

    @Test
    fun oneBadSegmentAmongGoodOnesIsStillRejected() {
        val reason = UserVulkanDriverValidator.elfRejection(
            elf64(loads = listOf(0x4000, 0x2000, 0x10000)),
        )
        assertTrue(reason!!.contains("p_align=0x2000"))
    }

    @Test
    fun x86_64MachineIsRejected() {
        val reason = UserVulkanDriverValidator.elfRejection(elf64(machine = 62))
        assertTrue(reason!!.contains("62"))
        assertTrue(reason.contains("aarch64"))
    }

    @Test
    fun truncatedFileIsRejectedNotThrown() {
        val reason = UserVulkanDriverValidator.elfRejection(ByteArray(32))
        assertTrue(reason!!.contains("too short"))
    }

    @Test
    fun wrongMagicIsRejected() {
        val bytes = elf64()
        bytes[1] = 'X'.code.toByte()
        assertTrue(UserVulkanDriverValidator.elfRejection(bytes)!!.contains("ELF magic"))
    }

    @Test
    fun elf32ClassIsRejected() {
        val bytes = elf64()
        bytes[4] = 1
        assertTrue(UserVulkanDriverValidator.elfRejection(bytes)!!.contains("64-bit"))
    }

    @Test
    fun missingProgramHeadersFailClosed() {
        val bytes = elf64()
        put16(bytes, 56, 0)
        assertTrue(
            UserVulkanDriverValidator.elfRejection(bytes)!!.contains("no program headers"),
        )
    }

    @Test
    fun truncatedProgramHeaderTableIsRejected() {
        val bytes = elf64(loads = listOf(0x4000, 0x4000, 0x4000))
        val chopped = bytes.copyOf(64 + 2 * 56 + 10)
        assertTrue(UserVulkanDriverValidator.elfRejection(chopped)!!.contains("truncated"))
    }

    @Test
    fun overflowingProgramHeaderOffsetIsRejectedNeverThrown() {
        // A crafted e_phoff near Long.MAX_VALUE must not wrap the bounds
        // check into an ArrayIndexOutOfBounds — it is a rejection.
        val bytes = elf64()
        put64(bytes, 32, Long.MAX_VALUE)
        val reason = UserVulkanDriverValidator.elfRejection(bytes)
        assertTrue(reason!!.contains("truncated"))
    }

    @Test
    fun programHeadersPastTheLoadedPrefixFailClosed() {
        // e_phoff inside the file but past the 4 MiB inspectable prefix:
        // the table exists on disk yet is not inspectable — rejected, and
        // never accepted uninspected.
        val bytes = elf64()
        put64(bytes, 32, 5_000_000)
        val reason = UserVulkanDriverValidator.elfRejection(bytes, fileSize = 8_000_000)
        assertTrue(reason!!.contains("truncated"))
    }

    // --- ICD manifest ---------------------------------------------------------

    @Test
    fun icdRequiresParsableJsonWithALibraryPath() {
        assertTrue(
            UserVulkanDriverValidator.validateIcd("not json")
                .let { it is UserVulkanDriverValidator.IcdOutcome.Rejected },
        )
        assertTrue(
            UserVulkanDriverValidator.validateIcd("""{"ICD":{"api_version":"1.3.1"}}""")
                .let { it is UserVulkanDriverValidator.IcdOutcome.Rejected },
        )
        val accepted = UserVulkanDriverValidator.validateIcd(
            """{"ICD":{"library_path":"/data/local/tmp/libvulkan_freedreno.so","api_version":"1.3.290"},"file_format_version":"1.0.1"}""",
        ) as UserVulkanDriverValidator.IcdOutcome.Accepted
        assertEquals("1.3.290", accepted.apiVersion)
        assertNull(accepted.warning)
    }

    @Test
    fun vulkanApiBelow13IsWarnOnlyNeverARejection() {
        val accepted = UserVulkanDriverValidator.validateIcd(
            """{"ICD":{"library_path":"libvulkan_freedreno.so","api_version":"1.1.262"}}""",
        ) as UserVulkanDriverValidator.IcdOutcome.Accepted
        assertEquals("1.1.262", accepted.apiVersion)
        assertTrue(accepted.warning!!.contains("1.1.262"))
        assertTrue(accepted.warning.contains("1.3"))
        assertTrue(accepted.warning.contains("1.10.3"))
        // Unversioned manifests stay accepted without a warning.
        val bare = UserVulkanDriverValidator.validateIcd(
            """{"ICD":{"library_path":"libvulkan_freedreno.so"}}""",
        ) as UserVulkanDriverValidator.IcdOutcome.Accepted
        assertNull(bare.apiVersion)
        assertNull(bare.warning)
    }

    // --- AdrenoTools meta.json -------------------------------------------------

    @Test
    fun metaHarvestsLabelAndDriverVersionWithTheVulkanPrefixStripped() {
        val accepted = UserVulkanDriverValidator.validateMeta(
            """{"schemaVersion":1,"name":"Mesa Turnip driver v26.0.0 - R8","author":"KIMCHI",""" +
                """"packageVersion":"1","vendor":"Mesa","driverVersion":"Vulkan 1.4.335","minApi":27,""" +
                """"libraryName":"vulkan.ad07xx.so"}""",
        ) as UserVulkanDriverValidator.MetaOutcome.Accepted
        assertEquals("Mesa Turnip driver v26.0.0 - R8", accepted.label)
        assertEquals("1.4.335", accepted.apiVersion)
    }

    @Test
    fun metaIsDisplayMetadataOnlyAndJunkFieldsNeverReject() {
        val accepted = UserVulkanDriverValidator.validateMeta(
            """{"driverVersion":"1.3.290","libraryName":"mismatch.so","minApi":99}""",
        ) as UserVulkanDriverValidator.MetaOutcome.Accepted
        assertNull(accepted.label)
        assertEquals("1.3.290", accepted.apiVersion)

        val bare = UserVulkanDriverValidator.validateMeta("""{}""")
            as UserVulkanDriverValidator.MetaOutcome.Accepted
        assertNull(bare.label)
        assertNull(bare.apiVersion)
    }

    @Test
    fun malformedMetaIsRejectedWithTheExactReason() {
        val rejected = UserVulkanDriverValidator.validateMeta("{ not json")
            as UserVulkanDriverValidator.MetaOutcome.Rejected
        assertTrue(rejected.reason.contains("meta.json could not be parsed"))
    }

    @Test
    fun androidNullCoercionCannotAlterValidationVerdicts() {
        // The Android-coerced form of a JSON null is the literal string
        // "null" (the desktop artifact returns "" instead) — the guard must
        // keep validation verdicts identical across platforms.
        val coercedLibraryPath = UserVulkanDriverValidator.validateIcd(
            """{"ICD":{"library_path":"null"}}""",
        )
        assertTrue(coercedLibraryPath is UserVulkanDriverValidator.IcdOutcome.Rejected)
        val jsonNullLibraryPath = UserVulkanDriverValidator.validateIcd(
            """{"ICD":{"library_path":null,"api_version":"1.3.1"}}""",
        )
        assertTrue(jsonNullLibraryPath is UserVulkanDriverValidator.IcdOutcome.Rejected)

        // A null api_version must stay absent, never render as "null".
        val coercedVersion = UserVulkanDriverValidator.validateIcd(
            """{"ICD":{"library_path":"x.so","api_version":"null"}}""",
        ) as UserVulkanDriverValidator.IcdOutcome.Accepted
        assertNull(coercedVersion.apiVersion)

        // meta.json: a null name/driverVersion must not become "null".
        val coercedName = UserVulkanDriverValidator.validateMeta(
            """{"name":"null","driverVersion":null}""",
        ) as UserVulkanDriverValidator.MetaOutcome.Accepted
        assertNull(coercedName.label)
        assertNull(coercedName.apiVersion)
    }
}

class UserVulkanDriverRegistryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun newRegistry(): Pair<UserVulkanDriverRegistry, File> {
        val root = temp.newFolder("drivers-${System.nanoTime()}")
        return UserVulkanDriverRegistry(root) to root
    }

    private fun put16(out: ByteArray, at: Int, v: Int) {
        out[at] = (v and 0xff).toByte(); out[at + 1] = ((v ushr 8) and 0xff).toByte()
    }

    private fun put32(out: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) out[at + i] = ((v ushr (8 * i)) and 0xff).toByte()
    }

    private fun put64(out: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) out[at + i] = ((v ushr (8 * i)) and 0xff).toByte()
    }

    private fun elf64(align: Long = 0x4000): ByteArray {
        val out = ByteArray(64 + 56)
        out[0] = 0x7f; out[1] = 'E'.code.toByte(); out[2] = 'L'.code.toByte(); out[3] = 'F'.code.toByte()
        out[4] = 2; out[5] = 1; out[6] = 1
        put16(out, 16, 3)
        put16(out, 18, UserVulkanDriverValidator.EM_AARCH64)
        put32(out, 20, 1)
        put64(out, 32, 64)
        put16(out, 52, 64)
        put16(out, 54, 56)
        put16(out, 56, 1)
        put32(out, 64, 1)
        put64(out, 64 + 48, align)
        return out
    }

    private fun soFile(align: Long = 0x4000): File {
        val file = temp.newFile("payload-${System.nanoTime()}.so")
        file.writeBytes(elf64(align))
        return file
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val file = temp.newFile("payload-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun icdJson(version: String? = "1.3.290"): ByteArray =
        if (version == null) {
            """{"ICD":{"library_path":"libvulkan_freedreno.so"}}""".toByteArray()
        } else {
            """{"ICD":{"library_path":"libvulkan_freedreno.so","api_version":"$version"},"file_format_version":"1.0.1"}""".toByteArray()
        }

    @Test
    fun bareSoImportStagesPayloadAndSynthesizesTheIcd() {
        val (registry, root) = newRegistry()
        val result = registry.import("Turnip 26.3 mesa CI", soFile())
        val imported = result as UserVulkanDriverImport.Imported
        assertEquals("user-turnip-26-3-mesa-ci", imported.driver.id)
        assertEquals("Turnip 26.3 mesa CI", imported.driver.label)
        assertNull(imported.warning)
        val dir = File(root, "turnip-26-3-mesa-ci")
        assertTrue(File(dir, "driver.so").isFile)
        assertTrue(File(dir, "icd.json").isFile)
        assertEquals(
            "driver.so",
            org.json.JSONObject(File(dir, "icd.json").readText())
                .getJSONObject("ICD").getString("library_path"),
        )
        assertEquals(1, registry.list().size)
        assertEquals(imported.driver, registry.find(imported.driver.id))
        // The recorded sha256 is the staged library's digest.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(File(dir, "driver.so").readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(digest, imported.driver.sha256)
    }

    @Test
    fun registrySurvivesRestartAsPlainDiskState() {
        val (registry, root) = newRegistry()
        registry.import("First", soFile())
        registry.import("Second", soFile())
        val reloaded = UserVulkanDriverRegistry(root)
        assertEquals(registry.list().map { it.id }, reloaded.list().map { it.id })
        assertEquals(registry.list().map { it.sha256 }, reloaded.list().map { it.sha256 })
        assertEquals(2, reloaded.list().size)
        val schema = org.json.JSONObject(File(root, "registry.json").readText())
        assertEquals(UserVulkanDriverRegistry.SCHEMA, schema.getInt("schema"))
    }

    @Test
    fun fourKbAlignedImportIsRejectedAndLeavesNoPartialEntry() {
        val (registry, root) = newRegistry()
        val result = registry.import("Bad alignment", soFile(align = 0x1000))
        val reason = (result as UserVulkanDriverImport.Rejected).reason
        assertTrue(reason.contains("16 KB pages"))
        assertTrue(reason.contains("`-Wl,-z,max-page-size=0x4000`"))
        assertEquals(0, registry.list().size)
        val driverDirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
        assertTrue(driverDirs.isNullOrEmpty())
        assertFalse(File(root, "registry.json").exists())
    }

    @Test
    fun malformedIcdInAZipIsRejectedAndLeavesNoPartialEntry() {
        val (registry, root) = newRegistry()
        val zip = zipOf(
            "libvulkan_freedreno.so" to elf64(),
            "freedreno_icd.aarch64.json" to """{"ICD":{}}""".toByteArray(),
        )
        val result = registry.import("Bad ICD", zip)
        assertTrue((result as UserVulkanDriverImport.Rejected).reason.contains("library_path"))
        assertEquals(0, registry.list().size)
        assertTrue(root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.isNullOrEmpty())
        assertFalse(File(root, "registry.json").exists())
    }

    @Test
    fun zipImportKeepsTheProvidedIcdAndItsApiVersion() {
        val (registry, _) = newRegistry()
        val zip = zipOf(
            "turnip/libvulkan_freedreno.so" to elf64(),
            "turnip/freedreno_icd.aarch64.json" to icdJson("1.1.262"),
        )
        val imported = registry.import("Nested", zip) as UserVulkanDriverImport.Imported
        assertEquals("1.1.262", imported.driver.vulkanApiVersion)
        assertTrue(imported.warning!!.contains("1.1.262"))
    }

    @Test
    fun archiveShapeViolationsCarryExactReasons() {
        val (registry, _) = newRegistry()
        val twoLibraries = registry.import(
            "Two", zipOf("a.so" to elf64(), "b.so" to elf64()),
        ) as UserVulkanDriverImport.Rejected
        assertTrue(twoLibraries.reason.contains("exactly one .so library"))
        assertTrue(twoLibraries.reason.contains("a.so, b.so"))

        val noLibrary = registry.import(
            "Empty", zipOf("readme.txt" to "hi".toByteArray()),
        ) as UserVulkanDriverImport.Rejected
        assertTrue(noLibrary.reason.contains("no .so Vulkan driver library"))

        val extra = registry.import(
            "Extra", zipOf("lib.so" to elf64(), "readme.md" to "hi".toByteArray()),
        ) as UserVulkanDriverImport.Rejected
        assertTrue(extra.reason.contains("unexpected: readme.md"))
        // The allowed-set wording must name the meta.json carve-out (C9).
        assertTrue(extra.reason.contains("optional AdrenoTools meta.json"))

        val traversal = registry.import(
            "Evil", zipOf("../evil.so" to elf64()),
        ) as UserVulkanDriverImport.Rejected
        assertTrue(traversal.reason.contains("unsafe entry path"))

        assertEquals(0, registry.list().size)
    }

    @Test
    fun sizeCapIsEnforcedWithTheExactReason() {
        val (registry, _) = newRegistry()
        val big = soFile()
        val result = registry.import("Big", big, maxImportBytes = 64)
        val reason = (result as UserVulkanDriverImport.Rejected).reason
        assertTrue(reason.contains("import cap"))
        assertEquals(0, registry.list().size)
    }

    @Test
    fun slugSanitizationCannotProducePathsOrCatalogIds() {
        assertEquals("turnip-26-3", UserVulkanDriverRegistry.slugify("Turnip 26.3"))
        assertEquals("evil", UserVulkanDriverRegistry.slugify("../EVIL"))
        assertEquals("driver", UserVulkanDriverRegistry.slugify("!!/..\\??"))
        assertEquals("a-b", UserVulkanDriverRegistry.slugify("a   b"))
        assertTrue(UserVulkanDriverRegistry.slugify("x".repeat(200)).length <= 48)
        // Id namespace: user ids can never be catalog ids.
        assertTrue(UserVulkanDriver.isUserId("user-turnip"))
        assertFalse(UserVulkanDriver.isUserId(VulkanDriverCatalog.TURNIP_26_1))
        assertFalse(UserVulkanDriver.isUserId(VulkanDriverCatalog.SYSTEM_DEFAULT))
        assertFalse(UserVulkanDriver.isUserId(null))
    }

    @Test
    fun longLabelWhoseSlugWouldEndInAHyphenStillImports() {
        val (registry, _) = newRegistry()
        // Slugs to 47 t's + "-b" (49 chars); take(48) would leave a trailing
        // hyphen, which the id regex forbids — the trim must rescue it.
        val label = "t".repeat(47) + " b"
        val imported = registry.import(label, soFile()) as UserVulkanDriverImport.Imported
        assertEquals("user-" + "t".repeat(47), imported.driver.id)
    }

    @Test
    fun duplicateLabelsGetDistinctSlugs() {
        val (registry, _) = newRegistry()
        val first = registry.import("Same name", soFile()) as UserVulkanDriverImport.Imported
        val second = registry.import("Same name", soFile()) as UserVulkanDriverImport.Imported
        assertTrue(first.driver.id != second.driver.id)
        assertEquals(setOf(first.driver.id, second.driver.id), registry.list().map { it.id }.toSet())
    }

    @Test
    fun reImportingQuarantinedLibraryBytesIsRejectedWithTheExactReason() {
        val (registry, _) = newRegistry()
        val imported = registry.import("Crashy", soFile()) as UserVulkanDriverImport.Imported
        registry.update(
            imported.driver.copy(
                earlyCrashStreak = 2,
                quarantined = true,
                quarantineReason = "quarantined after 2 early crashes",
            ),
        )
        // soFile() writes byte-identical content: a re-import must not
        // resurrect the quarantined artifact under a fresh id with a clean
        // crash streak.
        val result = registry.import("Crashy again", soFile())
        val reason = (result as UserVulkanDriverImport.Rejected).reason
        assertTrue(reason.contains("byte-identical"))
        assertTrue(reason.contains("\"Crashy\""))
        assertTrue(reason.contains("quarantined after 2 early crashes"))
        assertEquals(1, registry.list().size)
    }

    @Test
    fun interruptedImportLeftoversAreReclaimedOnTheNextImport() {
        val (registry, root) = newRegistry()
        val hour = 60L * 60L * 1000
        val staleStaging = File(root, ".incoming-deadbeef").apply { mkdirs() }
        File(staleStaging, "driver.so").writeBytes(ByteArray(16))
        staleStaging.setLastModified(System.currentTimeMillis() - 2 * hour)
        val liveStaging = File(root, ".incoming-live").apply { mkdirs() }
        val orphanPayload = File(root, "orphaned-slug").apply { mkdirs() }
        File(orphanPayload, "driver.so").writeBytes(ByteArray(16))
        val staleTemp = File(root, ".registry.json.tmp-stale").apply { writeText("{}") }
        // Not slug-shaped and not lane-owned: reconcile must leave it alone.
        val foreignDir = File(root, "Not.A.Slug").apply { mkdirs() }

        val imported = registry.import("Fresh", soFile()) as UserVulkanDriverImport.Imported

        assertFalse(staleStaging.exists())
        assertFalse(orphanPayload.exists())
        assertFalse(staleTemp.exists())
        // A concurrent import's young staging directory is spared.
        assertTrue(liveStaging.exists())
        assertTrue(foreignDir.exists())
        assertTrue(File(root, imported.driver.slug).isDirectory)
        assertEquals(1, registry.list().size)
    }

    @Test
    fun reconcileSparesThePayloadDirectoriesOfRegisteredDrivers() {
        val (registry, root) = newRegistry()
        // The second import's reconcile must not touch the first driver's
        // payload: the knownSlugs membership check is the only difference
        // between reclaiming orphans and deleting every driver per import.
        val first = registry.import("Keep me", soFile()) as UserVulkanDriverImport.Imported
        registry.import("Another", soFile()) as UserVulkanDriverImport.Imported
        val kept = File(root, first.driver.slug)
        assertTrue(File(kept, "driver.so").isFile)
        assertTrue(File(kept, "icd.json").isFile)
        assertEquals(2, registry.list().size)
    }

    @Test
    fun resetClearsAnUnreadableRegistryForAFreshStart() {
        val (registry, root) = newRegistry()
        root.mkdirs()
        File(root, "registry.json").writeText("""{"schema":1,"drivers":[{""")
        File(root, "leftover-slug").apply { mkdirs() }
        File(File(root, "leftover-slug"), "driver.so").writeBytes(ByteArray(8))
        File(root, ".registry.json.tmp-x").writeText("{}")
        File(root, "session-record.json").writeText("{}")

        assertTrue(runCatching { registry.list() }.isFailure)
        registry.reset()

        // The empty registry is durably published, not merely deleted: a
        // torn reset must never resurrect the old entries as ghosts.
        val published = org.json.JSONObject(File(root, "registry.json").readText())
        assertEquals(UserVulkanDriverRegistry.SCHEMA, published.getInt("schema"))
        assertEquals(0, published.getJSONArray("drivers").length())

        assertEquals(0, registry.list().size)
        assertFalse(File(root, "leftover-slug").exists())
        assertFalse(File(root, ".registry.json.tmp-x").exists())
        // Diagnostics co-tenants survive the lane reset.
        assertTrue(File(root, "session-record.json").isFile)
        // The lane is importable again.
        val imported = registry.import("Fresh start", soFile())
        assertTrue(imported is UserVulkanDriverImport.Imported)
        assertEquals(1, registry.list().size)
    }

    @Test
    fun removeDeletesPayloadAndRegistryEntry() {
        val (registry, root) = newRegistry()
        val imported = registry.import("Gone", soFile()) as UserVulkanDriverImport.Imported
        registry.remove(imported.driver.id)
        assertEquals(0, registry.list().size)
        assertFalse(File(root, "gone").exists())
        assertTrue(runCatching { registry.remove(imported.driver.id) }.isFailure)
    }

    @Test
    fun updateReplacesAnEntryAtomically() {
        val (registry, root) = newRegistry()
        val imported = registry.import("Guarded", soFile()) as UserVulkanDriverImport.Imported
        val quarantined = imported.driver.copy(
            earlyCrashStreak = 2,
            quarantined = true,
            quarantineReason = "quarantined after 2 early crashes",
        )
        registry.update(quarantined)
        assertEquals(quarantined, UserVulkanDriverRegistry(root).find(imported.driver.id))
    }

    @Test
    fun quarantinedModelRequiresAReasonAndUnquarantinedForbidsOne() {
        val base = UserVulkanDriver(
            id = "user-x", label = "x", libraryFileName = "driver.so",
            sha256 = "0".repeat(64), addedAt = 1L, vulkanApiVersion = null,
        )
        assertTrue(
            runCatching { base.copy(quarantined = true) }.isFailure,
        )
        assertTrue(
            runCatching { base.copy(quarantineReason = "leftover") }.isFailure,
        )
    }

    @Test
    fun unknownRegistrySchemaFailsClosedWithTheExactMessage() {
        val (_, root) = newRegistry()
        root.mkdirs()
        File(root, "registry.json").writeText("""{"schema":99,"drivers":[]}""")
        val failure = runCatching { UserVulkanDriverRegistry(root).list() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("schema 99 is not supported"))
    }

    @Test
    fun corruptRegistryJsonFailsClosedWithAnActionableMessage() {
        val (_, root) = newRegistry()
        root.mkdirs()
        File(root, "registry.json").writeText("""{"schema":1,"drivers":[{""")
        val failure = runCatching { UserVulkanDriverRegistry(root).list() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("registry is unreadable"))
        assertTrue(failure.message!!.contains("re-import"))
        // The repair pointer must name the affordance, not just "re-import".
        assertTrue(failure.message!!.contains("'Reset imported drivers'"))
    }

    @Test
    fun aFailedRegistrySaveAfterThePayloadMoveReclaimsTheStagedPayload() {
        val (registry, root) = newRegistry()
        // registry.json as a directory makes DurableFiles' temp→rename fail
        // (EISDIR/AccessDenied) only after the payload directory was already
        // moved into place, exercising the rollback's publication guard.
        File(root, "registry.json").mkdirs()
        assertTrue(runCatching { registry.import("Doomed", soFile()) }.isFailure)
        // No orphaned payload: the rollback reclaimed the moved directory.
        // (The published→keep direction of the guard needs a failure after
        // the rename and is not injectable at JVM level.)
        assertFalse(File(root, "doomed").exists())
    }

    @Test
    fun zipBombDecompressionIsCappedDuringThePrescan() {
        val (registry, _) = newRegistry()
        // A highly-compressible archive whose inflated size far exceeds a tiny
        // cap: the prescan must reject on inflated bytes, not after staging.
        val bomb = zipOf("libvulkan_freedreno.so" to ByteArray(1_000_000))
        val result = registry.import("Bomb", bomb, maxImportBytes = 64_000)
        val reason = (result as UserVulkanDriverImport.Rejected).reason
        assertTrue(reason.contains("import cap"))
        assertEquals(0, registry.list().size)
    }

    @Test
    fun directoryEntriesAreDrainedAgainstTheInflatedCap() {
        val (registry, _) = newRegistry()
        // isDirectory is name-based: a "bomb/"-named entry may carry a huge
        // DEFLATED payload. Skipping its drain would let nextEntry() inflate
        // it uncapped, so the prescan must cap directory entries too.
        val zip = temp.newFile("payload-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { stream ->
            stream.putNextEntry(ZipEntry("bomb/"))
            stream.write(ByteArray(1_000_000))
            stream.closeEntry()
            stream.putNextEntry(ZipEntry("lib.so"))
            stream.write(elf64())
            stream.closeEntry()
        }
        val result = registry.import("Dir bomb", zip, maxImportBytes = 64_000)
        val reason = (result as UserVulkanDriverImport.Rejected).reason
        assertTrue(reason.contains("import cap"))
        assertEquals(0, registry.list().size)
    }

    @Test
    fun androidNullCoercionCannotFakeAQuarantineReason() {
        val (registry, root) = newRegistry()
        root.mkdirs()
        val entry = """"id":"user-x","label":"X","libraryFileName":"driver.so",""" +
            """"sha256":"${"a".repeat(64)}","addedAt":1,"earlyCrashStreak":0,""" +
            """"quarantined":false"""
        // The persisted form: explicit JSON nulls for both optional fields.
        File(root, "registry.json").writeText(
            """{"schema":1,"drivers":[{$entry,"vulkanApiVersion":null,"quarantineReason":null}]}""",
        )
        val stored = registry.find("user-x")
        assertNull(stored?.vulkanApiVersion)
        assertNull(stored?.quarantineReason)

        // The Android-coerced form: the null sentinel surfacing as the
        // literal string "null" from optString. Without the has/isNull
        // read guard this trips the model invariant on every subsequent
        // read, bricking the whole lane on device.
        File(root, "registry.json").writeText(
            """{"schema":1,"drivers":[{$entry,"vulkanApiVersion":"null","quarantineReason":"null"}]}""",
        )
        val coerced = UserVulkanDriverRegistry(root).find("user-x")
        assertNull(coerced?.vulkanApiVersion)
        assertNull(coerced?.quarantineReason)
    }

    @Test
    fun blankDisplayNameFallsBackToTheImportedDriverLabel() {
        val (registry, root) = newRegistry()
        // SAF providers are known to return blank DISPLAY_NAME columns; the
        // fallback is the only guard keeping the exact-reason contract (I8)
        // from degrading to a generic init failure.
        val imported = registry.import("   ", soFile()) as UserVulkanDriverImport.Imported
        assertEquals("Imported driver", imported.driver.label)
        assertEquals("user-imported-driver", imported.driver.id)
        assertTrue(File(root, "imported-driver").isDirectory)
    }

    @Test
    fun absoluteZipEntryPathsAreRejectedAsUnsafe() {
        val (registry, _) = newRegistry()
        val result = registry.import(
            "Abs", zipOf("/data/local/tmp/evil.so" to elf64()),
        ) as UserVulkanDriverImport.Rejected
        assertTrue(result.reason.contains("unsafe entry path (/data/local/tmp/evil.so)"))
        assertEquals(0, registry.list().size)
    }

    @Test
    fun archiveWithTooManyEntriesIsRejectedBeforeNameBookkeepingGrows() {
        val (registry, _) = newRegistry()
        // A header flood of near-empty entries costs no inflated bytes; the
        // entry-count cap must bound the prescan anyway.
        val flood = zipOf(
            *(0..UserVulkanDriverRegistry.MAX_ARCHIVE_ENTRIES).map { index ->
                "junk$index.txt" to ByteArray(0)
            }.toTypedArray(),
        )
        val result = registry.import("Flood", flood)
        val reason = (result as UserVulkanDriverImport.Rejected).reason
        assertTrue(reason.contains("${UserVulkanDriverRegistry.MAX_ARCHIVE_ENTRIES + 1} entries"))
        assertTrue(reason.contains("single .so"))
        assertEquals(0, registry.list().size)
    }

    @Test
    fun unexpectedEntryNamesAreSampledWithAnExactOverflowCount() {
        val (registry, _) = newRegistry()
        val junkCount = UserVulkanDriverRegistry.MAX_REASON_SAMPLES + 4
        val zip = zipOf(
            *(listOf("lib.so" to elf64()) + (0 until junkCount).map { index ->
                "junk$index.txt" to "x".toByteArray()
            }).toTypedArray(),
        )
        val result = registry.import("Noisy", zip)
        val reason = (result as UserVulkanDriverImport.Rejected).reason
        assertTrue(reason.contains("unexpected: junk0.txt"))
        assertTrue(reason.contains("and 4 more"))
        // The reason stays bounded: not every name is quoted verbatim.
        assertFalse(reason.contains("junk${junkCount - 1}.txt"))
        assertEquals(0, registry.list().size)
    }

    @Test
    fun absurdIcdApiVersionComponentsAreWarnFreeNeverThrown() {
        val outcome = UserVulkanDriverValidator.validateIcd(
            """{"ICD":{"library_path":"driver.so","api_version":"99999999999999999999.1"}}""",
        ) as UserVulkanDriverValidator.IcdOutcome.Accepted
        assertEquals("99999999999999999999.1", outcome.apiVersion)
        assertNull(outcome.warning)
    }

    // --- AdrenoTools meta.json pack import (Eden/K11MCH1 format) ---------------

    private fun adrenoToolsPack(meta: ByteArray, library: String = "vulkan.ad07xx.so"): File = zipOf(
        "meta.json" to meta,
        library to elf64(),
    )

    @Test
    fun adrenoToolsMetaOnlyZipImportsWithLabelAndVersionFromMeta() {
        val (registry, root) = newRegistry()
        // The real K11MCH1 Turnip_v26.0.0_R8 pack: meta.json + one .so, no ICD.
        val meta = (
            """{"schemaVersion":1,"name":"Mesa Turnip driver v26.0.0 - R8",""" +
                """"description":"Compiled from Source + unsupported gpu hacks","author":"KIMCHI",""" +
                """"packageVersion":"1","vendor":"Mesa","driverVersion":"Vulkan 1.4.335",""" +
                """"minApi":27,"libraryName":"vulkan.ad07xx.so"}"""
            ).toByteArray()
        val imported = registry.import("Turnip_v26.0.0_R8", adrenoToolsPack(meta))
            as UserVulkanDriverImport.Imported
        // meta.name wins over the caller's display name; slug derives from it.
        assertEquals("Mesa Turnip driver v26.0.0 - R8", imported.driver.label)
        assertEquals("user-mesa-turnip-driver-v26-0-0-r8", imported.driver.id)
        assertEquals("1.4.335", imported.driver.vulkanApiVersion)
        assertNull(imported.warning)
        // No ICD in the pack → synthetic ICD; the metadata never lingers on disk.
        val dir = File(root, "mesa-turnip-driver-v26-0-0-r8")
        assertTrue(File(dir, "driver.so").isFile)
        assertTrue(File(dir, "icd.json").isFile)
        assertFalse(File(dir, "meta.json").exists())
        assertEquals(
            "driver.so",
            org.json.JSONObject(File(dir, "icd.json").readText())
                .getJSONObject("ICD").getString("library_path"),
        )
    }

    @Test
    fun metaApiVersionBelow13CarriesTheExistingWarnOnlyFloor() {
        val (registry, _) = newRegistry()
        val meta = """{"name":"Old turnip","driverVersion":"Vulkan 1.1.262"}""".toByteArray()
        val imported = registry.import("Whatever", adrenoToolsPack(meta))
            as UserVulkanDriverImport.Imported
        assertEquals("1.1.262", imported.driver.vulkanApiVersion)
        assertTrue(imported.warning!!.contains("1.1.262"))
        assertTrue(imported.warning.contains("1.10.3"))
    }

    @Test
    fun icdApiVersionStaysAuthoritativeOverMeta() {
        val (registry, _) = newRegistry()
        val zip = zipOf(
            "libvulkan_freedreno.so" to elf64(),
            "freedreno_icd.aarch64.json" to icdJson("1.3.290"),
            "meta.json" to """{"name":"Meta name","driverVersion":"Vulkan 1.1.0"}""".toByteArray(),
        )
        val imported = registry.import("Display name", zip) as UserVulkanDriverImport.Imported
        assertEquals("Meta name", imported.driver.label)
        assertEquals("1.3.290", imported.driver.vulkanApiVersion)
        assertNull(imported.warning)
    }

    @Test
    fun metaNameMissingOrBlankFallsBackToTheDisplayName() {
        val (registry, _) = newRegistry()
        val noName = registry.import(
            "Fallback label",
            adrenoToolsPack("""{"driverVersion":"1.3.290"}""".toByteArray()),
        ) as UserVulkanDriverImport.Imported
        assertEquals("Fallback label", noName.driver.label)

        val blank = registry.import(
            "Fallback 2",
            adrenoToolsPack("""{"name":"   "}""".toByteArray()),
        ) as UserVulkanDriverImport.Imported
        assertEquals("Fallback 2", blank.driver.label)
    }

    @Test
    fun metaNameLongerThan64CharsIsTruncatedLikeAnyLabel() {
        val (registry, _) = newRegistry()
        val imported = registry.import(
            "Display",
            adrenoToolsPack("""{"name":"${"x".repeat(80)}"}""".toByteArray()),
        ) as UserVulkanDriverImport.Imported
        assertEquals(64, imported.driver.label.length)
    }

    @Test
    fun malformedMetaRejectsTheWholeImportAndLeavesNoPartialEntry() {
        val (registry, root) = newRegistry()
        val result = registry.import("Bad meta", adrenoToolsPack("{ not json".toByteArray()))
        val reason = (result as UserVulkanDriverImport.Rejected).reason
        assertTrue(reason.contains("meta.json could not be parsed"))
        assertEquals(0, registry.list().size)
        assertTrue(root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.isNullOrEmpty())
        assertFalse(File(root, "registry.json").exists())
    }

    @Test
    fun metaJsonAndIcdJsonCountsAreEachCappedAtOneWithExactReasons() {
        val (registry, _) = newRegistry()
        val twoMetas = registry.import(
            "Two metas",
            zipOf(
                "meta.json" to """{"name":"A"}""".toByteArray(),
                "nested/meta.json" to """{"name":"B"}""".toByteArray(),
                "lib.so" to elf64(),
            ),
        ) as UserVulkanDriverImport.Rejected
        assertTrue(twoMetas.reason.contains("at most one AdrenoTools meta.json"))

        val twoIcds = registry.import(
            "Two ICDs",
            zipOf(
                "lib.so" to elf64(),
                "a.json" to icdJson(),
                "b.json" to icdJson(),
            ),
        ) as UserVulkanDriverImport.Rejected
        assertTrue(twoIcds.reason.contains("at most one ICD JSON manifest"))
    }

    @Test
    fun oversizedMetaAndIcdAreRejectedWithExactReasonsAndNoPartialEntry() {
        val (registry, root) = newRegistry()
        val pad = "x".repeat(UserVulkanDriverRegistry.MAX_ICD_BYTES.toInt())
        val hugeMeta = registry.import(
            "Huge meta",
            adrenoToolsPack("""{"name":"$pad"}""".toByteArray()),
        ) as UserVulkanDriverImport.Rejected
        assertTrue(hugeMeta.reason.contains("driver metadata"))
        assertTrue(hugeMeta.reason.contains("is tiny"))
        assertTrue(hugeMeta.reason.contains("not a meta.json"))

        val hugeIcd = registry.import(
            "Huge ICD",
            zipOf(
                "lib.so" to elf64(),
                "freedreno_icd.aarch64.json" to
                    """{"ICD":{"library_path":"$pad"}}""".toByteArray(),
            ),
        ) as UserVulkanDriverImport.Rejected
        assertTrue(hugeIcd.reason.contains("manifests are tiny"))
        assertTrue(hugeIcd.reason.contains("not an ICD"))

        assertEquals(0, registry.list().size)
        assertTrue(root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.isNullOrEmpty())
        assertFalse(File(root, "registry.json").exists())
    }

    @Test
    fun metaDetectionIsCaseInsensitiveOnTheBaseName() {
        val (registry, _) = newRegistry()
        val imported = registry.import(
            "Upper",
            zipOf("META.JSON" to """{"name":"Upper name"}""".toByteArray(), "lib.so" to elf64()),
        ) as UserVulkanDriverImport.Imported
        assertEquals("Upper name", imported.driver.label)
    }
}
