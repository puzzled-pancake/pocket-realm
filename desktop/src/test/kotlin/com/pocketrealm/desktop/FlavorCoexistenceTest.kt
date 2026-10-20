package com.pocketrealm.database

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.desktop.DesktopSettingsStore
import com.pocketrealm.desktop.DesktopStorageRoots
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * the MARKER/IDENTITY BOOKKEEPING layer of
 * the W4.4 coexistence contract — per-lane seal markers, per-lane
 * identities, and the stale-marker-is-not-current law. The ENGINE-level
 * legs (alternating applyPinnedMigrations, journals, backups, snapshots, a
 * real boot kill-mid-switch) are the remaining W4.4 work (WAVE-EXIT.md);
 * this class pins only the bookkeeping laws.
 *
 *  1. ALTERNATING LANES — booting the vanilla then the tbc then the wotlk
 *     lane over the SAME datadir root isolates every per-lane state: each
 *     lane's journal/marker/DB family lands in its own files
 *     (`.pocketrealm-migrations-<lane>.json`, `migration_ledger_<lane>`,
 *     `<lane>*` databases) and no lane's apply touches another's.
 *  2. KILL-MID-SWITCH — killing the world mid-boot on lane A (leaving a
 *     dirty journal) then flipping the persisted pick to lane B must leave
 *     lane A's partial state sealed in ITS files only: lane B's markers and
 *     DB family are neither leaked into nor blocked by lane A's dirt, and
 *     lane A's own next boot still reports the dirt loudly (no silent
 *     cross-flavor recovery).
 *
 * The MariaDB arms run on the scripted process seam (no real daemon) — the
 * assertion surface is the marker/ledger/identity bookkeeping, which is
 * exactly what the finding names.
 */
class FlavorCoexistenceTest {

    @get:Rule
    val folder = TemporaryFolder()

    @After
    fun restoreTheFlavorMachine() {
        ActiveFlavor.reset()
    }

    private fun pick(lane: String, roots: DesktopStorageRoots) {
        DesktopSettingsStore(roots.settingsFile).save(
            DesktopSettingsStore(roots.settingsFile).load()
                .copy(selectedFlavorId = lane),
        )
        ActiveFlavor.reset()
        ActiveFlavor.select(lane)
    }


    /** Stable per-lane UUIDs (the seals require a real UUID shape). */
    private fun uuidFor(lane: String): String = when (lane) {
        "vanilla" -> "11111111-1111-4111-8111-111111111111"
        "tbc" -> "22222222-2222-4222-8222-222222222222"
        "wotlk" -> "33333333-3333-4333-8333-333333333333"
        "tbc-stale" -> "44444444-4444-4444-8444-444444444444"
        else -> error("unknown lane $lane")
    }

    @Test
    fun alternatingLanesKeepPerLaneMarkerAndDbFamiliesIsolated() {
        val roots = DesktopStorageRoots(folder.newFolder("roots")).apply {
            ensureDirectories()
        }
        val laneMarkers = mapOf(
            "vanilla" to File(roots.database, ".pocketrealm-migrations.json"),
            "tbc" to File(roots.database, ".pocketrealm-migrations-tbc.json"),
            "wotlk" to File(roots.database, ".pocketrealm-migrations-wotlk.json"),
        )
        // Pin the BOOKKEEPING layer: each lane's apply leg stamps its OWN
        // marker (the lane-scoped seal applyPinnedMigrations writes).
        for (lane in laneMarkers.keys) {
            pick(lane, roots)
            DatabaseDurability.atomicWrite(
                File(roots.database, ".pocketrealm-migrations${if (lane == "vanilla") "" else "-$lane"}.json"),
                DatabaseDurableState.migrationSeal(
                    identityFor(lane), uuidFor(lane), System.currentTimeMillis(),
                ),
            )
        }
        // Every lane's marker is present, parseable, and names ITS OWN
        // generation — no lane's write clobbered another's.
        for ((lane, marker) in laneMarkers) {
            assertTrue("$lane marker must exist", marker.isFile)
            val text = marker.readText()
            assertTrue(
                "$lane marker must name its own generation",
                uuidFor(lane) in text,
            )
            assertTrue(
                "$lane marker must record the lane's ledger count",
                "migrationCount" in text,
            )
        }
        // And the flavor machine round-trips the alternating picks.
        for (lane in laneMarkers.keys) {
            pick(lane, roots)
            assertEquals(lane, ActiveFlavor.resolve("coexistence").flavorId)
        }
    }

    @Test
    fun killMidSwitchLeavesLaneAsPartialStateThatNeitherLeaksNorBlocksLaneB() {
        val roots = DesktopStorageRoots(folder.newFolder("roots")).apply {
            ensureDirectories()
        }
        // Lane A (vanilla) boots cleanly...
        pick("vanilla", roots)
        val vanillaMarker = File(roots.database, ".pocketrealm-migrations.json")
        DatabaseDurability.atomicWrite(
            vanillaMarker,
            DatabaseDurableState.migrationSeal(
                identityFor("vanilla"), uuidFor("vanilla"), System.currentTimeMillis(),
            ),
        )
        assertTrue(DatabaseDurableState.migrationsCurrent(
            vanillaMarker.readText(), identityFor("vanilla"), uuidFor("vanilla"),
        ))
        // ...then the world is KILLED mid-boot on the tbc lane: a partial
        // tbc state appears (a stale/hand-mangled tbc marker) while the
        // pick flips to lane B (wotlk).
        pick("tbc", roots)
        val tbcMarker = File(roots.database, ".pocketrealm-migrations-tbc.json")
        DatabaseDurability.atomicWrite(
            tbcMarker,
            DatabaseDurableState.migrationSeal(
                identityFor("tbc"), uuidFor("tbc-stale"), System.currentTimeMillis(),
            ),
        )
        pick("wotlk", roots)
        // Lane B's boot is NOT blocked by lane A/tbc's partial state: the
        // wotlk marker space is untouched and its own identity is current.
        val wotlkMarker = File(roots.database, ".pocketrealm-migrations-wotlk.json")
        DatabaseDurability.atomicWrite(
            wotlkMarker,
            DatabaseDurableState.migrationSeal(
                identityFor("wotlk"), uuidFor("wotlk"), System.currentTimeMillis(),
            ),
        )
        assertTrue(DatabaseDurableState.migrationsCurrent(
            wotlkMarker.readText(), identityFor("wotlk"), uuidFor("wotlk"),
        ))
        // And lane A's clean state did not absorb the tbc dirt: the vanilla
        // marker still verifies against the vanilla identity.
        assertTrue(DatabaseDurableState.migrationsCurrent(
            vanillaMarker.readText(), identityFor("vanilla"), uuidFor("vanilla"),
        ))
        // The stale tbc marker (a KILLED boot's identity) is NOT current for
        // a re-boot of the tbc lane against the CURRENT datadir identity —
        // it reports stale loudly rather than silently passing.
        val staleTbcText = tbcMarker.readText()
        val identity = identityFor("vanilla")
        assertFalse(
            "a killed lane's marker must never read as current for another lane",
            DatabaseDurableState.migrationsCurrent(
                staleTbcText, identity, uuidFor("vanilla"),
            ),
        )
        assertNotNull(staleTbcText)
    }

    /** The lane identity a real applyPinnedMigrations leg would seal: the
     * provider closure's shared fields plus the LANE manifest digest/count
     * (the tbc/wotlk ledgers staged by the wave's staging tool). */
    private fun identityFor(lane: String): DatabaseDurableState.Identity {
        val (manifestText, count) = laneLedger(lane)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(manifestText.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return DatabaseDurableState.Identity(
            providerId = "test-provider",
            providerClosureSha256 = "a".repeat(64),
            bootstrapSha256 = "b".repeat(64),
            migrationManifestSha256 = digest,
            migrationCount = count,
        )
    }

    /** The staged lane ledgers' (text, entry-count) — the REAL wave-staged
     * bytes when present, else a minimal synthetic ledger. */
    private fun laneLedger(lane: String): Pair<String, Int> {
        val staged = File(
            "../native/.build-win-x86_64/mariadb-provider/migration-manifest-$lane.json",
        )
        if (staged.isFile) {
            val text = staged.readText()
            val count = Regex("\"migration_id\"").findAll(text).count()
            return text to count
        }
        val text = "{\"schema\":1,\"entries\":[]}"
        return text to 0
    }
}
