package com.android.systemui.lite.systemui

interface SystemUIComponent {
    val name: String
    fun start()
    fun stop()
}
