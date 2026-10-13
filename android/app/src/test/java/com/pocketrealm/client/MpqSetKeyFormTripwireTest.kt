package com.pocketrealm.client

import com.pocketrealm.importer.SyntheticClientArchives
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R2-2 (android-core review round 1, medium): the MPQ-set gates compare
 * the registry's PATH-FORM requiredMpqs entries ("enus/locale-enus.mpq")
 * against BARE-FILENAME map keys built from Data/'s top-level entries, so
 * the tbc/wotlk required sets can never match - at the qualification flip
 * every genuine TBC/WotLK import would fail VAL-04 "missing base MPQ set"
 * on all three lanes. The Data/<locale> traversal is the RECORDED S5
 * item 5 deferral (ClientFlavor.kt), so this file is the tripwire that
 * forces S5 to reconcile the key comparison when the traversal lands -
 * and pins the absence of the plan-C2 exact-set rule (an unknown
 * patch-*.mpq is currently never rejected: the checks are missing-only).
 */
class MpqSetKeyFormTripwireTest {

    private fun source(repoRelative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = dir?.let { File(it, repoRelative) }
            if (candidate != null && candidate.isFile) return candidate.readText()
            dir = dir?.parentFile
        }
        error("source not found walking up from ${System.getProperty("user.dir")}: $repoRelative")
    }

    private class FakeAccess(
        private val tree: Map<String, List<SafClientScanner.Entry>>,
        private val blobs: Map<String, ByteArray>,
    ) : SafClientScanner.Access {
        override val rootId: String = "root"
        override fun children(parentId: String): List<SafClientScanner.Entry> =
            tree.getValue(parentId)

        // R1-arch-4/R2-1/R3-3/R4-2 (review round 1): the fake MUST honor
        // the production Access.read contract (SafClientScanner
        // .DocumentsAccess.read caps at maxBytes; rejectTruncation refuses
        // a blob that would be cut) - the uncapped form handed the
        // scanner's 4-byte MPQ-magic probe the whole stub, contentEquals
        // failed on length for every MPQ, and the positive control could
        // never pass as authored.
        override fun read(documentId: String, maxBytes: Int, rejectTruncation: Boolean): ByteArray {
            val blob = blobs.getValue(documentId)
            if (rejectTruncation && blob.size > maxBytes) {
                error("VAL-02: synthetic document exceeds the fast-scan limit")
            }
            return blob.copyOf(minOf(maxBytes, blob.size))
        }
    }

    @Test
    fun aQualifiedVanillaClientPassesTheMpqCompareLeg() {
        // The compare leg EXECUTES for a qualified flavor: a synthetic
        // vanilla tree (flat Data/ MPQ set + a build-5875 PE) scans
        // supported with no VAL-04 - proving this gate is live and that
        // the collision below is specific to the path-form entries.
        val wowId = "wow"
        val dataId = "data"
        val mpqIds = SyntheticClientArchives.BASE_MPQS.associateBy({ it }, { "mpq-$it" })
        val root = listOf(
            SafClientScanner.Entry(wowId, "WoW.exe", directory = false, size = 4096L),
            SafClientScanner.Entry(dataId, "Data", directory = true, size = 0L),
        )
        val data = mpqIds.map { (name, id) ->
            SafClientScanner.Entry(id, name, directory = false, size = 1024L)
        }
        val blobs = buildMap {
            put(wowId, SyntheticClientArchives.syntheticPe(build = 5875))
            mpqIds.forEach { (name, id) -> put(id, SyntheticClientArchives.mpqStub(name)) }
        }
        val result = SafClientScanner.scanAccess(
            FakeAccess(
                mapOf("root" to root, dataId to data),
                blobs,
            ),
        )
        assertTrue(
            "a complete vanilla flat set must validate: ${result.let { it.supported }}",
            result.supported,
        )
        assertFalse(
            "no missing-MPQ failure may fire for the vanilla flat set",
            result.toString().contains("missing base MPQ set"),
        )
    }

    @Suppress("LongMethod")
    private fun tbcTree(
        withLocaleSubdir: Boolean,
        extraPatch: String? = null,
    ): FakeAccess {
        val wowId = "wow"
        val dataId = "data"
        val localeId = "locale"
        val extraId = "extra"
        val flatIds = listOf("common", "expansion", "patch", "patch-2")
            .associateBy({ it }, { "mpq-$it" })
        val localeFiles = listOf(
            "locale-enus", "speech-enus", "expansion-locale-enus",
            "expansion-speech-enus", "patch-enus", "patch-enus-2",
        )
        val root = mutableListOf(
            SafClientScanner.Entry(wowId, "WoW.exe", directory = false, size = 4096L),
            SafClientScanner.Entry(dataId, "Data", directory = true, size = 0L),
        )
        val tree = mutableMapOf<String, List<SafClientScanner.Entry>>("root" to root)
        val blobs = mutableMapOf<String, ByteArray>(
            wowId to SyntheticClientArchives.syntheticPe(
                build = 8606, versionMajor = 2, versionMinor = 4, versionRev = 3,
            ),
        )
        for ((name, id) in flatIds) {
            root += SafClientScanner.Entry(id, "$name.MPQ", directory = false, size = 1024L)
            blobs[id] = SyntheticClientArchives.mpqStub("$name.MPQ")
        }
        if (withLocaleSubdir) {
            root += SafClientScanner.Entry(localeId, "enUS", directory = true, size = 0L)
            val localeEntries = localeFiles.map { name ->
                val id = "mpq-$name"
                blobs[id] = SyntheticClientArchives.mpqStub("$name.MPQ")
                SafClientScanner.Entry(id, "$name.MPQ", directory = false, size = 1024L)
            }
            tree[localeId] = localeEntries
        } else {
            for (name in localeFiles) {
                val id = "mpq-$name"
                root += SafClientScanner.Entry(id, "$name.MPQ", directory = false, size = 1024L)
                blobs[id] = SyntheticClientArchives.mpqStub("$name.MPQ")
            }
        }
        if (extraPatch != null) {
            root += SafClientScanner.Entry(extraId, extraPatch, directory = false, size = 1024L)
            blobs[extraId] = SyntheticClientArchives.mpqStub(extraPatch)
        }
        tree[dataId] = root.drop(2)
        return FakeAccess(tree, blobs)
    }

    @Test
    fun theTbcWotlkLocaleMpqsResolveThroughTheDataLocaleTraversal() {
        // W4 (the locale-MPQ defect the tbc E2E surfaced): the recorded
        // S5-era deferral ("slashed entries the key form cannot match") is
        // RETIRED - the scanner walks Data/<locale>/ (bounded depth) and
        // matches the registry's slashed requiredMpqs keys by lowercase
        // basename. A genuine-shape tbc tree (locale MPQs inside
        // Data/enUS/) scans SUPPORTED.
        val genuine = SafClientScanner.scanAccess(tbcTree(withLocaleSubdir = true))
        assertTrue(
            "a genuine tbc tree with locale MPQs in Data/enUS/ must scan " +
                "supported: ${genuine.failures}",
            genuine.supported,
        )
        // R2-R4-2: a flat layout cannot satisfy the SLASHED keys (the
        // locale MPQs must sit in Data/enUS/ - the only paths the genuine
        // client loads), so the flat locale placement refuses
        val flat = SafClientScanner.scanAccess(tbcTree(withLocaleSubdir = false))
        assertFalse(
            "a flat locale placement cannot satisfy the slashed keys: ${flat.failures}",
            flat.supported,
        )
        // and the gate still refuses a MISSING locale MPQ, naming the
        // registry's slashed key
        val wowId = "wow"
        val dataId = "data"
        val localeId = "locale"
        val root = mutableListOf(
            SafClientScanner.Entry(wowId, "WoW.exe", directory = false, size = 4096L),
            SafClientScanner.Entry(dataId, "Data", directory = true, size = 0L),
            SafClientScanner.Entry(localeId, "enUS", directory = true, size = 0L),
        )
        val blobs = mutableMapOf<String, ByteArray>(
            wowId to SyntheticClientArchives.syntheticPe(
                build = 8606, versionMajor = 2, versionMinor = 4, versionRev = 3,
            ),
        )
        val names = listOf("common", "expansion", "patch", "patch-2")
        for (name in names) {
            root += SafClientScanner.Entry("mpq-$name", "$name.MPQ", directory = false, size = 1024L)
            blobs["mpq-$name"] = SyntheticClientArchives.mpqStub("$name.MPQ")
        }
        root += SafClientScanner.Entry("mpq-locale", "locale-enus.MPQ", directory = false, size = 1024L)
        blobs["mpq-locale"] = SyntheticClientArchives.mpqStub("locale-enus.MPQ")
        val missing = SafClientScanner.scanAccess(
            FakeAccess(mapOf("root" to root, dataId to root.drop(1), localeId to listOf(
                SafClientScanner.Entry("mpq-locale", "locale-enus.MPQ", directory = false, size = 1024L),
            )), blobs),
        )
        assertFalse("a tbc tree missing most locale MPQs must refuse", missing.supported)
        assertTrue(
            "the refusal names the slashed registry key: ${missing.failures}",
            missing.failures.any {
                "enus/expansion-locale-enus.mpq" in it
            },
        )
    }

    @Test
    fun anUnknownPatchMpqRefusesWithTheExactSetCopy() {
        // R1-1/R2-5-2 (W4 code-red round 5): the tbc mpq_set note's
        // exact-set semantics are ENFORCED now that the flavor qualifies -
        // an unknown patch-9.mpq (valid magic, not in the tbc set) refuses
        // VAL-04 naming the file.
        val doctored = SafClientScanner.scanAccess(
            tbcTree(withLocaleSubdir = true, extraPatch = "patch-9.MPQ"),
        )
        assertFalse(
            "an unknown patch MPQ must refuse on the tbc lane: ${doctored.failures}",
            doctored.supported,
        )
        assertTrue(
            "the refusal names the file: ${doctored.failures}",
            doctored.failures.any { "patch-9.mpq" in it },
        )
    }

    @Test
    fun aPathDoctoredMpqSetRefusesLoud() {
        // R2-R4-2: a PATH-DOCTORED set (the required MPQs under
        // Data/decoy/, no Data/enUS/) must refuse - a basename-only match
        // accepted it into a generation whose client cannot load its data.
        val wowId = "wow"
        val dataId = "data"
        val decoyLocale = "decoy"
        val decoyRoot = mutableListOf(
            SafClientScanner.Entry(wowId, "WoW.exe", directory = false, size = 4096L),
            SafClientScanner.Entry(dataId, "Data", directory = true, size = 0L),
            SafClientScanner.Entry(decoyLocale, "decoy", directory = true, size = 0L),
        )
        val decoyBlobs = mutableMapOf<String, ByteArray>(
            wowId to SyntheticClientArchives.syntheticPe(
                build = 8606, versionMajor = 2, versionMinor = 4, versionRev = 3,
            ),
        )
        val names = listOf("common", "expansion", "patch", "patch-2")
        for (name in names) {
            decoyRoot += SafClientScanner.Entry("mpq-$name", "$name.MPQ", directory = false, size = 1024L)
            decoyBlobs["mpq-$name"] = SyntheticClientArchives.mpqStub("$name.MPQ")
        }
        for (name in listOf("locale-enus", "expansion-locale-enus")) {
            decoyRoot += SafClientScanner.Entry(
                "decoy-$name", "$name.MPQ", directory = false, size = 1024L)
            decoyBlobs["decoy-$name"] = SyntheticClientArchives.mpqStub("$name.MPQ")
        }
        val decoyTree = mutableMapOf<String, List<SafClientScanner.Entry>>(
            "root" to decoyRoot,
            dataId to decoyRoot.drop(2),
        )
        decoyTree[decoyLocale] = listOf(
            SafClientScanner.Entry(
                "decoy-locale-enus", "locale-enus.MPQ",
                directory = false, size = 1024L,
            ),
            SafClientScanner.Entry(
                "decoy-expansion-locale-enus", "expansion-locale-enus.MPQ",
                directory = false, size = 1024L,
            ),
        )
        val decoy = SafClientScanner.scanAccess(FakeAccess(decoyTree, decoyBlobs))
        assertFalse(
            "a path-doctored MPQ set (decoy dir, no enUS) must refuse: ${decoy.failures}",
            decoy.supported,
        )
    }

    @Test
    fun anOversizedWowExeRefusesWithTheVal02SizeCopy() {
        // R6-7: the charter's negative-matrix "oversized -> VAL codes" leg
        // - a declared WoW.exe size above the 32 MiB fast-scan cap must
        // refuse with the exact VAL-02 size copy.
        val wowId = "wow"
        val dataId = "data"
        val mpqIds = SyntheticClientArchives.BASE_MPQS.associateBy({ it }, { "mpq-$it" })
        val root = listOf(
            SafClientScanner.Entry(wowId, "WoW.exe", directory = false,
                size = 33L * 1024L * 1024L),
            SafClientScanner.Entry(dataId, "Data", directory = true, size = 0L),
        )
        val data = mpqIds.map { (name, id) ->
            SafClientScanner.Entry(id, name, directory = false, size = 1024L)
        }
        val blobs = buildMap {
            put(wowId, SyntheticClientArchives.syntheticPe(build = 5875))
            mpqIds.forEach { (name, id) -> put(id, SyntheticClientArchives.mpqStub(name)) }
        }
        val result = SafClientScanner.scanAccess(
            FakeAccess(mapOf("root" to root, dataId to data), blobs),
        )
        assertFalse("an oversized WoW.exe must refuse", result.supported)
        assertTrue(
            "must carry the exact VAL-02 size copy: ${result.failures}",
            result.failures.any {
                it == "VAL-02: selected executable exceeds fast-scan limit"
            },
        )
    }

    @Test
    fun bothGatesCompareMissingOnlyAndNeverRejectUnknownMpqs() {
        // R1-1/R2-5-2 (W4 code-red round 5): the C2 exact-set rule LANDED -
        // pin it POSITIVELY in both lanes: the unknown patch-*.mpq leg
        // (VAL-04 naming the file, non-vanilla lanes) plus the missing-only
        // compare it complements.
        val saf = source("android/app/src/main/java/com/pocketrealm/client/SafClientScanner.kt")
        val archive = source("android/app/src/main/java/com/pocketrealm/importer/ArchiveClientScanner.kt")
        // W4: the locale traversal + per-form key matching (the
        // retired S5 deferral's replacement + the R2-R4-2 path-doctored
        // refusal)
        assertTrue("walkMpqTree" in saf)
        assertTrue("mpqByRelPath.containsKey(lowered)" in saf)
        // missing-only comparison (never an unknown-entry rejection)
        for ((name, text) in listOf("SafClientScanner" to saf, "ArchiveClientScanner" to archive)) {
            assertTrue(
                "$name: lost the requiredMpqs missing-only compare",
                "requiredMpqs.orEmpty().filterNot" in text,
            )
            // the LANDED C2 exact-set rule: an unknown patch MPQ is
            // VAL-04 naming the file, on the non-vanilla lanes
            assertTrue(
                "$name: lost the exact-set unknown-patch rejection (R1-1)",
                "unknown patch MPQ not in the exact set" in text,
            )
            assertTrue(
                "$name: the exact-set leg must gate on the non-vanilla lanes " +
                    "(vanilla's documented tolerance)",
                "VANILLA.flavorId" in text,
            )
        }
    }
}
