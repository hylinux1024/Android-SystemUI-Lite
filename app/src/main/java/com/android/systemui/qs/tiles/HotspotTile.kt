package com.android.systemui.qs.tiles

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.android.systemui.R
import com.android.systemui.qs.QSTileBase
import java.lang.reflect.Method

class HotspotTile(context: Context) : QSTileBase(context) {

    companion object {
        private const val TAG = "HotspotTile"
    }

    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var isHotspotEnabled = false

    override fun getLabel(): String = context.getString(R.string.quick_settings_hotspot)
    override fun getIconResId(): Int = R.drawable.qs_hotspot_icon_search
    override fun getActiveIconResId(): Int = R.drawable.qs_hotspot_icon_on

    override fun isActiveState(): Boolean = isHotspotEnabled

    override fun toggle() {
        try {
            val method: Method = wifiManager.javaClass.getDeclaredMethod("setWifiApEnabled", Boolean::class.javaPrimitiveType)
            isHotspotEnabled = !isHotspotEnabled
            method.invoke(wifiManager, isHotspotEnabled)
            Log.i(TAG, "Hotspot ${if (isHotspotEnabled) "on" else "off"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle hotspot", e)
            isHotspotEnabled = false
        }
    }
}
