package com.pocketrealm.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The roster relay ops' JSON parsers (design-remote-accounts §8/§11/§12):
 * the shapes the world_runtime.cpp ops document, parsed without natives.
 */
class FriendRosterJsonTest {
    @Test
    fun accountRowsParseWithEveryModerationColumn() {
        val json = """
            {"ok":true,"accounts":[
                {"id":1,"username":"HOST","gmlevel":3,"failedLogins":0,"banned":false,
                 "lastLogonIp":"127.0.0.1","lastLogon":"2026-10-01 10:00:00","online":true},
                {"id":2,"username":"Dave","gmlevel":0,"failedLogins":4,"banned":true,
                 "lastLogonIp":"192.168.1.77","lastLogon":"2026-09-30 21:14:00","online":false}
            ]}
        """.trimIndent()
        val (reason, rows) = FriendRosterJson.parseAccounts(json)
        assertNull(reason)
        assertEquals(2, rows.size)
        val dave = rows[1]
        assertEquals(2L, dave.id)
        assertEquals("Dave", dave.username)
        assertEquals(0, dave.gmLevel)
        assertEquals(4L, dave.failedLogins)
        assertTrue(dave.banned)
        assertEquals("192.168.1.77", dave.lastLogonIp)
        assertEquals("2026-09-30 21:14:00", dave.lastLogon)
        assertEquals(false, dave.online)
        assertTrue(rows[0].online)
    }

    @Test
    fun refusedOpsCarryTheReasonAndNoRows() {
        assertEquals(
            "world-not-ready",
            FriendRosterJson.parseAccounts("{\"ok\":false,\"reason\":\"world-not-ready\"}").first,
        )
        assertTrue(
            FriendRosterJson.parseAccounts("{\"ok\":false,\"reason\":\"account-query-failed\"}").second.isEmpty(),
        )
        assertTrue(
            "garbage parses as an empty roster",
            FriendRosterJson.parseAccounts("<html>").second.isEmpty(),
        )
        assertEquals("unparseable", FriendRosterJson.parseAccounts("<html>").first)
        val blank = FriendRosterJson.parseAccounts(null)
        assertEquals("world-not-ready", blank.first)
        assertTrue(blank.second.isEmpty())
    }

    @Test
    fun characterRowsParse() {
        val json = """
            {"ok":true,"accountId":2,"characters":[
                {"guid":15,"name":"Daveling","race":1,"class":9,"level":7}
            ]}
        """.trimIndent()
        val (reason, rows) = FriendRosterJson.parseCharacters(json)
        assertNull(reason)
        assertEquals(1, rows.size)
        assertEquals("Daveling", rows[0].name)
        assertEquals(15L, rows[0].guid)
        assertEquals(7, rows[0].level)
    }

    @Test
    fun banVerdictsMapTheConsoleScan() {
        val (ok, detail) = FriendRosterJson.parseBanVerdict(
            "{\"ok\":true,\"result\":0,\"reason\":\"host ban\",\"output\":\"Dave is banned for 1 hour.\"}",
        )
        assertTrue(ok)
        assertEquals("Dave is banned for 1 hour.", detail)
        val (refused, refusedDetail) = FriendRosterJson.parseBanVerdict(
            "{\"ok\":false,\"result\":11,\"reason\":\"\",\"output\":\"Account not found.\"}",
        )
        assertTrue(!refused)
        assertEquals("Account not found.", refusedDetail)
        val (blankOk, blankDetail) = FriendRosterJson.parseBanVerdict("{\"ok\":true,\"result\":0,\"output\":\"\"}")
        assertTrue(blankOk)
        assertEquals("done", blankDetail)
    }
}
