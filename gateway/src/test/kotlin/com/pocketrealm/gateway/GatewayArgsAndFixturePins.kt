package com.pocketrealm.gateway

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.discovery.iroh.GatewayContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.io.path.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** W6-N4 code-red round-6 batched-minor pins (R2N4R2-2, R3-R1-3):
 *  the malformed-relay args gate refuses with a NON-NULL actionable
 *  detail (never the FFI's null-detail INTERNAL), and the extracted
 *  FakeStack realm-list fixture stays pinned to the owning registry's
 *  wotlk build. */
@ExtendWith(GamePortsGuard::class)
class GatewayArgsAndFixturePins {

    @Test
    fun `a malformed relay url refuses at the args gate with a non-null detail`() {
        val javaBin = Path(
            System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java",
        )
        val process = java.lang.ProcessBuilder(
            javaBin.toString(),
            "-Djna.tmpdir=E:/pocket_realm_toolchain/tmp/jna",
            "-jar", locateFatJar(),
            "--mode", "join",
            "--secret", "aa".repeat(32),
            "--node", "bb".repeat(32),
            "--relay-only",
            "--relay", "not-a-url",
            "--flavor", "wotlk",
            "--max-seconds", "1",
        ).start()
        val output = process.inputStream.bufferedReader().readText()
        val exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(exited, "the gateway must exit on a malformed relay url")
        assertEquals(
            GatewayContract.ExitCode.BAD_ARGS, process.exitValue(),
            "a malformed relay must refuse at the args gate (BAD_ARGS), " +
                "never defer to the FFI's null-detail INTERNAL",
        )
        assertTrue(
            output.contains(GatewayContract.ERR_BAD_ARGS) &&
                output.contains("--relay: join relay must be an https URL"),
            "the refusal must carry the fence's non-null actionable detail: $output",
        )
    }

    @Test
    fun `an https relay with an oversized port refuses at the args gate`() {
        val javaBin = Path(
            System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java",
        )
        val process = java.lang.ProcessBuilder(
            javaBin.toString(),
            "-Djna.tmpdir=E:/pocket_realm_toolchain/tmp/jna",
            "-jar", locateFatJar(),
            "--mode", "join",
            "--secret", "aa".repeat(32),
            "--node", "bb".repeat(32),
            "--relay-only",
            "--relay", "https://relay.example.com:99999/",
            "--flavor", "wotlk",
            "--max-seconds", "1",
        ).start()
        val output = process.inputStream.bufferedReader().readText()
        val exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(exited, "the gateway must exit on an oversized relay port")
        assertEquals(
            GatewayContract.ExitCode.BAD_ARGS, process.exitValue(),
            "an oversized relay port must refuse at the args gate",
        )
    }

    @Test
    fun `the fakestack fixture build matches the owning wotlk registry`() {
        // the extracted fixture's u16 LE build must equal
        // ClientFlavors.WOTLK.build — the fixture cannot silently drift
        // from the registry (the comment must never lie).
        val frame = FakeStackProtocol.fixtureFor(18085)
        val buildOffset = frame.size - 4 // u16 LE build sits 4 bytes from the end
        val buildLe = (frame[buildOffset].toInt() and 0xff) or
            ((frame[buildOffset + 1].toInt() and 0xff) shl 8)
        assertEquals(
            ClientFlavors.WOTLK.build, buildLe,
            "the FakeStack fixture's u16 LE build drifted from the owning registry",
        )
    }
}
