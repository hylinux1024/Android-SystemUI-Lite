package com.android.systemui.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.android.systemui.CoreStartable
import com.android.systemui.R
import com.android.systemui.notification.NotificationShadeManager
import com.android.systemui.statusbar.BatteryController.BatteryStateListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatusBarManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val windowManager: WindowManager,
    private val batteryController: BatteryController,
    private val notificationShadeManager: NotificationShadeManager
) : CoreStartable, BatteryStateListener {

    companion object {
        private const val TAG = "StatusBarManager"
        private const val CLOCK_UPDATE_INTERVAL_MS = 1000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var statusBarView: View? = null
    private var clockTextView: TextView? = null
    private var batteryLevelTextView: TextView? = null
    private var batteryIcon: ImageView? = null
    private var wifiIcon: ImageView? = null
    private var signalIcon: ImageView? = null
    private var systemIconsLayout: LinearLayout? = null
    private var cutoutSpace: Space? = null
    private val clockFormat = SimpleDateFormat("h:mm", Locale.getDefault())

    private var touchStartY = 0f
    private var touchStartX = 0f
    private var isPullDownTracking = false
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop

    private val clockUpdateRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, CLOCK_UPDATE_INTERVAL_MS)
        }
    }

    private val phoneStateListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            val level = signalStrength.level
            handler.post { updateSignalIcon(level) }
        }

        override fun onServiceStateChanged(serviceState: android.telephony.ServiceState) {
            handler.post { updateSignalIcon() }
        }
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.post { updateWifiIcon() }
        }
    }

    override fun start() {
        Log.i(TAG, "Starting status bar")
        createStatusBarWindow()
        handler.post(clockUpdateRunnable)
        registerReceivers()
        batteryController.addListener(this)
    }

    override fun stop() {
        Log.i(TAG, "Stopping status bar")
        handler.removeCallbacks(clockUpdateRunnable)
        unregisterReceivers()
        batteryController.removeListener(this)
        removeStatusBarWindow()
    }

    private fun registerReceivers() {
        val wifiFilter = IntentFilter()
        wifiFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        wifiFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        wifiFilter.addAction(WifiManager.RSSI_CHANGED_ACTION)
        context.registerReceiver(wifiReceiver, wifiFilter)

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        telephonyManager?.listen(phoneStateListener,
            PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_SERVICE_STATE)

        updateWifiIcon()
        updateSignalIcon()
        Log.i(TAG, "Receivers registered")
    }

    private fun unregisterReceivers() {
        try { context.unregisterReceiver(wifiReceiver) } catch (_: Exception) {}
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
    }

    private fun createStatusBarWindow() {
        val inflater = LayoutInflater.from(context)
        statusBarView = inflater.inflate(R.layout.status_bar, null)

        clockTextView = statusBarView?.findViewById(R.id.clock)
        batteryLevelTextView = statusBarView?.findViewById(R.id.battery_level)
        batteryIcon = statusBarView?.findViewById(R.id.battery_icon)
        wifiIcon = statusBarView?.findViewById(R.id.wifi_icon)
        signalIcon = statusBarView?.findViewById(R.id.signal_icon)
        systemIconsLayout = statusBarView?.findViewById(R.id.system_icons)
        cutoutSpace = statusBarView?.findViewById(R.id.cutout_space_view)

        statusBarView?.setOnApplyWindowInsetsListener { v, insets ->
            handleWindowInsets(v, insets)
            insets
        }

        statusBarView?.setOnTouchListener { _, event ->
            handleStatusBarTouch(event)
        }

        val statusBarHeight = getStatusBarHeight()
        Log.i(TAG, "Status bar height: ${statusBarHeight}px")

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            statusBarHeight,
            WindowManager.LayoutParams.TYPE_STATUS_BAR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                    or WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                    or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            setTitle("StatusBar")
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        }

        try {
            windowManager.addView(statusBarView, layoutParams)
            Log.i(TAG, "Status bar window added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add status bar window", e)
        }
    }

    private fun handleStatusBarTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (notificationShadeManager.isShowing()) {
                    // Shade already open, forward to shade's touch handler
                    notificationShadeManager.handleExternalTouch(event.rawX, event.rawY, event.actionMasked)
                    return true
                }
                touchStartY = event.rawY
                touchStartX = event.rawX
                isPullDownTracking = true
                // Forward DOWN to shade's touch handler (creates shade)
                notificationShadeManager.handleExternalTouch(event.rawX, event.rawY, event.actionMasked)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isPullDownTracking) return false
                val deltaY = event.rawY - touchStartY
                val deltaX = Math.abs(event.rawX - touchStartX)
                if (deltaY > touchSlop && deltaY > deltaX) {
                    // Forward to shade's touch handler (starts tracking)
                    notificationShadeManager.handleExternalTouch(event.rawX, event.rawY, event.actionMasked)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isPullDownTracking) return false
                isPullDownTracking = false
                // Forward UP to shade's touch handler (ends tracking, flings)
                notificationShadeManager.handleExternalTouch(event.rawX, event.rawY, event.actionMasked)
                return true
            }
        }
        return false
    }

    private fun handleWindowInsets(view: View, insets: WindowInsets) {
        val displayCutout = insets.displayCutout ?: return

        val cutoutRect = displayCutout.boundingRectTop
        if (cutoutRect.isEmpty) {
            cutoutSpace?.visibility = View.GONE
            val defaultPaddingStart = context.resources.getDimensionPixelSize(R.dimen.status_bar_padding_start)
            val defaultPaddingEnd = context.resources.getDimensionPixelSize(R.dimen.status_bar_padding_end)
            view.setPadding(defaultPaddingStart, view.paddingTop, defaultPaddingEnd, view.paddingBottom)
            return
        }

        val display = context.display
        val point = android.graphics.Point()
        display?.getRealSize(point)
        val screenWidth = point.x

        val isCornerCutout = cutoutRect.left <= 0 || cutoutRect.right >= screenWidth

        if (isCornerCutout) {
            cutoutSpace?.visibility = View.GONE
            val defaultPaddingStart = context.resources.getDimensionPixelSize(R.dimen.status_bar_padding_start)
            val defaultPaddingEnd = context.resources.getDimensionPixelSize(R.dimen.status_bar_padding_end)
            val cutoutWidth = cutoutRect.width()
            val safeInsetLeft = displayCutout.safeInsetLeft
            val safeInsetRight = displayCutout.safeInsetRight
            val finalPaddingLeft = maxOf(cutoutWidth, safeInsetLeft, defaultPaddingStart)
            val finalPaddingRight = maxOf(safeInsetRight, defaultPaddingEnd)
            view.setPadding(finalPaddingLeft, view.paddingTop, finalPaddingRight, view.paddingBottom)
        } else {
            cutoutSpace?.visibility = View.VISIBLE
            val cutoutWidth = cutoutRect.width()
            val cutoutSpaceParams = cutoutSpace?.layoutParams as? LinearLayout.LayoutParams
            cutoutSpaceParams?.width = cutoutWidth
            cutoutSpace?.layoutParams = cutoutSpaceParams
            val safeInsetLeft = displayCutout.safeInsetLeft
            val safeInsetRight = displayCutout.safeInsetRight
            view.setPadding(safeInsetLeft, view.paddingTop, safeInsetRight, view.paddingBottom)
        }
    }

    private fun removeStatusBarWindow() {
        statusBarView?.let {
            try {
                windowManager.removeView(it)
                statusBarView = null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove status bar window", e)
            }
        }
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = context.resources.getIdentifier(
            "status_bar_height", "dimen", "android"
        )
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            24 * context.resources.displayMetrics.density.toInt()
        }
    }

    override fun onBatteryLevelChanged(level: Int, isCharging: Boolean) {
        handler.post { updateBatteryUI(level, isCharging) }
    }

    private fun updateClock() {
        clockTextView?.text = clockFormat.format(Date())
    }

    private fun updateBatteryUI(level: Int, isCharging: Boolean) {
        batteryLevelTextView?.text = "$level%"
        val resId = if (isCharging) {
            context.resources.getIdentifier("stat_sys_battery_charge", "drawable", "android")
        } else {
            context.resources.getIdentifier("stat_sys_battery", "drawable", "android")
        }
        if (resId != 0) {
            batteryIcon?.setImageResource(resId)
        } else {
            batteryIcon?.setImageResource(R.drawable.stat_sys_battery_our)
        }
    }

    private fun updateWifiIcon() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: return
            val isWifiEnabled = wifiManager.isWifiEnabled
            val connectionInfo = wifiManager.connectionInfo
            val isConnected = connectionInfo != null && connectionInfo.networkId != -1

            val resId: Int
            if (isWifiEnabled && isConnected) {
                val rssi = connectionInfo.rssi
                val maxLevel = wifiManager.maxSignalLevel
                val level = if (maxLevel > 0) {
                    (wifiManager.calculateSignalLevel(rssi) * 4) / maxLevel
                } else {
                    0
                }
                val clampedLevel = level.coerceIn(0, 4)
                resId = getWifiDrawableId(clampedLevel)
            } else if (isWifiEnabled) {
                resId = context.resources.getIdentifier("stat_sys_wifi_signal_0", "drawable", "android")
            } else {
                wifiIcon?.visibility = View.GONE
                return
            }

            wifiIcon?.visibility = View.VISIBLE
            if (resId != 0) {
                wifiIcon?.setImageResource(resId)
            } else {
                wifiIcon?.setImageResource(R.drawable.stat_sys_wifi_our)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update WiFi icon", e)
            wifiIcon?.setImageResource(R.drawable.stat_sys_wifi_our)
        }
    }

    private fun getWifiDrawableId(level: Int): Int {
        val drawableNames = arrayOf(
            "stat_sys_wifi_signal_0",
            "stat_sys_wifi_signal_1",
            "stat_sys_wifi_signal_2",
            "stat_sys_wifi_signal_3",
            "stat_sys_wifi_signal_4"
        )
        return context.resources.getIdentifier(drawableNames[level], "drawable", "android")
    }

    private fun updateSignalIcon(level: Int? = null) {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    ?: return

            val serviceState = if (android.os.Build.VERSION.SDK_INT >= 31) {
                telephonyManager.serviceState
            } else {
                @Suppress("DEPRECATION")
                null
            }

            val inService = serviceState == null || serviceState.state == android.telephony.ServiceState.STATE_IN_SERVICE
            val hasSim = telephonyManager.simState == TelephonyManager.SIM_STATE_READY
            val isAirplaneMode = android.provider.Settings.Global.getInt(
                context.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0
            ) != 0

            val signalLevel: Int
            val drawableName: String

            if (isAirplaneMode || !hasSim || !inService) {
                drawableName = "stat_sys_signal_null"
                signalLevel = -1
            } else {
                val actualLevel = level ?: telephonyManager.signalStrength?.level ?: 0
                signalLevel = actualLevel.coerceIn(0, 4)
                drawableName = "stat_sys_signal_$signalLevel"
            }

            val resId = context.resources.getIdentifier(drawableName, "drawable", "android")

            signalIcon?.visibility = View.VISIBLE
            if (resId != 0) {
                signalIcon?.setImageResource(resId)
            } else {
                signalIcon?.setImageResource(R.drawable.stat_sys_signal_our)
            }

            Log.d(TAG, "Signal updated: level=$signalLevel, drawable=$drawableName, resId=$resId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update signal icon", e)
            signalIcon?.setImageResource(R.drawable.stat_sys_signal_our)
        }
    }
}
