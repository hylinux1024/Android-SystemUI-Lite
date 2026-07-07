package com.android.systemui.statusbar

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Space
import android.widget.TextView
import com.android.systemui.CoreStartable
import com.android.systemui.R
import com.android.systemui.wallpapers.WallpaperProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Status-bar entry point. Adds a TYPE_STATUS_BAR window overlay that mirrors the
 * AOSP SystemUI `PhoneStatusBarView` / phone-system-bar layout, driven by
 * domain controllers (clock / battery / wifi / signal) and a wallpaper-driven
 * dark/light tint (see WallpaperProvider).
 *
 * Window-management params, cutout handling and transient/visibility state mirror
 * AOSP `StatusBarWindowController` + `CentralSurfacesImpl`. Icons come from the
 * framework drawable namespace (`@android:drawable/stat_sys_*`); the specific
 * stat_sys vectors for the supporting system icons (vpn / bluetooth / cast / dnd /
 * etc.) live in this APK's `res/drawable/` (copied from SystemUI).
 *
 * The window is added in [start]; removed in [stop]. Registering happens up-front
 * in SystemUIApplication.onCreate per the CoreStartableComponent contract.
 */
@Singleton
class StatusBarManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val windowManager: WindowManager,
    private val batteryController: BatteryController,
    private val clockController: ClockController,
    private val wifiController: WifiController,
    private val signalController: SignalController,
    private val wallpaperProvider: WallpaperProvider,
    val autoHideController: AutoHideController        // public → SystemUIApplication wires it
) : CoreStartable,
    BatteryController.BatteryStateListener,
    WifiController.WifiStateListener,
    SignalController.SignalStateListener,
    AutoHideUiElement {

    companion object {
        private const val TAG = "StatusBarManager"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var statusBarView: View? = null
    private var clockTextView: TextView? = null
    private var batteryLevelTextView: TextView? = null
    private var batteryIcon: ImageView? = null
    private var wifiIcon: ImageView? = null
    private var signalIcon: ImageView? = null
    private var systemIconsLayout: android.widget.LinearLayout? = null
    private var cutoutSpace: Space? = null

    // touch → auto-hide + (reserved) Stage 2 shade-forward
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var isTransientShown = false
    private var isDarkTint = false

    // Wallpaper dark-mode listener — kept as a field so removeListener() can find it.
    private val wallpaperListener = WallpaperProvider.OnWallpaperColorsChangedListener { data ->
        handler.post { applyDarkMode(data.isDark) }
    }

    override fun start() {
        Log.i(TAG, "start")
        // The clock is bound + started inside createStatusBarWindow() once the view exists.
        createStatusBarWindow()
        registerListeners()
    }

    override fun stop() {
        Log.i(TAG, "stop")
        unregisterListeners()
        removeStatusBarWindow()
    }

    // ------------------------------------------------------------------ window

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

        // Bind the clock controller once we have its view. start() then runs the clock.
        clockController.bind(clockTextView)
        clockController.start()

        statusBarView?.setOnApplyWindowInsetsListener { v, insets ->
            handleWindowInsets(v, insets); insets
        }
        statusBarView?.setOnTouchListener { _, e -> handleStatusBarTouch(e) }

        val height = getStatusBarHeight()
        Log.i(TAG, "status bar height=${height}px")

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
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
            windowManager.addView(statusBarView, lp)
            Log.i(TAG, "status bar window added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add status bar window", e)
        }
    }

    private fun removeStatusBarWindow() {
        statusBarView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove status bar window", e)
            }
        }
        statusBarView = null
    }

    private fun getStatusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id)
        else (24 * context.resources.displayMetrics.density.toInt())
    }

    // ----------------------------------------------------------------- icon tint

    private fun applyDarkMode(isDark: Boolean) {
        isDarkTint = isDark
        val tint = if (isDark) {
            context.getColor(R.color.status_bar_icon_tint_dark)
        } else {
            context.getColor(R.color.status_bar_icon_tint_light)
        }
        val clockTint = if (isDark) {
            context.getColor(R.color.status_bar_clock_color_dark)
        } else {
            context.getColor(R.color.status_bar_clock_color_light)
        }
        wifiIcon?.imageTintList = ColorStateList.valueOf(tint)
        signalIcon?.imageTintList = ColorStateList.valueOf(tint)
        batteryIcon?.imageTintList = ColorStateList.valueOf(tint)
        batteryLevelTextView?.setTextColor(tint)
        clockTextView?.setTextColor(clockTint)
    }

    // ------------------------------------------------------------------ listeners

    /** Attach every source of dynamic state. Called from [start] after the view exists. */
    private fun registerListeners() {
        batteryController.addListener(this)
        wifiController.register(this)
        signalController.register(this)
        wifiController.start()
        signalController.start()
        // Wallpaper-driven dark/light tint. The listener is a field so stop() can find & remove it.
        wallpaperProvider.addListener(wallpaperListener)
        applyDarkMode(wallpaperProvider.current.isDark)
        applyInitialIconState()
        // Stage 2 (notification shade) touch-forward is reserved; no-op here.
    }

    /** Detach state sources (but leave the view/window in place). */
    private fun unregisterListeners() {
        batteryController.removeListener(this)
        wifiController.unregister()
        signalController.unregister()
        wifiController.stop()
        signalController.stop()
        clockController.stop()
        clockController.bind(null)
        wallpaperProvider.removeListener(wallpaperListener)
    }

    private fun applyInitialIconState() {
        val lvl = batteryController.getBatteryLevel()
        updateBatteryUI(lvl, batteryController.isCharging())
    }

    // BatteryStateListener
    override fun onBatteryLevelChanged(level: Int, isCharging: Boolean) {
        handler.post { updateBatteryUI(level, isCharging) }
    }

    // WifiStateListener
    override fun onWifiStateChanged(level: Int, enabled: Boolean, rssi: Int) {
        handler.post { updateWifiIcon(level, enabled) }
    }

    // SignalStateListener
    override fun onSignalStateChanged(level: Int, inService: Boolean, airplaneMode: Boolean) {
        handler.post { updateSignalIcon(level, inService, airplaneMode) }
    }

    // -------------------------------------------------------------- UI updates

    private fun updateBatteryUI(level: Int, isCharging: Boolean) {
        batteryLevelTextView?.text = "$level%"
        val name = if (isCharging) "stat_sys_battery_charge" else "stat_sys_battery"
        val resId = context.resources.getIdentifier(name, "drawable", "android")
        if (resId != 0) {
            batteryIcon?.setImageResource(resId)
        }
        // If the framework drawable is missing, fall back to a level-specific icon.
        if (resId == 0) {
            val lvlName = "stat_sys_battery_${level.toBatteryLevel()}"
            val lvlId = context.resources.getIdentifier(lvlName, "drawable", "android")
            if (lvlId != 0) batteryIcon?.setImageResource(lvlId)
        }
    }

    private fun updateWifiIcon(level: Int, enabled: Boolean) {
        if (!enabled) { wifiIcon?.visibility = View.GONE; return }
        wifiIcon?.visibility = View.VISIBLE
        val clamped = if (level in 0..4) level else 0
        val resId = context.resources.getIdentifier(
            "stat_sys_wifi_signal_$clamped", "drawable", "android")
        if (resId != 0) wifiIcon?.setImageResource(resId)
    }

    private fun updateSignalIcon(level: Int, inService: Boolean, airplaneMode: Boolean) {
        signalIcon?.visibility = View.VISIBLE
        if (!inService || airplaneMode || level < 0) {
            val nullId = context.resources.getIdentifier("stat_sys_signal_null", "drawable", "android")
            if (nullId != 0) signalIcon?.setImageResource(nullId)
            return
        }
        val clamped = level.coerceIn(0, 4)
        val resId = context.resources.getIdentifier("stat_sys_signal_$clamped", "drawable", "android")
        if (resId != 0) signalIcon?.setImageResource(resId)
    }

    /** Map a 0..100 battery level to a 0..N drawable bucket (AOSP-style). No icons → hide text only. */
    private fun Int.toBatteryLevel(): Int = when {
        this >= 100 -> 5
        this >= 80 -> 4
        this >= 60 -> 3
        this >= 40 -> 2
        this >= 20 -> 1
        else -> 0
    }

    // ------------------------------------------------------------------- cutout

    private fun handleWindowInsets(view: View, insets: WindowInsets) {
        val cutout = insets.displayCutout ?: run {
            // No cutout — reset to default horizontal padding.
            val ps = context.resources.getDimensionPixelSize(R.dimen.status_bar_padding_start)
            val pe = context.resources.getDimensionPixelSize(R.dimen.status_bar_padding_end)
            view.setPadding(ps, view.paddingTop, pe, view.paddingBottom)
            cutoutSpace?.visibility = View.GONE
            return
        }
        val topCutout = cutout.boundingRectTop
        if (topCutout.isEmpty) {
            val ps = context.resources.getDimensionPixelSize(R.dimen.status_bar_padding_start)
            val pe = context.resources.getDimensionPixelSize(R.dimen.status_bar_padding_end)
            view.setPadding(ps, view.paddingTop, pe, view.paddingBottom)
            cutoutSpace?.visibility = View.GONE
            return
        }
        val display = context.display
        val point = android.graphics.Point()
        display?.getRealSize(point)
        val isCornerCutout = topCutout.left <= 0 || topCutout.right >= (point.x ?: 0)

        if (isCornerCutout) {
            cutoutSpace?.visibility = View.GONE
            val ps = context.resources.getDimensionPixelSize(R.dimen.status_bar_padding_start)
            val pe = context.resources.getDimensionPixelSize(R.dimen.status_bar_padding_end)
            val left = maxOf(topCutout.width(), cutout.safeInsetLeft, ps)
            val right = maxOf(cutout.safeInsetRight, pe)
            view.setPadding(left, view.paddingTop, right, view.paddingBottom)
        } else {
            cutoutSpace?.visibility = View.VISIBLE
            (cutoutSpace?.layoutParams as? android.widget.LinearLayout.LayoutParams)?.width =
                topCutout.width()
            cutoutSpace?.layoutParams = cutoutSpace?.layoutParams
            view.setPadding(cutout.safeInsetLeft, view.paddingTop,
                cutout.safeInsetRight, view.paddingBottom)
        }
    }

    // -------------------------------------------------------------------- touch

    private fun handleStatusBarTouch(event: MotionEvent): Boolean {
        autoHideController.checkUserAutoHide(event)
        // Stage 2 (notification shade): forward DOWN/MOVE/UP via NotificationShadeManager.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                autoHideController.touchAutoHide()
                return true
            }
        }
        return true
    }

    // ----------------------------------------------------------- AutoHide

    override fun synchronizeState() { /* transient alpha handled by setTransientShown */ }

    override fun shouldHideOnTouch(): Boolean = true

    override fun isVisible(): Boolean = statusBarView?.visibility == View.VISIBLE

    override fun hide() {
        statusBarView?.animate()?.alpha(0f)?.withEndAction {
            statusBarView?.visibility = View.GONE
        }
    }

    fun setTransientShown(transient: Boolean) {
        handler.post {
            isTransientShown = transient
            statusBarView?.visibility = View.VISIBLE
            statusBarView?.alpha = if (transient) 0.8f else 1.0f
        }
    }

    fun setBarVisible(visible: Boolean) {
        handler.post { statusBarView?.visibility = if (visible) View.VISIBLE else View.GONE }
    }
}
