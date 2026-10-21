package com.pocketrealm.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The addon-repository warm-up used to read
 * the `addons` `by lazy` delegate from the init block's IO coroutine — the
 * delegate's backing field is assigned in declaration order ~230 lines
 * after the init block, so the warm-up deterministically-to-often died with
 * `Lazy.getValue ... <local1> is null` (warn-swallowed; the DESK-10/83
 * app-start seed silently lost — archived in every W4/W7 e2e probe log).
 * The construct now resolves `DesktopAddonRepository.get(roots)`, the
 * idempotent process singleton the lazy would create, which never touches
 * an unassigned instance delegate.
 */
class DesktopAppModelWarmUpRaceTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun twentyConstructionsProduceZeroWarmUpFailures() {
        val failuresBefore = warmUpFailureCount()
        repeat(20) { n ->
            val roots = DesktopStorageRoots(folder.newFolder("roots-$n"))
            val model = DesktopAppModel(roots)
            try {
                // drive the warm-up body deterministically (the init's IO
                // launch races the test otherwise); the DEFAULT construct
                // is the seam under test
                model.warmUpAddonRepository()
            } finally {
                model.close()
            }
        }
        val added = warmUpFailureCount() - failuresBefore
        assertFalse(
            "the warm-up failed $added time(s) across 20 constructions — " +
                "the construct still touches an unassigned delegate",
            added > 0,
        )
    }

    @Test
    fun theWarmUpConstructNeverReadsTheLazyDelegate() {
        // the source pin: the warm-up construct must resolve the singleton
        // companion, never the `addons` by-lazy property
        val source = File(
            "src/main/kotlin/com/pocketrealm/desktop/DesktopAppModel.kt",
        ).readText()
        val warmUp = source.substring(
            source.indexOf("internal fun warmUpAddonRepository"),
            source.indexOf("runCatching(construct)"),
        )
        assertTrue(
            "the warm-up construct must resolve DesktopAddonRepository.get(roots)",
            "DesktopAddonRepository.get(roots)" in warmUp,
        )
        assertFalse(
            "the warm-up construct still reads the addons lazy delegate " +
                "(the mid-construction null-delegate race)",
            "{ addons }" in warmUp,
        )
    }

    /** The DesktopLog ring's count of warm-up failure WARNs so far. */
    private fun warmUpFailureCount(): Int =
        DesktopLog.snapshot().count {
            it.level == "WARN" && "addon repository warm-up failed" in it.message
        }
}
