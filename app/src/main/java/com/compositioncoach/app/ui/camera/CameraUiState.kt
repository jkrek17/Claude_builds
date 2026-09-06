package com.compositioncoach.app.ui.camera

import android.net.Uri
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
)

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

fun CameraUiState.withSettings(settings: CoachSettings): CameraUiState = copy(settings = settings)

fun CameraUiState.withCameraBound(
    lensFacing: LensFacing,
    hasFlashUnit: Boolean,
    exposureRange: IntRange = 0..0,
): CameraUiState = copy(
    lensFacing = lensFacing,
    hasFlashUnit = hasFlashUnit,
    cameraError = null,
    exposureRange = exposureRange,
    // A fresh bind may report a different (or no) exposure range than before (e.g. a lens switch to a
    // camera with different capabilities); re-clamp rather than leave a now out-of-range index in place.
    exposureIndex = exposureIndex.coerceIn(exposureRange),
)

/** Applies a new device-health-driven cadence tier; see [CameraUiState.performanceTier]'s doc. */
fun CameraUiState.withPerformanceTier(tier: PerformanceTier): CameraUiState = copy(performanceTier = tier)

/** Records the exposure-compensation index a slider (or [preferFastCapture]-style control) just applied. */
fun CameraUiState.withExposureIndex(index: Int): CameraUiState =
    copy(exposureIndex = index.coerceIn(exposureRange))

/** Records the most recently saved photo, for a gallery-shortcut thumbnail button. */
fun CameraUiState.withLastPhotoUri(uri: Uri?): CameraUiState = copy(lastPhotoUri = uri)

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
    deviceRotationDegrees: Int = 0,
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
    deviceRotationDegrees = deviceRotationDegrees,
)

/** True exactly on the transition into shoot-ready, used to fire the haptic tick once per entry. */
fun CameraUiState.justEnteredShootReady(previous: CameraUiState): Boolean =
    composition.isShootReady && !previous.composition.isShootReady
