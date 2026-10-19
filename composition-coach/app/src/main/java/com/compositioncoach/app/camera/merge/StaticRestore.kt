package com.compositioncoach.app.camera.merge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.compositioncoach.app.camera.CaptureRepository
import com.compositioncoach.app.camera.FocusMerge
import com.compositioncoach.app.camera.PhotoCodec
import com.compositioncoach.vision.AccumulatedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Restores full-resolution detail from the Motion hold's companion still where the scene did not move.
 *
 * A Motion stack is built from the 1.2 to 2.8 MP analysis stream, so even the still parts of the scene (the
 * rocks next to Silky water) come out soft and small beside the 12 MP companion. Where the temporal variance
 * measured during the hold says a place was static, the companion's detail is as good a picture of it as the
 * stack is, and sharper. So:
 *
 * 1. **Mask** ([staticWeights]): the accumulator keeps a luma standard deviation over time per guide cell (1/4
 *    of the analysis size). Below [STATIC_LOW] a cell is static (weight 1), above [STATIC_HIGH] it moved
 *    (weight 0), smoothstep between; then eroded by one cell (so detail stops short of a moving edge) and
 *    feathered with two box blurs, so there is no seam where the two sources meet.
 * 2. **Align** ([align]): the companion is taken at a different instant than the first analysis frame, so a
 *    global sub-guide-pixel shift is found by block matching on guide-scale luma (+-[ALIGN_RADIUS] guide
 *    pixels, sum of absolute differences over the static cells, mean removed so the two sources' tone does not
 *    matter). A residual that stays large means the scene differs (another framing, a rebind): no restore.
 * 3. **Blend** ([blendStrip], per horizontal strip of the full-resolution companion): the output is the stack
 *    upscaled bilinearly to the companion's size, plus, weighted by the mask, the companion's own detail (the
 *    companion minus its low-pass at the stack's resolution). Taking only the *detail* means the stack's tone,
 *    colour and noise level stay in charge (the two sources come from different pipelines and differ), while
 *    the static places gain the companion's real fine structure. With a weight of 1 and a stack that is the
 *    companion at low resolution the output is the companion itself.
 *
 * The pure maths works on `IntArray` ARGB and `FloatArray` grids and is unit-tested on synthetic images;
 * [StaticRestorer] below is the Android orchestration (region decoding in strips like `FocusStacker`).
 */
object StaticRestore {
    /** Stack luma standard deviation (0..1, about 4/255) below which a guide cell counts as fully static. */
    const val STATIC_LOW = 0.015f

    /** Stack luma standard deviation (0..1, about 13/255) above which a guide cell counts as moving. */
    const val STATIC_HIGH = 0.05f

    /** Block-matching search radius in guide pixels. */
    const val ALIGN_RADIUS = 8

    /** A mean-removed luma residual (0..1) above this after alignment means the sources do not match. */
    const val MAX_RESIDUAL = 0.08f

    /** Less static area than this fraction of the guide is not worth a restore. */
    const val MIN_STATIC_FRACTION = 0.03f

    /** Fewest static guide cells the alignment needs to be trustworthy. */
    const val MIN_ALIGN_CELLS = 64

    /** The companion and the stack must have the same shape to within this relative difference. */
    const val MAX_ASPECT_ERROR = 0.03f

    private val SHIFTS = intArrayOf(16, 8, 0)

    /** Weight of the companion's detail where the scene is fully static. */
    const val DETAIL_GAIN = 1f

    /** Rows of the full-resolution companion processed at a time. */
    const val STRIP_ROWS = 256

    /** The companion's shift relative to the stack, in guide pixels (sub-pixel), and how well they match. */
    class Alignment(val dx: Float, val dy: Float, val residual: Float)

    /** Smoothstep in [0, 1]. */
    private fun smooth(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /**
     * Static weights (1 = static, 0 = moving) on the guide grid from per-cell luma [stdDev]: thresholds, then
     * erosion by one cell and two box blurs of radius one for the feather.
     */
    fun staticWeights(stdDev: FloatArray, gw: Int, gh: Int): FloatArray {
        require(stdDev.size == gw * gh) { "guide size mismatch" }
        val raw = FloatArray(stdDev.size) { 1f - smooth((stdDev[it] - STATIC_LOW) / (STATIC_HIGH - STATIC_LOW)) }
        return boxBlur(boxBlur(erode(raw, gw, gh), gw, gh), gw, gh)
    }

    private fun erode(src: FloatArray, w: Int, h: Int): FloatArray = FloatArray(w * h) { i ->
        val x = i % w
        val y = i / w
        var m = src[i]
        for (yy in (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(h - 1)) {
            for (xx in (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(w - 1)) m = minOf(m, src[yy * w + xx])
        }
        m
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int): FloatArray = FloatArray(w * h) { i ->
        val x = i % w
        val y = i / w
        var sum = 0f
        var n = 0
        for (yy in (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(h - 1)) {
            for (xx in (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(w - 1)) {
                sum += src[yy * w + xx]
                n++
            }
        }
        sum / n
    }

    /** Mean of [weights] (0..1): the static fraction of the picture. */
    fun staticFraction(weights: FloatArray): Float = if (weights.isEmpty()) 0f else (weights.sum() / weights.size)

    /**
     * Finds where the companion's guide sits in the stack's: the shift `(dx, dy)` in guide pixels such that
     * `stack(x + dx, y + dy)` matches `companion(x, y)`, by sum of absolute differences over the cells where
     * [weights] is above one half, after removing each source's mean. Null when too few static cells remain.
     */
    fun align(stack: FloatArray, companion: FloatArray, weights: FloatArray, gw: Int, gh: Int, radius: Int = ALIGN_RADIUS): Alignment? {
        val cells = ArrayList<Int>()
        var y = radius
        while (y < gh - radius) {
            var x = radius
            while (x < gw - radius) {
                if (weights[y * gw + x] > 0.5f) cells += y * gw + x
                x += 2
            }
            y += 2
        }
        if (cells.size < MIN_ALIGN_CELLS) return null
        var companionMean = 0.0
        for (c in cells) companionMean += companion[c]
        companionMean /= cells.size
        val side = 2 * radius + 1
        val cost = FloatArray(side * side)
        for (sy in -radius..radius) for (sx in -radius..radius) {
            var stackMean = 0.0
            for (c in cells) stackMean += stack[c + sy * gw + sx]
            stackMean /= cells.size
            var sad = 0.0
            for (c in cells) sad += abs((stack[c + sy * gw + sx] - stackMean) - (companion[c] - companionMean))
            cost[(sy + radius) * side + sx + radius] = (sad / cells.size).toFloat()
        }
        var best = 0
        for (i in cost.indices) if (cost[i] < cost[best]) best = i
        val bx = best % side
        val by = best / side
        fun refine(minus: Float, mid: Float, plus: Float): Float {
            val denominator = minus - 2f * mid + plus
            return if (denominator > 1e-9f) ((minus - plus) / (2f * denominator)).coerceIn(-0.5f, 0.5f) else 0f
        }
        val fx = if (bx in 1 until side - 1) refine(cost[by * side + bx - 1], cost[best], cost[by * side + bx + 1]) else 0f
        val fy = if (by in 1 until side - 1) refine(cost[(by - 1) * side + bx], cost[best], cost[(by + 1) * side + bx]) else 0f
        return Alignment(bx - radius + fx, by - radius + fy, cost[best])
    }

    /** Whether [alignment] says the two sources show the same scene. */
    fun matches(alignment: Alignment?): Boolean = alignment != null && alignment.residual <= MAX_RESIDUAL

    /** Area-averaged luma (0..1) of an ARGB image resampled onto a [gw] x [gh] grid covering the same view. */
    fun resampleLuma(argb: IntArray, width: Int, height: Int, gw: Int, gh: Int): FloatArray {
        val sums = FloatArray(gw * gh)
        val counts = IntArray(gw * gh)
        for (y in 0 until height) {
            val gy = (y.toLong() * gh / height).toInt()
            for (x in 0 until width) {
                val gx = (x.toLong() * gw / width).toInt()
                val c = argb[y * width + x]
                sums[gy * gw + gx] += (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
                counts[gy * gw + gx]++
            }
        }
        return FloatArray(gw * gh) { sums[it] / counts[it].coerceAtLeast(1) }
    }

    /** Rotates a [w] x [h] ARGB image clockwise by a multiple of 90 degrees; returns the pixels and the new size. */
    fun rotate(px: IntArray, w: Int, h: Int, degrees: Int): Triple<IntArray, Int, Int> {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return Triple(px, w, h)
        val dw = if (d == 180) w else h
        val dh = if (d == 180) h else w
        val out = IntArray(w * h)
        for (y in 0 until dh) for (x in 0 until dw) out[y * dw + x] = px[sourceIndex(x, y, w, h, d)]
        return Triple(out, dw, dh)
    }

    /** [rotate] for a grid of floats (the variance map follows the stack's rotation). */
    fun rotateGrid(grid: FloatArray, w: Int, h: Int, degrees: Int): Triple<FloatArray, Int, Int> {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return Triple(grid, w, h)
        val dw = if (d == 180) w else h
        val dh = if (d == 180) h else w
        val out = FloatArray(w * h)
        for (y in 0 until dh) for (x in 0 until dw) out[y * dw + x] = grid[sourceIndex(x, y, w, h, d)]
        return Triple(out, dw, dh)
    }

    /** Index in the source ([w] x [h]) of the pixel that lands at ([x], [y]) after a clockwise rotation by [d] (90, 180 or 270). */
    private fun sourceIndex(x: Int, y: Int, w: Int, h: Int, d: Int): Int = when (d) {
        90 -> (h - 1 - x) * w + y
        180 -> (h - 1 - y) * w + (w - 1 - x)
        else -> x * w + (w - 1 - y)
    }

    /** Per-channel box low-pass of window `(2r + 1)^2` (edges clamp to the available pixels). */
    fun lowPass(px: IntArray, w: Int, h: Int, r: Int): IntArray {
        val out = IntArray(w * h)
        val horizontal = IntArray(w * h)
        val prefix = IntArray(w + 1)
        for (channel in 0 until 3) {
            val shift = 16 - 8 * channel
            for (y in 0 until h) {
                for (x in 0 until w) prefix[x + 1] = prefix[x] + ((px[y * w + x] shr shift) and 0xFF)
                for (x in 0 until w) {
                    val lo = (x - r).coerceAtLeast(0)
                    val hi = (x + r).coerceAtMost(w - 1)
                    horizontal[y * w + x] = prefix[hi + 1] - prefix[lo]
                }
            }
            for (x in 0 until w) {
                var sum = 0
                var lo = 0
                var hi = -1
                for (y in 0 until h) {
                    val wantLo = (y - r).coerceAtLeast(0)
                    val wantHi = (y + r).coerceAtMost(h - 1)
                    while (hi < wantHi) {
                        hi++
                        sum += horizontal[hi * w + x]
                    }
                    while (lo < wantLo) {
                        sum -= horizontal[lo * w + x]
                        lo++
                    }
                    val count = (wantHi - wantLo + 1) * ((x + r).coerceAtMost(w - 1) - (x - r).coerceAtLeast(0) + 1)
                    out[y * w + x] = out[y * w + x] or (((sum + count / 2) / count) shl shift)
                }
            }
        }
        return out.also { for (i in it.indices) it[i] = it[i] or (0xFF shl 24) }
    }

    /**
     * The low-pass radius that matches a stack [scale] times smaller per side than the companion: the box that
     * averages about one stack pixel's worth of companion pixels.
     */
    fun detailRadius(scale: Float): Int = ((scale - 1f) / 2f).roundToInt().coerceAtLeast(1)

    /**
     * Blends output rows [y0] until [y0] + [rows] of the [outW] x [outH] result into [out] (`outW * rows`).
     *
     * @param stack the upright stack, [stackW] x [stackH].
     * @param weights static weights on the guide grid ([gw] x [gh] cells of [guideScale] stack pixels).
     * @param companion rows [companionTop] until [companionTop] + [companionRows] of the upright companion
     *   (`outW` wide, at least the rows [y0] until [y0] + [rows] plus `radius` more each side where they exist).
     * @param alignment where the companion's view sits in the stack's, from [align] (guide pixels).
     * @param radius the low-pass radius for the companion's detail, from [detailRadius].
     */
    fun blendStrip(
        stack: IntArray,
        stackW: Int,
        stackH: Int,
        weights: FloatArray,
        gw: Int,
        gh: Int,
        guideScale: Int,
        companion: IntArray,
        companionTop: Int,
        companionRows: Int,
        outW: Int,
        outH: Int,
        alignment: Alignment,
        radius: Int,
        y0: Int,
        rows: Int,
        out: IntArray,
    ) {
        val low = lowPass(companion, outW, companionRows, radius)
        val scaleX = stackW.toFloat() / outW
        val scaleY = stackH.toFloat() / outH
        val offsetX = alignment.dx * guideScale
        val offsetY = alignment.dy * guideScale
        for (ry in 0 until rows) {
            val y = y0 + ry
            val sy = ((y + 0.5f) * scaleY - 0.5f + offsetY).coerceIn(0f, stackH - 1f)
            val gy = ((sy + 0.5f) / guideScale - 0.5f).coerceIn(0f, gh - 1f)
            val cy = (y - companionTop).coerceIn(0, companionRows - 1)
            for (x in 0 until outW) {
                val sx = ((x + 0.5f) * scaleX - 0.5f + offsetX).coerceIn(0f, stackW - 1f)
                val gx = ((sx + 0.5f) / guideScale - 0.5f).coerceIn(0f, gw - 1f)
                val w = bilinear(weights, gw, gh, gx, gy) * DETAIL_GAIN
                val c = companion[cy * outW + x]
                val l = low[cy * outW + x]
                val s = sampleStack(stack, stackW, stackH, sx, sy)
                var result = 0xFF shl 24
                for (shift in SHIFTS) {
                    val base = (s shr shift) and 0xFF
                    val detail = ((c shr shift) and 0xFF) - ((l shr shift) and 0xFF)
                    result = result or ((base + w * detail).roundToInt().coerceIn(0, 255) shl shift)
                }
                out[ry * outW + x] = result
            }
        }
    }

    /** Bilinear sample of the ARGB [stack] at ([x], [y]); the channels are interpolated separately and packed opaque. */
    private fun sampleStack(stack: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = x - x0
        val fy = y - y0
        val a = stack[y0 * w + x0]
        val b = stack[y0 * w + x1]
        val c = stack[y1 * w + x0]
        val d = stack[y1 * w + x1]
        var result = 0xFF shl 24
        for (shift in SHIFTS) {
            val top = ((a shr shift) and 0xFF) * (1 - fx) + ((b shr shift) and 0xFF) * fx
            val bottom = ((c shr shift) and 0xFF) * (1 - fx) + ((d shr shift) and 0xFF) * fx
            result = result or ((top * (1 - fy) + bottom * fy).roundToInt().coerceIn(0, 255) shl shift)
        }
        return result
    }

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

    /** Whole-image convenience over [blendStrip] (tests, small images). */
    fun blend(
        stack: IntArray,
        stackW: Int,
        stackH: Int,
        weights: FloatArray,
        gw: Int,
        gh: Int,
        guideScale: Int,
        companion: IntArray,
        outW: Int,
        outH: Int,
        alignment: Alignment = Alignment(0f, 0f, 0f),
    ): IntArray {
        val scale = outW.toFloat() / stackW
        val out = IntArray(outW * outH)
        blendStrip(stack, stackW, stackH, weights, gw, gh, guideScale, companion, 0, outH, outW, outH, alignment, detailRadius(scale), 0, outH, out)
        return out
    }
}

/**
 * The Android half of [StaticRestore]: reads the companion still, checks it shows the same scene as the stack,
 * and assembles the restored picture strip by strip with [BitmapRegionDecoder] (a 12 MP companion is never
 * decoded whole next to the output; peak memory is the output bitmap plus the 2.8 MP stack and a few strips).
 */
class StaticRestorer(private val repository: CaptureRepository) {

    /**
     * The restored, upright picture at the companion's resolution, or null when there is nothing to restore
     * (no variance map, almost no static area, a companion that is flipped, smaller than the stack, framed
     * differently or too large) in which case the caller saves the plain stack. Throws only on cancellation or
     * an error such as `OutOfMemoryError`, which the caller treats as "keep the plain stack".
     */
    suspend fun restore(image: AccumulatedImage, companion: Uri): Bitmap? = withContext(Dispatchers.Default) {
        val stdDev = image.temporalStdDev ?: return@withContext null
        val rotation = when (PhotoCodec.exifOrientation(repository.context.contentResolver, companion)) {
            ExifInterface.ORIENTATION_NORMAL -> 0
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> return@withContext null // mirrored (front camera): not worth a transposed region decode
        }
        val (stack, stackW, stackH) = StaticRestore.rotate(image.rgb, image.width, image.height, image.uprightRotationDegrees)
        val (grid, gw, gh) = StaticRestore.rotateGrid(stdDev, image.guideWidth, image.guideHeight, image.uprightRotationDegrees)
        val weights = StaticRestore.staticWeights(grid, gw, gh)
        if (StaticRestore.staticFraction(weights) < StaticRestore.MIN_STATIC_FRACTION) return@withContext null

        val bytes = repository.readBytes(companion)
        val decoder = regionDecoder(bytes)
        try {
            val rawW = decoder.width
            val rawH = decoder.height
            val outW = if (rotation == 90 || rotation == 270) rawH else rawW
            val outH = if (rotation == 90 || rotation == 270) rawW else rawH
            if (outW.toLong() * outH > MAX_OUTPUT_PIXELS || outW < stackW || outH < stackH) return@withContext null
            val aspectError = abs((outW.toFloat() / outH) / (stackW.toFloat() / stackH) - 1f)
            if (aspectError > StaticRestore.MAX_ASPECT_ERROR) return@withContext null

            val stackGuide = FocusMerge.lumaGuide(stack, stackW, stackH, image.guideScale)
            val guideLongEdge = maxOf(gw, gh)
            val small = repository.decodeForAnalysis(companion, guideLongEdge) ?: return@withContext null
            val smallPixels = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
            val companionGuide = StaticRestore.resampleLuma(smallPixels, small.width, small.height, gw, gh)
            small.recycle()
            val alignment = StaticRestore.align(stackGuide.first, companionGuide, weights, gw, gh)
            if (!StaticRestore.matches(alignment)) {
                Log.i(TAG, "Static restore skipped: ${if (alignment == null) "too little static area" else "residual ${alignment.residual}"}")
                return@withContext null
            }
            alignment!!

            val radius = StaticRestore.detailRadius(outW.toFloat() / stackW)
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            try {
                val outRows = IntArray(outW * StaticRestore.STRIP_ROWS)
                var y0 = 0
                while (y0 < outH) {
                    val rows = minOf(StaticRestore.STRIP_ROWS, outH - y0)
                    val top = (y0 - radius).coerceAtLeast(0)
                    val bottom = (y0 + rows + radius).coerceAtMost(outH)
                    val strip = decodeUprightRows(decoder, rawW, rawH, rotation, top, bottom)
                    StaticRestore.blendStrip(
                        stack, stackW, stackH, weights, gw, gh, image.guideScale,
                        strip, top, bottom - top, outW, outH, alignment, radius, y0, rows, outRows,
                    )
                    out.setPixels(outRows, 0, outW, 0, y0, outW, rows)
                    y0 += rows
                }
                out
            } catch (t: Throwable) {
                out.recycle()
                throw t
            }
        } finally {
            decoder.recycle()
        }
    }

    /** Upright rows [top] until [bottom] of the companion as ARGB, decoded from the matching raw region. */
    private fun decodeUprightRows(decoder: BitmapRegionDecoder, rawW: Int, rawH: Int, rotation: Int, top: Int, bottom: Int): IntArray {
        val region = when (rotation) {
            90 -> Rect(top, 0, bottom, rawH)
            180 -> Rect(0, rawH - bottom, rawW, rawH - top)
            270 -> Rect(rawW - bottom, 0, rawW - top, rawH)
            else -> Rect(0, top, rawW, bottom)
        }
        val decoded = decoder.decodeRegion(region, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: error("Region decode failed")
        val upright = PhotoCodec.rotateClockwise(decoded, rotation)
        val pixels = IntArray(upright.width * upright.height).also { upright.getPixels(it, 0, upright.width, 0, 0, upright.width, upright.height) }
        upright.recycle()
        return pixels
    }

    private fun regionDecoder(bytes: ByteArray): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        } ?: error("No region decoder")

    companion object {
        private const val TAG = "StaticRestore"

        /** A bigger companion (a 50 MP sensor would need a 200 MB output bitmap) keeps the plain stack. */
        const val MAX_OUTPUT_PIXELS = 24_000_000L
    }
}
