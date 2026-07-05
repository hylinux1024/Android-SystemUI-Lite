package com.android.systemui.lite.core

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import kotlinx.coroutines.flow.StateFlow

interface ShadeController {
    /** Live shade-open fraction 0..1, mirrored from the internal [Animatable]. */
    val shadeProgress: StateFlow<Float>
    /** Animatable source of truth — compose tree reads this via [Animatable.asState]. */
    val shadeAnimatable: Animatable<Float, AnimationVector1D>
    fun toggleShade()
    /**
     * Immediate (unspringed) sync — used during drag so the panel tracks the finger with
     * zero latency; mirrors top-slide-drawer's `drawerOffsetY.snapTo`.
     */
    fun snapShade(progress: Float)
    /** Animate to [target] with a spring; used for fling / tap / release snap. */
    fun flingShade(target: Float)
}
