package com.android.systemui.lite.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SystemStateProvider(
    private val context: Context
) {
    companion object {
        private const val TAG = "SystemStateProvider"
    }

    data class BatteryState(
        val level: Int = 100,
        val isCharging: Boolean = false
    )

    data class ConnectivityState(
        val wifiEnabled: Boolean = false,
        val bluetoothEnabled: Boolean = false,
        val airplaneMode: Boolean = false,
        val dndEnabled: Boolean = false
    )

    // --- StateFlows ---
    private val _battery = MutableStateFlow(BatteryState())
    val battery: StateFlow<BatteryState> = _battery.asStateFlow()

    private val _connectivity = MutableStateFlow(ConnectivityState())
    val connectivity: StateFlow<ConnectivityState> = _connectivity.asStateFlow()

    private val _flashlightEnabled = MutableStateFlow(false)
    val flashlightEnabled: StateFlow<Boolean> = _flashlightEnabled.asStateFlow()

    private val _autoRotateEnabled = MutableStateFlow(true)
    val autoRotateEnabled: StateFlow<Boolean> = _autoRotateEnabled.asStateFlow()

    private val _brightness = MutableStateFlow(128)
    val brightness: StateFlow<Int> = _brightness.asStateFlow()

    private val _mediaVolume = MutableStateFlow(50)
    val mediaVolume: StateFlow<Int> = _mediaVolume.asStateFlow()

    private val _ringVolume = MutableStateFlow(50)
    val ringVolume: StateFlow<Int> = _ringVolume.asStateFlow()

    private val _alarmVolume = MutableStateFlow(50)
    val alarmVolume: StateFlow<Int> = _alarmVolume.asStateFlow()

    private val _timeString = MutableStateFlow("")
    val timeString: StateFlow<String> = _timeString.asStateFlow()

    private val _batterySaverEnabled = MutableStateFlow(false)
    val batterySaverEnabled: StateFlow<Boolean> = _batterySaverEnabled.asStateFlow()

    private val _locationEnabled = MutableStateFlow(false)
    val locationEnabled: StateFlow<Boolean> = _locationEnabled.asStateFlow()

    // Internal state
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var receiverRegistered = false

    // --- Unified BroadcastReceiver ---
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                    val percentage = (level * 100) / scale
                    _battery.value = BatteryState(level = percentage, isCharging = charging)
                }

                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    _connectivity.value = _connectivity.value.copy(
                        wifiEnabled = isWifiEnabled()
                    )
                }

                android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    _connectivity.value = _connectivity.value.copy(
                        bluetoothEnabled = isBluetoothEnabled()
                    )
                }

                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    _connectivity.value = _connectivity.value.copy(
                        airplaneMode = isAirplaneModeEnabled()
                    )
                }

                "android.settings.ZEN_MODE_CHANGED" -> {
                    _connectivity.value = _connectivity.value.copy(
                        dndEnabled = isDndEnabled()
                    )
                }
            }
        }
    }

    // --- Time ticker ---
    private val timeRunnable = object : Runnable {
        override fun run() {
            val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            _timeString.value = sdf.format(java.util.Date())
            mainHandler.postDelayed(this, 30000)
        }
    }

    fun start() {
        Log.d(TAG, "Starting SystemStateProvider...")
        readInitialState()
        registerReceiver()
        mainHandler.post(timeRunnable)
        Log.d(TAG, "SystemStateProvider started (battery=${_battery.value.level}%, wifi=${_connectivity.value.wifiEnabled})")
    }

    fun stop() {
        Log.d(TAG, "Stopping SystemStateProvider...")
        unregisterReceiver()
        mainHandler.removeCallbacks(timeRunnable)
    }

    // --- Initial state ---
    private fun readInitialState() {
        // Battery
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryIntent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            _battery.value = BatteryState(
                level = (level * 100) / scale,
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
            )
        }

        // Connectivity
        _connectivity.value = ConnectivityState(
            wifiEnabled = isWifiEnabled(),
            bluetoothEnabled = isBluetoothEnabled(),
            airplaneMode = isAirplaneModeEnabled(),
            dndEnabled = isDndEnabled()
        )

        // Other state
        _autoRotateEnabled.value = isAutoRotateEnabled()
        _brightness.value = getCurrentBrightness()
        _batterySaverEnabled.value = isBatterySaverEnabled()
        _locationEnabled.value = isLocationEnabled()

        // Volume
        audioManager?.let { am ->
            val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val curMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            _mediaVolume.value = if (maxMusic > 0) (curMusic * 100) / maxMusic else 0

            val maxRing = am.getStreamMaxVolume(AudioManager.STREAM_RING)
            val curRing = am.getStreamVolume(AudioManager.STREAM_RING)
            _ringVolume.value = if (maxRing > 0) (curRing * 100) / maxRing else 0

            val maxAlarm = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val curAlarm = am.getStreamVolume(AudioManager.STREAM_ALARM)
            _alarmVolume.value = if (maxAlarm > 0) (curAlarm * 100) / maxAlarm else 0
        }

        // Time
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        _timeString.value = sdf.format(java.util.Date())
    }

    // --- Receiver management ---
    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction("android.settings.ZEN_MODE_CHANGED")
        }
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(systemReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try { context.unregisterReceiver(systemReceiver) } catch (_: Exception) {}
        receiverRegistered = false
    }

    // --- Toggle methods ---

    fun toggleWifi() {
        val newState = !_connectivity.value.wifiEnabled
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.isWifiEnabled = newState
        _connectivity.value = _connectivity.value.copy(wifiEnabled = newState)
    }

    fun toggleBluetooth() {
        val newState = !_connectivity.value.bluetoothEnabled
        try {
            val btClass = Class.forName("android.bluetooth.BluetoothAdapter")
            val getDefaultAdapter = btClass.getMethod("getDefaultAdapter")
            val adapter = getDefaultAdapter.invoke(null)
            if (newState) {
                btClass.getMethod("enable").invoke(adapter)
            } else {
                btClass.getMethod("disable").invoke(adapter)
            }
            _connectivity.value = _connectivity.value.copy(bluetoothEnabled = newState)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle Bluetooth: ${e.message}", e)
        }
    }

    fun toggleDnd() {
        val newState = !_connectivity.value.dndEnabled
        try {
            Settings.Global.putInt(context.contentResolver, "zen_mode", if (newState) 1 else 0)
            _connectivity.value = _connectivity.value.copy(dndEnabled = newState)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle DND: ${e.message}", e)
        }
    }

    fun toggleFlashlight() {
        val newState = !_flashlightEnabled.value
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            cameraManager?.let { cm ->
                val cameraId = cm.cameraIdList?.firstOrNull()
                if (cameraId != null) {
                    cm.setTorchMode(cameraId, newState)
                    _flashlightEnabled.value = newState
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight: ${e.message}", e)
        }
    }

    fun toggleAirplaneMode() {
        val newState = !_connectivity.value.airplaneMode
        try {
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                if (newState) 1 else 0
            )
            val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            intent.putExtra("state", newState)
            context.sendBroadcast(intent)
            _connectivity.value = _connectivity.value.copy(airplaneMode = newState)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle airplane mode: ${e.message}", e)
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle auto-rotate: ${e.message}", e)
        }
    }

    fun setBrightness(value: Int) {
        val clamped = value.coerceIn(0, 255)
        try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, clamped)
            _brightness.value = clamped
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness: ${e.message}", e)
        }
    }

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

    // --- Private helpers ---

    private fun isWifiEnabled(): Boolean = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.isWifiEnabled == true
    } catch (e: Exception) { false }

    private fun isBluetoothEnabled(): Boolean = try {
        val btClass = Class.forName("android.bluetooth.BluetoothAdapter")
        val adapter = btClass.getMethod("getDefaultAdapter").invoke(null)
        btClass.getMethod("isEnabled").invoke(adapter) as Boolean
    } catch (e: Exception) { false }

    private fun isDndEnabled(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, "zen_mode", 0) != 0
    } catch (e: Exception) { false }

    private fun isAirplaneModeEnabled(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    } catch (e: Exception) { false }

    private fun isAutoRotateEnabled(): Boolean = try {
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) != 0
    } catch (e: Exception) { false }

    private fun getCurrentBrightness(): Int = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (e: Exception) { 128 }

    private fun isBatterySaverEnabled(): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        pm?.isPowerSaveMode == true
    } catch (e: Exception) { false }

    private fun isLocationEnabled(): Boolean = try {
        val mode = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE)
        mode != Settings.Secure.LOCATION_MODE_OFF
    } catch (e: Exception) { false }
}
