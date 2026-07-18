package com.android.systemui.statusbar.shade

import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for panel-expansion state. Any component that needs to
 * react to the panel being dragged open / flung / collapsed registers a
 * [ShadeExpansionListener] here.
 *
 * Mirrors AOSP `ShadeExpansionStateManager`.
 */
data class ShadeExpansionState(
    /** 0.0 = fully collapsed, 1.0 = fully expanded. */
    val fraction: Float = 0f,
    /** True whenever the panel is not fully collapsed. */
    val expanded: Boolean = false,
    /** True while the user's finger is actively dragging the panel. */
    val tracking: Boolean = false
)

fun interface ShadeExpansionListener {
    fun onPanelExpansionChanged(state: ShadeExpansionState)
}

@Singleton
class ShadeExpansionStateManager @Inject constructor() {

    private val listeners = CopyOnWriteArrayList<ShadeExpansionListener>()

    var state = ShadeExpansionState()
        private set

    fun addListener(listener: ShadeExpansionListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ShadeExpansionListener) {
        listeners.remove(listener)
    }

    /**
     * Update the expansion. Coerces [fraction] into [0, 1] and recomputes
     * [ShadeExpansionState.expanded]. Notifies every registered listener.
     */
    fun setExpansion(fraction: Float, tracking: Boolean) {
        val coerced = fraction.coerceIn(0f, 1f)
        state = ShadeExpansionState(
            fraction = coerced,
            expanded = coerced > 0f,
            tracking = tracking
        )
        listeners.forEach { it.onPanelExpansionChanged(state) }
    }
}
