package com.android.systemui.notification

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.android.systemui.CoreStartable
import com.android.systemui.R
import com.android.systemui.qs.QSPanelController
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationShadeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val windowManager: WindowManager,
    private val qsPanelController: QSPanelController
) : CoreStartable {

    companion object {
        private const val TAG = "NotifShadeManager"
        private const val FLING_COLLAPSE_MIN_DURATION = 250L
        private const val SPRING_BACK_DURATION = 400L
        private const val HIGH_VELOCITY_PX_PER_SECOND = 8000f
        private const val FACTOR_OF_HIGH_VELOCITY_FOR_MAX_OVERSHOOT = 0.5f
    }

    private val handler = Handler(Looper.getMainLooper())
    private var shadeView: NotificationShadeView? = null
    private var contentContainer: View? = null
    private var scrimBehind: View? = null
    private var isExpanded = false
    private var isAnimating = false
    private var isTracking = false
    private var isClosing = false
    private var currentExpansionFraction = 0f
    private var expandedHeight = 0f
    private var maxPanelHeight = 0f
    private val notifications = mutableListOf<NotificationEntry>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var heightAnimator: ValueAnimator? = null

    // Touch tracking state (matches AOSP startExpandMotion variables)
    private var initialExpandY = 0f
    private var initialExpandX = 0f
    private var initialOffsetOnTouch = 0f
    private var touchSlopExceeded = false
    private var gestureWaitForTouchSlop = true
    private var panelClosedOnDown = false
    private var interceptInitialY = 0f
    private var interceptInitialX = 0f

    // Velocity tracking
    private val velocityTracker: VelocityTracker = VelocityTracker.obtain()
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity: Float = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val panelOvershootAmount: Float by lazy {
        context.resources.getDimension(R.dimen.panel_overshoot_amount)
    }

    private val fastOutSlowIn = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    data class NotificationEntry(
        val packageName: String,
        val title: String,
        val content: String,
        val icon: android.graphics.drawable.Drawable? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    override fun start() {
        Log.i(TAG, "Starting notification shade manager")
    }

    override fun stop() {
        Log.i(TAG, "Stopping notification shade manager")
        collapseShade()
    }

    fun addNotification(entry: NotificationEntry) {
        notifications.add(0, entry)
        if (isExpanded) updateNotificationList()
    }

    fun removeNotification(packageName: String) {
        notifications.removeAll { it.packageName == packageName }
        if (isExpanded) updateNotificationList()
    }

    fun expandShade() {
        if (isExpanded && !isAnimating) return
        if (shadeView == null) createShadeView()
        cancelAnimator()
        isExpanded = true
        isClosing = false
        flingToHeight(0f, true, maxPanelHeight, 1f)
    }

    fun collapseShade() {
        if (!isExpanded || isAnimating) return
        cancelAnimator()
        isExpanded = false
        isClosing = true
        shadeView?.isFocusable = false
        shadeView?.isFocusableInTouchMode = false
        flingToHeight(0f, false, 0f, 1f)
    }

    fun toggleShade() {
        if (isExpanded) collapseShade() else expandShade()
    }

    fun isShowing(): Boolean = isExpanded

    fun createShadeIfNeeded() {
        if (shadeView == null) createShadeView()
    }

    // ==== Status bar external touch (matches AOSP handleExternalTouch) ====

    fun handleExternalTouch(rawX: Float, rawY: Float, action: Int) {
        createShadeIfNeeded()
        val now = System.currentTimeMillis()
        val event = MotionEvent.obtain(now, now, action, rawX, rawY, 0)
        handleTouchEvent(event)
        event.recycle()
    }

    // ==== Shade touch handling (called from NotificationShadeView) ====

    fun onInterceptShadeTouch(event: MotionEvent): Boolean {
        if (!isExpanded || isTracking) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interceptInitialX = event.x
                interceptInitialY = event.y
                initialExpandX = event.x
                initialExpandY = event.y
                touchSlopExceeded = false
                addMovement(event)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val y = event.y
                val x = event.x
                val h = y - interceptInitialY
                addMovement(event)

                if (canCollapsePanelOnTouch()) {
                    val hAbs = Math.abs(h)
                    val dxAbs = Math.abs(x - interceptInitialX)
                    if (h < -touchSlop && hAbs > dxAbs) {
                        cancelAnimator()
                        startExpandMotion(x, y, true, expandedHeight)
                        Log.d(TAG, "onInterceptShadeTouch: intercepting upward swipe")
                        return true
                    }
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker.clear()
                return false
            }
        }
        return false
    }

    fun onShadeTouch(event: MotionEvent): Boolean {
        if (!isExpanded && !isTracking) return false
        return handleTouchEvent(event)
    }

    // ==== Internal touch handling (matches AOSP TouchHandler.handleTouch) ====

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
            gestureWaitForTouchSlop = isFullyCollapsed()
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startExpandMotion(x, y, false, expandedHeight)
                panelClosedOnDown = isFullyCollapsed()
                addMovement(event)
                if (!gestureWaitForTouchSlop) {
                    touchSlopExceeded = true
                    cancelAnimator()
                    onTrackingStarted()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                addMovement(event)
                val h = y - initialExpandY

                if (Math.abs(h) > touchSlop && Math.abs(h) > Math.abs(x - initialExpandX)) {
                    touchSlopExceeded = true
                    if (gestureWaitForTouchSlop && !isTracking) {
                        if (initialOffsetOnTouch != 0f) {
                            startExpandMotion(x, y, false, expandedHeight)
                        }
                        cancelAnimator()
                        onTrackingStarted()
                    }
                }
                val newHeight = Math.max(0f, h + initialOffsetOnTouch)
                if (isTracking) {
                    setExpandedHeightInternal(newHeight)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                addMovement(event)
                endMotionEvent(event, x, y)
            }
        }
        return isTracking
    }

    // ==== Core expansion tracking (matches AOSP setExpandedHeightInternal) ====

    private fun setExpandedHeightInternal(h: Float) {
        expandedHeight = Math.min(h, maxPanelHeight)

        if (isClosing && expandedHeight < 1f && expandedHeight != 0f) {
            expandedHeight = 0f
            heightAnimator?.end()
        }

        currentExpansionFraction = Math.min(1f, if (maxPanelHeight == 0f) 0f else expandedHeight / maxPanelHeight)
        applyExpansionToViews()
    }

    private fun applyExpansionToViews() {
        val view = shadeView ?: return
        val container = contentContainer ?: return
        val scrim = scrimBehind ?: return
        val fraction = currentExpansionFraction.coerceIn(0f, 1f)

        // Content slides up from below
        container.translationY = -maxPanelHeight * (1f - fraction)

        // Full view alpha: fades in during first 30% of expansion
        view.alpha = fraction.coerceIn(0f, 1f)

        // Scrim alpha: evenly follows fraction up to 0.7 final alpha
        scrim.alpha = (fraction * 0.7f).coerceIn(0f, 0.7f)

        // Update visibility state
        if (fraction <= 0f) {
            if (!isAnimating && !isTracking) {
                isExpanded = false
                handler.post { removeShadeView() }
            }
        } else if (fraction > 0f && !isExpanded) {
            isExpanded = true
        }
    }

    // ==== Fling decision (matches AOSP flingExpands) ====

    private fun flingExpands(vel: Float, vectorVel: Float): Boolean {
        if (Math.abs(vectorVel) < minFlingVelocity) {
            return currentExpansionFraction > 0.5f
        }
        return vel > 0
    }

    // ==== Fling animation (matches AOSP flingToHeight) ====

    private fun flingToHeight(vel: Float, expand: Boolean, target: Float, collapseSpeedUpFactor: Float) {
        if (target == expandedHeight && heightAnimator == null) {
            onFlingEnd(false)
            return
        }

        val addOverscroll = expand && vel >= 0
        val overshootAmount = if (addOverscroll) {
            Math.min(1f, Math.max(0.2f, vel / (HIGH_VELOCITY_PX_PER_SECOND * FACTOR_OF_HIGH_VELOCITY_FOR_MAX_OVERSHOOT)))
        } else {
            0f
        }

        val actualTarget = target + overshootAmount * panelOvershootAmount
        val animator = ValueAnimator.ofFloat(expandedHeight, actualTarget)

        if (expand) {
            if (vel == 0f) {
                animator.duration = 350L
                animator.interpolator = DecelerateInterpolator()
            } else {
                val deceleration = Math.abs(vel) / 0.35f
                val durationMs = (Math.abs(actualTarget - expandedHeight) / deceleration * 1000f).toLong()
                animator.duration = durationMs.coerceIn(200L, 500L)
                animator.interpolator = DecelerateInterpolator(1.5f)
            }
        } else {
            if (vel == 0f) {
                animator.duration = FLING_COLLAPSE_MIN_DURATION
                animator.interpolator = DecelerateInterpolator(1.5f)
            } else {
                val deceleration = Math.abs(vel) / 0.35f
                val durationMs = (Math.abs(actualTarget - expandedHeight) / deceleration * 1000f).toLong()
                animator.duration = durationMs.coerceIn(150L, 400L)
                animator.interpolator = DecelerateInterpolator(1.5f)
            }
        }

        animator.addUpdateListener { animation ->
            val animatedValue = animation.animatedValue as Float
            expandedHeight = animatedValue
            currentExpansionFraction = Math.min(1f, if (maxPanelHeight == 0f) 0f else animatedValue / maxPanelHeight)
            applyExpansionToViews()
        }

        var cancelled = false
        animator.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {
                isAnimating = true
            }
            override fun onAnimationEnd(animation: android.animation.Animator) {
                isAnimating = false
                if (!cancelled && overshootAmount > 0f) {
                    springBack()
                } else {
                    onFlingEnd(cancelled)
                }
            }
            override fun onAnimationCancel(animation: android.animation.Animator) {
                isAnimating = false
                cancelled = true
            }
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })

        startAnimator(animator)
    }

    // ==== Spring back after fling overshoot ====

    private fun springBack() {
        val from = currentExpansionFraction
        if (from <= 1f) {
            onFlingEnd(false)
            return
        }

        val target = 1f
        val targetHeight = maxPanelHeight
        val animator = ValueAnimator.ofFloat(expandedHeight, targetHeight).apply {
            duration = SPRING_BACK_DURATION
            interpolator = fastOutSlowIn
            addUpdateListener { animation ->
                expandedHeight = animation.animatedValue as Float
                currentExpansionFraction = Math.min(target, expandedHeight / maxPanelHeight)
                applyExpansionToViews()
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) {}
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    expandedHeight = targetHeight
                    currentExpansionFraction = target
                    applyExpansionToViews()
                    onFlingEnd(false)
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    onFlingEnd(true)
                }
                override fun onAnimationRepeat(animation: android.animation.Animator) {}
            })
        }
        startAnimator(animator)
    }

    private fun onFlingEnd(cancelled: Boolean) {
        if (!cancelled && currentExpansionFraction <= 0f) {
            removeShadeView()
        }
        if (!cancelled && currentExpansionFraction >= 1f) {
            isExpanded = true
            isClosing = false
        }
        heightAnimator = null
    }

    // ==== Touch state helpers ====

    private fun startExpandMotion(newX: Float, newY: Float, startTracking: Boolean, currentHeight: Float) {
        initialOffsetOnTouch = currentHeight
        if (!isTracking || isFullyCollapsed()) {
            initialExpandY = newY
            initialExpandX = newX
        }
        if (startTracking) {
            touchSlopExceeded = true
            onTrackingStarted()
        }
    }

    private fun endMotionEvent(event: MotionEvent, x: Float, y: Float) {
        if ((isTracking && touchSlopExceeded)
            || Math.abs(x - initialExpandX) > touchSlop
            || Math.abs(y - initialExpandY) > touchSlop
            || (!isFullyExpanded() && !isFullyCollapsed())
            || event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            velocityTracker.computeCurrentVelocity(1000)
            val vel = velocityTracker.getYVelocity()
            val xVel = velocityTracker.getXVelocity()
            val vectorVel = Math.hypot(xVel.toDouble(), vel.toDouble()).toFloat()

            val expand: Boolean
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                expand = !panelClosedOnDown
            } else {
                expand = flingExpands(vel, vectorVel)
            }

            isTracking = false
            velocityTracker.clear()

            Log.d(TAG, "endMotionEvent: fraction=${currentExpansionFraction}, vel=$vel, vectorVel=$vectorVel, expand=$expand")

            if (expand) {
                isExpanded = true
                isClosing = false
                flingToHeight(vel, true, maxPanelHeight, 1f)
            } else {
                isExpanded = false
                isClosing = true
                shadeView?.isFocusable = false
                shadeView?.isFocusableInTouchMode = false
                flingToHeight(vel, false, 0f, 1f)
            }
        } else {
            isTracking = false
            velocityTracker.clear()
        }
    }

    private fun onTrackingStarted() {
        isClosing = false
        isTracking = true
    }

    private fun canCollapsePanelOnTouch(): Boolean {
        val scrollView = shadeView?.findViewById<ScrollView>(R.id.notification_stack_scroll_layout)
        return scrollView == null || scrollView.scrollY <= 0
    }

    private fun isFullyCollapsed(): Boolean = currentExpansionFraction <= 0f
    private fun isFullyExpanded(): Boolean = currentExpansionFraction >= 1f

    // ==== Velocity tracking (matches AOSP addMovement) ====

    private fun addMovement(event: MotionEvent) {
        val deltaX = event.rawX - event.x
        val deltaY = event.rawY - event.y
        event.offsetLocation(deltaX, deltaY)
        velocityTracker.addMovement(event)
        event.offsetLocation(-deltaX, -deltaY)
    }

    // ==== Animation helpers ====

    private fun startAnimator(animator: ValueAnimator) {
        cancelAnimator()
        isAnimating = true
        heightAnimator = animator
        animator.start()
    }

    private fun cancelAnimator() {
        heightAnimator?.let {
            it.removeAllListeners()
            it.cancel()
        }
        heightAnimator = null
        isAnimating = false
    }

    // ==== Shade view lifecycle ====

    private fun createShadeView() {
        removeShadeView()

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.notification_panel, null) as NotificationShadeView
        shadeView = view

        contentContainer = view.findViewById(R.id.notification_container_parent)
        scrimBehind = view.findViewById(R.id.scrim_behind)

        val qsTileGrid = view.findViewById<GridLayout>(R.id.qs_tile_grid)
        if (qsTileGrid != null) qsPanelController.setupTiles(qsTileGrid)

        val brightnessSlider = view.findViewById<SeekBar>(R.id.brightness_slider)
        if (brightnessSlider != null) qsPanelController.setupBrightnessSlider(brightnessSlider)

        // Wire touch handlers
        view.interceptHandler = { onInterceptShadeTouch(it) }
        view.touchHandler = { onShadeTouch(it) }

        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_UP) {
                collapseShade()
                true
            } else false
        }

        // Compute maxPanelHeight from screen metrics
        val screenHeight = context.resources.displayMetrics.heightPixels.toFloat()
        val statusBarHeight = getStatusBarHeight().toFloat()
        maxPanelHeight = screenHeight - statusBarHeight
        Log.d(TAG, "maxPanelHeight=$maxPanelHeight (screen=$screenHeight, sb=$statusBarHeight)")

        view.alpha = 0f
        contentContainer?.translationY = -maxPanelHeight
        currentExpansionFraction = 0f
        expandedHeight = 0f

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }

        try {
            windowManager.addView(view, layoutParams)
            updateNotificationList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add shade view", e)
            isExpanded = false
        }
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            (24 * context.resources.displayMetrics.density).toInt()
        }
    }

    private fun removeShadeView() {
        cancelAnimator()
        shadeView?.let {
            it.setOnTouchListener(null)
            it.setOnKeyListener(null)
            it.interceptHandler = null
            it.touchHandler = null
            try { windowManager.removeView(it) } catch (e: Exception) {
                Log.e(TAG, "Failed to remove shade view", e)
            }
        }
        shadeView = null
        contentContainer = null
        scrimBehind = null
        currentExpansionFraction = 0f
        expandedHeight = 0f
        maxPanelHeight = 0f
        isExpanded = false
        isTracking = false
        isClosing = false
        velocityTracker.clear()
    }

    // ==== Notification list ====

    private fun updateNotificationList() {
        val stack = shadeView?.findViewById<ScrollView>(R.id.notification_stack_scroll_layout)
            ?.findViewById<ViewGroup>(R.id.notification_stack) ?: return

        stack.removeAllViews()
        val inflater = LayoutInflater.from(context)
        for (entry in notifications) {
            val row = inflater.inflate(R.layout.notification_row, stack, false)
            row.findViewById<TextView>(R.id.title_text)?.text = entry.title
            row.findViewById<TextView>(R.id.content_text)?.text = entry.content
            row.findViewById<TextView>(R.id.time_text)?.text = timeFormat.format(Date(entry.timestamp))
            val appIcon = row.findViewById<ImageView>(R.id.app_icon)
            if (entry.icon != null) appIcon?.setImageDrawable(entry.icon)
            val clearBtn = row.findViewById<ImageView>(R.id.clear_button)
            clearBtn?.setOnClickListener {
                notifications.removeAll { it.packageName == entry.packageName }
                updateNotificationList()
            }
            stack.addView(row)
        }
    }
}
