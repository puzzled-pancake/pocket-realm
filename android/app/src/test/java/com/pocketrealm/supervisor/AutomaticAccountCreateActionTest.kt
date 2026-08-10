package com.pocketrealm.supervisor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticAccountCreateActionTest {
    @Test fun `existing GM collision rotates without entering initialization path`() {
        val collision = JSONObject()
            .put("code", "ACCOUNT_EXISTS")
            .put("accountId", 77)
            .put("gmLevel", 3)
        assertEquals(
            AutomaticAccountCreateAction.ROTATE_COLLISION,
            automaticAccountCreateAction(collision),
        )
    }

    @Test fun `only a created account with durable id is initialized`() {
        assertEquals(
            AutomaticAccountCreateAction.INITIALIZE_CREATED,
            automaticAccountCreateAction(JSONObject()
                .put("code", "ACCOUNT_CREATED").put("accountId", 8).put("gmLevel", 0)),
        )
        assertEquals(
            AutomaticAccountCreateAction.FAIL,
            automaticAccountCreateAction(JSONObject()
                .put("code", "ACCOUNT_CREATED").put("accountId", 0)),
        )
    }
}
