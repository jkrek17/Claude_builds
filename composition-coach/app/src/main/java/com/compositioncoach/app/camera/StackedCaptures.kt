package com.compositioncoach.app.camera

import android.os.SystemClock
import android.util.Log
import com.compositioncoach.app.camera.CapturePipeline.ExifFacts
import com.compositioncoach.app.camera.CapturePipeline.ShotRequest
import com.compositioncoach.app.camera.CapturePipeline.ShotResult
import com.compositioncoach.vision.FrameStackingSource
import com.compositioncoach.vision.StackBlend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.compositioncoach.app.outcome.CapturePipeline as OutcomePipeline

/**
 * The two multi-frame outcome pipelines of [CapturePipeline.capture] (outcome overload): the focus stack and
 * the Motion hold, each turned into a [ShotResult] with diagnostics (`pipeline`, `framesStacked`, and a
 * `degradedNote` when only the first frame survived, or [DETAIL_RESTORE_FAILED] when the stack was saved without
 * its full-resolution detail). Split out of [CapturePipeline] to keep both readable.
 */
internal class StackedCaptures(
    private val repository: CaptureRepository,
    private val motionCapture: MotionCapture,
    private val focusStacker: FocusStacker,
    private val diagnostics: (ShotRequest, CaptureResultTap.StillResult?, ExifFacts?, Long) -> CaptureDiagnostics,
) {

    suspend fun focusStack(request: ShotRequest, lens: LensControl?): ShotResult? {
        val controller = request.controller ?: return null
        if (lens == null) return null
        val mirror = request.lensFacing == LensFacing.FRONT
        val stacked = focusStacker.capture(controller, request.imageCapture, lens, mirror) ?: return null
        val savedAt = SystemClock.elapsedRealtime()
        val tapped = controller.captureResultTap?.forTimestamp(stacked.first.sensorTimestampNs)
        val exif = withContext(Dispatchers.Default) { ExifFacts.from(stacked.first.bytes) }
        val merged = stacked.mergedUri
        val diagnostics = diagnostics(request, tapped, exif, savedAt).copy(
            widthPx = stacked.first.width,
            heightPx = stacked.first.height,
            pipeline = if (merged != null) OutcomePipeline.FOCUS_STACK.name else OutcomePipeline.SINGLE.name,
            framesStacked = if (merged != null) FOCUS_STACK_FRAMES else null,
            degradedNote = if (merged == null) FOCUS_MERGE_FAILED else null,
        )
        Log.i(TAG, "Focus stack: capture ${stacked.captureMs} ms, total ${stacked.totalMs} ms, merged=${merged != null}")
        return if (merged != null) ShotResult(merged, diagnostics, companionUri = stacked.firstUri) else ShotResult(stacked.firstUri, diagnostics)
    }

    suspend fun motion(
        request: ShotRequest,
        pipeline: OutcomePipeline,
        holdSeconds: Int,
        stacking: FrameStackingSource,
        lens: LensControl?,
    ): ShotResult {
        val blend = when (pipeline) {
            OutcomePipeline.MOTION_LIGHTEN -> StackBlend.LIGHTEN
            OutcomePipeline.MOTION_MEDIAN -> StackBlend.MEDIAN
            else -> StackBlend.AVERAGE
        }
        val motion = motionCapture.run(
            source = stacking,
            blend = blend,
            holdSeconds = holdSeconds,
            imageCapture = request.imageCapture,
            isFrontCamera = request.lensFacing == LensFacing.FRONT,
            focalToLongEdge = lens?.focalToLongEdge(),
            lens = lens,
            controller = request.controller,
        )
        val savedAt = SystemClock.elapsedRealtime()
        val tapped = request.controller?.captureResultTap?.latestSince(request.shutterAtElapsedMs)
        val exif = runCatching { ExifFacts.from(repository.readExifHeader(motion.stillUri)) }.getOrNull()
        val stack = motion.stackUri
        val diagnostics = diagnostics(request, tapped, exif, savedAt).copy(
            widthPx = if (stack != null) motion.stackWidth else exif?.width,
            heightPx = if (stack != null) motion.stackHeight else exif?.height,
            pipeline = if (stack != null) pipeline.name else OutcomePipeline.SINGLE.name,
            framesStacked = motion.framesStacked.takeIf { stack != null },
            degradedNote = when (motion.status) {
                MotionStatus.TOO_SHAKY -> TOO_SHAKY_NOTE
                MotionStatus.NO_FRAMES -> NO_FRAMES_NOTE
                MotionStatus.STACKED -> if (stack == null) STACK_SAVE_FAILED else motion.note
            },
        )
        Log.i(
            TAG,
            "Motion: held ${motion.holdMs} ms, ${motion.framesStacked} frames, converged=${motion.converged}, " +
                "firstSecondFps=${motion.firstSecondFps}, exposure=${motion.exposureSummary}, staticRestored=${motion.staticRestored}",
        )
        return if (stack != null) {
            ShotResult(stack, diagnostics, companionUri = motion.stillUri, motionStatus = motion.status)
        } else {
            ShotResult(motion.stillUri, diagnostics, motionStatus = motion.status)
        }
    }

    companion object {
        private const val TAG = "StackedCaptures"
        private const val FOCUS_STACK_FRAMES = 3
        const val TOO_SHAKY_NOTE = "Too shaky, try again"
        const val NO_FRAMES_NOTE = "The stack didn't get enough frames; here is the normal photo"
        /** The stack was saved but the full-resolution detail pass failed (see [MotionCapture.DETAIL_RESTORE_FAILED_NOTE]). */
        const val DETAIL_RESTORE_FAILED = MotionCapture.DETAIL_RESTORE_FAILED_NOTE
        const val STACK_SAVE_FAILED = "The stack couldn't be saved; here is the normal photo"
        const val FOCUS_MERGE_FAILED = "The focus stack couldn't be merged; here is the normal photo"
    }
}
