package com.pocketrealm.client

import com.pocketrealm.desktop.DesktopLog
import com.pocketrealm.fs.FileDigests
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * The desktop vanilla-tweaks patch lane (DESK-27/58/89): the Android
 * `WineRuntimeStore.applyTweaks` twin over the user's own client copy.
 *
 * The patch PRODUCER is the staged, lockfile-pinned Windows exe
 * (`schemas/vanilla-tweaks-win.json` pins
 * `native/.build-vanilla-tweaks-win/staging/bin/vanilla-tweaks.exe` by size
 * and SHA-256); this object verifies the staged binary against the lockfile
 * before every run, invokes it with the request's [ClientTweaksConfig.toFlags]
 * (the upstream 1.6.0 CLI: `<exe> <infile> -o <outfile> <flags...>`), finalizes
 * the upstream output with the Pocket-Realm nearby-loot companion patch, and
 * publishes `WoW.exe.patched` next to the pristine executable only when the
 * complete image matches [ClientTweaksConfig.expectedPublishedPatchedBytes]
 * byte for byte. Publication is idempotent through the authenticated manifest
 * sidecar (the Android cache contract, verbatim semantics).
 *
 * Authorization is deliberately strong: a requested patch lane only ever
 * touches an executable whose full SHA-256 equals
 * [ClientTweaksConfig.AUTHORIZED_CLIENT_SHA256] (the enUS build-5875 layout
 * the byte-addressed patches were qualified against); anything else resolves
 * to pristine Vanilla through [ClientTweaksConfig.effectiveForExecutable] and
 * launches unpatched. The user's pristine `WoW.exe` is never modified.
 */
internal object DesktopVanillaTweaksPatcher {

    private const val PATCHER_VERSION = "1.6.0"
    private const val PRISTINE_NAME = "WoW.exe"
    private const val PATCHED_NAME = "WoW.exe.patched"
    private const val MANIFEST_NAME = "WoW.exe.patched.manifest.json"

    /** The patcher process budget (the Android PATCHER_TIMEOUT_SECONDS twin). */
    private const val PATCHER_TIMEOUT_SECONDS = 120L

    /** One patch-publication verdict. [executable] is the binary to launch:
     * the freshly published/cached `WoW.exe.patched`, or the pristine
     * `WoW.exe` when no patch was requested or the layout refused the lane. */
    data class Result(
        val executable: File,
        val patched: Boolean,
        val effectiveConfig: ClientTweaksConfig,
        val detail: String,
    )

    /**
     * Publish `WoW.exe.patched` for [requested] next to [dir]/WoW.exe and
     * return the executable the launch should run. Throws only on a requested
     * patch lane that could not be honored (a missing/mismatched patcher, a
     * native output that disagrees with the patch model) — the caller turns
     * that into the honest launch refusal, mirroring the Android prepare
     * contract. Unpatched requests never touch the client directory.
     */
    fun publishForLaunch(dir: File, requested: ClientTweaksConfig): Result {
        val pristine = File(dir, PRISTINE_NAME)
        check(pristine.isFile) { "vanilla-tweaks lane requires $PRISTINE_NAME under $dir" }
        val pristineSha = FileDigests.sha256(pristine)
        val resolution = resolveEffectiveClientTweaks(requested, pristineSha)
        val effective = resolution.config
        if (!effective.hasAnyPatch()) {
            return Result(
                executable = pristine,
                patched = false,
                effectiveConfig = effective,
                detail = if (resolution.fallback) {
                    "optional tweaks skipped: executable layout is not the qualified patch target"
                } else {
                    "no patches requested"
                },
            )
        }
        check(effective.acceptsExecutableForLaunch(pristineSha)) {
            "vanilla-tweaks patch refused: executable is not the authorized build-5875 layout"
        }
        return publishVerified(dir, pristineSha, effective)
    }

    /**
     * The publish core for an ALREADY-AUTHORIZED (config, pristine sha)
     * pair: cache check → staged patcher run → nearby-loot companion
     * finalize → byte-model verification → atomic publication + manifest
     * sidecar. Internal so the pipeline is testable on a synthetic image
     * (the production gate stays in [publishForLaunch]).
     */
    internal fun publishVerified(
        dir: File,
        pristineSha: String,
        effective: ClientTweaksConfig,
    ): Result {
        val pristine = File(dir, PRISTINE_NAME)
        check(pristine.isFile) { "vanilla-tweaks lane requires $PRISTINE_NAME under $dir" }
        check(effective.hasAnyPatch()) { "publish core requires a patch-carrying config" }
        val patched = File(dir, PATCHED_NAME)
        val manifestFile = File(dir, MANIFEST_NAME)
        val signature = signature(effective.toJson(), pristineSha)
        val expectedBytes = ClientTweaksConfig.expectedPublishedPatchedBytes(
            pristine.readBytes(),
            effective,
        )
        if (cacheValid(patched, manifestFile, signature, pristineSha, expectedBytes)) {
            return Result(patched, true, effective, "cached patched executable verified")
        }
        patched.delete()
        manifestFile.delete()
        val temporary = File(dir, ".$PATCHED_NAME.${ProcessHandle.current().pid()}.tmp")
        temporary.delete()
        try {
            runPatcher(pristine, temporary, effective.toFlags())
            // Companion patch owned by Pocket Realm: finalize the upstream
            // output exactly like the Android prepare path.
            val actualBytes =
                ClientTweaksConfig.applyNearbyLootAcceptPatch(temporary.readBytes())
            check(actualBytes.size == expectedBytes.size) { "patched executable size changed" }
            checkPeX86(actualBytes)
            check(actualBytes.contentEquals(expectedBytes)) {
                "vanilla-tweaks output differs from the authorized patch model"
            }
            FileAtomicReplace.write(temporary, actualBytes, patched)
            FileAtomicReplace.write(
                temporary,
                JSONObject()
                    .put("schema", 1)
                    .put("signature", signature)
                    .put("pristineSha256", pristineSha)
                    .put("patchedSha256", FileDigests.sha256(patched))
                    .put("nearbyLootAccept", true)
                    .put("config", JSONObject(effective.toJson()))
                    .put("patcher", JSONObject(lockfileArtifactJson()?.toString() ?: "{}"))
                    .toString()
                    .toByteArray(Charsets.UTF_8),
                manifestFile,
            )
        } finally {
            temporary.delete()
        }
        return Result(patched, true, effective, "WoW.exe.patched published and model-verified")
    }

    // The effective-tweaks resolution is the top-level
    // [resolveEffectiveClientTweaks] in ClientTweaksRuntime.kt (the shared
    // ClientTweaksConfigTest compiles against that twin).

    /** The Android cache contract, verbatim semantics: the sidecar must name
     * this request's signature, this pristine hash, and the patched image
     * must equal the expected model byte for byte. */
    private fun cacheValid(
        patched: File,
        manifestFile: File,
        signature: String,
        pristineSha: String,
        expectedBytes: ByteArray,
    ): Boolean {
        if (!patched.isFile || !manifestFile.isFile) return false
        return runCatching {
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val patchedBytes = patched.readBytes()
            manifest.getInt("schema") == 1 &&
                manifest.getString("signature") == signature &&
                manifest.getString("pristineSha256") == pristineSha &&
                manifest.getString("patchedSha256") == FileDigests.sha256(patched) &&
                patchedBytes.contentEquals(expectedBytes).also { if (it) checkPeX86(patchedBytes) }
        }.getOrDefault(false)
    }

    /** The authenticated sidecar signature (the Android formula, verbatim). */
    private fun signature(canonicalConfigJson: String, pristineSha: String): String =
        FileDigests.sha256(canonicalConfigJson + "\u0001" + PATCHER_VERSION + "\u0001" + pristineSha)

    /**
     * Runs the staged patcher: `<exe> <infile> -o <outfile> <flags...>` (the
     * upstream clap CLI; the Android runPatcher twin) under the
     * [PATCHER_TIMEOUT_SECONDS] budget. The staged binary is
     * lockfile-verified on every run — a replaced or corrupted staging never
     * reaches the user's client copy.
     */
    private fun runPatcher(pristine: File, patched: File, flags: List<String>) {
        val binary = stagedPatcherBinary()
            ?: error(
                "vanilla-tweaks patcher is not staged (expected " +
                    "$STAGED_PATCHER_REPO_PATH pinned by $LOCKFILE_REPO_PATH)",
            )
        checkLockfilePin(binary)
        runCheckedProcess(
            buildList {
                add(binary.absolutePath)
                add(pristine.absolutePath)
                add("-o"); add(patched.absolutePath)
                addAll(flags)
            },
            label = "vanilla-tweaks patcher",
            timeoutSeconds = PATCHER_TIMEOUT_SECONDS,
        )
    }

    /**
     * The Android `runCheckedProcess` twin (WineRuntimeStore): the merged
     * child output is drained under a hard cap WHILE the budget runs (a
     * chatty child can never block on a full pipe), and a child that
     * outlives [timeoutSeconds] is destroyed forcibly and refused honestly —
     * a hung child costs seconds, never an unbounded launch. Internal so
     * the budget contract is pinnable without staging a real patcher.
     */
    internal fun runCheckedProcess(
        command: List<String>,
        label: String,
        timeoutSeconds: Long,
    ): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val drain = thread(isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        // Retain up to the cap, but keep reading: the drain
                        // is what frees the child's pipe, not the retention.
                        if (output.length < PATCHER_OUTPUT_CAP_CHARS) output.appendLine(line)
                    }
                }
            }
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            drain.join(DRAIN_JOIN_MILLIS)
            DesktopLog.w(TAG, "$label timed out after ${timeoutSeconds}s: ${output.take(400)}")
            error("$label timed out after ${timeoutSeconds}s")
        }
        drain.join(DRAIN_JOIN_MILLIS)
        if (output.length > PATCHER_OUTPUT_CAP_CHARS) output.setLength(PATCHER_OUTPUT_CAP_CHARS)
        val rc = process.exitValue()
        if (rc != 0) {
            DesktopLog.w(TAG, "$label rc=$rc: ${output.take(400)}")
            error("$label failed (rc=$rc)")
        }
        return output.toString()
    }

    /** Lockfile pin check: size + SHA-256 must equal the pinned artifact. */
    internal fun checkLockfilePin(binary: File) {
        val artifact = lockfileArtifactJson()
            ?: error("vanilla-tweaks lockfile is absent ($LOCKFILE_REPO_PATH)")
        val pinnedSize = artifact.optLong("size", -1L)
        val pinnedSha = artifact.optString("sha256")
        check(pinnedSize == binary.length()) {
            "vanilla-tweaks patcher size ${binary.length()} does not match the lockfile pin $pinnedSize"
        }
        check(FileDigests.sha256(binary).equals(pinnedSha, ignoreCase = true)) {
            "vanilla-tweaks patcher sha256 does not match the lockfile pin"
        }
    }

    /** The lockfile's vanilla-tweaks artifact row (schema 1 layout), or null. */
    private fun lockfileArtifactJson(): JSONObject? {
        val lockfile = stagedLockfile() ?: return null
        return runCatching {
            val root = JSONObject(lockfile.readText(Charsets.UTF_8))
            check(root.optInt("schema") == 1) { "lockfile schema mismatch" }
            check(root.optString("component") == "vanilla-tweaks") {
                "lockfile component mismatch"
            }
            root.getJSONArray("artifacts").getJSONObject(0)
        }.getOrNull()
    }

    /**
     * The lockfile location, resolved like the staged binary: the jpackage
     * app dir first, then the repo checkout (dev runs walk up).
     */
    internal fun stagedLockfile(): File? {
        val javaLibraryPath = System.getProperty("java.library.path") ?: ""
        val candidates = javaLibraryPath.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(File(it), LOCKFILE_NAME) } +
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .take(MAX_REPO_WALK_UP)
                .map { File(it, LOCKFILE_REPO_PATH) }
        return candidates.firstOrNull { it.isFile }
    }

    /**
     * The staged patcher binary. Sources, in order: the jpackage app dir
     * (java.library.path — a packaged image can carry the exe beside the
     * DLLs), then the repo checkout (dev runs walk up, the same posture as
     * the bundled-asset resolver).
     */
    internal fun stagedPatcherBinary(): File? {
        val javaLibraryPath = System.getProperty("java.library.path") ?: ""
        val candidates = javaLibraryPath.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(File(it), PATCHER_BINARY_NAME) } +
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .take(MAX_REPO_WALK_UP)
                .map { File(it, STAGED_PATCHER_REPO_PATH) }
        return candidates.firstOrNull { candidate ->
            candidate.isFile && candidate.name.equals(PATCHER_BINARY_NAME, ignoreCase = true)
        }
    }

    /** PE32 little-endian x86 gate (the Android checkPeX86 twin). */
    private fun checkPeX86(bytes: ByteArray) {
        require(ClientTweaksConfig.peMagicOk(bytes)) { "client executable MZ header is invalid" }
        val peOffset = java.nio.ByteBuffer.wrap(bytes, 0x3c, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        require(peOffset >= 0 && peOffset + 6 <= bytes.size) {
            "client PE header is out of range"
        }
        require(
            bytes.copyOfRange(peOffset, peOffset + 4).contentEquals(
                byteArrayOf('P'.code.toByte(), 'E'.code.toByte(), 0, 0),
            ),
        ) { "client PE signature is invalid" }
        val machine = java.nio.ByteBuffer.wrap(bytes, peOffset + 4, 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        require(machine == 0x014c) { "client executable is not PE32 x86" }
    }

    /** pid-temp + fsync + atomic replace (the desktop secureWrite posture). */
    private object FileAtomicReplace {
        fun write(temp: File, content: ByteArray, target: File) {
            java.io.FileOutputStream(temp).use { stream ->
                stream.write(content)
                stream.fd.sync()
            }
            move(temp, target)
        }

        fun move(temp: File, target: File) {
            try {
                java.nio.file.Files.move(
                    temp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    temp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
    }

    internal val PATCHED_FILE_NAME: String = PATCHED_NAME

    private const val TAG = "ClientTweaks"
    private const val MAX_REPO_WALK_UP = 6
    private const val PATCHER_BINARY_NAME = "vanilla-tweaks.exe"
    private const val LOCKFILE_NAME = "vanilla-tweaks-lockfile-win.json"
    private const val PATCHER_OUTPUT_CAP_CHARS = 2_000
    private const val DRAIN_JOIN_MILLIS = 5_000L
    private const val STAGED_PATCHER_REPO_PATH =
        "native/.build-vanilla-tweaks-win/staging/bin/vanilla-tweaks.exe"
    private const val LOCKFILE_REPO_PATH = "schemas/vanilla-tweaks-lockfile-win.json"
}
