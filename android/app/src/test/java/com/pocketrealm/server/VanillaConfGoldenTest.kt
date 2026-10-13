package com.pocketrealm.server

import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.client.ClientRealmEndpointProjection
import com.pocketrealm.client.TimeframeStages
import com.pocketrealm.importer.ClientGenerationStore
import com.pocketrealm.ingame.ConfigWtfCodec
import com.pocketrealm.ingame.ManagedConfigPolicy
import com.pocketrealm.supervisor.RealmEndpoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.pocketrealm.client.ActiveFlavor
import org.junit.After
import org.junit.Before

/**
 * G3-7: the vanilla lane's generated conf bytes are frozen. Every surface the
 * app writes for a vanilla device — `realmd.conf`, `mangosd.conf`,
 * `aiplayerbot-disabled.conf`, a per-profile `aiplayerbot-<id>.conf` body, the
 * publish-time `WTF/Config.wtf` (SAFE_CONFIG) and `realmlist.wtf`, the Wine
 * realmlist projection, and the ManagedConfigPolicy enforced-lines
 * serialization — is byte-compared (raw `ByteArray`, never normalized text)
 * against the committed current-release fixture under
 * `src/test/resources/fixtures/g3/conf/`. Failures print a unified diff.
 *
 * This is the standing acceptance gate every conf-touching stage (S2's
 * realmd.conf divergence work, S3's aiplayerbot emission, S7's Config.wtf
 * rework, F3-3/H2-5's serverDatabases threading) must keep green: a template
 * reformat, a reordered key, or a stray newline that would silently change
 * what existing vanilla devices run fails here first.
 *
 * The fixture inputs below are fixed, device-independent stand-ins for the
 * runtime values (database connection strings, absolute paths): they are part
 * of the pinned contract, so changing one deliberately rotates the fixtures.
 * The one value that IS runtime-true in the fixtures is the vanilla database
 * NAME set (classicrealmd/classicmangos/classiccharacters/classiclogs), which
 * pins [ServerRuntimeFiles.serverDatabases]' vanilla resolution by transit.
 *
 * The on-device legs (adb pull of the `server/run` conf files, byte-compared
 * after a vanilla boot) run in the device lane and are deferred here.
 */
class VanillaConfGoldenTest {
    /** W4 flip fixture: the golden conf resolves the ACTIVE lane's
     * stage through the holder — pin the VANILLA lane. */
    @Before
    fun pinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After
    fun unpinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
    }


    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/fixtures/g3/conf/$name")!!.readBytes()

    private fun assertGolden(name: String, actual: ByteArray) {
        try {
            assertArrayEquals(fixture(name), actual)
        } catch (failure: AssertionError) {
            throw AssertionError(
                "vanilla conf golden drifted: $name\n" +
                    unifiedDiff(
                        String(fixture(name), Charsets.UTF_8),
                        String(actual, Charsets.UTF_8),
                    ),
                failure,
            )
        }
    }

    @Test fun `realmd conf matches the golden bytes`() {
        assertGolden(
            "realmd.conf",
            ServerRuntimeFiles.realmdConfText(
                loginDatabaseInfo = FIXTURE_LOGIN_DB_INFO,
                bindAddress = "127.0.0.1",
                realmLogFilePath = "$FIXTURE_LOGS/realmd.log",
            ).toByteArray(),
        )
    }

    /** design-remote-accounts §4.3/§6.3 (the desktop ServerRuntimeFiles D11
     * twin): a non-loopback LAN bind appends the WrongPass lockout keys to
     * the conf; the loopback golden above pins the omission. */
    @Test fun `realmd conf on a LAN bind emits the wrong pass lockout`() {
        val lan = ServerRuntimeFiles.realmdConfText(
            loginDatabaseInfo = FIXTURE_LOGIN_DB_INFO,
            bindAddress = "192.168.1.50",
            realmLogFilePath = "$FIXTURE_LOGS/realmd.log",
        )
        assertTrue("WrongPass.MaxCount = 10" in lan)
        assertTrue("WrongPass.BanTime = 900" in lan)
        assertTrue("WrongPass.BanType = 0" in lan)
        assertTrue("the lockout rides after the base conf body", lan.trimEnd().endsWith("WrongPass.BanType = 0"))
        // The loopback form stays byte-identical to the golden: no keys.
        val loopback = ServerRuntimeFiles.realmdConfText(
            loginDatabaseInfo = FIXTURE_LOGIN_DB_INFO,
            bindAddress = "127.0.0.1",
            realmLogFilePath = "$FIXTURE_LOGS/realmd.log",
        )
        assertTrue("WrongPass." !in loopback)
    }

    @Test fun `mangosd conf matches the golden bytes`() {
        assertGolden(
            "mangosd.conf",
            ServerRuntimeFiles.mangosdConfText(
                dataDirectoryAbsolutePath = FIXTURE_DATA_DIR,
                loginDatabaseInfo = FIXTURE_LOGIN_DB_INFO,
                worldDatabaseInfo = FIXTURE_WORLD_DB_INFO,
                characterDatabaseInfo = FIXTURE_CHAR_DB_INFO,
                logsDatabaseInfo = FIXTURE_LOGS_DB_INFO,
                bindAddress = "127.0.0.1",
                normalPlay = true,
                worldLogFilePath = "$FIXTURE_LOGS/world.log",
                worldLogFileLevel = ServerRuntimeFiles.DEFAULT_WORLD_LOG_FILE_LEVEL,
                databaseErrorsLogFilePath = "$FIXTURE_LOGS/database-errors.log",
                nearbyInteractCooldownMs =
                    NearbyInteractPolicy.normalizeTriggerGuardMs(NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS),
                playerbotConfigPath = "$FIXTURE_RUN/aiplayerbot-disabled.conf",
                botTarget = 0,
                // Design-timeframe §4.2: the golden conf carries the DEFAULT
                // stage (the qualified flavor's final patch) — the fixture
                // rotated deliberately when the contract line landed, exactly
                // like every other conf-touching stage.
                timeframeStage = TimeframeStages.confValue(""),
            ).toByteArray(),
        )
    }

    @Test fun `aiplayerbot disabled conf matches the golden bytes`() {
        assertGolden("aiplayerbot-disabled.conf", ServerRuntimeFiles.aiplayerbotDisabledConfText().toByteArray())
    }

    @Test fun `per profile aiplayerbot conf body matches the golden bytes`() {
        assertGolden(
            "aiplayerbot-mobile-low-b1-25-v1.conf",
            BotProfiles.LOW_25.playerbotConfig(com.pocketrealm.client.ClientFlavors.VANILLA).toByteArray(),
        )
    }

    @Test fun `publish time WTF Config wtf matches the golden bytes`() {
        assertGolden("Config.wtf", ClientGenerationStore.managedConfigWtfBytes())
    }

    @Test fun `publish time realmlist wtf matches the golden bytes`() {
        assertGolden("realmlist.wtf", ClientGenerationStore.managedRealmlistWtfBytes())
    }

    @Test fun `wine realmlist projection matches the golden bytes`() {
        assertGolden(
            "realmlist-projection.txt",
            ClientRealmEndpointProjection.text(RealmEndpoint.LOCAL).toByteArray(),
        )
    }

    @Test fun `managed config policy enforced lines match the golden serialization`() {
        assertGolden(
            "managed-config-enforced.conf",
            ConfigWtfCodec.merge(
                base = "",
                enforced = ManagedConfigPolicy.enforcedKeys(FIXTURE_CONDITIONS),
            ).text.toByteArray(),
        )
    }

    @Test fun `vanilla safe config never gains donor machine topology keys`() {
        val config = String(ClientGenerationStore.managedConfigWtfBytes(), Charsets.US_ASCII)
        listOf("processAffinityMask", "coresDetected", "hwDetect").forEach { key ->
            assertEquals("vanilla Config.wtf must not seed $key", -1, config.indexOf(key))
        }
    }

    private fun unifiedDiff(expected: String, actual: String): String {
        val expectedLines = expected.split("\n")
        val actualLines = actual.split("\n")
        val body = buildString {
            val common = minOf(expectedLines.size, actualLines.size)
            for (index in 0 until common) {
                if (expectedLines[index] != actualLines[index]) {
                    appendLine("- ${expectedLines[index]}")
                    appendLine("+ ${actualLines[index]}")
                }
            }
            expectedLines.drop(common).forEach { appendLine("- $it") }
            actualLines.drop(common).forEach { appendLine("+ $it") }
        }
        return "--- golden\n+++ actual\n$body"
    }

    companion object {
        const val FIXTURE_LOGIN_DB_INFO =
            ".;mariadb.sock;pocket_core;0123456789abcdef0123456789abcdef0123456789abcdef;classicrealmd"
        const val FIXTURE_WORLD_DB_INFO =
            ".;mariadb.sock;pocket_core;0123456789abcdef0123456789abcdef0123456789abcdef;classicmangos"
        const val FIXTURE_CHAR_DB_INFO =
            ".;mariadb.sock;pocket_core;0123456789abcdef0123456789abcdef0123456789abcdef;classiccharacters"
        const val FIXTURE_LOGS_DB_INFO =
            ".;mariadb.sock;pocket_core;0123456789abcdef0123456789abcdef0123456789abcdef;classiclogs"
        const val FIXTURE_DATA_DIR = "/data/data/fixtures/no_backup/content/o11-server/generations/generation"
        const val FIXTURE_LOGS = "/data/data/fixtures/no_backup/server/logs"
        const val FIXTURE_RUN = "/data/data/fixtures/no_backup/server/run"
        val FIXTURE_CONDITIONS = ManagedConfigPolicy.LaunchConditions(
            renderer = "dxvk", resolution = "1920x1080", gameMaximized = true,
            frameCap = 30, uiScale = null, audioMode = "off", realmLoopback = true,
            soundChannelsEnabled = false, soundChannels = 32,
        )
    }
}
