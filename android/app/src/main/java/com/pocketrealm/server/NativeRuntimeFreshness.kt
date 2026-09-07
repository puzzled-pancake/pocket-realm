package com.pocketrealm.server

/**
 * Pure expected-vs-actual evaluation behind the native-runtime staleness
 * fence. The Gradle task `validateNativeRuntimeFreshness` /
 * `validateNativeRuntimePins` (android/app/build.gradle.kts) mirrors this
 * contract at build time against the real staging bytes; this object pins
 * the failure vocabulary and semantics under unit test so the Gradle side
 * stays an honest mirror (any behavioral change here must be mirrored in the
 * build script task in the same change).
 *
 * The fence answers one question: do the staged realm-runtime .so bytes and
 * their recorded provenance match the reviewed lane pins, and do those pins
 * still agree with schemas/sources.json? A QA session once shipped an APK
 * whose packaged libpocket_world_runtime.so was two days stale (missing
 * world-chat/reset-state/llm-memory-state JNI ops) with an exit-0 BUILD
 * SUCCESSFUL - every check below exists to make that mismatch loud.
 */
internal object NativeRuntimeFreshness {

    /** Remedy printed with every fence failure, on the build and in tests. */
    const val REMEDY =
        "rerun the full lane build: python tools/build_o09_realm_runtime.py " +
            "(matching -PpocketAbi/-PpocketLane)"

    /** Lockfile/provenance field carrying the CMaNGOS source pin. */
    const val CMANGOS_COMMIT_FIELD = "cmangos_commit"

    /** Lockfile/provenance field carrying the playerbots source pin. */
    const val PLAYERBOTS_COMMIT_FIELD = "playerbots_commit"

    /** One reviewed artifact row: staged file name, pinned size and SHA-256. */
    data class ArtifactPin(val name: String, val size: Long, val sha256: String)

    /**
     * One staged file observation. [size] of 0 or a null [sha256] means the
     * staged artifact is missing or empty (never packaged silently).
     */
    data class StagedArtifact(val name: String, val size: Long, val sha256: String?) {
        val present: Boolean get() = size > 0 && sha256 != null
    }

    /** Source-commit pins from one JSON document; null = field absent. */
    data class CommitPins(val cmangos: String?, val playerbots: String?)

    /** Where the checked documents live, for actionable failure messages. */
    data class Labels(
        val lane: String,
        val lockfile: String,
        val staging: String,
    )

    /** Lockfile identity vs the (abi, provider) the build selected. */
    data class Identity(
        val expectedAbi: String,
        val expectedBackend: String,
        val lockfileAbi: String?,
        val lockfileBackend: String?,
    )

    /**
     * Everything the fence compares. [provenanceCommits]/[provenancePins] are
     * null when BUILD_PROVENANCE.json is missing; [staged] is only consulted
     * when [requireStagedBytes] is true (the pins-only mode runs where the
     * untracked staging outputs cannot exist, e.g. the CI unit-test lane).
     */
    @Suppress("LongParameterList")
    class Check(
        val labels: Labels,
        val identity: Identity,
        val pins: List<ArtifactPin>,
        val staged: List<StagedArtifact>,
        val lockfileCommits: CommitPins,
        val provenanceCommits: CommitPins?,
        val provenancePins: List<ArtifactPin>?,
        val sourcesCommits: CommitPins,
        val requireStagedBytes: Boolean,
    )

    /** Verdict: empty [failures] means the lane is fresh. */
    data class Verdict(val failures: List<String>) {
        val ok: Boolean get() = failures.isEmpty()

        /** Aggregated, actionable message ending with the remedy line. */
        fun message(laneLabel: String): String = buildString {
            append("Native runtime freshness fence FAILED for ")
            append(laneLabel)
            append(":\n")
            failures.forEach { reason ->
                append("  - ").append(reason).append('\n')
            }
            append("  Remedy: ").append(REMEDY)
        }
    }

    /**
     * Evaluates every fence mode and returns all failure reasons (never
     * stops at the first, so one rebuild fixes everything the fence found).
     */
    fun evaluate(check: Check): Verdict {
        val reasons = ArrayList<String>()
        with(check) {
            if (identity.lockfileAbi != identity.expectedAbi) {
                reasons += "${labels.lockfile} pins abi '${identity.lockfileAbi}' " +
                    "but the build selected '${identity.expectedAbi}'"
            }
            if (identity.lockfileBackend != identity.expectedBackend) {
                reasons += "${labels.lockfile} pins database_backend " +
                    "'${identity.lockfileBackend}' but the build selected " +
                    "'${identity.expectedBackend}'"
            }
            reasons += commitMismatches(
                lockfileCommits, sourcesCommits, labels.lockfile, "schemas/sources.json",
            )
            if (requireStagedBytes) {
                if (provenanceCommits == null || provenancePins == null) {
                    reasons += "BUILD_PROVENANCE.json is missing under ${labels.staging}; " +
                        "the staged runtime predates provenance recording or the full " +
                        "lane build never ran"
                } else {
                    reasons += commitMismatches(
                        provenanceCommits, lockfileCommits, labels.staging, labels.lockfile,
                    )
                    reasons += provenancePinMismatches(pins, provenancePins, labels.staging)
                }
                reasons += stagedByteMismatches(pins, staged, labels.staging)
                reasons += unpinnedStagedFiles(pins, staged, labels.staging)
            }
        }
        return Verdict(reasons)
    }

    private fun commitMismatches(
        actual: CommitPins,
        expected: CommitPins,
        actualLabel: String,
        expectedLabel: String,
    ): List<String> {
        val reasons = ArrayList<String>(2)
        if (actual.cmangos != expected.cmangos) {
            reasons += "$actualLabel $CMANGOS_COMMIT_FIELD '${actual.cmangos}' != " +
                "$expectedLabel pin '${expected.cmangos}'; the lane build ran against " +
                "STALE source pins"
        }
        if (actual.playerbots != expected.playerbots) {
            reasons += "$actualLabel $PLAYERBOTS_COMMIT_FIELD '${actual.playerbots}' != " +
                "$expectedLabel pin '${expected.playerbots}'; the lane build ran against " +
                "STALE source pins"
        }
        return reasons
    }

    private fun provenancePinMismatches(
        pins: List<ArtifactPin>,
        provenancePins: List<ArtifactPin>,
        stagingLabel: String,
    ): List<String> {
        val byName = pins.associateBy { it.name }
        val reasons = ArrayList<String>()
        for (provenance in provenancePins) {
            val lock = byName[provenance.name] ?: continue
            if (provenance.size != lock.size || !provenance.sha256.equals(lock.sha256, true)) {
                reasons += "BUILD_PROVENANCE.json under $stagingLabel records " +
                    "${provenance.name} as ${provenance.size} bytes / sha256 " +
                    "${provenance.sha256}, but the lockfile pins ${lock.size} bytes / " +
                    "sha256 ${lock.sha256}"
            }
        }
        return reasons
    }

    private fun stagedByteMismatches(
        pins: List<ArtifactPin>,
        staged: List<StagedArtifact>,
        stagingLabel: String,
    ): List<String> {
        val stagedByName = staged.associateBy { it.name }
        val reasons = ArrayList<String>()
        for (pin in pins) {
            val observed = stagedByName[pin.name]
            if (observed == null || !observed.present) {
                reasons += "staged ${pin.name} is missing or empty under $stagingLabel " +
                    "(lockfile pins ${pin.size} bytes, sha256 ${pin.sha256})"
                continue
            }
            if (observed.size != pin.size) {
                reasons += "staged ${pin.name} size ${observed.size} != lockfile pin " +
                    "${pin.size} ($stagingLabel): the staged .so is STALE relative to " +
                    "the reviewed lane pins"
            }
            if (!pin.sha256.equals(observed.sha256, true)) {
                reasons += "staged ${pin.name} sha256 ${observed.sha256} != lockfile pin " +
                    "${pin.sha256} ($stagingLabel): the staged .so is STALE relative to " +
                    "the reviewed lane pins"
            }
        }
        return reasons
    }

    private fun unpinnedStagedFiles(
        pins: List<ArtifactPin>,
        staged: List<StagedArtifact>,
        stagingLabel: String,
    ): List<String> = staged.filter { observed ->
        observed.present && pins.none { pin -> pin.name == observed.name }
    }.map { observed ->
        "staged ${observed.name} has no lockfile pin; unpinned native bytes must " +
            "not ride into the APK ($stagingLabel)"
    }
}
