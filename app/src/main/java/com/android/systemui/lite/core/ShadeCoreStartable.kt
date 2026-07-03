package com.android.systemui.lite.core

import android.content.Context
import android.content.res.Configuration
import android.app.PendingIntent
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.systemui.lite.CoreStartable
import com.android.systemui.lite.data.NotificationProvider
import com.android.systemui.lite.data.SystemStateProvider
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
import kotlin.time.Duration.Companion.milliseconds

class ShadeCoreStartable(private val context: Context) : CoreStartable, ShadeController {

    companion object {
        private const val TAG = "ShadeCoreStartable"
    }

    private val windowHost = WindowHost()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val sp by lazy {
        GlobalContext.get().get<SystemStateProvider>()
    }
    private val wp by lazy {
        GlobalContext.get().get<WallpaperProvider>()
    }
    private val notificationRepo by lazy {
        GlobalContext.get().get<NotificationProvider>()
    }

    private var shadeView: ComposeView? = null
    private var isShadeWindowAdded = false

    private val _shadeProgress = MutableStateFlow(0f)
    override val shadeProgress: StateFlow<Float> = _shadeProgress.asStateFlow()

    private var shadeAnimJob: Job? = null

    override fun start() {
        Log.d(TAG, "Starting ShadeCoreStartable...")
        windowHost.start()
        sp.start()
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
        sp.stop()
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
                delay(16.milliseconds)
            }
            if (_shadeProgress.value <= 0f) {
                closeShade()
            }
        }
    }

    private fun ensureShadeWindow() {
        if (isShadeWindowAdded) return

        // Re-read all platform-controlled QS state so the shade reflects anything the
        // user changed while it was closed (e.g. airplane mode from system Settings).
        sp.refreshTileState()

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

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
                    val wifiOn by sp.wifiEnabled.collectAsState()
                    val bluetoothOn by sp.bluetoothEnabled.collectAsState()
                    val bluetoothTransitioning by sp.bluetoothTransitioning.collectAsState()
                    val dndOn by sp.dndEnabled.collectAsState()
                    val airplaneOn by sp.airplaneModeEnabled.collectAsState()
                    val flashlightOn by sp.flashlightEnabled.collectAsState()
                    val autoRotateOn by sp.autoRotateEnabled.collectAsState()
                    val screenRecording by sp.screenRecording.collectAsState()
                    val brightness by sp.brightness.collectAsState()
                    val mediaVolume by sp.mediaVolume.collectAsState()
                    val progress by _shadeProgress.collectAsState()
                    val wallpaperColors by wp.wallpaperColors.collectAsState()
                    val shadeNotifications by notificationRepo.notifications.collectAsState()
                    val listenerConnected by notificationRepo.isConnected.collectAsState()
                    var viewHeightPx by remember { mutableFloatStateOf(0f) }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { viewHeightPx = it.height.toFloat() }
                            .offset {
                                IntOffset(0, (-(viewHeightPx * (1f - progress))).toInt())
                            }
                    ) {
                        NotificationShade(
                            themeColor = wallpaperColors.primary,
                            isWifiOn = wifiOn,
                            isBluetoothOn = bluetoothOn,
                            isBluetoothTransitioning = bluetoothTransitioning,
                            isDoNotDisturb = dndOn,
                            isFlashlightOn = flashlightOn,
                            isAirplaneMode = airplaneOn,
                            isAutoRotateOn = autoRotateOn,
                            isScreenRecording = screenRecording,
                            brightness = brightness / 255f,
                            mediaVolume = mediaVolume / 100f,
                            notifications = shadeNotifications,
                            listenerConnected = listenerConnected,
                            isResourceMonitorActive = false,
                            statusBarHeightDp = 28,
                            onToggleWifi = { sp.toggleWifi() },
                            onToggleBluetooth = { sp.toggleBluetooth() },
                            onToggleDnd = { sp.toggleDnd() },
                            onToggleFlashlight = { sp.toggleFlashlight() },
                            onToggleAirplaneMode = { sp.toggleAirplaneMode() },
                            onToggleAutoRotate = { sp.toggleAutoRotate() },
                            onToggleScreenRecording = { sp.toggleScreenRecording() },
                            onSetBrightness = { sp.setBrightness((it * 255).toInt()) },
                            onSetMediaVolume = { sp.setMediaVolume((it * 100).toInt()) },
                            onDismissNotification = { id ->
                                notificationRepo.dismissNotification(id.toString())
                            },
                            onClearAllNotifications = {
                                notificationRepo.clearAllNotifications()
                            },
                            onCloseShade = { animateShadeTo(0f) },
                            onDragShade = { progress -> dragShade(progress) },
                            onOpenShade = { flingShade(1f) },
                            onNotificationClick = { item ->
                                try {
                                    item.contentIntent?.send()
                                } catch (e: PendingIntent.CanceledException) {
                                    Log.e(TAG, "Failed to send contentIntent: ${e.message}", e)
                                }
                                animateShadeTo(0f)
                                if (item.autoCancel) {
                                    notificationRepo.dismissNotification(item.id.toString())
                                }
                            },
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
