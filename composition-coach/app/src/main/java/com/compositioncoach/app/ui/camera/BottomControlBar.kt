package com.compositioncoach.app.ui.camera

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.Accent
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.composition.model.GroupStatus
import kotlinx.coroutines.delay

/** How long the quiet-state check mark stays beside the shutter. */
private const val QUIET_CHECK_MS = 1_000L

/** The word under an armed shutter. The shutter's own description carries the meaning for TalkBack. */
const val AUTO_ARMED_LABEL = "Auto"

/**
 * Bottom transparent control row, Pixel-style: a gallery thumbnail (opens the last photo; long-press for
 * "Your eye" / "Use as guide"), the shutter with its readiness arc (its inner ring shows a Motion/Night
 * [holdProgress], and [GroupDots] sit around it in group scenes), and the lens switch. Flash and
 * settings live in [CameraTopBar]; the outcome strip
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
    arcAlpha: Float = if (awaitingSubject) GuidanceFormatter.AWAITING_SUBJECT_ARC_ALPHA else 1f,
    shootAtPeak: ShootAtPeakState? = null,
    modifier: Modifier = Modifier,
    holdProgress: Float? = null,
    groupStatus: GroupStatus? = null,
    galleryMenu: GalleryMenuActions = GalleryMenuActions(),
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RotatedChrome(deviceRotationDegrees = deviceRotationDegrees) {
                GalleryThumbnail(photoUri = lastPhotoUri, onClick = onOpenGallery, menu = galleryMenu)
            }

            RotatedChrome(deviceRotationDegrees = deviceRotationDegrees) {
                Box(contentAlignment = Alignment.Center) {
                ShutterButton(
                    isCapturing = isCapturing,
                    isShootReady = isShootReady,
                    score = score,
                    awaitingSubject = awaitingSubject,
                    animationsEnabled = animationsEnabled,
                    onClick = onShutterClick,
                    arcAlpha = arcAlpha,
                    // A Motion/Night hold fills the same inner ring the armed countdown drains.
                    autoCountdown = holdProgress?.coerceIn(0f, 1f) ?: shootAtPeak?.countdown,
                    onPress = shootAtPeak?.let { peak -> { peak.press() } },
                    onRelease = shootAtPeak?.let { peak -> { cancelled -> peak.release(cancelled) } },
                    onAccessibilityLongClick = shootAtPeak?.let { peak -> { peak.armForAccessibility() } },
                )
                groupStatus?.let { GroupDots(it) }
                }
            }

            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                RotatedChrome(deviceRotationDegrees = deviceRotationDegrees) {
                    IconButton(onClick = onSwitchLensClick, enabled = !isCapturing) {
                        Icon(imageVector = Icons.Outlined.Cameraswitch, contentDescription = "Switch camera", tint = OnScrim)
                    }
                }
            }
        }

        AutoArmedLabel(
            visible = shootAtPeak?.armed == true,
            deviceRotationDegrees = deviceRotationDegrees,
            animationsEnabled = animationsEnabled,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
        )

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

/** The tiny "Auto" under the shutter while shoot-at-the-peak is armed, so the waiting shutter explains itself. */
@Composable
private fun AutoArmedLabel(
    visible: Boolean,
    deviceRotationDegrees: Int,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val fadeMs = motionDuration(Motion.CROSSFADE_MS, animationsEnabled)
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(fadeMs)), exit = fadeOut(tween(fadeMs)), modifier = modifier) {
        RotatedChrome(deviceRotationDegrees = deviceRotationDegrees) {
            Text(
                text = AUTO_ARMED_LABEL,
                color = OnScrim,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clearAndSetSemantics { },
            )
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

@Preview(name = "Bottom bar, auto armed, rotation 90", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun BottomControlBarArmedRotatedPreview() {
    val peak = remember { ShootAtPeakState(ShootAtPeakController(), clock = { 0L }).also { it.armForAccessibility() } }
    CompositionCoachTheme {
        BottomControlBar(
            isCapturing = false,
            isShootReady = false,
            score = 84,
            lastPhotoUri = null,
            onShutterClick = {},
            onSwitchLensClick = {},
            onOpenGallery = {},
            deviceRotationDegrees = 90,
            animationsEnabled = false,
            shootAtPeak = peak,
        )
    }
}

@Preview(name = "Bottom bar, hold progress and group dots", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun BottomControlBarHoldPreview() {
    CompositionCoachTheme {
        BottomControlBar(
            isCapturing = false,
            isShootReady = false,
            score = 80,
            lastPhotoUri = null,
            onShutterClick = {},
            onSwitchLensClick = {},
            onOpenGallery = {},
            animationsEnabled = false,
            holdProgress = 0.4f,
            groupStatus = GroupStatus(ready = false, facesTotal = 3, facesReady = 2),
        )
    }
}
