package com.android.systemui.navigationbar.gestural

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.PathInterpolator
import com.android.systemui.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Visual feedback for an in-progress "edge swipe to go back" gesture.
 *
 * Faithfully mirrors the look & feel of AOSP `NavigationBarEdgePanel` in a
 * dependency-free form: it draws a single chevron arrow anchored to the
 * screen edge and reacts to the live gesture progress by changing its
 * extension, angle and alpha. The arrow is driven by [EdgeBackGestureHandler]
 * forwarding device-coordinate MotionEvents.
 *
 * Rendering uses [ValueAnimator] + [PathInterpolator] (matching the rest of
 * SystemUI-Lite, e.g. NotificationPanelViewController) instead of
 * androidx.dynamicanimation (which this project does not depend on).
 *
 * This view lives in a NOT_TOUCHABLE overlay window — touch is captured
 * upstream by an [android.hardware.input.InputMonitor]; this view is purely
 * decorative and never receives touches itself.
 */
class EdgeBackPanelView(context: Context) : View(context) {

    companion object {
        // Geometry constants — match AOSP NavigationBarEdgePanel defaults (dp).
        private const val BASE_TRANSLATION_DP = 32f
        private const val ARROW_LENGTH_DP = 18f
        private const val ARROW_THICKNESS_DP = 2.5f
        private const val ARROW_EXTENDED_ANGLE_DEGS = 56f

        private const val SHOW_DURATION_MS = 120L
        private const val HIDE_DURATION_MS = 100L
    }

    private val density = resources.displayMetrics.density
    private val baseTranslation = BASE_TRANSLATION_DP * density
    private val arrowLength = ARROW_LENGTH_DP * density
    private val arrowThickness = ARROW_THICKNESS_DP * density

    private val arrowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = arrowThickness
    }
    private val protectionPaint = Paint(arrowPaint).apply {
        // A faint outline so the arrow stays readable over busy backgrounds.
        strokeWidth = arrowThickness + 2f
    }

    private val arrowPath = Path()

    private var isLeftPanel = true
    private var isDark = false
    private var arrowColor = Color.WHITE
    private var protectionColor = Color.BLACK

    /** Current chevron draw state, all in [0,1]-ish ranges. */
    private var mCurrentExtension = 0f   // 0 = collapsed against edge, 1 = fully extended
    private var mCurrentAngle = 90f      // 90 = parallel to edge (no gesture), 56 = extended
    private var mVerticalOffset = 0f     // follows finger Y (view-local px)
    private var mFingerX = 0f            // follows finger X (view-local px)
    private var mAlpha = 0f

    private var showAnimator: ValueAnimator? = null
    private var hideAnimator: ValueAnimator? = null
    private val showInterpolator = PathInterpolator(0.4f, 0f, 0f, 1f)
    private val hideInterpolator = PathInterpolator(0.33f, 0f, 0.67f, 1f)

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        visibility = GONE
        applyColors()
    }

    /** Called by the handler on ACTION_DOWN to pick a side and anchor vertically. */
    fun resetForGesture(isLeft: Boolean, fingerX: Float, fingerY: Float) {
        isLeftPanel = isLeft
        mFingerX = fingerX
        mVerticalOffset = fingerY
        mCurrentExtension = 0f
        mCurrentAngle = 90f
        mAlpha = 0f
        cancelAnimators()
        visibility = VISIBLE
        invalidate()
    }

    /** Forward the live gesture. [progress] in [0,1], finger coords in view-local px. */
    fun updateGesture(progress: Float, fingerX: Float, fingerY: Float) {
        val p = progress.coerceIn(0f, 1f)
        mCurrentExtension = p
        // Interpolate angle from 90° (resting) to the extended angle as the drag grows.
        mCurrentAngle = 90f - (90f - ARROW_EXTENDED_ANGLE_DEGS) * p
        mFingerX = fingerX
        mVerticalOffset = fingerY
        mAlpha = 0.35f + 0.65f * p
        invalidate()
    }

    /** Animate the arrow in (gesture just crossed the threshold). */
    fun showArrow() {
        cancelAnimators()
        showAnimator = ValueAnimator.ofFloat(mAlpha, 1f).apply {
            duration = SHOW_DURATION_MS
            interpolator = showInterpolator
            addUpdateListener {
                mAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Animate the arrow out (gesture released or cancelled). */
    fun hideArrow() {
        cancelAnimators()
        hideAnimator = ValueAnimator.ofFloat(mAlpha, 0f).apply {
            duration = HIDE_DURATION_MS
            interpolator = hideInterpolator
            addUpdateListener {
                mAlpha = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    visibility = GONE
                }
            })
            start()
        }
    }

    private fun cancelAnimators() {
        showAnimator?.cancel()
        hideAnimator?.cancel()
    }

    /** Adapt arrow/protect colours to the underlying wallpaper luminance. */
    fun setDarkMode(dark: Boolean) {
        isDark = dark
        applyColors()
        invalidate()
    }

    private fun applyColors() {
        arrowColor = if (isDark) Color.WHITE else Color.parseColor("#222831")
        protectionColor = if (isDark) Color.parseColor("#222831") else Color.WHITE
        arrowPaint.color = arrowColor
        protectionPaint.color = protectionColor
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mAlpha <= 0f) return

        // Anchor the chevron to its edge and let it follow the finger's X with a
        // rubber-banded cap (AOSP behaviour): it never travels more than ~half the
        // screen width from the edge regardless of how far the finger drags.
        // "reach" is the distance travelled INWARD from the anchored edge, so for
        // the right edge it measures the drag as (width - fingerX), not fingerX.
        val maxReach = width * 0.5f
        val cx = if (isLeftPanel) {
            val reach = (mFingerX * mCurrentExtension).coerceIn(0f, maxReach)
            reach - arrowThickness / 2f
        } else {
            val reach = ((width - mFingerX) * mCurrentExtension).coerceIn(0f, maxReach)
            width - reach + arrowThickness / 2f
        }
        val cy = mVerticalOffset.coerceIn(height * 0.25f, height * 0.75f)

        canvas.save()
        canvas.translate(cx, cy)

        val radians = Math.toRadians(mCurrentAngle.toDouble())
        val tipX = (cos(radians) * arrowLength).toFloat()
        val tipY = (sin(radians) * arrowLength).toFloat()

        // Chevron pointing inward (into the screen), mirrored for the right edge.
        val x = if (isLeftPanel) tipX else -tipX
        val extent = 1f - 0.25f * (1f - mCurrentExtension)
        val ex = x * extent
        val ey = tipY * extent

        arrowPath.reset()
        arrowPath.moveTo(ex, ey)
        arrowPath.lineTo(0f, 0f)
        arrowPath.lineTo(ex, -ey)

        canvas.drawColor(Color.TRANSPARENT)
        protectionPaint.alpha = (mAlpha * 255).toInt()
        arrowPaint.alpha = (mAlpha * 255).toInt()
        canvas.drawPath(arrowPath, protectionPaint)
        canvas.drawPath(arrowPath, arrowPaint)
        canvas.restore()
    }
}
