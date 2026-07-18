package com.android.systemui.statusbar.shade

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.FrameLayout
import com.android.systemui.R

/**
 * Root view of the notification panel (the expanded shade).
 *
 * Mirrors AOSP `NotificationPanelView`: a translucent scrim-style background
 * whose color adapts to the wallpaper (dark tint on light wallpapers, light
 * tint on dark wallpapers) — the same way SystemUI's panel scrim blends with
 * the wallpaper behind it. The overall alpha is additionally driven by
 * [panelAlpha] during expand/collapse animations.
 */
class NotificationPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val layerPaint = Paint()
    private var backgroundPaint = Paint()

    init {
        // Will be overwritten as soon as the controller registers a wallpaper
        // listener; default to a dark tint so the panel is never transparent.
        setBackgroundColor(context.getColor(R.color.notification_panel_background_dark))
    }

    /**
     * Update the panel background tint based on whether the wallpaper is dark.
     * [isDark] = true  → wallpaper is dark  → panel uses a light tint so content stands out.
     * [isDark] = false → wallpaper is light → panel uses a dark tint (scrim look).
     */
    fun applyBackgroundForWallpaper(isDark: Boolean) {
        val color = if (isDark) {
            context.getColor(R.color.notification_panel_background_light)
        } else {
            context.getColor(R.color.notification_panel_background_dark)
        }
        setBackgroundColor(color)
    }

    /**
     * Overall panel alpha (0f = fully transparent, 1f = fully opaque).
     * Used by the controller during expand/collapse animations.
     */
    var panelAlpha: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun draw(canvas: Canvas) {
        if (panelAlpha < 1f) {
            layerPaint.alpha = (panelAlpha * 255).toInt().coerceIn(0, 255)
            canvas.saveLayer(null, layerPaint)
            super.draw(canvas)
            canvas.restore()
        } else {
            super.draw(canvas)
        }
    }
}
