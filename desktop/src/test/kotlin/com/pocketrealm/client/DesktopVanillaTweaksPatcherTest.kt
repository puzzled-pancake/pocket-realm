package com.pocketrealm.client

import com.pocketrealm.fs.FileDigests
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * the desktop vanilla-tweaks patch lane. The pipeline runs
 * the REAL staged, lockfile-pinned patcher (skipped honestly where the
 * staging is absent) against a synthetic PE32-i386 image and pins the
 * published `WoW.exe.patched` byte for byte against
 * [ClientTweaksConfig.expectedPublishedPatchedBytes] — the Android prepare
 * contract's authorization core, host-side.
 */
class DesktopVanillaTweaksPatcherTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** The nearby-loot gate's unique 24-byte original (offset 0x1EB944). */
    private val gateSignature = byteArrayOf(
        0x0B, 0xC1.toByte(), 0x8A.toByte(), 0x45, 0xFE.toByte(), 0x75, 0x18,
        0x3C, 0x02, 0x0F, 0x84.toByte(), 0xA1.toByte(), 0x00, 0x00, 0x00,
        0x3C, 0x03, 0x0F, 0x84.toByte(), 0x99.toByte(), 0x00, 0x00, 0x00,
        0x3C, 0x04,
    )

    /** A synthetic PE32-i386 image that passes the patcher's own
     * validation: MZ magic, e_lfanew -> "PE\0\0", machine 0x014c, size
     * above the highest patched offset (0x467958+4), gate at 0x1EB944. */
    private fun syntheticQualifiedImage(): ByteArray {
        val size = 0x500_000
        val image = ByteArray(size) { index -> ((index * 37 + 11) and 0xff).toByte() }
        image[0] = 'M'.code.toByte()
        image[1] = 'Z'.code.toByte()
        val peOffset = 0x80
        image[0x3c] = peOffset.toByte()
        image[0x3d] = 0
        image[0x3e] = 0
        image[0x3f] = 0
        image[peOffset] = 'P'.code.toByte()
        image[peOffset + 1] = 'E'.code.toByte()
        image[peOffset + 2] = 0
        image[peOffset + 3] = 0
        image[peOffset + 4] = 0x4c // machine 0x014c little-endian low byte
        image[peOffset + 5] = 0x01
        gateSignature.copyInto(image, 0x1EB944)
        return image
    }

    private fun stageClientDir(): Pair<File, ByteArray> {
        val dir = folder.newFolder("client")
        val image = syntheticQualifiedImage()
        File(dir, "WoW.exe").writeBytes(image)
        return dir to image
    }

    // authorization semantics ----------------

    @Test
    fun unpatchedRequestNeverTouchesTheClientDirectory() {
        val (dir, pristine) = stageClientDir()
        val before = File(dir, "WoW.exe").readBytes()
        val result = DesktopVanillaTweaksPatcher.publishForLaunch(dir, ClientTweaksConfig())
        assertFalse(result.patched)
        assertEquals("WoW.exe", result.executable.name)
        assertEquals("no patches requested", result.detail)
        assertTrue(before.contentEquals(File(dir, "WoW.exe").readBytes()))
        assertFalse(File(dir, "WoW.exe.patched").exists())
        assertTrue(pristine.isNotEmpty())
    }

    @Test
    fun wrongLayoutFallsBackToPristineVanillaAndKeepsTheRequest() {
        val (dir, _) = stageClientDir()
        val requested = ClientTweaksConfig.commonPreset()
        val sha = FileDigests.sha256(File(dir, "WoW.exe"))
        val resolution = resolveEffectiveClientTweaks(requested, sha)
        // The synthetic image is NOT the authorized build-5875 layout: the
        // request stays pristine-Vanilla for this launch (fallback), exactly
        // the Android contract that keeps the user's Settings intact.
        assertTrue(resolution.fallback)
        assertEquals(ClientTweaksConfig(), resolution.config)
        assertFalse(resolution.config.hasAnyPatch())
        val result = DesktopVanillaTweaksPatcher.publishForLaunch(dir, requested)
        assertFalse("unauthorized layout must not patch: ${result.detail}", result.patched)
        assertEquals("WoW.exe", result.executable.name)
        assertFalse(File(dir, "WoW.exe.patched").exists())
    }

    @Test
    fun authorizedShaResolvesTheRequestUntouched() {
        val requested = ClientTweaksConfig.commonPreset()
        val resolution = resolveEffectiveClientTweaks(
            requested,
            ClientTweaksConfig.AUTHORIZED_CLIENT_SHA256.uppercase(),
        )
        assertFalse(resolution.fallback)
        assertEquals(requested, resolution.config)
    }

    /**
     * the world conf's nearby-use knob verdict follows the
     * AUTHORIZED-layout EFFECTIVE resolution of the requested tweaks over
     * the user's actual client executable — requested consent alone is not
     * enough (a non-qualified layout launches unpatched, so the server must
     * not promise nearby-use), and no resolvable exe fails closed.
     */
    @Test
    fun nearbyUseKnobFollowsTheEffectiveLayoutResolution() {
        val requested = ClientTweaksConfig.commonPreset()
        assertTrue(
            nearbyUseEnabledForExecutable(requested, ClientTweaksConfig.AUTHORIZED_CLIENT_SHA256),
        )
        // Case-insensitive hash equality (the acceptsExecutableForLaunch posture).
        assertTrue(
            nearbyUseEnabledForExecutable(
                requested,
                ClientTweaksConfig.AUTHORIZED_CLIENT_SHA256.uppercase(),
            ),
        )
        // THE regression pin: the same consented request on a non-qualified
        // layout keeps the knob at 0.
        assertFalse(nearbyUseEnabledForExecutable(requested, "0".repeat(64)))
        // A pristine request never turns the knob on, authorized or not.
        assertFalse(
            nearbyUseEnabledForExecutable(
                ClientTweaksConfig(),
                ClientTweaksConfig.AUTHORIZED_CLIENT_SHA256,
            ),
        )
        // No resolvable executable hash fails closed.
        assertFalse(nearbyUseEnabledForExecutable(requested, null))
    }

    // lockfile pin ----------------

    @Test
    fun stagedPatcherIsPresentAndMatchesItsLockfilePin() {
        val binary = DesktopVanillaTweaksPatcher.stagedPatcherBinary()
        assumeTrue(
            "staged vanilla-tweaks.exe absent (the lockfile-pinned staging is a " +
                "repo build artifact)",
            binary != null,
        )
        val lockfile = DesktopVanillaTweaksPatcher.stagedLockfile()
        assumeTrue("lockfile absent", lockfile != null)
        // Throws on any drift: size or sha256 off the pin fails loud.
        DesktopVanillaTweaksPatcher.checkLockfilePin(binary!!)
        assertEquals(799_232L, binary.length())
    }

    // ---------------- the patcher process budget (the twin's contract) ----

    private val onWindows =
        System.getProperty("os.name").lowercase().contains("windows")

    /** One `cmd /c <script>` (Windows) or `/bin/sh -c <script>` (elsewhere). */
    private fun shell(script: String): List<String> =
        if (onWindows) listOf("cmd.exe", "/c", script) else listOf("/bin/sh", "-c", script)

    /** A single-process command that hangs far past any test budget. */
    private fun hangCommand(): List<String> =
        if (onWindows) {
            listOf("ping", "-n", "9999", "-w", "1000", "127.0.0.1")
        } else {
            listOf("sleep", "9999")
        }

    @Test(timeout = 60_000L)
    fun hungPatcherIsKilledAtItsBudgetAndRefusedHonestly() {
        val startedAt = System.currentTimeMillis()
        try {
            DesktopVanillaTweaksPatcher.runCheckedProcess(
                hangCommand(),
                label = "test-hang",
                timeoutSeconds = 3,
            )
            fail("a hung child must be refused, not awaited")
        } catch (expected: IllegalStateException) {
            assertTrue("unexpected refusal: ${expected.message}", "timed out" in expected.message!!)
        }
        // THE pin: the refusal lands at the budget (seconds), not at the
        // child's own 9999-iteration horizon — the unbounded readText() +
        // waitFor() this replaces hung the launch forever.
        val elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000.0
        assertTrue("refusal took ${elapsedSeconds}s", elapsedSeconds < 30)
    }

    @Test(timeout = 60_000L)
    fun chattyPatcherOutputIsDrainedUnderCapAndNonzeroExitsRefuse() {
        // ~620KB of merged output, well past the OS pipe buffer: only a
        // drain-while-waiting reader lets the child finish inside budget.
        val chatter = DesktopVanillaTweaksPatcher.runCheckedProcess(
            shell(
                if (onWindows) {
                    "for /l %i in (1,1,20000) do @echo 012345678901234567890123456789"
                } else {
                    "for i in $(seq 20000); do echo 012345678901234567890123456789; done"
                },
            ),
            label = "test-chatty",
            timeoutSeconds = 60,
        )
        assertTrue("retained output must be capped: ${chatter.length}", chatter.length <= 2_000)
        assertTrue("retained output must be nonempty", chatter.isNotEmpty())
        try {
            DesktopVanillaTweaksPatcher.runCheckedProcess(
                shell("exit 3"),
                label = "test-rc",
                timeoutSeconds = 30,
            )
            fail("a nonzero exit must be refused")
        } catch (expected: IllegalStateException) {
            assertTrue("unexpected refusal: ${expected.message}", "rc=3" in expected.message!!)
        }
    }

    // the full publish pipeline ----------------

    @Test
    fun publishPipelineProducesTheModelVerifiedPatchedImage() {
        val binary = DesktopVanillaTweaksPatcher.stagedPatcherBinary()
        assumeTrue("staged vanilla-tweaks.exe absent", binary != null)
        DesktopVanillaTweaksPatcher.checkLockfilePin(binary!!)
        val (dir, pristine) = stageClientDir()
        val pristineSha = FileDigests.sha256(File(dir, "WoW.exe"))
        val effective = ClientTweaksConfig.commonPreset()
        val result = DesktopVanillaTweaksPatcher.publishVerified(dir, pristineSha, effective)
        assertTrue(result.detail, result.patched)
        val patched = File(dir, "WoW.exe.patched")
        assertTrue(patched.isFile)
        assertEquals("WoW.exe.patched", result.executable.name)
        // THE pin: the published image equals the independent byte model.
        val expected = ClientTweaksConfig.expectedPublishedPatchedBytes(pristine, effective)
        assertTrue(
            "published bytes must equal expectedPublishedPatchedBytes",
            expected.contentEquals(patched.readBytes()),
        )
        // The pristine executable was never modified.
        assertTrue(pristine.contentEquals(File(dir, "WoW.exe").readBytes()))
        // The authenticated manifest sidecar carries the contract fields.
        val manifest = org.json.JSONObject(
            File(dir, "WoW.exe.patched.manifest.json").readText(),
        )
        assertEquals(1, manifest.getInt("schema"))
        assertEquals(pristineSha, manifest.getString("pristineSha256"))
        assertEquals(FileDigests.sha256(patched), manifest.getString("patchedSha256"))
        assertTrue(manifest.getBoolean("nearbyLootAccept"))
        assertEquals(
            effective,
            ClientTweaksConfig.fromJson(manifest.getJSONObject("config").toString()),
        )
        // The nearby-loot gate rewrite landed (the two companion bytes).
        val body = patched.readBytes()
        assertEquals(0x01, body[0x1EB94C].toInt() and 0xff)
        assertEquals(0x83, body[0x1EB94E].toInt() and 0xff)
    }

    @Test
    fun publishIsIdempotentThroughTheManifestSidecar() {
        val binary = DesktopVanillaTweaksPatcher.stagedPatcherBinary()
        assumeTrue("staged vanilla-tweaks.exe absent", binary != null)
        val (dir, _) = stageClientDir()
        val pristineSha = FileDigests.sha256(File(dir, "WoW.exe"))
        val effective = ClientTweaksConfig.commonPreset()
        DesktopVanillaTweaksPatcher.publishVerified(dir, pristineSha, effective)
        val patched = File(dir, "WoW.exe.patched")
        val firstWrite = patched.readBytes()
        // The second publish takes the verified cache path (same bytes, no
        // patcher run) — the detail names the cache.
        val second = DesktopVanillaTweaksPatcher.publishVerified(dir, pristineSha, effective)
        assertTrue("second publish must hit the cache: ${second.detail}", "cached" in second.detail)
        assertTrue(firstWrite.contentEquals(patched.readBytes()))
    }
}
