package com.android.systemui.lite.recents

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import com.android.systemui.lite.model.RecentTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the system recents list via [ActivityManager.getRecentTasks] and resolves each entry to a
 * [RecentTask] with a human-readable label and icon. Requires the privileged REAL_GET_TASKS
 * permission (granted to SystemUI) — without it the platform returns only the calling app's own
 * tasks, which would make the list useless.
 *
 * The home task (launcher) and the recents task itself are filtered out so the list only contains
 * real app tasks the user can switch to.
 *
 * Note: on modern Android the returned [ActivityManager.RecentTaskInfo] objects have null
 * `baseActivity`/`topActivity` ComponentName fields (they're stripped for privacy in the
 * cross-process parcel). The component is instead read from `info.baseIntent.component`, which is
 * always populated for real app tasks.
 */
class RecentsProvider(private val context: Context) {

    companion object {
        private const val TAG = "RecentsProvider"
        private const val MAX_TASKS = 12
    }

    private val pm: PackageManager = context.packageManager

    /** Resolve the current recents list. Runs on [Dispatchers.IO] — involves IPC + icon loads. */
    suspend fun getRecentTasks(): List<RecentTask> = withContext(Dispatchers.IO) {
        try {
            // ActivityTaskManager.getRecentTasks(maxNum, flags, userId) is the current API.
            // ActivityManager.getRecentTasks(int, int) is deprecated and returns empty on modern
            // Android, which is exactly the bug we're fixing.
            val atm = context.getSystemService(android.app.ActivityTaskManager::class.java) ?: run {
                Log.w(TAG, "ActivityTaskManager service null")
                return@withContext emptyList()
            }
            val raw = atm.getRecentTasks(MAX_TASKS, ActivityManager.RECENT_IGNORE_UNAVAILABLE, 0)
            val homePackage = homePackageName()

            val result = raw.mapNotNull { info ->
                // Prefer baseIntent.component — baseActivity/topActivity are null in the
                // cross-process result on modern Android (stripped for privacy).
                val component = info.baseIntent?.component
                    ?: info.baseActivity
                    ?: info.topActivity
                    ?: return@mapNotNull null
                if (component.packageName == homePackage) return@mapNotNull null
                if (component.className.contains("RecentsActivity")) return@mapNotNull null

                val (label, icon) = resolveLabelAndIcon(component, info.taskDescription)

                RecentTask(
                    taskId = info.id.takeIf { it > 0 } ?: info.persistentId,
                    persistentId = info.persistentId,
                    label = label,
                    componentName = component.flattenToString(),
                    icon = icon,
                    isRunning = info.isRunning,
                    thumbnail = null // Thumbnails require SystemUI-internal surface access; omitted.
                )
            }
            Log.d(TAG, "recents loaded: ${result.size} tasks")
            result
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "3-arg getRecentTasks not found, falling back to ActivityManager")
            getRecentTasksLegacy()
        } catch (e: SecurityException) {
            Log.e(TAG, "getRecentTasks denied — missing REAL_GET_TASKS?", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "getRecentTasks failed: ${e.message}", e)
            emptyList()
        }
    }

    /** Deprecated 2-arg fallback. */
    private suspend fun getRecentTasksLegacy(): List<RecentTask> = withContext(Dispatchers.IO) {
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return@withContext emptyList()
            val raw = am.getRecentTasks(MAX_TASKS, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
            val homePackage = homePackageName()
            raw.mapNotNull { info ->
                val component = info.baseIntent?.component ?: info.baseActivity ?: info.topActivity
                    ?: return@mapNotNull null
                if (component.packageName == homePackage) return@mapNotNull null
                if (component.className.contains("RecentsActivity")) return@mapNotNull null
                val (label, icon) = resolveLabelAndIcon(component, info.taskDescription)
                RecentTask(
                    taskId = info.id.takeIf { it > 0 } ?: info.persistentId,
                    persistentId = info.persistentId,
                    label = label,
                    componentName = component.flattenToString(),
                    icon = icon,
                    isRunning = info.isRunning,
                    thumbnail = null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "legacy getRecentTasks failed: ${e.message}")
            emptyList()
        }
    }

    /** Bring an existing task to the foreground. */
    fun moveToFront(task: RecentTask) {
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            am.moveTaskToFront(task.persistentId, ActivityManager.MOVE_TASK_WITH_HOME)
        } catch (e: Exception) {
            Log.e(TAG, "moveTaskToFront failed: ${e.message}")
        }
    }

    /**
     * Remove a task from recents (swipe-dismiss). `ActivityManager.removeTask()` is not public, so
     * we reach the hidden one on `IActivityTaskManager` via the concrete service-impl class.
     */
    fun removeTask(task: RecentTask) {
        try {
            val atm = context.getSystemService(android.app.ActivityTaskManager::class.java) ?: return
            val method = atm.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType)
            method.invoke(atm, task.persistentId)
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "removeTask not found on ActivityTaskManager")
        } catch (e: Exception) {
            Log.e(TAG, "removeTask failed: ${e.message}")
        }
    }

    // --- internals ---------------------------------------------------------

    private fun resolveLabelAndIcon(
        component: ComponentName,
        taskDescription: android.app.ActivityManager.TaskDescription?
    ): Pair<String, Drawable?> {
        // Prefer the TaskDescription label — it's what the launcher shows and stays correct even
        // if the activity's manifest label is generic.
        val tdLabel = taskDescription?.label
        if (!tdLabel.isNullOrBlank()) {
            return tdLabel to resolveIcon(component, taskDescription)
        }
        return try {
            val ai = pm.getActivityInfo(component, 0)
            val label = ai.loadLabel(pm).toString()
            val icon = ai.loadIcon(pm)
            label to icon
        } catch (e: PackageManager.NameNotFoundException) {
            component.shortClassName to null
        }
    }

    private fun resolveIcon(
        component: ComponentName,
        taskDescription: android.app.ActivityManager.TaskDescription?
    ): Drawable? {
        taskDescription?.icon?.let { icon ->
            return android.graphics.drawable.BitmapDrawable(context.resources, icon)
        }
        return try {
            val ai = pm.getActivityInfo(component, 0)
            ai.loadIcon(pm)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun homePackageName(): String {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
        }
        val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName ?: "com.android.launcher3"
    }
}
