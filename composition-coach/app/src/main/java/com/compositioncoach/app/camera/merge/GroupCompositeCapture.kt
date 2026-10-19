package com.compositioncoach.app.camera.merge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageCapture
import com.compositioncoach.app.camera.CameraController
import com.compositioncoach.app.camera.CaptureRepository
import com.compositioncoach.app.camera.CapturedJpeg
import com.compositioncoach.app.camera.ExifOrientations
import com.compositioncoach.app.camera.LensControl
import com.compositioncoach.app.camera.MotionSensor
import com.compositioncoach.app.camera.captureBurst
import com.compositioncoach.vision.GyroAlignment
import com.compositioncoach.vision.PerformanceTier
import com.compositioncoach.vision.PixelShift
import com.compositioncoach.vision.StillImageAnalyzer
import com.compositioncoach.vision.merge.FaceComposite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The group outcome's face composite (`CapturePipeline.GROUP_COMPOSITE`): a burst of full-resolution frames
 * in which every face is taken from the frame where it looks best, so nobody blinks.
 *
 * ## Capture
 * [FULL_FRAMES] back-to-back JPEGs through [captureBurst] ([REDUCED_FRAMES] under a `REDUCED` tier, one frame
 * under `MINIMAL`, see [frameCount]) with auto-exposure locked ([LensControl.lockAutoExposure], released in
 * `finally`) so the frames share one brightness and a face pasted from another frame matches its neighbours.
 * The first frame is saved untouched as the companion before any merging starts, so a failed merge never
 * costs the shot.
 *
 * ## Merge (all in `Dispatchers.Default`)
 * 1. Each JPEG is decoded at 1/[GUIDE_SCALE] (`inSampleSize`): its luma is the alignment and sharpness guide,
 *    and the same decode, rotated upright, goes to ML Kit's face detector with classification on
 *    ([StillImageAnalyzer.detectFaces]; `eyesOpenProbability` and the box per face). Boxes are mapped back into
 *    the stored buffer's coordinates.
 * 2. The gyro shift of every frame (as in `FocusStacker`: rotation between sensor timestamps times the focal
 *    length in pixels, mapped into the buffer) seeds a per-face block match on the guide
 *    ([FaceComposite.plan]); the plan picks the base frame, each face's best frame, and refuses a face whose
 *    alignment residual is large (the person moved or turned), keeping the base there.
 * 3. The output is built in [STRIP_ROWS]-row strips: the base frame is region-decoded strip by strip and each
 *    chosen face box (grown 25 %, feathered over 8 % of its width) is region-decoded from its source frame at
 *    the aligned position and blended in. Peak memory is the output bitmap plus a strip and a few face patches,
 *    never a second full frame.
 *
 * The result is saved as `_group` with the first frame's EXIF. It is saved whenever the base frame is not the
 * companion (that alone is an improvement: more open eyes) or any face was replaced; otherwise only the
 * companion survives and [Result.note] says why. `OutOfMemoryError` is caught like any other merge failure.
 */
class GroupCompositeCapture(
    private val repository: CaptureRepository,
    private val motionSensor: MotionSensor,
    private val analyzer: StillImageAnalyzer = StillImageAnalyzer(),
) {

    /**
     * [mergedUri] is null when only the companion survived; [firstUri] is the companion; [framesUsed] counts the
     * frames that contribute to the saved result (1 when only the companion survived); [note] is one plain
     * English sentence when the outcome is degraded (see the `NOTE_*` constants), else null.
     */
    class Result(
        val mergedUri: Uri?,
        val firstUri: Uri,
        val first: CapturedJpeg,
        val framesUsed: Int,
        val captureMs: Long,
        val totalMs: Long,
        val note: String?,
    )

    /** What the merge stage produced: the [bitmap] in stored orientation, or [note] saying why it kept the companion. */
    private class Merged(val bitmap: Bitmap?, val framesUsed: Int, val note: String?)

    /**
     * Runs the burst and the composite. Returns null when not even one frame could be captured (the caller then
     * falls back to a normal capture); throws only on cancellation. [lens] may be null (no exposure lock then).
     */
    suspend fun capture(
        controller: CameraController,
        imageCapture: ImageCapture,
        lens: LensControl?,
        mirror: Boolean,
        tier: PerformanceTier,
    ): Result? {
        val start = SystemClock.elapsedRealtime()
        val wanted = frameCount(tier)
        var frames: List<CapturedJpeg> = emptyList()
        try {
            if (wanted > 1) {
                runCatching { lens?.lockAutoExposure() }
                delay(AE_LOCK_SETTLE_MS)
            }
            frames = controller.captureBurst(imageCapture, wanted)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Group burst failed", t)
        } finally {
            runCatching { lens?.unlockAutoExposure() }
        }
        if (frames.isEmpty() && wanted > 1) {
            // The burst is all-or-nothing; one plain frame still saves the photo.
            frames = try {
                controller.captureBurst(imageCapture, 1)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Group fallback capture failed", t)
                emptyList()
            }
        }
        val captureMs = SystemClock.elapsedRealtime() - start
        val first = frames.firstOrNull() ?: return null
        val firstUri = repository.saveJpeg(first.bytes, first.rotationDegrees, mirror)
        if (frames.size < 2) {
            return Result(null, firstUri, first, 1, captureMs, SystemClock.elapsedRealtime() - start, NOTE_ONE_FRAME)
        }

        var note: String? = null
        var mergedUri: Uri? = null
        var framesUsed = 1
        try {
            val merged = withContext(Dispatchers.Default) { merge(frames, controller.captureTargetRotationDegrees, lens, mirror) }
            note = merged.note
            if (merged.bitmap != null) {
                val orientation = ExifOrientations.tagFor(first.rotationDegrees, mirror)
                mergedUri = repository.exportProcessedOriented(merged.bitmap, "_group", orientation, first.bytes)
                framesUsed = merged.framesUsed
            }
        } catch (c: CancellationException) {
            throw c
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "Group composite ran out of memory; keeping the first frame", oom)
            note = NOTE_OUT_OF_MEMORY
        } catch (t: Throwable) {
            Log.w(TAG, "Group composite failed; keeping the first frame", t)
            note = NOTE_MERGE_FAILED
        }
        return Result(mergedUri, firstUri, first, framesUsed, captureMs, SystemClock.elapsedRealtime() - start, note)
    }

    /** The guide-scale pass (luma, faces, plan) and the strip composite; see the class doc. */
    private suspend fun merge(frames: List<CapturedJpeg>, captureTargetRotation: Int, lens: LensControl?, mirror: Boolean): Merged {
        val first = frames[0]
        val lumas = ArrayList<FloatArray>(frames.size)
        val uprights = ArrayList<Bitmap>(frames.size)
        var gw = 0
        var gh = 0
        try {
            for (frame in frames) {
                val small = BitmapFactory.decodeByteArray(
                    frame.bytes, 0, frame.bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = GUIDE_SCALE; inPreferredConfig = Bitmap.Config.ARGB_8888 },
                ) ?: error("Cannot decode a burst frame")
                if (gw == 0) { gw = small.width; gh = small.height }
                require(small.width == gw && small.height == gh) { "Frames differ in size" }
                val pixels = IntArray(gw * gh).also { small.getPixels(it, 0, gw, 0, 0, gw, gh) }
                lumas += FloatArray(pixels.size) { FaceComposite.luma(pixels[it]) }
                uprights += upright(small, frame.rotationDegrees)
            }
            val detected = analyzer.detectFaces(uprights)
            val faces = frames.indices.map { i ->
                detected[i].orEmpty().map { f ->
                    FaceComposite.Face(FaceComposite.uprightRectToBuffer(f.bounds, frames[i].rotationDegrees), f.eyesOpenProbability)
                }
            }
            if (detected.all { it == null }) return Merged(null, 1, NOTE_DETECTION_FAILED)
            if (faces.all { it.isEmpty() }) return Merged(null, 1, NOTE_NO_FACES)

            val bufferToPortrait = ((first.rotationDegrees + if (mirror) -captureTargetRotation else captureTargetRotation) % 360 + 360) % 360
            val shifts = guideShifts(frames, bufferToPortrait, lens, mirror, gw, first.width)
            val plan = FaceComposite.plan(lumas, gw, gh, faces, shifts)
            if (plan.base == 0 && plan.replacements.isEmpty()) {
                return Merged(null, 1, if (plan.rejected > 0) NOTE_MOVED else NOTE_NO_IMPROVEMENT)
            }
            val bitmap = composite(frames, plan, gw, gh)
            val used = (plan.replacements.map { it.source } + plan.base).distinct().size
            return Merged(bitmap, used, null)
        } finally {
            uprights.forEach { it.recycle() }
        }
    }

    /** Gyro shift of every frame relative to the first, in guide pixels (zero where the gyro history has no answer). */
    private fun guideShifts(frames: List<CapturedJpeg>, bufferToPortrait: Int, lens: LensControl?, mirror: Boolean, guideWidth: Int, fullWidth: Int): List<PixelShift> {
        val first = frames[0]
        val focal = lens?.focalToLongEdge() ?: GyroAlignment.DEFAULT_FOCAL_TO_LONG_EDGE
        val scale = fullWidth.toFloat() / guideWidth
        return frames.map { f ->
            val delta = motionSensor.angularDeltaBetween(first.sensorTimestampNs, f.sensorTimestampNs) ?: return@map PixelShift.NONE
            val display = GyroAlignment.contentShift(delta, maxOf(first.width, first.height), focal, isFrontCamera = mirror)
            val buffer = GyroAlignment.clamp(GyroAlignment.toBuffer(display, bufferToPortrait), MAX_SHIFT_PX)
            PixelShift((buffer.dx / scale).roundToInt(), (buffer.dy / scale).roundToInt())
        }
    }

    /** Pastes the planned faces into the base frame, strip by strip; returns the stored-orientation bitmap. */
    private fun composite(frames: List<CapturedJpeg>, plan: FaceComposite.Plan, gw: Int, gh: Int): Bitmap {
        val needed = (plan.replacements.map { it.source } + plan.base).distinct()
        val decoders = needed.associateWith { regionDecoder(frames[it].bytes) }
        try {
            val base = decoders.getValue(plan.base)
            val width = base.width
            val height = base.height
            require(decoders.values.all { it.width == width && it.height == height }) { "Frames differ in size" }
            val scaleX = width.toFloat() / gw
            val scaleY = height.toFloat() / gh
            // Each patch: the grown box in full-resolution pixels (cut where the aligned source would leave the frame) and its shift.
            class Patch(val box: FaceComposite.PixelBox, val source: Int, val dx: Int, val dy: Int)
            val patches = plan.replacements.mapNotNull { r ->
                val dx = (r.alignment.dx * scaleX).roundToInt()
                val dy = (r.alignment.dy * scaleY).roundToInt()
                val box = FaceComposite.sourceSafeBox(FaceComposite.growBox(r.bounds, width, height), dx, dy, width, height)
                box?.let { Patch(it, r.source, dx, dy) }
            }
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            var y0 = 0
            while (y0 < height) {
                val rows = minOf(STRIP_ROWS, height - y0)
                val strip = decodePixels(base, Rect(0, y0, width, y0 + rows), options)
                for (p in patches) {
                    val top = maxOf(p.box.top, y0)
                    val bottom = minOf(p.box.bottom, y0 + rows)
                    if (bottom <= top) continue
                    val patch = decodePixels(
                        decoders.getValue(p.source),
                        Rect(p.box.left + p.dx, top + p.dy, p.box.right + p.dx, bottom + p.dy),
                        options,
                    )
                    FaceComposite.blendPatch(strip, width, y0, rows, p.box, patch, top, bottom - top)
                }
                out.setPixels(strip, 0, width, 0, y0, width, rows)
                y0 += rows
            }
            return out
        } finally {
            decoders.values.forEach { it.recycle() }
        }
    }

    private fun decodePixels(decoder: BitmapRegionDecoder, rect: Rect, options: BitmapFactory.Options): IntArray {
        val bitmap = decoder.decodeRegion(rect, options) ?: error("Region decode failed")
        try {
            require(bitmap.width == rect.width() && bitmap.height == rect.height()) { "Region decode returned a different size" }
            return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        } finally {
            bitmap.recycle()
        }
    }

    /** [small] rotated clockwise by [degrees] to stand upright for ML Kit (recycles [small] when it is replaced). */
    private fun upright(small: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return small
        val rotated = Bitmap.createBitmap(small, 0, 0, small.width, small.height, Matrix().apply { postRotate(d.toFloat()) }, true)
        if (rotated !== small) small.recycle()
        return rotated
    }

    private fun regionDecoder(bytes: ByteArray): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        } ?: error("No region decoder")

    companion object {
        private const val TAG = "GroupCompositeCapture"

        /** Frames in a full-power burst. */
        const val FULL_FRAMES = 5

        /** Frames under a `REDUCED` tier (half, rounded up). */
        const val REDUCED_FRAMES = 3

        /** Guide decode scale: 1/4 of the full resolution per side (`inSampleSize`). */
        const val GUIDE_SCALE = 4

        const val STRIP_ROWS = 256

        /** A bigger gyro shift than this between frames is not "a little drift"; clamp rather than trust it. */
        const val MAX_SHIFT_PX = 120

        /** Time for the auto-exposure lock to reach the repeating request before the first frame is taken. */
        const val AE_LOCK_SETTLE_MS = 100L

        // Degraded notes (English; the integrator maps each to a DegradedNote string resource).
        const val NOTE_ONE_FRAME = "Only one frame was captured, so no faces could be combined."
        const val NOTE_NO_FACES = "No faces were found in the burst, so the photo was kept as shot."
        const val NOTE_DETECTION_FAILED = "Face detection was not available, so the photo was kept as shot."
        const val NOTE_NO_IMPROVEMENT = "No face looked better in another frame, so the photo was kept as shot."
        const val NOTE_MOVED = "People moved too much between frames to combine faces, so the photo was kept as shot."
        const val NOTE_OUT_OF_MEMORY = "The phone ran out of memory combining faces, so the photo was kept as shot."
        const val NOTE_MERGE_FAILED = "Combining faces failed, so the photo was kept as shot."

        /** Burst length for [tier]: all frames at FULL, half at REDUCED, the first frame only at MINIMAL. */
        fun frameCount(tier: PerformanceTier): Int = when (tier) {
            PerformanceTier.FULL -> FULL_FRAMES
            PerformanceTier.REDUCED -> REDUCED_FRAMES
            PerformanceTier.MINIMAL -> 1
        }
    }
}
