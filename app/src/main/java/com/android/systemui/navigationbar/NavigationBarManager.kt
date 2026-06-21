package com.android.systemui.navigationbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.app.Instrumentation
import com.android.systemui.CoreStartable
import com.android.systemui.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavigationBarManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val windowManager: WindowManager
) : CoreStartable {

    companion object {
        private const val TAG = "NavBarManager"
        private const val NAVIGATION_MODE_KEY = "navigation_mode"
        private const val MODE_THREE_BUTTON = 0
        private const val MODE_GESTURAL = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    private var navigationBarView: View? = null
    private var edgeBackGestureHandler: EdgeBackGestureHandler? = null
    private var currentMode = MODE_THREE_BUTTON
    private var isAttached = false

    private val navigationModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val newMode = getNavigationMode()
            if (newMode != currentMode) {
                Log.i(TAG, "Navigation mode changed: $currentMode -> $newMode")
                currentMode = newMode
                handler.post { createNavigationBar() }
            }
        }
    }

    override fun start() {
        Log.i(TAG, "Starting navigation bar")
        currentMode = getNavigationMode()
        handler.post { createNavigationBar() }

        val filter = IntentFilter("android.settings.GESTURE_NAVIGATION_SETTINGS_CHANGED")
        try {
            context.registerReceiver(navigationModeReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register navigation mode receiver", e)
        }
    }

    override fun stop() {
        Log.i(TAG, "Stopping navigation bar")
        try { context.unregisterReceiver(navigationModeReceiver) } catch (_: Exception) {}
        removeNavigationBar()
        edgeBackGestureHandler?.stop()
    }

    private fun getNavigationMode(): Int {
        return try {
            Settings.Secure.getInt(context.contentResolver, NAVIGATION_MODE_KEY, MODE_THREE_BUTTON)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read navigation mode", e)
            MODE_THREE_BUTTON
        }
    }

    private fun createNavigationBar() {
        removeNavigationBar()

        val inflater = LayoutInflater.from(context)
        navigationBarView = if (currentMode == MODE_GESTURAL) {
            inflater.inflate(R.layout.navigation_bar_gestural, null)
        } else {
            inflater.inflate(R.layout.navigation_bar, null)
        }

        if (currentMode == MODE_GESTURAL) {
            setupGestureNavigation()
        } else {
            setupThreeButtonNavigation()
        }

        val navBarHeight = getNavigationBarHeight()
        val navBarFrameHeight = getNavBarFrameHeight()
        Log.i(TAG, "Navigation bar height: ${navBarHeight}px, frame: ${navBarFrameHeight}px, mode: $currentMode")

        // Use AOSP NavigationBar window params
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            navBarFrameHeight,
            WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                    or WindowManager.LayoutParams.FLAG_SLIPPERY,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            setTitle("NavigationBar")
            token = Binder()
            privateFlags = privateFlags or
                    WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC or
                    WindowManager.LayoutParams.PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            setTrustedOverlay()
        }

        try {
            windowManager.addView(navigationBarView, layoutParams)
            isAttached = true
            Log.i(TAG, "Navigation bar window added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add navigation bar window", e)
        }
    }

    private fun setupThreeButtonNavigation() {
        navigationBarView?.findViewById<ImageView>(R.id.back_button)?.setOnClickListener {
            simulateKeyPress(KeyEvent.KEYCODE_BACK)
        }

        navigationBarView?.findViewById<ImageView>(R.id.home_button)?.setOnClickListener {
            simulateKeyPress(KeyEvent.KEYCODE_HOME)
        }

        navigationBarView?.findViewById<ImageView>(R.id.recent_button)?.setOnClickListener {
            simulateKeyPress(KeyEvent.KEYCODE_RECENT_APPS)
        }
    }

    private fun setupGestureNavigation() {
        edgeBackGestureHandler = EdgeBackGestureHandler(context, windowManager).apply {
            start()
        }
    }

    private fun removeNavigationBar() {
        if (isAttached && navigationBarView != null) {
            try {
                windowManager.removeView(navigationBarView)
                Log.i(TAG, "Navigation bar window removed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove navigation bar", e)
            }
        }
        navigationBarView = null
        isAttached = false
    }

    private fun getNavigationBarHeight(): Int {
        val resourceId = context.resources.getIdentifier(
            "navigation_bar_height", "dimen", "android"
        )
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            48 * context.resources.displayMetrics.density.toInt()
        }
    }

    private fun getNavBarFrameHeight(): Int {
        val resourceId = context.resources.getIdentifier(
            "navigation_bar_frame_height", "dimen", "android"
        )
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            getNavigationBarHeight()
        }
    }

    private fun simulateKeyPress(keyCode: Int) {
        Thread {
            try {
                val instrumentation = Instrumentation()
                instrumentation.sendKeyDownUpSync(keyCode)
            } catch (e2: Exception) {
                Log.e(TAG, "Instrumentation fallback also failed: $keyCode", e2)
            }
//            try {
//                // Use input command for more reliable key simulation
//                val keyName = when (keyCode) {
//                    KeyEvent.KEYCODE_BACK -> "4"
//                    KeyEvent.KEYCODE_HOME -> "3"
//                    KeyEvent.KEYCODE_RECENT_APPS -> "312"
//                    else -> keyCode.toString()
//                }
//                val process = Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyName))
//                process.waitFor()
//                Log.d(TAG, "Key event sent: $keyCode ($keyName)")
//            } catch (e: Exception) {
//                Log.e(TAG, "Failed to simulate key press: $keyCode", e)
//                // Fallback to Instrumentation
//                try {
//                    val instrumentation = Instrumentation()
//                    instrumentation.sendKeyDownUpSync(keyCode)
//                } catch (e2: Exception) {
//                    Log.e(TAG, "Instrumentation fallback also failed: $keyCode", e2)
//                }
//            }
        }.start()
    }
}
