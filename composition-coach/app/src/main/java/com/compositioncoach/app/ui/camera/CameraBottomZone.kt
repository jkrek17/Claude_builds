package com.compositioncoach.app.ui.camera

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.OnScrimMuted
import com.compositioncoach.composition.model.SceneIntent

/**
 * Everything below the preview: the zoom readout and the "Analysis unavailable" chip at the top of the
 * black area, then — stacked upward from the bottom edge — the "SHOOT" / "Hold still" chip, the zoom
 * presets, the mode strip, and the shutter row. That order is the spec's ("mode strip directly above the shutter"), and none of it
 * ever moves when the phone rotates; only the icons inside it counter-rotate.
 */
@Composable
fun CameraBottomZone(
    sceneIntent: SceneIntent,
    onSceneIntentSelected: (SceneIntent) -> Unit,
    isCapturing: Boolean,
    isShootReady: Boolean,
    score: Int,
    awaitingSubject: Boolean,
    showQuietCheck: Boolean,
    analysisUnavailable: Boolean,
    zoomRatio: Float,
    zoomChipVisible: Boolean,
    lastPhotoUri: Uri?,
    onShutterClick: () -> Unit,
    onSwitchLensClick: () -> Unit,
    onOpenGallery: () -> Unit,
    deviceRotationDegrees: Int,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier,
    holdStill: Boolean = false,
    arcAlpha: Float = if (awaitingSubject) GuidanceFormatter.AWAITING_SUBJECT_ARC_ALPHA else 1f,
    zoomPresets: List<Float> = emptyList(),
    onZoomPresetSelected: (Float) -> Unit = {},
    shootAtPeak: ShootAtPeakState? = null,
) {
    Box(modifier.fillMaxWidth()) {
        if (analysisUnavailable) {
            Text(
                text = "Analysis unavailable",
                color = OnScrimMuted,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            )
        }

        ZoomChip(
            zoomRatio = zoomRatio,
            visible = zoomChipVisible,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val fadeMs = motionDuration(Motion.CROSSFADE_MS, animationsEnabled)
            // One chip slot above the shutter: "Hold still" while movement is holding the shot back,
            // otherwise "SHOOT" while the framing is ready. The chip crossfades its own wording.
            AnimatedVisibility(
                visible = isShootReady || holdStill,
                enter = fadeIn(tween(fadeMs)),
                exit = fadeOut(tween(fadeMs)),
            ) {
                CueChip(
                    text = if (holdStill) SteadyHint.TEXT else GuidanceFormatter.SHOOT_TEXT,
                    description = if (holdStill) SteadyHint.DESCRIPTION else "Framing is ready, shoot now",
                    emphasized = !holdStill,
                    animationsEnabled = animationsEnabled,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            ZoomPresetRow(
                presets = zoomPresets,
                zoomRatio = zoomRatio,
                onSelect = onZoomPresetSelected,
                deviceRotationDegrees = deviceRotationDegrees,
                animationsEnabled = animationsEnabled,
            )

            ModeStrip(
                selected = sceneIntent,
                onSelect = onSceneIntentSelected,
                animationsEnabled = animationsEnabled,
            )

            BottomControlBar(
                isCapturing = isCapturing,
                isShootReady = isShootReady,
                score = score,
                lastPhotoUri = lastPhotoUri,
                onShutterClick = onShutterClick,
                onSwitchLensClick = onSwitchLensClick,
                onOpenGallery = onOpenGallery,
                deviceRotationDegrees = deviceRotationDegrees,
                awaitingSubject = awaitingSubject,
                showQuietCheck = showQuietCheck,
                animationsEnabled = animationsEnabled,
                arcAlpha = arcAlpha,
                shootAtPeak = shootAtPeak,
            )
        }
    }
}

@Preview(name = "Bottom zone", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun CameraBottomZonePreview() {
    CompositionCoachTheme {
        CameraBottomZone(
            sceneIntent = SceneIntent.PORTRAIT,
            onSceneIntentSelected = {},
            isCapturing = false,
            isShootReady = false,
            score = 64,
            awaitingSubject = false,
            showQuietCheck = false,
            analysisUnavailable = false,
            zoomRatio = 1f,
            zoomChipVisible = false,
            lastPhotoUri = null,
            onShutterClick = {},
            onSwitchLensClick = {},
            onOpenGallery = {},
            deviceRotationDegrees = 0,
            animationsEnabled = false,
            zoomPresets = listOf(0.6f, 1f, 2f, 5f),
        )
    }
}

@Preview(name = "Bottom zone, shoot ready", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun CameraBottomZoneReadyPreview() {
    CompositionCoachTheme {
        CameraBottomZone(
            sceneIntent = SceneIntent.AUTO,
            onSceneIntentSelected = {},
            isCapturing = false,
            isShootReady = true,
            score = 93,
            awaitingSubject = false,
            showQuietCheck = false,
            analysisUnavailable = false,
            zoomRatio = 1f,
            zoomChipVisible = false,
            lastPhotoUri = null,
            onShutterClick = {},
            onSwitchLensClick = {},
            onOpenGallery = {},
            deviceRotationDegrees = 0,
            animationsEnabled = false,
        )
    }
}

@Preview(name = "Bottom zone, rotation 90", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun CameraBottomZoneRotatedPreview() {
    CompositionCoachTheme {
        CameraBottomZone(
            sceneIntent = SceneIntent.LANDSCAPE,
            onSceneIntentSelected = {},
            isCapturing = false,
            isShootReady = false,
            score = 81,
            awaitingSubject = false,
            showQuietCheck = true,
            analysisUnavailable = false,
            zoomRatio = 1f,
            zoomChipVisible = false,
            lastPhotoUri = null,
            onShutterClick = {},
            onSwitchLensClick = {},
            onOpenGallery = {},
            deviceRotationDegrees = 90,
            animationsEnabled = false,
            zoomPresets = listOf(0.6f, 1f, 2f, 5f),
        )
    }
}

@Preview(name = "Bottom zone, hold still", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun CameraBottomZoneHoldStillPreview() {
    CompositionCoachTheme {
        CameraBottomZone(
            sceneIntent = SceneIntent.AUTO,
            onSceneIntentSelected = {},
            isCapturing = false,
            isShootReady = true,
            score = 92,
            awaitingSubject = false,
            showQuietCheck = false,
            analysisUnavailable = false,
            zoomRatio = 1.4f,
            zoomChipVisible = false,
            lastPhotoUri = null,
            onShutterClick = {},
            onSwitchLensClick = {},
            onOpenGallery = {},
            deviceRotationDegrees = 0,
            animationsEnabled = false,
            holdStill = true,
            arcAlpha = SteadyHint.MOVING_ARC_ALPHA,
            zoomPresets = listOf(0.6f, 1f, 2f),
        )
    }
}
