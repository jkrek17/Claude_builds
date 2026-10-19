package com.compositioncoach.app.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.OnScrim
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** How long the slider stays after the focus tap or the last drag. */
const val EXPOSURE_SLIDER_VISIBLE_MS = 2_500L

private val TRACK_HEIGHT = 128.dp
private val SLIDER_WIDTH = 48.dp
private val THUMB_SIZE = 22.dp

/** Gap between the focus ring's edge (32dp radius, see [FocusRingOverlay]) and the slider. */
private val RING_CLEARANCE = 32.dp + 8.dp

/**
 * The exposure slider Pixel shows beside the focus ring: a thin vertical track with a sun for a thumb and
 * the EV ("+0.7") above it. Appears for [EXPOSURE_SLIDER_VISIBLE_MS] after each focus tap ([tapId] changes)
 * and stays while it is being dragged; drag up to brighten. Sits to the right of the ring, or to its left
 * when the ring is too close to the right edge. Renders nothing when the camera has no exposure range.
 *
 * Fills the preview box (it positions itself from [tapOffset], in preview pixels). [stepEv] is the bound
 * camera's EV per index step; 0 (unknown) falls back to [ExposureText.fallbackStepEv].
 */
@Composable
fun ExposureSlider(
    tapOffset: Offset,
    tapId: Int,
    range: IntRange,
    index: Int,
    stepEv: Float,
    onIndexChange: (Int) -> Unit,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!ExposureText.isAdjustable(range)) return
    val evPerStep = stepEv.takeIf { it > 0f } ?: ExposureText.fallbackStepEv(range)
    var interaction by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(tapId, interaction, dragging) {
        visible = true
        if (dragging) return@LaunchedEffect
        delay(EXPOSURE_SLIDER_VISIBLE_MS)
        visible = false
    }
    val fadeMs = motionDuration(Motion.STATE_CHANGE_MS, animationsEnabled)
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val sliderWidthPx = with(density) { SLIDER_WIDTH.toPx() }
        val clearancePx = with(density) { RING_CLEARANCE.toPx() }
        val columnHeightPx = with(density) { (TRACK_HEIGHT + 24.dp).toPx() }
        val rightX = tapOffset.x + clearancePx
        val x = if (rightX + sliderWidthPx <= widthPx) rightX else tapOffset.x - clearancePx - sliderWidthPx
        val y = (tapOffset.y - columnHeightPx / 2f).coerceIn(0f, (heightPx - columnHeightPx).coerceAtLeast(0f))

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(fadeMs)),
            exit = fadeOut(tween(fadeMs)),
            modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
        ) {
            ExposureSliderBody(
                range = range,
                index = index,
                stepEv = evPerStep,
                onIndexChange = onIndexChange,
                onDragging = { active ->
                    dragging = active
                    interaction++
                },
            )
        }
    }
}

@Composable
private fun ExposureSliderBody(
    range: IntRange,
    index: Int,
    stepEv: Float,
    onIndexChange: (Int) -> Unit,
    onDragging: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val trackPx = with(density) { (TRACK_HEIGHT - THUMB_SIZE).toPx() }
    val currentIndex by rememberUpdatedState(index)
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction = ExposureText.fractionFor(index, range)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(SLIDER_WIDTH)
            .clearAndSetSemantics {
                contentDescription = ExposureText.description(index, stepEv)
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = index.toFloat(),
                    range = range.first.toFloat()..range.last.toFloat(),
                    steps = (range.last - range.first - 1).coerceAtLeast(0),
                )
                setProgress { target ->
                    onIndexChange(target.roundToInt().coerceIn(range))
                    true
                }
            },
    ) {
        Text(
            text = ExposureText.format(index, stepEv),
            color = OnScrim,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.height(24.dp),
        )
        Box(
            modifier = Modifier
                .width(SLIDER_WIDTH)
                .height(TRACK_HEIGHT)
                .pointerInput(range) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragFraction = ExposureText.fractionFor(currentIndex, range)
                            onDragging(true)
                        },
                        onDragEnd = { onDragging(false) },
                        onDragCancel = { onDragging(false) },
                    ) { change, dy ->
                        change.consume()
                        // Up (negative dy) brightens.
                        dragFraction = (dragFraction - dy / trackPx).coerceIn(0f, 1f)
                        val next = ExposureText.indexFor(dragFraction, range)
                        if (next != currentIndex) onIndexChange(next)
                    }
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            Canvas(modifier = Modifier.width(2.dp).height(TRACK_HEIGHT)) {
                drawLine(
                    color = OnScrim.copy(alpha = 0.6f),
                    start = Offset(size.width / 2f, 0f),
                    end = Offset(size.width / 2f, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            Icon(
                imageVector = Icons.Outlined.WbSunny,
                contentDescription = null,
                tint = OnScrim,
                modifier = Modifier
                    .offset { IntOffset(0, ((1f - fraction) * trackPx).roundToInt()) }
                    .size(THUMB_SIZE),
            )
        }
    }
}

@Preview(name = "Exposure slider", showBackground = true, backgroundColor = 0xFF303030, widthDp = 240, heightDp = 240)
@Composable
private fun ExposureSliderPreview() {
    CompositionCoachTheme {
        Box(Modifier.size(240.dp)) {
            FocusRingOverlay(tapOffset = Offset(200f, 300f))
            ExposureSlider(
                tapOffset = Offset(200f, 300f),
                tapId = 1,
                range = -6..6,
                index = 2,
                stepEv = 1f / 3f,
                onIndexChange = {},
                animationsEnabled = false,
            )
        }
    }
}
