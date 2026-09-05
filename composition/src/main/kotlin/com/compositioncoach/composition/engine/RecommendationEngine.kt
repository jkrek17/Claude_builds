package com.compositioncoach.composition.engine

import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity

/**
 * Collects every analyzer's [Recommendation], ranks them, and trims the list to what the chosen
 * [GuidanceLevel] should actually show. Also derives the "strengths" list from metrics that are doing
 * well, for the post-capture review screen.
 *
 * Ranking key (highest first): [Severity] first — a HIGH severity issue always outranks a LOW one
 * regardless of anything else — then the analyzer's own [Recommendation.confidence], then
 * [Recommendation.expectedImprovement] (filled in later by [CompositionOptimizer] where estimable, so
 * this mostly matters on the *second* pass, see [CompositionEngine]), then "ease of correction": a small
 * pan or a rotation is a one-second correction, "step back" requires physically moving.
 *
 * Conflict handling: two recommendations that ask for opposite physical actions (move left vs. right,
 * raise vs. lower, rotate CW vs. CCW, step back vs. move closer) are never shown together — whichever
 * ranks lower is simply dropped, on the theory that acting on the higher-ranked one first is what the
 * photographer should do anyway. A second recommendation for the *same* direction is dropped for the
 * same reason (there is nothing more specific to add once the higher-ranked one already says "move left").
 */
class RecommendationEngine {

    fun strengths(metrics: List<CompositionMetric>): List<String> =
        metrics.filter { it.applicable && it.score >= STRENGTH_SCORE_THRESHOLD && it.strength != null }
            .sortedByDescending { it.score }
            .map { it.strength!! }
            .distinct()

    fun improvements(metrics: List<CompositionMetric>): List<String> =
        metrics.filter { it.applicable && it.issue != null }
            .sortedBy { it.score }
            .map { it.issue!! }
            .distinct()
            .take(MAX_IMPROVEMENTS)

    /** Ranks and trims every metric's recommendation according to [level]. */
    fun rank(metrics: List<CompositionMetric>, level: GuidanceLevel): List<Recommendation> {
        val raw = metrics.mapNotNull { it.recommendation }
        val sorted = raw.sortedWith(rankingComparator)
        val deduped = dropConflictingDirections(sorted)
        val minSeverity = if (level == GuidanceLevel.MINIMAL) Severity.MEDIUM else Severity.NONE
        val filtered = deduped.filter { it.severity.ordinal >= minSeverity.ordinal }
        val limit = when (level) {
            GuidanceLevel.MINIMAL -> 1
            GuidanceLevel.BALANCED -> 2
            GuidanceLevel.COACH -> 3
        }
        return filtered.take(limit)
    }

    private val rankingComparator = compareByDescending<Recommendation> { it.severity.ordinal }
        .thenByDescending { it.confidence }
        .thenByDescending { it.expectedImprovement ?: 0f }
        .thenByDescending { easeOfCorrection(it.direction) }

    /** Higher = easier/faster to correct. Rotation and small pan/tilt corrections are nearly instant; stepping back or forward takes longer. */
    private fun easeOfCorrection(direction: Direction): Float = when (direction) {
        Direction.ROTATE_CLOCKWISE, Direction.ROTATE_COUNTER_CLOCKWISE -> 1.0f
        Direction.LEFT, Direction.RIGHT, Direction.UP, Direction.DOWN -> 0.8f
        Direction.CLOSER -> 0.5f
        Direction.BACK -> 0.3f
        Direction.NONE -> 0.6f
    }

    private fun oppositeOf(direction: Direction): Direction? = when (direction) {
        Direction.LEFT -> Direction.RIGHT
        Direction.RIGHT -> Direction.LEFT
        Direction.UP -> Direction.DOWN
        Direction.DOWN -> Direction.UP
        Direction.ROTATE_CLOCKWISE -> Direction.ROTATE_COUNTER_CLOCKWISE
        Direction.ROTATE_COUNTER_CLOCKWISE -> Direction.ROTATE_CLOCKWISE
        Direction.CLOSER -> Direction.BACK
        Direction.BACK -> Direction.CLOSER
        Direction.NONE -> null
    }

    private fun dropConflictingDirections(sorted: List<Recommendation>): List<Recommendation> {
        val kept = mutableListOf<Recommendation>()
        val takenDirections = mutableSetOf<Direction>()
        for (rec in sorted) {
            if (rec.direction == Direction.NONE) {
                kept += rec
                continue
            }
            if (rec.direction in takenDirections) continue
            val opposite = oppositeOf(rec.direction)
            if (opposite != null && opposite in takenDirections) continue
            kept += rec
            takenDirections += rec.direction
        }
        return kept
    }

    companion object {
        const val STRENGTH_SCORE_THRESHOLD = 0.8f
        const val MAX_IMPROVEMENTS = 5
    }
}
