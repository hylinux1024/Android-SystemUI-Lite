package com.android.systemui.lite.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that holds an ongoing screen-recording session.
 *
 * AOSP's implementation of screen recording (MediaProjectionPermissionActivity + MediaProjectionSysRecordCallback)
 * runs inside a named foreground service because API 34+ requires a foreground service with type
 * `mediaProjection` to host any MediaProjection instance — but the flag is requested from android 33+.
 *
 * The screen recording pipeline already lives in [ScreenRecorderController]; this service answers
 * the platform's "why is this app holding a MediaProjection?" requirement with a single persistent
 * Recording... notification, and is started/stopped directly from the controller.
 */
class ScreenRecorderService : Service() {

    companion object {
        const val TAG = "ScreenRecorderService"
        const val CHANNEL_ID = "screen_recording"
        const val NOTIFICATION_ID = 0xC001

        fun start(context: Context) {
            val intent = Intent(context, ScreenRecorderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ScreenRecorderService::class.java))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
        Log.d(TAG, "Foreground service started (mediaProjection)")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Foreground service stopped")
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing screen-recording session"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording screen")
            .setContentText("Tap the Screen Rec tile to stop.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
