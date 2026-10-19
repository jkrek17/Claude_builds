package com.compositioncoach.app.ui.camera

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.Accent
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.OnScrim
import kotlinx.coroutines.delay

private const val CAPTURE_FLASH_MS = 60L
private val DISC_SIZE = 58.dp
private val ARMED_DISC_SIZE = 50.dp
private val COUNTDOWN_RING_SIZE = 58.dp

/**
 * A 72dp shutter whose outer ring is the readiness arc: it sweeps clockwise from 8 o'clock with the
 * smoothed score and closes into a full ready-green ring with a soft outer glow once framing is good
 * enough to fire (`docs/COACHING_UI.md`, "Readiness arc"). While the declared mode is still waiting for
 * its subject the arc dims to 30%, because the score behind it is a held, stale value.
 *
 * A press scales it to 90% and a capture flashes it white for a beat — the two together are what make the
 * shutter feel like it actually fired. [arcAlpha] dims the arc (30% awaiting a subject, 70% while the phone
 * is moving, see [SteadyHint.arcAlpha]).
 *
 * Press and release are reported separately ([onPress]/[onRelease]) so "shoot at the peak"
 * ([ShootAtPeakController]) can tell a tap from a hold; without them a tap simply calls [onClick]. While
 * armed, [autoCountdown] (1 -> 0) draws a thin inner ring that drains towards the auto-shoot timeout.
 * TalkBack gets "Take photo" as the click action and, when [onAccessibilityLongClick] is set, "Shoot when
 * ready" as the long-press action.
 */
@Composable
fun ShutterButton(
    isCapturing: Boolean,
    isShootReady: Boolean,
    score: Int,
    awaitingSubject: Boolean,
    animationsEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    arcAlpha: Float = if (awaitingSubject) GuidanceFormatter.AWAITING_SUBJECT_ARC_ALPHA else 1f,
    autoCountdown: Float? = null,
    onPress: (() -> Unit)? = null,
    onRelease: ((cancelled: Boolean) -> Unit)? = null,
    onAccessibilityLongClick: (() -> Unit)? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = tween(motionDuration(Motion.PRESS_MS, animationsEnabled)),
        label = "shutterScale",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (isShootReady) 1f else 0f,
        animationSpec = tween(motionDuration(Motion.STATE_CHANGE_MS, animationsEnabled)),
        label = "shutterGlow",
    )
    // The sweep animates rather than jumping with each 10Hz frame update; the score itself is already
    // smoothed by the engine, so this is only about not stepping.
    val sweepProgress by animateFloatAsState(
        targetValue = score.coerceIn(0, 100) / 100f,
        animationSpec = tween(motionDuration(Motion.STATE_CHANGE_MS, animationsEnabled)),
        label = "shutterArcSweep",
    )

    var showFlash by remember { mutableStateOf(false) }
    LaunchedEffect(isCapturing) {
        if (isCapturing) {
            showFlash = true
            delay(CAPTURE_FLASH_MS)
            showFlash = false
        }
    }
    val flashAlpha by animateFloatAsState(
        targetValue = if (showFlash) 1f else 0f,
        animationSpec = tween(CAPTURE_FLASH_MS.toInt() / 2),
        label = "captureFlash",
    )

    // While armed the disc shrinks a little to make room for the thin countdown ring inside the arc.
    val discSize by animateDpAsState(
        targetValue = if (autoCountdown != null) ARMED_DISC_SIZE else DISC_SIZE,
        animationSpec = tween(motionDuration(Motion.PRESS_MS, animationsEnabled)),
        label = "shutterDisc",
    )
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentlyCapturing by rememberUpdatedState(isCapturing)

    Box(modifier = modifier.size(96.dp).scale(scale), contentAlignment = Alignment.Center) {
        // Soft outer glow: a few widening, thinning rings rather than a real blur (cheap, and identical on
        // every API level — Modifier.blur only actually blurs on API 31+).
        if (glowAlpha > 0f) {
            Canvas(modifier = Modifier.size(96.dp)) {
                val steps = 3
                for (i in steps downTo 1) {
                    drawCircle(
                        color = Accent.Ready.copy(alpha = glowAlpha * 0.12f * i / steps),
                        radius = size.minDimension / 2f - (4 - i).dp.toPx(),
                    )
                }
            }
        }
        Canvas(modifier = Modifier.size(72.dp)) {
            drawReadinessArc(
                score = score,
                isShootReady = isShootReady,
                strokeWidthPx = 4.dp.toPx(),
                alpha = arcAlpha,
                progressOverride = if (isShootReady) 1f else sweepProgress,
            )
        }
        if (autoCountdown != null) {
            // Reduced motion: the ring stays full rather than visibly draining.
            AutoCountdownRing(fraction = if (animationsEnabled) autoCountdown else 1f)
        }
        Box(
            modifier = Modifier
                .size(discSize)
                .clip(CircleShape)
                .background(OnScrim.copy(alpha = if (isCapturing) 0.5f else 0.95f)),
        )
        if (flashAlpha > 0f) {
            Box(modifier = Modifier.size(discSize).clip(CircleShape).background(OnScrim.copy(alpha = flashAlpha)))
        }
        // The touch target is the whole 72dp ring, fixed, on top: the disc shrinks while armed, and a target
        // that shrank under a held finger would read as the finger sliding off and cancel the hold.
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                // Keyed on Unit so a capture starting mid-gesture (the auto fire) never cancels the gesture
                // half-way and leaves the press state stuck.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        if (currentlyCapturing) return@awaitEachGesture
                        pressed = true
                        currentOnPress?.invoke()
                        val up = waitForUpOrCancellation()
                        up?.consume()
                        pressed = false
                        val release = currentOnRelease
                        when {
                            release != null -> release(up == null)
                            up != null -> currentOnClick()
                        }
                    }
                }
                .semantics {
                    role = Role.Button
                    contentDescription = when {
                        autoCountdown != null -> "Shutter, waiting for the best moment"
                        isShootReady -> "Shutter, ready to shoot"
                        else -> "Shutter"
                    }
                    if (isCapturing) disabled()
                    onClick(label = "Take photo") {
                        currentOnClick()
                        true
                    }
                    if (onAccessibilityLongClick != null) {
                        onLongClick(label = "Shoot when ready") {
                            onAccessibilityLongClick()
                            true
                        }
                    }
                },
        )
    }
}

/**
 * The armed shutter's thin inner ring: a 1.5dp white arc from 12 o'clock that drains anticlockwise as
 * the auto-shoot timeout runs out, sitting between the readiness arc and the shrunken disc.
 */
@Composable
private fun AutoCountdownRing(fraction: Float) {
    Canvas(modifier = Modifier.size(COUNTDOWN_RING_SIZE)) {
        val stroke = 1.5.dp.toPx()
        val diameter = size.minDimension - stroke
        drawArc(
            color = OnScrim,
            startAngle = -90f,
            sweepAngle = 360f * fraction.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(diameter, diameter),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Preview(name = "Shutter, low score", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ShutterButtonLowPreview() {
    CompositionCoachTheme {
        ShutterButton(isCapturing = false, isShootReady = false, score = 38, awaitingSubject = false, animationsEnabled = false, onClick = {})
    }
}

@Preview(name = "Shutter, good score", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ShutterButtonGoodPreview() {
    CompositionCoachTheme {
        ShutterButton(isCapturing = false, isShootReady = false, score = 78, awaitingSubject = false, animationsEnabled = false, onClick = {})
    }
}

@Preview(name = "Shutter, shoot ready", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ShutterButtonReadyPreview() {
    CompositionCoachTheme {
        ShutterButton(isCapturing = false, isShootReady = true, score = 93, awaitingSubject = false, animationsEnabled = false, onClick = {})
    }
}

@Preview(name = "Shutter, awaiting subject", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ShutterButtonAwaitingPreview() {
    CompositionCoachTheme {
        ShutterButton(isCapturing = false, isShootReady = false, score = 71, awaitingSubject = true, animationsEnabled = false, onClick = {})
    }
}

@Preview(name = "Shutter, armed (auto)", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ShutterButtonArmedPreview() {
    CompositionCoachTheme {
        ShutterButton(
            isCapturing = false,
            isShootReady = false,
            score = 82,
            awaitingSubject = false,
            animationsEnabled = true,
            onClick = {},
            autoCountdown = 0.6f,
        )
    }
}

@Preview(name = "Shutter, moving (arc at 70%)", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ShutterButtonMovingPreview() {
    CompositionCoachTheme {
        ShutterButton(
            isCapturing = false,
            isShootReady = true,
            score = 91,
            awaitingSubject = false,
            animationsEnabled = false,
            onClick = {},
            arcAlpha = SteadyHint.MOVING_ARC_ALPHA,
        )
    }
}
