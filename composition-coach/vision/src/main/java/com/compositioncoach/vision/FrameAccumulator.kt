package com.compositioncoach.vision

import com.compositioncoach.vision.merge.Convergence
import com.compositioncoach.vision.merge.ConvergenceTracker
import kotlin.math.sqrt

/** How a Motion stack combines its frames. */
enum class StackBlend {
    /** Per-channel mean: moving water turns to silk, noise drops by ~sqrt(n). */
    AVERAGE,

    /** Per-channel maximum ("lighten"): moving lights draw trails, the static scene stays as one frame. */
    LIGHTEN,

    /** Per-pixel median (by luma): anything present in fewer than half the frames (passers-by) disappears. */
    MEDIAN,
}

/**
 * A finished Motion stack: [width] x [height] opaque ARGB pixels, row-major, in the display-portrait
 * orientation the frames were accumulated in.
 *
 * @param frames how many frames went into the result.
 * @param uprightRotationDegrees clockwise rotation (0/90/180/270) still to apply to make the image physically
 *   upright for the hold at the start of the stack (0 for a portrait hold). Rotations by multiples of 90 are
 *   lossless, so the saver applies it to the pixels rather than via EXIF.
 * @param framesOffered how many frames reached the accumulator (MEDIAN keeps only a decimated subset, [frames]).
 * @param temporalStdDev per-cell standard deviation (0..1 luma) of the frames over time on a grid
 *   [guideWidth] x [guideHeight] cells of [guideScale] pixels, in the same orientation as [rgb]; low = static.
 *   Null when fewer than two frames were stacked. Drives the static-region restore (`StaticRestore`).
 */
class AccumulatedImage(
    val width: Int,
    val height: Int,
    val rgb: IntArray,
    val frames: Int,
    val uprightRotationDegrees: Int = 0,
    val framesOffered: Int = frames,
    val temporalStdDev: FloatArray? = null,
    val guideScale: Int = FrameAccumulator.GUIDE_SCALE,
) {
    /** Width of the [temporalStdDev] grid in cells. */
    val guideWidth: Int get() = (width + guideScale - 1) / guideScale

    /** Height of the [temporalStdDev] grid in cells. */
    val guideHeight: Int get() = (height + guideScale - 1) / guideScale

    init {
        require(rgb.size == width * height) { "pixel count mismatch" }
        require(temporalStdDev == null || temporalStdDev.size == guideWidth * guideHeight) { "guide size mismatch" }
    }
}

/**
 * Pure, allocation-bounded frame stacker for the Motion outcomes (Silky water, Light trails, Clear the crowd).
 * Frames are opaque ARGB `IntArray`s of one fixed size; 8 bits per channel throughout.
 *
 * ## Alignment
 * Each frame may come with an integer content shift `(shiftX, shiftY)`: how far the scene moved in this frame
 * relative to the first one (positive = right/down). The frame is sampled at `(x + shiftX, y + shiftY)`, which
 * puts its content back where it was in the first frame; samples that fall outside are clamped to the nearest
 * edge pixel (a thin smear at most [maxShiftPx] wide, never a black border). Shifts are clamped to
 * [maxShiftPx]. The shift comes from [GyroAlignment] - rotation-only alignment: hand *translation* (parallax)
 * and in-plane roll are not corrected, which is fine for distant scenes and a few seconds' hold.
 *
 * ## Memory
 * AVERAGE keeps three `IntArray` channel sums, LIGHTEN one packed `IntArray`. MEDIAN must keep frames: at most
 * [MAX_MEDIAN_FRAMES] of them, further limited to [medianBudgetBytes] (64 MB by default: 13 frames at
 * 1280x960, never fewer than [MIN_MEDIAN_FRAMES]). The hold's length is not known in advance (it ends on
 * convergence, see [converged]), so the kept frames are decimated *adaptively*: every `stride`-th offered
 * frame is kept, and whenever the store is full every other kept frame is dropped and the stride doubles.
 * The kept frames therefore always span the whole hold evenly, between half the capacity and all of it,
 * whether the stream runs at 8 or 30 fps and whether the hold lasts 4 s or 15 s.
 *
 * ## Convergence and static regions
 * A [ConvergenceTracker] decides when more frames no longer change the result ([converged],
 * [convergence]; rules in [Convergence]). A guide-scale (1/[GUIDE_SCALE]) running mean and variance of luma per
 * cell feeds both the AVERAGE convergence check (the mean *is* the average image's luma) and
 * [AccumulatedImage.temporalStdDev], the static-region mask for restoring detail from the full-resolution still.
 */
class FrameAccumulator(
    val mode: StackBlend,
    val width: Int,
    val height: Int,
    /** Kept for source compatibility; the MEDIAN decimation adapts to the hold, so this is only a hint. */
    @Suppress("UNUSED_PARAMETER") expectedFrames: Int = 0,
    val maxShiftPx: Int = Int.MAX_VALUE,
    medianBudgetBytes: Long = DEFAULT_MEDIAN_BUDGET_BYTES,
) {
    private val n = width * height

    /** Frames that reached the accumulator (including MEDIAN frames skipped by the stride). */
    var framesOffered: Int = 0
        private set

    /** Frames actually contributing to the result (for MEDIAN: the frames currently kept). */
    var framesUsed: Int = 0
        private set

    private val sums: IntArray? = if (mode == StackBlend.AVERAGE) IntArray(n * 3) else null
    private val lightest: IntArray? = if (mode == StackBlend.LIGHTEN) IntArray(n) else null

    /** Store capacity for MEDIAN (0 otherwise). */
    val medianCapacity: Int = if (mode == StackBlend.MEDIAN) {
        (medianBudgetBytes / (n.toLong() * 4)).toInt().coerceIn(MIN_MEDIAN_FRAMES, MAX_MEDIAN_FRAMES)
    } else {
        0
    }
    private val kept = ArrayList<IntArray>()
    private var stride = 1

    /** Reused for the aligned copy of AVERAGE/LIGHTEN frames (consumed at once); MEDIAN keeps its own copies. */
    private var shiftScratch: IntArray? = null

    // Guide-scale temporal statistics (Welford), one cell per GUIDE_SCALE x GUIDE_SCALE block.
    private val guideW = (width + GUIDE_SCALE - 1) / GUIDE_SCALE
    private val guideH = (height + GUIDE_SCALE - 1) / GUIDE_SCALE
    private val guideMean = FloatArray(guideW * guideH)
    private val guideM2 = FloatArray(guideW * guideH)
    private var guideFrames = 0

    private val tracker = ConvergenceTracker(mode)

    /** True once the stack has converged (see [Convergence]); sticky. */
    val converged: Boolean get() = tracker.converged

    /** 0..1: how close the stack is to converging; 1 = converged. */
    val convergence: Float get() = tracker.estimate

    init {
        require(width > 0 && height > 0) { "empty frame" }
    }

    /**
     * Adds one frame. Returns false (and ignores it) when its size is wrong; true otherwise, including when the
     * MEDIAN stride skips it.
     */
    fun add(argb: IntArray, shiftX: Int = 0, shiftY: Int = 0): Boolean {
        if (argb.size != n) return false
        val index = framesOffered++
        if (mode == StackBlend.MEDIAN) {
            if (index % stride != 0) {
                checkpoint()
                return true
            }
            if (kept.size >= medianCapacity) {
                decimate()
                if (index % stride != 0) {
                    checkpoint()
                    return true
                }
            }
        }
        val sx = shiftX.coerceIn(-maxShiftPx, maxShiftPx)
        val sy = shiftY.coerceIn(-maxShiftPx, maxShiftPx)
        val aligned = when {
            mode == StackBlend.MEDIAN -> shifted(argb, sx, sy, IntArray(n))
            sx == 0 && sy == 0 -> argb
            else -> shifted(argb, sx, sy, shiftScratch ?: IntArray(n).also { shiftScratch = it })
        }
        when (mode) {
            StackBlend.AVERAGE -> addAverage(aligned)
            StackBlend.LIGHTEN -> addLighten(aligned)
            StackBlend.MEDIAN -> kept += aligned
        }
        framesUsed = if (mode == StackBlend.MEDIAN) kept.size else framesUsed + 1
        updateGuide(aligned)
        checkpoint()
        return true
    }

    /** The stacked image so far, or null before the first frame. MEDIAN does its selection here (O(n * k)). */
    fun result(uprightRotationDegrees: Int = 0): AccumulatedImage? {
        if (framesUsed == 0) return null
        val out = when (mode) {
            StackBlend.AVERAGE -> averageResult()
            StackBlend.LIGHTEN -> lightest!!.copyOf()
            StackBlend.MEDIAN -> medianResult()
        }
        val stdDev = if (guideFrames >= 2) FloatArray(guideMean.size) { sqrt((guideM2[it] / guideFrames).coerceAtLeast(0f)) } else null
        return AccumulatedImage(width, height, out, framesUsed, uprightRotationDegrees, framesOffered, stdDev, GUIDE_SCALE)
    }

    private fun shifted(src: IntArray, sx: Int, sy: Int, out: IntArray): IntArray {
        for (y in 0 until height) {
            val srcRow = (y + sy).coerceIn(0, height - 1) * width
            val row = y * width
            for (x in 0 until width) out[row + x] = src[srcRow + (x + sx).coerceIn(0, width - 1)]
        }
        return out
    }

    /** MEDIAN store full: drop every other kept frame and double the stride, so the kept set still spans the hold. */
    private fun decimate() {
        val survivors = kept.filterIndexed { i, _ -> i % 2 == 0 }
        kept.clear()
        kept.addAll(survivors)
        stride *= 2
    }

    private fun addAverage(px: IntArray) {
        val s = sums!!
        for (i in 0 until n) {
            val c = px[i]
            val j = i * 3
            s[j] += (c shr 16) and 0xFF
            s[j + 1] += (c shr 8) and 0xFF
            s[j + 2] += c and 0xFF
        }
    }

    private fun addLighten(px: IntArray) {
        val l = lightest!!
        if (framesUsed == 0) {
            var clipped = 0
            for (i in 0 until n) {
                val c = px[i]
                l[i] = c or OPAQUE
                if (lumaLevel((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) >= Convergence.CLIP_LUMA) clipped++
            }
            tracker.onLightenFrame(1f, clipped.toFloat() / n)
            return
        }
        var raised = 0
        var clipped = 0
        for (i in 0 until n) {
            val c = px[i]
            val o = l[i]
            val cr = (c shr 16) and 0xFF
            val cg = (c shr 8) and 0xFF
            val cb = c and 0xFF
            val pr = (o shr 16) and 0xFF
            val pg = (o shr 8) and 0xFF
            val pb = o and 0xFF
            if (maxOf(cr - pr, cg - pg, cb - pb) >= Convergence.RAISE_THRESHOLD_LEVELS) raised++
            val r = maxOf(cr, pr)
            val g = maxOf(cg, pg)
            val b = maxOf(cb, pb)
            if (lumaLevel(r, g, b) >= Convergence.CLIP_LUMA) clipped++
            l[i] = OPAQUE or (r shl 16) or (g shl 8) or b
        }
        tracker.onLightenFrame(raised.toFloat() / n, clipped.toFloat() / n)
    }

    private fun lumaLevel(r: Int, g: Int, b: Int): Int = (77 * r + 150 * g + 29 * b) shr 8

    private fun lumaWeighted(c: Int): Int = 77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF)

    /** Folds the frame's guide-scale luma (four samples per cell) into the per-cell running mean and variance. */
    private fun updateGuide(px: IntArray) {
        guideFrames++
        val k = guideFrames
        val half = GUIDE_SCALE / 2
        for (gy in 0 until guideH) {
            val rowA = (gy * GUIDE_SCALE).coerceAtMost(height - 1) * width
            val rowB = (gy * GUIDE_SCALE + half).coerceAtMost(height - 1) * width
            for (gx in 0 until guideW) {
                val xa = (gx * GUIDE_SCALE).coerceAtMost(width - 1)
                val xb = (gx * GUIDE_SCALE + half).coerceAtMost(width - 1)
                val sum = lumaWeighted(px[rowA + xa]) + lumaWeighted(px[rowA + xb]) + lumaWeighted(px[rowB + xa]) + lumaWeighted(px[rowB + xb])
                val l = sum * LUMA_NORM
                val j = gy * guideW + gx
                val delta = l - guideMean[j]
                guideMean[j] += delta / k
                guideM2[j] += delta * (l - guideMean[j])
            }
        }
    }

    /** Takes the convergence sample when one is due (AVERAGE: the guide mean; MEDIAN: the sampled median luma). */
    private fun checkpoint() {
        if (!tracker.checkpointDue(framesOffered)) return
        when (mode) {
            StackBlend.AVERAGE -> if (guideFrames > 0) tracker.onCheckpoint(guideMean.copyOf())
            StackBlend.MEDIAN -> if (kept.size >= MIN_MEDIAN_SAMPLE_FRAMES) tracker.onCheckpoint(sampledMedianLuma())
            StackBlend.LIGHTEN -> Unit
        }
    }

    /** Median luma (0..1) of the kept frames at every [MEDIAN_SAMPLE_STEP]-th pixel in both directions. */
    private fun sampledMedianLuma(): FloatArray {
        val k = kept.size
        val cols = (width + MEDIAN_SAMPLE_STEP - 1) / MEDIAN_SAMPLE_STEP
        val rows = (height + MEDIAN_SAMPLE_STEP - 1) / MEDIAN_SAMPLE_STEP
        val lumas = IntArray(k)
        val out = FloatArray(cols * rows)
        for (ry in 0 until rows) for (rx in 0 until cols) {
            val i = ry * MEDIAN_SAMPLE_STEP * width + rx * MEDIAN_SAMPLE_STEP
            for (f in 0 until k) lumas[f] = lumaWeighted(kept[f][i])
            insertionSort(lumas, k)
            out[ry * cols + rx] = lumas[k / 2] / (255f * 256f)
        }
        return out
    }

    private fun insertionSort(a: IntArray, size: Int) {
        for (i in 1 until size) {
            val v = a[i]
            var j = i - 1
            while (j >= 0 && a[j] > v) {
                a[j + 1] = a[j]
                j--
            }
            a[j + 1] = v
        }
    }

    private fun averageResult(): IntArray {
        val s = sums!!
        val k = framesUsed
        val half = k / 2
        return IntArray(n) { i ->
            val j = i * 3
            OPAQUE or (((s[j] + half) / k) shl 16) or (((s[j + 1] + half) / k) shl 8) or ((s[j + 2] + half) / k)
        }
    }

    /**
     * Per pixel, the frame whose luma is the median of the kept frames contributes its whole colour (a
     * per-channel median could mix channels from different frames into a colour that never existed).
     */
    private fun medianResult(): IntArray {
        val frames = kept.toList()
        val k = frames.size
        val lumas = IntArray(k)
        val order = IntArray(k)
        val out = IntArray(n)
        for (i in 0 until n) {
            for (f in 0 until k) {
                lumas[f] = lumaWeighted(frames[f][i])
                order[f] = f
            }
            // Insertion sort of k <= 24 indices by luma: cheaper than a general sort at this size.
            for (a in 1 until k) {
                val idx = order[a]
                var b = a - 1
                while (b >= 0 && lumas[order[b]] > lumas[idx]) {
                    order[b + 1] = order[b]
                    b--
                }
                order[b + 1] = idx
            }
            out[i] = frames[order[k / 2]][i] or OPAQUE
        }
        return out
    }

    companion object {
        const val MAX_MEDIAN_FRAMES = 24

        /** Smallest MEDIAN store: after a decimation at least half of it (3 frames) is still kept. */
        const val MIN_MEDIAN_FRAMES = 6
        const val DEFAULT_MEDIAN_BUDGET_BYTES = 64L * 1024 * 1024

        /** Side of a static-mask / convergence cell in pixels (a quarter of the analysis size per side). */
        const val GUIDE_SCALE = 4

        private const val MEDIAN_SAMPLE_STEP = 4
        private const val MIN_MEDIAN_SAMPLE_FRAMES = 3
        private const val OPAQUE = 0xFF shl 24

        /** Maps the sum of four 8-bit-weighted lumas (4 x 255 x 256) to 0..1. */
        private const val LUMA_NORM = 1f / (4f * 255f * 256f)
    }
}
