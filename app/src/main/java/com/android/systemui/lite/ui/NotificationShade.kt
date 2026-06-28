package com.android.systemui.lite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.lite.model.NotificationItem
import com.android.systemui.lite.model.NotificationType

@Composable
fun NotificationShade(
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
    listenerConnected: Boolean = true,
    isResourceMonitorActive: Boolean,
    statusBarHeightDp: Int,
    onToggleWifi: () -> Unit,
    onToggleBluetooth: () -> Unit,
    onToggleDnd: () -> Unit,
    onToggleFlashlight: () -> Unit,
    onToggleAirplaneMode: () -> Unit,
    onToggleAutoRotate: () -> Unit,
    onToggleScreenRecording: () -> Unit,
    onSetBrightness: (Float) -> Unit,
    onSetMediaVolume: (Float) -> Unit,
    onDismissNotification: (Any) -> Unit,
    onClearAllNotifications: () -> Unit,
    onCloseShade: () -> Unit,
    onNotificationClick: (NotificationItem) -> Unit,
    onPlayPauseMusic: () -> Unit,
    onPrevTrack: () -> Unit,
    onNextTrack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState()
    var accumCloseY by remember { mutableFloatStateOf(0f) }

    val shadeCloseConnection = remember(lazyListState) {
        object : NestedScrollConnection {
            private fun isAtTop() = lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y < 0) {
                    val atTop = isAtTop()
                    if (atTop || source != NestedScrollSource.UserInput) {
                        accumCloseY += -available.y
                        return androidx.compose.ui.geometry.Offset(0f, available.y)
                    }
                }
                accumCloseY = 0f
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (available.y < 0 && accumCloseY > 120f) {
                    onCloseShade()
                    accumCloseY = 0f
                    return available
                }
                accumCloseY = 0f
                return Velocity.Zero
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(top = (statusBarHeightDp + 16).dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
            .nestedScroll(shadeCloseConnection)
    ) {
        Box(Modifier.scrollable(rememberScrollState(), Orientation.Vertical)) {
            Column {
                val toggles = listOf(
                    Triple("Wi-Fi", isWifiOn, onToggleWifi),
                    Triple("Bluetooth", isBluetoothOn, onToggleBluetooth),
                    Triple("DND", isDoNotDisturb, onToggleDnd),
                    Triple("Flashlight", isFlashlightOn, onToggleFlashlight),
                    Triple("Airplane", isAirplaneMode, onToggleAirplaneMode),
                    Triple("Auto-Rotate", isAutoRotateOn, onToggleAutoRotate),
                    Triple("Screen Rec", isScreenRecording, onToggleScreenRecording)
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Quick Settings", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.Settings, null, tint = Color.White, modifier = Modifier.size(18.dp).clickable { onCloseShade() })
                }
                Spacer(Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Row 1
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        toggles.take(3).forEach { tile ->
                            QSTile(tile.first, tile.second, tile.third, themeColor, Modifier.weight(1f))
                        }
                    }
                    // Row 2
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        toggles.drop(3).take(3).forEach { tile ->
                            QSTile(tile.first, tile.second, tile.third, themeColor, Modifier.weight(1f))
                        }
                    }
                    // Row 3
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        toggles.drop(6).firstOrNull()?.let { tile ->
                            QSTile(tile.first, tile.second, tile.third, themeColor, Modifier.weight(0.33f))
                        }
                        Spacer(Modifier.weight(0.67f))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Sliders
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Star, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Slider(value = brightness, onValueChange = onSetBrightness,
                            colors = SliderDefaults.colors(thumbColor = themeColor, activeTrackColor = themeColor, inactiveTrackColor = Color.White.copy(alpha = 0.15f)),
                            modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Favorite, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Slider(value = mediaVolume, onValueChange = onSetMediaVolume,
                            colors = SliderDefaults.colors(thumbColor = themeColor, activeTrackColor = themeColor, inactiveTrackColor = Color.White.copy(alpha = 0.15f)),
                            modifier = Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Media
                val musicNotif = notifications.find { it.type == NotificationType.MUSIC }
                if (musicNotif != null) {
                    MediaControlShadeWidget(musicNotif, themeColor, onPlayPauseMusic, onPrevTrack, onNextTrack)
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        // Notifications
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            val hasClearable = notifications.any { it.type != NotificationType.MUSIC && it.isClearable }
            if (hasClearable) {
                Text("Clear All", color = themeColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onClearAllNotifications() })
            }
        }
        Spacer(Modifier.height(6.dp))

        Box(Modifier.weight(1f)) {
            val cleanNotifs = notifications.filter { it.type != NotificationType.MUSIC }
            if (cleanNotifs.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .scrollable(rememberScrollState(), Orientation.Vertical),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (listenerConnected) "No recent notifications" else "Listener not connected",
                            color = Color.White.copy(alpha = 0.3f),
                            fontSize = 11.sp
                        )
                        if (!listenerConnected) {
                            Text(
                                "Check logcat: SystemNotificationListener",
                                color = Color(0xFFFF6B6B).copy(alpha = 0.5f),
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            } else {
                LazyColumn(state = lazyListState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    items(cleanNotifs, key = { it.id }) { item ->
                        ShadeNotificationCard(item, themeColor, { onDismissNotification(item.id) }, { onNotificationClick(item) })
                    }
                }
            }
        }
    }
}

@Composable
fun QSTile(label: String, isActive: Boolean, onClick: () -> Unit, themeColor: Color, modifier: Modifier = Modifier) {
    val activeBg = Color(0xFFD3E4FF)
    val inactiveBg = Color(0xFF30343A)
    val activeTextColor = Color(0xFF001C38)
    val inactiveTextColor = Color(0xFFE2E2E6)
    Column(
        modifier.shadow(4.dp, RoundedCornerShape(16.dp)).background(if (isActive) activeBg else inactiveBg, RoundedCornerShape(16.dp))
            .border(1.dp, if (isActive) Color.Transparent else Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .clickable { onClick() }.padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Icon(imageVector = when (label) {
            "Wi-Fi" -> Icons.Default.Favorite; "Bluetooth" -> Icons.Default.Share; "DND" -> Icons.Default.Close
            "Flashlight" -> Icons.Default.Star; "Airplane" -> Icons.Default.Info; "Auto-Rotate" -> Icons.Default.Refresh
            else -> Icons.Default.Notifications
        }, contentDescription = label, tint = if (isActive) activeTextColor else inactiveTextColor, modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (isActive) activeTextColor else inactiveTextColor.copy(alpha = 0.8f), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MediaControlShadeWidget(item: NotificationItem, themeColor: Color, onPlayPause: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(themeColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(16.dp)) { drawCircle(color = themeColor, radius = size.width / (if (item.isPlaying) 3f else 4f)) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(item.trackTitle, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(item.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.ArrowBack, "Prev", tint = Color.White, modifier = Modifier.size(20.dp).clickable { onPrev() })
            Icon(if (item.isPlaying) Icons.Default.Close else Icons.Default.PlayArrow, "PlayPause", tint = themeColor, modifier = Modifier.size(24.dp).clickable { onPlayPause() })
            Icon(Icons.Default.Done, "Next", tint = Color.White, modifier = Modifier.size(20.dp).clickable { onNext() })
        }
    }
}

@Composable
fun ShadeNotificationCard(item: NotificationItem, themeColor: Color, onDismiss: () -> Unit, onClick: () -> Unit) {
    val cardModifier = if (item.contentIntent != null) {
        Modifier.clickable { onClick() }
    } else {
        Modifier
    }
    Row(
        cardModifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(24.dp).background(themeColor.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(imageVector = when (item.type) {
                NotificationType.MESSAGE -> Icons.Default.Favorite; NotificationType.EMAIL -> Icons.Default.Email
                NotificationType.SYSTEM_ALERT -> Icons.Default.Warning; else -> Icons.Default.Notifications
            }, null, tint = themeColor, modifier = Modifier.size(12.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(item.appName, color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text(item.timestamp, color = Color.White.copy(alpha = 0.4f), fontSize = 8.sp)
            }
            Text(item.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.text, color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (item.isClearable) {
            Icon(Icons.Default.Close, "Dismiss", tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(16.dp).clickable { onDismiss() })
        }
    }
}

