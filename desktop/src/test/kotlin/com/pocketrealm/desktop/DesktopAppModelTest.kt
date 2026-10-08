package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.database.DatabaseSqliteControlPlane.DATABASES
import com.pocketrealm.database.DesktopSeedLocator
import com.pocketrealm.database.DesktopSqliteConnection
import com.pocketrealm.database.DesktopSqliteSeals
import com.pocketrealm.database.DesktopSqliteSealMarkers
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.supervisor.RuntimeSnapshot
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * GAP-1 (was DESKPROD-1): the restore consent gate at the app-model level,
 * on a REAL model (supervisor + journal + single-instance lock, the
 * DesktopAppModelSpentWorldTest posture). The desktop build has no compose
 * UI-test dependency, so the dialog composition itself is pinned by this
 * gate + review; what IS pinned here is the model half of the contract:
 * the gate blocks exactly while the supervisor holds a world, and
 * [DesktopAppModel.restoreBackup] refuses with the SAME reason the UI
 * disabled its buttons on — before the backend is engaged, so the backend's
 * runtimes-at-rest refusal (the final arbiter, untouched) is never the
 * first thing a phase-blind tap hits.
 */
class DesktopAppModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Seeds an ACTIVE local generation (clean WORLD_READY) into the
     * journal the model will load — the "realm is up" state. The session id
     * must be a UUID (the shared codec validates the format). */
    private fun seedActiveLocalGeneration(roots: DesktopStorageRoots) {
        DesktopSupervisorJournal(roots.supervisorJournalDir).write(
            RuntimeSnapshot(
                sessionId = "12345678-1234-1234-1234-123456789abc",
                requestedProfile = DesktopAppModel.DESKTOP_PROFILE,
                runtimeMode = RuntimeMode.LOCAL,
                phase = RuntimePhase.WORLD_READY,
                clean = true,
                lastDurableAction = "world-ready-client-not-requested",
            ),
        )
    }

    @Test fun restoreGateBlocksWhileTheSupervisorHoldsAWorld() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-world-ready"))
        seedActiveLocalGeneration(roots)
        DesktopAppModel(roots).use { model ->
            assertTrue("the seed must hold a world", model.state.value.phase == RuntimePhase.WORLD_READY)
            val gate = model.restoreGate()
            assertTrue("a held world must block the gate: $gate", gate is RestoreGate.Blocked)
            val reason = (gate as RestoreGate.Blocked).reason
            assertTrue("the reason must name the phase: $reason", "WORLD_READY" in reason)
            assertTrue("the reason must say what to do: $reason", "stop the realm" in reason)
        }
    }

    @Test fun restoreGateReadyWhenSupervisorIsAtRest() {
        DesktopAppModel(DesktopStorageRoots(folder.newFolder("roots-at-rest"))).use { model ->
            assertTrue("a fresh model is at rest", model.state.value.phase == RuntimePhase.STOPPED)
            assertTrue(
                "an at-rest supervisor must leave the gate ready: ${model.restoreGate()}",
                model.restoreGate() is RestoreGate.Ready,
            )
        }
    }

    @Test fun restoreBackupRefusesWithTheSameReasonWhenTheGateBlocks() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-refused"))
        seedActiveLocalGeneration(roots)
        DesktopAppModel(roots).use { model ->
            // The reason the UI disabled its restore buttons with...
            val gate = model.restoreGate()
            assertTrue("precondition: the gate blocks", gate is RestoreGate.Blocked)
            val uiReason = (gate as RestoreGate.Blocked).reason
            // ...is the SAME reason the model refuses with, before the
            // backend is engaged (a snapshot-missing or at-rest backend
            // refusal would carry different copy — this pins the gate as
            // the refusing layer, not a relayed backend error).
            val operation = runBlocking { model.restoreBackup("manual-nonexistent-1") }
            assertFalse("a blocked gate must refuse the restore", operation.ok)
            assertTrue(
                "the refusal keeps the RESTORE-REFUSED marker: ${operation.detail}",
                operation.detail.startsWith("RESTORE-REFUSED: "),
            )
            assertTrue(
                "the refusal carries the gate's own reason verbatim: ${operation.detail}",
                uiReason in operation.detail,
            )
        }
    }

    // ------------------------------------------------------------------
    // GAP-2 (was DESKPROD-2): the restore proof boots a MINIMAL spec, not
    // the user's — bots-disabled so the committed state is exactly the
    // picked snapshot, and the one-lifetime spend DISCLOSED instead of
    // discovered as WRONG_STATE on the next Start. (A bots-disabled proof
    // no longer exercises the profile-conf staging path during restore —
    // acceptable, because every ordinary Start exercises it; GAP-2 risk
    // note (a). Do not "fix" this back.)
    // ------------------------------------------------------------------

    /** A real built-in bot profile id (the registry's balanced-100). */
    private val selectedBotProfile = "mobile-balanced-b100-v1"

    /**
     * GAP-2: the proof's launch spec carries the forced bots-disabled
     * profile, not the settings-resolved one. Deterministic without any
     * native lane: a PARTIALLY seeded datadir (one of four databases)
     * makes the supervisor's preflight refuse DB-NOT-SEEDED before any
     * component starts, and the preflight-failure snapshot publishes the
     * spec's own requestedProfile — so the spec each path built is
     * observable, and the proof's must be the override.
     */
    @Test fun restoreProofBootsTheBotsDisabledProfile() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-proof-profile"))
        // D19: this leg pins the SQLITE dev/compat lane's datadir gate —
        // select it explicitly (the shipped default is MariaDB).
        selectSqliteProvider(roots)
        DesktopAppModel(roots).use { model ->
            // Settings select a real bot profile — the resolution the
            // ORDINARY start carries (and provisions).
            model.updateSettings { it.copy(botProfileId = selectedBotProfile) }
            assertEquals(
                "precondition: the settings must resolve the selected profile",
                selectedBotProfile,
                model.backend.activeBotProfileId(),
            )
            // One of the four seeded databases present: the datadir gate
            // refuses "partially seeded" deterministically (no bootstrap
            // path, no identity comparison, no natives).
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, "classicrealmd")
                .apply { parentFile?.mkdirs(); writeBytes(ByteArray(0)) }

            // The production proof refuses honestly at the datadir gate —
            // and the failure snapshot publishes the spec IT built.
            val proofFailure = runBlocking { model.bootRestoredStackWorldReadyProof() }
            assertNotNull("the proof must refuse on a partially seeded root", proofFailure)
            assertTrue(
                "the refusal must be the datadir gate's own copy: $proofFailure",
                "DB-NOT-SEEDED" in proofFailure!!,
            )
            assertEquals(
                "the proof's spec must carry the forced bots-disabled profile",
                DesktopAppModel.DESKTOP_PROFILE,
                model.state.value.requestedProfile,
            )

            // Differential: an ORDINARY start through the same model keeps
            // carrying the settings-resolved profile — the override is the
            // proof's, not a global behavior change.
            val ordinary = runBlocking { model.startRealm(includeClient = false) }
            assertFalse(ordinary.ok)
            assertEquals(
                "the ordinary start keeps the settings-resolved profile",
                selectedBotProfile,
                model.state.value.requestedProfile,
            )
        }
    }

    /**
     * GAP-2: a committed restore's status copy names the DESK-25
     * consequence — the proof reached WORLD_READY, which spends this app
     * process's one world start, so the next start needs an app relaunch.
     * The proof is the injected seam here (the transaction discipline is
     * what the JVM leg pins); a passed proof ALWAYS leaves the lifetime
     * spent on the production path (startWorld sets the flag at READY,
     * DesktopRuntimeBackend.kt), so the disclosure is the honest copy for
     * exactly the state the seam asserts.
     */
    @Test fun restoreCompletionSurfacesTheLifetimeSpend() {
        assumeSeamStaged()
        val roots = DesktopStorageRoots(folder.newFolder("roots-lifetime-copy"))
        selectSqliteProvider(roots)
        seedSealedDatadir(roots)
        DesktopAppModel(roots).use { model ->
            val created = model.backend.createNamedBackup("lifetime")
            assertTrue("create must succeed: ${created.detail}", created.ok)
            val operation = runBlocking {
                model.restoreBackup(requireNotNull(created.snapshotId)) { null }
            }
            assertTrue("restore must commit: ${operation.detail}", operation.ok)
            assertTrue(
                "the completion copy must disclose the relaunch requirement: ${operation.detail}",
                "the next start needs an app relaunch" in operation.detail,
            )
            assertTrue(
                "the copy must also name the bots-disabled proof: ${operation.detail}",
                "bots disabled" in operation.detail,
            )
        }
    }

    /**
     * The init-block addon repository warm-up (DESK-10/83) must not die
     * silently: the fresh-install seed purpose failing with zero trace was
     * the defect — a failed construction now surfaces as the model's WARN
     * (the constructor thunk is the seam; the repository itself is a
     * process singleton, not constructible twice in one test JVM).
     */
    @Test fun addonRepositoryWarmUpLogsItsFailureInsteadOfSwallowingIt() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-addon-warmup"))
        DesktopAppModel(roots).use { model ->
            val before = DesktopLog.snapshot().size
            model.warmUpAddonRepository { throw IllegalStateException("registry seed failed") }
            val logged = DesktopLog.snapshot().drop(before)
            assertTrue(
                "the warm-up failure must be a WARN, not a silent swallow: $logged",
                logged.any {
                    it.level == "WARN" && it.kind == "AppModel" &&
                        "addon repository warm-up failed" in it.message
                },
            )
        }
    }

    /** The sqlite seam DLL this class's commit-path leg needs (skips
     * cleanly without it; hard-fails under -PrequireNatives). */
    private fun assumeSeamStaged() =
        requireNativeIfDemanded("sqlite seam", File("../native/.build-win-x86_64/sqlite-seam-build/pocket_sqlite.dll"))

    /** D19: pin these roots to the EXPLICIT SQLITE dev/compat selection so
     * the SQLite datadir fixtures below stay on the lane they test,
     * independent of the MariaDB shipped default. */
    private fun selectSqliteProvider(roots: DesktopStorageRoots) {
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            com.pocketrealm.storage.Settings.Snapshot(
                databaseProvider = com.pocketrealm.storage.Settings.DatabaseProvider.SQLITE,
            ).toJson(),
        )
    }

    /** A real seeded datadir (every database present, sealed the way the
     * seeder stamps at seed) — the DesktopSnapshotsRestoreTest fixture,
     * minimal form: the commit-path leg needs begin/commit to hash-restore
     * real snapshot bytes. */
    private fun seedSealedDatadir(roots: DesktopStorageRoots) {
        roots.sqliteDatadir.mkdirs()
        DATABASES.forEach { database ->
            DesktopSqliteConnection(
                DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).absolutePath,
            ).use { connection ->
                connection.queryText("PRAGMA journal_mode=WAL;")
                connection.exec("CREATE TABLE realm_note(value TEXT);")
            }
        }
        val identity = DesktopSeedLocator.locate()
            ?.let {
                runCatching { DesktopSqliteSeals.identityFromProvenance(it.provenance.readText()) }
                    .getOrNull()
            }
            ?: DesktopSqliteSeals.identityFromProvenance(
                JSONObject().put(
                    "seed_transcripts",
                    JSONObject().also { pins ->
                        DATABASES.forEach { database ->
                            pins.put(
                                database,
                                JSONObject()
                                    .put("sha256", "a".repeat(64))
                                    .put("gzip_sha256", "a".repeat(64))
                                    .put("size", 1L)
                                    .put("gzip_size", 1L),
                            )
                        }
                    },
                ).toString(),
            )
        val sealText = DesktopSqliteSeals.migrationSeal(identity, System.currentTimeMillis())
        DesktopSqliteSealMarkers.atomicWriteMarker(
            roots.sqliteDatadir, DesktopSqliteSeals.MIGRATION_MARKER_NAME, sealText,
        )
        DesktopSqliteSealMarkers.atomicWriteMarker(
            roots.sqliteDatadir,
            DesktopSqliteSeals.GENERATION_MARKER_NAME,
            DesktopSqliteSeals.generationMarker(
                generationUuid = java.util.UUID.randomUUID().toString(),
                identity = identity,
                migrationSealSha256 = com.pocketrealm.fs.FileDigests.sha256(sealText),
            ),
        )
        DesktopSqliteSealMarkers.atomicWriteMarker(
            roots.sqliteDatadir, DesktopSqliteSeals.CLEAN_MARKER_NAME,
            DesktopSqliteSeals.cleanSeal(System.currentTimeMillis()),
        )
    }
}
