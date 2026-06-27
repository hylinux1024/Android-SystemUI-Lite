package com.android.systemui.lite.systemui.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.android.systemui.lite.SystemUIApplication

/**
 * StatusBarManager - The central coordinator for the real status bar.
 *
 * This class:
 * 1. Creates and manages the TYPE_STATUS_BAR window
 * 2. Registers with system_server via CommandQueue
 * 3. Reads real system state (battery, wifi, bluetooth, time)
 * 4. Notifies the UI layer of state changes
 *
 * This is the real-device counterpart to the simulation in SystemUIViewModel.
 */
class StatusBarManager(private val context: Context) {

    companion object {
        private const val TAG = "StatusBarManager"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowController = StatusBarWindowController(context)
    private val commandQueue = CommandQueue()

    // System state
    private var batteryLevel = 100
    private var isCharging = false
    private var isWifiOn = false
    private var isBluetoothOn = false
    private var isDoNotDisturb = false
    private var isAirplaneMode = false
    private var timeString = ""

    // State change listeners
    private val stateListeners = mutableListOf<StateListener>()

    interface StateListener {
        fun onBatteryChanged(level: Int, charging: Boolean) {}
        fun onWifiChanged(enabled: Boolean) {}
        fun onBluetoothChanged(enabled: Boolean) {}
        fun onDoNotDisturbChanged(enabled: Boolean) {}
        fun onAirplaneModeChanged(enabled: Boolean) {}
        fun onTimeChanged(time: String) {}
    }

    // Broadcast receivers for system state
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val percentage = (level * 100) / scale
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL

                    if (percentage != batteryLevel || charging != isCharging) {
                        batteryLevel = percentage
                        isCharging = charging
                        notifyBatteryChanged(percentage, charging)
                    }
                }
            }
        }
    }

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(
                        WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN
                    )
                    val enabled = state == WifiManager.WIFI_STATE_ENABLED
                    if (enabled != isWifiOn) {
                        isWifiOn = enabled
                        notifyWifiChanged(enabled)
                    }
                }
                android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        android.bluetooth.BluetoothAdapter.EXTRA_STATE,
                        android.bluetooth.BluetoothAdapter.ERROR
                    )
                    val enabled = state == android.bluetooth.BluetoothAdapter.STATE_ON
                    if (enabled != isBluetoothOn) {
                        isBluetoothOn = enabled
                        notifyBluetoothChanged(enabled)
                    }
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    val enabled = Settings.Global.getInt(
                        context.contentResolver,
                        Settings.Global.AIRPLANE_MODE_ON, 0
                    ) != 0
                    if (enabled != isAirplaneMode) {
                        isAirplaneMode = enabled
                        notifyAirplaneModeChanged(enabled)
                    }
                }
                NotificationManagerCompat.ACTION_DO_NOT_DISTURB_CHANGED -> {
                    try {
                        val enabled = Settings.Global.getInt(
                            context.contentResolver,
                            "zen_mode", 0
                        ) != 0
                        if (enabled != isDoNotDisturb) {
                            isDoNotDisturb = enabled
                            notifyDoNotDisturbChanged(enabled)
                        }
                    } catch (e: Exception) {
                        // zen_mode might not exist on all devices
                    }
                }
            }
        }
    }

    // Time ticker
    private val timeRunnable = object : Runnable {
        override fun run() {
            updateTimeString()
            mainHandler.postDelayed(this, 1000)
        }
    }

    /**
     * Start the status bar manager.
     * This is called from the CoreStartable bootstrap.
     */
    fun start() {
        Log.d(TAG, "Starting StatusBarManager...")

        // Read initial system state
        readInitialState()

        // Create and attach the status bar window
        val heightPx = windowController.getStatusBarHeight()
        // Note: In a real implementation, we'd create a PhoneStatusBarView here
        // For now, the window will be managed by SystemUIOverlayService

        // Register with system_server
        val registered = commandQueue.register()
        if (registered) {
            Log.d(TAG, "Successfully registered CommandQueue with system_server")
        } else {
            Log.w(TAG, "CommandQueue registration failed, running in standalone mode")
        }

        // Register broadcast receivers
        registerReceivers()

        // Start time ticker
        mainHandler.post(timeRunnable)

        Log.d(TAG, "StatusBarManager started (battery=${batteryLevel}%, wifi=$isWifiOn, bt=$isBluetoothOn)")
    }

    /**
     * Stop the status bar manager.
     */
    fun stop() {
        Log.d(TAG, "Stopping StatusBarManager...")

        // Unregister broadcast receivers
        unregisterReceivers()

        // Stop time ticker
        mainHandler.removeCallbacks(timeRunnable)

        // Detach window
        windowController.detach()
    }

    /**
     * Read the initial system state.
     */
    private fun readInitialState() {
        // Battery
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryIntent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            batteryLevel = (level * 100) / scale
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        }

        // WiFi
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        isWifiOn = wifiManager?.isWifiEnabled == true

        // Bluetooth
        isBluetoothOn = try {
            val btClass = Class.forName("android.bluetooth.BluetoothAdapter")
            val getDefaultAdapter = btClass.getMethod("getDefaultAdapter")
            val adapter = getDefaultAdapter.invoke(null)
            val isEnabled = btClass.getMethod("isEnabled")
            isEnabled.invoke(adapter) as Boolean
        } catch (e: Exception) {
            false
        }

        // Airplane mode
        isAirplaneMode = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON, 0
        ) != 0

        // Do Not Disturb
        isDoNotDisturb = try {
            Settings.Global.getInt(context.contentResolver, "zen_mode", 0) != 0
        } catch (e: Exception) {
            false
        }

        // Time
        updateTimeString()
    }

    private fun updateTimeString() {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val newTime = sdf.format(java.util.Date())
        if (newTime != timeString) {
            timeString = newTime
            notifyTimeChanged(newTime)
        }
    }

    /**
     * Register broadcast receivers for system state changes.
     * Uses RECEIVER_NOT_EXPORTED for Android 14+ compatibility.
     */
    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(NotificationManagerCompat.ACTION_DO_NOT_DISTURB_CHANGED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(connectivityReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(batteryReceiver, filter)
            context.registerReceiver(connectivityReceiver, filter)
        }
    }

    /**
     * Unregister broadcast receivers.
     */
    private fun unregisterReceivers() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
        try {
            context.unregisterReceiver(connectivityReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }

    // Listener management

    fun addStateListener(listener: StateListener) {
        synchronized(stateListeners) {
            stateListeners.add(listener)
        }
    }

    fun removeStateListener(listener: StateListener) {
        synchronized(stateListeners) {
            stateListeners.remove(listener)
        }
    }

    // Notification methods

    private fun notifyBatteryChanged(level: Int, charging: Boolean) {
        synchronized(stateListeners) {
            stateListeners.forEach { it.onBatteryChanged(level, charging) }
        }
    }

    private fun notifyWifiChanged(enabled: Boolean) {
        synchronized(stateListeners) {
            stateListeners.forEach { it.onWifiChanged(enabled) }
        }
    }

    private fun notifyBluetoothChanged(enabled: Boolean) {
        synchronized(stateListeners) {
            stateListeners.forEach { it.onBluetoothChanged(enabled) }
        }
    }

    private fun notifyDoNotDisturbChanged(enabled: Boolean) {
        synchronized(stateListeners) {
            stateListeners.forEach { it.onDoNotDisturbChanged(enabled) }
        }
    }

    private fun notifyAirplaneModeChanged(enabled: Boolean) {
        synchronized(stateListeners) {
            stateListeners.forEach { it.onAirplaneModeChanged(enabled) }
        }
    }

    private fun notifyTimeChanged(time: String) {
        synchronized(stateListeners) {
            stateListeners.forEach { it.onTimeChanged(time) }
        }
    }

    // Public getters

    fun getBatteryLevel() = batteryLevel
    fun isCharging() = isCharging
    fun isWifiEnabled() = isWifiOn
    fun isBluetoothEnabled() = isBluetoothOn
    fun isDoNotDisturbEnabled() = isDoNotDisturb
    fun isAirplaneModeEnabled() = isAirplaneMode
    fun getTimeString() = timeString

    fun getCommandQueue() = commandQueue
    fun getWindowController() = windowController
}

/**
 * Placeholder for NotificationManagerCompat - will be replaced with real implementation
 * in Phase 3 when we add NotificationListenerService.
 */
object NotificationManagerCompat {
    const val ACTION_DO_NOT_DISTURB_CHANGED = "android.settings.ZEN_MODE_CHANGED"
}
