package com.pocketrealm.llm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.pocketrealm.R
import com.pocketrealm.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service (dataSync) that downloads the on-device LLM model GGUF
 * (2.6 GB class) via LlmModelCoordinator. Follows the ImportWorkerService
 * pattern: partial wake lock for the duration, progress in the notification,
 * cancellation via a stop action, and a resumable download that discards on
 * checksum mismatch. The service runs in the main process; the model file is
 * consumed by the embedded world runtime through AiPlayerbot.LLMModelPath.
 */
class LlmModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val lastProgress = AtomicLong(-1)

    @Volatile private var cancelled = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            task?.cancel(CancellationException("cancelled by user"))
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_DOWNLOAD || task?.isActive == true) {
            // this delivery may have arrived via startForegroundService: the
            // contract requires a startForeground call for it regardless of
            // the dedupe outcome (idempotent when already foreground)
            startForegroundCompat()
            return START_REDELIVER_INTENT
        }
        cancelled = false
        startForegroundCompat()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:llm-model-download")
            .apply { acquire(8L * 60L * 60L * 1000L) }
        // the registry id is the single source of truth for the whole
        // descriptor (fileName, url, size, sha256): loose extras for the
        // individual fields could mix a selected model's pinned bytes into
        // another model's staged file name. Built before the launch so the
        // failure notification can rebuild its retry intent with the SAME
        // descriptor this task used.
        val descriptor = LlmModelRegistry.byId(intent.getStringExtra(EXTRA_MODEL_ID))
            .let {
                LlmModelCoordinator.ModelDescriptor(
                    fileName = it.fileName, url = it.url, size = it.size, sha256 = it.sha256,
                )
            }
        task = scope.launch {
            try {
                val file = LlmModelCoordinator.download(applicationContext, descriptor, { written ->
                    val mb = written / (1024L * 1024L)
                    if (mb != lastProgress.getAndSet(mb)) {
                        updateNotification(mb, descriptor.size / (1024L * 1024L))
                    }
                }, { cancelled })
                AppLog.i(TAG, "model staged: ${file.absolutePath} (${file.length()} bytes)")
                doneNotification(true)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (cancelled) {
                    AppLog.i(TAG, "model download cancelled by user")
                } else {
                    AppLog.e(TAG, "model download failed: ${error.message}")
                    doneNotification(false, descriptor.fileName)
                }
            } finally {
                wakeLock?.takeIf { it.isHeld }?.release()
                wakeLock = null
                // remove the ongoing progress notification; the terminal state
                // lives in the separate done-notification id
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        cancelled = true
        task?.cancel(CancellationException("service destroyed"))
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("Preparing model download…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String, ongoing: Boolean = true): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 0,
            Intent(this, LlmModelDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Cancel", cancelIntent).build())
            .build()
    }

    private fun updateNotification(mibDone: Long, mibTotal: Long) {
        val text = if (mibTotal > 0) "Downloading model: $mibDone / $mibTotal MB" else "Downloading model: $mibDone MB"
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun doneNotification(ok: Boolean, descriptorFileName: String? = null) {
        val manager = getSystemService(NotificationManager::class.java)
        if (ok) {
            // success is informational: no Cancel action (it targets the
            // progress id and would be dead here) and tappable away
            val builder = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Model ready")
                .setOngoing(false)
                .setAutoCancel(true)
            packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        this, 2, launch,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
            manager.notify(DONE_NOTIFICATION_ID, builder.build())
            return
        }
        // terminal failure: the notification itself is the retry surface, and
        // the ongoing Cancel action (which targets the progress id) is dropped.
        // The retry carries the failed download's registry id so it re-fetches
        // the same pinned descriptor, never whatever the default names.
        val descriptorId = LlmModelRegistry.all
            .firstOrNull { it.fileName == descriptorFileName }?.id
            ?: LlmModelRegistry.DEFAULT_MODEL_ID
        val retryIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LlmModelDownloadService::class.java).setAction(ACTION_DOWNLOAD)
                .putExtra(EXTRA_MODEL_ID, descriptorId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            DONE_NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Model download failed — tap to retry")
                .setOngoing(false)
                .setContentIntent(retryIntent)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "LLM model download", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val TAG = "LlmModelDownload"
        private const val CHANNEL_ID = "llm-model-download"
        private const val NOTIFICATION_ID = 41
        private const val DONE_NOTIFICATION_ID = 42
        private const val ACTION_DOWNLOAD = "com.pocketrealm.llm.action.DOWNLOAD"
        private const val ACTION_CANCEL = "com.pocketrealm.llm.action.CANCEL"
        private const val EXTRA_MODEL_ID = "model_id"

        /** Downloads the registry model selected by [modelId] (default = registry default). */
        // modelId null falls back to DEFAULT_MODEL_ID - since the S4 flip that is
        // the LOCAL-ONLY tuned model (no URL): a null-id download fails by
        // design. Production callers pass the selected descriptor's id.
        fun start(context: Context, modelId: String? = null) {
            context.startForegroundService(
                Intent(context, LlmModelDownloadService::class.java)
                    .setAction(ACTION_DOWNLOAD)
                    .putExtra(EXTRA_MODEL_ID, modelId ?: LlmModelRegistry.DEFAULT_MODEL_ID),
            )
        }
    }
}
