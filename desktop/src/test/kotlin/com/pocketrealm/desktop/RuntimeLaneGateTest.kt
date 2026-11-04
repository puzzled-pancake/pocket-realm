package com.pocketrealm.desktop

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import java.io.File
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * the world-start runtime-lane identity gate —
 * a non-vanilla boot passes ONLY when that lane's own runtime is present
 * (the dev -PrpFlavor lane root); a vanilla boot always passes; and the
 * refusal (runtime absent) names both lanes and the staging remedy.
 * presence is not identity — the staged DLL's
 * bytes must match the lane lockfile's artifact pin (size + sha256), so a
 * stale or partial rebuild refuses loud.
 */
class RuntimeLaneGateTest {
    @get:Rule
    val folder = TemporaryFolder()

    @After
    fun restoreTheFlavorMachine() {
        ActiveFlavor.reset()
    }

    private fun backend(): DesktopRuntimeBackend =
        DesktopRuntimeBackend(DesktopStorageRoots(folder.newFolder("roots")))

    /** The per-lane runtime DLL the gate searches for (the dev -PrpFlavor
     * lane root). Present on dev machines that built the lane; absent on a
     * clean checkout or the packaged image. */
    private fun laneRuntimeDll(lane: String): File = File(
        File(System.getProperty("user.dir")).parentFile,
        "native/.build-win-x86_64-$lane-mariadb/pocket-runtime-build/pocket_world_runtime.dll",
    )

    @Test
    fun sqliteProviderOnATbcLaneRefusesWithTheLaneScopeCopy() {
        // the SQLite provider ships only the
        // vanilla classic* datadir — a tbc pick on SQLite must refuse loud
        // naming the MariaDB remedy, not die late on 'tbcrealmd is not
        // seeded'.
        val roots = DesktopStorageRoots(folder.newFolder("sqlite-tbc-roots"))
        val settingsFile = File(roots.settingsFile.parentFile, "settings.json")
        settingsFile.parentFile.mkdirs()
        settingsFile.writeText(
            "{" +
                "\"databaseProvider\": \"SQLITE\"," +
                "\"selectedFlavorId\": \"tbc\"" +
                "}",
        )
        val backend = DesktopRuntimeBackend(roots)
        val failure = assertThrows(IllegalStateException::class.java) {
            backend.requireSqliteLaneMatchesProvider()
        }
        val message = failure.message ?: ""
        assertTrue(
            "the refusal names the scope + the MariaDB remedy: $message",
            "SQLITE-LANE-SCOPE" in message && "MariaDB" in message,
        )
    }

    @Test
    fun vanillaBootAlwaysPassesTheGate() {
        val backend = backend()
        backend.requireRuntimeLaneMatches(ClientFlavors.VANILLA)
    }

    @Test
    fun tbcBootWithThePerLaneRuntimePresentPassesTheGate() {
        org.junit.Assume.assumeTrue(laneRuntimeDll("tbc").isFile)
        // this pass now includes the sha256 check against the
        // committed tbc lane lockfile pin — the staged bytes must BE the
        // pinned bytes, not merely sit at the lane path.
        backend().requireRuntimeLaneMatches(ClientFlavors.TBC)
    }

    @Test
    fun wotlkBootWithThePerLaneRuntimePresentPassesTheGate() {
        org.junit.Assume.assumeTrue(laneRuntimeDll("wotlk").isFile)
        backend().requireRuntimeLaneMatches(ClientFlavors.WOTLK)
    }

    @Test
    fun theGateRefusalNamesBothLanesAndTheRemedy() {
        // Shape pin: the refusal is asserted against a synthetic base whose
        // runtime is ABSENT, by pointing user.dir at a temp folder (and
        // restoring it) — the roots walk then finds no runtime either.
        val originalUserDir = System.getProperty("user.dir")
        val fakeRepo = folder.newFolder("fake-repo")
        try {
            System.setProperty("user.dir", fakeRepo.absolutePath)
            val backend = backend()
            val failure = assertThrows(IllegalStateException::class.java) {
                backend.requireRuntimeLaneMatches(ClientFlavors.TBC)
            }
            val message = failure.message ?: ""
            assertTrue(
                "the refusal names both lanes and the remedy: $message",
                "vanilla" in message && "tbc" in message &&
                    "RUNTIME-LANE MISMATCH" in message &&
                    "build_win_realm_runtime.py" in message,
            )
        } finally {
            System.setProperty("user.dir", originalUserDir)
        }
    }

    /** a staged DLL whose bytes disagree with the lane lockfile pin
     * refuses loud (a stale rebuild cannot boot unnoticed). */
    @Test
    fun aStagedDllDisagreeingWithTheLanePinRefusesLoud() {
        val fakeRepo = folder.newFolder("stale-lane-repo")
        val runtimeRoot =
            "native/.build-win-x86_64-tbc-mariadb/pocket-runtime-build"
        // the gate covers both lane DLLs — stage both
        val realmd = File(fakeRepo, "$runtimeRoot/pocket_realmd_runtime.dll")
        realmd.parentFile.mkdirs()
        realmd.writeBytes(ByteArray(16))
        val realmdSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(realmd.readBytes()).joinToString("") { "%02x".format(it) }
        val dll = File(fakeRepo, "$runtimeRoot/pocket_world_runtime.dll")
        dll.writeBytes(ByteArray(64) { it.toByte() })
        File(fakeRepo, "schemas").mkdirs()
        File(fakeRepo, "schemas/realm-runtime-lockfile-mariadb-win-tbc.json").writeText(
            """{"schema":1,"artifacts":[""" +
                """{"path":"$runtimeRoot/pocket_realmd_runtime.dll",""" +
                """"size":${realmd.length()},"sha256":"$realmdSha"},""" +
                """{"path":"$runtimeRoot/pocket_world_runtime.dll",""" +
                """"size":26275840,"sha256":"18ae206fed520c93deadbeef"}]}""",
        )
        val originalUserDir = System.getProperty("user.dir")
        try {
            System.setProperty("user.dir", fakeRepo.absolutePath)
            val failure = assertThrows(IllegalStateException::class.java) {
                backend().requireRuntimeLaneMatches(ClientFlavors.TBC)
            }
            val message = failure.message ?: ""
            assertTrue(
                "the stale-bytes refusal names the digest disagreement: $message",
                "RUNTIME-LANE MISMATCH" in message && "sha256" in message &&
                    "disagrees with the tbc lane lockfile pin" in message,
            )
        } finally {
            System.setProperty("user.dir", originalUserDir)
        }
    }

    /** a DLL at the lane path with NO lane lockfile beside it refuses
     * loud — presence alone never proved lane identity, and now cannot. */
    @Test
    fun aLaneRuntimeWithoutItsLaneLockfileRefusesLoud() {
        val fakeRepo = folder.newFolder("unpinned-lane-repo")
        val dll = File(
            fakeRepo,
            "native/.build-win-x86_64-wotlk-mariadb/pocket-runtime-build/pocket_world_runtime.dll",
        )
        dll.parentFile.mkdirs()
        dll.writeBytes(ByteArray(8))
        val originalUserDir = System.getProperty("user.dir")
        try {
            System.setProperty("user.dir", fakeRepo.absolutePath)
            val failure = assertThrows(IllegalStateException::class.java) {
                backend().requireRuntimeLaneMatches(ClientFlavors.WOTLK)
            }
            val message = failure.message ?: ""
            assertTrue(
                "the unpinned refusal names the runtime-lane mismatch remedy: $message",
                "RUNTIME-LANE MISMATCH" in message && "build_win_realm_runtime.py" in message,
            )
        } finally {
            System.setProperty("user.dir", originalUserDir)
        }
    }

    /** the gate covers BOTH lane DLLs — a staged realmd DLL
     * whose bytes disagree with the lane lockfile's realmd row refuses
     * exactly like a stale world DLL (a partial rebuild cannot boot the
     * realmd half unnoticed). */
    @Test
    fun aStagedRealmdDllDisagreeingWithTheLanePinRefusesLoud() {
        val fakeRepo = folder.newFolder("stale-realmd-repo")
        val runtimeRoot =
            "native/.build-win-x86_64-tbc-mariadb/pocket-runtime-build"
        // the WORLD dll matches its pin; only the REALMD dll is stale —
        // the gate must still refuse, naming the realmd artifact.
        val world = File(fakeRepo, "$runtimeRoot/pocket_world_runtime.dll")
        world.parentFile.mkdirs()
        world.writeBytes(ByteArray(64) { it.toByte() })
        val realmd = File(fakeRepo, "$runtimeRoot/pocket_realmd_runtime.dll")
        realmd.writeBytes(ByteArray(32) { (it + 1).toByte() })
        File(fakeRepo, "schemas").mkdirs()
        val worldSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(world.readBytes()).joinToString("") { "%02x".format(it) }
        File(fakeRepo, "schemas/realm-runtime-lockfile-mariadb-win-tbc.json").writeText(
            """{"schema":1,"artifacts":[""" +
                """{"path":"$runtimeRoot/pocket_realmd_runtime.dll",""" +
                """"size":2621440,"sha256":"1099fb4c61899dd6deadbeef"},""" +
                """{"path":"$runtimeRoot/pocket_world_runtime.dll",""" +
                """"size":${world.length()},"sha256":"$worldSha"}]}""",
        )
        val originalUserDir = System.getProperty("user.dir")
        try {
            System.setProperty("user.dir", fakeRepo.absolutePath)
            val failure = assertThrows(IllegalStateException::class.java) {
                backend().requireRuntimeLaneMatches(ClientFlavors.TBC)
            }
            val message = failure.message ?: ""
            assertTrue(
                "the refusal names the REALMD artifact: $message",
                "RUNTIME-LANE MISMATCH" in message &&
                    "pocket_realmd_runtime.dll" in message &&
                    "disagrees with the tbc lane lockfile pin" in message,
            )
        } finally {
            System.setProperty("user.dir", originalUserDir)
        }
    }

    /** a lockfile with no pin row at the DLL's path refuses loud. */
    @Test
    fun aLaneLockfileWithoutTheRuntimePinRowRefusesLoud() {
        val fakeRepo = folder.newFolder("pinless-lockfile-repo")
        val runtimeRoot =
            "native/.build-win-x86_64-tbc-mariadb/pocket-runtime-build"
        val dll = File(fakeRepo, "$runtimeRoot/pocket_world_runtime.dll")
        dll.parentFile.mkdirs()
        dll.writeBytes(ByteArray(8))
        File(fakeRepo, "$runtimeRoot/pocket_realmd_runtime.dll").writeBytes(ByteArray(8))
        File(fakeRepo, "schemas").mkdirs()
        File(fakeRepo, "schemas/realm-runtime-lockfile-mariadb-win-tbc.json").writeText(
            """{"schema":1,"artifacts":[]}""",
        )
        val originalUserDir = System.getProperty("user.dir")
        try {
            System.setProperty("user.dir", fakeRepo.absolutePath)
            val failure = assertThrows(IllegalStateException::class.java) {
                backend().requireRuntimeLaneMatches(ClientFlavors.TBC)
            }
            val message = failure.message ?: ""
            assertTrue(
                "the missing-pin refusal names the lane lockfile: $message",
                "RUNTIME-LANE MISMATCH" in message &&
                    "pins no artifact at" in message,
            )
        } finally {
            System.setProperty("user.dir", originalUserDir)
        }
    }
}
