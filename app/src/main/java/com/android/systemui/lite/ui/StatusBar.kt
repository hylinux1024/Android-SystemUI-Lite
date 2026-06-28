package com.android.systemui.lite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.lite.model.BatteryPercentageStyle
import com.android.systemui.lite.model.ClockPosition

@Composable
fun StatusBar(
    heightDp: Int,
    iconSizeDp: Int,
    clockPosition: ClockPosition,
    batteryStyle: BatteryPercentageStyle,
    batteryLevel: Int,
    isCharging: Boolean,
    isWifiOn: Boolean,
    isBluetoothOn: Boolean,
    isDoNotDisturb: Boolean,
    isAirplaneMode: Boolean,
    timeString: String,
    isTrafficActive: Boolean,
    themeColor: Color,
    safeInsetLeft: Int = 0,
    safeInsetRight: Int = 0,
    onShadeToggle: () -> Unit,
    onShadeDragUpdate: ((Float) -> Unit)? = null,
    onShadeDragEnd: ((totalDragY: Float, isFling: Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    isShadeOpen: Boolean = false
) {
    var totalDragY by remember { mutableStateOf(0f) }
    var totalDragX by remember { mutableStateOf(0f) }
    var dragStartMs by remember { mutableStateOf(0L) }

    // Status bar dimensions matching AOSP SystemUI-Lite reference
    val statusBarHeight = 24.dp
    val defaultHorizontalPadding = 8.dp
    val iconSize = 14.dp
    val clockTextSize = 14.sp

    // Convert safe insets from pixels to dp (using density)
    val density = LocalDensity.current
    val safeLeftDp = with(density) { safeInsetLeft.toDp() }
    val safeRightDp = with(density) { safeInsetRight.toDp() }

    // Use the larger of default padding or safe inset
    val startPadding = maxOf(defaultHorizontalPadding, safeLeftDp)
    val endPadding = maxOf(defaultHorizontalPadding, safeRightDp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(statusBarHeight)
            .background(Color.Transparent)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        totalDragY = 0f
                        totalDragX = 0f
                        dragStartMs = System.currentTimeMillis()
                    },
                    onDragEnd = {
                        val absY = kotlin.math.abs(totalDragY)
                        val absX = kotlin.math.abs(totalDragX)
                        if (absY < 8f && absX < 8f) {
                            onShadeToggle()
                        } else if (!isShadeOpen) {
                            val elapsedMs = (System.currentTimeMillis() - dragStartMs).coerceAtLeast(1)
                            val velocity = totalDragY / elapsedMs * 1000f
                            val isFling = velocity > 800f && totalDragY > 40f
                            onShadeDragEnd?.invoke(totalDragY, isFling)
                        }
                    },
                    onDragCancel = {},
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDragY += dragAmount.y
                        totalDragX += dragAmount.x
                        if (totalDragY > 0 && !isShadeOpen) {
                            onShadeDragUpdate?.invoke(totalDragY)
                        }
                    }
                )
            }
            .padding(start = startPadding, end = endPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // --- Clock Section ---
        val clockComposable = @Composable {
            Text(
                text = timeString,
                color = Color.White,
                fontSize = clockTextSize,
                fontWeight = FontWeight.Medium
            )
        }

        // --- System Status Glyphs ---
        val iconsComposable = @Composable {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isTrafficActive) {
                    Text(
                        text = "1.2 MB/s",
                        color = themeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                if (isDoNotDisturb) {
                    Canvas(modifier = Modifier.size(iconSize)) {
                        drawCircle(color = Color.White.copy(alpha = 0.8f))
                        drawLine(
                            color = Color.Black,
                            start = Offset(size.width * 0.2f, size.height * 0.5f),
                            end = Offset(size.width * 0.8f, size.height * 0.5f),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }
                if (isAirplaneMode) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Airplane Mode",
                        tint = Color.White,
                        modifier = Modifier.size(iconSize)
                    )
                } else {
                    if (isWifiOn) {
                        Icon(
                            painter = painterResource(id = com.android.systemui.R.drawable.stat_sys_wifi_4),
                            contentDescription = "WiFi On",
                            tint = Color.White,
                            modifier = Modifier.size(iconSize)
                        )
                    }
                    if (isBluetoothOn) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = "Bluetooth On",
                            tint = Color.White,
                            modifier = Modifier.size(iconSize)
                        )
                    }
                }

                // Battery Icon
                if (batteryStyle != BatteryPercentageStyle.HIDDEN) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (batteryStyle == BatteryPercentageStyle.ICON_AND_TEXT || batteryStyle == BatteryPercentageStyle.TEXT_ONLY) {
                            Text(
                                text = "$batteryLevel%",
                                color = if (batteryLevel < 20) Color(0xFFEF4444) else Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (batteryStyle == BatteryPercentageStyle.ICON_ONLY || batteryStyle == BatteryPercentageStyle.ICON_AND_TEXT) {
                            Canvas(
                                modifier = Modifier
                                    .width(13.dp)
                                    .height(iconSize)
                            ) {
                                val w = size.width
                                val h = size.height
                                // Outline
                                drawRoundRect(
                                    color = if (batteryLevel < 20) Color(0xFFEF4444) else Color.White.copy(alpha = 0.5f),
                                    topLeft = Offset(0f, h * 0.1f),
                                    size = Size(w * 0.88f, h * 0.8f),
                                    style = Stroke(width = 1.25.dp.toPx()),
                                    cornerRadius = CornerRadius(2.dp.toPx())
                                )
                                // Tip
                                drawRect(
                                    color = if (batteryLevel < 20) Color(0xFFEF4444) else Color.White.copy(alpha = 0.5f),
                                    topLeft = Offset(w * 0.88f, h * 0.35f),
                                    size = Size(w * 0.12f, h * 0.3f)
                                )
                                // Fill
                                val fillW = (w * 0.88f - 4.dp.toPx()) * (batteryLevel / 100f)
                                drawRect(
                                    color = when {
                                        isCharging -> Color(0xFF10B981)
                                        batteryLevel < 20 -> Color(0xFFEF4444)
                                        else -> Color.White
                                    },
                                    topLeft = Offset(2.dp.toPx(), h * 0.1f + 2.dp.toPx()),
                                    size = Size(fillW, h * 0.8f - 4.dp.toPx())
                                )
                            }
                        }
                    }
                }
            }
        }

        // Clock Gravity Arrangement
        when (clockPosition) {
            ClockPosition.LEFT -> {
                clockComposable()
                Spacer(modifier = Modifier.weight(1f))
                iconsComposable()
            }
            ClockPosition.CENTER -> {
                Spacer(modifier = Modifier.weight(1f))
                Box(modifier = Modifier.wrapContentSize(), contentAlignment = Alignment.Center) {
                    clockComposable()
                }
                Spacer(modifier = Modifier.weight(1f))
                iconsComposable()
            }
            ClockPosition.RIGHT -> {
                iconsComposable()
                Spacer(modifier = Modifier.weight(1f))
                clockComposable()
            }
        }
    }
}
