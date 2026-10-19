package com.compositioncoach.app.camera

import android.util.Range
import androidx.camera.core.Camera

/** Flash cycles OFF -> AUTO -> ON, see [CameraController.cycleFlashMode]. */
enum class FlashMode { OFF, AUTO, ON }

/** Which physical lens is currently bound. */
enum class LensFacing { BACK, FRONT }

/** Result of a [CameraController.bind] attempt, surfaced by the ViewModel as UI state. */
sealed interface CameraBindResult {
    /**
     * @param lensFacing the lens actually bound, which can differ from the request on single-camera devices.
     * @param exposureRange the camera's supported exposure-compensation index range (both bounds `0` when
     *   the device doesn't support exposure compensation at all); pass indices to
     *   [CameraController.setExposureCompensation] clamped to this range.
     */
    data class Success(
        val camera: Camera,
        val hasFlashUnit: Boolean,
        val lensFacing: LensFacing,
        val exposureRange: Range<Int> = Range(0, 0),
        /** What the bind configured for capture quality; see [CaptureSessionInfo]. */
        val session: CaptureSessionInfo = CaptureSessionInfo(lensFacing = lensFacing),
        /**
         * The controller that produced this bind. The view model keeps it so zoom presets, exposure,
         * enhancement rebinds and the capture pipeline act on the same session the screen bound, without the
         * screen having to hand the controller over separately. Null only in tests.
         */
        val controller: CameraController? = null,
    ) : CameraBindResult
    data class Failure(val throwable: Throwable) : CameraBindResult
}

/**
 * Converts an `android.util.Range<Int>` to a plain Kotlin [IntRange] — used when handing
 * [CameraBindResult.Success.exposureRange] to `CameraUiState`, which deliberately stays on [IntRange]
 * rather than `android.util.Range` so its reducers stay exercisable in a plain JVM unit test (see
 * `CameraUiState.exposureRange`'s KDoc for why the Android type can't be).
 */
fun Range<Int>.toIntRange(): IntRange = lower..upper

/**
 * The capture-quality facts of one bind, reported by [CameraController.bind] and copied into every capture's
 * [CaptureDiagnostics].
 *
 * @param requestedEnhancement the persisted preference the bind read.
 * @param activeEnhancement what the session actually runs (OFF when the lens lacks the requested one, or when
 *   binding under the extension failed and the controller fell back).
 * @param availableEnhancements every enhancement the bound lens offers, always including OFF (AUTO only when
 *   it keeps live coaching running, see [CaptureQuality.offeredEnhancements]).
 * @param enhancementsPausingCoaching the offered enhancements whose session cannot carry ImageAnalysis, so
 *   selecting one pauses live coaching (the UI can say so on the chip before the user picks it).
 * @param analysisPausedByEnhancement true when the active extension cannot carry `ImageAnalysis` alongside
 *   Preview + ImageCapture (`ExtensionsManager.isImageAnalysisSupported` is false), so live coaching is off for
 *   as long as that enhancement is selected.
 * @param configuredCaptureSize the `ImageCapture` stream size CameraX chose.
 * @param largestFourByThree the largest 4:3 JPEG size the camera reports in its stream configuration map.
 * @param cropIsFullFrame whether the shared ViewPort's crop for `ImageCapture` is the whole stream (so the
 *   JPEG the HAL encoded is saved as-is, never cropped and re-encoded in software).
 * @param qualityFlagsApplied whether the Camera2 high-quality request options were set (not under an
 *   extension, and not while conserving power).
 */
data class CaptureSessionInfo(
    val lensFacing: LensFacing,
    val requestedEnhancement: CaptureEnhancement = CaptureEnhancement.OFF,
    /** The outcome's enhancement override in effect for this bind ([CameraController.setEnhancementOverride]), or null. */
    val outcomeEnhancement: CaptureEnhancement? = null,
    val activeEnhancement: CaptureEnhancement = CaptureEnhancement.OFF,
    val availableEnhancements: Set<CaptureEnhancement> = setOf(CaptureEnhancement.OFF),
    val enhancementsPausingCoaching: Set<CaptureEnhancement> = emptySet(),
    val analysisPausedByEnhancement: Boolean = false,
    val captureModeName: String = "MAXIMIZE_QUALITY",
    val jpegQuality: Int = CaptureQuality.JPEG_QUALITY,
    val configuredCaptureSize: CaptureQuality.PixelSize? = null,
    val largestFourByThree: CaptureQuality.PixelSize? = null,
    val cropIsFullFrame: Boolean? = null,
    val qualityFlagsApplied: Boolean = false,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
) {
    /** True when the capture stream is the largest 4:3 size and is not cropped: every available pixel is used. */
    val isFullResolution: Boolean?
        get() {
            val configured = configuredCaptureSize ?: return null
            val largest = largestFourByThree ?: return null
            val sameSize = configured.area == largest.area
            return sameSize && cropIsFullFrame != false
        }
}
