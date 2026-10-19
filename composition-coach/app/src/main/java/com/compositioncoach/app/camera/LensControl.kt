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

    // ---- Manual request options. Camera2CameraControl keeps one option set per control, so every change
    // re-applies the union of what is currently wanted (focus and exposure are set by different pipelines).
    private var manualFocusDiopters: Float? = null
    private var aeLocked = false
    private var manualExposure: ManualExposure? = null

    /** A fixed sensor exposure: [exposureNs] shutter and [iso] sensitivity, with auto-exposure off. */
    data class ManualExposure(val exposureNs: Long, val iso: Int)

    /** Whether `CONTROL_AE_LOCK` is honoured on this lens. */
    val supportsAeLock: Boolean get() = characteristic(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true

    /** Whether `CONTROL_AE_MODE_OFF` with `SENSOR_EXPOSURE_TIME` / `SENSOR_SENSITIVITY` is honoured (MANUAL_SENSOR capability). */
    val supportsManualExposure: Boolean
        get() {
            val caps = characteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
            val aeModes = characteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: return false
            return caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
                aeModes.contains(CaptureRequest.CONTROL_AE_MODE_OFF)
        }

    /** The sensor's exposure-time range in nanoseconds, or null when not reported. */
    val exposureTimeRangeNs: LongRange?
        get() = characteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper }

    /** The sensor's ISO range, or null when not reported. */
    val isoRange: IntRange?
        get() = characteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper }

    /**
     * Turns AF off and drives the lens to [diopters] (0 = infinity) on the repeating request; the future
     * completes once the request is submitted, not when the lens has arrived (callers allow a settle time).
     */
    suspend fun setManualFocus(diopters: Float) {
        manualFocusDiopters = diopters
        apply()
    }

    /** Clears the manual focus so CameraX's continuous AF resumes. Never throws. */
    suspend fun restoreAutoFocus() {
        manualFocusDiopters = null
        runCatching { apply() }.onFailure { Log.w(TAG, "Could not restore autofocus", it) }
    }

    /**
     * Freezes auto-exposure at its current result (`CONTROL_AE_LOCK`) so the frames of a stack share one
     * brightness. No-op when the lens does not support it. Callers unlock with [unlockAutoExposure].
     */
    suspend fun lockAutoExposure() {
        if (!supportsAeLock) return
        aeLocked = true
        apply()
    }

    /** Releases [lockAutoExposure]. Never throws. */
    suspend fun unlockAutoExposure() {
        if (!aeLocked) return
        aeLocked = false
        runCatching { apply() }.onFailure { Log.w(TAG, "Could not unlock auto-exposure", it) }
    }

    /**
     * Fixes the shutter and ISO (`CONTROL_AE_MODE_OFF`): [exposureNs] and [iso] are clamped to the sensor's
     * ranges. Returns the values actually requested, or null when the lens has no manual sensor control (then
     * nothing changes). Callers restore with [restoreAutoExposure].
     */
    suspend fun setManualExposure(exposureNs: Long, iso: Int): ManualExposure? {
        if (!supportsManualExposure) return null
        val range = exposureTimeRangeNs
        val isoR = isoRange
        val wanted = ManualExposure(
            exposureNs = if (range != null) exposureNs.coerceIn(range.first, range.last) else exposureNs,
            iso = if (isoR != null) iso.coerceIn(isoR.first, isoR.last) else iso,
        )
        manualExposure = wanted
        apply()
        return wanted
    }

    /** Clears [setManualExposure] so auto-exposure resumes. Never throws. */
    suspend fun restoreAutoExposure() {
        if (manualExposure == null) return
        manualExposure = null
        runCatching { apply() }.onFailure { Log.w(TAG, "Could not restore auto-exposure", it) }
    }

    /** Re-applies every wanted manual option as one set (or clears them all when nothing is wanted). */
    private suspend fun apply() {
        val focus = manualFocusDiopters
        val exposure = manualExposure
        val control = Camera2CameraControl.from(camera.cameraControl)
        if (focus == null && !aeLocked && exposure == null) {
            withContext(Dispatchers.Main.immediate) { control.clearCaptureRequestOptions().awaitFuture() }
            return
        }
        val builder = CaptureRequestOptions.Builder()
        if (focus != null) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
        }
        if (exposure != null) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.exposureNs)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, exposure.iso)
        } else if (aeLocked) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        withContext(Dispatchers.Main.immediate) { control.setCaptureRequestOptions(builder.build()).awaitFuture() }
    }

    companion object {
        private const val TAG = "LensControl"
    }
}
