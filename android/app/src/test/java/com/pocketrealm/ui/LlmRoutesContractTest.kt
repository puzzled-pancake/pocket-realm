package com.pocketrealm.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Route contract for the pushed AI bot LLM submenu (mirrors the Add-ons one). */
class LlmRoutesContractTest {
    @Test fun hubRouteHasStableShapeAndTitle() {
        assertEquals("llm", LlmRoutes.HUB)
        assertEquals("AI bot LLM", screenTitle(LlmRoutes.HUB))
    }

    @Test fun hubRouteIsNotATopLevelDestination() {
        // The submenu is pushed from Settings; it is not a Screen destination
        // and therefore never appears in the bottom bar / navigation rail.
        assertEquals(null, Screen.fromRoute(LlmRoutes.HUB))
    }
}
