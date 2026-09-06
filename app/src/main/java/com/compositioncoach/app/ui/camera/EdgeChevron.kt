package com.compositioncoach.app.ui.camera

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.OnScrim

/** The chevron's footprint, per `docs/COACHING_UI.md`. */
val CHEVRON_SIZE = 64.dp

/** The breathe: alpha 0.55 to 0.8 over 1.2s, ease-in-out. It never translates. */
private const val BREATHE_MIN_ALPHA = 0.55f
private const val BREATHE_MAX_ALPHA = 0.8f
private const val BREATHE_MS = 1_200

/**
 * The primary cue for a LEFT/RIGHT/UP/DOWN headline: a translucent chevron centred on the *physical* edge
 * that direction points at, pointing outward — "move the camera this way".
 *
 * [edge] is already the resolved *screen* edge (see [CueEdge.forDirection]), so this composable does no
 * rotation maths of its own: it draws a chevron pointing out of [edge] and its caller aligns it there. The
 * chevron is decorative (`clearAndSetSemantics {}` — no semantics at all); the chip beside it carries the
 * meaning for TalkBack, per the spec's accessibility rule.
 *
 * The breathe is the app's only looping animation, and it stops entirely when the system animator duration
 * scale is 0 ([LocalAnimationsEnabled]) — the chevron then sits at its mid alpha.
 */
@Composable
fun EdgeChevron(edge: CueEdge, animationsEnabled: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "chevronBreathe")
    val breathing by transition.animateFloat(
        initialValue = BREATHE_MIN_ALPHA,
        targetValue = BREATHE_MAX_ALPHA,
        animationSpec = infiniteRepeatable(tween(BREATHE_MS), repeatMode = RepeatMode.Reverse),
        label = "chevronAlpha",
    )
    val alpha = if (animationsEnabled) breathing else (BREATHE_MIN_ALPHA + BREATHE_MAX_ALPHA) / 2f

    Box(modifier = modifier.size(CHEVRON_SIZE).clearAndSetSemantics {}) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val w = size.width
            val h = size.height
            // A single "greater-than" pointing out of `edge`: two arms meeting at the outer tip.
            val (tip, armA, armB) = when (edge) {
                CueEdge.RIGHT -> Triple(Offset(w * 0.78f, h / 2f), Offset(w * 0.34f, h * 0.22f), Offset(w * 0.34f, h * 0.78f))
                CueEdge.LEFT -> Triple(Offset(w * 0.22f, h / 2f), Offset(w * 0.66f, h * 0.22f), Offset(w * 0.66f, h * 0.78f))
                CueEdge.TOP -> Triple(Offset(w / 2f, h * 0.22f), Offset(w * 0.22f, h * 0.66f), Offset(w * 0.78f, h * 0.66f))
                CueEdge.BOTTOM -> Triple(Offset(w / 2f, h * 0.78f), Offset(w * 0.22f, h * 0.34f), Offset(w * 0.78f, h * 0.34f))
            }
            val path = Path().apply {
                moveTo(armA.x, armA.y)
                lineTo(tip.x, tip.y)
                lineTo(armB.x, armB.y)
            }
            // A dark under-stroke first so the chevron stays legible over a bright frame, then the white
            // chevron on top — the same two-pass treatment the old directional arrow used.
            drawPath(path, color = Color.Black.copy(alpha = alpha * 0.45f), style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, color = OnScrim.copy(alpha = alpha), style = stroke)
        }
    }
}

@Preview(name = "Edge chevrons", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun EdgeChevronPreview() {
    CompositionCoachTheme {
        Box(Modifier.size(240.dp)) {
            CueEdge.entries.forEach { edge ->
                EdgeChevron(
                    edge = edge,
                    animationsEnabled = false,
                    modifier = Modifier.align(edge.toAlignment()),
                )
            }
        }
    }
}
