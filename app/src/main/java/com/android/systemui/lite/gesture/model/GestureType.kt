package com.android.systemui.lite.gesture.model

/**
 * The semantic outcome of a committed [GestureType]. What the user meant to do. The action
 * sink maps each of these to a platform keyevent, recents launch, or shade toggle.
 */
enum class GestureType {
    BACK,
    HOME,
    RECENTS,
    /** Pull-down from the status bar to open the notification shade. */
    SHADE,
}
