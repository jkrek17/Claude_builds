package com.compositioncoach.vision

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What a running Motion stack reports, for the hold loop.
 *
 * @param framesStacked frames the stack has been offered so far (the real, measured count).
 * @param convergence 0..1 estimate of how close the stack is to converging (see `merge.Convergence`).
 * @param converged true once more frames would no longer change the result.
 */
data class StackingStatus(val framesStacked: Int, val convergence: Float, val converged: Boolean)

/**
 * Motion stacking on the live analysis stream, implemented by [VisionPipeline]. CameraX allows one
 * `ImageAnalysis` use case, so stacking rides on it: while active, every frame the pipeline accepts is also
 * converted to RGB at the analysis resolution (raise it with `CameraController.setAnalysisResolutionHint`
 * before binding for a Motion outcome) and fed to a [FrameAccumulator]. The ML detectors are skipped while
 * stacking (the last faces/objects/mask are re-reported) so more frames reach the stack.
 *
 * The stack has no frame budget of its own: how long it runs is the caller's decision, made from
 * [stackingStatus] (convergence) and the clock, never from a frame count assumed at some frame rate.
 *
 * `:app` reaches it with `(frameSource as? FrameStackingSource)`.
 */
interface FrameStackingSource {
    /** Convergence estimate 0..1 of the running stack (1 = converged), null when not stacking. For the hold's progress ring. */
    val stackingProgress: StateFlow<Float?>

    /** Frames stacked, convergence estimate and verdict of the running stack; null when not stacking. */
    val stackingStatus: StateFlow<StackingStatus?> get() = MutableStateFlow(null)

    /**
     * Starts a stack, discarding any unfinished one. Returns false when the pipeline is not running.
     *
     * @param maxFrames a safety ceiling on frames stacked, far above what any hold can reach (frames beyond it
     *   are ignored). It is *not* an estimate of the hold: MEDIAN decimates adaptively to fit its memory
     *   budget, see [FrameAccumulator].
     * @param aligner gyro rotation since the start for a frame; null = no alignment.
     * @param focalToLongEdge focal length / long image edge for the bound lens and zoom
     *   ([GyroAlignment.DEFAULT_FOCAL_TO_LONG_EDGE] when unknown).
     * @param maxShiftFraction alignment shifts are clamped to this fraction of the long edge; a bigger
     *   rotation than that is shake the hold should have aborted on.
     */
    fun startAccumulating(
        mode: StackBlend,
        maxFrames: Int,
        aligner: FrameAligner? = null,
        focalToLongEdge: Float = GyroAlignment.DEFAULT_FOCAL_TO_LONG_EDGE,
        maxShiftFraction: Float = DEFAULT_MAX_SHIFT_FRACTION,
    ): Boolean

    /**
     * Ends the stack and returns the result (MEDIAN's per-pixel selection runs here, off the pipeline thread),
     * or null when no frame was stacked. Safe to call when not stacking.
     */
    suspend fun stopAccumulating(): AccumulatedImage?

    companion object {
        const val DEFAULT_MAX_SHIFT_FRACTION = 0.04f
    }
}
