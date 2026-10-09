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

    /** The scanner's key form: bare lowercase filenames, never a path. */
    private fun bareNameKeys(requiredMpqs: Set<String>): Set<String> =
        requiredMpqs.map { it.substringAfterLast('/') }.toSet()

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

    @Test
    fun theTbcWotlkRequiredSetsCarrySlashedEntriesTheKeyFormCannotMatch() {
        // The deferral precondition itself: every later-expansion registry
        // carries locale-prefixed requiredMpqs entries while BOTH gates'
        // key forms are bare filenames - so reconstructing the scanner's
        // comparison yields a missing-set of EXACTLY the slashed entries.
        // When S5 lands the Data/<locale> traversal, this test FAILS its
        // precondition on purpose: reconcile the key comparison (and add
        // the C2 exact-set rule) in the same change, then re-pin.
        for (flavor in ClientFlavors.ALL.filter { it.flavorId != "vanilla" }) {
            val slashed = flavor.requiredMpqs.filter { "/" in it }
            assertTrue(
                "${flavor.flavorId}: the recorded deferral precondition " +
                    "changed - no slashed requiredMpqs remain. S5 landed the " +
                    "Data/<locale> traversal: reconcile the scanner key " +
                    "comparison with the registry path-form and re-pin this " +
                    "tripwire (plus the C2 exact-set rule).",
                slashed.isNotEmpty(),
            )
            // Reconstruct the gates' comparison: keys are bare names, so
            // the slashed entries are ALWAYS the missing-set.
            val keys = bareNameKeys(flavor.requiredMpqs)
            val missing = flavor.requiredMpqs.filterNot(keys::contains)
            assertEquals(
                "${flavor.flavorId}: the missing-set the gates compute is " +
                    "exactly the slashed entries - a genuine client with " +
                    "every MPQ staged still fails VAL-04 at the flip",
                slashed,
                missing,
            )
        }
    }

    @Test
    fun bothGatesCompareMissingOnlyAndNeverRejectUnknownMpqs() {
        // The C2 exact-set rule (an unknown patch-*.mpq is VAL-04 naming
        // the file) has NO implementing check in either lane: pin the
        // absence so S5's reconcile cannot forget the other half of the
        // contract. If these source pins break because the exact-set rule
        // LANDED, update this test to pin the rule positively instead.
        val saf = source("android/app/src/main/java/com/pocketrealm/client/SafClientScanner.kt")
        val archive = source("android/app/src/main/java/com/pocketrealm/importer/ArchiveClientScanner.kt")
        // bare-name key forms (the collision half)
        assertTrue("associateBy { it.name.lowercase() }" in saf)
        // missing-only comparison (never an unknown-entry rejection)
        for ((name, text) in listOf("SafClientScanner" to saf, "ArchiveClientScanner" to archive)) {
            assertTrue(
                "$name: lost the requiredMpqs missing-only compare",
                "filterNot(mpqs::containsKey)" in text,
            )
            assertTrue(
                "$name: an exact-set rejection exists but this tripwire " +
                    "was not re-pinned - pin the C2 rule positively",
                "unknown" !in text.lowercase() || "patch-" !in text.lowercase(),
            )
        }
    }
}
