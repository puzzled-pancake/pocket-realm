package com.pocketrealm.database

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.sqlite.SQLiteDatabase
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketrealm.server.IRealmControl
import com.pocketrealm.server.IWorldControl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P6.5/DEC-09: the differential parity lane's in-app barrage driver (the
 * DatabaseLifecycleTest precedent, extended per the Part 2 P6.5 spec).
 * Runs IDENTICALLY on both server APKs — Server A (the default MariaDB
 * build) and Server B (-PdifferentialTestLane + -PsqliteProvider, x86_64)
 * — driving the same fixed-SQL Binder surface and emitting a per-table
 * state dump (Server A via the P5 exporter's staging; Server B via the
 * row-count emitter over the SQLite datadir rows) plus the telemetry
 * keys the host oracle (tools/run_differential_parity.py) compares
 * against the KNOWN-DIFFERENCE LEDGER. NO new SQL injection surface: the
 * Binder stays fixed-SQL.
 *
 * The dump is ROW-COUNT-ONLY today: the content-level comparator (typed
 * field comparators over the staged TSVs vs a canonical SQLite emission,
 * modulo the ledger classes) is REGISTERED-NOT-IMPLEMENTED (the W9 TSV
 * leg — see the plan's P6.5 entries).
 *
 * Profiles (the orchestrator passes -e differentialProfile):
 * - quick: the smoke leg — lifecycle, start/stop cycles, revisions, the
 *   db-level dirty-kill matrix, dump/oracle.
 * - standard: the evidence leg — adds W2/W3 (the synthetic account
 *   barrage through the REAL :world console writer — the F30
 *   cross-process LoginDatabase path — plus password/gmlevel/status/
 *   character-persistence probes), W6's world save (client-acked), the
 *   W5 stepped bot soak with W10 telemetry sampling, and the DEC-02
 *   concurrent-save world-kill + db-kill + recover + world-restart
 *   sentinel matrix. The dump runs BEFORE the soak: bot generation is
 *   seeded by its own RNG state and is telemetry-compared, never
 *   row-diffed. W4 (gameplay probes) is a stretch item and is recorded
 *   SKIPPED-STRETCH, never silently.
 * - massive: the soak leg (longer steps + the b100 rung); same shape.
 */
@RunWith(AndroidJUnit4::class)
class DifferentialBarrageRunner {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val evidence = JSONObject()
    private val connections = ArrayList<ServiceConnection>()
    // W10 drain telemetry: every client-acked world stop duration (the
    // ≤2×A drain band's inputs, R2 A).
    private val worldDrainMs = JSONArray()

    @After fun unbind() {
        connections.forEach { runCatching { context.unbindService(it) } }
        connections.clear()
    }

    @Test fun differentialBarrage() {
        val profile = InstrumentationRegistry.getArguments().getString("differentialProfile") ?: "quick"
        require(profile in PROFILES) { "unknown differentialProfile '$profile' (must be one of $PROFILES)" }
        val fullBarrage = profile != "quick"
        val control = bind(
            "com.pocketrealm.database.DatabaseService",
        ) { IDatabaseControl.Stub.asInterface(it) }.api
        val status = assertOk("status", control.status())
        val sqliteCapable = status.optBoolean("sqliteCapable", false)
        evidence.put("profile", profile)
        evidence.put("providerMode", status.optString("providerMode"))
        evidence.put("sqliteCapable", sqliteCapable)
        // W8 cross-engine agreement inputs: the revision/seal state both
        // engines must agree on (412/412, current) — compared host-side.
        evidence.put("revisionState", JSONObject()
            .put("migrationManifestCount", status.optLong("migrationManifestCount", -1))
            .put("migrationSealedCount", status.optLong("migrationSealedCount", -1))
            .put("migrationsCurrent", status.optBoolean("migrationsCurrent")))

        // W1 boot parity: initialize (A: MariaDB bootstrap+migrations; B:
        // the .sqlz seed replay), migrations, start, health.
        val started = System.currentTimeMillis()
        assertOk("initialize", control.initialize())
        assertOk("migrations", control.applyPinnedMigrations())
        val startStatus = assertOk("start", control.start())
        assertOk("health", control.queryHealth())
        evidence.put("bootWallMs", System.currentTimeMillis() - started)
        evidence.put("startKeys", startStatus)

        // W2-lite: the account/character surface reachable through the
        // fixed-SQL Binder at the quick scale.
        evidence.put("accountBarrage", if (fullBarrage) "FULL:world-console-writer" else "SKIPPED:quick-profile-scope")

        // W6 start/stop cycles (database-level stop/start/health waves;
        // the world-level saveall waves are the standard profile's).
        val waves = JSONArray()
        for (wave in 1..3) {
            val waveStart = System.currentTimeMillis()
            assertOk("stop-$wave", control.stop())
            assertOk("start-$wave", control.start())
            assertOk("health-$wave", control.queryHealth())
            waves.put(JSONObject().put("wave", wave).put("cycleMs", System.currentTimeMillis() - waveStart))
        }
        evidence.put("saveWaves", waves)

        assertOk("stop-for-revisions", control.stop())

        // W8 revision parity (stopped-state): the engine's own
        // verification is idempotent-verify on both providers.
        assertOk("revisions", control.applyPinnedMigrations()).also {
            assertTrue(it.optBoolean("revisionMismatchRejected") || it.optBoolean("idempotent"))
        }
        evidence.put("revisionsVerified", true)

        // W2/W3/W6-world (standard/massive): the synthetic account barrage
        // through the REAL :world console writer + a client-acked world
        // save, then a clean full teardown so the dump stays stopped-state.
        // The world needs prepared game data (the user's client, imported
        // through the managed importer); when it is absent the lane host
        // cannot drive the world legs — record the GATE loudly (probed
        // POSITIVELY on the prepared-data pointer, not by error sniffing)
        // and run the DB-scope legs (the host oracle turns the gate into a
        // distinct PASS-GATED verdict; it can never satisfy the standard
        // exit).
        var worldGated = false
        if (fullBarrage) {
            val worldLeg = worldAccountBarrage(control)
            evidence.put("worldLeg", worldLeg)
            worldGated = worldLeg.optBoolean("gated")
            if (worldGated) {
                evidence.put("accounts", "SKIPPED:game-data-gate")
                evidence.put("botSoak", "SKIPPED:game-data-gate")
            } else {
                evidence.put("accounts", worldLeg.getJSONObject("accounts"))
                evidence.put("worldBootMs", worldLeg.getLong("worldBootMs"))
                evidence.put("worldSaveAckMs", worldLeg.getJSONArray("saveAckMs"))
            }
            // W4 gameplay probes: the managed-addon scriptable surface —
            // a stretch item, skipped loudly, never silently.
            evidence.put("gameplayProbes", "SKIPPED:stretch")
        }

        // The per-table state dump for the host oracle (the export legs
        // are stopped-state by design; standard runs it BEFORE the bot
        // soak so the compared state stays deterministic).
        evidence.put("dump", dump(control, sqliteCapable))

        // W7 db-level dirty-kill matrix (debug builds only): running →
        // dirty kill → recover (which re-seals clean). NOTE (R1 B, R2 B):
        // on the SQLite provider this RUNNING-state kill is a marker
        // deletion drill (the in-process engine has closed its handles and
        // nothing is in flight); the load-bearing in-flight B-side kill is
        // the concurrent-save world-kill leg. Recover leaves STOPPED.
        assertOk("pre-kill-start", control.start())
        assertOk("kill-for-test", control.killForTest())
        evidence.put("quickRecoverResult", assertOk("recover", control.recover()))
        evidence.put("dirtyKillRecovered", true)

        if (fullBarrage && !worldGated) {
            botSoakAndDirtyKill(control, profile)
        }

        // W10 telemetry: the comparative keys (absolutes stay DEC-04
        // device-gated; the host oracle compares A vs B only).
        val finalStatus = JSONObject(control.status())
        evidence.put("telemetry", JSONObject()
            .put("bootWallMs", evidence.optLong("bootWallMs"))
            .put("saveWaves", waves)
            .put("worldDrainMs", worldDrainMs)
            .put("lastStatus", finalStatus))
        // W8 inputs from the FINAL state (post-migrations: 412/412,
        // current) — the initial read predates the apply and says little.
        evidence.put("revisionState", JSONObject()
            .put("migrationManifestCount", finalStatus.optLong("migrationManifestCount", -1))
            .put("migrationSealedCount", finalStatus.optLong("migrationSealedCount", -1))
            .put("migrationsCurrent", finalStatus.optBoolean("migrationsCurrent")))

        writeEvidence(evidence)
    }

    /** W2/W3/W6-world: db+realm+world boot, the synthetic account barrage
     * (create/verify/gmlevel/status/persistence through the REAL world
     * console writer), one client-acked save, clean world/realm/db stop.
     * Returns {"gated": true, ...} when no prepared game data exists (the
     * DB-scope legs continue). */
    private fun worldAccountBarrage(control: IDatabaseControl): JSONObject {
        // POSITIVE game-data probe (R1 E): gate ONLY on the prepared-data
        // pointer's actual absence — a staged-but-broken import or any
        // other world-start failure must FAIL the run, never gate it.
        val preparedPointer = File(context.filesDir, "content/o11-server/active.json")
        if (!preparedPointer.isFile) {
            return JSONObject().put("gated", true)
                .put("reason", "game-data-missing")
                .put("probe", preparedPointer.absolutePath)
        }
        assertOk("barrage-db-start", control.start())
        assertOk("barrage-db-health", control.queryHealth())
        val realm = bind("com.pocketrealm.server.RealmRuntimeService") { IRealmControl.Stub.asInterface(it) }
        assertOk("barrage-realm-start", realm.api.start())
        waitReady(120_000, "realm") { JSONObject(realm.api.status()) }
        val world = bind("com.pocketrealm.server.WorldRuntimeService") { IWorldControl.Stub.asInterface(it) }
        val bootStart = System.currentTimeMillis()
        assertOk("barrage-world-start", world.api.startNormal())
        waitReady(600_000, "world") { JSONObject(world.api.status()) }
        val worldBootMs = System.currentTimeMillis() - bootStart

        // W2: 100 synthetic accounts, fixed names/passwords, identical on
        // both servers. createAccount is the console `account create`
        // command issued from the :world process — the F30 cross-process
        // LoginDatabase writer on BOTH engines.
        val created = JSONArray()
        var createdCount = 0
        for (i in 1..100) {
            val username = "diffacct%03d".format(i)
            val password = "diffpass%03d".format(i)
            assertOk("create-$username", world.api.createAccount(username, password))
            createdCount++
            created.put(username)
        }
        var verified = 0
        var wrongPasswordRejected = 0
        for (i in 1..100) {
            val username = "diffacct%03d".format(i)
            val password = "diffpass%03d".format(i)
            val verifiedResult = assertOk("verify-$username", world.api.verifyAccountPassword(username, password))
            if (verifiedResult.optBoolean("passwordVerified")) verified++
            val wrongResult = assertOk("verify-wrong-$username", world.api.verifyAccountPassword(username, "wrongpass"))
            if (!wrongResult.optBoolean("passwordVerified")) wrongPasswordRejected++
        }
        var gmLeveled = 0
        for (i in 1..10) {
            val username = "diffacct%03d".format(i)
            assertOk("gmlevel-$username", world.api.setAccountGmLevel(username, 3))
            gmLeveled++
        }
        var statusProbed = 0
        var persistenceProbed = 0
        var persistenceMissing = 0
        for (i in 1..100) {
            val username = "diffacct%03d".format(i)
            val account = assertOk("status-$username", world.api.accountStatus(username))
            if (account.optBoolean("accountExists")) statusProbed++
            // W3 read probe: the characters + character_inventory SELECTs
            // through the engine (synthetic accounts have no characters —
            // the deterministic outcome is character-missing on both).
            val persistence = assertOk("persistence-$username", world.api.characterPersistence(username, "Diffchar"))
            persistenceProbed++
            if (persistence.optString("reason") == "character-missing") persistenceMissing++
        }
        // W6-world: one client-acked saveall (the W10 comparative band).
        val saveAckMs = JSONArray()
        val saveStart = System.currentTimeMillis()
        assertOk("barrage-world-save", world.api.save())
        saveAckMs.put(System.currentTimeMillis() - saveStart)

        // Clean teardown: world → realm → database (the supervisor order).
        // Every world stop retires the :world process (retireCleanProcess)
        // — close the handles so nothing reuses a dying proxy (R1 D).
        timedWorldStop("barrage-world-stop") { world.api.stop() }
        world.close()
        assertOk("barrage-realm-stop", realm.api.stop())
        realm.close()
        assertOk("barrage-db-stop", control.stop())
        return JSONObject()
            .put("accounts", JSONObject()
                .put("created", createdCount)
                .put("verified", verified)
                .put("wrongPasswordRejected", wrongPasswordRejected)
                .put("gmLeveled", gmLeveled)
                .put("statusProbed", statusProbed)
                .put("persistenceProbed", persistenceProbed)
                .put("persistenceMissing", persistenceMissing)
                .put("usernames", created))
            .put("worldBootMs", worldBootMs)
            .put("saveAckMs", saveAckMs)
    }

    /** W5 stepped bot soak + W10 telemetry sampling + the DEC-02
     * concurrent-save world-kill/db-kill/recover/world-restart sentinel
     * matrix. */
    private fun botSoakAndDirtyKill(control: IDatabaseControl, profile: String) {
        val steps = when (profile) {
            "massive" -> listOf(
                "mobile-typical-b50-v1" to 120L,
                "mobile-balanced-b100-v1" to 180L,
                "mobile-lowcpu-nearby-b160-v1" to 300L,
            )
            else -> listOf(
                "mobile-typical-b50-v1" to 60L,
                "mobile-lowcpu-nearby-b160-v1" to 90L,
            )
        }
        assertOk("soak-db-start", control.start())
        assertOk("soak-db-health", control.queryHealth())
        val realm = bind("com.pocketrealm.server.RealmRuntimeService") { IRealmControl.Stub.asInterface(it) }
        assertOk("soak-realm-start", realm.api.start())
        waitReady(120_000, "realm") { JSONObject(realm.api.status()) }
        var world = bind("com.pocketrealm.server.WorldRuntimeService") { IWorldControl.Stub.asInterface(it) }
        val soakSteps = JSONArray()
        for ((index, step) in steps.withIndex()) {
            val (profileId, soakSeconds) = step
            assertOk("soak-world-start-$profileId", world.api.startBotProfile(profileId))
            waitReady(600_000, "world") { JSONObject(world.api.status()) }
            val target = JSONObject(world.api.status()).optLong("selectedBotTarget", 0L)
            assertTrue("bot profile reported no target ($profileId)", target > 0)
            waitForBots(world.api, target, 900_000)
            val samples = JSONArray()
            val deadline = System.currentTimeMillis() + soakSeconds * 1000
            while (System.currentTimeMillis() < deadline) {
                val status = JSONObject(world.api.status())
                assertTrue("world left READY during soak: $status", status.getString("state") == "READY")
                samples.put(JSONObject()
                    .put("botsOnline", status.optLong("botsOnline"))
                    .put("effectiveTarget", status.optLong("effectiveBotTarget"))
                    .put("p50Ms", status.optLong("worldTickP50Ms"))
                    .put("p95Ms", status.optLong("worldTickP95Ms"))
                    .put("p99Ms", status.optLong("worldTickP99Ms"))
                    .put("maxMs", status.optLong("worldTickWindowMaxMs"))
                    .put("hardStalls", status.optLong("worldHardStalls"))
                    .put("dbProbeDelayMs", status.optLong("dbProbeDelayMs")))
                Thread.sleep(10_000)
            }
            soakSteps.put(JSONObject().put("profileId", profileId)
                .put("target", target).put("samples", samples))
            // Step boundary: every world stop arms the 250 ms retire fuse
            // (retireCleanProcess) — close, sleep past the fuse, rebind
            // (R1 D + R2 B: the precedents' sleep was load-bearing).
            timedWorldStop("soak-world-stop-$profileId") { world.api.stop() }
            world.close()
            if (index < steps.size - 1) {
                Thread.sleep(750)
                world = bind("com.pocketrealm.server.WorldRuntimeService") { IWorldControl.Stub.asInterface(it) }
            }
        }
        evidence.put("botSoak", soakSteps)

        // DEC-02 cross-engine pair: kill :world while a save is IN FLIGHT.
        // R2 B/D: the Binder killForTest CANNOT be mid-save — save() and
        // killForTest() serialize on the service's transition gate, so a
        // Binder kill lands only after the save acks. The kill below is
        // KERNEL-LEVEL instead: the runner shares the app UID with :world,
        // so Os.kill(SIGKILL) on the world's pid bypasses every user-space
        // lock and lands while saveNative is in flight. R3 B: the kill
        // delay is ADAPTIVE (≥1 s and a quarter of the OBSERVED save-ack)
        // so it lands inside the writing phase, past the CLI-queue phase
        // (the world thread dequeues the saveall within one Update tick);
        // the premise is triple-recorded (thread alive at kill, the save
        // call dying unacked, the thread terminating after the kill).
        Thread.sleep(750)
        world = bind("com.pocketrealm.server.WorldRuntimeService") { IWorldControl.Stub.asInterface(it) }
        assertOk("mid-save-world-start", world.api.startBotProfile(steps.last().first))
        waitReady(600_000, "world") { JSONObject(world.api.status()) }
        val worldPid = JSONObject(world.api.status()).optLong("pid", -1)
        assertTrue("world status carries no pid", worldPid > 0)
        val observedSaveAckMs = evidence.optJSONArray("worldSaveAckMs")?.optLong(0) ?: 0L
        assertTrue("no observed save-ack basis for the kill delay", observedSaveAckMs > 0)
        val killDelayMs = maxOf(1_000L, observedSaveAckMs / 4)
        evidence.put("killDelayMs", killDelayMs)
        val midSaveStart = System.currentTimeMillis()
        val saveDiedUnacked = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveThread = Thread {
            runCatching { world.api.save() }.onFailure {
                saveDiedUnacked.set(it is android.os.DeadObjectException)
            }
        }
        saveThread.isDaemon = true
        saveThread.start()
        Thread.sleep(killDelayMs)
        val killFiredWhileSaveInFlight = saveThread.isAlive
        android.system.Os.kill(worldPid.toInt(), android.system.OsConstants.SIGKILL)
        evidence.put("killFiredWhileSaveInFlight", killFiredWhileSaveInFlight)
        saveThread.join(120_000)
        assertTrue("save thread never terminated after the kill", !saveThread.isAlive)
        evidence.put("saveThreadTerminated", true)
        evidence.put("saveDiedUnacked", saveDiedUnacked.get())
        evidence.put("concurrentSaveKillAckMs", System.currentTimeMillis() - midSaveStart)
        world.close()
        // Post-kill margin + fresh-process proof (R3 D/B): the precedents
        // sleep past the death window, and the rebind must land on a NEW
        // :world pid — a recycled/stale pid would mean the kill missed.
        Thread.sleep(1_000)
        world = bind("com.pocketrealm.server.WorldRuntimeService") { IWorldControl.Stub.asInterface(it) }
        val reboundPid = JSONObject(world.api.status()).optLong("pid", -1)
        assertTrue("rebind landed on the killed pid ($worldPid vs $reboundPid)", reboundPid != worldPid)
        assertOk("soak-db-kill", control.killForTest())
        // Retire the realm fault domain across the db kill (R2 D / the
        // O09:109 precedent): realmd stays READY over a dead db and its
        // start refuses from READY — stop it before the recovery restart.
        runCatching { realm.api.stop() }
        val recoverResult = assertOk("soak-db-recover", control.recover())
        val startAfterRecover = assertOk("post-kill-db-start", control.start())
        // R3 B: the recovery outcomes ride the evidence — a silent
        // corruption rebuild (VACUUM INTO) or an InnoDB recovery without
        // observed output must be visible to the host oracle, not healed
        // quietly (W7's diagnostic completeness).
        evidence.put("dbRecoverResult", recoverResult)
        evidence.put("dbStartAfterRecover", startAfterRecover)
        assertOk("post-kill-realm-start", realm.api.start())
        waitReady(120_000, "realm") { JSONObject(realm.api.status()) }
        assertOk("post-kill-world-start", world.api.startBotProfile(steps.last().first))
        waitReady(600_000, "world") { JSONObject(world.api.status()) }
        var sentinelsAlive = 0
        for (i in 1..100) {
            val username = "diffacct%03d".format(i)
            val account = assertOk("sentinel-$username", world.api.accountStatus(username))
            if (account.optBoolean("accountExists")) sentinelsAlive++
        }
        evidence.put("sentinelsAliveAfterRecovery", sentinelsAlive)
        timedWorldStop("soak-world-final-stop") { world.api.stop() }
        world.close()
        assertOk("soak-realm-final-stop", realm.api.stop())
        realm.close()
        assertOk("soak-db-final-stop", control.stop())
    }

    /** One client-acked world stop, timed into the W10 drain telemetry. */
    private fun timedWorldStop(name: String, stop: () -> String) {
        val started = System.currentTimeMillis()
        assertOk(name, stop())
        worldDrainMs.put(System.currentTimeMillis() - started)
    }

    /** Server A: the P5 exporter's staging (the exporter's per-table
     * records incl. row counts + content digests). Server B: the row-count
     * emitter over the SQLite datadir rows. */
    private fun dump(control: IDatabaseControl, sqliteCapable: Boolean): JSONObject {
        val dump = JSONObject()
        if (!sqliteCapable) {
            val translation = assertOk("translate", control.translateUserStateToSqliteStaging())
            dump.put("source", "mariadb-outfile-staging")
            dump.put("phase", translation.optString("phase"))
            dump.put("tables", translation.optJSONObject("databases") ?: JSONObject())
            return dump
        }
        dump.put("source", "sqlite-rowcount-emitter")
        val tables = JSONObject()
        val roots = com.pocketrealm.storage.StorageRoots.get(context)
        val datadir = File(roots.databaseRoot, DatabaseSqliteControlPlane.SQLITE_DATADIR_NAME)
        for (database in DatabaseSqliteControlPlane.DATABASES) {
            val file = DatabaseSqliteControlPlane.databaseFile(datadir, database)
            if (!file.isFile) continue
            val perTable = JSONObject()
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) perTable.put(cursor.getString(0), rowCount(db, cursor.getString(0)))
                }
            }
            tables.put(database, perTable)
        }
        dump.put("rowCounts", tables)
        return dump
    }

    private fun rowCount(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM \"$table\";", null).use { it.moveToFirst(); it.getLong(0) }

    private fun writeEvidence(value: JSONObject) {
        // Internal filesDir: pullable via `run-as` (external app dirs are
        // shell-inaccessible since API 30).
        val out = File(context.filesDir, "differential-barrage.json")
        out.writeText(value.toString())
    }

    private fun <T> bind(component: String, convert: (IBinder) -> T): Bound<T> {
        val latch = CountDownLatch(1)
        var binder: T? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = convert(service!!); latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        connections.add(connection)
        // The explicit component form (the DatabaseLifecycleTest
        // precedent) — the services are non-exported; an action-string
        // intent does not resolve for them.
        val intent = Intent().setClassName(context, component)
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        assertTrue(latch.await(60, TimeUnit.SECONDS))
        return Bound(binder!!, connection)
    }

    /** A bound service handle; close() unbinds (killForTest and every
     * clean stop take the hosting process down — the O13/O09 precedents
     * close+rebind after each). */
    private inner class Bound<T>(val api: T, private val connection: ServiceConnection) {
        fun close() {
            runCatching { context.unbindService(connection) }
            connections.remove(connection)
        }
    }

    private fun waitReady(timeoutMs: Long, component: String, read: () -> JSONObject) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = read()
        while (System.currentTimeMillis() < deadline) {
            when (latest.optString("state")) {
                "READY" -> return
                "FAILED" -> throw AssertionError("$component failed: $latest")
            }
            Thread.sleep(200)
            latest = read()
        }
        throw AssertionError("$component readiness timed out: $latest")
    }

    private fun waitForBots(api: IWorldControl, target: Long, timeoutMs: Long) {
        if (target <= 0) return
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = JSONObject(api.status())
        while (System.currentTimeMillis() < deadline) {
            if (latest.optLong("botsOnline") >= target - 2) return
            if (latest.optString("state") == "FAILED") throw AssertionError("world failed during bot ramp: $latest")
            Thread.sleep(2_000)
            latest = JSONObject(api.status())
        }
        throw AssertionError("bot ramp timed out at $target: $latest")
    }

    private fun assertOk(name: String, raw: String): JSONObject {
        val parsed = JSONObject(raw)
        assertTrue("$name failed: $raw", parsed.optBoolean("ok"))
        return parsed
    }

    private companion object {
        val PROFILES = setOf("quick", "standard", "massive")
    }
}
