package com.pocketrealm.ui

import com.pocketrealm.ui.online.OnlinePlayCopy
import org.junit.Assert.assertTrue
import org.junit.Test

/** The privacy dialog's substance (the wording lives in the shared model). */
class PublicRealmPrivacyCopyTest {
    @Test fun theGameListDialogNamesEveryPartyThatSeesTheAddress() {
        val copy = OnlinePlayCopy.PRIVACY_COPY
        // Looking for a game exposes the device's address to the services
        // that carry the list.
        assertTrue("carry the list" in copy && "internet address" in copy)
        // The host of a joined game can see it too.
        assertTrue("host of a game you join" in copy)
        // No credential rides a join.
        assertTrue("password stays on this device" in copy)
        // Account creation sends a checkable code, never the password.
        assertTrue("one-way code" in copy && "never the password" in copy)
        // The host's device code is per-game.
        assertTrue("device code" in copy)
    }
}
