package com.compositioncoach.composition.engine

import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SmoothedComposition
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Tuning constants for [CompositionSmoother]. Everything is expressed in *time*, not frames, so the
 * behaviour is the same on a phone that analyses 5 frames a second and one that manages 10.
 *
 * Score:
 * @param scoreTimeConstantMs time constant of the exponential moving average on the raw score. After one
 *   time constant the smoothed score has covered ~63 % of a step change; ~1.5 s makes a hand-held frame
 *   feel steady without lagging a deliberate reframe by more than a couple of seconds.
 * @param scoreTimeConstantOnSubjectChangeMs much faster constant used while the number of subjects has
 *   just changed (someone stepped in or out of frame): the old value is no longer meaningful.
 * @param displayDeadband the number on screen does not move until the smoothed score has drifted at
 *   least this many points from it. Kills the ±1 flicker entirely.
 * @param displayMinHoldMs minimum time the displayed number stays put before it may change again.
 * @param displaySnapDelta a change at least this large is shown immediately (a real reframe happened).
 *
 * Recommendations:
 * @param recommendationConfirmMs a challenger must be the top-ranked advice continuously for this long
 *   before it replaces the current headline.
 * @param recommendationMinHoldMs the current headline is protected from replacement until it has been
 *   shown this long, so the photographer has time to read and act on it.
 * @param recommendationMinShowMs advice never disappears before it has been on screen this long, even if the
 *   issue clears immediately: a line that flashes for half a second cannot be read.
 * @param issueGoneMs if the current headline's issue has been absent from the raw ranking for this long
 *   (while the subject was still being seen), it is dropped: the problem is fixed.
 * @param subjectLostDropMs frames with no subject at all are not evidence that a subject-related issue was
 *   fixed (the detector blinked, or the person is momentarily out of frame), so they do not count toward
 *   [issueGoneMs]; instead the advice is dropped only after the subject has been missing this long.
 * @param oppositeDirectionExtraConfirmMs extra confirmation required when the challenger asks for the
 *   opposite action (LEFT after RIGHT, UP after DOWN...), which is the swap that reads as flicker.
 *
 * Shoot-ready:
 * @param shootReadyEnterScore smoothed score needed to light up SHOOT, held continuously for
 *   [shootReadyEnterHoldMs] so a momentary spike does not flash it.
 * @param shootReadyExitScore below this the state turns off (lower than the enter score: hysteresis).
 *
 * @param nominalUpdateIntervalMs assumed spacing between updates when frame timestamps do not advance
 *   (equal or missing timestamps, e.g. in tests). Real frames use their own timestamps.
 */
data class SmoothingConfig(
    val scoreTimeConstantMs: Float = 1500f,
    val scoreTimeConstantOnSubjectChangeMs: Float = 350f,
    val displayDeadband: Int = 2,
    val displayMinHoldMs: Long = 700,
    val displaySnapDelta: Int = 10,
    val recommendationConfirmMs: Long = 800,
    val recommendationMinHoldMs: Long = 2000,
    val recommendationMinShowMs: Long = 1500,
    val issueGoneMs: Long = 1200,
    val subjectLostDropMs: Long = 2500,
    val oppositeDirectionExtraConfirmMs: Long = 500,
    val shootReadyEnterScore: Int = 88,
    val shootReadyExitScore: Int = 84,
    val shootReadyEnterHoldMs: Long = 400,
    val nominalUpdateIntervalMs: Long = 100,
) {
    init {
        require(shootReadyExitScore < shootReadyEnterScore) { "exit threshold must be below enter threshold" }
    }
}

/**
 * Temporal smoothing between the per-frame [CompositionResult] and what the UI shows.
 *
 *  - The score is an exponential moving average, and the *displayed* integer is additionally gated by a
 *    dead band and a minimum hold time, so it reads as a steady judgement that moves when the framing
 *    actually changes, not a live meter that ticks every second.
 *  - The headline recommendation has hysteresis: a challenger must win for a while before it takes over,
 *    the current advice is held long enough to act on, and opposite directions need extra confirmation.
 *    Secondary lines (COACH mode) ride along unsmoothed; a flicker there is far less disruptive.
 *  - Shoot-ready needs a sustained high score and no high-severity issue, and turns off at a lower score
 *    than it turned on at.
 *  - While [CompositionResult.awaitingSubject] is true (a declared shooting mode is still waiting for its
 *    subject to appear — see [IntentSubjectCoach]) all of the above is suspended: the meaningless `0`
 *    score is never fed into the EMA (the displayed number just holds at its last real value), shoot-ready
 *    is forced off, and the "find your subject" recommendation is shown immediately — it is a mode
 *    message describing what the coach is doing right now, not competing framing advice that needs to
 *    earn its place through the usual confirmation delay. Normal smoothing resumes, from a clean
 *    recommendation slate, the moment a frame with a real subject comes back.
 *
 * All timing is driven by [CompositionResult.timestampNanos]; see [SmoothingConfig.nominalUpdateIntervalMs]
 * for the fallback when timestamps do not advance. Time between updates is capped so a pause (app in the
 * background, camera switch) does not count as a long confirmation.
 */
class CompositionSmoother(private val config: SmoothingConfig = SmoothingConfig()) {

    private var lastTimestampNanos: Long = Long.MIN_VALUE

    private var smoothedScore: Float? = null
    private var displayedScore: Int = 0
    private var sinceDisplayChangeMs: Long = 0
    private var subjectChangeBoostMs: Long = 0
    private var lastSubjectCount: Int = -1

    private var current: Recommendation? = null
    private var currentShownMs: Long = 0
    private var currentAbsentMs: Long = 0
    private var subjectLostMs: Long = 0
    private var candidate: Recommendation? = null
    private var candidateMs: Long = 0

    private var shootReady: Boolean = false
    private var aboveEnterMs: Long = 0

    private var wasAwaitingSubject: Boolean = false

    fun update(result: CompositionResult): SmoothedComposition {
        val dtMs = elapsedSince(result.timestampNanos)

        if (result.awaitingSubject) {
            wasAwaitingSubject = true
            showAwaitingHeadlineImmediately(result.recommendations.firstOrNull())
            shootReady = false
            aboveEnterMs = 0
            return SmoothedComposition(
                displayScore = displayedScore,
                activeRecommendations = result.recommendations,
                isShootReady = false,
                scene = result.scene,
                primarySubject = result.primarySubject,
                raw = result,
                awaitingSubject = true,
            )
        }
        if (wasAwaitingSubject) {
            // The subject reappeared: drop the "find subject" mode message and its confirmation state right
            // away rather than making the resumed advice out-wait recommendationMinHoldMs as if it were an
            // ordinary challenger.
            promote(null)
            wasAwaitingSubject = false
        }

        val score = smoothScore(result, dtMs)
        updateDisplayedScore(score, dtMs)
        updateHeadlineRecommendation(result, dtMs)
        updateShootReady(score, result, dtMs)

        val headline = current
        val rest = result.recommendations.filter { it.id != headline?.id }
        return SmoothedComposition(
            displayScore = displayedScore,
            activeRecommendations = buildList { headline?.let { add(it) }; addAll(rest) },
            isShootReady = shootReady,
            scene = result.scene,
            primarySubject = result.primarySubject,
            raw = result,
            awaitingSubject = false,
        )
    }

    /** Shows the mode message as the headline at once — no confirmation delay, see the class kdoc. */
    private fun showAwaitingHeadlineImmediately(recommendation: Recommendation?) {
        if (current?.id != recommendation?.id) promote(recommendation) else current = recommendation
    }

    fun reset() {
        lastTimestampNanos = Long.MIN_VALUE
        smoothedScore = null
        displayedScore = 0
        sinceDisplayChangeMs = 0
        subjectChangeBoostMs = 0
        lastSubjectCount = -1
        current = null
        currentShownMs = 0
        currentAbsentMs = 0
        subjectLostMs = 0
        candidate = null
        candidateMs = 0
        shootReady = false
        aboveEnterMs = 0
        wasAwaitingSubject = false
    }

    // --- time ---------------------------------------------------------------------------------------

    private fun elapsedSince(timestampNanos: Long): Long {
        val last = lastTimestampNanos
        lastTimestampNanos = timestampNanos
        if (last == Long.MIN_VALUE) return 0L
        val dt = (timestampNanos - last) / 1_000_000L
        return if (dt <= 0L) config.nominalUpdateIntervalMs else dt.coerceAtMost(MAX_STEP_MS)
    }

    // --- score --------------------------------------------------------------------------------------

    private fun smoothScore(result: CompositionResult, dtMs: Long): Float {
        val subjectCountChanged = lastSubjectCount != -1 && lastSubjectCount != result.subjects.size
        lastSubjectCount = result.subjects.size
        if (subjectCountChanged) subjectChangeBoostMs = SUBJECT_CHANGE_BOOST_WINDOW_MS
        subjectChangeBoostMs = (subjectChangeBoostMs - dtMs).coerceAtLeast(0)

        val previous = smoothedScore
        val next = if (previous == null) {
            result.rawScore
        } else {
            val tau = if (subjectChangeBoostMs > 0) config.scoreTimeConstantOnSubjectChangeMs else config.scoreTimeConstantMs
            val alpha = 1f - exp(-dtMs / tau)
            previous + alpha * (result.rawScore - previous)
        }
        smoothedScore = next
        return next
    }

    private fun updateDisplayedScore(score: Float, dtMs: Long) {
        val target = score.roundToInt().coerceIn(0, 100)
        if (smoothedScore != null && sinceDisplayChangeMs == 0L && displayedScore == 0 && dtMs == 0L) {
            // First update: show the initial value straight away.
            displayedScore = target
            return
        }
        sinceDisplayChangeMs += dtMs
        val delta = abs(target - displayedScore)
        val allowed = delta >= config.displaySnapDelta ||
            (delta >= config.displayDeadband && sinceDisplayChangeMs >= config.displayMinHoldMs)
        if (allowed) {
            displayedScore = target
            sinceDisplayChangeMs = 0
        }
    }

    // --- recommendations ----------------------------------------------------------------------------

    private fun updateHeadlineRecommendation(result: CompositionResult, dtMs: Long) {
        val top = result.recommendations.firstOrNull()
        val existing = current

        if (existing == null) {
            promote(top)
            return
        }

        currentShownMs += dtMs
        val stillPresent = result.recommendations.firstOrNull { it.id == existing.id }
        if (stillPresent != null) {
            current = stillPresent // same advice, refreshed numbers/vector
            currentAbsentMs = 0
            subjectLostMs = 0
        } else {
            val subjectRelated = existing.category != MetricCategory.HORIZON
            if (subjectRelated && result.subjects.isEmpty()) {
                // No subject in this frame: not evidence the issue was fixed, just that we can't see it.
                subjectLostMs += dtMs
                if (subjectLostMs >= config.subjectLostDropMs) {
                    promote(top)
                    return
                }
            } else {
                currentAbsentMs += dtMs
                if (currentAbsentMs >= config.issueGoneMs && currentShownMs >= config.recommendationMinShowMs) {
                    promote(top) // the problem is fixed and the advice was readable long enough: drop it
                    return
                }
            }
        }

        if (top == null || top.id == existing.id) {
            candidate = null
            candidateMs = 0
            return
        }

        if (candidate?.id == top.id) candidateMs += dtMs else { candidate = top; candidateMs = 0 }
        val required = config.recommendationConfirmMs +
            if (isOpposite(existing.direction, top.direction)) config.oppositeDirectionExtraConfirmMs else 0
        if (candidateMs >= required && currentShownMs >= config.recommendationMinHoldMs) {
            promote(top)
        }
    }

    private fun promote(recommendation: Recommendation?) {
        current = recommendation
        currentShownMs = 0
        currentAbsentMs = 0
        subjectLostMs = 0
        candidate = null
        candidateMs = 0
    }

    // --- shoot-ready --------------------------------------------------------------------------------

    private fun updateShootReady(score: Float, result: CompositionResult, dtMs: Long) {
        val hasMajorIssue = result.metrics.any { it.applicable && it.severity == Severity.HIGH }
        if (hasMajorIssue) {
            shootReady = false
            aboveEnterMs = 0
            return
        }
        if (shootReady) {
            if (score < config.shootReadyExitScore) { shootReady = false; aboveEnterMs = 0 }
            return
        }
        if (score >= config.shootReadyEnterScore) {
            aboveEnterMs += dtMs
            if (aboveEnterMs >= config.shootReadyEnterHoldMs) shootReady = true
        } else {
            aboveEnterMs = 0
        }
    }

    private fun isOpposite(a: Direction?, b: Direction?): Boolean = a != null && b != null && OPPOSITES[a] == b

    companion object {
        /** Longest gap counted between two updates; anything longer (pause, camera switch) counts as this. */
        private const val MAX_STEP_MS = 500L
        private const val SUBJECT_CHANGE_BOOST_WINDOW_MS = 600L

        private val OPPOSITES = mapOf(
            Direction.LEFT to Direction.RIGHT, Direction.RIGHT to Direction.LEFT,
            Direction.UP to Direction.DOWN, Direction.DOWN to Direction.UP,
            Direction.ROTATE_CLOCKWISE to Direction.ROTATE_COUNTER_CLOCKWISE,
            Direction.ROTATE_COUNTER_CLOCKWISE to Direction.ROTATE_CLOCKWISE,
            Direction.CLOSER to Direction.BACK, Direction.BACK to Direction.CLOSER,
        )
    }
}
