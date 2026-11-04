package com.pocketrealm.ui

import com.pocketrealm.realm.RealmState
import com.pocketrealm.realm.SaveReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PocketRealmNavigationTest {
    @Test fun routesResolveWithoutStaticInitializationCycles() {
        assertEquals("Home", Screen.fromRoute("home")?.label)
        assertNull(Screen.fromRoute("bots"))
        assertEquals("Multiplayer", Screen.fromRoute("lan")?.label)
        assertEquals("Add-ons", Screen.fromRoute("addons")?.label)
        assertEquals("Controls", Screen.fromRoute("controls")?.label)
        assertEquals("Settings", Screen.fromRoute("settings")?.label)
        assertEquals("Game setup", Screen.fromRoute("client")?.label)
        assertNull(Screen.fromRoute(null))
        assertNull(Screen.fromRoute("unknown"))
    }

    @Test fun topBarStatusSummarizesEveryRealmState() {
        assertEquals("Realm stopped", realmStatusBadge(RealmState.Idle()))
        assertEquals("Starting…", realmStatusBadge(RealmState.Starting(attempt = 2)))
        assertEquals("Realm online", realmStatusBadge(RealmState.Running(0L)))
        assertEquals("Saving…", realmStatusBadge(RealmState.Saving(SaveReason.USER_SAVE_EXIT)))
        assertEquals("Stopping…", realmStatusBadge(RealmState.Stopping(forced = false)))
        assertEquals("Recovering…", realmStatusBadge(RealmState.Recovering("x")))
        assertEquals("Needs attention", realmStatusBadge(RealmState.Failed("x")))
        // the unreachable lane never reads as a clean stop — the
        // badge must name the supervisor loss explicitly.
        assertEquals(
            "Supervisor unreachable",
            realmStatusBadge(RealmState.SupervisorUnreachable("x")),
        )
    }

    @Test fun wideLayoutRequiresLandscapeAndSixHundredDp() {
        assertEquals(PaneLayout.WIDE, paneLayout(1280f, 720f))
        assertEquals(PaneLayout.WIDE, paneLayout(1920f, 1080f))
        assertEquals(PaneLayout.STACKED, paneLayout(720f, 1280f))
        assertEquals(PaneLayout.STACKED, paneLayout(580f, 400f))
    }
}
