package com.compositioncoach.app.ui.camera

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.compositioncoach.app.ui.theme.Background
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.Scrim

/** The visible chip; each sits in a 48dp touch target. */
private val CHIP_SIZE = 36.dp

/**
 * Pixel-style zoom presets above the mode strip: small round chips ("0.6", "1×", "2", "5"), the one for
 * the current range filled white and reading the live ratio, so a pinch to 1.4× lights the 1× chip and
 * relabels it "1.4×" ([ZoomPresets]). [selectedPreset] is `CameraUiState.selectedZoomPreset`; it decides the lit
 * chip whenever the camera sits on a preset ([ZoomPresets.litIndex]). Hidden when the lens has only one preset. Each chip counter-rotates
 * in place like the other chrome; being round, nothing about the row's layout changes with rotation.
 */
@Composable
fun ZoomPresetRow(
    presets: List<Float>,
    zoomRatio: Float,
    onSelect: (Float) -> Unit,
    deviceRotationDegrees: Int,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier,
    selectedPreset: Float? = null,
) {
    if (!ZoomPresets.isVisible(presets)) return
    val active = ZoomPresets.litIndex(presets, selectedPreset, zoomRatio)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
        presets.forEachIndexed { index, preset ->
            RotatedChrome(deviceRotationDegrees = deviceRotationDegrees) {
                ZoomPresetChip(
                    label = ZoomPresets.chipLabel(
                        preset,
                        zoomRatio,
                        active = index == active,
                        sittingOn = preset == selectedPreset || ZoomPresets.isOnPreset(preset, zoomRatio),
                    ),
                    description = ZoomPresets.chipDescription(preset),
                    selected = index == active,
                    animationsEnabled = animationsEnabled,
                    onClick = { onSelect(preset) },
                )
            }
        }
    }
}

@Composable
private fun ZoomPresetChip(
    label: String,
    description: String,
    selected: Boolean,
    animationsEnabled: Boolean,
    onClick: () -> Unit,
) {
    val fill by animateColorAsState(
        targetValue = if (selected) OnScrim.copy(alpha = 0.92f) else Scrim,
        animationSpec = tween(motionDuration(Motion.STATE_CHANGE_MS, animationsEnabled)),
        label = "zoomChipFill",
    )
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.RadioButton
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(CHIP_SIZE).clip(CircleShape).background(fill),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (selected) Background else OnScrim,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

private val PreviewPresets = listOf(0.6f, 1f, 2f, 5f)

@Preview(name = "Zoom presets, on 1×", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ZoomPresetRowPreview() {
    CompositionCoachTheme {
        ZoomPresetRow(PreviewPresets, zoomRatio = 1f, onSelect = {}, deviceRotationDegrees = 0, animationsEnabled = false)
    }
}

@Preview(name = "Zoom presets, pinched to 1.4×", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ZoomPresetRowPinchedPreview() {
    CompositionCoachTheme {
        ZoomPresetRow(PreviewPresets, zoomRatio = 1.4f, onSelect = {}, deviceRotationDegrees = 0, animationsEnabled = false)
    }
}

@Preview(name = "Zoom presets, rotation 90", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ZoomPresetRowRotatedPreview() {
    CompositionCoachTheme {
        ZoomPresetRow(PreviewPresets, zoomRatio = 2f, onSelect = {}, deviceRotationDegrees = 90, animationsEnabled = false)
    }
}
