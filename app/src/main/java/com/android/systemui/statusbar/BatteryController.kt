package com.android.systemui.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryController @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "BatteryController"
    }

    interface BatteryStateListener {
        fun onBatteryLevelChanged(level: Int, isCharging: Boolean)
    }

    private val listeners = mutableListOf<BatteryStateListener>()
    private var batteryLevel = 0
    private var isCharging = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                batteryLevel = (level * 100) / scale
                
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                             status == BatteryManager.BATTERY_STATUS_FULL
                
                Log.d(TAG, "Battery level: $batteryLevel%, charging: $isCharging")
                notifyListeners()
            }
        }
    }

    fun register() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)
        Log.i(TAG, "Battery controller registered")
    }

    fun unregister() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver not registered", e)
        }
    }

    fun addListener(listener: BatteryStateListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: BatteryStateListener) {
        listeners.remove(listener)
    }

    fun getBatteryLevel(): Int = batteryLevel
    fun isCharging(): Boolean = isCharging

    private fun notifyListeners() {
        listeners.forEach { it.onBatteryLevelChanged(batteryLevel, isCharging) }
    }
}
