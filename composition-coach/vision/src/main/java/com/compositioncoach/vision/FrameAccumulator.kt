package com.compositioncoach.vision

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
 */
class AccumulatedImage(
    val width: Int,
    val height: Int,
    val rgb: IntArray,
    val frames: Int,
    val uprightRotationDegrees: Int = 0,
) {
    init {
        require(rgb.size == width * height) { "pixel count mismatch" }
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
 * AVERAGE keeps three `IntArray` channel sums, LIGHTEN one packed `IntArray`. MEDIAN must keep frames: a ring
 * of at most [MAX_MEDIAN_FRAMES] frames, further limited to [medianBudgetBytes] (64 MB by default: 13 frames at
 * 1280x960). To still span the whole hold it keeps every `stride`-th frame, `stride = ceil(expectedFrames /
 * capacity)`; frames beyond that overwrite the oldest.
 */
class FrameAccumulator(
    val mode: StackBlend,
    val width: Int,
    val height: Int,
    expectedFrames: Int,
    val maxShiftPx: Int = Int.MAX_VALUE,
    medianBudgetBytes: Long = DEFAULT_MEDIAN_BUDGET_BYTES,
) {
    private val n = width * height

    /** Frames that reached the accumulator (including MEDIAN frames skipped by the stride). */
    var framesOffered: Int = 0
        private set

    /** Frames actually contributing to the result. */
    var framesUsed: Int = 0
        private set

    private val sums: IntArray? = if (mode == StackBlend.AVERAGE) IntArray(n * 3) else null
    private val lightest: IntArray? = if (mode == StackBlend.LIGHTEN) IntArray(n) else null

    /** Ring capacity for MEDIAN (0 otherwise). */
    val medianCapacity: Int = if (mode == StackBlend.MEDIAN) {
        (medianBudgetBytes / (n.toLong() * 4)).toInt().coerceIn(MIN_MEDIAN_FRAMES, MAX_MEDIAN_FRAMES)
    } else {
        0
    }
    private val ring: Array<IntArray?> = arrayOfNulls(medianCapacity)
    private var ringNext = 0
    private val stride: Int = if (medianCapacity > 0) {
        ((expectedFrames.coerceAtLeast(1) + medianCapacity - 1) / medianCapacity).coerceAtLeast(1)
    } else {
        1
    }

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
        if (mode == StackBlend.MEDIAN && index % stride != 0) return true
        val sx = shiftX.coerceIn(-maxShiftPx, maxShiftPx)
        val sy = shiftY.coerceIn(-maxShiftPx, maxShiftPx)
        val aligned = if (sx == 0 && sy == 0 && mode != StackBlend.MEDIAN) argb else shifted(argb, sx, sy)
        when (mode) {
            StackBlend.AVERAGE -> addAverage(aligned)
            StackBlend.LIGHTEN -> addLighten(aligned)
            StackBlend.MEDIAN -> {
                ring[ringNext] = aligned
                ringNext = (ringNext + 1) % medianCapacity
            }
        }
        framesUsed++
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
        return AccumulatedImage(width, height, out, framesUsed.coerceAtMost(if (mode == StackBlend.MEDIAN) medianCapacity else Int.MAX_VALUE), uprightRotationDegrees)
    }

    private fun shifted(src: IntArray, sx: Int, sy: Int): IntArray {
        val out = IntArray(n)
        for (y in 0 until height) {
            val srcRow = (y + sy).coerceIn(0, height - 1) * width
            val row = y * width
            for (x in 0 until width) out[row + x] = src[srcRow + (x + sx).coerceIn(0, width - 1)]
        }
        return out
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
        val first = framesUsed == 0
        for (i in 0 until n) {
            val c = px[i]
            if (first) {
                l[i] = c or OPAQUE
                continue
            }
            val o = l[i]
            val r = maxOf((c shr 16) and 0xFF, (o shr 16) and 0xFF)
            val g = maxOf((c shr 8) and 0xFF, (o shr 8) and 0xFF)
            val b = maxOf(c and 0xFF, o and 0xFF)
            l[i] = OPAQUE or (r shl 16) or (g shl 8) or b
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
        val frames = ring.filterNotNull()
        val k = frames.size
        val lumas = IntArray(k)
        val order = IntArray(k)
        val out = IntArray(n)
        for (i in 0 until n) {
            for (f in 0 until k) {
                val c = frames[f][i]
                lumas[f] = 77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF)
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
        const val MIN_MEDIAN_FRAMES = 3
        const val DEFAULT_MEDIAN_BUDGET_BYTES = 64L * 1024 * 1024
        private const val OPAQUE = 0xFF shl 24
    }
}
