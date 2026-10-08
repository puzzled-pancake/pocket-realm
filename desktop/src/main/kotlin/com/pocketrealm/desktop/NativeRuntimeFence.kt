package com.pocketrealm.desktop

import com.pocketrealm.fs.FileDigests
import com.pocketrealm.server.NativeRuntimeFreshness
import java.io.File
import org.json.JSONObject

/**
 * Build-time driver behind the desktop `validateNativeRuntimeFreshness` /
 * `validateNativeRuntimePins` Gradle tasks (the DESK-115 parity leg): the
 * Windows mirror of the Android staleness fence. It reads the committed
 * win-lane lockfile (schemas/realm-runtime-lockfile-mariadb-win.json — the
 * MARIADB backend the app's shipped default boots on; the staged
 * pocket_*.dll are the MariaDB Connector/C build whose static libmysql.dll
 * import is why packageApp stages libmysql.dll and the provider closure
 * beside them) and
 * schemas/sources.json, and — in staged mode — rehashes the four staged
 * DLLs packageApp jpackages (the lockfile's artifact rows), the pinned
 * seed transcripts the image bundles, and the lane provenance, feeding the
 * already-compiled shared [NativeRuntimeFreshness.evaluate]. A non-empty
 * Verdict fails the build with the fence's REMEDY copy, so a stale
 * pocket_*.dll can no longer ride into the app image silently (the ABI
 * status width the app checks at runtime passes for a stale-but-ABI-stable
 * DLL; this fence is the build-time catch).
 *
 * Honest-skip contract (mirrors packageApp's missing-inputs posture): staged
 * mode with any staged DLL OR seed transcript absent means the native lanes
 * were never built on this machine — the driver exits 0 with a loud skip
 * line and packageApp's own missing-inputs check reports the gaps. Only
 * fully-staged bytes are judged. `pocketSkipNativeFreshness=true` disables
 * the fence loudly; any other value is refused as ambiguous.
 */
@Suppress("MagicNumber") // exit codes
object NativeRuntimeFence {

    /**
     * The desktop lane's lockfile: the mariadb-win lane, matching the
     * staged runtime bytes (the SQLITE dev/compat selection keeps
     * schemas/realm-runtime-lockfile-sqlite-win.json pinned by
     * tests/test_win_lockfile.py's BuildConfig telltale).
     */
    const val LOCKFILE_RELATIVE = "schemas/realm-runtime-lockfile-mariadb-win.json"

    /** Committed source-pin registry the lockfile commits must still match. */
    const val SOURCES_RELATIVE = "schemas/sources.json"

    /** The realm-staging provenance written by the full lane build (the
     * non-sqlite staging lane, matching this lockfile's backend). */
    const val PROVENANCE_RELATIVE =
        "native/.build-o09-x86_64/realm-staging/BUILD_PROVENANCE.json"

    /**
     * The seed transcripts packageApp bundles into the image (staged by the
     * sqlite staging lane — the .sqlz bytes are ABI-independent and pinned
     * by THIS lockfile's seed_transcripts rows, which the lane writer
     * cross-checks against the sqlite sibling + the append-only baseline).
     * The desktop twin of the android validateRealmRuntime seed leg
     * (android/app/build.gradle.kts).
     */
    const val SEED_STAGING_RELATIVE = "native/.build-o09-x86_64/realm-staging-sqlite"

    /**
     * The seed databases whose .sqlz packageApp hardcodes into the image
     * (desktop/build.gradle.kts) — the fence walks THIS fixed list, never
     * the lockfile's seed_transcripts key set, so a deleted or malformed
     * row cannot silently un-verify a bundled seed (the desktop twin of
     * the android validateRealmRuntime `lockfile has no seed pin` error,
     * which also walks the fixed packaged list).
     */
    val PACKAGED_SEED_DATABASES = listOf(
        "classicrealmd",
        "classiccharacters",
        "classiclogs",
        "classicmangos",
    )

    /** The desktop lane identity: the win mariadb lane, nothing else. */
    const val EXPECTED_ABI = "windows-x86_64"
    const val EXPECTED_BACKEND = "mariadb"

    /** Thrown for missing/unparseable lane documents; main maps it to exit 2. */
    class FenceDocumentException(message: String) : java.io.IOException(message)

    const val EXIT_OK = 0
    const val EXIT_FENCE_FAILED = 1
    const val EXIT_USAGE_OR_PARSE = 2

    /**
     * Runs the fence and returns the process exit code: 0 pass/skip,
     * 1 non-empty Verdict, 2 usage or document errors.
     */
    fun run(
        repoRoot: File,
        laneLabel: String,
        requireStagedBytes: Boolean,
        skipRequested: String,
    ): Int {
        val skip = skipRequested.trim()
        if (skip == "true") {
            println(
                "!!! pocketSkipNativeFreshness=true: the native staleness fence is " +
                    "DISABLED for $laneLabel; a stale pocket_*.dll can be packaged " +
                    "silently !!!",
            )
            return EXIT_OK
        }
        require(skip.isEmpty()) {
            "Refusing ambiguous -PpocketSkipNativeFreshness='$skip'; pass exactly " +
                "'true' to skip loudly, or drop the property to run the fence"
        }
        return when (val outcome = evaluate(repoRoot, laneLabel, requireStagedBytes)) {
            is Outcome.Skipped -> {
                println(
                    "validateNativeRuntimeFreshness SKIPPED for $laneLabel (the native " +
                        "lanes were not built here; packageApp would skip on the same " +
                        "missing inputs): ${outcome.missing.joinToString(", ")}",
                )
                EXIT_OK
            }
            is Outcome.Failed -> {
                println(outcome.verdict.message(laneLabel))
                EXIT_FENCE_FAILED
            }
            is Outcome.Passed -> {
                println(
                    "Native runtime freshness fence PASSED for $laneLabel (" +
                        "${outcome.pinCount} lockfile pin(s), staged bytes: " +
                        "${if (requireStagedBytes) "rehashed" else "not required"}).",
                )
                EXIT_OK
            }
        }
    }

    /** One lockfile artifact row plus the repo-relative path it hashes. */
    private data class PinWithPath(val pin: NativeRuntimeFreshness.ArtifactPin, val path: String)

    /** The commit + artifact pin rows parsed out of one lane document. */
    private data class DocumentPins(
        val commits: NativeRuntimeFreshness.CommitPins,
        val artifacts: List<PinWithPath>,
    )

    /** One seed_transcripts row plus the staged .sqlz observation of it;
     * a null [pin] means the lockfile row is missing or unparseable. */
    private data class SeedObservation(
        val database: String,
        val pin: NativeRuntimeFreshness.SeedTranscriptPin?,
        val size: Long,
        val sha256: String?,
    )

    /** Everything the fence compares, gathered from the lane documents. */
    private data class LaneDocuments(
        val laneLabel: String,
        val lockfile: JSONObject,
        val pins: List<PinWithPath>,
        val staged: List<NativeRuntimeFreshness.StagedArtifact>,
        val provenance: Provenance?,
        val sourcesCommits: NativeRuntimeFreshness.CommitPins,
        val requireStagedBytes: Boolean,
    )

    /** Lane provenance: commits always; artifact rows when the document has them. */
    private data class Provenance(
        val commits: NativeRuntimeFreshness.CommitPins,
        val pins: List<NativeRuntimeFreshness.ArtifactPin>,
    )

    private sealed interface Outcome {
        data class Passed(val pinCount: Int) : Outcome
        data class Skipped(val missing: List<String>) : Outcome
        data class Failed(val verdict: NativeRuntimeFreshness.Verdict) : Outcome
    }

    /**
     * Evaluates the whole fence and returns its terminal outcome. The early
     * exits ARE the verdict shapes a fence driver has (no pins, absent staged
     * bytes, verdict); they stay explicit rather than nested.
     */
    @Suppress("ReturnCount") // the terminal fence outcomes, kept explicit
    private fun evaluate(repoRoot: File, laneLabel: String, requireStagedBytes: Boolean): Outcome {
        val lockfile = readJsonObject(repoRoot.resolve(LOCKFILE_RELATIVE), "lockfile")
        val pinsWithPaths = documentPins(lockfile).artifacts
        if (pinsWithPaths.isEmpty()) {
            return Outcome.Failed(
                NativeRuntimeFreshness.Verdict(listOf("$LOCKFILE_RELATIVE has no artifact pins")),
            )
        }
        val staged = observeStagedBytes(repoRoot, pinsWithPaths, requireStagedBytes)
        val seedStaged = observeSeedTranscripts(repoRoot, lockfile, requireStagedBytes)
        if (requireStagedBytes) {
            val missing = pinsWithPaths.mapIndexedNotNull { index, pinWithPath ->
                if (!staged[index].present) pinWithPath.pin.name else null
            } + seedStaged.filter { it.sha256 == null }.map { "${it.database}.sqlz" }
            if (missing.isNotEmpty()) return Outcome.Skipped(missing)
        }
        val provenance = if (requireStagedBytes) {
            readProvenance(repoRoot.resolve(PROVENANCE_RELATIVE))
        } else {
            null
        }
        val sourcesCommits = readSourcesCommits(repoRoot.resolve(SOURCES_RELATIVE))
        val verdict = NativeRuntimeFreshness.evaluate(
            buildCheck(
                LaneDocuments(
                    laneLabel, lockfile, pinsWithPaths, staged,
                    provenance, sourcesCommits, requireStagedBytes,
                ),
            ),
        )
        val seedFailures = seedTranscriptMismatches(seedStaged, requireStagedBytes)
        val combined = if (seedFailures.isEmpty()) {
            verdict
        } else {
            NativeRuntimeFreshness.Verdict(verdict.failures + seedFailures)
        }
        return if (combined.ok) Outcome.Passed(pinsWithPaths.size) else Outcome.Failed(combined)
    }

    private fun observeStagedBytes(
        repoRoot: File,
        pins: List<PinWithPath>,
        requireStagedBytes: Boolean,
    ): List<NativeRuntimeFreshness.StagedArtifact> {
        if (!requireStagedBytes) {
            // Pins-only mode (the test leg): the untracked staging outputs
            // cannot be assumed to exist, exactly like the shared unit test's
            // pinsOnlyModeIgnoresAbsentStagingOutputs fixture.
            return emptyList()
        }
        return pins.map { (pin, relative) ->
            val file = repoRoot.resolve(relative)
            if (file.isFile) {
                NativeRuntimeFreshness.StagedArtifact(pin.name, file.length(), FileDigests.sha256(file))
            } else {
                NativeRuntimeFreshness.StagedArtifact(pin.name, 0, null)
            }
        }
    }

    /**
     * The seed transcripts the image bundles, keyed by the FIXED database
     * set packageApp stages ([PACKAGED_SEED_DATABASES] — never the
     * lockfile's key set, so a deleted or malformed seed_transcripts row
     * cannot silently narrow fence coverage below what ships): pinned rows
     * parsed by field name (like the shared evaluator's
     * [NativeRuntimeFreshness.seedTranscriptPin]) and — staged mode only —
     * the staged .sqlz bytes hashed against them, the desktop twin of the
     * android validateRealmRuntime .sqlz leg. A packaged DB with no
     * parseable pin keeps a null pin (a Verdict failure, both modes);
     * absent files keep a null sha256 (the honest-skip list), never a
     * silent pass: packageApp bundles these exact bytes.
     */
    private fun observeSeedTranscripts(
        repoRoot: File,
        lockfile: JSONObject,
        requireStagedBytes: Boolean,
    ): List<SeedObservation> {
        val rows = lockfile.optJSONObject("seed_transcripts")
        return PACKAGED_SEED_DATABASES.map { database ->
            val row = rows?.optJSONObject(database)
            val gzipSha256 = row?.optString("gzip_sha256")?.takeIf { it.isNotEmpty() }
            val gzipSize = row
                ?.takeIf { it.has("gzip_size") }
                ?.let { candidate -> runCatching { candidate.getLong("gzip_size") }.getOrNull() }
            val pin = if (gzipSha256 != null && gzipSize != null) {
                NativeRuntimeFreshness.SeedTranscriptPin(gzipSha256, gzipSize)
            } else {
                null
            }
            val file = repoRoot.resolve("$SEED_STAGING_RELATIVE/assets/seed/$database.sqlz")
            val staged = requireStagedBytes && file.isFile
            SeedObservation(
                database,
                pin,
                if (staged) file.length() else 0,
                if (staged) FileDigests.sha256(file) else null,
            )
        }
    }

    private fun seedTranscriptMismatches(
        observed: List<SeedObservation>,
        requireStagedBytes: Boolean,
    ): List<String> = observed.mapNotNull { seed ->
        val suffix = "$SEED_STAGING_RELATIVE): the shipped seed bytes are " +
            "STALE relative to the lockfile lane pins"
        when {
            seed.pin == null ->
                "$LOCKFILE_RELATIVE has no parseable seed pin for ${seed.database} " +
                    "(seed_transcripts.${seed.database} lacks gzip_sha256/gzip_size) but " +
                    "packageApp still bundles ${seed.database}.sqlz — restore the row " +
                    "(the android twin errors on the same gap)"
            !requireStagedBytes -> null // pins mode: documents only, no staged bytes
            seed.sha256 == null ->
                "seed transcript ${seed.database}.sqlz is missing under $SEED_STAGING_RELATIVE " +
                    "(lockfile pins ${seed.pin.gzipSize} bytes, gzip sha256 ${seed.pin.gzipSha256})"
            seed.size != seed.pin.gzipSize ->
                "seed transcript ${seed.database}.sqlz size ${seed.size} != lockfile pin " +
                    "${seed.pin.gzipSize} ($suffix"
            !seed.pin.gzipSha256.equals(seed.sha256, ignoreCase = true) ->
                "seed transcript ${seed.database}.sqlz gzip sha256 ${seed.sha256} != lockfile " +
                    "pin ${seed.pin.gzipSha256} ($suffix"
            else -> null
        }
    }

    private fun buildCheck(documents: LaneDocuments): NativeRuntimeFreshness.Check {
        val lockfile = documents.lockfile
        return NativeRuntimeFreshness.Check(
            labels = NativeRuntimeFreshness.Labels(
                lane = documents.laneLabel,
                lockfile = LOCKFILE_RELATIVE,
                staging = PROVENANCE_RELATIVE,
            ),
            identity = NativeRuntimeFreshness.Identity(
                expectedAbi = EXPECTED_ABI,
                expectedBackend = EXPECTED_BACKEND,
                lockfileAbi = lockfile.optString("abi").takeIf { it.isNotEmpty() },
                lockfileBackend = lockfile.optString("database_backend").takeIf { it.isNotEmpty() },
            ),
            pins = documents.pins.map { it.pin },
            staged = documents.staged,
            lockfileCommits = documentPins(lockfile).commits,
            provenanceCommits = documents.provenance?.commits,
            provenancePins = documents.provenance?.pins,
            sourcesCommits = documents.sourcesCommits,
            requireStagedBytes = documents.requireStagedBytes,
        )
    }

    /**
     * Everything the fence parses out of ONE lane document (lockfile or
     * provenance): the source-commit pins plus the artifact rows with the
     * repo-relative paths they hash. Rows resolve BY FIELD NAME through the
     * parsed JSON, mirroring the shared evaluator's artifactPins parse.
     */
    private fun documentPins(record: JSONObject): DocumentPins {
        val commits = NativeRuntimeFreshness.CommitPins(
            cmangos = record.optString("cmangos_commit").takeIf { it.isNotEmpty() },
            playerbots = record.optString("playerbots_commit").takeIf { it.isNotEmpty() },
        )
        val rows = record.optJSONArray("artifacts")
        val artifacts = rows?.asSequence()?.mapNotNull { row -> row as? JSONObject }
            ?.mapNotNull { row ->
                val path = row.optString("path").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val size = if (row.has("size")) row.getLong("size") else return@mapNotNull null
                val sha256 = row.optString("sha256").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                PinWithPath(NativeRuntimeFreshness.ArtifactPin(File(path).name, size, sha256), path)
            }
            ?.toList()
            .orEmpty()
        return DocumentPins(commits, artifacts)
    }

    /**
     * Provenance commits + artifact pins; the provenance rows record the
     * Android lane's staged .so set, so only the commit cross-check overlaps
     * the win lockfile — which is exactly the staleness telltale between
     * lanes (a win DLL built from different source pins than the recorded
     * lane). Missing or unparseable provenance is the shared evaluator's
     * "BUILD_PROVENANCE.json is missing" failure, fed as nulls.
     */
    private fun readProvenance(file: File): Provenance? =
        if (!file.isFile) {
            null
        } else {
            runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()?.let { record ->
                val pins = documentPins(record)
                Provenance(pins.commits, pins.artifacts.map { it.pin })
            }
        }

    /** cmangos-core / playerbots commit pins from schemas/sources.json. */
    private fun readSourcesCommits(sources: File): NativeRuntimeFreshness.CommitPins {
        val record = readJsonObject(sources, "schemas/sources.json")
        val entries = record.optJSONArray("sources") ?: return NULL_COMMITS
        fun pinOf(id: String): String? = entries.asSequence()
            .mapNotNull { row -> row as? JSONObject }
            .firstOrNull { it.optString("id") == id }
            ?.optString("commit")
            ?.takeIf { it.isNotEmpty() }
        return NativeRuntimeFreshness.CommitPins(cmangos = pinOf("cmangos-core"), playerbots = pinOf("playerbots"))
    }

    private fun readJsonObject(file: File, what: String): JSONObject {
        if (!file.isFile) {
            throw FenceDocumentException("$what is missing: ${file.absolutePath}")
        }
        return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrElse { failure ->
            throw FenceDocumentException(
                "$what is not parseable JSON (${file.absolutePath}): ${failure.message}",
            )
        }
    }

    private val NULL_COMMITS = NativeRuntimeFreshness.CommitPins(cmangos = null, playerbots = null)
}

/**
 * Gradle entry point: `--mode pins` fences only the committed documents
 * (the `test` leg), `--mode staged` additionally rehashes the staged DLLs
 * and the provenance (the `packageApp` leg).
 */
fun main(args: Array<String>) {
    var repoRoot: File? = null
    var lane = "desktop (mariadb-win)"
    var mode = "pins"
    var skip = ""
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--repo-root" -> repoRoot = File(args[index + 1])
            "--lane" -> lane = args[index + 1]
            "--mode" -> mode = args[index + 1]
            "--skip" -> skip = args[index + 1]
            else -> {
                println("unknown argument: ${args[index]}")
                System.exit(NativeRuntimeFence.EXIT_USAGE_OR_PARSE)
            }
        }
        index += 2
    }
    val requireStagedBytes = when (mode) {
        "pins" -> false
        "staged" -> true
        else -> {
            println("unknown --mode: $mode (use 'pins' or 'staged')")
            System.exit(NativeRuntimeFence.EXIT_USAGE_OR_PARSE)
            false
        }
    }
    val root = repoRoot ?: File(".").canonicalFile
    val exit = try {
        NativeRuntimeFence.run(root, lane, requireStagedBytes, skip)
    } catch (failure: NativeRuntimeFence.FenceDocumentException) {
        println(failure.message)
        NativeRuntimeFence.EXIT_USAGE_OR_PARSE
    }
    System.exit(exit)
}
