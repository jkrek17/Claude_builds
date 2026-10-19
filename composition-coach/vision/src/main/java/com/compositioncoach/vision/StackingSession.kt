package com.compositioncoach.vision

/**
 * One Motion stack in progress inside [VisionPipeline]: converts each offered frame to display-portrait RGB,
 * aligns it with the gyro and feeds the [FrameAccumulator]. Only touched from the pipeline thread (and from
 * [result] after it has been detached). Pure apart from the [FrameAligner] callback.
 *
 * Frames are accumulated in the *display-portrait* orientation (`imageInfo.rotationDegrees`, no physical-hold
 * correction), because that is the orientation in which device gyro axes map straight onto image axes (see
 * [GyroAlignment]). The physical-hold correction measured at the start is carried in
 * [AccumulatedImage.uprightRotationDegrees] and applied when saving.
 */
internal class StackingSession(
    private val mode: StackBlend,
    val maxFrames: Int,
    private val aligner: FrameAligner?,
    private val focalToLongEdge: Float,
    private val maxShiftFraction: Float,
    private val deviceRotationAtStart: Int,
) {
    private var accumulator: FrameAccumulator? = null
    private var uprightExtraDegrees = 0
    private var argbScratch: IntArray? = null

    val framesStacked: Int get() = accumulator?.framesOffered ?: 0

    /** Frames stacked as a fraction of the safety ceiling [maxFrames] (not the hold's progress, see [convergence]). */
    val progress: Float get() = (framesStacked.toFloat() / maxFrames.coerceAtLeast(1)).coerceIn(0f, 1f)

    /** 0..1: how close the stack is to converging (1 = converged); 0 before the first frame. */
    val convergence: Float get() = accumulator?.convergence ?: 0f

    /** True once more frames would no longer change the stack. */
    val converged: Boolean get() = accumulator?.converged ?: false

    /**
     * Adds one frame. [displayMapper] must be built with the frame's display rotation (no hold correction).
     * Frames of a different size than the first (a rebind mid-stack) are ignored.
     */
    fun offer(
        y: YuvConverter.Plane,
        u: YuvConverter.Plane,
        v: YuvConverter.Plane,
        displayMapper: FrameCoordinateMapper,
        isFrontCamera: Boolean,
        timestampNs: Long,
    ) {
        if (framesStacked >= maxFrames) return
        val width = displayMapper.outputWidth
        val height = displayMapper.outputHeight
        val acc = accumulator ?: run {
            val longEdge = maxOf(width, height)
            uprightExtraDegrees = extraRotation(displayMapper.rotationDegrees, deviceRotationAtStart, isFrontCamera)
            FrameAccumulator(mode, width, height, maxFrames, maxShiftPx = (longEdge * maxShiftFraction).toInt(), medianBudgetBytes = medianBudget())
                .also { accumulator = it }
        }
        if (acc.width != width || acc.height != height) return
        // AVERAGE/LIGHTEN consume the pixels immediately, so one scratch array is reused; MEDIAN keeps a copy
        // per frame (made by the accumulator's alignment step), so the scratch is still safe to reuse.
        val pixels = YuvConverter.toUprightArgb(y, u, v, displayMapper, argbScratch).also { argbScratch = it }
        val shift = aligner?.angularDeltaSinceStart(timestampNs)
            ?.let { GyroAlignment.contentShift(it, maxOf(width, height), focalToLongEdge, isFrontCamera) }
            ?: PixelShift.NONE
        acc.add(pixels, shift.dx, shift.dy)
    }

    /** The stacked image, or null when no frame was stacked. MEDIAN's selection is the slow part. */
    fun result(): AccumulatedImage? = accumulator?.result(uprightExtraDegrees)

    companion object {
        /** MEDIAN keeps frames: a quarter of the heap, between 64 and 96 MB (13 to 20 frames at 1280x960). */
        private fun medianBudget(): Long = (Runtime.getRuntime().maxMemory() / 4).coerceIn(
            FrameAccumulator.DEFAULT_MEDIAN_BUDGET_BYTES,
            FrameAccumulator.DEFAULT_MEDIAN_BUDGET_BYTES * 3 / 2,
        )

        /**
         * Clockwise rotation still needed after display-portrait to stand the image physically upright: the
         * difference between [UprightRotation]'s full rotation and the display rotation. The front camera's
         * image is mirrored *after* rotating, and a mirror reverses the sense of a later rotation, so its
         * extra turn is the negation.
         */
        fun extraRotation(displayRotation: Int, deviceRotation: Int, isFrontCamera: Boolean): Int {
            val full = UprightRotation.computeUprightRotationDegrees(displayRotation, deviceRotation, isFrontCamera)
            val diff = full - displayRotation
            val signed = if (isFrontCamera) -diff else diff
            return ((signed % 360) + 360) % 360
        }
    }
}
