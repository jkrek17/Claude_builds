package com.compositioncoach.composition.engine

import com.compositioncoach.composition.model.DetectedLine
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect

/**
 * Small, reusable heuristics over [ImageStatistics] shared by [SceneClassifier] and [SubjectResolver].
 * Kept in one place so the "what counts as a strong signal" thresholds don't drift between the two.
 */
object StatsHeuristics {

    /** Dominant lines whose angle is close enough to vertical to read as building edges / verticals. */
    fun ImageStatistics.verticalLineCount(minStrength: Float = 0.4f): Int =
        dominantLines.count { it.isRoughlyVertical && it.strength >= minStrength }

    /** The strongest roughly-horizontal dominant line at or above [minStrength], if any. */
    fun ImageStatistics.strongHorizontalLine(minStrength: Float = 0.5f): DetectedLine? =
        dominantLines.filter { it.isRoughlyHorizontal && it.strength >= minStrength }.maxByOrNull { it.strength }

    /** Mean luminance of the top half of the frame minus the bottom half (positive = top is brighter, as with sky). */
    fun ImageStatistics.topMinusBottomLuminance(): Float {
        val top = regionLuminance(NormalizedRect(0f, 0f, 1f, 0.5f))
        val bottom = regionLuminance(NormalizedRect(0f, 0.5f, 1f, 1f))
        return top - bottom
    }

    /** True when the top half is meaningfully brighter than the bottom half (sky-over-ground pattern). */
    fun ImageStatistics.hasBrighterTopThanBottom(margin: Float = 0.03f): Boolean = topMinusBottomLuminance() > margin

    /** Mean edge density over the whole grid. */
    fun ImageStatistics.meanEdgeDensity(): Float {
        if (edgeDensity.isEmpty()) return 0f
        var sum = 0f
        for (v in edgeDensity) sum += v
        return sum / edgeDensity.size
    }

    /**
     * A single compact rectangle around the busiest cluster of edge energy, and how much busier it is
     * than the rest of the frame (ratio of its mean edge density to the surrounding background's).
     * Returns null when the image has no meaningful edge energy at all (near-blank frame).
     */
    data class HotRegion(val rect: NormalizedRect, val contrastRatio: Float)

    fun ImageStatistics.compactHighEdgeRegion(fraction: Float = 0.3f): HotRegion? {
        val mean = meanEdgeDensity()
        if (mean <= 1e-4f) return null
        val centroid = visualWeightCentroid()
        val halfW = fraction / 2f
        val halfH = fraction / 2f
        val rect = NormalizedRect(
            (centroid.x - halfW).coerceIn(0f, 1f - fraction.coerceAtMost(1f)),
            (centroid.y - halfH).coerceIn(0f, 1f - fraction.coerceAtMost(1f)),
            0f, 0f,
        ).let { NormalizedRect(it.left, it.top, (it.left + fraction).coerceAtMost(1f), (it.top + fraction).coerceAtMost(1f)) }
        val inside = regionEdgeDensity(rect)
        // Background = whole-frame mean with the hot region's contribution removed (approximation is fine at this scale).
        val backgroundMean = ((mean * (gridWidth * gridHeight)) - inside * (rect.area * gridWidth * gridHeight))
            .coerceAtLeast(0f) / (gridWidth * gridHeight * (1f - rect.area)).coerceAtLeast(1f)
        val ratio = if (backgroundMean <= 1e-4f) if (inside > 1e-4f) 10f else 1f else inside / backgroundMean
        return HotRegion(rect, ratio)
    }

    /** Centroid of [ImageStatistics.visualWeightCentroid] exposed with a stable name for readability at call sites. */
    fun ImageStatistics.visualCentroid(): NormalizedPoint = visualWeightCentroid()
}
