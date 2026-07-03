package com.android.systemui.lite.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.Manifest
import android.util.Log
import androidx.activity.ComponentActivity
import com.android.systemui.lite.data.ScreenRecorderController
import org.koin.core.context.GlobalContext

/**
 * No-UI trampoline that drives the MediaProjection consent dialog and forwards the
 * resulting token intent to the [ScreenRecorderController] singleton. Owned with the
 * controller but carries no UI of its own — it is visible only while the system's
 * "Start recording / cancel" dialog is on screen. On any deny or cancel the tile's
 * [ScreenRecorderController.isRecording] stays inactive (US-009 AC5 / AC8).
 */
class ScreenRecorderActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ScreenRecorderActivity"
        private const val REQ_CONSENT = 0x5C01
    }

    private val controller: ScreenRecorderController by lazy {
        GlobalContext.get().get()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate — requesting runtime permissions then launching MediaProjection consent")
        ensurePermissionsThenConsent()
    }

    /**
     * Asks for RECORD_AUDIO + POST_NOTIFICATIONS (AC7) then immediately launches the
     * consent dialog. We do not block on the permission results because the controller
     * degrades gracefully to video-only when audio is denied — launching promptly keeps
     * the recorded-with-mic and recorded-without-mic paths identical for the user.
     */
    private fun ensurePermissionsThenConsent() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isNotEmpty()) {
            runCatching { requestPermissions(needed.toTypedArray(), REQ_CONSENT) }
                .onFailure { Log.w(TAG, "requestPermissions call failed: ${it.message}") }
        }
        launchConsent()
    }

    private fun launchConsent() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpm == null) {
            Log.e(TAG, "MediaProjectionManager unavailable — cancelling")
            controller.onConsentResult(Activity.RESULT_CANCELED, null)
            finish()
            return
        }
        val consentIntent = mpm.createScreenCaptureIntent()
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(consentIntent, REQ_CONSENT)
            Log.d(TAG, "Launched MediaProjection consent dialog (requestCode=$REQ_CONSENT)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch consent dialog: ${e.message}", e)
            controller.onConsentResult(Activity.RESULT_CANCELED, null)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CONSENT) return
        Log.d(TAG, "onActivityResult: consent response resultCode=$resultCode")
        controller.onConsentResult(resultCode, data)
        // The controller owns the MediaProjection token from here, not this activity.
        finish()
    }
}
