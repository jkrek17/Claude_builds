package com.compositioncoach.vision

import kotlinx.coroutines.flow.StateFlow

/**
 * Motion stacking on the live analysis stream, implemented by [VisionPipeline]. CameraX allows one
 * `ImageAnalysis` use case, so stacking rides on it: while active, every frame the pipeline accepts is also
 * converted to RGB at the analysis resolution (raise it with `CameraController.setAnalysisResolutionHint`
 * before binding for a Motion outcome) and fed to a [FrameAccumulator]. The ML detectors are skipped while
 * stacking (the last faces/objects/mask are re-reported) so more frames reach the stack.
 *
 * `:app` reaches it with `(frameSource as? FrameStackingSource)`.
 */
interface FrameStackingSource {
    /** frames stacked / maxFrames (0..1) while stacking, null otherwise. For the hold's progress ring. */
    val stackingProgress: StateFlow<Float?>

    /**
     * Starts a stack, discarding any unfinished one. Returns false when the pipeline is not running.
     *
     * @param maxFrames frames expected over the hold; progress reaches 1 there and frames beyond it are
     *   ignored (MEDIAN additionally decimates to fit its ring, see [FrameAccumulator]).
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
