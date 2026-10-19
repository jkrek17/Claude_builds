package com.compositioncoach.vision.merge

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Puts the reference frame's fine detail back into an averaged strip so the merge does not go soft.
 *
 * Averaging aligned frames lowers noise but also blurs anything that is a fraction of a pixel out of place
 * (residual alignment error, rolling shutter, tiny subject motion). The reference frame is the sharpest of the
 * burst, so its high-frequency detail (the pixel minus its 3 x 3 box blur, on luma) is added back scaled by
 * [AMOUNT] (0.5), with two guards the plain recipe lacks:
 *
 * - **Coring.** Detail below [CORING_SIGMAS] noise standard deviations is dropped (and larger detail shrunk by
 *   that much), otherwise the reference's own noise would be re-added at half strength and cancel a good part
 *   of what the average just removed.
 * - **No halos.** The corrected luma may not leave the reference's local 3 x 3 range, so a hard edge is
 *   steepened but never overshoots into a bright or dark rim.
 *
 * The same luma change is added to the three channels, so colour noise is not touched. Pure functions on ARGB
 * strips.
 */
object DetailTransfer {
    /** The spec's "scaled by 0.5". */
    const val AMOUNT = 0.5f

    /** Detail under this many noise standard deviations (of the high-pass) is treated as noise. */
    const val CORING_SIGMAS = 2f

    private const val MEDIAN_BINS = 1024
    private const val BIN_WIDTH = 0.25f
    private const val MAD_TO_SIGMA = 1.4826f
    private val HIGH_PASS_GAIN = sqrt(8f / 9f)

    /** Integer BT.601-ish luma of an ARGB pixel, 0..255 (the same weights as `SharpnessScorer`). */
    fun lumaOf(argb: Int): Int = (77 * ((argb shr 16) and 0xFF) + 150 * ((argb shr 8) and 0xFF) + 29 * (argb and 0xFF)) shr 8

    /**
     * Per-pixel noise standard deviation (in 0..255 luma levels) of an image or a band of one: the median
     * absolute deviation of the 3 x 3 high-pass of [luma], scaled for the high-pass's gain on white noise.
     */
    fun estimateNoise(luma: IntArray, width: Int, height: Int): Float {
        if (width < 3 || height < 3) return 0f
        val hist = IntArray(MEDIAN_BINS)
        var n = 0
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            var sum = 0
            for (dy in -1..1) for (dx in -1..1) sum += luma[(y + dy) * width + x + dx]
            val hp = abs(luma[y * width + x] - sum / 9f)
            hist[min(MEDIAN_BINS - 1, (hp / BIN_WIDTH).toInt())]++
            n++
        }
        var seen = 0
        var bin = 0
        while (bin < MEDIAN_BINS - 1 && seen + hist[bin] < n / 2) {
            seen += hist[bin]
            bin++
        }
        return MAD_TO_SIGMA * (bin + 0.5f) * BIN_WIDTH / HIGH_PASS_GAIN
    }

    /**
     * Adds the reference's detail to [out], rows [y0] until [y0] + [rows] of a [width] x [height] image
     * (`width * rows` ARGB, modified in place). [reference] holds `refRows` rows of the reference frame starting at
     * row [refTop]; it should include one row above and below the strip when they exist (rows outside it are
     * clamped to its edge). [noiseSigma] is the per-pixel noise in luma levels ([estimateNoise]).
     */
    fun apply(
        out: IntArray,
        reference: IntArray,
        refTop: Int,
        refRows: Int,
        width: Int,
        height: Int,
        y0: Int,
        rows: Int,
        noiseSigma: Float,
        amount: Float = AMOUNT,
    ) {
        if (amount <= 0f) return
        val luma = IntArray(refRows * width) { lumaOf(reference[it]) }
        val coring = CORING_SIGMAS * noiseSigma * HIGH_PASS_GAIN
        val lastRow = refTop + refRows - 1
        for (ry in 0 until rows) {
            val y = y0 + ry
            for (x in 0 until width) {
                var sum = 0
                var lo = Int.MAX_VALUE
                var hi = Int.MIN_VALUE
                for (dy in -1..1) {
                    val row = (y + dy).coerceIn(0, height - 1).coerceIn(refTop, lastRow) - refTop
                    for (dx in -1..1) {
                        val v = luma[row * width + (x + dx).coerceIn(0, width - 1)]
                        sum += v
                        lo = min(lo, v)
                        hi = max(hi, v)
                    }
                }
                val centreRow = y.coerceIn(refTop, lastRow) - refTop
                val centre = luma[centreRow * width + x]
                val detail = centre - sum / 9f
                val cored = when {
                    detail > coring -> detail - coring
                    detail < -coring -> detail + coring
                    else -> 0f
                }
                if (cored == 0f) continue
                val p = out[ry * width + x]
                val current = lumaOf(p)
                var target = current + amount * cored
                // Never push past the reference's own local range (unless the average already sits outside it).
                target = if (cored > 0f) min(target, max(hi.toFloat(), current.toFloat())) else max(target, min(lo.toFloat(), current.toFloat()))
                val delta = (target - current).roundToInt()
                if (delta == 0) continue
                out[ry * width + x] = (0xFF shl 24) or
                    (channel(((p shr 16) and 0xFF) + delta) shl 16) or
                    (channel(((p shr 8) and 0xFF) + delta) shl 8) or
                    channel((p and 0xFF) + delta)
            }
        }
    }

    private fun channel(v: Int): Int = v.coerceIn(0, 255)
}
