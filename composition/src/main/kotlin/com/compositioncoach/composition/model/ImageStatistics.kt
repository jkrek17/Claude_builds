package com.compositioncoach.composition.model

/**
 * Cheap, detector-free statistics computed from a heavily downscaled frame.
 * All grids are row-major, [gridHeight] rows of [gridWidth] cells, values 0..1.
 *
 * @param luminance mean luminance per cell.
 * @param edgeDensity gradient magnitude per cell (how "busy" that area is).
 * @param horizontalSymmetry 0..1, how similar the left half is to the mirrored right half.
 * @param verticalSymmetry 0..1, how similar the top half is to the mirrored bottom half.
 * @param dominantLines strong straight lines, if a line detector ran (may be empty).
 * @param estimatedHorizonAngleDegrees visual horizon tilt if a horizon was found (positive = clockwise), else null.
 */
data class ImageStatistics(
    val gridWidth: Int,
    val gridHeight: Int,
    val luminance: FloatArray,
    val edgeDensity: FloatArray,
    val horizontalSymmetry: Float,
    val verticalSymmetry: Float,
    val meanLuminance: Float,
    val contrast: Float,
    val dominantLines: List<DetectedLine> = emptyList(),
    val estimatedHorizonAngleDegrees: Float? = null,
) {
    init {
        require(luminance.size == gridWidth * gridHeight) { "luminance grid size mismatch" }
        require(edgeDensity.size == gridWidth * gridHeight) { "edgeDensity grid size mismatch" }
    }

    fun luminanceAt(col: Int, row: Int): Float = luminance[row * gridWidth + col]
    fun edgeDensityAt(col: Int, row: Int): Float = edgeDensity[row * gridWidth + col]

    /** Cell containing a normalized point. */
    fun cellOf(p: NormalizedPoint): Pair<Int, Int> =
        (p.x * gridWidth).toInt().coerceIn(0, gridWidth - 1) to (p.y * gridHeight).toInt().coerceIn(0, gridHeight - 1)

    /** Mean of [grid] over the cells overlapped by [rect]. Returns 0 if rect covers no cell. */
    fun regionMean(rect: NormalizedRect, grid: FloatArray): Float {
        val r = rect.clampToFrame()
        if (r.isEmpty) return 0f
        val c0 = (r.left * gridWidth).toInt().coerceIn(0, gridWidth - 1)
        val c1 = ((r.right * gridWidth).toInt() - 1).coerceIn(c0, gridWidth - 1)
        val r0 = (r.top * gridHeight).toInt().coerceIn(0, gridHeight - 1)
        val r1 = ((r.bottom * gridHeight).toInt() - 1).coerceIn(r0, gridHeight - 1)
        var sum = 0f
        var n = 0
        for (row in r0..r1) for (col in c0..c1) {
            sum += grid[row * gridWidth + col]; n++
        }
        return if (n == 0) 0f else sum / n
    }

    fun regionLuminance(rect: NormalizedRect) = regionMean(rect, luminance)
    fun regionEdgeDensity(rect: NormalizedRect) = regionMean(rect, edgeDensity)

    /**
     * Centre of "visual weight": edge density weighted centroid (busy/high-contrast areas attract the eye).
     * Returns the frame centre for a featureless image.
     */
    fun visualWeightCentroid(): NormalizedPoint {
        var sx = 0f; var sy = 0f; var sw = 0f
        for (row in 0 until gridHeight) for (col in 0 until gridWidth) {
            val w = edgeDensity[row * gridWidth + col]
            sx += w * (col + 0.5f) / gridWidth
            sy += w * (row + 0.5f) / gridHeight
            sw += w
        }
        return if (sw <= 1e-6f) NormalizedPoint.CENTER else NormalizedPoint(sx / sw, sy / sw)
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        /** A featureless statistics object for tests and fallbacks. */
        fun flat(gridWidth: Int = 16, gridHeight: Int = 16, luminance: Float = 0.5f) = ImageStatistics(
            gridWidth, gridHeight,
            FloatArray(gridWidth * gridHeight) { luminance },
            FloatArray(gridWidth * gridHeight),
            horizontalSymmetry = 0.5f, verticalSymmetry = 0.5f,
            meanLuminance = luminance, contrast = 0f,
        )
    }
}
