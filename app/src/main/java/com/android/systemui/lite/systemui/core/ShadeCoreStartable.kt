package com.android.systemui.lite.systemui.core

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
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
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.systemui.lite.systemui.CoreStartable
import com.android.systemui.lite.systemui.ui.NotificationShade
import com.android.systemui.lite.systemui.viewmodel.SystemUIViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ShadeCoreStartable(private val context: Context) : CoreStartable, ShadeController {

    companion object {
        private const val TAG = "ShadeCoreStartable"
    }

    private val windowHost = WindowHost()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val viewModel by lazy { SystemUIViewModel.instance }

    private var shadeView: ComposeView? = null
    private var isShadeWindowAdded = false

    private val _shadeProgress = MutableStateFlow(0f)
    val shadeProgress: StateFlow<Float> = _shadeProgress

    private var shadeAnimJob: Job? = null

    override fun start() {
        Log.d(TAG, "Starting ShadeCoreStartable...")
        windowHost.start()
        Log.d(TAG, "ShadeCoreStartable started")
    }

    override fun onBootCompleted() {}

    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun stop() {
        Log.d(TAG, "Stopping ShadeCoreStartable...")
        shadeAnimJob?.cancel()
        scope.cancel()
        closeNotificationShade()
        windowHost.destroy()
    }

    override fun toggleShade() {
        if (isShadeWindowAdded && _shadeProgress.value > 0.5f) {
            animateShadeTo(0f)
        } else {
            ensureShadeWindow()
            animateShadeTo(1f)
        }
    }

    override fun dragShade(progress: Float) {
        shadeAnimJob?.cancel()
        _shadeProgress.value = progress.coerceIn(0f, 1f)
        if (progress > 0f) {
            ensureShadeWindow()
        }
    }

    override fun flingShade(target: Float) {
        animateShadeTo(target.coerceIn(0f, 1f))
    }

    private fun animateShadeTo(target: Float) {
        shadeAnimJob?.cancel()
        val from = _shadeProgress.value
        val to = target
        if (!isShadeWindowAdded && to <= 0f) return

        shadeAnimJob = scope.launch {
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

    private fun ensureShadeWindow() {
        if (isShadeWindowAdded) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val screenHeightPx = context.resources.displayMetrics.heightPixels
        val maxShadeOffsetPx = screenHeightPx.toFloat()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.FILL
            setTitle("NotificationShade")
            packageName = context.packageName
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        shadeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(windowHost)
            setViewTreeViewModelStoreOwner(windowHost)
            setViewTreeSavedStateRegistryOwner(windowHost)

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
                        onCloseShade = { animateShadeTo(0f) }
                    )
                }
            }
        }

        try {
            wm.addView(shadeView, params)
            isShadeWindowAdded = true
            Log.d(TAG, "Notification shade window added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add shade window: ${e.message}", e)
        }
    }

    private fun closeNotificationShade() {
        shadeView?.let { view ->
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove shade: ${e.message}", e)
            }
        }
        shadeView = null
        isShadeWindowAdded = false
    }
}
