package com.compositioncoach.vision.merge

/**
 * Edge-aware refinement of a soft mask with a guided filter (He, Sun and Tang, 2010) on the photo's luma, so
 * a coarse person mask (the segmenter's 32x32 grid, feathered) snaps to the real outline of hair and
 * shoulders instead of ending in a uniform soft ramp.
 *
 * ## Method
 * The filter assumes the output is a local linear function of the guide, `q = a * I + b` inside every
 * `(2r + 1)` window, and picks `a` and `b` per window by least squares against the input mask `p`:
 * `a = cov(I, p) / (var(I) + eps)`, `b = mean(p) - a * mean(I)`. Where the guide has a strong edge inside the
 * window (`var(I)` much larger than [EPS]) the mask follows that edge; on flat luma (`var(I)` near zero) `a`
 * collapses to 0 and the output is the local mean of the mask, i.e. the input stays as soft as it was. The
 * per-window coefficients are averaged over all windows covering a pixel before the final `q`, which keeps
 * the result free of blocky seams. Every mean is an exact box mean (clamped windows are divided by their true
 * pixel count), computed with running sums so the cost does not depend on the radius.
 *
 * ## Scale
 * [RADIUS] is in pixels of the *guide*, which is the photo box-averaged down by [guideFactor] (a long edge of
 * about [GUIDE_LONG_EDGE] px), so the same radius means the same fraction of the frame at 2 MP and 12 MP.
 * Pure `FloatArray` maths, no Android classes.
 */
object MaskRefine {
    /** Window radius in guide pixels (spec: 8 at guide scale). */
    const val RADIUS = 8

    /**
     * Regularisation, in squared luma units (luma is 0..1): contrast below about `sqrt(EPS)` = 7 % of the
     * range does not pull the mask, so skin texture and sensor noise do not carve holes in it.
     */
    const val EPS = 0.005f

    /** Target long edge of the guide in pixels. */
    const val GUIDE_LONG_EDGE = 512

    /** A guide: luma 0..1 on a [width] x [height] grid. */
    class Guide(val luma: FloatArray, val width: Int, val height: Int)

    /** Integer reduction factor so the guide's long edge is at most [GUIDE_LONG_EDGE] (1 for small images). */
    fun guideFactor(width: Int, height: Int): Int {
        val long = maxOf(width, height)
        return ((long + GUIDE_LONG_EDGE - 1) / GUIDE_LONG_EDGE).coerceAtLeast(1)
    }

    /** BT.601 luma (0..1) of [argb] box-averaged by [factor] per side (edge blocks average what they cover). */
    fun lumaGuide(argb: IntArray, width: Int, height: Int, factor: Int = guideFactor(width, height)): Guide {
        require(argb.size == width * height) { "pixel count mismatch" }
        val f = factor.coerceAtLeast(1)
        val gw = (width + f - 1) / f
        val gh = (height + f - 1) / f
        val sums = FloatArray(gw * gh)
        val counts = IntArray(gw * gh)
        for (y in 0 until height) {
            val row = (y / f) * gw
            for (x in 0 until width) {
                val c = argb[y * width + x]
                val l = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
                sums[row + x / f] += l
                counts[row + x / f]++
            }
        }
        return Guide(FloatArray(gw * gh) { sums[it] / counts[it].coerceAtLeast(1) }, gw, gh)
    }

    /** Bilinear resample of a [gridW] x [gridH] grid (cell centres) onto a [width] x [height] grid. */
    fun resampleGrid(grid: FloatArray, gridW: Int, gridH: Int, width: Int, height: Int): FloatArray {
        require(grid.size == gridW * gridH) { "grid size mismatch" }
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            val gy = ((y + 0.5f) / height * gridH - 0.5f).coerceIn(0f, gridH - 1f)
            val y0 = gy.toInt()
            val y1 = (y0 + 1).coerceAtMost(gridH - 1)
            val fy = gy - y0
            for (x in 0 until width) {
                val gx = ((x + 0.5f) / width * gridW - 0.5f).coerceIn(0f, gridW - 1f)
                val x0 = gx.toInt()
                val x1 = (x0 + 1).coerceAtMost(gridW - 1)
                val fx = gx - x0
                val top = grid[y0 * gridW + x0] * (1 - fx) + grid[y0 * gridW + x1] * fx
                val bottom = grid[y1 * gridW + x0] * (1 - fx) + grid[y1 * gridW + x1] * fx
                out[y * width + x] = top * (1 - fy) + bottom * fy
            }
        }
        return out
    }

    /**
     * Refines [mask] (0..1, same size as [guide]) with the guided filter and clamps the result to 0..1.
     * [radius] and [eps] default to the spec's values.
     */
    fun refine(mask: FloatArray, guide: Guide, radius: Int = RADIUS, eps: Float = EPS): FloatArray {
        val q = guidedFilter(guide.luma, mask, guide.width, guide.height, radius, eps)
        for (i in q.indices) q[i] = q[i].coerceIn(0f, 1f)
        return q
    }

    /**
     * [refine] where the guide has structure, the input where it does not: the result is `p + w * (q - p)` with
     * `w = v / (v + eps)` and `v` the guide's local variance (window radius, smoothed once more by the same
     * window). On flat luma `w` is 0 and the mask is returned unchanged (plain [refine] would blur a narrow
     * subject away there, since with no guide structure it degenerates to a box mean); across an edge `w` is
     * close to 1 over the filter's reach and the mask is pulled onto the edge. Clamped to 0..1.
     */
    fun refineEdges(mask: FloatArray, guide: Guide, radius: Int = RADIUS, eps: Float = EPS): FloatArray {
        val n = mask.size
        val guided = guidedFilter(guide.luma, mask, guide.width, guide.height, radius, eps)
        val meanI = boxMean(guide.luma, guide.width, guide.height, radius)
        val corrI = boxMean(FloatArray(n) { guide.luma[it] * guide.luma[it] }, guide.width, guide.height, radius)
        val structure = boxMean(FloatArray(n) { (corrI[it] - meanI[it] * meanI[it]).coerceAtLeast(0f) }, guide.width, guide.height, radius)
        return FloatArray(n) {
            val w = structure[it] / (structure[it] + eps)
            (mask[it] + w * (guided[it] - mask[it])).coerceIn(0f, 1f)
        }
    }

    /** The guided filter itself (see the class doc): [input] filtered with [guide], both `width * height`. */
    fun guidedFilter(guide: FloatArray, input: FloatArray, width: Int, height: Int, radius: Int = RADIUS, eps: Float = EPS): FloatArray {
        val n = width * height
        require(guide.size == n && input.size == n) { "guide and input must be ${width}x$height" }
        val meanI = boxMean(guide, width, height, radius)
        val meanP = boxMean(input, width, height, radius)
        val corrI = boxMean(FloatArray(n) { guide[it] * guide[it] }, width, height, radius)
        val corrIp = boxMean(FloatArray(n) { guide[it] * input[it] }, width, height, radius)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val variance = corrI[i] - meanI[i] * meanI[i]
            val cov = corrIp[i] - meanI[i] * meanP[i]
            a[i] = cov / (variance.coerceAtLeast(0f) + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = boxMean(a, width, height, radius)
        val meanB = boxMean(b, width, height, radius)
        return FloatArray(n) { meanA[it] * guide[it] + meanB[it] }
    }

    /** Exact mean over the `(2r + 1)` square window around every pixel, clamped at the borders. */
    fun boxMean(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val r = radius.coerceAtLeast(0)
        val horizontal = FloatArray(width * height)
        val line = DoubleArray(maxOf(width, height) + 1)
        for (y in 0 until height) {
            line[0] = 0.0
            for (x in 0 until width) line[x + 1] = line[x] + src[y * width + x]
            for (x in 0 until width) {
                val lo = (x - r).coerceAtLeast(0)
                val hi = (x + r).coerceAtMost(width - 1)
                horizontal[y * width + x] = ((line[hi + 1] - line[lo]) / (hi - lo + 1)).toFloat()
            }
        }
        val out = FloatArray(width * height)
        for (x in 0 until width) {
            line[0] = 0.0
            for (y in 0 until height) line[y + 1] = line[y] + horizontal[y * width + x]
            for (y in 0 until height) {
                val lo = (y - r).coerceAtLeast(0)
                val hi = (y + r).coerceAtMost(height - 1)
                out[y * width + x] = ((line[hi + 1] - line[lo]) / (hi - lo + 1)).toFloat()
            }
        }
        return out
    }
}
