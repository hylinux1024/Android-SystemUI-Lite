package com.android.systemui.lite.systemui.core

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
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
import com.android.systemui.lite.systemui.CoreStartable
import com.android.systemui.lite.systemui.ui.NavigationBar
import com.android.systemui.lite.systemui.viewmodel.SystemUIViewModel

class NavigationBarCoreStartable(private val context: Context) : CoreStartable {

    companion object {
        private const val TAG = "NavigationBarCoreStartable"
    }

    private val windowHost = WindowHost()
    private var navBarView: ComposeView? = null

    private val viewModel by lazy { SystemUIViewModel.instance }

    override fun start() {
        Log.d(TAG, "Starting NavigationBarCoreStartable...")
        windowHost.start()
        initNavBarWindow()
        Log.d(TAG, "NavigationBarCoreStartable started")
    }

    override fun onBootCompleted() {}

    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun stop() {
        Log.d(TAG, "Stopping NavigationBarCoreStartable...")
        navBarView?.let { view ->
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing nav bar window: ${e.message}")
            }
        }
        navBarView = null
        windowHost.destroy()
    }

    private fun initNavBarWindow() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val navBarHeight = getNavigationBarHeightPx()

        @Suppress("DEPRECATION")
        val TYPE_NAVIGATION_BAR = 2019

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            navBarHeight,
            TYPE_NAVIGATION_BAR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            setTitle("NavigationBar")
            packageName = context.packageName
            setFitInsetsTypes(0)
        }

        navBarView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(windowHost)
            setViewTreeViewModelStoreOwner(windowHost)
            setViewTreeSavedStateRegistryOwner(windowHost)

            setContent {
                MaterialTheme {
                    val themeColor by viewModel.themeColor.collectAsState()
                    val navigationMode by viewModel.navigationMode.collectAsState()

                    NavigationBar(
                        themeColor = themeColor,
                        navigationMode = navigationMode,
                        onBack = { sendKeyEvent(4) },
                        onHome = { sendKeyEvent(3) },
                        onRecents = { sendKeyEvent(187) }
                    )
                }
            }
        }

        try {
            wm.addView(navBarView, params)
            Log.d(TAG, "NavigationBar window added (TYPE_NAVIGATION_BAR)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add nav bar window: ${e.message}")
            @Suppress("DEPRECATION")
            val fallbackParams = WindowManager.LayoutParams(
                params.width,
                params.height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = params.gravity
            }
            try {
                wm.addView(navBarView, fallbackParams)
                Log.d(TAG, "NavigationBar window added with TYPE_APPLICATION_OVERLAY (fallback)")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to add nav bar with fallback: ${e2.message}")
            }
        }
    }

    private fun getNavigationBarHeightPx(): Int {
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val height = if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
        return if (height > 0) height else (48 * context.resources.displayMetrics.density).toInt()
    }

    private fun sendKeyEvent(keyCode: Int) {
        try {
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send key event $keyCode: ${e.message}")
        }
    }
}
