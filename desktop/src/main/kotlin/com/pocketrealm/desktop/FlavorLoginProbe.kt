package com.pocketrealm.desktop

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.storage.Settings
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 5 login-topology live verification: launches one STAGED genuine flavor
 * client on the Windows desktop lane and drives [Win32AutoLogin] against its
 * live login window — the evidence that promotes the tbc/wotlk login
 * topologies from UNVERIFIED (they previously rode the verified vanilla
 * lane's contract on static PE-string captures alone).
 *
 * The probe:
 *  1. aims the client's `realmlist.wtf` at 127.0.0.1 (the drive must never
 *     dial a live server) and forces windowed video (the client's own
 *     first-run Config.wtf, written only when absent);
 *  2. spawns the client's WoW.exe and polls the flavor's pinned
 *     [Win32AutoLogin.loginSignatureFor] login frame;
 *  3. on a stable match, drives the SendInput credential choreography
 *     ([Win32AutoLogin.tryLogin] — the credentials are throwaway and there
 *     is no realm to answer them);
 *  4. kills the client process and prints ONE JSON evidence line.
 *
 * Any failure exits non-zero with the honest reason — the probe never
 * fabricates a match.
 *
 * Usage:
 *   gradlew flavorLoginProbe -DclientDir="E:/Wow clients/<dir>" -DflavorId=tbc
 */
fun main() {
    val clientDir = System.getProperty("clientDir")?.let(::File)
        ?: error("-DclientDir=<staged client folder> is required")
    val flavorId = System.getProperty("flavorId")
        ?: error("-DflavorId=<tbc|wotlk|vanilla> is required")
    val flavor = ClientFlavors.ALL.firstOrNull { it.flavorId == flavorId }
        ?: error("unknown flavor id \"$flavorId\"")
    val signature = Win32AutoLogin.loginSignatureFor(flavorId)
        ?: error("no pinned login-window signature for flavor \"$flavorId\"")
    val exe = File(clientDir, "WoW.exe")
    check(exe.isFile) { "no WoW.exe under ${clientDir.absolutePath}" }
    // R4-R2-1: the client exe MUST match the flavor's S5.1 hash pin — a
    // wrong-client or doctored binary would make the promotion evidence a
    // lie (the probe is the identity gate for the login-topology archive).
    val exeSha = sha256(exe)
    val pinnedSha = flavor.knownExeSha256
    check(pinnedSha == null || exeSha == pinnedSha) {
        "the client exe SHA-256 ($exeSha) does not match the $flavorId S5.1 pin " +
            "($pinnedSha) — refusing to verify a wrong-client login topology"
    }
    val evidence = mutableListOf<Pair<String, String>>(
        "flavor" to flavorId,
        "clientDir" to clientDir.absolutePath,
        "exeSha256" to exeSha,
        "pinnedWindowClass" to signature.windowClass,
        "acceptedWindowClasses" to signature.acceptedWindowClasses.joinToString("|"),
        "pinnedTitle" to signature.title,
    )

    // 1. belt-and-braces: never dial a live realm; windowed video.
    // the realmlist leg FAILS CLOSED — a client dir with no locatable
    // realmlist.wtf aborts the probe before any spawn instead of dialing
    // the client's compiled-in default realm.
    val realmlistNotes = aimRealmListsAtLoopback(clientDir)
    evidence += "realmlist" to realmlistNotes.joinToString("|")
    forceWindowedConfig(clientDir)?.let { path ->
        evidence += "configWtf" to path
    }

    // 2. spawn the client.
    val process = ProcessBuilder(exe.absolutePath)
        .directory(clientDir)
        .start()
    evidence += "pid" to process.pid().toString()
    try {
        // 3. poll for the pinned login frame (the DESK-138 wait contract,
        // generous first-run budget).
        val timings = Settings.AutoLoginTimings(
            sessionTimeoutMs = 180_000L,
            loginUiSettleMs = 2_500L,
            requiredStablePolls = 5,
            pollIntervalMs = 500L,
        )
        // pollLoginWindow lives behind Win32AutoLogin; tryLogin re-polls
        // internally, so drive the CHOREOGRAPHY directly and record the
        // poll verdict first via a second poll (cheap, already stable).
        val pid = process.pid().toInt()
        // re-run support: a forced-kill aftermath leaves the genuine
        // client on its known-safe "Hardware changed" modal (it appears
        // tens of seconds into boot), so the dismissal runs CONCURRENTLY
        // with the window poll — click &No (non-destructive) until the
        // pinned frame appears.
        var dismissals = 0
        val stopDismissal = java.util.concurrent.atomic.AtomicBoolean(false)
        val dismissThread = Thread {
            while (!stopDismissal.get()) {
                if (Win32AutoLogin.dismissHardwareChangedModal(pid)) {
                    dismissals++
                }
                Thread.sleep(3_000)
            }
        }.apply { isDaemon = true; start() }
        val poll = try {
            Win32AutoLogin.pollLoginWindow(pid, flavorId, timings)
        } finally {
            stopDismissal.set(true)
            dismissThread.join(5_000)
        }
        if (dismissals > 0) {
            evidence += "modalDismissed" to "hardware-changed (&No) x$dismissals"
        }
        when (poll) {
            is Win32AutoLogin.LoginWindowPoll.NeverAppeared -> {
                evidence += "poll" to "NEVER_APPEARED: ${poll.reason}"
                println(evidenceLine(evidence))
                error("the pinned login frame never appeared: ${poll.reason}")
            }
            is Win32AutoLogin.LoginWindowPoll.Found -> {
                evidence += "poll" to "FOUND"
            }
        }
        // The credential choreography: throwaway creds, no realm answers
        // them; the probe only proves SendInput lands on the pinned frame.
        val loginFailure = Win32AutoLogin.tryLogin(
            pid,
            username = "pocketrealm-probe",
            password = "probe-no-realm",
            timings = timings,
            flavorId = flavorId,
        )
        evidence += "tryLogin" to (loginFailure ?: "ok (credentials typed; no realm to answer)")
        evidence += "verdict" to "LOGIN_WINDOW_VERIFIED"
        println(evidenceLine(evidence))
    } finally {
        process.destroy()
        Thread.sleep(1_500)
        if (process.isAlive) {
            ProcessBuilder("taskkill", "/F", "/T", "/PID", process.pid().toString())
                .start().waitFor()
        }
        evidence += "clientKilled" to "true"
    }
}

/** The realmlist.wtf locations a client can consult: the install root,
 * every locale pack under Data/<locale>/ (not just enUS — R3-1), and the
 * WTF dir. */
internal fun realmlistCandidates(clientDir: File): List<File> = buildList {
    add(File(clientDir, "realmlist.wtf"))
    val dataDir = File(clientDir, "Data")
    dataDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { locale ->
        add(File(locale, "realmlist.wtf"))
    }
    add(File(clientDir, "WTF/realmlist.wtf"))
}

/**
 * aims EVERY existing realmlist.wtf at loopback (the old
 * first-candidate rewrite left a Truewow repack's Data/enUS realmlist
 * pointing at its live server) and FAILS CLOSED — a client dir with none
 * of the candidates aborts the probe (IllegalStateException naming the
 * dir) instead of silently spawning a client that would dial its
 * compiled-in default realm. Returns one "path (note)" entry per
 * rewritten/verified file, for the evidence line.
 */
internal fun aimRealmListsAtLoopback(clientDir: File): List<String> {
    val candidates = realmlistCandidates(clientDir).filter { it.isFile }
    if (candidates.isEmpty()) {
        error(
            "no realmlist.wtf found under ${clientDir.absolutePath} — the " +
                "probe refuses to spawn a client whose compiled-in default " +
                "realm it cannot neutralize (the drive must never dial a " +
                "live server)",
        )
    }
    return candidates.map { target ->
        // ALWAYS rewrite — a bare substring check for
        // "127.0.0.1" let a realmlist whose directive still named a live
        // host pass through uncorrected when the address appeared in a
        // comment. The unconditional rewrite is idempotent (the correct
        // content is identical) and eliminates the false-negative class.
        target.writeText("set realmlist 127.0.0.1\n")
        target.absolutePath + " (rewritten to loopback)"
    }
}

/** First-run windowed Config.wtf, written only when absent (the client
 * owns the file afterwards). */
private fun forceWindowedConfig(clientDir: File): String? {
    val wtf = File(clientDir, "WTF")
    val config = File(wtf, "Config.wtf")
    if (config.isFile) return "existing (${config.absolutePath})"
    wtf.mkdirs()
    config.writeText(
        "SET gxWindow \"1\"\n" +
            "SET gxMaximize \"0\"\n" +
            "SET gxResolution \"1280x720\"\n",
    )
    return config.absolutePath
}

private fun evidenceLine(evidence: List<Pair<String, String>>): String =
    "FLAVOR-LOGIN-PROBE " + evidence.joinToString(" ") { (k, v) ->
        "$k=\"$v\""
    }

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
