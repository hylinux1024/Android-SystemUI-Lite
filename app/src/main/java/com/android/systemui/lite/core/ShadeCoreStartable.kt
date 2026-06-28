package com.android.systemui.lite.core

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
import com.android.systemui.lite.CoreStartable
import com.android.systemui.lite.data.WallpaperProvider
import com.android.systemui.lite.ui.NotificationShade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

class ShadeCoreStartable(private val context: Context) : CoreStartable, ShadeController {

    companion object {
        private const val TAG = "ShadeCoreStartable"
    }

    private val windowHost = WindowHost()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val sp by lazy {
        GlobalContext.get().get<com.android.systemui.lite.data.SystemStateProvider>()
    }
    private val wp by lazy {
        GlobalContext.get().get<WallpaperProvider>()
    }

    private var shadeView: ComposeView? = null
    private var isShadeWindowAdded = false

    private val _shadeProgress = MutableStateFlow(0f)
    override val shadeProgress: StateFlow<Float> = _shadeProgress.asStateFlow()

    private var shadeAnimJob: Job? = null

    override fun start() {
        Log.d(TAG, "Starting ShadeCoreStartable...")
        windowHost.start()
        wp.start()
        Log.d(TAG, "ShadeCoreStartable started")
    }

    override fun onBootCompleted() {}
    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun stop() {
        Log.d(TAG, "Stopping ShadeCoreStartable...")
        shadeAnimJob?.cancel()
        scope.cancel()
        closeShade()
        wp.stop()
        windowHost.destroy()
    }

    override fun toggleShade() {
        Log.d(TAG, "toggleShade: isAdded=$isShadeWindowAdded, progress=${_shadeProgress.value}")
        if (isShadeWindowAdded && _shadeProgress.value > 0.5f) {
            animateShadeTo(0f)
        } else {
            ensureShadeWindow()
            animateShadeTo(1f)
        }
    }

    override fun dragShade(progress: Float) {
        shadeAnimJob?.cancel()
        val clamped = progress.coerceIn(0f, 1f)
        _shadeProgress.value = clamped
        if (clamped > 0f) ensureShadeWindow()
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
                closeShade()
            }
        }
    }

    private fun ensureShadeWindow() {
        if (isShadeWindowAdded) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val screenHeightPx = context.resources.displayMetrics.heightPixels

        @Suppress("DEPRECATION")
        val TYPE_STATUS_BAR_SUB_PANEL = 2018

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            TYPE_STATUS_BAR_SUB_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.FILL
            setTitle("NotificationShade")
            packageName = context.packageName
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        shadeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(windowHost)
            setViewTreeViewModelStoreOwner(windowHost)
            setViewTreeSavedStateRegistryOwner(windowHost)

            setContent {
                MaterialTheme {
                    val connectivity by sp.connectivity.collectAsState()
                    val brightness by sp.brightness.collectAsState()
                    val mediaVolume by sp.mediaVolume.collectAsState()
                    val flashlightOn by sp.flashlightEnabled.collectAsState()
                    val autoRotateOn by sp.autoRotateEnabled.collectAsState()
                    val progress by _shadeProgress.collectAsState()
                    val wallpaperColors by wp.wallpaperColors.collectAsState()

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset {
                                IntOffset(0, (-(screenHeightPx.toFloat() * (1f - progress))).toInt())
                            }
                    ) {
                        NotificationShade(
                            themeColor = wallpaperColors.primary,
                            isWifiOn = connectivity.wifiEnabled,
                            isBluetoothOn = connectivity.bluetoothEnabled,
                            isDoNotDisturb = connectivity.dndEnabled,
                            isFlashlightOn = flashlightOn,
                            isAirplaneMode = connectivity.airplaneMode,
                            isAutoRotateOn = autoRotateOn,
                            isScreenRecording = false,
                            brightness = brightness / 255f,
                            mediaVolume = mediaVolume / 100f,
                            notifications = emptyList(),
                            isResourceMonitorActive = false,
                            statusBarHeightDp = 28,
                            onToggleWifi = { sp.toggleWifi() },
                            onToggleBluetooth = { sp.toggleBluetooth() },
                            onToggleDnd = { sp.toggleDnd() },
                            onToggleFlashlight = { sp.toggleFlashlight() },
                            onToggleAirplaneMode = { sp.toggleAirplaneMode() },
                            onToggleAutoRotate = { sp.toggleAutoRotate() },
                            onToggleScreenRecording = {},
                            onSetBrightness = { sp.setBrightness((it * 255).toInt()) },
                            onSetMediaVolume = { sp.setMediaVolume((it * 100).toInt()) },
                            onDismissNotification = {},
                            onClearAllNotifications = {},
                            onCloseShade = { animateShadeTo(0f) },
                            onPlayPauseMusic = {},
                            onPrevTrack = {},
                            onNextTrack = {}
                        )
                    }
                }
            }
        }

        try {
            wm.addView(shadeView, params)
            isShadeWindowAdded = true
            Log.d(TAG, "Notification shade window added (TYPE_STATUS_BAR_SUB_PANEL)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add shade window: ${e.message}", e)
        }
    }

    private fun closeShade() {
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
        _shadeProgress.value = 0f
    }
}
