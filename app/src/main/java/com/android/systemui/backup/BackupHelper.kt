package com.android.systemui.backup

import android.app.backup.BackupAgentHelper
import android.os.ParcelFileDescriptor
import android.util.Log

class BackupHelper : BackupAgentHelper() {

    companion object {
        private const val TAG = "BackupHelper"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BackupHelper created")
    }

    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: android.app.backup.BackupDataOutput?,
        newState: ParcelFileDescriptor?
    ) {
        Log.i(TAG, "onBackup called")
        try {
            super.onBackup(oldState, data, newState)
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "BackupHelper destroyed")
    }
}
