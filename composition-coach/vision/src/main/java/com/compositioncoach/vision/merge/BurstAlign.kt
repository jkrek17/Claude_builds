package com.compositioncoach.vision.merge

import com.compositioncoach.vision.PixelShift
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A translation with a fractional part, in pixels of whatever grid it was measured on (positive = right / down).
 * Same convention as [PixelShift]: the frame's content moved by `(dx, dy)` relative to the reference, so
 * sampling the frame at `(x + dx, y + dy)` lines it up with the reference at `(x, y)`.
 */
data class SubPixelShift(val dx: Float, val dy: Float) {
    fun scaled(factorX: Float, factorY: Float = factorX) = SubPixelShift(dx * factorX, dy * factorY)

    fun roundedToPixels() = PixelShift(dx.roundToInt(), dy.roundToInt())

    companion object {
        val NONE = SubPixelShift(0f, 0f)
        fun of(shift: PixelShift) = SubPixelShift(shift.dx.toFloat(), shift.dy.toFloat())
    }
}

/**
 * Refines a gyro-predicted alignment between two burst frames by block matching on their luma guides.
 *
 * The gyro (see `GyroAlignment`) gets a hand-held burst within a few guide pixels but not exactly: it ignores
 * parallax, roll, rolling shutter and timestamp skew. A summed-squared-difference search around the gyro's
 * guess fixes the rest, in two levels so a 12 MP burst's guide (about 1000 x 750) stays cheap:
 *
 * 1. both guides are halved and every integer shift within `radius / 2` of the gyro guess is scored;
 * 2. the winner is doubled and its 3 x 3 neighbourhood on the full guide is scored, then a parabola through
 *    the three costs along each axis gives the fractional part (a guide pixel is 4 full-resolution pixels, so
 *    integer-only matching would leave up to 2 px of error and blur the average).
 *
 * The cost per pixel is `min(d^2, TRUNCATION^2)`: a moving subject or a clipped highlight costs at most a
 * constant instead of dominating, so the (static) majority of the frame decides. Ties go to the candidate
 * nearest the gyro's guess, so a textureless frame (night sky) keeps the gyro's answer.
 *
 * Pure functions over row-major luma planes in 0..1.
 */
object BurstAlign {
    /** The search radius around the gyro's guess, in guide pixels (the spec's "+/- 8"). */
    const val SEARCH_RADIUS = 8

    /** Per-pixel cost ceiling, in luma units (0..1): beyond this a pixel is "different", however different. */
    private const val TRUNCATION = 0.15f

    /** A candidate must beat the incumbent by this factor to replace it (ties keep the nearer-to-gyro one). */
    private const val IMPROVEMENT_FACTOR = 0.995f

    /** Below this side length matching is meaningless; the initial guess is returned as it is. */
    private const val MIN_SIDE = 16

    /**
     * The shift of [frame] relative to [reference] (both [width] x [height]), searched within [radius] guide
     * pixels of [initial].
     */
    fun refine(
        reference: FloatArray,
        frame: FloatArray,
        width: Int,
        height: Int,
        initial: SubPixelShift = SubPixelShift.NONE,
        radius: Int = SEARCH_RADIUS,
    ): SubPixelShift {
        require(reference.size >= width * height && frame.size >= width * height) { "plane smaller than ${width}x$height" }
        if (width < MIN_SIDE || height < MIN_SIDE) return initial
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        val coarseRef = halve(reference, width, height)
        val coarseFrame = halve(frame, width, height)
        val coarse = searchInteger(
            coarseRef, coarseFrame, cw, ch,
            (initial.dx / 2f).roundToInt(), (initial.dy / 2f).roundToInt(), (radius + 1) / 2,
        )
        // Fine level: the gyro's own guess (the coarse level only sees it to the nearest 2 px), then the
        // 3 x 3 around the doubled coarse winner; a candidate must beat the incumbent to replace it.
        var ix = initial.dx.roundToInt()
        var iy = initial.dy.roundToInt()
        var here = cost(reference, frame, width, height, ix, iy)
        val centreX = coarse.first * 2
        val centreY = coarse.second * 2
        for (dy in 0..2) for (dx in 0..2) {
            val x = centreX + dx - 1
            val y = centreY + dy - 1
            if (x == ix && y == iy) continue
            val c = cost(reference, frame, width, height, x, y)
            if (c < here * IMPROVEMENT_FACTOR) {
                here = c
                ix = x
                iy = y
            }
        }
        // Parabola through the neighbours of the winner.
        val fx = parabola(cost(reference, frame, width, height, ix - 1, iy), here, cost(reference, frame, width, height, ix + 1, iy))
        val fy = parabola(cost(reference, frame, width, height, ix, iy - 1), here, cost(reference, frame, width, height, ix, iy + 1))
        return SubPixelShift(ix + fx, iy + fy)
    }

    /**
     * [guide] sampled at `(x + shift.dx, y + shift.dy)` with bilinear interpolation, clamped at the edges: the
     * guide as the reference sees it. Returns [guide] itself for a zero shift.
     */
    fun shifted(guide: FloatArray, width: Int, height: Int, shift: SubPixelShift): FloatArray {
        if (shift == SubPixelShift.NONE) return guide
        val x0 = floor(shift.dx).toInt()
        val y0 = floor(shift.dy).toInt()
        val fx = shift.dx - x0
        val fy = shift.dy - y0
        return FloatArray(width * height) { i ->
            val x = i % width + x0
            val y = i / width + y0
            val xa = x.coerceIn(0, width - 1)
            val xb = (x + 1).coerceIn(0, width - 1)
            val ya = y.coerceIn(0, height - 1)
            val yb = (y + 1).coerceIn(0, height - 1)
            (guide[ya * width + xa] * (1 - fx) + guide[ya * width + xb] * fx) * (1 - fy) +
                (guide[yb * width + xa] * (1 - fx) + guide[yb * width + xb] * fx) * fy
        }
    }

    /** 2 x 2 box average (odd trailing row / column replicated). */
    private fun halve(src: FloatArray, w: Int, h: Int): FloatArray {
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        return FloatArray(cw * ch) { i ->
            val x = (i % cw) * 2
            val y = (i / cw) * 2
            val x1 = minOf(x + 1, w - 1)
            val y1 = minOf(y + 1, h - 1)
            (src[y * w + x] + src[y * w + x1] + src[y1 * w + x] + src[y1 * w + x1]) * 0.25f
        }
    }

    /** Best integer shift within [radius] of `(cx, cy)`, scanning outwards ring by ring so ties keep the nearest. */
    private fun searchInteger(ref: FloatArray, frame: FloatArray, w: Int, h: Int, cx: Int, cy: Int, radius: Int): Pair<Int, Int> {
        var bestX = cx
        var bestY = cy
        var bestCost = cost(ref, frame, w, h, cx, cy)
        for (ring in 1..radius) {
            for (dy in -ring..ring) {
                val onEdge = dy == -ring || dy == ring
                var dx = -ring
                while (dx <= ring) {
                    val c = cost(ref, frame, w, h, cx + dx, cy + dy)
                    if (c < bestCost * IMPROVEMENT_FACTOR) {
                        bestCost = c
                        bestX = cx + dx
                        bestY = cy + dy
                    }
                    dx += if (onEdge || dx == ring) 1 else 2 * ring
                }
            }
        }
        return bestX to bestY
    }

    /** Mean truncated squared difference between [ref] at `(x, y)` and [frame] at `(x + dx, y + dy)`; infinite when little overlaps. */
    private fun cost(ref: FloatArray, frame: FloatArray, w: Int, h: Int, dx: Int, dy: Int): Float {
        val xStart = maxOf(0, -dx)
        val xEnd = minOf(w, w - dx)
        val yStart = maxOf(0, -dy)
        val yEnd = minOf(h, h - dy)
        val count = (xEnd - xStart).toLong() * (yEnd - yStart)
        if (xEnd <= xStart || yEnd <= yStart || count < w.toLong() * h / 4) return Float.MAX_VALUE
        val cap = TRUNCATION * TRUNCATION
        var sum = 0.0
        for (y in yStart until yEnd) {
            val r = y * w
            val f = (y + dy) * w + dx
            var row = 0f
            for (x in xStart until xEnd) {
                val d = frame[f + x] - ref[r + x]
                val sq = d * d
                row += if (sq < cap) sq else cap
            }
            sum += row
        }
        return (sum / count).toFloat()
    }

    /** Offset of a parabola's minimum from the middle sample, in -0.5..0.5; 0 when the three costs are not a clear valley. */
    private fun parabola(before: Float, centre: Float, after: Float): Float {
        if (before == Float.MAX_VALUE || after == Float.MAX_VALUE) return 0f
        val denominator = before - 2f * centre + after
        if (denominator <= 1e-9f) return 0f
        return (0.5f * (before - after) / denominator).coerceIn(-0.5f, 0.5f)
    }
}
