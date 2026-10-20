@file:Suppress("TooManyFunctions")

package com.pocketrealm.desktop

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.server.WorldNative
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimePhase
import java.io.File
import kotlin.system.exitProcess

/**
 * W4.1: the per-flavor DESKTOP END-TO-END qualification probe — the flow a
 * real user drives, headless, on the shipped-default MariaDB lane:
 *
 *   import (folder lane, or the archive lane) → the prepared-data lane's
 *   consumption check → realm boot to READY → genuine-client auto-login →
 *   Save & Exit.
 *
 * Usage:
 *   gradlew flavorE2eProbe -DflavorId=tbc -DclientDir="E:/Wow clients/<dir>"
 *                          [-Darchive=<zip>] [-DskipImport=1]
 *
 * Per step, one EVIDENCE line (`E2E-PROBE key="value" ...`); any failure
 * exits non-zero with the honest step name — the probe never fabricates a
 * pass. The staged client's WoW.exe is hash-pinned against the flavor's
 * S5.1 identity before a byte is imported (the FlavorLoginProbe rule).
 */
private const val BOOT_TIMEOUT_MS = 15 * 60_000L
private const val PHASE_POLL_MS = 2_000L
private const val SESSION_TIMEOUT_MS = 120_000L
private const val SHA_READ_BUFFER = 1 shl 16
private const val MS_PER_SECOND = 1000L

/** The harness lane's account identity (the AUTO-LOGIN step's provision +
 * type choreography drives both). */
private data class E2eCredentials(val username: String, val password: String)

/** WorldNative.createAccountNative's return codes: 10 = created, 0 = the
 * account already exists (both leave a usable lane account row). */
private const val ACCOUNT_CREATE_OK = 10
private const val ACCOUNT_EXISTS = 0

private fun sha256(file: File): String =
    file.inputStream().use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(SHA_READ_BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

private fun sha256(stream: java.io.InputStream): String =
    stream.use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(SHA_READ_BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

/** The pinned genuine-client identity gate (the FlavorLoginProbe rule):
 * the staged WoW.exe must be the flavor's S5.1-measured build before a
 * byte of it is imported. */
private fun pinnedClientDir(flavor: ClientFlavor): File {
    val dir = File(System.getProperty("clientDir")
        ?: error("-DclientDir=<staged genuine client folder> is required " +
            "(or -DskipImport=1 to reuse the managed generation)"))
    val exe = File(dir, "WoW.exe")
    check(exe.isFile) { "IMPORT: no WoW.exe under ${dir.absolutePath}" }
    val sha = sha256(exe)
    requirePinnedSha(flavor, sha)
    return dir
}

/** IMPORT: the folder lane, or the archive lane with -Darchive. */
private fun importLane(flavor: ClientFlavor, model: DesktopAppModel): String {
    val archive = System.getProperty("archive")?.let(::File)
    return if (archive != null) {
        check(archive.isFile) { "IMPORT: archive missing: $archive" }
        // the archive lane's own gate is PE build/version based (an
        // exe-sha mismatch is only a warning), so the probe enforces the
        // S5.1 sha pin itself on the resolved flavor before a byte moves.
        // the archive lane stands ALONE — a genuine
        // clientDir pairing is no longer required (it made every
        // archive-lane E2E run demand a second full client tree); the
        // archive's own WoW.exe member is hash-pinned below, which is the
        // same S5.1 evidence the folder lane produces.
        System.getProperty("clientDir")?.let { pinnedClientDir(flavor) }
        // R3-W4-1 (code-red round 3): the archive's own WoW.exe member
        // must hash to the S5.1 pin — a doctored same-PE-version archive
        // is refused here.
        pinnedArchiveExe(flavor, archive)
        kotlinx.coroutines.runBlocking { model.importClientArchive(archive) { } }
        "archive"
    } else {
        kotlinx.coroutines.runBlocking {
            model.importClientFolder(pinnedClientDir(flavor)) { }
        }
        "folder"
    }
}

/** R3-W4-1: the archive member's WoW.exe must be the flavor's S5.1-measured
 * build. Streams the member (never a full extract) and enforces the pin
 * with the same loud copy as the folder lane. */
internal fun pinnedArchiveExe(flavor: ClientFlavor, archive: File) {
    java.util.zip.ZipFile(archive).use { zip ->
        val entry = wowExeEntry(zip, flavor)
        val sha = sha256(zip.getInputStream(entry))
        requirePinnedSha(flavor, sha)
    }
}

/** The shallowest WoW.exe member of a client-tree archive. */
private fun wowExeEntry(
    zip: java.util.zip.ZipFile,
    flavor: ClientFlavor,
): java.util.zip.ZipEntry =
    zip.entries().asSequence()
        .filter { !it.isDirectory }
        .filter { it.name.substringAfterLast('/').equals("WoW.exe", true) }
        .minByOrNull { it.name.count { ch -> ch == '/' } }
        ?: error(
            "IMPORT: the archive carries no WoW.exe member — the " +
                "${flavor.flavorId} E2E refuses to import an archive " +
                "that is not a client tree",
        )

/** Wait for the supervisor to reach one of [wanted]; a phase in [refused]
 * refuses with the journal's own error; timeout is a loud failure. */
private fun awaitPhase(model: DesktopAppModel, what: String, timeoutMs: Long,
                       wanted: Set<RuntimePhase>,
                       refused: Set<RuntimePhase> = emptySet()): RuntimePhase {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val phase = model.supervisor.state.value.phase
        when {
            phase in wanted -> return phase
            phase in refused -> error(
                "$what: runtime entered $phase: " +
                    (model.supervisor.state.value.lastError ?: "no detail"))
        }
        Thread.sleep(PHASE_POLL_MS)
    }
    error("$what: not reached within ${timeoutMs / MS_PER_SECOND}s")
}


/** The AUTO-LOGIN step: provision the lane account + the SRP verifier row
 * (provider-aware; the booted world serves THIS lane's realmd), drive the
 * SendInput choreography, and require the RUNNING phase — the client
 * passed the login + realm screens to the character screen. */
private fun autologinStep(
    model: DesktopAppModel,
    flavor: ClientFlavor,
    pid: Int,
    credentials: E2eCredentials,
    evidence: MutableList<Pair<String, String>>,
) {
    val (username, password) = credentials
    val createRc = WorldNative.createAccountNative(
        username, password, ServerRuntimeContract.CONTROL_TIMEOUT_MS)
    check(createRc == ACCOUNT_CREATE_OK || createRc == ACCOUNT_EXISTS) {
        "AUTOLOGIN: account create refused (rc=$createRc)"
    }
    WorldNative.setAccountAddonNative(
        username, flavor.accountExpansion,
        ServerRuntimeContract.CONTROL_TIMEOUT_MS)
    val srp = VsrpMath.verifierFor(username, password)
    val seeded = seedSrpVerifierRowForLane(model.backend, model.roots, username, srp)
    evidence += "verifierRow" to (seeded?.let { "sqlite" } ?: "mariadb")
    val timings = model.settings.value.effectiveAutoLoginTimings()
    val failure = Win32AutoLogin.tryLogin(
        pid, username, password, timings, flavorId = flavor.flavorId)
    check(failure == null) { "AUTOLOGIN: $failure" }
    awaitPhase(model, "AUTOLOGIN", SESSION_TIMEOUT_MS,
        wanted = setOf(RuntimePhase.RUNNING))
    evidence += "autoLogin" to "character-screen"
}

/** a refused archive import (e.g. the storage preflight) must
 * still land a machine-visible evidence line — the failure record IS the
 * evidence for the archived attempt. Extracted from main for
 * length/complexity. */
private fun recordArchiveAttempt(
    flavor: ClientFlavor,
    evidence: MutableList<Pair<String, String>>,
) {
    val import = System.getProperty("archive")?.let(::File) ?: return
    if (System.getProperty("skipImport") == "1") return
    check(import.isFile) { "IMPORT: archive missing: $import" }
    pinnedArchiveExe(flavor, import)
    evidence += "archiveAttempt" to import.name
    emit(evidence)
}

private fun emit(evidence: List<Pair<String, String>>) {
    println(
        "E2E-PROBE " + evidence.joinToString(" ") { (k, v) -> "$k=\"$v\"" }
    )
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "TooManyFunctions")
fun main() {
    val flavorId = System.getProperty("flavorId")
        ?: error("-DflavorId=<vanilla|tbc|wotlk> is required")
    val flavor = ClientFlavors.ALL.firstOrNull { it.flavorId == flavorId }
        ?: error("unknown flavor id \"$flavorId\"")
    val skipImport = System.getProperty("skipImport") == "1"
    val username = (System.getProperty("e2eUser") ?: "RPTEST").uppercase()
    val password = System.getProperty("e2ePass") ?: "rptestpass"
    val evidence = mutableListOf("flavor" to flavorId)
    fun emit() = println(
        "E2E-PROBE " + evidence.joinToString(" ") { (k, v) -> "$k=\"$v\"" })

    try {
        val model = DesktopAppModel()
        recordArchiveAttempt(flavor, evidence)
        try {
            // the persisted lane pick (the Home flavor card's write).
            model.updateSettings { it.copy(selectedFlavorId = flavorId) }
            // parity: the UI's HomeScreen re-applies the persisted
            // pick to the ActiveFlavor machine when the settings arrive —
            // the headless probe drives the same law explicitly, or the
            // holder-based resolvers (import size limits) refuse the
            // multi-qualified no-pick state before the import starts.
            runCatching { com.pocketrealm.client.ActiveFlavor.select(flavorId) }
            evidence += "lane" to "persisted"

            if (!skipImport) {
                evidence += "import" to importLane(flavor, model)
            } else {
                evidence += "import" to "skipped(reused)"
            }

            // DATA-PREP CONSUMPTION: the boot consumes the published
            // per-flavor generation (the envelope resolves for THIS lane).
            val prep = model.worldDataStatus()
            check(prep.ready) {
                "PREP: the prepared-data lane is not ready: ${prep.detail}"
            }
            evidence += "preparedData" to (prep.detail ?: "ready")

            check(kotlinx.coroutines.runBlocking {
                model.startRealm(includeClient = true)
            }.ok) { "BOOT: start refused" }
            awaitPhase(model, "BOOT", BOOT_TIMEOUT_MS,
                wanted = setOf(RuntimePhase.RUNNING, RuntimePhase.WORLD_READY),
                refused = setOf(RuntimePhase.ERROR, RuntimePhase.CLIENT_FAILED))
            val client = kotlinx.coroutines.runBlocking {
                model.backend.observe(RuntimeComponent.CLIENT)
            }
            val pid = client.pid ?: 0
            check(client.ready && pid > 0) { "BOOT: client not up (pid=$pid)" }
            evidence += "boot" to "READY"
            evidence += "clientPid" to pid.toString()

            autologinStep(model, flavor, pid, E2eCredentials(username, password), evidence)

            // SAVE & EXIT: the graceful stop; the world saves, the client
            // dies, the journal commits clean.
            check(kotlinx.coroutines.runBlocking { model.saveAndExit() }.ok) {
                "SAVE-EXIT: refused"
            }
            awaitPhase(model, "SAVE-EXIT", SESSION_TIMEOUT_MS,
                wanted = setOf(RuntimePhase.STOPPED))
            val journal = model.supervisor.state.value
            check(journal.clean) {
                "SAVE-EXIT: journal not clean: ${journal.lastDurableAction}"
            }
            evidence += "saveExit" to "clean"
            emit()
        } finally {
            model.close()
        }
    } catch (failure: IllegalStateException) {
        evidence += "FAILED" to (failure.message ?: failure.javaClass.simpleName)
        emit()
        exitProcess(1)
    }
}


/** R3-4 (round 2 extraction): the S5.1 sha-pin gate over the staged
 * WoW.exe — a pinless flavor refuses (a hash-pinned genuine-client import
 * cannot be evidenced unpinned) and a sha drift refuses naming both
 * digests. Internal so the JVM pin test drives the production check. */
internal fun requirePinnedSha(flavor: ClientFlavor, sha: String) {
    check(flavor.knownExeSha256 != null) {
        "IMPORT: ${flavor.flavorId} has no S5.1 sha pin — the probe refuses " +
            "(a hash-pinned genuine-client import cannot be evidenced unpinned)"
    }
    check(sha == flavor.knownExeSha256) {
        "IMPORT: the staged WoW.exe sha256 ($sha) does not match the " +
            "${flavor.flavorId} S5.1 pin (${flavor.knownExeSha256})"
    }
}
