package com.compositioncoach.vision

import com.compositioncoach.composition.model.DetectedLine
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.NormalizedPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Computes [ImageStatistics] from a camera frame's Y (luminance) plane. Pure Kotlin, no Android
 * types, so it is directly unit-testable with synthetic luma arrays.
 *
 * ## Pipeline
 * 1. Backward-sample the Y plane into a small, square, **upright** intermediate buffer
 *    ([EDGE_SAMPLE_SIZE] x [EDGE_SAMPLE_SIZE], luminance 0..1). "Backward" means: for every pixel of
 *    the *output* (upright) buffer we ask [FrameCoordinateMapper.normalizedPointToSensor] where that
 *    pixel comes from in the original sensor buffer, then sample luma there (nearest-neighbour) —
 *    this handles rotation, the analysis crop rect, and front-camera mirroring in one place and
 *    avoids scatter artifacts.
 * 2. Run a 3x3 Sobel filter over that buffer to get per-pixel gradient components (Gx, Gy) and
 *    magnitude, normalized 0..1 (see [MAX_GRADIENT] for the normalization constant).
 * 3. Block-average both the luminance and the edge-magnitude buffers down to the small output grid
 *    (default 24x24) requested by [ImageStatistics].
 * 4. Derive meanLuminance/contrast (mean/stddev over the intermediate buffer, more precise than the
 *    coarser output grid), horizontal/vertical symmetry, an experimental horizon estimate, and a
 *    handful of experimental "dominant lines".
 *
 * ## Symmetry scoring
 * `horizontalSymmetry` starts from `1 - meanAbsDiff(L(x,y), L(w-1-x,y))` over the grid, but a
 * perfectly uniform, featureless image (a blank wall) would trivially score 1.0 ("perfectly
 * symmetric") despite having no real content to be symmetric *about* — that's not useful signal.
 * To avoid claiming a blank wall is "perfectly symmetric", the raw score is blended towards a
 * neutral 0.5 in proportion to how much edge content the frame has:
 * `confidence = min(1, meanEdgeDensity / EDGE_CONFIDENCE_SATURATION)`,
 * `score = 0.5 + confidence * (rawScore - 0.5)`. Edges are sparse in real photos (most of a frame is
 * comparatively flat), so confidence saturates to 1 well before the mean reaches anywhere near 1 —
 * otherwise almost every real frame would wash out to ~0.5. A frame with a couple of genuinely
 * (a)symmetric features reports close to its raw score; a blank wall (~0 edge content everywhere)
 * reports ~0.5 ("no information") rather than a false 1.0. Vertical symmetry uses the same construction.
 *
 * ## Horizon / dominant lines (experimental)
 * The horizon estimate is a simple heuristic, not a real vanishing-point/Hough-transform detector:
 * find the row with the strongest luminance step between it and the next row (averaged across the
 * whole width), require that step to be consistent in sign across most of the width (else it's
 * probably texture/clutter, not a horizon), then fit a line through per-column-band local maxima to
 * get a tilt angle. Dominant lines are similarly cheap: columns/rows whose Sobel energy is dominated
 * by one gradient axis (a vertical edge has strong Gx, near-zero Gy, and vice-versa) and stands out
 * from the surrounding columns/rows become candidate line segments. Both are best-effort; an empty/
 * null result when confidence is low is expected and correct, not a bug.
 */
object ImageStatisticsComputer {
    /** Default output grid resolution requested by the composition engine (~24x24 cells). */
    const val DEFAULT_GRID_SIZE = 24

    /** Resolution of the intermediate upright buffer used for gradient computation. */
    const val EDGE_SAMPLE_SIZE = 96

    // Max possible |Sobel response| for luminance in 0..1: each kernel's weights sum to 4 in
    // magnitude (1+2+1), achieved when one side of the 3x3 window is all 0 and the other all 1.
    private const val MAX_SOBEL_COMPONENT = 4f
    private val MAX_GRADIENT = hypot(MAX_SOBEL_COMPONENT, MAX_SOBEL_COMPONENT)

    /** Frame-wide mean edge density at which symmetry-score confidence saturates to 1 (see [symmetryScore]). */
    private const val EDGE_CONFIDENCE_SATURATION = 0.06

    private const val HORIZON_NOISE_FLOOR = 0.035f
    private const val HORIZON_BAND_COUNT = 6
    private const val HORIZON_SIGN_AGREEMENT_THRESHOLD = 0.6f
    private const val LINE_ENERGY_THRESHOLD = 0.16f
    private const val MAX_DOMINANT_LINES = 4

    /**
     * @param luma the Y-plane bytes (full plane, e.g. `imageProxy.planes[0].buffer`).
     * @param width / height sensor buffer dimensions (must match what [mapper] was built with).
     * @param rowStride / pixelStride Y-plane strides, as reported by `Image.Plane`.
     * @param mapper describes rotation, crop, and mirroring for this frame; also gives sensor size.
     * @param gridWidth / gridHeight output grid resolution (defaults to ~24x24 per the composition contract).
     */
    fun compute(
        luma: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        mapper: FrameCoordinateMapper,
        gridWidth: Int = DEFAULT_GRID_SIZE,
        gridHeight: Int = DEFAULT_GRID_SIZE,
    ): ImageStatistics {
        val size = EDGE_SAMPLE_SIZE
        val small = FloatArray(size * size)
        for (uy in 0 until size) {
            val ny = (uy + 0.5f) / size
            for (ux in 0 until size) {
                val nx = (ux + 0.5f) / size
                val (sx, sy) = mapper.normalizedPointToSensor(nx, ny)
                small[uy * size + ux] = sampleLuma(luma, sx, sy, width, height, rowStride, pixelStride)
            }
        }

        val gx = FloatArray(size * size)
        val gy = FloatArray(size * size)
        val gmag = FloatArray(size * size)
        for (y in 0 until size) {
            val ym1 = (y - 1).coerceIn(0, size - 1)
            val yp1 = (y + 1).coerceIn(0, size - 1)
            for (x in 0 until size) {
                val xm1 = (x - 1).coerceIn(0, size - 1)
                val xp1 = (x + 1).coerceIn(0, size - 1)
                val tl = small[ym1 * size + xm1]; val tc = small[ym1 * size + x]; val tr = small[ym1 * size + xp1]
                val ml = small[y * size + xm1]; val mr = small[y * size + xp1]
                val bl = small[yp1 * size + xm1]; val bc = small[yp1 * size + x]; val br = small[yp1 * size + xp1]
                val gxv = (tr + 2f * mr + br) - (tl + 2f * ml + bl)
                val gyv = (bl + 2f * bc + br) - (tl + 2f * tc + tr)
                val idx = y * size + x
                gx[idx] = gxv
                gy[idx] = gyv
                gmag[idx] = (hypot(gxv, gyv) / MAX_GRADIENT).coerceIn(0f, 1f)
            }
        }

        val luminanceGrid = downsampleBlockAverage(small, size, gridWidth, gridHeight)
        val edgeGrid = downsampleBlockAverage(gmag, size, gridWidth, gridHeight)

        var sum = 0.0
        for (v in small) sum += v
        val mean = (sum / small.size).toFloat()
        var sq = 0.0
        for (v in small) sq += (v - mean).toDouble() * (v - mean)
        val stdDev = sqrt(sq / small.size).toFloat()

        val meanEdge = edgeGrid.average().toFloat()
        val horizontalSymmetry = symmetryScore(luminanceGrid, edgeGrid, gridWidth, gridHeight, meanEdge, horizontal = true)
        val verticalSymmetry = symmetryScore(luminanceGrid, edgeGrid, gridWidth, gridHeight, meanEdge, horizontal = false)

        val horizonLine = estimateHorizon(small, size)
        val dominantLines = buildList {
            horizonLine?.let { add(it) }
            addAll(dominantStraightLines(gx, gy, size, excludeHorizonRow = horizonLine != null))
        }.sortedByDescending { it.strength }.take(MAX_DOMINANT_LINES)

        return ImageStatistics(
            gridWidth = gridWidth,
            gridHeight = gridHeight,
            luminance = luminanceGrid,
            edgeDensity = edgeGrid,
            horizontalSymmetry = horizontalSymmetry,
            verticalSymmetry = verticalSymmetry,
            meanLuminance = mean,
            contrast = stdDev,
            dominantLines = dominantLines,
            estimatedHorizonAngleDegrees = horizonLine?.angleDegrees,
        )
    }

    /** Nearest-neighbour luma sample at (continuous) sensor coordinates (x, y), clamped to the buffer. */
    private fun sampleLuma(
        luma: ByteArray,
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): Float {
        val cx = x.toInt().coerceIn(0, width - 1)
        val cy = y.toInt().coerceIn(0, height - 1)
        val idx = cy * rowStride + cx * pixelStride
        if (idx < 0 || idx >= luma.size) return 0f
        return (luma[idx].toInt() and 0xFF) / 255f
    }

    /** Block-average a square [srcSize] x [srcSize] buffer down to [dstW] x [dstH]. */
    private fun downsampleBlockAverage(src: FloatArray, srcSize: Int, dstW: Int, dstH: Int): FloatArray {
        val dst = FloatArray(dstW * dstH)
        for (row in 0 until dstH) {
            val y0 = (row * srcSize) / dstH
            val y1 = (((row + 1) * srcSize) / dstH).coerceAtLeast(y0 + 1).coerceAtMost(srcSize)
            for (col in 0 until dstW) {
                val x0 = (col * srcSize) / dstW
                val x1 = (((col + 1) * srcSize) / dstW).coerceAtLeast(x0 + 1).coerceAtMost(srcSize)
                var s = 0f
                var n = 0
                for (yy in y0 until y1) for (xx in x0 until x1) {
                    s += src[yy * srcSize + xx]
                    n++
                }
                dst[row * dstW + col] = if (n > 0) s / n else 0f
            }
        }
        return dst
    }

    /** See class KDoc "Symmetry scoring". */
    private fun symmetryScore(
        luminance: FloatArray,
        edge: FloatArray,
        gridWidth: Int,
        gridHeight: Int,
        meanEdge: Float,
        horizontal: Boolean,
    ): Float {
        var weightedDiff = 0.0
        var weightSum = 0.0
        for (row in 0 until gridHeight) {
            for (col in 0 until gridWidth) {
                val mirrorCol = if (horizontal) gridWidth - 1 - col else col
                val mirrorRow = if (horizontal) row else gridHeight - 1 - row
                val idx = row * gridWidth + col
                val mIdx = mirrorRow * gridWidth + mirrorCol
                val weight = (edge[idx] + edge[mIdx]) / 2.0
                weightedDiff += weight * abs(luminance[idx] - luminance[mIdx])
                weightSum += weight
            }
        }
        val rawScore = if (weightSum > 1e-6) (1.0 - weightedDiff / weightSum) else 1.0
        // Real frames have sparse edges (most of a photo is comparatively flat), so saturate
        // confidence well before the frame-wide mean edge density gets anywhere near 1 -- otherwise
        // almost every real frame would blend all the way down to the neutral 0.5.
        val confidence = (meanEdge.toDouble() / EDGE_CONFIDENCE_SATURATION).coerceIn(0.0, 1.0)
        val blended = 0.5 + confidence * (rawScore - 0.5)
        return blended.coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * Finds the row with the strongest sustained horizontal luminance transition and, if confident,
     * fits a line to it. See class KDoc "Horizon / dominant lines (experimental)".
     */
    private fun estimateHorizon(small: FloatArray, size: Int): DetectedLine? {
        val rowMeans = FloatArray(size)
        for (r in 0 until size) {
            var s = 0f
            for (c in 0 until size) s += small[r * size + c]
            rowMeans[r] = s / size
        }

        var bestRow = -1
        var bestDelta = 0f
        for (r in 0 until size - 1) {
            val d = rowMeans[r + 1] - rowMeans[r]
            if (abs(d) > abs(bestDelta)) {
                bestDelta = d
                bestRow = r
            }
        }
        if (bestRow < 0 || abs(bestDelta) < HORIZON_NOISE_FLOOR) return null

        // Check the transition is sustained across most of the width, not just a local feature.
        val bandWidth = size / HORIZON_BAND_COUNT
        var agreeing = 0
        val bandCenters = FloatArray(HORIZON_BAND_COUNT)
        val bandRows = IntArray(HORIZON_BAND_COUNT)
        for (b in 0 until HORIZON_BAND_COUNT) {
            val c0 = b * bandWidth
            val c1 = if (b == HORIZON_BAND_COUNT - 1) size else c0 + bandWidth
            // Search a small vertical window around bestRow for this band's own strongest transition.
            var localBestRow = bestRow
            var localBestDelta = 0f
            val searchStart = (bestRow - 2).coerceAtLeast(0)
            val searchEnd = (bestRow + 2).coerceAtMost(size - 2)
            for (r in searchStart..searchEnd) {
                var d = 0f
                for (c in c0 until c1) d += small[(r + 1) * size + c] - small[r * size + c]
                d /= (c1 - c0)
                if (abs(d) > abs(localBestDelta)) {
                    localBestDelta = d
                    localBestRow = r
                }
            }
            bandCenters[b] = (c0 + c1) / 2f / size
            bandRows[b] = localBestRow
            if (localBestDelta != 0f && (localBestDelta > 0f) == (bestDelta > 0f) && abs(localBestDelta) > HORIZON_NOISE_FLOOR * 0.5f) {
                agreeing++
            }
        }
        val confidence = agreeing.toFloat() / HORIZON_BAND_COUNT
        if (confidence < HORIZON_SIGN_AGREEMENT_THRESHOLD) return null

        // Least-squares line fit: row (y) as a function of band center (x).
        var sumX = 0.0; var sumY = 0.0; var sumXY = 0.0; var sumXX = 0.0
        val n = HORIZON_BAND_COUNT
        for (b in 0 until n) {
            val x = bandCenters[b].toDouble()
            val y = (bandRows[b] + 0.5) / size
            sumX += x; sumY += y; sumXY += x * y; sumXX += x * x
        }
        val denom = n * sumXX - sumX * sumX
        val slope = if (abs(denom) > 1e-9) (n * sumXY - sumX * sumY) / denom else 0.0
        val intercept = (sumY - slope * sumX) / n
        val yLeft = intercept.toFloat().coerceIn(0f, 1f)
        val yRight = (intercept + slope).toFloat().coerceIn(0f, 1f)
        return DetectedLine(NormalizedPoint(0f, yLeft), NormalizedPoint(1f, yRight), strength = confidence)
    }

    /**
     * Cheap "dominant lines" via column/row Sobel-energy tracking: a column dominated by a strong,
     * consistent Gx response (and weak Gy) reads like a vertical edge/line; a row dominated by Gy
     * reads like a horizontal one. Non-max-suppressed, capped, low-confidence -> empty.
     */
    private fun dominantStraightLines(gx: FloatArray, gy: FloatArray, size: Int, excludeHorizonRow: Boolean): List<DetectedLine> {
        val colEnergy = FloatArray(size)
        val rowEnergy = FloatArray(size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val idx = y * size + x
                val vx = abs(gx[idx])
                val vy = abs(gy[idx])
                // Only count a pixel toward a column's "vertical line" energy when the gradient there
                // is clearly horizontal-dominant (i.e. a vertical edge), and vice-versa for rows.
                if (vx > vy * 1.5f) colEnergy[x] += vx
                if (vy > vx * 1.5f) rowEnergy[y] += vy
            }
        }
        for (i in 0 until size) {
            colEnergy[i] /= (size * MAX_SOBEL_COMPONENT)
            rowEnergy[i] /= (size * MAX_SOBEL_COMPONENT)
        }

        val verticalLines = topLocalMaxima(colEnergy, LINE_ENERGY_THRESHOLD, minSpacing = size / 12)
            .map { (index, strength) -> DetectedLine(NormalizedPoint(index / size.toFloat(), 0f), NormalizedPoint(index / size.toFloat(), 1f), strength) }
        val horizontalLines = topLocalMaxima(rowEnergy, LINE_ENERGY_THRESHOLD, minSpacing = size / 12)
            .map { (index, strength) -> DetectedLine(NormalizedPoint(0f, index / size.toFloat()), NormalizedPoint(1f, index / size.toFloat()), strength) }

        return (verticalLines + horizontalLines).sortedByDescending { it.strength }.take(MAX_DOMINANT_LINES)
    }

    private fun topLocalMaxima(energy: FloatArray, threshold: Float, minSpacing: Int): List<Pair<Int, Float>> {
        val candidates = mutableListOf<Pair<Int, Float>>()
        for (i in energy.indices) {
            if (energy[i] < threshold) continue
            val prev = if (i > 0) energy[i - 1] else -1f
            val next = if (i < energy.size - 1) energy[i + 1] else -1f
            if (energy[i] >= prev && energy[i] >= next) candidates.add(i to energy[i])
        }
        val sorted = candidates.sortedByDescending { it.second }
        val chosen = mutableListOf<Pair<Int, Float>>()
        for (c in sorted) {
            if (chosen.none { abs(it.first - c.first) < minSpacing.coerceAtLeast(1) }) chosen.add(c)
        }
        return chosen
    }
}
