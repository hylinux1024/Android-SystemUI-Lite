package com.android.systemui.qs.tiles

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.util.Log
import com.android.systemui.R
import com.android.systemui.qs.QSTileBase

class FlashlightTile(context: Context) : QSTileBase(context) {

    companion object {
        private const val TAG = "FlashlightTile"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var isFlashlightOn = false

    override fun getLabel(): String = context.getString(R.string.quick_settings_flashlight)
    override fun getIconResId(): Int = R.drawable.qs_flashlight_icon_off
    override fun getActiveIconResId(): Int = R.drawable.qs_flashlight_icon_on

    override fun isActiveState(): Boolean = isFlashlightOn

    override fun toggle() {
        try {
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                val cameraId = cameraManager.cameraIdList.firstOrNull()
                if (cameraId != null) {
                    isFlashlightOn = !isFlashlightOn
                    cameraManager.setTorchMode(cameraId, isFlashlightOn)
                    Log.i(TAG, "Flashlight ${if (isFlashlightOn) "on" else "off"}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight", e)
            isFlashlightOn = false
        }
    }
}
