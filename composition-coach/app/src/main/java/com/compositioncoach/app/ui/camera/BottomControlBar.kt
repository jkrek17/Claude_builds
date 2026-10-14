package com.compositioncoach.app.ui.camera

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PhotoLibrary
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

/** How long the quiet-state check mark stays beside the shutter. */
private const val QUIET_CHECK_MS = 1_000L

/**
 * Bottom transparent control row, Pixel-style: a gallery thumbnail (opens the last photo), the shutter
 * with its readiness arc, and the lens switch. Flash and settings live in [CameraTopBar]; the mode strip
 * sits directly above this row.
 *
 * [deviceRotationDegrees] rotates the thumbnail, shutter and lens-switch icon in place (each wrapped in
 * its own [RotatedChrome]) so they stay upright to the person holding the phone, while the row itself
 * never moves.
 */
@Composable
fun BottomControlBar(
    isCapturing: Boolean,
    isShootReady: Boolean,
    score: Int,
    lastPhotoUri: Uri?,
    onShutterClick: () -> Unit,
    onSwitchLensClick: () -> Unit,
    onOpenGallery: () -> Unit,
    deviceRotationDegrees: Int = 0,
    awaitingSubject: Boolean = false,
    showQuietCheck: Boolean = false,
    animationsEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RotatedChrome(deviceRotationDegrees = deviceRotationDegrees) {
                GalleryThumbnail(photoUri = lastPhotoUri, onClick = onOpenGallery)
            }

            RotatedChrome(deviceRotationDegrees = deviceRotationDegrees) {
                ShutterButton(
                    isCapturing = isCapturing,
                    isShootReady = isShootReady,
                    score = score,
                    awaitingSubject = awaitingSubject,
                    animationsEnabled = animationsEnabled,
                    onClick = onShutterClick,
                )
            }

            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                RotatedChrome(deviceRotationDegrees = deviceRotationDegrees) {
                    IconButton(onClick = onSwitchLensClick, enabled = !isCapturing) {
                        Icon(imageVector = Icons.Outlined.Cameraswitch, contentDescription = "Switch camera", tint = OnScrim)
                    }
                }
            }
        }

        QuietStateCheck(
            visible = showQuietCheck,
            animationsEnabled = animationsEnabled,
            modifier = Modifier.align(Alignment.Center).offset(x = 62.dp),
        )
    }
}

/**
 * The quiet state's acknowledgement: a 20dp check mark beside the shutter for a second when advice clears
 * and the framing is already good — "hold this framing", said without words. It carries a content
 * description so TalkBack users get the same acknowledgement.
 */
@Composable
private fun QuietStateCheck(visible: Boolean, animationsEnabled: Boolean, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            shown = true
            delay(QUIET_CHECK_MS)
            shown = false
        } else {
            shown = false
        }
    }
    val fadeMs = motionDuration(Motion.CROSSFADE_MS, animationsEnabled)
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(fadeMs)),
        exit = fadeOut(tween(fadeMs)),
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = "Framing looks good, hold it",
            tint = Accent.Ready,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * A 44dp rounded-square thumbnail of [photoUri], the last photo captured — tapping opens it. Shows a plain
 * gallery-icon placeholder when there is no photo yet.
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
            Icon(imageVector = Icons.Outlined.PhotoLibrary, contentDescription = null, tint = OnScrim.copy(alpha = 0.6f))
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
            score = 64,
            lastPhotoUri = null,
            onShutterClick = {},
            onSwitchLensClick = {},
            onOpenGallery = {},
            animationsEnabled = false,
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
            score = 93,
            lastPhotoUri = null,
            onShutterClick = {},
            onSwitchLensClick = {},
            onOpenGallery = {},
            animationsEnabled = false,
        )
    }
}

@Preview(name = "Bottom bar, quiet check", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun BottomControlBarQuietPreview() {
    CompositionCoachTheme {
        BottomControlBar(
            isCapturing = false,
            isShootReady = false,
            score = 76,
            lastPhotoUri = null,
            onShutterClick = {},
            onSwitchLensClick = {},
            onOpenGallery = {},
            showQuietCheck = true,
            animationsEnabled = false,
        )
    }
}
