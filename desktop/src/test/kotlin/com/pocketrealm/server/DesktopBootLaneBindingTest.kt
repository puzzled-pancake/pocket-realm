package com.pocketrealm.server

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.TwoQualifiedFlavors
import com.pocketrealm.desktop.DesktopLlmModelManager
import com.pocketrealm.desktop.DesktopStorageRoots
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.RealmEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * R1-r2-1 (desktop-twin round 2): the desktop twin never received the
 * android lane's boot-lane binding (D62's persisted-state resolution +
 * D63 R2-2m's start-pinned boot lane) — every per-flavor resolution
 * resolved the LIVE ActiveFlavor machine at call time, so at the W4 flip
 * a Home switch between world start and provisioning re-laned the
 * expansion write onto the boot lane's world database, and a switch
 * mid-start could tear the conf's database names vs the prepared-data
 * generation across lanes in ONE start.
 *
 * The behavioral half drives the resolver + database-name seam with the
 * two-qualified registry installed and a mid-start machine switch: the
 * boot lane (resolved ONCE from the persisted pick) keeps binding the
 * expansion and the world/realmd conf names while the live machine moves.
 * The wiring half (the backend's @Volatile bootLane pin/clear + the four
 * provision sites + the conf lanes) is a source contract — the real
 * backend needs the embedded world, so it is not JVM-drivable (the
 * established posture for this seam).
 */
class DesktopBootLaneBindingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun files(): ServerRuntimeFiles {
        val roots = DesktopStorageRoots(tmp.newFolder("roots"))
        return ServerRuntimeFiles(
            roots,
            llmModelManager = DesktopLlmModelManager(roots),
        )
    }

    private fun snapshotWith(pick: String?): Settings.Snapshot =
        Settings.Snapshot(selectedFlavorId = pick ?: "")

    @Test
    fun thePersistedPickBindsTheStartLaneAgainstAMidStartSwitch() {
        TwoQualifiedFlavors.install()
        try {
            val files = files()
            val persisted = snapshotWith("tbc")
            // start pins the lane from the PERSISTED pick (no live machine
            // selection exists at boot)
            val bootLane = files.bootLaneFlavor(persisted)
            assertEquals("tbc", bootLane.flavorId)
            // a mid-start Home switch moves the LIVE machine...
            ActiveFlavor.select("vanilla")
            assertEquals("vanilla", ActiveFlavor.resolve("live machine").flavorId)
            // ...but the pinned boot lane still answers, and the conf's
            // database names still bind to it
            val stillBoot = files.bootLaneFlavor(persisted)
            assertEquals("tbc", stillBoot.flavorId)
            assertEquals(
                "the conf must name the BOOT lane's databases",
                "tbcmangos", ServerRuntimeFiles.serverDatabases(stillBoot).world,
            )
            assertEquals(
                "the live machine moved (the tear the binding prevents)",
                "classicmangos", ServerRuntimeFiles.serverDatabases().world,
            )
        } finally {
            ActiveFlavor.reset()
        }
    }

    @Test
    fun anUnselectedTwoQualifiedStartRefusesLoudInsteadOfGuessing() {
        TwoQualifiedFlavors.install()
        try {
            val files = files()
            val thrown = runCatching {
                files.bootLaneFlavor(snapshotWith(null))
            }.exceptionOrNull()
            assertNotNull(
                "an unselected persisted pick at a two-qualified flip must refuse",
                thrown,
            )
        } finally {
            ActiveFlavor.reset()
        }
    }

    @Test
    fun theSoleQualifiedLaneStillDegradesWithoutAnyPick() {
        ActiveFlavor.reset() // the real registry: vanilla-only
        try {
            val files = files()
            assertEquals(
                "vanilla",
                files.bootLaneFlavor(snapshotWith(null)).flavorId,
            )
        } finally {
            ActiveFlavor.reset()
        }
    }

    @Test
    fun theBackendPinsTheBootLaneAndTheProvisionSeamsConsumeIt() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "schemas/flavor.json").isFile }
            ?: error("repo root not found")
        val backend = File(
            root,
            "desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackend.kt",
        ).readText()
        assertTrue(
            "start() must pin the boot lane BEFORE any component leg (the " +
                "projection and both conf writers read the pin)",
            "_bootLane.value = files.bootLaneFlavor(startSnapshot)" in backend,
        )
        assertTrue(
            "the pin must ride the start transition's own snapshot",
            "bootLaneSnapshot = startSnapshot" in backend,
        )
        assertEquals(
            "every provision seam must consume the pinned boot lane " +
                "(a live-machine resolve re-lanes the write mid-start)",
            4,
            backend.split("(bootLane ?: ServerRuntimeFiles.serverFlavor()).accountExpansion")
                .size - 1,
        )
        assertEquals(
            "no bare live-machine expansion resolve may remain anywhere in " +
                "the backend (R2r3-3's widened enumeration)",
            0,
            backend.split("ServerRuntimeFiles.serverFlavor().accountExpansion")
                .size - 1,
        )
        assertTrue(
            "the boot-lane pin must die with the world it served",
            "_bootLane.value = null" in backend,
        )
        // R1-r3-1: the stage legs (conf emission, overlay desiredStage,
        // lifecycle record) resolve through the PINNED lane — the bare
        // confValue( delegates to the live holder machine.
        assertTrue(
            "the world stage must resolve through confValueFor on the " +
                "pinned boot lane",
            "TimeframeStages.confValueFor(" in backend &&
                "checkNotNull(bootLane)" in backend,
        )
        assertTrue(
            "startWorld must consume the PINNED snapshot (a fresh disk " +
                "load re-opens the mid-window straddle)",
            "bootLaneSnapshot ?: DesktopSettingsStore(roots.settingsFile).load()" in backend,
        )
        // R3-1: the realm leg threads the pinned snapshot into realmdConfig
        assertTrue(
            "the realm leg must thread the pinned snapshot into realmdConfig",
            "files.realmdConfig(bindAddress, bootLaneSnapshot)" in backend,
        )
        // R3-1: the projection legs read the pinned lane
        assertTrue(
            "the projection's build list must read the pinned lane",
            "(bootLane ?: ServerRuntimeFiles.serverFlavor()).realmdAcceptedBuilds" in backend,
        )
        assertEquals(
            "every realmd-name projection leg reads the pinned lane",
            3,
            Regex(Regex.escape("bootLane ?: ServerRuntimeFiles.serverFlavor(),"))
                .findAll(backend).count(),
        )
        val runtimeFiles = File(
            root,
            "desktop/src/main/kotlin/com/pocketrealm/server/ServerRuntimeFiles.kt",
        ).readText()
        assertTrue(
            "worldConfig must resolve ONE lane and bind data + databases to it",
            "worldLaneFlavor(SERVER_RUNTIME_LANE, settingsSnapshot)" in runtimeFiles &&
                "requireActive(lane)" in runtimeFiles &&
                "serverDatabases(lane)" in runtimeFiles,
        )
        assertTrue(
            "realmdConfig must bind the START's threaded snapshot (a fresh " +
                "disk load re-opens the realm/world straddle)",
            "serverDatabases(worldLaneFlavor(SERVER_RUNTIME_LANE, settingsSnapshot))" in runtimeFiles,
        )
        assertTrue(
            "worldConfig's stage emission must bind the pinned lane " +
                "(confValueFor), never the live machine",
            "TimeframeStages.confValueFor(snapshot.timeframeStage, lane.flavorId)" in runtimeFiles,
        )
    }

    @Test
    fun realmdConfigBindsTheThreadedSnapshotAgainstADiskFlip() {
        // R1-r3-3/R2r3-2/R3-1 (round 3): REALM and WORLD are separate
        // component starts with a seconds-wide READY window — the same
        // snapshot threaded into BOTH conf writers must name the SAME
        // database family even while a persisted write lands on disk
        // between the two calls (the straddle the backend's single pin
        // prevents: start() pins once, both legs consume that pin).
        TwoQualifiedFlavors.install()
        try {
            val files = files()
            val bootSnapshot = snapshotWith("tbc")
            val bootConf = files
                .realmdConfig(RealmEndpoint.LOOPBACK_ADDRESS, bootSnapshot)
                .readText()
            // a Home tap persists a different lane between the two legs
            val afterTap = snapshotWith("vanilla")
            val worldConf = files
                .realmdConfig(RealmEndpoint.LOOPBACK_ADDRESS, afterTap)
                .readText()
            val bootNames = ServerRuntimeFiles.serverDatabases(
                files.bootLaneFlavor(bootSnapshot),
            )
            val worldNames = ServerRuntimeFiles.serverDatabases(
                files.bootLaneFlavor(afterTap),
            )
            assertTrue(
                "the REALM conf binds ITS threaded snapshot's lane",
                bootNames.realmd in bootConf,
            )
            assertTrue(
                "the WORLD leg's threaded snapshot names its own lane",
                worldNames.realmd in worldConf,
            )
            // the one-lane property: when the backend pins ONCE and both
            // legs consume that pin, the two confs agree — demonstrated by
            // the same snapshot answering both
            val pinned = files.bootLaneFlavor(bootSnapshot)
            assertEquals(
                bootNames.realmd,
                ServerRuntimeFiles.serverDatabases(pinned).realmd,
            )
        } finally {
            ActiveFlavor.reset()
        }
    }

    @Test
    fun theHarnessSeamsConsumeThePinnedBootLaneToo() {
        // R2r3-3/R1-r3-5 (round 3): the RpHost harness's account op and
        // AUTH_SESSION build resolve the pinned boot lane (the fifth
        // provision seam + the auth frames), never the live holder machine
        // a headless harness never populates.
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "schemas/flavor.json").isFile }
            ?: error("repo root not found")
        val host = File(
            root,
            "desktop/src/main/kotlin/com/pocketrealm/desktop/RpHost.kt",
        ).readText()
        assertTrue(
            "the harness account op must consume the pinned boot lane",
            "(backend.bootLane ?: ServerRuntimeFiles.serverFlavor()).accountExpansion" in host,
        )
        assertTrue(
            "the harness AUTH_SESSION must thread the pinned lane's build",
            "authBuild = (backend.bootLane" in host,
        )
        val client = File(
            root,
            "desktop/src/main/kotlin/com/pocketrealm/desktop/RpWowClient.kt",
        ).readText()
        assertTrue(
            "the client's auth build must come from the threaded lane",
            "private val authBuild: Int" in client &&
                "o = writeLe32(body, o, authBuild)" in client,
        )
        val gate = File(
            root,
            "desktop/src/main/kotlin/com/pocketrealm/desktop/AuthGate.kt",
        ).readText()
        assertTrue(
            "the verifier seeding must route provider-aware (the MariaDB " +
                "lane through the engine's owner-gated SQL)",
            "backend.isMariadbLane()" in gate &&
                "seedSrpVerifierRow(account, srp.vHex, srp.sHex)" in gate,
        )
        val engine = File(
            root,
            "desktop/src/main/kotlin/com/pocketrealm/database/DesktopMariaDbEngine.kt",
        ).readText()
        assertTrue(
            "the desktop engine must carry the android realmdDatabaseName " +
                "guard's twin (the machine's fail-loud resolution, then the " +
                "W1.2 vanilla-only requirement before any SQL crosses) — and " +
                "corrects D63's 'verified in place' claim",
            "com.pocketrealm.server.ServerRuntimeFiles.serverFlavor()" in engine &&
                "the realmlist projection resolves only the vanilla lane (the " in engine,
        )
    }
}
