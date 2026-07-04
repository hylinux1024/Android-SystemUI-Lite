package com.android.systemui.lite.navigation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.android.systemui.lite.model.GestureSession
import com.android.systemui.lite.model.GestureState
import com.android.systemui.lite.model.GestureType
import com.android.systemui.lite.model.TouchZone
import kotlin.math.min

private val AffordanceSpec: SpringSpec<Float> =
    spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy)

/** Target geometric values for the pill keyed off [GestureState]. All sizes in px. */
private data class PanelTargets(
    val arrowLengthPx: Float,
    val bgWidthPx: Float,
    val bgAlpha: Float,
    val cornerPx: Float,
)

private fun targetsFor(state: GestureState, progress: Float): PanelTargets = when (state) {
    GestureState.GONE -> PanelTargets(0f, 0f, 0f, 0f)
    GestureState.ENTRY -> PanelTargets(arrowLengthPx = 8f, bgWidthPx = 28f, bgAlpha = 0.35f, cornerPx = 12f)
    GestureState.ACTIVE -> PanelTargets(
        arrowLengthPx = 8f + 16f * progress,
        bgWidthPx = 56f * (0.5f + 0.5f * progress),
        bgAlpha = 0.7f,
        cornerPx = 18f,
    )
    GestureState.INACTIVE -> PanelTargets(arrowLengthPx = 12f, bgWidthPx = 32f, bgAlpha = 0.3f, cornerPx = 12f)
    GestureState.COMMITTED -> PanelTargets(arrowLengthPx = 24f, bgWidthPx = 72f, bgAlpha = 0f, cornerPx = 24f)
    GestureState.CANCELLED -> PanelTargets(arrowLengthPx = 6f, bgWidthPx = 26f, bgAlpha = 0f, cornerPx = 10f)
}

/**
 * Stock-Android-style back-gesture affordance: an inward-pointing chevron pill rendered at the
 * edge the drag began (left edge → arrow points right, right edge → arrow points left). Reads its
 * geometry from the supplied [GestureSession]; renders nothing when the session is null or
 * [GestureState.GONE] so the overlay does not consume touches.
 *
 * The composable is self-contained — it only draws from [session]/[gestureType] and never
 * manipulates windows or lifetimes. Spring animation drives every visible attribute with distinct
 * target values for each [GestureState].
 */
@Composable
fun GestureEdgePanel(
    session: GestureSession?,
    gestureType: GestureType?,
    modifier: Modifier = Modifier,
) {
    // No affordance for missing / inactive / non-edge sessions — leave zero-size so we don't
    // intercept touches outside the gesture strip.
    if (session == null ||
        session.state == GestureState.GONE ||
        session.zone == TouchZone.NONE ||
        (session.zone != TouchZone.LEFT_EDGE && session.zone != TouchZone.RIGHT_EDGE)
    ) {
        return
    }

    val isLeft = session.zone == TouchZone.LEFT_EDGE
    val targets = targetsFor(session.state, session.progress)

    val arrowLengthPx by animateFloatAsState(targets.arrowLengthPx, AffordanceSpec, label = "edge_arrow")
    val bgWidthPx by animateFloatAsState(targets.bgWidthPx, AffordanceSpec, label = "edge_bg_width")
    val bgAlpha by animateFloatAsState(targets.bgAlpha, AffordanceSpec, label = "edge_bg_alpha")
    val cornerPx by animateFloatAsState(targets.cornerPx, AffordanceSpec, label = "edge_corner")

    val colorScheme = MaterialTheme.colorScheme
    val bg = colorScheme.surfaceVariant.copy(alpha = bgAlpha)
    val fg = colorScheme.onSurfaceVariant

    Canvas(modifier.fillMaxSize()) {
        if (bgAlpha < 0.002f && arrowLengthPx < 0.5f) return@Canvas

        val canvasW = size.width
        val canvasH = size.height
        val pillW = bgWidthPx.coerceAtLeast(1f)
        val pillH = (pillW * 2.5f).coerceAtLeast(64f)
        val pillLeft = if (isLeft) 0f else canvasW - pillW
        // Track the finger's y position reported at gesture start, nudged so the pill sits just
        // under the dragging finger (matches stock AOS back-gesture pill position).
        val centerY = (session.startY - 8f).coerceIn(pillH / 2f, canvasH - pillH / 2f)
        val pillTop = centerY - pillH / 2f

        if (bgAlpha >= 0.002f) {
            drawRoundRect(
                color = bg,
                topLeft = Offset(pillLeft, pillTop),
                size = Size(pillW, pillH),
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                style = Fill,
            )
        }

        // Chevron: inward-pointing arrow. Left edge → points right; right edge → points left.
        val arrow = Path().apply {
            val half = arrowLengthPx / 2f
            if (isLeft) {
                val x0 = pillLeft + pillW * 0.30f
                moveTo(x0, centerY - half)
                lineTo(x0 + arrowLengthPx * 0.9f, centerY)
                lineTo(x0, centerY + half)
            } else {
                val x0 = pillLeft + pillW * 0.70f
                moveTo(x0, centerY - half)
                lineTo(x0 - arrowLengthPx * 0.9f, centerY)
                lineTo(x0, centerY + half)
            }
        }
        drawPath(
            arrow,
            color = fg.copy(alpha = min(bgAlpha * 2.0f, 1f)),
            style = Stroke(width = 4f, cap = StrokeCap.Round),
        )
    }
}
