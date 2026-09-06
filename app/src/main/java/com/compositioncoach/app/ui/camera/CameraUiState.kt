package com.compositioncoach.app.ui.camera

import com.compositioncoach.app.camera.FlashMode
import com.compositioncoach.app.camera.LensFacing
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.composition.model.DetectedObject
import com.compositioncoach.composition.model.SmoothedComposition
import com.compositioncoach.composition.model.SubjectMask

/** Rolling stats shown only in debug mode. */
data class DebugStats(
    val fps: Float = 0f,
    val lastLatencyMs: Long = 0L,
    val samplingIntervalMs: Long = CoachSettings.DEFAULT_INTERVAL_MS,
    val engineTimeMs: Long = 0L,
    val detectorTimings: Map<String, Long> = emptyMap(),
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
)

// The functions below are pure state transforms with no Android dependency, extracted so the
// camera view model's reducer logic is unit-testable on the plain JVM (see CameraUiStateTest).

fun CameraUiState.withSettings(settings: CoachSettings): CameraUiState = copy(settings = settings)

fun CameraUiState.withCameraBound(lensFacing: LensFacing, hasFlashUnit: Boolean): CameraUiState =
    copy(lensFacing = lensFacing, hasFlashUnit = hasFlashUnit, cameraError = null)

fun CameraUiState.withCameraError(message: String): CameraUiState = copy(cameraError = message)

fun CameraUiState.withFlashMode(mode: FlashMode): CameraUiState = copy(flashMode = mode)

fun CameraUiState.withCaptureStarted(): CameraUiState = copy(isCapturing = true, captureError = null)

/**
 * Capture finished successfully: stops the shutter's disabled state and starts the post-capture fade
 * (score badge / guidance banner drop to 30% opacity) so the cut to the review screen doesn't read as an
 * abrupt jump from full-opacity UI. [CameraViewModel] clears [postCaptureFadeActive] again ~1s later via
 * [withPostCaptureFadeEnded], whether or not navigation actually happened by then.
 */
fun CameraUiState.withCaptureFinished(): CameraUiState = copy(isCapturing = false, postCaptureFadeActive = true)

fun CameraUiState.withCaptureError(message: String): CameraUiState =
    copy(isCapturing = false, captureError = message, postCaptureFadeActive = false)

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
): CameraUiState = copy(
    composition = composition,
    debugStats = debugStats.copy(
        fps = fps,
        lastLatencyMs = latencyMs,
        samplingIntervalMs = samplingIntervalMs,
        engineTimeMs = engineTimeMs,
        detectorTimings = detectorTimings,
    ),
    debugFrame = debugFrame,
)

/** True exactly on the transition into shoot-ready, used to fire the haptic tick once per entry. */
fun CameraUiState.justEnteredShootReady(previous: CameraUiState): Boolean =
    composition.isShootReady && !previous.composition.isShootReady
