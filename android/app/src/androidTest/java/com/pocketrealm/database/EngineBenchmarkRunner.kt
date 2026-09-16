package com.pocketrealm.database

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.sqlite.SQLiteDatabase
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketrealm.storage.StorageRoots
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * DB-scope engine benchmark (the host-answerable piece of "how does
 * SQLite compare to MariaDB under CMaNGOS"): runs IDENTICALLY on
 * Server A (MariaDB) and Server B (SQLite) and measures what the world-less
 * host can measure — lifecycle timings (start/stop/health/migrate/backup),
 * query latency over the SAME fixed-seed statement battery on the SAME
 * seeded data, write-burst latency under the synchronous=NORMAL + WAL
 * connection contract, and footprint (datadir bytes + RSS of the database processes).
 *
 * Representation notes (disclosed in every evidence bundle):
 * - SQLite numbers are IN-PROCESS (SQLiteDatabase on a copy of the real
 *   datadir) — architecturally what CMaNGOS DO_SQLITE does in :world.
 * - MariaDB numbers ride the ENGINE'S OWN client-launcher path
 *   (DatabaseNative + libpocket_mariadb_client.so) batched over stdin;
 *   per-statement latency therefore approximates server-side execution,
 *   NOT the C-connector round trip CMaNGOS runtime uses. The client-spawn
 *   round trip is measured separately (20x SELECT 1) — that cost is what
 *   bootstrap/migrations pay, not steady-state gameplay.
 * - The world-driven metrics (tick p99, saveall-ack under bots, probe
 *   delay under load) need real-device runs; this benchmark does not
 *   measure them.
 */
@RunWith(AndroidJUnit4::class)
class EngineBenchmarkRunner {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val evidence = JSONObject()
    private val connections = ArrayList<ServiceConnection>()

    @After fun unbind() {
        connections.forEach { runCatching { context.unbindService(it) } }
        connections.clear()
    }

    @Test fun engineBenchmark() {
        // The JNI program-launcher lives in libwine_spike (DatabaseNative's
        // own load) — the instrumentation process must load it itself.
        DatabaseNative.load()
        val control = bind("com.pocketrealm.database.DatabaseService") { IDatabaseControl.Stub.asInterface(it) }.api
        val status = assertOk(control.status())
        val sqliteCapable = status.optBoolean("sqliteCapable", false)
        evidence.put("providerMode", status.optString("providerMode"))
        evidence.put("sqliteCapable", sqliteCapable)
        evidence.put("abi", android.os.Build.SUPPORTED_ABIS.first())

        // ---- lifecycle ----------------------------------------------------------
        evidence.put("initializeMs", timed { assertOk(control.initialize()) })
        evidence.put("idempotentMigrationsMs", timed { assertOk(control.applyPinnedMigrations()) })
        val cycles = JSONArray()
        for (cycle in 1..5) {
            val startMs = timed { assertOk(control.start()) }
            val healthMs = timed { assertOk(control.queryHealth()) }
            val rssRunningKb = databaseRssKb()
            val stopMs = timed { assertOk(control.stop()) }
            cycles.put(JSONObject().put("cycle", cycle)
                .put("startMs", startMs).put("healthMs", healthMs)
                .put("stopMs", stopMs).put("rssRunningKb", rssRunningKb))
        }
        evidence.put("lifecycleCycles", cycles)

        // ---- Binder health RTT (running) ----------------------------------------
        assertOk(control.start())
        val rtt = ArrayList<Long>()
        repeat(20) { rtt.add(timed { assertOk(control.queryHealth()) }) }
        evidence.put("healthBinderRttMs", sortedRttSummary(rtt))
        evidence.put("rssRunningKb", databaseRssKb())

        // ---- backup (STOPPED-state operation) --------------------------------------
        assertOk(control.stop())
        evidence.put("createNamedBackupMs", timed { assertOk(control.createNamedBackup("engine-bench")) })
        evidence.put("listBackupsMs", timed { assertOk(control.listBackups()) })

        // ---- the query/write battery ----------------------------------------------
        val ids = lookupIds()
        if (sqliteCapable) {
            evidence.put("battery", sqliteBattery(ids))
        } else {
            assertOk(control.start())
            assertOk(control.queryHealth())
            evidence.put("battery", mariadbBattery(ids))
            assertOk(control.stop())
        }

        // ---- footprint ------------------------------------------------------------
        val roots = StorageRoots.get(context)
        evidence.put("datadirBytes", dirSize(if (sqliteCapable)
            File(roots.databaseRoot, DatabaseSqliteControlPlane.SQLITE_DATADIR_NAME) else roots.databaseDatadir))
        if (!sqliteCapable) {
            evidence.put("providerBytes", dirSize(File(roots.databaseRoot, "provider")))
        }
        evidence.put("rssStoppedKb", databaseRssKb())

        File(context.filesDir, "engine-benchmark.json").writeText(evidence.toString())
    }

    // ===========================================================================
    // The shared statement battery. SAME fixed-seed ids for both engines.
    // ===========================================================================

    private fun lookupIds(): JSONObject {
        val random = Random(42)
        fun idsFor(table: String, max: Int, count: Int): JSONArray {
            val values = JSONArray()
            repeat(count) { values.put(1 + random.nextInt(max)) }
            return values
        }
        return JSONObject()
            .put("item_template", idsFor("item_template", 17_718, 1_000))
            .put("creature_template", idsFor("creature_template", 10_384, 1_000))
            .put("quest_template", idsFor("quest_template", 4_245, 1_000))
            .put("spell_template", idsFor("spell_template", 22_374, 1_000))
    }

    /** SQLite leg: in-process over a COPY of the production datadir (the
     * production files stay untouched; the copy carries the same schema,
     * volumes, and page layout; the WAL + synchronous=NORMAL connection
     * policy applies). */
    private fun sqliteBattery(ids: JSONObject): JSONObject {
        val roots = StorageRoots.get(context)
        val source = DatabaseSqliteControlPlane.databaseFile(
            File(roots.databaseRoot, DatabaseSqliteControlPlane.SQLITE_DATADIR_NAME), "classicmangos")
        assertTrue("classicmangos.sqlite3 missing", source.isFile)
        val copy = File(context.filesDir, "bench-copy-classicmangos.sqlite3")
        copy.delete()
        val copyMs = timed { source.copyTo(copy, overwrite = true) }
        val battery = JSONObject().put("mode", "in-process-sqlite-on-copy")
            .put("sourceBytes", source.length()).put("copyMs", copyMs)
        SQLiteDatabase.openDatabase(copy.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("PRAGMA synchronous=NORMAL")
            val primaryKeys = JSONObject()
            val lookups = JSONObject()
            for (table in ids.keys()) {
                val tableIds = ids.getJSONArray(table)
                val pk = db.rawQuery("SELECT name FROM pragma_table_info(?) WHERE pk>0 ORDER BY pk LIMIT 1;",
                    arrayOf(table)).use { it.moveToFirst(); it.getString(0) }
                primaryKeys.put(table, pk)
                // warmup (page cache)
                repeat(25) { i ->
                    db.rawQuery("SELECT * FROM $table WHERE $pk=?;",
                        arrayOf(tableIds.getInt(i).toString())).use { it.moveToFirst() }
                }
                val perCallMs = ArrayList<Double>()
                for (i in 0 until tableIds.length()) {
                    val elapsed = timedNanos {
                        db.rawQuery("SELECT * FROM $table WHERE $pk=?;",
                            arrayOf(tableIds.getInt(i).toString())).use { it.moveToFirst() }
                    }
                    perCallMs.add(elapsed / 1e6)
                }
                lookups.put(table, latencySummary(perCallMs))
            }
            battery.put("primaryKeys", primaryKeys)
            battery.put("pointLookups1k", lookups)

            val scans = JSONObject()
            scans.put("lootRangeScan200x10", timed {
                repeat(10) { i ->
                    db.rawQuery("SELECT * FROM creature_loot_template WHERE entry BETWEEN ? AND ?;",
                        arrayOf((i * 1000).toString(), (i * 1000 + 200).toString()))
                        .use { c -> while (c.moveToNext()) { } }
                }
            })
            scans.put("countLoot151k", timed {
                db.rawQuery("SELECT COUNT(*) FROM creature_loot_template;", null).use { it.moveToFirst() }
            })
            scans.put("countCreature66k", timed {
                db.rawQuery("SELECT COUNT(*) FROM creature;", null).use { it.moveToFirst() }
            })
            scans.put("countTravelPath414k", timed {
                db.rawQuery("SELECT COUNT(*) FROM ai_playerbot_travelnode_path;", null).use { it.moveToFirst() }
            })
            scans.put("joinCreatureTemplate5k", timed {
                db.rawQuery("SELECT c.guid,c.id,ct.name FROM creature c JOIN creature_template ct ON ct.entry=c.id LIMIT 5000;", null)
                    .use { c -> while (c.moveToNext()) { } }
            })
            battery.put("scansJoinsMs", scans)
        }

        // Write burst under the WAL + synchronous=NORMAL contract on the copy.
        SQLiteDatabase.openDatabase(copy.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("PRAGMA synchronous=NORMAL")
            db.execSQL("DROP TABLE IF EXISTS bench_scratch")
            val writes = JSONObject()
            val insert = timed {
                db.execSQL("CREATE TABLE bench_scratch(a INTEGER PRIMARY KEY, b TEXT)")
                db.beginTransaction()
                for (i in 1..500) db.execSQL("INSERT INTO bench_scratch(a,b) VALUES(?,?)", arrayOf(i, "row-$i"))
                db.setTransactionSuccessful()
                db.endTransaction()
            }
            val update = timed {
                db.beginTransaction()
                for (i in 1..100) db.execSQL("UPDATE bench_scratch SET b=? WHERE a=?", arrayOf("upd-$i", i))
                db.setTransactionSuccessful()
                db.endTransaction()
            }
            val delete = timed {
                db.beginTransaction()
                for (i in 301..500) db.execSQL("DELETE FROM bench_scratch WHERE a=?", arrayOf(i.toString()))
                db.setTransactionSuccessful()
                db.endTransaction()
            }
            db.execSQL("DROP TABLE bench_scratch")
            writes.put("insert500TxnMs", insert)
            writes.put("update100TxnMs", update)
            writes.put("delete200TxnMs", delete)
            battery.put("writeBurst", writes)
        }
        copy.delete()
        return battery
    }

    /** MariaDB leg: the ENGINE'S OWN client-launcher path (DatabaseNative →
     * libpocket_mariadb_client.so), batched over stdin. Per-statement
     * latency = (batch wall - spawn baseline) / n — server-side execution
     * approximation, not the C-connector round trip. */
    private fun mariadbBattery(ids: JSONObject): JSONObject {
        val roots = StorageRoots.get(context)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val client = File(nativeDir, "libpocket_mariadb_client.so")
        assertTrue("mariadb client missing", client.isFile)
        val providerRoot = File(roots.databaseRoot, "provider")
        val socket = File(roots.databaseRun, "mariadb.sock")
        val secrets = JSONObject(File(roots.databaseRoot, "secrets.json").readText())
        val core = secrets.getString("core")
        val admin = secrets.getString("admin")
        val battery = JSONObject().put("mode", "client-batch-over-socket")

        fun clientRun(sqlBody: String, user: String, password: String, database: String? = null): Pair<Long, DatabaseRunResult> {
            val stdin = File(roots.databaseRoot, "bench-input.sql")
            stdin.writeText(sqlBody)
            val args = buildList {
                addAll(listOf("--no-defaults", "--protocol=socket", "--socket=${socket.absolutePath}",
                    "--user=$user", "--batch", "--skip-column-names"))
                if (database != null) add("--database=$database")
            }
            val argv = arrayOf(
                nativeDir.absolutePath, client.absolutePath, "mariadb",
                roots.databaseRoot.absolutePath, roots.databaseRoot.absolutePath,
                File(providerRoot, "lib").absolutePath,
                args.joinToString("\n"),
                "MYSQL_PWD=$password",
                stdin.absolutePath,
                "60000", "false",
            )
            val start = System.nanoTime()
            val raw = if (android.os.Build.SUPPORTED_ABIS.first() == "arm64-v8a") {
                DatabaseNative.runBionicProgramNative(
                    argv[0], argv[1], argv[2], argv[3], argv[4], argv[5],
                    argv[6], argv[7], argv[8], argv[9].toInt(), argv[10].toBoolean())
            } else {
                DatabaseNative.runGlibcProgramNative(
                    argv[0], argv[1], argv[2], argv[3], argv[4], argv[5],
                    argv[6], argv[7], argv[8], argv[9].toInt(), argv[10].toBoolean())
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            return elapsedMs to DatabaseRunResult.parse(raw)
        }

        // Spawn/connect baseline: the fixed process-spawn cost one CLI
        // statement pays.
        val spawnSamples = ArrayList<Long>()
        repeat(20) {
            val (ms, result) = clientRun("SELECT 1;", "pocket_core", core, "classicmangos")
            assertTrue("spawn baseline failed: ${result.stderr}", result.ok)
            spawnSamples.add(ms)
        }
        val spawnBaselineMs = spawnSamples.sorted()[spawnSamples.size / 2]
        battery.put("clientRoundTripMedianMs", spawnBaselineMs)
        battery.put("clientRoundTripSamplesMs", JSONArray(spawnSamples))

        // PK discovery (the engine's own translate-columns pattern);
        // cross-engine column names are expected to agree — a
        // disagreement here would itself be a parity finding, recorded
        // in the evidence.
        val discoverySql = buildString {
            for (table in ids.keys()) {
                append("SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE ")
                append("WHERE TABLE_SCHEMA='classicmangos' AND TABLE_NAME='$table' ")
                append("AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION LIMIT 1;\n")
            }
        }
        val (_, discovery) = clientRun(discoverySql, "pocket_core", core)
        assertTrue("pk discovery failed: ${discovery.stderr.take(400)}", discovery.ok)
        val tableOrder = ids.keys().asSequence().toList()
        val pkByTable = HashMap<String, String>()
        discovery.stdout.lineSequence().filter { it.isNotBlank() }.take(tableOrder.size)
            .forEachIndexed { index, line -> pkByTable[tableOrder[index]] = line.trim() }
        val primaryKeysJson = JSONObject()
        tableOrder.forEach { primaryKeysJson.put(it, pkByTable[it]) }
        battery.put("primaryKeys", primaryKeysJson)

        // Point-lookup batches (server-side exec approximation).
        val lookups = JSONObject()
        for (table in tableOrder) {
            val pk = pkByTable[table] ?: "entry"
            val tableIds = ids.getJSONArray(table)
            val warmup = StringBuilder()
            for (i in 0 until 25) warmup.append("SELECT * FROM $table WHERE $pk=${tableIds.getInt(i)};\n")
            clientRun(warmup.toString(), "pocket_core", core, "classicmangos")
            val sql = StringBuilder()
            for (i in 0 until tableIds.length()) {
                sql.append("SELECT * FROM $table WHERE $pk=${tableIds.getInt(i)};\n")
            }
            val (ms, result) = clientRun(sql.toString(), "pocket_core", core, "classicmangos")
            assertTrue("$table batch failed: ${result.stderr.take(400)}", result.ok)
            val perCallMs = (ms - spawnBaselineMs).coerceAtLeast(0) / tableIds.length().toDouble()
            lookups.put(table, JSONObject()
                .put("batchWallMs", ms).put("perStatementMs", perCallMs))
        }
        battery.put("pointLookups1k", lookups)

        val scans = JSONObject()
        fun batch(name: String, sqlBody: String) {
            val (ms, result) = clientRun(sqlBody, "pocket_core", core, "classicmangos")
            assertTrue("$name failed: ${result.stderr.take(400)}", result.ok)
            scans.put(name, JSONObject().put("batchWallMs", ms)
                .put("netMs", (ms - spawnBaselineMs).coerceAtLeast(0)))
        }
        val scanSql = buildString {
            repeat(10) { i -> append("SELECT COUNT(*) FROM creature_loot_template WHERE entry BETWEEN ${i * 1000} AND ${i * 1000 + 200};\n") }
            append("SELECT COUNT(*) FROM creature_loot_template;\n")
            append("SELECT COUNT(*) FROM creature;\n")
            append("SELECT COUNT(*) FROM ai_playerbot_travelnode_path;\n")
            append("SELECT COUNT(*) FROM (SELECT c.guid,c.id,ct.name FROM creature c JOIN creature_template ct ON ct.entry=c.id LIMIT 5000) t;\n")
        }
        batch("scansCountsJoin", scanSql)
        battery.put("scansJoinsMs", scans)

        // Write burst in a scratch DATABASE (product schemas untouched).
        val writeSql = buildString {
            append("CREATE DATABASE IF NOT EXISTS bench_scratch;\n")
            append("USE bench_scratch;\n")
            append("CREATE TABLE bench_scratch(a INT PRIMARY KEY, b VARCHAR(64)) ENGINE=InnoDB;\n")
            append("START TRANSACTION;\n")
            for (i in 1..500) append("INSERT INTO bench_scratch VALUES($i,'row-$i');\n")
            append("COMMIT;\n")
            append("START TRANSACTION;\n")
            for (i in 1..100) append("UPDATE bench_scratch SET b='upd-$i' WHERE a=$i;\n")
            append("COMMIT;\n")
            append("START TRANSACTION;\n")
            for (i in 301..500) append("DELETE FROM bench_scratch WHERE a=$i;\n")
            append("COMMIT;\n")
            append("DROP DATABASE bench_scratch;\n")
        }
        val (writeMs, writeResult) = clientRun(writeSql, "pocket_admin", admin)
        assertTrue("write burst failed: ${writeResult.stderr.take(400)}", writeResult.ok)
        battery.put("writeBurst", JSONObject()
            .put("batchWallMs", writeMs).put("netMs", (writeMs - spawnBaselineMs).coerceAtLeast(0)))

        return battery
    }

    // ===========================================================================
    // helpers
    // ===========================================================================

    private fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun timedNanos(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    private fun latencySummary(samplesMs: List<Double>): JSONObject {
        val sorted = samplesMs.sorted()
        fun pct(p: Double) = sorted[(sorted.size * p).toInt().coerceAtMost(sorted.size - 1)]
        return JSONObject()
            .put("count", sorted.size)
            .put("p50Ms", pct(0.50)).put("p95Ms", pct(0.95)).put("p99Ms", pct(0.99))
            .put("maxMs", sorted.last())
            .put("totalMs", sorted.sum())
    }

    private fun sortedRttSummary(samples: List<Long>): JSONObject {
        val sorted = samples.sorted()
        return JSONObject().put("count", sorted.size)
            .put("minMs", sorted.first())
            .put("p50Ms", sorted[sorted.size / 2])
            .put("maxMs", sorted.last())
    }

    /** Best-effort RSS of the database-serving processes (the :database
     * service +, on MariaDB, the mariadbd daemon tree) — same-UID /proc. */
    private fun databaseRssKb(): Long {
        var total = 0L
        val proc = File("/proc")
        for (pidDir in proc.listFiles { f -> f.name.toIntOrNull() != null } ?: return 0) {
            val cmd = runCatching { File(pidDir, "cmdline").readText() }.getOrNull() ?: continue
            val isDatabaseService = cmd.contains("com.pocketrealm:database")
            val isMariadbd = cmd.contains("mariadbd")
            if (!isDatabaseService && !isMariadbd) continue
            val status = runCatching { File(pidDir, "status").readText() }.getOrNull() ?: continue
            val rss = Regex("VmRSS:\\s+(\\d+) kB").find(status)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            total += rss
        }
        return total
    }

    private fun dirSize(root: File): Long {
        if (!root.exists()) return 0
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun bind(component: String, convert: (IBinder) -> IDatabaseControl): Bound {
        val latch = CountDownLatch(1)
        var binder: IDatabaseControl? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = convert(service!!); latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        connections.add(connection)
        val intent = Intent().setClassName(context, component)
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        assertTrue(latch.await(60, TimeUnit.SECONDS))
        return Bound(binder!!)
    }

    private inner class Bound(val api: IDatabaseControl) {
        // The connection stays in `connections` until @After — one handle, one service.
    }

    private fun assertOk(raw: String): JSONObject {
        val parsed = JSONObject(raw)
        assertTrue("control failure: $raw", parsed.optBoolean("ok"))
        return parsed
    }
}
