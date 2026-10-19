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
import com.compositioncoach.vision.GyroAlignment
import com.compositioncoach.vision.PixelShift
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The focus stack: in-memory captures at a set of focus distances, by default infinity, 1/3 and 2/3 of
 * the lens's closest focus ([FAR_FRACTIONS]; the Food outcome passes [NEAR_FRACTIONS]), set in diopters via
 * [LensControl.setManualFocus] (i.e. `CONTROL_AF_MODE_OFF` + `LENS_FOCUS_DISTANCE`), autofocus restored afterwards
 * whatever happens, then merged by [FocusMerge].
 *
 * Alignment is rotation-only from the gyro (the hand drifts a few pixels between exposures): the rotation
 * between capture completions, times the focal length in pixels, mapped from portrait axes into the stored
 * buffer (whose rotation to portrait is the JPEG's rotation plus the capture target rotation). Not corrected: focus breathing (the field of view changes slightly with
 * focus distance; at the edges of a 12 MP frame this can be several pixels, softening the blend there) and
 * anything that moves between exposures (leaves, water: the merge may mix two positions).
 *
 * The infinity exposure is also saved untouched (original JPEG bytes) as the normal first frame, so a failed
 * merge never costs the shot. Merging decodes the JPEGs in 256-row strips with [BitmapRegionDecoder]
 * (peak memory ~ one full-size output bitmap plus one strip per frame).
 */
class FocusStacker(private val repository: CaptureRepository, private val motionSensor: MotionSensor) {

    /** [mergedUri] null when only the first frame could be saved. */
    class Result(val mergedUri: Uri?, val firstUri: Uri, val first: CapturedJpeg, val captureMs: Long, val totalMs: Long)

    /**
     * Runs the stack at [focusFractions] of the lens's closest focus distance in diopters (0 = infinity,
     * 1 = closest; at least two, the first is the companion frame). Returns null when the lens cannot do it or
     * the first capture failed (the caller then falls back to a normal capture); throws only on cancellation.
     */
    suspend fun capture(
        controller: CameraController,
        imageCapture: ImageCapture,
        lens: LensControl,
        mirror: Boolean,
        focusFractions: List<Float> = FAR_FRACTIONS,
    ): Result? {
        require(focusFractions.size >= 2) { "A focus stack needs at least two distances" }
        val min = lens.minimumFocusDistanceDiopters ?: return null
        if (!lens.supportsFocusStack) return null
        val start = SystemClock.elapsedRealtime()
        val count = focusFractions.size
        val frames = ArrayList<CapturedJpeg>(count)
        val times = ArrayList<Long>(count)
        try {
            for (distance in diopters(focusFractions, min)) {
                lens.setManualFocus(distance)
                delay(LENS_SETTLE_MS)
                frames += controller.captureBurst(imageCapture, 1).first()
                times += motionSensor.nowNs()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Focus stack capture stopped after ${frames.size} frame(s)", t)
        } finally {
            withContext(NonCancellable) { lens.restoreAutoFocus() }
        }
        val captureMs = SystemClock.elapsedRealtime() - start
        val first = frames.firstOrNull() ?: return null
        val firstUri = repository.saveJpeg(first.bytes, first.rotationDegrees, mirror)
        if (frames.size < count) return Result(null, firstUri, first, captureMs, SystemClock.elapsedRealtime() - start)
        val merged = runCatching {
            val shifts = gyroShifts(controller, motionSensor, lens, mirror, frames, times)
            val bitmap = withContext(Dispatchers.Default) { merge(frames, shifts) }
            val orientation = ExifOrientations.tagFor(first.rotationDegrees, mirror)
            repository.exportProcessedOriented(bitmap, "_stack", orientation, first.bytes)
        }.onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Focus merge failed; keeping the first frame", it) }
            .getOrNull()
        return Result(merged, firstUri, first, captureMs, SystemClock.elapsedRealtime() - start)
    }

    /** Merges the stored-orientation JPEGs into one stored-orientation bitmap. */
    private fun merge(frames: List<CapturedJpeg>, shifts: List<PixelShift>): Bitmap {
        val guides = frames.mapIndexed { i, f ->
            val small = BitmapFactory.decodeByteArray(f.bytes, 0, f.bytes.size, BitmapFactory.Options().apply { inSampleSize = FocusMerge.GUIDE_SCALE })
                ?: error("Cannot decode frame $i")
            val px = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
            Triple(FocusMerge.luma(px), small.width, small.height).also { small.recycle() }
        }
        val gw = guides[0].second
        val gh = guides[0].third
        val aligned = guides.mapIndexed { i, (g, w, h) ->
            require(w == gw && h == gh) { "Frames differ in size" }
            val s = shifts[i]
            FocusMerge.shiftGuide(g, gw, gh, PixelShift(s.dx / FocusMerge.GUIDE_SCALE, s.dy / FocusMerge.GUIDE_SCALE))
        }
        val weights = FocusMerge.weights(aligned, gw, gh)
        val decoders = frames.map { openRegionDecoder(it.bytes) }
        try {
            val width = decoders[0].width
            val height = decoders[0].height
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val outRows = IntArray(width * STRIP_ROWS)
            var y0 = 0
            while (y0 < height) {
                val rows = minOf(STRIP_ROWS, height - y0)
                val strips = decoders.mapIndexed { i, d -> decodeStrip(d, width, height, y0, rows, shifts[i]) }
                FocusMerge.blendStrip(strips, width, height, y0, rows, weights, gw, gh, outRows)
                out.setPixels(outRows, 0, width, 0, y0, width, rows)
                y0 += rows
            }
            return out
        } finally {
            decoders.forEach { it.recycle() }
        }
    }

    companion object {
        private const val TAG = "FocusStacker"

        /** Landscape / A view: infinity, then 1/3 and 2/3 of the way to the closest focus (the lens's diopters). */
        val FAR_FRACTIONS: List<Float> = listOf(0f, 1f / 3f, 2f / 3f)

        /** Food & things: the closest focus, then 0.8x and 0.6x of it in diopters (just behind the nearest plane). */
        val NEAR_FRACTIONS: List<Float> = listOf(1f, 0.8f, 0.6f)

        /** Focus distances in diopters for [fractions] of the lens's [minimumDiopters]. */
        fun diopters(fractions: List<Float>, minimumDiopters: Float): List<Float> = fractions.map { it * minimumDiopters }

        /** Lens travel between distances (VCM lenses settle in ~50-150 ms). */
        const val LENS_SETTLE_MS = 180L
        const val STRIP_ROWS = 256
        /** A bigger shift than this between exposures is not "a little drift"; clamp rather than trust it. */
        const val MAX_SHIFT_PX = 120

        /**
         * Pixel shifts of every frame relative to the first, from the gyro rotation between capture completions
         * ([times], from [MotionSensor.nowNs]) times the focal length in pixels, mapped from portrait axes into the
         * stored buffer (whose rotation to portrait is the JPEG's rotation plus the capture target rotation). Frames
         * with no gyro data get [PixelShift.NONE]. Shared with the exposure bracket.
         */
        internal fun gyroShifts(
            controller: CameraController,
            motionSensor: MotionSensor,
            lens: LensControl?,
            mirror: Boolean,
            frames: List<CapturedJpeg>,
            times: List<Long>,
        ): List<PixelShift> {
            val first = frames.first()
            // CameraX reports rotationDegrees relative to the capture target rotation, so add it back (front cameras subtract).
            val target = controller.captureTargetRotationDegrees
            val bufferToPortrait = ((first.rotationDegrees + if (mirror) -target else target) % 360 + 360) % 360
            val focal = lens?.focalToLongEdge() ?: GyroAlignment.DEFAULT_FOCAL_TO_LONG_EDGE
            return times.map { t ->
                val delta = motionSensor.angularDeltaBetween(times[0], t) ?: return@map PixelShift.NONE
                val display = GyroAlignment.contentShift(delta, maxOf(first.width, first.height), focal, isFrontCamera = mirror)
                GyroAlignment.clamp(GyroAlignment.toBuffer(display, bufferToPortrait), MAX_SHIFT_PX)
            }
        }

        /** Decodes output rows [y0] until [y0] + [rows] of a [shift]ed frame (the rows its samples need) as a [FocusMerge.Strip]. */
        internal fun decodeStrip(decoder: BitmapRegionDecoder, width: Int, height: Int, y0: Int, rows: Int, shift: PixelShift): FocusMerge.Strip {
            val top = (y0 + shift.dy).coerceIn(0, height - 1)
            val bottom = (y0 + rows + shift.dy).coerceIn(top + 1, height)
            val bitmap = decoder.decodeRegion(Rect(0, top, width, bottom), BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?: error("Region decode failed")
            val n = bottom - top
            val px = IntArray(width * n).also { bitmap.getPixels(it, 0, width, 0, 0, width, n) }
            bitmap.recycle()
            return FocusMerge.Strip(px, top, n, shift)
        }

        internal fun openRegionDecoder(bytes: ByteArray): BitmapRegionDecoder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
            } ?: error("No region decoder")
    }
}
