package com.android.systemui.tuner

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class TunerActivity : Activity() {

    companion object {
        private const val TAG = "TunerActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "TunerActivity onCreate")

        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "System UI Tuner"
            textSize = 24f
            setPadding(0, 0, 0, 32)
        }
        container.addView(title)

        val components = listOf(
            "Status Bar" to true,
            "Navigation Bar" to true,
            "Notification Shade" to true,
            "Quick Settings" to true
        )

        for ((name, enabled) in components) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 16, 0, 16)
            }

            val label = TextView(this).apply {
                text = name
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)

            val toggle = Switch(this).apply {
                isChecked = enabled
                setOnCheckedChangeListener { _, isChecked ->
                    Log.i(TAG, "$name ${if (isChecked) "enabled" else "disabled"}")
                }
            }
            row.addView(toggle)

            container.addView(row)
        }

        scrollView.addView(container)
        setContentView(scrollView)
    }
}
