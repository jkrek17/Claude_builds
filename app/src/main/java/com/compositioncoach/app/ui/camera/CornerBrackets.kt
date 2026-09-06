package com.compositioncoach.app.ui.camera

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.OnScrim

/** Arm length of each bracket, per `docs/COACHING_UI.md`. */
private val BRACKET_ARM = 18.dp

/** How far the brackets travel, and over how long, before easing back. */
private val BRACKET_TRAVEL = 8.dp
private const val BRACKET_CYCLE_MS = 1_400

/** How far in from the preview's own edges the brackets sit at rest. */
private val BRACKET_INSET = 28.dp

/**
 * The primary cue for CLOSER / BACK: four corner brackets that ease 8dp [inward] (get closer) and back, or
 * outward (step back), over 1.4s. Nothing else on the frame moves, so the direction of travel *is* the
 * instruction; the chip beside the top edge only labels it.
 *
 * Decorative: no semantics at all, per the spec's accessibility rule. Corners are frame corners in
 * *screen* space and are symmetric under every quarter turn, so unlike the chevron this cue needs no
 * rotation handling — a bracket in the top-left is a frame corner in any hold.
 *
 * The loop stops when the system animator duration scale is 0; the brackets then sit at rest.
 */
@Composable
fun CornerBrackets(inward: Boolean, animationsEnabled: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "bracketEase")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BRACKET_CYCLE_MS, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "bracketProgress",
    )
    val travelFraction = if (animationsEnabled) progress else 0f

    Canvas(modifier = modifier.fillMaxSize().clearAndSetSemantics {}) {
        val arm = BRACKET_ARM.toPx()
        val travel = BRACKET_TRAVEL.toPx() * travelFraction * if (inward) 1f else -1f
        val inset = BRACKET_INSET.toPx() + travel
        val strokeWidth = 2.dp.toPx()
        val left = inset
        val top = inset
        val right = size.width - inset
        val bottom = size.height - inset

        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(OnScrim.copy(alpha = 0.85f), Offset(x, y), Offset(x + arm * dx, y), strokeWidth, StrokeCap.Round)
            drawLine(OnScrim.copy(alpha = 0.85f), Offset(x, y), Offset(x, y + arm * dy), strokeWidth, StrokeCap.Round)
        }
        corner(left, top, 1f, 1f)
        corner(right, top, -1f, 1f)
        corner(left, bottom, 1f, -1f)
        corner(right, bottom, -1f, -1f)
    }
}

@Preview(name = "Corner brackets (closer)", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun CornerBracketsPreview() {
    CompositionCoachTheme {
        Canvas(Modifier.fillMaxSize()) { drawRect(Color.Transparent) }
        CornerBrackets(inward = true, animationsEnabled = false)
    }
}
