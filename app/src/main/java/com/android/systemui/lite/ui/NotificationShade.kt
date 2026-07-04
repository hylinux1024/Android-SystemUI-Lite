package com.android.systemui.lite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.lite.model.NotificationItem
import com.android.systemui.lite.model.NotificationType

@Composable
fun NotificationShade(
    themeColor: Color,
    isWifiOn: Boolean,
    isBluetoothOn: Boolean,
    isBluetoothTransitioning: Boolean = false,
    isDoNotDisturb: Boolean,
    isFlashlightOn: Boolean,
    isFlashlightAvailable: Boolean = true,
    isAirplaneMode: Boolean,
    isAutoRotateOn: Boolean,
    isBatterySaverOn: Boolean,
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
    onToggleBatterySaver: () -> Unit,
    onToggleScreenRecording: () -> Unit,
    // Long-press callbacks — each fires after the shade closes. Tiles with no detail
    // panel pass a no-op lambda from ShadeCoreStartable.
    onLongPressWifi: () -> Unit = {},
    onLongPressBluetooth: () -> Unit = {},
    onLongPressDnd: () -> Unit = {},
    onLongPressFlashlight: () -> Unit = {},
    onLongPressAirplaneMode: () -> Unit = {},
    onLongPressAutoRotate: () -> Unit = {},
    onLongPressBatterySaver: () -> Unit = {},
    onLongPressScreenRecording: () -> Unit = {},
    onSetBrightness: (Float) -> Unit,
    onSetMediaVolume: (Float) -> Unit,
    onDismissNotification: (Any) -> Unit,
    onClearAllNotifications: () -> Unit,
    onCloseShade: () -> Unit,
    onDragShade: ((Float) -> Unit)? = null,
    onOpenShade: (() -> Unit)? = null,
    onNotificationClick: (NotificationItem) -> Unit,
    onPlayPauseMusic: () -> Unit,
    onPrevTrack: () -> Unit,
    onNextTrack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState()
    var viewHeightPx by remember { mutableFloatStateOf(0f) }
    val cleanNotifs = notifications.filter { it.type != NotificationType.MUSIC }
    // Touch slop used by the swipe-up-to-close detector below. Captured here (not
    // inside the suspend block) so the Modifier chain stays outside the recomposer.
    val touchSlop = LocalViewConfiguration.current.touchSlop

    Column(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewHeightPx = it.height.toFloat() }
            .background(Color.Black.copy(alpha = 0.92f))
            .pointerInput(Unit) {
                // Manual swipe-up-to-close detector running on the Initial pass
                // (parent-first), exactly like AOSP's NotificationPanelViewController
                // TouchHandler.onInterceptTouchEvent: the panel wins the gesture away
                // from children (LazyColumn, QS-tile scrollable, empty-state scrollable)
                // once an upward drag past touch slop begins while the panel is
                // collapsible — i.e. the list is scrolled to top OR the list is empty.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var prevTimeMs = down.uptimeMillis
                    var prevY = down.position.y
                    var totalDragY = 0f
                    var armed = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // Find the change for our pointer without a lambda predicate so the
                        // compiler can infer the type unambiguously.
                        var change: PointerInputChange? = null
                        for (c in event.changes) {
                            if (c.id == down.id) { change = c; break }
                        }
                        val current = change ?: break
                        if (!current.pressed) {
                            if (armed && viewHeightPx > 0f) {
                                val progress = (1f + totalDragY / viewHeightPx).coerceIn(0f, 1f)
                                val dtMs = (current.uptimeMillis - prevTimeMs).coerceAtLeast(1)
                                val velocityY = (current.position.y - prevY) / dtMs * 1000f
                                if (progress < 0.6f || velocityY < -600f) onCloseShade()
                                else onOpenShade?.invoke()
                            }
                            break
                        }
                        // Compose 1.7.x has no public positionChange(); compute the per-event
                        // delta manually from the previous position. This is exactly what
                        // positionChange() returns in newer Compose versions.
                        val dy = current.position.y - prevY
                        prevTimeMs = current.uptimeMillis
                        prevY = current.position.y
                        totalDragY += dy
                        val contentAtTop = lazyListState.firstVisibleItemIndex == 0 &&
                            lazyListState.firstVisibleItemScrollOffset == 0
                        if (!armed && totalDragY < -touchSlop &&
                            (contentAtTop || cleanNotifs.isEmpty())) {
                            armed = true
                        }
                        if (armed) {
                            current.consume()
                            if (viewHeightPx > 0f) {
                                val progress = (1f + totalDragY / viewHeightPx).coerceIn(0f, 1f)
                                onDragShade?.invoke(progress)
                            }
                        }
                    }
                }
            }
            .padding(top = (statusBarHeightDp + 16).dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        Box(Modifier.scrollable(rememberScrollState(), Orientation.Vertical)) {
            Column {
                // Each tile carries its active state, an optional per-tile transitioning
                // flag (Bluetooth — US-005), and an optional availability flag
                // (Flashlight — US-006: disabled/grey when the device has no torch).
                data class Toggle(
                    val label: String,
                    val active: Boolean,
                    val transitioning: Boolean = false,
                    val available: Boolean = true,
                    val onToggle: () -> Unit,
                    val onLongPress: () -> Unit = {}
                )

                val toggles = listOf(
                    Toggle("Wi-Fi", isWifiOn, onToggle = { onToggleWifi() }, onLongPress = onLongPressWifi),
                    Toggle("Bluetooth", isBluetoothOn, isBluetoothTransitioning, onToggle = { onToggleBluetooth() }, onLongPress = onLongPressBluetooth),
                    Toggle("DND", isDoNotDisturb, onToggle = { onToggleDnd() }, onLongPress = onLongPressDnd),
                    Toggle("Flashlight", isFlashlightOn, available = isFlashlightAvailable, onToggle = { onToggleFlashlight() }, onLongPress = onLongPressFlashlight),
                    Toggle("Airplane", isAirplaneMode, onToggle = { onToggleAirplaneMode() }, onLongPress = onLongPressAirplaneMode),
                    Toggle("Auto-Rotate", isAutoRotateOn, onToggle = { onToggleAutoRotate() }, onLongPress = onLongPressAutoRotate),
                    Toggle("Battery Saver", isBatterySaverOn, onToggle = { onToggleBatterySaver() }, onLongPress = onLongPressBatterySaver),
                    Toggle("Screen Rec", isScreenRecording, onToggle = { onToggleScreenRecording() }, onLongPress = onLongPressScreenRecording)
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Quick Settings", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.Settings, null, tint = Color.White, modifier = Modifier.size(18.dp).clickable { onCloseShade() })
                }
                Spacer(Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Row 1 — tiles 1..4
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        toggles.take(4).forEach { tile ->
                            QSTile(tile.label, tile.active, tile.transitioning, tile.available, tile.onToggle, themeColor, Modifier.weight(1f), tile.onLongPress)
                        }
                    }
                    // Row 2 — tiles 5..8
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        toggles.drop(4).forEach { tile ->
                            QSTile(tile.label, tile.active, tile.transitioning, tile.available, tile.onToggle, themeColor, Modifier.weight(1f), tile.onLongPress)
                        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QSTile(label: String, isActive: Boolean, isTransitioning: Boolean = false, isAvailable: Boolean = true, onClick: () -> Unit, themeColor: Color, modifier: Modifier = Modifier, onLongClick: () -> Unit = {}) {
    val activeBg = Color(0xFFD3E4FF)
    val inactiveBg = Color(0xFF30343A)
    val activeTextColor = Color(0xFF001C38)
    val inactiveTextColor = Color(0xFFE2E2E6)
    // transition colors
    val transitionBg = themeColor.copy(alpha = 0.18f)
    val transitionTextColor = Color(0xFFE2E2E6)
    // unavailable (no torch / hardware missing) — flat grey, no highlight, no press.
    val unavailableBg = Color(0xFF1C1C1E)
    val unavailableTextColor = Color.White.copy(alpha = 0.28f)

    // When unavailable, tile is neither active nor press-reacting; show a static
    // greyscale visual so the user understands the feature is missing (US-006 AC4).
    val bg = when {
        !isAvailable -> unavailableBg
        isTransitioning -> transitionBg
        isActive -> activeBg
        else -> inactiveBg
    }
    val border = when {
        !isAvailable -> Color.White.copy(alpha = 0.04f)
        isTransitioning -> themeColor
        isActive -> Color.Transparent
        else -> Color.White.copy(alpha = 0.05f)
    }
    val textColor = when {
        !isAvailable -> unavailableTextColor
        isTransitioning -> transitionTextColor
        isActive -> activeTextColor
        else -> inactiveTextColor.copy(alpha = 0.8f)
    }
    val iconColor = when {
        !isAvailable -> unavailableTextColor
        isTransitioning -> transitionTextColor
        isActive -> activeTextColor
        else -> inactiveTextColor
    }

    // Recording pulse: for an active Screen Rec tile, pulse a red dot so the tile's
    // recording state is clearly "animated" (US-009 AC3). Non-recording tiles bypass
    // the transition entirely so we don't pay an extra animation frame per frame.
    val showRecordingPulse = isActive && label == "Screen Rec"
    val recordingPulse by animateRecordingPulse(showRecordingPulse, label)

    Column(
        modifier.shadow(4.dp, RoundedCornerShape(16.dp)).background(bg, RoundedCornerShape(16.dp))
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .combinedClickable(enabled = isAvailable && !isTransitioning, onClick = onClick, onLongClick = onLongClick).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Box {
            Icon(imageVector = when (label) {
                "Wi-Fi" -> Icons.Filled.Wifi
                "Bluetooth" -> Icons.Filled.Bluetooth
                "DND" -> Icons.Filled.NotificationsOff
                "Flashlight" -> Icons.Filled.FlashlightOn
                "Airplane" -> Icons.Filled.Flight
                "Auto-Rotate" -> Icons.Filled.ScreenRotation
                "Battery Saver" -> Icons.Filled.BatterySaver
                "Screen Rec" -> Icons.Filled.FiberManualRecord
                else -> Icons.Filled.Circle
            }, contentDescription = label, tint = iconColor, modifier = Modifier.size(16.dp))
            if (isActive && label == "Screen Rec") {
                // Pulsing red "recording" dot in the corner beside the icon.
                Canvas(Modifier.size(8.dp).align(Alignment.TopEnd).offset(x = 7.dp, y = (-5).dp)) {
                    drawCircle(color = Color(0xFFFF3B30).copy(alpha = recordingPulse), radius = size.minDimension / 2f)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = textColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun animateRecordingPulse(active: Boolean, label: String): State<Float> {
    if (!active) return remember { mutableStateOf(0f) }
    return rememberInfiniteTransition(label = "sc-$label-rec-pulse")
        .animateFloat(
            initialValue = 0.35f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "sc-$label-rec-pulse-alpha"
        )
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

