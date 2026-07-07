package com.android.systemui.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.util.Log
import com.android.systemui.CoreStartable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks WiFi state and signal level (0..4) and reports them to a listener
 * (status bar wifi_icon ImageView).
 *
 * Contract:
 *  - RSSI → level via `WifiManager.calculateSignalLevel(rssi, 5)`.
 *  - `enabled=false` (WiFi off) hides the icon entirely.
 *  - Probes initial state on start() so the icon is correct before the first RSSI_CHANGED.
 *
 * Uses framework drawables `@android:drawable/stat_sys_wifi_signal_0..4`
 * via resource lookup (devices ship these), to mirror AOSP wifi icon slot.
 */
@Singleton
class WifiController @Inject constructor(
    @ApplicationContext private val context: Context
) : CoreStartable {

    companion object {
        private const val TAG = "WifiController"
        private const val LEVELS = 5
    }

    /** Invoked every time WiFi connectivity changes. */
    fun interface WifiStateListener {
        fun onWifiStateChanged(level: Int, enabled: Boolean, rssi: Int)
    }

    private val wifiManager: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    private var listener: WifiStateListener? = null
    @Volatile private var enabled = false
    @Volatile private var rssi = 0
    private var registered = false

    fun register(listener: WifiStateListener) {
        this.listener = listener
    }

    fun unregister() {
        this.listener = null
    }

    override fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        context.registerReceiver(wifiReceiver, filter)
        registered = true
        // Probe initial so the first frame already reflects reality.
        update()
        Log.i(TAG, "WifiController started")
    }

    override fun stop() {
        if (!registered) return
        try { context.unregisterReceiver(wifiReceiver) } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver not registered", e)
        }
        registered = false
    }

    private fun update() {
        val wm = wifiManager ?: return
        enabled = wm.isWifiEnabled
        if (enabled) {
            val info = wm.connectionInfo
            // info is null until fully associated; fall back to a mid-level bar.
            rssi = info?.rssi ?: WifiManager.calculateSignalLevel(-50, LEVELS) - 1
        }
        val level = if (enabled)
            WifiManager.calculateSignalLevel(rssi.coerceIn(-100, -30), LEVELS) - 1
        else
            -1
        listener?.onWifiStateChanged(level.coerceIn(-1, LEVELS - 1), enabled, rssi)
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.RSSI_CHANGED_ACTION ->
                    rssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -50)
                WifiManager.WIFI_STATE_CHANGED_ACTION ->
                    enabled = intent.getIntExtra(
                        WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN
                    ) == WifiManager.WIFI_STATE_ENABLED
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> { /* re-runs via rssi/state */ }
            }
            update()
        }
    }
}
