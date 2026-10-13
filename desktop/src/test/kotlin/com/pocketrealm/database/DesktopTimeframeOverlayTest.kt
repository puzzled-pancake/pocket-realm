package com.pocketrealm.database

import com.pocketrealm.client.GeneratedTimeframeOverlayCatalog
import com.pocketrealm.client.TimeframeStages
import com.pocketrealm.desktop.requireNativeIfDemanded
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.pocketrealm.client.ActiveFlavor
import org.junit.After
import org.junit.Before

/**
 * Design-timeframe §2/§4.3: the desktop stage-overlay applier — desired vs
 * applied resolved against the durable pocketrealm_meta ledger, reversible
 * INSERT OR IGNORE overlays executed against the world DB, the DEFAULT
 * stage recording without touching the world DB (existing worlds
 * unaffected), and every hostile shape refused loud. Runs against the real
 * pocket_sqlite seam (skips cleanly where it is not staged).
 */
class DesktopTimeframeOverlayTest {
    /** W4 flip fixture: the sole-qualified degrade is retired; machinery
     * tests that do not exercise the Home pick flow pin the VANILLA lane
     * (holder select) so the resolvers see one honest lane. */
    @Before
    fun pinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After
    fun unpinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
    }

    @get:Rule
    val folder = TemporaryFolder()

    private val seamDll = File("../native/.build-win-x86_64/sqlite-seam-build/pocket_sqlite.dll")

    /** The seed's own game_event/conditions shape (measured against the
     * pinned classicmangos transcript; the applier targets exactly these
     * columns). */
    private fun stageWorldDb(datadir: File) {
        datadir.mkdirs()
        DesktopSqliteConnection(
            DatabaseSqliteControlPlane.databaseFile(datadir, "classicmangos").absolutePath,
        ).use { db ->
            db.exec(
                "CREATE TABLE game_event (" +
                    "entry mediumint NOT NULL, " +
                    "schedule_type int NOT NULL DEFAULT 0, " +
                    "occurence INTEGER NOT NULL DEFAULT 86400, " +
                    "length INTEGER NOT NULL DEFAULT 43200, " +
                    "holiday mediumint NOT NULL DEFAULT 0, " +
                    "linkedTo mediumint NOT NULL DEFAULT 0, " +
                    "description TEXT, PRIMARY KEY (entry));",
            )
            db.exec(
                "CREATE TABLE conditions (" +
                    "condition_entry INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "type INTEGER NOT NULL DEFAULT 0, " +
                    "value1 mediumint NOT NULL DEFAULT 0, " +
                    "value2 mediumint NOT NULL DEFAULT 0, " +
                    "value3 mediumint NOT NULL DEFAULT 0, " +
                    "value4 mediumint NOT NULL DEFAULT 0, " +
                    "flags INTEGER NOT NULL DEFAULT 0, comments TEXT);",
            )
        }
    }

    private fun counts(datadir: File): Pair<Long, Long> {
        DesktopSqliteConnection(
            DatabaseSqliteControlPlane.databaseFile(datadir, "classicmangos").absolutePath,
        ).use { db ->
            val events = db.queryLong("SELECT COUNT(*) FROM game_event;") ?: 0L
            val conditions = db.queryLong("SELECT COUNT(*) FROM conditions;") ?: 0L
            return events to conditions
        }
    }

    private fun markerState(datadir: File, entry: Long): Long? {
        DesktopSqliteConnection(
            DatabaseSqliteControlPlane.databaseFile(datadir, "classicmangos").absolutePath,
        ).use { db ->
            return db.queryLong(
                "SELECT schedule_type FROM game_event WHERE entry = $entry;",
            )
        }
    }

    private fun stageMariadbMarker(datadir: File) {
        val markerFile = File(
            datadir.parentFile,
            DatabaseDurableState.ACTIVE_PROVIDER_MARKER_NAME,
        )
        markerFile.writeText(
            DatabaseDurableState.activeProviderMarker(
                DatabaseDurableState.ProviderMode.MARIADB,
                DatabaseRuntimeContract.WINDOWS_PROVIDER_ID,
                "11111111-1111-4111-8111-111111111111",
            ),
        )
    }

    @Test
    fun defaultStageRecordsWithoutTouchingTheWorldDb() {
        requireNativeIfDemanded("sqlite seam", seamDll)
        val datadir = folder.newFolder("sqlite-datadir")
        stageWorldDb(datadir)
        // W4 flip: the lane under test is the vanilla lane (the sole-
        // qualified .single() degrade is retired).
        val defaultStage = TimeframeStages.defaultStageId("vanilla")
        val applied = DesktopTimeframeOverlay.prepareForStart(datadir, defaultStage)
        assertEquals(defaultStage, applied.appliedStage)
        assertEquals("default-seed", applied.overlaySha256)
        // The seed IS the default: zero world-DB rows added.
        assertEquals(0L to 0L, counts(datadir))
        assertEquals(defaultStage, DesktopTimeframeOverlay.readAppliedStage(datadir))
        assertTrue(applied.appliedAtMs > 0)
    }

    @Test
    fun readAppliedStageIsNullOrQuietWithoutAMetaDatabase() {
        requireNativeIfDemanded("sqlite seam", seamDll)
        val datadir = folder.newFolder("sqlite-datadir-bare")
        assertNull(DesktopTimeframeOverlay.readAppliedStage(datadir))
    }

    @Test
    fun nonDefaultStageAppliesTheStagedOverlayAndRecordsIt() {
        requireNativeIfDemanded("sqlite seam", seamDll)
        val datadir = folder.newFolder("sqlite-datadir")
        stageWorldDb(datadir)
        val applied = DesktopTimeframeOverlay.prepareForStart(datadir, "vanilla-1.5")
        assertEquals("vanilla-1.5", applied.appliedStage)
        assertEquals(
            GeneratedTimeframeOverlayCatalog.overlaySha256["vanilla-1.5"],
            applied.overlaySha256,
        )
        // The stage marker scaffolding landed: vanilla-1.5 is order 4 ->
        // marker event 8000005 + its CONDITION_ACTIVE_GAME_EVENT (12) row
        // (value1 = the marker entry; the 8000001+ ranges verified to hold
        // zero rows in a real seeded datadir).
        assertEquals(1L to 1L, counts(datadir))
        assertEquals(0L, markerState(datadir, 8000005))
        DesktopSqliteConnection(
            DatabaseSqliteControlPlane.databaseFile(datadir, "classicmangos").absolutePath,
        ).use { db ->
            assertEquals(12L, db.queryLong("SELECT type FROM conditions WHERE condition_entry = 8000005;"))
            assertEquals(8000005L, db.queryLong("SELECT value1 FROM conditions WHERE condition_entry = 8000005;"))
        }
    }

    @Test
    fun reapplyingTheAppliedStageIsAnIdempotentShortCircuit() {
        requireNativeIfDemanded("sqlite seam", seamDll)
        val datadir = folder.newFolder("sqlite-datadir")
        stageWorldDb(datadir)
        val first = DesktopTimeframeOverlay.prepareForStart(datadir, "vanilla-1.5")
        val second = DesktopTimeframeOverlay.prepareForStart(datadir, "vanilla-1.5")
        assertEquals(first, second)
        assertEquals(1L to 1L, counts(datadir))
    }

    @Test
    fun backwardMoveReappliesTheEarlierOverlayAndDestroysNothing() {
        requireNativeIfDemanded("sqlite seam", seamDll)
        val datadir = folder.newFolder("sqlite-datadir")
        stageWorldDb(datadir)
        DesktopTimeframeOverlay.prepareForStart(datadir, "vanilla-1.9")
        assertEquals(1L to 1L, counts(datadir))
        // Backward to 1.1: the earlier overlay re-applies over the same
        // datadir — its OWN marker lands, the later stage's rows stay
        // (never DELETE, design §2).
        DesktopTimeframeOverlay.prepareForStart(datadir, "vanilla-1.1")
        assertEquals("vanilla-1.1", DesktopTimeframeOverlay.readAppliedStage(datadir))
        assertEquals(2L to 2L, counts(datadir))
        assertEquals(0L, markerState(datadir, 8000001))
        assertEquals(0L, markerState(datadir, 8000009))
    }

    @Test
    fun aDestructiveOverlayStatementIsRefusedBeforeAnyExecution() {
        requireNativeIfDemanded("sqlite seam", seamDll)
        val datadir = folder.newFolder("sqlite-datadir")
        stageWorldDb(datadir)
        // The real pipeline always hands the applier SPLIT statements (the
        // shared scanner); a hostile DESTRUCTIVE statement in that list is
        // refused by the re-checked policy before any execution.
        val hostile = listOf(
            "INSERT OR IGNORE INTO game_event (entry) VALUES (8000001);",
            "DELETE FROM game_event;",
        )
        try {
            DesktopTimeframeOverlay.applyOverlay(datadir, "vanilla-1.1", hostile)
            fail("a destructive statement must be refused")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "refusal must name the flag-set policy: ${expected.message}",
                "non-flag-set" in expected.message!!,
            )
        }
        // Nothing executed: the datadir is untouched.
        assertEquals(0L to 0L, counts(datadir))
        assertNull(DesktopTimeframeOverlay.readAppliedStage(datadir))
    }

    @Test
    fun missingWorldDatabaseRefusesLoudly() {
        requireNativeIfDemanded("sqlite seam", seamDll)
        val datadir = folder.newFolder("sqlite-datadir")
        try {
            DesktopTimeframeOverlay.prepareForStart(datadir, "vanilla-1.5")
            fail("an unseeded datadir must refuse a non-default stage")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "refusal must name the missing world DB: ${expected.message}",
                "world database is missing" in expected.message!!,
            )
        }
    }

    @Test
    fun anUnstagedStageRefusesLoudly() {
        requireNativeIfDemanded("sqlite seam", seamDll)
        val datadir = folder.newFolder("sqlite-datadir")
        stageWorldDb(datadir)
        try {
            // A registered stage of a lane whose overlays are not staged
            // in v1 (tbc/wotlk: the qualified lane only).
            DesktopTimeframeOverlay.prepareForStart(datadir, "tbc-2.4.3")
            fail("an unstaged overlay must refuse")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "refusal must name the staged set: ${expected.message}",
                "no overlay staged" in expected.message!!,
            )
        }
    }

    @Test
    fun aMariadbMarkerRoutesEveryStageThroughTheMariaDbEngineLeg() {
        // PROVIDER-AWARE (the MariaDB-first default-flip): a durable
        // MARIADB active-provider marker hands the WHOLE overlay — the
        // non-default stages AND the default one — to the MariaDB engine
        // leg; this SQLite datadir is a retained artifact whose ledger
        // must never speak for the MariaDB world.
        requireNativeIfDemanded("sqlite seam", seamDll)
        val datadir = folder.newFolder("sqlite-datadir")
        stageWorldDb(datadir)
        stageMariadbMarker(datadir)
        val staged = mutableListOf<String>()
        fun leg(stage: String) = DesktopTimeframeOverlay.AppliedState(stage, "mariadb-leg-sha", 7L).also {
            staged.add(stage)
        }
        // A non-default stage routes (applies on MariaDB, not refused).
        val applied = DesktopTimeframeOverlay.prepareForStart(datadir, "vanilla-1.5", ::leg)
        assertEquals("vanilla-1.5", applied.appliedStage)
        // The DEFAULT stage routes too — the live provider's ledger is
        // MariaDB's pocketrealm_meta, never the retained SQLite file.
        // W4 flip: the lane under test is the vanilla lane (the sole-
        // qualified .single() degrade is retired).
        val defaultStage = TimeframeStages.defaultStageId("vanilla")
        DesktopTimeframeOverlay.prepareForStart(datadir, defaultStage, ::leg)
        assertEquals(listOf("vanilla-1.5", defaultStage), staged)
        // The retained SQLite datadir stays untouched by both legs: no
        // overlay rows, no meta ledger, no recorded stage.
        assertEquals(0L to 0L, counts(datadir))
        assertNull(DesktopTimeframeOverlay.readAppliedStage(datadir))
    }

    @Test
    fun aMariadbMarkerWithoutTheEngineLegRefusesLoudly() {
        // The SQLite execution leg must never write a MariaDB world's
        // stage: a MARIADB marker with no engine leg supplied refuses
        // (the caller wired the lane wrong — a loud refusal, never a
        // silent SQLite substitute).
        requireNativeIfDemanded("sqlite seam", seamDll)
        val datadir = folder.newFolder("sqlite-datadir")
        stageWorldDb(datadir)
        stageMariadbMarker(datadir)
        try {
            DesktopTimeframeOverlay.prepareForStart(datadir, "vanilla-1.5")
            fail("the MARIADB lane must refuse without its engine leg")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "refusal must name the MariaDB engine leg: ${expected.message}",
                "MariaDB engine leg" in expected.message!!,
            )
        }
        // And nothing was touched on the refused path.
        assertEquals(0L to 0L, counts(datadir))
        assertNull(DesktopTimeframeOverlay.readAppliedStage(datadir))
    }

    @Test
    fun aPreexistingLedgerCannotBypassTheMariadbLaneGuard() {
        // The applied==desired fast path must not out-rank the provider
        // routing: select a non-default stage on the SQLite lane (the
        // meta ledger records it), THEN switch the provider to MariaDB
        // (the marker is rewritten; the retained datadir and its ledger
        // survive untouched) — a world start at that stage must STILL
        // hand the overlay to the MariaDB engine leg, else a MariaDB
        // world boots naming a stage its DB never received.
        requireNativeIfDemanded("sqlite seam", seamDll)
        val datadir = folder.newFolder("sqlite-datadir")
        stageWorldDb(datadir)
        DesktopTimeframeOverlay.prepareForStart(datadir, "vanilla-1.5")
        assertEquals("vanilla-1.5", DesktopTimeframeOverlay.readAppliedStage(datadir))
        stageMariadbMarker(datadir)
        var legRuns = 0
        val applied = DesktopTimeframeOverlay.prepareForStart(datadir, "vanilla-1.5") {
            legRuns++
            DesktopTimeframeOverlay.AppliedState(it, "mariadb-leg-sha", 9L)
        }
        assertEquals("the retained SQLite ledger must not short-circuit the MariaDB leg", 1, legRuns)
        assertEquals("vanilla-1.5", applied.appliedStage)
        // The retained SQLite ledger is untouched by the MariaDB leg.
        assertEquals("vanilla-1.5", DesktopTimeframeOverlay.readAppliedStage(datadir))
    }

    @Test
    fun theMetaUpsertEscapesALiteralQuote() {
        val sql = TimeframeOverlayPlan.stageStateUpsert("vanilla-1'1", "sha'", 5L)
        assertTrue("''" in sql)
        // And it executes: the upsert keeps exactly one row and updates in
        // place (the seam round trip).
        requireNativeIfDemanded("sqlite seam", seamDll)
        val datadir = folder.newFolder("sqlite-datadir-meta")
        stageWorldDb(datadir)
        DesktopTimeframeOverlay.prepareForStart(datadir, "vanilla-1.5")
        DesktopTimeframeOverlay.prepareForStart(datadir, "vanilla-1.1")
        DesktopSqliteConnection(
            DatabaseSqliteControlPlane.databaseFile(
                datadir, DatabaseSqliteControlPlane.META_DATABASE,
            ).absolutePath,
        ).use { meta ->
            assertEquals(1L, meta.queryLong("SELECT COUNT(*) FROM timeframe_stage_state;"))
            assertEquals("vanilla-1.1", meta.queryText("SELECT applied_stage FROM timeframe_stage_state WHERE id = 1;"))
        }
    }
}
