package com.android.systemui.lite

interface CoreStartable {
    fun start()
    fun onBootCompleted() {}
    fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
    fun stop() {}
}
