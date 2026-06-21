package com.android.systemui.qs.tiles

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.android.systemui.R
import com.android.systemui.qs.QSTileBase

class AirplaneTile(context: Context) : QSTileBase(context) {

    companion object {
        private const val TAG = "AirplaneTile"
        private const val AIRPLANE_MODE_ON = "airplane_mode_on"
    }

    override fun getLabel(): String = context.getString(R.string.quick_settings_airplane)
    override fun getIconResId(): Int = R.drawable.qs_airplane_icon_off
    override fun getActiveIconResId(): Int = R.drawable.qs_airplane_icon_on

    override fun isActiveState(): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, AIRPLANE_MODE_ON, 0) == 1
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get airplane mode state", e)
            false
        }
    }

    override fun toggle() {
        try {
            val newState = if (isActiveState()) 0 else 1
            Settings.Global.putInt(context.contentResolver, AIRPLANE_MODE_ON, newState)
            Log.i(TAG, "Airplane mode ${if (newState == 1) "on" else "off"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle airplane mode", e)
        }
    }
}
