package com.compositioncoach.composition.engine

import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SmoothedComposition

/**
 * All the tuning constants for [CompositionSmoother] in one place, with defaults chosen for a ~10 Hz
 * analysis loop (so [recommendationConfirmFrames] = 3 is "stable for ~0.3s" and
 * [recommendationMinHoldFrames] = 6 is "~0.6s", per the module brief).
 *
 * @param scoreAlpha EMA smoothing factor for the displayed score in the steady state (higher = snappier).
 * @param scoreAlphaOnSubjectChange faster EMA factor used for the one update right after the number of
 *   resolved subjects changes (someone entered/left frame) — the old smoothed score is no longer a
 *   meaningful anchor, so catch up quickly instead of dragging it in slowly.
 * @param recommendationConfirmFrames a challenger recommendation must rank #1 for this many consecutive
 *   updates before it is allowed to replace the currently displayed one.
 * @param recommendationMinHoldFrames the currently displayed recommendation is protected from replacement
 *   until it has been shown for at least this many updates, *unless* its own issue disappears entirely
 *   (see [issueGoneFrames]).
 * @param issueGoneFrames if the current recommendation's id stops appearing in the raw ranked list for
 *   this many consecutive updates, it is dropped immediately (the min-hold guarantee above is waived —
 *   there is no point protecting advice for a problem that's already fixed).
 * @param oppositeDirectionExtraConfirm additional consecutive frames required, on top of
 *   [recommendationConfirmFrames], before swapping to a challenger whose [Direction] is the *opposite* of
 *   the current one (e.g. LEFT -> RIGHT) — these are exactly the swaps most likely to look like flicker.
 * @param shootReadyEnterScore smoothed score at/above which "shoot ready" turns on.
 * @param shootReadyExitScore smoothed score below which "shoot ready" turns back off; kept lower than
 *   [shootReadyEnterScore] so the state doesn't chatter right at the boundary.
 */
data class SmoothingConfig(
    val scoreAlpha: Float = 0.25f,
    val scoreAlphaOnSubjectChange: Float = 0.6f,
    val recommendationConfirmFrames: Int = 3,
    val recommendationMinHoldFrames: Int = 6,
    val issueGoneFrames: Int = 3,
    val oppositeDirectionExtraConfirm: Int = 1,
    val shootReadyEnterScore: Int = 88,
    val shootReadyExitScore: Int = 84,
)

/**
 * Temporal smoothing so the on-screen score glides instead of jittering frame-to-frame, and hysteresis so
 * the single headline instruction doesn't flip-flop when two pieces of advice are momentarily neck-and-
 * neck. See [SmoothingConfig] for the tuning knobs. Only the *headline* recommendation gets hysteresis;
 * any other recommendations [GuidanceLevel.COACH] would show ride along unsmoothed directly from
 * [CompositionResult.recommendations], since a secondary line of advice flickering slightly is much less
 * disruptive than the main instruction changing every second.
 */
class CompositionSmoother(private val config: SmoothingConfig = SmoothingConfig()) {

    private var smoothedScore: Float? = null
    private var lastSubjectCount: Int = -1

    private var current: Recommendation? = null
    private var currentHeldFrames: Int = 0
    private var currentAbsentFrames: Int = 0

    private var candidate: Recommendation? = null
    private var candidateStreak: Int = 0

    private var shootReady: Boolean = false

    fun update(result: CompositionResult): SmoothedComposition {
        val score = smoothScore(result)
        updateHeadlineRecommendation(result)
        updateShootReady(score, result)

        val headline = current
        val rest = result.recommendations.filter { it.id != headline?.id }
        val active = buildList {
            headline?.let { add(it) }
            addAll(rest)
        }

        return SmoothedComposition(
            displayScore = Math.round(score).coerceIn(0, 100),
            activeRecommendations = active,
            isShootReady = shootReady,
            scene = result.scene,
            primarySubject = result.primarySubject,
            raw = result,
        )
    }

    fun reset() {
        smoothedScore = null
        lastSubjectCount = -1
        current = null
        currentHeldFrames = 0
        currentAbsentFrames = 0
        candidate = null
        candidateStreak = 0
        shootReady = false
    }

    private fun smoothScore(result: CompositionResult): Float {
        val subjectCountChanged = lastSubjectCount != -1 && lastSubjectCount != result.subjects.size
        lastSubjectCount = result.subjects.size
        val previous = smoothedScore
        val next = if (previous == null) {
            result.rawScore
        } else {
            val alpha = if (subjectCountChanged) config.scoreAlphaOnSubjectChange else config.scoreAlpha
            previous + alpha * (result.rawScore - previous)
        }
        smoothedScore = next
        return next
    }

    private fun updateHeadlineRecommendation(result: CompositionResult) {
        val top = result.recommendations.firstOrNull()
        val existing = current

        if (existing == null) {
            current = top
            currentHeldFrames = if (top != null) 1 else 0
            currentAbsentFrames = 0
            candidate = null
            candidateStreak = 0
            return
        }

        currentHeldFrames++
        val stillPresent = result.recommendations.firstOrNull { it.id == existing.id }
        if (stillPresent != null) {
            current = stillPresent // keep the same identity, but refresh its numbers
            currentAbsentFrames = 0
        } else {
            currentAbsentFrames++
            if (currentAbsentFrames >= config.issueGoneFrames) {
                // The current issue is gone: drop it immediately, ignoring the min-hold guarantee.
                current = top
                currentHeldFrames = if (top != null) 1 else 0
                currentAbsentFrames = 0
                candidate = null
                candidateStreak = 0
                return
            }
        }

        if (top == null || top.id == current?.id) {
            candidate = null
            candidateStreak = 0
            return
        }

        val sameCandidateAsLastTime = candidate?.id == top.id
        candidate = top
        candidateStreak = if (sameCandidateAsLastTime) candidateStreak + 1 else 1

        val requiredStreak = config.recommendationConfirmFrames +
            (if (isOppositeDirection(current?.direction, top.direction)) config.oppositeDirectionExtraConfirm else 0)

        if (candidateStreak >= requiredStreak && currentHeldFrames >= config.recommendationMinHoldFrames) {
            current = top
            currentHeldFrames = 1
            currentAbsentFrames = 0
            candidate = null
            candidateStreak = 0
        }
    }

    /**
     * Shoot-ready needs a high smoothed score AND no high-severity issue in the latest raw result: a great
     * score with a pole growing out of someone's head is not "shoot". Exiting uses the lower threshold so
     * the badge does not chatter at the boundary.
     */
    private fun updateShootReady(score: Float, result: CompositionResult) {
        val hasMajorIssue = result.metrics.any { it.applicable && it.severity == Severity.HIGH }
        shootReady = when {
            hasMajorIssue -> false
            !shootReady && score >= config.shootReadyEnterScore -> true
            shootReady && score < config.shootReadyExitScore -> false
            else -> shootReady
        }
    }

    private fun isOppositeDirection(a: Direction?, b: Direction?): Boolean {
        if (a == null || b == null) return false
        val opposites = mapOf(
            Direction.LEFT to Direction.RIGHT,
            Direction.RIGHT to Direction.LEFT,
            Direction.UP to Direction.DOWN,
            Direction.DOWN to Direction.UP,
            Direction.ROTATE_CLOCKWISE to Direction.ROTATE_COUNTER_CLOCKWISE,
            Direction.ROTATE_COUNTER_CLOCKWISE to Direction.ROTATE_CLOCKWISE,
            Direction.CLOSER to Direction.BACK,
            Direction.BACK to Direction.CLOSER,
        )
        return opposites[a] == b
    }
}
