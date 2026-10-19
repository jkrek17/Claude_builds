package com.compositioncoach.app.camera

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.camera.core.ImageCapture
import com.compositioncoach.vision.AccumulatedImage
import com.compositioncoach.vision.FrameAligner
import com.compositioncoach.vision.FrameStackingSource
import com.compositioncoach.vision.GyroAlignment
import com.compositioncoach.vision.StackBlend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** How a Motion hold ended. */
enum class MotionStatus {
    /** The stack was saved (plus the first frame). */
    STACKED,

    /** The phone was MOVING for more than [MotionCapture.MAX_MOVING_FRACTION] of the hold; only the first frame was saved. */
    TOO_SHAKY,

    /** Too few frames reached the stack (analysis stalled); only the first frame was saved. */
    NO_FRAMES,
}

/**
 * Orchestrates a Motion outcome (Silky water, Light trails, Clear the crowd): a hold of `holdSeconds` during
 * which the live analysis stream is stacked ([FrameStackingSource]), a normal still taken at the start (so the
 * experiment never costs the shot), and both saved.
 *
 * ## Timeline
 * 1. Start stacking with gyro alignment ([MotionSensor.angularDeltaSince] from the hold's start; frame
 *    timestamps are used when they share the gyro's clock, else the frame's processing time).
 * 2. In parallel, capture the still through CameraX's own file path (the first frame).
 * 3. Hold: every [TICK_MS] the progress ([progress], 0..1 = the larger of time and frame progress) is updated
 *    and the gyro steadiness sampled; time spent MOVING above [MAX_MOVING_FRACTION] of the hold ends it early as
 *    [MotionStatus.TOO_SHAKY]. The hold also ends once the stack has its expected frames.
 * 4. Stop stacking, rotate the stack upright (lossless 90-degree steps) and save it with
 *    `CaptureRepository.exportProcessed` (`_silk`, `_trails`, `_clear`).
 *
 * The stack's resolution is the analysis stream's (1280x960 when the state layer set
 * `CameraController.setAnalysisResolutionHint(MOTION_ANALYSIS_RESOLUTION)` before binding, else 640x480).
 */
class MotionCapture(private val repository: CaptureRepository, private val motionSensor: MotionSensor) {

    private val _progress = MutableStateFlow<Float?>(null)

    /** Hold progress 0..1 while a Motion hold runs, null otherwise. Drives the UI's progress ring. */
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    class Result(
        val stackUri: Uri?,
        val stillUri: Uri,
        val status: MotionStatus,
        val framesStacked: Int,
        val holdMs: Long,
        val stackWidth: Int?,
        val stackHeight: Int?,
    )

    /** Runs the hold; throws only when even the still capture fails (or on cancellation). */
    suspend fun run(
        source: FrameStackingSource,
        blend: StackBlend,
        holdSeconds: Int,
        imageCapture: ImageCapture,
        isFrontCamera: Boolean,
        focalToLongEdge: Float?,
    ): Result = coroutineScope {
        val holdMs = holdSeconds.coerceAtLeast(1) * 1_000L
        val maxFrames = (holdSeconds.coerceAtLeast(1) * EXPECTED_FPS).coerceAtLeast(MIN_FRAMES)
        val startNs = motionSensor.nowNs()
        val aligner = FrameAligner { frameTs ->
            val sameClock = abs(motionSensor.nowNs() - frameTs) < SAME_CLOCK_TOLERANCE_NS
            if (sameClock) motionSensor.angularDeltaBetween(startNs, frameTs) else motionSensor.angularDeltaSince(startNs)
        }
        _progress.value = 0f
        val started = source.startAccumulating(blend, maxFrames, aligner, focalToLongEdge ?: GyroAlignment.DEFAULT_FOCAL_TO_LONG_EDGE)
        val still = async { repository.capture(imageCapture, isFrontCamera) }
        val begin = SystemClock.elapsedRealtime()
        var status = MotionStatus.STACKED
        var image: AccumulatedImage? = null
        try {
            if (started) {
                var movingMs = 0L
                var last = begin
                while (true) {
                    delay(TICK_MS)
                    val now = SystemClock.elapsedRealtime()
                    if (motionSensor.steadiness.value == Steadiness.MOVING) movingMs += now - last
                    last = now
                    val stackProgress = source.stackingProgress.value ?: 0f
                    _progress.value = maxOf((now - begin).toFloat() / holdMs, stackProgress).coerceAtMost(1f)
                    if (movingMs > holdMs * MAX_MOVING_FRACTION) {
                        status = MotionStatus.TOO_SHAKY
                        break
                    }
                    if (now - begin >= holdMs || stackProgress >= 1f) break
                }
            }
        } finally {
            image = withContext(kotlinx.coroutines.NonCancellable) { source.stopAccumulating() }
            _progress.value = null
        }
        val heldMs = SystemClock.elapsedRealtime() - begin
        val stillUri = still.await()
        if (status == MotionStatus.STACKED && (image == null || image!!.frames < MIN_FRAMES)) status = MotionStatus.NO_FRAMES
        val stacked = image?.takeIf { status == MotionStatus.STACKED }
        val stackUri = stacked?.let { saveStack(it, blend) }
        Result(stackUri, stillUri, status, image?.frames ?: 0, heldMs, stacked?.width, stacked?.height)
    }

    private suspend fun saveStack(image: AccumulatedImage, blend: StackBlend): Uri? = try {
        val bitmap = withContext(Dispatchers.Default) {
            val raw = Bitmap.createBitmap(image.rgb, image.width, image.height, Bitmap.Config.ARGB_8888)
            PhotoCodec.rotateClockwise(raw, image.uprightRotationDegrees)
        }
        repository.exportProcessed(bitmap, suffixFor(blend))
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        android.util.Log.w(TAG, "Saving the Motion stack failed; the first frame is kept", t)
        null
    }

    companion object {
        private const val TAG = "MotionCapture"
        const val TICK_MS = 50L
        /** Frames per second of hold the stack expects (the pipeline's stacking floor allows up to 25). */
        const val EXPECTED_FPS = 12
        const val MIN_FRAMES = 3
        /** More than this fraction of the hold spent MOVING ends it as [MotionStatus.TOO_SHAKY]. */
        const val MAX_MOVING_FRACTION = 0.25f
        private const val SAME_CLOCK_TOLERANCE_NS = 1_000_000_000L

        fun suffixFor(blend: StackBlend): String = when (blend) {
            StackBlend.AVERAGE -> "_silk"
            StackBlend.LIGHTEN -> "_trails"
            StackBlend.MEDIAN -> "_clear"
        }
    }
}
