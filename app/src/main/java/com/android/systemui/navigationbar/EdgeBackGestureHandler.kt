package com.android.systemui.navigationbar

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout

class EdgeBackGestureHandler(
    private val context: Context,
    private val windowManager: WindowManager
) {

    companion object {
        private const val TAG = "EdgeBackGestureHandler"
        private const val EDGE_SIZE_DP = 20
        private const val BACK_GESTURE_THRESHOLD = 0.4f
    }

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var edgeBackView: View? = null
    private var isTracking = false
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f

    fun start() {
        Log.i(TAG, "Starting edge back gesture handler")
        createEdgeBackView()
    }

    fun stop() {
        Log.i(TAG, "Stopping edge back gesture handler")
        removeEdgeBackView()
    }

    private fun createEdgeBackView() {
        val edgeSizePx = (EDGE_SIZE_DP * context.resources.displayMetrics.density).toInt()

        edgeBackView = object : FrameLayout(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                return handleTouchEvent(event)
            }
        }.apply {
            setBackgroundColor(0x01000000) // Nearly transparent
        }

        val layoutParams = WindowManager.LayoutParams(
            edgeSizePx,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START
        }

        try {
            windowManager.addView(edgeBackView, layoutParams)
            Log.i(TAG, "Edge back view added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add edge back view", e)
        }
    }

    private fun removeEdgeBackView() {
        edgeBackView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove edge back view", e)
            }
        }
        edgeBackView = null
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                lastX = event.x
                isTracking = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTracking) return false
                val deltaX = event.x - startX
                val deltaY = event.y - startY
                if (deltaX > touchSlop && Math.abs(deltaY) < Math.abs(deltaX)) {
                    lastX = event.x
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isTracking) return false
                isTracking = false
                val deltaX = event.x - startX
                if (deltaX > context.resources.displayMetrics.widthPixels * BACK_GESTURE_THRESHOLD) {
                    performBackAction()
                }
                return true
            }
        }
        return false
    }

    private fun performBackAction() {
        Log.d(TAG, "Back gesture detected")
        try {
            Runtime.getRuntime().exec("input keyevent KEYCODE_BACK")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform back action", e)
        }
    }
}
