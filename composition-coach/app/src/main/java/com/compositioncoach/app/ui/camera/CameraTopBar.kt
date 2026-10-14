package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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

/**
 * The translucent strip over the top of the preview and, per `docs/APP_UX.md`, nothing but: flash at the
 * left, settings at the right. The shooting mode used to have a chip here; the mode strip above the
 * shutter is the control for it now. The one addition is the "DEBUG" chip, which exists only while
 * developer mode is on.
 *
 * [deviceRotationDegrees] rotates each icon in place (each wrapped in its own [RotatedChrome]) so they
 * stay upright to the person holding the phone even though this strip's own position never moves.
 */
@Composable
fun CameraTopBar(
    showDebugChip: Boolean,
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
                                FlashMode.OFF -> Icons.Outlined.FlashOff
                                FlashMode.AUTO -> Icons.Outlined.FlashAuto
                                FlashMode.ON -> Icons.Outlined.FlashOn
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
        RotatedChrome(deviceRotationDegrees = deviceRotationDegrees) {
            IconButton(onClick = onSettingsClick) {
                Icon(imageVector = Icons.Outlined.Settings, contentDescription = "Settings", tint = OnScrim)
            }
        }
    }
}

@Preview(name = "Top bar", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun CameraTopBarPreview() {
    CompositionCoachTheme {
        CameraTopBar(
            showDebugChip = false,
            flashMode = FlashMode.OFF,
            hasFlashUnit = true,
            onFlashClick = {},
            onSettingsClick = {},
        )
    }
}

@Preview(name = "Top bar, debug", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun CameraTopBarDebugPreview() {
    CompositionCoachTheme {
        CameraTopBar(
            showDebugChip = true,
            flashMode = FlashMode.AUTO,
            hasFlashUnit = true,
            onFlashClick = {},
            onSettingsClick = {},
        )
    }
}

@Preview(name = "Top bar, rotation 90", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun CameraTopBarRotatedPreview() {
    CompositionCoachTheme {
        CameraTopBar(
            showDebugChip = false,
            flashMode = FlashMode.ON,
            hasFlashUnit = true,
            onFlashClick = {},
            onSettingsClick = {},
            deviceRotationDegrees = 90,
        )
    }
}
