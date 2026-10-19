package com.compositioncoach.vision.merge

import com.compositioncoach.vision.StackBlend
import kotlin.math.abs

/**
 * The stopping rules of an adaptive Motion hold, as pure functions. The outcome spec's `holdSeconds` is the
 * *minimum*; the hold then goes on until the effect has converged or a per-blend cap is reached:
 *
 * - **Silky water** ([StackBlend.AVERAGE]): converged when the running average changed by less than
 *   [AVERAGE_TOLERANCE] (1 %) mean absolute luma between the stack at N/2 and at N frames. Cap [SILKY_CAP_MS].
 * - **Light trails** ([StackBlend.LIGHTEN]): converged when a new frame raises fewer than
 *   [LIGHTEN_RAISED_FRACTION] (0.2 %) of the pixels, or when more than [LIGHTEN_CLIPPED_FRACTION] (2 %) of
 *   the pixels are clipped (more light would only blow the picture out). Cap [TRAILS_CAP_MS].
 * - **Clear the crowd** ([StackBlend.MEDIAN]): converged when the median changed in fewer than
 *   [MEDIAN_CHANGED_FRACTION] (0.5 %) of the pixels between N/2 and N frames. Cap [CROWD_CAP_MS].
 *
 * Nothing here knows the frame rate. A frame count computed at an assumed rate is what ended holds early on
 * 30 fps devices; N is whatever the stream actually delivered, and the hold ends on [shouldStop].
 */
object Convergence {
    /** Silky water: mean absolute change of the average luma (0..1) below which the stack has settled. */
    const val AVERAGE_TOLERANCE = 0.01f

    /** Light trails: fraction of pixels a frame may still raise before the trails are considered complete. */
    const val LIGHTEN_RAISED_FRACTION = 0.002f

    /** Light trails: more than this fraction of clipped pixels ends the hold (the picture is already bright). */
    const val LIGHTEN_CLIPPED_FRACTION = 0.02f

    /** Clear the crowd: fraction of pixels whose median may still change between N/2 and N. */
    const val MEDIAN_CHANGED_FRACTION = 0.005f

    /** A pixel counts as raised by a new frame when its brightest channel went up by at least this (0..255), above sensor noise. */
    const val RAISE_THRESHOLD_LEVELS = 16

    /** A pixel counts as clipped when its luma is at least this (0..255). */
    const val CLIP_LUMA = 250

    /** A median pixel counts as changed when its luma moved by more than this (0..255), above sensor noise. */
    const val MEDIAN_CHANGE_LEVELS = 12

    /** Frames averaged over when judging "a new frame raises fewer than 0.2 % of pixels" (one quiet frame is not a verdict). */
    const val LIGHTEN_WINDOW_FRAMES = 5

    /** No convergence verdict before this many frames: the N/2 vs N comparison needs something to compare. */
    const val MIN_FRAMES_FOR_VERDICT = 8

    const val SILKY_CAP_MS = 6_000L
    const val TRAILS_CAP_MS = 10_000L
    const val CROWD_CAP_MS = 15_000L

    /** The longest the hold may run for [mode], whatever the convergence says. */
    fun capMs(mode: StackBlend): Long = when (mode) {
        StackBlend.AVERAGE -> SILKY_CAP_MS
        StackBlend.LIGHTEN -> TRAILS_CAP_MS
        StackBlend.MEDIAN -> CROWD_CAP_MS
    }

    /**
     * The frame counts (offered to the stack) at which an N/2 vs N comparison is made: 8, 12, 16, 24, 32, 48...
     * Every entry's half is also an entry, so each check compares against a snapshot taken two entries earlier.
     */
    fun checkpointFrames(index: Int): Int {
        require(index >= 0) { "negative checkpoint index" }
        val octave = index / 2
        val base = if (index % 2 == 0) MIN_FRAMES_FOR_VERDICT else MIN_FRAMES_FOR_VERDICT * 3 / 2
        return base shl octave
    }

    /** Mean absolute difference of two equally sized luma samples (each 0..1); 0 for empty input. */
    fun meanAbsChange(before: FloatArray, after: FloatArray): Float {
        require(before.size == after.size) { "sample size mismatch" }
        if (before.isEmpty()) return 0f
        var sum = 0.0
        for (i in before.indices) sum += abs(after[i] - before[i])
        return (sum / before.size).toFloat()
    }

    /** Fraction of samples whose luma (0..1) moved by more than [toleranceLevels]/255; 0 for empty input. */
    fun changedFraction(before: FloatArray, after: FloatArray, toleranceLevels: Int = MEDIAN_CHANGE_LEVELS): Float {
        require(before.size == after.size) { "sample size mismatch" }
        if (before.isEmpty()) return 0f
        val tolerance = toleranceLevels / 255f
        var changed = 0
        for (i in before.indices) if (abs(after[i] - before[i]) > tolerance) changed++
        return changed.toFloat() / before.size
    }

    fun isAverageConverged(change: Float): Boolean = change < AVERAGE_TOLERANCE

    fun isLightenConverged(raisedFraction: Float, clippedFraction: Float): Boolean =
        clippedFraction > LIGHTEN_CLIPPED_FRACTION || raisedFraction < LIGHTEN_RAISED_FRACTION

    fun isMedianConverged(changedFraction: Float): Boolean = changedFraction < MEDIAN_CHANGED_FRACTION

    /** How close [measure] is to its [threshold] (smaller is better), as 0..1: 1 at or below the threshold. */
    fun closeness(measure: Float, threshold: Float): Float =
        if (measure <= threshold) 1f else (threshold / measure).coerceIn(0f, 1f)

    /**
     * Whether the hold ends now: never before [minimumMs], on convergence from then on, and at the latest at
     * the cap ([capMs], but never below the minimum).
     */
    fun shouldStop(elapsedMs: Long, minimumMs: Long, capMs: Long, converged: Boolean): Boolean =
        elapsedMs >= maxOf(capMs, minimumMs) || (elapsedMs >= minimumMs && converged)

    /** The progress ring: `max(time / minimum, convergence estimate)`, 0..1. */
    fun holdProgress(elapsedMs: Long, minimumMs: Long, convergenceEstimate: Float): Float =
        maxOf(elapsedMs.toFloat() / minimumMs.coerceAtLeast(1L), convergenceEstimate).coerceIn(0f, 1f)
}

/**
 * Tracks the convergence of one stack as frames arrive. Fed by [com.compositioncoach.vision.FrameAccumulator]:
 * LIGHTEN reports per-frame statistics ([onLightenFrame]); AVERAGE and MEDIAN hand over a luma sample (0..1)
 * whenever [checkpointDue], and the tracker compares it with the sample from N/2 frames earlier. The verdict is
 * sticky: once converged the stack stays converged (the hold's minimum time still applies elsewhere).
 */
class ConvergenceTracker(private val mode: StackBlend) {
    /** True once the stack has converged by the rules of [Convergence] for [mode]. */
    var converged: Boolean = false
        private set

    /** 0..1: how close the stack is to converging (1 = converged); 0 before the first verdict is possible. */
    var estimate: Float = 0f
        private set

    private val raisedWindow = FloatArray(Convergence.LIGHTEN_WINDOW_FRAMES)
    private var lightenFrames = 0
    private val snapshots = arrayOfNulls<FloatArray>(3)
    private var checkpointIndex = 0

    /** LIGHTEN: one more frame raised [raisedFraction] of the pixels; [clippedFraction] of the stack is clipped. */
    fun onLightenFrame(raisedFraction: Float, clippedFraction: Float) {
        raisedWindow[lightenFrames % raisedWindow.size] = raisedFraction
        lightenFrames++
        if (lightenFrames < Convergence.MIN_FRAMES_FOR_VERDICT) return
        val n = minOf(lightenFrames, raisedWindow.size)
        var sum = 0f
        for (i in 0 until n) sum += raisedWindow[i]
        val raised = sum / n
        val ok = Convergence.isLightenConverged(raised, clippedFraction)
        converged = converged || ok
        estimate = if (converged) 1f else maxOf(estimate, Convergence.closeness(raised, Convergence.LIGHTEN_RAISED_FRACTION))
    }

    /** AVERAGE / MEDIAN: whether [framesOffered] has reached the next checkpoint (always false for LIGHTEN). */
    fun checkpointDue(framesOffered: Int): Boolean =
        mode != StackBlend.LIGHTEN && framesOffered >= Convergence.checkpointFrames(checkpointIndex)

    /**
     * AVERAGE / MEDIAN: records the luma [sample] taken at the current checkpoint and, from the third
     * checkpoint on, compares it with the one from two checkpoints earlier (half the frames).
     */
    fun onCheckpoint(sample: FloatArray) {
        val half = if (checkpointIndex >= 2) snapshots[(checkpointIndex - 2) % snapshots.size] else null
        if (half != null && half.size == sample.size) {
            val ok: Boolean
            val close: Float
            if (mode == StackBlend.AVERAGE) {
                val change = Convergence.meanAbsChange(half, sample)
                ok = Convergence.isAverageConverged(change)
                close = Convergence.closeness(change, Convergence.AVERAGE_TOLERANCE)
            } else {
                val changed = Convergence.changedFraction(half, sample)
                ok = Convergence.isMedianConverged(changed)
                close = Convergence.closeness(changed, Convergence.MEDIAN_CHANGED_FRACTION)
            }
            converged = converged || ok
            estimate = if (converged) 1f else close
        }
        snapshots[checkpointIndex % snapshots.size] = sample
        checkpointIndex++
    }
}

/**
 * The Motion analysis resolution policy: the stream is raised to the largest size the device sustains at
 * [MIN_SUSTAINED_FPS] or more, judged on the first second of a hold.
 */
object HoldResolution {
    /** Below this many stacked frames per second at the raised resolution, fall back to the smaller size. */
    const val MIN_SUSTAINED_FPS = 8f

    /** How long the stream is observed before the verdict. */
    const val OBSERVE_MS = 1_000L

    /** Frames per second over [elapsedMs]; 0 when no time has passed. */
    fun fps(frames: Int, elapsedMs: Long): Float = if (elapsedMs <= 0L) 0f else frames * 1000f / elapsedMs

    /** Whether [frames] in [elapsedMs] (measured from the first frame's hold start) sustains the raised resolution. */
    fun sustains(frames: Int, elapsedMs: Long): Boolean = fps(frames, elapsedMs) >= MIN_SUSTAINED_FPS

    /**
     * Whether the next Motion bind should use the smaller size: the first second ran below
     * [MIN_SUSTAINED_FPS] ([firstSecondFps]; null = not observed, no verdict) at a size larger than the
     * fallback ([stackPixels] > [fallbackPixels]; a stack already at the fallback size has nowhere to go).
     */
    fun advisesFallback(firstSecondFps: Float?, stackPixels: Long, fallbackPixels: Long): Boolean =
        firstSecondFps != null && firstSecondFps < MIN_SUSTAINED_FPS && stackPixels > fallbackPixels
}
