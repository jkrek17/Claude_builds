package com.compositioncoach.app.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream

/**
 * Everything between the shutter press and a saved file, in order (the README's "Capture pipeline" section
 * walks the same steps with the settings that affect each):
 *
 *  1. **Steady-shot gate** ([awaitSteady]): if the gyro says the phone is MOVING, wait up to
 *     [STEADY_WAIT_MS] for it to settle to STEADY or SLIGHT_MOTION, then shoot regardless. 450 ms is long
 *     enough to ride out the jolt of pressing the shutter (the most common cause of a soft phone photo)
 *     and short enough not to miss the moment.
 *  2. **Capture**: either lucky imaging (three in-memory frames back to back, keep the sharpest, write its
 *     original JPEG bytes - no re-encode) or a single capture through CameraX's own file-saving path. Lucky
 *     imaging only runs when [ShotRequest.burst] says so; any burst failure falls back to a single capture.
 *  3. **Diagnostics**: ISO, shutter, aperture and focal length from the HAL's capture result
 *     ([CaptureResultTap]) when available, else from the JPEG's EXIF; plus everything the request knows.
 *
 * Auto-level is deliberately *not* here: it runs after the review screen is showing (see [AutoLevelProcessor]).
 * Nothing in this class touches the main thread except issuing `takePicture` (a CameraX requirement); JPEG
 * decoding and scoring run on [Dispatchers.Default], file I/O on [Dispatchers.IO].
 */
class CapturePipeline(
    private val repository: CaptureRepository,
    private val motionSensor: MotionSensor,
) {

    /** Everything a capture needs to know, snapshotted at shutter time. */
    class ShotRequest(
        val controller: CameraController?,
        val imageCapture: ImageCapture,
        val lensFacing: LensFacing,
        val session: CaptureSessionInfo?,
        val burst: Boolean,
        val zoomRatio: Float?,
        val exposureIndex: Int?,
        val shutterAtElapsedMs: Long,
        val steadiness: Steadiness,
        val waitedForSteadyMs: Long,
    )

    class ShotResult(val uri: Uri, val diagnostics: CaptureDiagnostics)

    /**
     * Step 1. Returns the steadiness at the moment the capture may proceed and how long it waited (0 when the
     * phone was already steady, or when there is no gyro).
     */
    suspend fun awaitSteady(maxWaitMs: Long = STEADY_WAIT_MS): Pair<Steadiness, Long> {
        val start = SystemClock.elapsedRealtime()
        if (!motionSensor.isAvailable || motionSensor.steadiness.value != Steadiness.MOVING) {
            return motionSensor.steadiness.value to 0L
        }
        val settled = withTimeoutOrNull(maxWaitMs) { motionSensor.steadiness.first { it != Steadiness.MOVING } }
        return (settled ?: motionSensor.steadiness.value) to (SystemClock.elapsedRealtime() - start)
    }

    /** Steps 2 and 3. Throws only if even the single-capture path fails. */
    suspend fun capture(request: ShotRequest): ShotResult {
        val controller = request.controller
        if (request.burst && controller != null) {
            try {
                return captureSharpestOfBurst(request, controller)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Burst capture failed; falling back to a single capture", t)
            }
        }
        return captureSingle(request)
    }

    private suspend fun captureSharpestOfBurst(request: ShotRequest, controller: CameraController): ShotResult {
        val frames = controller.captureBurst(request.imageCapture, BURST_SIZE)
        val scores = withContext(Dispatchers.Default) {
            coroutineScope { frames.map { frame -> async { scoreJpeg(frame.bytes) } }.awaitAll() }
        }
        if (scores.all { it <= 0f }) error("No burst frame could be scored")
        val picked = SharpnessScorer.sharpestIndex(scores)
        val winner = frames[picked]
        val uri = repository.saveJpeg(winner.bytes, winner.rotationDegrees, mirrorHorizontally = request.lensFacing == LensFacing.FRONT)
        val savedAt = SystemClock.elapsedRealtime()
        val tapped = controller.captureResultTap?.forTimestamp(winner.sensorTimestampNs)
        val exif = withContext(Dispatchers.Default) { ExifFacts.from(winner.bytes) }
        val diagnostics = baseDiagnostics(request, tapped, exif, savedAt).copy(
            widthPx = winner.width,
            heightPx = winner.height,
            captureMode = "${request.session?.captureModeName ?: "MAXIMIZE_QUALITY"}+BURST$BURST_SIZE",
            burstScores = scores,
            burstPicked = picked,
        )
        return ShotResult(uri, diagnostics)
    }

    private suspend fun captureSingle(request: ShotRequest): ShotResult {
        val uri = repository.capture(request.imageCapture, isFrontCamera = request.lensFacing == LensFacing.FRONT)
        val savedAt = SystemClock.elapsedRealtime()
        val tapped = request.controller?.captureResultTap?.latestSince(request.shutterAtElapsedMs)
        val exif = runCatching { ExifFacts.from(repository.readExifHeader(uri)) }.getOrNull()
        val diagnostics = baseDiagnostics(request, tapped, exif, savedAt).copy(
            widthPx = exif?.width,
            heightPx = exif?.height,
        )
        return ShotResult(uri, diagnostics)
    }

    private fun baseDiagnostics(
        request: ShotRequest,
        tapped: CaptureResultTap.StillResult?,
        exif: ExifFacts?,
        savedAtElapsedMs: Long,
    ): CaptureDiagnostics {
        val session = request.session
        return CaptureDiagnostics(
            iso = tapped?.iso ?: exif?.iso,
            exposureTimeNs = tapped?.exposureTimeNs ?: exif?.exposureTimeNs,
            aperture = tapped?.aperture ?: exif?.aperture,
            focalLengthMm = tapped?.focalLengthMm ?: exif?.focalLengthMm,
            lensFacing = request.lensFacing,
            enhancement = session?.activeEnhancement ?: CaptureEnhancement.OFF,
            captureMode = session?.captureModeName,
            jpegQuality = session?.jpegQuality ?: CaptureQuality.JPEG_QUALITY,
            steadiness = request.steadiness,
            waitedForSteadyMs = request.waitedForSteadyMs,
            zoomRatio = request.zoomRatio,
            exposureCompensationIndex = request.exposureIndex,
            captureLatencyMs = savedAtElapsedMs - request.shutterAtElapsedMs,
            configuredCaptureSize = session?.configuredCaptureSize?.toString(),
            isFullResolution = session?.isFullResolution,
            noiseReductionMode = CaptureResultTap.noiseReductionName(tapped?.noiseReductionMode),
            edgeMode = CaptureResultTap.edgeName(tapped?.edgeMode),
            opticalStabilization = tapped?.opticalStabilizationMode?.let {
                it == android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            },
        )
    }

    /**
     * Sharpness of one burst frame: the central half (in each dimension) of the JPEG decoded at 1:2, converted
     * to luma and scored with [SharpnessScorer]. Returns 0 if the frame cannot be decoded.
     */
    private fun scoreJpeg(bytes: ByteArray): Float {
        val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        } ?: return 0f
        val bitmap = try {
            val w = decoder.width
            val h = decoder.height
            decoder.decodeRegion(
                Rect(w / 4, h / 4, w * 3 / 4, h * 3 / 4),
                BitmapFactory.Options().apply {
                    inSampleSize = 2
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } finally {
            decoder.recycle()
        } ?: return 0f
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()
        return SharpnessScorer.score(SharpnessScorer.lumaFromArgb(pixels), width, height)
    }

    /** The EXIF facts diagnostics fall back to. */
    class ExifFacts(
        val iso: Int?,
        val exposureTimeNs: Long?,
        val aperture: Float?,
        val focalLengthMm: Float?,
        val width: Int?,
        val height: Int?,
    ) {
        companion object {
            fun from(jpegHeader: ByteArray): ExifFacts {
                val exif = ExifInterface(ByteArrayInputStream(jpegHeader))
                fun int(tag: String) = exif.getAttributeInt(tag, -1).takeIf { it > 0 }
                fun dbl(tag: String) = exif.getAttributeDouble(tag, -1.0).takeIf { it > 0 }
                return ExifFacts(
                    iso = int(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
                    exposureTimeNs = dbl(ExifInterface.TAG_EXPOSURE_TIME)?.let { (it * 1e9).toLong() },
                    aperture = dbl(ExifInterface.TAG_F_NUMBER)?.toFloat(),
                    focalLengthMm = dbl(ExifInterface.TAG_FOCAL_LENGTH)?.toFloat(),
                    width = int(ExifInterface.TAG_PIXEL_X_DIMENSION) ?: int(ExifInterface.TAG_IMAGE_WIDTH),
                    height = int(ExifInterface.TAG_PIXEL_Y_DIMENSION) ?: int(ExifInterface.TAG_IMAGE_LENGTH),
                )
            }
        }
    }

    companion object {
        private const val TAG = "CapturePipeline"
        const val STEADY_WAIT_MS = 450L
        const val BURST_SIZE = 3
    }
}
