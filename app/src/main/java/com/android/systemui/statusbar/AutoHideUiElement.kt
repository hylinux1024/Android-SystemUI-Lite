package com.android.systemui.statusbar

/**
 * UI element that participates in transient / auto-hide behavior.
 *
 * Port of AOSP `AutoHideUiElement` (frameworks/base/packages/SystemUI/src/com/android/...).
 */
interface AutoHideUiElement {
    /** Sync visibility/auto-hide state from the controller. Default no-op. */
    fun synchronizeState() {}

    /** Whether this element should be hidden on touch. */
    fun shouldHideOnTouch(): Boolean

    /** Whether the element is currently visible. */
    fun isVisible(): Boolean

    /** Hide the element (with optional animation). */
    fun hide()
}
