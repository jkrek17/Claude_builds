package com.compositioncoach.app.camera.merge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageCapture
import com.compositioncoach.app.camera.CameraController
import com.compositioncoach.app.camera.CapturedJpeg
import com.compositioncoach.app.camera.CaptureRepository
import com.compositioncoach.app.camera.ExifOrientations
import com.compositioncoach.app.camera.FocusMerge
import com.compositioncoach.app.camera.FocusStacker
import com.compositioncoach.app.camera.LensControl
import com.compositioncoach.app.camera.MotionSensor
import com.compositioncoach.app.camera.captureBurst
import com.compositioncoach.app.camera.setExposureCompensation
import com.compositioncoach.vision.PerformanceTier
import com.compositioncoach.vision.PixelShift
import com.compositioncoach.vision.merge.ExposureFusion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The exposure bracket: three full-resolution frames at -2, 0 and +2 EV (exposure compensation, clamped to the
 * lens's range; a narrower range uses its ends), fused by [ExposureFusion] into one `_hdr` photo with the sky of
 * the dark frame and the shadows of the bright one. The 0 EV frame is saved untouched first (the companion), so a
 * failed fusion never costs the shot.
 *
 * ## Exposure control
 * Compensation only moves auto-exposure while it is unlocked, so each non-base frame is: unlock AE, set the
 * compensation, wait [EV_SETTLE_MS] for AE to converge, lock AE ([LensControl.lockAutoExposure], where the lens
 * supports it) and capture; the lock keeps a changing scene from moving the exposure between that frame and the
 * next. The base is the compensation the user had set (so "0 EV" is their 0), and compensation, AE lock and
 * autofocus are restored in `finally`, even on cancellation.
 *
 * ## With the focus stack (the A view)
 * When [LensControl.supportsFocusStack], the three focus distances of [FocusStacker.FAR_FRACTIONS] are captured at
 * the base exposure, then the lens returns to the first distance (infinity) for the -2 and +2 EV frames: five
 * frames. The three base-exposure frames are focus-merged ([FocusMerge], per strip) into one frame that takes the
 * place of the 0 EV frame in the exposure fusion, so depth of field and dynamic range come out of one pass.
 *
 * ## Alignment and memory
 * Gyro first ([FocusStacker.gyroShifts]), then a refinement on the guide-scale luma by median-threshold-bitmap
 * matching ([ExposureFusion.alignmentShift], +-[ExposureFusion.ALIGN_RADIUS] guide pixels, independent of the
 * exposure difference). Fusion runs in [STRIP_ROWS]-row strips through region decoders: peak memory is one
 * full-size output bitmap, one strip per frame and the guide-scale planes. [PerformanceTier] scales the frames:
 * FULL as above, REDUCED a 0 / -2 EV pair without the focus stack, MINIMAL the first frame only. An
 * [OutOfMemoryError] during fusion keeps the companion and reports a note.
 */
class ExposureBracketCapture(private val repository: CaptureRepository, private val motionSensor: MotionSensor) {

    /**
     * @property mergedUri the `_hdr` photo, null when only the companion survived.
     * @property firstUri the companion (the first frame at the base exposure), saved before any merging.
     * @property first the companion's capture, for EXIF-based diagnostics.
     * @property framesUsed frames that went into the fusion (1 when none did).
     * @property focusStacked whether the base-exposure frame came from a focus merge (pipeline FOCUS_STACK-like).
     * @property note one plain English sentence when the result is degraded, else null.
     */
    class Result(
        val mergedUri: Uri?,
        val firstUri: Uri,
        val first: CapturedJpeg,
        val framesUsed: Int,
        val captureMs: Long,
        val totalMs: Long,
        val note: String?,
        val focusStacked: Boolean = false,
    )

    /** What to capture: compensation indices (the first is the base) and whether to focus-stack the base exposure. */
    internal class Plan(val exposureIndices: List<Int>, val focusStack: Boolean) {
        val frameCount: Int get() = exposureIndices.size + if (focusStack) FocusStacker.FAR_FRACTIONS.size - 1 else 0
    }

    private class Frame(val jpeg: CapturedJpeg, val timeNs: Long, val exposureIndex: Int, val inFocusGroup: Boolean)

    /**
     * Runs the bracket. Returns null when it cannot run here (no bound camera, or no exposure compensation range)
     * or the first capture failed, so the caller falls back to a normal capture; throws only on cancellation.
     * [withFocusStack] false never focus-stacks (the Landscape fallback when the caller handles focus itself).
     */
    suspend fun capture(
        controller: CameraController,
        imageCapture: ImageCapture,
        lens: LensControl?,
        mirror: Boolean,
        tier: PerformanceTier,
        withFocusStack: Boolean = true,
    ): Result? {
        val state = controller.camera?.cameraInfo?.exposureState ?: return null
        if (!state.isExposureCompensationSupported) return null
        val range = state.exposureCompensationRange
        val step = state.exposureCompensationStep.toFloat()
        if (step <= 0f || range.lower >= range.upper) return null
        val base = state.exposureCompensationIndex
        val canFocusStack = withFocusStack && lens != null && lens.supportsFocusStack && (lens.minimumFocusDistanceDiopters ?: 0f) > 0f
        val plan = plan(tier, base, step, range.lower, range.upper, canFocusStack)

        val start = SystemClock.elapsedRealtime()
        val frames = ArrayList<Frame>(plan.frameCount)
        try {
            captureFrames(controller, imageCapture, lens, plan, frames)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Exposure bracket capture stopped after ${frames.size} frame(s)", t)
        } finally {
            withContext(NonCancellable) {
                lens?.unlockAutoExposure()
                if (plan.focusStack) lens?.restoreAutoFocus()
                controller.setExposureCompensation(base)
            }
        }
        val captureMs = SystemClock.elapsedRealtime() - start
        val first = frames.firstOrNull()?.jpeg ?: return null
        val firstUri = repository.saveJpeg(first.bytes, first.rotationDegrees, mirror)
        logExposures(controller, frames)

        fun finish(merged: Uri?, used: Int, note: String?, focusStacked: Boolean = false) =
            Result(merged, firstUri, first, used, captureMs, SystemClock.elapsedRealtime() - start, note, focusStacked)

        if (plan.exposureIndices.size < 2) {
            return finish(null, 1, if (tier == PerformanceTier.MINIMAL) NOTE_THERMAL else NOTE_COLLAPSED)
        }
        val exposures = frames.filter { !it.inFocusGroup && it !== frames.first() }
        if (exposures.isEmpty()) return finish(null, 1, NOTE_COLLAPSED)
        val focusFrames = frames.count { it.inFocusGroup }.takeIf { it >= 2 } ?: 0
        val used = (if (focusFrames >= 2) focusFrames else 1) + exposures.size

        var note: String? = null
        val merged = try {
            val shifts = FocusStacker.gyroShifts(controller, motionSensor, lens, mirror, frames.map { it.jpeg }, frames.map { it.timeNs })
            val bitmap = withContext(Dispatchers.Default) { fuse(frames, shifts) }
            val orientation = ExifOrientations.tagFor(first.rotationDegrees, mirror)
            repository.exportProcessedOriented(bitmap, "_hdr", orientation, first.bytes)
        } catch (c: CancellationException) {
            throw c
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "Exposure fusion ran out of memory; keeping the first frame", oom)
            note = NOTE_LOW_MEMORY
            null
        } catch (t: Throwable) {
            Log.w(TAG, "Exposure fusion failed; keeping the first frame", t)
            note = NOTE_MERGE_FAILED
            null
        }
        if (merged == null) return finish(null, 1, note)
        if (note == null && frames.size < plan.frameCount) note = NOTE_PARTIAL
        return finish(merged, used, note, focusStacked = focusFrames >= 2)
    }

    /** Captures [plan]'s frames into [frames], in order; stops (leaving what it has) on the first failure. */
    private suspend fun captureFrames(
        controller: CameraController,
        imageCapture: ImageCapture,
        lens: LensControl?,
        plan: Plan,
        frames: MutableList<Frame>,
    ) {
        suspend fun shot(exposureIndex: Int, inFocusGroup: Boolean) {
            val jpeg = controller.captureBurst(imageCapture, 1).first()
            frames += Frame(jpeg, motionSensor.nowNs(), exposureIndex, inFocusGroup)
        }
        val minimum = lens?.minimumFocusDistanceDiopters ?: 0f
        val distances = FocusStacker.diopters(FocusStacker.FAR_FRACTIONS, minimum)
        if (plan.focusStack && lens != null) {
            lens.setManualFocus(distances.first())
            delay(FocusStacker.LENS_SETTLE_MS)
        }
        lens?.lockAutoExposure()
        shot(plan.exposureIndices.first(), plan.focusStack)
        if (plan.focusStack && lens != null) {
            for (distance in distances.drop(1)) {
                lens.setManualFocus(distance)
                delay(FocusStacker.LENS_SETTLE_MS)
                shot(plan.exposureIndices.first(), inFocusGroup = true)
            }
            // Back to the first distance for the brackets; it settles during the exposure wait below.
            lens.setManualFocus(distances.first())
        }
        for (index in plan.exposureIndices.drop(1)) {
            lens?.unlockAutoExposure()
            controller.setExposureCompensation(index)
            delay(EV_SETTLE_MS)
            lens?.lockAutoExposure()
            shot(index, inFocusGroup = false)
        }
    }

    private fun logExposures(controller: CameraController, frames: List<Frame>) {
        val tap = controller.captureResultTap ?: return
        val line = frames.joinToString("; ") { f ->
            val r = tap.forTimestamp(f.jpeg.sensorTimestampNs)
            "idx ${f.exposureIndex}${if (f.inFocusGroup) "f" else ""}: ${r?.exposureTimeNs?.let { "${it / 1000}us" } ?: "?"} iso ${r?.iso ?: "?"}"
        }
        Log.i(TAG, "Exposure bracket frames: $line")
    }

    /**
     * Fuses the stored-orientation [frames] ([shifts]: gyro shifts relative to the first) into one stored-orientation
     * bitmap: guide-scale decode, alignment, optional focus merge of the base-exposure group, exposure weights and
     * pyramid, then the full-resolution strip pass.
     */
    private fun fuse(frames: List<Frame>, shifts: List<PixelShift>): Bitmap {
        val scale = ExposureFusion.GUIDE_SCALE
        val decoded = frames.mapIndexed { i, f ->
            val small = BitmapFactory.decodeByteArray(f.jpeg.bytes, 0, f.jpeg.bytes.size, BitmapFactory.Options().apply { inSampleSize = scale })
                ?: error("Cannot decode frame $i")
            val px = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
            ExposureFusion.Guide(px, small.width, small.height).also { small.recycle() }
        }
        val gw = decoded[0].width
        val gh = decoded[0].height
        require(decoded.all { it.width == gw && it.height == gh }) { "Frames differ in size" }

        // Alignment: gyro estimate in guide pixels, refined against the first frame; the refinement is added to the
        // gyro shift in full-resolution pixels so the gyro's precision is kept.
        val referenceLuma = ExposureFusion.luma(decoded[0].pixels)
        val guideShifts = ArrayList<PixelShift>(frames.size)
        val fullShifts = ArrayList<PixelShift>(frames.size)
        for (i in frames.indices) {
            if (i == 0) {
                guideShifts += PixelShift.NONE
                fullShifts += PixelShift.NONE
                continue
            }
            val initial = PixelShift((shifts[i].dx / scale.toFloat()).roundToInt(), (shifts[i].dy / scale.toFloat()).roundToInt())
            val refined = ExposureFusion.alignmentShift(referenceLuma, ExposureFusion.luma(decoded[i].pixels), gw, gh, initial)
            guideShifts += refined
            fullShifts += PixelShift(
                (shifts[i].dx + (refined.dx - initial.dx) * scale).coerceIn(-MAX_SHIFT_PX, MAX_SHIFT_PX),
                (shifts[i].dy + (refined.dy - initial.dy) * scale).coerceIn(-MAX_SHIFT_PX, MAX_SHIFT_PX),
            )
        }
        val aligned = decoded.mapIndexed { i, g -> ExposureFusion.shiftArgb(g.pixels, gw, gh, guideShifts[i]) }

        // The base-exposure frames: one frame, or a focus merge of the group (weights on the focus merge's own grid).
        val group = frames.indices.filter { frames[it].inFocusGroup }.takeIf { it.size >= 2 }.orEmpty()
        val bracket = frames.indices.filter { it != 0 && !frames[it].inFocusGroup }
        var focusWeights: List<FloatArray> = emptyList()
        var fgw = 0
        var fgh = 0
        val baseGuide: IntArray
        if (group.isNotEmpty()) {
            val lumas = ArrayList<FloatArray>(group.size)
            for (i in group) {
                val (l, w8, h8) = FocusMerge.lumaGuide(aligned[i], gw, gh, scale = 2)
                lumas += l
                fgw = w8
                fgh = h8
            }
            focusWeights = FocusMerge.weights(lumas, fgw, fgh)
            baseGuide = ExposureFusion.mixGuides(group.map { aligned[it] }, focusWeights.map { ExposureFusion.resample(it, fgw, fgh, gw, gh) }, gw, gh)
        } else {
            baseGuide = aligned[0]
        }
        val exposureGuides = listOf(baseGuide) + bracket.map { aligned[it] }
        val weights = ExposureFusion.weights(exposureGuides, gw, gh)
        val correction = ExposureFusion.correction(exposureGuides, weights, ExposureFusion.pyramidBlend(exposureGuides, weights, gw, gh), gw, gh)

        val decoders = frames.map { FocusStacker.openRegionDecoder(it.jpeg.bytes) }
        try {
            val width = decoders[0].width
            val height = decoders[0].height
            require(decoders.all { it.width == width && it.height == height }) { "Frames differ in size" }
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val outRows = IntArray(width * STRIP_ROWS)
            val baseRows = if (group.isNotEmpty()) IntArray(width * STRIP_ROWS) else IntArray(0)
            var y0 = 0
            while (y0 < height) {
                val rows = minOf(STRIP_ROWS, height - y0)
                val strips = ArrayList<ExposureFusion.Strip>(exposureGuides.size)
                if (group.isNotEmpty()) {
                    val focusStrips = group.map { FocusStacker.decodeStrip(decoders[it], width, height, y0, rows, fullShifts[it]) }
                    FocusMerge.blendStrip(focusStrips, width, height, y0, rows, focusWeights, fgw, fgh, baseRows)
                    strips += ExposureFusion.Strip(baseRows, y0, rows, PixelShift.NONE)
                } else {
                    strips += FocusStacker.decodeStrip(decoders[0], width, height, y0, rows, PixelShift.NONE).toExposureStrip()
                }
                for (i in bracket) strips += FocusStacker.decodeStrip(decoders[i], width, height, y0, rows, fullShifts[i]).toExposureStrip()
                ExposureFusion.blendStrip(strips, width, height, y0, rows, weights, correction, gw, gh, outRows)
                out.setPixels(outRows, 0, width, 0, y0, width, rows)
                y0 += rows
            }
            return out
        } finally {
            decoders.forEach { it.recycle() }
        }
    }

    private fun FocusMerge.Strip.toExposureStrip() = ExposureFusion.Strip(pixels, top, rows, shift)

    companion object {
        private const val TAG = "ExposureBracket"

        /** EV distance of the dark and bright frames from the base. */
        const val BRACKET_STOPS = 2f

        /** Wait for auto-exposure to converge after a compensation change (about 6 preview frames). */
        const val EV_SETTLE_MS = 250L

        const val STRIP_ROWS = 256

        /** Same bound as the focus stack: a bigger shift than this between exposures is not drift. */
        const val MAX_SHIFT_PX = FocusStacker.MAX_SHIFT_PX + ExposureFusion.ALIGN_RADIUS * ExposureFusion.GUIDE_SCALE

        const val NOTE_COLLAPSED = "Only one exposure was captured, so there was nothing to blend."
        const val NOTE_THERMAL = "The phone is running hot, so only one frame was taken."
        const val NOTE_PARTIAL = "Some exposures were missed, so the blend used fewer frames."
        const val NOTE_MERGE_FAILED = "The exposure blend failed, so this is the normal photo."
        const val NOTE_LOW_MEMORY = "There was not enough memory to blend the exposures, so this is the normal photo."

        /**
         * Compensation indices of the bracket for a lens whose range is [lower]..[upper] in steps of [stepEv] EV and a
         * base of [base]: the base, then the dark frame, then the bright one, each clamped into the range (so a
         * narrow range uses its ends) and dropped when it lands on an index already used.
         */
        internal fun bracketIndices(base: Int, stepEv: Float, lower: Int, upper: Int): List<Int> {
            val offset = (BRACKET_STOPS / stepEv).roundToInt().coerceAtLeast(1)
            return listOf(base, (base - offset).coerceIn(lower, upper), (base + offset).coerceIn(lower, upper)).distinct()
        }

        /** The frames to take at [tier] (see the class doc), given what the lens offers. */
        internal fun plan(tier: PerformanceTier, base: Int, stepEv: Float, lower: Int, upper: Int, canFocusStack: Boolean): Plan {
            val indices = bracketIndices(base, stepEv, lower, upper)
            return when (tier) {
                PerformanceTier.FULL -> Plan(indices, canFocusStack && indices.size > 1)
                PerformanceTier.REDUCED -> Plan(indices.take(2), focusStack = false)
                PerformanceTier.MINIMAL -> Plan(indices.take(1), focusStack = false)
            }
        }
    }
}
