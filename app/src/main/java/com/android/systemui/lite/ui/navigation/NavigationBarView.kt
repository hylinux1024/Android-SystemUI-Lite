package com.android.systemui.lite.ui.navigation

import android.annotation.SuppressLint
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.android.systemui.lite.model.GestureEdge
import com.android.systemui.lite.model.GestureSession
import com.android.systemui.lite.model.GestureState
import com.android.systemui.lite.model.GestureType
import com.android.systemui.lite.model.NavigationMode
import com.android.systemui.lite.model.TouchZone
import com.android.systemui.lite.navigation.GestureEdgePanel
import com.android.systemui.lite.navigation.GestureHandler
import kotlin.math.min

@Composable
fun NavigationBarView(
    themeColor: Color,
    navigationMode: NavigationMode,
    handler: GestureHandler,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit
) {
    // GESTURE mode UI is hosted in three separate thin strip windows (see
    // NavigationBarCoreStartable) — this composable only renders the three-button bar.
    if (navigationMode == NavigationMode.THREE_BUTTON) {
        ThreeButtonNavigationBar(themeColor, onBack, onHome, onRecents)
    }
}

@Composable
fun ThreeButtonNavigationBar(
    themeColor: Color,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(Color.Black.copy(alpha = 0.3f)).padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(20.dp)) {
                drawPath(Path().apply { moveTo(size.width, 0f); lineTo(0f, size.height / 2); lineTo(size.width, size.height); close() }, Color.White)
            }
        }
        Box(Modifier.size(48.dp).clickable { onHome() }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(20.dp)) { drawCircle(Color.White, style = Stroke(width = 2.dp.toPx())) }
        }
        Box(Modifier.size(48.dp).clickable { onRecents() }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(18.dp)) { drawRect(Color.White, style = Stroke(width = 2.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, size.height)) }
        }
    }
}

/**
 * Edge-zone strip (left or right). Hosted in its own thin [android.view.WindowManager] overlay
 * by the startable, so it only receives touches that actually fall in the edge band — the app
 * below keeps getting every other touch. Feeds drags into the shared [GestureHandler] and overlays
 * the spring-animated back affordance on top.
 *
 * [GestureHandler.onAction] is wired by the startable (not here) to the real `sendKeyEvent`
 * dispatch, so this file stays free of key codes.
 *
 * @param edge which side of the screen this strip covers (drives arrow direction + layout).
 * @param screenWidthPx full display width in px, passed through to [GestureHandler.onDown].
 * @param screenHeightPx full display height in px, passed through to [GestureHandler.onDown].
 */
@Composable
fun GestureEdgeZone(
    handler: GestureHandler,
    edge: GestureEdge,
    screenWidthPx: Int,
    screenHeightPx: Int
) {
    val session by handler.session.collectAsState()
    val trackedType by handler.trackedType.collectAsState()

    val density = LocalDensity.current
    // Classify which [TouchZone] this physical strip covers so the affordance only renders
    // on the strip where the gesture actually began (otherwise both edge strips render).
    val zoneForThisStrip = when (edge) {
        GestureEdge.LEFT -> TouchZone.LEFT_EDGE
        GestureEdge.RIGHT -> TouchZone.RIGHT_EDGE
        GestureEdge.BOTTOM -> TouchZone.BOTTOM
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        var totalDragX by remember { mutableStateOf(0f) }
        var totalDragY by remember { mutableStateOf(0f) }
        // Starts false; flips true the moment the handler begins tracking a drag, at which point
        // subsequent deltas in this gesture session are consumed so they don't leak to the app
        // below (e.g. a list that happens to be scrolled from the edge strip).
        var consuming by remember { mutableStateOf(false) }

        Box(
            Modifier.fillMaxSize().pointerInput(screenWidthPx, screenHeightPx) {
                detectDragGestures(
                    onDragStart = { offset ->
                        totalDragX = 0f; totalDragY = 0f; consuming = false
                        // Convert strip-local coordinates to display coordinates. The strip window
                        // is pinned to one edge, so only the axis along the edge is shifted.
                        // NOTE: maxWidth is a Dp — multiply by density to get px before subtracting
                        // from the px screen width, otherwise the right-edge touch point lands at
                        // an x far short of the real screen edge and the down-classifier misses
                        // the RIGHT_EDGE zone.
                        val maxWidthPx = with(density) { maxWidth.toPx() }
                        val displayX = when (edge) {
                            GestureEdge.LEFT -> offset.x
                            GestureEdge.RIGHT -> screenWidthPx - maxWidthPx + offset.x
                            GestureEdge.BOTTOM -> offset.x
                        }
                        val displayY = offset.y
                        handler.onDown(displayX, displayY, screenWidthPx, screenHeightPx)
                        consuming = handler.session.value != null
                    },
                    onDragEnd = { handler.onUp() },
                    onDragCancel = { handler.onUp() },
                    onDrag = { change, dragAmount ->
                        if (consuming) {
                            change.consume()
                            totalDragX += dragAmount.x
                            totalDragY += dragAmount.y
                            handler.onMove(dragAmount.x, dragAmount.y, totalDragX, totalDragY)
                        } else if (handler.session.value != null) {
                            // Handler just transitioned ENTRY->ACTIVE mid-drag; begin consuming.
                            consuming = true
                            change.consume()
                            totalDragX += dragAmount.x
                            totalDragY += dragAmount.y
                            handler.onMove(dragAmount.x, dragAmount.y, totalDragX, totalDragY)
                        }
                        // Otherwise leave unconsumed so the app below receives the drag.
                    }
                )
            }
        )

        // Visual affordance — only on the strip whose zone matches the active session. Without
        // this check both edge strips would render the same arrow for a single gesture.
        if (session?.zone == zoneForThisStrip) {
            GestureEdgePanel(
                session = session,
                gestureType = trackedType,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

/**
 * Visual affordance for the bottom-zone home/recents swipe: a horizontal pill (the
 * "navigation handle") that springs in on ENTRY, stretches wider as the drag progresses, then
 * fades on COMMITTED / CANCELLED. Modeled on AOSP `NavigationHandle` (a rounded rect sitting
 * just above the bottom edge). Renders nothing for non-BOTTOM sessions so the edge strips keep
 * their own affordance.
 */
@Composable
fun GestureBottomHandle(session: GestureSession?, modifier: Modifier = Modifier) {
    if (session == null ||
        session.state == GestureState.GONE ||
        session.zone != TouchZone.BOTTOM
    ) {
        return
    }

    // Resting width ≈ 150dp, grows toward the COMMITTED target as the gesture progresses.
    val widthPx by animateFloatAsState(
        targetValue = when (session.state) {
            GestureState.GONE, GestureState.CANCELLED -> 0f
            GestureState.COMMITTED -> 0f
            GestureState.ENTRY -> 60f
            GestureState.ACTIVE -> 60f + 240f * session.progress
            GestureState.INACTIVE -> 56f
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "bottom_handle_width",
    )
    val alpha by animateFloatAsState(
        targetValue = when (session.state) {
            GestureState.GONE, GestureState.COMMITTED, GestureState.CANCELLED -> 0f
            GestureState.ENTRY -> 0.45f
            GestureState.ACTIVE -> 0.8f
            GestureState.INACTIVE -> 0.35f
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "bottom_handle_alpha",
    )
    val density = LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme
    val pill = colorScheme.onSurfaceVariant.copy(alpha = alpha)
    val radius = with(density) { 2.dp.toPx() }   // matches AOSP navigation_handle_radius
    val height = radius * 2f                       // 4dp tall pill
    val bottomMargin = with(density) { 6.dp.toPx() }

    Canvas(modifier.fillMaxSize()) {
        if (alpha < 0.002f || widthPx < 1f) return@Canvas
        val cx = size.width / 2f
        val left = (cx - widthPx / 2f).coerceAtLeast(0f)
        val right = (cx + widthPx / 2f).coerceAtMost(size.width)
        val top = size.height - bottomMargin - height
        drawRoundRect(
            color = pill,
            topLeft = Offset(left, top),
            size = Size(right - left, height),
            cornerRadius = CornerRadius(radius, radius),
            style = Fill,
        )
    }
}

/**
 * Bottom-zone strip. Hosted in its own thin bottom overlay by the startable. Detects upward
 * swipes for HOME (short swipe, quick release) and RECENTS (longer swipe with a brief hold).
 */
@Composable
fun GestureBottomZone(
    handler: GestureHandler,
    screenWidthPx: Int,
    screenHeightPx: Int
) {
    val density = LocalDensity.current
    val session by handler.session.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        var totalDragX by remember { mutableStateOf(0f) }
        var totalDragY by remember { mutableStateOf(0f) }
        var consuming by remember { mutableStateOf(false) }

        Box(
            Modifier.fillMaxSize().pointerInput(screenWidthPx, screenHeightPx) {
                detectDragGestures(
                    onDragStart = { offset ->
                        totalDragX = 0f; totalDragY = 0f; consuming = false
                        // Strip-local y is relative to the bottom strip; convert to display y by
                        // adding the strip's on-screen top (screen height − strip height).
                        // NOTE: maxHeight is a Dp — convert to px first (same bug class as the
                        // right edge above).
                        val displayX = offset.x
                        val maxHeightPx = with(density) { maxHeight.toPx() }
                        val displayY = screenHeightPx - maxHeightPx + offset.y
                        handler.onDown(displayX, displayY, screenWidthPx, screenHeightPx)
                        consuming = handler.session.value != null
                    },
                    onDragEnd = { handler.onUp() },
                    onDragCancel = { handler.onUp() },
                    onDrag = { change, dragAmount ->
                        if (consuming) {
                            change.consume()
                            totalDragX += dragAmount.x
                            totalDragY += dragAmount.y
                            handler.onMove(dragAmount.x, dragAmount.y, totalDragX, totalDragY)
                        } else if (handler.session.value != null) {
                            consuming = true
                            change.consume()
                            totalDragX += dragAmount.x
                            totalDragY += dragAmount.y
                            handler.onMove(dragAmount.x, dragAmount.y, totalDragX, totalDragY)
                        }
                    }
                )
            }
        )

        GestureBottomHandle(session = session, modifier = Modifier.fillMaxSize())
    }
}
