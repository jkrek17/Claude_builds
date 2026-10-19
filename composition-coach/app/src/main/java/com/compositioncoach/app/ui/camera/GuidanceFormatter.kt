package com.compositioncoach.app.ui.camera

import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity

/** Which colour band a displayed score falls into. Mapped to an actual colour by `theme.scoreColor`. */
enum class ScoreTier { LOW, GOOD, EXCELLENT }

/**
 * The single spatial cue on screen, chosen from the headline recommendation — the "Primary cue" table in
 * `docs/COACHING_UI.md`. Exactly one of these is ever showing.
 */
sealed interface PrimaryCue {
    /** A 64dp chevron on the *physical* edge [direction] points at; see [CueEdge.forDirection]. */
    data class EdgeChevron(val direction: Direction) : PrimaryCue

    /** Four corner brackets easing [inward] (CLOSER) or outward (BACK). */
    data class CornerBrackets(val inward: Boolean) : PrimaryCue

    /** The bubble level at the physical-top edge; no chevron accompanies it. */
    data object BubbleLevel : PrimaryCue

    /** No spatial cue is possible (separation, symmetry-centre, looking room without a side): chip only. */
    data object ChipOnly : PrimaryCue
}

/**
 * Every rule that turns engine output into on-screen coaching words and cue *kinds*, kept free of Compose
 * types so it is unit-testable on the plain JVM (see `GuidanceFormatterTest`). The composables in this
 * package render what this decides; they never re-derive wording or level behaviour themselves.
 *
 * The spec these implement is `docs/COACHING_UI.md`: words are a label on a spatial cue, never the cue
 * itself, so every string here is 1-3 words and sentences (`Recommendation.reason`) are left to the
 * review screen. The one exception is [explanationText], which the Coach-level secondary icon row shows
 * for 3 seconds when tapped.
 */
object GuidanceFormatter {

    /** Text shown on the cue chip while framing is good enough to fire the shutter. */
    const val SHOOT_TEXT = "SHOOT"

    /** The chip shown before the engine has classified any scene at all. */
    const val POINT_AT_SUBJECT = "Point at a subject"

    /** Fallback awaiting-subject wording when the engine's find-subject recommendation has no title. */
    const val LOOKING_FOR_SUBJECT = "Looking for a subject"

    /** The readiness arc dims to this while the declared mode is still waiting for its subject. */
    const val AWAITING_SUBJECT_ARC_ALPHA = 0.3f

    /** Below this score there is always advice, so the quiet "hold this framing" check mark never shows. */
    const val QUIET_STATE_MIN_SCORE = 70

    /**
     * The exact chip wording table from `docs/COACHING_UI.md`. Direction wins when there is one (it is the
     * physical action the chevron/brackets/level are already showing); otherwise the category names the
     * problem. A category outside the table falls back to the recommendation's own [Recommendation.title]
     * trimmed to three words, so a future analyzer degrades to short text rather than to a blank chip.
     */
    fun chipText(recommendation: Recommendation): String = when (recommendation.direction) {
        Direction.RIGHT -> "Slightly right"
        Direction.LEFT -> "Slightly left"
        Direction.UP -> "Raise"
        Direction.DOWN -> "Lower"
        Direction.CLOSER -> "Closer"
        Direction.BACK -> "Step back"
        Direction.ROTATE_CLOCKWISE, Direction.ROTATE_COUNTER_CLOCKWISE -> "Level"
        Direction.NONE -> categoryChipText(recommendation.category) ?: shortTitle(recommendation.title)
    }

    /** The chip wording for a directionless recommendation, or null when the category isn't in the table. */
    private fun categoryChipText(category: MetricCategory): String? = when (category) {
        MetricCategory.HORIZON -> "Level"
        MetricCategory.BACKGROUND_DISTRACTION -> "Clear background"
        MetricCategory.SUBJECT_SEPARATION -> "Plainer background"
        MetricCategory.LOOKING_ROOM -> "Give looking room"
        MetricCategory.EDGE_TENSION -> "Too close to edge"
        MetricCategory.CROPPING -> "Someone's cut off"
        MetricCategory.SYMMETRY -> "Center it"
        else -> null
    }

    /** At most the first three words of [title] — the chip's hard budget, per the spec's "1-3 words". */
    private fun shortTitle(title: String): String =
        title.trim().split(" ").filter { it.isNotBlank() }.take(3).joinToString(" ")

    /** The chip for the awaiting-subject state: the find-subject recommendation's own title ("Looking for a face"). */
    fun awaitingSubjectChipText(recommendation: Recommendation?): String =
        recommendation?.title?.takeIf { it.isNotBlank() } ?: LOOKING_FOR_SUBJECT

    /** Which spatial cue the headline [recommendation] earns; see [PrimaryCue]. */
    fun cueFor(recommendation: Recommendation): PrimaryCue = when (recommendation.direction) {
        Direction.LEFT, Direction.RIGHT, Direction.UP, Direction.DOWN ->
            PrimaryCue.EdgeChevron(recommendation.direction)
        Direction.CLOSER -> PrimaryCue.CornerBrackets(inward = true)
        Direction.BACK -> PrimaryCue.CornerBrackets(inward = false)
        Direction.ROTATE_CLOCKWISE, Direction.ROTATE_COUNTER_CLOCKWISE -> PrimaryCue.BubbleLevel
        Direction.NONE -> if (recommendation.category == MetricCategory.HORIZON) {
            PrimaryCue.BubbleLevel
        } else {
            PrimaryCue.ChipOnly
        }
    }

    /**
     * The headline recommendation actually coached at [level], or null when there is nothing to show.
     *
     * Minimal only speaks for `severity >= MEDIUM` (the spec's guidance-level table). The chip is the
     * *label* on a cue, so suppressing the cue suppresses its chip too — at Minimal a low-severity
     * headline leaves the frame completely clean rather than leaving a word floating with nothing to
     * point at.
     */
    fun headline(activeRecommendations: List<Recommendation>, level: GuidanceLevel): Recommendation? {
        val primary = activeRecommendations.firstOrNull() ?: return null
        if (level == GuidanceLevel.MINIMAL && !isAtLeastMedium(primary.severity)) return null
        return primary
    }

    private fun isAtLeastMedium(severity: Severity): Boolean =
        severity == Severity.MEDIUM || severity == Severity.HIGH

    /** How many secondary icon chips the level allows: Minimal none, Balanced 1, Coach 2. */
    fun secondaryIconCount(level: GuidanceLevel): Int = when (level) {
        GuidanceLevel.MINIMAL -> 0
        GuidanceLevel.BALANCED -> 1
        GuidanceLevel.COACH -> 2
    }

    /** Only Coach's secondary icons expand into a one-line explanation when tapped. */
    fun secondaryIconsTappable(level: GuidanceLevel): Boolean = level == GuidanceLevel.COACH

    /** The secondary recommendations shown as icons under the top bar, already trimmed for [level]. */
    fun secondary(activeRecommendations: List<Recommendation>, level: GuidanceLevel): List<Recommendation> =
        activeRecommendations.drop(1).take(secondaryIconCount(level))

    /** The one-line explanation a tapped secondary icon shows for 3 seconds: the recommendation's instruction. */
    fun explanationText(recommendation: Recommendation): String = recommendation.instruction

    /**
     * What TalkBack announces for the cue chip — the chip's words in a spoken sentence ("Move slightly
     * right" rather than the bare "Slightly right"), per the spec's accessibility section.
     */
    fun chipDescription(recommendation: Recommendation): String = when (recommendation.direction) {
        Direction.RIGHT -> "Move slightly right"
        Direction.LEFT -> "Move slightly left"
        Direction.UP -> "Raise the camera"
        Direction.DOWN -> "Lower the camera"
        Direction.CLOSER -> "Move closer"
        Direction.BACK -> "Step back"
        Direction.ROTATE_CLOCKWISE, Direction.ROTATE_COUNTER_CLOCKWISE -> "Level the camera"
        Direction.NONE -> recommendation.instruction.ifBlank { chipText(recommendation) }
    }

    /** "Composition score 82" — what the score chip announces, per the spec's accessibility section. */
    fun scoreAnnouncement(score: Int): String = "Composition score $score"

    /**
     * Whether the shoot-ready visual treatment (green ring, "SHOOT" chip) should actually show:
     * `isShootReady` alone isn't enough while `awaitingSubject` is true, since the displayed score is a
     * held, stale value in that state.
     */
    fun effectiveShootReady(isShootReady: Boolean, awaitingSubject: Boolean): Boolean =
        isShootReady && !awaitingSubject

    fun scoreTier(score: Int): ScoreTier = when {
        score >= 88 -> ScoreTier.EXCELLENT
        score >= 70 -> ScoreTier.GOOD
        else -> ScoreTier.LOW
    }

    /**
     * The quiet "nothing to fix" state: no advice and a score that is already good, so the chip clears and
     * a check mark blinks beside the shutter for a second instead of the frame simply going empty.
     */
    fun showsQuietCheck(hasHeadline: Boolean, displayScore: Int, isShootReady: Boolean, hasScene: Boolean): Boolean =
        hasScene && !hasHeadline && !isShootReady && displayScore >= QUIET_STATE_MIN_SCORE
}
