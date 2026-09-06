package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compositioncoach.app.camera.FlashMode
import com.compositioncoach.app.ui.theme.Accent
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.Scrim
import com.compositioncoach.composition.model.SceneIntent

/**
 * The translucent control strip over the top of the preview, Pixel-style: flash + a "DEBUG" chip (debug
 * mode only) at the left, the shooting-mode chip (shown only when [sceneIntent] isn't [SceneIntent.AUTO],
 * tapping it opens Settings same as the gear) and the settings gear at the right.
 *
 * [deviceRotationDegrees] rotates the flash icon, mode chip and settings gear in place (each wrapped in
 * its own [RotatedChrome]) so they stay upright to the person holding the phone even though this strip's
 * own position never moves — see `app/README.md`'s device-rotation section.
 */
@Composable
fun CameraTopBar(
    showDebugChip: Boolean,
    sceneIntent: SceneIntent,
    flashMode: FlashMode,
    hasFlashUnit: Boolean,
    onFlashClick: () -> Unit,
    onSettingsClick: () -> Unit,
    deviceRotationDegrees: Int = 0,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().background(Scrim).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (hasFlashUnit) {
                RotatedChrome(deviceRotationDegrees = deviceRotationDegrees) {
                    IconButton(onClick = onFlashClick) {
                        Icon(
                            imageVector = when (flashMode) {
                                FlashMode.OFF -> Icons.Filled.FlashOff
                                FlashMode.AUTO -> Icons.Filled.FlashAuto
                                FlashMode.ON -> Icons.Filled.FlashOn
                            },
                            contentDescription = "Flash: $flashMode",
                            tint = OnScrim,
                        )
                    }
                }
            }
            if (showDebugChip) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Accent.Warn)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(text = "DEBUG", color = Color.Black, fontSize = 10.sp)
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sceneIntent != SceneIntent.AUTO) {
                // The chip's visible padding alone is well under the 48dp touch-target minimum;
                // minimumInteractiveComponentSize() reserves an at-least-48dp tap area around the small
                // visible pill without growing how the chip itself looks (same idea IconButton uses
                // internally, just with an explicit inner Box instead of a fixed .size()).
                RotatedChrome(deviceRotationDegrees = deviceRotationDegrees) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clickable(onClick = onSettingsClick),
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(OnScrim.copy(alpha = 0.15f))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(text = sceneIntent.label, color = OnScrim, fontSize = 12.sp)
                        }
                    }
                }
            }
            RotatedChrome(deviceRotationDegrees = deviceRotationDegrees) {
                IconButton(onClick = onSettingsClick) {
                    Icon(imageVector = Icons.Filled.Settings, contentDescription = "Settings", tint = OnScrim)
                }
            }
        }
    }
}

@Preview(name = "Debug chip + settings", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun CameraTopBarPreview() {
    CompositionCoachTheme {
        CameraTopBar(
            showDebugChip = true,
            sceneIntent = SceneIntent.AUTO,
            flashMode = FlashMode.OFF,
            hasFlashUnit = true,
            onFlashClick = {},
            onSettingsClick = {},
        )
    }
}

@Preview(name = "Shooting mode chip", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun CameraTopBarModeChipPreview() {
    CompositionCoachTheme {
        CameraTopBar(
            showDebugChip = false,
            sceneIntent = SceneIntent.PORTRAIT,
            flashMode = FlashMode.AUTO,
            hasFlashUnit = true,
            onFlashClick = {},
            onSettingsClick = {},
        )
    }
}
