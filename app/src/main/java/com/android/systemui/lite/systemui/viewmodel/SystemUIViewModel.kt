package com.android.systemui.lite.systemui.viewmodel

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.systemui.lite.SystemUIApplication
import com.android.systemui.lite.systemui.model.*
import com.android.systemui.lite.systemui.plugins.PluginCategory
import com.android.systemui.lite.systemui.plugins.SystemUIPlugin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SystemUIViewModel : ViewModel() {

    companion object {
        private var _instance: SystemUIViewModel? = null
        val instance: SystemUIViewModel
            get() {
                if (_instance == null) {
                    _instance = SystemUIViewModel()
                }
                return _instance!!
            }
    }

    // Helper to log to SystemUIApplication logger
    private fun log(tag: String, message: String) {
        SystemUIApplication.instance.logSystemEvent(tag, message)
    }

    // 1. Core SystemUI State
    private val _isWifiOn = MutableStateFlow(true)
    val isWifiOn = _isWifiOn.asStateFlow()

    private val _isBluetoothOn = MutableStateFlow(false)
    val isBluetoothOn = _isBluetoothOn.asStateFlow()

    private val _isDoNotDisturb = MutableStateFlow(false)
    val isDoNotDisturb = _isDoNotDisturb.asStateFlow()

    private val _isFlashlightOn = MutableStateFlow(false)
    val isFlashlightOn = _isFlashlightOn.asStateFlow()

    private val _isAirplaneMode = MutableStateFlow(false)
    val isAirplaneMode = _isAirplaneMode.asStateFlow()

    private val _isAutoRotateOn = MutableStateFlow(true)
    val isAutoRotateOn = _isAutoRotateOn.asStateFlow()

    private val _isScreenRecording = MutableStateFlow(false)
    val isScreenRecording = _isScreenRecording.asStateFlow()

    private val _batteryLevel = MutableStateFlow(84)
    val batteryLevel = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging = _isCharging.asStateFlow()

    private val _brightness = MutableStateFlow(0.7f)
    val brightness = _brightness.asStateFlow()

    private val _mediaVolume = MutableStateFlow(0.6f)
    val mediaVolume = _mediaVolume.asStateFlow()

    private val _timeString = MutableStateFlow("10:42")
    val timeString = _timeString.asStateFlow()

    // 2. Keyguard (Lockscreen) State
    private val _isLocked = MutableStateFlow(true)
    val isLocked = _isLocked.asStateFlow()

    private val _usePinSecurity = MutableStateFlow(false)
    val usePinSecurity = _usePinSecurity.asStateFlow()

    private val _correctPin = MutableStateFlow("1234")
    val correctPin = _correctPin.asStateFlow()

    private val _enteredPin = MutableStateFlow("")
    val enteredPin = _enteredPin.asStateFlow()

    private val _lockscreenError = MutableStateFlow<String?>(null)
    val lockscreenError = _lockscreenError.asStateFlow()

    // 3. Navigation State
    private val _navigationMode = MutableStateFlow(NavigationMode.GESTURES)
    val navigationMode = _navigationMode.asStateFlow()

    private val _activeGestureFeedback = MutableStateFlow<String?>(null)
    val activeGestureFeedback = _activeGestureFeedback.asStateFlow()

    // 4. Notifications
    private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val notifications = _notifications.asStateFlow()

    // 5. Configurable Parameters
    private val _statusBarHeight = MutableStateFlow(28) // dp
    val statusBarHeight = _statusBarHeight.asStateFlow()

    private val _statusBarIconSize = MutableStateFlow(16) // dp
    val statusBarIconSize = _statusBarIconSize.asStateFlow()

    private val _clockPosition = MutableStateFlow(ClockPosition.LEFT)
    val clockPosition = _clockPosition.asStateFlow()

    private val _batteryStyle = MutableStateFlow(BatteryPercentageStyle.ICON_AND_TEXT)
    val batteryStyle = _batteryStyle.asStateFlow()

    // Central color accent
    private val _themeColor = MutableStateFlow(Color(0xFF00ADB5)) // Default Material Teal
    val themeColor = _themeColor.asStateFlow()

    // 6. Wallpaper List & Selected
    val wallpaperList = listOf(
        WallpaperItem(0, "Cosmic Twilight", listOf(Color(0xFF0F172A), Color(0xFF3B0764), Color(0xFF701A75)), isDark = true),
        WallpaperItem(1, "Nordic Mint", listOf(Color(0xFF1E293B), Color(0xFF064E3B), Color(0xFF065F46)), isDark = true),
        WallpaperItem(2, "Sunset Gold", listOf(Color(0xFF2E1065), Color(0xFF7C2D12), Color(0xFFD97706)), isDark = true),
        WallpaperItem(3, "Cyber Neon", listOf(Color(0xFF090D16), Color(0xFF0D1E36), Color(0xFF1E0E32)), isDark = true),
        WallpaperItem(4, "Minimal Carbon", listOf(Color(0xFF111111), Color(0xFF222222), Color(0xFF333333)), isDark = true)
    )
    private val _selectedWallpaperId = MutableStateFlow(0)
    val selectedWallpaperId = _selectedWallpaperId.asStateFlow()

    // Active screen state inside the simulator: "Home", "NotificationShade", "Recents"
    private val _activeScreen = MutableStateFlow("Home") // Home, Shade, Recents
    val activeScreen = _activeScreen.asStateFlow()

    // Log Feed linked directly from Application
    val systemLogs = SystemUIApplication.instance.systemLogs

    // Active plugins list
    val plugins = SystemUIApplication.instance.pluginManager.plugins

    // Active media player track state
    private val _currentTrackIndex = MutableStateFlow(0)
    val tracks = listOf(
        Pair("Resonance", "Home (Synthesizer Remix)"),
        Pair("Starboy", "The Weeknd"),
        Pair("Blinding Lights", "The Weeknd"),
        Pair("Midnight City", "M83")
    )

    init {
        // Start time updater coroutine
        viewModelScope.launch {
            while (true) {
                val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
                _timeString.value = formatter.format(Date())
                delay(30000) // check every 30 seconds
            }
        }

        // Detect actual system navigation mode
        detectNavigationMode()

        // Preload mock notifications
        resetNotifications()
        log("SystemUI", "SystemUIViewModel initialized and default state configured.")
    }

    private fun detectNavigationMode() {
        try {
            val context = SystemUIApplication.instance
            val mode = android.provider.Settings.Secure.getInt(
                context.contentResolver,
                "navigation_mode",
                0 // default to 3-button
            )
            val navMode = when (mode) {
                0 -> NavigationMode.THREE_BUTTON
                2 -> NavigationMode.GESTURES
                else -> NavigationMode.THREE_BUTTON
            }
            _navigationMode.value = navMode
            log("NavigationBarController", "Detected system navigation mode: $mode -> $navMode")
        } catch (e: Exception) {
            log("NavigationBarController", "Failed to detect navigation mode: ${e.message}")
            _navigationMode.value = NavigationMode.THREE_BUTTON
        }
    }

    // Toggle Quick Settings Tiles
    fun toggleWifi() {
        _isWifiOn.value = !_isWifiOn.value
        log("QuickSettings", "Wi-Fi toggled: ${_isWifiOn.value}")
    }

    fun toggleBluetooth() {
        _isBluetoothOn.value = !_isBluetoothOn.value
        log("QuickSettings", "Bluetooth toggled: ${_isBluetoothOn.value}")
    }

    fun toggleDnd() {
        _isDoNotDisturb.value = !_isDoNotDisturb.value
        log("QuickSettings", "Do Not Disturb toggled: ${_isDoNotDisturb.value}")
    }

    fun toggleFlashlight() {
        _isFlashlightOn.value = !_isFlashlightOn.value
        log("QuickSettings", "Flashlight toggled: ${_isFlashlightOn.value}")
    }

    fun toggleAirplaneMode() {
        _isAirplaneMode.value = !_isAirplaneMode.value
        if (_isAirplaneMode.value) {
            _isWifiOn.value = false
            _isBluetoothOn.value = false
        }
        log("QuickSettings", "Airplane Mode toggled: ${_isAirplaneMode.value}")
    }

    fun toggleAutoRotate() {
        _isAutoRotateOn.value = !_isAutoRotateOn.value
        log("QuickSettings", "Auto Rotate toggled: ${_isAutoRotateOn.value}")
    }

    fun toggleScreenRecording() {
        _isScreenRecording.value = !_isScreenRecording.value
        log("QuickSettings", "Screen Recording toggled: ${_isScreenRecording.value}")
    }

    fun setBrightness(value: Float) {
        _brightness.value = value.coerceIn(0.0f, 1.0f)
        log("DisplayController", "Backlight brightness set to ${(value * 100).toInt()}%")
    }

    fun setMediaVolume(value: Float) {
        _mediaVolume.value = value.coerceIn(0.0f, 1.0f)
        log("VolumeController", "System media volume slider: ${(value * 15).toInt()}/15")
    }

    fun changeBatteryLevel(level: Int) {
        _batteryLevel.value = level.coerceIn(0, 100)
        log("BatteryService", "Battery telemetry simulated: $level%")
    }

    fun toggleCharging() {
        _isCharging.value = !_isCharging.value
        log("BatteryService", "Charging state simulated: ${_isCharging.value}")
    }

    // Manage Lockscreen state
    fun lockDevice() {
        _isLocked.value = true
        _activeScreen.value = "Home"
        _enteredPin.value = ""
        _lockscreenError.value = null
        log("KeyguardViewManager", "Device transition -> LOCKED")
    }

    fun setSecurityMode(usePin: Boolean) {
        _usePinSecurity.value = usePin
        log("KeyguardViewManager", "Lockscreen security changed to: ${if (usePin) "PIN (1234)" else "Swipe To Unlock"}")
    }

    fun changePinCode(newPin: String) {
        if (newPin.length == 4 && newPin.all { it.isDigit() }) {
            _correctPin.value = newPin
            log("KeyguardViewManager", "System Master PIN successfully updated to $newPin")
        }
    }

    fun handlePinInput(char: Char) {
        if (_enteredPin.value.length < 4) {
            _enteredPin.value += char
            if (_enteredPin.value.length == 4) {
                verifyPin()
            }
        }
    }

    fun deletePinDigit() {
        if (_enteredPin.value.isNotEmpty()) {
            _enteredPin.value = _enteredPin.value.dropLast(1)
            _lockscreenError.value = null
        }
    }

    private fun verifyPin() {
        if (_enteredPin.value == _correctPin.value) {
            _isLocked.value = false
            _enteredPin.value = ""
            _lockscreenError.value = null
            log("KeyguardViewManager", "Biometric/PIN Authentication SUCCESSFUL. Unlocking device.")
        } else {
            _enteredPin.value = ""
            _lockscreenError.value = "Incorrect PIN code. Try again (Hint: 1234)"
            log("KeyguardViewManager", "Keyguard Authentication FAIL: Invalid credentials entered.")
        }
    }

    fun swipeToUnlock() {
        if (!_usePinSecurity.value) {
            _isLocked.value = false
            log("KeyguardViewManager", "Keyguard dismissed via Swipe gesture.")
        } else {
            log("KeyguardViewManager", "Cannot swipe-unlock: PIN verification required.")
        }
    }

    // Manage Navigation Modes and Gestures
    fun setNavigationMode(mode: NavigationMode) {
        _navigationMode.value = mode
        log("NavigationBarController", "Navigation configuration shifted to: $mode")
    }

    fun triggerGesture(actionName: String) {
        viewModelScope.launch {
            _activeGestureFeedback.value = actionName
            log("GestureNav", "System gesture registered: $actionName")
            
            // Execute simulated gesture actions
            when (actionName) {
                "Swipe Left -> BACK", "Swipe Right -> BACK" -> {
                    if (_activeScreen.value != "Home") {
                        _activeScreen.value = "Home"
                        log("SystemUI", "Navigation Back event consumed: Restored Home screen.")
                    } else if (_isLocked.value) {
                        log("SystemUI", "Back event ignored: Device is locked.")
                    } else {
                        log("SystemUI", "Navigation Back event dispatched to top foreground activity.")
                    }
                }
                "Swipe Up -> HOME" -> {
                    if (!_isLocked.value) {
                        _activeScreen.value = "Home"
                        log("SystemUI", "Dispatched HOME intent. Returning to launcher workspace.")
                    }
                }
                "Swipe Up & Hold -> RECENTS" -> {
                    if (!_isLocked.value) {
                        _activeScreen.value = "Recents"
                        log("SystemUI", "Triggered Recents Overview interface.")
                    }
                }
            }
            delay(1000)
            if (_activeGestureFeedback.value == actionName) {
                _activeGestureFeedback.value = null
            }
        }
    }

    // Notifications Management
    fun addCustomNotification(appName: String, title: String, text: String, type: NotificationType) {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        val time = formatter.format(Date())
        val newId = System.currentTimeMillis()
        
        val newNotification = NotificationItem.createLegacy(
            id = newId.toInt(),
            appName = appName,
            title = title,
            text = text,
            timestamp = time,
            type = type
        )
        
        val current = _notifications.value.toMutableList()
        current.add(0, newNotification)
        _notifications.value = current
        log("NotificationPresenter", "New notification posted by $appName: '$title' - ($newId)")
    }

    fun dismissNotification(id: Any) {
        val current = _notifications.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index != -1) {
            val item = current[index]
            current.removeAt(index)
            _notifications.value = current
            log("NotificationPresenter", "Notification dismissed by user: ID $id (${item.appName})")
        }
    }

    fun clearAllNotifications() {
        _notifications.value = _notifications.value.filter { it.type == NotificationType.MUSIC } // Keep active media player if playing
        log("NotificationPresenter", "Bulk dismiss action: All cleanable notifications dismissed.")
    }

    fun resetNotifications() {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        val time = formatter.format(Date())
        _notifications.value = listOf(
            NotificationItem.createLegacy(101, "Music Player", "Now Playing", "", time, NotificationType.MUSIC, isPlaying = true, artist = "Home", trackTitle = "Resonance"),
            NotificationItem.createLegacy(102, "Gmail", "StackOverflow Weekly", "Check out top Kotlin answers for Room and Jetpack Compose state mapping.", time, NotificationType.EMAIL),
            NotificationItem.createLegacy(103, "Messages", "Alex", "Hey! Are we still reviewing the SystemUI gesture controllers today at 3?", time, NotificationType.MESSAGE),
            NotificationItem.createLegacy(104, "System Update", "Android 16 Core", "System update ready to install. Restart to apply patches.", time, NotificationType.SYSTEM_ALERT)
        )
        log("NotificationPresenter", "Notification stack reloaded with default mock alerts.")
    }

    // Music control simulations (linked to Music Notification)
    fun togglePlayPauseMusic() {
        val current = _notifications.value.toMutableList()
        val index = current.indexOfFirst { it.type == NotificationType.MUSIC }
        if (index != -1) {
            val item = current[index]
            val newState = !item.isPlaying
            current[index] = item.copy(isPlaying = newState)
            _notifications.value = current
            log("MediaSession", "Simulated media session: ${if (newState) "PLAY" else "PAUSE"} triggered.")
        }
    }

    fun skipNextTrack() {
        val nextIndex = (_currentTrackIndex.value + 1) % tracks.size
        _currentTrackIndex.value = nextIndex
        val track = tracks[nextIndex]
        
        val current = _notifications.value.toMutableList()
        val index = current.indexOfFirst { it.type == NotificationType.MUSIC }
        if (index != -1) {
            val item = current[index]
            current[index] = item.copy(trackTitle = track.first, artist = track.second, isPlaying = true)
            _notifications.value = current
            log("MediaSession", "Skipped next track: '${track.first}' by ${track.second}")
        }
    }

    fun skipPrevTrack() {
        val prevIndex = if (_currentTrackIndex.value - 1 < 0) tracks.size - 1 else _currentTrackIndex.value - 1
        _currentTrackIndex.value = prevIndex
        val track = tracks[prevIndex]

        val current = _notifications.value.toMutableList()
        val index = current.indexOfFirst { it.type == NotificationType.MUSIC }
        if (index != -1) {
            val item = current[index]
            current[index] = item.copy(trackTitle = track.first, artist = track.second, isPlaying = true)
            _notifications.value = current
            log("MediaSession", "Skipped previous track: '${track.first}' by ${track.second}")
        }
    }

    // Configure Parameters
    fun updateStatusBarHeight(height: Int) {
        _statusBarHeight.value = height.coerceIn(20, 60)
        log("SystemUIConfig", "StatusBar layout height calibrated to ${height}dp.")
    }

    fun updateStatusBarIconSize(size: Int) {
        _statusBarIconSize.value = size.coerceIn(12, 28)
        log("SystemUIConfig", "StatusBar notification glyph size calibrated to ${size}dp.")
    }

    fun setClockPosition(position: ClockPosition) {
        _clockPosition.value = position
        log("SystemUIConfig", "StatusBar clock display gravity shifted to $position.")
    }

    fun setBatteryStyle(style: BatteryPercentageStyle) {
        _batteryStyle.value = style
        log("SystemUIConfig", "StatusBar battery element visualization changed to $style.")
    }

    fun updateThemeColor(color: Color) {
        _themeColor.value = color
        log("SystemUIConfig", "System UI color palette runtime theme override: #${Integer.toHexString(color.hashCode()).uppercase()}")
    }

    fun selectWallpaper(id: Int) {
        if (id in wallpaperList.indices) {
            _selectedWallpaperId.value = id
            log("WallpaperManager", "Wallpaper successfully updated to dynamic pattern: '${wallpaperList[id].name}'")
        }
    }

    // Active Screen Toggle
    fun toggleNotificationShade() {
        if (_isLocked.value) {
            log("SystemUI", "Cannot open Notification Shade: Device is keyguard-locked.")
            return
        }
        if (_activeScreen.value == "NotificationShade") {
            _activeScreen.value = "Home"
            log("SystemUI", "Collapsing Notification Shade panel.")
        } else {
            _activeScreen.value = "NotificationShade"
            log("SystemUI", "Expanding Notification Shade overlay.")
        }
    }

    fun setScreen(screen: String) {
        _activeScreen.value = screen
        log("SystemUI", "Simulated active workspace changed to: $screen")
    }

    // Plugin toggles
    fun togglePlugin(id: String) {
        val newState = SystemUIApplication.instance.pluginManager.togglePlugin(id)
        log("SystemUIPlugin", "Plugin state updated: $id is now ${if (newState) "ACTIVE" else "INACTIVE"}")
    }

    fun isPluginEnabled(id: String): Boolean {
        return plugins.value.find { it.id == id }?.isEnabled ?: false
    }

    fun clearTerminalLogs() {
        SystemUIApplication.instance.clearLogs()
    }
}
