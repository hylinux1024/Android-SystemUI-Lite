package com.android.systemui.lite.core

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.ServiceManager
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.lite.CoreStartable
import com.android.systemui.lite.globalactions.GlobalActionsDialog
import com.android.systemui.lite.statusbar.CommandQueue

class GlobalActionsCoreStartable(private val context: Context) : CoreStartable, CommandQueue.Callbacks {

    companion object {
        private const val TAG = "GlobalActionsCoreSt"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowHost = WindowHost()
    private val commandQueue = CommandQueue()
    private var dialogView: ComposeView? = null
    private var isDialogAdded = false
    private var isVisible by mutableStateOf(false)

    private var statusBarService: IStatusBarService? = null
    @Volatile
    var isRegistered = false
        private set

    override fun start() {
        Log.d(TAG, "Starting GlobalActionsCoreStartable...")
        windowHost.start()

        commandQueue.addCallback(this)

        // Retry registration with delay to handle timing issues at boot
        tryRegister()
        mainHandler.postDelayed({ tryRegister() }, 2000L)
        mainHandler.postDelayed({ tryRegister() }, 5000L)

        try {
            val binder = ServiceManager.getService("statusbar")
            if (binder != null) {
                statusBarService = IStatusBarService.Stub.asInterface(binder)
                Log.d(TAG, "Obtained IStatusBarService for shutdown/reboot")
            } else {
                Log.w(TAG, "Failed to get statusbar service binder - will use shell fallback")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to obtain IStatusBarService: ${e.message}")
        }

        Log.d(TAG, "GlobalActionsCoreStartable started (registered=$isRegistered)")
    }

    private fun tryRegister() {
        if (isRegistered) return
        val registered = commandQueue.register()
        if (registered && !isRegistered) {
            isRegistered = true
            Log.d(TAG, "CommandQueue registered with system_server successfully")
        } else if (!registered) {
            Log.w(TAG, "CommandQueue registration FAILED (attempted) - will retry")
        }
    }

    override fun onBootCompleted() {}

    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun stop() {
        Log.d(TAG, "Stopping GlobalActionsCoreStartable...")
        commandQueue.removeCallback(this)
        dismissDialog()
        windowHost.destroy()
        statusBarService = null
    }

    fun show() {
        Log.d(TAG, "Manual trigger: show() called")
        showDialog()
    }

    override fun showGlobalActionsMenu() {
        Log.d(TAG, "IPC: showGlobalActionsMenu received from system_server")
        if (isVisible) {
            dismissDialog()
        } else {
            showDialog()
        }
    }

    override fun showShutdownUi(isReboot: Boolean, reason: String?) {
        Log.d(TAG, "IPC: showShutdownUi isReboot=$isReboot, reason=$reason")
        if (!isVisible) {
            showDialog()
        }
    }

    private fun showDialog() {
        if (isDialogAdded) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        val windowType = WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.FILL
            setTitle("GlobalActions")
            packageName = context.packageName
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        dialogView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(windowHost)
            setViewTreeViewModelStoreOwner(windowHost)
            setViewTreeSavedStateRegistryOwner(windowHost)

            setContent {
                MaterialTheme {
                    GlobalActionsDialog(
                        isVisible = isVisible,
                        onDismiss = { dismissDialog() },
                        onShutdown = { performShutdown() },
                        onRestart = { performRestart() }
                    )
                }
            }
        }

        try {
            wm.addView(dialogView, params)
            isDialogAdded = true
            isVisible = true
            Log.d(TAG, "Global actions dialog added (type=$windowType)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add global actions dialog: ${e.message}", e)
        }
    }

    private fun dismissDialog() {
        isVisible = false
        dialogView?.let { view ->
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove global actions dialog: ${e.message}", e)
            }
        }
        dialogView = null
        isDialogAdded = false
    }

    private fun performShutdown() {
        Log.d(TAG, "performShutdown")
        try {
            statusBarService?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "IStatusBarService.shutdown failed: ${e.message}")
            execShell("reboot -p")
        }
        if (statusBarService == null) {
            execShell("reboot -p")
        }
    }

    private fun performRestart() {
        Log.d(TAG, "performRestart")
        try {
            statusBarService?.reboot(false)
        } catch (e: Exception) {
            Log.e(TAG, "IStatusBarService.reboot failed: ${e.message}")
            execShell("reboot")
        }
        if (statusBarService == null) {
            execShell("reboot")
        }
    }

    private fun execShell(command: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            Log.d(TAG, "Shell: su -c $command")
        } catch (e: Exception) {
            Log.e(TAG, "Shell fallback failed: ${e.message}", e)
        }
    }
}
