package com.compositioncoach.vision

/**
 * Downsamples a raw selfie-segmentation confidence mask into the small, upright, front-mirrored grid
 * that [com.compositioncoach.composition.model.SubjectMask] expects. Pure Kotlin (only depends on
 * [FrameCoordinateMapper], itself pure Kotlin) so it is directly unit-testable with a synthetic mask.
 *
 * ## Why this needs the mapper, not a simple resize
 * ML Kit's raw-size selfie-segmentation mask is reported in the *rotated-full* pixel space (same
 * space as `Face.boundingBox`) but at the model's own output resolution ([sourceWidth]/[sourceHeight]),
 * which has no fixed relationship to the sensor buffer's size — a naive resize would ignore rotation,
 * the analysis crop rect, and front-camera mirroring, all three of which [FrameCoordinateMapper]
 * already knows how to undo. Instead, for every cell of the *output* (upright, mirrored) grid, this
 * walks backward: [FrameCoordinateMapper.normalizedPointToRotatedFull] gives the rotated-full pixel
 * position that output cell corresponds to, which is then rescaled from [FrameCoordinateMapper.rotatedFullWidth]/
 * [FrameCoordinateMapper.rotatedFullHeight] into the mask's own [sourceWidth]/[sourceHeight] to get a
 * source-mask pixel index. This is the same backward-sampling shape [ImageStatisticsComputer] uses for
 * the luma plane, just one coordinate space over (rotated-full instead of sensor).
 *
 * A small [SUBSAMPLES]x[SUBSAMPLES] box-average per output cell is used instead of a single
 * nearest-neighbour sample — cheap (16 source lookups per cell at the default 4x4) but noticeably
 * more stable than nearest-neighbour when the output grid (32x32 by default) is coarser than the
 * source mask, which is the common case.
 */
object MaskDownsampler {
    /** Default output grid size for [com.compositioncoach.composition.model.SubjectMask]. */
    const val DEFAULT_GRID_SIZE = 32

    /** Per-cell box-average footprint (SUBSAMPLES x SUBSAMPLES source samples per output cell). */
    private const val SUBSAMPLES = 4

    /**
     * @param source raw mask confidence values, row-major, size `sourceWidth * sourceHeight`, each
     *   0..1 (ML Kit's selfie-segmentation foreground confidence).
     * @param mapper the same [FrameCoordinateMapper] built for this frame's faces/objects — its
     *   rotation/crop/mirror describe how the *sensor* buffer relates to the upright output frame;
     *   the mask's own resolution is independent of it and handled by the [sourceWidth]/[sourceHeight] rescale.
     * @param out reused output buffer; must be exactly `gridWidth * gridHeight` (a mismatched buffer
     *   is replaced, but callers should size it once and keep reusing it to avoid allocation).
     * @return [out] (or a freshly sized replacement), filled in row-major order.
     */
    fun downsample(
        source: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int,
        mapper: FrameCoordinateMapper,
        gridWidth: Int = DEFAULT_GRID_SIZE,
        gridHeight: Int = DEFAULT_GRID_SIZE,
        out: FloatArray? = null,
    ): FloatArray {
        require(source.size == sourceWidth * sourceHeight) { "source mask size mismatch" }
        val result = out?.takeIf { it.size == gridWidth * gridHeight } ?: FloatArray(gridWidth * gridHeight)
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            result.fill(0f)
            return result
        }
        val scaleX = sourceWidth.toFloat() / mapper.rotatedFullWidth.toFloat()
        val scaleY = sourceHeight.toFloat() / mapper.rotatedFullHeight.toFloat()

        for (row in 0 until gridHeight) {
            for (col in 0 until gridWidth) {
                var sum = 0f
                for (sy in 0 until SUBSAMPLES) {
                    val ny = (row + (sy + 0.5f) / SUBSAMPLES) / gridHeight
                    for (sx in 0 until SUBSAMPLES) {
                        val nx = (col + (sx + 0.5f) / SUBSAMPLES) / gridWidth
                        val (rx, ry) = mapper.normalizedPointToRotatedFull(nx, ny)
                        val mx = (rx * scaleX).toInt().coerceIn(0, sourceWidth - 1)
                        val my = (ry * scaleY).toInt().coerceIn(0, sourceHeight - 1)
                        sum += source[my * sourceWidth + mx]
                    }
                }
                result[row * gridWidth + col] = sum / (SUBSAMPLES * SUBSAMPLES)
            }
        }
        return result
    }
}
