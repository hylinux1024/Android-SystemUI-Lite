package com.android.systemui.qs.tiles

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.android.systemui.R
import com.android.systemui.qs.QSTileBase

class DndTile(context: Context) : QSTileBase(context) {

    companion object {
        private const val TAG = "DndTile"
        private const val ZEN_MODE = "zen_mode"
        private const val ZEN_MODE_OFF = 0
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun getLabel(): String = context.getString(R.string.quick_settings_dnd)
    override fun getIconResId(): Int = R.drawable.qs_dnd_icon_off
    override fun getActiveIconResId(): Int = R.drawable.qs_dnd_icon_on

    override fun isActiveState(): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, ZEN_MODE, ZEN_MODE_OFF) != ZEN_MODE_OFF
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get DND state", e)
            false
        }
    }

    override fun toggle() {
        try {
            val newMode = if (isActiveState()) ZEN_MODE_OFF else NotificationManager.INTERRUPTION_FILTER_ALL
            Settings.Global.putInt(context.contentResolver, ZEN_MODE, newMode)
            Log.i(TAG, "DND ${if (newMode != ZEN_MODE_OFF) "on" else "off"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle DND", e)
        }
    }
}
