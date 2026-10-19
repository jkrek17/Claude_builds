package com.compositioncoach.app.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.R
import com.compositioncoach.app.ui.theme.Accent
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.Scrim
import com.compositioncoach.composition.model.GroupStatus
import com.compositioncoach.composition.model.NormalizedRect
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/** How long the "Night isn't available on this phone" note stays after the outcome is picked. */
private const val DEGRADED_NOTE_MS = 3_000L

/** The ghost's dash: 6dp on, 4dp off, 1dp, 40 % white (`docs/WAVE2.md` section 6). */
private const val GHOST_ALPHA = 0.4f

/**
 * One small dot per face just outside the shutter's readiness arc, clustered over 12 o'clock and filled
 * ready-green as each face becomes ready ([GroupCues.dots]). Sized to sit in the shutter's 96dp box.
 */
@Composable
fun GroupDots(status: GroupStatus, modifier: Modifier = Modifier) {
    val dots = GroupCues.dots(status)
    if (dots.isEmpty()) return
    val description = stringResource(R.string.group_ready_description, status.facesReady.coerceAtMost(status.facesTotal), status.facesTotal)
    Canvas(modifier.size(96.dp).clearAndSetSemantics { contentDescription = description }) {
        val radius = size.minDimension / 2f - 3.dp.toPx()
        val dotRadius = 2.5.dp.toPx()
        dots.forEachIndexed { index, filled ->
            val angle = Math.toRadians(GroupCues.dotAngleDegrees(index, dots.size).toDouble())
            val center = Offset(size.width / 2f + radius * cos(angle).toFloat(), size.height / 2f + radius * sin(angle).toFloat())
            if (filled) {
                drawCircle(Accent.Ready, dotRadius, center)
            } else {
                drawCircle(OnScrim.copy(alpha = 0.6f), dotRadius, center, style = Stroke(1.dp.toPx()))
            }
        }
    }
}

/**
 * The re-shoot ghost: the previous photo's subject outline as a 1dp dashed 40 % white rectangle, placed through
 * [OverlayMapper.toPxRect] like every other overlay so it lands on the same physical spot in any hold. A tap on
 * it calls [onDismiss] (`CameraViewModel.onUseAsGhost(null)`). Must sit in the preview's 3:4 box.
 */
@Composable
fun GhostOverlay(rect: NormalizedRect?, deviceRotationDegrees: Int, onDismiss: () -> Unit) {
    if (rect == null || rect.isEmpty) return
    val description = stringResource(R.string.ghost_description)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val px = OverlayMapper.toPxRect(rect.clampToFrame(), deviceRotationDegrees, wPx, hPx)
        Canvas(Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            drawRoundRect(
                color = OnScrim.copy(alpha = GHOST_ALPHA),
                topLeft = Offset(px.left, px.top),
                size = Size(px.right - px.left, px.bottom - px.top),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
            )
        }
        with(density) {
            Box(
                Modifier
                    .offset(px.left.toDp(), px.top.toDp())
                    .size((px.right - px.left).toDp(), (px.bottom - px.top).toDp())
                    .clickable(onClickLabel = description, onClick = onDismiss)
                    .clearAndSetSemantics { contentDescription = description },
            )
        }
    }
}

/**
 * `CameraUiState.outcomeDegradedNote`, resolved from `strings.xml` ("Night isn't available on this phone, using
 * the best we can"), as a small chip for three seconds
 * each time the outcome is picked, then gone: said once, never nagging.
 */
@Composable
fun DegradedNoteChip(note: String?, outcomeKey: Any, animationsEnabled: Boolean, modifier: Modifier = Modifier) {
    var visible by remember(outcomeKey) { mutableStateOf(note != null) }
    LaunchedEffect(outcomeKey, note) {
        visible = note != null
        if (note != null) {
            delay(DEGRADED_NOTE_MS)
            visible = false
        }
    }
    val fadeMs = motionDuration(Motion.CROSSFADE_MS, animationsEnabled)
    AnimatedVisibility(visible = visible && note != null, enter = fadeIn(tween(fadeMs)), exit = fadeOut(tween(fadeMs)), modifier = modifier) {
        Text(
            text = note.orEmpty(),
            color = OnScrim,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Scrim)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Preview(name = "Group dots, 2 of 4 ready", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun GroupDotsPreview() {
    CompositionCoachTheme {
        Box {
            ShutterButton(isCapturing = false, isShootReady = false, score = 72, awaitingSubject = false, animationsEnabled = false, onClick = {})
            GroupDots(GroupStatus(ready = false, facesTotal = 4, facesReady = 2))
        }
    }
}

@Preview(name = "Ghost", showBackground = true, backgroundColor = 0xFF303030, widthDp = 300, heightDp = 400)
@Composable
private fun GhostPreview() {
    CompositionCoachTheme {
        Box(Modifier.fillMaxSize()) {
            GhostOverlay(NormalizedRect(0.3f, 0.2f, 0.7f, 0.6f), deviceRotationDegrees = 0, onDismiss = {})
        }
    }
}

@Preview(name = "Ghost, rotation 90", showBackground = true, backgroundColor = 0xFF303030, widthDp = 300, heightDp = 400)
@Composable
private fun GhostRotatedPreview() {
    CompositionCoachTheme {
        Box(Modifier.fillMaxSize()) {
            GhostOverlay(NormalizedRect(0.1f, 0.2f, 0.4f, 0.5f), deviceRotationDegrees = 90, onDismiss = {})
        }
    }
}

@Preview(name = "Degraded note", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun DegradedNotePreview() {
    CompositionCoachTheme {
        DegradedNoteChip(note = "Night isn't available on this phone", outcomeKey = 1, animationsEnabled = false)
    }
}
