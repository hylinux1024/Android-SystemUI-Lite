package com.android.systemui.notification

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

class NotificationShadeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    var interceptHandler: ((MotionEvent) -> Boolean)? = null
    var touchHandler: ((MotionEvent) -> Boolean)? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (interceptHandler?.invoke(ev) == true) return true
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (touchHandler?.invoke(ev) == true) return true
        return super.onTouchEvent(ev)
    }
}
