package com.android.systemui.qs

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.android.systemui.R

abstract class QSTileBase(protected val context: Context) {

    companion object {
        private const val ANIM_DURATION = 300L
    }

    protected var isActive = false
    protected var tileView: View? = null
    protected var iconView: ImageView? = null
    protected var labelView: TextView? = null
    private var backgroundDrawable: Drawable? = null
    private var colorAnimator: ValueAnimator? = null

    private val inactiveBgColor = 0x1AFFFFFF.toInt()
    private val activeBgColor = 0xFF1A73E8.toInt()
    private val inactiveLabelColor = 0xDEFFFFFF.toInt()
    private val activeLabelColor = 0xFFFFFFFF.toInt()

    abstract fun getLabel(): String
    abstract fun getIconResId(): Int
    abstract fun getActiveIconResId(): Int
    abstract fun toggle()
    abstract fun isActiveState(): Boolean

    fun bindView(view: View) {
        tileView = view
        iconView = view.findViewById(R.id.tile_icon)
        labelView = view.findViewById(R.id.tile_label)

        val bg = view.background
        if (bg is RippleDrawable) {
            backgroundDrawable = bg.findDrawableByLayerId(android.R.id.background)
        }

        labelView?.text = getLabel()
        isActive = isActiveState()
        applyState(animate = false)

        tileView?.setOnClickListener {
            toggle()
            isActive = isActiveState()
            applyState(animate = true)
        }
    }

    fun refreshState() {
        isActive = isActiveState()
        applyState(animate = true)
    }

    private fun applyState(animate: Boolean) {
        if (animate) {
            animateColorState()
        } else {
            setColorsImmediate()
        }
        iconView?.setImageResource(if (isActive) getActiveIconResId() else getIconResId())
    }

    private fun setColorsImmediate() {
        val targetBg = if (isActive) activeBgColor else inactiveBgColor
        val targetLabel = if (isActive) activeLabelColor else inactiveLabelColor
        backgroundDrawable?.setTint(targetBg)
        labelView?.setTextColor(targetLabel)
    }

    private fun animateColorState() {
        colorAnimator?.cancel()

        val fromBg = backgroundDrawable?.let { getTintColor(it) } ?: inactiveBgColor
        val fromLabel = labelView?.currentTextColor ?: inactiveLabelColor
        val toBg = if (isActive) activeBgColor else inactiveBgColor
        val toLabel = if (isActive) activeLabelColor else inactiveLabelColor

        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIM_DURATION
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                val bgColor = lerpColor(fromBg, toBg, fraction)
                val labelColor = lerpColor(fromLabel, toLabel, fraction)
                backgroundDrawable?.setTint(bgColor)
                labelView?.setTextColor(labelColor)
            }
            start()
        }
    }

    private fun getTintColor(drawable: Drawable): Int {
        return try {
            val field = drawable.javaClass.getDeclaredField("mTint")
            field.isAccessible = true
            (field.get(drawable) as? android.content.res.ColorStateList)?.defaultColor ?: inactiveBgColor
        } catch (e: Exception) {
            inactiveBgColor
        }
    }

    private fun lerpColor(from: Int, to: Int, fraction: Float): Int {
        val a = android.graphics.Color.alpha(from) + ((android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * fraction).toInt()
        val r = android.graphics.Color.red(from) + ((android.graphics.Color.red(to) - android.graphics.Color.red(from)) * fraction).toInt()
        val g = android.graphics.Color.green(from) + ((android.graphics.Color.green(to) - android.graphics.Color.green(from)) * fraction).toInt()
        val b = android.graphics.Color.blue(from) + ((android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * fraction).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }

    open fun destroy() {
        tileView = null
        iconView = null
        labelView = null
        backgroundDrawable = null
        colorAnimator?.cancel()
    }
}
