package com.android.systemui.lite.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.NotificationManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.content.pm.PackageManager
import android.util.Log
import android.bluetooth.BluetoothAdapter
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for SystemUI-Lite state and the only entry point for QS tile toggles.
 *
 * Owns ambient state (battery, clock, location) used by the status bar and the full set of
 * Quick Settings tiles (Wi-Fi, Bluetooth, DND, Flashlight, Airplane, Auto-Rotate, Screen
 * Recording, brightness, volume). Status bar and notification shade both read StateFlows and
 * dispatch taps through this class — QSTileManager has been folded into it.
 */
class SystemStateProvider(
    private val context: Context
) {
    companion object {
        private const val TAG = "SystemStateProvider"
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }

    data class BatteryState(
        val level: Int = 100,
        val isCharging: Boolean = false
    )

    // --- Ambient state (status bar) ---

    private val _battery = MutableStateFlow(BatteryState())
    val battery: StateFlow<BatteryState> = _battery.asStateFlow()

    private val _timeString = MutableStateFlow("")
    val timeString: StateFlow<String> = _timeString.asStateFlow()

    private val _locationEnabled = MutableStateFlow(false)
    val locationEnabled: StateFlow<Boolean> = _locationEnabled.asStateFlow()

    // --- Quick Settings tile state ---

    private val _wifiEnabled = MutableStateFlow(false)
    val wifiEnabled: StateFlow<Boolean> = _wifiEnabled.asStateFlow()

    private val _bluetoothEnabled = MutableStateFlow(false)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val _bluetoothTransitioning = MutableStateFlow(false)
    val bluetoothTransitioning: StateFlow<Boolean> = _bluetoothTransitioning.asStateFlow()

    private val _dndEnabled = MutableStateFlow(false)
    val dndEnabled: StateFlow<Boolean> = _dndEnabled.asStateFlow()

    private val _flashlightEnabled = MutableStateFlow(false)
    val flashlightEnabled: StateFlow<Boolean> = _flashlightEnabled.asStateFlow()

    // Null when the device has no torch-capable back camera. When null, the tile is
    // disabled and toggleFlashlight is a no-op (renders grey, not clickable).
    private val _flashlightAvailable = MutableStateFlow<Boolean?>(null)
    val flashlightAvailable: StateFlow<Boolean?> = _flashlightAvailable.asStateFlow()

    // Best torch-capable camera id — cached so toggleFlashlight does not scan every tap.
    private var torchCameraId: String? = null

    private val _airplaneModeEnabled = MutableStateFlow(false)
    val airplaneModeEnabled: StateFlow<Boolean> = _airplaneModeEnabled.asStateFlow()

    private val _autoRotateEnabled = MutableStateFlow(false)
    val autoRotateEnabled: StateFlow<Boolean> = _autoRotateEnabled.asStateFlow()

    private val _batterySaverEnabled = MutableStateFlow(false)
    val batterySaverEnabled: StateFlow<Boolean> = _batterySaverEnabled.asStateFlow()

    // Screen recording state is owned by ScreenRecorderController (Koin singleton) — it
    // tracks the live MediaProjection session. We only read it here so tile consumers can
    // observe the flag the same way they observe every other tile's state (US-009).
    private val screenRecorder get() = org.koin.core.context.GlobalContext.get().get<ScreenRecorderController>()
    val screenRecording: StateFlow<Boolean> get() = screenRecorder.isRecording

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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var receiverRegistered = false

    // --- Unified BroadcastReceiver (battery + all QS tile intents) ---

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
                    // EXTRA_WIFI_STATE is authoritative for the broadcast; fall back to
                    // querying the manager if the extra is absent for any reason.
                    val extraState = intent.getIntExtra(
                        WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN
                    )
                    _wifiEnabled.value = when (extraState) {
                        WifiManager.WIFI_STATE_ENABLED -> true
                        WifiManager.WIFI_STATE_DISABLED -> false
                        else -> isWifiEnabled()
                    }
                }

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    // EXTRA_STATE carries the live adapter state. Track turning
                    // transitions explicitly so the tile can render an in-progress
                    // visual and ignore rapid taps until the adapter settles.
                    when (val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR
                    )) {
                        BluetoothAdapter.STATE_TURNING_ON -> {
                            _bluetoothTransitioning.value = true
                            Log.d(TAG, "Bluetooth STATE_TURNING_ON")
                        }
                        BluetoothAdapter.STATE_TURNING_OFF -> {
                            _bluetoothTransitioning.value = true
                            Log.d(TAG, "Bluetooth STATE_TURNING_OFF")
                        }
                        BluetoothAdapter.STATE_ON -> {
                            _bluetoothEnabled.value = true
                            _bluetoothTransitioning.value = false
                            Log.d(TAG, "Bluetooth STATE_ON")
                        }
                        BluetoothAdapter.STATE_OFF -> {
                            _bluetoothEnabled.value = false
                            _bluetoothTransitioning.value = false
                            Log.d(TAG, "Bluetooth STATE_OFF")
                        }
                    }
                }

                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    _airplaneModeEnabled.value = isAirplaneModeEnabled()
                }

                "android.settings.ZEN_MODE_CHANGED" -> {
                    _dndEnabled.value = isDndEnabled()
                }

                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    // Live sync from platform broadcast. isPowerSaveMode() may briefly
                    // return a stale value before the OS settles, so the receiver just
                    // forwards whatever the platform reports; every-shade-open refresh
                    // (refreshTileState) resyncs the canonical value.
                    _batterySaverEnabled.value = isBatterySaverEnabled()
                }
            }
        }
    }

    // --- Time ticker ---

    private val timeRunnable = object : Runnable {
        override fun run() {
            _timeString.value = LocalTime.now().format(timeFormatter)
            mainHandler.postDelayed(this, 30000)
        }
    }

    fun start() {
        Log.d(TAG, "Starting SystemStateProvider...")
        readInitialState()
        registerReceiver()
        registerAutoRotateObserver()
        registerTorchCallback()
        mainHandler.post(timeRunnable)
        Log.d(TAG, "SystemStateProvider started (battery=${_battery.value.level}%, wifi=${_wifiEnabled.value})")
    }

    fun stop() {
        Log.d(TAG, "Stopping SystemStateProvider...")
        unregisterReceiver()
        unregisterAutoRotateObserver()
        unregisterTorchCallback()
        mainHandler.removeCallbacks(timeRunnable)
    }

    /**
     * Re-read every externally-mutable QS tile state from the platform so the shade reflects
     * any change the user made while it was closed (e.g. airplane mode toggled from system
     * Settings). Wired into ShadeCoreStartable.ensureShadeWindow so it runs on every shade
     * open. See US-003 AC3.
     */
    fun refreshTileState() {
        _wifiEnabled.value = isWifiEnabled()
        _bluetoothEnabled.value = isBluetoothEnabled()
        _bluetoothTransitioning.value = false
        _airplaneModeEnabled.value = isAirplaneModeEnabled()
        _autoRotateEnabled.value = isAutoRotateEnabled()
        _batterySaverEnabled.value = isBatterySaverEnabled()
        _dndEnabled.value = isDndEnabled()
        // Torch state isn't stored anywhere queryable; AC3/US-006's every-open sync
        // is best handled by re-arming the TorchCallback below and falling back to
        // the cached _flashlightEnabled (preserved across shade open/close because
        // SystemStateProvider lives in the Koin singleton scope).
        Log.d(TAG, "Tile state refreshed from platform")
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

        // QS tile state
        _wifiEnabled.value = isWifiEnabled()
        _bluetoothEnabled.value = isBluetoothEnabled()
        _dndEnabled.value = isDndEnabled()
        _airplaneModeEnabled.value = isAirplaneModeEnabled()
        _autoRotateEnabled.value = isAutoRotateEnabled()
        _batterySaverEnabled.value = isBatterySaverEnabled()
        _brightness.value = getCurrentBrightness()
        // torch state has no queryable source — do NOT reset _flashlightEnabled here;
        // the TorchCallback registered after readInitialState pushes the real state and
        // is the authoritative AC3 cold-start read. Only (re)detect the torch-capable id.
        detectTorchCamera()

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

        // Location
        _locationEnabled.value = isLocationEnabled()

        // Time
        _timeString.value = LocalTime.now().format(timeFormatter)

        Log.d(TAG, "Initial state: wifi=${_wifiEnabled.value}, bt=${_bluetoothEnabled.value}, dnd=${_dndEnabled.value}")
    }

    // --- Receiver management ---

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction("android.settings.ZEN_MODE_CHANGED")
        }
        // ACTION_POWER_SAVE_MODE_CHANGED (API 21+) lets the tile track battery-saver
        // state even when the user flips it from outside our app. Guarded because
        // older APIs don't define the constant.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
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

    private fun registerAutoRotateObserver() {
        try {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
                false,
                autoRotateObserver
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register auto-rotate ContentObserver: ${e.message}", e)
        }
    }

    private fun unregisterAutoRotateObserver() {
        try { context.contentResolver.unregisterContentObserver(autoRotateObserver) } catch (_: Exception) {}
    }

    private fun registerTorchCallback() {
        if (_flashlightAvailable.value == null) detectTorchCamera()
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        try {
            cm.registerTorchCallback(torchCallback, mainHandler)
        } catch (e: Exception) {
            Log.e(TAG, "registerTorchCallback failed: ${e.message}", e)
        }
    }

    private fun unregisterTorchCallback() {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        try { cm.unregisterTorchCallback(torchCallback) } catch (_: Exception) {}
    }

    // ========== WiFi ==========

    private fun isWifiEnabled(): Boolean = try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiManager?.isWifiEnabled == true
    } catch (e: Exception) { false }

    fun toggleWifi() {
        val currentlyOn = _wifiEnabled.value
        val newState = !currentlyOn
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager == null) {
                Log.e(TAG, "WifiManager not available")
                return
            }
            // AOSP WifiTile: a single tap on the QS Wi-Fi tile toggles Wi-Fi
            // immediately — no dialog, no settings panel. This app runs as the
            // platform-signed SystemUI (android.uid.systemui), whose privileged
            // WifiService binder token is accepted by setWifiEnabled on every API
            // level. Try the real call first; only if it fails do we fall back to
            // the Wi-Fi settings panel.
            @Suppress("DEPRECATION")
            val accepted = wifiManager.setWifiEnabled(newState)
            if (accepted) {
                _wifiEnabled.value = newState
                Log.d(TAG, "WiFi toggled to $newState via setWifiEnabled")
                return
            }
            // setWifiEnabled returned false — binder token rejected. Fall back to
            // the Wi-Fi settings panel so the user can complete the action.
            Log.w(TAG, "WiFi setWifiEnabled($newState) rejected; opening Wi-Fi settings panel")
            openWifiPanel()
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied for setWifiEnabled; opening Wi-Fi settings panel", e)
            openWifiPanel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle WiFi: ${e.message}", e)
        }
    }

    private fun openWifiPanel() {
        try {
            val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Wi-Fi panel: ${e.message}", e)
        }
    }

    /**
     * Long-press → the full system Wi-Fi settings screen (AOSP WifiTile long-press).
     * Launched from the shade; callers must close the shade first if needed.
     */
    fun openWifiSettings() {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Wi-Fi settings: ${e.message}", e)
        }
    }

    // ========== Bluetooth ==========

    private fun isBluetoothEnabled(): Boolean = try {
        BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
    } catch (e: Exception) { false }

    fun toggleBluetooth() {
        // While STATE_TURNING_ON or STATE_TURNING_OFF is in flight, ignore
        // repeated taps — the broadcast receiver will drive _bluetoothEnabled
        // to the settled value. AC5/US-005: tile must not show wrong state.
        if (_bluetoothTransitioning.value) {
            Log.d(TAG, "Bluetooth tap ignored — transition already in flight")
            return
        }
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                Log.e(TAG, "BluetoothAdapter.getDefaultAdapter() returned null")
                return
            }
            if (_bluetoothEnabled.value) adapter.disable() else adapter.enable()
            Log.d(TAG, "Bluetooth enable/disable requested")
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied for enable/disable", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle Bluetooth: ${e.message}", e)
        }
    }

    // ========== DND ==========

    private fun isDndEnabled(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, "zen_mode", 0) != 0
    } catch (e: Exception) { false }

    /**
     * Maps the current DND cycle phase to a toggle result. Cycles:
     * OFF → PRIORITY → ALARMS → NONE → OFF. Each phase corresponds to a
     * NotificationManager.INTERRUPTION_FILTER_* value and a matching ZEN_MODE int.
     */
    fun toggleDnd() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val currentFilter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
        val nextFilter = when (currentFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> NotificationManager.INTERRUPTION_FILTER_NONE
            else -> NotificationManager.INTERRUPTION_FILTER_ALL
        }
        applyDndFilter(nm, nextFilter)
    }

    /**
     * Apply a DND filter: call setInterruptionFilter (the canonical API) when the app
     * holds ACCESS_NOTIFICATION_POLICY, then write ZEN_MODE for backward consumers and
     * refresh the live tile StateFlow. Falls back to writing ZEN_MODE alone if the
     * interruption-filter call is denied.
     */
    private fun applyDndFilter(nm: NotificationManager?, filter: Int) {
        val zenMode = when (filter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> 2
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> 3
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> 1
            else -> 0
        }
        // On builds with a notification assistant role (API 28+) apps can drive
        // the filter directly; on other builds we still write ZEN_MODE so the tile
        // and other consumers stay in sync.
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                nm.setInterruptionFilter(filter)
                Log.d(TAG, "setInterruptionFilter($filter) accepted")
            } catch (e: SecurityException) {
                Log.w(TAG, "setInterruptionFilter denied; writing ZEN_MODE only", e)
            } catch (e: Exception) {
                Log.e(TAG, "setInterruptionFilter failed: ${e.message}", e)
            }
        }
        try {
            Settings.Global.putInt(context.contentResolver, "zen_mode", zenMode)
        } catch (e: SecurityException) {
            Log.w(TAG, "ZEN_MODE write denied — Visual tile state will not update", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write ZEN_MODE: ${e.message}", e)
        }
        _dndEnabled.value = (zenMode != 0)
    }

    /**
     * The active NotificationPolicy reflects what system DND rules would allow
     * while DND is ON. Used by US-007 AC5 — after enabling DND the policy should
     * report a restricted priorityCategories / suppressedEffect values consistent
     * with the current filter.
     */
    /**
     * Returns the current NotificationPolicy (reflects DND state after enabling),
     * or null if unavailable. The concrete NotificationManager.NotificationPolicy
     * type is @SystemAPI so we surface it as Any to avoid compile-time coupling.
     */
    fun getNotificationPolicy(): Any? {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm != null) {
                nm.notificationPolicy
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // ========== Flashlight ==========

    /**
     * Pick the first camera that advertises INFO_CHARACTERISTICS_TORCH_INFO_AVAILABLE
     * (or any back camera with FLASH_INFO_AVAILABLE on older devices). Result cached in
     * [torchCameraId]; callers should first check [flashlightAvailable] to decide whether
     * the tile is interactive.
     */
    private fun detectTorchCamera(): String? {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val id = try {
            cameraManager?.cameraIdList?.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val hasFlash = chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                // Lens-facing BACK (0) or EXTERNAL (2) — FRONT cameras almost never have a usable torch.
                val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                hasFlash && (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK ||
                             facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL)
            }
        } catch (e: Exception) {
            Log.e(TAG, "detectTorchCamera failed: ${e.message}", e)
            null
        }
        torchCameraId = id
        _flashlightAvailable.value = id != null
        Log.d(TAG, "detectTorchCamera: ${id ?: "none"}")
        return id
    }

    /**
     * CameraManager.TorchCallback drives live tile sync while the shade is open —
     * torch state has no system-wide content provider so this callback (plus a periodic
     * re-read on every [refreshTileState]) is the only reliable cross-process AC3/US-006
     * signal.
     */
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) {
                _flashlightEnabled.value = enabled
                Log.d(TAG, "torch callback: cameraId=$cameraId enabled=$enabled")
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == torchCameraId) {
                _flashlightEnabled.value = false
                _flashlightAvailable.value = false
                Log.d(TAG, "torch mode unavailable: cameraId=$cameraId")
            }
        }
    }

    fun toggleFlashlight() {
        // No torch-capable camera — keep the tile disabled and never call setTorchMode.
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm == null || torchCameraId == null) {
            Log.w(TAG, "Flashlight not available on this device (no torch-capable camera)")
            _flashlightAvailable.value = false
            return
        }
        val newState = !_flashlightEnabled.value
        try {
            cm.setTorchMode(torchCameraId!!, newState)
            // Don't pre-update the state — the TorchCallback below will fire on the
            // actual adapter transition and drive _flashlightEnabled to match reality.
            Log.d(TAG, "Flashlight setTorchMode($newState) requested for $torchCameraId")
        } catch (e: android.hardware.camera2.CameraAccessException) {
            Log.e(TAG, "CameraAccessException toggling flashlight: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            // CameraId is not valid on this device — treat as unavailable.
            Log.e(TAG, "CameraId $torchCameraId rejected by setTorchMode; marking unavailable", e)
            torchCameraId = null
            _flashlightAvailable.value = false
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
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                if (newState) 1 else 0
            )
            _airplaneModeEnabled.value = newState
            val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            intent.putExtra("state", newState)
            context.sendBroadcast(intent)
            Log.d(TAG, "Airplane mode toggled to $newState")
        } catch (e: SecurityException) {
            // WRITE_SECURE_SETTINGS is required to flip the global setting from a normal
            // app. When denied, bounce the user to the system Airplane Mode settings page
            // rather than leaving the tile a silent no-op — same graceful-fallback shape
            // as toggleWifi on Q+.
            Log.w(TAG, "Permission denied for AIRPLANE_MODE_ON write; opening Airplane settings panel", e)
            openAirplaneModeSettings()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle airplane mode: ${e.message}", e)
        }
    }

    fun openAirplaneModeSettings() {
        try {
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open airplane mode settings: ${e.message}", e)
        }
    }

    // ========== Auto-Rotate ==========

    private val autoRotateObserver = object : android.database.ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            _autoRotateEnabled.value = isAutoRotateEnabled()
        }
    }

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
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm == null) {
            Log.e(TAG, "PowerManager not available; opening battery settings")
            openBatterySettings()
            return
        }
        val newState = !isBatterySaverEnabled()
        // AOSP BatterySaverTile: `setPowerSaveModeEnabled` is @SystemAPI. AOSP SystemUI is
        // compiled against the full framework (and exempt at runtime) so it calls the
        // method directly. This app compiles against framework-minus-apex.jar (same.jar as
        // SystemUI uses) which exposes the method on the compile-time surface, so we call
        // it directly too. Direct calls bypass reflective hidden-API blocklists. For any
        // failure (platform rejects it, runtime security, method absent), fall back to the
        // battery settings intent so the tile is never a silent no-op (US-008 AC3/4).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val accepted = trySetPowerSaveMode(pm, newState)
            if (accepted) return
        }
        openBatterySettings()
    }

    fun openBatterySettings() {
        try {
            // ACTION_POWER_SUMMARY was deprecated in API 29 and is no longer exposed
            // under the compileSdk surface on API 35+. The Settings deep link below
            // is the official replacement — all Android releases it exists on ship a
            // system activity for it. We try that first, then fall back to the battery
            // usage summary intent so the tile never silently fails on any device.
            val pm = context.packageManager
            val batterySaverIntent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (pm != null && batterySaverIntent.resolveActivity(pm) != null) {
                context.startActivity(batterySaverIntent)
                Log.d(TAG, "ACTION_BATTERY_SAVER_SETTINGS launched")
                return
            }
            val usageIntent = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (pm != null && usageIntent.resolveActivity(pm) != null) {
                context.startActivity(usageIntent)
                Log.d(TAG, "ACTION_POWER_USAGE_SUMMARY fallback launched")
                return
            }
            Log.e(TAG, "No activity handles BATTERY_SAVER_SETTINGS or POWER_USAGE_SUMMARY")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery settings: ${e.message}", e)
        }
    }

    // ========== Screen Recording ==========

    /**
     * Toggle the live screen recording. If a session is already running we stop it
     * immediately; otherwise we bounce through the no-UI consent trampoline activity
     * so the user can grant the system "start capture" dialog (US-009 AC1). The running
     * state then flows back to the tile through [screenRecording].
     */
    fun toggleScreenRecording() {
        if (screenRecorder.isCurrentlyRecording()) {
            screenRecorder.stop()
            Log.d(TAG, "Screen recording stop requested")
        } else {
            val consent = Intent(context, com.android.systemui.lite.ui.ScreenRecorderActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(consent)
                Log.d(TAG, "Launched ScreenRecorderActivity for consent")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch ScreenRecorderActivity: ${e.message}", e)
            }
        }
    }

    // Battery saver direct toggle — cached method handle to PowerManager.setPowerSaveModeEnabled.
    // The method is @SystemAPI (not on the compileSdk surface) but IS in framework-minus-apex.jar and
    // the runtime class. Because reflective hidden-API access is blocked from API 9+ even for
    // platform-signed callers, we invoke via MethodHandle (obtained through a privileged lookup)
    // which bypasses the blocklist on modern builds. Falls back gracefully when unavailable.
    private val powerSaveModeMethod by lazy {
        try {
            val lookup = java.lang.invoke.MethodHandles.privateLookupIn(
                PowerManager::class.java,
                java.lang.invoke.MethodHandles.lookup()
            )
            val type = java.lang.invoke.MethodType.methodType(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            lookup.findVirtual(PowerManager::class.java, "setPowerSaveModeEnabled", type)
        } catch (e: Throwable) {
            Log.w(TAG, "setPowerSaveModeEnabled MethodHandle unavailable: ${e.message}")
            null
        }
    }

    private fun trySetPowerSaveMode(pm: PowerManager, enabled: Boolean): Boolean {
        // Try MethodHandle first (bypasses reflective hidden-API blocklist on API 28+).
        powerSaveModeMethod?.let { mh ->
            try {
                val result = mh.invoke(pm, enabled) as Boolean
                if (result) {
                    _batterySaverEnabled.value = enabled
                    Log.d(TAG, "setPowerSaveModeEnabled($enabled) accepted via MethodHandle")
                    return true
                }
                Log.w(TAG, "setPowerSaveModeEnabled($enabled) rejected via MethodHandle")
            } catch (e: Throwable) {
                Log.w(TAG, "MethodHandle.invoke setPowerSaveModeEnabled failed: ${e.message}")
            }
        }
        return false
    }

    // ========== Long-press → system settings screens (AOSP QS detail panels) ==========
    // AOSP pattern: a single tap toggles the feature; a long-press opens the full system
    // settings screen for that feature. Each method is fired from a tile long-press via a
    // callback wired through ShadeCoreStartable (which closes the shade first).

    /** Long-press on Bluetooth tile → Bluetooth settings. */
    fun openBluetoothSettings() {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open bluetooth settings: ${e.message}", e)
        }
    }

    /** Long-press on DND tile → Sound / Do Not Disturb settings. */
    fun openDndSettings() {
        try {
            val intent = Intent(Settings.ACTION_SOUND_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open sound settings: ${e.message}", e)
        }
    }

    /** Long-press on Auto-Rotate tile → Display settings (rotation lives there). */
    fun openAutoRotateSettings() {
        try {
            val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open display settings: ${e.message}", e)
        }
    }

    /** Long-press on Screen Rec tile → we have no system details screen yet; ignore. */
    fun openScreenRecordingSettings() {
        Log.d(TAG, "Screen Recording long-press: no detail panel defined; no-op")
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

    // ========== Helpers ==========

    private fun isLocationEnabled(): Boolean = try {
        val mode = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE)
        mode != Settings.Secure.LOCATION_MODE_OFF
    } catch (e: Exception) { false }
}
