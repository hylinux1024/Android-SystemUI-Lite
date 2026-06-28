package com.android.systemui.lite.systemui.core

interface ShadeController {
    fun toggleShade()
    fun dragShade(progress: Float)
    fun flingShade(target: Float)
}
