package com.pocketrealm.database

import android.content.ContentResolver
import android.database.CharArrayBuffer
import android.database.ContentObserver
import android.database.Cursor
import android.database.DataSetObserver
import android.net.Uri
import android.os.Bundle
import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.TimeframeStages
import com.pocketrealm.client.TwoQualifiedFlavors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.After
import org.junit.Before

/**
 * Design-timeframe /: the SHARED stage-overlay decision surface
 * (TimeframeOverlayPlan) — the exact statements and policy that both platform
 * appliers execute. The catalog SQL is split by the seed scanner and every
 * statement must be a flag-set INSERT OR IGNORE; the default stage verdict,
 * the unstaged refusal, and the meta bookkeeping text are pinned here
 * (pure JVM — the SQLiteDatabase execution leg is the device lane, the
 * documented posture for the framework API).
 */
class TimeframeOverlayPlanTest {
    /** flip fixture: the overlay plan resolves the ACTIVE lane
     * through the holder — pin the VANILLA lane (holder select). */
    @Before
    fun pinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After
    fun unpinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
    }


    @Test
    fun nonVanillaNonFinalStagesRefuseUnstagedLoudly() {
        // W7 R5-1: this build stages vanilla overlays ONLY, so a
        // stored non-final tbc/wotlk stage must refuse the statements plan
        // loudly (both provider legs throw this refusal at world start) —
        // today's behavior made explicit so the Settings copy and the
        // humanized failure stay pinned to it.
        for (stageId in listOf("tbc-2.0.1", "tbc-2.3", "wotlk-3.0.2", "wotlk-3.3")) {
            val ex = assertThrows(IllegalStateException::class.java) {
                TimeframeOverlayPlan.statementsFor(stageId)
            }
            assertTrue(
                "refusal for $stageId lost its unstaged marker: " + ex.message,
                ex.message!!.contains("no overlay staged for"),
            )
        }
    }

    @Test
    fun vanillaNonFinalStagesRemainStaged() {
        // the other half of the posture: vanilla's own non-final stages
        // DO carry overlays, so the refusal is a flavor fact, never a
        // broken catalog.
        val statements = TimeframeOverlayPlan.statementsFor("vanilla-1.5")
        assertTrue(statements.isNotEmpty())
    }

    @Test
    fun defaultStageIsTheQualifiedLanesFinalPatch() {
        val default = TimeframeStages.defaultStageId(
            TimeframeOverlayPlan.qualifiedLane().flavorId,
        )
        assertTrue(TimeframeOverlayPlan.isDefaultStage(default))
        // "" is not confValue's output (the conf always names a concrete
        // stage), but the default verdict must not accidentally cover it.
        assertNotEquals(default, "")
        // A non-final stage of the qualified lane is NOT the default.
        assertTrue(!TimeframeOverlayPlan.isDefaultStage("vanilla-1.5"))
    }

    @Test
    fun theOverlayLaneResolvesThroughTheActiveFlavorMachine() {
        // The plan's lane used to
        // be `qualified().singleOrNull() ?: VANILLA` - a silent fallback
        // that diverged from the ACTIVE lane the conf stage resolves
        // through at a multi-qualified flip. The plan must never pick a
        // lane silently: ambiguous (no pick) fails loud...
        // The pinned SURFACE
        // (qualifiedLane) must be invoked INSIDE the try - install() only
        // swaps the qualified registry and cannot throw; the resolver the
        // plan is bound to is what refuses the ambiguous state.
        try {
            TwoQualifiedFlavors.install()
            TimeframeOverlayPlan.qualifiedLane()
            fail("two qualified flavors with no pick must fail loud")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "the ambiguity refusal must name the machine's message: ${expected.message}",
                "expected exactly one qualified flavor" in expected.message!!,
            )
        } finally {
            ActiveFlavor.reset()
        }
        // ...and with tbc selected the lane - and therefore the overlay's
        // world database - follows the ACTIVE flavor, never a vanilla
        // fallback (the invariant-4 cross-flavor write direction).
        TwoQualifiedFlavors.install(selection = "tbc")
        try {
            assertEquals(TwoQualifiedFlavors.TBC, TimeframeOverlayPlan.qualifiedLane())
            assertEquals(
                TwoQualifiedFlavors.TBC.databases.world,
                TimeframeOverlayPlan.worldDatabase(),
            )
            assertTrue(
                "the overlay must never target the vanilla world DB while " +
                    "the tbc lane is active",
                "classicmangos" != TimeframeOverlayPlan.worldDatabase(),
            )
            // the default-stage verdict covers tbc's own final patch (the
            // seed IS the final patch - no overlay at the default stage)
            assertTrue(
                TimeframeOverlayPlan.isDefaultStage(
                    TimeframeStages.defaultStageId("tbc"),
                ),
            )
        } finally {
            ActiveFlavor.reset()
        }
    }

    @Test
    fun theWorldProcessLaneBindingThreadsThePersistedPick() {
        // The :world process
        // never seeds the in-memory ActiveFlavor holder (the selection is
        // :ui-local), so a bare holder resolve refuses at a multi-qualified
        // flip even with a valid persisted pick. WorldRuntimeService
        // installs the plan's lane binding at :world onCreate, threading the
        // persisted Home selection as a value (the ActiveFlavorState +
        // Settings form the :supervisor twins use). This test simulates the
        // world-process state machine: three qualified flavors, NO holder
        // pick, persisted pick "tbc" — the bound resolver must resolve tbc
        // while the default (holder-machine) resolver refuses.
        val threeQualified = TwoQualifiedFlavors.lanes +
            com.pocketrealm.client.ClientFlavors.WOTLK.copy(qualified = true)
        val persistedPick = "tbc"
        // The binding WorldRuntimeService installs: persisted selection
        // threaded as a value through the pure state machine.
        val worldResolver: () -> com.pocketrealm.client.ClientFlavor = {
            com.pocketrealm.client.ActiveFlavorState(persistedPick)
                .resolve(threeQualified, "the world lane binding test")
        }
        try {
            // Without the binding, the default (holder machine) refuses the
            // same state the binding resolves - the reason the seam exists.
            ActiveFlavor.reset()
            ActiveFlavor.qualifiedSource = { threeQualified }
            try {
                com.pocketrealm.client.ActiveFlavor.resolve(
                    "the unbound world lane must refuse",
                )
                fail("3 qualified flavors with no holder pick must fail loud")
            } catch (expected: IllegalStateException) {
                assertTrue(
                    "expected exactly one qualified flavor" in expected.message!!,
                )
            }
            // With the binding installed (what onCreate does), the plan
            // resolves the persisted pick.
            TimeframeOverlayPlan.installLaneResolver(worldResolver)
            assertEquals(
                TwoQualifiedFlavors.TBC,
                TimeframeOverlayPlan.qualifiedLane(),
            )
            assertEquals(
                TwoQualifiedFlavors.TBC.databases.world,
                TimeframeOverlayPlan.worldDatabase(),
            )
        } finally {
            // Restore the default holder-machine resolver for every other
            // test in this class.
            TimeframeOverlayPlan.installLaneResolver {
                ActiveFlavor.resolve(
                    "the timeframe overlay plan resolves the qualified lane",
                )
            }
            ActiveFlavor.reset()
        }
    }

    @Test
    fun theStagedOverlayPlanIsTwoFlagSetStatementsPerStage() {
        val statements = TimeframeOverlayPlan.statementsFor("vanilla-1.5")
        // The v1 marker scaffolding: one game_event marker + one
        // CONDITION_ACTIVE_GAME_EVENT row, INSERT OR IGNORE only.
        assertEquals(2, statements.size)
        statements.forEach { statement ->
            val firstCode = statement.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("--") }
            assertTrue(
                "every statement must be a flag-set INSERT OR IGNORE: $firstCode",
                firstCode!!.uppercase().startsWith("INSERT OR IGNORE INTO"),
            )
        }
        assertTrue(statements[0].contains("INTO game_event"))
        assertTrue(statements[1].contains("INTO conditions"))
        // The vanilla-1.5 marker ids (order 4 → the reserved ranges,
        // verified against a real seeded datadir).
        assertTrue("8000005" in statements[0])
        assertTrue("8000005" in statements[1] && ", 12," in statements[1])
    }

    @Test
    fun everyStagedOverlayPassesThePolicyAndCoversExactlyTheVanillaStages() {
        // The staged set is the generated catalog's own key set; v1 = the
        // qualified lane only.
        // Compared as a SET: the catalog map's key order is lexicographic,
        // not the timeline order the registry lists.
        val staged = com.pocketrealm.client.GeneratedTimeframeOverlayCatalog
            .overlaySha256.keys
        assertEquals(
            TimeframeStages.stagesFor(TimeframeOverlayPlan.qualifiedLane().flavorId)
                .map { it.id }
                .toSet(),
            staged,
        )
        staged.sorted().forEach { stage ->
            assertEquals(2, TimeframeOverlayPlan.statementsFor(stage).size)
        }
    }

    @Test
    fun anUnstagedStageIsRefusedLoudly() {
        try {
            TimeframeOverlayPlan.statementsFor("tbc-2.4.3")
            fail("an unstaged overlay must be refused")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "refusal must name the staged set: ${expected.message}",
                "no overlay staged" in expected.message!!,
            )
        }
    }

    @Test
    fun aDestructiveStatementIsRefusedByThePolicy() {
        val hostile = listOf(
            DatabaseSqliteControlPlane.SeedStatement(
                0, 0, "INSERT OR IGNORE INTO game_event (entry) VALUES (8000001);",
            ),
            DatabaseSqliteControlPlane.SeedStatement(1, 0, "DELETE FROM game_event;"),
        )
        try {
            TimeframeOverlayPlan.policyChecked(hostile, "vanilla-1.1")
            fail("a destructive statement must be refused")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "refusal must name the flag-set policy: ${expected.message}",
                "non-flag-set" in expected.message!!,
            )
        }
        // An EMPTY plan is refused too (a silent no-op overlay is a lie).
        try {
            TimeframeOverlayPlan.policyChecked(emptyList(), "vanilla-1.1")
            fail("an empty plan must be refused")
        } catch (expected: IllegalStateException) {
            assertTrue("stages no statements" in expected.message!!)
        }
    }

    @Test
    fun theMetaBookkeepingTextEscapesAndReadsBack() {
        val upsert = TimeframeOverlayPlan.stageStateUpsert("vanilla-1'1", "sha'", 5L)
        assertTrue("''" in upsert)
        assertTrue(upsert.startsWith("INSERT INTO timeframe_stage_state"))
        assertTrue("ON CONFLICT(id) DO UPDATE" in upsert)
        assertNull(TimeframeOverlayPlan.overlaySha256("tbc-2.4.3"))
        assertEquals(
            "the world DB follows the qualified lane's registry",
            TimeframeOverlayPlan.qualifiedLane().databases.world,
            TimeframeOverlayPlan.worldDatabase(),
        )
    }

    @Test
    fun thePragmaLegReadsAnEmptyCursorAsSuccessNeverAFailure() {
        // LVF-server-db-009: the applier's pragma leg ran EVERY pragma
        // through compileStatement(pragma).simpleQueryForString(); the
        // set-only connection assignments (synchronous, busy_timeout,
        // foreign_keys, secure_delete, cache_size) return NO row, so the
        // read threw SQLiteDoneException with a null message - surfaced as
        // "DB-CONFIG: PRAGMA synchronous=NORMAL; failed: null" through the
        // timeframe-overlay wrapper and refusing the whole world start.
        // The engine's own twin (DatabaseEngine.execPragma) is the
        // semantics the overlay leg must mirror: rawQuery, an EMPTY cursor
        // reads as null and is SUCCESS; only genuine errors (bad syntax,
        // unknown pragma) keep the DB-CONFIG vocabulary. Pinned here
        // against a cursor fake - the SQLiteDatabase execution leg itself
        // stays the documented device lane.
        assertNull(TimeframeOverlayApplier.pragmaRowText(SingleCellCursor(null)))
        assertEquals("wal", TimeframeOverlayApplier.pragmaRowText(SingleCellCursor("wal")))
    }

    @Test
    fun everyPragmaTheApplierExecutesStaysOnTheEmptyTolerantStatementSurface() {
        // The full pragma vocabulary the applier feeds execPragma: the
        // connection policy on every writable open plus the WAL checkpoint
        // before the meta publish. The incident statement must stay a
        // set-only ASSIGNMENT - its healthy execution yields no row, which
        // is exactly why a row-read leg refused world starts - and nothing
        // may enter the vocabulary in a non-pragma shape the empty-
        // tolerant read does not cover.
        val executed = DatabaseSqliteConfigPolicy.renderConnectionPragmas() +
            TimeframeOverlayApplier.CHECKPOINT_PRAGMA
        assertTrue("PRAGMA synchronous=NORMAL;" in executed)
        assertTrue("PRAGMA wal_checkpoint(TRUNCATE);" in executed)
        executed.forEach { pragma ->
            assertTrue(
                "must stay a bare PRAGMA statement: $pragma",
                pragma.startsWith("PRAGMA ") && pragma.endsWith(";"),
            )
        }
    }
}

/**
 * One-optional-row, one-string-column android.database.Cursor fake: the
 * minimum the pragma read semantics need (moveToFirst + getString),
 * everything else inert. The JVM lane has no SQLiteDatabase, so the fake
 * exists only to pin the empty-is-success read - never to simulate a
 * database.
 */
private class SingleCellCursor(private val row: String?) : Cursor {
    private var positioned = false
    private var closed = false

    override fun getCount(): Int = if (row == null) 0 else 1
    override fun getPosition(): Int = if (positioned) 0 else -1
    override fun move(offset: Int): Boolean = false
    override fun moveToPosition(position: Int): Boolean = false
    override fun moveToFirst(): Boolean {
        positioned = row != null
        return positioned
    }

    override fun moveToLast(): Boolean = moveToFirst()
    override fun moveToNext(): Boolean = false
    override fun moveToPrevious(): Boolean = false
    override fun isFirst(): Boolean = positioned
    override fun isLast(): Boolean = positioned
    override fun isBeforeFirst(): Boolean = row == null
    override fun isAfterLast(): Boolean = row == null
    override fun getColumnIndex(columnName: String?): Int = 0
    override fun getColumnIndexOrThrow(columnName: String?): Int = 0
    override fun getColumnName(columnIndex: Int): String = "value"
    override fun getColumnNames(): Array<String> = arrayOf("value")
    override fun getColumnCount(): Int = 1
    override fun getBlob(columnIndex: Int): ByteArray = error("the pragma read never fetches blobs")
    override fun getString(columnIndex: Int): String? = row
    override fun copyStringToBuffer(columnIndex: Int, buffer: CharArrayBuffer?) = Unit
    override fun getShort(columnIndex: Int): Short = 0
    override fun getInt(columnIndex: Int): Int = 0
    override fun getLong(columnIndex: Int): Long = 0L
    override fun getFloat(columnIndex: Int): Float = 0f
    override fun getDouble(columnIndex: Int): Double = 0.0
    override fun getType(columnIndex: Int): Int = Cursor.FIELD_TYPE_STRING
    override fun isNull(columnIndex: Int): Boolean = row == null
    @Deprecated("the framework deprecates cursor lifecycle calls", ReplaceWith("close()"))
    override fun deactivate() {}
    @Deprecated("the framework deprecates cursor lifecycle calls")
    override fun requery(): Boolean = false
    override fun close() {
        closed = true
    }

    override fun isClosed(): Boolean = closed
    override fun registerContentObserver(observer: ContentObserver?) = Unit
    override fun unregisterContentObserver(observer: ContentObserver?) = Unit
    override fun registerDataSetObserver(observer: DataSetObserver?) = Unit
    override fun unregisterDataSetObserver(observer: DataSetObserver?) = Unit
    override fun setExtras(extras: Bundle?) = Unit
    override fun setNotificationUri(cr: ContentResolver?, uri: Uri?) = Unit
    override fun getNotificationUri(): Uri? = null
    override fun getWantsAllOnMoveCalls(): Boolean = false
    override fun getExtras(): Bundle = Bundle()
    override fun respond(extras: Bundle?): Bundle = Bundle()
}
