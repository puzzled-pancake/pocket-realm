package com.pocketrealm.database

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The migration storage gate is a SNAPSHOT
 * budget, not a datadir-size guess — applyPinnedMigrations takes a full
 * pre-migration datadir snapshot (measured ~1.76 GiB on the tbc/wotlk
 * lanes) while the live datadir keeps its bytes. The static floor must
 * therefore sit ABOVE the measured snapshot cost in BOTH engines; this pin
 * fails the moment either constant is lowered back under the measured cost
 * or the citation drifts away.
 */
class DesktopMigrationStorageBudgetTest {
    private fun repoRoot(): File = generateSequence(File(System.getProperty("user.dir"))) {
        it.parentFile
    }.firstOrNull { File(it, "schemas/flavor.json").isFile }
        ?: error("repo root not found")

    private fun engineSource(relative: String): String =
        File(repoRoot(), relative).readText()

    @Test
    fun theDesktopMigrationBudgetExceedsTheMeasuredSnapshotCost() {
        val source = engineSource(
            "desktop/src/main/kotlin/com/pocketrealm/database/DesktopMariaDbEngine.kt",
        )
        assertTrue(
            "the desktop migration gate must reserve 3 GiB (snapshot ~1.76 GiB " +
                "+ live datadir headroom on the wotlk lane)",
            "private const val MIN_MIGRATION_BYTES = 3L * 1024 * 1024 * 1024" in source,
        )
        assertTrue(
            "the constant must cite the measured snapshot cost it covers",
            "measured ~1.76 GiB" in source,
        )
    }

    @Test
    fun theAndroidMigrationBudgetMirrorsTheDesktopFloor() {
        val source = engineSource(
            "android/app/src/main/java/com/pocketrealm/database/DatabaseEngine.kt",
        )
        assertTrue(
            "the android migration gate mirrors the desktop 3 GiB floor",
            "private const val MIN_MIGRATION_BYTES = 3L * 1024 * 1024 * 1024" in source,
        )
    }

    @Test
    fun theFlavorStorageBlocksStayAboveTheMigrationBudget() {
        // the schemas/flavor.json measured blocks — 12 GiB tbc / 24 GiB wotlk
        // extracted — are the per-lane totals the storage UI
        // quotes; the migration budget must stay a fraction of the smallest
        // qualified lane's block, not approach it.
        val flavor = org.json.JSONObject(
            File(repoRoot(), "schemas/flavor.json").readText(),
        )
        val tbc = flavor.getJSONArray("flavors").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .first { it.getString("id") == "tbc" }
        }
        val extracted = tbc.getJSONObject("storage").getLong("extracted_estimate_bytes")
        assertTrue(
            "the tbc extracted block must stay well above the 3 GiB migration budget",
            extracted >= 12L * 1024 * 1024 * 1024,
        )
    }
}
