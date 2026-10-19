package com.compositioncoach.app.camera

import android.graphics.Bitmap
import com.compositioncoach.composition.model.SubjectMask
import com.compositioncoach.vision.merge.MaskRefine
import kotlin.math.roundToInt

/**
 * The review screen's "Portrait" look when the phone has no vendor Bokeh extension: the subject stays exactly
 * as shot, the background is darkened and softened. Pure pixel maths on ARGB arrays ([applyArgb]), with a
 * [Bitmap] wrapper ([apply]); never touches the saved file (the caller saves a copy via
 * `CaptureRepository.exportProcessed`).
 *
 * ## Steps
 * 1. **Mask.** The still analysis's [SubjectMask] (32x32 person probability in the upright photo) is feathered
 *    on its own grid (two 3x3 box passes, then a smoothstep from 0.3 to 0.7), upscaled bilinearly onto a luma
 *    guide of the photo (long edge at most [MaskRefine.GUIDE_LONG_EDGE] px) and refined there with a guided
 *    filter of radius [MaskRefine.RADIUS] ([MaskRefine.refineEdges]): where the photo has a real edge (hair
 *    against a wall, a shoulder against a window) the mask follows it, and on flat or low-contrast areas it
 *    stays the soft ~1-cell (~3 % of the frame) ramp, never a cut-out outline. The refined alpha is sampled
 *    bilinearly per pixel.
 * 2. **Background.** A half-resolution copy (2x2 averages) is darkened by [DARKEN] and box-blurred twice
 *    (horizontal + vertical running sums, radius 1 % of the full long edge, i.e. 0.5 % at half resolution; two
 *    box passes approximate a Gaussian). Working at half resolution quarters the cost and doubles the reach.
 *    The radius is scaled by `radiusScale`: 1 for the rear camera, [SELFIE_RADIUS_SCALE] (60 %) for a selfie,
 *    where the subject is close and a strong blur would look like a cut-out.
 * 3. **Recomposite** at full resolution: `out = a * original + (1 - a) * mix(original, background, strength)`,
 *    background sampled bilinearly. Where the mask is fully subject (`a = 1`) the original pixel is copied.
 *
 * The blur is depth-free (one radius for the whole background), so a subject standing close to a wall still
 * gets a uniform blur; that is the documented fallback when the phone has no depth map or Bokeh extension.
 *
 * Only offered when the mask covers 8-70 % of the frame ([isApplicable]): smaller is a far-away person whose
 * mask is too coarse, larger leaves almost no background to separate. Limits: the mask is refined to the photo's
 * edges only within the guided filter's reach (about 1.5 % of the long edge), so strands of hair and fingers
 * stay approximate, and a uniform blur is not depth-aware (true lens bokeh grows with distance).
 */
object BackgroundSeparator {
    const val MIN_COVERAGE = 0.08f
    const val MAX_COVERAGE = 0.70f
    const val DARKEN = 0.75f
    /** Blur radius as a fraction of the full-resolution long edge. */
    const val RADIUS_FRACTION = 0.01f

    /** Selfie blur radius relative to the rear camera's (60 %): pass it as `radiusScale` for a front-camera photo. */
    const val SELFIE_RADIUS_SCALE = 0.6f

    /** A refined subject alpha at or above this is treated as fully subject (the original pixel is copied). */
    private const val SUBJECT_OPAQUE = 0.995f

    /** Whether the look makes sense for [mask] (see the class doc). */
    fun isApplicable(mask: SubjectMask?): Boolean = mask != null && mask.coverage() in MIN_COVERAGE..MAX_COVERAGE

    /**
     * Returns a new bitmap with the background separated; [bitmap] is not modified. [radiusScale] scales the
     * blur radius ([SELFIE_RADIUS_SCALE] for a selfie, 1 for the rear camera).
     */
    fun apply(bitmap: Bitmap, mask: SubjectMask, strength: Float = 1f, radiusScale: Float = 1f): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = applyArgb(pixels, w, h, mask, strength, radiusScale)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** The pure core of [apply]: [pixels] (row-major ARGB, [width] x [height]) in, a new array out. */
    fun applyArgb(pixels: IntArray, width: Int, height: Int, mask: SubjectMask, strength: Float, radiusScale: Float = 1f): IntArray {
        require(pixels.size == width * height) { "pixel count mismatch" }
        val s = strength.coerceIn(0f, 1f)
        val guide = MaskRefine.lumaGuide(pixels, width, height)
        val alpha = refinedAlpha(mask, guide)
        val hw = (width + 1) / 2
        val hh = (height + 1) / 2
        val half = halfResolution(pixels, width, height, hw, hh)
        val radius = (RADIUS_FRACTION * radiusScale.coerceAtLeast(0f) * maxOf(width, height) / 2f).roundToInt().coerceAtLeast(1)
        repeat(2) { boxBlur(half, hw, hh, radius) }
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val gy = ((y + 0.5f) / height * guide.height - 0.5f).coerceIn(0f, guide.height - 1f)
            val by = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, hh - 1f)
            for (x in 0 until width) {
                val i = y * width + x
                val gx = ((x + 0.5f) / width * guide.width - 0.5f).coerceIn(0f, guide.width - 1f)
                val a = bilinear(alpha, guide.width, guide.height, gx, gy)
                if (a >= SUBJECT_OPAQUE || s == 0f) {
                    out[i] = pixels[i]
                    continue
                }
                val bx = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, hw - 1f)
                val o = pixels[i]
                val k = (1f - a) * s
                val r = mix((o shr 16) and 0xFF, bilinear(half[2], hw, hh, bx, by), k)
                val g = mix((o shr 8) and 0xFF, bilinear(half[1], hw, hh, bx, by), k)
                val b = mix(o and 0xFF, bilinear(half[0], hw, hh, bx, by), k)
                out[i] = (o and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /** The feathered mask upscaled onto [guide] and refined by [MaskRefine.refineEdges] (see the class doc, step 1). */
    internal fun refinedAlpha(mask: SubjectMask, guide: MaskRefine.Guide): FloatArray {
        val coarse = MaskRefine.resampleGrid(featheredAlpha(mask), mask.gridWidth, mask.gridHeight, guide.width, guide.height)
        return MaskRefine.refineEdges(coarse, guide)
    }

    /** Feathered subject alpha on the mask grid (see the class doc, step 1). */
    internal fun featheredAlpha(mask: SubjectMask): FloatArray {
        val gw = mask.gridWidth
        val gh = mask.gridHeight
        var grid = mask.probability.copyOf()
        repeat(2) {
            val src = grid
            grid = FloatArray(gw * gh) { i ->
                val cx = i % gw
                val cy = i / gw
                var sum = 0f
                for (dy in -1..1) for (dx in -1..1) {
                    sum += src[(cy + dy).coerceIn(0, gh - 1) * gw + (cx + dx).coerceIn(0, gw - 1)]
                }
                sum / 9f
            }
        }
        return FloatArray(gw * gh) { i ->
            val t = ((grid[i] - 0.3f) / 0.4f).coerceIn(0f, 1f)
            t * t * (3f - 2f * t)
        }
    }

    /** Three channel planes (index 2 = red, 1 = green, 0 = blue) at half size, darkened. */
    private fun halfResolution(pixels: IntArray, w: Int, h: Int, hw: Int, hh: Int): Array<FloatArray> {
        val planes = Array(3) { FloatArray(hw * hh) }
        for (y in 0 until hh) for (x in 0 until hw) {
            var r = 0f; var g = 0f; var b = 0f; var n = 0
            for (dy in 0..1) for (dx in 0..1) {
                val sx = 2 * x + dx
                val sy = 2 * y + dy
                if (sx >= w || sy >= h) continue
                val c = pixels[sy * w + sx]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
            }
            val j = y * hw + x
            planes[2][j] = r / n * DARKEN
            planes[1][j] = g / n * DARKEN
            planes[0][j] = b / n * DARKEN
        }
        return planes
    }

    /** One separable box-blur pass (horizontal, then vertical) of radius [r] on each plane, edges clamped. */
    internal fun boxBlur(planes: Array<FloatArray>, w: Int, h: Int, r: Int) {
        val window = 2f * r + 1f
        val line = FloatArray(maxOf(w, h))
        for (p in planes) {
            for (y in 0 until h) blurLine(p, y * w, 1, w, r, window, line)
            for (x in 0 until w) blurLine(p, x, w, h, r, window, line)
        }
    }

    private fun blurLine(p: FloatArray, start: Int, step: Int, n: Int, r: Int, window: Float, line: FloatArray) {
        for (i in 0 until n) line[i] = p[start + i * step]
        var sum = 0f
        for (k in -r..r) sum += line[k.coerceIn(0, n - 1)]
        for (i in 0 until n) {
            p[start + i * step] = sum / window
            sum += line[(i + r + 1).coerceAtMost(n - 1)] - line[(i - r).coerceAtLeast(0)]
        }
    }

    private fun mix(orig: Int, bg: Float, k: Float): Int = (orig + k * (bg - orig)).roundToInt().coerceIn(0, 255)

    private fun bilinear(grid: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = x - x0
        val fy = y - y0
        val top = grid[y0 * w + x0] * (1 - fx) + grid[y0 * w + x1] * fx
        val bottom = grid[y1 * w + x0] * (1 - fx) + grid[y1 * w + x1] * fx
        return top * (1 - fy) + bottom * fy
    }
}
