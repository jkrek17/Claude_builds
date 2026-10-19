package com.compositioncoach.app.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Camera2-level facts and manual lens control for the bound camera, for the focus stack and for gyro
 * alignment. Obtained from [CameraController.lensControl]; only valid for the bind it came from. Under a vendor
 * extension the Camera2 request options are ignored by the vendor session, which is why the focus stack only
 * runs with no extension bound (see [OutcomeCapturePlanner]).
 */
@OptIn(ExperimentalCamera2Interop::class)
class LensControl internal constructor(private val camera: Camera) {

    private val info: Camera2CameraInfo? = runCatching { Camera2CameraInfo.from(camera.cameraInfo) }.getOrNull()

    private fun <T> characteristic(key: CameraCharacteristics.Key<T>): T? =
        runCatching { info?.getCameraCharacteristic(key) }.getOrNull()

    /** Closest focus distance in diopters (1/m; 10 = 10 cm), or 0/null for a fixed-focus lens. */
    val minimumFocusDistanceDiopters: Float? get() = characteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)

    /** Whether `CONTROL_AF_MODE_OFF` + `LENS_FOCUS_DISTANCE` are honoured: AF can be switched off and the lens moves. */
    val supportsManualFocus: Boolean
        get() {
            val min = minimumFocusDistanceDiopters ?: return false
            val afModes = characteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return false
            return min > 0f && afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)
        }

    /** Whether the focus stack can run on this lens (see [FocusStacker]). */
    val supportsFocusStack: Boolean get() = supportsManualFocus

    /** Clockwise rotation from the sensor buffer to natural portrait (`SENSOR_ORIENTATION`), 90 on most backs. */
    val sensorOrientationDegrees: Int get() = characteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    /**
     * Focal length divided by the long edge of the image, at the current zoom: multiply by an image's long edge
     * in pixels to get the focal length in pixels for gyro alignment. Null when the lens does not report focal
     * length and physical sensor size (callers then use `GyroAlignment.DEFAULT_FOCAL_TO_LONG_EDGE`).
     * Approximate on logical multi-cameras, where the zoom ratio may have switched to another physical lens.
     */
    fun focalToLongEdge(): Float? {
        val focal = characteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val size = characteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val long = maxOf(size.width, size.height)
        if (focal <= 0f || long <= 0f) return null
        val zoom = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        return focal / long * zoom
    }

    /**
     * Turns AF off and drives the lens to [diopters] (0 = infinity) on the repeating request; the future
     * completes once the request is submitted, not when the lens has arrived (callers allow a settle time).
     */
    suspend fun setManualFocus(diopters: Float) {
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
            .build()
        withContext(Dispatchers.Main.immediate) {
            Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options).awaitFuture()
        }
    }

    /** Clears the manual focus options so CameraX's continuous AF resumes. Never throws. */
    suspend fun restoreAutoFocus() {
        runCatching {
            withContext(Dispatchers.Main.immediate) {
                Camera2CameraControl.from(camera.cameraControl).clearCaptureRequestOptions().awaitFuture()
            }
        }.onFailure { Log.w(TAG, "Could not restore autofocus", it) }
    }

    companion object {
        private const val TAG = "LensControl"
    }
}
