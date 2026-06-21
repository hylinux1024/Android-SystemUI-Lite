package com.android.systemui.qs.tiles

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.android.systemui.R
import com.android.systemui.qs.QSTileBase

class LocationTile(context: Context) : QSTileBase(context) {

    companion object {
        private const val TAG = "LocationTile"
        private const val LOCATION_MODE = "location_mode"
        private const val LOCATION_MODE_OFF = 0
        private const val LOCATION_MODE_HIGH_ACCURACY = 3
    }

    override fun getLabel(): String = context.getString(R.string.quick_settings_location)
    override fun getIconResId(): Int = R.drawable.qs_location_icon_off
    override fun getActiveIconResId(): Int = R.drawable.qs_location_icon_on

    override fun isActiveState(): Boolean {
        return try {
            Settings.Secure.getInt(context.contentResolver, LOCATION_MODE, LOCATION_MODE_OFF) != LOCATION_MODE_OFF
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get location state", e)
            false
        }
    }

    override fun toggle() {
        try {
            val newMode = if (isActiveState()) LOCATION_MODE_OFF else LOCATION_MODE_HIGH_ACCURACY
            Settings.Secure.putInt(context.contentResolver, LOCATION_MODE, newMode)
            Log.i(TAG, "Location ${if (newMode != LOCATION_MODE_OFF) "on" else "off"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle location", e)
        }
    }
}
