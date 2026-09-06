package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.engine.StatsHeuristics.meanEdgeDensity
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.OverlayGeometry
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SubjectMask

/**
 * Looks for classic distracting-background problems behind the primary subject's head.
 *
 * **With a [SubjectMask]** (present only when a person segmenter ran): [MaskHeuristics] traces the actual
 * mask silhouette instead of a face-box guess, which lets this analyzer look for two things:
 *  1. **"Pole through the head"**: a narrow (<= [POLE_MAX_WIDTH_CELLS] mask cells wide) column of high edge
 *     density that touches the top of the mask and continues for at least [POLE_MIN_HEIGHT_CELLS] cells
 *     above it, with clearly quieter cells immediately either side — the textbook "a lamppost/tree appears
 *     to sprout from their head" error, caught precisely because the mask's own head-top is used rather
 *     than the coarser face-box estimate.
 *  2. **General clutter**: the band directly above the mask (as tall as the mask is wide), with any cell
 *     that is itself part of the mask excluded, is noticeably busier than the frame's overall background.
 *
 * **Without a mask**: falls back to the original face-box heuristic — a narrow column directly above the
 * estimated head top, plus a ring immediately around the face box, each compared against the frame's mean
 * edge density / luminance.
 *
 * Either case is fixed the same way: pan toward whichever side (left/right of the head) is *less* busy,
 * since that pulls the background behind the head away from the distracting area. [CompositionMetric.confidence]
 * scales with how strong the contrast against the background is — a marginal difference isn't worth
 * interrupting the photographer for.
 */
class BackgroundDistractionAnalyzer : CompositionAnalyzer {
    override val name: String = "BackgroundDistractionAnalyzer"
    override val category: MetricCategory = MetricCategory.BACKGROUND_DISTRACTION

    override fun analyze(context: AnalysisContext): CompositionMetric {
        val face = context.primarySubject?.face
        val stats = context.frame.stats
        if (face == null || stats == null) {
            return CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)
        }
        val mask = context.frame.subjectMask
        return (mask?.let { analyzeWithMask(stats, it) }) ?: analyzeWithoutMask(face, stats)
    }

    // --- Mask-driven path ---------------------------------------------------------------------------

    private fun analyzeWithMask(stats: ImageStatistics, mask: SubjectMask): CompositionMetric? {
        val maskBounds = mask.bounds() ?: return null
        val background = stats.meanEdgeDensity().coerceAtLeast(MIN_BACKGROUND_EDGE)

        val bandHeight = maskBounds.width.coerceAtLeast(MIN_COLUMN_HEIGHT)
        val bandRect = NormalizedRect(maskBounds.left, (maskBounds.top - bandHeight).coerceAtLeast(0f), maskBounds.right, maskBounds.top)
        val bandEdge = MaskHeuristics.regionEdgeDensityExcludingMask(mask, stats, bandRect)
        val bandRatio = bandEdge / background

        val pole = poleColumnRange(mask, stats, maskBounds)
        val generalClutter = bandRatio >= RING_RATIO_THRESHOLD
        val flagged = pole != null || generalClutter

        val worstRatio = if (pole != null) maxOf(bandRatio, COLUMN_RATIO_THRESHOLD) else bandRatio
        val score = (1f - ((worstRatio - 1f) / COLUMN_RATIO_THRESHOLD).coerceIn(0f, 1f)).coerceIn(0f, 1f)

        val recommendation = if (!flagged) {
            null
        } else {
            val leftEdge = MaskHeuristics.regionEdgeDensityExcludingMask(mask, stats, NormalizedRect(0f, maskBounds.top, maskBounds.left, maskBounds.bottom))
            val rightEdge = MaskHeuristics.regionEdgeDensityExcludingMask(mask, stats, NormalizedRect(maskBounds.right, maskBounds.top, 1f, maskBounds.bottom))
            val moveLeft = leftEdge <= rightEdge
            val direction = if (moveLeft) Direction.LEFT else Direction.RIGHT
            Recommendation(
                id = if (pole != null) "background.pole" else "background.clutter",
                category = category,
                priority = if (pole != null) Priority.HIGH else Priority.MEDIUM,
                confidence = MASK_CONFIDENCE,
                severity = if (pole != null) Severity.MEDIUM else Severity.LOW,
                title = if (pole != null) "Something behind the subject lines up with their head" else "Background is distracting",
                instruction = if (moveLeft) "Move slightly left to clear the background" else "Move slightly right to clear the background",
                reason = if (pole != null) {
                    "A narrow vertical shape directly behind the head reads as if it's growing out of it."
                } else {
                    "The area right above the subject's head is busier than the rest of the background."
                },
                direction = direction,
                vector = ReframeVector(dx = if (moveLeft) -PAN_MAGNITUDE else PAN_MAGNITUDE),
            )
        }

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = MASK_CONFIDENCE,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = when {
                pole != null -> "A narrow shape appears to grow from the subject's head"
                generalClutter -> "A busy patch sits right behind the subject's head"
                else -> null
            },
            recommendation = recommendation,
            geometry = listOfNotNull(recommendation?.let { OverlayGeometry.Region(maskBounds, isProblem = true) }),
            strength = if (score >= 0.9f) "Clean background behind the subject" else null,
        )
    }

    /**
     * Looks, within the mask's own horizontal span, for a column (or pair of columns) of cells that are
     * all busy for [POLE_MIN_HEIGHT_CELLS] rows immediately above the mask's top row, while the columns
     * just outside that span are clearly quieter — the "narrow" part of "narrow column", which tells a true
     * pole/lamppost apart from a generally busy wall or hedge (that would trip [analyzeWithMask]'s general
     * clutter check instead, not this one).
     */
    private fun poleColumnRange(mask: SubjectMask, stats: ImageStatistics, maskBounds: NormalizedRect): IntRange? {
        val headTopRow = (maskBounds.top * mask.gridHeight).toInt().coerceIn(0, mask.gridHeight - 1)
        if (headTopRow < POLE_MIN_HEIGHT_CELLS) return null

        fun edgeAt(col: Int, row: Int): Float {
            val (sc, sr) = stats.cellOf(with(MaskHeuristics) { mask.cellCenter(col, row) })
            return stats.edgeDensityAt(sc, sr)
        }

        fun columnIsBusy(col: Int): Boolean {
            if (col !in 0 until mask.gridWidth) return false
            for (row in (headTopRow - POLE_MIN_HEIGHT_CELLS) until headTopRow) {
                if (row < 0 || edgeAt(col, row) < POLE_EDGE_THRESHOLD) return false
            }
            return true
        }

        val leftCol = (maskBounds.left * mask.gridWidth).toInt().coerceIn(0, mask.gridWidth - 1)
        val rightCol = ((maskBounds.right * mask.gridWidth).toInt() - 1).coerceIn(leftCol, mask.gridWidth - 1)

        for (width in 1..POLE_MAX_WIDTH_CELLS) {
            var start = leftCol
            while (start + width - 1 <= rightCol) {
                val range = start until (start + width)
                if (range.all { columnIsBusy(it) }) {
                    val leftNeighborBusy = start - 1 >= 0 && columnIsBusy(start - 1)
                    val rightNeighborBusy = start + width <= mask.gridWidth - 1 && columnIsBusy(start + width)
                    if (!leftNeighborBusy && !rightNeighborBusy) return range
                }
                start++
            }
        }
        return null
    }

    // --- No-mask fallback (unchanged heuristic) ------------------------------------------------------

    private fun analyzeWithoutMask(face: DetectedFace, stats: ImageStatistics): CompositionMetric {
        val headTop = face.estimatedHeadTop.coerceIn(0f, 1f)
        val background = stats.meanEdgeDensity().coerceAtLeast(MIN_BACKGROUND_EDGE)

        var columnRatio = 1f
        var brightnessContrast = 0f
        if (headTop > MIN_COLUMN_HEIGHT) {
            val column = NormalizedRect(face.bounds.left, 0f, face.bounds.right, headTop)
            columnRatio = stats.regionEdgeDensity(column) / background
            brightnessContrast = stats.regionLuminance(column) - stats.meanLuminance
        }

        val ring = face.bounds.let { NormalizedRect(it.left - RING_MARGIN, it.top - RING_MARGIN, it.right + RING_MARGIN, it.bottom + RING_MARGIN) }
        val ringRatio = stats.regionEdgeDensity(ring) / background

        val growingOutOfHead = columnRatio >= COLUMN_RATIO_THRESHOLD || brightnessContrast >= BRIGHTNESS_THRESHOLD
        val generalClutter = ringRatio >= RING_RATIO_THRESHOLD

        val worstRatio = maxOf(columnRatio, ringRatio)
        val score = (1f - ((worstRatio - 1f) / (COLUMN_RATIO_THRESHOLD)).coerceIn(0f, 1f)).coerceIn(0f, 1f)

        val recommendation = if (!growingOutOfHead && !generalClutter) {
            null
        } else {
            val leftEdge = stats.regionEdgeDensity(NormalizedRect(0f, face.bounds.top, face.bounds.left, face.bounds.bottom))
            val rightEdge = stats.regionEdgeDensity(NormalizedRect(face.bounds.right, face.bounds.top, 1f, face.bounds.bottom))
            val moveLeft = leftEdge <= rightEdge
            val direction = if (moveLeft) Direction.LEFT else Direction.RIGHT
            val confidence = ((worstRatio - 1f) / 2f).coerceIn(0.3f, 0.95f)
            Recommendation(
                id = if (growingOutOfHead) "background.growingfromhead" else "background.clutter",
                category = category,
                priority = if (growingOutOfHead) Priority.HIGH else Priority.MEDIUM,
                confidence = confidence,
                severity = if (growingOutOfHead) Severity.MEDIUM else Severity.LOW,
                title = if (growingOutOfHead) "Something behind the subject lines up with their head" else "Background is distracting",
                instruction = if (moveLeft) "Move slightly left to clear the background" else "Move slightly right to clear the background",
                reason = "The background right behind the subject is busier or brighter than the rest of the frame.",
                direction = direction,
                vector = ReframeVector(dx = if (moveLeft) -PAN_MAGNITUDE else PAN_MAGNITUDE),
            )
        }

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = 0.7f,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = if (recommendation != null) "A busy patch sits right behind the subject's head" else null,
            recommendation = recommendation,
            geometry = listOfNotNull(recommendation?.let { OverlayGeometry.Region(ring, isProblem = true) }),
            // Gated on `recommendation == null`: `growingOutOfHead` can fire on brightnessContrast
            // alone (a bright, low-texture sky patch above the head) while both edge-density ratios
            // stay near/below 1.0, which used to let `score` read ~1.0 -- "Clean background" (strength)
            // right next to "A busy patch..." (issue) on the same metric.
            strength = if (recommendation == null && score >= 0.9f) "Clean background behind the subject" else null,
        )
    }

    companion object {
        const val MIN_COLUMN_HEIGHT = 0.02f
        const val COLUMN_RATIO_THRESHOLD = 1.6f
        const val BRIGHTNESS_THRESHOLD = 0.22f
        const val RING_RATIO_THRESHOLD = 1.4f
        const val RING_MARGIN = 0.06f
        const val MIN_BACKGROUND_EDGE = 0.02f
        const val PAN_MAGNITUDE = 0.08f
        const val MASK_CONFIDENCE = 0.85f

        /** "Narrow" ceiling for the pole detector, in mask grid cells. */
        const val POLE_MAX_WIDTH_CELLS = 2

        /** The pole must read as busy for at least this many rows above the mask's head top. */
        const val POLE_MIN_HEIGHT_CELLS = 3

        /** Edge density at/above this (on the mask grid's mapped stats cells) counts as "busy" for the pole check. */
        const val POLE_EDGE_THRESHOLD = 0.5f
    }
}
