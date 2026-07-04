package com.android.systemui.lite.core

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
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
import com.android.systemui.lite.CoreStartable
import com.android.systemui.lite.model.RecentTask
import com.android.systemui.lite.recents.RecentsProvider
import com.android.systemui.lite.ui.RecentsPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Hosts the [RecentsPanel] Compose overlay. Unlike the notification shade (which animates in), the
 * recents panel is shown/hidden as a whole — it's a transient fullscreen overlay that appears on
 * the RECENTS gesture and dismisses on any action.
 *
 * The task list is read fresh each time the panel opens so it always reflects the current state.
 */
class RecentsCoreStartable(private val context: Context) : CoreStartable {

    companion object {
        private const val TAG = "RecentsCoreStartable"
    }

    private val windowHost = WindowHost()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val recentsProvider = RecentsProvider(context)

    private var panelView: ComposeView? = null
    private var isPanelAdded = false

    private var tasks by mutableStateOf<List<RecentTask>>(emptyList())
    private var refreshJob: Job? = null

    override fun start() {
        Log.d(TAG, "Starting RecentsCoreStartable...")
        windowHost.start()
        Log.d(TAG, "RecentsCoreStartable started")
    }

    override fun onBootCompleted() {}
    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun stop() {
        Log.d(TAG, "Stopping RecentsCoreStartable...")
        refreshJob?.cancel()
        scope.cancel()
        hidePanel()
        windowHost.destroy()
    }

    /** Show the recents panel. Reads the task list, then attaches the overlay window. */
    fun showRecents() {
        Log.d(TAG, "showRecents")
        // Cancel any in-flight refresh.
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val loaded = recentsProvider.getRecentTasks()
            tasks = loaded
            Log.d(TAG, "recents loaded: ${loaded.size} tasks")
            ensurePanelWindow()
        }
    }

    fun hideRecents() {
        Log.d(TAG, "hideRecents")
        hidePanel()
    }

    // --- window management -------------------------------------------------

    private fun ensurePanelWindow() {
        if (isPanelAdded) return

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
            setTitle("RecentsPanel")
            packageName = context.packageName
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        panelView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(windowHost)
            setViewTreeViewModelStoreOwner(windowHost)
            setViewTreeSavedStateRegistryOwner(windowHost)

            setContent {
                MaterialTheme {
                    RecentsPanel(
                        tasks = tasks,
                        onOpenTask = { task ->
                            recentsProvider.moveToFront(task)
                            hidePanel()
                        },
                        onDismissTask = { task ->
                            recentsProvider.removeTask(task)
                            // Refresh the list so the card disappears.
                            scope.launch {
                                tasks = recentsProvider.getRecentTasks()
                            }
                        },
                        onClose = { hidePanel() }
                    )
                }
            }
        }

        try {
            wm.addView(panelView, params)
            isPanelAdded = true
            Log.d(TAG, "Recents panel window added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add recents panel: ${e.message}", e)
        }
    }

    private fun hidePanel() {
        panelView?.let { view ->
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove recents panel: ${e.message}")
            }
        }
        panelView = null
        isPanelAdded = false
    }
}
