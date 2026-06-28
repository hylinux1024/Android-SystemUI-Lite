package com.android.systemui.lite.systemui

interface CoreStartable {
    fun start()
    fun onBootCompleted() {}
    fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
    fun stop() {}
}
