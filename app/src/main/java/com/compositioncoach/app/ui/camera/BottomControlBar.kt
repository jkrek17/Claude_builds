package com.compositioncoach.app.ui.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.camera.FlashMode
import kotlinx.coroutines.delay

/** Bottom transparent control row: flash toggle, large shutter, camera switch — always visible, never a full bar. */
@Composable
fun BottomControlBar(
    flashMode: FlashMode,
    hasFlashUnit: Boolean,
    isCapturing: Boolean,
    onFlashClick: () -> Unit,
    onShutterClick: () -> Unit,
    onSwitchLensClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (hasFlashUnit) {
                IconButton(onClick = onFlashClick) {
                    Icon(
                        imageVector = when (flashMode) {
                            FlashMode.OFF -> Icons.Filled.FlashOff
                            FlashMode.AUTO -> Icons.Filled.FlashAuto
                            FlashMode.ON -> Icons.Filled.FlashOn
                        },
                        contentDescription = "Flash: $flashMode",
                        tint = Color.White,
                    )
                }
            }
        }

        ShutterButton(isCapturing = isCapturing, onClick = onShutterClick)

        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            IconButton(onClick = onSwitchLensClick, enabled = !isCapturing) {
                Icon(imageVector = Icons.Filled.Cameraswitch, contentDescription = "Switch camera", tint = Color.White)
            }
        }
    }
}

private const val CAPTURE_FLASH_MS = 60L

/**
 * A 72dp white-ring shutter with a press-scale animation (90% while held) and a brief white flash overlay
 * the instant a capture starts (the rising edge of [isCapturing]) — the two together are what make the
 * shutter feel like it actually fired, the way a dedicated camera's does. Disabled (dimmed, no ripple)
 * while capturing.
 */
@Composable
private fun ShutterButton(isCapturing: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (pressed) 0.9f else 1f, label = "shutterScale")

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

    Box(modifier = Modifier.size(72.dp).scale(scale), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(72.dp)) {
            drawCircle(
                color = Color.White,
                radius = size.minDimension / 2f - 4.dp.toPx(),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx()),
            )
        }
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (isCapturing) 0.5f else 0.95f))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = !isCapturing,
                    onClick = onClick,
                ),
        )
        if (flashAlpha > 0f) {
            Box(modifier = Modifier.size(58.dp).clip(CircleShape).background(Color.White.copy(alpha = flashAlpha)))
        }
    }
}

@Preview(name = "Bottom bar", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun BottomControlBarPreview() {
    CompositionCoachTheme {
        BottomControlBar(
            flashMode = FlashMode.AUTO,
            hasFlashUnit = true,
            isCapturing = false,
            onFlashClick = {},
            onShutterClick = {},
            onSwitchLensClick = {},
        )
    }
}
