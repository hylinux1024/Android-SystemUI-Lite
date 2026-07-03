package com.android.systemui.lite.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ambient, non-Quick-Settings system state for the status bar and wallpaper.
 *
 * Owns the battery level, clock time string, and location state. QS tile
 * state and actions live in com.android.systemui.lite.qs.QSTileManager.
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

    // --- StateFlows ---
    private val _battery = MutableStateFlow(BatteryState())
    val battery: StateFlow<BatteryState> = _battery.asStateFlow()

    private val _timeString = MutableStateFlow("")
    val timeString: StateFlow<String> = _timeString.asStateFlow()

    private val _locationEnabled = MutableStateFlow(false)
    val locationEnabled: StateFlow<Boolean> = _locationEnabled.asStateFlow()

    // Internal state
    private val mainHandler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false

    // --- Battery BroadcastReceiver ---
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            val percentage = (level * 100) / scale
            _battery.value = BatteryState(level = percentage, isCharging = charging)
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
        mainHandler.post(timeRunnable)
        Log.d(TAG, "SystemStateProvider started (battery=${_battery.value.level}%)")
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

        // Location
        _locationEnabled.value = isLocationEnabled()

        // Time
        _timeString.value = LocalTime.now().format(timeFormatter)
    }

    // --- Receiver management ---
    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(batteryReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try { context.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        receiverRegistered = false
    }

    // --- Private helpers ---

    private fun isLocationEnabled(): Boolean = try {
        val mode = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE)
        mode != Settings.Secure.LOCATION_MODE_OFF
    } catch (e: Exception) { false }
}
