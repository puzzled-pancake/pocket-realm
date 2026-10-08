package com.pocketrealm.server

import com.pocketrealm.server.NativeRuntimeFreshness.ArtifactPin
import com.pocketrealm.server.NativeRuntimeFreshness.Check
import com.pocketrealm.server.NativeRuntimeFreshness.CommitPins
import com.pocketrealm.server.NativeRuntimeFreshness.Identity
import com.pocketrealm.server.NativeRuntimeFreshness.Labels
import com.pocketrealm.server.NativeRuntimeFreshness.StagedArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the native staleness fence semantics. The Gradle tasks
 * validateNativeRuntimeFreshness / validateNativeRuntimePins
 * (android/app/build.gradle.kts) mirror this contract against the real
 * staging bytes and lockfiles; these tests are the executable specification
 * both sides must keep honoring (fixture values below are the real x86_64
 * lane hashes: STALE_WORLD_SHA is a stale staged .so that must be rejected,
 * WORLD_SHA is the matching current build).
 */
class NativeRuntimeFreshnessTest {
    private val laneLabel = "x86_64/full staged bytes"
    private val lockfileLabel = "schemas/realm-runtime-lockfile.json"
    private val stagingLabel = "native/.build-o09-x86_64/realm-staging"

    private val cmangosPin = "ce83805d48f9c98b2af617096be5347acb8a1f17"
    private val playerbotsPin = "7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d"
    private val otherCommit = "1111111111111111111111111111111111111111"
    private val worldSha = "8b06a4dc48005215cb31083504edf5e9531e135a8c57dfcca98930ccb1da8ee7"
    private val staleWorldSha =
        "0cca901050b17c966c2c271f933210abcfc2822e88473283b4b809298a5a9cdb"
    private val realmdSha = "d556837dc1b9ea81dc30db62fef9fb598d6c0154f677bceb49fb4faf6c401385"
    private val worldBytes = 34119512L
    private val staleWorldBytes = 33865880L
    private val realmdBytes = 5409176L

    private val worldPin = ArtifactPin("libpocket_world_runtime.so", worldBytes, worldSha)
    private val realmdPin = ArtifactPin("libpocket_realmd_runtime.so", realmdBytes, realmdSha)

    private fun passingCheck(): Check = Check(
        labels = Labels(laneLabel, lockfileLabel, stagingLabel),
        identity = Identity("x86_64", "mysql", "x86_64", "mysql"),
        pins = listOf(worldPin, realmdPin),
        staged = listOf(
            StagedArtifact(worldPin.name, worldBytes, worldSha),
            StagedArtifact(realmdPin.name, realmdBytes, realmdSha),
        ),
        lockfileCommits = CommitPins(cmangosPin, playerbotsPin),
        provenanceCommits = CommitPins(cmangosPin, playerbotsPin),
        provenancePins = listOf(worldPin, realmdPin),
        sourcesCommits = CommitPins(cmangosPin, playerbotsPin),
        requireStagedBytes = true,
    )

    @Test fun freshLanePassesWithNoFailures() {
        val verdict = NativeRuntimeFreshness.evaluate(passingCheck())
        assertTrue("expected no failures, got ${verdict.failures}", verdict.ok)
        assertTrue(verdict.failures.isEmpty())
    }

    @Test fun pinsOnlyModeIgnoresAbsentStagingOutputs() {
        // The CI unit-test lane checks out no untracked staging bytes: the
        // committed-pin coherence alone must still pass.
        val verdict = NativeRuntimeFreshness.evaluate(
            passingCheck().copy(staged = emptyList(), provenanceCommits = null,
                provenancePins = null, requireStagedBytes = false),
        )
        assertTrue("expected no failures, got ${verdict.failures}", verdict.ok)
    }

    @Test fun staleStagedSha256FailsTheFreshnessCheck() {
        val verdict = NativeRuntimeFreshness.evaluate(
            passingCheck().copy(
                staged = listOf(
                    StagedArtifact(worldPin.name, worldBytes, staleWorldSha),
                    StagedArtifact(realmdPin.name, realmdBytes, realmdSha),
                ),
            ),
        )
        assertEquals(1, verdict.failures.size)
        val reason = verdict.failures.first()
        assertTrue(reason.contains("libpocket_world_runtime.so"))
        assertTrue(reason.contains("STALE"))
        assertTrue(reason.contains(staleWorldSha))
        assertTrue(reason.contains(worldSha))
        assertTrue(reason.contains(stagingLabel))
    }

    @Test fun staleStagedSizeFailsIndependently() {
        val verdict = NativeRuntimeFreshness.evaluate(
            passingCheck().copy(
                staged = listOf(
                    StagedArtifact(worldPin.name, staleWorldBytes, worldSha),
                    StagedArtifact(realmdPin.name, realmdBytes, realmdSha),
                ),
            ),
        )
        assertTrue(verdict.failures.any { reason ->
            reason.contains("size") && reason.contains(staleWorldBytes.toString()) &&
                reason.contains(worldBytes.toString())
        })
    }

    @Test fun missingOrEmptyStagedArtifactFailsWithPinnedExpectations() {
        val verdict = NativeRuntimeFreshness.evaluate(
            passingCheck().copy(
                staged = listOf(
                    StagedArtifact(worldPin.name, 0, null),
                    StagedArtifact(realmdPin.name, realmdBytes, realmdSha),
                ),
            ),
        )
        val reason = verdict.failures.single()
        assertTrue(reason.contains("libpocket_world_runtime.so"))
        assertTrue(reason.contains("missing or empty"))
        assertTrue(reason.contains(worldBytes.toString()))
        assertTrue(reason.contains(worldSha))
    }

    @Test fun absentProvenanceFailsLoudly() {
        val verdict = NativeRuntimeFreshness.evaluate(
            passingCheck().copy(provenanceCommits = null, provenancePins = null),
        )
        assertTrue(verdict.failures.any { reason ->
            reason.contains("BUILD_PROVENANCE.json") && reason.contains("missing")
        })
    }

    @Test fun provenanceCommitDriftFromLockfileFails() {
        val verdict = NativeRuntimeFreshness.evaluate(
            passingCheck().copy(
                provenanceCommits = CommitPins(otherCommit, playerbotsPin),
            ),
        )
        assertTrue(verdict.failures.any { reason ->
            reason.contains("cmangos_commit") && reason.contains(otherCommit) &&
                reason.contains(cmangosPin)
        })
    }

    @Test fun provenanceArtifactDriftFromLockfileFails() {
        val verdict = NativeRuntimeFreshness.evaluate(
            passingCheck().copy(
                provenancePins = listOf(
                    ArtifactPin(worldPin.name, staleWorldBytes, staleWorldSha),
                    realmdPin,
                ),
            ),
        )
        assertTrue(verdict.failures.any { reason ->
            reason.contains("BUILD_PROVENANCE.json under") &&
                reason.contains("records ${worldPin.name}") &&
                reason.contains(staleWorldSha) && reason.contains(worldSha)
        })
    }

    @Test fun lockfileCommitsDriftFromSourcesJsonFails() {
        val verdict = NativeRuntimeFreshness.evaluate(
            passingCheck().copy(
                lockfileCommits = CommitPins(cmangosPin, otherCommit),
            ),
        )
        assertTrue(verdict.failures.any { reason ->
            reason.contains("playerbots_commit") && reason.contains(otherCommit) &&
                reason.contains(playerbotsPin) && reason.contains("STALE source pins")
        })
    }

    @Test fun lockfileIdentityMismatchFails() {
        val verdict = NativeRuntimeFreshness.evaluate(
            passingCheck().copy(
                identity = Identity("arm64-v8a", "sqlite", "x86_64", "mysql"),
            ),
        )
        assertTrue(verdict.failures.any { it.contains("pins abi") && it.contains("arm64-v8a") })
        assertTrue(verdict.failures.any {
            it.contains("database_backend") && it.contains("sqlite")
        })
    }

    @Test fun unpinnedStagedFileMustNotRideIntoTheApk() {
        val verdict = NativeRuntimeFreshness.evaluate(
            passingCheck().copy(
                staged = listOf(
                    StagedArtifact(worldPin.name, worldBytes, worldSha),
                    StagedArtifact(realmdPin.name, realmdBytes, realmdSha),
                    StagedArtifact("libpocket_rogue.so", worldBytes, staleWorldSha),
                ),
            ),
        )
        val rogueReason = verdict.failures.single()
        assertTrue(rogueReason.contains("libpocket_rogue.so"))
        assertTrue(rogueReason.contains("no lockfile pin"))
    }

    @Test fun everyFailureModeIsReportedTogether() {
        val verdict = NativeRuntimeFreshness.evaluate(
            passingCheck().copy(
                lockfileCommits = CommitPins(otherCommit, playerbotsPin),
                provenanceCommits = null,
                provenancePins = null,
                staged = listOf(
                    StagedArtifact(worldPin.name, worldBytes, staleWorldSha),
                    StagedArtifact(realmdPin.name, realmdBytes, realmdSha),
                ),
            ),
        )
        // exactly the three intended modes: the lockfile/source commit
        // drift, the missing provenance, the stale staged world .so --
        // keeping realmd staged-and-pinned so no fourth (missing-
        // artifact) reason rides along
        assertEquals(3, verdict.failures.size)
    }

    @Test fun messageNamesLaneExpectedActualAndRemedy() {
        val verdict = NativeRuntimeFreshness.evaluate(
            passingCheck().copy(
                staged = listOf(StagedArtifact(worldPin.name, worldBytes, staleWorldSha)),
            ),
        )
        val message = verdict.message(laneLabel)
        assertTrue(message.contains(laneLabel))
        assertTrue(message.contains(staleWorldSha))
        assertTrue(message.contains(worldSha))
        assertTrue(message.contains("Remedy:"))
        assertTrue(message.contains("build_o09_realm_runtime.py"))
        assertTrue(
            "remedy must name the skip escape only through the gradle task docs",
            !message.contains("pocketSkipNativeFreshness"),
        )
    }

    // --- lockfile/provenance record parsing shared by BOTH gradle gates --
    // Real lockfile row shape (schemas/realm-runtime-lockfile*.json): pin
    // fields ride alongside sibling fields ("needed", "load_alignments"),
    // and the writer's serialization order is an implementation detail. The
    // gates must resolve rows by field name through the parsed document -
    // a textual-order-dependent gate (the retired regex record match in
    // validateRealmRuntime) errors 'no record' on a writer field reorder
    // while its JsonSlurper sibling passes the same bytes.

    private val realmdPath =
        "native/.build-o09-x86_64/realm-staging/jniLibs/x86_64/libpocket_realmd_runtime.so"
    private val worldPath =
        "native/.build-o09-x86_64/realm-staging/jniLibs/x86_64/libpocket_world_runtime.so"

    @Test fun artifactPinsResolveRowsByFieldNameRegardlessOfSerializedOrder() {
        val record: Map<*, *> = mapOf(
            "artifacts" to listOf(
                // keys deliberately NOT in writer order (path, size, sha256)
                mapOf(
                    "load_alignments" to listOf(16384, 16384),
                    "sha256" to worldSha,
                    "needed" to listOf("libc.so", "libz.so"),
                    "size" to worldBytes,
                    "path" to worldPath,
                ),
                mapOf(
                    "sha256" to realmdSha,
                    "size" to realmdBytes,
                    "path" to realmdPath,
                ),
            ),
        )
        val pins = NativeRuntimeFreshness.artifactPins(record)
        assertEquals(2, pins.size)
        val world = pins.single { it.path == worldPath }
        assertEquals(worldBytes, world.size)
        assertEquals(worldSha, world.sha256)
        val realmd = pins.single { it.path == realmdPath }
        assertEquals(realmdBytes, realmd.size)
        assertEquals(realmdSha, realmd.sha256)
    }

    @Test fun artifactPinsSkipRowsMissingPinFieldsInsteadOfHalfParsing() {
        val record: Map<*, *> = mapOf(
            "artifacts" to listOf(
                mapOf("path" to worldPath, "size" to worldBytes), // no sha256
                mapOf("path" to worldPath, "sha256" to worldSha), // no size
                "not-an-object",
                mapOf("path" to realmdPath, "size" to realmdBytes, "sha256" to realmdSha),
            ),
        )
        val pins = NativeRuntimeFreshness.artifactPins(record)
        assertEquals(listOf(realmdPath), pins.map { it.path })
        assertEquals(realmdBytes, pins.single().size)
        assertEquals(realmdSha, pins.single().sha256)
    }

    @Test fun artifactPinsTreatAbsentArtifactsArrayAsNoPins() {
        assertTrue(NativeRuntimeFreshness.artifactPins(mapOf("abi" to "x86_64")).isEmpty())
        assertTrue(NativeRuntimeFreshness.artifactPins(mapOf("artifacts" to "not-a-list")).isEmpty())
    }

    @Test fun seedTranscriptPinsResolveByFieldNameRegardlessOfSerializedOrder() {
        // classiccharacters row from the real x86_64 sqlite-lane lockfile
        // (schemas/realm-runtime-lockfile-sqlite.json), keys deliberately
        // reordered so gzip_size precedes gzip_sha256.
        val record: Map<*, *> = mapOf(
            "seed_transcripts" to mapOf(
                "classiccharacters" to mapOf(
                    "gzip_size" to 7107915L,
                    "size" to 153514454L,
                    "gzip_sha256" to "42c6e149453702d38de9007f77b2eeab309e5f62d03e2543f6203d436f597ad1",
                    "sha256" to "6792fdb1eef2e0d80f7048a457a417484b97608212c29b2715ddf4cab7a5e829",
                ),
            ),
        )
        val pin = NativeRuntimeFreshness.seedTranscriptPin(record, "classiccharacters")
        assertNotNull(pin)
        assertEquals("42c6e149453702d38de9007f77b2eeab309e5f62d03e2543f6203d436f597ad1", pin!!.gzipSha256)
        assertEquals(7107915L, pin.gzipSize)
    }

    @Test fun seedTranscriptPinIsNullWhenDatabaseOrGzipFieldsAbsent() {
        val record: Map<*, *> = mapOf(
            "seed_transcripts" to mapOf(
                "classicrealmd" to mapOf("sha256" to worldSha, "size" to worldBytes),
            ),
        )
        assertNull("unknown database has no pin", NativeRuntimeFreshness.seedTranscriptPin(record, "classiclogs"))
        assertNull("row without gzip fields has no pin", NativeRuntimeFreshness.seedTranscriptPin(record, "classicrealmd"))
        assertNull("document without seed_transcripts has no pin", NativeRuntimeFreshness.seedTranscriptPin(mapOf("abi" to "x86_64"), "classicrealmd"))
    }


    private fun Check.copy(
        identity: Identity = this.identity,
        pins: List<ArtifactPin> = this.pins,
        staged: List<StagedArtifact> = this.staged,
        lockfileCommits: CommitPins = this.lockfileCommits,
        provenanceCommits: CommitPins? = this.provenanceCommits,
        provenancePins: List<ArtifactPin>? = this.provenancePins,
        sourcesCommits: CommitPins = this.sourcesCommits,
        requireStagedBytes: Boolean = this.requireStagedBytes,
    ): Check = Check(
        labels = labels,
        identity = identity,
        pins = pins,
        staged = staged,
        lockfileCommits = lockfileCommits,
        provenanceCommits = provenanceCommits,
        provenancePins = provenancePins,
        sourcesCommits = sourcesCommits,
        requireStagedBytes = requireStagedBytes,
    )
}
