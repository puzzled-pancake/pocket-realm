package com.pocketrealm.desktop

import com.pocketrealm.bots.BotAdmissionController
import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.bots.ThermalLevel
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * the desktop admission monitor's decision cycle with
 * synthetic samples (the pattern of the shared BotPolicyTest) — every
 * WorldNative touch rides the constructor seams, so the shared
 * BotAdmissionController's apply path, the generation fence, the persisted
 * record (the next `PocketRealm.BotTarget` conf knob) and the retarget
 * handshake are pinned without any DLL. The profile is
 * LOW_CPU_160: its launch target equals its selected target (no startup
 * ramp phase) with headroom above its minimum online, so the overload /
 * recovery policy legs are deterministic.
 */
class DesktopBotAdmissionTest {
    @get:Rule
    val folder = TemporaryFolder()

    // selectedTarget = initialTarget = 160, minimumOnline = 120,
    // reduce/increase step 25 (the shared catalog's report profile).
    private val profile = BotProfiles.LOW_CPU_160

    /** A fake bot status lane: enabled flag at 2, online count at 4. */
    private fun botStatus(online: Long, enabled: Boolean = true): LongArray =
        LongArray(19).also {
            it[2] = if (enabled) 1L else 0L
            it[4] = online
        }

    private fun metrics(
        sampledAtMs: Long,
        worldP99Ms: Int = 0,
        freeMemoryMiB: Long = 8_000L,
        freeStorageMiB: Long = 64_000L,
        hardStallCount: Int = 0,
        hardStallTotal: Long = 0L,
    ) = DesktopBotResourceMetrics(
        sampledAtElapsedMs = sampledAtMs,
        tickSamples = 2_048,
        worldP50Ms = 1,
        worldP95Ms = worldP99Ms / 2,
        worldP99Ms = worldP99Ms,
        worldMaxMs = worldP99Ms,
        hardStallCount = hardStallCount,
        hardStallTotal = hardStallTotal,
        lastHardStallElapsedMs = 0L,
        dbProbeDelayMs = 0L,
        worldState = 2L,
        statusTickCount = 1_000,
        statusLastTickMs = worldP99Ms.toLong(),
        statusMaxTickMs = worldP99Ms.toLong(),
        processCpuLoad = 0.25,
        committedMemoryMiB = 1_024,
        freeMemoryMiB = freeMemoryMiB,
        freeStorageMiB = freeStorageMiB,
        thermal = ThermalLevel.NONE,
    )

    /** A monitor with recorder seams; the sample sequence is fed by the
     * test through the returned holder. Inner so the seams can see the
     * outer helper functions and the shared profile. Tests drive
     * [DesktopBotAdmission.registerGeneration] + [DesktopBotAdmission
     * .cycleOnce] synchronously — no monitor thread, no sample race. */
    private inner class Harness(root: File) {
        val recordFile = File(root, "bot-admission.json")
        val begins = mutableListOf<Long>()
        val sets = mutableListOf<Pair<Int, Long>>()
        val retires = mutableListOf<Long>()
        var paused = false
        var bot = botStatus(profile.selectedTarget.toLong())
        var sample = metrics(0)

        val admission = DesktopBotAdmission(
            sampler = DesktopBotResourceSampler(root),
            recordFile = recordFile,
            beginGeneration = { generation -> begins.add(generation); 0 },
            setAdmissionTarget = { target, generation ->
                sets.add(target to generation); 0
            },
            retireGeneration = { generation -> retires.add(generation); 0 },
            worldPausedProbe = { paused },
            readInputs = { bot to sample },
        )
    }

    @Test
    fun memoryFloorOverloadLowersTheTargetAndAppliesItThroughTheGeneration() {
        val harness = Harness(folder.newFolder("h1"))
        // The sample clock anchors to wall time (the monitor stamps
        // startedAtMs = System.currentTimeMillis() at registration, and the
        // sample's elapsed offset is the difference).
        val clock = System.currentTimeMillis()
        harness.sample = metrics(
            sampledAtMs = clock + 5_000,
            freeMemoryMiB = profile.admission.minFreeMemoryMiB - 1,
        )
        harness.admission.registerGeneration(profile)
        val generation = harness.begins.single()
        assertTrue(harness.admission.cycleOnce(generation, BotAdmissionController(profile)))
        // The safety overload reduced by one step, floored at minimumOnline,
        // and applied under the registered generation.
        val expected = (profile.initialTarget - profile.admission.reduceStep)
            .coerceAtLeast(profile.minimumOnline)
        assertEquals(
            "one target application under the registered generation",
            listOf(expected to generation),
            harness.sets,
        )
        // The verdict carries the adaptation for the Bots screen.
        val verdict = harness.admission.verdict.value
        assertNotNull(verdict)
        assertEquals(expected, verdict!!.effectiveTarget)
        assertTrue(verdict.adapted)
        assertEquals(profile.admission.minFreeMemoryMiB - 1, verdict.freeMemoryMiB)
        // The persisted record feeds the NEXT world's `PocketRealm.BotTarget`.
        val record = harness.admission.readRecord()
        assertNotNull(record)
        assertEquals(profile.id, record!!.profileId)
        assertEquals(expected, record.effectiveTarget)
        harness.admission.stop()
    }

    @Test
    fun cooldownHoldsTheSecondOverloadedSample() {
        val harness = Harness(folder.newFolder("h2"))
        val clock = System.currentTimeMillis()
        harness.sample = metrics(
            sampledAtMs = clock + 5_000,
            freeMemoryMiB = profile.admission.minFreeMemoryMiB - 1,
        )
        harness.admission.registerGeneration(profile)
        val generation = harness.begins.single()
        val controller = BotAdmissionController(profile)
        assertTrue(harness.admission.cycleOnce(generation, controller))
        assertEquals(1, harness.sets.size)
        // Same instant: the change cooldown refuses a second reduction.
        assertTrue(harness.admission.cycleOnce(generation, controller))
        assertEquals(
            "the cooldown must hold the second overloaded sample",
            1,
            harness.sets.size,
        )
        harness.admission.stop()
    }

    @Test
    fun healthySamplesRampTheTargetBackAfterTheHealthyWindow() {
        val harness = Harness(folder.newFolder("h3"))
        // Overload once to lower the target, then go healthy: the shared
        // controller demands the performance warmup anchor and the healthy
        // window before it ramps back (its pinned policy). The sample clock
        // anchors to wall time (see h1).
        val clock = System.currentTimeMillis()
        harness.sample = metrics(
            sampledAtMs = clock + 5_000,
            freeMemoryMiB = profile.admission.minFreeMemoryMiB - 1,
        )
        harness.admission.registerGeneration(profile)
        val generation = harness.begins.single()
        val controller = BotAdmissionController(profile)
        assertTrue(harness.admission.cycleOnce(generation, controller))
        val lowered = harness.sets.single().first
        val warmup = profile.admission.performanceWarmupMs
        val cooldown = profile.admission.changeCooldownMs
        val ramp = profile.admission.healthyRampMs
        // Healthy sample one: anchors the performance warmup.
        harness.sample = metrics(sampledAtMs = clock + 6_000)
        assertTrue(harness.admission.cycleOnce(generation, controller))
        // Healthy sample two: the warmup passes; the healthy window anchors.
        harness.sample = metrics(sampledAtMs = clock + 6_000 + warmup)
        assertTrue(harness.admission.cycleOnce(generation, controller))
        // Healthy sample three: warmup + healthy window + cooldown all passed.
        harness.sample = metrics(sampledAtMs = clock + 6_000 + warmup + ramp + cooldown + 1)
        assertTrue(harness.admission.cycleOnce(generation, controller))
        val expectedRamp = (lowered + profile.admission.increaseStep)
            .coerceAtMost(profile.selectedTarget)
        assertEquals(
            listOf(lowered to generation, expectedRamp to generation),
            harness.sets,
        )
        harness.admission.stop()
    }

    @Test
    fun pausedWorldAndDisabledBotLaneWithholdSampling() {
        val harness = Harness(folder.newFolder("h4"))
        val clock = System.currentTimeMillis()
        harness.sample = metrics(sampledAtMs = clock + 1_000)
        harness.paused = true
        harness.admission.registerGeneration(profile)
        val generation = harness.begins.single()
        val controller = BotAdmissionController(profile)
        assertTrue(harness.admission.cycleOnce(generation, controller))
        harness.paused = false
        harness.bot = botStatus(online = 0, enabled = false)
        assertTrue(harness.admission.cycleOnce(generation, controller))
        // Neither tick observed a sample: no target application, no verdict.
        assertTrue(harness.sets.isEmpty())
        assertNull(harness.admission.verdict.value)
        harness.admission.stop()
    }

    @Test
    fun stopRetiresTheGenerationAndClearsTheLiveVerdictButKeepsTheRecord() {
        val harness = Harness(folder.newFolder("h5"))
        harness.sample = metrics(
            sampledAtMs = System.currentTimeMillis() + 5_000,
            freeMemoryMiB = profile.admission.minFreeMemoryMiB - 1,
        )
        harness.admission.registerGeneration(profile)
        val generation = harness.begins.single()
        harness.admission.cycleOnce(generation, BotAdmissionController(profile))
        assertNotNull(harness.admission.verdict.value)
        assertTrue(harness.admission.stop())
        assertEquals(listOf(generation), harness.retires)
        assertFalse(harness.admission.active())
        assertNull(harness.admission.verdict.value)
        // The record (the next boot's conf feed) survives the stop.
        assertNotNull(harness.admission.readRecord())
    }

    @Test
    fun retargetSwapsTheProfileThroughTheHandshake() {
        val harness = Harness(folder.newFolder("h6"))
        val next = BotProfiles.defaultProfile
        harness.admission.start(profile)
        val firstGeneration = harness.begins.single()
        val rc = harness.admission.retarget(next)
        assertEquals(0, rc)
        // The old generation retired, a fresh one registered, and the new
        // profile's launch target applied under it (the RpHost-proven
        // begin/set/retire sequence, DESK-116/123).
        assertEquals(listOf(firstGeneration), harness.retires)
        assertEquals(2, harness.begins.size)
        val secondGeneration = harness.begins.last()
        assertTrue(harness.sets.contains(next.initialTarget to secondGeneration))
        assertTrue(harness.admission.active())
        assertTrue(harness.admission.stop())
        assertEquals(listOf(firstGeneration, secondGeneration), harness.retires)
    }

    @Test
    fun startRefusesWhenThePreviousMonitorIsStillRegistered() {
        val harness = Harness(folder.newFolder("h7"))
        harness.admission.registerGeneration(profile)
        try {
            harness.admission.registerGeneration(profile)
            fail("a double registration must refuse")
        } catch (expected: IllegalStateException) {
            assertTrue(
                (expected.message ?: "").contains("already registered"),
            )
        }
        harness.admission.stop()
    }

    @Test
    fun persistedLaunchProfileLowersTheNextConfKnobWithinTheProfileContract() {
        val root = folder.newFolder("h8")
        val recordFile = File(root, "bot-admission.json")
        val loweredTarget = (profile.initialTarget - profile.admission.reduceStep)
            .coerceAtLeast(profile.minimumOnline)
        recordFile.writeText(
            JSONObject()
                .put("schema", 1)
                .put("profileId", profile.id)
                .put("effectiveTarget", loweredTarget)
                .put("selectedTarget", profile.selectedTarget)
                .put("reason", "memory-floor")
                .put("at", 1L)
                .toString(),
        )
        val admission = DesktopBotAdmission(
            sampler = DesktopBotResourceSampler(root),
            recordFile = recordFile,
            beginGeneration = { 0 },
            setAdmissionTarget = { _, _ -> 0 },
            retireGeneration = { 0 },
        )
        val launch = admission.persistedLaunchProfile(profile)
        assertEquals(profile.id, launch.id)
        assertEquals(
            "the admission-lowered target rides the next conf",
            loweredTarget,
            launch.initialTarget,
        )
        // A different profile's record never crosses over.
        val other = BotProfiles.defaultProfile
        assertEquals(other.initialTarget, admission.persistedLaunchProfile(other).initialTarget)
    }

    @Test
    fun aCorruptOrForeignRecordDegradesToTheProfile() {
        val root = folder.newFolder("h9")
        val recordFile = File(root, "bot-admission.json")
        recordFile.writeText("{not json")
        val admission = DesktopBotAdmission(
            sampler = DesktopBotResourceSampler(root),
            recordFile = recordFile,
            beginGeneration = { 0 },
            setAdmissionTarget = { _, _ -> 0 },
            retireGeneration = { 0 },
        )
        assertNull(admission.readRecord())
        assertEquals(profile, admission.persistedLaunchProfile(profile))
    }
}
