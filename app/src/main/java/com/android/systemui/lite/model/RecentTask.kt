package com.android.systemui.lite.model

import android.graphics.drawable.Drawable

/** A single entry in the recents (recent apps) list. */
data class RecentTask(
    /** Task id — passed to [android.app.ActivityTaskManager] to bring to front. */
    val taskId: Int,
    /** Stable id across task restarts. */
    val persistentId: Int,
    /** User-visible label (resolved from the activity's labelRes). */
    val label: String,
    /** The activity that launched the task (used to resolve the icon + relaunch intent). */
    val componentName: String,
    /** Task icon, or null if unavailable. */
    val icon: Drawable?,
    /** True if the task is currently running (vs. only cached in the recents list). */
    val isRunning: Boolean,
    /** Snapshot/thumbnail bitmap, if available. */
    val thumbnail: android.graphics.Bitmap?
)
