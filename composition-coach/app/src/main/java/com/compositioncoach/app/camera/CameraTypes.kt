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
