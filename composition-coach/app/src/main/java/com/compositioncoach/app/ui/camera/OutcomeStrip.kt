package com.compositioncoach.app.ui.camera

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compositioncoach.app.R
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.OnScrim
import kotlin.math.abs

/** Everything about the outcome strip that is arithmetic or wording rather than layout (`OutcomeStripLogicTest`). */
object OutcomeStripLogic {

    /** Outcomes in strip order: the enum's own order, which is the order `docs/PRODUCT.md` lists them. */
    val outcomes: List<Outcome> = Outcome.entries

    /** How far a horizontal drag must travel, in dp, before it counts as a swipe to the next outcome. */
    const val SWIPE_THRESHOLD_DP = 48f

    /**
     * The outcome a swipe of [steps] lands on, clamped at both ends. A swipe *left* (negative drag) advances,
     * the way a camera's mode dial does; wrapping would make a hard swipe at an end jump across the strip.
     */
    fun afterSteps(current: Outcome, steps: Int): Outcome =
        outcomes[(outcomes.indexOf(current) + steps).coerceIn(0, outcomes.lastIndex)]

    /** Steps for an accumulated drag of [dragPx] against [thresholdPx]: -1, 0 or +1. */
    fun stepsForDrag(dragPx: Float, thresholdPx: Float): Int = when {
        abs(dragPx) < thresholdPx -> 0
        dragPx < 0f -> 1
        else -> -1
    }

    /** The plain words on the strip, from `strings.xml` (the same words as [Outcome.label]). */
    @StringRes
    fun labelRes(outcome: Outcome): Int = when (outcome) {
        Outcome.ANYTHING -> R.string.outcome_anything
        Outcome.PORTRAIT -> R.string.outcome_portrait
        Outcome.GROUP -> R.string.outcome_group
        Outcome.LANDSCAPE -> R.string.outcome_landscape
        Outcome.NIGHT -> R.string.outcome_night
        Outcome.LIGHT_TRAILS -> R.string.outcome_light_trails
        Outcome.SILKY_WATER -> R.string.outcome_silky_water
        Outcome.CLEAR_CROWD -> R.string.outcome_clear_crowd
        Outcome.FOOD -> R.string.outcome_food
        Outcome.SELFIE -> R.string.outcome_selfie
    }
}

private val SELECTED_SIZE = 15.sp
private val UNSELECTED_SIZE = 13.sp
private const val UNSELECTED_ALPHA = 0.6f

/**
 * The strip directly above the shutter: the photograph you want, in plain words (`docs/PRODUCT.md`, "Outcomes,
 * not modes"): Anything · A person · A group · A view · Night · Light trails · Silky water · Clear the crowd ·
 * Food & things · Selfie. The selected outcome is centred, white and slightly larger; the others are 60 % white.
 * Tap one or swipe the strip; a light haptic tick confirms either way and [onSelect] goes to
 * `CameraViewModel.onOutcomeSelected`, which sets everything underneath.
 *
 * The list scrolls only under program control: free scrolling would let the selection drift off centre, so a
 * drag is read as one outcome per [OutcomeStripLogic.SWIPE_THRESHOLD_DP] travelled.
 */
@Composable
fun OutcomeStrip(
    selected: Outcome,
    onSelect: (Outcome) -> Unit,
    modifier: Modifier = Modifier,
    animationsEnabled: Boolean = true,
) {
    val outcomes = OutcomeStripLogic.outcomes
    val selectedIndex = outcomes.indexOf(selected).coerceAtLeast(0)
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    val stripDescription = stringResource(R.string.outcome_strip_description)

    val select: (Outcome) -> Unit = { outcome ->
        if (outcome != selected) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onSelect(outcome)
        }
    }

    // Keep the selected outcome centred, however the selection changed.
    LaunchedEffect(selectedIndex, listState, animationsEnabled) {
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
        if (item == null) {
            listState.scrollToItem(selectedIndex)
        } else {
            val delta = item.offset - (info.viewportSize.width - item.size) / 2f
            if (animationsEnabled) listState.animateScrollBy(delta) else listState.scrollBy(delta)
        }
    }

    LazyRow(
        state = listState,
        userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 24.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics { contentDescription = stripDescription }
            .pointerInput(selected) {
                val thresholdPx = with(density) { OutcomeStripLogic.SWIPE_THRESHOLD_DP.dp.toPx() }
                detectHorizontalDragGestures(
                    onDragEnd = { dragAccumulator = 0f },
                    onDragCancel = { dragAccumulator = 0f },
                ) { _, dragAmount ->
                    dragAccumulator += dragAmount
                    val steps = OutcomeStripLogic.stepsForDrag(dragAccumulator, thresholdPx)
                    if (steps != 0) {
                        dragAccumulator = 0f
                        select(OutcomeStripLogic.afterSteps(selected, steps))
                    }
                }
            },
    ) {
        items(items = outcomes, key = { it.name }) { outcome ->
            OutcomeLabel(
                text = stringResource(OutcomeStripLogic.labelRes(outcome)),
                selected = outcome == selected,
                animationsEnabled = animationsEnabled,
                onClick = { select(outcome) },
            )
        }
    }
}

@Composable
private fun OutcomeLabel(text: String, selected: Boolean, animationsEnabled: Boolean, onClick: () -> Unit) {
    val stateMs = motionDuration(Motion.STATE_CHANGE_MS, animationsEnabled)
    val color by animateColorAsState(
        targetValue = if (selected) OnScrim else OnScrim.copy(alpha = UNSELECTED_ALPHA),
        animationSpec = tween(stateMs),
        label = "outcomeColor",
    )
    val size by animateFloatAsState(
        targetValue = if (selected) SELECTED_SIZE.value else UNSELECTED_SIZE.value,
        animationSpec = tween(stateMs),
        label = "outcomeSize",
    )
    Text(
        text = text,
        color = color,
        fontSize = size.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Preview(name = "Outcome strip", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun OutcomeStripPreview() {
    CompositionCoachTheme {
        OutcomeStrip(selected = Outcome.PORTRAIT, onSelect = {}, animationsEnabled = false)
    }
}

@Preview(name = "Outcome strip, light trails", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun OutcomeStripMotionPreview() {
    CompositionCoachTheme {
        OutcomeStrip(selected = Outcome.LIGHT_TRAILS, onSelect = {}, animationsEnabled = false)
    }
}
