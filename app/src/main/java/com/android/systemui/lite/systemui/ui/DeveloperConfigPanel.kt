package com.android.systemui.lite.systemui.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.*
import com.android.systemui.lite.SystemUIApplication
import com.android.systemui.lite.systemui.model.*
import com.android.systemui.lite.systemui.plugins.PluginCategory
import com.android.systemui.lite.systemui.viewmodel.SystemUIViewModel
import kotlinx.coroutines.launch

@Composable
fun DeveloperConfigPanel(
    viewModel: SystemUIViewModel,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(0) }
    val tabs = listOf("Parameters", "Plugins", "Spammer", "Console Logs")

    Card(
        modifier = modifier
            .testTag("developer_config_panel")
            .fillMaxHeight(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C1E)), // Professional Polish Slate background (#1A1C1E)
        shape = RoundedCornerShape(32.dp), // rounded-[32px]
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)) // border-white/5
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // --- HEADER ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2D2F33)) // bg-[#2D2F33]
                    .padding(20.dp), // p-5
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SystemUI Debug",
                        color = Color(0xFFE2E2E6), // text-[#E2E2E6]
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "com.android.systemui",
                        color = Color(0xFF909094), // text-[#909094]
                        fontSize = 12.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .background(Color(0x3310B981), RoundedCornerShape(100.dp)) // bg-green-500/20 (20% is 0x33)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Running",
                        color = Color(0xFF4ADE80), // text-green-400
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        style = androidx.compose.ui.text.TextStyle(letterSpacing = 1.sp)
                    )
                }
            }

            // --- MAIN TAB ENGINE ---
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // --- TAB NAVIGATOR ---
                ScrollableTabRow(
                    selectedTabIndex = activeTab,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    edgePadding = 0.dp,
                    divider = {},
                    indicator = { tabPositions ->
                        if (activeTab < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                color = viewModel.themeColor.collectAsState().value,
                                modifier = Modifier.tabIndicatorOffset(tabPositions[activeTab])
                            )
                        }
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = activeTab == index,
                            onClick = { activeTab = index },
                            text = {
                                Text(
                                    text = title,
                                    fontSize = 12.sp,
                                    fontWeight = if (activeTab == index) FontWeight.Bold else FontWeight.Normal,
                                    color = if (activeTab == index) Color.White else Color(0xFF909094) // text-[#909094]
                                )
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // --- MAIN TAB CONTENT ---
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (activeTab) {
                        0 -> ParametersTab(viewModel)
                        1 -> PluginsTab(viewModel)
                        2 -> SpammerTab(viewModel)
                        3 -> ConsoleLogsTab(viewModel)
                    }
                }
            }
        }
    }
}

// ==========================================
// TAB 0: PARAMETERS TAB
// ==========================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParametersTab(viewModel: SystemUIViewModel) {
    val statusBarHeight by viewModel.statusBarHeight.collectAsState()
    val statusBarIconSize by viewModel.statusBarIconSize.collectAsState()
    val clockPosition by viewModel.clockPosition.collectAsState()
    val batteryStyle by viewModel.batteryStyle.collectAsState()
    val navigationMode by viewModel.navigationMode.collectAsState()
    val themeColor by viewModel.themeColor.collectAsState()
    val usePinSecurity by viewModel.usePinSecurity.collectAsState()
    val correctPin by viewModel.correctPin.collectAsState()
    val selectedWallpaperId by viewModel.selectedWallpaperId.collectAsState()

    val colors = listOf(
        Color(0xFF00ADB5), // Default Teal
        Color(0xFF8B5CF6), // Royal Purple
        Color(0xFFEF4444), // Crimson Flame
        Color(0xFF10B981), // Emerald Mint
        Color(0xFF3B82F6), // Electric Blue
        Color(0xFFEAB308)  // Cyberpunk Gold
    )

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // SystemUI Core Status
        item {
            val app = com.android.systemui.lite.SystemUIApplication.instance
            var isActive by remember { mutableStateOf(true) }

            ParameterCard(title = "SystemUI Core Status") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "CoreStartable Engine",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (isActive) "Running (${app.getStartableCount()} components)" else "Stopped",
                                color = if (isActive) Color(0xFF4ADE80) else Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }

                        Button(
                            onClick = {
                                if (isActive) {
                                    app.stopServicesIfNeeded()
                                    isActive = false
                                } else {
                                    app.startServicesIfNeeded()
                                    isActive = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isActive) Color(0xFFEF4444) else themeColor
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                if (isActive) "Stop" else "Start",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        "CoreStartable-based components:\n" +
                        "StatusBar, NavigationBar, NotificationShade, QuickSettings.\n" +
                        "All windows are managed by CoreStartables (no Android Services).",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // Theme Colors Selection
        item {
            ParameterCard(title = "SystemUI Accent Theme") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select accent color token applied to notification pills, icons and status sliders:", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        colors.forEach { color ->
                            val isSelected = themeColor == color
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(color, CircleShape)
                                    .border(
                                        width = if (isSelected) 3.dp else 0.dp,
                                        color = if (isSelected) Color.White else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { viewModel.updateThemeColor(color) }
                            )
                        }
                    }
                }
            }
        }

        // Wallpaper Selector
        item {
            ParameterCard(title = "Desktop Wallpaper Customize") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Dynamic wallpaper background pattern runtime rendering:", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        viewModel.wallpaperList.forEach { paper ->
                            val isSelected = selectedWallpaperId == paper.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) themeColor.copy(alpha = 0.15f) else Color(0xFF1E293B)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) themeColor else Color(0xFF334155),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { viewModel.selectWallpaper(paper.id) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(paper.name, color = if (isSelected) themeColor else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Height & Icon Size Calibration
        item {
            ParameterCard(title = "Status Bar & Icon Metrics") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Height Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("StatusBar Height", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("${statusBarHeight}dp", color = themeColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = statusBarHeight.toFloat(),
                            onValueChange = { viewModel.updateStatusBarHeight(it.toInt()) },
                            valueRange = 20f..60f,
                            colors = SliderDefaults.colors(
                                thumbColor = themeColor,
                                activeTrackColor = themeColor,
                                inactiveTrackColor = Color(0xFF334155)
                            )
                        )
                    }

                    // Icon Size Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("StatusBar Icon Sizing", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("${statusBarIconSize}dp", color = themeColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = statusBarIconSize.toFloat(),
                            onValueChange = { viewModel.updateStatusBarIconSize(it.toInt()) },
                            valueRange = 12f..28f,
                            colors = SliderDefaults.colors(
                                thumbColor = themeColor,
                                activeTrackColor = themeColor,
                                inactiveTrackColor = Color(0xFF334155)
                            )
                        )
                    }
                }
            }
        }

        // Clock Gravity & Battery Styles
        item {
            ParameterCard(title = "Layout Gravity & Style Configuration") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Clock positions
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Clock Alignment Gravity", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ClockPosition.values().forEach { pos ->
                                val isSelected = clockPosition == pos
                                Button(
                                    onClick = { viewModel.setClockPosition(pos) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) themeColor else Color(0xFF1E293B)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        pos.name,
                                        color = if (isSelected) Color.Black else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Battery styles
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Battery Indicator Style", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BatteryPercentageStyle.values().forEach { style ->
                                val isSelected = batteryStyle == style
                                Button(
                                    onClick = { viewModel.setBatteryStyle(style) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) themeColor else Color(0xFF1E293B)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        style.name.replace("_", " "),
                                        color = if (isSelected) Color.Black else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Navigation Modes & Lockscreen security
        item {
            ParameterCard(title = "System Controls & Security Core") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Navigation Modes
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("System Navigation Mode", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NavigationMode.values().forEach { mode ->
                                val isSelected = navigationMode == mode
                                Button(
                                    onClick = { viewModel.setNavigationMode(mode) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) themeColor else Color(0xFF1E293B)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        if (mode == NavigationMode.THREE_BUTTON) "3-Button Nav" else "Gesture Nav",
                                        color = if (isSelected) Color.Black else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Keyguard Security
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Lockscreen Security Guard", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Secure PIN Lock", color = Color(0xFF94A3B8), fontSize = 11.sp)
                            Switch(
                                checked = usePinSecurity,
                                onCheckedChange = { viewModel.setSecurityMode(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = themeColor,
                                    checkedTrackColor = themeColor.copy(alpha = 0.4f),
                                    uncheckedThumbColor = Color(0xFF64748B),
                                    uncheckedTrackColor = Color(0xFF1E293B)
                                )
                            )
                        }
                        if (usePinSecurity) {
                            var pinText by remember { mutableStateOf(correctPin) }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                OutlinedTextField(
                                    value = pinText,
                                    onValueChange = {
                                        if (it.length <= 4 && it.all { c -> c.isDigit() }) {
                                            pinText = it
                                            if (it.length == 4) {
                                                viewModel.changePinCode(it)
                                            }
                                        }
                                    },
                                    label = { Text("4-Digit PIN Code", fontSize = 10.sp) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = themeColor,
                                        unfocusedBorderColor = Color(0xFF334155)
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = { viewModel.lockDevice() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("LOCK NOW", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            Button(
                                onClick = { viewModel.lockDevice() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("LOCK DEVICE NOW", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ParameterCard(
    title: String,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF30343A), RoundedCornerShape(16.dp)) // Professional Polish Card Gray (#30343A)
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp)) // White 5% border
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

// ==========================================
// TAB 1: SYSTEM PLUGINS TAB
// ==========================================
@Composable
fun PluginsTab(viewModel: SystemUIViewModel) {
    val plugins by viewModel.plugins.collectAsState()
    val themeColor by viewModel.themeColor.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Plug-and-Play System Customizer (SystemUIPlugins)",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Text(
            text = "Android SystemUI defines runtime plugins using a service-binding pipeline. Enable components dynamically to hot-reload features without rebuilding the image bundle:",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(plugins) { plugin ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF30343A), RoundedCornerShape(14.dp)) // Professional Polish Card Gray (#30343A)
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp)) // White 5% border
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                if (plugin.isEnabled) themeColor.copy(alpha = 0.15f) else Color(0xFF334155),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (plugin.category) {
                                PluginCategory.STATUS_BAR -> Icons.Default.Favorite
                                PluginCategory.LOCK_SCREEN -> Icons.Default.Lock
                                PluginCategory.QUICK_SETTINGS -> Icons.Default.Menu
                            },
                            contentDescription = null,
                            tint = if (plugin.isEnabled) themeColor else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(plugin.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF1E293B), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = plugin.category.name,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 7.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text(plugin.description, color = Color(0xFF94A3B8), fontSize = 10.sp, lineHeight = 13.sp)
                    }

                    Switch(
                        checked = plugin.isEnabled,
                        onCheckedChange = { viewModel.togglePlugin(plugin.id) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = themeColor,
                            checkedTrackColor = themeColor.copy(alpha = 0.4f),
                            uncheckedThumbColor = Color(0xFF64748B),
                            uncheckedTrackColor = Color(0xFF1E293B)
                        )
                    )
                }
            }
        }
    }
}

// ==========================================
// TAB 2: SPAMMER TAB
// ==========================================
@Composable
fun SpammerTab(viewModel: SystemUIViewModel) {
    val themeColor by viewModel.themeColor.collectAsState()

    var customAppName by remember { mutableStateOf("WeChat") }
    var customTitle by remember { mutableStateOf("New Message") }
    var customText by remember { mutableStateOf("Do you want to test this SystemUI simulator code now?") }
    var customType by remember { mutableStateOf(NotificationType.MESSAGE) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Quick Spammers
        item {
            ParameterCard(title = "Fast Trigger Mock Alerts") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Trigger standard preconfigured notification templates instantly in status shade and lockscreen:", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.resetNotifications() },
                            colors = ButtonDefaults.buttonColors(containerColor = themeColor),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Reset Stack", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { viewModel.clearAllNotifications() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Clear Clearable", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { viewModel.addCustomNotification("Slack", "Manager", "Urgent bugfix branch has failed compilation.", NotificationType.MESSAGE) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(4.dp)
                        ) {
                            Text("Slack Alert", color = Color.White, fontSize = 9.sp)
                        }
                        Button(
                            onClick = { viewModel.addCustomNotification("System Update", "FOTA Installation", "A new system security update is available.", NotificationType.SYSTEM_ALERT) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(4.dp)
                        ) {
                            Text("Sys Alert", color = Color.White, fontSize = 9.sp)
                        }
                        Button(
                            onClick = { viewModel.addCustomNotification("GitHub", "New PR", "User submitted 12 commits into main-ref.", NotificationType.EMAIL) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(4.dp)
                        ) {
                            Text("Git Email", color = Color.White, fontSize = 9.sp)
                        }
                    }
                }
            }
        }

        // Custom Notification Form
        item {
            ParameterCard(title = "Publish Custom Custom Notification") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = customAppName,
                        onValueChange = { customAppName = it },
                        label = { Text("App Name Source") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = themeColor,
                            unfocusedBorderColor = Color(0xFF334155)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = customTitle,
                        onValueChange = { customTitle = it },
                        label = { Text("Notification Title") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = themeColor,
                            unfocusedBorderColor = Color(0xFF334155)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        label = { Text("Message Body Text") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = themeColor,
                            unfocusedBorderColor = Color(0xFF334155)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Type selection
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Category Notification Type", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(NotificationType.MESSAGE, NotificationType.EMAIL, NotificationType.SYSTEM_ALERT).forEach { type ->
                                val isSelected = customType == type
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (isSelected) themeColor else Color(0xFF1E293B),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .clickable { customType = type }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = type.name,
                                        color = if (isSelected) Color.Black else Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (customAppName.isNotEmpty() && customTitle.isNotEmpty() && customText.isNotEmpty()) {
                                viewModel.addCustomNotification(customAppName, customTitle, customText, customType)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = themeColor),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("POST SIMULATED NOTIFICATION", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ==========================================
// TAB 3: CONSOLE LOGS TAB
// ==========================================
@Composable
fun ConsoleLogsTab(viewModel: SystemUIViewModel) {
    val systemLogs by viewModel.systemLogs.collectAsState()
    val themeColor by viewModel.themeColor.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll to bottom of logs when a new one comes
    LaunchedEffect(systemLogs.size) {
        if (systemLogs.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(systemLogs.size - 1)
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Pulsing blue dot from the Professional Polish theme
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color(0xFF3B82F6).copy(alpha = pulseAlpha), CircleShape)
                )
                Text(
                    text = "SystemUI Kernel Logger (ttyHSL0)",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Button(
                onClick = { viewModel.clearTerminalLogs() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D2F33)), // Dark slate grey clear button
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Clear", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(14.dp)) // bg-black/20
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp)) // border-white/5
                .padding(10.dp)
        ) {
            if (systemLogs.isEmpty()) {
                Text(
                    text = "No log streams. Trigger UI interactions to start tracing...",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(systemLogs) { entry ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            // Timestamp
                            val formatter = remember { java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()) }
                            Text(
                                text = formatter.format(java.util.Date(entry.timestamp)) + " ",
                                color = Color(0xFF475569),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            // Tag
                            val tagColor = when (entry.tag) {
                                "StatusBarController" -> Color(0xFF3B82F6)
                                "NavigationBarController" -> Color(0xFFEAB308)
                                "KeyguardViewController" -> Color(0xFF10B981)
                                "NotificationPresenter" -> Color(0xFFEF4444)
                                "PluginManager" -> Color(0xFFEC4899)
                                else -> themeColor
                            }
                            Text(
                                text = "[${entry.tag}] ",
                                color = tagColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            // Msg
                            Text(
                                text = entry.message,
                                color = Color(0xFFCBD5E1),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}
