package com.compositioncoach.app.ui.camera

import android.net.Uri
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.camera.CapturePlan
import com.compositioncoach.app.camera.CaptureSessionInfo
import com.compositioncoach.app.camera.ZoomPresets
import com.compositioncoach.app.camera.Steadiness
import com.compositioncoach.app.outcome.ExperienceMode
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.app.outcome.OutcomePlanner
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.app.camera.FlashMode
import com.compositioncoach.app.camera.LensFacing
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.composition.model.DetectedObject
import com.compositioncoach.composition.model.SmoothedComposition
import com.compositioncoach.composition.model.SubjectMask
import com.compositioncoach.vision.PerformanceTier

/** Rolling stats shown only in debug mode. */
data class DebugStats(
    val fps: Float = 0f,
    val lastLatencyMs: Long = 0L,
    val samplingIntervalMs: Long = CoachSettings.DEFAULT_INTERVAL_MS,
    val engineTimeMs: Long = 0L,
    val detectorTimings: Map<String, Long> = emptyMap(),
    /**
     * The clockwise rotation `:vision` applied to the raw camera buffer to reach physical-up coordinates
     * for the latest frame (`FrameAnalysis.analysisRotationDegrees`). Shown on the debug overlay's `Rot:`
     * line as `upright=`, next to the device rotation it was derived from — the two together make an
     * on-device rotation bug readable without a screenshot.
     */
    val analysisRotationDegrees: Int = 0,
    /**
     * The latest frame's `DeviceOrientation.rollDegrees`: the residual tilt after re-referencing to the
     * quantized device rotation, so a level hold reads ~0 in every orientation and positive means the
     * horizon appears rotated clockwise in the analysis frame. Shown as `roll=` on the same line.
     */
    val rollDegrees: Float = 0f,
)

/**
 * Raw per-frame detector output the debug overlays draw directly ([DebugGeometryOverlay]'s object boxes
 * and mask heat layer) — kept separate from [com.compositioncoach.composition.model.CompositionResult]
 * because [DetectedObject]/[SubjectMask] describe every detection, not just the ones that became a
 * [com.compositioncoach.composition.model.DetectedSubject].
 */
data class DebugFrameData(
    val objects: List<DetectedObject> = emptyList(),
    val subjectMask: SubjectMask? = null,
)

/** Everything [CameraScreen] needs to render. Owned and reduced by [CameraViewModel]. */
data class CameraUiState(
    val composition: SmoothedComposition = SmoothedComposition.EMPTY,
    val lensFacing: LensFacing = LensFacing.BACK,
    val flashMode: FlashMode = FlashMode.OFF,
    val hasFlashUnit: Boolean = false,
    val isCapturing: Boolean = false,
    val settings: CoachSettings = CoachSettings(),
    val debugStats: DebugStats = DebugStats(),
    val debugFrame: DebugFrameData = DebugFrameData(),
    val analysisUnavailable: Boolean = false,
    val cameraError: String? = null,
    val captureError: String? = null,
    /** True right after a capture finishes, until [withPostCaptureFadeEnded] — see that function's doc. */
    val postCaptureFadeActive: Boolean = false,
    /**
     * The device-health-driven cadence tier currently applied to the vision pipeline (see
     * `com.compositioncoach.app.camera.ThermalPolicy`). Surfaced here for the debug overlay; FULL is the
     * default and everyday value on a device that is neither thermally throttled nor in battery saver.
     */
    val performanceTier: PerformanceTier = PerformanceTier.FULL,
    /**
     * The bound camera's supported exposure-compensation index range, converted from
     * [com.compositioncoach.app.camera.CameraBindResult.Success.exposureRange] (an `android.util.Range`,
     * which — unlike this plain Kotlin [IntRange] — cannot be exercised in a plain JVM unit test: its
     * accessors are stubbed to return null under Gradle's mockable android.jar, which then NPEs on
     * unboxing). Both bounds `0` means the device doesn't support exposure compensation (or no camera has
     * bound yet) — a slider driven by this should hide itself in that case rather than render a
     * zero-width range.
     */
    val exposureRange: IntRange = 0..0,
    /** Current exposure-compensation index, for a slider's position — see [onExposureCompensationChanged]. */
    val exposureIndex: Int = 0,
    /**
     * The most recently saved photo's [Uri] (process-lifetime only — not persisted across process death;
     * see `CaptureRepository.latestPhotoUri` for restoring it on a fresh process start), for a
     * gallery-shortcut thumbnail button. Null until the first successful capture this process.
     */
    val lastPhotoUri: Uri? = null,
    /**
     * The latest `FrameAnalysis.deviceRotationDegrees` (0/90/180/270) — the phone's quantized physical
     * rotation from natural portrait. Drives the Pixel-style "controls rotate in place" chrome
     * ([rememberControlCounterRotation]) and [OverlayMapper]'s physical-up-to-display rotation, since the
     * app itself stays portrait-locked and the preview content never rotates. Defaults to 0 (natural
     * portrait) until the first frame arrives.
     */
    val deviceRotationDegrees: Int = 0,

    // --- Image quality contract (camera engineer implements, UI engineer consumes) -------------------
    /** Enhancement pipelines the bound lens supports; empty until the camera reports them. */
    val availableEnhancements: Set<CaptureEnhancement> = emptySet(),
    /** The enhancement the next capture will use (persisted in settings; AUTO when supported, else OFF). */
    val enhancement: CaptureEnhancement = CaptureEnhancement.OFF,
    /** Gyroscope-derived steadiness for the "Hold still" hint and steady-shot gating. */
    val steadiness: Steadiness = Steadiness.STEADY,
    /** Current zoom ratio and the preset chips to offer (e.g. 0.6, 1, 2), already clamped to the lens. */
    val zoomRatio: Float = 1f,
    val zoomPresets: List<Float> = listOf(1f),

    // --- Outcomes, holds, timer, group, ghost---
    /** The photograph the user asked for; drives scene intent, pipeline and the hold instruction. */
    val outcome: Outcome = Outcome.DEFAULT,
    /** Easy (default) or Coach experience. */
    val experienceMode: ExperienceMode = ExperienceMode.EASY,
    /** 0..1 progress of a Motion/Night hold in progress, null when no hold is running. */
    val holdProgress: Float? = null,
    /** The chip to show while the outcome needs something from the user ("Hold still for 4 seconds"). */
    val holdInstruction: String? = null,
    /** Group readiness from the latest frame (null outside group scenes). */
    val groupStatus: com.compositioncoach.composition.model.GroupStatus? = null,
    /** Self-timer length in seconds (0 = off) and the running countdown (null when idle). */
    val timerSeconds: Int = 0,
    val timerCountdown: Int? = null,
    /** Outline of the previous shot's subject for the re-shoot ghost (null = none). */
    val ghostRect: com.compositioncoach.composition.model.NormalizedRect? = null,
    /** Whether the current outcome had to fall back on this phone (`OutcomeCapturePlanner`'s plan for the bound lens). */
    val outcomeDegraded: Boolean = false,
    /** The once-per-pick note saying so, as a translatable [DegradedNote] (null when nothing to say). */
    val outcomeDegradedNote: DegradedNote? = null,
    /**
     * True when the selected enhancement runs in a vendor extensions session that cannot also carry
     * ImageAnalysis, so live coaching is paused while it is selected (the camera still previews and captures).
     * The UI should show a small note (e.g. "Coaching paused in Night") rather than the analysis-unavailable
     * chip, since switching the enhancement back to Off or Auto restores coaching immediately.
     */
    val analysisPausedByEnhancement: Boolean = false,
    /**
     * True from the shutter press until the capture is actually taken while steady-shot gating waits (at
     * most 450 ms) for the phone to stop MOVING; the UI can show "Hold still" during it.
     */
    val isWaitingForSteady: Boolean = false,
    /**
     * What the current bind configured (active/requested enhancement, capture stream size and whether it is
     * the full sensor resolution, capture mode). Null until the first successful bind. For the debug overlay.
     */
    val captureSession: CaptureSessionInfo? = null,
)

/** The zoom preset chip that matches [CameraUiState.zoomRatio] (within 5%), or null between presets. */
val CameraUiState.selectedZoomPreset: Float?
    get() = ZoomPresets.selectedPreset(zoomPresets, zoomRatio)

/**
 * Whether the next shutter press will run lucky imaging (three frames, keep the sharpest): the user's
 * burst_sharpest setting, no extension handling the capture, not conserving power, and the flash not armed
 * (three flash pops would be both slow and differently lit, so the sharpness comparison would be meaningless).
 */
val CameraUiState.burstSharpestActive: Boolean
    get() = settings.burstSharpestEffective(enhancement, preferFastCapture) && flashMode == FlashMode.OFF

/**
 * Whether the *next* camera bind should trade capture quality for shutter speed — true when either the
 * user's own battery-saver setting or a device-health-driven [performanceTier] below FULL calls for it.
 * The UI passes this to `CameraController.setPreferFastCapture` before/at bind time (see that function's
 * KDoc for why it only takes effect on the next bind, not live on an already-bound `ImageCapture`).
 */
val CameraUiState.preferFastCapture: Boolean
    get() = settings.batterySaver || performanceTier != PerformanceTier.FULL

// The functions below are pure state transforms with no Android dependency, extracted so the
// camera view model's reducer logic is unit-testable on the plain JVM (see CameraUiStateTest).

/**
 * New settings. The experience mode and timer length mirror their settings. The outcome is not taken from
 * here: [CameraViewModel] applies it ([withOutcome]) with its side effects, ignoring a stale emission while a
 * just-selected outcome is still being saved.
 */
fun CameraUiState.withSettings(settings: CoachSettings): CameraUiState = copy(
    settings = settings,
    experienceMode = settings.experienceMode,
    timerSeconds = settings.timerSeconds,
)

// --- Wave 2 reducers -------------------------------------------------------------------------------------

/** Whether the score number shows: Coach mode and the user's stored preference (Easy shows the ring only). */
val CameraUiState.showScoreNumber: Boolean get() = settings.effectiveShowScore(experienceMode)

/**
 * The shoot-at-the-peak condition (with steadiness on top, see `SteadyHint.peakReached`): in a group scene
 * (the engine reports a [GroupStatus][com.compositioncoach.composition.model.GroupStatus], or the Group outcome
 * is selected and no group is in frame yet) everyone must be ready; otherwise the usual shoot-ready.
 */
val CameraUiState.peakReady: Boolean
    get() = when {
        groupStatus != null -> groupStatus.ready
        outcome == Outcome.GROUP -> false
        else -> composition.isShootReady
    }

/** A hold is pending while a hold outcome's capture runs (from the shutter to the saved file) or reports progress. */
private val CameraUiState.holdPending: Boolean get() = isCapturing || holdProgress != null

/** Selects [outcome]; re-derives the hold instruction. Degradation is set separately ([withOutcomePlan]). */
fun CameraUiState.withOutcome(outcome: Outcome): CameraUiState =
    copy(outcome = outcome, holdInstruction = OutcomePlanner.holdInstruction(outcome, holdPending))

/** Whether the selected outcome had to fall back on this phone ([plan] for the bound lens), and the note saying so. */
fun CameraUiState.withOutcomePlan(plan: CapturePlan): CameraUiState =
    copy(outcomeDegraded = plan.degraded, outcomeDegradedNote = DegradedNote.of(plan))

/** The capture port's hold progress (0..1, null when no hold runs). */
fun CameraUiState.withHoldProgress(progress: Float?): CameraUiState {
    val next = copy(holdProgress = progress?.coerceIn(0f, 1f))
    return next.copy(holdInstruction = OutcomePlanner.holdInstruction(outcome, next.holdPending))
}

/** The self-timer's displayed value (seconds left, 0 while a group hand-over waits, null when idle). */
fun CameraUiState.withTimerCountdown(value: Int?): CameraUiState = copy(timerCountdown = value)

/** The re-shoot ghost outline (null clears it). */
fun CameraUiState.withGhost(rect: NormalizedRect?): CameraUiState = copy(ghostRect = rect)

/**
 * A (re)bind succeeded. CameraX resets exposure compensation to 0 whenever a camera is rebound, so the
 * slider's index resets too rather than displaying a value the hardware no longer has. When [session] is
 * given, the enhancement chips, the coaching-paused note and the zoom presets follow the freshly bound lens.
 */
fun CameraUiState.withCameraBound(
    lensFacing: LensFacing,
    hasFlashUnit: Boolean,
    exposureRange: IntRange = 0..0,
    session: CaptureSessionInfo? = null,
): CameraUiState {
    val bound = copy(
        lensFacing = lensFacing,
        hasFlashUnit = hasFlashUnit,
        cameraError = null,
        exposureRange = exposureRange,
        exposureIndex = 0.coerceIn(exposureRange),
    )
    if (session == null) return bound
    return bound.copy(
        availableEnhancements = session.availableEnhancements,
        enhancement = session.activeEnhancement,
        analysisPausedByEnhancement = session.analysisPausedByEnhancement,
        captureSession = session,
    ).withZoomState(zoomRatio, session.minZoomRatio, session.maxZoomRatio)
}

/** The camera's live zoom state changed (pinch, preset, or a lens switch); re-derives the preset chips. */
fun CameraUiState.withZoomState(ratio: Float, minRatio: Float, maxRatio: Float): CameraUiState {
    val safeMin = if (minRatio > 0f) minRatio else 1f
    val safeMax = if (maxRatio >= safeMin) maxRatio else safeMin
    return copy(zoomRatio = ratio.coerceIn(safeMin, safeMax), zoomPresets = ZoomPresets.compute(safeMin, safeMax))
}

/**
 * The phone's quantized physical rotation from the gyro/gravity fallback (`MotionSensor`), used only while no
 * `FrameAnalysis` is arriving to carry it (analysis paused by an enhancement, or unavailable). Drives the same
 * chrome rotation and capture EXIF orientation as the frame-borne value.
 */
fun CameraUiState.withDeviceRotation(deviceRotationDegrees: Int): CameraUiState = copy(deviceRotationDegrees = deviceRotationDegrees)

/** Latest gyro classification, see [CameraUiState.steadiness]. */
fun CameraUiState.withSteadiness(steadiness: Steadiness): CameraUiState = copy(steadiness = steadiness)

/** Steady-shot gating started or ended, see [CameraUiState.isWaitingForSteady]. */
fun CameraUiState.withWaitingForSteady(waiting: Boolean): CameraUiState = copy(isWaitingForSteady = waiting)

/** The user picked an enhancement; shown immediately while the camera rebinds to it. */
fun CameraUiState.withEnhancementRequested(enhancement: CaptureEnhancement): CameraUiState = copy(enhancement = enhancement)

/** Applies a new device-health-driven cadence tier; see [CameraUiState.performanceTier]'s doc. */
fun CameraUiState.withPerformanceTier(tier: PerformanceTier): CameraUiState = copy(performanceTier = tier)

/** Records the exposure-compensation index a slider (or [preferFastCapture]-style control) just applied. */
fun CameraUiState.withExposureIndex(index: Int): CameraUiState =
    copy(exposureIndex = index.coerceIn(exposureRange))

/** Records the most recently saved photo, for a gallery-shortcut thumbnail button. */
fun CameraUiState.withLastPhotoUri(uri: Uri?): CameraUiState = copy(lastPhotoUri = uri)

fun CameraUiState.withCameraError(message: String): CameraUiState = copy(cameraError = message)

fun CameraUiState.withFlashMode(mode: FlashMode): CameraUiState = copy(flashMode = mode)

/** The shutter fired. A hold outcome shows its instruction from here until the file is saved. */
fun CameraUiState.withCaptureStarted(): CameraUiState =
    copy(isCapturing = true, captureError = null, holdInstruction = OutcomePlanner.holdInstruction(outcome, holdPending = true))

/**
 * Capture finished successfully: stops the shutter's disabled state and starts the post-capture fade
 * (score badge / guidance banner drop to 30% opacity) so the cut to the review screen doesn't read as an
 * abrupt jump from full-opacity UI. [CameraViewModel] clears [postCaptureFadeActive] again ~1s later via
 * [withPostCaptureFadeEnded], whether or not navigation actually happened by then.
 */
fun CameraUiState.withCaptureFinished(): CameraUiState = copy(
    isCapturing = false,
    isWaitingForSteady = false,
    postCaptureFadeActive = true,
    holdProgress = null,
    holdInstruction = null,
    ghostRect = null,
)

fun CameraUiState.withCaptureError(message: String): CameraUiState = copy(
    isCapturing = false,
    isWaitingForSteady = false,
    captureError = message,
    postCaptureFadeActive = false,
    holdProgress = null,
    holdInstruction = null,
)

/** Ends the post-capture fade window started by [withCaptureFinished]. */
fun CameraUiState.withPostCaptureFadeEnded(): CameraUiState = copy(postCaptureFadeActive = false)

fun CameraUiState.withFrameUpdate(
    composition: SmoothedComposition,
    latencyMs: Long,
    fps: Float,
    samplingIntervalMs: Long,
    engineTimeMs: Long,
    detectorTimings: Map<String, Long> = emptyMap(),
    debugFrame: DebugFrameData = DebugFrameData(),
    deviceRotationDegrees: Int = 0,
    analysisRotationDegrees: Int = 0,
    rollDegrees: Float = 0f,
): CameraUiState = copy(
    composition = composition,
    debugStats = debugStats.copy(
        fps = fps,
        lastLatencyMs = latencyMs,
        samplingIntervalMs = samplingIntervalMs,
        engineTimeMs = engineTimeMs,
        detectorTimings = detectorTimings,
        analysisRotationDegrees = analysisRotationDegrees,
        rollDegrees = rollDegrees,
    ),
    debugFrame = debugFrame,
    deviceRotationDegrees = deviceRotationDegrees,
    groupStatus = composition.raw.groupStatus,
)

/** True exactly on the transition into [peakReady] (group-aware shoot-ready), used to fire the haptic tick once per entry. */
fun CameraUiState.justEnteredShootReady(previous: CameraUiState): Boolean =
    peakReady && !previous.peakReady
