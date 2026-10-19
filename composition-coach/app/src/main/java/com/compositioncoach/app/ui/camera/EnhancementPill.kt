package com.compositioncoach.app.ui.camera

import androidx.compose.ui.res.stringResource
import com.compositioncoach.app.R
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.OnScrimMuted
import com.compositioncoach.app.ui.theme.Scrim
import com.compositioncoach.app.ui.theme.Surface
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.SmoothedComposition

/**
 * The capture-enhancement pill in the top strip ("Auto", "HDR", "Night", "Off"). A tap moves to the next
 * option ([EnhancementCycle.next]) with a 200ms crossfade and a light tick; a long-press opens a small menu
 * listing every option, each one whose session cannot carry live analysis ([pausingCoaching],
 * `CaptureSessionInfo.enhancementsPausingCoaching`) marked "pauses coaching" so the trade is known before it is
 * made. [current] is what the camera is running (`CameraUiState.enhancement`). Renders nothing when the lens offers nothing beyond Off, so the top strip stays
 * "flash · settings" on cameras without extensions.
 *
 * Does not rotate itself; [CameraTopBar] wraps it in [RotatedChrome] like its icons.
 */
@Composable
fun EnhancementPill(
    current: CaptureEnhancement,
    available: Set<CaptureEnhancement>,
    onSelect: (CaptureEnhancement) -> Unit,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier,
    pausingCoaching: Set<CaptureEnhancement> = emptySet(),
) {
    if (!EnhancementCycle.isVisible(available)) return
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val crossfadeMs = motionDuration(Motion.CROSSFADE_MS, animationsEnabled)
    val off = current == CaptureEnhancement.OFF

    Box(modifier = modifier) {
        AnimatedContent(
            targetState = current,
            transitionSpec = { fadeIn(tween(crossfadeMs)) togetherWith fadeOut(tween(crossfadeMs)) },
            label = "enhancementPill",
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = "Next enhancement",
                    onLongClickLabel = "Choose enhancement",
                    onLongClick = {
                        // Light, like every other tick here: the one medium tick is reserved for shoot-ready.
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        menuOpen = true
                    },
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(EnhancementCycle.next(current, available))
                    },
                )
                .clearAndSetSemantics { contentDescription = EnhancementCycle.description(current) },
        ) { shown ->
            Text(
                text = EnhancementCycle.label(shown),
                color = if (off) OnScrimMuted else OnScrim,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Scrim)
                    .border(1.dp, OnScrim.copy(alpha = if (off) 0.25f else 0.6f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = Surface,
        ) {
            EnhancementCycle.options(available).forEach { option ->
                DropdownMenuItem(
                    text = { EnhancementMenuText(option, EnhancementCycle.menuNote(option, pausingCoaching)) },
                    trailingIcon = {
                        if (option == current) {
                            Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.selected), tint = OnScrim)
                        } else {
                            Box(Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        menuOpen = false
                        if (option != current) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(option)
                        }
                    },
                )
            }
        }
    }
}

/** An option's label, plus a small muted note ("pauses coaching") when it has one. */
@Composable
private fun EnhancementMenuText(option: CaptureEnhancement, note: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(option.label, color = OnScrim)
        if (note != null) {
            Text(note, color = OnScrimMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Preview(name = "Enhancement pill", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun EnhancementPillPreview() {
    CompositionCoachTheme {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val all = setOf(CaptureEnhancement.AUTO, CaptureEnhancement.HDR, CaptureEnhancement.NIGHT)
            CaptureEnhancement.entries.forEach { value ->
                EnhancementPill(current = value, available = all, onSelect = {}, animationsEnabled = false)
            }
        }
    }
}

private val PausedPreviewComposition = SmoothedComposition.EMPTY.copy(
    displayScore = 74,
    scene = SceneClassification(SceneType.PORTRAIT, 0.9f),
)

@Preview(name = "Coaching paused in Night", showBackground = true, backgroundColor = 0xFF303030, widthDp = 300, heightDp = 400)
@Composable
private fun CoachingPausedPreview() {
    CompositionCoachTheme {
        CoachingLayer(
            composition = PausedPreviewComposition,
            guidanceLevel = GuidanceLevel.BALANCED,
            showScore = true,
            hasScene = true,
            deviceRotationDegrees = 0,
            animationsEnabled = false,
            pausedText = EnhancementCycle.pausedText(CaptureEnhancement.NIGHT),
        )
    }
}

@Preview(name = "Coaching paused in Night, rotation 90", showBackground = true, backgroundColor = 0xFF303030, widthDp = 300, heightDp = 400)
@Composable
private fun CoachingPausedRotatedPreview() {
    CompositionCoachTheme {
        CoachingLayer(
            composition = PausedPreviewComposition,
            guidanceLevel = GuidanceLevel.BALANCED,
            showScore = true,
            hasScene = true,
            deviceRotationDegrees = 90,
            animationsEnabled = false,
            pausedText = EnhancementCycle.pausedText(CaptureEnhancement.NIGHT),
        )
    }
}
