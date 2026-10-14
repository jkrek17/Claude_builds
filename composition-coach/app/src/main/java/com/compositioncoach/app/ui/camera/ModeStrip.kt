package com.compositioncoach.app.ui.camera

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.composition.model.SceneIntent
import kotlin.math.abs

/** Everything about the mode strip that is arithmetic rather than layout, so it can be unit-tested. */
object ModeStripLogic {

    /** Modes in strip order — the enum's own order, which is the order `docs/APP_UX.md` lists them. */
    val modes: List<SceneIntent> = SceneIntent.entries

    /** How far a horizontal drag must travel, in dp, before it counts as a swipe to the next mode. */
    const val SWIPE_THRESHOLD_DP = 48f

    /**
     * The mode a swipe of [steps] lands on, clamped at both ends. A swipe *left* (the strip's content
     * moving left, a negative drag) advances to the next mode, the way a camera's mode dial does.
     */
    fun modeAfterSteps(current: SceneIntent, steps: Int): SceneIntent {
        val index = (modes.indexOf(current) + steps).coerceIn(0, modes.lastIndex)
        return modes[index]
    }
}

private val SELECTED_SIZE = 15.sp
private val UNSELECTED_SIZE = 13.sp
private const val UNSELECTED_ALPHA = 0.6f

/**
 * The Pixel-style mode strip directly above the shutter: `Auto · Portrait · Group · Landscape ·
 * Architecture · Object`. The selected mode is centred, white and slightly larger; the others are 60%
 * white. Tap a mode or swipe the strip horizontally to change it; a light haptic tick confirms either way.
 *
 * This is the *same* setting as Settings > Shooting mode — both write `CoachSettings.sceneIntent`, so the
 * strip and the settings screen always agree; the strip is just the primary control.
 *
 * The list scrolls only under program control (`userScrollEnabled = false`): free scrolling would let the
 * highlighted mode drift away from centre, so a drag is read as "one mode per
 * [ModeStripLogic.SWIPE_THRESHOLD_DP] travelled" and the centring animation follows the selection instead.
 */
@Composable
fun ModeStrip(
    selected: SceneIntent,
    onSelect: (SceneIntent) -> Unit,
    modifier: Modifier = Modifier,
    animationsEnabled: Boolean = true,
) {
    val modes = ModeStripLogic.modes
    val selectedIndex = modes.indexOf(selected).coerceAtLeast(0)
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    val select: (SceneIntent) -> Unit = { intent ->
        if (intent != selected) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onSelect(intent)
        }
    }

    // Keep the selected mode centred, however the selection changed (tap, swipe, or the settings screen
    // writing the same value).
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
            .height(36.dp)
            .semantics { contentDescription = "Shooting mode" }
            .pointerInput(selected) {
                val thresholdPx = with(density) { ModeStripLogic.SWIPE_THRESHOLD_DP.dp.toPx() }
                detectHorizontalDragGestures(
                    onDragEnd = { dragAccumulator = 0f },
                    onDragCancel = { dragAccumulator = 0f },
                ) { _, dragAmount ->
                    dragAccumulator += dragAmount
                    if (abs(dragAccumulator) >= thresholdPx) {
                        val steps = if (dragAccumulator < 0) 1 else -1
                        dragAccumulator = 0f
                        select(ModeStripLogic.modeAfterSteps(selected, steps))
                    }
                }
            },
    ) {
        items(items = modes, key = { it.name }) { intent ->
            ModeLabel(
                intent = intent,
                selected = intent == selected,
                animationsEnabled = animationsEnabled,
                onClick = { select(intent) },
            )
        }
    }
}

@Composable
private fun ModeLabel(intent: SceneIntent, selected: Boolean, animationsEnabled: Boolean, onClick: () -> Unit) {
    val stateMs = motionDuration(Motion.STATE_CHANGE_MS, animationsEnabled)
    val color by animateColorAsState(
        targetValue = if (selected) OnScrim else OnScrim.copy(alpha = UNSELECTED_ALPHA),
        animationSpec = tween(stateMs),
        label = "modeColor",
    )
    val size by animateFloatAsState(
        targetValue = if (selected) SELECTED_SIZE.value else UNSELECTED_SIZE.value,
        animationSpec = tween(stateMs),
        label = "modeSize",
    )
    Text(
        text = intent.label,
        color = color,
        fontSize = size.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Preview(name = "Mode strip", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ModeStripPreview() {
    CompositionCoachTheme {
        ModeStrip(selected = SceneIntent.PORTRAIT, onSelect = {}, animationsEnabled = false)
    }
}
