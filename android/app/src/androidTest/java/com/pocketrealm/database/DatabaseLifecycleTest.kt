package com.pocketrealm.database

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DatabaseLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val evidence = JSONObject()
    private var connection: ServiceConnection? = null

    @After fun unbind() {
        connection?.let { runCatching { context.unbindService(it) } }
    }

    @Test fun o08FullAcceptance() {
        val control = bind()
        val provider = assertOk("status", control.status())
            .also { assertTrue(it.getBoolean("providerReady")) }
            .optString("providerMode")
        // leastPrivilegeVerified/privilegedActionDenied are the MariaDB
        // bootstrap's least-privilege probes (CREATE USER denial etc.);
        // the socketless SQLite lane has no daemon credential surface.
        // cleanStopped is the born-clean D6 contract on BOTH providers'
        // first-run shapes (idempotent re-runs omit it).
        assertOk("initialize", control.initialize()).also {
            if (provider == "MARIADB") {
                assertTrue(it.getBoolean("leastPrivilegeVerified"))
                assertTrue(it.getBoolean("privilegedActionDenied"))
            }
            if (!it.optBoolean("idempotent")) {
                assertTrue(it.getBoolean("cleanStopped"))
            }
        }
        assertOk("migrations", control.applyPinnedMigrations()).also {
            assertTrue(it.getInt("total") > 100)
            // the idempotent re-run shape (ok/applied/skipped/total/
            // cleanStopped) carries no revisionMismatchRejected; the
            // first-run shape of BOTH providers must.
            if (!it.optBoolean("idempotent")) {
                assertTrue(it.getBoolean("revisionMismatchRejected"))
            }
            assertTrue(it.getBoolean("cleanStopped"))
        }
        assertOk("storage-full", control.storageFullTest()).also {
            assertEquals("DB-FULL", it.getString("classification"))
            assertTrue(it.getBoolean("refusedBeforeWrite"))
        }
        assertOk("snapshot-restore", control.snapshotAndRestoreTest()).also {
            assertTrue(it.getBoolean("restoredAndQueried"))
            assertFalse(it.getBoolean("liveDatadirCopied"))
        }
        assertOk("start", control.start()).also { assertTrue(it.getBoolean("tcpDisabled")) }
        assertOk("health", control.queryHealth()).also { assertTrue(it.getBoolean("authenticated")) }
        assertOk("stop", control.stop()).also { assertTrue(it.getBoolean("cleanMarker")) }

        assertOk("dirty-start", control.start())
        assertOk("dirty-kill", control.killForTest()).also { assertFalse(it.getBoolean("cleanMarker")) }
        assertOk("recovery", control.recover()).also {
            assertTrue(it.getBoolean("recovered"))
            assertTrue(it.getBoolean("recoveryOutputObserved"))
            assertTrue(it.getBoolean("cleanStopped"))
        }
        // Self-heal contract (addendum 6/7 follow-up, SQLite): after an
        // unclean death a bare start() recovers in place (WAL checkpoint +
        // integrity gate + re-seal) instead of refusing until a full
        // uninstall. MariaDB keeps its explicit recover() contract.
        if (evidence.getJSONObject("status").optString("providerMode") == "SQLITE") {
            assertOk("self-heal-prestart", control.start())
            assertOk("self-heal-kill", control.killForTest()).also { assertFalse(it.getBoolean("cleanMarker")) }
            assertOk("self-healed-start", control.start()).also {
                assertTrue("start must report the in-place self-heal", it.getBoolean("selfHealed"))
            }
            assertOk("self-healed-health", control.queryHealth()).also {
                assertTrue(it.getBoolean("authenticated"))
            }
            assertOk("self-healed-stop", control.stop()).also { assertTrue(it.getBoolean("cleanMarker")) }
            // The second admission surface: a dirty generation must also
            // self-heal through applyPinnedMigrations (the runner calls
            // init -> migrations -> start, so migrations is usually the
            // first gate an unclean death actually hits).
            assertOk("self-heal-mig-prestart", control.start())
            assertOk("self-heal-mig-kill", control.killForTest()).also { assertFalse(it.getBoolean("cleanMarker")) }
            assertOk("self-healed-migrations", control.applyPinnedMigrations()).also {
                assertTrue("migrations must report the in-place self-heal", it.getBoolean("selfHealed"))
            }
            assertOk("self-heal-mig-status", control.status()).also {
                assertTrue(it.getBoolean("cleanMarker"))
            }
        }
        assertOk("final-status", control.status()).also {
            assertEquals("STOPPED", it.getString("state"))
            assertTrue(it.getBoolean("cleanMarker"))
        }
        evidence.put("schema", 1)
            .put("deviceModel", Build.MODEL)
            .put("api", Build.VERSION.SDK_INT)
            .put("abis", Build.SUPPORTED_ABIS.joinToString(","))
            .put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
            .put("variant", "databaseRuntime")
            .put("test", "DatabaseLifecycleTest.o08FullAcceptance")
            .put("recordedAt", System.currentTimeMillis())
        File(context.filesDir, "o08-database-acceptance.json")
            .writeText(evidence.toString(2) + "\n")
    }

    private fun assertOk(label: String, raw: String): JSONObject {
        val json = JSONObject(raw)
        assertTrue("$label failed: $raw", json.optBoolean("ok"))
        evidence.put(label, json)
        return json
    }

    private fun bind(): IDatabaseControl {
        val latch = CountDownLatch(1)
        var control: IDatabaseControl? = null
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                control = IDatabaseControl.Stub.asInterface(binder); latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        connection = serviceConnection
        val intent = Intent().setClassName(context, "com.pocketrealm.database.DatabaseService")
        assertTrue(context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE))
        assertTrue("database service bind timeout", latch.await(15, TimeUnit.SECONDS))
        return requireNotNull(control)
    }
}
