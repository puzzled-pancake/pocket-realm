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
import java.util.concurrent.atomic.AtomicBoolean

/** Dedicated mangosd fault domain; bots require an explicit measured profile. */
class WorldRuntimeService : Service() {
    private lateinit var files: ServerRuntimeFiles
    private lateinit var ownership: ComponentOwnership
    private lateinit var resourceSampler: BotResourceSampler
    private val admissionRunning = AtomicBoolean(false)
    @Volatile private var admissionThread: Thread? = null
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
                stopAdmissionMonitor()
                files.writeLifecycle("world", false, "owner-lost")
                runCatching { WorldNative.stopNative(5_000) }
                stopSelf()
                Process.killProcess(Process.myPid())
            }, "world-owner-loss").start()
        }
    }

    private val binder = object : IWorldControl.Stub() {
        override fun claim(sessionId: String, instanceToken: String, ownerLease: IBinder) =
            guarded { ownership.claim(sessionId, instanceToken, ownerLease) }
        override fun status() = guarded { ownership.decorate(
            ServerStatusJson.world(WorldNative.statusNative(), WorldNative.detailNative())
                .put("onlinePlayers", WorldNative.onlinePlayersNative())
                .also(::addBotStatus)) }
        override fun start() = guarded {
            stopAdmissionMonitor()
            files.writeLifecycle("world", false, "start")
            val rc = WorldNative.startNative(files.worldConfig().absolutePath)
            ServerStatusJson.operation("world", "start", rc)
        }
        override fun startNormal() = guarded {
            stopAdmissionMonitor()
            files.writeLifecycle("world", false, "start-normal")
            val rc = WorldNative.startNative(files.worldConfigNormal().absolutePath)
            ServerStatusJson.operation("world", "start-normal", rc)
        }
        override fun startBotProfile(profileId: String) = guarded {
            val profile = BotProfiles.require(profileId)
            files.writeLifecycle("world", false, "start-bot-profile", profile.id)
            val rc = WorldNative.startNative(files.worldConfigBot(profile).absolutePath)
            if (rc == 0) startAdmissionMonitor(profile)
            ServerStatusJson.operation("world", "start-bot-profile", rc).put("profileId", profile.id)
        }
        override fun setBotTarget(target: Int) = guarded {
            val rc = WorldNative.setBotTargetNative(target)
            ServerStatusJson.operation("world", "set-bot-target", rc).put("requestedTarget", target)
        }
        override fun botStatus() = guarded {
            JSONObject().put("schema", 1).put("ok", true).put("component", "world")
                .also(::addBotStatus)
        }
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
        override fun save() = guarded {
            val rc = WorldNative.saveNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            files.writeLifecycle("world", false, "save", ServerRuntimeContract.errorName(rc.toLong()))
            ServerStatusJson.operation("world", "save", rc)
        }
        override fun stop() = guarded {
            stopAdmissionMonitor()
            val rc = WorldNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            files.writeLifecycle("world", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
            if (rc == 0) retireCleanProcess()
            ServerStatusJson.operation("world", "stop", rc)
        }
        override fun stopOwned(instanceToken: String) = guarded {
            ownership.requireOwner(instanceToken)
            stopAdmissionMonitor()
            val rc = WorldNative.stopNative(ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            files.writeLifecycle("world", rc == 0, "stop", ServerRuntimeContract.errorName(rc.toLong()))
            if (rc == 0) {
                ownership.clear(instanceToken)
                retireCleanProcess()
            }
            ServerStatusJson.operation("world", "stop", rc)
        }
        override fun forceStopOwned(instanceToken: String): String {
            ownership.requireOwner(instanceToken)
            stopAdmissionMonitor()
            files.writeLifecycle("world", false, "forced-stop")
            Process.killProcess(Process.myPid())
            return ""
        }
        override fun killForTest(): String {
            files.writeLifecycle("world", false, "kill-for-test")
            Process.killProcess(Process.myPid())
            return ""
        }
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() {
        stopAdmissionMonitor()
        runCatching { WorldNative.stopNative(5_000) }
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
        check(bot.size == 7 && bot[0] == ServerRuntimeContract.ABI_VERSION)
        val performance = WorldNative.performanceStatusNative()
        check(performance.size == 7 && performance[0] == ServerRuntimeContract.ABI_VERSION)
        value.put("compiledPlayerbots", bot[1] != 0L)
            .put("playerbotsEnabled", bot[2] != 0L)
            .put("botsAvailable", bot[3])
            .put("botsOnline", bot[4])
            .put("effectiveBotTarget", bot[5])
            .put("botAccountCount", bot[6])
            .put("tickWindowSamples", performance[1])
            .put("worldTickP50Ms", performance[2])
            .put("worldTickP95Ms", performance[3])
            .put("worldTickP99Ms", performance[4])
            .put("worldTickWindowMaxMs", performance[5])
            .put("worldHardStalls", performance[6])
            .put("auctionHouseBot", false)
        activeBotProfile?.let { profile ->
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
        val statusMetrics = if (bot[2] != 0L) runCatching { resourceSampler.read(performance) }.getOrNull()
            else latestMetrics
        statusMetrics?.let { metrics ->
            value.put("worldPssMiB", metrics.worldPssMiB)
                .put("freeMemoryMiB", metrics.freeMemoryMiB)
                .put("freeStorageMiB", metrics.freeStorageMiB)
                .put("thermalLevel", metrics.thermal.name.lowercase())
                .put("resourceSampleElapsedMs", metrics.sampledAtElapsedMs)
        }
        if (admissionError.isNotEmpty()) value.put("botAdmissionError", admissionError.take(256))
        pendingAdmissionTarget?.let { value.put("botAdmissionPendingTarget", it) }
    }

    private fun startAdmissionMonitor(profile: BotProfile) {
        stopAdmissionMonitor()
        activeBotProfile = profile
        admissionState = null
        latestMetrics = null
        admissionError = ""
        pendingAdmissionTarget = null
        val controller = BotAdmissionController(profile)
        val startedAt = SystemClock.elapsedRealtime()
        admissionRunning.set(true)
        admissionThread = Thread({
            while (admissionRunning.get()) {
                try {
                    val bot = WorldNative.botStatusNative()
                    if (bot.size == 7 && bot[2] != 0L) {
                        // A timed-out setBotTarget call may still be in flight
                        // on the world tick thread. Retry the same desired
                        // target before allowing policy to advance, so the
                        // reported adaptation cannot silently diverge from
                        // native state.
                        pendingAdmissionTarget?.let { target ->
                            val rc = WorldNative.setBotTargetNative(target)
                            if (rc == 0) {
                                pendingAdmissionTarget = null
                                admissionError = ""
                            } else {
                                admissionError = "target $target: ${ServerRuntimeContract.errorName(rc.toLong())}"
                            }
                        }
                        if (pendingAdmissionTarget != null) {
                            Thread.sleep(ADMISSION_INTERVAL_MS)
                            continue
                        }
                        val metrics = resourceSampler.read(WorldNative.performanceStatusNative())
                        latestMetrics = metrics
                        val state = controller.observe(BotResourceSample(
                            elapsedMs = metrics.sampledAtElapsedMs - startedAt,
                            onlineBots = bot[4].toInt(),
                            worldP99Ms = metrics.worldP99Ms,
                            freeMemoryMiB = metrics.freeMemoryMiB,
                            freeStorageMiB = metrics.freeStorageMiB,
                            thermal = metrics.thermal,
                            hardStallCount = metrics.hardStallCount,
                        ))
                        admissionState = state
                        if (state.changed) {
                            pendingAdmissionTarget = state.effectiveTarget
                            val rc = WorldNative.setBotTargetNative(state.effectiveTarget)
                            if (rc == 0) {
                                pendingAdmissionTarget = null
                                admissionError = ""
                            } else {
                                admissionError = "target ${state.effectiveTarget}: " +
                                    ServerRuntimeContract.errorName(rc.toLong())
                            }
                        }
                    }
                    Thread.sleep(ADMISSION_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                } catch (error: Throwable) {
                    admissionError = (error.message ?: error.javaClass.simpleName).take(256)
                    AppLog.e(TAG, "bot admission sample failed", error)
                    try { Thread.sleep(ADMISSION_INTERVAL_MS) } catch (_: InterruptedException) { break }
                }
            }
        }, "bot-admission").also { it.start() }
    }

    private fun stopAdmissionMonitor() {
        admissionRunning.set(false)
        admissionThread?.interrupt()
        admissionThread = null
        activeBotProfile = null
        admissionState = null
        latestMetrics = null
        admissionError = ""
        pendingAdmissionTarget = null
    }
    companion object {
        private const val TAG = "WorldRuntimeService"
        private const val ADMISSION_INTERVAL_MS = 10_000L
    }
}
