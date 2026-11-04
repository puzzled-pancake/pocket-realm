package com.pocketrealm.desktop

import com.pocketrealm.bots.BotAdmissionController
import com.pocketrealm.bots.BotAdmissionState
import com.pocketrealm.bots.BotProfile
import com.pocketrealm.bots.BotResourceSample
import com.pocketrealm.server.AdmissionMonitorEpoch
import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.server.WorldNative
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * One admission verdict for the Bots screen: the shared controller's
 * decision (selected vs effective target, adapted, reason) plus the sample
 * it was derived from and any monitor error. Null while no monitor runs.
 */
data class DesktopBotAdmissionVerdict(
    val profileId: String,
    val selectedTarget: Int,
    val effectiveTarget: Int,
    val adapted: Boolean,
    val reason: String,
    val onlineBots: Int,
    val worldP99Ms: Int,
    val freeMemoryMiB: Long,
    val freeStorageMiB: Long,
    val processCpuLoad: Double,
    val committedMemoryMiB: Long,
    val error: String,
    val sampledAtWallMs: Long,
)

/**
 * The persisted admission record (runtime/server/bot-admission.json): the
 * last applied effective target for a profile id. DESK-99's conf feed —
 * the world-side knob (`PocketRealm.BotTarget`, ServerRuntimeFiles.kt:153)
 * for the NEXT world start reads this, so an admission-lowered target
 * survives the one-world-per-process lifetime boundary instead of every
 * reboot re-ramping into the same overload the controller just shed.
 */
data class DesktopBotAdmissionRecord(
    val profileId: String,
    val effectiveTarget: Int,
    val selectedTarget: Int,
    val reason: String,
    val atWallMs: Long,
)

/**
 * the desktop bot admission monitor — the Android world
 * service's startAdmissionMonitor/runAdmissionMonitor/stopAdmissionMonitor
 * loop (WorldRuntimeService.kt:498-673) running inside the desktop world
 * supervision. The shared [BotAdmissionController] observes
 * [BotResourceSample]s on the Android cadence; each applied decision goes
 * through the admission-generation handshake the shared WorldNative shim
 * exposes (begin → setAdmissionBotTarget under the generation → retire),
 * fenced by the shared [AdmissionMonitorEpoch] exactly like the Android
 * service (a retired monitor can never publish into a new run, and a
 * queued native target dies with its generation).
 *
 * Every WorldNative touch rides a constructor seam: production wires the
 * shared shim, tests inject recorders and run the decision cycle with
 * synthetic samples (the DESK-8 "sampler + decision cycle" legs) without
 * any DLL.
 */
class DesktopBotAdmission(
    private val sampler: DesktopBotResourceSampler,
    private val recordFile: File,
    /** The admission-generation handshake seams (production: the shared
     * WorldNative externals; tests: recorders). */
    private val beginGeneration: (Long) -> Int =
        { generation -> WorldNative.beginAdmissionBotTargetGenerationNative(generation) },
    private val setAdmissionTarget: (Int, Long) -> Int =
        { target, generation -> WorldNative.setAdmissionBotTargetNative(target, generation) },
    private val retireGeneration: (Long) -> Int =
        { generation -> WorldNative.retireAdmissionBotTargetGenerationNative(generation) },
    /** Companion-pause guard (Android's runAdmissionMonitor contract): a
     * paused world reports frozen tick metrics; withholding adaptation
     * avoids misreading a deliberate pause as a hard stall. */
    private val worldPausedProbe: () -> Boolean = { WorldNative.isWorldPausedNative() == 1 },
    /** The bot status lane (enabled flag at 2, online count at 4) and the
     * resource sample, pulled together so a test can fake both. */
    private val readInputs: () -> Pair<LongArray, DesktopBotResourceMetrics> = {
        WorldNative.botStatusNative() to sampler.readFromWorld()
    },
) {
    private val epoch = AdmissionMonitorEpoch()
    private val lock = Any()
    private var thread: Thread? = null
    private var generation: Long? = null
    private var startedAtMs: Long = 0
    private var activeProfile: BotProfile? = null
    private var controller: BotAdmissionController? = null
    private var pendingAdmissionTarget: Int? = null
    private var admissionError: String = ""

    private val _verdict = MutableStateFlow<DesktopBotAdmissionVerdict?>(null)

    /** The latest admission verdict, or null while no monitor runs. */
    val verdict: StateFlow<DesktopBotAdmissionVerdict?> = _verdict.asStateFlow()

    /** True while the monitor thread is registered (a measured profile owns the target). */
    fun active(): Boolean = synchronized(lock) { thread != null }

    /**
     * Android's startAdmissionMonitor's registration leg: register a fresh
     * admission generation with the native world and wire the observe
     * state — no thread. [start] adds the loop thread; tests call this
     * directly and drive [cycleOnce] synchronously (no sample race).
     * Throws when the native generation registration fails (the caller
     * records the honest lifecycle line; the world keeps its static conf
     * target) or when a monitor is already registered.
     */
    fun registerGeneration(profile: BotProfile) {
        synchronized(lock) {
            check(thread == null && generation == null) {
                "bot admission monitor is already registered"
            }
        }
        val newGeneration = epoch.begin()
        val beginRc = beginGeneration(newGeneration)
        if (beginRc != 0) {
            epoch.invalidate()
            error(
                "native bot-target generation registration failed: " +
                    ServerRuntimeContract.errorName(beginRc.toLong()),
            )
        }
        synchronized(lock) {
            activeProfile = profile
            controller = BotAdmissionController(profile)
            startedAtMs = System.currentTimeMillis()
            pendingAdmissionTarget = null
            admissionError = ""
            generation = newGeneration
        }
    }

    /**
     * Android's startAdmissionMonitor: register the generation, then start
     * the observe-loop thread on the Android cadence.
     */
    fun start(profile: BotProfile) {
        registerGeneration(profile)
        val loopGeneration = synchronized(lock) { generation }
            ?: error("bot admission generation missing after registration")
        val loopController = synchronized(lock) { controller }
            ?: error("bot admission controller missing after registration")
        val worker = Thread({ runLoop(loopGeneration, loopController) }, "bot-admission-desktop")
        worker.isDaemon = true
        synchronized(lock) { thread = worker }
        try {
            worker.start()
        } catch (failure: Throwable) {
            epoch.invalidate { retireGeneration(loopGeneration) }
            synchronized(lock) {
                if (thread === worker) {
                    thread = null
                    generation = null
                    activeProfile = null
                    controller = null
                }
            }
            throw failure
        }
    }

    /**
     * apply [profile] to a RUNNING world — the RpHost-proven
     * handshake in the Android startBotProfileAt shape: the previous
     * monitor retires (its generation dies, queued targets invalidated),
     * a fresh generation registers, the new profile's initial target is
     * set under it (the live analog of the new boot's `PocketRealm.BotTarget`
     * conf line), and the observe loop restarts for the new profile.
     * Returns the target-set rc (0 = applied); a non-zero rc stays pending
     * and the loop retries it before policy advances (the Android
     * runAdmissionMonitor pending contract).
     */
    fun retarget(profile: BotProfile): Int {
        check(stop()) { "previous bot admission monitor did not stop" }
        start(profile)
        val current = synchronized(lock) { generation }
        if (current == null) {
            error("bot admission monitor generation missing after start")
        }
        val rc = setAdmissionTarget(profile.initialTarget, current)
        synchronized(lock) {
            if (rc == 0) {
                pendingAdmissionTarget = null
                admissionError = ""
            } else {
                pendingAdmissionTarget = profile.initialTarget
                admissionError = "target ${profile.initialTarget}: " +
                    ServerRuntimeContract.errorName(rc.toLong())
            }
        }
        return rc
    }

    /**
     * Android's stopAdmissionMonitor: invalidate the epoch gate (no
     * current-generation action can enter), retire the native generation
     * under the same gate, then join the worker. True only when the
     * retirement succeeded AND the thread is provably gone; a successful
     * stop clears the verdict (no monitor — no live verdict) but never the
     * persisted record file (that is the next boot's conf feed).
     */
    fun stop(): Boolean {
        val (worker, current) = synchronized(lock) { thread to generation }
        var retireRc = 0
        epoch.invalidate {
            if (current != null) retireRc = retireGeneration(current)
        }
        worker?.interrupt()
        if (worker != null && worker !== Thread.currentThread()) {
            try {
                worker.join(ADMISSION_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        val stopped = worker?.isAlive != true && retireRc == 0
        synchronized(lock) {
            if (stopped && thread === worker) {
                thread = null
                generation = null
                activeProfile = null
                controller = null
                pendingAdmissionTarget = null
                admissionError = ""
                _verdict.value = null
            } else if (!stopped) {
                admissionError = if (retireRc != 0) {
                    "native admission generation retirement failed: " +
                        ServerRuntimeContract.errorName(retireRc.toLong())
                } else {
                    "admission monitor stop timed out"
                }
            }
        }
        return stopped
    }

    // ------------------------------------------------------------------
    // The observe loop (Android's runAdmissionMonitor, desktop-shaped).
    // ------------------------------------------------------------------

    private fun runLoop(loopGeneration: Long, controller: BotAdmissionController) {
        while (true) {
            if (!cycleOnce(loopGeneration, controller)) break
            try {
                Thread.sleep(ADMISSION_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /**
     * ONE admission iteration, sleep-free so tests drive it synchronously.
     * Returns false only when the loop must end (generation retired, or
     * the thread was interrupted); any sample failure degrades to the
     * verdict's error field and keeps the loop alive (the Android posture).
     */
    internal fun cycleOnce(loopGeneration: Long, controller: BotAdmissionController): Boolean =
        try {
            cycleOnceChecked(loopGeneration, controller)
        } catch (_: InterruptedException) {
            false
        } catch (error: Throwable) {
            epoch.publishIfCurrent(loopGeneration) {
                admissionError = (error.message ?: error.javaClass.simpleName).take(256)
            }
            true // keep looping; the next cadence tick retries
        }

    private fun cycleOnceChecked(loopGeneration: Long, controller: BotAdmissionController): Boolean {
        if (!epoch.isCurrent(loopGeneration)) return false
        // A paused world (companion mode) reports frozen tick metrics;
        // withholding adaptation avoids the monitor misreading a
        // deliberate pause as a hard stall.
        if (worldPausedProbe()) return true
        val (bot, metrics) = readInputs()
        if (!epoch.isCurrent(loopGeneration)) return false
        if (bot.size != BOT_STATUS_SIZE || bot[BOT_STATUS_ENABLED] == 0L) return true
        // A timed-out native request stays queued only while this exact
        // generation remains active. Retry it before policy advances so
        // reported and native targets cannot diverge.
        val pending = synchronized(lock) { pendingAdmissionTarget }
        if (pending != null) {
            val rc = epoch.runIfCurrent(loopGeneration) {
                setAdmissionTarget(pending, loopGeneration)
            } ?: return false
            if (!epoch.publishIfCurrent(loopGeneration) {
                    if (rc == 0) {
                        pendingAdmissionTarget = null
                        admissionError = ""
                    } else {
                        admissionError = "target $pending: " +
                            ServerRuntimeContract.errorName(rc.toLong())
                    }
                }
            ) {
                return false
            }
            if (synchronized(lock) { pendingAdmissionTarget } != null) return true
            if (!epoch.isCurrent(loopGeneration)) return false
        }
        val profile = synchronized(lock) { activeProfile } ?: return false
        val state = controller.observe(
            BotResourceSample(
                elapsedMs = (metrics.sampledAtElapsedMs - startedAtMs).coerceAtLeast(0),
                onlineBots = bot[BOT_STATUS_ONLINE].toInt(),
                worldP99Ms = metrics.worldP99Ms,
                freeMemoryMiB = metrics.freeMemoryMiB,
                freeStorageMiB = metrics.freeStorageMiB,
                thermal = metrics.thermal,
                hardStallCount = metrics.hardStallCount,
                hardStallTotal = metrics.hardStallTotal,
                // the human session count rides the sample as a plain
                // observation field (bot status index 9, the same lane the
                // backend's WorldPresenceSample reads) — observation only,
                // the shared controller's policy does not consume it yet.
                realPlayers = bot[BOT_STATUS_REAL_PLAYERS].toInt().coerceAtLeast(0),
            ),
        )
        if (!epoch.publishIfCurrent(loopGeneration) {
                _verdict.value = DesktopBotAdmissionVerdict(
                    profileId = profile.id,
                    selectedTarget = state.selectedTarget,
                    effectiveTarget = state.effectiveTarget,
                    adapted = state.adapted,
                    reason = state.reason,
                    onlineBots = bot[BOT_STATUS_ONLINE].toInt(),
                    worldP99Ms = metrics.worldP99Ms,
                    freeMemoryMiB = metrics.freeMemoryMiB,
                    freeStorageMiB = metrics.freeStorageMiB,
                    processCpuLoad = metrics.processCpuLoad,
                    committedMemoryMiB = metrics.committedMemoryMiB,
                    error = synchronized(lock) { admissionError },
                    sampledAtWallMs = metrics.sampledAtElapsedMs,
                )
            }
        ) {
            return false
        }
        if (!state.changed) return true
        val rc = epoch.runIfCurrent(loopGeneration) {
            setAdmissionTarget(state.effectiveTarget, loopGeneration)
        } ?: return false
        if (!epoch.publishIfCurrent(loopGeneration) {
                if (rc == 0) {
                    pendingAdmissionTarget = null
                    admissionError = ""
                } else {
                    pendingAdmissionTarget = state.effectiveTarget
                    admissionError = "target ${state.effectiveTarget}: " +
                        ServerRuntimeContract.errorName(rc.toLong())
                }
            }
        ) {
            return false
        }
        if (rc == 0) writeRecord(profile, state)
        return true
    }

    // ------------------------------------------------------------------
    // The persisted record (the next conf's admission-lowered knob).
    // ------------------------------------------------------------------

    private fun writeRecord(profile: BotProfile, state: BotAdmissionState) {
        runCatching {
            recordFile.parentFile?.mkdirs()
            recordFile.writeText(
                JSONObject()
                    .put("schema", 1)
                    .put("profileId", profile.id)
                    .put("effectiveTarget", state.effectiveTarget)
                    .put("selectedTarget", state.selectedTarget)
                    .put("reason", state.reason)
                    .put("at", System.currentTimeMillis())
                    .toString(),
            )
        }
    }

    /** The last persisted admission record, or null (absent/corrupt file —
     * the conf feed degrades to the profile's own initial target). */
    fun readRecord(): DesktopBotAdmissionRecord? {
        if (!recordFile.isFile) return null
        return runCatching {
            val value = JSONObject(recordFile.readText())
            if (value.optInt("schema", 0) != 1) return null
            val target = value.optInt("effectiveTarget", 0)
            val profileId = value.optString("profileId", "")
            if (target <= 0 || profileId.isEmpty()) return null
            DesktopBotAdmissionRecord(
                profileId = profileId,
                effectiveTarget = target,
                selectedTarget = value.optInt("selectedTarget", target),
                reason = value.optString("reason", ""),
                atWallMs = value.optLong("at", 0L),
            )
        }.getOrNull()
    }

    /**
     * DESK-99's conf feed: the launch-time profile with the persisted
     * admission-lowered initial target applied — only when the record
     * belongs to the same profile and its effective target is lower. The
     * clamp respects the shared BotProfile contract
     * (initialTarget in minimumOnline..selectedTarget).
     */
    fun persistedLaunchProfile(profile: BotProfile): BotProfile {
        val record = readRecord() ?: return profile
        if (record.profileId != profile.id) return profile
        if (record.effectiveTarget >= profile.initialTarget) return profile
        val lowered = record.effectiveTarget
            .coerceIn(profile.minimumOnline, profile.selectedTarget)
        if (lowered >= profile.initialTarget) return profile
        return profile.copy(initialTarget = lowered)
    }

    companion object {
        private const val ADMISSION_INTERVAL_MS = 10_000L
        private const val ADMISSION_JOIN_TIMEOUT_MS = 6_000L

        /** WorldNative.botStatusNative indices (the Android world service's
         * runAdmissionMonitor contract: enabled at 2, online bots at 4). */
        private const val BOT_STATUS_SIZE = 19
        private const val BOT_STATUS_ENABLED = 2
        private const val BOT_STATUS_ONLINE = 4

        /** The real-player count index (the backend's WorldPresenceSample
         * lane; D7 feeds it into the sample as an observation field). */
        private const val BOT_STATUS_REAL_PLAYERS = 9
    }
}
