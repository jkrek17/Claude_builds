package com.compositioncoach.app.camera

/**
 * Pure-Kotlin sharpness metric used by lucky imaging (see [CapturePipeline]): the **variance of the
 * Laplacian** of a (downscaled) luminance plane.
 *
 * ## Why this metric
 * The Laplacian (here the 4-neighbour kernel `[0 1 0; 1 -4 1; 0 1 0]`) is a second-derivative filter: it
 * is near zero over flat regions and smooth gradients and large across crisp edges and fine texture. Motion
 * blur from hand shake and missed focus both act as a low-pass filter on the image, which removes exactly
 * that high-frequency energy, so the *spread* (variance) of the Laplacian response drops monotonically as
 * blur increases. It is cheap (one pass, integer arithmetic), needs no reference image, and is the
 * standard "focus measure" in the lucky-imaging literature (Pech-Pacheco et al., 2000).
 *
 * ## Why it is a fair comparison between burst frames, and when it is not
 * The Laplacian also amplifies sensor noise, so a noisier frame can score "sharper". Across three
 * back-to-back frames captured with the same exposure settings the noise level is essentially identical, so
 * the comparison stays fair; the box-filter downscale (averaging `f x f` blocks) further suppresses
 * per-pixel noise relative to real edges before the Laplacian runs. Scores are only meaningful *relative to
 * other frames of the same scene*; never compare a score across different scenes or exposures.
 *
 * ## Why it runs on a centre crop
 * The caller decodes only the central region of each JPEG (see [CapturePipeline]). Hand-shake blur is
 * dominated by camera *rotation*, which smears the whole frame almost uniformly, so the centre is
 * representative, and decoding a crop at 1:2 keeps blur of a couple of pixels measurable, whereas a whole
 * frame decoded at 1:8 would average that blur away.
 */
object SharpnessScorer {

    /**
     * Rec. 601 luma from packed ARGB pixels (as returned by `Bitmap.getPixels`), in 0..255, using the
     * integer approximation `(77 R + 150 G + 29 B) >> 8`.
     */
    fun lumaFromArgb(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val p = argb[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        (77 * r + 150 * g + 29 * b) shr 8
    }

    /**
     * Box-filter downscale by an integer [factor] (each output pixel is the mean of a `factor x factor`
     * block). Trailing rows/columns that do not fill a whole block are dropped. A factor of 1 returns the
     * input unchanged.
     */
    fun downscale(luma: IntArray, width: Int, height: Int, factor: Int): Plane {
        require(luma.size >= width * height) { "plane smaller than ${width}x$height" }
        if (factor <= 1) return Plane(luma, width, height)
        val w = width / factor
        val h = height / factor
        val out = IntArray(w * h)
        val area = factor * factor
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                val y0 = y * factor
                val x0 = x * factor
                for (dy in 0 until factor) {
                    val row = (y0 + dy) * width + x0
                    for (dx in 0 until factor) sum += luma[row + dx]
                }
                out[y * w + x] = sum / area
            }
        }
        return Plane(out, w, h)
    }

    /**
     * Variance of the 4-neighbour Laplacian over the interior of the plane (the one-pixel border is
     * skipped). Returns 0 for planes smaller than 3x3.
     */
    fun varianceOfLaplacian(luma: IntArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val lap = luma[i - width] + luma[i + width] + luma[i - 1] + luma[i + 1] - 4 * luma[i]
                sum += lap
                sumSq += lap.toDouble() * lap
                n++
            }
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /**
     * The score lucky imaging compares: box-downscale the plane by [downscaleFactor] (raised further if
     * needed so the longer side is at most [maxDimension]), then take [varianceOfLaplacian]. Higher is
     * sharper.
     */
    fun score(luma: IntArray, width: Int, height: Int, downscaleFactor: Int = 1, maxDimension: Int = 1280): Float {
        var factor = downscaleFactor.coerceAtLeast(1)
        while (maxOf(width, height) / factor > maxDimension) factor++
        val plane = downscale(luma, width, height, factor)
        return varianceOfLaplacian(plane.luma, plane.width, plane.height).toFloat()
    }

    /** Index of the highest score (the earliest frame on a tie), or -1 if [scores] is empty. */
    fun sharpestIndex(scores: List<Float>): Int {
        if (scores.isEmpty()) return -1
        var best = 0
        for (i in 1 until scores.size) if (scores[i] > scores[best]) best = i
        return best
    }

    /** A luminance plane and its dimensions. */
    class Plane(val luma: IntArray, val width: Int, val height: Int)
}
