package com.android.systemui.lite.core

import kotlinx.coroutines.flow.StateFlow

interface ShadeController {
    val shadeProgress: StateFlow<Float>
    fun toggleShade()
    fun dragShade(progress: Float)
    fun flingShade(target: Float)
}
