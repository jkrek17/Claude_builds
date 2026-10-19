package com.compositioncoach.app.camera.merge

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageCapture
import com.compositioncoach.app.camera.CameraController
import com.compositioncoach.app.camera.CapturePipeline
import com.compositioncoach.app.camera.CaptureRepository
import com.compositioncoach.app.camera.CapturedJpeg
import com.compositioncoach.app.camera.ExifOrientations
import com.compositioncoach.app.camera.LensControl
import com.compositioncoach.app.camera.MotionSensor
import com.compositioncoach.app.camera.captureBurst
import com.compositioncoach.app.camera.setExposureCompensation
import com.compositioncoach.vision.GyroAlignment
import com.compositioncoach.vision.PerformanceTier
import com.compositioncoach.vision.PixelShift
import com.compositioncoach.vision.merge.ToneLift
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The burst merge for Anything, Food & things, A person, Selfie ([BurstMergeMode.BURST_MERGE], five frames) and
 * Night when the vendor extension is absent ([BurstMergeMode.NIGHT_STACK], eight frames): full-resolution
 * JPEGs captured back to back with auto-exposure locked, aligned, and averaged with moving pixels rejected, so
 * noise falls roughly with the square root of the frame count while moving subjects stay sharp.
 *
 * ## Capture
 * - **Burst merge**: exposure compensation is lowered a third of a stop when the lens's step allows (highlights
 *   survive), auto-exposure settles and is locked, the burst runs, and the merge lifts the third of a stop back
 *   ([ToneLift]). Compensation and the lock are restored in `finally`.
 * - **Night stack**: when the lens has manual sensor control and a gyro is running, one normal auto-exposed
 *   frame is taken first (the companion, and the source of the ISO and shutter auto-exposure picks), then the
 *   burst runs at the longest shutter the steadiness allows (STEADY up to 1/8 s, SLIGHT_MOTION 1/15 s) at the
 *   ISO divided by the shutter gain ([BurstMergePlanner.nightExposure]). Otherwise auto-exposure is locked and
 *   the first burst frame is the companion. The merge ends with a gentle shadow lift.
 * - Thermal tier scales the frame count ([BurstMergePlanner.frameCount]); at MINIMAL only the normal photo is saved.
 *
 * ## After the burst
 * The companion is saved first, untouched, so an experiment never costs the shot. The merge then runs on
 * [Dispatchers.Default] through [StripMerger] (peak memory: the output bitmap plus one strip per frame, plus the
 * guide-scale planes) and is saved as a second file through [CaptureRepository.exportProcessedOriented] with the
 * companion's EXIF. A failed merge or an `OutOfMemoryError` keeps the companion and sets [Result.note].
 *
 * Alignment: gyro rotation between sensor timestamps (as in `FocusStacker`) as the starting point, refined by
 * block matching at 1/4 scale against the sharpest frame (see [StripMerger] and `BurstAlign`).
 */
class BurstMergeCapture(private val repository: CaptureRepository, private val motionSensor: MotionSensor) {

    /**
     * @param mergedUri the merged photo, or null when only the companion survived.
     * @param firstUri the companion, saved first.
     * @param first the companion's capture, for EXIF-based diagnostics.
     * @param framesUsed frames that went into the merge (1 when there is none).
     * @param captureMs from the call until the burst was in memory.
     * @param totalMs from the call until the merged photo was saved.
     * @param note one plain English sentence when the result is degraded; one of the `NOTE_*` constants.
     * @param framesCaptured frames the burst delivered (merge candidates, the companion excluded in the Night manual path).
     * @param referenceIndex index (in capture order) of the sharpest frame the others were aligned to, or -1.
     * @param manualExposure the shutter and ISO the Night stack forced, or null when auto-exposure ran.
     * @param liftedStops the exposure lift the merge applied to undo the bias, in stops.
     */
    class Result(
        val mergedUri: Uri?,
        val firstUri: Uri,
        val first: CapturedJpeg,
        val framesUsed: Int,
        val captureMs: Long,
        val totalMs: Long,
        val note: String?,
        val framesCaptured: Int = 1,
        val referenceIndex: Int = -1,
        val manualExposure: LensControl.ManualExposure? = null,
        val liftedStops: Float = 0f,
    )

    /**
     * Runs the burst and the merge. Returns null when the burst could not be captured at all (the caller falls
     * back to a normal capture); throws only on cancellation.
     *
     * @param lens the bound lens's controls, or null (then the burst runs without an exposure lock).
     * @param mirror true for the front camera (gyro signs, EXIF mirroring).
     * @param tier the thermal tier: frames are scaled down, MINIMAL saves the normal photo only.
     * @param mode which merge to run.
     * @param biasExposure whether the everyday burst is shot a third of a stop under and lifted back in the merge
     *   (the companion is then a third of a stop darker than a normal photo).
     */
    suspend fun capture(
        controller: CameraController,
        imageCapture: ImageCapture,
        lens: LensControl?,
        mirror: Boolean,
        tier: PerformanceTier,
        mode: BurstMergeMode = BurstMergeMode.BURST_MERGE,
        biasExposure: Boolean = true,
    ): Result? {
        val start = SystemClock.elapsedRealtime()
        val count = BurstMergePlanner.frameCount(mode, tier)
        val restore = ExposureRestore(controller, lens)
        var companion: CapturedJpeg? = null
        var frames: List<CapturedJpeg> = emptyList()
        var manual: LensControl.ManualExposure? = null
        var liftedStops = 0f
        var burstFailed = false
        try {
            if (count > 1) {
                if (mode == BurstMergeMode.NIGHT_STACK) {
                    captureNightProbe(controller, imageCapture, lens)?.let {
                        companion = it.frame
                        manual = it.manual
                    }
                } else if (biasExposure) {
                    liftedStops = lowerExposure(controller, restore)
                }
                if (manual == null) lens?.let { restore.lockAe(it) }
            }
            frames = runCatching { controller.captureBurst(imageCapture, count) }
                .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Burst capture failed", it) }
                .getOrNull().orEmpty()
            burstFailed = frames.isEmpty()
        } finally {
            restore.run()
        }
        val captureMs = SystemClock.elapsedRealtime() - start
        val first = companion ?: frames.firstOrNull()
        if (first == null) return null
        val firstUri = repository.saveJpeg(first.bytes, first.rotationDegrees, mirror)
        // The frames to merge: the whole burst, or in the Night manual path the burst after the probe.
        val toMerge = frames
        fun done(note: String?, merged: Uri? = null, used: Int = 1, reference: Int = -1) = Result(
            mergedUri = merged,
            firstUri = firstUri,
            first = first,
            framesUsed = used,
            captureMs = captureMs,
            totalMs = SystemClock.elapsedRealtime() - start,
            note = note,
            framesCaptured = toMerge.size.coerceAtLeast(1),
            referenceIndex = reference,
            manualExposure = manual,
            liftedStops = liftedStops,
        )
        if (count <= 1) return done(NOTE_THERMAL)
        if (burstFailed) return done(NOTE_BURST_FAILED)
        if (toMerge.size < 2) return done(NOTE_ONE_FRAME)

        var framesUsed = 1
        var reference = -1
        val merged = try {
            withContext(Dispatchers.Default) {
                val target = controller.captureTargetRotationDegrees
                val bufferToPortrait = ((toMerge[0].rotationDegrees + if (mirror) -target else target) % 360 + 360) % 360
                val focal = lens?.focalToLongEdge() ?: GyroAlignment.DEFAULT_FOCAL_TO_LONG_EDGE
                val merger = StripMerger()
                val prepared = merger.prepare(toMerge) { from, to ->
                    gyroShift(toMerge[from], toMerge[to], focal, mirror, bufferToPortrait)
                }
                framesUsed = prepared.framesUsed
                reference = prepared.sourceIndices[prepared.referenceIndex]
                if (prepared.framesUsed < 2) return@withContext null
                val lut = ToneLift.lut(
                    exposureStops = liftedStops,
                    shadowLift = if (mode == BurstMergeMode.NIGHT_STACK) ToneLift.NIGHT_SHADOW_LIFT else 0f,
                )
                val bitmap = merger.merge(prepared, lut)
                try {
                    repository.exportProcessedOriented(bitmap, mode.suffix, ExifOrientations.tagFor(first.rotationDegrees, mirror), first.bytes)
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "Out of memory during the ${mode.name} merge; keeping the first frame", oom)
            return done(NOTE_OUT_OF_MEMORY, reference = reference)
        } catch (t: Throwable) {
            Log.w(TAG, "The ${mode.name} merge failed; keeping the first frame", t)
            return done(NOTE_MERGE_FAILED, reference = reference)
        }
        if (merged == null) return done(NOTE_ONE_FRAME, used = framesUsed, reference = reference)
        val note = if (framesUsed < MIN_COMFORTABLE_FRAMES) noteFewFrames(framesUsed) else null
        return done(note, merged, framesUsed, reference)
    }

    /** The Night stack's opening frame: auto-exposure's own photo, and the manual exposure to burst at (null: stay on auto). */
    private class NightProbe(val frame: CapturedJpeg, val manual: LensControl.ManualExposure?)

    /**
     * The Night stack's manual path: one auto-exposed frame (the companion) to read auto-exposure's ISO and
     * shutter from, then the longest steady shutter at the matching ISO. Returns null (no extra frame is taken)
     * when the lens, the gyro or the steadiness rule that out; the caller then locks auto-exposure instead.
     */
    private suspend fun captureNightProbe(controller: CameraController, imageCapture: ImageCapture, lens: LensControl?): NightProbe? {
        if (lens == null || !lens.supportsManualExposure || !motionSensor.isAvailable) return null
        val steadiness = motionSensor.steadiness.value
        if (BurstMergePlanner.nightShutterNs(steadiness) == null) return null
        val probe = runCatching { controller.captureBurst(imageCapture, 1).first() }
            .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Night probe frame failed", it) }
            .getOrNull() ?: return null
        val tapped = controller.captureResultTap?.forTimestamp(probe.sensorTimestampNs)
        val exif = runCatching { CapturePipeline.ExifFacts.from(probe.bytes) }.getOrNull()
        val autoNs = tapped?.exposureTimeNs ?: exif?.exposureTimeNs
        val autoIso = tapped?.iso ?: exif?.iso
        if (autoNs == null || autoIso == null) return NightProbe(probe, null)
        val plan = BurstMergePlanner.nightExposure(autoNs, autoIso, steadiness, lens.exposureTimeRangeNs, lens.isoRange)
            ?: return NightProbe(probe, null)
        val applied = runCatching { lens.setManualExposure(plan.exposureNs, plan.iso) }
            .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Manual exposure failed", it) }
            .getOrNull()
        if (applied != null) delay(MANUAL_EXPOSURE_SETTLE_MS)
        return NightProbe(probe, applied)
    }

    /** Lowers exposure compensation for the bias and lets auto-exposure settle; returns the stops to lift back. */
    private suspend fun lowerExposure(controller: CameraController, restore: ExposureRestore): Float {
        val state = controller.camera?.cameraInfo?.exposureState ?: return 0f
        if (!state.isExposureCompensationSupported) return 0f
        val step = runCatching { state.exposureCompensationStep.toFloat() }.getOrDefault(0f)
        val current = state.exposureCompensationIndex
        val range = state.exposureCompensationRange
        val target = (current + BurstMergePlanner.biasIndexDelta(step)).coerceIn(range.lower, range.upper)
        if (target >= current) return 0f
        restore.compensation(current)
        controller.setExposureCompensation(target)
        delay(AE_SETTLE_MS)
        return BurstMergePlanner.liftStops(target - current, step)
    }

    /** The shift of frame [to] relative to frame [from], in the stored buffer's pixels (the gyro half of alignment). */
    private fun gyroShift(from: CapturedJpeg, to: CapturedJpeg, focalToLongEdge: Float, mirror: Boolean, bufferToPortrait: Int): PixelShift {
        val delta = motionSensor.angularDeltaBetween(from.sensorTimestampNs, to.sensorTimestampNs) ?: return PixelShift.NONE
        val display = GyroAlignment.contentShift(delta, maxOf(from.width, from.height), focalToLongEdge, isFrontCamera = mirror)
        return GyroAlignment.clamp(GyroAlignment.toBuffer(display, bufferToPortrait), MAX_GYRO_SHIFT_PX)
    }

    /** Undoes everything the capture changed on the camera, whatever happens (also on cancellation). */
    private class ExposureRestore(private val controller: CameraController, private val lens: LensControl?) {
        private var compensationIndex: Int? = null

        fun compensation(original: Int) {
            compensationIndex = original
        }

        suspend fun lockAe(lens: LensControl) {
            runCatching { lens.lockAutoExposure() }
                .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Could not lock auto-exposure", it) }
        }

        suspend fun run() = withContext(NonCancellable) {
            lens?.let {
                // Both are no-ops when nothing was set, and neither throws.
                it.restoreAutoExposure()
                it.unlockAutoExposure()
            }
            compensationIndex?.let { runCatching { controller.setExposureCompensation(it) } }
        }
    }

    companion object {
        private const val TAG = "BurstMergeCapture"

        /** Let auto-exposure converge after the compensation change, before it is locked. */
        const val AE_SETTLE_MS = 300L

        /** Let the sensor pick up a manual shutter (the preview pipeline runs a frame or two behind). */
        const val MANUAL_EXPOSURE_SETTLE_MS = 400L

        /** A bigger gyro shift than this between frames is not hand drift; clamp rather than trust it. */
        const val MAX_GYRO_SHIFT_PX = 120

        /** Fewer merged frames than this earns a note (the spec's "fewer than 3"). */
        const val MIN_COMFORTABLE_FRAMES = 3

        // Degraded notes (English; the integrator maps each to a string resource).
        const val NOTE_THERMAL = "The phone is warm, so this photo was saved without merging frames."
        const val NOTE_BURST_FAILED = "The extra frames could not be captured, so this is the single photo."
        const val NOTE_ONE_FRAME = "The frames did not line up, so this is the single photo."
        const val NOTE_MERGE_FAILED = "The merge failed, so this is the single photo."
        const val NOTE_OUT_OF_MEMORY = "There was not enough memory to merge, so this is the single photo."

        fun noteFewFrames(used: Int) = "Only $used frames lined up, so the merge is light."
    }
}
