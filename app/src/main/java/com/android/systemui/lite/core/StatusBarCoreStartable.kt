package com.android.systemui.lite.core

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.systemui.lite.CoreStartable
import com.android.systemui.lite.ui.StatusBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.context.GlobalContext

class StatusBarCoreStartable(
    private val context: Context,
    private val shadeController: ShadeController
) : CoreStartable {

    companion object {
        private const val TAG = "StatusBarCoreStartable"
    }

    private val windowHost = WindowHost()
    private var statusBarView: ComposeView? = null

    private val sp by lazy {
        GlobalContext.get().get<com.android.systemui.lite.data.SystemStateProvider>()
    }
    private val qsm by lazy {
        GlobalContext.get().get<com.android.systemui.lite.qs.QSTileManager>()
    }

    data class CutoutInfo(
        val safeInsetLeft: Int = 0,
        val safeInsetRight: Int = 0,
        val cutoutRect: Rect = Rect()
    )
    private val _cutoutInfo = MutableStateFlow(CutoutInfo())

    override fun start() {
        Log.d(TAG, "Starting StatusBarCoreStartable...")
        windowHost.start()
        sp.start()
        initStatusBarWindow()
        Log.d(TAG, "StatusBarCoreStartable started")
    }

    override fun onBootCompleted() {}

    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun stop() {
        Log.d(TAG, "Stopping StatusBarCoreStartable...")
        statusBarView?.let { view ->
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing status bar window: ${e.message}")
            }
        }
        statusBarView = null
        sp.stop()
        windowHost.destroy()
    }

    private fun initStatusBarWindow() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val statusBarHeightPx = getStatusBarHeightPx()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            statusBarHeightPx,
            WindowManager.LayoutParams.TYPE_STATUS_BAR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            setTitle("StatusBar")
            packageName = context.packageName
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        val screenHeightPx = context.resources.displayMetrics.heightPixels
        val maxShadeOffsetPx = screenHeightPx.toFloat()

        statusBarView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(windowHost)
            setViewTreeViewModelStoreOwner(windowHost)
            setViewTreeSavedStateRegistryOwner(windowHost)

            setOnApplyWindowInsetsListener { view, windowInsets ->
                val displayCutout = windowInsets.displayCutout
                if (displayCutout != null) {
                    val cutoutRect = displayCutout.boundingRectTop
                    val screenWidth = context.resources.displayMetrics.widthPixels
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
                    _cutoutInfo.value = CutoutInfo(safeInsetLeft = safeLeft, safeInsetRight = safeRight, cutoutRect = cutoutRect)
                } else {
                    _cutoutInfo.value = CutoutInfo()
                }
                view.onApplyWindowInsets(windowInsets)
            }

            setContent {
                MaterialTheme {
                    val battery by sp.battery.collectAsState()
                    val timeString by sp.timeString.collectAsState()
                    val cutout by _cutoutInfo.collectAsState()
                    val shadeProgress by shadeController.shadeProgress.collectAsState()
                    val wifiOn by qsm.wifiEnabled.collectAsState()
                    val bluetoothOn by qsm.bluetoothEnabled.collectAsState()
                    val dndOn by qsm.dndEnabled.collectAsState()
                    val airplaneOn by qsm.airplaneModeEnabled.collectAsState()

                    StatusBar(
                        heightDp = 28,
                        iconSizeDp = 16,
                        clockPosition = com.android.systemui.lite.model.ClockPosition.LEFT,
                        batteryStyle = com.android.systemui.lite.model.BatteryPercentageStyle.ICON_AND_TEXT,
                        batteryLevel = battery.level,
                        isCharging = battery.isCharging,
                        isWifiOn = wifiOn,
                        isBluetoothOn = bluetoothOn,
                        isDoNotDisturb = dndOn,
                        isAirplaneMode = airplaneOn,
                        timeString = timeString,
                        isTrafficActive = false,
                        themeColor = androidx.compose.ui.graphics.Color(0xFF00ADB5),
                        safeInsetLeft = cutout.safeInsetLeft,
                        safeInsetRight = cutout.safeInsetRight,
                        onShadeToggle = { shadeController.toggleShade() },
                        onShadeDragUpdate = { offset ->
                            shadeController.dragShade((offset / maxShadeOffsetPx).coerceIn(0f, 1f))
                        },
                        onShadeDragEnd = { offset, isFling ->
                            if (kotlin.math.abs(offset) < 8f) return@StatusBar
                            val shouldOpen = if (isFling) offset > 120f
                            else offset > maxShadeOffsetPx / 3f
                            shadeController.flingShade(if (shouldOpen) 1f else 0f)
                        },
                        isShadeOpen = shadeProgress > 0.5f
                    )
                }
            }
        }

        try {
            wm.addView(statusBarView, params)
            Log.d(TAG, "StatusBar window added (TYPE_STATUS_BAR)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add status bar window: ${e.message}")
        }
    }

    private fun getStatusBarHeightPx(): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            (25 * context.resources.displayMetrics.density).toInt()
        }
    }
}
