package com.compositioncoach.app.ui.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.OnScrim
import kotlinx.coroutines.delay

private const val FADE_IN_MS = 150
private const val VISIBLE_MS = 800L

/**
 * One tap-to-focus ring: appears at [tapOffset] (fades in over [FADE_IN_MS], per the spec), holds, then
 * fades out after [VISIBLE_MS] total. [tapOffset] is a fresh value (and a fresh key, see [CameraScreen])
 * each tap, so retapping the same spot restarts the animation rather than being a no-op.
 */
@Composable
fun FocusRingOverlay(tapOffset: Offset, modifier: Modifier = Modifier) {
    var visible by remember(tapOffset) { mutableStateOf(false) }
    LaunchedEffect(tapOffset) {
        visible = true
        delay(VISIBLE_MS)
        visible = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(FADE_IN_MS),
        label = "focusRingAlpha",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        if (alpha <= 0f) return@Canvas
        val radius = 32.dp.toPx()
        drawCircle(
            color = OnScrim.copy(alpha = 0.9f * alpha),
            radius = radius,
            center = tapOffset,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

@Preview(name = "Focus ring", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun FocusRingOverlayPreview() {
    CompositionCoachTheme {
        FocusRingOverlay(tapOffset = Offset(120f, 120f))
    }
}
