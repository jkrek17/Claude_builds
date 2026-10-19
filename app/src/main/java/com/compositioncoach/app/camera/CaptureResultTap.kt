package com.compositioncoach.app.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock

/**
 * A `CameraCaptureSession.CaptureCallback` installed through `Camera2Interop.Extender.setSessionCaptureCallback`
 * on the [androidx.camera.core.ImageCapture] use case, which CameraX attaches to every still request. It keeps
 * the last few *still-capture* results (`CONTROL_CAPTURE_INTENT == STILL_CAPTURE`; preview frames are ignored)
 * so a capture's diagnostics can say what the HAL actually did: ISO, exposure time, aperture and focal length,
 * plus what EXIF cannot tell you - the noise-reduction and edge (sharpening) modes the ISP applied and whether
 * OIS was engaged. Results are matched to an in-memory capture by `SENSOR_TIMESTAMP` (which equals
 * `ImageProxy.imageInfo.timestamp`), or for the file-saving path by "the latest still that completed after
 * the shutter press".
 *
 * This is the public-API route to the per-capture `TotalCaptureResult`; CameraX's own
 * `Camera2CameraCaptureResult` is a restricted (internal) class that R8 and future CameraX releases are free
 * to break. It is not installed under a CameraX Extension (vendor sessions do not run app capture callbacks),
 * where diagnostics fall back to the JPEG's EXIF.
 */
class CaptureResultTap : CameraCaptureSession.CaptureCallback() {

    /** What one still request reported. Any field may be null if the HAL omits that key. */
    data class StillResult(
        val sensorTimestampNs: Long?,
        val completedAtElapsedMs: Long,
        val iso: Int?,
        val exposureTimeNs: Long?,
        val aperture: Float?,
        val focalLengthMm: Float?,
        val noiseReductionMode: Int?,
        val edgeMode: Int?,
        val opticalStabilizationMode: Int?,
    )

    private val results = ArrayDeque<StillResult>()

    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
        val intent = runCatching { request.get(CaptureRequest.CONTROL_CAPTURE_INTENT) }.getOrNull()
        if (intent != CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE) return
        val still = StillResult(
            sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP),
            completedAtElapsedMs = SystemClock.elapsedRealtime(),
            iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
            exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            aperture = result.get(CaptureResult.LENS_APERTURE),
            focalLengthMm = result.get(CaptureResult.LENS_FOCAL_LENGTH),
            noiseReductionMode = result.get(CaptureResult.NOISE_REDUCTION_MODE),
            edgeMode = result.get(CaptureResult.EDGE_MODE),
            opticalStabilizationMode = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE),
        )
        synchronized(results) {
            results.addLast(still)
            while (results.size > CAPACITY) results.removeFirst()
        }
    }

    /** The still whose sensor timestamp equals [sensorTimestampNs], if it was seen. */
    fun forTimestamp(sensorTimestampNs: Long): StillResult? =
        synchronized(results) { results.lastOrNull { it.sensorTimestampNs == sensorTimestampNs } }

    /** The most recent still that completed at or after [elapsedRealtimeMs]. */
    fun latestSince(elapsedRealtimeMs: Long): StillResult? =
        synchronized(results) { results.lastOrNull { it.completedAtElapsedMs >= elapsedRealtimeMs } }

    companion object {
        private const val CAPACITY = 8

        fun noiseReductionName(mode: Int?): String? = when (mode) {
            null -> null
            CaptureRequest.NOISE_REDUCTION_MODE_OFF -> "OFF"
            CaptureRequest.NOISE_REDUCTION_MODE_FAST -> "FAST"
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
            CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL -> "MINIMAL"
            CaptureRequest.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> "ZSL"
            else -> mode.toString()
        }

        fun edgeName(mode: Int?): String? = when (mode) {
            null -> null
            CaptureRequest.EDGE_MODE_OFF -> "OFF"
            CaptureRequest.EDGE_MODE_FAST -> "FAST"
            CaptureRequest.EDGE_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
            CaptureRequest.EDGE_MODE_ZERO_SHUTTER_LAG -> "ZSL"
            else -> mode.toString()
        }
    }
}
