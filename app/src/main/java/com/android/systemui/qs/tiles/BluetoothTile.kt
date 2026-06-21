package com.android.systemui.qs.tiles

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.android.systemui.R
import com.android.systemui.qs.QSTileBase

class BluetoothTile(context: Context) : QSTileBase(context) {

    companion object {
        private const val TAG = "BluetoothTile"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter

    override fun getLabel(): String = context.getString(R.string.quick_settings_bluetooth)
    override fun getIconResId(): Int = R.drawable.qs_bluetooth_icon_off
    override fun getActiveIconResId(): Int = R.drawable.qs_bluetooth_icon_on

    override fun isActiveState(): Boolean {
        return try {
            bluetoothAdapter?.isEnabled == true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Bluetooth state", e)
            false
        }
    }

    override fun toggle() {
        try {
            bluetoothAdapter?.let { adapter ->
                if (adapter.isEnabled) {
                    adapter.disable()
                    Log.i(TAG, "Bluetooth disabled")
                } else {
                    adapter.enable()
                    Log.i(TAG, "Bluetooth enabled")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle Bluetooth", e)
        }
    }
}
