package com.compositioncoach.app.ui.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.Accent
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.OnScrim
import kotlinx.coroutines.delay

private const val CAPTURE_FLASH_MS = 60L

/**
 * A 72dp shutter whose outer ring is the readiness arc: it sweeps clockwise from 8 o'clock with the
 * smoothed score and closes into a full ready-green ring with a soft outer glow once framing is good
 * enough to fire (`docs/COACHING_UI.md`, "Readiness arc"). While the declared mode is still waiting for
 * its subject the arc dims to 30%, because the score behind it is a held, stale value.
 *
 * A press scales it to 90% and a capture flashes it white for a beat — the two together are what make the
 * shutter feel like it actually fired.
 */
@Composable
fun ShutterButton(
    isCapturing: Boolean,
    isShootReady: Boolean,
    score: Int,
    awaitingSubject: Boolean,
    animationsEnabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
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

    Box(modifier = Modifier.size(96.dp).scale(scale), contentAlignment = Alignment.Center) {
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
                alpha = if (awaitingSubject) GuidanceFormatter.AWAITING_SUBJECT_ARC_ALPHA else 1f,
                progressOverride = if (isShootReady) 1f else sweepProgress,
            )
        }
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(OnScrim.copy(alpha = if (isCapturing) 0.5f else 0.95f))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = !isCapturing,
                    onClick = onClick,
                )
                .semantics { contentDescription = if (isShootReady) "Shutter, ready to shoot" else "Shutter" },
        )
        if (flashAlpha > 0f) {
            Box(modifier = Modifier.size(58.dp).clip(CircleShape).background(OnScrim.copy(alpha = flashAlpha)))
        }
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
