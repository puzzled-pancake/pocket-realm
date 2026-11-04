package com.pocketrealm.server

import com.pocketrealm.client.ClientFlavors
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the ANDROID boot-time lane-vs-runtime gate —
 * the desktop requireRuntimeLaneMatches twin. The packaged Android realm
 * runtime is the VANILLA lane's libpocket_world_runtime.so only; after the
 * flip a tbc/wotlk Home pick reachable the world/realmd start path, and
 * WITHOUT this gate startNative loaded vanilla core bytes against the
 * lane's conf+database family. The gate refuses LOUD before any native
 * call, naming the remedy.
 */
class AndroidBootLaneGateTest {
    private fun serverFilesSource(): String {
        val root = generateSequence(File(System.getProperty("user.dir"))) {
            it.parentFile
        }.firstOrNull { File(it, "schemas/flavor.json").isFile }
            ?: error("repo root not found")
        return File(
            root,
            "android/app/src/main/java/com/pocketrealm/server/" +
                "ServerRuntimeFiles.kt",
        ).readText()
    }

    @Test
    fun vanillaBootPassesTheAndroidGate() {
        ServerRuntimeFiles.requireAndroidRuntimeLaneMatches(ClientFlavors.VANILLA)
    }

    @Test
    fun aTbcPickRefusesWithTheActionableCopy() {
        val failure = assertThrows(IllegalStateException::class.java) {
            ServerRuntimeFiles.requireAndroidRuntimeLaneMatches(ClientFlavors.TBC)
        }
        val message = failure.message ?: ""
        assertTrue(
            "the refusal names the mismatch and both lanes: $message",
            "RUNTIME-LANE MISMATCH" in message &&
                "vanilla" in message && "tbc" in message,
        )
        assertTrue(
            "the refusal names the remedy (re-pick Vanilla): $message",
            "Re-pick Vanilla on Home" in message,
        )
    }

    @Test
    fun aWotlkPickRefusesWithTheActionableCopy() {
        val failure = assertThrows(IllegalStateException::class.java) {
            ServerRuntimeFiles.requireAndroidRuntimeLaneMatches(ClientFlavors.WOTLK)
        }
        assertTrue(
            "the wotlk refusal names the mismatch: ${failure.message}",
            "RUNTIME-LANE MISMATCH" in (failure.message ?: ""),
        )
    }

    @Test
    fun bothNativeStartPathsGateBeforeStartNative() {
        // source shape: the world AND realmd services call the gate before
        // their startNative — the wrong-core boot must be unreachable.
        val root = generateSequence(File(System.getProperty("user.dir"))) {
            it.parentFile
        }.firstOrNull { File(it, "schemas/flavor.json").isFile }
            ?: error("repo root not found")
        val world = File(
            root,
            "android/app/src/main/java/com/pocketrealm/server/" +
                "WorldRuntimeService.kt",
        ).readText()
        val realm = File(
            root,
            "android/app/src/main/java/com/pocketrealm/server/" +
                "RealmRuntimeService.kt",
        ).readText()
        val checks = listOf(
            Triple("WorldRuntimeService", world, "WorldNative.startNative"),
            Triple("RealmRuntimeService", realm, "RealmNative.startNative"),
        )
        for ((name, source, native) in checks) {
            val gate = source.indexOf("requireAndroidRuntimeLaneMatches")
            val start = source.indexOf(native)
            assertTrue("$name: the gate call is present", gate >= 0)
            assertTrue("$name: the native start is present", start >= 0)
            assertTrue(
                "$name: the gate must run BEFORE $native",
                gate < start,
            )
        }
    }

    @Test
    fun theAndroidGateIsTheDocumentedVanillaOnlyLaw() {
        // the gate's own law names the packaged-runtime scope
        val source = serverFilesSource()
        assertTrue(
            "the gate documents the vanilla-only Android runtime scope",
            "VANILLA lane" in source,
        )
    }
}
