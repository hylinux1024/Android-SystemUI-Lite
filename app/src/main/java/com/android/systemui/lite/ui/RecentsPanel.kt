package com.android.systemui.lite.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.lite.model.RecentTask

/**
 * Full-screen recents panel — the SystemUI-Lite2 equivalent of AOSP's RecentsActivity. Shows a
 * vertical list of [RecentTask] cards; tapping a card brings that task to front, swiping a card
 * up removes it from recents. Hosted in its own WindowManager overlay by
 * [com.android.systemui.lite.core.RecentsCoreStartable].
 */
@Composable
fun RecentsPanel(
    tasks: List<RecentTask>,
    onOpenTask: (RecentTask) -> Unit,
    onDismissTask: (RecentTask) -> Unit,
    onClose: () -> Unit
) {
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClose() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp, bottom = 32.dp, start = 16.dp, end = 16.dp)
        ) {
            Text(
                text = "Recent apps",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (tasks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No recent apps",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 16.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 64.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(items = tasks, key = { it.persistentId }) { task ->
                        RecentTaskCard(
                            task = task,
                            density = density,
                            onClick = { onOpenTask(task) },
                            onSwipeDismiss = { onDismissTask(task) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentTaskCard(
    task: RecentTask,
    density: androidx.compose.ui.unit.Density,
    onClick: () -> Unit,
    onSwipeDismiss: () -> Unit
) {
    var dragY by remember { mutableFloatStateOf(0f) }
    val dismissPx = with(density) { 120.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .pointerInput(task.persistentId) {
                detectDragGestures(
                    onDragEnd = {
                        if (dragY < -dismissPx) onSwipeDismiss()
                        dragY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragY += dragAmount.y
                    }
                )
            }
            .clickable { onClick() }
            .offset { IntOffset(0, if (dragY < 0f) dragY.toInt() else 0) },
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TaskIcon(task.icon, task.thumbnail)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.label,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (task.isRunning) "Running" else "Cached",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TaskIcon(icon: Drawable?, thumbnail: android.graphics.Bitmap?) {
    val bitmap = thumbnail ?: icon?.toBitmap(
        width = 96, height = 96, config = android.graphics.Bitmap.Config.ARGB_8888
    )
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.2f))
        )
    }
}
