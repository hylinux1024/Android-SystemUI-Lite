package com.android.systemui.lite.core

interface ShadeController {
    fun toggleShade()
    fun dragShade(progress: Float)
    fun flingShade(target: Float)
}
