package com.pocketrealm.server

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.pocketrealm.bots.BotAdmissionController
import com.pocketrealm.bots.BotAdmissionState
import com.pocketrealm.bots.BotProfile
import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.bots.BotResourceSample
import com.pocketrealm.bots.BotResourceSampler
import com.pocketrealm.bots.BotRuntimeMetrics
import com.pocketrealm.log.AppLog
import com.pocketrealm.supervisor.ComponentOwnership
import org.json.JSONObject

/** Dedicated mangosd fault domain; bots require an explicit measured profile. */
class WorldRuntimeService : Service() {
    private lateinit var files: ServerRuntimeFiles
    private lateinit var ownership: ComponentOwnership
    private lateinit var resourceSampler: BotResourceSampler
    private val admissionMonitorLock = Any()
    private val admissionEpoch = AdmissionMonitorEpoch()
    private val transitionGate = AdmissionTransitionGate()
    @Volatile private var admissionThread: Thread? = null
    @Volatile private var admissionGeneration: Long? = null
    @Volatile private var activeBotProfile: BotProfile? = null
    @Volatile private var admissionState: BotAdmissionState? = null
    @Volatile private var latestMetrics: BotRuntimeMetrics? = null
    @Volatile private var admissionError = ""
    /** Desired native target awaiting acknowledgement; retained across a timeout. */
    @Volatile private var pendingAdmissionTarget: Int? = null
    override fun onCreate() {
        super.onCreate()
        files = ServerRuntimeFiles(applicationContext)
        resourceSampler = BotResourceSampler(applicationContext, applicationContext.filesDir)
        ownership = ComponentOwnership("world") {
            Thread({
                transitionGate.run {
                    stopAdmissionMonitor()
                    files.writeLifecycle("world", false, "owner-lost")
                    runCatching { WorldNative.stopNative(5_000) }
                    stopSelf()
                    Process.killProcess(Process.myPid())
                }
            }, "world-owner-loss").start()
        }
    }

    private val binder = object : IWorldControl.Stub() {
        override fun claim(sessionId: String, instanceToken: String, ownerLease: IBinder) =
            guarded { ownership.claim(sessionId, instanceToken, ownerLease) }
        override fun status() = guarded { transitionGate.run {
            ownership.decorate(
                ServerStatusJson.world(WorldNative.statusNative(), WorldNative.detailNative())
                    .put("onlinePlayers", WorldNative.onlinePlayersNative())
                    .also(::addBotStatus))
        } }
        override fun start() = guarded { transitionGate.run {
            check(stopAdmissionMonitor()) { "previous bot admission monitor did not stop" }
            files.writeLifecycle("world", false, "start")
            val rc = WorldNative.startNative(files.worldConfig().absolutePath)
            ServerStatusJson.operation("world", "start", rc)
        } }
        override fun startNormal() = guarded { transitionGate.run {
            check(stopAdmissionMonitor()) { "previous bot admission monitor did not stop" }
            files.writeLifecycle("world", false, "start-normal")
            val rc = WorldNative.startNative(files.worldConfigNormal().absolutePath)
            ServerStatusJson.operation("world", "start-normal", rc)
        } }
        override fun startBotProfile(profileId: String) = guarded { transitionGate.run {
            check(stopAdmissionMonitor()) { "previous bot admission monitor did not stop" }
            val profile = BotProfiles.require(profileId)
            files.writeLifecycle("world", false, "start-bot-profile", profile.id)
            val rc = WorldNative.startNative(files.worldConfigBot(profile).absolutePath)
            if (rc == 0) startAdmissionMonitor(profile)
            ServerStatusJson.operation("world", "start-bot-profile", rc).put("profileId", profile.id)
        } }
        override fun setBotTarget(target: Int) = guarded { transitionGate.run {
            check(activeBotProfile == null) {
                "bot admission owns the target while a measured profile is active"
            }
            val rc = WorldNative.setBotTargetNative(target)
            ServerStatusJson.operation("world", "set-bot-target", rc).put("requestedTarget", target)
        } }
        override fun botStatus() = guarded { transitionGate.run {
            JSONObject().put("schema", 1).put("ok", true).put("component", "world")
                .also(::addBotStatus)
        } }
        override fun createAccount(username: String, password: String) = guarded {
            ServerRuntimeContract.requireAccountToken("username", username)
            ServerRuntimeContract.requireAccountToken("password", password)
            val rc = WorldNative.createAccountNative(username, password, ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            accountResult("create-account", username, rc)
        }
        override fun setAccountGmLevel(username: String, level: Int) = guarded {
            ServerRuntimeContract.requireAccountToken("username", username)
            require(level in 0..3) { "GM level must be in 0..3" }
            val rc = WorldNative.setAccountGmLevelNative(
                username, level, ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            accountResult("set-account-gmlevel", username, rc)
        }
        override fun accountStatus(username: String) = guarded {
            ServerRuntimeContract.requireAccountToken("username", username)
            accountResult("account-status", username, 0)
        }
        override fun characterPersistence(username: String, characterName: String) = guarded {
            ServerRuntimeContract.requireAccountToken("username", username)
            ServerRuntimeContract.requireCharacterName(characterName)
            JSONObject(WorldNative.characterPersistenceNative(username, characterName))
                .put("schema", 1).put("ok", true).put("component", "world")
        }
        override fun realmStatus() = guarded {
            JSONObject(WorldNative.realmInfoNative())
                .put("schema", 1).put("ok", true).put("component", "world")
        }
        override fun save() = guarded { transitionGate.run {
            val rc = WorldNative.saveNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            files.writeLifecycle("world", false, "save", ServerRuntimeContract.errorName(rc.toLong()))
            ServerStatusJson.operation("world", "save", rc)
        } }
        override fun stop() = guarded { transitionGate.run {
            stopAdmissionMonitor()
            val rc = WorldNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            files.writeLifecycle("world", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
            if (rc == 0) retireCleanProcess()
            ServerStatusJson.operation("world", "stop", rc)
        } }
        override fun stopOwned(instanceToken: String) = guarded { transitionGate.run {
            ownership.requireOwner(instanceToken)
            stopAdmissionMonitor()
            val rc = WorldNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            files.writeLifecycle("world", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
            if (rc == 0) {
                ownership.clear(instanceToken)
                retireCleanProcess()
            }
            ServerStatusJson.operation("world", "stop", rc)
        } }
        override fun forceStopOwned(instanceToken: String): String {
            return transitionGate.run {
                ownership.requireOwner(instanceToken)
                stopAdmissionMonitor()
                files.writeLifecycle("world", false, "forced-stop")
                Process.killProcess(Process.myPid())
                ""
            }
        }
        override fun killForTest(): String {
            return transitionGate.run {
                stopAdmissionMonitor()
                files.writeLifecycle("world", false, "kill-for-test")
                Process.killProcess(Process.myPid())
                ""
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() {
        transitionGate.run {
            stopAdmissionMonitor()
            runCatching { WorldNative.stopNative(5_000) }
        }
        super.onDestroy()
    }
    /**
     * CMaNGOS owns process-lifetime singleton registries and queue threads.
     * Once native shutdown has acknowledged and the clean journal is durable,
     * retire this dedicated fault domain instead of reusing stale globals.
     * The delay lets the successful Binder reply reach the supervisor first.
     */
    private fun retireCleanProcess() {
        Thread({
            Thread.sleep(250)
            stopSelf()
            Process.killProcess(Process.myPid())
        }, "world-clean-retire").start()
    }
    private inline fun guarded(block: () -> JSONObject): String = try { block().toString() } catch (error: Throwable) {
        AppLog.e(TAG, "world control request failed", error); failure(error)
    }
    private fun failure(error: Throwable) = JSONObject().put("schema", 1).put("ok", false)
        .put("component", "world").put("errorClass", error.javaClass.simpleName)
        .put("error", (error.message ?: "world request failed").take(512)).toString()
    private fun accountResult(operation: String, username: String, rc: Int): JSONObject {
        val info = WorldNative.accountInfoNative(username)
        val exists = info.size >= 2 && info[0] > 0
        val accepted = rc == 0 || (rc == 10 && exists)
        return JSONObject().put("schema", 1).put("ok", accepted)
            .put("component", "world").put("operation", operation)
            .put("code", ServerRuntimeContract.errorName(rc.toLong()))
            .put("accountExists", exists).put("accountId", info.getOrElse(0) { 0 })
            .put("gmLevel", info.getOrElse(1) { -1 })
    }
    private fun addBotStatus(value: JSONObject) {
        val bot = WorldNative.botStatusNative()
        check(bot.size == BOT_STATUS_SIZE && bot[0] == ServerRuntimeContract.ABI_VERSION)
        val profile = activeBotProfile
        val cachedMetrics = latestMetrics
        val performance = if (profile == null) WorldNative.performanceStatusNative() else null
        if (performance != null) {
            check(performance.size == PERFORMANCE_STATUS_SIZE &&
                performance[0] == ServerRuntimeContract.ABI_VERSION)
        }
        value.put("compiledPlayerbots", bot[1] != 0L)
            .put("playerbotsEnabled", bot[2] != 0L)
            .put("botsAvailable", bot[3])
            .put("botsOnline", bot[4])
            .put("effectiveBotTarget", bot[5])
            .put("botAccountCount", bot[6])
            .put("botTelemetrySampleUnixSeconds", bot[7])
            .put("activeBots", bot[8])
            .put("realPlayers", bot[9])
            .put("botsSameActiveZone", bot[10])
            .put("botsWithin150", bot[11])
            .put("botsWithin500", bot[12])
            .put("botsWithin1500", bot[13])
            .put("botsLevelDelta2", bot[14])
            .put("botsLevelDelta4", bot[15])
            .put("botLoginsLast60s", bot[16])
            .put("botTeleportsLast60s", bot[17])
            .put("botRerandomizesLast60s", bot[18])
            .put("auctionHouseBot", false)
        profile?.let {
            value.put("botProfileId", profile.id).put("selectedBotTarget", profile.selectedTarget)
                .put("botGenerationState", when {
                    bot[2] == 0L -> "generating"
                    bot[3] >= profile.selectedTarget -> "complete"
                    else -> "incomplete"
                })
        }
        admissionState?.let { state ->
            value.put("botTargetAdapted", state.adapted)
                .put("botAdmissionReason", state.reason)
        }

        if (cachedMetrics != null) {
            putPerformanceStatus(value, cachedMetrics)
        } else if (performance != null) {
            value.put("tickWindowSamples", performance[1])
                .put("worldTickP50Ms", performance[2])
                .put("worldTickP95Ms", performance[3])
                .put("worldTickP99Ms", performance[4])
                .put("worldTickWindowMaxMs", performance[5])
                .put("worldHardStalls", performance[6])
                .put("worldHardStallsInWindow", performance[6])
                .put("worldHardStallTotal", performance[7])
                .put("worldLastHardStallElapsedMs", performance[8])
        } else {
            value.put("tickWindowSamples", 0)
                .put("worldTickP50Ms", 0)
                .put("worldTickP95Ms", 0)
                .put("worldTickP99Ms", 0)
                .put("worldTickWindowMaxMs", 0)
                .put("worldHardStalls", 0)
                .put("worldHardStallsInWindow", 0)
                .put("worldHardStallTotal", 0)
                .put("worldLastHardStallElapsedMs", 0)
        }

        val statusMetrics = cachedMetrics ?: if (profile == null && bot[2] != 0L && performance != null) {
            runCatching { resourceSampler.read(performance) }.getOrNull()
        } else null
        if (statusMetrics != null) {
            val metrics = statusMetrics
            value.put("worldPssMiB", metrics.worldPssMiB)
                .put("freeMemoryMiB", metrics.freeMemoryMiB)
                .put("freeStorageMiB", metrics.freeStorageMiB)
                .put("thermalLevel", metrics.thermal.name.lowercase())
                .put("resourceSampleElapsedMs", metrics.sampledAtElapsedMs)
                .put("resourceSampleAgeMs",
                    (SystemClock.elapsedRealtime() - metrics.sampledAtElapsedMs).coerceAtLeast(0))
                .put("resourceSampleState", "ready")
        } else if (profile != null) {
            value.put("worldPssMiB", 0)
                .put("freeMemoryMiB", 0)
                .put("freeStorageMiB", 0)
                .put("thermalLevel", "unknown")
                .put("resourceSampleElapsedMs", 0)
                .put("resourceSampleAgeMs", 0)
                .put("resourceSampleState", "pending")
        }
        if (admissionError.isNotEmpty()) value.put("botAdmissionError", admissionError.take(256))
        pendingAdmissionTarget?.let { value.put("botAdmissionPendingTarget", it) }
    }

    private fun putPerformanceStatus(value: JSONObject, metrics: BotRuntimeMetrics) {
        value.put("tickWindowSamples", metrics.tickSamples)
            .put("worldTickP50Ms", metrics.worldP50Ms)
            .put("worldTickP95Ms", metrics.worldP95Ms)
            .put("worldTickP99Ms", metrics.worldP99Ms)
            .put("worldTickWindowMaxMs", metrics.worldMaxMs)
            .put("worldHardStalls", metrics.hardStallCount)
            .put("worldHardStallsInWindow", metrics.hardStallCount)
            .put("worldHardStallTotal", metrics.hardStallTotal)
            .put("worldLastHardStallElapsedMs", metrics.lastHardStallElapsedMs)
    }

    private fun startAdmissionMonitor(profile: BotProfile) {
        synchronized(admissionMonitorLock) {
            check(admissionThread == null && admissionGeneration == null) {
                "bot admission monitor is already registered"
            }
        }
        val generation = admissionEpoch.begin()
        val beginRc = WorldNative.beginAdmissionBotTargetGenerationNative(generation)
        if (beginRc != 0) {
            admissionEpoch.invalidate()
            error("native bot-target generation registration failed: " +
                ServerRuntimeContract.errorName(beginRc.toLong()))
        }
        val controller = BotAdmissionController(profile)
        val startedAt = SystemClock.elapsedRealtime()
        val thread = Thread({
            runAdmissionMonitor(controller, startedAt, generation)
        }, "bot-admission")
        synchronized(admissionMonitorLock) {
            activeBotProfile = profile
            admissionState = null
            latestMetrics = null
            admissionError = ""
            pendingAdmissionTarget = null
            admissionGeneration = generation
            admissionThread = thread
        }
        try {
            thread.start()
        } catch (failure: Throwable) {
            admissionEpoch.invalidate {
                WorldNative.retireAdmissionBotTargetGenerationNative(generation)
            }
            synchronized(admissionMonitorLock) {
                if (admissionThread === thread) {
                    admissionThread = null
                    admissionGeneration = null
                    activeBotProfile = null
                }
            }
            throw failure
        }
    }

    private fun runAdmissionMonitor(
        controller: BotAdmissionController,
        startedAt: Long,
        generation: Long,
    ) {
        while (admissionEpoch.isCurrent(generation)) {
            try {
                if (!admissionEpoch.isCurrent(generation)) break
                val bot = WorldNative.botStatusNative()
                if (!admissionEpoch.isCurrent(generation)) break
                if (bot.size == BOT_STATUS_SIZE && bot[2] != 0L) {
                    // A timed-out native request stays queued only while this
                    // exact generation remains active. Retry it before policy
                    // advances so reported and native targets cannot diverge.
                    val pendingTarget = pendingAdmissionTarget
                    if (pendingTarget != null) {
                        val rc = admissionEpoch.runIfCurrent(generation) {
                            WorldNative.setAdmissionBotTargetNative(pendingTarget, generation)
                        } ?: break
                        if (!admissionEpoch.publishIfCurrent(generation) {
                            if (rc == 0) {
                                pendingAdmissionTarget = null
                                admissionError = ""
                            } else {
                                admissionError = "target $pendingTarget: " +
                                    ServerRuntimeContract.errorName(rc.toLong())
                            }
                        }) break
                    }
                    if (pendingAdmissionTarget != null) {
                        Thread.sleep(ADMISSION_INTERVAL_MS)
                        continue
                    }
                    if (!admissionEpoch.isCurrent(generation)) break
                    val performance = WorldNative.performanceStatusNative()
                    if (!admissionEpoch.isCurrent(generation)) break
                    val metrics = resourceSampler.read(performance)
                    if (!admissionEpoch.publishIfCurrent(generation) {
                        latestMetrics = metrics
                    }) break
                    val state = controller.observe(BotResourceSample(
                        elapsedMs = metrics.sampledAtElapsedMs - startedAt,
                        onlineBots = bot[4].toInt(),
                        worldP99Ms = metrics.worldP99Ms,
                        freeMemoryMiB = metrics.freeMemoryMiB,
                        freeStorageMiB = metrics.freeStorageMiB,
                        thermal = metrics.thermal,
                        hardStallCount = metrics.hardStallCount,
                        hardStallTotal = metrics.hardStallTotal,
                    ))
                    if (!admissionEpoch.publishIfCurrent(generation) {
                        admissionState = state
                    }) break
                    if (state.changed) {
                        if (!admissionEpoch.publishIfCurrent(generation) {
                            pendingAdmissionTarget = state.effectiveTarget
                        }) break
                        val rc = admissionEpoch.runIfCurrent(generation) {
                            WorldNative.setAdmissionBotTargetNative(state.effectiveTarget, generation)
                        } ?: break
                        if (!admissionEpoch.publishIfCurrent(generation) {
                            if (rc == 0) {
                                pendingAdmissionTarget = null
                                admissionError = ""
                            } else {
                                admissionError = "target ${state.effectiveTarget}: " +
                                    ServerRuntimeContract.errorName(rc.toLong())
                            }
                        }) break
                    }
                }
                Thread.sleep(ADMISSION_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            } catch (error: Throwable) {
                if (!admissionEpoch.publishIfCurrent(generation) {
                    admissionError = (error.message ?: error.javaClass.simpleName).take(256)
                    AppLog.e(TAG, "bot admission sample failed", error)
                }) break
                try { Thread.sleep(ADMISSION_INTERVAL_MS) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun stopAdmissionMonitor(): Boolean {
        val (thread, generation) = synchronized(admissionMonitorLock) {
            admissionThread to admissionGeneration
        }
        var retireRc = 0
        /* Invalidation shares the epoch gate with setters, and native retire
         * shares the target fence with the world-tick consumer. Once this
         * returns, no queued or executing retired target can reach a new run. */
        admissionEpoch.invalidate {
            if (generation != null) {
                retireRc = WorldNative.retireAdmissionBotTargetGenerationNative(generation)
            }
        }
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(ADMISSION_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        val stopped = thread?.isAlive != true && retireRc == 0
        synchronized(admissionMonitorLock) {
            if (stopped && admissionThread === thread) {
                admissionThread = null
                admissionGeneration = null
                activeBotProfile = null
                admissionState = null
                latestMetrics = null
                admissionError = ""
                pendingAdmissionTarget = null
            } else if (!stopped) {
                admissionError = if (retireRc != 0) {
                    "native admission generation retirement failed: " +
                        ServerRuntimeContract.errorName(retireRc.toLong())
                } else "admission monitor stop timed out"
            }
        }
        return stopped
    }
    companion object {
        private const val TAG = "WorldRuntimeService"
        private const val ADMISSION_INTERVAL_MS = 10_000L
        private const val ADMISSION_JOIN_TIMEOUT_MS = 6_000L
        private const val BOT_STATUS_SIZE = 19
        private const val PERFORMANCE_STATUS_SIZE = 9
    }
}
