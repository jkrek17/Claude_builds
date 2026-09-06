package com.compositioncoach.composition

import com.compositioncoach.composition.engine.CompositionSmoother
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.DetectedSubject
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.OverlayGeometry
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CompositionSmootherTest {

    private fun recommendation(id: String, direction: Direction) = Recommendation(
        id = id,
        category = MetricCategory.SUBJECT_PLACEMENT,
        priority = Priority.MEDIUM,
        confidence = 0.8f,
        severity = Severity.MEDIUM,
        title = "t",
        instruction = "i",
        direction = direction,
    )

    private fun resultWith(recommendation: Recommendation?, score: Float): CompositionResult =
        CompositionResult.empty().copy(
            rawScore = score,
            score = Math.round(score),
            recommendations = listOfNotNull(recommendation),
            subjects = listOf(), // constant subject count (0) across updates unless a test overrides it
        )

    @Test
    fun `alternating left-right advice does not flip the displayed recommendation every frame`() {
        val smoother = CompositionSmoother()
        val left = recommendation("left", Direction.LEFT)
        val right = recommendation("right", Direction.RIGHT)

        val displayedIds = mutableListOf<String?>()
        repeat(20) { i ->
            val raw = if (i % 2 == 0) left else right
            val smoothed = smoother.update(resultWith(raw, 70f))
            displayedIds += smoothed.primaryRecommendation?.id
        }

        // The raw advice alternated every single frame; the displayed one must not have.
        val changes = displayedIds.zipWithNext().count { (a, b) -> a != b }
        assertTrue("displayed advice changed too often: $displayedIds", changes < 5)
    }

    @Test
    fun `score EMA converges toward a steady raw score`() {
        val smoother = CompositionSmoother()
        var last = 0
        repeat(30) {
            last = smoother.update(resultWith(null, 90f)).displayScore
        }
        assertTrue("expected score to converge near 90, was $last", abs(last - 90) <= 1)
    }

    @Test
    fun `a stable recommendation is held for at least the minimum frames even if briefly challenged`() {
        val smoother = CompositionSmoother()
        val steady = recommendation("steady", Direction.LEFT)
        val challenger = recommendation("challenger", Direction.UP)

        // Establish "steady" as current.
        smoother.update(resultWith(steady, 70f))
        // A single-frame challenge should not be enough to replace it (needs sustained confirmation).
        val afterOneChallenge = smoother.update(resultWith(challenger, 70f))
        assertEquals("steady", afterOneChallenge.primaryRecommendation?.id)
    }

    @Test
    fun `shoot ready needs a sustained high score and has hysteresis on exit`() {
        val smoother = CompositionSmoother()
        // Score EMA starts equal to the first raw score; a single high frame must not flash SHOOT.
        val first = smoother.update(resultWith(null, 95f))
        assertTrue(!first.isShootReady)
        // ~0.5 s of sustained high score (nominal 100 ms per update) lights it up.
        var ready = first
        repeat(6) { ready = smoother.update(resultWith(null, 95f)) }
        assertTrue(ready.isShootReady)

        // A small dip that stays above the exit threshold should not turn shoot-ready off.
        val stillReady = smoother.update(resultWith(null, 90f))
        assertTrue(stillReady.isShootReady)
    }

    @Test
    fun `displayed score ignores small jitter and holds between changes`() {
        val smoother = CompositionSmoother()
        val shown = mutableListOf<Int>()
        // Raw score jitters by a couple of points every frame, as detections wobble on a still subject.
        repeat(40) { i -> shown += smoother.update(resultWith(null, if (i % 2 == 0) 71f else 73f)).displayScore }
        val changes = shown.zipWithNext().count { (a, b) -> a != b }
        assertTrue("displayed score changed $changes times for jitter: $shown", changes <= 1)
    }

    @Test
    fun `a large real change shows quickly`() {
        val smoother = CompositionSmoother()
        repeat(5) { smoother.update(resultWith(null, 40f)) }
        var shown = 40
        // 2 s at nominal rate: with scoreTimeConstantMs=2000 that's one full time constant, ~63% of the way.
        repeat(20) { shown = smoother.update(resultWith(null, 90f)).displayScore }
        assertTrue("expected the display to move well toward 90 within 2 s, was $shown", shown >= 65)
    }

    @Test
    fun `advice swaps only after the challenger has been consistent for the confirm time`() {
        val smoother = CompositionSmoother()
        val steady = recommendation("steady", Direction.LEFT)
        val challenger = recommendation("challenger", Direction.UP)
        repeat(25) { smoother.update(resultWith(steady, 70f)) } // 2.5 s: past the minimum hold
        var shownId: String? = null
        repeat(5) { shownId = smoother.update(resultWith(challenger, 70f)).primaryRecommendation?.id } // 0.5 s
        assertEquals("steady", shownId)
        repeat(5) { shownId = smoother.update(resultWith(challenger, 70f)).primaryRecommendation?.id } // 1.0 s total
        assertEquals("challenger", shownId)
    }

    private fun resultWithSubject(recommendation: Recommendation?, score: Float): CompositionResult =
        resultWith(recommendation, score).copy(
            subjects = listOf(
                com.compositioncoach.composition.model.DetectedSubject(
                    id = 0,
                    kind = com.compositioncoach.composition.model.SubjectKind.FACE,
                    bounds = com.compositioncoach.composition.model.NormalizedRect(0.4f, 0.3f, 0.6f, 0.5f),
                ),
            ),
        )

    @Test
    fun `advice is dropped once its issue is gone and it has been readable long enough`() {
        val smoother = CompositionSmoother()
        val steady = recommendation("steady", Direction.LEFT)
        repeat(3) { smoother.update(resultWithSubject(steady, 70f)) } // shown 0.3 s
        var shownId: String? = "steady"
        // Issue gone but the subject is still visible: must stay until it has been shown 1.5 s...
        repeat(10) { shownId = smoother.update(resultWithSubject(null, 80f)).primaryRecommendation?.id } // 1.3 s total
        assertEquals("steady", shownId)
        // ...then drop (issue absent >= 1.2 s and shown >= 1.5 s).
        repeat(4) { shownId = smoother.update(resultWithSubject(null, 80f)).primaryRecommendation?.id }
        assertEquals(null, shownId)
    }

    @Test
    fun `losing the subject briefly does not drop subject advice`() {
        val smoother = CompositionSmoother()
        val steady = recommendation("steady", Direction.LEFT)
        repeat(20) { smoother.update(resultWithSubject(steady, 70f)) }
        var shownId: String? = null
        // Detector blink: 1.5 s of frames with no subject at all.
        repeat(15) { shownId = smoother.update(resultWith(null, 70f)).primaryRecommendation?.id }
        assertEquals("steady", shownId)
        // Subject gone for good: dropped after 2.5 s without a subject.
        repeat(12) { shownId = smoother.update(resultWith(null, 70f)).primaryRecommendation?.id }
        assertEquals(null, shownId)
    }

    @Test
    fun `reset clears all smoother state`() {
        val smoother = CompositionSmoother()
        smoother.update(resultWith(recommendation("a", Direction.LEFT), 95f))
        smoother.reset()
        val afterReset = smoother.update(resultWith(null, 10f))
        assertEquals(10, afterReset.displayScore)
        assertTrue(!afterReset.isShootReady)
    }

    // --- secondary advice slots --------------------------------------------------------------------

    private fun resultWithAll(recommendations: List<Recommendation>, score: Float): CompositionResult =
        CompositionResult.empty().copy(
            rawScore = score,
            score = Math.round(score),
            recommendations = recommendations,
            subjects = listOf(),
        )

    @Test
    fun `secondary line does not change on alternating raw secondaries`() {
        val smoother = CompositionSmoother()
        val headline = recommendation("headline", Direction.LEFT)
        val secA = recommendation("secA", Direction.UP)
        val secB = recommendation("secB", Direction.DOWN)

        val displayedSecondaryIds = mutableListOf<String?>()
        repeat(30) { i ->
            val secondary = if (i % 2 == 0) secA else secB
            val smoothed = smoother.update(resultWithAll(listOf(headline, secondary), 70f))
            displayedSecondaryIds += smoothed.activeRecommendations.getOrNull(1)?.id
        }

        // The raw secondary alternated every single frame; the displayed secondary line must not have.
        val changes = displayedSecondaryIds.zipWithNext().count { (a, b) -> a != b }
        assertTrue("displayed secondary changed too often: $displayedSecondaryIds", changes < 5)
    }

    @Test
    fun `a secondary line never duplicates the headline id while the headline hysteresis catches up`() {
        val smoother = CompositionSmoother()
        val a = recommendation("a", Direction.LEFT)
        val b = recommendation("b", Direction.UP)

        // "a" is the headline, "b" rides as the secondary line.
        repeat(25) { smoother.update(resultWithAll(listOf(a, b), 70f)) } // past the headline's minimum hold

        // Now "b" overtakes "a" in the raw ranking; once the headline swaps to "b", the secondary slot must
        // never keep showing "b" too.
        var smoothed = smoother.update(resultWithAll(listOf(a, b), 70f))
        repeat(30) {
            smoothed = smoother.update(resultWithAll(listOf(b, a), 70f))
            val ids = smoothed.activeRecommendations.map { it.id }
            assertEquals("duplicate id shown across lines: $ids", ids.size, ids.distinct().size)
        }
    }

    // --- display geometry ---------------------------------------------------------------------------

    /** A minimal but realistic result: a primary subject, a SUBJECT_PLACEMENT metric carrying a
     * [OverlayGeometry.TargetPoint], and a headline recommendation asking to move toward it. */
    private fun placementResult(anchor: NormalizedPoint?, target: NormalizedPoint, timestampNanos: Long): CompositionResult {
        val subject = anchor?.let {
            DetectedSubject(
                id = 1,
                kind = SubjectKind.FACE,
                bounds = NormalizedRect(it.x - 0.05f, it.y - 0.05f, it.x + 0.05f, it.y + 0.05f),
            )
        }
        val rec = recommendation("placement.thirds", Direction.RIGHT)
        val metric = CompositionMetric(
            category = MetricCategory.SUBJECT_PLACEMENT,
            analyzerName = "SubjectPlacementAnalyzer",
            score = 0.5f,
            confidence = 0.9f,
            severity = Severity.LOW,
            recommendation = rec,
            geometry = listOf(OverlayGeometry.TargetPoint(target, "target")),
        )
        return CompositionResult.empty(timestampNanos).copy(
            rawScore = 70f,
            score = 70,
            recommendations = listOf(rec),
            metrics = listOf(metric),
            subjects = listOfNotNull(subject),
            primarySubject = subject,
        )
    }

    @Test
    fun `display geometry eases toward a new position and holds through a brief blink`() {
        val smoother = CompositionSmoother()
        val start = NormalizedPoint(0.3f, 0.3f)
        val startTarget = NormalizedPoint(0.5f, 0.3f)
        val moved = NormalizedPoint(0.7f, 0.6f)
        val movedTarget = NormalizedPoint(0.9f, 0.6f)
        var t = 0L
        var last = smoother.update(placementResult(start, startTarget, t))
        repeat(10) { t += 100L * 1_000_000L; last = smoother.update(placementResult(start, startTarget, t)) }
        assertTrue("expected anchor settled near $start, was ${last.displayAnchor}", last.displayAnchor!!.distanceTo(start) < 0.02f)

        // Subject jumps to a new position: the display should not snap there instantly (it's an EMA)...
        t += 100L * 1_000_000L
        val justAfterJump = smoother.update(placementResult(moved, movedTarget, t))
        assertTrue(
            "expected the display to still be easing toward the new anchor, was ${justAfterJump.displayAnchor}",
            justAfterJump.displayAnchor!!.distanceTo(moved) > 0.05f,
        )
        // ...but converges there after several time constants (geometryTimeConstantMs = 400 ms default).
        repeat(20) { t += 100L * 1_000_000L; last = smoother.update(placementResult(moved, movedTarget, t)) }
        assertTrue("expected anchor to converge near $moved, was ${last.displayAnchor}", last.displayAnchor!!.distanceTo(moved) < 0.02f)
        assertTrue(
            "expected target to converge near $movedTarget, was ${last.displayTarget}",
            last.displayTarget!!.distanceTo(movedTarget) < 0.02f,
        )

        // Detector blink: 3 frames with no subject at all must not clear the held display.
        repeat(3) { t += 100L * 1_000_000L; last = smoother.update(placementResult(null, movedTarget, t)) }
        val heldAnchor = last.displayAnchor
        assertNotNull("anchor should be held (non-null) through a brief blink", heldAnchor)
        assertTrue("anchor should be held near $moved through a brief blink, was $heldAnchor", heldAnchor!!.distanceTo(moved) < 0.02f)

        // Subject reappears: tracking resumes normally.
        t += 100L * 1_000_000L
        last = smoother.update(placementResult(moved, movedTarget, t))
        assertNotNull("anchor should resume tracking once the subject reappears", last.displayAnchor)
    }

    // --- direction stability --------------------------------------------------------------------------

    @Test
    fun `direction flip within the same id is suppressed until it persists 600 ms`() {
        val smoother = CompositionSmoother()
        val right = recommendation("placement.thirds", Direction.RIGHT)
        val left = recommendation("placement.thirds", Direction.LEFT)

        // Establish RIGHT as the displayed direction.
        var shown = smoother.update(resultWith(right, 70f)).primaryRecommendation?.direction
        repeat(5) { shown = smoother.update(resultWith(right, 70f)).primaryRecommendation?.direction }
        assertEquals(Direction.RIGHT, shown)

        // The raw advice flips to LEFT (same id, the subject jittering on top of its target) — held at
        // RIGHT for under 600 ms (nominal 100 ms/update).
        repeat(5) { shown = smoother.update(resultWith(left, 70f)).primaryRecommendation?.direction } // 0.5 s
        assertEquals(Direction.RIGHT, shown)

        // Persisted past 600 ms: the flip is now accepted.
        repeat(2) { shown = smoother.update(resultWith(left, 70f)).primaryRecommendation?.direction } // 0.7 s total
        assertEquals(Direction.LEFT, shown)
    }

    // --- score cadence --------------------------------------------------------------------------------

    @Test
    fun `display score deadband is 3 points`() {
        val smoother = CompositionSmoother()
        repeat(10) { smoother.update(resultWith(null, 50f)) } // settle at 50

        // A 2-point drift, even held indefinitely, must stay inside the new deadband of 3.
        var shown = 50
        repeat(30) { shown = smoother.update(resultWith(null, 52f)).displayScore }
        assertEquals("a 2-point drift should stay inside the deadband of 3", 50, shown)

        // A drift that reaches 3 points from what's displayed is eventually shown.
        repeat(30) { shown = smoother.update(resultWith(null, 53f)).displayScore }
        assertEquals(53, shown)
    }
}
