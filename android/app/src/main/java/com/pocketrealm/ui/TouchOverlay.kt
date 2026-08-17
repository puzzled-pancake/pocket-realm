package com.pocketrealm.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.pocketrealm.client.ClientDisplayHost
import com.pocketrealm.client.ClusterAnchor
import com.pocketrealm.client.ControllerAction
import com.pocketrealm.client.ControllerFamily
import com.pocketrealm.client.InputProfile
import com.pocketrealm.client.OverlayClusterId
import com.pocketrealm.client.OverlayControl
import com.pocketrealm.client.OverlayLayout
import com.pocketrealm.client.OverlayMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

internal enum class OverlayPresentation { OFF, MINIMAL, FULL }

internal val fullOverlayActionPages: List<List<OverlayControl>> = listOf(
    listOf(OverlayControl.ACTION_1, OverlayControl.ACTION_2, OverlayControl.ACTION_3, OverlayControl.ACTION_4),
    listOf(OverlayControl.ACTION_5, OverlayControl.ACTION_6, OverlayControl.ACTION_7, OverlayControl.ACTION_8),
    listOf(OverlayControl.ACTION_9, OverlayControl.ACTION_10, OverlayControl.ACTION_11, OverlayControl.ACTION_12),
)

/** Touch geometry remains accessible at every persisted visual-scale value. */
internal fun effectiveTouchTargetDp(requestedScale: Float): Float =
    BASE_TOUCH_TARGET_DP * requestedScale.coerceAtLeast(MIN_TOUCH_SCALE)

/** Resolves AUTO without conflating an ignored controller with an active controller. */
internal fun overlayPresentation(
    mode: OverlayMode,
    controllerFamily: ControllerFamily,
    physicalControllerConnected: Boolean,
): OverlayPresentation = when (mode) {
    OverlayMode.OFF -> OverlayPresentation.OFF
    OverlayMode.MINIMAL -> OverlayPresentation.MINIMAL
    OverlayMode.FULL -> OverlayPresentation.FULL
    OverlayMode.AUTO -> if (
        physicalControllerConnected && controllerFamily !in setOf(
            ControllerFamily.TOUCH_ONLY,
            ControllerFamily.KEYBOARD_MOUSE,
        )
    ) OverlayPresentation.MINIMAL else OverlayPresentation.FULL
}

/** Landscape-first controls layered over the real client surface. */
@Composable
fun TouchOverlay(host: ClientDisplayHost, modifier: Modifier = Modifier) {
    val profile by host.profile.collectAsState()
    val cameraLocked by host.cameraLocked.collectAsState()
    val controllerConnected by host.physicalControllerConnected.collectAsState()
    val presentation = overlayPresentation(
        profile.overlayMode,
        profile.controllerFamily,
        controllerConnected,
    )
    var visible by remember(host.generation) { mutableStateOf(true) }
    var drawerExpanded by remember(host.generation) { mutableStateOf(false) }
    var showModeHud by remember(host.generation) { mutableStateOf(false) }
    var moveMode by remember(host.generation) { mutableStateOf(false) }
    var containerSize by remember(host.generation) { mutableStateOf(IntSize.Zero) }

    // Only an explicit mode change re-shows controls the user hid. AUTO
    // presentation flips on controller connect/disconnect must not override
    // the user's Hide.
    LaunchedEffect(profile.overlayMode) {
        visible = profile.overlayMode != OverlayMode.OFF
        drawerExpanded = false
        moveMode = false
    }
    // Skip the initial value so opening the client does not flash the HUD.
    LaunchedEffect(host.generation) {
        host.cameraLocked.drop(1).collect {
            showModeHud = true
            delay(MODE_HUD_DURATION_MS)
            showModeHud = false
        }
    }
    if (presentation == OverlayPresentation.OFF) return

    val opacity = profile.overlayOpacity
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { containerSize = it.size },
    ) {
        if (!visible) {
            // Hiding controls must also remove camera/keyboard hit regions. This
            // is the sole remaining touch target and is deliberately subdued.
            OverlayButton(
                label = "Controls",
                tag = "touch-overlay-toggle",
                opacity = opacity.coerceAtMost(HIDDEN_RESTORE_OPACITY),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) { visible = true }
            return@Box
        }

        if (presentation == OverlayPresentation.FULL) {
            if (profile.overlayLayout == OverlayLayout.CONSOLE) {
                ConsoleTouchControls(host, profile, opacity, moveMode, containerSize)
            } else {
                FullTouchControls(host, profile, opacity, moveMode, containerSize)
            }
        }

        if (showModeHud) {
            Text(
                if (cameraLocked) "Camera locked - right stick looks" else "Pointer free - right stick moves cursor",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("touch-camera-mode-hud"),
            )
        }

        MovableCluster(
            clusterId = OverlayClusterId.DRAWER,
            host = host,
            profile = profile,
            containerSize = containerSize,
            moveMode = moveMode,
            stockAlignment = Alignment.TopEnd,
            stockPadding = PaddingValues(10.dp),
            stockZIndex = 2f,
        ) {
            UtilityDrawer(
                host = host,
                cameraLocked = cameraLocked,
                opacity = opacity,
                expanded = drawerExpanded,
                onExpandedChange = { drawerExpanded = it },
                onHide = { visible = false },
                onToggleMoveMode = {
                    moveMode = !moveMode
                    if (moveMode) drawerExpanded = false
                },
            )
        }

        // Rearranging scrim covers the drawer too, so exit controls float.
        if (moveMode) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .zIndex(5f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OverlayButton("Done moving", "touch-move-done", opacity) { moveMode = false }
                OverlayButton("Reset layout", "touch-move-reset", opacity) {
                    host.resetOverlayClusterPositions()
                }
            }
        }
    }
}

/** Invisible right-side camera-look drag area shared by both full layouts. */
@Composable
private fun BoxScope.TouchCameraRegion(host: ClientDisplayHost, profile: InputProfile) {
    Box(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .fillMaxWidth(profile.cameraRegionWidth)
            .pointerInput(
                host.generation,
                profile.invertCameraX,
                profile.invertCameraY,
                profile.touchCameraSensitivity,
            ) {
                val scaler = TouchCameraScaler(profile.touchCameraSensitivity)
                detectDragGestures(
                    onDragStart = {
                        scaler.reset()
                        host.dispatchRightButton(true)
                    },
                    onDragEnd = {
                        scaler.reset()
                        host.dispatchRightButton(false)
                    },
                    onDragCancel = {
                        scaler.reset()
                        host.dispatchRightButton(false)
                    },
                    onDrag = { _, drag ->
                        val dx = if (profile.invertCameraX) -drag.x else drag.x
                        val dy = if (profile.invertCameraY) -drag.y else drag.y
                        val scaled = scaler.scale(dx, dy)
                        if (scaled.first != 0 || scaled.second != 0) {
                            host.dispatchRelativePointer(scaled.first, scaled.second)
                        }
                    },
                )
            }
            .testTag("touch-camera-region"),
    )
}

/**
 * Console-style touch arrangement for controller-free play: movement pad
 * with Shift/Ctrl page holds bottom-left (modifiers sit opposite the face
 * keys so one thumb holds while the other taps), a 1-4 face diamond with a
 * 5-8 pad above it bottom-right, a camera-look toggle with left/right
 * clicks and target bottom-center, and a radial / nearby-use / Move UI /
 * menu stack top-left. Every cluster is movable and every action stays
 * remappable through the profile.
 */
@Composable
private fun BoxScope.ConsoleTouchControls(
    host: ClientDisplayHost,
    profile: InputProfile,
    opacity: Float,
    moveMode: Boolean,
    containerSize: IntSize,
) {
    val targetSize = effectiveTouchTargetDp(profile.overlayScale).dp

    if (!moveMode) {
        TouchCameraRegion(host, profile)
    }

    MovableCluster(
        clusterId = OverlayClusterId.UTILITY_ROW,
        host = host,
        profile = profile,
        containerSize = containerSize,
        moveMode = moveMode,
        stockAlignment = Alignment.TopStart,
        stockPadding = PaddingValues(start = 12.dp, top = 8.dp),
        stockZIndex = 0f,
    ) {
        // Two stacked pairs keep the stack narrow so the collapsed drawer
        // never crosses it on narrow screens; the tight top inset keeps the
        // stack clear of the movement pad at the 16:9 target height.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionKey(host, profile, OverlayControl.RADIAL, "Radial", opacity, targetSize, wide = true)
                ActionKey(host, profile, OverlayControl.NEARBY_USE, "Use near", opacity, targetSize, wide = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionKey(host, profile, OverlayControl.MOVE_UI, "Move UI", opacity, targetSize, wide = true)
                ActionKey(host, profile, OverlayControl.MENU, "Menu", opacity, targetSize, wide = true)
            }
        }
    }

    MovableCluster(
        clusterId = OverlayClusterId.MOVEMENT,
        host = host,
        profile = profile,
        containerSize = containerSize,
        moveMode = moveMode,
        stockAlignment = Alignment.BottomStart,
        stockPadding = PaddingValues(start = 12.dp, bottom = 12.dp),
        stockZIndex = 0f,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionKey(host, profile, OverlayControl.MODIFIER_SHIFT, "Shift", opacity, targetSize, wide = true)
                ActionKey(host, profile, OverlayControl.MODIFIER_CTRL, "Ctrl", opacity, targetSize, wide = true)
            }
            Row(Modifier.padding(start = 56.dp)) {
                ActionKey(host, profile, OverlayControl.MOVE_UP, "W", opacity, targetSize)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionKey(host, profile, OverlayControl.MOVE_LEFT, "A", opacity, targetSize)
                ActionKey(host, profile, OverlayControl.MOVE_DOWN, "S", opacity, targetSize)
                ActionKey(host, profile, OverlayControl.MOVE_RIGHT, "D", opacity, targetSize)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionKey(host, profile, OverlayControl.AUTO_RUN, "Auto", opacity, targetSize, wide = true)
                ActionKey(host, profile, OverlayControl.JUMP, "Jump", opacity, targetSize, wide = true)
            }
        }
    }

    MovableCluster(
        clusterId = OverlayClusterId.FACE,
        host = host,
        profile = profile,
        containerSize = containerSize,
        moveMode = moveMode,
        stockAlignment = Alignment.BottomEnd,
        stockPadding = PaddingValues(end = 12.dp, bottom = 12.dp),
        stockZIndex = 0f,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionKey(host, profile, OverlayControl.ACTION_5, "5", opacity, targetSize)
                ActionKey(host, profile, OverlayControl.ACTION_6, "6", opacity, targetSize)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionKey(host, profile, OverlayControl.ACTION_7, "7", opacity, targetSize)
                ActionKey(host, profile, OverlayControl.ACTION_8, "8", opacity, targetSize)
            }
            ActionKey(host, profile, OverlayControl.ACTION_3, "3", opacity, targetSize)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionKey(host, profile, OverlayControl.ACTION_2, "2", opacity, targetSize)
                ActionKey(host, profile, OverlayControl.ACTION_4, "4", opacity, targetSize)
            }
            ActionKey(host, profile, OverlayControl.ACTION_1, "1", opacity, targetSize)
        }
    }

    MovableCluster(
        clusterId = OverlayClusterId.LOOK_CLICKS,
        host = host,
        profile = profile,
        containerSize = containerSize,
        moveMode = moveMode,
        stockAlignment = Alignment.BottomCenter,
        stockPadding = PaddingValues(bottom = 12.dp),
        stockZIndex = 0f,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ActionKey(host, profile, OverlayControl.TARGET, "Target", opacity, targetSize, wide = true)
            ActionKey(host, profile, OverlayControl.LOOK_TOGGLE, "Look", opacity, targetSize, wide = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionKey(host, profile, OverlayControl.MOUSE_LEFT, "Tap", opacity, targetSize, wide = true)
                ActionKey(host, profile, OverlayControl.MOUSE_RIGHT, "R-tap", opacity, targetSize, wide = true)
            }
        }
    }
}

@Composable
private fun BoxScope.FullTouchControls(
    host: ClientDisplayHost,
    profile: InputProfile,
    opacity: Float,
    moveMode: Boolean,
    containerSize: IntSize,
) {
    var actionPage by remember(host.generation) { mutableIntStateOf(0) }
    val targetSize = effectiveTouchTargetDp(profile.overlayScale).dp

    // Invisible right-side look area. Utility and action controls are composed
    // after it, so their 48dp+ hit targets win pointer dispatch. Suppressed
    // while rearranging so an accidental empty-screen drag cannot dispatch
    // camera-look.
    if (!moveMode) {
        TouchCameraRegion(host, profile)
    }

    MovableCluster(
        clusterId = OverlayClusterId.TARGET_ROW,
        host = host,
        profile = profile,
        containerSize = containerSize,
        moveMode = moveMode,
        stockAlignment = Alignment.TopStart,
        stockPadding = PaddingValues(start = 12.dp, top = 12.dp),
        stockZIndex = 0f,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionKey(host, profile, OverlayControl.TARGET, "Target", opacity, targetSize, wide = true)
            ActionKey(host, profile, OverlayControl.USE_LOOT, "Use / Open", opacity, targetSize, wide = true)
            ActionKey(host, profile, OverlayControl.MENU, "Menu", opacity, targetSize, wide = true)
        }
    }

    MovableCluster(
        clusterId = OverlayClusterId.MOVEMENT,
        host = host,
        profile = profile,
        containerSize = containerSize,
        moveMode = moveMode,
        stockAlignment = Alignment.BottomStart,
        stockPadding = PaddingValues(start = 12.dp, bottom = 12.dp),
        stockZIndex = 0f,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.padding(start = 56.dp)) {
                ActionKey(host, profile, OverlayControl.MOVE_UP, "W", opacity, targetSize)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionKey(host, profile, OverlayControl.MOVE_LEFT, "A", opacity, targetSize)
                ActionKey(host, profile, OverlayControl.MOVE_DOWN, "S", opacity, targetSize)
                ActionKey(host, profile, OverlayControl.MOVE_RIGHT, "D", opacity, targetSize)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionKey(host, profile, OverlayControl.AUTO_RUN, "Auto", opacity, targetSize, wide = true)
                ActionKey(host, profile, OverlayControl.JUMP, "Jump", opacity, targetSize, wide = true)
            }
        }
    }

    MovableCluster(
        clusterId = OverlayClusterId.ACTIONS,
        host = host,
        profile = profile,
        containerSize = containerSize,
        moveMode = moveMode,
        stockAlignment = Alignment.BottomEnd,
        stockPadding = PaddingValues(end = 12.dp, bottom = 12.dp),
        stockZIndex = 0f,
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OverlayButton(
                    label = when (actionPage) {
                        0 -> "Actions 1-4"
                        1 -> "Actions 5-8"
                        else -> "Actions 9-12"
                    },
                    tag = "touch-action-page-label",
                    opacity = opacity,
                ) { actionPage = (actionPage + 1) % fullOverlayActionPages.size }
                OverlayButton(
                    label = if (actionPage == fullOverlayActionPages.lastIndex) "< First" else "Next >",
                    tag = "touch-action-page",
                    opacity = opacity,
                ) { actionPage = (actionPage + 1) % fullOverlayActionPages.size }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fullOverlayActionPages[actionPage].forEach { control ->
                    val label = (control.ordinal - OverlayControl.ACTION_1.ordinal + 1).toString()
                    ActionKey(host, profile, control, label, opacity, targetSize)
                }
            }
        }
    }
}

/**
 * Wraps one touch cluster so the player can drag it anywhere in move mode.
 * Without a saved anchor the cluster keeps its stock alignment; anchored
 * clusters are placed from normalized fractions of the overlay container and
 * re-clamped at render time, so a cluster that grows after anchoring — the
 * utility drawer expanding its menu near an edge — always stays fully on the
 * display. While move mode is on, a scrim covers the cluster: it owns the
 * drag gesture across the whole cluster area and blocks the covered buttons
 * from dispatching game input while the player rearranges. The scrim also
 * finalizes an in-flight drag when it is torn down mid-gesture.
 */
@Composable
private fun BoxScope.MovableCluster(
    clusterId: OverlayClusterId,
    host: ClientDisplayHost,
    profile: InputProfile,
    containerSize: IntSize,
    moveMode: Boolean,
    stockAlignment: Alignment,
    stockPadding: PaddingValues,
    stockZIndex: Float,
    content: @Composable () -> Unit,
) {
    val savedAnchor = profile.overlayClusterPositions[clusterId]
    var dragging by remember(clusterId) { mutableStateOf(false) }
    var liveAnchor by remember(clusterId) { mutableStateOf(savedAnchor) }
    var clusterSize by remember(clusterId) { mutableStateOf(IntSize.Zero) }
    var stockTopLeft by remember(clusterId) { mutableStateOf(Offset.Zero) }

    // External anchor changes (reset layout, relaunch) re-sync unless a drag
    // is already in flight.
    LaunchedEffect(savedAnchor) {
        if (!dragging) liveAnchor = savedAnchor
    }

    fun clampAnchor(anchor: ClusterAnchor): ClusterAnchor {
        if (containerSize.width <= 0 || containerSize.height <= 0) return anchor
        val maxX = (containerSize.width - clusterSize.width).coerceAtLeast(0).toFloat() / containerSize.width
        val maxY = (containerSize.height - clusterSize.height).coerceAtLeast(0).toFloat() / containerSize.height
        return ClusterAnchor(
            anchor.xFraction.coerceIn(0f, maxX),
            anchor.yFraction.coerceIn(0f, maxY),
        )
    }

    val anchor = liveAnchor
    val placement = if (anchor != null) {
        // Render-time clamp keeps a grown cluster (drawer menu expanding
        // near an edge) fully on the display; reading clusterSize here
        // re-renders whenever the cluster or container size changes.
        val topLeft = clampedClusterTopLeft(anchor, containerSize, clusterSize)
        with(LocalDensity.current) {
            Modifier
                .align(Alignment.TopStart)
                .offset(topLeft.x.toDp(), topLeft.y.toDp())
        }
    } else {
        Modifier.align(stockAlignment).padding(stockPadding)
    }

    Box(
        placement
            .zIndex(if (dragging) 4f else stockZIndex)
            .onGloballyPositioned { coordinates ->
                clusterSize = coordinates.size
                if (liveAnchor == null && !dragging) {
                    stockTopLeft = coordinates.positionInParent()
                }
            },
    ) {
        content()
        if (moveMode) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(MOVE_MODE_SCRIM)
                    .border(1.5.dp, MOVE_MODE_OUTLINE, RoundedCornerShape(18.dp))
                    // Re-keyed on the container size so drag math survives
                    // inset/density changes that do not recreate the activity.
                    .pointerInput(clusterId, containerSize) {
                        val finishDrag: () -> Unit = {
                            dragging = false
                            liveAnchor?.let { current ->
                                val clamped = clampAnchor(current)
                                liveAnchor = clamped
                                host.updateOverlayClusterPosition(
                                    clusterId,
                                    clamped.xFraction,
                                    clamped.yFraction,
                                )
                            }
                        }
                        var dragFinalized = false
                        try {
                            detectDragGestures(
                                onDragStart = {
                                    if (containerSize.width <= 0 || containerSize.height <= 0) {
                                        return@detectDragGestures
                                    }
                                    // A pointerInput block serves many gestures;
                                    // re-arm the teardown guard each time.
                                    dragFinalized = false
                                    dragging = true
                                    if (liveAnchor == null) {
                                        // Seed from the stock placement so the
                                        // first anchored frame does not jump.
                                        liveAnchor = ClusterAnchor(
                                            stockTopLeft.x / containerSize.width,
                                            stockTopLeft.y / containerSize.height,
                                        )
                                    }
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    liveAnchor?.let { current ->
                                        liveAnchor = clampAnchor(
                                            ClusterAnchor(
                                                current.xFraction + amount.x / containerSize.width,
                                                current.yFraction + amount.y / containerSize.height,
                                            ),
                                        )
                                    }
                                },
                                onDragEnd = {
                                    dragFinalized = true
                                    finishDrag()
                                },
                                onDragCancel = {
                                    dragFinalized = true
                                    finishDrag()
                                },
                            )
                        } finally {
                            // Tearing the scrim down mid-drag (mode exit) does
                            // not invoke onDragCancel; finalize here instead
                            // so dragging state never strands.
                            if (!dragFinalized) finishDrag()
                        }
                    },
            )
        }
    }
}

@Composable
private fun UtilityDrawer(
    host: ClientDisplayHost,
    cameraLocked: Boolean,
    opacity: Float,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onHide: () -> Unit,
    onToggleMoveMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier
            .background(Color(0xFF081019).copy(alpha = if (expanded) 0.82f else 0f), RoundedCornerShape(16.dp))
            .padding(if (expanded) 8.dp else 0.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Camera mode is intentionally one tap from gameplay in both the
            // controller-minimal and full-touch presentations.
            OverlayButton(
                label = cameraModeButtonLabel(cameraLocked),
                tag = "touch-camera-lock",
                opacity = opacity.coerceAtMost(0.72f),
            ) { host.toggleCameraLock() }
            OverlayButton(
                label = if (expanded) "Close" else "More",
                tag = "touch-utility-drawer",
                opacity = opacity.coerceAtMost(0.72f),
            ) { onExpandedChange(!expanded) }
        }
        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OverlayButton("Zoom +", "touch-camera-zoom-in", opacity) {
                    host.dispatchWheel(cameraZoomWheelTicks(zoomIn = true))
                }
                OverlayButton("Zoom -", "touch-camera-zoom-out", opacity) {
                    host.dispatchWheel(cameraZoomWheelTicks(zoomIn = false))
                }
                OverlayButton("Keyboard", "touch-chat", opacity) { host.showIme() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DirectActionKey(host, ControllerAction.MAP, "Map", "touch-map", opacity)
                DirectActionKey(host, ControllerAction.INVENTORY, "Bags", "touch-bags", opacity)
                OverlayButton("Settings", "touch-settings", opacity) {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
                OverlayButton("Hide", "touch-overlay-toggle", opacity, onClick = onHide)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OverlayButton("Move buttons", "touch-move-mode", opacity, onClick = onToggleMoveMode)
            }
        }
    }
}

internal fun cameraModeButtonLabel(cameraLocked: Boolean): String =
    if (cameraLocked) "Pointer" else "Camera"

@Composable
private fun DirectActionKey(
    host: ClientDisplayHost,
    action: ControllerAction,
    label: String,
    tag: String,
    opacity: Float,
) {
    Box(
        Modifier
            .sizeIn(minWidth = 52.dp, minHeight = 48.dp)
            .background(Color(0xFF101720).copy(alpha = opacity), RoundedCornerShape(14.dp))
            .virtualAction(host, action, VIRTUAL_SOURCE_DIRECT_BASE + action.ordinal)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, modifier = Modifier.padding(horizontal = 10.dp)) }
}

@Composable
private fun ActionKey(
    host: ClientDisplayHost,
    profile: InputProfile,
    control: OverlayControl,
    fallbackLabel: String,
    opacity: Float,
    targetSize: androidx.compose.ui.unit.Dp,
    wide: Boolean = false,
) {
    val action = InputProfile.actionFor(profile, control)
    val label = action.shortLabel(fallbackLabel)
    Box(
        Modifier
            .size(if (wide || label.length > 3) targetSize * WIDE_TARGET_RATIO else targetSize, targetSize)
            .background(Color(0xFF101720).copy(alpha = opacity), RoundedCornerShape(15.dp))
            .virtualAction(host, action, VIRTUAL_SOURCE_BASE + control.ordinal)
            .testTag("touch-control-${control.name}"),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White) }
}

@Composable
private fun OverlayButton(
    label: String,
    tag: String,
    opacity: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .background(Color(0xFF101720).copy(alpha = opacity), RoundedCornerShape(14.dp))
            .testTag(tag)
            // clickable keeps the latest callback across camera/profile
            // recompositions. The old pointerInput(tag) captured the first
            // expanded=false lambda, so later More/Close taps could be stale.
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, modifier = Modifier.padding(horizontal = 12.dp)) }
}

private fun Modifier.virtualAction(
    host: ClientDisplayHost,
    action: ControllerAction,
    source: Int,
): Modifier = pointerInput(host.generation, action, source) {
    detectTapGestures(
        onPress = {
            host.dispatchVirtualAction(action, true, source)
            try {
                tryAwaitRelease()
            } finally {
                host.dispatchVirtualAction(action, false, source)
            }
        },
    )
}

private fun ControllerAction.shortLabel(fallback: String): String = when (this) {
    ControllerAction.DISABLED -> "Off"
    ControllerAction.POINTER_LEFT -> "Select"
    ControllerAction.POINTER_RIGHT, ControllerAction.USE_LOOT_CLICK -> "Use / open\nat pointer"
    ControllerAction.CAMERA_LOCK -> "Camera"
    ControllerAction.JUMP -> "Jump"
    ControllerAction.ESCAPE -> "Menu"
    ControllerAction.AUTO_RUN -> "Auto"
    ControllerAction.INTERACT -> "I key"
    ControllerAction.MAP -> "Map"
    ControllerAction.INVENTORY -> "Bags"
    ControllerAction.NAV_UP -> "Up"
    ControllerAction.NAV_DOWN -> "Down"
    ControllerAction.NAV_LEFT -> "Left"
    ControllerAction.NAV_RIGHT -> "Right"
    ControllerAction.TARGET, ControllerAction.TARGET_PULSE -> "Target"
    ControllerAction.SHIFT -> "Shift"
    ControllerAction.CTRL -> "Ctrl"
    else -> keyCode?.let { displayName.removePrefix("Key ").removePrefix("Move ").removePrefix("Strafe ") }
        ?: fallback
}

/** WoW's default wheel binding is up = zoom in, down = zoom out. */
internal fun cameraZoomWheelTicks(zoomIn: Boolean): Int = if (zoomIn) -1 else 1

/**
 * Placement for an anchored cluster, clamped so the cluster renders fully
 * inside the overlay container. Clamping at render time — not only at drop
 * time — keeps a cluster on screen after it grows, e.g. the utility drawer
 * expanding its menu near an edge, or after container inset changes.
 */
internal fun clampedClusterTopLeft(
    anchor: ClusterAnchor,
    containerSize: IntSize,
    clusterSize: IntSize,
): IntOffset {
    val maxX = (containerSize.width - clusterSize.width).coerceAtLeast(0)
    val maxY = (containerSize.height - clusterSize.height).coerceAtLeast(0)
    return IntOffset(
        (anchor.xFraction * containerSize.width).roundToInt().coerceIn(0, maxX),
        (anchor.yFraction * containerSize.height).roundToInt().coerceIn(0, maxY),
    )
}

/** Fraction-preserving touch scaler so a lower speed stays smooth for one-pixel drags. */
internal class TouchCameraScaler(private val sensitivity: Float) {
    private var remainderX = 0f
    private var remainderY = 0f

    init {
        require(sensitivity in 0.15f..1f) { "touch camera sensitivity is out of range" }
    }

    fun scale(dx: Float, dy: Float): Pair<Int, Int> {
        val x = dx * sensitivity + remainderX
        val y = dy * sensitivity + remainderY
        val wholeX = x.toInt()
        val wholeY = y.toInt()
        remainderX = x - wholeX
        remainderY = y - wholeY
        return wholeX to wholeY
    }

    fun reset() {
        remainderX = 0f
        remainderY = 0f
    }
}

private const val VIRTUAL_SOURCE_BASE = 0x7400
private const val VIRTUAL_SOURCE_DIRECT_BASE = 0x7500
private const val HIDDEN_RESTORE_OPACITY = 0.28f
private const val MODE_HUD_DURATION_MS = 1_200L
private const val BASE_TOUCH_TARGET_DP = 52f
private const val MIN_TOUCH_TARGET_DP = 48f
private const val MIN_TOUCH_SCALE = MIN_TOUCH_TARGET_DP / BASE_TOUCH_TARGET_DP
private const val WIDE_TARGET_RATIO = 92f / BASE_TOUCH_TARGET_DP

/** Scrim/outline shown over every cluster while the player rearranges them. */
private val MOVE_MODE_SCRIM = Color(0xFF6EC1FF).copy(alpha = 0.16f)
private val MOVE_MODE_OUTLINE = Color(0xFF6EC1FF)
