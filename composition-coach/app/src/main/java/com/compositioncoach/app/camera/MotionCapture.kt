package com.compositioncoach.app.camera

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageCapture
import com.compositioncoach.app.camera.merge.StaticRestorer
import com.compositioncoach.vision.AccumulatedImage
import com.compositioncoach.vision.AngularDelta
import com.compositioncoach.vision.FrameAligner
import com.compositioncoach.vision.FrameStackingSource
import com.compositioncoach.vision.GyroAlignment
import com.compositioncoach.vision.StackBlend
import com.compositioncoach.vision.merge.Convergence
import com.compositioncoach.vision.merge.HoldResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

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
 * Orchestrates a Motion outcome (Silky water, Light trails, Clear the crowd): an adaptive hold during which the
 * live analysis stream is stacked ([FrameStackingSource]), a normal still taken at the start (so the experiment
 * never costs the shot), and both saved.
 *
 * ## Timeline
 * 1. The still is taken through CameraX's own file path (the first frame, the companion). Light trails wait for
 *    it, because its exposure is what the trails' manual exposure is derived from and the bias below would
 *    otherwise darken it.
 * 2. Exposure for the hold, after a [MotionExposure.SETTLE_MS] settle: auto-exposure is locked
 *    ([LensControl.lockAutoExposure]) so every frame shares one brightness. Light trails bias exposure one stop
 *    under first so lights do not clip, and where the lens takes manual sensor control use the longest shutter
 *    the stream's frame interval allows (continuous trails instead of dotted ones). All of it is restored in
 *    `finally`.
 * 3. Start stacking with gyro alignment ([ChainedAligner]: [MotionSensor.angularDeltaBetween] from the hold's
 *    start, re-based every few seconds so the 8 s gyro history covers holds of up to 15 s).
 * 4. Hold ([HoldLoop]): `holdSeconds` is the *minimum*. After it the hold goes on until the stack converges
 *    ([Convergence]) or the blend's cap is reached (Silky water 6 s, Light trails 10 s, Clear the crowd 15 s).
 *    It never ends because a frame count assumed at some frame rate filled. Every [TICK_MS] the progress ring is
 *    updated to `max(time / minimum, convergence)`, and time spent MOVING above [MAX_MOVING_FRACTION] of the
 *    minimum hold ends it early as [MotionStatus.TOO_SHAKY].
 * 5. Stop stacking, restore the still parts of the picture from the full-resolution still
 *    ([StaticRestorer]: sharp rocks, smooth water), rotate upright (lossless 90-degree steps) and save with
 *    `CaptureRepository.exportProcessed` (`_silk`, `_trails`, `_clear`).
 *
 * The stack's resolution is the analysis stream's (up to 1920x1440 when the state layer set
 * `CameraController.setAnalysisResolutionHint(MotionCapture.analysisResolution())` before binding, else 640x480);
 * the saved picture is the companion's resolution when the static restore ran.
 */
class MotionCapture(private val repository: CaptureRepository, private val motionSensor: MotionSensor) {

    private val _progress = MutableStateFlow<Float?>(null)

    /** Hold progress 0..1 while a Motion hold runs, null otherwise. Drives the UI's progress ring. */
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    private val restorer = StaticRestorer(repository)

    /**
     * @param converged the hold ended because the stack had converged (false: the cap, the minimum, or a failure).
     * @param firstSecondFps stacked frames per second over the hold's first second, null when it ended sooner.
     * @param staticRestored the still parts of the picture were restored from the full-resolution still.
     * @param exposureSummary what the hold did to exposure, for the diagnostics log ("AE locked", "manual 1/36 s ISO 80"...).
     * @param note one plain English sentence when something optional failed (the integrator maps it), else null.
     */
    class Result(
        val stackUri: Uri?,
        val stillUri: Uri,
        val status: MotionStatus,
        val framesStacked: Int,
        val holdMs: Long,
        val stackWidth: Int?,
        val stackHeight: Int?,
        val converged: Boolean = false,
        val firstSecondFps: Float? = null,
        val staticRestored: Boolean = false,
        val exposureSummary: String? = null,
        val note: String? = null,
    )

    /**
     * Runs the hold; throws only when even the still capture fails (or on cancellation).
     *
     * [lens] and [controller] are optional: without them the hold runs on whatever exposure the camera has
     * (no lock, no Light trails bias or manual shutter).
     */
    suspend fun run(
        source: FrameStackingSource,
        blend: StackBlend,
        holdSeconds: Int,
        imageCapture: ImageCapture,
        isFrontCamera: Boolean,
        focalToLongEdge: Float?,
        lens: LensControl? = null,
        controller: CameraController? = null,
    ): Result = coroutineScope {
        val minimumMs = holdSeconds.coerceAtLeast(1) * 1_000L
        val capMs = maxOf(Convergence.capMs(blend), minimumMs)
        val shutterAt = SystemClock.elapsedRealtime()
        val exposure = HoldExposure(lens, controller)
        val still: Deferred<Uri> = async { repository.capture(imageCapture, isFrontCamera) }
        var status = MotionStatus.STACKED
        var image: AccumulatedImage? = null
        var outcome: HoldLoop.Outcome? = null
        var begin = SystemClock.elapsedRealtime()
        _progress.value = 0f
        try {
            if (blend == StackBlend.LIGHTEN) {
                // The trails' exposure comes from the normal photo's, and the bias must not touch that photo.
                still.await()
                exposure.prepareTrails(controller?.captureResultTap?.latestSince(shutterAt))
            } else {
                delay(MotionExposure.SETTLE_MS)
                exposure.lockAfterSettle()
            }
            val startNs = motionSensor.nowNs()
            val aligner = ChainedAligner(startNs, motionSensor::nowNs, motionSensor::angularDeltaBetween)
            val maxFrames = (capMs / 1_000L * MAX_STREAM_FPS).toInt()
            val started = source.startAccumulating(blend, maxFrames, aligner, focalToLongEdge ?: GyroAlignment.DEFAULT_FOCAL_TO_LONG_EDGE)
            begin = SystemClock.elapsedRealtime()
            if (started) {
                val loop = HoldLoop(blend, minimumMs, capMs, SystemClock::elapsedRealtime)
                outcome = loop.run(source, { motionSensor.steadiness.value }) { _progress.value = it }
                if (outcome.end == HoldLoop.End.TOO_SHAKY) status = MotionStatus.TOO_SHAKY
            }
        } finally {
            image = withContext(NonCancellable) {
                val stacked = source.stopAccumulating()
                exposure.restore()
                stacked
            }
            _progress.value = null
        }
        val heldMs = SystemClock.elapsedRealtime() - begin
        val stillUri = still.await()
        if (status == MotionStatus.STACKED && (image == null || image!!.frames < MIN_FRAMES)) status = MotionStatus.NO_FRAMES
        val stacked = image?.takeIf { status == MotionStatus.STACKED }
        val saved = stacked?.let { saveStack(it, blend, stillUri) }
        val fps = outcome?.firstSecondFps
        if (stacked != null && HoldResolution.advisesFallback(fps, stacked.width.toLong() * stacked.height, FALLBACK_PIXELS)) {
            Log.i(TAG, "Only $fps fps at ${stacked.width}x${stacked.height}: the next Motion bind uses ${CameraController.MOTION_ANALYSIS_FALLBACK_RESOLUTION}")
            fallbackAdvised = true
        }
        Result(
            stackUri = saved?.uri,
            stillUri = stillUri,
            status = status,
            framesStacked = image?.frames ?: 0,
            holdMs = heldMs,
            stackWidth = saved?.width,
            stackHeight = saved?.height,
            converged = outcome?.end == HoldLoop.End.CONVERGED,
            firstSecondFps = fps,
            staticRestored = saved?.restored == true,
            exposureSummary = exposure.summary,
            note = saved?.note,
        )
    }

    private class SavedStack(val uri: Uri, val width: Int, val height: Int, val restored: Boolean, val note: String?)

    private suspend fun saveStack(image: AccumulatedImage, blend: StackBlend, stillUri: Uri): SavedStack? = try {
        var note: String? = null
        val restored = try {
            restorer.restore(image, stillUri)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Restoring the static detail failed; the plain stack is saved", t)
            note = DETAIL_RESTORE_FAILED_NOTE
            null
        }
        val bitmap = restored ?: withContext(Dispatchers.Default) {
            val raw = Bitmap.createBitmap(image.rgb, image.width, image.height, Bitmap.Config.ARGB_8888)
            PhotoCodec.rotateClockwise(raw, image.uprightRotationDegrees)
        }
        val width = bitmap.width
        val height = bitmap.height
        SavedStack(repository.exportProcessed(bitmap, suffixFor(blend)), width, height, restored != null, note)
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Log.w(TAG, "Saving the Motion stack failed; the first frame is kept", t)
        null
    }

    companion object {
        private const val TAG = "MotionCapture"
        const val TICK_MS = 50L
        const val MIN_FRAMES = 3

        /** More than this fraction of the (minimum) hold spent MOVING ends it as [MotionStatus.TOO_SHAKY]. */
        const val MAX_MOVING_FRACTION = 0.25f

        /** Safety ceiling on stacked frames per second of cap: far above the analysis stream's rate, so it never ends a hold. */
        const val MAX_STREAM_FPS = 60

        const val DETAIL_RESTORE_FAILED_NOTE = "The sharp-detail pass couldn't run; this is the stack as shot"

        private val FALLBACK_PIXELS = CameraController.MOTION_ANALYSIS_FALLBACK_RESOLUTION.let { it.width.toLong() * it.height }

        @Volatile private var fallbackAdvised = false

        /**
         * The analysis size the next Motion bind should ask for ([CameraController.setAnalysisResolutionHint]):
         * [CameraController.MOTION_ANALYSIS_RESOLUTION] until a hold measured fewer than
         * [HoldResolution.MIN_SUSTAINED_FPS] stacked frames per second over its first second at that size, then
         * [CameraController.MOTION_ANALYSIS_FALLBACK_RESOLUTION] for the rest of the process.
         */
        fun analysisResolution(): Size =
            if (fallbackAdvised) CameraController.MOTION_ANALYSIS_FALLBACK_RESOLUTION else CameraController.MOTION_ANALYSIS_RESOLUTION

        /** Forgets a fallback verdict (tests). */
        internal fun resetResolutionFallback() {
            fallbackAdvised = false
        }

        fun suffixFor(blend: StackBlend): String = when (blend) {
            StackBlend.AVERAGE -> "_silk"
            StackBlend.LIGHTEN -> "_trails"
            StackBlend.MEDIAN -> "_clear"
        }
    }
}

/**
 * The hold's timing loop, free of Android so it runs under a virtual clock in tests: polls [source] every
 * [MotionCapture.TICK_MS], publishes `max(time / minimum, convergence)` as progress, and ends with
 * [Convergence.shouldStop] (never before [minimumMs], on convergence from then on, at the latest at [capMs]) or
 * as [End.TOO_SHAKY]. Nothing in it depends on the stream's frame rate.
 */
internal class HoldLoop(
    private val mode: StackBlend,
    private val minimumMs: Long,
    private val capMs: Long,
    private val clock: () -> Long,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    enum class End { CONVERGED, CAP, TOO_SHAKY }

    /** How the loop ended, after [elapsedMs], and the frame rate seen over the first second (null if it ended sooner). */
    class Outcome(val end: End, val elapsedMs: Long, val firstSecondFps: Float?)

    suspend fun run(source: FrameStackingSource, steadiness: () -> Steadiness, onProgress: (Float) -> Unit): Outcome {
        val begin = clock()
        var movingMs = 0L
        var last = begin
        var fps: Float? = null
        while (true) {
            pause(MotionCapture.TICK_MS)
            val now = clock()
            val elapsed = now - begin
            if (steadiness() == Steadiness.MOVING) movingMs += now - last
            last = now
            val status = source.stackingStatus.value
            val convergence = status?.convergence ?: source.stackingProgress.value ?: 0f
            onProgress(Convergence.holdProgress(elapsed, minimumMs, convergence))
            if (fps == null && elapsed >= HoldResolution.OBSERVE_MS) fps = HoldResolution.fps(status?.framesStacked ?: 0, elapsed)
            if (movingMs > minimumMs * MotionCapture.MAX_MOVING_FRACTION) return Outcome(End.TOO_SHAKY, elapsed, fps)
            val converged = status?.converged == true
            if (Convergence.shouldStop(elapsed, minimumMs, capMs, converged)) {
                return Outcome(if (converged && elapsed >= minimumMs) End.CONVERGED else End.CAP, elapsed, fps)
            }
        }
    }

    override fun toString(): String = "HoldLoop($mode, min=${minimumMs}ms, cap=${capMs}ms)"
}

/**
 * Rotation of the phone since the hold's start for each frame, from the gyro history. The history is 8 s long
 * and a hold may last 15 s, so the aggregate is re-based: once a frame is more than [REBASE_AFTER_NS] past the
 * base, the rotation up to that frame is added to the base and the base moves forward (small angles add).
 * Frame timestamps are used when they share the gyro's clock, else the frame's processing time.
 */
internal class ChainedAligner(
    startNs: Long,
    private val now: () -> Long,
    private val between: (fromNs: Long, toNs: Long) -> AngularDelta?,
) : FrameAligner {
    private var baseNs = startNs
    private var base = AngularDelta.ZERO

    override fun angularDeltaSinceStart(frameTimestampNs: Long): AngularDelta? {
        val current = now()
        val target = if (abs(current - frameTimestampNs) < SAME_CLOCK_TOLERANCE_NS) frameTimestampNs else current
        if (target - baseNs > REBASE_AFTER_NS) {
            val step = between(baseNs, target) ?: return null
            base = AngularDelta(base.x + step.x, base.y + step.y, base.z + step.z)
            baseNs = target
        }
        val d = between(baseNs, target) ?: return null
        return AngularDelta(base.x + d.x, base.y + d.y, base.z + d.z)
    }

    companion object {
        private const val SAME_CLOCK_TOLERANCE_NS = 1_000_000_000L
        const val REBASE_AFTER_NS = 3_000_000_000L
    }
}

/** Pure planning of the hold's exposure. */
internal object MotionExposure {
    /** Wait before locking (or after applying manual exposure) so the first locked frame is a settled one. */
    const val SETTLE_MS = 300L

    /** Light trails shoot this many stops under auto-exposure so the lights do not clip. */
    const val TRAILS_BIAS_STOPS = 1f

    /** The stream's frame interval minus this is the longest per-frame shutter, so frames do not stretch. */
    const val FRAME_INTERVAL_MARGIN_NS = 5_000_000L

    /** The analysis stream's nominal frame interval (30 fps) when the real one is unknown. */
    const val NOMINAL_FRAME_INTERVAL_NS = 33_333_333L

    /** A fixed sensor exposure: [exposureNs] shutter at [iso]. */
    class Plan(val exposureNs: Long, val iso: Int)

    /** The exposure-compensation index [stops] below [current] (steps of [stepEv] EV), clamped to [range]; [current] if the step is unknown. */
    fun biasIndex(current: Int, stepEv: Float, range: IntRange, stops: Float = TRAILS_BIAS_STOPS): Int {
        if (stepEv <= 0f) return current
        return (current - (stops / stepEv).roundToInt()).coerceIn(range.first, range.last)
    }

    /**
     * Manual exposure for Light trails from the auto-exposure the normal photo used ([autoExposureNs] at
     * [autoIso]): the same total light less [biasStops], delivered with the longest shutter the frame interval
     * allows ([frameIntervalNs] minus a margin, within the sensor's [exposureRangeNs]), at the ISO that makes
     * up the rest. In bright scenes the shutter stops at what the lowest ISO needs, so nothing over-exposes.
     */
    fun trailsPlan(
        autoExposureNs: Long,
        autoIso: Int,
        biasStops: Float,
        frameIntervalNs: Long,
        exposureRangeNs: LongRange,
        isoRange: IntRange,
    ): Plan? {
        if (autoExposureNs <= 0L || autoIso <= 0 || frameIntervalNs <= 0L) return null
        val gain = autoExposureNs.toDouble() * autoIso / 2.0.pow(biasStops.toDouble())
        val longest = (frameIntervalNs - FRAME_INTERVAL_MARGIN_NS).coerceIn(exposureRangeNs.first, exposureRangeNs.last)
        val needed = (gain / isoRange.first).toLong().coerceIn(exposureRangeNs.first, exposureRangeNs.last)
        val shutter = minOf(longest, needed)
        val iso = (gain / shutter).roundToInt().coerceIn(isoRange.first, isoRange.last)
        return Plan(shutter, iso)
    }
}

/**
 * Applies and restores the hold's exposure: the auto-exposure lock, the Light trails bias and manual
 * exposure. Every camera call is guarded (a failure leaves the camera on auto and the hold still runs), and
 * [restore] undoes whatever was applied; it is safe to call when nothing was.
 */
private class HoldExposure(private val lens: LensControl?, private val controller: CameraController?) {
    private var biasedFrom: Int? = null
    private var locked = false
    private var manual = false

    /** What was done, for the diagnostics log; null when nothing was. */
    var summary: String? = null
        private set

    /** Silky water and Clear the crowd: freeze auto-exposure at its settled result. */
    suspend fun lockAfterSettle() {
        val l = lens ?: return
        locked = l.supportsAeLock
        if (safely { l.lockAutoExposure() } != null && locked) summary = "AE locked"
    }

    /**
     * Light trails: manual exposure with the longest shutter the stream allows when the lens supports it and the
     * normal photo's exposure is known; otherwise one stop of bias, a settle and the auto-exposure lock.
     */
    suspend fun prepareTrails(still: CaptureResultTap.StillResult?) {
        val l = lens ?: return
        val plan = manualPlan(l, still)
        if (plan != null) {
            val applied = safely { l.setManualExposure(plan.exposureNs, plan.iso) }
            if (applied != null) {
                manual = true
                summary = "manual ${applied.exposureNs / 1_000_000.0} ms ISO ${applied.iso}"
                delay(MotionExposure.SETTLE_MS)
                return
            }
        }
        biasedFrom = applyBias()
        delay(MotionExposure.SETTLE_MS)
        lockAfterSettle()
        summary = listOfNotNull(biasedFrom?.let { "-${MotionExposure.TRAILS_BIAS_STOPS.toInt()} EV" }, summary).joinToString(", ").ifEmpty { null }
    }

    private fun manualPlan(l: LensControl, still: CaptureResultTap.StillResult?): MotionExposure.Plan? {
        val exposureNs = still?.exposureTimeNs ?: return null
        val iso = still.iso ?: return null
        if (!l.supportsManualExposure) return null
        val exposureRange = l.exposureTimeRangeNs ?: return null
        val isoRange = l.isoRange ?: return null
        return MotionExposure.trailsPlan(
            exposureNs, iso, MotionExposure.TRAILS_BIAS_STOPS, MotionExposure.NOMINAL_FRAME_INTERVAL_NS, exposureRange, isoRange,
        )
    }

    /** Lowers exposure compensation by one stop; returns the index to restore, or null when it could not be applied. */
    private fun applyBias(): Int? = runCatching {
        val camera = controller?.camera ?: return null
        val state = camera.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return null
        val current = state.exposureCompensationIndex
        val range = state.exposureCompensationRange
        val target = MotionExposure.biasIndex(current, state.exposureCompensationStep.toFloat(), range.lower..range.upper)
        if (target == current) return null
        controller.setExposureCompensation(target)
        current
    }.getOrNull()

    suspend fun restore() {
        val l = lens
        if (l != null) {
            if (manual) safely { l.restoreAutoExposure() }
            if (locked) safely { l.unlockAutoExposure() }
        }
        biasedFrom?.let { index -> runCatching { controller?.setExposureCompensation(index) } }
        manual = false
        locked = false
        biasedFrom = null
    }

    /** Runs a camera call, returning a non-null marker on success; failures are logged, cancellation propagates. */
    private suspend fun <T : Any> safely(block: suspend () -> T?): T? = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Log.w("MotionCapture", "Exposure control failed; the hold continues on auto exposure", t)
        null
    }
}
