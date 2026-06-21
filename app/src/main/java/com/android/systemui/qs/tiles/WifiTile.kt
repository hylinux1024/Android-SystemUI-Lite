package com.android.systemui.qs.tiles

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.android.systemui.R
import com.android.systemui.qs.QSTileBase

class WifiTile(context: Context) : QSTileBase(context) {

    companion object {
        private const val TAG = "WifiTile"
    }

    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    override fun getLabel(): String = context.getString(R.string.quick_settings_wifi)
    override fun getIconResId(): Int = R.drawable.qs_wifi_icon_off
    override fun getActiveIconResId(): Int = R.drawable.qs_wifi_icon_on

    override fun isActiveState(): Boolean {
        return try {
            wifiManager.isWifiEnabled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get WiFi state", e)
            false
        }
    }

    override fun toggle() {
        try {
            if (wifiManager.isWifiEnabled) {
                wifiManager.isWifiEnabled = false
                Log.i(TAG, "WiFi disabled")
            } else {
                wifiManager.isWifiEnabled = true
                Log.i(TAG, "WiFi enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle WiFi", e)
        }
    }
}
