package com.android.systemui.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.android.systemui.CoreStartable
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens to ACTION_BATTERY_CHANGED and exposes a small model of state-of-charge
 * + charging-fraction to a registered listener (status bar Battery level Text /
 * icon).
 *
 * Faithful port of SystemUI-Lite's BatteryController. The drawable selections
 * (`stat_sys_*_charge` / plain battery skin) are handled by the listener
 * (StatusBarManager.updateBatteryUI); this class stays a pure data source.
 */
@Singleton
class BatteryController @Inject constructor(
    @ApplicationContext private val context: Context
) : CoreStartable {

    companion object {
        private const val TAG = "BatteryController"
    }

    interface BatteryStateListener {
        fun onBatteryLevelChanged(level: Int, isCharging: Boolean)
    }

    private val listeners = CopyOnWriteArrayList<BatteryStateListener>()
    @Volatile private var batteryLevel = 100
    @Volatile private var isCharging = false
    @Volatile private var isRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            batteryLevel = (level * 100 / scale).coerceIn(0..100)
            val status = intent.getIntExtra(
                BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN
            )
            isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL)
            notifyListeners()
        }
    }

    override fun start() {
        if (isRegistered) return
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        isRegistered = true
        Log.i(TAG, "BatteryController started")
    }

    override fun stop() {
        if (!isRegistered) return
        try { context.unregisterReceiver(batteryReceiver) } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver not registered", e)
        }
        isRegistered = false
    }

    fun addListener(listener: BatteryStateListener) { listeners.add(listener) }
    fun removeListener(listener: BatteryStateListener) { listeners.remove(listener) }
    fun getBatteryLevel(): Int = batteryLevel
    fun isCharging(): Boolean = isCharging

    private fun notifyListeners() {
        listeners.forEach { it.onBatteryLevelChanged(batteryLevel, isCharging) }
    }
}
