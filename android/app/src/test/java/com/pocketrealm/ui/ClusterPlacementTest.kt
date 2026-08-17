package com.pocketrealm.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pocketrealm.client.ClusterAnchor
import org.junit.Assert.assertEquals
import org.junit.Test

/** Anchored clusters must render fully on screen, including after they grow. */
class ClusterPlacementTest {
    @Test
    fun inBoundsAnchorRendersUnchanged() {
        assertEquals(
            IntOffset(640, 540),
            clampedClusterTopLeft(ClusterAnchor(0.5f, 0.75f), IntSize(1280, 720), IntSize(400, 160)),
        )
    }

    @Test
    fun grownClusterIsPulledBackInsideTheContainer() {
        // Anchor saved when the cluster was small; the expanded menu would
        // otherwise extend past the bottom edge, taking its buttons with it.
        assertEquals(
            IntOffset(1024, 240),
            clampedClusterTopLeft(ClusterAnchor(0.8f, 0.8f), IntSize(1280, 720), IntSize(200, 480)),
        )
    }

    @Test
    fun clusterLargerThanContainerPinsToTheTopLeft() {
        assertEquals(
            IntOffset(0, 0),
            clampedClusterTopLeft(ClusterAnchor(0.9f, 0.9f), IntSize(1280, 720), IntSize(1280, 900)),
        )
    }
}
