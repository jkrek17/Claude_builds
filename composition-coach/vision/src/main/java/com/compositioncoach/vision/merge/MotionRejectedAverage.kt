package com.compositioncoach.vision.merge

import com.compositioncoach.vision.PixelShift
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The burst merge's core: a per-pixel weighted average of aligned exposures in which a pixel that disagrees
 * with the reference by more than noise can explain gets (almost) no weight. A static scene averages (noise
 * variance falls toward `1 / N`); a moving subject, a passing car, a leaf in the wind stays as the reference
 * saw it instead of ghosting.
 *
 * ## Weights (on the guide, not at full resolution)
 * For every non-reference frame `i`, with its guide aligned to the reference's (see [BurstAlign]):
 * `w = exp(-d^2 / (2 sigma^2))` where `d^2` is the 3 x 3-averaged squared difference `(luma_i - luma_ref)^2`
 * and `sigma = TOLERANCE_SIGMAS * sqrt(2) * sigmaNoise(x)`, widened by an allowance for residual misalignment
 * proportional to the reference's local gradient (an edge that is half a guide pixel off differs by much more
 * than noise without anything having moved). `sigmaNoise(x)` is the frame's noise estimate ([estimateNoise])
 * scaled by the local brightness (shot noise grows with the square root of the signal). The reference's own
 * weight is always 1. Weights are box-smoothed, dropped for frames that barely agree anywhere ([meanWeight])
 * and [normalize]d to sum to 1 at every guide cell.
 *
 * ## Blend (at full resolution)
 * [blendStrip] upsamples the weights bilinearly to every output pixel and mixes the sources per channel. Sources
 * are sampled at `(x + dx, y + dy)` with their (rounded) alignment shift, clamped to the strip. It works on
 * horizontal strips so a 12 MP merge never holds more than one strip per frame at a time.
 */
object MotionRejectedAverage {
    /** A difference smaller than this many noise standard deviations (of a difference) counts as "the same". */
    const val TOLERANCE_SIGMAS = 2f

    /** Fraction of the reference's local gradient (luma per guide pixel) tolerated as misalignment error. */
    private const val ALIGNMENT_ALLOWANCE = 0.35f

    /** Floor for the difference's sigma: one 8-bit level, so noise-free inputs still compare sensibly. */
    private const val SIGMA_FLOOR = 1f / 255f

    /** Brightness offset in the shot-noise model, so black does not get zero tolerance. */
    private const val NOISE_BRIGHTNESS_OFFSET = 0.03f
    private const val NOISE_SCALE_MIN = 0.5f
    private const val NOISE_SCALE_MAX = 2f
    private const val MEDIAN_BINS = 4096
    private const val MAD_TO_SIGMA = 1.4826f

    /** A non-reference frame whose mean weight is below this agrees with the reference almost nowhere: not worth merging. */
    const val MIN_MEAN_WEIGHT = 0.25f

    private val SQRT2 = sqrt(2f)

    /** The gain of a 3 x 3 high-pass (pixel minus its box mean) on white noise. */
    private val HIGH_PASS_GAIN = sqrt(8f / 9f)

    /** Rows [top] until [top] + [rows] of one full-resolution source (`width * rows` ARGB) and its alignment shift. */
    class Strip(val pixels: IntArray, val top: Int, val rows: Int, val shift: PixelShift)

    /** BT.601 luma (0..1) of ARGB pixels. */
    fun luma(argb: IntArray): FloatArray = FloatArray(argb.size) { i ->
        val c = argb[i]
        (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
    }

    /** Box-averaged luma guide of a full image, [scale] times smaller per side; returns the guide and its size. */
    fun lumaGuide(argb: IntArray, width: Int, height: Int, scale: Int): Triple<FloatArray, Int, Int> {
        val gw = (width + scale - 1) / scale
        val gh = (height + scale - 1) / scale
        val sums = FloatArray(gw * gh)
        val counts = IntArray(gw * gh)
        val l = luma(argb)
        for (y in 0 until height) for (x in 0 until width) {
            val j = (y / scale) * gw + x / scale
            sums[j] += l[y * width + x]
            counts[j]++
        }
        return Triple(FloatArray(gw * gh) { sums[it] / counts[it].coerceAtLeast(1) }, gw, gh)
    }

    /**
     * Standard deviation of the per-pixel noise of a luma plane (0..1): the median absolute deviation of its
     * 3 x 3 high-pass, which texture and edges (a minority of pixels) barely move, scaled for the high-pass's
     * own gain (`sqrt(8/9)` for white noise).
     */
    fun estimateNoise(plane: FloatArray, width: Int, height: Int): Float {
        if (width < 3 || height < 3) return 0f
        val hist = IntArray(MEDIAN_BINS)
        var n = 0
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            var sum = 0f
            for (dy in -1..1) for (dx in -1..1) sum += plane[(y + dy) * width + x + dx]
            val hp = kotlin.math.abs(plane[y * width + x] - sum / 9f)
            hist[min(MEDIAN_BINS - 1, (hp * MEDIAN_BINS).toInt())]++
            n++
        }
        return MAD_TO_SIGMA * medianOf(hist, n) / HIGH_PASS_GAIN
    }

    /** Un-normalised weights of [aligned] (a guide already shifted onto [reference]) on the guide grid, in 0..1. */
    fun rawWeights(reference: FloatArray, aligned: FloatArray, width: Int, height: Int, noiseSigma: Float? = null): FloatArray {
        val n = width * height
        val diffSq = FloatArray(n) { val d = aligned[it] - reference[it]; d * d }
        val sigma = noiseSigma ?: min(estimateNoise(reference, width, height), temporalNoise(reference, aligned))
        val smoothDiff = boxAverage3(diffSq, width, height)
        val smoothRef = boxAverage3(reference, width, height)
        var meanLuma = 0f
        for (v in smoothRef) meanLuma += v
        meanLuma /= n
        val raw = FloatArray(n)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val gx = (reference[y * width + min(x + 1, width - 1)] - reference[y * width + max(x - 1, 0)]) * 0.5f
            val gy = (reference[min(y + 1, height - 1) * width + x] - reference[max(y - 1, 0) * width + x]) * 0.5f
            val scale = sqrt((smoothRef[i] + NOISE_BRIGHTNESS_OFFSET) / (meanLuma + NOISE_BRIGHTNESS_OFFSET))
                .coerceIn(NOISE_SCALE_MIN, NOISE_SCALE_MAX)
            val noise = TOLERANCE_SIGMAS * SQRT2 * sigma * scale
            val misalignment = ALIGNMENT_ALLOWANCE * sqrt(gx * gx + gy * gy)
            val variance = noise * noise + misalignment * misalignment + SIGMA_FLOOR * SIGMA_FLOOR
            raw[i] = exp(-smoothDiff[i] / (2f * variance))
        }
        return boxAverage3(raw, width, height)
    }

    /** Noise of one frame implied by how much it differs from the reference: the median |difference| is mostly noise while most of the scene is static. */
    private fun temporalNoise(reference: FloatArray, aligned: FloatArray): Float {
        val hist = IntArray(MEDIAN_BINS)
        for (i in reference.indices) {
            hist[min(MEDIAN_BINS - 1, (kotlin.math.abs(aligned[i] - reference[i]) * MEDIAN_BINS).toInt())]++
        }
        return MAD_TO_SIGMA * medianOf(hist, reference.size) / SQRT2
    }

    /** Median of values in 0..1 held in a [MEDIAN_BINS]-bin histogram of [n] samples. */
    private fun medianOf(hist: IntArray, n: Int): Float {
        var seen = 0
        var bin = 0
        while (bin < MEDIAN_BINS - 1 && seen + hist[bin] < n / 2) {
            seen += hist[bin]
            bin++
        }
        return (bin + 0.5f) / MEDIAN_BINS
    }

    /** Mean of a weight map: how much of the frame agrees with the reference. */
    fun meanWeight(weights: FloatArray): Float {
        var sum = 0.0
        for (w in weights) sum += w
        return (sum / weights.size).toFloat()
    }

    /** Scales the maps in place so they sum to 1 at every cell (a cell where all are 0 goes to an even split). */
    fun normalize(weights: List<FloatArray>) {
        require(weights.isNotEmpty())
        val n = weights[0].size
        for (i in 0 until n) {
            var sum = 0f
            for (w in weights) sum += w[i]
            if (sum > 0f) for (w in weights) w[i] /= sum else for (w in weights) w[i] = 1f / weights.size
        }
    }

    /**
     * Normalised weights for [guides] (unaligned, one per frame) given each frame's [shifts] onto the reference
     * (in guide pixels; the reference's own is ignored). The reference gets weight 1 before normalisation.
     */
    fun weights(
        guides: List<FloatArray>,
        shifts: List<SubPixelShift>,
        referenceIndex: Int,
        width: Int,
        height: Int,
    ): List<FloatArray> {
        val reference = guides[referenceIndex]
        val raw = guides.mapIndexed { i, guide ->
            if (i == referenceIndex) FloatArray(width * height) { 1f }
            else rawWeights(reference, BurstAlign.shifted(guide, width, height, shifts[i]), width, height)
        }
        normalize(raw)
        return raw
    }

    /**
     * Blends output rows [y0] until [y0] + [rows] of a [width] x [height] image into [out] (`width * rows`).
     * [weights] are normalised guide-grid maps ([gw] x [gh]), one per strip in the same order. Each strip must
     * cover the rows its shifted samples need (rows outside it are clamped to its edge).
     */
    fun blendStrip(
        strips: List<Strip>,
        width: Int,
        height: Int,
        y0: Int,
        rows: Int,
        weights: List<FloatArray>,
        gw: Int,
        gh: Int,
        out: IntArray,
    ) {
        val k = strips.size
        require(weights.size == k) { "one weight map per strip" }
        val xi = IntArray(width)
        val fx = FloatArray(width)
        for (x in 0 until width) {
            val gx = ((x + 0.5f) / width * gw - 0.5f).coerceIn(0f, gw - 1f)
            xi[x] = min(gx.toInt(), gw - 1)
            fx[x] = gx - xi[x]
        }
        val rowWeights = Array(k) { FloatArray(gw) }
        for (ry in 0 until rows) {
            val y = y0 + ry
            val gy = ((y + 0.5f) / height * gh - 0.5f).coerceIn(0f, gh - 1f)
            val ya = min(gy.toInt(), gh - 1)
            val yb = min(ya + 1, gh - 1)
            val fy = gy - ya
            for (s in 0 until k) {
                val grid = weights[s]
                val dst = rowWeights[s]
                for (g in 0 until gw) dst[g] = grid[ya * gw + g] * (1f - fy) + grid[yb * gw + g] * fy
            }
            for (x in 0 until width) {
                val xb = min(xi[x] + 1, gw - 1)
                var r = 0f
                var g = 0f
                var b = 0f
                for (s in 0 until k) {
                    val w = rowWeights[s][xi[x]] * (1f - fx[x]) + rowWeights[s][xb] * fx[x]
                    if (w <= 0f) continue
                    val strip = strips[s]
                    val sy = (y + strip.shift.dy).coerceIn(0, height - 1).coerceIn(strip.top, strip.top + strip.rows - 1)
                    val sx = (x + strip.shift.dx).coerceIn(0, width - 1)
                    val c = strip.pixels[(sy - strip.top) * width + sx]
                    r += w * ((c shr 16) and 0xFF)
                    g += w * ((c shr 8) and 0xFF)
                    b += w * (c and 0xFF)
                }
                out[ry * width + x] = (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
            }
        }
    }

    /** Whole-image convenience over [blendStrip] (tests, small images). */
    fun blend(
        sources: List<IntArray>,
        width: Int,
        height: Int,
        weights: List<FloatArray>,
        gw: Int,
        gh: Int,
        shifts: List<PixelShift>? = null,
    ): IntArray {
        val out = IntArray(width * height)
        val strips = sources.mapIndexed { i, px -> Strip(px, 0, height, shifts?.get(i) ?: PixelShift.NONE) }
        blendStrip(strips, width, height, 0, height, weights, gw, gh, out)
        return out
    }

    private fun channel(v: Float): Int = v.roundToInt().coerceIn(0, 255)

    /** 3 x 3 mean, shrinking to the cells that exist at the border. */
    private fun boxAverage3(src: FloatArray, w: Int, h: Int): FloatArray {
        val horizontal = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val a = max(x - 1, 0)
            val b = min(x + 1, w - 1)
            var sum = 0f
            for (xx in a..b) sum += src[y * w + xx]
            horizontal[y * w + x] = sum / (b - a + 1)
        }
        return FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val a = max(y - 1, 0)
            val b = min(y + 1, h - 1)
            var sum = 0f
            for (yy in a..b) sum += horizontal[yy * w + x]
            sum / (b - a + 1)
        }
    }
}
