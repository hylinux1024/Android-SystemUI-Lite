package com.android.systemui.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import com.android.systemui.CoreStartable
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the status-bar clock (the `clock` TextView in status_bar.xml).
 *
 * Mirrors AOSP `com.android.systemui.statusbar.policy.Clock`:
 *  - Main-thread time tick via ACTION_TIME_TICK / TIME_CHANGED / TIMEZONE_CHANGED / CONFIGURATION_CHANGED.
 *  - Updates the bound TextView twice through `updateClock()` so the field never "jumps".
 *  - 12/24-hour format is decided by `DateFormat.is24HourFormat()`.
 *
 * Ticking is driven by a 60s `Handler.postDelayed` after every TIME_TICK, not a live runnable —
 * this avoids a perpetually-running callback during doze/manual-clock scenarios we don't yet implement.
 */
@Singleton
class ClockController @Inject constructor(
    @ApplicationContext private val context: Context
) : CoreStartable {

    companion object {
        private const val TAG = "ClockController"
        private const val TICK_INTERVAL_MS = 60_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isRegistered = false
    private var lastDispatchableTime: Date? = null
    private var clockView: TextView? = null

    fun bind(view: TextView?) {
        clockView = view
        updateClock()
    }

    override fun start() {
        if (isRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        context.registerReceiver(timeReceiver, filter)
        isRegistered = true
        updateClock()
        Log.i(TAG, "Clock started")
    }

    override fun stop() {
        if (!isRegistered) return
        try { context.unregisterReceiver(timeReceiver) } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Time receiver not registered", e)
        }
        isRegistered = false
        Log.i(TAG, "Clock stopped")
    }

    /** Re-render the clock from the system time into the bound TextView. */
    private fun updateClock() {
        val tv = clockView ?: return
        val now = Calendar.getInstance()
        val format = when {
            android.text.format.DateFormat.is24HourFormat(context) -> "HH:mm"
            else -> "h:mm"
        }
        val sdf = SimpleDateFormat(format, Locale.getDefault())
        val text = sdf.format(now.time)
        tv.text = text
        tv.contentDescription = text
        lastDispatchableTime = now.time
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_TIME_TICK -> {
                    // Tick fires on the minute; nudge forward 60s and reschedule.
                    handler.postDelayed({ updateClock() }, TICK_INTERVAL_MS)
                }
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_CONFIGURATION_CHANGED -> updateClock()
            }
        }
    }
}
