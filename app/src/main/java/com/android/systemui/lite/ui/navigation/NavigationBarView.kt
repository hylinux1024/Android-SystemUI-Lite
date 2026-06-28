package com.android.systemui.lite.ui.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.android.systemui.lite.model.NavigationMode

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
        NavigationMode.GESTURES -> GestureNavigationBar(themeColor) { gesture ->
            when {
                gesture.contains("HOME") -> onHome()
                gesture.contains("RECENTS") -> onRecents()
                gesture.contains("BACK") -> onBack()
            }
        }
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
            Canvas(Modifier.size(18.dp)) { drawRect(Color.White, style = Stroke(width = 2.dp.toPx()), size = Size(size.width, size.height)) }
        }
    }
}

@Composable
fun GestureNavigationBar(themeColor: Color, onGesture: (String) -> Unit) {
    var dragAccumulatedY by remember { mutableStateOf(0f) }
    Box(
        Modifier.fillMaxWidth().height(24.dp).background(Color.Transparent)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragAccumulatedY = 0f },
                    onDragEnd = {
                        if (dragAccumulatedY < -80f) {
                            if (dragAccumulatedY < -150f) onGesture("Swipe Up & Hold -> RECENTS")
                            else onGesture("Swipe Up -> HOME")
                        }
                    },
                    onDragCancel = {},
                    onDrag = { change, dragAmount -> change.consume(); dragAccumulatedY += dragAmount.y }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.width(72.dp).height(4.dp).background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(2.dp)))
    }
}
