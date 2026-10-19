package com.compositioncoach.app.camera

import com.compositioncoach.vision.PixelShift
import kotlin.math.roundToInt

/**
 * Pure focus-stack merge: per-pixel choice of the sharpest of several differently focused, aligned exposures.
 *
 * ## Focus measure (on a guide, not at full resolution)
 * Each source is reduced to a luma guide [GUIDE_SCALE] (8x) smaller per side. On the guide, the squared
 * Laplacian (4-neighbour) is averaged over three window radii (1, 2 and 4 guide pixels: three levels of
 * locality, so fine texture and larger structure both count, and a flat patch next to a sharp edge inherits
 * the edge's verdict). The per-source energies become weights `e^4 / sum(e^4)`: close to a hard "sharpest
 * wins" where one source is clearly sharper, an even mix where none is (flat sky, so noise is averaged
 * rather than picked). The weight maps are box-smoothed twice so transitions are gradual.
 *
 * ## Blend (at full resolution)
 * Weights are upsampled bilinearly to every output pixel and the sources are mixed per channel. Sources are
 * sampled at `(x + dx, y + dy)` with their alignment shift (content moved by `(dx, dy)` relative to the first
 * exposure), clamped at the edges. [blendStrip] works on horizontal strips so a 12 MP merge never holds three
 * full-size decodes at once.
 */
object FocusMerge {
    const val GUIDE_SCALE = 8
    private const val EPSILON = 1e-6f

    /** Rows [top] until [top] + [rows] of one full-resolution source (`width * rows` ARGB), and its shift. */
    class Strip(val pixels: IntArray, val top: Int, val rows: Int, val shift: PixelShift)

    /** BT.601 luma (0..1) of ARGB pixels. */
    fun luma(argb: IntArray): FloatArray = FloatArray(argb.size) { i ->
        val c = argb[i]
        (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
    }

    /** Box-averaged luma guide of a full image; returns the guide and its size. */
    fun lumaGuide(argb: IntArray, width: Int, height: Int, scale: Int = GUIDE_SCALE): Triple<FloatArray, Int, Int> {
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

    /** Moves a guide by a shift already expressed in guide pixels (sampling at `(x + dx, y + dy)`, clamped). */
    fun shiftGuide(guide: FloatArray, gw: Int, gh: Int, shift: PixelShift): FloatArray =
        if (shift == PixelShift.NONE) guide else FloatArray(gw * gh) { i ->
            guide[(i / gw + shift.dy).coerceIn(0, gh - 1) * gw + (i % gw + shift.dx).coerceIn(0, gw - 1)]
        }

    /** Per-source weight maps on the guide grid, summing to 1 at every cell (see the class doc). */
    fun weights(guides: List<FloatArray>, gw: Int, gh: Int): List<FloatArray> {
        require(guides.isNotEmpty())
        val energies = guides.map { guide ->
            val lap = FloatArray(gw * gh) { i ->
                val x = i % gw
                val y = i / gw
                fun at(xx: Int, yy: Int) = guide[yy.coerceIn(0, gh - 1) * gw + xx.coerceIn(0, gw - 1)]
                val v = 4f * guide[i] - at(x - 1, y) - at(x + 1, y) - at(x, y - 1) - at(x, y + 1)
                v * v
            }
            val e1 = boxAverage(lap, gw, gh, 1)
            val e2 = boxAverage(lap, gw, gh, 2)
            val e4 = boxAverage(lap, gw, gh, 4)
            FloatArray(gw * gh) { (e1[it] + e2[it] + e4[it]) / 3f }
        }
        val raw = energies.map { e -> FloatArray(gw * gh) { val v = e[it] + EPSILON; val sq = v * v; sq * sq } }
        val smoothed = raw.map { boxAverage(boxAverage(normalize(raw, it, gw * gh), gw, gh, 1), gw, gh, 1) }
        val total = FloatArray(gw * gh) { i -> smoothed.sumOf { it[i].toDouble() }.toFloat() }
        return smoothed.map { w -> FloatArray(gw * gh) { i -> if (total[i] > 0f) w[i] / total[i] else 1f / smoothed.size } }
    }

    private fun normalize(all: List<FloatArray>, one: FloatArray, n: Int): FloatArray = FloatArray(n) { i ->
        var sum = 0f
        for (a in all) sum += a[i]
        if (sum > 0f) one[i] / sum else 1f / all.size
    }

    private fun boxAverage(src: FloatArray, w: Int, h: Int, r: Int): FloatArray = FloatArray(w * h) { i ->
        val x = i % w
        val y = i / w
        var sum = 0f
        var n = 0
        for (yy in (y - r).coerceAtLeast(0)..(y + r).coerceAtMost(h - 1)) {
            for (xx in (x - r).coerceAtLeast(0)..(x + r).coerceAtMost(w - 1)) {
                sum += src[yy * w + xx]
                n++
            }
        }
        sum / n
    }

    /**
     * Blends output rows [y0] until [y0] + [rows] of a [width] x [height] image into [out] (`width * rows`).
     * Each strip must cover the rows its shifted samples need (rows outside it are clamped to its edge).
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
        val w = FloatArray(k)
        for (ry in 0 until rows) {
            val y = y0 + ry
            val gy = ((y + 0.5f) / height * gh - 0.5f).coerceIn(0f, gh - 1f)
            for (x in 0 until width) {
                val gx = ((x + 0.5f) / width * gw - 0.5f).coerceIn(0f, gw - 1f)
                var r = 0f
                var g = 0f
                var b = 0f
                for (s in 0 until k) {
                    w[s] = bilinear(weights[s], gw, gh, gx, gy)
                    val strip = strips[s]
                    val sy = (y + strip.shift.dy).coerceIn(0, height - 1).coerceIn(strip.top, strip.top + strip.rows - 1)
                    val sx = (x + strip.shift.dx).coerceIn(0, width - 1)
                    val c = strip.pixels[(sy - strip.top) * width + sx]
                    r += w[s] * ((c shr 16) and 0xFF)
                    g += w[s] * ((c shr 8) and 0xFF)
                    b += w[s] * (c and 0xFF)
                }
                out[ry * width + x] = (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
            }
        }
    }

    /** Whole-image convenience over [blendStrip] (tests, small images). */
    fun blend(sources: List<IntArray>, width: Int, height: Int, weights: List<FloatArray>, gw: Int, gh: Int, shifts: List<PixelShift>? = null): IntArray {
        val out = IntArray(width * height)
        val strips = sources.mapIndexed { i, px -> Strip(px, 0, height, shifts?.get(i) ?: PixelShift.NONE) }
        blendStrip(strips, width, height, 0, height, weights, gw, gh, out)
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
