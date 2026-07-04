package com.android.systemui.lite.ui.navigation

import android.annotation.SuppressLint
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.android.systemui.lite.model.GestureType
import com.android.systemui.lite.model.NavigationMode
import com.android.systemui.lite.navigation.GestureEdgePanel
import com.android.systemui.lite.navigation.GestureHandler

@Composable
fun NavigationBarView(
    themeColor: Color,
    navigationMode: NavigationMode,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit
) {
    when (navigationMode) {
        NavigationMode.THREE_BUTTON -> ThreeButtonNavigationBar(themeColor, onBack, onHome, onRecents)
        NavigationMode.GESTURES -> GestureNavRoot(onAction = { gesture ->
            when (gesture) {
                GestureType.BACK -> onBack()
                GestureType.HOME -> onHome()
                GestureType.RECENTS -> onRecents()
            }
        })
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
 * Gesture-nav root. Holds a process-scoped [GestureHandler] fed by every pointer event in the
 * window and overlays the spring-animated back affordance on top. The Box fills its parent so it
 * covers both edge strips and the bottom zone (edge touches resolve to BACK, bottom touches to
 * HOME/RECENTS, with the zone locked at touch-down — see [GestureHandler]).
 *
 * The handler's [GestureHandler.onAction] is supplied by [onAction], letting the startable wire the
 * matching `sendKeyEvent` without this file knowing about key codes.
 */
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun GestureNavRoot(onAction: (GestureType) -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val handler = remember {
        GestureHandler(
            onAction = onAction,
            context = context,
            density = density,
            scope = scope
        )
    }
    val session by handler.session.collectAsState()
    val trackedType by handler.trackedType.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = maxWidth.value
        val heightPx = maxHeight.value

        var totalDragX by remember { mutableStateOf(0f) }
        var totalDragY by remember { mutableStateOf(0f) }

        // Full-window touch routing: every pointer-down/move/up flows through GestureHandler so
        // edge swipes become BACK, bottom swipes HOME/RECENTS, and hold-and-release RECENTS.
        Box(
            Modifier.fillMaxSize().pointerInput(widthPx, heightPx) {
                detectDragGestures(
                    onDragStart = { offset ->
                        totalDragX = 0f; totalDragY = 0f
                        handler.onDown(offset.x, offset.y, widthPx.toInt(), heightPx.toInt())
                    },
                    onDragEnd = { handler.onUp() },
                    onDragCancel = { handler.onUp() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDragX += dragAmount.x
                        totalDragY += dragAmount.y
                        handler.onMove(dragAmount.x, dragAmount.y, totalDragX, totalDragY)
                    }
                )
            }
        )

        // Edge-only visual affordance; renders nothing for bottom/home/recents sessions.
        GestureEdgePanel(
            session = session,
            gestureType = trackedType,
            modifier = Modifier.matchParentSize()
        )
    }
}
