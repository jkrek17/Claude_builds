package com.compositioncoach.app.ui.camera

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.compositioncoach.app.ui.theme.Accent
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.Scrim
import kotlinx.coroutines.delay

/**
 * Bottom transparent control row, Pixel-style: a gallery thumbnail (opens the last photo), the shutter
 * (flash/mode/settings live in [CameraTopBar] instead), and the lens switch.
 */
@Composable
fun BottomControlBar(
    isCapturing: Boolean,
    isShootReady: Boolean,
    lastPhotoUri: Uri?,
    onShutterClick: () -> Unit,
    onSwitchLensClick: () -> Unit,
    onOpenGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GalleryThumbnail(photoUri = lastPhotoUri, onClick = onOpenGallery)

        ShutterButton(isCapturing = isCapturing, isShootReady = isShootReady, onClick = onShutterClick)

        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            IconButton(onClick = onSwitchLensClick, enabled = !isCapturing) {
                Icon(imageVector = Icons.Filled.Cameraswitch, contentDescription = "Switch camera", tint = OnScrim)
            }
        }
    }
}

/**
 * A 44dp rounded-square thumbnail of [photoUri], the last photo captured this session — tapping opens it.
 * Shows a plain gallery-icon placeholder when there is no photo yet (fresh install, or the hook that would
 * supply it isn't wired — see [CameraScreen]/`app/README.md` for the exact source of [photoUri]).
 */
@Composable
private fun GalleryThumbnail(photoUri: Uri?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Scrim)
            .border(1.dp, OnScrim.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Open last photo" },
        contentAlignment = Alignment.Center,
    ) {
        if (photoUri != null) {
            Image(
                painter = rememberAsyncImagePainter(photoUri),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Icon(imageVector = Icons.Filled.PhotoLibrary, contentDescription = null, tint = OnScrim.copy(alpha = 0.6f))
        }
    }
}

private const val CAPTURE_FLASH_MS = 60L

/**
 * A 72dp shutter with a press-scale animation (90% while held) and a brief white flash overlay the instant
 * a capture starts — the two together are what make the shutter feel like it actually fired, the way a
 * dedicated camera's does. The ring animates to [Accent.Ready] with a soft outer glow once [isShootReady],
 * and back to white the instant framing drops out of it. Disabled (dimmed, no ripple) while capturing.
 */
@Composable
private fun ShutterButton(isCapturing: Boolean, isShootReady: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (pressed) 0.9f else 1f, animationSpec = tween(Motion.PRESS_MS), label = "shutterScale")
    val ringColor by animateColorAsState(if (isShootReady) Accent.Ready else OnScrim, animationSpec = tween(250), label = "shutterRing")
    val glowAlpha by animateFloatAsState(if (isShootReady) 1f else 0f, animationSpec = tween(250), label = "shutterGlow")

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
        // Soft outer glow: a few widening, thinning rings rather than a real blur (cheap, and identical
        // on every API level — Modifier.blur needs API 31+ to actually blur).
        if (glowAlpha > 0f) {
            Canvas(modifier = Modifier.size(96.dp)) {
                val steps = 3
                for (i in steps downTo 1) {
                    drawCircle(
                        color = ringColor.copy(alpha = glowAlpha * 0.12f * i / steps),
                        radius = size.minDimension / 2f - (4 - i).dp.toPx(),
                    )
                }
            }
        }
        Canvas(modifier = Modifier.size(72.dp)) {
            drawCircle(
                color = ringColor,
                radius = size.minDimension / 2f - 4.dp.toPx(),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx()),
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

@Preview(name = "Bottom bar", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun BottomControlBarPreview() {
    CompositionCoachTheme {
        BottomControlBar(
            isCapturing = false,
            isShootReady = false,
            lastPhotoUri = null,
            onShutterClick = {},
            onSwitchLensClick = {},
            onOpenGallery = {},
        )
    }
}

@Preview(name = "Bottom bar, shoot ready", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun BottomControlBarReadyPreview() {
    CompositionCoachTheme {
        BottomControlBar(
            isCapturing = false,
            isShootReady = true,
            lastPhotoUri = null,
            onShutterClick = {},
            onSwitchLensClick = {},
            onOpenGallery = {},
        )
    }
}
