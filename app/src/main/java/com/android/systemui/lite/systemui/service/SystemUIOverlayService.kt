package com.android.systemui.lite.systemui.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.systemui.lite.SystemUIApplication
import com.android.systemui.lite.systemui.ui.CustomStatusBar
import com.android.systemui.lite.systemui.ui.NotificationShade
import com.android.systemui.lite.systemui.viewmodel.SystemUIViewModel

/**
 * SystemUIOverlayService manages the actual system windows for the status bar
 * and notification shade.
 *
 * In Phase 1, we transition from TYPE_APPLICATION_OVERLAY to TYPE_STATUS_BAR
 * which is the real system window type used by AOSP SystemUI.
 *
 * As a system app with sharedUserId=android.uid.systemui, we can use
 * TYPE_STATUS_BAR directly without SYSTEM_ALERT_WINDOW permission.
 */
class SystemUIOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "SystemUIOverlayService"
        private const val CHANNEL_ID = "systemui_service_channel"
        private const val NOTIFICATION_ID = 9110

        var isRunning = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, SystemUIOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SystemUIOverlayService::class.java)
            context.stopService(intent)
        }
    }

    // WindowManager for creating system windows
    private lateinit var windowManager: WindowManager
    private var statusBarView: ComposeView? = null
    private var shadeView: ComposeView? = null

    private val viewModel by lazy { SystemUIViewModel.instance }

    // --- Lifecycle & Jetpack Compose ViewTree Requirements ---
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d(TAG, "SystemUIOverlayService onCreate")

        // Complete the ViewTree Lifecycle and SavedState bindings
        lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Setup persistent status bar system window
        initStatusBarWindow()

        // Start as foreground service to prevent system kill during boot
        startForeground(NOTIFICATION_ID, createNotification())

        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        Log.d(TAG, "SystemUIOverlayService started with status bar window")
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SystemUI Core",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SystemUI process persistent notification"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SystemUI")
            .setContentText("SystemUI is running")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    /**
     * Initializes the status bar system window.
     *
     * Uses TYPE_STATUS_BAR which is the real system window type.
     * As a system app with android.uid.systemui, we have permission to create this.
     *
     * In later phases, this will be moved to StatusBarWindowController.
     */
    private fun initStatusBarWindow() {
        // Use TYPE_STATUS_BAR - the real system window type for status bar
        // Only available to system apps with INTERNAL_SYSTEM_WINDOW permission
        @Suppress("DEPRECATION")
        val windowType = WindowManager.LayoutParams.TYPE_STATUS_BAR

        val statusBarHeight = getStatusBarHeightPx()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            statusBarHeight,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            setTitle("StatusBar")
            packageName = packageName
            // Don't let insets adjust position
            setFitInsetsTypes(0)
        }

        statusBarView = ComposeView(this).apply {
            setupViewTreeOwners()
            setContent {
                MaterialTheme {
                    val batteryLevel by viewModel.batteryLevel.collectAsState()
                    val isCharging by viewModel.isCharging.collectAsState()
                    val isWifiOn by viewModel.isWifiOn.collectAsState()
                    val isBluetoothOn by viewModel.isBluetoothOn.collectAsState()
                    val isDoNotDisturb by viewModel.isDoNotDisturb.collectAsState()
                    val isAirplaneMode by viewModel.isAirplaneMode.collectAsState()
                    val timeString by viewModel.timeString.collectAsState()
                    val themeColor by viewModel.themeColor.collectAsState()

                    val heightDp by viewModel.statusBarHeight.collectAsState()
                    val iconSize by viewModel.statusBarIconSize.collectAsState()
                    val clockPosition by viewModel.clockPosition.collectAsState()
                    val batteryStyle by viewModel.batteryStyle.collectAsState()

                    val plugins by viewModel.plugins.collectAsState()
                    val isTrafficActive = plugins.find { it.id == "traffic_indicator" }?.isEnabled == true

                    CustomStatusBar(
                        heightDp = heightDp,
                        iconSizeDp = iconSize,
                        clockPosition = clockPosition,
                        batteryStyle = batteryStyle,
                        batteryLevel = batteryLevel,
                        isCharging = isCharging,
                        isWifiOn = isWifiOn,
                        isBluetoothOn = isBluetoothOn,
                        isDoNotDisturb = isDoNotDisturb,
                        isAirplaneMode = isAirplaneMode,
                        timeString = timeString,
                        isTrafficActive = isTrafficActive,
                        themeColor = themeColor,
                        onShadeToggle = {
                            toggleNotificationShade()
                        }
                    )
                }
            }
        }

        try {
            windowManager.addView(statusBarView, params)
            Log.d(TAG, "Status bar TYPE_STATUS_BAR window added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add status bar window: ${e.message}", e)
            // Fallback: try TYPE_APPLICATION_OVERLAY if TYPE_STATUS_BAR fails
            // This can happen if the app is not properly signed or not in priv-app
            initStatusBarOverlayFallback()
        }
    }

    /**
     * Fallback: use TYPE_APPLICATION_OVERLAY if TYPE_STATUS_BAR fails.
     * This allows testing on non-rooted devices (with overlay permission).
     */
    @Suppress("DEPRECATION")
    private fun initStatusBarOverlayFallback() {
        Log.w(TAG, "Falling back to TYPE_APPLICATION_OVERLAY for status bar")

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            getStatusBarHeightPx(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
        }

        statusBarView = ComposeView(this).apply {
            setupViewTreeOwners()
            setContent {
                MaterialTheme {
                    val batteryLevel by viewModel.batteryLevel.collectAsState()
                    val isCharging by viewModel.isCharging.collectAsState()
                    val isWifiOn by viewModel.isWifiOn.collectAsState()
                    val isBluetoothOn by viewModel.isBluetoothOn.collectAsState()
                    val isDoNotDisturb by viewModel.isDoNotDisturb.collectAsState()
                    val isAirplaneMode by viewModel.isAirplaneMode.collectAsState()
                    val timeString by viewModel.timeString.collectAsState()
                    val themeColor by viewModel.themeColor.collectAsState()

                    val heightDp by viewModel.statusBarHeight.collectAsState()
                    val iconSize by viewModel.statusBarIconSize.collectAsState()
                    val clockPosition by viewModel.clockPosition.collectAsState()
                    val batteryStyle by viewModel.batteryStyle.collectAsState()

                    val plugins by viewModel.plugins.collectAsState()
                    val isTrafficActive = plugins.find { it.id == "traffic_indicator" }?.isEnabled == true

                    CustomStatusBar(
                        heightDp = heightDp,
                        iconSizeDp = iconSize,
                        clockPosition = clockPosition,
                        batteryStyle = batteryStyle,
                        batteryLevel = batteryLevel,
                        isCharging = isCharging,
                        isWifiOn = isWifiOn,
                        isBluetoothOn = isBluetoothOn,
                        isDoNotDisturb = isDoNotDisturb,
                        isAirplaneMode = isAirplaneMode,
                        timeString = timeString,
                        isTrafficActive = isTrafficActive,
                        themeColor = themeColor,
                        onShadeToggle = {
                            toggleNotificationShade()
                        }
                    )
                }
            }
        }

        try {
            windowManager.addView(statusBarView, params)
            Log.d(TAG, "Status bar overlay (fallback) added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add status bar overlay: ${e.message}", e)
        }
    }

    /**
     * Toggles the notification shade overlay window.
     */
    fun toggleNotificationShade() {
        if (shadeView != null) {
            closeNotificationShade()
        } else {
            openNotificationShade()
        }
    }

    private fun openNotificationShade() {
        @Suppress("DEPRECATION")
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.FILL
        }

        shadeView = ComposeView(this).apply {
            setupViewTreeOwners()
            setContent {
                MaterialTheme {
                    val themeColor by viewModel.themeColor.collectAsState()
                    val isWifiOn by viewModel.isWifiOn.collectAsState()
                    val isBluetoothOn by viewModel.isBluetoothOn.collectAsState()
                    val isDoNotDisturb by viewModel.isDoNotDisturb.collectAsState()
                    val isFlashlightOn by viewModel.isFlashlightOn.collectAsState()
                    val isAirplaneMode by viewModel.isAirplaneMode.collectAsState()
                    val isAutoRotateOn by viewModel.isAutoRotateOn.collectAsState()
                    val isScreenRecording by viewModel.isScreenRecording.collectAsState()
                    val brightness by viewModel.brightness.collectAsState()
                    val mediaVolume by viewModel.mediaVolume.collectAsState()
                    val notifications by viewModel.notifications.collectAsState()

                    val plugins by viewModel.plugins.collectAsState()
                    val isResourceMonitorActive = plugins.find { it.id == "resource_monitor" }?.isEnabled == true

                    Box(modifier = Modifier.fillMaxSize()) {
                        NotificationShade(
                            viewModel = viewModel,
                            themeColor = themeColor,
                            isWifiOn = isWifiOn,
                            isBluetoothOn = isBluetoothOn,
                            isDoNotDisturb = isDoNotDisturb,
                            isFlashlightOn = isFlashlightOn,
                            isAirplaneMode = isAirplaneMode,
                            isAutoRotateOn = isAutoRotateOn,
                            isScreenRecording = isScreenRecording,
                            brightness = brightness,
                            mediaVolume = mediaVolume,
                            notifications = notifications,
                            isResourceMonitorActive = isResourceMonitorActive,
                            onDismissNotification = { viewModel.dismissNotification(it) },
                            onClearAllNotifications = { viewModel.clearAllNotifications() }
                        )
                    }
                }
            }
        }

        try {
            windowManager.addView(shadeView, params)
            Log.d(TAG, "Notification shade overlay added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open notification shade: ${e.message}", e)
        }
    }

    fun closeNotificationShade() {
        shadeView?.let { view ->
            try {
                windowManager.removeView(view)
                shadeView = null
                Log.d(TAG, "Notification shade overlay removed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove shade: ${e.message}", e)
            }
        }
    }

    private fun ComposeView.setupViewTreeOwners() {
        setViewTreeLifecycleOwner(this@SystemUIOverlayService)
        setViewTreeViewModelStoreOwner(this@SystemUIOverlayService)
        setViewTreeSavedStateRegistryOwner(this@SystemUIOverlayService)
    }

    /**
     * Get the real system status bar height from resources.
     * Falls back to 28dp if the resource is not available.
     */
    private fun getStatusBarHeightPx(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            // Fallback: 28dp
            val density = resources.displayMetrics.density
            (28 * density).toInt()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.d(TAG, "SystemUIOverlayService destroying...")

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED

        statusBarView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing status bar: ${e.message}")
            }
        }
        closeNotificationShade()
    }
}
