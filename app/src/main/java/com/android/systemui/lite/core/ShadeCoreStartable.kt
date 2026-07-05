package com.android.systemui.lite.core

import android.content.Context
import android.content.res.Configuration
import android.app.PendingIntent
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.systemui.lite.CoreStartable
import com.android.systemui.lite.data.NotificationProvider
import com.android.systemui.lite.data.SystemStateProvider
import com.android.systemui.lite.data.WallpaperProvider
import com.android.systemui.lite.ui.NotificationShade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

class ShadeCoreStartable(private val context: Context) : CoreStartable, ShadeController {

    companion object {
        private const val TAG = "ShadeCoreStartable"

        private const val TYPE_STATUS_BAR_SUB_PANEL = 2018

        /** Shade progress at or below this is treated as "invisible"; the window is unloaded. */
        private const val INVISIBLE_PROGRESS_THRESHOLD = 0.005f
    }

    private val windowHost = WindowHost()
    // Use AndroidUiDispatcher.Main — it provides the MonotonicFrameClock required by
    // Compose's Animatable. A bare-Dispatchers.Main scope hits:
    //   "A MonotonicFrameClock is not available in this CoroutineContext"
    // when shadeAnimatable.animateTo runs.
    private val scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)
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

    /**
     * Compose [Animatable] is the single source of truth for the shade-open fraction.
     *
     * Mirrors the top-slide-drawer approach:
     *  - During drag: [Animatable.snapTo] so the panel tracks the finger with zero latency.
     *  - On release: [Animatable.animateTo] with a spring for natural snap-open/closed.
     *
     * We still mirror the value into [_shadeProgress] so the rest of the codebase (which
     * consumes `ShadeController.shadeProgress` as a [StateFlow]) keeps working without
     * a setter pass-through.
     */
    override val shadeAnimatable = Animatable(
        initialValue = 0f,
    )
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
        Log.d(TAG, "toggleShade: isAdded=$isShadeWindowAdded, progress=${shadeAnimatable.value}")
        // Toggle comparing the Animatable's live value, not the StateFlow mirror which
        // lags a frame behind (line 211 write hasn't landed yet when we read here).
        if (!(isShadeWindowAdded && shadeAnimatable.value > 0.5f)) ensureShadeWindow()
        runShadeAnim { animateToWithSpring(Spring.DampingRatioNoBouncy, target = 1f) }
    }

    override fun snapShade(progress: Float) {
        // Immediate — no animation. Used during drag so the panel tracks finger with zero
        // latency (top-slide-drawer parity: drawerOffsetY.snapTo). snapTo cancels any
        // in-flight animation on the Animatable, so the coroutine we're already in (if
        // this came from a drag frame) is okay — but callers are non-suspend, so we
        // hop onto the dispatch scope to satisfy the suspend contract.
        val clamped = progress.coerceIn(0f, 1f)
        if (clamped > 0f) ensureShadeWindow()
        shadeAnimJob?.cancel()
        shadeAnimJob = scope.launch { shadeAnimatable.snapTo(clamped) }
    }

    override fun flingShade(target: Float) {
        val clamped = target.coerceIn(0f, 1f)
        if (clamped > 0f) ensureShadeWindow()
        runShadeAnim { animateToWithSpring(Spring.DampingRatioLowBouncy, target = clamped) }
    }

    /**
     * Cancel any in-flight shade animation and run [block] inside a fresh coroutine on the
     * UI dispatcher. Animations that land within [INVISIBLE_PROGRESS_THRESHOLD] of 0 close the
     * window on completion so the next open starts clean.
     */
    private fun runShadeAnim(block: suspend CoroutineScope.() -> Unit) {
        shadeAnimJob?.cancel()
        shadeAnimJob = scope.launch {
            block()
            if (shadeAnimatable.value <= INVISIBLE_PROGRESS_THRESHOLD) closeShade()
        }
    }

    /**
     * Spring-animate the shade to [target] with damping [ratio]. Stiffness is shared between
     * all uses (toggle, fling) — damping is the only user-visible difference.
     */
    private suspend fun CoroutineScope.animateToWithSpring(
        dampingRatio: Float,
        target: Float,
    ) {
        shadeAnimatable.animateTo(
            targetValue = target,
            animationSpec = spring(
                dampingRatio = dampingRatio,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }

    private fun ensureShadeWindow() {
        if (isShadeWindowAdded) return

        // Re-read all platform-controlled QS state so the shade reflects anything the
        // user changed while it was closed (e.g. airplane mode from system Settings).
        sp.refreshTileState()

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            TYPE_STATUS_BAR_SUB_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT,
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
                    val flashlightAvailableState = sp.flashlightAvailable.collectAsState()
                    val autoRotateOn by sp.autoRotateEnabled.collectAsState()
                    val batterySaverOn by sp.batterySaverEnabled.collectAsState()
                    val screenRecording by sp.screenRecording.collectAsState()
                    val brightness by sp.brightness.collectAsState()
                    val mediaVolume by sp.mediaVolume.collectAsState()
                    // Subscribe to the live Animatable value so every animation frame drives the
                    // offset directly — smoother round-trip than going through a StateFlow.
                    val progress by shadeAnimatable.asState()
                    // Inline-scoped mirror: keep the rest of the codebase's StateFlow consumers
                    // (anything that can't observe an Animatable, e.g. tests) in sync.
                    LaunchedEffect(progress) { _shadeProgress.value = progress }
                    val wallpaperColors by wp.wallpaperColors.collectAsState()
                    val shadeNotifications by notificationRepo.notifications.collectAsState()
                    val listenerConnected by notificationRepo.isConnected.collectAsState()
                    var viewHeightPx by remember { mutableFloatStateOf(0f) }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { viewHeightPx = it.height.toFloat() }
                            .graphicsLayer {
                                // Render-only translation. NOT Modifier.offset — offset changes
                                // the layout position, so child pointer events report coordinates
                                // in the shifted space. graphicsLayer's translationY leaves the
                                // layout (and pointer hit-testing) coordinates untouched so the
                                // pull-down gesture in the status-bar window sees a stable
                                // coordinate frame even as the panel animates.
                                translationY = -(viewHeightPx * (1f - progress))
                            }
                    ) {
                        NotificationShade(
                            themeColor = wallpaperColors.primary,
                            isWifiOn = wifiOn,
                            isBluetoothOn = bluetoothOn,
                            isBluetoothTransitioning = bluetoothTransitioning,
                            isDoNotDisturb = dndOn,
                            isFlashlightOn = flashlightOn,
                            isFlashlightAvailable = flashlightAvailableState.value ?: true,
                            isAirplaneMode = airplaneOn,
                            isAutoRotateOn = autoRotateOn,
                            isBatterySaverOn = batterySaverOn,
                            isScreenRecording = screenRecording,
                            brightness = brightness / 255f,
                            mediaVolume = mediaVolume / 100f,
                            notifications = shadeNotifications,
                            listenerConnected = listenerConnected,
                            isResourceMonitorActive = false,
                            statusBarHeightDp = 28,
                            // In-place toggle tiles (Wi-Fi / Bluetooth / DND / Flashlight /
                            // Airplane / Auto-Rotate): shade stays open so the user sees the
                            // tile state flip immediately. Only Battery Saver and Screen Rec
                            // close the shade — they both bounce to an external activity /
                            // consent flow, so the panel must be out of the way first.
                            onToggleWifi = { sp.toggleWifi() },
                            onToggleBluetooth = { sp.toggleBluetooth() },
                            onToggleDnd = { sp.toggleDnd() },
                            onToggleFlashlight = { sp.toggleFlashlight() },
                            onToggleAirplaneMode = { sp.toggleAirplaneMode() },
                            onToggleAutoRotate = { sp.toggleAutoRotate() },
                            onToggleBatterySaver = { flingShade(0f); sp.toggleBatterySaver() },
                            onToggleScreenRecording = { flingShade(0f); sp.toggleScreenRecording() },
                            // Long-press: close the shade, then open the matching system
                            // settings screen (AOSP QS tile long-press convention).
                            onLongPressWifi = { flingShade(0f); sp.openWifiSettings() },
                            onLongPressBluetooth = { flingShade(0f); sp.openBluetoothSettings() },
                            onLongPressDnd = { flingShade(0f); sp.openDndSettings() },
                            onLongPressFlashlight = { /* no settings screen */ },
                            onLongPressAirplaneMode = { flingShade(0f); sp.openAirplaneModeSettings() },
                            onLongPressAutoRotate = { flingShade(0f); sp.openAutoRotateSettings() },
                            onLongPressBatterySaver = { flingShade(0f); sp.openBatterySettings() },
                            onLongPressScreenRecording = { /* no settings screen */ },
                            onSetBrightness = { sp.setBrightness((it * 255).toInt()) },
                            onSetMediaVolume = { sp.setMediaVolume((it * 100).toInt()) },
                            onDismissNotification = { id ->
                                notificationRepo.dismissNotification(id.toString())
                            },
                            onClearAllNotifications = {
                                notificationRepo.clearAllNotifications()
                            },
                            onCloseShade = { flingShade(0f) },
                            onNotificationClick = { item ->
                                try {
                                    item.contentIntent?.send()
                                } catch (e: PendingIntent.CanceledException) {
                                    Log.e(TAG, "Failed to send contentIntent: ${e.message}", e)
                                }
                                flingShade(0f)
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
        // Reset the Animatable so the next drag starts from 0 (top-slide-drawer parity:
        // a closed drawer always resets to 0 — no stale progress carried between gestures).
        scope.launch { shadeAnimatable.snapTo(0f) }
        _shadeProgress.value = 0f
    }
}
