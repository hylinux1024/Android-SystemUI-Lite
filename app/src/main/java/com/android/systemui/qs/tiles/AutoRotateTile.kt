package com.android.systemui.qs.tiles

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.android.systemui.R
import com.android.systemui.qs.QSTileBase

class AutoRotateTile(context: Context) : QSTileBase(context) {

    companion object {
        private const val TAG = "AutoRotateTile"
        private const val ACCELEROMETER_ROTATION = "accelerometer_rotation"
    }

    override fun getLabel(): String = context.getString(R.string.quick_settings_auto_rotate)
    override fun getIconResId(): Int = R.drawable.qs_auto_rotate_icon_off
    override fun getActiveIconResId(): Int = R.drawable.qs_auto_rotate_icon_on

    override fun isActiveState(): Boolean {
        return try {
            Settings.System.getInt(context.contentResolver, ACCELEROMETER_ROTATION, 0) == 1
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get auto-rotate state", e)
            false
        }
    }

    override fun toggle() {
        try {
            val newState = if (isActiveState()) 0 else 1
            Settings.System.putInt(context.contentResolver, ACCELEROMETER_ROTATION, newState)
            Log.i(TAG, "Auto-rotate ${if (newState == 1) "on" else "off"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle auto-rotate", e)
        }
    }
}
