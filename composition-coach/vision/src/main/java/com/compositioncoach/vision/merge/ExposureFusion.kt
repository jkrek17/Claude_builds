package com.compositioncoach.vision.merge

import com.compositioncoach.vision.PixelShift
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Mertens exposure fusion: several differently exposed, aligned frames of one scene become one image with
 * highlight detail from the dark frame and shadow detail from the bright one, with no tone mapping beyond the
 * blend itself. Pure functions over `IntArray` ARGB images, so every step is JVM-testable.
 *
 * ## Weights (on a guide, [GUIDE_SCALE] times smaller per side)
 * Each frame gets a per-pixel quality `contrast x saturation x well-exposedness`:
 * contrast is the magnitude of the 4-neighbour Laplacian of luma, saturation is the standard deviation of the
 * three channels, and well-exposedness is a Gaussian (sigma [EXPOSEDNESS_SIGMA]) around mid-grey, multiplied
 * over the channels, so clipped and crushed pixels count for almost nothing. The qualities are normalised across
 * frames ([weights]), so they sum to 1 at every cell.
 *
 * ## Blend
 * 1. [pyramidBlend] blends the guides with a [PYRAMID_LEVELS]-level Laplacian pyramid (each frame's Laplacian
 *    levels weighted by the Gaussian levels of its weight map), which hides seams and halos.
 * 2. [correction] is that blend minus the plain weighted average of the same guides: the low-frequency
 *    brightness the plain average gets wrong near strong weight edges.
 * 3. [blendStrip] mixes the full-resolution frames with the bilinearly upsampled weights and adds the upsampled
 *    correction, one horizontal strip at a time, so a 12 MP merge never holds all the frames decoded at once
 *    (the same strip pattern as the focus merge).
 *
 * [boxGuide] makes a guide from a full image; [alignmentShift] refines a gyro shift on the guide luma with
 * median-threshold-bitmap matching, which is unaffected by the exposure difference between the frames.
 */
object ExposureFusion {
    /** Guide size divisor: weights, pyramid and alignment run on images this many times smaller per side. */
    const val GUIDE_SCALE = 4

    /** Levels of the Laplacian pyramid (the coarsest is the Gaussian residual); fewer on tiny guides. */
    const val PYRAMID_LEVELS = 4

    /** Spread of the well-exposedness Gaussian around mid-grey, in 0..1 intensity. */
    const val EXPOSEDNESS_SIGMA = 0.2f

    /** Alignment search radius in guide pixels (8 guide pixels = 32 full-resolution pixels). */
    const val ALIGN_RADIUS = 8

    /** Floor added to contrast and saturation so a flat patch keeps a weight decided by exposure alone. */
    private const val QUALITY_FLOOR = 1e-3f
    private const val MIN_QUALITY = 1e-12f

    /** Pixels within this many 8-bit levels of the median are ignored by alignment (they flip with noise). */
    private const val ALIGN_NOISE_BAND = 4
    private const val ALIGN_STRIDE = 2
    private const val ALIGN_MIN_VALID_FRACTION = 0.05
    private const val UNKNOWN: Byte = -1

    /** Rows [top] until [top] + [rows] of one full-resolution frame (`width * rows` ARGB) and its alignment shift. */
    class Strip(val pixels: IntArray, val top: Int, val rows: Int, val shift: PixelShift)

    /** Three float colour planes (0..255) of equal size. */
    class Planes(val r: FloatArray, val g: FloatArray, val b: FloatArray)

    /** A guide image: ARGB [pixels] of size [width] x [height]. */
    class Guide(val pixels: IntArray, val width: Int, val height: Int)

    /** BT.601 luma (0..1) of ARGB pixels. */
    fun luma(argb: IntArray): FloatArray = FloatArray(argb.size) { i ->
        val c = argb[i]
        (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
    }

    /** Box-averages a full image down by [scale] per side (edge cells average fewer pixels). */
    fun boxGuide(argb: IntArray, width: Int, height: Int, scale: Int = GUIDE_SCALE): Guide {
        val gw = (width + scale - 1) / scale
        val gh = (height + scale - 1) / scale
        val r = IntArray(gw * gh)
        val g = IntArray(gw * gh)
        val b = IntArray(gw * gh)
        val n = IntArray(gw * gh)
        for (y in 0 until height) {
            val row = (y / scale) * gw
            for (x in 0 until width) {
                val j = row + x / scale
                val c = argb[y * width + x]
                r[j] += (c shr 16) and 0xFF
                g[j] += (c shr 8) and 0xFF
                b[j] += c and 0xFF
                n[j]++
            }
        }
        return Guide(
            IntArray(gw * gh) { j ->
                val k = n[j].coerceAtLeast(1)
                (0xFF shl 24) or (((r[j] + k / 2) / k) shl 16) or (((g[j] + k / 2) / k) shl 8) or ((b[j] + k / 2) / k)
            },
            gw,
            gh,
        )
    }

    /** Moves a guide by a shift in guide pixels (sampling at `(x + dx, y + dy)`, clamped at the edges). */
    fun shiftArgb(guide: IntArray, gw: Int, gh: Int, shift: PixelShift): IntArray =
        if (shift == PixelShift.NONE) guide else IntArray(gw * gh) { i ->
            guide[(i / gw + shift.dy).coerceIn(0, gh - 1) * gw + (i % gw + shift.dx).coerceIn(0, gw - 1)]
        }

    // ---- Alignment --------------------------------------------------------------------------------------------

    /**
     * Refines [initial] (typically the gyro shift, in guide pixels) by block matching [other] against [reference]
     * (both guide luma, 0..1): the result is the shift, within [radius] of [initial], for which sampling [other]
     * at `(x + dx, y + dy)` lines it up with [reference]. Frames of different exposure are compared as median
     * threshold bitmaps (each pixel is "brighter than the frame's median" or not), the classic exposure-
     * independent alignment signal, scored as the fraction of mismatching pixels; ties go to the smaller change.
     * Returns [initial] when the guide is too small or too featureless to match.
     */
    fun alignmentShift(
        reference: FloatArray,
        other: FloatArray,
        gw: Int,
        gh: Int,
        initial: PixelShift = PixelShift.NONE,
        radius: Int = ALIGN_RADIUS,
    ): PixelShift {
        val marginX = radius + abs(initial.dx)
        val marginY = radius + abs(initial.dy)
        if (gw - 2 * marginX < 8 || gh - 2 * marginY < 8) return initial
        val refBits = medianBits(reference)
        val otherBits = medianBits(other)
        val total = ((gw - 2 * marginX) / ALIGN_STRIDE) * ((gh - 2 * marginY) / ALIGN_STRIDE)
        var best = initial
        var bestScore = Double.MAX_VALUE
        var bestDistance = Int.MAX_VALUE
        for (ry in -radius..radius) for (rx in -radius..radius) {
            val ox = initial.dx + rx
            val oy = initial.dy + ry
            var valid = 0
            var mismatched = 0
            var y = marginY
            while (y < gh - marginY) {
                var x = marginX
                while (x < gw - marginX) {
                    val a = refBits[y * gw + x]
                    val b = otherBits[(y + oy) * gw + x + ox]
                    if (a != UNKNOWN && b != UNKNOWN) {
                        valid++
                        if (a != b) mismatched++
                    }
                    x += ALIGN_STRIDE
                }
                y += ALIGN_STRIDE
            }
            if (valid < total * ALIGN_MIN_VALID_FRACTION) continue
            val score = mismatched.toDouble() / valid
            val distance = abs(rx) + abs(ry)
            if (score < bestScore - 1e-9 || (abs(score - bestScore) <= 1e-9 && distance < bestDistance)) {
                bestScore = score
                bestDistance = distance
                best = PixelShift(ox, oy)
            }
        }
        return best
    }

    /** 0 = at or below the median, 1 = above it, [UNKNOWN] within the noise band around it. */
    private fun medianBits(luma: FloatArray): ByteArray {
        val histogram = IntArray(256)
        for (v in luma) histogram[(v * 255f).roundToInt().coerceIn(0, 255)]++
        var seen = 0
        var median = 0
        while (median < 255 && seen + histogram[median] < luma.size / 2) {
            seen += histogram[median]
            median++
        }
        return ByteArray(luma.size) { i ->
            val level = (luma[i] * 255f).roundToInt().coerceIn(0, 255)
            when {
                abs(level - median) <= ALIGN_NOISE_BAND -> UNKNOWN
                level > median -> 1
                else -> 0
            }
        }
    }

    // ---- Weights ----------------------------------------------------------------------------------------------

    /** Per-frame weight maps on the guide grid, summing to 1 at every cell. */
    fun weights(guides: List<IntArray>, gw: Int, gh: Int): List<FloatArray> {
        require(guides.isNotEmpty()) { "No frames" }
        val n = gw * gh
        val quality = guides.map { px -> quality(px, gw, gh) }
        val total = DoubleArray(n)
        for (q in quality) for (i in 0 until n) total[i] += q[i].toDouble()
        return quality.map { q -> FloatArray(n) { i -> (q[i] / total[i]).toFloat() } }
    }

    /** Contrast x saturation x well-exposedness of one guide frame. */
    private fun quality(px: IntArray, gw: Int, gh: Int): FloatArray {
        val l = luma(px)
        val twoSigmaSq = 2f * EXPOSEDNESS_SIGMA * EXPOSEDNESS_SIGMA
        fun well(v: Float): Float = exp(-(v - 0.5f) * (v - 0.5f) / twoSigmaSq)
        return FloatArray(gw * gh) { i ->
            val x = i % gw
            val y = i / gw
            fun at(xx: Int, yy: Int) = l[yy.coerceIn(0, gh - 1) * gw + xx.coerceIn(0, gw - 1)]
            val contrast = abs(4f * l[i] - at(x - 1, y) - at(x + 1, y) - at(x, y - 1) - at(x, y + 1))
            val c = px[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val mean = (r + g + b) / 3f
            val saturation = sqrt(((r - mean) * (r - mean) + (g - mean) * (g - mean) + (b - mean) * (b - mean)) / 3f)
            maxOf((contrast + QUALITY_FLOOR) * (saturation + QUALITY_FLOOR) * well(r) * well(g) * well(b), MIN_QUALITY)
        }
    }

    // ---- Pyramid blend ----------------------------------------------------------------------------------------

    /** The weighted average of the guides (no pyramid): what the strip blend would produce on the guide grid. */
    fun weightedAverage(guides: List<IntArray>, weights: List<FloatArray>, gw: Int, gh: Int): Planes {
        val n = gw * gh
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        for (k in guides.indices) {
            val px = guides[k]
            val w = weights[k]
            for (i in 0 until n) {
                val c = px[i]
                r[i] += w[i] * ((c shr 16) and 0xFF)
                g[i] += w[i] * ((c shr 8) and 0xFF)
                b[i] += w[i] * (c and 0xFF)
            }
        }
        return Planes(r, g, b)
    }

    /** Same as [weightedAverage] but returned as an ARGB guide (for chaining a mixed result into another fusion). */
    fun mixGuides(guides: List<IntArray>, weights: List<FloatArray>, gw: Int, gh: Int): IntArray {
        val p = weightedAverage(guides, weights, gw, gh)
        return IntArray(gw * gh) { i -> (0xFF shl 24) or (channel(p.r[i]) shl 16) or (channel(p.g[i]) shl 8) or channel(p.b[i]) }
    }

    /** Laplacian-pyramid blend of the guides with their [weights] (colour planes 0..255, unclamped). */
    fun pyramidBlend(guides: List<IntArray>, weights: List<FloatArray>, gw: Int, gh: Int, levels: Int = PYRAMID_LEVELS): Planes {
        require(guides.isNotEmpty() && guides.size == weights.size)
        val count = levelCount(gw, gh, levels)
        val sizes = levelSizes(gw, gh, count)
        val blended = Array(3) { Array(count) { l -> FloatArray(sizes[l].first * sizes[l].second) } }
        for (k in guides.indices) {
            val weightPyramid = gaussianPyramid(weights[k], sizes)
            val px = guides[k]
            for (ch in 0 until 3) {
                val shift = 16 - 8 * ch
                val plane = FloatArray(gw * gh) { i -> ((px[i] shr shift) and 0xFF).toFloat() }
                val gauss = gaussianPyramid(plane, sizes)
                for (l in 0 until count) {
                    val target = blended[ch][l]
                    val wl = weightPyramid[l]
                    val level = gauss[l]
                    if (l == count - 1) {
                        for (i in target.indices) target[i] += wl[i] * level[i]
                    } else {
                        val up = resample(gauss[l + 1], sizes[l + 1].first, sizes[l + 1].second, sizes[l].first, sizes[l].second)
                        for (i in target.indices) target[i] += wl[i] * (level[i] - up[i])
                    }
                }
            }
        }
        val out = Array(3) { ch ->
            var acc = blended[ch][count - 1]
            for (l in count - 2 downTo 0) {
                val up = resample(acc, sizes[l + 1].first, sizes[l + 1].second, sizes[l].first, sizes[l].second)
                val level = blended[ch][l]
                acc = FloatArray(up.size) { i -> up[i] + level[i] }
            }
            acc
        }
        return Planes(out[0], out[1], out[2])
    }

    /** [fused] (from [pyramidBlend]) minus the plain weighted average of the same guides. */
    fun correction(guides: List<IntArray>, weights: List<FloatArray>, fused: Planes, gw: Int, gh: Int): Planes {
        val plain = weightedAverage(guides, weights, gw, gh)
        return Planes(
            FloatArray(gw * gh) { fused.r[it] - plain.r[it] },
            FloatArray(gw * gh) { fused.g[it] - plain.g[it] },
            FloatArray(gw * gh) { fused.b[it] - plain.b[it] },
        )
    }

    private fun levelCount(gw: Int, gh: Int, wanted: Int): Int {
        val fits = log2(minOf(gw, gh).coerceAtLeast(1).toDouble()).toInt()
        return wanted.coerceAtMost(fits).coerceAtLeast(1)
    }

    private fun levelSizes(gw: Int, gh: Int, count: Int): List<Pair<Int, Int>> {
        val sizes = ArrayList<Pair<Int, Int>>(count)
        var w = gw
        var h = gh
        repeat(count) {
            sizes += w to h
            w = (w + 1) / 2
            h = (h + 1) / 2
        }
        return sizes
    }

    private fun gaussianPyramid(base: FloatArray, sizes: List<Pair<Int, Int>>): List<FloatArray> {
        val out = ArrayList<FloatArray>(sizes.size)
        out += base
        for (l in 1 until sizes.size) out += reduce(out[l - 1], sizes[l - 1].first, sizes[l - 1].second)
        return out
    }

    private val KERNEL = floatArrayOf(1f / 16, 4f / 16, 6f / 16, 4f / 16, 1f / 16)

    /** Binomial blur and 2x decimation; the output is `(w + 1) / 2` by `(h + 1) / 2` and a constant stays constant. */
    private fun reduce(src: FloatArray, w: Int, h: Int): FloatArray {
        val horizontal = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (k in -2..2) s += KERNEL[k + 2] * src[y * w + (x + k).coerceIn(0, w - 1)]
            horizontal[y * w + x] = s
        }
        val nw = (w + 1) / 2
        val nh = (h + 1) / 2
        return FloatArray(nw * nh) { i ->
            val x = (i % nw) * 2
            val y = (i / nw) * 2
            var s = 0f
            for (k in -2..2) s += KERNEL[k + 2] * horizontal[(y + k).coerceIn(0, h - 1) * w + x]
            s
        }
    }

    /** Bilinear resize of a [w] x [h] grid to [tw] x [th] (cell centres aligned), clamped at the edges. */
    fun resample(grid: FloatArray, w: Int, h: Int, tw: Int, th: Int): FloatArray {
        if (w == tw && h == th) return grid
        val out = FloatArray(tw * th)
        for (y in 0 until th) {
            val gy = ((y + 0.5f) * h / th - 0.5f).coerceIn(0f, h - 1f)
            for (x in 0 until tw) {
                val gx = ((x + 0.5f) * w / tw - 0.5f).coerceIn(0f, w - 1f)
                out[y * tw + x] = bilinear(grid, w, h, gx, gy)
            }
        }
        return out
    }

    // ---- Full-resolution blend --------------------------------------------------------------------------------

    /**
     * Fuses output rows [y0] until [y0] + [rows] of a [width] x [height] image into [out] (`width * rows`).
     * Each pixel is the weighted mix of the strips' pixels (sampled at their alignment shift, clamped to the image
     * and to the rows each strip holds) with the bilinearly upsampled [weights] (one map per strip, on the
     * [gw] x [gh] guide grid), plus the upsampled [correction].
     */
    fun blendStrip(
        strips: List<Strip>,
        width: Int,
        height: Int,
        y0: Int,
        rows: Int,
        weights: List<FloatArray>,
        correction: Planes,
        gw: Int,
        gh: Int,
        out: IntArray,
    ) {
        for (ry in 0 until rows) {
            val y = y0 + ry
            val gy = ((y + 0.5f) / height * gh - 0.5f).coerceIn(0f, gh - 1f)
            for (x in 0 until width) {
                val gx = ((x + 0.5f) / width * gw - 0.5f).coerceIn(0f, gw - 1f)
                var r = bilinear(correction.r, gw, gh, gx, gy)
                var g = bilinear(correction.g, gw, gh, gx, gy)
                var b = bilinear(correction.b, gw, gh, gx, gy)
                for (s in strips.indices) {
                    val w = bilinear(weights[s], gw, gh, gx, gy)
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

    /**
     * Whole-image convenience (tests, small images): guides [scale] times smaller, shifts (full-resolution
     * pixels, relative to the first frame) applied to the guides at that scale, weights, pyramid, correction,
     * then one [blendStrip] over the full height.
     */
    fun fuse(sources: List<IntArray>, width: Int, height: Int, shifts: List<PixelShift>? = null, scale: Int = GUIDE_SCALE): IntArray {
        val boxes = sources.map { boxGuide(it, width, height, scale) }
        val gw = boxes[0].width
        val gh = boxes[0].height
        val aligned = boxes.mapIndexed { i, g ->
            val s = shifts?.get(i) ?: PixelShift.NONE
            shiftArgb(g.pixels, gw, gh, PixelShift(s.dx / scale, s.dy / scale))
        }
        val w = weights(aligned, gw, gh)
        val corr = correction(aligned, w, pyramidBlend(aligned, w, gw, gh), gw, gh)
        val out = IntArray(width * height)
        val strips = sources.mapIndexed { i, px -> Strip(px, 0, height, shifts?.get(i) ?: PixelShift.NONE) }
        blendStrip(strips, width, height, 0, height, w, corr, gw, gh, out)
        return out
    }

    private fun channel(v: Float): Int = v.roundToInt().coerceIn(0, 255)

    private fun bilinear(grid: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = x - x0
        val fy = y - y0
        return (grid[y0 * w + x0] * (1 - fx) + grid[y0 * w + x1] * fx) * (1 - fy) +
            (grid[y1 * w + x0] * (1 - fx) + grid[y1 * w + x1] * fx) * fy
    }
}
