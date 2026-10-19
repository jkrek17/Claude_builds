package com.compositioncoach.app.ui.camera

import android.net.Uri
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneIntent

/**
 * What the camera chrome derives from [CameraUiState] each frame, in one place so the screen and its tests
 * agree. Pure (`CameraChromeModelTest`).
 *
 * @param pausedText the coaching layer's "Coaching paused in Night" chip, see [EnhancementCycle.pausedTextFor].
 */
data class CameraChromeModel(
    val hasScene: Boolean,
    val shootReady: Boolean,
    val quietCheck: Boolean,
    val pausedText: String?,
    val arcAlpha: Float,
) {
    companion object {
        fun of(state: CameraUiState): CameraChromeModel {
            val composition = state.composition
            val hasScene = composition.scene != SceneClassification.UNKNOWN
            val headline = GuidanceFormatter.headline(composition.activeRecommendations, state.settings.guidanceLevel)
            return CameraChromeModel(
                hasScene = hasScene,
                shootReady = GuidanceFormatter.effectiveShootReady(composition.isShootReady, composition.awaitingSubject),
                quietCheck = GuidanceFormatter.showsQuietCheck(
                    hasHeadline = headline != null,
                    displayScore = composition.displayScore,
                    isShootReady = composition.isShootReady,
                    hasScene = hasScene,
                ),
                pausedText = EnhancementCycle.pausedTextFor(state.enhancement, state.analysisPausedByEnhancement),
                arcAlpha = SteadyHint.arcAlpha(composition.awaitingSubject, state.steadiness),
            )
        }
    }
}

/**
 * Everything [CameraScreen] draws over the 4:3 preview from [state]: the coaching layer (paused while an
 * enhancement stops analysis), the debug geometry, the top strip with the enhancement pill, the debug panel and
 * the onboarding card. Stateless, so a Robolectric test composes it with a fake state.
 */
@Composable
fun BoxScope.CameraPreviewChrome(
    state: CameraUiState,
    animationsEnabled: Boolean,
    onFlashClick: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnhancementSelected: (CaptureEnhancement) -> Unit,
    onOnboardingDismissed: () -> Unit,
    coachingAlpha: Float = 1f,
    model: CameraChromeModel = CameraChromeModel.of(state),
) {
    CoachingLayer(
        composition = state.composition,
        guidanceLevel = state.settings.guidanceLevel,
        showScore = state.settings.showScore,
        hasScene = model.hasScene,
        deviceRotationDegrees = state.deviceRotationDegrees,
        animationsEnabled = animationsEnabled,
        modifier = Modifier.alpha(coachingAlpha),
        pausedText = model.pausedText,
    )

    if (state.settings.debugMode) {
        DebugGeometryOverlay(state.composition, state.debugFrame, deviceRotationDegrees = state.deviceRotationDegrees)
    }

    CameraTopBar(
        showDebugChip = state.settings.debugMode,
        flashMode = state.flashMode,
        hasFlashUnit = state.hasFlashUnit,
        onFlashClick = onFlashClick,
        onSettingsClick = onOpenSettings,
        deviceRotationDegrees = state.deviceRotationDegrees,
        modifier = Modifier.align(Alignment.TopCenter),
        enhancement = state.enhancement,
        availableEnhancements = state.availableEnhancements,
        onEnhancementSelected = onEnhancementSelected,
        animationsEnabled = animationsEnabled,
        enhancementsPausingCoaching = state.captureSession?.enhancementsPausingCoaching.orEmpty(),
    )

    if (state.settings.debugMode) {
        DebugOverlay(
            composition = state.composition,
            debugStats = state.debugStats,
            performanceTier = state.performanceTier.name,
            deviceRotationDegrees = state.deviceRotationDegrees,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
        )
    }

    if (!state.settings.onboardingSeen) {
        OnboardingCard(
            onDismiss = onOnboardingDismissed,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).padding(horizontal = 4.dp),
        )
    }
}

/**
 * [CameraBottomZone] fed from [state]: the "Hold still" hint (the steady-shot gate holding the shutter, or
 * MOVING while the shutter is engaged or the framing ready, held at least [SteadyHint.MIN_VISIBLE_MS]), the zoom
 * chips lit from [CameraUiState.selectedZoomPreset], the mode strip and the shutter row. [displayZoomRatio] is
 * the screen's instant pinch readout ([ZoomDisplayState]).
 */
@Composable
fun CameraBottomChrome(
    state: CameraUiState,
    displayZoomRatio: Float,
    zoomChipVisible: Boolean,
    lastPhotoUri: Uri?,
    animationsEnabled: Boolean,
    onShutterClick: () -> Unit,
    onSwitchLensClick: () -> Unit,
    onOpenGallery: () -> Unit,
    onSceneIntentSelected: (SceneIntent) -> Unit,
    onZoomPresetSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
    shootAtPeak: ShootAtPeakState? = null,
    model: CameraChromeModel = CameraChromeModel.of(state),
) {
    val holdStill = rememberHoldStillVisible(
        SteadyHint.wanted(
            waitingForSteady = state.isWaitingForSteady,
            steadiness = state.steadiness,
            shutterEngaged = shootAtPeak?.engaged == true || state.isCapturing,
            shootReady = model.shootReady,
        ),
    )
    CameraBottomZone(
        sceneIntent = state.settings.sceneIntent,
        onSceneIntentSelected = onSceneIntentSelected,
        isCapturing = state.isCapturing,
        isShootReady = model.shootReady,
        score = state.composition.displayScore,
        awaitingSubject = state.composition.awaitingSubject,
        showQuietCheck = model.quietCheck,
        analysisUnavailable = state.analysisUnavailable,
        zoomRatio = displayZoomRatio,
        zoomChipVisible = zoomChipVisible,
        lastPhotoUri = lastPhotoUri,
        onShutterClick = onShutterClick,
        onSwitchLensClick = onSwitchLensClick,
        onOpenGallery = onOpenGallery,
        deviceRotationDegrees = state.deviceRotationDegrees,
        animationsEnabled = animationsEnabled,
        modifier = modifier,
        holdStill = holdStill,
        arcAlpha = model.arcAlpha,
        zoomPresets = state.zoomPresets,
        onZoomPresetSelected = onZoomPresetSelected,
        shootAtPeak = shootAtPeak,
        selectedZoomPreset = state.selectedZoomPreset,
    )
}
