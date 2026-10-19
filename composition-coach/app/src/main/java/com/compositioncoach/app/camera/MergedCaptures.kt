package com.compositioncoach.app.camera

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.compositioncoach.app.camera.CapturePipeline.ExifFacts
import com.compositioncoach.app.camera.CapturePipeline.ShotRequest
import com.compositioncoach.app.camera.CapturePipeline.ShotResult
import com.compositioncoach.app.camera.merge.BurstMergeCapture
import com.compositioncoach.app.camera.merge.BurstMergeMode
import com.compositioncoach.app.camera.merge.ExposureBracketCapture
import com.compositioncoach.app.camera.merge.GroupCompositeCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.compositioncoach.app.outcome.CapturePipeline as OutcomePipeline

/**
 * The Wave 3 multi-frame outcome pipelines of [CapturePipeline.capture] (outcome overload): the burst merge
 * (Anything, A person, Selfie, Night without the vendor extension), the exposure bracket (A view) and the group
 * face composite (A group), each turned into a [ShotResult] with diagnostics (`pipeline`, `framesStacked` and the
 * capture class's English note as `degradedNote`). Every method returns null when the capture class could not
 * run here, so the caller falls back to a normal capture. When a merge did not land, the result is the companion
 * photo alone; when it did, the merged photo is the result and the companion rides along for Compare.
 */
internal class MergedCaptures(
    repository: CaptureRepository,
    motionSensor: MotionSensor,
    private val diagnostics: (ShotRequest, CaptureResultTap.StillResult?, ExifFacts?, Long) -> CaptureDiagnostics,
) {
    private val burstMerge = BurstMergeCapture(repository, motionSensor)
    private val exposureBracket = ExposureBracketCapture(repository, motionSensor)
    private val groupComposite by lazy { GroupCompositeCapture(repository, motionSensor) }

    /** BURST_MERGE (five frames) or NIGHT_STACK (eight), per [mode]. */
    suspend fun burstMerge(request: ShotRequest, lens: LensControl?, mode: BurstMergeMode): ShotResult? {
        val controller = request.controller ?: return null
        val result = burstMerge.capture(
            controller, request.imageCapture, lens, mirror(request), request.tier, mode,
            biasExposure = mode == BurstMergeMode.BURST_MERGE,
        ) ?: return null
        val pipeline = if (mode == BurstMergeMode.NIGHT_STACK) OutcomePipeline.NIGHT_STACK else OutcomePipeline.BURST_MERGE
        Log.i(TAG, "${pipeline.name}: capture ${result.captureMs} ms, total ${result.totalMs} ms, frames=${result.framesUsed}, merged=${result.mergedUri != null}")
        return shot(request, pipeline, result.mergedUri, result.firstUri, result.first, result.framesUsed, result.note)
    }

    /** EXPOSURE_BRACKET; with the focus stack folded in when [withFocusStack] and the lens can. */
    suspend fun exposureBracket(request: ShotRequest, lens: LensControl?, withFocusStack: Boolean): ShotResult? {
        val controller = request.controller ?: return null
        val result = exposureBracket.capture(controller, request.imageCapture, lens, mirror(request), request.tier, withFocusStack)
            ?: return null
        Log.i(TAG, "EXPOSURE_BRACKET: capture ${result.captureMs} ms, total ${result.totalMs} ms, frames=${result.framesUsed}, focusStacked=${result.focusStacked}")
        // The fusion ran exactly when a merged photo exists; a focus merge alone is never saved, so FOCUS_STACK is
        // not a separate tag here (the capture mode names it).
        return shot(
            request, OutcomePipeline.EXPOSURE_BRACKET, result.mergedUri, result.firstUri, result.first, result.framesUsed, result.note,
            modeSuffix = if (result.focusStacked && result.mergedUri != null) "+FOCUS_STACK" else null,
        )
    }

    /** GROUP_COMPOSITE. */
    suspend fun groupComposite(request: ShotRequest, lens: LensControl?): ShotResult? {
        val controller = request.controller ?: return null
        val result = groupComposite.capture(controller, request.imageCapture, lens, mirror(request), request.tier) ?: return null
        Log.i(TAG, "GROUP_COMPOSITE: capture ${result.captureMs} ms, total ${result.totalMs} ms, frames=${result.framesUsed}, merged=${result.mergedUri != null}")
        return shot(request, OutcomePipeline.GROUP_COMPOSITE, result.mergedUri, result.firstUri, result.first, result.framesUsed, result.note)
    }

    private fun mirror(request: ShotRequest) = request.lensFacing == LensFacing.FRONT

    private suspend fun shot(
        request: ShotRequest,
        pipeline: OutcomePipeline,
        mergedUri: Uri?,
        firstUri: Uri,
        first: CapturedJpeg,
        framesUsed: Int,
        note: String?,
        modeSuffix: String? = null,
    ): ShotResult {
        val savedAt = SystemClock.elapsedRealtime()
        val tapped = request.controller?.captureResultTap?.forTimestamp(first.sensorTimestampNs)
        val exif = withContext(Dispatchers.Default) { runCatching { ExifFacts.from(first.bytes) }.getOrNull() }
        val base = diagnostics(request, tapped, exif, savedAt)
        val diagnostics = base.copy(
            widthPx = first.width,
            heightPx = first.height,
            captureMode = modeSuffix?.let { "${base.captureMode ?: "MAXIMIZE_QUALITY"}$it" } ?: base.captureMode,
            pipeline = (if (mergedUri != null) pipeline else OutcomePipeline.SINGLE).name,
            framesStacked = framesUsed.takeIf { mergedUri != null },
            degradedNote = note,
        )
        return if (mergedUri != null) ShotResult(mergedUri, diagnostics, companionUri = firstUri) else ShotResult(firstUri, diagnostics)
    }

    private companion object {
        const val TAG = "MergedCaptures"
    }
}
