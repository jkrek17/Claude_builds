package com.compositioncoach.composition.model

/**
 * A coarse foreground/subject probability mask from a segmentation model, downsampled to a small grid in
 * the upright, front-camera-mirrored frame (row-major, [gridHeight] rows of [gridWidth] cells, values 0..1
 * = probability the cell belongs to the subject/person).
 *
 * Used for subject separation (how distinct the subject is from what surrounds it), background clutter
 * behind the subject (edge energy in cells that are *not* subject), and as a body-extent fallback when the
 * pose detector has nothing.
 */
data class SubjectMask(
    val gridWidth: Int,
    val gridHeight: Int,
    val probability: FloatArray,
) {
    init {
        require(probability.size == gridWidth * gridHeight) { "mask grid size mismatch" }
    }

    fun at(col: Int, row: Int): Float = probability[row * gridWidth + col]

    fun cellOf(p: NormalizedPoint): Pair<Int, Int> =
        (p.x * gridWidth).toInt().coerceIn(0, gridWidth - 1) to (p.y * gridHeight).toInt().coerceIn(0, gridHeight - 1)

    /** Fraction of the whole frame covered by subject cells above [threshold]. */
    fun coverage(threshold: Float = 0.5f): Float {
        var n = 0
        for (v in probability) if (v >= threshold) n++
        return n.toFloat() / probability.size
    }

    /** Mean subject probability over the cells overlapped by [rect]. */
    fun regionMean(rect: NormalizedRect): Float {
        val r = rect.clampToFrame()
        if (r.isEmpty) return 0f
        val c0 = (r.left * gridWidth).toInt().coerceIn(0, gridWidth - 1)
        val c1 = ((r.right * gridWidth).toInt() - 1).coerceIn(c0, gridWidth - 1)
        val r0 = (r.top * gridHeight).toInt().coerceIn(0, gridHeight - 1)
        val r1 = ((r.bottom * gridHeight).toInt() - 1).coerceIn(r0, gridHeight - 1)
        var sum = 0f
        var n = 0
        for (row in r0..r1) for (col in c0..c1) { sum += probability[row * gridWidth + col]; n++ }
        return if (n == 0) 0f else sum / n
    }

    /** Bounding box of all cells above [threshold], or null when the mask is empty. */
    fun bounds(threshold: Float = 0.5f): NormalizedRect? {
        var minC = Int.MAX_VALUE; var minR = Int.MAX_VALUE; var maxC = -1; var maxR = -1
        for (row in 0 until gridHeight) for (col in 0 until gridWidth) {
            if (probability[row * gridWidth + col] >= threshold) {
                if (col < minC) minC = col; if (col > maxC) maxC = col
                if (row < minR) minR = row; if (row > maxR) maxR = row
            }
        }
        if (maxC < 0) return null
        return NormalizedRect(
            minC.toFloat() / gridWidth, minR.toFloat() / gridHeight,
            (maxC + 1).toFloat() / gridWidth, (maxR + 1).toFloat() / gridHeight,
        )
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
