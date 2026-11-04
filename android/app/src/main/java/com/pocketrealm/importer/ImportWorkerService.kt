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
import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.storage.Settings
import com.pocketrealm.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
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
        if (task?.isActive == true) return START_REDELIVER_INTENT
        if (intent?.action == ACTION_IMPORT_ARCHIVE) return startArchiveImport(intent, startId)
        if (intent?.action == ACTION_PREPARE_SERVER_DATA) return startServerDataBuild(startId)
        if (intent?.action != ACTION_IMPORT) return START_REDELIVER_INTENT
        val rawUri = intent.getStringExtra(EXTRA_TREE_URI) ?: return START_NOT_STICKY
        val testProfile = intent.getBooleanExtra(EXTRA_TEST_PROFILE, false) && BuildConfig.DEBUG
        val interruptAfter = if (testProfile) intent.getIntExtra(EXTRA_INTERRUPT_AFTER, 0) else 0
        val interruptPoint = if (testProfile) intent.getStringExtra(EXTRA_INTERRUPT_POINT) else null
        startForegroundCompat("Checking selected client…")
        ImportProcessMetricsSampler.markStarted(applicationContext)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:o11-import")
            .apply { acquire(8L * 60L * 60L * 1000L) }
        task = scope.launch {
            seedLaneFromPersistedPick()
            // The construction resolves the ACTIVE lane — a
            // multi-qualified no-pick refusal lands in the notification.
            // the refusal is a designed
            // first-run outcome, so it must run the SAME teardown as a
            // finished run — the wake lock is already held and the
            // foreground state is set; a bare return@launch leaked one 8h
            // PARTIAL_WAKE_LOCK per attempt and kept the foreground
            // service up.
            val importer = try {
                buildImporter(testProfile, joinOnly(applicationContext))
            } catch (_: IllegalStateException) {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(
                        "Pick a game version on the Home screen first, then " +
                            "start the import again.",
                    ),
                )
                ImportProcessMetricsSampler.markStopped(applicationContext)
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                ServiceCompat.stopForeground(this@ImportWorkerService, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopSelf(startId)
                return@launch
            }
            try {
                importer.run(
                    Uri.parse(rawUri),
                    afterVerified = { verified ->
                        updateNotification(importer)
                        if (interruptAfter > 0 && verified >= interruptAfter) killTestProcess()
                    },
                    beforePublish = { if (interruptPoint == INTERRUPT_BEFORE_PUBLISH) killTestProcess() },
                    afterRenameBeforeActivate = {
                        if (interruptPoint == INTERRUPT_AFTER_RENAME) killTestProcess()
                    },
                    onDataStageTick = { notifyDataStageTick(importer) },
                )
                updateNotification(importer)
            } catch (_: CancellationException) {
                // The durable journal remains PAUSED and can be resumed safely.
            } catch (failure: Throwable) {
                // The durable journal and notification are the only
                // post-mortem channels a resumed session has: a silently
                // swallowed failure froze the UI at "staging: 0/0 files"
                // through two full retry loops with nothing to diagnose.
                runCatching { importer.journalFailure(failure) }
                android.util.Log.e(
                    "PocketRealmImport",
                    "import failed: ${failure.message}",
                    failure,
                )
                updateNotification(importer, failure.message)
            } finally {
                importer.close()
                ImportProcessMetricsSampler.markStopped(applicationContext)
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
        ImportProcessMetricsSampler.markStopped(applicationContext)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun startArchiveImport(intent: Intent, startId: Int): Int {
        val rawUri = intent.getStringExtra(EXTRA_TREE_URI) ?: return START_NOT_STICKY
        val expectedBytes = intent.getLongExtra(EXTRA_ARCHIVE_BYTES, -1L)
        if (expectedBytes <= 0L) return START_NOT_STICKY
        val testProfile = intent.getBooleanExtra(EXTRA_TEST_PROFILE, false) && BuildConfig.DEBUG
        val interruptAfter = if (testProfile) intent.getIntExtra(EXTRA_INTERRUPT_AFTER, 0) else 0
        val interruptPoint = if (testProfile) intent.getStringExtra(EXTRA_INTERRUPT_POINT) else null
        startForegroundCompat("Copying archive into Pocket Realm…")
        ImportProcessMetricsSampler.markStarted(applicationContext)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:o11-import")
            .apply { acquire(8L * 60L * 60L * 1000L) }
        task = scope.launch {
            seedLaneFromPersistedPick()
            // See the folder arm's lane refusal note.
            val importer = try {
                buildImporter(testProfile, joinOnly(applicationContext))
            } catch (_: IllegalStateException) {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(
                        "Pick a game version on the Home screen first, then " +
                            "start the import again.",
                    ),
                )
                // same teardown as the folder arm's refusal — the
                // held wake lock and the foreground state are not the next
                // try/finally's to clean up (that block is never reached).
                ImportProcessMetricsSampler.markStopped(applicationContext)
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                ServiceCompat.stopForeground(this@ImportWorkerService, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopSelf(startId)
                return@launch
            }
            try {
                importer.runArchive(
                    Uri.parse(rawUri),
                    expectedBytes,
                    afterStaging = { if (interruptPoint == INTERRUPT_AFTER_STAGING) killTestProcess() },
                    afterDetection = { if (interruptPoint == INTERRUPT_AFTER_DETECTION) killTestProcess() },
                    onScratchEntry = { filesDone ->
                        if (interruptPoint == INTERRUPT_DURING_SCRATCH &&
                            interruptAfter > 0 && filesDone >= interruptAfter
                        ) killTestProcess()
                    },
                    afterVerified = { verified ->
                        updateNotification(importer)
                        if (interruptAfter > 0 && verified >= interruptAfter) killTestProcess()
                    },
                    beforePublish = { if (interruptPoint == INTERRUPT_BEFORE_PUBLISH) killTestProcess() },
                    afterRenameBeforeActivate = {
                        if (interruptPoint == INTERRUPT_AFTER_RENAME) killTestProcess()
                    },
                    onDataStageTick = { notifyDataStageTick(importer) },
                )
                updateNotification(importer)
            } catch (_: CancellationException) {
                // The durable journal remains PAUSED and can be resumed safely.
            } catch (failure: Throwable) {
                // The durable journal and notification are the only
                // post-mortem channels a resumed session has: a silently
                // swallowed failure froze the UI at "staging: 0/0 files"
                // through two full retry loops with nothing to diagnose.
                runCatching { importer.journalFailure(failure) }
                android.util.Log.e(
                    "PocketRealmImport",
                    "import failed: ${failure.message}",
                    failure,
                )
                updateNotification(importer, failure.message)
            } finally {
                importer.close()
                ImportProcessMetricsSampler.markStopped(applicationContext)
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                ServiceCompat.stopForeground(this@ImportWorkerService, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        }
        return if (testProfile) START_NOT_STICKY else START_REDELIVER_INTENT
    }

    /** Builds the server's world data for the already-imported client
     * (the step a join-only import skipped). */
    private fun startServerDataBuild(startId: Int): Int {
        startForegroundCompat("Building server data…")
        ImportProcessMetricsSampler.markStarted(applicationContext)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:o11-import")
            .apply { acquire(8L * 60L * 60L * 1000L) }
        task = scope.launch {
            seedLaneFromPersistedPick()
            val importer = runCatching { buildImporter(testProfile = false, joinOnly = false) }.getOrNull()
            try {
                if (importer == null) {
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID,
                        notification("Pick a game version on the Home screen first, then try again."),
                    )
                } else {
                    importer.prepareActiveServerData(onDataStageTick = { notifyDataStageTick(importer) })
                    setJoinOnly(applicationContext, false)
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification("Server data ready."))
                }
            } catch (_: CancellationException) {
                // Finished stages are kept; building again resumes.
            } catch (failure: Throwable) {
                android.util.Log.e("PocketRealmImport", "server data build failed: ${failure.message}", failure)
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification("Server data build stopped: ${failure.message}".take(300)),
                )
            } finally {
                importer?.close()
                ImportProcessMetricsSampler.markStopped(applicationContext)
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                ServiceCompat.stopForeground(this@ImportWorkerService, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startForegroundCompat(text: String) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(text),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    /** Data-stage ticks fire once a second; throttle notification work. */
    private fun notifyDataStageTick(importer: ManagedClientImporter) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDataNotifyMs < DATA_NOTIFY_INTERVAL_MS) return
        lastDataNotifyMs = now
        updateNotification(importer)
    }

    private var lastDataNotifyMs = 0L

    /**
     * Without the data-stage preference the notification would freeze on the
     * last copied MPQ for the entire (many-minute) server-data phase. Prefer
     * the running data stage; fall back to the copy-phase file counter.
     */
    /**
     * The Home picker's flavor selection is :ui-process-local memory; this
     * service runs in :import, whose holder starts empty — with several
     * qualified flavors the construction's bare resolve refuses "found 3"
     * even though the pick was made (and persisted) on Home. Re-apply the
     * persisted pick before construction — the same persisted-key threading
     * the :supervisor/:world/:database lanes do. A null or unqualified
     * persisted id leaves the holder empty, so the designed first-run
     * refusal below still fires with its honest copy.
     */
    private suspend fun seedLaneFromPersistedPick() {
        if (ActiveFlavor.selectedId != null) return
        val persisted = Settings(applicationContext).flow.first().selectedFlavorOrNull() ?: return
        runCatching { ActiveFlavor.select(persisted) }
    }

    /** The importer construction resolves the ACTIVE lane
     * through the storage planner's flavor budgets — extracted so both
     * launch arms share one construction and the multi-qualified no-pick
     * refusal surfaces as the service's honest notification instead of a
     * process crash. */
    private fun buildImporter(testProfile: Boolean, joinOnly: Boolean): ManagedClientImporter =
        if (testProfile) ManagedClientImporter(
            applicationContext,
            ImportLimits(minFiles = 3, minTotalBytes = 1, maxFiles = 128, maxTotalBytes = 64L shl 20),
            storagePlanner = ImportStoragePlanner(
                applicationContext, extractedEstimate = 0, preparedEstimate = 0,
                wineEstimate = 0, minimumReserve = 16L shl 20,
            ),
            prepareData = false,
        ) else if (joinOnly) ManagedClientImporter(
            // Join only: the game files alone, so no server-data space is
            // reserved and the server build is skipped.
            applicationContext,
            storagePlanner = ImportStoragePlanner(applicationContext, preparedEstimate = 0),
            prepareData = false,
        ) else ManagedClientImporter(
            applicationContext,
            prepareData = BuildConfig.ENABLE_CLIENT_DATA_PREPARATION,
        )

    private fun updateNotification(importer: ManagedClientImporter, failureMessage: String? = null) {
        val status = importer.status()
        val active = importer.dataCheckpoints(status.importId)
            .firstOrNull { it.state == DataStageState.RUNNING }
        val text = if (active != null) {
            val counter = if (active.total > 0) " ${active.processed}/${active.total}" else ""
            "server data$counter • ${active.checkpoint ?: active.stage.name.lowercase().replace('_', ' ')}"
        } else {
            val checkpoint = status.lastRelativePath?.let { " • $it" }.orEmpty()
            val base = "${status.phase.name.lowercase().replace('_', ' ')}: " +
                "${status.filesProcessed}/${status.filesTotal} files$checkpoint"
            if (failureMessage != null) "$base • failed: $failureMessage".take(300) else base
        }
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
        private const val ACTION_IMPORT_ARCHIVE = "com.pocketrealm.action.IMPORT_CLIENT_ARCHIVE"
        private const val ACTION_PREPARE_SERVER_DATA = "com.pocketrealm.action.PREPARE_SERVER_DATA"
        private const val OPTIONS_PREFS = "import_options"
        private const val KEY_JOIN_ONLY = "join_only"
        private const val EXTRA_TREE_URI = "tree_uri"
        private const val EXTRA_ARCHIVE_BYTES = "archive_bytes"
        private const val EXTRA_TEST_PROFILE = "test_profile"
        private const val EXTRA_INTERRUPT_AFTER = "interrupt_after"
        private const val EXTRA_INTERRUPT_POINT = "interrupt_point"
        const val INTERRUPT_AFTER_STAGING = "AFTER_STAGING"
        const val INTERRUPT_AFTER_DETECTION = "AFTER_DETECTION"
        const val INTERRUPT_BEFORE_PUBLISH = "BEFORE_PUBLISH"
        const val INTERRUPT_AFTER_RENAME = "AFTER_RENAME_BEFORE_ACTIVATE"
        const val INTERRUPT_DURING_SCRATCH = "DURING_SCRATCH"
        private const val CHANNEL = "client_import"
        private const val NOTIFICATION_ID = 1101
        private const val DATA_NOTIFY_INTERVAL_MS = 5_000L

        /** Whether the current import copies the game files only (kept
         * across restarts and resumes of that import). */
        fun joinOnly(context: Context): Boolean =
            context.getSharedPreferences(OPTIONS_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_JOIN_ONLY, false)

        fun setJoinOnly(context: Context, joinOnly: Boolean) {
            context.getSharedPreferences(OPTIONS_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_JOIN_ONLY, joinOnly).commit()
        }

        /** Builds the server's world data for the imported client. */
        fun buildServerData(context: Context) {
            val intent = Intent(context, ImportWorkerService::class.java).setAction(ACTION_PREPARE_SERVER_DATA)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun start(
            context: Context, uri: Uri, testProfile: Boolean = false, interruptAfter: Int = 0,
            interruptPoint: String? = null,
        ) {
            val intent = Intent(context, ImportWorkerService::class.java).setAction(ACTION_IMPORT)
                .putExtra(EXTRA_TREE_URI, uri.toString()).putExtra(EXTRA_TEST_PROFILE, testProfile)
                .putExtra(EXTRA_INTERRUPT_AFTER, interruptAfter).putExtra(EXTRA_INTERRUPT_POINT, interruptPoint)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun startArchive(
            context: Context, uri: Uri, expectedBytes: Long, testProfile: Boolean = false,
            interruptAfter: Int = 0, interruptPoint: String? = null,
        ) {
            val intent = Intent(context, ImportWorkerService::class.java).setAction(ACTION_IMPORT_ARCHIVE)
                .putExtra(EXTRA_TREE_URI, uri.toString()).putExtra(EXTRA_ARCHIVE_BYTES, expectedBytes)
                .putExtra(EXTRA_TEST_PROFILE, testProfile)
                .putExtra(EXTRA_INTERRUPT_AFTER, interruptAfter).putExtra(EXTRA_INTERRUPT_POINT, interruptPoint)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        /**
         * Watchdog/resume routing: restart whichever lane the durable journal
         * describes. The archive lane's restart size is phase-dependent:
         * before staging finishes, bytes_total IS the source archive size and
         * stagedBytes is mere progress (passing progress as the size once
         * deleted the partial and re-staged toward a truncated target); after
         * finishStaging, bytes_total becomes the extraction payload total for
         * progress display, and the staged file's journaled size — which
         * equals the source size by construction — is the durable anchor.
         */
        fun resumeActive(context: Context, uri: String, testProfile: Boolean = false): Boolean {
            ManagedClientImporter(context).use { importer ->
                val status = importer.status()
                if (status.sourceUri != uri) return false
                return when (status.sourceKind) {
                    ImportSourceKind.ARCHIVE -> {
                        val size = when {
                            status.stagedPath != null && status.stagedBytes > 0 -> status.stagedBytes
                            else -> status.bytesTotal
                        }
                        if (size <= 0) false else {
                            startArchive(context, Uri.parse(uri), size, testProfile); true
                        }
                    }
                    ImportSourceKind.TREE -> {
                        start(context, Uri.parse(uri), testProfile); true
                    }
                }
            }
        }

        /** True when the active client's server world data is built. */
        private fun serverDataReady(context: Context): Boolean = runCatching {
            com.pocketrealm.server.PreparedDataStore(
                java.io.File(com.pocketrealm.storage.StorageRoots.get(context).content, "o11-server"),
            ).requireActiveEnvelope(ActiveFlavor.resolve("the server data check reads the active version"))
        }.isSuccess

        private fun killTestProcess(): Nothing {
            android.os.Process.killProcess(android.os.Process.myPid())
            throw AssertionError("killProcess returned")
        }

        fun readStatus(context: Context): JSONObject = ManagedClientImporter(context).use { importer ->
            val value = importer.status()
            val checkpoints = importer.dataCheckpoints(value.importId)
            val activeFile = importer.activeFile(value.importId)
            val metrics = ImportProcessMetricsSampler.sample(context.applicationContext)
            JSONObject().put("schema", 2).put("phase", value.phase.name)
                .put("sourceKind", value.sourceKind.name).put("stagedBytes", value.stagedBytes)
                .put("filesProcessed", value.filesProcessed).put("filesTotal", value.filesTotal)
                .put("bytesCopied", value.bytesCopied).put("bytesTotal", value.bytesTotal)
                .put("lastRelativePath", value.lastRelativePath).put("warningCount", value.warningCount)
                .put("lastError", value.lastError).put("activeGeneration", value.activeGeneration)
                .put("sourceUri", value.sourceUri ?: JSONObject.NULL)
                .put("dataPreparationEnabled", BuildConfig.ENABLE_CLIENT_DATA_PREPARATION)
                .put("joinOnly", joinOnly(context))
                .put("serverDataReady", serverDataReady(context))
                .put("updatedAtMs", value.updatedAtMs)
                .put("activeFile", activeFile?.let {
                    JSONObject().put("relativePath", it.relativePath)
                        .put("copiedBytes", it.copiedBytes).put("expectedBytes", it.expectedBytes)
                })
                .put("dataStages", JSONArray().apply {
                    checkpoints.forEach { checkpoint ->
                        put(JSONObject().put("stage", checkpoint.stage.name)
                            .put("state", checkpoint.state.name)
                            .put("processed", checkpoint.processed).put("total", checkpoint.total)
                            .put("bytesWritten", checkpoint.bytesWritten)
                            .put("checkpoint", checkpoint.checkpoint)
                            .put("attempt", checkpoint.attempt)
                            .put("lastError", checkpoint.lastError)
                            .put("updatedAtMs", checkpoint.updatedAtMs)
                            .put("startedAtMs", checkpoint.startedAtMs)
                            .put("completedAtMs", checkpoint.completedAtMs))
                    }
                })
                .put("worker", JSONObject().put("present", metrics.workerPresent)
                    .put("state", metrics.state).put("rssBytes", metrics.rssBytes)
                    .put("threadCount", metrics.threadCount)
                    .put("processCount", metrics.processCount)
                    .put("lastExitReason", metrics.lastExit?.reason ?: JSONObject.NULL)
                    .put("lastExitAgeMs", metrics.lastExit?.ageMs ?: 0L))
                .put("device", importer.deviceProfile().let { device ->
                    JSONObject().put("label", device.label).put("soc", device.soc)
                        .put("activelyCooled", device.activelyCooled).put("abi", device.abi)
                        .put("api", device.api).put("cores", device.cores)
                        .put("ramBytes", device.ramBytes)
                })
                .put("benchmark", JSONObject().apply {
                    val benchmarks = importer.benchmarks()
                    put("latest", benchmarks.firstOrNull()?.let { benchmark ->
                        JSONObject().put("deviceLabel", benchmark.deviceLabel)
                            .put("totalMs", benchmark.totalMs).put("copyMs", benchmark.copyMs)
                            .put("dataMs", benchmark.dataMs)
                            .put("stageDurations", JSONObject(benchmark.stageDurationsJson))
                            .put("mmapMaps", benchmark.mmapMaps).put("mmapThreads", benchmark.mmapThreads)
                            .put("createdAtMs", benchmark.createdAtMs)
                    })
                    put("history", JSONArray().apply {
                        benchmarks.forEach { benchmark ->
                            put(JSONObject().put("deviceLabel", benchmark.deviceLabel)
                                .put("totalMs", benchmark.totalMs)
                                .put("createdAtMs", benchmark.createdAtMs))
                        }
                    })
                })
        }
    }
}
