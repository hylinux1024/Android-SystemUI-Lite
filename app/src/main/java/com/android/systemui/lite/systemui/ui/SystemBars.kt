package com.android.systemui.lite.systemui.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.lite.systemui.model.BatteryPercentageStyle
import com.android.systemui.lite.systemui.model.ClockPosition
import com.android.systemui.lite.systemui.model.NavigationMode
import com.android.systemui.lite.systemui.model.NotificationItem
import com.android.systemui.lite.systemui.model.NotificationType
import com.android.systemui.lite.systemui.viewmodel.SystemUIViewModel

@Composable
fun SystemBars(
    viewModel: SystemUIViewModel,
    modifier: Modifier = Modifier
) {
    val isWifiOn by viewModel.isWifiOn.collectAsState()
    val isBluetoothOn by viewModel.isBluetoothOn.collectAsState()
    val isDoNotDisturb by viewModel.isDoNotDisturb.collectAsState()
    val isFlashlightOn by viewModel.isFlashlightOn.collectAsState()
    val isAirplaneMode by viewModel.isAirplaneMode.collectAsState()
    val isAutoRotateOn by viewModel.isAutoRotateOn.collectAsState()
    val isScreenRecording by viewModel.isScreenRecording.collectAsState()
    val batteryLevel by viewModel.batteryLevel.collectAsState()
    val isCharging by viewModel.isCharging.collectAsState()
    val brightness by viewModel.brightness.collectAsState()
    val mediaVolume by viewModel.mediaVolume.collectAsState()
    val timeString by viewModel.timeString.collectAsState()

    val isLocked by viewModel.isLocked.collectAsState()
    val usePinSecurity by viewModel.usePinSecurity.collectAsState()
    val enteredPin by viewModel.enteredPin.collectAsState()
    val lockscreenError by viewModel.lockscreenError.collectAsState()

    val navigationMode by viewModel.navigationMode.collectAsState()
    val activeGestureFeedback by viewModel.activeGestureFeedback.collectAsState()

    val notifications by viewModel.notifications.collectAsState()

    val statusBarHeight by viewModel.statusBarHeight.collectAsState()
    val statusBarIconSize by viewModel.statusBarIconSize.collectAsState()
    val clockPosition by viewModel.clockPosition.collectAsState()
    val batteryStyle by viewModel.batteryStyle.collectAsState()

    val themeColor by viewModel.themeColor.collectAsState()
    val selectedWallpaperId by viewModel.selectedWallpaperId.collectAsState()
    val activeScreen by viewModel.activeScreen.collectAsState()

    val wallpaper = viewModel.wallpaperList[selectedWallpaperId]

    // Active plugins state check
    val plugins by viewModel.plugins.collectAsState()
    val isDynamicIslandActive = plugins.find { it.id == "dynamic_island" }?.isEnabled == true
    val isTrafficActive = plugins.find { it.id == "traffic_indicator" }?.isEnabled == true
    val isCyberClockActive = plugins.find { it.id == "cyber_clock" }?.isEnabled == true
    val isResourceMonitorActive = plugins.find { it.id == "resource_monitor" }?.isEnabled == true

    // Animate wallpaper change smoothly
    val wallpaperBrush = remember(selectedWallpaperId) {
        Brush.linearGradient(
            colors = wallpaper.colors,
            start = Offset(0f, 0f),
            end = Offset(1000f, 1500f)
        )
    }

    Box(
        modifier = modifier
            .testTag("simulated_phone_container")
            .shadow(16.dp, RoundedCornerShape(32.dp))
            .aspectRatio(0.482f) // Standard 19.5:9 smartphone aspect ratio
            .border(4.dp, Color(0xFF1E293B), RoundedCornerShape(32.dp))
            .clip(RoundedCornerShape(32.dp))
            .background(wallpaperBrush)
    ) {
        // --- 1. Launcher Home Screen UI (when unlocked and shade not pulled) ---
        if (!isLocked && activeScreen == "Home") {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        var totalDragY = 0f
                        detectDragGestures(
                            onDragStart = { totalDragY = 0f },
                            onDragEnd = {
                                if (totalDragY > 40f) {
                                    viewModel.setScreen("NotificationShade")
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
                LauncherWorkspace(
                    themeColor = themeColor,
                    isResourceMonitorActive = isResourceMonitorActive,
                    onAppClick = { appName ->
                        viewModel.addCustomNotification(
                            appName = appName,
                            title = "App Opened",
                            text = "The app '$appName' was successfully initialized inside SystemUI environment.",
                            type = NotificationType.GENERIC
                        )
                    }
                )
            }
        }

        // --- 2. Recents Overview ---
        if (!isLocked && activeScreen == "Recents") {
            RecentsOverview(
                themeColor = themeColor,
                onClearAll = { viewModel.setScreen("Home") }
            )
        }

        // --- 3. Lockscreen View ---
        if (isLocked) {
            LockscreenView(
                timeString = timeString,
                themeColor = themeColor,
                usePinSecurity = usePinSecurity,
                enteredPin = enteredPin,
                lockscreenError = lockscreenError,
                isCyberClockActive = isCyberClockActive,
                notifications = notifications,
                onPinDigit = { viewModel.handlePinInput(it) },
                onPinDelete = { viewModel.deletePinDigit() },
                onSwipeUnlock = { viewModel.swipeToUnlock() },
                onDismissNotification = { viewModel.dismissNotification(it) }
            )
        }

        // --- 4. Notification Shade Overlay (Pulled Down) ---
        AnimatedVisibility(
            visible = !isLocked && activeScreen == "NotificationShade",
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = spring(stiffness = Spring.StiffnessLow)
            ),
            exit = slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = spring(stiffness = Spring.StiffnessLow)
            )
        ) {
            NotificationShade(
                viewModel = viewModel,
                themeColor = themeColor,
                isWifiOn = isWifiOn,
                isBluetoothOn = isBluetoothOn,
                isDoNotDisturb = isDoNotDisturb,
                isFlashlightOn = isFlashlightOn,
                isAirplaneMode = isAirplaneMode,
                isAutoRotateOn = isAutoRotateOn,
                isScreenRecording = isScreenRecording,
                brightness = brightness,
                mediaVolume = mediaVolume,
                notifications = notifications,
                isResourceMonitorActive = isResourceMonitorActive,
                onDismissNotification = { viewModel.dismissNotification(it) },
                onClearAllNotifications = { viewModel.clearAllNotifications() }
            )
        }

        // --- 5. Custom Status Bar (Always at top) ---
        StatusBar(
            heightDp = statusBarHeight,
            iconSizeDp = statusBarIconSize,
            clockPosition = clockPosition,
            batteryStyle = batteryStyle,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            isWifiOn = isWifiOn,
            isBluetoothOn = isBluetoothOn,
            isDoNotDisturb = isDoNotDisturb,
            isAirplaneMode = isAirplaneMode,
            timeString = timeString,
            isTrafficActive = isTrafficActive,
            themeColor = themeColor,
            onShadeToggle = { viewModel.toggleNotificationShade() }
        )

        // --- 6. Dynamic Island Cutout (If active) ---
        if (isDynamicIslandActive) {
            DynamicIsland(
                notifications = notifications,
                themeColor = themeColor,
                onPlayPause = { viewModel.togglePlayPauseMusic() },
                onNext = { viewModel.skipNextTrack() }
            )
        }

        // --- 7. Navigation Bar (Always at bottom) ---
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            if (navigationMode == NavigationMode.THREE_BUTTON) {
                ThreeButtonNavigationBar(
                    themeColor = themeColor,
                    onBack = { viewModel.triggerGesture("Back Button Tapped") },
                    onHome = { viewModel.setScreen("Home") },
                    onRecents = { viewModel.setScreen("Recents") }
                )
            } else {
                GestureNavigationBar(
                    themeColor = themeColor,
                    onGesture = { viewModel.triggerGesture(it) }
                )
            }
        }

        // --- 8. Simulated Edge Swipe Zones for Gesture Back ---
        if (navigationMode == NavigationMode.GESTURES) {
            // Left edge
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(16.dp)
                    .align(Alignment.CenterStart)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {},
                            onDragEnd = { viewModel.triggerGesture("Swipe Left -> BACK") },
                            onDragCancel = {},
                            onDrag = { change, dragAmount ->
                                if (dragAmount.x > 8) {
                                    change.consume()
                                }
                            }
                        )
                    }
            )
            // Right edge
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(16.dp)
                    .align(Alignment.CenterEnd)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {},
                            onDragEnd = { viewModel.triggerGesture("Swipe Right -> BACK") },
                            onDragCancel = {},
                            onDrag = { change, dragAmount ->
                                if (dragAmount.x < -8) {
                                    change.consume()
                                }
                            }
                        )
                    }
            )
        }

        // --- 9. Temporary Gesture Feedback Alert Overlay (HUD) ---
        AnimatedVisibility(
            visible = activeGestureFeedback != null,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            activeGestureFeedback?.let { feedback ->
                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                        .border(1.dp, themeColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                feedback.contains("BACK") -> Icons.Default.ArrowBack
                                feedback.contains("HOME") -> Icons.Default.Home
                                feedback.contains("RECENTS") -> Icons.Default.Menu
                                else -> Icons.Default.Notifications
                            },
                            contentDescription = null,
                            tint = themeColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = feedback,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// DYNAMIC ISLAND CUTOUT
// ==========================================
@Composable
fun BoxScope.DynamicIsland(
    notifications: List<NotificationItem>,
    themeColor: Color,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    val musicNotif = notifications.find { it.type == NotificationType.MUSIC }
    val latestNotif = notifications.firstOrNull { it.type != NotificationType.MUSIC }

    val hasEvent = musicNotif != null || latestNotif != null
    val isPlaying = musicNotif?.isPlaying == true

    var isExpanded by remember { mutableStateOf(false) }

    val width by animateDpAsState(
        targetValue = when {
            !hasEvent -> 110.dp
            isExpanded -> 280.dp
            isPlaying -> 180.dp
            else -> 150.dp
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
    )

    val height by animateDpAsState(
        targetValue = when {
            isExpanded -> if (musicNotif != null) 76.dp else 56.dp
            else -> 28.dp
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
    )

    Box(
        modifier = Modifier
            .padding(top = 40.dp)
            .width(width)
            .height(height)
            .shadow(8.dp, RoundedCornerShape(20.dp))
            .background(Color.Black, RoundedCornerShape(20.dp))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
            .align(Alignment.TopCenter)
            .clickable { isExpanded = !isExpanded }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isExpanded) {
            if (musicNotif != null) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: pulsing visual representation of disk/album cover
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(themeColor.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(24.dp)) {
                            drawCircle(color = themeColor, radius = size.width / (if (isPlaying) 3f else 4f))
                        }
                    }
                    // Mid: Titles
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = musicNotif.trackTitle,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = musicNotif.artist,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Right: controls
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(24.dp)
                                .clickable { onPlayPause() }
                        )
                        Icon(
                            imageVector = Icons.Default.Done, // placeholder for Next
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(24.dp)
                                .clickable { onNext() }
                        )
                    }
                }
            } else if (latestNotif != null) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(themeColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = latestNotif.title,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = latestNotif.text,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        } else {
            // Pill collapsed state
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isPlaying) {
                    // Tiny music wave
                    Canvas(modifier = Modifier.size(10.dp)) {
                        drawCircle(color = themeColor, radius = 3.dp.toPx())
                    }
                    Text("Playing", color = themeColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                } else if (latestNotif != null) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = themeColor,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(latestNotif.appName, color = Color.White, fontSize = 9.sp)
                } else {
                    // Notch camera
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(Color(0xFF1E293B), CircleShape)
                    )
                    Text("SystemUI", color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp)
                }
            }
        }
    }
}

// ==========================================
// KEYGUARD LOCKSCREEN
// ==========================================
@Composable
fun LockscreenView(
    timeString: String,
    themeColor: Color,
    usePinSecurity: Boolean,
    enteredPin: String,
    lockscreenError: String?,
    isCyberClockActive: Boolean,
    notifications: List<NotificationItem>,
    onPinDigit: (Char) -> Unit,
    onPinDelete: () -> Unit,
    onSwipeUnlock: () -> Unit,
    onDismissNotification: (Any) -> Unit
) {
    var swipeOffset by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {},
                    onDragEnd = {
                        if (swipeOffset < -150f) {
                            onSwipeUnlock()
                        }
                        swipeOffset = 0f
                    },
                    onDragCancel = { swipeOffset = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        swipeOffset += dragAmount.y
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 90.dp, bottom = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // --- TOP: Clock Section ---
            if (isCyberClockActive) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = timeString,
                        color = themeColor,
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.shadow(8.dp, CircleShape)
                    )
                    Text(
                        text = "CORE.STATUS: SECURED",
                        color = themeColor.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = timeString,
                        color = Color.White,
                        fontSize = 58.sp,
                        fontWeight = FontWeight.Light
                    )
                    Text(
                        text = "Thursday, June 25",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp
                    )
                }
            }

            // --- MIDDLE: Secure PIN Overlay vs Clean Notifications ---
            if (usePinSecurity) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp)
                ) {
                    Text(
                        text = "Enter Device PIN",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    // Dots
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (i in 0 until 4) {
                            val active = i < enteredPin.length
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        if (active) themeColor else Color.White.copy(alpha = 0.25f),
                                        CircleShape
                                    )
                            )
                        }
                    }

                    if (lockscreenError != null) {
                        Text(
                            text = lockscreenError,
                            color = Color(0xFFEF4444),
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // Keypad
                    val keys = listOf(
                        listOf('1', '2', '3'),
                        listOf('4', '5', '6'),
                        listOf('7', '8', '9'),
                        listOf('C', '0', 'X')
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        keys.forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                row.forEach { key ->
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .background(Color.White.copy(alpha = 0.1f), CircleShape)
                                            .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                                            .clickable {
                                                when (key) {
                                                    'C' -> { /* No op */ }
                                                    'X' -> onPinDelete()
                                                    else -> onPinDigit(key)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = key.toString(),
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Notifications scrolling area on Lockscreen
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 16.dp)
                ) {
                    if (notifications.isEmpty()) {
                        Text(
                            text = "No recent notifications",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(notifications.filter { it.type != NotificationType.MUSIC }) { item ->
                                LockscreenNotificationCard(item = item, onDismiss = { onDismissNotification(item.id) })
                            }
                        }
                    }
                }
            }

            // --- BOTTOM: Swipe to unlock prompt & Shortcuts ---
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!usePinSecurity) {
                    Text(
                        text = "↑ Swipe Up to Unlock",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.SansSerif
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = "Emergency Calls",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.White.copy(alpha = 0.15f), CircleShape)
                            .padding(8.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Camera Shortcut",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.White.copy(alpha = 0.15f), CircleShape)
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun LockscreenNotificationCard(
    item: NotificationItem,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(Color.White.copy(alpha = 0.15f), CircleShape),
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
                tint = Color.White,
                modifier = Modifier.size(12.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        ) {
            Text(
                text = "${item.appName} • ${item.title}",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.text,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Dismiss",
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier
                .size(16.dp)
                .clickable { onDismiss() }
        )
    }
}

// ==========================================
// LAUNCHER WORKSPACE (HOME SCREEN)
// ==========================================
@Composable
fun LauncherWorkspace(
    themeColor: Color,
    isResourceMonitorActive: Boolean,
    onAppClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 90.dp, bottom = 70.dp, start = 20.dp, end = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top: Custom Resource Monitor Widget if active, else Clock widget
        if (isResourceMonitorActive) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .border(1.dp, themeColor.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "SYSTEM TELEMETRY ENGINE",
                        color = themeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("CPU LOAD", color = Color.White.copy(alpha = 0.6f), fontSize = 8.sp)
                            Text("42% (A55 + A78)", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        Column {
                            Text("RAM USAGE", color = Color.White.copy(alpha = 0.6f), fontSize = 8.sp)
                            Text("5.8 GB / 12 GB", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        } else {
            // Cool elegant minimalist Clock widget
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "10:42",
                    color = Color.White,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Google AI Studio",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Mid: App Icons Grid
        val apps = listOf(
            Pair("Phone", Icons.Default.Phone),
            Pair("Messages", Icons.Default.Favorite),
            Pair("Gmail", Icons.Default.Email),
            Pair("Settings", Icons.Default.Settings)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            apps.forEach { app ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onAppClick(app.first) }
                        .padding(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = app.second,
                            contentDescription = app.first,
                            tint = themeColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = app.first,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Bottom: Quick Dock
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                .padding(vertical = 8.dp, horizontal = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val dockApps = listOf(Icons.Default.Phone, Icons.Default.Email, Icons.Default.Settings, Icons.Default.Star)
                dockApps.forEach { icon ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.Black.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// RECENTS OVERVIEW
// ==========================================
@Composable
fun RecentsOverview(
    themeColor: Color,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 90.dp, bottom = 70.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Recents Overview",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )

        // Stacked apps cards
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(130.dp)
                    .fillMaxHeight()
                    .shadow(4.dp, RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E293B), RoundedCornerShape(12.dp))
                    .border(1.dp, themeColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Settings, null, tint = themeColor, modifier = Modifier.size(12.dp))
                        Text("Settings", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                    )
                }
            }
        }

        Button(
            onClick = onClearAll,
            colors = ButtonDefaults.buttonColors(containerColor = themeColor),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Text("Clear All", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ==========================================
// NAVIGATION BARS (3-BUTTON & GESTURES)
// ==========================================
@Composable
fun ThreeButtonNavigationBar(
    themeColor: Color,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color.Black.copy(alpha = 0.3f))
            .padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back button (triangle)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(20.dp)) {
                val path = Path().apply {
                    moveTo(size.width, 0f)
                    lineTo(0f, size.height / 2)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(path = path, color = Color.White)
            }
        }
        // Home button (circle)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable { onHome() },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(20.dp)) {
                drawCircle(color = Color.White, style = Stroke(width = 2.dp.toPx()))
            }
        }
        // Recents button (square)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable { onRecents() },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(18.dp)) {
                drawRect(
                    color = Color.White,
                    style = Stroke(width = 2.dp.toPx()),
                    size = Size(size.width, size.height)
                )
            }
        }
    }
}

@Composable
fun GestureNavigationBar(
    themeColor: Color,
    onGesture: (String) -> Unit
) {
    var dragStartY by remember { mutableStateOf(0f) }
    var dragAccumulatedY by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(Color.Transparent)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragStartY = offset.y
                        dragAccumulatedY = 0f
                    },
                    onDragEnd = {
                        // Gesture complete
                        if (dragAccumulatedY < -80f) {
                            if (dragAccumulatedY < -150f) {
                                onGesture("Swipe Up & Hold -> RECENTS")
                            } else {
                                onGesture("Swipe Up -> HOME")
                            }
                        }
                    },
                    onDragCancel = {},
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragAccumulatedY += dragAmount.y
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Gesture pill line
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(4.dp)
                .background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(2.dp))
        )
    }
}

/**
 * NavigationBar wrapper - delegates to ThreeButtonNavigationBar or GestureNavigationBar
 * based on the current navigation mode.
 */
@Composable
fun NavigationBar(
    themeColor: Color,
    navigationMode: NavigationMode,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit
) {
    when (navigationMode) {
        NavigationMode.THREE_BUTTON -> {
            ThreeButtonNavigationBar(
                themeColor = themeColor,
                onBack = onBack,
                onHome = onHome,
                onRecents = onRecents
            )
        }
        NavigationMode.GESTURES -> {
            GestureNavigationBar(
                themeColor = themeColor,
                onGesture = { gesture ->
                    when {
                        gesture.contains("HOME") -> onHome()
                        gesture.contains("RECENTS") -> onRecents()
                        gesture.contains("BACK") -> onBack()
                    }
                }
            )
        }
    }
}
