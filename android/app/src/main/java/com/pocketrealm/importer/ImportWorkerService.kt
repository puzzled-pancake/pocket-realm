package com.pocketrealm.importer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pocketrealm.BuildConfig
import com.pocketrealm.R
import com.pocketrealm.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Finite, restartable import work isolated from the UI and Wine processes. */
class ImportWorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var task: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val binder = object : IImportWorker.Stub() {
        override fun statusJson(): String = readStatus(applicationContext).toString()
        override fun cancel() { task?.cancel(CancellationException("cancelled by user")) }
        override fun killForTest() {
            check(BuildConfig.DEBUG) { "test process kill is debug-only" }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_IMPORT || task?.isActive == true) return START_REDELIVER_INTENT
        val rawUri = intent.getStringExtra(EXTRA_TREE_URI) ?: return START_NOT_STICKY
        val testProfile = intent.getBooleanExtra(EXTRA_TEST_PROFILE, false) && BuildConfig.DEBUG
        val interruptAfter = if (testProfile) intent.getIntExtra(EXTRA_INTERRUPT_AFTER, 0) else 0
        val interruptPoint = if (testProfile) intent.getStringExtra(EXTRA_INTERRUPT_POINT) else null
        startForegroundCompat("Checking selected client…")
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:o11-import")
            .apply { acquire(8L * 60L * 60L * 1000L) }
        task = scope.launch {
            val importer = if (testProfile) ManagedClientImporter(
                applicationContext,
                ImportLimits(minFiles = 3, minTotalBytes = 1, maxFiles = 128, maxTotalBytes = 64L shl 20),
                storagePlanner = ImportStoragePlanner(
                    applicationContext, extractedEstimate = 0, wineEstimate = 0,
                    minimumReserve = 16L shl 20,
                ),
                prepareData = false,
            ) else ManagedClientImporter(
                applicationContext,
                // The database-only ARM lane verifies and publishes the
                // managed client but defers memory-heavy O11 extraction to a
                // later full runtime APK. Full lanes retain the production
                // preparation contract.
                prepareData = BuildConfig.ENABLE_CLIENT_DATA_PREPARATION,
            )
            try {
                importer.run(
                    Uri.parse(rawUri),
                    afterVerified = { verified ->
                        updateNotification(importer.status())
                        if (interruptAfter > 0 && verified >= interruptAfter) killTestProcess()
                    },
                    beforePublish = { if (interruptPoint == INTERRUPT_BEFORE_PUBLISH) killTestProcess() },
                    afterRenameBeforeActivate = {
                        if (interruptPoint == INTERRUPT_AFTER_RENAME) killTestProcess()
                    },
                )
                updateNotification(importer.status())
            } catch (_: CancellationException) {
                // The durable journal remains PAUSED and can be resumed safely.
            } catch (_: Throwable) {
                updateNotification(importer.status())
            } finally {
                importer.close()
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                ServiceCompat.stopForeground(this@ImportWorkerService, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        }
        return if (testProfile) START_NOT_STICKY else START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        task?.cancel()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun startForegroundCompat(text: String) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(text),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun updateNotification(status: ImportStatus) {
        val checkpoint = status.lastRelativePath?.let { " • $it" }.orEmpty()
        val text = "${status.phase.name.lowercase().replace('_', ' ')}: " +
            "${status.filesProcessed}/${status.filesTotal} files$checkpoint"
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Pocket Realm client import")
        .setContentText(text).setOnlyAlertOnce(true).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )).build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Client import", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        private const val ACTION_IMPORT = "com.pocketrealm.action.IMPORT_CLIENT"
        private const val EXTRA_TREE_URI = "tree_uri"
        private const val EXTRA_TEST_PROFILE = "test_profile"
        private const val EXTRA_INTERRUPT_AFTER = "interrupt_after"
        private const val EXTRA_INTERRUPT_POINT = "interrupt_point"
        const val INTERRUPT_BEFORE_PUBLISH = "BEFORE_PUBLISH"
        const val INTERRUPT_AFTER_RENAME = "AFTER_RENAME_BEFORE_ACTIVATE"
        private const val CHANNEL = "client_import"
        private const val NOTIFICATION_ID = 1101

        fun start(
            context: Context, uri: Uri, testProfile: Boolean = false, interruptAfter: Int = 0,
            interruptPoint: String? = null,
        ) {
            val intent = Intent(context, ImportWorkerService::class.java).setAction(ACTION_IMPORT)
                .putExtra(EXTRA_TREE_URI, uri.toString()).putExtra(EXTRA_TEST_PROFILE, testProfile)
                .putExtra(EXTRA_INTERRUPT_AFTER, interruptAfter).putExtra(EXTRA_INTERRUPT_POINT, interruptPoint)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        private fun killTestProcess(): Nothing {
            android.os.Process.killProcess(android.os.Process.myPid())
            throw AssertionError("killProcess returned")
        }

        fun readStatus(context: Context): JSONObject = ManagedClientImporter(context).use { importer ->
            val value = importer.status()
            JSONObject().put("schema", 1).put("phase", value.phase.name)
                .put("filesProcessed", value.filesProcessed).put("filesTotal", value.filesTotal)
                .put("bytesCopied", value.bytesCopied).put("bytesTotal", value.bytesTotal)
                .put("lastRelativePath", value.lastRelativePath).put("warningCount", value.warningCount)
                .put("lastError", value.lastError).put("activeGeneration", value.activeGeneration)
                .put("dataPreparationEnabled", BuildConfig.ENABLE_CLIENT_DATA_PREPARATION)
                .put("updatedAtMs", value.updatedAtMs)
        }
    }
}
