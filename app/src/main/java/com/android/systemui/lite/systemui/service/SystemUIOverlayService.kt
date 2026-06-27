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
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
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
import com.android.systemui.lite.systemui.ui.CustomStatusBar
import com.android.systemui.lite.systemui.ui.NotificationShade
import com.android.systemui.lite.systemui.viewmodel.SystemUIViewModel

/**
 * SystemUIOverlayService manages the actual system windows for the status bar
 * and notification shade.
 *
 * This service creates two system windows:
 * 1. Status bar window (TYPE_STATUS_BAR) at the top of the screen
 * 2. Navigation bar window (TYPE_NAVIGATION_BAR) at the bottom of the screen
 *
 * As a system app with sharedUserId=android.uid.systemui, we can use these
 * window types directly.
 */
class SystemUIOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "SystemUIOverlayService"
        private const val CHANNEL_ID = "systemui_service_channel"
        private const val NOTIFICATION_ID = 9110

        var isRunning = false
            private set

        fun startService(context: Context) {
            Log.d(TAG, "startService called")
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
    private var navBarView: ComposeView? = null
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
        Log.d(TAG, "=== SystemUIOverlayService onCreate ===")
        isRunning = true

        // Complete the ViewTree Lifecycle and SavedState bindings
        lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Create notification channel FIRST (required for Android O+)
        createNotificationChannel()

        // Start as foreground service to prevent system kill during boot
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground service started")

        // Create the status bar window
        initStatusBarWindow()

        // Create the navigation bar window
        initNavBarWindow()

        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        Log.d(TAG, "=== SystemUIOverlayService fully started ===")
        Log.d(TAG, "Status bar view: ${statusBarView != null}")
        Log.d(TAG, "Nav bar view: ${navBarView != null}")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SystemUI Core",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SystemUI process persistent notification"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SystemUI")
            .setContentText("SystemUI is running")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    /**
     * Create the status bar system window at the top of the screen.
     */
    private fun initStatusBarWindow() {
        Log.d(TAG, "initStatusBarWindow called")
        val statusBarHeight = getStatusBarHeightPx()
        Log.d(TAG, "Status bar height: ${statusBarHeight}px")

        // Try TYPE_STATUS_BAR first (requires INTERNAL_SYSTEM_WINDOW permission)
        val params = createStatusBarLayoutParams(statusBarHeight)

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

        addWindowWithFallback(statusBarView!!, params, "StatusBar")
    }

    /**
     * Create the navigation bar system window at the bottom of the screen.
     */
    private fun initNavBarWindow() {
        Log.d(TAG, "initNavBarWindow called")
        val navBarHeight = getNavigationBarHeightPx()
        Log.d(TAG, "Nav bar height: ${navBarHeight}px")

        if (navBarHeight == 0) {
            Log.d(TAG, "Nav bar height is 0, skipping (gesture navigation?)")
            return
        }

        val params = createNavBarLayoutParams(navBarHeight)

        navBarView = ComposeView(this).apply {
            setupViewTreeOwners()
            setContent {
                MaterialTheme {
                    val themeColor by viewModel.themeColor.collectAsState()
                    val navigationMode by viewModel.navigationMode.collectAsState()

                    com.android.systemui.lite.systemui.ui.NavigationBar(
                        themeColor = themeColor,
                        navigationMode = navigationMode,
                        onBack = { /* TODO: inject back event */ },
                        onHome = { /* TODO: inject home event */ },
                        onRecents = { /* TODO: inject recents event */ }
                    )
                }
            }
        }

        addWindowWithFallback(navBarView!!, params, "NavigationBar")
    }

    /**
     * Try to add a window with TYPE first, fall back to TYPE_APPLICATION_OVERLAY.
     */
    private fun addWindowWithFallback(view: View, primaryParams: WindowManager.LayoutParams, name: String) {
        try {
            windowManager.addView(view, primaryParams)
            Log.d(TAG, "$name window added successfully (${primaryParams.type})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add $name with type ${primaryParams.type}: ${e.message}")
            // Fallback to TYPE_APPLICATION_OVERLAY
            @Suppress("DEPRECATION")
            val fallbackParams = WindowManager.LayoutParams(
                primaryParams.width,
                primaryParams.height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = primaryParams.gravity
            }
            try {
                windowManager.addView(view, fallbackParams)
                Log.d(TAG, "$name window added with TYPE_APPLICATION_OVERLAY (fallback)")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to add $name with fallback: ${e2.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun createStatusBarLayoutParams(heightPx: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            WindowManager.LayoutParams.TYPE_STATUS_BAR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            setTitle("StatusBar")
            packageName = packageName
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    @Suppress("DEPRECATION")
    private fun createNavBarLayoutParams(heightPx: Int): WindowManager.LayoutParams {
        // TYPE_NAVIGATION_BAR = 2019 (hidden API, use integer value)
        val TYPE_NAVIGATION_BAR = 2019
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            TYPE_NAVIGATION_BAR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            setTitle("NavigationBar")
            packageName = packageName
            setFitInsetsTypes(0)
        }
    }

    /**
     * Toggles the notification shade overlay window.
     */
    fun toggleNotificationShade() {
        Log.d(TAG, "toggleNotificationShade called")
        if (shadeView != null) {
            closeNotificationShade()
        } else {
            openNotificationShade()
        }
    }

    private fun openNotificationShade() {
        @Suppress("DEPRECATION")
        val windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

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

    private fun getStatusBarHeightPx(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            val density = resources.displayMetrics.density
            (25 * density).toInt()
        }
    }

    private fun getNavigationBarHeightPx(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.d(TAG, "SystemUIOverlayService destroying...")

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED

        statusBarView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { Log.e(TAG, "Error removing status bar: ${e.message}") }
        }
        navBarView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { Log.e(TAG, "Error removing nav bar: ${e.message}") }
        }
        closeNotificationShade()
    }
}
