package com.compositioncoach.app.camera

import android.graphics.ImageFormat
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPoint
import androidx.camera.view.PreviewView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

// Controls that act on the camera [CameraController] bound last: focus, zoom, exposure and in-memory bursts.
// Extensions over the controller's bound camera, split out of CameraController.kt to keep both files readable.

/** Focuses (and meters) on a tap in [previewView]'s local coordinates. Auto-cancels after 3s. */
fun CameraController.tapToFocus(previewView: PreviewView, x: Float, y: Float) {
    val cam = camera ?: return
    val point: MeteringPoint = previewView.meteringPointFactory.createPoint(x, y)
    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
        .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    runCatching { cam.cameraControl.startFocusAndMetering(action) }
}

/**
 * Pinch-to-zoom: [delta] is a multiplicative factor applied to the current zoom ratio.
 * @return the zoom ratio actually applied (for the screen's zoom chip), or null if there is no bound
 *   camera yet (a pinch that starts before the bind `DisposableEffect` finishes).
 */
fun CameraController.applyZoomDelta(delta: Float): Float? {
    val current = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
    return setZoomRatio(current * delta)
}

/**
 * Sets an absolute zoom ratio, clamped to the bound camera's range. CameraX applies it on the next
 * frame (it does not animate); the view model eases between presets by calling this in small steps.
 * @return the ratio applied, or null when no camera is bound.
 */
fun CameraController.setZoomRatio(ratio: Float): Float? {
    val cam = camera ?: return null
    val state = cam.cameraInfo.zoomState.value
    val min = state?.minZoomRatio ?: 1f
    val max = state?.maxZoomRatio ?: 1f
    val next = ratio.coerceIn(min, max)
    runCatching { cam.cameraControl.setZoomRatio(next) }
    return next
}

/**
 * Sets exposure compensation, clamped to the bound camera's supported range (see
 * [CameraBindResult.Success.exposureRange]). A no-op if there is no bound camera, or the device
 * doesn't support exposure compensation at all (an empty/zero range). CameraX resets the index to 0
 * whenever the camera is rebound, which is why `CameraUiState.withCameraBound` resets its copy too.
 */
fun CameraController.setExposureCompensation(index: Int) {
    val cam = camera ?: return
    val state = cam.cameraInfo.exposureState
    if (!state.isExposureCompensationSupported) return
    val range = state.exposureCompensationRange
    val clamped = index.coerceIn(range.lower, range.upper)
    runCatching { cam.cameraControl.setExposureCompensationIndex(clamped) }
}

/**
 * Issues [count] in-memory captures back to back (all requests are queued at once, so CameraX runs them
 * as fast as the pipeline allows) and returns their JPEG bytes in capture order. Each `ImageProxy` is
 * copied and closed inside its callback so the capture `ImageReader` never runs out of buffers.
 * Throws if any frame fails; the caller falls back to a single capture.
 */
suspend fun CameraController.captureBurst(capture: ImageCapture, count: Int): List<CapturedJpeg> = withContext(Dispatchers.Main.immediate) {
    val pending = List(count) { CompletableDeferred<CapturedJpeg>() }
    pending.forEach { deferred -> capture.takePicture(captureExecutor, InMemoryCallback(deferred)) }
    pending.awaitAll()
}

private class InMemoryCallback(private val result: CompletableDeferred<CapturedJpeg>) : ImageCapture.OnImageCapturedCallback() {
    override fun onCaptureSuccess(image: ImageProxy) {
        try {
            result.complete(image.toCapturedJpeg())
        } catch (t: Throwable) {
            result.completeExceptionally(t)
        } finally {
            image.close()
        }
    }

    override fun onError(exception: ImageCaptureException) {
        result.completeExceptionally(exception)
    }
}

/** Flash cycles OFF -> AUTO -> ON; the new mode applies to the bound capture at once and to later binds. */
fun CameraController.cycleFlashMode(): FlashMode {
    flashMode = when (flashMode) {
        FlashMode.OFF -> FlashMode.AUTO
        FlashMode.AUTO -> FlashMode.ON
        FlashMode.ON -> FlashMode.OFF
    }
    applyFlashMode(flashMode)
    return flashMode
}

internal fun CameraController.applyFlashMode(mode: FlashMode) {
    val capture = imageCapture ?: return
    capture.flashMode = mode.toImageCaptureFlashMode()
}

internal fun FlashMode.toImageCaptureFlashMode(): Int = when (this) {
    FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
    FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
    FlashMode.ON -> ImageCapture.FLASH_MODE_ON
}

/** One in-memory JPEG capture, copied out of its `ImageProxy`. */
class CapturedJpeg(
    val bytes: ByteArray,
    /** Clockwise rotation that makes the stored pixels upright (`ImageInfo.rotationDegrees`). */
    val rotationDegrees: Int,
    /** `SENSOR_TIMESTAMP` of the frame; matches [CaptureResultTap.StillResult.sensorTimestampNs]. */
    val sensorTimestampNs: Long,
    val width: Int,
    val height: Int,
)

private fun ImageProxy.toCapturedJpeg(): CapturedJpeg {
    require(format == ImageFormat.JPEG) { "Expected a JPEG ImageProxy, got format $format" }
    val buffer = planes[0].buffer
    buffer.rewind()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return CapturedJpeg(bytes, imageInfo.rotationDegrees, imageInfo.timestamp, width, height)
}
