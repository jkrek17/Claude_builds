package com.compositioncoach.app.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.ExtendableBuilder
import androidx.camera.core.ImageCapture

/** Same log tag as the controller, so `adb logcat -s CameraController` still shows the resolution check. */
private const val QUALITY_TAG = "CameraController"

/**
 * Sets the high-quality Camera2 request options the camera advertises on [builder]; returns whether at least
 * one option was set. Why each one, as part of the whole image-quality configuration of a bind:
 *
 * 1. **CameraX Extensions.** On every bind the lens is asked which of AUTO/HDR/NIGHT its vendor extensions
 *    library offers ([CaptureSessionInfo.availableEnhancements]). When the persisted preference
 *    (`capture_enhancement`, read through [enhancementPreference]) is available, the session binds with
 *    `ExtensionsManager.getExtensionEnabledCameraSelector`, which routes the still capture through the
 *    vendor's own multi-frame merge (on Pixels, the same family of processing the stock app uses). That is the
 *    single biggest lever a third-party app has; everything below is for when it is unavailable or OFF.
 *    Trade-off: many vendor sessions cannot also carry [ImageAnalysis]
 *    (`ExtensionsManager.isImageAnalysisSupported`). Then Preview + ImageCapture bind under the extension and
 *    analysis is simply not bound — live coaching pauses (`CaptureSessionInfo.analysisPausedByEnhancement`,
 *    shown as a small note) rather than running a second camera session, which phones cannot do on the same
 *    lens anyway. That trade is only ever made on an explicit HDR/Night choice: AUTO, the default, is offered
 *    only when its session keeps analysis ([CaptureQuality.offeredEnhancements]), so first launch never opens
 *    with coaching switched off. If binding under the extension throws, the controller falls back to OFF in
 *    the same bind.
 * 2. **Camera2 request options** (only when no extension owns the ISP, and not while conserving power), each
 *    set only when the camera lists the mode as available:
 *    - `NOISE_REDUCTION_MODE` and `EDGE_MODE` = HIGH_QUALITY: the ISP's best spatial denoise and its
 *      full-quality sharpening kernel instead of the real-time ones. `TEMPLATE_STILL_CAPTURE` usually
 *      defaults to these already; stating them stops a repeating-request default from leaking into the still.
 *    - `COLOR_CORRECTION_ABERRATION_MODE` = HIGH_QUALITY: removes lateral chromatic aberration, whose colour
 *      fringes read as softness at high-contrast edges, especially in the corners.
 *    - `HOT_PIXEL_MODE` = HIGH_QUALITY: hot pixels are otherwise sharpened into bright specks.
 *    - `DISTORTION_CORRECTION_MODE` = HIGH_QUALITY (API 28+): the lens distortion model is applied with the
 *      high-quality resampler; it also makes the preview geometry match the saved photo's.
 *    - `LENS_OPTICAL_STABILIZATION_MODE` = ON: OIS cancels most low-frequency hand shake during the
 *      exposure; it is the hardware half of steady-shot (see [MotionSensor]).
 *    - `TONEMAP_MODE` is deliberately **not** set: HIGH_QUALITY would replace the vendor's tuned (often
 *      scene-adaptive, local) tone curve with a generic one, fighting the vendor pipeline for no sharpness gain.
 *    CameraX merges the Camera2Interop options of every use case in a session into the one repeating
 *    request, so the same values are set on [Preview] as on [ImageCapture]; setting them on capture alone
 *    would still reach the preview stream, just non-deterministically.
 * 3. **JPEG quality 100** ([CaptureQuality.JPEG_QUALITY]): quantization at 95 already discards fine,
 *    low-contrast texture (foliage, hair, fabric), which is exactly what "less sharp" means when comparing
 *    files at 100% zoom. The cost is a ~1.5-2x larger file.
 * 4. **Resolution check.** After binding, the [ImageCapture] stream size is compared with the largest 4:3 JPEG
 *    size in the camera's stream configuration map, and its ViewPort crop rect with the full stream; both are
 *    logged and recorded in [CaptureSessionInfo] / `CaptureDiagnostics.isFullResolution`.
 *
 */
@OptIn(ExperimentalCamera2Interop::class)
internal fun <T> applyQualityOptions(builder: ExtendableBuilder<T>, cameraInfo: CameraInfo): Boolean {
    val c2 = runCatching { Camera2CameraInfo.from(cameraInfo) }.getOrNull() ?: return false
    val extender = Camera2Interop.Extender(builder)
    var any = false
    fun IntArray?.offers(mode: Int) = this?.contains(mode) == true

    if (c2.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            .offers(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
    ) {
        extender.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        any = true
    }
    if (c2.getCameraCharacteristic(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES).offers(CaptureRequest.EDGE_MODE_HIGH_QUALITY)) {
        extender.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        any = true
    }
    if (c2.getCameraCharacteristic(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
            .offers(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
    ) {
        extender.setCaptureRequestOption(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
        )
        any = true
    }
    if (c2.getCameraCharacteristic(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
            .offers(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
    ) {
        extender.setCaptureRequestOption(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
        any = true
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        c2.getCameraCharacteristic(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
            .offers(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
    ) {
        extender.setCaptureRequestOption(
            CaptureRequest.DISTORTION_CORRECTION_MODE,
            CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
        )
        any = true
    }
    if (c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            .offers(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
    ) {
        extender.setCaptureRequestOption(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
        )
        any = true
    }
    return any
}


/**
 * Fills in the resolution check described in the class doc and logs its verdict: a WARN line whenever
 * the photo will not use every pixel a third-party app can get.
 */
@OptIn(ExperimentalCamera2Interop::class)
internal fun CaptureSessionInfo.withResolutionCheck(capture: ImageCapture, cameraInfo: CameraInfo?): CaptureSessionInfo {
    val resolutionInfo = runCatching { capture.resolutionInfo }.getOrNull()
    val configured = resolutionInfo?.resolution?.let { CaptureQuality.PixelSize(it.width, it.height) }
    val crop = resolutionInfo?.cropRect
    val cropFull = if (resolutionInfo != null && crop != null) {
        crop.left == 0 && crop.top == 0 && crop.width() == resolutionInfo.resolution.width &&
            crop.height() == resolutionInfo.resolution.height
    } else {
        null
    }
    val largest = cameraInfo?.let { info ->
        runCatching {
            val map = Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.map { CaptureQuality.PixelSize(it.width, it.height) }.orEmpty()
            CaptureQuality.largestFourByThree(sizes)
        }.getOrNull()
    }
    val checked = copy(configuredCaptureSize = configured, largestFourByThree = largest, cropIsFullFrame = cropFull)
    val message = "ImageCapture stream=$configured crop=${crop?.toShortString()} largest4:3=$largest " +
        "fullResolution=${checked.isFullResolution}"
    if (checked.isFullResolution == false) Log.w(QUALITY_TAG, "$message (photo will not use every available pixel)") else Log.i(QUALITY_TAG, message)
    return checked
}

