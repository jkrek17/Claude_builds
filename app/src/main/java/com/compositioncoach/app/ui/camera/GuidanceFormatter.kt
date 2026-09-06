package com.compositioncoach.app.ui.camera

import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.Recommendation

/** Which color band a displayed score falls into. Mapped to an actual [androidx.compose.ui.graphics.Color] in the theme. */
enum class ScoreTier { LOW, GOOD, EXCELLENT }

/**
 * Pure text/formatting logic for the on-screen coaching copy. Kept free of Compose types so it is
 * unit-testable on the plain JVM (see GuidanceFormatterTest); [ui.camera.ScoreBadge] and
 * [ui.camera.GuidanceBanner] call into this rather than embedding the rules inline.
 */
object GuidanceFormatter {

    /** A short directional glyph for the primary recommendation, shown next to the instruction text. */
    fun glyphFor(direction: Direction): String = when (direction) {
        Direction.LEFT -> "←"
        Direction.RIGHT -> "→"
        Direction.UP -> "↑"
        Direction.DOWN -> "↓"
        Direction.CLOSER -> "＋"
        Direction.BACK -> "－"
        Direction.ROTATE_CLOCKWISE -> "↻"
        Direction.ROTATE_COUNTER_CLOCKWISE -> "↺"
        Direction.NONE -> ""
    }

    /** "→ Move slightly right" — the glyph and instruction combined for the primary guidance line. */
    fun primaryLine(recommendation: Recommendation): String {
        val glyph = glyphFor(recommendation.direction)
        return if (glyph.isEmpty()) recommendation.instruction else "$glyph ${recommendation.instruction}"
    }

    /** A smaller secondary-recommendation line, glyph included but no reason (reason is COACH-only, see [reasonLine]). */
    fun secondaryLine(recommendation: Recommendation): String = primaryLine(recommendation)

    /** COACH-level shows *why*, as a subtitle under the primary instruction. Null when there is nothing to add. */
    fun reasonLine(recommendation: Recommendation, level: GuidanceLevel): String? =
        recommendation.reason.takeIf { level == GuidanceLevel.COACH && !it.isNullOrBlank() }

    fun scoreTier(score: Int): ScoreTier = when {
        score >= 88 -> ScoreTier.EXCELLENT
        score >= 70 -> ScoreTier.GOOD
        else -> ScoreTier.LOW
    }

    /** "94 — SHOOT" for the badge when framing is strong enough to fire the shutter. */
    fun shootReadyScoreText(score: Int): String = "$score — SHOOT"

    const val SHOOT_READY_SUBTITLE = "Great framing"

    const val NO_SUBJECT_TEXT = "Looking for a subject…"

    /** Alpha applied to [ScoreBadge]'s held score number while [SmoothedComposition.awaitingSubject] is true. */
    const val AWAITING_SUBJECT_ALPHA = 0.4f

    /** The [ScoreBadge] text alpha for the current awaiting-subject state — full opacity otherwise. */
    fun badgeAlpha(awaitingSubject: Boolean): Float = if (awaitingSubject) AWAITING_SUBJECT_ALPHA else 1f

    /**
     * The small line shown above the headline in [GuidanceBanner] while awaiting a subject — the
     * find-subject recommendation's title (e.g. "Looking for a face").
     */
    fun awaitingSubjectTitleLine(recommendation: Recommendation): String = recommendation.title

    /**
     * The prominent headline shown in [GuidanceBanner] while awaiting a subject — the find-subject
     * recommendation's instruction (e.g. "Move closer to your subject"). Never combined with a
     * directional glyph: the recommendation has no direction to point in.
     */
    fun awaitingSubjectHeadline(recommendation: Recommendation): String = recommendation.instruction

    /** Text shown when there is no advice and the framing is already decent. */
    const val HOLD_FRAMING_HINT = "Hold this framing"

    /** The hold-framing hint only makes sense once the score is good; below that there is always advice. */
    const val HOLD_FRAMING_MIN_SCORE = 70

    fun showsHoldFramingHint(displayScore: Int): Boolean = displayScore >= HOLD_FRAMING_MIN_SCORE
}
