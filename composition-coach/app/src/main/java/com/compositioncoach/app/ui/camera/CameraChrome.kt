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
import com.compositioncoach.app.outcome.ExperienceMode
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.app.ui.settings.UiPreferences
import com.compositioncoach.composition.model.SceneClassification

/** The coaching layer's alpha while a Motion/Night hold runs: the progress ring is the thing to watch. */
const val HOLD_DIMMED_ALPHA = 0.35f

/**
 * What the camera chrome derives from [CameraUiState] and the [UiPreferences] each frame, in one place so the
 * screen and its tests agree. Pure (`CameraChromeModelTest`).
 *
 * Easy (default) shows no number, one chip, no secondary icons and no reasons; Coach adds all three
 * (`docs/PRODUCT.md`, "Two experiences"). [pro] reveals the exposure slider, zoom presets, enhancement pill,
 * grid and developer overlays; without it they are not drawn at all.
 *
 * @param pausedText the coaching layer's "Coaching paused in Night" chip, see [EnhancementCycle.pausedTextFor].
 * @param overrideChip what replaces the cue chip: a hold instruction, group blocker or "Arm out a little".
 * @param shootReady in a group scene this is the group's readiness, not the single-frame `isShootReady`.
 */
data class CameraChromeModel(
    val hasScene: Boolean,
    val shootReady: Boolean,
    val quietCheck: Boolean,
    val pausedText: String?,
    val arcAlpha: Float,
    val experience: ExperienceMode = ExperienceMode.EASY,
    val pro: Boolean = false,
    val showScore: Boolean = false,
    val overrideChip: LiveChip? = null,
    val coachingAlpha: Float = 1f,
    val headsUp: HeadsUpCue = HeadsUpCue(null, null, false),
) {
    val coach: Boolean get() = experience == ExperienceMode.COACH

    companion object {
        fun of(state: CameraUiState, preferences: UiPreferences = UiPreferences()): CameraChromeModel {
            val composition = state.composition
            val hasScene = composition.scene != SceneClassification.UNKNOWN
            val experience = UiPreferences.effectiveExperience(state.experienceMode, preferences.experienceMode)
            val headline = GuidanceFormatter.headline(composition.activeRecommendations, state.settings.guidanceLevel)
            val singleFrameReady = GuidanceFormatter.effectiveShootReady(composition.isShootReady, composition.awaitingSubject)
            val shootReady = state.groupStatus?.let { it.ready && !composition.awaitingSubject } ?: singleFrameReady
            val overrideChip = LiveChipRules.overrideChip(state)
            val pausedText = EnhancementCycle.pausedTextFor(state.enhancement, state.analysisPausedByEnhancement)
            val spokenHeadline = headline.takeIf { pausedText == null && overrideChip == null && !shootReady && !composition.awaitingSubject }
            return CameraChromeModel(
                hasScene = hasScene,
                shootReady = shootReady,
                quietCheck = GuidanceFormatter.showsQuietCheck(
                    hasHeadline = headline != null,
                    displayScore = composition.displayScore,
                    isShootReady = composition.isShootReady,
                    hasScene = hasScene,
                ),
                pausedText = pausedText,
                arcAlpha = SteadyHint.arcAlpha(composition.awaitingSubject, state.steadiness),
                experience = experience,
                pro = preferences.proControls,
                showScore = experience == ExperienceMode.COACH && state.settings.showScore,
                overrideChip = overrideChip,
                coachingAlpha = if (state.holdProgress != null) HOLD_DIMMED_ALPHA else 1f,
                headsUp = HeadsUpCue(
                    direction = spokenHeadline?.direction,
                    chipText = when {
                        shootReady -> GuidanceFormatter.SHOOT_TEXT
                        overrideChip != null -> overrideChip.text
                        spokenHeadline != null -> GuidanceFormatter.chipText(spokenHeadline)
                        else -> null
                    },
                    shootReady = shootReady,
                ),
            )
        }
    }
}

/**
 * Everything [CameraScreen] draws over the 4:3 preview from [state]: the re-shoot ghost, the coaching layer
 * (dimmed during a hold, quiet while an enhancement pauses analysis), the debug geometry (Pro + developer mode),
 * the top strip (flash, timer, enhancement pill under Pro, settings), the timer countdown, the degraded-outcome
 * note and the onboarding card. Stateless, so a Robolectric test composes it with a fake state.
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
    onTimerSelected: (Int) -> Unit = {},
    onGhostDismiss: () -> Unit = {},
) {
    val debug = model.pro && state.settings.debugMode

    GhostOverlay(rect = state.ghostRect, deviceRotationDegrees = state.deviceRotationDegrees, onDismiss = onGhostDismiss)

    CoachingLayer(
        composition = state.composition,
        guidanceLevel = state.settings.guidanceLevel,
        showScore = model.showScore,
        hasScene = model.hasScene,
        deviceRotationDegrees = state.deviceRotationDegrees,
        animationsEnabled = animationsEnabled,
        modifier = Modifier.alpha(coachingAlpha),
        pausedText = model.pausedText,
        showSecondary = model.coach,
        showReason = model.coach,
        overrideChip = model.overrideChip,
        cueAlpha = model.coachingAlpha,
    )

    if (debug) {
        DebugGeometryOverlay(state.composition, state.debugFrame, deviceRotationDegrees = state.deviceRotationDegrees)
    }

    CameraTopBar(
        showDebugChip = debug,
        flashMode = state.flashMode,
        hasFlashUnit = state.hasFlashUnit,
        onFlashClick = onFlashClick,
        onSettingsClick = onOpenSettings,
        deviceRotationDegrees = state.deviceRotationDegrees,
        modifier = Modifier.align(Alignment.TopCenter),
        enhancement = state.enhancement,
        availableEnhancements = if (model.pro) state.availableEnhancements else emptySet(),
        onEnhancementSelected = onEnhancementSelected,
        animationsEnabled = animationsEnabled,
        enhancementsPausingCoaching = state.captureSession?.enhancementsPausingCoaching.orEmpty(),
        timerSeconds = state.timerSeconds,
        outcome = state.outcome,
        onTimerSelected = onTimerSelected,
    )

    TimerCountdownOverlay(
        countdown = state.timerCountdown,
        deviceRotationDegrees = state.deviceRotationDegrees,
        animationsEnabled = animationsEnabled,
    )

    DegradedNoteChip(
        note = state.outcomeDegradedNote,
        outcomeKey = state.outcome,
        animationsEnabled = animationsEnabled,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
    )

    if (debug) {
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
 * [CameraBottomZone] fed from [state]: the "Hold still" hint, the zoom chips (Pro only), the outcome strip, the
 * shutter row with the hold ring and group dots, and the gallery thumbnail's long-press menu.
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
    onOutcomeSelected: (Outcome) -> Unit,
    onZoomPresetSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
    shootAtPeak: ShootAtPeakState? = null,
    model: CameraChromeModel = CameraChromeModel.of(state),
    galleryMenu: GalleryMenuActions = GalleryMenuActions(),
) {
    val holdStill = rememberHoldStillVisible(
        SteadyHint.wanted(
            waitingForSteady = state.isWaitingForSteady,
            steadiness = state.steadiness,
            shutterEngaged = shootAtPeak?.engaged == true || state.isCapturing || state.holdProgress != null,
            shootReady = model.shootReady,
        ),
    )
    CameraBottomZone(
        outcome = state.outcome,
        onOutcomeSelected = onOutcomeSelected,
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
        zoomPresets = if (model.pro) state.zoomPresets else emptyList(),
        onZoomPresetSelected = onZoomPresetSelected,
        shootAtPeak = shootAtPeak,
        selectedZoomPreset = state.selectedZoomPreset,
        holdProgress = state.holdProgress,
        groupStatus = state.groupStatus,
        galleryMenu = galleryMenu,
    )
}
