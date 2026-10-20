package com.pocketrealm.server

import com.pocketrealm.supervisor.RealmEndpoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design-tunnel §4.4.2 (the Android half of the desktop
 * DesktopTunnelServerFilesTest contract): the wildcard-bind lane the tunnel
 * host's start path feeds the Android conf writers. "0.0.0.0" is the ONE
 * raw conf value accepted beside the strict typed grammar
 * ([ServerRuntimeContract.resolveBindAddress] — it is never a
 * [RealmEndpoint]), and a wildcard bind is non-loopback, so the
 * brute-force lockout rides every tunnel start too. The conf body is the
 * pure companion template the instance writers feed (the
 * VanillaConfGoldenTest seam) — the golden pin stays byte-exact because
 * loopback output is unchanged.
 */
class ServerRuntimeFilesTunnelBindTest {

    @Test
    fun realmdConfBodyBindsTheWildcardAndLockoutRidesIt() {
        val conf = ServerRuntimeFiles.realmdConfText(
            loginDatabaseInfo = "secrets",
            bindAddress = RealmEndpoint.WILDCARD_BIND,
            realmLogFilePath = "realmd.log",
        )
        assertTrue("BindIP must carry the wildcard:\n$conf", "BindIP = \"0.0.0.0\"" in conf)
        assertTrue(
            "a wildcard bind accepts remote logons — the D11 lockout must ride it:\n$conf",
            "WrongPass.MaxCount = 10" in conf,
        )
        assertTrue("WrongPass.BanTime = 900" in conf)
        assertTrue("WrongPass.BanType = 0" in conf)
    }

    @Test
    fun loopbackConfBodyStaysByteIdenticalWithoutTheLockout() {
        val conf = ServerRuntimeFiles.realmdConfText(
            loginDatabaseInfo = "secrets",
            bindAddress = RealmEndpoint.LOOPBACK_ADDRESS,
            realmLogFilePath = "realmd.log",
        )
        assertTrue("BindIP = \"127.0.0.1\"" in conf)
        assertFalse(
            "the loopback-only LOCAL lane emits no lockout (the self-lockout rule)",
            "WrongPass" in conf,
        )
    }

    @Test
    fun worldConfBodyBindsTheWildcard() {
        val conf = ServerRuntimeFiles.mangosdConfText(
            dataDirectoryAbsolutePath = "data",
            loginDatabaseInfo = "secrets",
            worldDatabaseInfo = "world",
            characterDatabaseInfo = "characters",
            logsDatabaseInfo = "logs",
            bindAddress = RealmEndpoint.WILDCARD_BIND,
            normalPlay = true,
            worldLogFilePath = "world.log",
            worldLogFileLevel = 1,
            databaseErrorsLogFilePath = "db-errors.log",
            nearbyInteractCooldownMs = NearbyInteractPolicy.normalizeTriggerGuardMs(0),
            playerbotConfigPath = "aiplayerbot.conf",
            botTarget = 0,
            timeframeStage = "vanilla-1.12.1",
        )
        assertTrue("world BindIP must carry the wildcard:\n$conf", "BindIP = \"0.0.0.0\"" in conf)
        assertTrue("the world port stays fixed:\n$conf", "WorldServerPort = ${ServerRuntimeContract.WORLD_PORT}" in conf)
    }
}
