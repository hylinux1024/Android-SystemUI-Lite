package com.android.systemui.lite.systemui.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntOffset
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
import com.android.systemui.lite.systemui.ui.StatusBar
import com.android.systemui.lite.systemui.ui.NotificationShade
import com.android.systemui.lite.systemui.viewmodel.SystemUIViewModel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SystemUIOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "SystemUIOverlayService"
        private const val CHANNEL_ID = "systemui_service_channel"
        private const val NOTIFICATION_ID = 9110
        private const val DRAG_MULTIPLIER = 1f

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

    private lateinit var windowManager: WindowManager
    private var statusBarView: ComposeView? = null
    private var navBarView: ComposeView? = null
    private var shadeView: ComposeView? = null

    private val viewModel by lazy { SystemUIViewModel.instance }

    // Shared shade progress (0f = closed, 1f = fully open) — observed by shade window
    private val _shadeProgress = MutableStateFlow(0f)
    val shadeProgress: StateFlow<Float> = _shadeProgress

    // Whether the shade window is currently added to WindowManager
    private var isShadeWindowAdded = false
    private var shadeAnimJob: Job? = null

    data class CutoutInfo(
        val safeInsetLeft: Int = 0,
        val safeInsetRight: Int = 0,
        val cutoutRect: Rect = Rect()
    )
    private val _cutoutInfo = MutableStateFlow(CutoutInfo())
    private val cutoutInfo: StateFlow<CutoutInfo> = _cutoutInfo

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

        lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground service started")

        initStatusBarWindow()

        initNavBarWindow()

        ensureShadeWindow()

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

    // ---- Status bar window (small, TYPE_STATUS_BAR) ----

    private fun initStatusBarWindow() {
        Log.d(TAG, "initStatusBarWindow called")
        val statusBarHeightPx = getStatusBarHeightPx()
        Log.d(TAG, "Status bar height: ${statusBarHeightPx}px")

        val params = createStatusBarLayoutParams(statusBarHeightPx)

        val screenHeightPx = resources.displayMetrics.heightPixels
        val maxShadeOffsetPx = screenHeightPx.toFloat()

        statusBarView = ComposeView(this).apply {
            setupViewTreeOwners()

            setOnApplyWindowInsetsListener { view, windowInsets ->
                val displayCutout = windowInsets.displayCutout
                if (displayCutout != null) {
                    val cutoutRect = displayCutout.boundingRectTop
                    val screenWidth = resources.displayMetrics.widthPixels
                    val isCornerCutout = cutoutRect.left <= 0 || cutoutRect.right >= screenWidth
                    val safeLeft: Int
                    val safeRight: Int
                    if (isCornerCutout) {
                        val cutoutWidth = cutoutRect.width()
                        safeLeft = maxOf(cutoutWidth, displayCutout.safeInsetLeft)
                        safeRight = maxOf(displayCutout.safeInsetRight, 0)
                    } else {
                        safeLeft = displayCutout.safeInsetLeft
                        safeRight = displayCutout.safeInsetRight
                    }
                    _cutoutInfo.value = CutoutInfo(
                        safeInsetLeft = safeLeft,
                        safeInsetRight = safeRight,
                        cutoutRect = cutoutRect
                    )
                } else {
                    _cutoutInfo.value = CutoutInfo()
                }
                view.onApplyWindowInsets(windowInsets)
            }

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
                    val currentCutout by cutoutInfo.collectAsState()

                    StatusBar(
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
                        safeInsetLeft = currentCutout.safeInsetLeft,
                        safeInsetRight = currentCutout.safeInsetRight,
                        onShadeToggle = { toggleNotificationShade() },
                        onShadeDragUpdate = { offset ->
                            ensureShadeWindow()
                            _shadeProgress.value =
                                (offset * DRAG_MULTIPLIER / maxShadeOffsetPx).coerceIn(0f, 1f)
                        },
                        onShadeDragEnd = { offset, isFling ->
                            if (kotlin.math.abs(offset) < 8f) return@StatusBar
                            val shouldOpen = if (isFling) offset > 120f
                            else offset > maxShadeOffsetPx / 3f
                            setShadeTarget(if (shouldOpen) 1f else 0f, animated = true)
                        }
                    )
                }
            }
        }

        addWindowWithFallback(statusBarView!!, params, "StatusBar")
    }

    // ---- Shade window (full-screen, TYPE_STATUS_BAR_PANEL) ----
    private fun ensureShadeWindow() {
        if (isShadeWindowAdded) return

        @Suppress("DEPRECATION")
        val windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val statusBarHeightPx = getStatusBarHeightPx()
        val screenHeightPx = resources.displayMetrics.heightPixels
        val maxShadeOffsetPx = screenHeightPx.toFloat()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.FILL
            setTitle("NotificationShade")
            packageName = this@SystemUIOverlayService.packageName
            setFitInsetsTypes(0)
        }

        shadeView = ComposeView(this).apply {
            setupViewTreeOwners()
            setContent {
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
                val progress by _shadeProgress.collectAsState()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset {
                            IntOffset(
                                0,
                                (-(maxShadeOffsetPx * (1f - progress))).toInt()
                            )
                        }
                ) {
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
                        onClearAllNotifications = { viewModel.clearAllNotifications() },
                        onCloseShade = { setShadeTarget(0f, animated = true) }
                    )
                }
            }
        }

        try {
            windowManager.addView(shadeView, params)
            isShadeWindowAdded = true
            Log.d(TAG, "Notification shade window added (TYPE_APPLICATION_OVERLAY)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add shade window: ${e.message}", e)
        }
    }

    private fun closeNotificationShade() {
        shadeView?.let { view ->
            try {
                windowManager.removeView(view)
                Log.d(TAG, "Notification shade window removed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove shade: ${e.message}", e)
            }
        }
        shadeView = null
        isShadeWindowAdded = false
    }

    fun toggleNotificationShade() {
        Log.d(TAG, "toggleNotificationShade")
        if (isShadeWindowAdded) {
            setShadeTarget(0f, animated = true)
        } else {
            ensureShadeWindow()
            setShadeTarget(1f, animated = true)
        }
    }

    private fun setShadeTarget(target: Float, animated: Boolean) {
        shadeAnimJob?.cancel()
        val clampedTarget = target.coerceIn(0f, 1f)
        if (!animated) {
            _shadeProgress.value = clampedTarget
            return
        }
        val from = _shadeProgress.value
        val to = clampedTarget
        shadeAnimJob = GlobalScope.launch {
            val startTime = System.nanoTime()
            val duration = 400_000_000L
            while (isActive) {
                val elapsed = System.nanoTime() - startTime
                if (elapsed >= duration) {
                    _shadeProgress.value = to
                    break
                }
                val fraction = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                val eased = 1f - (1f - fraction) * (1f - fraction) * (1f - fraction)
                _shadeProgress.value = from + (to - from) * eased
                delay(16)
            }
            if (_shadeProgress.value <= 0f) {
                closeNotificationShade()
            }
        }
    }

    // ---- Navigation bar window ----

    private fun initNavBarWindow() {
        Log.d(TAG, "initNavBarWindow called")
        val navBarHeight = getNavigationBarHeightPx()
        Log.d(TAG, "Nav bar height: ${navBarHeight}px")

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
                        onBack = { sendKeyEvent(4) },
                        onHome = { sendKeyEvent(3) },
                        onRecents = { sendKeyEvent(187) }
                    )
                }
            }
        }

        addWindowWithFallback(navBarView!!, params, "NavigationBar")
    }

    // ---- Helpers ----

    private fun addWindowWithFallback(view: View, primaryParams: WindowManager.LayoutParams, name: String) {
        try {
            windowManager.addView(view, primaryParams)
            Log.d(TAG, "$name window added successfully (${primaryParams.type})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add $name with type ${primaryParams.type}: ${e.message}")
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
        val height = if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
        Log.d(TAG, "NavigationBar height from resources: ${height}px")
        return if (height > 0) height else (48 * resources.displayMetrics.density).toInt()
    }

    private fun sendKeyEvent(keyCode: Int) {
        Log.d(TAG, "Sending key event: $keyCode")
        try {
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send key event $keyCode: ${e.message}")
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
