package com.compositioncoach.composition.engine

import com.compositioncoach.composition.analyzer.SubjectPlacementAnalyzer
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.OverlayGeometry
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SmoothedComposition
import com.compositioncoach.composition.model.SubjectKind
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Tuning constants for [CompositionSmoother]. Everything is expressed in *time*, not frames, so the
 * behaviour is the same on a phone that analyses 5 frames a second and one that manages 10.
 *
 * Score:
 * @param scoreTimeConstantMs time constant of the exponential moving average on the raw score. After one
 *   time constant the smoothed score has covered ~63 % of a step change; ~2 s makes a hand-held frame
 *   feel steady without lagging a deliberate reframe by more than a few seconds.
 * @param scoreTimeConstantOnSubjectChangeMs much faster constant used while the number of subjects has
 *   just changed (someone stepped in or out of frame): the old value is no longer meaningful.
 * @param displayDeadband the number on screen does not move until the smoothed score has drifted at
 *   least this many points from it. Kills small flicker entirely.
 * @param displayMinHoldMs minimum time the displayed number stays put before it may change again.
 * @param displaySnapDelta a change at least this large is shown immediately (a real reframe happened).
 * @param displayRoundTo the displayed score is rounded to the nearest multiple of this many points.
 *   Left at 1 (no extra rounding beyond the nearest integer); bumping it to e.g. 5 would show a coarser
 *   "70 / 75 / 80"-style readout for an even calmer feel — a one-line tune if field feedback ever asks
 *   for it, nothing else needs to change.
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
 * @param directionFlipHoldMs when the headline (or a secondary line)'s own advice flips to the opposite
 *   direction *while keeping the same id* — the subject sitting right on top of its target and jittering
 *   across it — the previously displayed direction and instruction text are held until the new direction
 *   has persisted this long, instead of refreshing every frame.
 * @param secondaryRecommendationConfirmMs / secondaryRecommendationMinHoldMs the same two knobs as
 *   [recommendationConfirmMs] / [recommendationMinHoldMs], but for the up-to-two secondary advice lines
 *   (COACH mode). Slightly longer than the headline's: a secondary line is read less urgently, so there is
 *   no reason for it to churn faster than the headline it sits below.
 *
 * Display geometry (arrow / target ring / region highlight / horizon line):
 * @param geometryTimeConstantMs time constant of the EMA applied to every `display*` geometry field on
 *   [SmoothedComposition] (subject anchor, placement target, headline region, horizon angle). Faster than
 *   [scoreTimeConstantMs] (~400 ms) because geometry needs to visibly track a deliberate reframe quickly;
 *   it is per-frame detector jitter this exists to absorb, not slow drift.
 * @param geometryHoldMs how long a `display*` geometry field holds its last smoothed value when its
 *   source is momentarily missing (a one- or two-frame detector blink) before it is cleared to null.
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
    val scoreTimeConstantMs: Float = 2000f,
    val scoreTimeConstantOnSubjectChangeMs: Float = 350f,
    val displayDeadband: Int = 3,
    val displayMinHoldMs: Long = 1000,
    val displaySnapDelta: Int = 10,
    val displayRoundTo: Int = 1,
    val recommendationConfirmMs: Long = 800,
    val recommendationMinHoldMs: Long = 2000,
    val recommendationMinShowMs: Long = 1500,
    val issueGoneMs: Long = 1200,
    val subjectLostDropMs: Long = 2500,
    val oppositeDirectionExtraConfirmMs: Long = 500,
    val directionFlipHoldMs: Long = 600,
    val secondaryRecommendationConfirmMs: Long = 1200,
    val secondaryRecommendationMinHoldMs: Long = 2500,
    val geometryTimeConstantMs: Float = 400f,
    val geometryHoldMs: Long = 600,
    val shootReadyEnterScore: Int = 88,
    val shootReadyExitScore: Int = 84,
    val shootReadyEnterHoldMs: Long = 400,
    val nominalUpdateIntervalMs: Long = 100,
) {
    init {
        require(shootReadyExitScore < shootReadyEnterScore) { "exit threshold must be below enter threshold" }
    }
}

/** Directions that count as "the opposite ask" for hysteresis/flip-suppression purposes. */
private val OPPOSITE_DIRECTIONS = mapOf(
    Direction.LEFT to Direction.RIGHT, Direction.RIGHT to Direction.LEFT,
    Direction.UP to Direction.DOWN, Direction.DOWN to Direction.UP,
    Direction.ROTATE_CLOCKWISE to Direction.ROTATE_COUNTER_CLOCKWISE,
    Direction.ROTATE_COUNTER_CLOCKWISE to Direction.ROTATE_CLOCKWISE,
    Direction.CLOSER to Direction.BACK, Direction.BACK to Direction.CLOSER,
)

private fun isOpposite(a: Direction?, b: Direction?): Boolean = a != null && b != null && OPPOSITE_DIRECTIONS[a] == b

/** Categories whose advice is "move the subject to a point" — the only ones the target ring makes sense for. */
private val TARGET_RING_CATEGORIES = setOf(
    MetricCategory.SUBJECT_PLACEMENT,
    MetricCategory.LOOKING_ROOM,
    MetricCategory.EDGE_TENSION,
)

/**
 * One displayed advice line's hysteresis state machine — confirm time, minimum hold/show time, issue-gone
 * and subject-lost dropping, and (new) suppression of a same-id direction flip. [CompositionSmoother] runs
 * one instance for the headline and up to two more for secondary lines, each fed a candidate pool that
 * already excludes the ids shown in higher-priority slots (see [CompositionSmoother.update]).
 */
private class AdviceSlot(
    private val confirmMs: Long,
    private val minHoldMs: Long,
    private val minShowMs: Long,
    private val issueGoneMs: Long,
    private val subjectLostDropMs: Long,
    private val oppositeExtraConfirmMs: Long,
    private val directionFlipHoldMs: Long,
) {
    var current: Recommendation? = null
        private set
    private var currentShownMs: Long = 0
    private var currentAbsentMs: Long = 0
    private var subjectLostMs: Long = 0
    private var candidate: Recommendation? = null
    private var candidateMs: Long = 0
    private var pendingFlipDirection: Direction? = null
    private var pendingFlipMs: Long = 0

    /**
     * Advances this slot by [dtMs]. [pool] is the ranked candidate list this slot is allowed to pick from
     * (already excluding ids claimed by higher-priority slots this frame); [hasSubjects] is whether any
     * subject at all is visible this frame (see [CompositionResult.subjects]). Returns the recommendation
     * this slot should display right now.
     */
    fun update(pool: List<Recommendation>, hasSubjects: Boolean, dtMs: Long): Recommendation? {
        val top = pool.firstOrNull()
        val existing = current ?: run {
            promote(top)
            return current
        }

        currentShownMs += dtMs
        val stillPresent = pool.firstOrNull { it.id == existing.id }
        if (stillPresent != null) {
            current = stabilizeDirection(stillPresent, existing, dtMs)
            currentAbsentMs = 0
            subjectLostMs = 0
        } else {
            val subjectRelated = existing.category != MetricCategory.HORIZON
            if (subjectRelated && !hasSubjects) {
                // No subject in this frame: not evidence the issue was fixed, just that we can't see it.
                subjectLostMs += dtMs
                if (subjectLostMs >= subjectLostDropMs) {
                    promote(top)
                    return current
                }
            } else {
                currentAbsentMs += dtMs
                if (currentAbsentMs >= issueGoneMs && currentShownMs >= minShowMs) {
                    promote(top) // the problem is fixed and the advice was readable long enough: drop it
                    return current
                }
            }
        }

        if (top == null || top.id == existing.id) {
            candidate = null
            candidateMs = 0
            return current
        }

        if (candidate?.id == top.id) candidateMs += dtMs else { candidate = top; candidateMs = 0 }
        val required = confirmMs + if (isOpposite(existing.direction, top.direction)) oppositeExtraConfirmMs else 0
        if (candidateMs >= required && currentShownMs >= minHoldMs) {
            promote(top)
        }
        return current
    }

    /**
     * Same id, possibly-refreshed numbers/vector/direction. If the direction flipped to the exact opposite
     * of what's currently displayed (the subject jittering across its target), the old direction and
     * instruction text are held until the new one has persisted [directionFlipHoldMs] — today's bug is
     * refreshing [current] wholesale every frame, which re-flips the instruction as fast as the detector
     * jitters.
     */
    private fun stabilizeDirection(refreshed: Recommendation, displayed: Recommendation, dtMs: Long): Recommendation {
        if (!isOpposite(displayed.direction, refreshed.direction)) {
            pendingFlipDirection = null
            pendingFlipMs = 0
            return refreshed
        }
        if (pendingFlipDirection == refreshed.direction) {
            pendingFlipMs += dtMs
        } else {
            pendingFlipDirection = refreshed.direction
            pendingFlipMs = 0
        }
        return if (pendingFlipMs >= directionFlipHoldMs) {
            pendingFlipDirection = null
            pendingFlipMs = 0
            refreshed
        } else {
            displayed // hold the previously displayed direction/instruction until the flip is confirmed
        }
    }

    /** Immediately replaces the displayed recommendation, resetting every timer (a clean slate). */
    fun promote(recommendation: Recommendation?) {
        current = recommendation
        currentShownMs = 0
        currentAbsentMs = 0
        subjectLostMs = 0
        candidate = null
        candidateMs = 0
        pendingFlipDirection = null
        pendingFlipMs = 0
    }

    /** Overwrites the displayed recommendation without touching any timer — used only for the "awaiting
     * subject" mode message, which bypasses confirmation entirely (see [CompositionSmoother]'s kdoc). */
    fun refresh(recommendation: Recommendation?) {
        current = recommendation
    }

    /** Drops the displayed recommendation at once if its id is one a higher-priority slot just claimed —
     * the same piece of advice must never appear as both the headline and a secondary line. */
    fun dropIfIdIn(excludedIds: Set<String>) {
        if (current?.id in excludedIds) promote(null)
    }

    fun reset() = promote(null)
}

/** Time-based EMA over a nullable [Float], holding its last value for [holdMs] when the source goes missing. */
private class FloatEma(private val tauMs: Float, private val holdMs: Long) {
    private var value: Float? = null
    private var missingMs: Long = 0

    fun update(source: Float?, dtMs: Long): Float? {
        if (source == null) {
            missingMs += dtMs
            if (missingMs > holdMs) value = null
            return value
        }
        missingMs = 0
        val prev = value
        value = if (prev == null) source else prev + emaAlpha(dtMs, tauMs) * (source - prev)
        return value
    }

    fun reset() {
        value = null
        missingMs = 0
    }
}

/** Time-based EMA over a nullable [NormalizedPoint]. See [FloatEma]. */
private class PointEma(private val tauMs: Float, private val holdMs: Long) {
    private var value: NormalizedPoint? = null
    private var missingMs: Long = 0

    fun update(source: NormalizedPoint?, dtMs: Long, reset: Boolean = false): NormalizedPoint? {
        if (reset) {
            value = source
            missingMs = 0
            return value
        }
        if (source == null) {
            missingMs += dtMs
            if (missingMs > holdMs) value = null
            return value
        }
        missingMs = 0
        val prev = value
        val alpha = emaAlpha(dtMs, tauMs)
        value = if (prev == null) source else NormalizedPoint(prev.x + alpha * (source.x - prev.x), prev.y + alpha * (source.y - prev.y))
        return value
    }

    fun reset() {
        value = null
        missingMs = 0
    }
}

/** Time-based EMA over a nullable [NormalizedRect]. See [FloatEma]. */
private class RectEma(private val tauMs: Float, private val holdMs: Long) {
    private var value: NormalizedRect? = null
    private var missingMs: Long = 0

    fun update(source: NormalizedRect?, dtMs: Long, reset: Boolean = false): NormalizedRect? {
        if (reset) {
            value = source
            missingMs = 0
            return value
        }
        if (source == null) {
            missingMs += dtMs
            if (missingMs > holdMs) value = null
            return value
        }
        missingMs = 0
        val prev = value
        val alpha = emaAlpha(dtMs, tauMs)
        value = if (prev == null) {
            source
        } else {
            NormalizedRect(
                prev.left + alpha * (source.left - prev.left),
                prev.top + alpha * (source.top - prev.top),
                prev.right + alpha * (source.right - prev.right),
                prev.bottom + alpha * (source.bottom - prev.bottom),
            )
        }
        return value
    }

    fun reset() {
        value = null
        missingMs = 0
    }
}

private fun emaAlpha(dtMs: Long, tauMs: Float): Float = 1f - exp(-dtMs / tauMs)

private fun NormalizedPoint.clampToFrame(): NormalizedPoint = NormalizedPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))

private fun OverlayGeometry.Line.clampToFrame(): OverlayGeometry.Line =
    OverlayGeometry.Line(start.clampToFrame(), end.clampToFrame(), label)

/** Rebuilds a horizon line from an angle the same way [com.compositioncoach.composition.analyzer.HorizonAnalyzer] does. */
private fun horizonLineForAngle(angleDegrees: Float): OverlayGeometry.Line {
    val halfRise = (tan(Math.toRadians(angleDegrees.toDouble())) * 0.5).toFloat()
    return OverlayGeometry.Line(
        start = NormalizedPoint(0f, 0.5f - halfRise),
        end = NormalizedPoint(1f, 0.5f + halfRise),
        label = "horizon",
    )
}

/**
 * Temporal smoothing between the per-frame [CompositionResult] and what the UI shows.
 *
 *  - The score is an exponential moving average, and the *displayed* integer is additionally gated by a
 *    dead band and a minimum hold time, so it reads as a steady judgement that moves when the framing
 *    actually changes, not a live meter that ticks every second.
 *  - Every displayed advice line has hysteresis via [AdviceSlot]: a challenger must win for a while before
 *    it takes over, the current advice is held long enough to act on, opposite directions need extra
 *    confirmation, and a same-id direction flip (the subject jittering across its target) is suppressed
 *    until it persists. The headline runs one slot; up to two secondary lines (COACH mode) each run their
 *    own slot, slightly slower to change, fed from the raw ranking with the ids already shown in
 *    higher-priority slots removed — nothing unsmoothed reaches the UI any more.
 *  - The display geometry (subject anchor, placement target, headline region, horizon line) is likewise
 *    time-smoothed — see [SmoothedComposition]'s kdoc for what drives the arrow/ring/highlight/level line.
 *  - Shoot-ready needs a sustained high score and no high-severity issue, and turns off at a lower score
 *    than it turned on at.
 *  - While [CompositionResult.awaitingSubject] is true (a declared shooting mode is still waiting for its
 *    subject to appear — see [IntentSubjectCoach]) all of the above is suspended: the meaningless `0`
 *    score is never fed into the EMA (the displayed number just holds at its last real value), shoot-ready
 *    is forced off, and the "find your subject" recommendation is shown immediately — it is a mode
 *    message describing what the coach is doing right now, not competing framing advice that needs to
 *    earn its place through the usual confirmation delay. Normal smoothing resumes, from a clean
 *    recommendation and geometry slate, the moment a frame with a real subject comes back.
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

    private val headlineSlot = AdviceSlot(
        confirmMs = config.recommendationConfirmMs,
        minHoldMs = config.recommendationMinHoldMs,
        minShowMs = config.recommendationMinShowMs,
        issueGoneMs = config.issueGoneMs,
        subjectLostDropMs = config.subjectLostDropMs,
        oppositeExtraConfirmMs = config.oppositeDirectionExtraConfirmMs,
        directionFlipHoldMs = config.directionFlipHoldMs,
    )
    private val secondarySlots = List(2) {
        AdviceSlot(
            confirmMs = config.secondaryRecommendationConfirmMs,
            minHoldMs = config.secondaryRecommendationMinHoldMs,
            minShowMs = config.recommendationMinShowMs,
            issueGoneMs = config.issueGoneMs,
            subjectLostDropMs = config.subjectLostDropMs,
            oppositeExtraConfirmMs = config.oppositeDirectionExtraConfirmMs,
            directionFlipHoldMs = config.directionFlipHoldMs,
        )
    }

    private val anchorEma = PointEma(config.geometryTimeConstantMs, config.geometryHoldMs)
    private val subjectBoundsEma = RectEma(config.geometryTimeConstantMs, config.geometryHoldMs)
    private val targetEma = PointEma(config.geometryTimeConstantMs, config.geometryHoldMs)
    private val regionEma = RectEma(config.geometryTimeConstantMs, config.geometryHoldMs)
    private val horizonAngleEma = FloatEma(config.geometryTimeConstantMs, config.geometryHoldMs)
    private var lastSubjectKey: Pair<Int, SubjectKind>? = null
    private var lastHeadlineIdForRegion: String? = null

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
            resetGeometry()
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
            // The subject reappeared: drop the "find subject" mode message and every slot's confirmation
            // state right away rather than making the resumed advice out-wait recommendationMinHoldMs as if
            // it were an ordinary challenger, and start every display geometry EMA from a clean slate.
            headlineSlot.reset()
            secondarySlots.forEach { it.reset() }
            resetGeometry()
            wasAwaitingSubject = false
        }

        val score = smoothScore(result, dtMs)
        updateDisplayedScore(score, dtMs)

        val hasSubjects = result.subjects.isNotEmpty()
        val headline = headlineSlot.update(result.recommendations, hasSubjects, dtMs)
        val usedIds = mutableSetOf<String>()
        headline?.let { usedIds += it.id }

        val secondaryResults = mutableListOf<Recommendation>()
        for (slot in secondarySlots) {
            slot.dropIfIdIn(usedIds)
            val pool = result.recommendations.filter { it.id !in usedIds }
            val rec = slot.update(pool, hasSubjects, dtMs)
            if (rec != null) {
                secondaryResults += rec
                usedIds += rec.id
            }
        }

        updateShootReady(score, result, dtMs)
        val geometry = updateGeometry(result, headline, dtMs)

        return SmoothedComposition(
            displayScore = displayedScore,
            activeRecommendations = buildList { headline?.let { add(it) }; addAll(secondaryResults) },
            isShootReady = shootReady,
            scene = result.scene,
            primarySubject = result.primarySubject,
            raw = result,
            awaitingSubject = false,
            displayAnchor = geometry.anchor,
            displayTarget = geometry.target,
            displayRegion = geometry.region,
            displayHorizon = geometry.horizon,
            displaySubjectBounds = geometry.subjectBounds,
        )
    }

    /** Shows the mode message as the headline at once — no confirmation delay, see the class kdoc. */
    private fun showAwaitingHeadlineImmediately(recommendation: Recommendation?) {
        if (headlineSlot.current?.id != recommendation?.id) headlineSlot.promote(recommendation) else headlineSlot.refresh(recommendation)
    }

    fun reset() {
        lastTimestampNanos = Long.MIN_VALUE
        smoothedScore = null
        displayedScore = 0
        sinceDisplayChangeMs = 0
        subjectChangeBoostMs = 0
        lastSubjectCount = -1
        headlineSlot.reset()
        secondarySlots.forEach { it.reset() }
        resetGeometry()
        shootReady = false
        aboveEnterMs = 0
        wasAwaitingSubject = false
    }

    private fun resetGeometry() {
        anchorEma.reset()
        subjectBoundsEma.reset()
        targetEma.reset()
        regionEma.reset()
        horizonAngleEma.reset()
        lastSubjectKey = null
        lastHeadlineIdForRegion = null
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
            previous + emaAlpha(dtMs, tau) * (result.rawScore - previous)
        }
        smoothedScore = next
        return next
    }

    private fun roundForDisplay(score: Float): Int {
        val step = config.displayRoundTo.coerceAtLeast(1)
        return ((score / step).roundToInt() * step).coerceIn(0, 100)
    }

    private fun updateDisplayedScore(score: Float, dtMs: Long) {
        val target = roundForDisplay(score)
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

    // --- display geometry -----------------------------------------------------------------------------

    private class GeometryFrame(
        val anchor: NormalizedPoint?,
        val subjectBounds: NormalizedRect?,
        val target: NormalizedPoint?,
        val region: NormalizedRect?,
        val horizon: OverlayGeometry.Line?,
    )

    private fun updateGeometry(result: CompositionResult, headline: Recommendation?, dtMs: Long): GeometryFrame {
        val primary = result.primarySubject
        val subjectKey = primary?.let { it.id to it.kind }
        val subjectChanged = subjectKey != null && subjectKey != lastSubjectKey
        if (subjectKey != null) lastSubjectKey = subjectKey

        val anchor = anchorEma.update(primary?.anchorPoint, dtMs, reset = subjectChanged)
        val subjectBounds = subjectBoundsEma.update(primary?.bounds, dtMs, reset = subjectChanged)

        val headlineIdChanged = headline?.id != lastHeadlineIdForRegion
        lastHeadlineIdForRegion = headline?.id
        val region = regionEma.update(headline?.region, dtMs, reset = headlineIdChanged)

        val target = targetEma.update(extractTargetPoint(result, headline), dtMs)

        val horizonAngle = horizonAngleEma.update(extractHorizonAngle(result), dtMs)
        val horizon = horizonAngle?.let { horizonLineForAngle(it).clampToFrame() }

        return GeometryFrame(
            anchor = anchor?.clampToFrame(),
            subjectBounds = subjectBounds?.clampToFrame(),
            target = target?.clampToFrame(),
            region = region?.clampToFrame(),
            horizon = horizon,
        )
    }

    private fun extractTargetPoint(result: CompositionResult, headline: Recommendation?): NormalizedPoint? {
        val category = headline?.category ?: return null
        if (category !in TARGET_RING_CATEGORIES) return null
        val placementMetric = result.metrics.firstOrNull { it.category == MetricCategory.SUBJECT_PLACEMENT && it.applicable }
            ?: return null
        val target = placementMetric.geometry.filterIsInstance<OverlayGeometry.TargetPoint>().firstOrNull()?.point ?: return null
        val anchor = result.primarySubject?.anchorPoint ?: return null
        if (anchor.distanceTo(target) <= SubjectPlacementAnalyzer.PLACEMENT_DEAD_ZONE) return null
        return target
    }

    private fun extractHorizonAngle(result: CompositionResult): Float? {
        val metric = result.metrics.firstOrNull { it.category == MetricCategory.HORIZON && it.applicable } ?: return null
        val line = metric.geometry.filterIsInstance<OverlayGeometry.Line>().firstOrNull() ?: return null
        return Math.toDegrees(atan2((line.end.y - line.start.y).toDouble(), (line.end.x - line.start.x).toDouble())).toFloat()
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

    companion object {
        /** Longest gap counted between two updates; anything longer (pause, camera switch) counts as this. */
        private const val MAX_STEP_MS = 500L
        private const val SUBJECT_CHANGE_BOOST_WINDOW_MS = 600L
    }
}
