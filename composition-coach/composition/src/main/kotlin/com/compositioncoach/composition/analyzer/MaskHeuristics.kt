package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.SubjectMask

/**
 * Small, reusable heuristics that combine a [SubjectMask] (a coarse, ~32x32 foreground-probability grid,
 * present only when a person segmenter ran) with [ImageStatistics] (the engine's own, differently-sized
 * luminance/edge grid) — shared by every mask-driven analyzer ([SubjectSeparationAnalyzer],
 * [BackgroundDistractionAnalyzer], [BalanceAnalyzer]).
 *
 * The two grids are never assumed to share dimensions: every helper here walks the *mask's* grid and maps
 * each cell to a normalized frame point ([cellCenter]), then looks up the corresponding stats cell via
 * [ImageStatistics.cellOf] — so this works regardless of how coarse either grid is.
 */
object MaskHeuristics {
    /** Probability at/above which a mask cell counts as "subject" rather than background. */
    const val SUBJECT_THRESHOLD = 0.6f

    /** Normalized-frame centre point of one mask grid cell, for cross-referencing against other grids. */
    fun SubjectMask.cellCenter(col: Int, row: Int): NormalizedPoint =
        NormalizedPoint((col + 0.5f) / gridWidth, (row + 0.5f) / gridHeight)

    /** Grid coordinates (col, row) of every mask cell at or above [threshold]. */
    fun subjectCells(mask: SubjectMask, threshold: Float = SUBJECT_THRESHOLD): List<Pair<Int, Int>> {
        val cells = mutableListOf<Pair<Int, Int>>()
        for (row in 0 until mask.gridHeight) for (col in 0 until mask.gridWidth) {
            if (mask.at(col, row) >= threshold) cells += col to row
        }
        return cells
    }

    /**
     * Non-subject cells within [ringWidth] grid cells (Chebyshev distance) of at least one subject cell —
     * a thin border tracing the mask's own silhouette rather than a rectangular bounding-box ring.
     *
     * @param knownSubjectCells the result of a prior [subjectCells] call on this same [mask]/[threshold],
     *   when the caller already has one (e.g. [SubjectSeparationAnalyzer]) — avoids re-scanning the whole
     *   grid and re-allocating an identical cell list every call. Computed fresh when omitted.
     */
    fun ringCells(
        mask: SubjectMask,
        ringWidth: Int = 2,
        threshold: Float = SUBJECT_THRESHOLD,
        knownSubjectCells: List<Pair<Int, Int>>? = null,
    ): List<Pair<Int, Int>> {
        val subject = (knownSubjectCells ?: subjectCells(mask, threshold)).toSet()
        if (subject.isEmpty()) return emptyList()
        val ring = mutableListOf<Pair<Int, Int>>()
        for (row in 0 until mask.gridHeight) for (col in 0 until mask.gridWidth) {
            val here = col to row
            if (here in subject) continue
            val near = (-ringWidth..ringWidth).any { dc ->
                (-ringWidth..ringWidth).any { dr -> (dc != 0 || dr != 0) && (col + dc to row + dr) in subject }
            }
            if (near) ring += here
        }
        return ring
    }

    /** Mean [ImageStatistics.luminance] of [cells] (mask grid coordinates), or the frame mean if [cells] is empty. */
    fun meanLuminance(mask: SubjectMask, stats: ImageStatistics, cells: List<Pair<Int, Int>>): Float {
        if (cells.isEmpty()) return stats.meanLuminance
        var sum = 0f
        for ((col, row) in cells) {
            val (sc, sr) = stats.cellOf(with(mask) { cellCenter(col, row) })
            sum += stats.luminanceAt(sc, sr)
        }
        return sum / cells.size
    }

    /** Mean [ImageStatistics.edgeDensity] of [cells] (mask grid coordinates), or 0 if [cells] is empty. */
    fun meanEdgeDensity(mask: SubjectMask, stats: ImageStatistics, cells: List<Pair<Int, Int>>): Float {
        if (cells.isEmpty()) return 0f
        var sum = 0f
        for ((col, row) in cells) {
            val (sc, sr) = stats.cellOf(with(mask) { cellCenter(col, row) })
            sum += stats.edgeDensityAt(sc, sr)
        }
        return sum / cells.size
    }

    /**
     * Mean edge density inside [rect], sampled on the mask's own grid resolution and skipping any cell
     * that is itself part of the subject — used to measure "how busy is the background here" without the
     * subject's own texture bleeding into the answer.
     */
    fun regionEdgeDensityExcludingMask(mask: SubjectMask, stats: ImageStatistics, rect: NormalizedRect, threshold: Float = SUBJECT_THRESHOLD): Float {
        val r = rect.clampToFrame()
        if (r.isEmpty) return 0f
        val c0 = (r.left * mask.gridWidth).toInt().coerceIn(0, mask.gridWidth - 1)
        val c1 = ((r.right * mask.gridWidth).toInt() - 1).coerceIn(c0, mask.gridWidth - 1)
        val r0 = (r.top * mask.gridHeight).toInt().coerceIn(0, mask.gridHeight - 1)
        val r1 = ((r.bottom * mask.gridHeight).toInt() - 1).coerceIn(r0, mask.gridHeight - 1)
        var sum = 0f
        var n = 0
        for (row in r0..r1) for (col in c0..c1) {
            if (mask.at(col, row) >= threshold) continue
            val (sc, sr) = stats.cellOf(with(mask) { cellCenter(col, row) })
            sum += stats.edgeDensityAt(sc, sr)
            n++
        }
        return if (n == 0) 0f else sum / n
    }
}
