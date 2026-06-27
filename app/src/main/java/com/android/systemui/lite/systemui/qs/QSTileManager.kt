package com.android.systemui.lite.systemui.qs

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.android.systemui.lite.SystemUIApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Method

/**
 * QSTileManager - Manages Quick Settings tiles with real system state.
 *
 * In AOSP, each QS tile is a separate class (e.g., WifiTile, BluetoothTile)
 * that extends QSTileImpl and implements handleUpdateState(), handleSetState(), etc.
 *
 * This is a simplified version that:
 * 1. Reads real system state for each tile
 * 2. Handles toggle actions via system APIs
 * 3. Notifies the UI layer of state changes
 */
class QSTileManager(private val context: Context) {

    companion object {
        private const val TAG = "QSTileManager"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Tile states
    private val _wifiEnabled = MutableStateFlow(false)
    val wifiEnabled: StateFlow<Boolean> = _wifiEnabled.asStateFlow()

    private val _bluetoothEnabled = MutableStateFlow(false)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val _dndEnabled = MutableStateFlow(false)
    val dndEnabled: StateFlow<Boolean> = _dndEnabled.asStateFlow()

    private val _flashlightEnabled = MutableStateFlow(false)
    val flashlightEnabled: StateFlow<Boolean> = _flashlightEnabled.asStateFlow()

    private val _airplaneModeEnabled = MutableStateFlow(false)
    val airplaneModeEnabled: StateFlow<Boolean> = _airplaneModeEnabled.asStateFlow()

    private val _autoRotateEnabled = MutableStateFlow(false)
    val autoRotateEnabled: StateFlow<Boolean> = _autoRotateEnabled.asStateFlow()

    private val _batterySaverEnabled = MutableStateFlow(false)
    val batterySaverEnabled: StateFlow<Boolean> = _batterySaverEnabled.asStateFlow()

    private val _locationEnabled = MutableStateFlow(false)
    val locationEnabled: StateFlow<Boolean> = _locationEnabled.asStateFlow()

    // Brightness (0-255)
    private val _brightness = MutableStateFlow(128)
    val brightness: StateFlow<Int> = _brightness.asStateFlow()

    // Volume levels
    private val _mediaVolume = MutableStateFlow(50)
    val mediaVolume: StateFlow<Int> = _mediaVolume.asStateFlow()

    private val _ringVolume = MutableStateFlow(50)
    val ringVolume: StateFlow<Int> = _ringVolume.asStateFlow()

    private val _alarmVolume = MutableStateFlow(50)
    val alarmVolume: StateFlow<Int> = _alarmVolume.asStateFlow()

    // System services
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Start monitoring QS tile state.
     */
    fun start() {
        Log.d(TAG, "Starting QSTileManager...")

        // Read initial states
        readInitialState()

        // Register for state change broadcasts
        registerReceivers()

        Log.d(TAG, "QSTileManager started")
    }

    /**
     * Stop monitoring QS tile state.
     */
    fun stop() {
        Log.d(TAG, "Stopping QSTileManager...")
        unregisterReceivers()
    }

    /**
     * Read the initial state of all QS tiles.
     */
    private fun readInitialState() {
        // WiFi
        _wifiEnabled.value = isWifiEnabled()

        // Bluetooth
        _bluetoothEnabled.value = isBluetoothEnabled()

        // DND
        _dndEnabled.value = isDndEnabled()

        // Airplane mode
        _airplaneModeEnabled.value = isAirplaneModeEnabled()

        // Auto-rotate
        _autoRotateEnabled.value = isAutoRotateEnabled()

        // Battery saver
        _batterySaverEnabled.value = isBatterySaverEnabled()

        // Location
        _locationEnabled.value = isLocationEnabled()

        // Brightness
        _brightness.value = getCurrentBrightness()

        // Volume
        audioManager?.let { am ->
            val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            _mediaVolume.value = if (maxVolume > 0) (currentVolume * 100) / maxVolume else 0

            val maxRing = am.getStreamMaxVolume(AudioManager.STREAM_RING)
            val currentRing = am.getStreamVolume(AudioManager.STREAM_RING)
            _ringVolume.value = if (maxRing > 0) (currentRing * 100) / maxRing else 0

            val maxAlarm = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val currentAlarm = am.getStreamVolume(AudioManager.STREAM_ALARM)
            _alarmVolume.value = if (maxAlarm > 0) (currentAlarm * 100) / maxAlarm else 0
        }

        Log.d(TAG, "Initial state: wifi=${_wifiEnabled.value}, bt=${_bluetoothEnabled.value}, dnd=${_dndEnabled.value}")
    }

    // ========== WiFi ==========

    private fun isWifiEnabled(): Boolean {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.isWifiEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    fun toggleWifi() {
        val newState = !_wifiEnabled.value
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.isWifiEnabled = newState
            _wifiEnabled.value = newState
            Log.d(TAG, "WiFi toggled to $newState")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle WiFi: ${e.message}", e)
        }
    }

    // ========== Bluetooth ==========

    private fun isBluetoothEnabled(): Boolean {
        return try {
            val btClass = Class.forName("android.bluetooth.BluetoothAdapter")
            val getDefaultAdapter = btClass.getMethod("getDefaultAdapter")
            val adapter = getDefaultAdapter.invoke(null)
            val isEnabled = btClass.getMethod("isEnabled")
            isEnabled.invoke(adapter) as Boolean
        } catch (e: Exception) {
            false
        }
    }

    fun toggleBluetooth() {
        val newState = !_bluetoothEnabled.value
        try {
            val btClass = Class.forName("android.bluetooth.BluetoothAdapter")
            val getDefaultAdapter = btClass.getMethod("getDefaultAdapter")
            val adapter = getDefaultAdapter.invoke(null)
            val enableMethod = btClass.getMethod("enable")
            if (newState) {
                enableMethod.invoke(adapter)
            } else {
                val disableMethod = btClass.getMethod("disable")
                disableMethod.invoke(adapter)
            }
            _bluetoothEnabled.value = newState
            Log.d(TAG, "Bluetooth toggled to $newState")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle Bluetooth: ${e.message}", e)
        }
    }

    // ========== DND ==========

    private fun isDndEnabled(): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, "zen_mode", 0) != 0
        } catch (e: Exception) {
            false
        }
    }

    fun toggleDnd() {
        val newState = !_dndEnabled.value
        try {
            val zenMode = if (newState) 1 else 0 // 1 = IMPORTANT_ONLY, 0 = OFF
            Settings.Global.putInt(context.contentResolver, "zen_mode", zenMode)
            _dndEnabled.value = newState
            Log.d(TAG, "DND toggled to $newState")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle DND: ${e.message}", e)
        }
    }

    // ========== Flashlight ==========

    fun toggleFlashlight() {
        val newState = !_flashlightEnabled.value
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            cameraManager?.let { cm ->
                val cameraId = cm.cameraIdList?.firstOrNull()
                if (cameraId != null) {
                    cm.setTorchMode(cameraId, newState)
                    _flashlightEnabled.value = newState
                    Log.d(TAG, "Flashlight toggled to $newState")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight: ${e.message}", e)
        }
    }

    // ========== Airplane Mode ==========

    private fun isAirplaneModeEnabled(): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        } catch (e: Exception) {
            false
        }
    }

    fun toggleAirplaneMode() {
        val newState = !_airplaneModeEnabled.value
        try {
            Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, if (newState) 1 else 0)
            _airplaneModeEnabled.value = newState
            // Broadcast the change
            val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            intent.putExtra("state", newState)
            context.sendBroadcast(intent)
            Log.d(TAG, "Airplane mode toggled to $newState")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle airplane mode: ${e.message}", e)
        }
    }

    // ========== Auto-Rotate ==========

    private fun isAutoRotateEnabled(): Boolean {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) != 0
        } catch (e: Exception) {
            false
        }
    }

    fun toggleAutoRotate() {
        val newState = !_autoRotateEnabled.value
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (newState) 1 else 0
            )
            _autoRotateEnabled.value = newState
            Log.d(TAG, "Auto-rotate toggled to $newState")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle auto-rotate: ${e.message}", e)
        }
    }

    // ========== Battery Saver ==========

    private fun isBatterySaverEnabled(): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            powerManager?.isPowerSaveMode == true
        } catch (e: Exception) {
            false
        }
    }

    fun toggleBatterySaver() {
        // Battery saver cannot be toggled directly via API
        // It's controlled by the system based on battery level
        Log.d(TAG, "Battery saver toggle requested (system-controlled)")
    }

    // ========== Location ==========

    private fun isLocationEnabled(): Boolean {
        return try {
            val locationMode = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE)
            locationMode != Settings.Secure.LOCATION_MODE_OFF
        } catch (e: Exception) {
            false
        }
    }

    // ========== Brightness ==========

    private fun getCurrentBrightness(): Int {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {
            128
        }
    }

    fun setBrightness(value: Int) {
        val clamped = value.coerceIn(0, 255)
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                clamped
            )
            _brightness.value = clamped
            Log.d(TAG, "Brightness set to $clamped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness: ${e.message}", e)
        }
    }

    // ========== Volume ==========

    fun setMediaVolume(percent: Int) {
        audioManager?.let { am ->
            val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = (percent * maxVolume) / 100
            am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            _mediaVolume.value = percent
        }
    }

    fun setRingVolume(percent: Int) {
        audioManager?.let { am ->
            val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_RING)
            val targetVolume = (percent * maxVolume) / 100
            am.setStreamVolume(AudioManager.STREAM_RING, targetVolume, 0)
            _ringVolume.value = percent
        }
    }

    fun setAlarmVolume(percent: Int) {
        audioManager?.let { am ->
            val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val targetVolume = (percent * maxVolume) / 100
            am.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0)
            _alarmVolume.value = percent
        }
    }

    // ========== Broadcast Receivers ==========

    private val stateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    _wifiEnabled.value = isWifiEnabled()
                }
                android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    _bluetoothEnabled.value = isBluetoothEnabled()
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    _airplaneModeEnabled.value = isAirplaneModeEnabled()
                }
                "android.settings.ZEN_MODE_CHANGED" -> {
                    _dndEnabled.value = isDndEnabled()
                }
            }
        }
    }

    private fun registerReceivers() {
        val filter = android.content.IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction("android.settings.ZEN_MODE_CHANGED")
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(stateReceiver, filter)
        }
    }

    private fun unregisterReceivers() {
        try {
            context.unregisterReceiver(stateReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
}
