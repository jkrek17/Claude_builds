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
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.vision.FrameStackingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import com.compositioncoach.app.outcome.CapturePipeline as OutcomePipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
 *     and short enough not to miss the moment. Night instead holds for its `holdSeconds` of steadiness
 *     ([awaitSteadyHold]) with a progress ring.
 *  2. **Capture** ([capture]), per the outcome's plan: Motion stack, focus stack, or either lucky imaging (three in-memory frames back to back, keep the sharpest, write its
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

    /** Motion holds (see [MotionCapture]); its [MotionCapture.progress] is re-exposed as [holdProgress]. */
    private val motionCapture = MotionCapture(repository, motionSensor)
    private val stacked = StackedCaptures(repository, motionCapture, FocusStacker(repository, motionSensor), ::baseDiagnostics)
    private val shutterSound by lazy { ShutterSound(repository.context) }

    /** Night's steady hold ([awaitSteadyHold]) progress, null when none runs. */
    private val steadyHoldProgress = MutableStateFlow<Float?>(null)

    /** 0..1 while a Motion hold or a steady hold is running (for the progress ring), null otherwise. */
    val holdProgress: Flow<Float?> = combine(motionCapture.progress, steadyHoldProgress) { motion, steady -> motion ?: steady }

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

    /**
     * @param uri the photo to review: the stack for Motion/focus outcomes, else the only photo.
     * @param companionUri the normal first frame saved alongside a stack (null when there is none).
     * @param motionStatus how a Motion hold ended (null for other pipelines); TOO_SHAKY means [uri] is the
     *   first frame and the UI should say "Too shaky, try again".
     * @param plan what [capture] (outcome overload) decided, including the degraded note.
     */
    class ShotResult(
        val uri: Uri,
        val diagnostics: CaptureDiagnostics,
        val companionUri: Uri? = null,
        val motionStatus: MotionStatus? = null,
        val plan: CapturePlan? = null,
    )

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

    /**
     * Step 1 for an outcome that asks the photographer to hold (Night): waits until the phone has been steady
     * (not MOVING) for [holdMs] in a row, publishing the progress on [holdProgress] for the ring; a MOVING
     * reading starts the count again. Gives up and shoots after [maxWaitMs] (the vendor Night pipeline still
     * merges frames; a missed hold costs sharpness, not the photo). Without a gyro the phone reads STEADY, so
     * this is a plain [holdMs] countdown. Returns the steadiness at the end and the time waited.
     */
    suspend fun awaitSteadyHold(holdMs: Long, maxWaitMs: Long = holdMs + STEADY_HOLD_EXTRA_MS): Pair<Steadiness, Long> {
        val start = SystemClock.elapsedRealtime()
        var last = start
        var steadyMs = 0L
        steadyHoldProgress.value = 0f
        try {
            while (true) {
                delay(STEADY_HOLD_TICK_MS)
                val now = SystemClock.elapsedRealtime()
                steadyMs = if (motionSensor.steadiness.value == Steadiness.MOVING) 0L else steadyMs + (now - last)
                last = now
                steadyHoldProgress.value = (steadyMs.toFloat() / holdMs).coerceIn(0f, 1f)
                if (steadyMs >= holdMs || now - start >= maxWaitMs) break
            }
        } finally {
            steadyHoldProgress.value = null
        }
        return motionSensor.steadiness.value to (SystemClock.elapsedRealtime() - start)
    }

    private suspend fun captureAny(request: ShotRequest): ShotResult {
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

    /**
     * Steps 2 and 3: captures [outcome] with the pipeline its spec asks for, degraded to what this device and
     * bind can do ([OutcomeCapturePlanner]). The steady gate ([awaitSteady] / [awaitSteadyHold]) stays the
     * caller's job; auto-level likewise runs afterwards on [ShotResult.uri].
     *
     * - SINGLE / BURST_SHARPEST: one capture, or lucky imaging when [ShotRequest.burst] allows and no extension
     *   is active.
     * - FOCUS_STACK: [FocusStacker] when the lens takes manual focus and no extension is active, else burst.
     * - MOTION_*: [MotionCapture] over [stacking] (pass `frameSource as? FrameStackingSource`); without it the
     *   capture falls back to a burst and says so in [CaptureDiagnostics.degradedNote].
     *
     * The shutter click plays first when the region requires it ([ShutterSoundPolicy]). Diagnostics carry
     * `pipeline`, `framesStacked` and `degradedNote`. Throws only if even a single capture fails.
     */
    suspend fun capture(outcome: Outcome, request: ShotRequest, stacking: FrameStackingSource? = null): ShotResult {
        val controller = request.controller
        val session = request.session
        val lens = controller?.lensControl
        val active = session?.activeEnhancement ?: CaptureEnhancement.OFF
        val support = PipelineSupport(
            motionStacking = stacking != null && session?.analysisPausedByEnhancement != true,
            focusStacking = lens?.supportsFocusStack == true,
        )
        val plan = OutcomeCapturePlanner.plan(outcome, session?.availableEnhancements ?: setOf(CaptureEnhancement.OFF), support)
        shutterSound.playIfMandatory()
        val plain = active == CaptureEnhancement.OFF
        val result = when (plan.pipeline) {
            OutcomePipeline.SINGLE -> captureSingle(request).tagged(OutcomePipeline.SINGLE)
            OutcomePipeline.BURST_SHARPEST -> captureAny(request.withBurst(plain && controller != null))
                .let { it.tagged(if (it.diagnostics.burstScores != null) OutcomePipeline.BURST_SHARPEST else OutcomePipeline.SINGLE) }
            OutcomePipeline.FOCUS_STACK -> (if (plain) stacked.focusStack(request, lens) else null)
                ?: captureAny(request.withBurst(plain && controller != null)).tagged(OutcomePipeline.SINGLE)
            OutcomePipeline.MOTION_AVERAGE, OutcomePipeline.MOTION_LIGHTEN, OutcomePipeline.MOTION_MEDIAN ->
                stacked.motion(request, plan.pipeline, outcome.spec.holdSeconds, stacking!!, lens)
        }
        // What actually went wrong on this shot ("Too shaky") outranks what the plan expected ("no Night here").
        val diagnostics = result.diagnostics.copy(degradedNote = result.diagnostics.degradedNote ?: plan.degradedNote)
        return ShotResult(result.uri, diagnostics, result.companionUri, result.motionStatus, plan)
    }

    private fun ShotRequest.withBurst(burst: Boolean) = ShotRequest(
        controller, imageCapture, lensFacing, session, burst, zoomRatio, exposureIndex, shutterAtElapsedMs, steadiness, waitedForSteadyMs,
    )

    private fun ShotResult.tagged(pipeline: OutcomePipeline, frames: Int? = null) =
        ShotResult(uri, diagnostics.copy(pipeline = pipeline.name, framesStacked = frames), companionUri, motionStatus, plan)

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

    internal fun baseDiagnostics(
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

        /** How long past its hold a steady hold may run before shooting anyway. */
        const val STEADY_HOLD_EXTRA_MS = 3_000L
        private const val STEADY_HOLD_TICK_MS = 50L
        const val BURST_SIZE = 3
    }
}
