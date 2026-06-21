package com.android.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log

class SysuiRestartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SysuiRestartReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Restart received, killing process")
        Process.killProcess(Process.myPid())
    }
}
