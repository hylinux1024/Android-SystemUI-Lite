package com.android.systemui.lite.qs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.bluetooth.BluetoothAdapter

/**
 * Single owner of Quick Settings tile state and toggle actions.
 *
 * Every QS tile reads its active/inactive state and dispatches taps through
 * this class. SystemStateProvider owns ambient, non-QS state (battery level,
 * clock, location); this class owns the connectivity/torch/rotation tiles.
 */
class QSTileManager(private val context: Context) {

    companion object {
        private const val TAG = "QSTileManager"
    }

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

    private val _screenRecording = MutableStateFlow(false)
    val screenRecording: StateFlow<Boolean> = _screenRecording.asStateFlow()

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

    fun start() {
        Log.d(TAG, "Starting QSTileManager...")
        readInitialState()
        registerReceiver()
        Log.d(TAG, "QSTileManager started")
    }

    fun stop() {
        Log.d(TAG, "Stopping QSTileManager...")
        unregisterReceiver()
    }

    private fun readInitialState() {
        _wifiEnabled.value = isWifiEnabled()
        _bluetoothEnabled.value = isBluetoothEnabled()
        _dndEnabled.value = isDndEnabled()
        _airplaneModeEnabled.value = isAirplaneModeEnabled()
        _autoRotateEnabled.value = isAutoRotateEnabled()
        _batterySaverEnabled.value = isBatterySaverEnabled()
        _screenRecording.value = false
        _brightness.value = getCurrentBrightness()
        _flashlightEnabled.value = false
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

    private fun isWifiEnabled(): Boolean = try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiManager?.isWifiEnabled == true
    } catch (e: Exception) { false }

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

    private fun isBluetoothEnabled(): Boolean = try {
        BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
    } catch (e: Exception) { false }

    fun toggleBluetooth() {
        val newState = !_bluetoothEnabled.value
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            if (newState) adapter.enable() else adapter.disable()
            _bluetoothEnabled.value = newState
            Log.d(TAG, "Bluetooth toggled to $newState")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle Bluetooth: ${e.message}", e)
        }
    }

    // ========== DND ==========

    private fun isDndEnabled(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, "zen_mode", 0) != 0
    } catch (e: Exception) { false }

    fun toggleDnd() {
        val newState = !_dndEnabled.value
        try {
            val zenMode = if (newState) 1 else 0
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

    private fun isAirplaneModeEnabled(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    } catch (e: Exception) { false }

    fun toggleAirplaneMode() {
        val newState = !_airplaneModeEnabled.value
        try {
            Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, if (newState) 1 else 0)
            _airplaneModeEnabled.value = newState
            val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            intent.putExtra("state", newState)
            context.sendBroadcast(intent)
            Log.d(TAG, "Airplane mode toggled to $newState")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle airplane mode: ${e.message}", e)
        }
    }

    // ========== Auto-Rotate ==========

    private fun isAutoRotateEnabled(): Boolean = try {
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) != 0
    } catch (e: Exception) { false }

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

    private fun isBatterySaverEnabled(): Boolean = try {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        powerManager?.isPowerSaveMode == true
    } catch (e: Exception) { false }

    fun toggleBatterySaver() {
        // Direct API toggle is platform-gated; callers fall back to settings intent.
        Log.d(TAG, "Battery saver toggle requested (system-controlled)")
        _batterySaverEnabled.value = isBatterySaverEnabled()
    }

    // ========== Screen Recording ==========

    fun toggleScreenRecording() {
        // US-009 wires a real MediaProjection flow into this entry point.
        _screenRecording.value = !_screenRecording.value
        Log.d(TAG, "Screen recording requested, active=${_screenRecording.value}")
    }

    // ========== Brightness ==========

    private fun getCurrentBrightness(): Int = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (e: Exception) { 128 }

    fun setBrightness(value: Int) {
        val clamped = value.coerceIn(0, 255)
        try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, clamped)
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

    // ========== Broadcast Receiver ==========

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    _wifiEnabled.value = isWifiEnabled()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
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

    private var receiverRegistered = false

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction("android.settings.ZEN_MODE_CHANGED")
        }
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(stateReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(stateReceiver)
        } catch (e: Exception) {
            // already unregistered
        }
        receiverRegistered = false
    }
}
