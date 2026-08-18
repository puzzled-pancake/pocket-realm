package com.winlator.xenvironment.components;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WindowAuthorityEligibilityTest {
    private static boolean eligible(
            boolean present,
            boolean root,
            boolean viewable,
            boolean origin,
            boolean inputOutput,
            boolean content,
            int width,
            int height,
            long lifetime) {
        return WindowAuthorityEligibility.isEligible(
                present, root, viewable, origin, inputOutput, content,
                width, height, lifetime);
    }

    @Test
    public void liveMappedNonRootInputOutputWindowIsEligible() {
        assertTrue(eligible(true, false, true, true, true, true,
                1280, 720, 91L));
    }

    @Test
    public void rootUnmappedDestroyedAndUnknownWindowsAreRejected() {
        assertFalse(eligible(true, true, true, true, true, true,
                1280, 720, 91L));
        assertFalse(eligible(true, false, false, true, true, true,
                1280, 720, 91L));
        assertFalse(eligible(false, false, false, false, false, false,
                0, 0, 0L));
        assertFalse(eligible(true, false, true, false, true, true,
                1280, 720, 91L));
    }

    @Test
    public void nonRenderableOrInvalidLifetimeWindowsAreRejected() {
        assertFalse(eligible(true, false, true, true, false, true,
                1280, 720, 91L));
        assertFalse(eligible(true, false, true, true, true, false,
                1280, 720, 91L));
        assertFalse(eligible(true, false, true, true, true, true,
                0, 720, 91L));
        assertFalse(eligible(true, false, true, true, true, true,
                1280, -1, 91L));
        assertFalse(eligible(true, false, true, true, true, true,
                1280, 720, 0L));
    }
}
