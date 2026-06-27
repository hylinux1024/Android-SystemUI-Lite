package com.android.systemui.lite.systemui.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.lite.systemui.model.NotificationItem
import com.android.systemui.lite.systemui.model.NotificationType
import com.android.systemui.lite.systemui.viewmodel.SystemUIViewModel

@Composable
fun NotificationShade(
    viewModel: SystemUIViewModel,
    themeColor: Color,
    isWifiOn: Boolean,
    isBluetoothOn: Boolean,
    isDoNotDisturb: Boolean,
    isFlashlightOn: Boolean,
    isAirplaneMode: Boolean,
    isAutoRotateOn: Boolean,
    isScreenRecording: Boolean,
    brightness: Float,
    mediaVolume: Float,
    notifications: List<NotificationItem>,
    isResourceMonitorActive: Boolean,
    onDismissNotification: (Any) -> Unit,
    onClearAllNotifications: () -> Unit,
    onCloseShade: () -> Unit = { viewModel.toggleNotificationShade() }
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(16.dp)
            .pointerInput(Unit) {
                var totalDragY = 0f
                detectDragGestures(
                    onDragStart = { totalDragY = 0f },
                    onDragEnd = {
                        if (totalDragY < -40f) {
                            onCloseShade()
                        }
                    },
                    onDragCancel = {},
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDragY += dragAmount.y
                    }
                )
            }
    ) {
        // --- 1. Quick Settings Grid (3x3) ---
        val toggles = listOf(
            Triple("Wi-Fi", isWifiOn, { viewModel.toggleWifi() }),
            Triple("Bluetooth", isBluetoothOn, { viewModel.toggleBluetooth() }),
            Triple("DND", isDoNotDisturb, { viewModel.toggleDnd() }),
            Triple("Flashlight", isFlashlightOn, { viewModel.toggleFlashlight() }),
            Triple("Airplane", isAirplaneMode, { viewModel.toggleAirplaneMode() }),
            Triple("Auto-Rotate", isAutoRotateOn, { viewModel.toggleAutoRotate() }),
            Triple("Screen Rec", isScreenRecording, { viewModel.toggleScreenRecording() })
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Quick Settings", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onCloseShade() }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                toggles.take(3).forEach { tile ->
                    QSTile(label = tile.first, isActive = tile.second, onClick = tile.third, themeColor = themeColor, modifier = Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                toggles.drop(3).take(3).forEach { tile ->
                    QSTile(label = tile.first, isActive = tile.second, onClick = tile.third, themeColor = themeColor, modifier = Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                toggles.drop(6).firstOrNull()?.let { tile ->
                    QSTile(label = tile.first, isActive = tile.second, onClick = tile.third, themeColor = themeColor, modifier = Modifier.weight(0.33f))
                }
                Spacer(modifier = Modifier.weight(0.67f))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- 2. Brightness Slider & Volume Slider ---
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Star, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Slider(
                    value = brightness,
                    onValueChange = { viewModel.setBrightness(it) },
                    colors = SliderDefaults.colors(
                        thumbColor = themeColor,
                        activeTrackColor = themeColor,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Favorite, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Slider(
                    value = mediaVolume,
                    onValueChange = { viewModel.setMediaVolume(it) },
                    colors = SliderDefaults.colors(
                        thumbColor = themeColor,
                        activeTrackColor = themeColor,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // --- 3. Music Media Controller Widget inside Shade ---
        val musicNotif = notifications.find { it.type == NotificationType.MUSIC }
        if (musicNotif != null) {
            MediaControlShadeWidget(
                item = musicNotif,
                themeColor = themeColor,
                onPlayPause = { viewModel.togglePlayPauseMusic() },
                onPrev = { viewModel.skipPrevTrack() },
                onNext = { viewModel.skipNextTrack() }
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // --- 4. Notifications Scrolling Stack ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Notifications", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "Clear All",
                color = themeColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onClearAllNotifications() }
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        Box(modifier = Modifier.weight(1f)) {
            val cleanNotifs = notifications.filter { it.type != NotificationType.MUSIC }
            if (cleanNotifs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No recent notifications", color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(cleanNotifs, key = { it.id }) { item ->
                        ShadeNotificationCard(item = item, themeColor = themeColor, onDismiss = { onDismissNotification(item.id) })
                    }
                }
            }
        }
    }
}

@Composable
fun QSTile(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    themeColor: Color,
    modifier: Modifier = Modifier
) {
    val activeBg = Color(0xFFD3E4FF)
    val inactiveBg = Color(0xFF30343A)
    val activeTextColor = Color(0xFF001C38)
    val inactiveTextColor = Color(0xFFE2E2E6)

    Column(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .background(
                if (isActive) activeBg else inactiveBg,
                RoundedCornerShape(16.dp)
            )
            .border(
                1.dp,
                if (isActive) Color.Transparent else Color.White.copy(alpha = 0.05f),
                RoundedCornerShape(16.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = when (label) {
                "Wi-Fi" -> Icons.Default.Favorite
                "Bluetooth" -> Icons.Default.Share
                "DND" -> Icons.Default.Close
                "Flashlight" -> Icons.Default.Star
                "Airplane" -> Icons.Default.Info
                "Auto-Rotate" -> Icons.Default.Refresh
                else -> Icons.Default.Notifications
            },
            contentDescription = label,
            tint = if (isActive) activeTextColor else inactiveTextColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (isActive) activeTextColor else inactiveTextColor.copy(alpha = 0.8f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun MediaControlShadeWidget(
    item: NotificationItem,
    themeColor: Color,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Art
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(themeColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(16.dp)) {
                drawCircle(color = themeColor, radius = size.width / (if (item.isPlaying) 3f else 4f))
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
        ) {
            Text(item.trackTitle, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(item.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp, maxLines = 1)
        }

        // Media buttons
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Prev",
                tint = Color.White,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onPrev() }
            )
            Icon(
                imageVector = if (item.isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                contentDescription = "PlayPause",
                tint = themeColor,
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onPlayPause() }
            )
            Icon(
                imageVector = Icons.Default.Done,
                contentDescription = "Next",
                tint = Color.White,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onNext() }
            )
        }
    }
}

@Composable
fun ShadeNotificationCard(
    item: NotificationItem,
    themeColor: Color,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(themeColor.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (item.type) {
                    NotificationType.MESSAGE -> Icons.Default.Favorite
                    NotificationType.EMAIL -> Icons.Default.Email
                    NotificationType.SYSTEM_ALERT -> Icons.Default.Warning
                    else -> Icons.Default.Notifications
                },
                contentDescription = null,
                tint = themeColor,
                modifier = Modifier.size(12.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(item.appName, color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text(item.timestamp, color = Color.White.copy(alpha = 0.4f), fontSize = 8.sp)
            }
            Text(item.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.text, color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Dismiss",
            tint = Color.White.copy(alpha = 0.4f),
            modifier = Modifier
                .size(16.dp)
                .clickable { onDismiss() }
        )
    }
}
