package com.android.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log

/**
 * Trigger a clean SystemUI restart via:
 *   adb shell am broadcast -a com.android.systemui.action.RESTART -p com.android.systemui
 *
 * The framework re-spawns the persistent SystemUI process immediately, so this
 * is the safest way to test the boot path repeatedly.
 */
class SysuiRestartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SysuiRestartReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Restart received, killing process")
        Process.killProcess(Process.myPid())
    }
}
