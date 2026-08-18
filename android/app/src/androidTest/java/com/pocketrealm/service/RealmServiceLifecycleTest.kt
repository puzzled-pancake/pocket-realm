package com.pocketrealm.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketrealm.supervisor.IRuntimeSupervisorControl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Compatibility guard: the former simulated service is now the real :supervisor Binder owner. */
@RunWith(AndroidJUnit4::class)
class RealmServiceLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun serviceLivesInDedicatedSupervisorProcessAndPublishesJournalState() {
        val connected = CountDownLatch(1)
        var control: IRuntimeSupervisorControl? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                control = IRuntimeSupervisorControl.Stub.asInterface(service)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        assertTrue(context.bindService(Intent(context, RealmService::class.java), connection, Context.BIND_AUTO_CREATE))
        assertTrue(connected.await(10, TimeUnit.SECONDS))
        val status = JSONObject(checkNotNull(control).status())
        assertTrue(status.getBoolean("ok"))
        assertEquals(2, status.getInt("schema"))
        val processes = context.getSystemService(android.app.ActivityManager::class.java)
            .runningAppProcesses.orEmpty().map { it.processName }
        assertTrue(processes.contains("${context.packageName}:supervisor"))
        context.unbindService(connection)
    }
}
